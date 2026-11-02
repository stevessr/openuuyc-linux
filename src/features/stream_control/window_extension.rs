//! Controller-side state for the private OpenUUYC window extension.
use super::{OutgoingControlMessage, StreamControlHandle, StreamControlProtocol, lock};
use crate::features::window_control::{self, Request, WindowInfo};
use anyhow::{Result, bail, ensure};
use std::time::{Duration, Instant};

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

impl StreamControlHandle {
    pub(crate) fn window_snapshot(&self) -> Snapshot {
        let state = lock(&self.window_extension);
        let expired = state.pending.as_ref().is_some_and(|(_, _, since)| since.elapsed() > Duration::from_secs(5));
        Snapshot {
            windows: state.windows.clone(),
            pending: state.pending.is_some() && !expired,
            error: if expired { Some("远端窗口拓展未响应（可能未安装或未授权）".into()) }
                else { state.error.clone() },
        }
    }

    pub(crate) fn request_remote_windows(&self) -> Result<()> {
        self.send_window_command("list", None)
    }

    pub(crate) fn focus_remote_window(&self, id: &str) -> Result<()> {
        // Focus requests are only sent for a window the peer actually listed.
        ensure!(lock(&self.window_extension).windows.iter().any(|w| w.id == id),
            "请选择当前远端窗口列表中的窗口");
        self.send_window_command("focus", Some(id.to_owned()))
    }

    fn send_window_command(&self, action: &'static str, window_id: Option<String>) -> Result<()> {
        ensure!(lock(&self.shared).text_channel_open, "远端 TEXT 数据通道尚未连接");
        let mut state = lock(&self.window_extension);
        if state.pending.as_ref().is_some_and(|(_, _, at)| at.elapsed() <= Duration::from_secs(5)) {
            bail!("上一条窗口命令仍在等待回执");
        }
        state.next_id = state.next_id.wrapping_add(1).max(1);
        let request_id = state.next_id;
        let payload = window_control::encode_request(&Request {
            request_id, action: action.into(), window_id,
        })?;
        self.outgoing.send(OutgoingControlMessage {
            annotation_generation: None,
            sequence: 0,
            payload,
            protocol: StreamControlProtocol::Unsupported,
            completion: None,
        }).map_err(|_| anyhow::anyhow!("远端窗口命令发送任务已停止"))?;
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
        let Some((id, action, _)) = state.pending.as_ref().copied() else {
            return Ok(true);
        };
        if id != reply.request_id { return Ok(true); }
        state.pending = None;
        if reply.ok {
            if action == "list" { state.windows = reply.windows; }
            state.error = None;
        } else {
            state.error = reply.error.or_else(|| Some("远端拒绝了窗口命令".into()));
        }
        Ok(true)
    }

    pub(crate) fn window_control_disconnect(&self) {
        let mut state = lock(&self.window_extension);
        state.pending = None;
        state.windows.clear();
        state.error = None;
    }
}
