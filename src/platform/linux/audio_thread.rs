//! Per-thread setup for the audio output worker. Windows enters the COM
//! multithreaded apartment here; PulseAudio and ALSA keep no per-thread
//! runtime, so on Linux there is nothing to enter.
use std::convert::Infallible;

pub(crate) fn initialize() -> Result<(), Infallible> {
    Ok(())
}

pub(crate) fn description() -> String {
    "none (Linux has no COM)".to_owned()
}

/// Low latency is enabled by default; only bounded audio workers enter RT.
static RT_OVERRIDE: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(2);
pub(crate) fn rt_requested() -> bool {
    match RT_OVERRIDE.load(std::sync::atomic::Ordering::Relaxed) {
        0 => false,
        1 => true,
        _ => rt_default(std::env::var("OPENUUYC_AUDIO_RT").ok().as_deref()),
    }
}
fn rt_default(value: Option<&str>) -> bool {
    value != Some("0")
}
pub(crate) fn set_rt_requested(enabled: bool) {
    RT_OVERRIDE.store(u8::from(enabled), std::sync::atomic::Ordering::Relaxed);
}

pub(crate) struct RealtimeGuard {
    policy: libc::c_int,
    parameters: libc::sched_param,
    _thread_bound: std::marker::PhantomData<std::rc::Rc<()>>,
}
impl RealtimeGuard {
    pub(crate) fn enter() -> anyhow::Result<Self> {
        anyhow::ensure!(rt_requested(), "未请求音频 RT 模式");
        Self::enter_allowed()
    }
    fn enter_allowed() -> anyhow::Result<Self> {
        let mut policy = 0;
        let mut parameters = libc::sched_param { sched_priority: 0 };
        // SAFETY: valid output storage, operating only on the calling thread.
        let thread = unsafe { libc::pthread_self() };
        let code = unsafe { libc::pthread_getschedparam(thread, &mut policy, &mut parameters) };
        if code != 0 {
            return Err(std::io::Error::from_raw_os_error(code).into());
        }
        let desired = libc::sched_param { sched_priority: 10 };
        let code = unsafe { libc::pthread_setschedparam(thread, libc::SCHED_RR, &desired) };
        if code != 0 {
            return Err(std::io::Error::from_raw_os_error(code))
                .map_err(|e| anyhow::anyhow!("音频 RT 请求被拒绝：{e}；保持普通调度，不自动提权"));
        }
        tracing::info!(
            policy = "SCHED_RR",
            priority = 10,
            "audio dedicated worker RT scheduling active"
        );
        Ok(Self {
            policy,
            parameters,
            _thread_bound: std::marker::PhantomData,
        })
    }
}
impl Drop for RealtimeGuard {
    fn drop(&mut self) {
        // SAFETY: !Send/!Sync guard can only be dropped on its entering thread.
        let code = unsafe {
            libc::pthread_setschedparam(libc::pthread_self(), self.policy, &self.parameters)
        };
        if code != 0 {
            tracing::warn!(code, "audio worker scheduling restore failed")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rt_default_is_on_and_explicit_zero_disables_it() {
        assert!(rt_default(None));
        assert!(rt_default(Some("1")));
        assert!(!rt_default(Some("0")));
    }
    #[test]
    #[ignore = "explicit local thread scheduling probe; restores before thread exit"]
    fn live_rt_thread_restores_scheduler() {
        std::thread::spawn(|| {
            let mut original = 0;
            let mut before = libc::sched_param { sched_priority: 0 };
            unsafe {
                assert_eq!(
                    libc::pthread_getschedparam(libc::pthread_self(), &mut original, &mut before),
                    0
                )
            };
            let guard =
                RealtimeGuard::enter_allowed().expect("RT permission for opt-in local test");
            let mut active = 0;
            let mut parameters = libc::sched_param { sched_priority: 0 };
            unsafe {
                assert_eq!(
                    libc::pthread_getschedparam(libc::pthread_self(), &mut active, &mut parameters),
                    0
                )
            };
            assert_eq!(active, libc::SCHED_RR);
            assert_eq!(parameters.sched_priority, 10);
            drop(guard);
            unsafe {
                assert_eq!(
                    libc::pthread_getschedparam(libc::pthread_self(), &mut active, &mut parameters),
                    0
                )
            };
            assert_eq!(active, original);
            assert_eq!(parameters.sched_priority, before.sched_priority);
        })
        .join()
        .unwrap();
    }
}
