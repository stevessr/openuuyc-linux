//! Encode the compositor's actual SBS image while preserving its rendering pose.
//! CPU baseline, not a substitute desktop capture or a synthetic frame source.
use super::{capture::RawFrame, stereo::Frame};
use anyhow::{Result, ensure};
use openuuyc_codec::{
    Codec, Format,
    encoder::{Config, Encoder as Core},
};
use std::sync::{Arc, atomic::AtomicBool};
pub(crate) struct Encoder {
    core: Option<Core>,
    size: (u16, u16),
    generation: u32,
    previous: u64,
}
impl Encoder {
    pub(crate) fn new(generation: u32) -> Result<Self> {
        ensure!(
            generation > 0 && generation <= i32::MAX as u32,
            "invalid stereo generation"
        );
        Ok(Self {
            core: None,
            size: (0, 0),
            generation,
            previous: 0,
        })
    }
    pub(crate) fn encode(
        &mut self,
        raw: RawFrame,
        keyframe: bool,
        cancel: &Arc<AtomicBool>,
    ) -> Result<Option<Frame>> {
        ensure!(
            raw.sequence > self.previous,
            "out-of-order compositor frame"
        );
        ensure!(
            raw.bgra.len() == usize::from(raw.width) * usize::from(raw.height) * 4,
            "invalid compositor image size"
        );
        let size = (raw.width, raw.height);
        if self.core.is_some() {
            ensure!(
                size == self.size,
                "stereo dimensions changed: renegotiate a new generation"
            );
        }
        if self.core.is_none() {
            self.core = Some(Core::new(Config {
                width: raw.width.into(),
                height: raw.height.into(),
                format: Format {
                    codec: Codec::H264,
                    chroma: 1,
                    depth: 8,
                },
                fps: 30,
                bitrate: 20_000_000,
            })?);
            self.size = size;
        }
        let core = self.core.as_mut().unwrap();
        core.prepare(&raw.bgra, usize::from(raw.width) * 4, cancel)?;
        let packet = core.encode(raw.predicted_time / 100, keyframe, cancel)?;
        self.previous = raw.sequence;
        let Some(packet) = packet else {
            return Ok(None);
        };
        let frame = Frame {
            generation: self.generation,
            sequence: raw.sequence,
            predicted_time: raw.predicted_time,
            width: raw.width,
            height: raw.height,
            codec: 1,
            keyframe: packet.keyframe,
            views: raw.views,
            annex_b: packet.data,
        };
        frame.validate()?;
        Ok(Some(frame))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn real_encoder_preserves_stereo_metadata() {
        // Synthetic pixels are solely a codec unit fixture, never a runtime source.
        let mut views = [0.; 22];
        for eye in views.chunks_mut(11) {
            eye[6] = 1.;
            eye[7] = -0.8;
            eye[8] = 0.8;
            eye[9] = 0.8;
            eye[10] = -0.8;
        }
        let mut pixels = vec![0; 128 * 64 * 4];
        for p in pixels.chunks_mut(4) {
            p.copy_from_slice(&[32, 64, 128, 255]);
        }
        let raw = RawFrame {
            sequence: 1,
            predicted_time: 123_456_700,
            width: 128,
            height: 64,
            views,
            bgra: pixels,
        };
        let mut encoder = Encoder::new(7).unwrap();
        let frame = encoder
            .encode(raw, true, &Arc::new(AtomicBool::new(false)))
            .unwrap()
            .unwrap();
        assert!(frame.keyframe && !frame.annex_b.is_empty());
        assert_eq!(
            (frame.generation, frame.predicted_time, frame.views),
            (7, 123_456_700, views)
        );
        assert!(!frame.fragments().unwrap().is_empty());
    }
}
