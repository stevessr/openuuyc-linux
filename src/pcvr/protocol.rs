//! Fixed-size, bounded binary tracking frames shared with drivers/pcvr/protocol.h.
use anyhow::{Result, ensure};
pub(crate) const FRAME_BYTES: usize = 224;
pub(crate) const FRAME_V3_BYTES: usize = 444;
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct GazeSample {
    pub(crate) pose: [f32; 7],
}
pub(crate) const FRAME_V2_BYTES: usize = 400;
pub(crate) fn sequence(frame: &[u8]) -> Result<u64> {
    ensure!(
        matches!(frame.len(), FRAME_BYTES | FRAME_V2_BYTES | FRAME_V3_BYTES)
            && &frame[..4] == b"OUVR",
        "invalid tracking frame"
    );
    let version = u32::from_le_bytes(frame[4..8].try_into()?);
    ensure!(
        (version == 1 && frame.len() == FRAME_BYTES)
            || (version == 2 && frame.len() == FRAME_V2_BYTES)
            || (version == 3 && frame.len() == FRAME_V3_BYTES),
        "unsupported tracking version/size"
    );
    let devices_end = if version >= 2 { 352 } else { 224 };
    let sequence = u64::from_le_bytes(frame[8..16].try_into()?);
    ensure!(sequence > 0, "zero sequence");
    for device in frame[32..devices_end].chunks_exact(64) {
        let flags = u32::from_le_bytes(device[..4].try_into()?);
        let buttons = u32::from_le_bytes(device[4..8].try_into()?);
        ensure!(
            flags <= if version >= 2 { 63 } else { 15 } && buttons <= 3 && device[60..64] == [0; 4],
            "invalid tracking flags"
        );
        let mut values = [0f32; 13];
        for (i, b) in device[8..60].chunks_exact(4).enumerate() {
            values[i] = f32::from_le_bytes(b.try_into()?);
            ensure!(values[i].is_finite(), "nonfinite pose");
            if !(3..7).contains(&i) {
                ensure!(values[i].abs() <= 1000., "pose out of range");
            }
        }
        if flags & 1 != 0 {
            let norm: f32 = values[3..7].iter().map(|v| v * v).sum();
            ensure!((0.98..=1.02).contains(&norm), "invalid quaternion");
        }
    }
    if version >= 2 {
        for control in frame[352..FRAME_V2_BYTES].chunks_exact(24) {
            let flags = u32::from_le_bytes(control[..4].try_into()?);
            let buttons = u32::from_le_bytes(control[4..8].try_into()?);
            ensure!(flags <= 127 && buttons <= 15, "invalid controller flags");
            for (i, bytes) in control[8..].chunks_exact(4).enumerate() {
                let value = f32::from_le_bytes(bytes.try_into()?);
                ensure!(
                    value.is_finite()
                        && if i < 2 {
                            (0.0..=1.0).contains(&value)
                        } else {
                            (-1.0..=1.0).contains(&value)
                        },
                    "invalid controller axis"
                );
            }
        }
    }
    if version == 3 {
        let flags = u32::from_le_bytes(frame[400..404].try_into()?);
        let reserved = u32::from_le_bytes(frame[404..408].try_into()?);
        let sample_time = u64::from_le_bytes(frame[408..416].try_into()?);
        ensure!(flags <= 1 && reserved == 0, "invalid gaze flags");
        let mut pose = [0f32; 7];
        for (i, bytes) in frame[416..].chunks_exact(4).enumerate() {
            pose[i] = f32::from_le_bytes(bytes.try_into()?);
            ensure!(pose[i].is_finite(), "nonfinite gaze pose");
        }
        if flags == 1 {
            ensure!(sample_time > 0, "gaze sample time unavailable");
            ensure!(
                pose[..3].iter().all(|v| v.abs() <= 1000.),
                "gaze origin out of range"
            );
            let norm = pose[3..].iter().map(|v| v * v).sum::<f32>();
            ensure!((0.98..=1.02).contains(&norm), "invalid gaze orientation");
            let xr_time = u64::from_le_bytes(frame[16..24].try_into()?);
            ensure!(xr_time > 0, "gaze frame time unavailable");
            ensure!(
                xr_time.abs_diff(sample_time) <= 100_000_000,
                "stale gaze sample"
            );
        } else {
            ensure!(
                sample_time == 0 && pose.iter().all(|v| *v == 0.),
                "invalid empty gaze sample"
            );
        }
    }
    Ok(sequence)
}
/// Call only after sequence() validates the complete packet.
pub(crate) fn gaze(frame: &[u8]) -> Option<GazeSample> {
    if frame.len() != FRAME_V3_BYTES
        || u32::from_le_bytes(frame[4..8].try_into().ok()?) != 3
        || u32::from_le_bytes(frame[400..404].try_into().ok()?) != 1
    {
        return None;
    }
    let mut pose = [0.; 7];
    for (value, bytes) in pose.iter_mut().zip(frame[416..].chunks_exact(4)) {
        *value = f32::from_le_bytes(bytes.try_into().ok()?);
    }
    Some(GazeSample { pose })
}
/// Call only after sequence() validates the full packet. Lost head tracking is not a pose.
pub(crate) fn head_valid(frame: &[u8]) -> bool {
    frame
        .get(32..36)
        .is_some_and(|flags| u32::from_le_bytes(flags.try_into().unwrap()) & 3 == 3)
}
/// The head pose in the headset's LOCAL space (xyz, xyzw): its position and
/// orientation together, never a partially tracked frame. Used to register the
/// external compositor's coordinate space; a defaulted pose must not align.
pub(crate) fn head_pose(frame: &[u8]) -> Option<[f32; 7]> {
    let device = frame.get(32..96)?;
    if u32::from_le_bytes(device[..4].try_into().ok()?) & 3 != 3 {
        return None;
    }
    let mut pose = [0f32; 7];
    for (i, bytes) in device[8..36].chunks_exact(4).enumerate() {
        pose[i] = f32::from_le_bytes(bytes.try_into().ok()?);
        if !pose[i].is_finite() {
            return None;
        }
    }
    if pose[..3].iter().any(|v| v.abs() > 1000.) {
        return None;
    }
    let norm: f32 = pose[3..].iter().map(|v| v * v).sum();
    if !(0.98..=1.02).contains(&norm) {
        return None;
    }
    Some(pose)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn frame() -> Vec<u8> {
        let mut f = vec![0; 224];
        f[..4].copy_from_slice(b"OUVR");
        f[4] = 1;
        f[8] = 1;
        f
    }
    fn gaze_frame() -> Vec<u8> {
        let mut frame = frame();
        frame.resize(FRAME_V3_BYTES, 0);
        frame[4] = 3;
        frame[16..24].copy_from_slice(&100_000_000u64.to_le_bytes());
        frame[400..404].copy_from_slice(&1u32.to_le_bytes());
        frame[408..416].copy_from_slice(&95_000_000u64.to_le_bytes());
        frame[424..428].copy_from_slice(&(-0.03f32).to_le_bytes());
        frame[440..444].copy_from_slice(&1f32.to_le_bytes());
        frame
    }
    #[test]
    fn gaze_v3_requires_fresh_real_pose_and_extracts_only_valid_samples() {
        let frame = gaze_frame();
        assert_eq!(sequence(&frame).unwrap(), 1);
        assert_eq!(
            gaze(&frame),
            Some(GazeSample {
                pose: [0., 0., -0.03, 0., 0., 0., 1.],
            })
        );
        assert!(sequence(&frame[..FRAME_V2_BYTES]).is_err());

        let mut stale = frame.clone();
        stale[408..416].copy_from_slice(&200_000_001u64.to_le_bytes());
        assert!(
            sequence(&stale).is_err(),
            "a gaze sample more than 100 ms from frame time must not validate"
        );

        let mut invalid_flags = frame;
        invalid_flags[400] = 2;
        assert!(sequence(&invalid_flags).is_err());
    }
    #[test]
    fn gaze_v3_without_a_valid_sample_is_uniform_and_bounded() {
        let mut frame = frame();
        frame.resize(FRAME_V3_BYTES, 0);
        frame[4] = 3;
        frame[16..24].copy_from_slice(&100_000_000u64.to_le_bytes());
        assert_eq!(sequence(&frame).unwrap(), 1);
        assert_eq!(gaze(&frame), None);
        frame[404] = 1;
        assert!(sequence(&frame).is_err());
    }
    #[test]
    fn accepts_lost_tracking_but_not_zero_sequence_or_wrong_sizes() {
        assert_eq!(sequence(&frame()).unwrap(), 1);
        assert!(sequence(&frame()[..223]).is_err());
        let mut f = frame();
        f[8] = 0;
        assert!(sequence(&f).is_err());
    }
    #[test]
    fn v2_validates_real_aim_and_analog_controls() {
        let mut f = frame();
        f.resize(FRAME_V2_BYTES, 0);
        f[4] = 2;
        assert!(sequence(&f).is_ok());
        f[32] = 51;
        f[64..68].copy_from_slice(&1f32.to_le_bytes());
        assert!(sequence(&f).is_ok());
        assert!(sequence(&f[..224]).is_err());
        f[224] = 3;
        assert!(sequence(&f).is_err());
        f[256..260].copy_from_slice(&1f32.to_le_bytes());
        assert!(sequence(&f).is_ok());
        f[360..364].copy_from_slice(&1.01f32.to_le_bytes());
        assert!(sequence(&f).is_err());
        f[360..364].copy_from_slice(&0.5f32.to_le_bytes());
        f[368..372].copy_from_slice(&(-1f32).to_le_bytes());
        assert!(sequence(&f).is_ok());
    }
    #[test]
    fn head_requires_both_orientation_and_position() {
        let mut f = frame();
        for flags in 0..4 {
            f[32] = flags;
            assert_eq!(head_valid(&f), flags == 3);
        }
        assert!(!head_valid(&f[..32]));
    }
    #[test]
    fn head_pose_needs_both_tracked_position_and_orientation() {
        let mut f = frame();
        f[32] = 3;
        assert_eq!(head_pose(&f), None, "zeroed devices are not a pose");
        for (i, value) in [1f32, 2., 3.].into_iter().enumerate() {
            f[40 + i * 4..44 + i * 4].copy_from_slice(&value.to_le_bytes());
        }
        f[52..56].copy_from_slice(&0f32.to_le_bytes());
        f[56..60].copy_from_slice(&0f32.to_le_bytes());
        f[60..64].copy_from_slice(&0f32.to_le_bytes());
        f[64..68].copy_from_slice(&2f32.to_le_bytes());
        assert!(sequence(&f).is_err(), "non-unit quaternion");
        f[64..68].copy_from_slice(&1f32.to_le_bytes());
        assert_eq!(head_pose(&f), Some([1., 2., 3., 0., 0., 0., 1.]));
        f[32] = 1;
        assert_eq!(head_pose(&f), None, "orientation alone cannot align");
        f[32] = 2;
        assert_eq!(head_pose(&f), None, "position alone cannot align");
        f[32] = 3;
        assert_eq!(head_pose(&f[..32]), None);
    }
    #[test]
    fn rejects_nonfinite_and_invalid_quaternion() {
        let mut f = frame();
        f[32] = 3;
        assert!(sequence(&f).is_err());
        f[64..68].copy_from_slice(&1f32.to_le_bytes());
        assert!(sequence(&f).is_ok());
        f[40..44].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(sequence(&f).is_err());
    }
}
