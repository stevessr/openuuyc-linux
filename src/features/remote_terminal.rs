//! Explicit per-connection terminal client; no command/history is logged or saved.
use crate::features::host::terminal::{Frame, FrameType};
use bytes::Bytes;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use webrtc::data_channel::{RTCDataChannel, data_channel_state::RTCDataChannelState};
pub(crate) const CHANNEL: &str = "OPENUUYC_TERMINAL_V1";
#[derive(Debug, PartialEq, Eq)]
enum SendGate {
    Send,
    Wait,
    Stop,
}
fn send_gate(open: bool, buffered: usize, wait: std::time::Duration) -> SendGate {
    if !open || wait >= std::time::Duration::from_secs(2) {
        SendGate::Stop
    } else if buffered > 512 * 1024 {
        SendGate::Wait
    } else {
        SendGate::Send
    }
}
#[derive(Clone)]
pub(crate) struct Terminal(Arc<Mutex<State>>);
struct State {
    tx: Option<mpsc::Sender<Vec<u8>>>,
    channel: Option<std::sync::Weak<RTCDataChannel>>,
    cols: u16,
    rows: u16,
    epoch: u64,
    handle: u32,
    active: bool,
    pending: bool,
    visible: bool,
    status: String,
    started: Option<std::time::Instant>,
    parser: vt100::Parser,
    input: String,
}
impl Default for Terminal {
    fn default() -> Self {
        Self(Arc::new(Mutex::new(State {
            tx: None,
            channel: None,
            cols: 80,
            rows: 24,
            epoch: 0,
            handle: 0,
            active: false,
            pending: false,
            visible: false,
            started: None,
            status: "此设备的终端通道尚未就绪".into(),
            parser: vt100::Parser::new(24, 80, 500),
            input: String::new(),
        })))
    }
}
impl Terminal {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn send(state: &State, kind: FrameType, payload: &[u8]) -> bool {
        state.tx.as_ref().is_some_and(|tx| {
            tx.try_send(Frame::encode(kind, state.handle, payload))
                .is_ok()
        })
    }
    pub(crate) fn bind(&self, channel: &Arc<RTCDataChannel>) {
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(64);
        {
            let mut s = self.lock();
            s.tx = Some(tx);
            s.channel = Some(Arc::downgrade(channel));
        }
        let receive = self.clone();
        channel.on_message(Box::new(move |message| {
            let receive = receive.clone();
            Box::pin(async move {
                receive.receive(&message.data);
            })
        }));
        let closed = self.clone();
        channel.on_close(Box::new(move || {
            closed.disconnected();
            Box::pin(async {})
        }));
        let weak = Arc::downgrade(channel);
        let failed = self.clone();
        tokio::spawn(async move {
            while let Some(bytes) = rx.recv().await {
                let Some(ch) = weak.upgrade() else {
                    failed.disconnected();
                    break;
                };
                let started = std::time::Instant::now();
                loop {
                    match send_gate(
                        ch.ready_state() == RTCDataChannelState::Open,
                        ch.buffered_amount().await,
                        started.elapsed(),
                    ) {
                        SendGate::Wait => {
                            tokio::time::sleep(std::time::Duration::from_millis(20)).await
                        }
                        SendGate::Send => {
                            if ch.send(&Bytes::from(bytes)).await.is_err() {
                                failed.disconnected();
                                return;
                            }
                            break;
                        }
                        SendGate::Stop => {
                            // Only this optional channel is closed. Never cancel
                            // the desktop peer, nor pretend the queued input ran.
                            failed.disconnected();
                            failed.lock().status =
                                "终端发送拥塞或断开，最后输入未确认；Shell 状态待确认".into();
                            let _ = ch.close().await;
                            return;
                        }
                    }
                }
            }
        });
    }
    fn receive(&self, data: &[u8]) {
        if data.len() > 256 * 1024 + 16 {
            return;
        }
        let Some(frame) = Frame::parse(data) else {
            return;
        };
        if frame.payload.len() + 16 != data.len() {
            return;
        }
        let mut s = self.lock();
        if frame.session != s.handle {
            return;
        }
        match frame.kind {
            FrameType::OpenResp => {
                let v = serde_json::from_slice::<serde_json::Value>(&frame.payload).ok();
                let ok = v.as_ref().and_then(|v| v["code"].as_i64()) == Some(0);
                if !s.pending {
                    if ok {
                        Self::send(&s, FrameType::Close, &[]);
                    }
                    return;
                }
                s.pending = false;
                s.active = ok;
                s.status = if ok {
                    "远端 Shell 已打开".into()
                } else {
                    v.and_then(|v| v["message"].as_str().map(str::to_owned))
                        .unwrap_or_else(|| "远端拒绝终端".into())
                };
            }
            FrameType::Stdout if s.active => s.parser.process(&frame.payload),
            FrameType::Exit | FrameType::Error => {
                s.epoch = s.epoch.wrapping_add(1);
                s.input.clear();
                s.active = false;
                s.pending = false;
                // Shell 没了，这个句柄不再指向活着的会话；下次打开新开一个。
                s.handle = 0;
                s.status = if frame.kind == FrameType::Exit {
                    "Shell 已退出".into()
                } else {
                    serde_json::from_slice::<serde_json::Value>(&frame.payload)
                        .ok()
                        .and_then(|v| v["message"].as_str().map(str::to_owned))
                        .unwrap_or_else(|| "终端请求失败".into())
                };
            }
            _ => {}
        }
    }
    pub(crate) fn show(&self) {
        self.lock().visible = true;
    }
    /// 离开这个 Shell，但不结束它：只发 DETACH，和 tmux detach 一样。句柄
    /// 留着，下次打开按同一个句柄接回原来的 Shell（远端会重放当前屏幕）。
    pub(crate) fn detach(&self) {
        let mut s = self.lock();
        s.epoch = s.epoch.wrapping_add(1);
        if s.active || s.pending {
            Self::send(&s, FrameType::Detach, &[]);
        }
        s.active = false;
        s.pending = false;
        s.visible = false;
        s.input.clear();
        s.status = if s.handle != 0 {
            "已离开远端 Shell，它仍在远端运行；再打开会接回同一个 Shell".into()
        } else {
            "已离开远端终端".into()
        };
    }
    /// 结束这个 Shell。只有用户明确要求时才发 CLOSE：多数远端把它当作杀掉
    /// 进程，和 [`Self::detach`] 不是一回事。
    pub(crate) fn end(&self) {
        let mut s = self.lock();
        s.epoch = s.epoch.wrapping_add(1);
        if s.active || s.pending {
            Self::send(&s, FrameType::Close, &[]);
        }
        s.active = false;
        s.pending = false;
        s.handle = 0;
        s.input.clear();
        s.parser = vt100::Parser::new(s.rows, s.cols, 500);
        s.status = "已结束远端 Shell".into();
    }
    pub(crate) fn disconnected(&self) {
        let mut s = self.lock();
        s.epoch = s.epoch.wrapping_add(1);
        let was_connected = s.tx.is_some() || s.channel.is_some();
        s.tx = None;
        s.channel = None;
        s.active = false;
        s.pending = false;
        s.input.clear();
        s.parser = vt100::Parser::new(s.rows, s.cols, 500);
        if was_connected {
            s.status = "终端连接已结束".into();
        }
    }
    fn open(&self) {
        let mut s = self.lock();
        if s.active || s.pending {
            return;
        }
        if !s
            .channel
            .as_ref()
            .and_then(|c| c.upgrade())
            .is_some_and(|c| c.ready_state() == RTCDataChannelState::Open)
        {
            s.status = "此设备不支持终端扩展，或通道尚未就绪".into();
            return;
        }
        s.epoch = s.epoch.wrapping_add(1);
        let reattach = Self::prepare_open(&mut s);
        if Self::send(
            &s,
            FrameType::Open,
            serde_json::to_string(
                &serde_json::json!({"cols":s.cols,"rows":s.rows,"name":"OpenUUYC"}),
            )
            .unwrap()
            .as_bytes(),
        ) {
            s.pending = true;
            s.started = Some(std::time::Instant::now());
            s.status = if reattach {
                "正在接回上次的 Shell…".into()
            } else {
                "等待远端授权并创建 Shell…".into()
            };
        }
    }
    /// 打开前把状态准备好：有句柄就按它接回上次的 Shell（远端保留着它并重
    /// 放屏幕），没有才生成新的。远端遇到不存在的句柄会用它新建，所以这个
    /// 句柄一直可用。重放的是一整屏，旧的模型内容要丢掉，否则会接在后面重
    /// 复一遍。返回是不是接回。
    fn prepare_open(s: &mut State) -> bool {
        let reattach = s.handle != 0;
        if !reattach {
            s.handle = (uuid::Uuid::new_v4().as_u128() as u32 & 0x7fff_ffff).max(1);
        }
        s.parser = vt100::Parser::new(s.rows, s.cols, 500);
        s.input.clear();
        reattach
    }
    fn submit(&self, epoch: u64, input: String, send: Option<String>) {
        let mut s = self.lock();
        if s.epoch != epoch {
            if send.is_some() {
                s.status = "Shell 状态已变化，未发送旧输入".into();
            }
            return;
        }
        s.input = input;
        if let Some(bytes) = send {
            if !s.active || !Self::send(&s, FrameType::Stdin, bytes.as_bytes()) {
                s.status = "终端输入未发送（通道拥塞、Shell 退出或断开）".into();
            }
        }
    }
    pub(crate) fn ui(&self, ctx: &egui::Context) {
        let (mut visible, status, contents, active, pending, mut input, epoch, handle) = {
            let mut s = self.lock();
            if s.pending
                && s.started
                    .is_some_and(|t| t.elapsed() > std::time::Duration::from_secs(8))
            {
                // 创建超时：只离开，不杀可能已经在远端跑起来的 Shell。
                Self::send(&s, FrameType::Detach, &[]);
                s.pending = false;
                s.status = "终端创建超时，已离开；Shell 可能仍在远端".into();
            }
            (
                s.visible,
                s.status.clone(),
                s.parser.screen().contents(),
                s.active,
                s.pending,
                s.input.clone(),
                s.epoch,
                s.handle,
            )
        };
        if !visible {
            return;
        }
        let mut open = false;
        let mut end = false;
        let mut send = None;
        egui::Window::new("远端终端")
            .id(egui::Id::new((
                "remote-terminal",
                Arc::as_ptr(&self.0) as usize,
            )))
            .open(&mut visible)
            .default_width(740.)
            .show(ctx, |ui| {
                ui.label(
                    "命令在当前连接的远端设备执行；需要被控端单独授权。关闭此窗口只是离开，\
                     Shell 会留在远端继续运行，再打开可接回同一个 Shell。",
                );
                ui.label(status);
                ui.horizontal(|ui| {
                    if !active && !pending {
                        let label = if handle != 0 {
                            "接回远端 Shell"
                        } else {
                            "打开远端 Shell"
                        };
                        open = ui.button(label).clicked();
                    }
                    if (active || pending || handle != 0)
                        && ui
                            .button("结束 Shell")
                            .on_hover_text("让远端结束这个 Shell；只是离开窗口不需要点它")
                            .clicked()
                    {
                        end = true;
                    }
                });
                egui::ScrollArea::both()
                    .max_height(500.)
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                    ui.add(
                        egui::Label::new(egui::RichText::new(contents).monospace())
                            .selectable(true),
                    );
                });
                ui.horizontal(|ui| {
                    for (cols, rows) in [(80u16, 24u16), (100, 30), (120, 36)] {
                        if ui
                            .add_enabled(!pending, egui::Button::new(format!("{cols}×{rows}")))
                            .clicked()
                        {
                            let mut s = self.lock();
                            let payload = serde_json::json!({"cols":cols,"rows":rows}).to_string();
                            if !s.active || Self::send(&s, FrameType::Resize, payload.as_bytes()) {
                                s.cols = cols;
                                s.rows = rows;
                                s.parser.screen_mut().set_size(rows, cols);
                                s.status = if s.active {
                                    "终端尺寸已请求（协议无独立尺寸确认）"
                                } else {
                                    "已选择下一 Shell 的终端尺寸"
                                }
                                .into();
                            } else {
                                s.status = "终端尺寸未发送".into();
                            }
                        }
                    }
                });
                ui.add_enabled_ui(active, |ui| {
                    ui.horizontal(|ui| {
                        let response = ui.add(
                            egui::TextEdit::singleline(&mut input)
                                .desired_width(500.)
                                .char_limit(4096)
                                .hint_text("命令（按发送执行）"),
                        );
                        if ui.button("发送 ↵").clicked()
                            || (response.lost_focus()
                                && ui.input(|i| i.key_pressed(egui::Key::Enter)))
                        {
                            send = Some(format!("{}\r", input));
                            input.clear();
                        }
                    });
                    ui.horizontal(|ui| {
                        for (label, bytes) in [
                            ("Ctrl+C", "\x03"),
                            ("Tab", "\t"),
                            ("↑", "\x1b[A"),
                            ("↓", "\x1b[B"),
                            ("Esc", "\x1b"),
                        ] {
                            if ui.button(label).clicked() {
                                send = Some(bytes.into());
                            }
                        }
                    });
                });
            });
        self.submit(epoch, input, send);
        if !visible {
            self.detach();
        } else if end {
            self.end();
        } else if open {
            self.open();
        }
        ctx.request_repaint_after(std::time::Duration::from_millis(50));
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exited_shell_cannot_receive_a_stale_ui_submission_or_restore_its_draft() {
        let t = Terminal::default();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
            s.input = "old draft".into();
        }
        let epoch = t.lock().epoch;
        t.receive(&Frame::encode(FrameType::Exit, 7, b"{}"));
        t.submit(epoch, "old draft".into(), Some("old command\r".into()));
        let s = t.lock();
        assert!(s.input.is_empty());
        assert!(!s.active);
        assert!(s.status.contains("未发送旧输入"));
    }
    #[test]
    fn temporary_backpressure_waits_but_timeout_and_disconnect_stop() {
        use std::time::Duration;
        assert_eq!(send_gate(true, 512 * 1024, Duration::ZERO), SendGate::Send);
        assert_eq!(
            send_gate(true, 512 * 1024 + 1, Duration::from_millis(500)),
            SendGate::Wait
        );
        assert_eq!(
            send_gate(true, 512 * 1024 + 1, Duration::from_secs(2)),
            SendGate::Stop
        );
        assert_eq!(send_gate(false, 0, Duration::ZERO), SendGate::Stop);
    }
    #[test]
    fn optional_terminal_cancellation_cannot_cancel_desktop() {
        let desktop = tokio_util::sync::CancellationToken::new();
        let terminal = desktop.child_token();
        terminal.cancel();
        assert!(!desktop.is_cancelled());
        let replacement = desktop.child_token();
        desktop.cancel();
        assert!(replacement.is_cancelled());
    }
    #[test]
    fn output_is_session_scoped_and_late_open_is_not_activated() {
        let t = Terminal::default();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
        }
        t.receive(&Frame::encode(FrameType::Stdout, 8, b"secret"));
        assert!(t.lock().parser.screen().contents().is_empty());
        t.receive(&Frame::encode(FrameType::Stdout, 7, b"ok"));
        assert_eq!(t.lock().parser.screen().contents(), "ok");
        t.detach();
        t.receive(&Frame::encode(FrameType::OpenResp, 7, br#"{"code":0}"#));
        assert!(!t.lock().active);
    }
    /// 关窗口只是离开：句柄留着，下次打开接回同一个 Shell。
    fn wired() -> (Terminal, mpsc::Receiver<Vec<u8>>) {
        let terminal = Terminal::default();
        let (tx, rx) = mpsc::channel(8);
        terminal.lock().tx = Some(tx);
        (terminal, rx)
    }
    #[test]
    fn closing_the_window_detaches_and_keeps_the_shell() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
        }
        t.detach();
        let s = t.lock();
        assert_eq!(s.handle, 7, "the handle survives so the shell can be reattached");
        assert!(!s.active);
        assert!(!s.visible);
        drop(s);
        let frame = Frame::parse(&rx.try_recv().expect("detach frame")).unwrap();
        assert_eq!(frame.kind, FrameType::Detach);
        assert_eq!(frame.session, 7);
    }
    #[test]
    fn reopening_reuses_the_handle_and_clears_the_old_screen() {
        let (t, _rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = false;
            s.parser.process(b"stale screen");
            assert_eq!(s.parser.screen().contents(), "stale screen");
            // 打开前要判断通道是否就绪；这里只验证句柄与重放缓冲那一段。
            assert!(Terminal::prepare_open(&mut s), "an existing handle means reattach");
            assert_eq!(s.handle, 7);
            assert!(s.parser.screen().contents().is_empty());
        }
        // 没有句柄时生成一个新的非零句柄。
        let fresh = Terminal::default();
        let mut fresh = fresh.lock();
        assert!(!Terminal::prepare_open(&mut fresh));
        assert_ne!(fresh.handle, 0);
    }
    #[test]
    fn ending_the_shell_clears_the_handle_and_sends_close() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
        }
        t.end();
        assert_eq!(t.lock().handle, 0);
        let frame = Frame::parse(&rx.try_recv().expect("close frame")).unwrap();
        assert_eq!(frame.kind, FrameType::Close);
        assert_eq!(frame.session, 7);
    }
    #[test]
    fn ansi_and_unicode_are_rendered_not_executed() {
        let t = Terminal::default();
        {
            let mut s = t.lock();
            s.handle = 1;
            s.active = true;
        }
        t.receive(&Frame::encode(
            FrameType::Stdout,
            1,
            "\x1b[31m中文\x1b[0m\r\n$ ".as_bytes(),
        ));
        assert!(t.lock().parser.screen().contents().contains("中文"));
        t.disconnected();
        assert!(t.lock().parser.screen().contents().is_empty());
    }
}
