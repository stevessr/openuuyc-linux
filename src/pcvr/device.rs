//! Owner-only shared device snapshots for the in-process Monado runtime.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use std::{
    fs::File,
    os::{
        fd::AsRawFd,
        unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt},
    },
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
const MAP: usize = 4096;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Calibration {
    pub eye_width: u32,
    pub eye_height: u32,
    pub refresh_hz: f32,
    pub views: [f32; 22],
    /// Actual STAGE origin expressed in headset LOCAL: xyz, xyzw. Never invented.
    pub stage: Option<[f32; 7]>,
}
impl Calibration {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            (64..=4096).contains(&self.eye_width)
                && self.eye_width % 2 == 0
                && (64..=4096).contains(&self.eye_height)
                && self.eye_height % 2 == 0
                && self.refresh_hz.is_finite()
                && (30.0..=165.0).contains(&self.refresh_hz),
            "无效眼部显示参数"
        );
        for eye in self.views.chunks_exact(11) {
            ensure!(eye.iter().all(|v| v.is_finite()), "非有限眼部参数");
            ensure!(eye[..3].iter().all(|v| v.abs() < 0.2), "眼位偏移超出范围");
            let norm: f32 = eye[3..7].iter().map(|v| v * v).sum();
            ensure!((0.98..=1.02).contains(&norm), "眼位旋转无效");
            ensure!(
                eye[7..].iter().all(|v| v.abs() < 1.56)
                    && eye[8] - eye[7] > 0.05
                    && eye[9] - eye[10] > 0.05,
                "眼部 FOV 无效"
            );
        }
        if let Some(stage) = self.stage {
            ensure!(
                stage.iter().all(|v| v.is_finite()) && stage[..3].iter().all(|v| v.abs() <= 1000.0),
                "无效地面空间变换"
            );
            let norm: f32 = stage[3..].iter().map(|v| v * v).sum();
            ensure!((0.98..=1.02).contains(&norm), "无效地面空间旋转");
        }
        let ipd = (0..3)
            .map(|i| (self.views[i] - self.views[11 + i]).powi(2))
            .sum::<f32>()
            .sqrt();
        ensure!((0.03..=0.1).contains(&ipd), "IPD 超出支持范围");
        Ok(())
    }
}
pub(crate) struct Broker {
    map: *mut u8,
    path: PathBuf,
    _file: File,
    _ownership: File,
    generation: u64,
    sequence: u64,
    calibration: Calibration,
    haptics: super::haptics::Endpoint,
}
// SAFETY: mapping/file descriptors are exclusively owned and all access is serialized by Session.state.
unsafe impl Send for Broker {}
fn private_dir(path: &Path) -> Result<()> {
    match std::fs::DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(e.into()),
    }
    let m = std::fs::symlink_metadata(path)?;
    ensure!(
        m.is_dir() && m.uid() == unsafe { libc::getuid() } && m.mode() & 0o077 == 0,
        "OpenXR IPC 目录不安全"
    );
    Ok(())
}
fn monotonic() -> i64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) } != 0 {
        return 0;
    };
    ts.tv_sec * 1_000_000_000 + ts.tv_nsec
}
impl Broker {
    pub fn create(root: &Path, calibration: Calibration) -> Result<Self> {
        calibration.validate()?;
        let base = root.join("openuuyc-pcvr");
        private_dir(&base)?;
        let dir = base.join("openxr");
        private_dir(&dir)?;
        let ownership = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(base.join("publisher.lock"))?;
        let m = ownership.metadata()?;
        ensure!(
            m.is_file() && m.uid() == unsafe { libc::getuid() } && m.mode() & 0o077 == 0,
            "发布锁不安全"
        );
        ensure!(
            unsafe { libc::flock(ownership.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
            "其他进程正在发布 VR 姿态"
        );
        let haptics = super::haptics::Endpoint::bind(&dir)?;
        let path = dir.join("device.shm");
        if path.exists() {
            use std::io::Read;
            let mut old = std::fs::OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
                .open(&path)?;
            let meta = old.metadata()?;
            let mut magic = [0; 4];
            old.read_exact(&mut magic)?;
            ensure!(
                meta.is_file()
                    && meta.uid() == unsafe { libc::getuid() }
                    && meta.mode() & 0o077 == 0
                    && meta.len() == MAP as u64
                    && &magic == b"OUXD",
                "遗留 IPC 文件无效，拒绝替换"
            );
            std::fs::remove_file(&path)?;
        }
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&path)?;
        file.set_len(MAP as u64)?;
        let mapping = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                MAP,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                file.as_raw_fd(),
                0,
            )
        };
        if mapping == libc::MAP_FAILED {
            let error = std::io::Error::last_os_error();
            let _ = std::fs::remove_file(&path);
            return Err(error.into());
        }

