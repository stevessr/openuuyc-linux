//! Controller-side state for the private OpenUUYC window extension.
use super::{OutgoingControlMessage, StreamControlHandle, StreamControlProtocol, lock};
use crate::features::window_control::{self, Request, WindowInfo};
use anyhow::{Result, bail, ensure};
use std::time::{Duration, Instant};
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const TIMEOUT_ERROR: &str = "远端窗口拓展未响应（可能未安装或未授权）";

#[derive(Clone, Debug, Default)]
pub(crate) struct Snapshot {
    pub windows: Vec<WindowInfo>,
    pub pending: bool,
    pub error: Option<String>,
}

#[derive(Default)]
pub(super) struct State {
    next_id: u64,
    pending: Option<(u64, &'static str, Instant)>,
    windows: Vec<WindowInfo>,
    error: Option<String>,
}

impl State {
    fn expire_pending(&mut self, now: Instant) -> bool {
        if !self
            .pending
            .as_ref()
            .is_some_and(|(_, _, since)| now.saturating_duration_since(*since) > REQUEST_TIMEOUT)
        {
            return false;
        }
        self.pending = None;
        self.windows.clear();
        self.error = Some(TIMEOUT_ERROR.into());
        true
    }

    fn apply_reply(&mut self, reply: window_control::Response, now: Instant) -> bool {
        self.expire_pending(now);
        let Some((id, action, _)) = self.pending.as_ref().copied() else {
            return false;
        };
        if id != reply.request_id {
            return false;
        }
        self.pending = None;
        if reply.ok {
            if action == "list" {
                self.windows = reply.windows;
            }
            self.error = None;
        } else {
            self.windows.clear();
            self.error = reply.error.or_else(|| Some("远端拒绝了窗口命令".into()));
        }
        true
    }
}

impl StreamControlHandle {
    pub(crate) fn window_snapshot(&self) -> Snapshot {
        let mut state = lock(&self.window_extension);
        state.expire_pending(Instant::now());
        Snapshot {
            windows: state.windows.clone(),
            pending: state.pending.is_some(),
            error: state.error.clone(),
        }
    }

    pub(crate) fn request_remote_windows(&self) -> Result<()> {
        self.send_window_command("list", None)
    }

    pub(crate) fn focus_remote_window(&self, id: &str) -> Result<()> {
        // Focus requests are only sent for a window the peer actually listed.
        {
            let mut state = lock(&self.window_extension);
            state.expire_pending(Instant::now());
            ensure!(
                state.windows.iter().any(|w| w.id == id),
                "请选择当前远端窗口列表中的窗口"
            );
        }
        self.send_window_command("focus", Some(id.to_owned()))
    }

    fn send_window_command(&self, action: &'static str, window_id: Option<String>) -> Result<()> {
        // Keep the channel-open state locked until the command is registered.
        // Channel close takes this lock before clearing extension state, so a
        // request cannot pass the open check and enqueue after disconnect.
        let shared = lock(&self.shared);
        ensure!(shared.text_channel_open, "远端 TEXT 数据通道尚未连接");
        let mut state = lock(&self.window_extension);
        if state.pending.is_some() {
            bail!("上一条窗口命令仍在等待回执");
        }
        state.next_id = state.next_id.wrapping_add(1).max(1);
        let request_id = state.next_id;
        let payload = window_control::encode_request(&Request {
            request_id,
            action: action.into(),
            window_id,
        })?;
        self.outgoing
            .send(OutgoingControlMessage {
                annotation_generation: None,
                sequence: 0,
                payload,
                protocol: StreamControlProtocol::Unsupported,
                completion: None,
            })
            .map_err(|_| anyhow::anyhow!("远端窗口命令发送任务已停止"))?;
        state.error = None;
        state.pending = Some((request_id, action, Instant::now()));
        Ok(())
    }

    /// Called before official protobuf parsing; no official client should
    /// ever be expected to understand this private message.
    pub(crate) fn receive_window_reply(&self, bytes: &[u8]) -> Result<bool> {
        if !window_control::is_packet(bytes) {
            return Ok(false);
        }
        let reply = window_control::decode_response(bytes)?;
        let mut state = lock(&self.window_extension);
        state.apply_reply(reply, Instant::now());
        Ok(true)
    }

    pub(crate) fn window_control_disconnect(&self) {
        let mut state = lock(&self.window_extension);
        state.pending = None;
        state.windows.clear();
        state.error = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seeded_state(action: &'static str) -> State {
        State {
            pending: Some((2, action, Instant::now())),
            windows: vec![WindowInfo {
                id: "niri:42".into(),
                title: "Editor".into(),
                application: "foot".into(),
                focused: true,
            }],
            ..Default::default()
        }
    }

    #[test]
    fn denied_window_command_discards_cached_inventory() {
        let mut state = seeded_state("focus");
        let applied = state.apply_reply(
            window_control::Response {
                request_id: 2,
                ok: false,
                error: Some("permission revoked".into()),
                ..Default::default()
            },
            Instant::now(),
        );

        assert!(applied);
        assert!(state.windows.is_empty());
        assert!(state.pending.is_none());
        assert_eq!(state.error.as_deref(), Some("permission revoked"));
    }

    #[test]
    fn timeout_discards_inventory_and_ignores_late_reply() {
        let mut state = seeded_state("list");
        let timed_out_at =
            state.pending.as_ref().unwrap().2 + REQUEST_TIMEOUT + Duration::from_millis(1);

        assert!(state.expire_pending(timed_out_at));
        assert!(state.windows.is_empty());
        assert_eq!(state.error.as_deref(), Some(TIMEOUT_ERROR));

        let applied = state.apply_reply(
            window_control::Response {
                request_id: 2,
                ok: true,
                windows: vec![WindowInfo {
                    id: "niri:99".into(),
                    title: "Late reply".into(),
                    application: "foot".into(),
                    focused: false,
                }],
                ..Default::default()
            },
            timed_out_at + Duration::from_millis(1),
        );

        assert!(!applied);
        assert!(state.windows.is_empty());
        assert!(state.pending.is_none());
        assert_eq!(state.error.as_deref(), Some(TIMEOUT_ERROR));
    }

    #[test]
    fn channel_close_cannot_leave_a_late_pending_window_command() {
        use std::sync::TryLockError;
        use std::thread;

        let profile = crate::media::ConnectionMediaOptions::default()
            .resolve(crate::media::LocalDisplayInfo::FALLBACK)
            .unwrap();
        let (handle, _outgoing, _echo) = StreamControlHandle::new(
            profile,
            crate::diagnostics::performance::PerformanceMonitor::new("window extension test"),
        );
        handle.set_data_channel_open("TEXT_DATA_CHANNEL", true);

        let blocked_state = lock(&handle.window_extension);
        let sender_handle = handle.clone();
        let sender = thread::spawn(move || sender_handle.request_remote_windows());

        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match handle.shared.try_lock() {
                Err(TryLockError::WouldBlock) => break,
                Ok(guard) => drop(guard),
                Err(TryLockError::Poisoned(_)) => panic!("shared state mutex poisoned"),
            }
            assert!(
                Instant::now() < deadline,
                "sender did not hold channel state while waiting"
            );
            thread::yield_now();
        }

        let close_handle = handle.clone();
        let close = thread::spawn(move || {
            close_handle.set_data_channel_open("TEXT_DATA_CHANNEL", false);
        });
        drop(blocked_state);

        sender.join().unwrap().unwrap();
        close.join().unwrap();
        let snapshot = handle.window_snapshot();
        assert!(!snapshot.pending);
        assert!(snapshot.windows.is_empty());
    }
}
