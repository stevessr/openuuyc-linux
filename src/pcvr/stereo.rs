//! Bounded stereo access units. Both eyes share one side-by-side coded image.
//! Pose/FOV are the rendering views, not the receiver's current head pose.
use anyhow::{Result, ensure};
pub const CHANNEL: &str = "OPENUUYC_VR_VIDEO_V1";
pub const HEADER: usize = 136;
pub const MAX_FRAME: usize = 4 * 1024 * 1024;
pub const CHUNK: usize = 48_000;
#[derive(Clone, Debug)]
pub struct Frame {
    pub generation: u32,
    pub sequence: u64,
    pub predicted_time: i64,
    pub width: u16,
    pub height: u16,
    pub codec: u8,
    pub keyframe: bool,
    /// For each eye: position xyz, orientation xyzw, FOV left/right/up/down.
    pub views: [f32; 22],
    pub annex_b: Vec<u8>,
}
impl Frame {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.generation > 0
                && self.generation <= i32::MAX as u32
                && self.sequence > 0
                && self.sequence <= i64::MAX as u64
                && self.predicted_time > 0,
            "invalid stereo generation/time"
        );
        ensure!(
            (128..=8192).contains(&self.width)
                && self.width % 4 == 0
                && (64..=4096).contains(&self.height)
                && self.height % 2 == 0,
            "invalid stereo dimensions"
        );
        ensure!((1..=2).contains(&self.codec), "unsupported stereo codec");
        ensure!(
            !self.annex_b.is_empty() && self.annex_b.len() <= MAX_FRAME,
            "invalid stereo access unit size"
        );
        for eye in self.views.chunks_exact(11) {
            ensure!(eye.iter().all(|v| v.is_finite()), "nonfinite stereo view");
            ensure!(
                eye[..3].iter().all(|v| v.abs() <= 1000.),
                "stereo position out of range"
            );
            let norm: f32 = eye[3..7].iter().map(|v| v * v).sum();
            ensure!((0.98..=1.02).contains(&norm), "invalid stereo orientation");
            ensure!(
                eye[7..11].iter().all(|v| v.abs() < 1.56)
                    && eye[8] - eye[7] > 0.05
                    && eye[9] - eye[10] > 0.05,
                "invalid stereo FOV"
            );
        }
        Ok(())
    }
    pub fn fragments(&self) -> Result<Vec<Vec<u8>>> {
        self.validate()?;
        let mut result = Vec::new();
        for (index, chunk) in self.annex_b.chunks(CHUNK).enumerate() {
            let mut packet = Vec::with_capacity(HEADER + chunk.len());
            packet.extend_from_slice(b"OUVS");
            packet.extend_from_slice(&1u16.to_le_bytes());
            packet.extend_from_slice(&u16::from(self.keyframe).to_le_bytes());
            packet.extend_from_slice(&self.generation.to_le_bytes());
            packet.extend_from_slice(&self.sequence.to_le_bytes());
            packet.extend_from_slice(&self.predicted_time.to_le_bytes());
            packet.extend_from_slice(&self.width.to_le_bytes());
            packet.extend_from_slice(&self.height.to_le_bytes());
            packet.extend_from_slice(&[self.codec, 0, 0, 0]);
            for value in [self.annex_b.len(), index * CHUNK, chunk.len()] {
                packet.extend_from_slice(&(value as u32).to_le_bytes());
            }
            for value in self.views {
                packet.extend_from_slice(&value.to_le_bytes());
            }
            packet.extend_from_slice(chunk);
            result.push(packet);
        }
        Ok(result)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn frame() -> Frame {
        let mut views = [0.; 22];
        for eye in views.chunks_mut(11) {
            eye[6] = 1.;
            eye[7] = -0.8;
            eye[8] = 0.8;
            eye[9] = 0.8;
            eye[10] = -0.8;
        }
        Frame {
            generation: 1,
            sequence: 7,
            predicted_time: 100,
            width: 2048,
            height: 1024,
            codec: 1,
            keyframe: true,
            views,
            annex_b: vec![1; CHUNK + 3],
        }
    }
    #[test]
    fn bounded_fragments_carry_both_views() {
        let f = frame();
        let packets = f.fragments().unwrap();
        assert_eq!(packets.len(), 2);
        assert_eq!(packets[0].len(), HEADER + CHUNK);
        assert_eq!(&packets[0][..4], b"OUVS");
        assert_eq!(
            u32::from_le_bytes(packets[1][40..44].try_into().unwrap()),
            CHUNK as u32
        );
        assert_eq!(&packets[1][HEADER..], &[1, 1, 1]);
    }
    #[test]
    fn rejects_invalid_views_dimensions_and_payloads() {
        let mut f = frame();
        f.views[0] = f32::NAN;
        assert!(f.validate().is_err());
        f = frame();
        f.width = 2049;
        assert!(f.validate().is_err());
        f = frame();
        f.views[6] = 0.;
        assert!(f.validate().is_err());
        f = frame();
        f.annex_b = vec![0; MAX_FRAME + 1];
        assert!(f.validate().is_err());
    }
}