        let generation = (u64::from_le_bytes(uuid::Uuid::new_v4().as_bytes()[..8].try_into()?)
            & i64::MAX as u64)
            .max(1);
        let mut broker = Self {
            map: mapping.cast(),
            path,
            _file: file,
            _ownership: ownership,
            generation,
            sequence: 0,
            calibration,
            haptics,
        };
        broker.publish(&[0; 224], false)?;
        Ok(broker)
    }
    pub fn has_stage(&self) -> bool {
        self.calibration.stage.is_some()
    }
    pub fn haptics(&self) -> [Option<super::haptics::Pulse>; 2] {
        self.haptics.latest(self.generation)
    }
    pub fn publish(&mut self, tracking: &[u8], valid: bool) -> Result<()> {
        ensure!(matches!(tracking.len(), 224 | 400), "追踪帧大小错误");
        if valid {
            super::protocol::sequence(tracking)?;
        }
        let mut snapshot = [0u8; 568];
        snapshot[..4].copy_from_slice(b"OUXD");
        snapshot[4..8]
            .copy_from_slice(&(if tracking.len() == 400 { 2u32 } else { 1u32 }).to_le_bytes());
        snapshot[16..24].copy_from_slice(&self.generation.to_le_bytes());
        snapshot[24..32].copy_from_slice(&(if valid { monotonic() } else { 0 }).to_le_bytes());
        snapshot[32..36].copy_from_slice(&self.calibration.eye_width.to_le_bytes());
        snapshot[36..40].copy_from_slice(&self.calibration.eye_height.to_le_bytes());
        snapshot[40..44].copy_from_slice(&self.calibration.refresh_hz.to_le_bytes());
        for (i, v) in self.calibration.views.iter().enumerate() {
            snapshot[48 + i * 4..52 + i * 4].copy_from_slice(&v.to_le_bytes());
        }
        snapshot[136..136 + tracking.len()].copy_from_slice(tracking);
        if let Some(stage) = self.calibration.stage {
            snapshot[44..48].copy_from_slice(&1u32.to_le_bytes());
            for (i, v) in stage.iter().enumerate() {
                snapshot[536 + i * 4..540 + i * 4].copy_from_slice(&v.to_le_bytes());
            }
        }
        self.sequence = self.sequence.wrapping_add(2);
        // SAFETY: page alignment ensures AtomicU64 alignment; no Rust alias escapes.
        unsafe {
            let guard = &*(self.map.add(8).cast::<AtomicU64>());
            guard.store(self.sequence | 1, Ordering::Release);
            std::sync::atomic::fence(Ordering::SeqCst);
            for (i, v) in snapshot.iter().enumerate() {
                if !(8..16).contains(&i) {
                    std::ptr::write_volatile(self.map.add(i), *v);
                }
            }
            std::sync::atomic::fence(Ordering::Release);
            guard.store(self.sequence, Ordering::Release);
        }
        Ok(())
    }
}
impl Drop for Broker {
    fn drop(&mut self) {
        let _ = self.publish(&[0; 224], false);
        unsafe { libc::munmap(self.map.cast(), MAP) };
        if std::fs::symlink_metadata(&self.path)
            .ok()
            .zip(self._file.metadata().ok())
            .is_some_and(|(a, b)| a.ino() == b.ino() && a.dev() == b.dev())
        {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    pub fn calibration() -> Calibration {
        let mut views = [0.; 22];
        for (i, e) in views.chunks_mut(11).enumerate() {
            e[0] = if i == 0 { -0.032 } else { 0.032 };
            e[6] = 1.;
            e[7] = -0.8;
            e[8] = 0.8;
            e[9] = 0.8;
            e[10] = -0.8;
        }
        Calibration {
            eye_width: 1024,
            eye_height: 1024,
            refresh_hz: 90.,
            views,
            stage: None,
        }
    }
    #[test]
    fn v2_snapshot_keeps_real_controller_tail_and_clears_it_on_v1_fallback() {
        let root = std::env::temp_dir().join(format!("ouxd-v2-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let mut broker = Broker::create(&root, calibration()).unwrap();
        let mut frame = [0u8; 400];
        frame[..4].copy_from_slice(b"OUVR");
        frame[4] = 2;
        frame[8] = 1;
        frame[352] = 127;
        frame[360..364].copy_from_slice(&0.7f32.to_le_bytes());
        broker.publish(&frame, true).unwrap();
        let snapshot = std::fs::read(&broker.path).unwrap();
        assert_eq!(&snapshot[4..8], &2u32.to_le_bytes());
        assert_eq!(&snapshot[136..536], &frame);
        let mut old = [0u8; 224];
        old[..4].copy_from_slice(b"OUVR");
        old[4] = 1;
        old[8] = 2;
        broker.publish(&old, true).unwrap();
        let snapshot = std::fs::read(&broker.path).unwrap();
        assert_eq!(&snapshot[4..8], &1u32.to_le_bytes());
        assert!(snapshot[360..536].iter().all(|v| *v == 0));
        drop(broker);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn measured_stage_is_validated_and_published_without_fabrication() {
        let mut calibration = calibration();
        let mut old = serde_json::to_value(&calibration).unwrap();
        old.as_object_mut().unwrap().remove("stage");
        assert!(
            serde_json::from_value::<Calibration>(old)
                .unwrap()
                .stage
                .is_none()
        );
        calibration.stage = Some([0.0, -1.6, 0.0, 0.0, 0.0, 0.0, 1.0]);
        calibration.validate().unwrap();
        let root = std::env::temp_dir().join(format!("ouxd-stage-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let broker = Broker::create(&root, calibration.clone()).unwrap();
        let data = std::fs::read(&broker.path).unwrap();
        assert_eq!(&data[44..48], &1u32.to_le_bytes());
        assert_eq!(&data[540..544], &(-1.6f32).to_le_bytes());
        drop(broker);
        std::fs::remove_dir_all(root).unwrap();
        calibration.stage.as_mut().unwrap()[6] = 0.0;
        assert!(calibration.validate().is_err());
        calibration.stage.as_mut().unwrap()[6] = f32::NAN;
        assert!(calibration.validate().is_err());
    }
    #[test]
    fn checks_real_eye_geometry() {
        let mut c = calibration();
        c.validate().unwrap();
        c.views[11] = c.views[0];
        assert!(c.validate().is_err());
        c = calibration();
        c.refresh_hz = f32::NAN;
        assert!(c.validate().is_err());
    }
    #[test]
    fn rejects_an_existing_public_ipc_directory_without_changing_it() {
        use std::os::unix::fs::PermissionsExt;
        let root = std::env::temp_dir().join(format!("ouxd-permissions-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(root.join("openuuyc-pcvr")).unwrap();
        std::fs::set_permissions(
            root.join("openuuyc-pcvr"),
            std::fs::Permissions::from_mode(0o755),
        )
        .unwrap();
        assert!(Broker::create(&root, calibration()).is_err());
        assert_eq!(
            std::fs::metadata(root.join("openuuyc-pcvr"))
                .unwrap()
                .mode()
                & 0o777,
            0o755
        );
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn publishes_private_snapshot_and_releases_ownership() {
        let root = std::env::temp_dir().join(format!("ouxd-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let mut b = Broker::create(&root, calibration()).unwrap();
        assert!(Broker::create(&root, calibration()).is_err());
        let mut frame = [0; 224];
        frame[..4].copy_from_slice(b"OUVR");
        frame[4] = 1;
        frame[8] = 1;
        b.publish(&frame, true).unwrap();
        let data = std::fs::read(&b.path).unwrap();
        assert_eq!(&data[..4], b"OUXD");
        assert_eq!(&data[136..360], &frame);
        assert!(i64::from_le_bytes(data[24..32].try_into().unwrap()) > 0);
        assert_eq!(data[8] & 1, 0);
        drop(b);
        assert!(!root.join("openuuyc-pcvr/openxr/device.shm").exists());
        drop(Broker::create(&root, calibration()).unwrap());
        std::fs::remove_dir_all(root).unwrap();
    }
}
