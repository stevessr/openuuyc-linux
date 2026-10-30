//! Read the private latest-frame export from the real Monado compositor.
//! Pixels are CPU readback for now; never substitute desktop capture.
use anyhow::{Result, ensure};
use std::{fs::File, os::unix::fs::FileExt};
const HEADER: usize = 256;
const EYE_BYTES: usize = 1024 * 1024 * 4;
pub(crate) const MAP_BYTES: usize = HEADER + 2 * EYE_BYTES;
pub(crate) struct RawFrame {
    pub sequence: u64,
    pub predicted_time: i64,
    pub width: u16,
    pub height: u16,
    pub views: [f32; 22],
    pub bgra: Vec<u8>,
}
/// Both clocks are host CLOCK_MONOTONIC; PICO timestamps must not be compared here.
pub(crate) fn fresh_at(predicted_time: i64, now: i64) -> bool {
    predicted_time > 0
        && now > 0
        && predicted_time.saturating_sub(now) <= 100_000_000
        && now.saturating_sub(predicted_time) <= 250_000_000
}
pub(crate) fn fresh(predicted_time: i64) -> bool {
    let mut now = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut now) } != 0 {
        return false;
    }
    fresh_at(
        predicted_time,
        now.tv_sec
            .saturating_mul(1_000_000_000)
            .saturating_add(now.tv_nsec),
    )
}
pub(crate) fn read_latest(file: &File, previous: u64) -> Result<Option<RawFrame>> {
    let mut header = [0u8; HEADER];
    file.read_exact_at(&mut header, 0)?;
    let u32_at = |offset| u32::from_le_bytes(header[offset..offset + 4].try_into().unwrap());
    let u64_at = |offset| u64::from_le_bytes(header[offset..offset + 8].try_into().unwrap());
    let guard = u64_at(0);
    if guard == 0 || guard & 1 != 0 {
        return Ok(None);
    }
    ensure!(
        &header[8..12] == b"OUST" && u32_at(12) == 2,
        "invalid compositor export version"
    );
    ensure!(
        u32_at(44) == 0,
        "external compositor coordinate space requires alignment before PICO projection"
    );
    let sequence = u64_at(16);
    if sequence <= previous {
        return Ok(None);
    }
    let timestamp = u64_at(24);
    let w = u32_at(32) as usize;
    let h = u32_at(36) as usize;
    ensure!(
        (64..=1024).contains(&w)
            && w % 2 == 0
            && (64..=1024).contains(&h)
            && h % 2 == 0
            && u32_at(40) == 1
            && timestamp > 0
            && timestamp <= i64::MAX as u64,
        "invalid compositor frame geometry/time"
    );
    let mut views = [0f32; 22];
    for (i, v) in views.iter_mut().enumerate() {
        *v = f32::from_le_bytes(header[48 + i * 4..52 + i * 4].try_into().unwrap());
    }
    for eye in views.chunks_exact(11) {
        ensure!(eye.iter().all(|v| v.is_finite()), "invalid compositor view");
        let norm: f32 = eye[3..7].iter().map(|v| v * v).sum();
        ensure!(
            (0.98..=1.02).contains(&norm)
                && eye[..3].iter().all(|v| v.abs() <= 1000.)
                && eye[7..].iter().all(|v| v.abs() < 1.56)
                && eye[8] - eye[7] > 0.05
                && eye[9] - eye[10] > 0.05,
            "invalid compositor pose/FOV"
        );
    }
    let mut bgra = vec![0; w * h * 8];
    let mut row = vec![0; w * 4];
    for eye in 0..2 {
        for y in 0..h {
            file.read_exact_at(&mut row, (HEADER + eye * EYE_BYTES + y * w * 4) as u64)?;
            let output = &mut bgra[y * w * 8 + eye * w * 4..y * w * 8 + (eye + 1) * w * 4];
            for (src, dst) in row.chunks_exact(4).zip(output.chunks_exact_mut(4)) {
                dst.copy_from_slice(&[src[2], src[1], src[0], 255]);
            }
        }
    }
    let mut after = [0; 8];
    file.read_exact_at(&mut after, 0)?;
    if u64::from_le_bytes(after) != guard {
        return Ok(None);
    }
    Ok(Some(RawFrame {
        sequence,
        predicted_time: timestamp as i64,
        width: (w * 2) as u16,
        height: h as u16,
        views,
        bgra,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Seek, SeekFrom, Write};
    #[test]
    fn frame_clock_rejects_cached_frames_and_unreasonable_future_times() {
        let now = 1_000_000_000;
        assert!(fresh_at(now, now));
        assert!(fresh_at(now + 20_000_000, now));
        assert!(!fresh_at(now - 250_000_001, now));
        assert!(!fresh_at(now + 100_000_001, now));
        assert!(!fresh_at(0, now));
        assert!(!fresh_at(i64::MAX, now));
    }
    fn fixture() -> File {
        use std::os::unix::fs::OpenOptionsExt;
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "oust-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        let mut file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
            .unwrap();
        std::fs::remove_file(path).unwrap();
        file.set_len(MAP_BYTES as u64).unwrap();
        let mut header = [0u8; HEADER];
        header[0..8].copy_from_slice(&2u64.to_le_bytes());
        header[8..12].copy_from_slice(b"OUST");
        header[12..16].copy_from_slice(&2u32.to_le_bytes());
        header[16..24].copy_from_slice(&1u64.to_le_bytes());
        header[24..32].copy_from_slice(&99u64.to_le_bytes());
        header[32..36].copy_from_slice(&64u32.to_le_bytes());
        header[36..40].copy_from_slice(&64u32.to_le_bytes());
        header[40..44].copy_from_slice(&1u32.to_le_bytes());
        for eye in 0..2 {
            for (i, v) in [0f32, 0., 0., 0., 0., 0., 1., -0.8, 0.8, 0.8, -0.8]
                .iter()
                .enumerate()
            {
                let p = 48 + (eye * 11 + i) * 4;
                header[p..p + 4].copy_from_slice(&v.to_le_bytes());
            }
        }
        file.write_all(&header).unwrap();
        file.seek(SeekFrom::Start(HEADER as u64)).unwrap();
        file.write_all(&[1, 2, 3, 4]).unwrap();
        file.seek(SeekFrom::Start((HEADER + EYE_BYTES) as u64))
            .unwrap();
        file.write_all(&[5, 6, 7, 8]).unwrap();
        file
    }
    #[test]
    fn reads_both_eyes_and_reorders_pixels() {
        let file = fixture();
        let frame = read_latest(&file, 0).unwrap().unwrap();
        assert_eq!(
            (
                frame.width,
                frame.height,
                frame.sequence,
                frame.predicted_time
            ),
            (128, 64, 1, 99)
        );
        assert_eq!(&frame.bgra[..4], &[3, 2, 1, 255]);
        assert_eq!(&frame.bgra[256..260], &[7, 6, 5, 255]);
        assert_eq!(frame.views[6], 1.);
        assert!(read_latest(&file, 1).unwrap().is_none());
    }
    #[test]
    fn rejects_unaligned_external_compositor_space() {
        let file = fixture();
        file.write_all_at(&1u32.to_le_bytes(), 44).unwrap();
        assert!(read_latest(&file, 0).is_err());
    }
    #[test]
    fn skips_in_progress_and_rejects_invalid_headers() {
        let file = fixture();
        file.write_all_at(&3u64.to_le_bytes(), 0).unwrap();
        assert!(read_latest(&file, 0).unwrap().is_none());
        file.write_all_at(&4u64.to_le_bytes(), 0).unwrap();
        file.write_all_at(&2048u32.to_le_bytes(), 32).unwrap();
        assert!(read_latest(&file, 0).is_err());
    }
}

/// Owned by an authorized device session. The parent is the broker's private
/// directory; inode checking prevents cleanup from deleting a replacement.
pub(crate) struct Export {
    pub file: File,
    path: std::path::PathBuf,
}
impl Export {
    pub(crate) fn create(directory: &std::path::Path) -> Result<Self> {
        use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
        let meta = std::fs::symlink_metadata(directory)?;
        ensure!(
            meta.is_dir() && meta.uid() == unsafe { libc::getuid() } && meta.mode() & 0o077 == 0,
            "unsafe stereo export directory"
        );
        let path = directory.join("stereo.shm");
        if let Ok(meta) = std::fs::symlink_metadata(&path) {
            ensure!(
                meta.is_file()
                    && meta.uid() == unsafe { libc::getuid() }
                    && meta.mode() & 0o077 == 0,
                "unsafe stale stereo export"
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
        file.set_len(MAP_BYTES as u64)?;
        Ok(Self { file, path })
    }
}
impl Drop for Export {
    fn drop(&mut self) {
        use std::os::unix::fs::MetadataExt;
        if std::fs::symlink_metadata(&self.path)
            .ok()
            .zip(self.file.metadata().ok())
            .is_some_and(|(a, b)| a.ino() == b.ino() && a.dev() == b.dev())
        {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

#[cfg(test)]
mod lifecycle_tests {
    use super::*;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt};
    #[test]
    fn authorized_export_is_private_and_removed_on_drop() {
        let path = std::env::temp_dir().join(format!("oust-lifecycle-{}", std::process::id()));
        std::fs::DirBuilder::new()
            .mode(0o700)
            .create(&path)
            .unwrap();
        {
            let export = Export::create(&path).unwrap();
            assert_eq!(export.file.metadata().unwrap().len(), MAP_BYTES as u64);
            assert_eq!(export.file.metadata().unwrap().mode() & 0o077, 0);
            assert!(read_latest(&export.file, 0).unwrap().is_none());
        }
        assert!(!path.join("stereo.shm").exists());
        std::fs::remove_dir(path).unwrap();
    }
}
