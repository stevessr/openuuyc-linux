//! Calibrated PCVR cadence. Work time counts toward the frame budget; never
//! add a whole frame's sleep after encoding or queue catch-up frames.
use anyhow::{Result, ensure};
use std::time::Duration;
#[derive(Clone, Copy, Debug)]
pub(crate) struct Rate {
    pub fps: u32,
    pub refresh_hz: f32,
    pub period: Duration,
}
impl Rate {
    pub fn new(refresh_hz: f32) -> Result<Self> {
        ensure!(
            refresh_hz.is_finite() && (30.0..=165.0).contains(&refresh_hz),
            "invalid PCVR refresh rate"
        );
        Ok(Self {
            fps: refresh_hz.round() as u32,
            refresh_hz,
            period: Duration::from_secs_f64(1.0 / f64::from(refresh_hz)),
        })
    }
    pub fn remaining(self, work: Duration) -> Duration {
        self.period.saturating_sub(work)
    }
}
impl Default for Rate {
    fn default() -> Self {
        Self::new(30.0).unwrap()
    }
}
/// A measured sender rate, not a promise that the headset displayed each frame.
#[derive(Default)]
pub(crate) struct Meter {
    generation: u32,
    baseline: Option<(std::time::Instant, u64)>,
    value: Option<f64>,
}
impl Meter {
    pub fn sample(&mut self, generation: u32, now: std::time::Instant, count: u64) -> Option<f64> {
        if generation == 0 || self.generation != generation {
            self.generation = generation;
            self.baseline = Some((now, count));
            self.value = None;
            return None;
        }
        let Some((stamp, before)) = self.baseline else {
            self.baseline = Some((now, count));
            return None;
        };
        let Some(elapsed) = now.checked_duration_since(stamp) else {
            self.baseline = Some((now, count));
            self.value = None;
            return None;
        };
        let Some(frames) = count.checked_sub(before) else {
            self.baseline = Some((now, count));
            self.value = None;
            return None;
        };
        if elapsed >= Duration::from_secs(1) {
            self.value = Some(frames as f64 / elapsed.as_secs_f64());
            self.baseline = Some((now, count));
        }
        self.value
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn real_headset_rates_have_correct_frame_budgets() {
        for hz in [30.0, 59.94, 72.0, 90.0, 120.0, 144.0, 165.0] {
            let rate = Rate::new(hz).unwrap();
            assert_eq!(rate.fps, hz.round() as u32);
            assert!((rate.period.as_secs_f64() * f64::from(hz) - 1.0).abs() < 0.000001);
            assert_eq!(rate.remaining(Duration::ZERO), rate.period);
            let work = Duration::from_millis(5);
            assert_eq!(rate.remaining(work) + work, rate.period);
            assert_eq!(rate.remaining(Duration::from_secs(1)), Duration::ZERO);
        }
        assert!(Rate::new(72.0).unwrap().period < Duration::from_millis(14));
    }
    #[test]
    fn measured_rate_resets_on_stop_new_generation_or_counter_reset() {
        let start = std::time::Instant::now();
        let mut meter = Meter::default();
        assert_eq!(meter.sample(1, start, 50), None);
        assert_eq!(
            meter.sample(1, start + Duration::from_millis(500), 86),
            None
        );
        assert_eq!(
            meter.sample(1, start + Duration::from_secs(1), 122),
            Some(72.0)
        );
        assert_eq!(
            meter.sample(1, start + Duration::from_secs(2), 122),
            Some(0.0)
        );
        assert_eq!(meter.sample(2, start + Duration::from_secs(3), 150), None);
        assert_eq!(meter.sample(2, start + Duration::from_secs(4), 1), None);
        assert_eq!(meter.sample(0, start + Duration::from_secs(5), 1), None);
    }
    #[test]
    fn rejects_unusable_or_unbounded_rates() {
        for hz in [0.0, 29.9, 165.1, f32::NAN, f32::INFINITY] {
            assert!(Rate::new(hz).is_err());
        }
    }
}
