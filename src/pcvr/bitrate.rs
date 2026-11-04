//! Bounded feedback for the PCVR unordered SCTP video channel.
//! Queue pressure is observable here; unlike the desktop RTP path, this
//! channel has no congestion-control target bitrate to query.
use std::time::{Duration, Instant};

pub(crate) const INITIAL_BPS: u32 = 20_000_000;
const MINIMUM_BPS: u32 = 2_000_000;
const MAXIMUM_BPS: u32 = INITIAL_BPS;
const HIGH_WATER_BYTES: usize = 128 * 1024;
const LOW_WATER_BYTES: usize = 32 * 1024;
const DECREASE_COOLDOWN: Duration = Duration::from_millis(100);
const RECOVERY_INTERVAL: Duration = Duration::from_secs(2);

#[derive(Debug)]
pub(crate) struct Controller {
    target_bps: u32,
    last_decrease: Option<Instant>,
    healthy_since: Option<Instant>,
}
impl Default for Controller {
    fn default() -> Self {
        Self {
            target_bps: INITIAL_BPS,
            last_decrease: None,
            healthy_since: None,
        }
    }
}
impl Controller {
    /// Apply one latest-only channel queue sample and return the encoder rate.
    pub(crate) fn observe(&mut self, queued_bytes: usize, now: Instant) -> u32 {
        if queued_bytes > HIGH_WATER_BYTES {
            self.healthy_since = None;
            if self
                .last_decrease
                .is_none_or(|last| now.saturating_duration_since(last) >= DECREASE_COOLDOWN)
            {
                self.target_bps = (self.target_bps * 3 / 4).max(MINIMUM_BPS);
                self.last_decrease = Some(now);
            }
        } else if queued_bytes <= LOW_WATER_BYTES {
            let healthy = *self.healthy_since.get_or_insert(now);
            if now.saturating_duration_since(healthy) >= RECOVERY_INTERVAL {
                let increment = (self.target_bps / 10).max(250_000);
                self.target_bps = self.target_bps.saturating_add(increment).min(MAXIMUM_BPS);
                self.healthy_since = Some(now);
            }
        } else {
            self.healthy_since = None;
        }
        self.target_bps
    }

    /// A send deadline miss is direct congestion evidence, even when the last
    /// sampled queue happened to drain between fragments.
    pub(crate) fn congested(&mut self, now: Instant) -> u32 {
        self.observe(HIGH_WATER_BYTES + 1, now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backpressure_downshifts_with_a_floor_and_cooldown() {
        let start = Instant::now();
        let mut controller = Controller::default();
        assert_eq!(controller.target_bps, INITIAL_BPS);
        assert_eq!(controller.observe(HIGH_WATER_BYTES + 1, start), 15_000_000);
        assert_eq!(
            controller.observe(HIGH_WATER_BYTES + 1, start + Duration::from_millis(50)),
            15_000_000
        );
        assert_eq!(
            controller.observe(HIGH_WATER_BYTES + 1, start + DECREASE_COOLDOWN),
            11_250_000
        );
        for step in 2..20 {
            controller.congested(start + DECREASE_COOLDOWN * step);
        }
        assert_eq!(controller.target_bps, MINIMUM_BPS);
    }

    #[test]
    fn recovery_requires_continuous_low_queue_and_never_exceeds_ceiling() {
        let start = Instant::now();
        let mut controller = Controller::default();
        controller.congested(start);
        let reduced = controller.target_bps;
        assert_eq!(
            controller.observe(LOW_WATER_BYTES, start + Duration::from_secs(1)),
            reduced
        );
        assert_eq!(
            controller.observe(
                LOW_WATER_BYTES,
                start + Duration::from_secs(3) - Duration::from_millis(1)
            ),
            reduced
        );
        let first_recovery = reduced * 11 / 10;
        assert_eq!(
            controller.observe(LOW_WATER_BYTES, start + Duration::from_secs(3)),
            first_recovery
        );
        controller.observe(
            LOW_WATER_BYTES + 1,
            start + Duration::from_secs(3) + Duration::from_millis(1),
        );
        assert_eq!(
            controller.observe(LOW_WATER_BYTES, start + Duration::from_secs(4)),
            first_recovery,
            "a mid-water queue breaks the recovery window"
        );
        let second_recovery = first_recovery * 11 / 10;
        assert_eq!(
            controller.observe(LOW_WATER_BYTES, start + Duration::from_secs(6)),
            second_recovery
        );
        for step in 4..100 {
            controller.observe(0, start + RECOVERY_INTERVAL * step);
        }
        assert_eq!(controller.target_bps, MAXIMUM_BPS);
    }

    #[test]
    fn hysteresis_keeps_middle_queue_from_changing_rate() {
        let start = Instant::now();
        let mut controller = Controller::default();
        let target = controller.target_bps;
        for second in 0..10 {
            controller.observe(
                (LOW_WATER_BYTES + HIGH_WATER_BYTES) / 2,
                start + Duration::from_secs(second),
            );
        }
        assert_eq!(controller.target_bps, target);
    }
}
