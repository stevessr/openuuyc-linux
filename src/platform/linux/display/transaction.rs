use std::time::{Duration, Instant};

pub(crate) const CONFIRM_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug)]
pub(crate) struct FrameConfirmation {
    expected: (u32, u32),
    deadline: Instant,
    confirmed: bool,
}

impl FrameConfirmation {
    pub(crate) fn applied(expected: (u32, u32), now: Instant, timeout: Duration) -> Self {
        Self {
            expected,
            deadline: now + timeout,
            confirmed: false,
        }
    }

    /// A capture is fresh only when the caller has just received a frame from
    /// the selected output. Its source geometry, not the encoded size, decides
    /// whether this mode transaction can commit.
    pub(crate) fn observe_frame(&mut self, now: Instant, source_size: (u32, u32)) -> bool {
        if !self.confirmed && now < self.deadline && source_size == self.expected {
            self.confirmed = true;
        }
        self.confirmed
    }

    pub(crate) fn rollback_due(&self, now: Instant) -> bool {
        !self.confirmed && now >= self.deadline
    }
}

#[derive(Debug, thiserror::Error)]
#[error("no fresh {width}x{height} frame arrived within {timeout:?}")]
pub(crate) struct FrameUnconfirmed {
    pub width: u32,
    pub height: u32,
    pub timeout: Duration,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matching_fresh_frame_confirms_before_deadline() {
        let started = Instant::now();
        let mut confirmation =
            FrameConfirmation::applied((2560, 1440), started, Duration::from_secs(30));

        assert!(confirmation.observe_frame(started + Duration::from_secs(1), (2560, 1440)));
        assert!(!confirmation.rollback_due(started + Duration::from_secs(31)));
    }

    #[test]
    fn old_or_wrong_size_frame_does_not_confirm() {
        let started = Instant::now();
        let mut confirmation =
            FrameConfirmation::applied((2560, 1440), started, Duration::from_secs(30));

        assert!(!confirmation.observe_frame(started + Duration::from_secs(1), (1920, 1080)));
        assert!(!confirmation.rollback_due(started + Duration::from_secs(29)));
        assert!(confirmation.rollback_due(started + Duration::from_secs(30)));
    }

    #[test]
    fn frame_at_or_after_deadline_cannot_commit() {
        let started = Instant::now();
        let mut confirmation =
            FrameConfirmation::applied((1280, 720), started, Duration::from_secs(30));

        assert!(!confirmation.observe_frame(started + Duration::from_secs(30), (1280, 720)));
        assert!(confirmation.rollback_due(started + Duration::from_secs(30)));
    }
}
