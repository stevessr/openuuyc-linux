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
    sequence: u64,
    fps: u32,
    bitrate: u32,
}
impl Encoder {
    pub(crate) fn new(generation: u32, fps: u32) -> Result<Self> {
        ensure!(
            generation > 0 && generation <= i32::MAX as u32,
            "invalid stereo generation"
        );
        ensure!(
            (30..=165).contains(&fps),
            "invalid stereo encoder frame rate"
        );
        Ok(Self {
            core: None,
            size: (0, 0),
            generation,
            previous: 0,
            sequence: 0,
            fps,
            bitrate: super::bitrate::INITIAL_BPS,
        })
    }
    pub(crate) fn set_bitrate(&mut self, bitrate: u32) -> Result<()> {
        ensure!(
            (2_000_000..=super::bitrate::INITIAL_BPS).contains(&bitrate),
            "PCVR encoder bitrate outside adaptive bounds"
        );
        if bitrate == self.bitrate {
            return Ok(());
        }
        if let Some(core) = self.core.as_mut() {
            core.configure(self.fps, bitrate)?;
        }
        self.bitrate = bitrate;
        Ok(())
    }

    pub(crate) fn encode(
        &mut self,
        raw: RawFrame,
        keyframe: bool,
        gaze: Option<super::foveation::Gaze>,
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
                fps: self.fps,
                bitrate: self.bitrate,
            })?);
            self.size = size;
        }
        let core = self.core.as_mut().unwrap();
        super::foveation::fill_qp_offsets(
            raw.width,
            raw.height,
            &raw.views,
            gaze,
            core.macroblock_qp_offsets_mut(),
        );
        core.prepare(&raw.bgra, usize::from(raw.width) * 4, cancel)?;
        let packet = core.encode(raw.predicted_time / 100, keyframe, cancel)?;
        self.previous = raw.sequence;
        let Some(packet) = packet else {
            return Ok(None);
        };
        // Capture may legitimately skip compositor publications. Only lost
        // encoded access units should appear as transport gaps to the decoder.
        self.sequence = self
            .sequence
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or_else(|| anyhow::anyhow!("stereo transport sequence exhausted"))?;
        let frame = Frame {
            generation: self.generation,
            sequence: self.sequence,
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
            origin: 0,
            head: None,
        };
        let mut encoder = Encoder::new(7, 72).unwrap();
        let frame = encoder
            .encode(raw, true, None, &Arc::new(AtomicBool::new(false)))
            .unwrap()
            .unwrap();
        assert_eq!(encoder.fps, 72);
        assert!(Encoder::new(7, 29).is_err());
        assert!(Encoder::new(7, 166).is_err());
        assert!(frame.keyframe && !frame.annex_b.is_empty());
        assert_eq!(
            (frame.generation, frame.predicted_time, frame.views),
            (7, 123_456_700, views)
        );
        assert!(!frame.fragments().unwrap().is_empty());
        assert_eq!(frame.sequence, 1);
        for (source, wire) in [(4, 2), (9, 3)] {
            let raw = RawFrame {
                sequence: source,
                predicted_time: 123_456_700 + source as i64 * 1_000_000,
                width: 128,
                height: 64,
                views,
                bgra: vec![64; 128 * 64 * 4],
                origin: 0,
                head: None,
            };
            let next = encoder
                .encode(raw, false, None, &Arc::new(AtomicBool::new(false)))
                .unwrap()
                .unwrap();
            assert_eq!(
                next.sequence, wire,
                "capture skips must not look like lost encoded frames"
            );
        }
    }
}
#[cfg(test)]
mod gaze_and_bitrate_tests {
    use super::*;

    fn fixture(sequence: u64, width: u16, height: u16) -> RawFrame {
        let mut views = [0.; 22];
        for (eye, view) in views.chunks_exact_mut(11).enumerate() {
            view[0] = if eye == 0 { -0.032 } else { 0.032 };
            view[6] = 1.;
            view[7] = -0.8;
            view[8] = 0.8;
            view[9] = 0.8;
            view[10] = -0.8;
        }
        let width = usize::from(width);
        let height = usize::from(height);
        let mut bgra = vec![0; width * height * 4];
        for y in 0..height {
            for x in 0..width {
                // Pseudo random texture so that final slope/DC correctness is
                // visible in H264 output.
                let shade = ((x * 31 + y * 17 + x * y) % 256) as u8;
                let pixel = &mut bgra[(y * width + x) * 4..][..4];
                pixel.copy_from_slice(&[shade, shade.wrapping_add(31), 255 - shade, 255]);
            }
        }
        RawFrame {
            sequence,
            predicted_time: 123_456_700 + sequence as i64 * 1_000_000,
            width: width as u16,
            height: height as u16,
            views,
            bgra,
            origin: 0,
            head: None,
        }
    }

    #[test]
    fn gaze_changes_the_real_pcvr_sbs_h264_access_unit() {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut uniform = Encoder::new(9, 72).unwrap();
        let baseline = uniform
            .encode(fixture(1, 256, 128), true, None, &cancel)
            .unwrap()
            .unwrap();
        let mut foveated = Encoder::new(9, 72).unwrap();
        let gaze = super::super::foveation::Gaze {
            pose: [0., 0., -0.03, 0., 0., 0., 1.],
        };
        let roi = foveated
            .encode(fixture(1, 256, 128), true, Some(gaze), &cancel)
            .unwrap()
            .unwrap();

        assert_ne!(baseline.annex_b, roi.annex_b);
    }

    #[test]
    fn bitrate_can_change_after_real_encoder_initialization() {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut encoder = Encoder::new(10, 72).unwrap();
        let first = encoder
            .encode(fixture(1, 128, 64), true, None, &cancel)
            .unwrap()
            .unwrap();
        assert!(first.keyframe);
        encoder.set_bitrate(10_000_000).unwrap();
        assert_eq!(encoder.bitrate, 10_000_000);
        assert!(encoder.set_bitrate(1_000_000).is_err());
        let next = encoder
            .encode(fixture(2, 128, 64), false, None, &cancel)
            .unwrap()
            .unwrap();
        assert_eq!(next.sequence, first.sequence + 1);
        assert!(!next.annex_b.is_empty());
    }
}
