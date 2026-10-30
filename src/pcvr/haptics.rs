//! Bounded, generation-bound local runtime -> authenticated headset feedback.
use anyhow::{Result, ensure};
use std::{
    os::unix::{
        fs::{FileTypeExt, MetadataExt, PermissionsExt},
        net::UnixDatagram,
    },
    path::{Path, PathBuf},
};
#[derive(Clone, Copy, Debug)]
pub(crate) struct Pulse {
    pub hand: u32,
    pub amplitude: f32,
    pub frequency: f32,
    pub duration_ms: u32,
}
fn decode(bytes: &[u8], generation: u64, now: u64) -> Result<Pulse> {
    ensure!(
        bytes.len() == 40 && &bytes[..4] == b"OUHP",
        "invalid haptic packet"
    );
    let u32_at = |i| u32::from_le_bytes(bytes[i..i + 4].try_into().unwrap());
    let u64_at = |i| u64::from_le_bytes(bytes[i..i + 8].try_into().unwrap());
    ensure!(
        u32_at(4) == 1 && generation > 0 && u64_at(8) == generation,
        "stale haptic generation"
    );
    let timestamp = u64_at(16);
    ensure!(
        timestamp > 0 && timestamp <= now && now - timestamp <= 250_000_000,
        "expired haptic packet"
    );
    let pulse = Pulse {
        hand: u32_at(24),
        amplitude: f32::from_bits(u32_at(28)),
        frequency: f32::from_bits(u32_at(32)),
        duration_ms: u32_at(36),
    };
    ensure!(
        pulse.hand < 2
            && pulse.amplitude.is_finite()
            && (0.0..=1.0).contains(&pulse.amplitude)
            && pulse.frequency.is_finite()
            && (0.0..=1000.0).contains(&pulse.frequency)
            && pulse.duration_ms <= 1000,
        "invalid haptic parameters"
    );
    Ok(pulse)
}
pub(crate) struct Endpoint {
    socket: UnixDatagram,
    path: PathBuf,
    inode: u64,
    device: u64,
}
impl Endpoint {
    pub fn bind(directory: &Path) -> Result<Self> {
        let path = directory.join("haptics.sock");
        match std::fs::symlink_metadata(&path) {
            Ok(m) => {
                ensure!(
                    m.file_type().is_socket()
                        && m.uid() == unsafe { libc::getuid() }
                        && m.mode() & 0o077 == 0,
                    "unsafe stale haptic socket"
                );
                std::fs::remove_file(&path)?;
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
        let socket = UnixDatagram::bind(&path)?;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        socket.set_nonblocking(true)?;
        let m = std::fs::symlink_metadata(&path)?;
        Ok(Self {
            socket,
            path,
            inode: m.ino(),
            device: m.dev(),
        })
    }
    pub fn latest(&self, generation: u64) -> [Option<Pulse>; 2] {
        let mut pulses = [None, None];
        let mut buffer = [0u8; 41];
        for _ in 0..32 {
            match self.socket.recv(&mut buffer) {
                Ok(n) => {
                    let mut time = libc::timespec {
                        tv_sec: 0,
                        tv_nsec: 0,
                    };
                    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) } != 0 {
                        break;
                    }
                    let now = (time.tv_sec as u64)
                        .saturating_mul(1_000_000_000)
                        .saturating_add(time.tv_nsec as u64);
                    if let Ok(pulse) = decode(&buffer[..n], generation, now) {
                        pulses[pulse.hand as usize] = Some(pulse);
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }
        pulses
    }
}
impl Drop for Endpoint {
    fn drop(&mut self) {
        if std::fs::symlink_metadata(&self.path)
            .is_ok_and(|m| m.ino() == self.inode && m.dev() == self.device)
        {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn packet() -> [u8; 40] {
        let mut b = [0; 40];
        b[..4].copy_from_slice(b"OUHP");
        b[4] = 1;
        b[8] = 7;
        b[16..24].copy_from_slice(&1_000_000_000u64.to_le_bytes());
        b[28..32].copy_from_slice(&0.5f32.to_le_bytes());
        b[36..40].copy_from_slice(&100u32.to_le_bytes());
        b
    }
    #[test]
    fn bounds_and_generation_are_required() {
        let mut b = packet();
        assert!(decode(&b, 7, 1_000_000_001).is_ok());
        assert!(decode(&b, 8, 1_000_000_001).is_err());
        assert!(decode(&b, 7, 1_250_000_001).is_err());
        assert!(decode(&b, 7, 999_999_999).is_err());
        assert!(decode(&b[..39], 7, 1_000_000_001).is_err());
        b[28..32].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(decode(&b, 7, 1_000_000_001).is_err());
    }
    #[test]
    fn receives_latest_per_hand_and_discards_wrong_generations() {
        let root = std::env::temp_dir().join(format!("ouhp-wire-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let endpoint = Endpoint::bind(&root).unwrap();
        let sender = UnixDatagram::unbound().unwrap();
        let mut b = packet();
        let mut now = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut now) };
        b[16..24].copy_from_slice(
            &((now.tv_sec as u64) * 1_000_000_000 + now.tv_nsec as u64).to_le_bytes(),
        );
        sender.send_to(&b, &endpoint.path).unwrap();
        b[24] = 1;
        sender.send_to(&b, &endpoint.path).unwrap();
        b[8] = 8;
        sender.send_to(&b, &endpoint.path).unwrap();
        let pulses = endpoint.latest(7);
        assert_eq!(pulses[0].unwrap().hand, 0);
        assert_eq!(pulses[1].unwrap().hand, 1);
        assert!(endpoint.latest(7).iter().all(Option::is_none));
        drop(endpoint);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn socket_is_private_and_cleanup_preserves_replacement() {
        let root = std::env::temp_dir().join(format!("ouhp-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let endpoint = Endpoint::bind(&root).unwrap();
        assert_eq!(
            std::fs::metadata(&endpoint.path).unwrap().mode() & 0o777,
            0o600
        );
        std::fs::remove_file(&endpoint.path).unwrap();
        std::fs::write(&endpoint.path, b"replacement").unwrap();
        drop(endpoint);
        assert!(root.join("haptics.sock").exists());
        assert!(Endpoint::bind(&root).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
