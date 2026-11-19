//! Host setting for reporting application window sources as an independent simulated device.
use std::sync::atomic::{AtomicBool, Ordering};

pub(crate) const DEVICE_NAME: &str = "应用窗口";
pub(crate) const KIND_WINDOW: i32 = 1;

static ENABLED: AtomicBool = AtomicBool::new(true);

pub(crate) fn enabled() -> bool {
    ENABLED.load(Ordering::Acquire)
}

pub(crate) fn set_enabled(value: bool) {
    ENABLED.store(value, Ordering::Release);
}
