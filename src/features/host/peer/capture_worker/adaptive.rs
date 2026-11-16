//! Per-track encoder performance trials; slow candidates are not driver failures.
use std::collections::{HashMap, HashSet};
use std::hash::Hash;
use std::time::{Duration, Instant};

/// Everything that decides which candidates may stand in for the baseline.
/// When this is unchanged the exclusion set is unchanged too, so the host loop
/// can reuse it instead of rebuilding a set on every frame.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) struct Workload {
    pub adapter: u64,
    pub backend: crate::features::host::format::Backend,
    pub format: crate::features::host::format::Format,
    pub desired: (u32, u32),
    pub fps: u32,
    pub requested_maximum: Option<(u32, u32)>,
    /// Candidates only ever join the failed set during a session.
    pub failed: usize,
}

pub(super) struct Adaptive<K> {
    scores: HashMap<K, Duration>,
    exploring: bool,
    settled: bool,
    retry_after: Option<Instant>,
    window: Option<(K, Instant)>,
    samples: u32,
    total: Duration,
    slow_windows: u32,
    last_sample: Option<Instant>,
}
impl<K> Default for Adaptive<K> {
    fn default() -> Self {
        Self {
            scores: HashMap::new(),
            exploring: false,
            settled: false,
            retry_after: None,
            window: None,
            samples: 0,
            total: Duration::ZERO,
            slow_windows: 0,
            last_sample: None,
        }
    }
}
impl<K: Copy + Eq + Hash> Adaptive<K> {
    /// Performance exclusions never overwrite the real failed-candidate set.
    /// While exploring, try unmeasured candidates. Once settled, pin the best
    /// measured surviving candidate so ranking cannot immediately undo the trial.
    pub fn exclusions(&self, all: impl Iterator<Item = K>, failed: &HashSet<K>) -> HashSet<K> {
        let mut excluded = failed.clone();
        if self.settled {
            if let Some((&best, _)) = self
                .scores
                .iter()
                .filter(|(id, _)| !failed.contains(*id))
                .min_by_key(|(_, cost)| **cost)
            {
                excluded.extend(all.filter(|id| *id != best));
            }
        } else if self.exploring {
            excluded.extend(self.scores.keys().copied());
        }
        excluded
    }

    pub fn finish_trials(&mut self, now: Instant) {
        self.settled = true;
        self.retry_after = Some(now + Duration::from_secs(30));
        self.window = None;
    }

