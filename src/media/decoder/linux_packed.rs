//! CPU fallback for packed AV1 4:4:4 pictures. P010 uses 16-bit GPU textures
//! instead, so no 10-bit 4:2:0 precision is discarded on the upload path.
use super::Rgba8;
use crate::media::video_color::{ColorMatrix, RenderColor};
use anyhow::{Result, bail};

/// The AV1 core packs 8-bit 4:4:4 as [V, U, Y, A] and 10-bit 4:4:4
/// as DXGI Y410 (U in bits 0..9, Y in 10..19, V in 20..29).
pub(super) fn packed_444_to_rgba(
    width: u32,
    height: u32,
    data: &[u8],
    ten_bit: bool,
    color: RenderColor,
) -> Result<Vec<Rgba8>> {
    let pixels = (width as usize)
        .checked_mul(height as usize)
        .filter(|&count| count > 0)
        .ok_or_else(|| anyhow::anyhow!("invalid packed 4:4:4 dimensions"))?;
    if pixels.checked_mul(4) != Some(data.len()) {
        bail!("invalid packed 4:4:4 frame {width}x{height}: {} bytes", data.len());
    }
    let matrix = color.transform(if ten_bit { 10 } else { 8 });
    let mut output = Vec::with_capacity(pixels);
    for input in data.chunks_exact(4) {
        let (y, u, v) = if ten_bit {
            let word = u32::from_le_bytes(input.try_into().expect("four bytes"));
            let sample = |shift| (((word >> shift) & 1023) << 6) as f32 / 65535.0;
            (sample(10), sample(0), sample(20))
        } else {
            (input[2] as f32 / 255.0, input[1] as f32 / 255.0, input[0] as f32 / 255.0)
        };
        let yuv = [y, u, v, 1.0];
        let mut rgb = [0.0; 3];
        for channel in 0..3 {
            rgb[channel] = matrix[channel].iter().zip(yuv).map(|(a, b)| a * b).sum();
        }
        if let Some(peak) = color.hdr_peak_nits {
            rgb = pq_to_sdr(rgb, peak, color.matrix == ColorMatrix::Bt2020);
        }
        output.push(Rgba8(rgb.map(|value| (value.clamp(0.0, 1.0) * 255.0).round() as u8)
            .map_or_rgba()));
    }
    Ok(output)
}

/// ST 2084 PQ -> linear light -> bounded SDR, preserving highlights.
/// The destination is an SDR swapchain, not a claim of native HDR output.
fn pq_to_sdr(rgb: [f32; 3], peak: u16, bt2020: bool) -> [f32; 3] {
    let decode = |pq: f32| {
        let value = pq.clamp(0.0, 1.0).powf(1.0 / (2523.0 / 32.0));
        let num = (value - 3424.0 / 4096.0).max(0.0);
        let den = (2413.0 / 128.0 - (2392.0 / 128.0) * value).max(1e-6);
        (num / den).powf(1.0 / (2610.0 / 16384.0)) * 10000.0
    };
    let linear = rgb.map(decode);
    let linear = if bt2020 {
        [
            1.6605 * linear[0] - 0.5876 * linear[1] - 0.0728 * linear[2],
            -0.1246 * linear[0] + 1.1329 * linear[1] - 0.0083 * linear[2],
            -0.0182 * linear[0] - 0.1006 * linear[1] + 1.1187 * linear[2],
        ]
    } else {
        linear
    };
    let white = (peak as f32).max(203.0);
    let norm = white / (white + 203.0);
    linear.map(|nits| {
        let mapped = (nits.max(0.0) / (nits.max(0.0) + 203.0) / norm).clamp(0.0, 1.0);
        if mapped <= 0.0031308 {
            mapped * 12.92
        } else {
            1.055 * mapped.powf(1.0 / 2.4) - 0.055
        }
    })
}

trait Rgba {
    fn map_or_rgba(self) -> [u8; 4];
}
impl Rgba for [u8; 3] {
    fn map_or_rgba(self) -> [u8; 4] {
        [self[0], self[1], self[2], 255]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_short_and_overflow_frames() {
        assert!(packed_444_to_rgba(3, 2, &[0; 20], false, RenderColor::default()).is_err());
        assert!(packed_444_to_rgba(u32::MAX, u32::MAX, &[], true, RenderColor::default()).is_err());
    }

    #[test]
    fn ayuv_and_y410_neutral_gray() {
        let color = RenderColor { full_range: true, ..RenderColor::default() };
        let ayuv = packed_444_to_rgba(1, 1, &[128, 128, 128, 255], false, color).unwrap();
        let y410 = (512_u32 | (512_u32 << 10) | (512_u32 << 20) | (3_u32 << 30)).to_le_bytes();
        let ten = packed_444_to_rgba(1, 1, &y410, true, color).unwrap();
        for result in [ayuv[0].0, ten[0].0] {
            assert!((i32::from(result[0]) - i32::from(result[1])).abs() <= 2);
            assert!((i32::from(result[1]) - i32::from(result[2])).abs() <= 2);
            assert_eq!(result[3], 255);
        }
    }

    #[test]
    fn pq_is_finite_and_monotonic() {
        let a = pq_to_sdr([0.4; 3], 1000, false);
        let b = pq_to_sdr([0.7; 3], 1000, false);
        for channel in 0..3 {
            assert!(a[channel].is_finite() && b[channel].is_finite());
            assert!(a[channel] < b[channel]);
            assert!((0.0..=1.0).contains(&a[channel]));
            assert!((0.0..=1.0).contains(&b[channel]));
        }
    }
}