    /// Only successful new input frames are observations. A second of warmup
    /// is ignored after each switch. Require two seconds and sixty samples per
    /// window: sparse/idle sources and isolated keyframe spikes cannot trigger
    /// a trial. Two consecutive windows over 110% of the frame budget start it.
    pub fn observe(
        &mut self,
        id: K,
        now: Instant,
        cost: Duration,
        budget: Duration,
    ) -> Option<Duration> {
        if self.settled && self.retry_after.is_some_and(|at| now < at) {
            return None;
        }
        let gap = self
            .last_sample
            .is_some_and(|last| now.saturating_duration_since(last) > Duration::from_millis(500));
        self.last_sample = Some(now);
        if gap || self.window.as_ref().is_none_or(|(old, _)| *old != id) {
            self.window = Some((id, now + Duration::from_secs(1)));
            self.samples = 0;
            self.total = Duration::ZERO;
            self.slow_windows = 0;
            return None;
        }
        let (_, start) = self.window.unwrap();
        if now < start {
            return None;
        }
        self.samples += 1;
        self.total += cost;
        if now.duration_since(start) < Duration::from_secs(2) || self.samples < 60 {
            return None;
        }
        let avg = self.total / self.samples;
        self.samples = 0;
        self.total = Duration::ZERO;
        self.window = Some((id, now));
        if avg > budget.mul_f64(1.1) {
            self.slow_windows += 1;
        } else {
            self.slow_windows = 0;
            if self.exploring && !self.settled {
                self.scores.insert(id, avg);
                self.finish_trials(now);
                return Some(avg);
            }
        }
        if self.slow_windows < 2 {
            return None;
        }
        // Reconsider alternatives after cooldown only if the incumbent again
        // misses two windows. Old scores belong to the previous load regime.
        if self.settled {
            self.scores.clear();
            self.settled = false;
            self.retry_after = None;
        }
        self.scores.insert(id, avg);
        self.exploring = true;
        self.window = None;
        Some(avg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn feed(p: &mut Adaptive<u8>, id: u8, start: Instant, ticks: u64, ms: u64) {
        for tick in 0..ticks {
            p.observe(
                id,
                start + Duration::from_millis(tick * 20),
                Duration::from_millis(ms),
                Duration::from_millis(7),
            );
        }
    }
    #[test]
    fn trials_exhaust_to_fastest_and_honor_real_failures() {
        let t = Instant::now();
        let mut p = Adaptive::default();
        feed(&mut p, 1, t, 260, 16);
        assert_eq!(
            p.exclusions([1, 2].into_iter(), &HashSet::new()),
            HashSet::from([1])
        );
        feed(&mut p, 2, t + Duration::from_secs(6), 260, 9);
        assert_eq!(
            p.exclusions([1, 2].into_iter(), &HashSet::new()),
            HashSet::from([1, 2])
        );
        p.finish_trials(t + Duration::from_secs(12));
        assert_eq!(
            p.exclusions([1, 2].into_iter(), &HashSet::new()),
            HashSet::from([1])
        );
        assert_eq!(
            p.exclusions([1, 2].into_iter(), &HashSet::from([2])),
            HashSet::from([2])
        );
    }
    #[test]
    fn fast_trial_stays_selected_instead_of_bouncing() {
        let t = Instant::now();
        let mut p = Adaptive::default();
        feed(&mut p, 1, t, 260, 16);
        feed(&mut p, 2, t + Duration::from_secs(6), 160, 5);
        assert_eq!(
            p.exclusions([1, 2, 3].into_iter(), &HashSet::new()),
            HashSet::from([1, 3])
        );
        feed(&mut p, 2, t + Duration::from_secs(10), 500, 16);
        assert_eq!(
            p.exclusions([1, 2, 3].into_iter(), &HashSet::new()),
            HashSet::from([1, 3])
        );
    }
    #[test]
    fn warmup_spike_and_single_slow_window_do_not_switch() {
        let t = Instant::now();
        let mut p = Adaptive::default();
        feed(&mut p, 1, t, 50, 100);
        feed(&mut p, 1, t + Duration::from_secs(1), 110, 16);
        assert!(p.exclusions([1, 2].into_iter(), &HashSet::new()).is_empty());
        feed(&mut p, 1, t + Duration::from_millis(3200), 110, 5);
        feed(&mut p, 1, t + Duration::from_millis(5400), 80, 16);
        assert!(p.exclusions([1, 2].into_iter(), &HashSet::new()).is_empty());
    }
    #[test]
    fn cooldown_then_sustained_overload_restarts_trials_with_fresh_scores() {
        let t = Instant::now();
        let mut p = Adaptive::default();
        feed(&mut p, 1, t, 260, 16);
        feed(&mut p, 2, t + Duration::from_secs(6), 160, 5);
        feed(&mut p, 2, t + Duration::from_secs(10), 500, 16);
        assert_eq!(
            p.exclusions([1, 2, 3].into_iter(), &HashSet::new()),
            HashSet::from([1, 3])
        );
        feed(&mut p, 2, t + Duration::from_secs(40), 260, 16);
        assert_eq!(
            p.exclusions([1, 2, 3].into_iter(), &HashSet::new()),
            HashSet::from([2])
        );
    }

    #[test]
    fn idle_frames_never_accumulate_an_overload_window() {
        let t = Instant::now();
        let mut p = Adaptive::default();
        for n in 0..100 {
            p.observe(
                1,
                t + Duration::from_secs(n),
                Duration::from_secs(1),
                Duration::from_millis(7),
            );
        }
        assert!(p.exclusions([1, 2].into_iter(), &HashSet::new()).is_empty());
    }
}
