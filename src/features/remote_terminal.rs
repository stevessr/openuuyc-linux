//! Explicit per-connection terminal client; no command/history is logged or saved.
use crate::features::host::terminal::{Frame, FrameType};
use bytes::Bytes;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use webrtc::data_channel::{RTCDataChannel, data_channel_state::RTCDataChannelState};
pub(crate) const CHANNEL: &str = "OPENUUYC_TERMINAL_V1";
/// 列表请求的编号从这段高位起。主控分配的 Shell 编号是小整数，两者不能
/// 混在一起：列表回复按请求编号过滤，混了就会被当成某个 Shell 的输出。
const LIST_REQUEST_BASE: u32 = 0x4000_0000;
/// 远端会话列表最多显示这么多行，远端给再多也不无限增长。
const MAX_LISTED: usize = 512;
/// 列表请求发出后这么久还没有回复，就当它丢了；否则一次丢包会让「正在
/// 刷新…」永远停在那里，后面的刷新也都被当成重复请求挡掉。
const LIST_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
/// 远端会话列表里的一行。文字都来自远端，只用于显示。
#[derive(Clone, Debug, PartialEq, Eq)]
struct RemoteSession {
    id: u32,
    name: String,
    shell: String,
    state: String,
    created_ms: i64,
    last_active_ms: i64,
}
/// 终端模拟器持有的屏幕网格。它不是每帧生成的图片快照：收到 Shell 的
/// 原始字节后更新单元格，界面只在有变化时重绘。
#[derive(Clone, Debug, Default)]
struct TerminalGrid {
    cols: u16,
    rows: u16,
    cursor: (u16, u16),
    cursor_visible: bool,
    cells: Vec<GridCell>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct GridCell {
    text: String,
    fg: TerminalColor,
    bg: TerminalColor,
    bold: bool,
    dim: bool,
    italic: bool,
    underline: bool,
    inverse: bool,
    wide: bool,
    continuation: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum TerminalColor {
    #[default]
    Default,
    Indexed(u8),
    Rgb(u8, u8, u8),
}

impl TerminalGrid {
    fn empty(cols: u16, rows: u16) -> Self {
        Self {
            cols,
            rows,
            cursor: (0, 0),
            cursor_visible: true,
            cells: vec![GridCell::default(); usize::from(cols) * usize::from(rows)],
        }
    }

    fn ensure_size(&mut self, cols: u16, rows: u16) {
        if self.cols != cols || self.rows != rows {
            *self = Self::empty(cols, rows);
        }
    }

    /// 从 vt100 的屏幕模型增量更新网格；只在收到输出/改尺寸时调用。
    #[cfg(test)]
    fn from_parser(parser: &vt100::Parser) -> Self {
        let mut grid = Self::default();
        grid.update_from_parser(parser);
        grid
    }

    fn update_from_parser(&mut self, parser: &vt100::Parser) {
        let screen = parser.screen();
        let (rows, cols) = screen.size();
        self.ensure_size(cols, rows);
        let (cursor_row, cursor_col) = screen.cursor_position();
        self.cursor = (cursor_col, cursor_row);
        self.cursor_visible = !screen.hide_cursor();
        for row in 0..rows {
            for col in 0..cols {
                let Some(cell) = screen.cell(row, col) else {
                    continue;
                };
                let index = usize::from(row) * usize::from(cols) + usize::from(col);
                let Some(target) = self.cells.get_mut(index) else {
                    continue;
                };
                target.text.clear();
                target.text.push_str(cell.contents());
                target.fg = terminal_color(cell.fgcolor());
                target.bg = terminal_color(cell.bgcolor());
                target.bold = cell.bold();
                target.dim = cell.dim();
                target.italic = cell.italic();
                target.underline = cell.underline();
                target.inverse = cell.inverse();
                target.wide = cell.is_wide();
                target.continuation = cell.is_wide_continuation();
            }
        }
    }

    fn update_from_ghostty(&mut self, rendered: crate::features::ghostty_vt::ScreenState) {
        self.ensure_size(rendered.cols, rendered.rows);
        self.cursor = rendered.cursor;
        self.cursor_visible = rendered.cursor_visible;
        for (target, source) in self.cells.iter_mut().zip(rendered.cells) {
            target.text = source.text;
            target.fg = match source.fg {
                crate::features::ghostty_vt::CellColor::None => TerminalColor::Default,
                crate::features::ghostty_vt::CellColor::Indexed(index) => {
                    TerminalColor::Indexed(index)
                }
                crate::features::ghostty_vt::CellColor::Rgb(r, g, b) => {
                    TerminalColor::Rgb(r, g, b)
                }
            };
            target.bg = match source.bg {
                crate::features::ghostty_vt::CellColor::None => TerminalColor::Default,
                crate::features::ghostty_vt::CellColor::Indexed(index) => {
                    TerminalColor::Indexed(index)
                }
                crate::features::ghostty_vt::CellColor::Rgb(r, g, b) => {
                    TerminalColor::Rgb(r, g, b)
                }
            };
            target.bold = source.bold;
            target.dim = source.dim;
            target.italic = source.italic;
            target.underline = source.underline;
            target.inverse = source.inverse;
            target.wide = source.wide;
            target.continuation = source.continuation;
        }
    }

    fn update_from_state(&mut self, state: &State) {
        if let Some(ghostty) = state.ghostty.as_ref() {
            let rendered = ghostty.read_state();
            if rendered.cols != 0 && rendered.rows != 0 {
                self.update_from_ghostty(rendered);
                return;
            }
        }
        self.update_from_parser(&state.parser);
    }
}

#[cfg(test)]
fn grid_from_state(state: &State) -> TerminalGrid {
    let mut grid = TerminalGrid::default();
    grid.update_from_state(state);
    grid
}

impl Terminal {
    /// 收到输出或改尺寸后更新屏幕网格，并且只请求一次重绘；没有变化时
    /// UI 不会因为定时器空转。
    fn refresh_grid(state: &mut State) {
        if let Ok(mut grid) = state.grid.lock() {
            grid.update_from_state(state);
        }
        Self::request_repaint(state);
    }

    fn request_repaint(state: &State) {
        if let Some(ctx) = state.ctx.as_ref() {
            ctx.request_repaint();
        }
    }
}

fn terminal_color(color: vt100::Color) -> TerminalColor {
    match color {
        vt100::Color::Default => TerminalColor::Default,
        vt100::Color::Idx(index) => TerminalColor::Indexed(index),
        vt100::Color::Rgb(r, g, b) => TerminalColor::Rgb(r, g, b),
    }
}

const DEFAULT_FG: egui::Color32 = egui::Color32::from_rgb(220, 224, 232);
const DEFAULT_BG: egui::Color32 = egui::Color32::from_rgb(12, 14, 18);
const LOCAL_ECHO_FG: egui::Color32 = egui::Color32::from_rgb(170, 210, 255);

fn map_terminal_color(color: TerminalColor, default: egui::Color32) -> egui::Color32 {
    match color {
        TerminalColor::Default => default,
        TerminalColor::Indexed(index) => terminal_palette(index),
        TerminalColor::Rgb(r, g, b) => egui::Color32::from_rgb(r, g, b),
    }
}

fn terminal_palette(index: u8) -> egui::Color32 {
    const BASE: [[u8; 3]; 16] = [
        [0, 0, 0],
        [205, 49, 49],
        [13, 188, 121],
        [229, 229, 16],
        [36, 114, 200],
        [188, 63, 188],
        [17, 168, 205],
        [229, 229, 229],
        [102, 102, 102],
        [241, 76, 76],
        [35, 209, 139],
        [245, 245, 67],
        [59, 142, 234],
        [214, 112, 214],
        [41, 184, 219],
        [255, 255, 255],
    ];
    if usize::from(index) < BASE.len() {
        let [r, g, b] = BASE[usize::from(index)];
        return egui::Color32::from_rgb(r, g, b);
    }
    if index < 232 {
        let cube = index - 16;
        let component = |value: u8| if value == 0 { 0 } else { 55 + value * 40 };
        return egui::Color32::from_rgb(
            component(cube / 36),
            component((cube % 36) / 6),
            component(cube % 6),
        );
    }
    let gray = 8 + (index - 232) * 10;
    egui::Color32::from_rgb(gray, gray, gray)
}

fn cell_colors(cell: &GridCell) -> (egui::Color32, egui::Color32) {
    let mut foreground = map_terminal_color(cell.fg, DEFAULT_FG);
    let mut background = map_terminal_color(cell.bg, DEFAULT_BG);
    if cell.dim {
        foreground = foreground.gamma_multiply(0.72);
    }
    if cell.inverse {
        std::mem::swap(&mut foreground, &mut background);
    }
    (foreground, background)
}

/// Map egui keyboard events to the bytes a remote shell expects. Printable
/// text arrives as `Event::Text`; this covers control keys, cursor keys and
/// the small set of editing keys a terminal must forward.
fn terminal_key(key: egui::Key, modifiers: egui::Modifiers) -> Option<&'static [u8]> {
    if modifiers.ctrl {
        return match key {
            egui::Key::A => Some(b"\x01"),
            egui::Key::C => Some(b"\x03"),
            egui::Key::D => Some(b"\x04"),
            egui::Key::E => Some(b"\x05"),
            egui::Key::K => Some(b"\x0b"),
            egui::Key::L => Some(b"\x0c"),
            egui::Key::R => Some(b"\x12"),
            egui::Key::U => Some(b"\x15"),
            egui::Key::W => Some(b"\x17"),
            egui::Key::Z => Some(b"\x1a"),
            _ => None,
        };
    }
    match key {
        egui::Key::Enter => Some(b"\r"),
        egui::Key::Backspace => Some(b"\x7f"),
        egui::Key::Tab => Some(b"\t"),
        egui::Key::Escape => Some(b"\x1b"),
        egui::Key::ArrowUp => Some(b"\x1b[A"),
        egui::Key::ArrowDown => Some(b"\x1b[B"),
        egui::Key::ArrowRight => Some(b"\x1b[C"),
        egui::Key::ArrowLeft => Some(b"\x1b[D"),
        egui::Key::Home => Some(b"\x1b[H"),
        egui::Key::End => Some(b"\x1b[F"),
        egui::Key::Insert => Some(b"\x1b[2~"),
        egui::Key::Delete => Some(b"\x1b[3~"),
        egui::Key::PageUp => Some(b"\x1b[5~"),
        egui::Key::PageDown => Some(b"\x1b[6~"),
        _ => None,
    }
}

/// ui() 每帧读一次的状态，避免在界面闭包里反复加锁。
struct View {
    visible: bool,
    status: String,
    /// 本地已发送、但远端回显还没回来的输入；在光标处先画出来。
    pending_input: String,
    active: bool,
    pending: bool,
    epoch: u64,
    handle: u32,
    sessions: Vec<RemoteSession>,
    list_error: String,
    listing: bool,
    /// 首次清单为空时自动新建一个 Shell。
    auto_open: bool,
}
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
    /// 已经发出 OPEN、还没收到回复。只有这时才采用回复里的会话编号。
    open_sent: bool,
    /// 那次 OPEN 是不是新建 Shell（帧头会话号 0，编号由远端分配）。
    open_new: bool,
    visible: bool,
    status: String,
    started: Option<std::time::Instant>,
    parser: vt100::Parser,
    ghostty: Option<crate::features::ghostty_vt::Screen>,
    /// 终端自己的屏幕网格；输出线程更新它，UI 只读它来画。
    grid: Arc<Mutex<TerminalGrid>>,
    /// 本地即时回显：已发送但远端还没回显的输入。
    pending_input: String,
    /// 本次打开窗口后的清单已经回来过；避免每次刷新都自动新建。
    auto_open_done: bool,
    auto_open: bool,
    /// UI 第一次绘制时拿到，之后收到输出只请求一次重绘，不做 60fps 轮询。
    ctx: Option<egui::Context>,
    input: String,
    /// 远端已经开着的会话，来自 ListSessionsResp。
    sessions: Vec<RemoteSession>,
    /// 在飞的列表请求编号；0 表示没有。
    list_request: u32,
    /// 列表请求编号的计数器，保证每次请求都不同。
    list_seq: u32,
    list_error: String,
    last_list: Option<std::time::Instant>,
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
            open_sent: false,
            open_new: false,
            visible: false,
            started: None,
            status: "此设备的终端通道尚未就绪".into(),
            parser: vt100::Parser::new(24, 80, 500),
            ghostty: crate::features::ghostty_vt::Screen::new(80, 24),
            grid: Arc::new(Mutex::new(TerminalGrid::empty(80, 24))),
            pending_input: String::new(),
            auto_open_done: false,
            auto_open: false,
            ctx: None,
            input: String::new(),
            sessions: Vec::new(),
            list_request: 0,
            list_seq: 0,
            list_error: String::new(),
            last_list: None,
        })))
    }
}
impl Terminal {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn send(state: &State, kind: FrameType, payload: &[u8]) -> bool {
        Self::send_session(state, kind, state.handle, payload)
    }
    /// 按指定会话号发一帧：列表请求用请求编号，关掉远端刚分配的 Shell 时
    /// 用远端给的编号，都不能借用当前 Shell 句柄。
    fn send_session(state: &State, kind: FrameType, session: u32, payload: &[u8]) -> bool {
        state
            .tx
            .as_ref()
            .is_some_and(|tx| tx.try_send(Frame::encode(kind, session, payload)).is_ok())
    }
    pub(crate) fn bind(&self, channel: &Arc<RTCDataChannel>) {
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(64);
        {
            let mut s = self.lock();
            s.tx = Some(tx);
            s.channel = Some(Arc::downgrade(channel));
            // 新通道上还没有列表：丢掉旧连接的会话与错误，下一次 ui()
            // 会立刻重新拉一次。
            s.sessions.clear();
            s.list_request = 0;
            s.list_error.clear();
            s.last_list = None;
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
        // 列表回复带的是列表请求编号，不是 Shell 句柄，必须在句柄过滤之前
        // 认出来，否则每次刷新都会被当成别的会话的数据丢掉。
        if frame.kind == FrameType::ListSessionsResp {
            Self::receive_list(&mut s, &frame);
            Self::request_repaint(&s);
            return;
        }
        if frame.kind == FrameType::Error && s.list_request != 0 && frame.session == s.list_request
        {
            // 列表请求的失败只影响列表，不是当前 Shell 出事。
            s.list_request = 0;
            s.list_error = Self::remote_message(&frame.payload, "会话列表请求失败");
            return;
        }
        if frame.kind == FrameType::OpenResp {
            // 新建 Shell 时远端分配的编号和本地请求的 0 不一样，不能按
            // 当前句柄过滤，否则永远等不到回复。
            Self::receive_open(&mut s, &frame);
            Self::request_repaint(&s);
            return;
        }
        if frame.session != s.handle {
            return;
        }
        match frame.kind {
            FrameType::Stdout if s.active => {
                s.parser.process(&frame.payload);
                if let Some(ghostty) = s.ghostty.as_mut() {
                    ghostty.process(&frame.payload);
                }
                // 远端已经回显/重绘，以远端屏幕为准。
                s.pending_input.clear();
                Self::refresh_grid(&mut s);
            }
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
                    Self::remote_message(&frame.payload, "终端请求失败")
                };
            }
            _ => {}
        }
        Self::request_repaint(&s);
    }
    /// 存下一次会话列表。只认自己发出的那个请求的回复；没有请求在飞时
    /// 到达的是过期回复，直接丢掉，免得两次刷新互相覆盖。
    fn receive_list(s: &mut State, frame: &Frame) {
        if s.list_request == 0 {
            return;
        }
        // 官方主控可能不回请求编号（用 0），两种都收。
        if frame.session != s.list_request && frame.session != 0 {
            return;
        }
        s.list_request = 0;
        match Self::parse_sessions(&frame.payload) {
            Ok(sessions) => {
                s.sessions = sessions;
                s.list_error.clear();
                if !s.auto_open_done {
                    s.auto_open_done = true;
                    // 远端没有任何可接回的 Shell：点开窗口就直接新建，
                    // 不让用户先看到一个空清单。
                    let has_running =
                        s.sessions.iter().any(|session| session.state != "exited");
                    s.auto_open =
                        !has_running && !s.active && !s.pending && s.handle == 0;
                }
            }
            // 解析失败保留上一次的列表，只报错，免得界面突然空掉。
            Err(error) => {
                s.list_error = error;
                s.auto_open_done = true;
                s.auto_open = false;
            }
        }
    }
    /// 处理 OPEN 的回复。新建 Shell 用 0 请求，远端在回复里给出它分配的
    /// 编号（字段拼写不统一，见 [`Self::response_handle`]）；之后的输出、
    /// 输入、尺寸、离开都按这个编号发，不能再用本地猜的。
    fn receive_open(s: &mut State, frame: &Frame) {
        if !s.open_sent {
            // 没有等着的 OPEN：重复或无关的回复，忽略。
            return;
        }
        let value = serde_json::from_slice::<serde_json::Value>(&frame.payload).ok();
        let ok = value.as_ref().and_then(|v| v["code"].as_i64()) == Some(0);
        s.open_sent = false;
        let new_shell = std::mem::take(&mut s.open_new);
        if !ok {
            if s.pending {
                s.pending = false;
                s.active = false;
                s.status = Self::remote_message(&frame.payload, "远端拒绝终端");
            }
            return;
        }
        let id = Self::response_handle(value.as_ref(), frame.session);
        if !s.pending {
            // 超时或离开之后才到的回复：不激活它，但新建的 Shell 要按远端
            // 实际分配的编号关掉，别让它留在远端空跑。接回已有会话的回复
            // 只忽略——那是用户想留着的东西，不能替他结束。
            if new_shell {
                Self::send_session(s, FrameType::Close, id, &[]);
            }
            return;
        }
        s.pending = false;
        s.handle = id;
        s.active = true;
        s.status = "远端 Shell 已打开".into();
    }
    /// 解析会话列表回复：JSON 数组，每项一个会话对象。字段名按主控的
    /// 实现与官方客户端的读法取，`session_id` 可能是数字也可能是字符串。
    fn parse_sessions(payload: &[u8]) -> Result<Vec<RemoteSession>, String> {
        let value = serde_json::from_slice::<serde_json::Value>(payload)
            .map_err(|_| "会话列表格式无法解析".to_owned())?;
        let Some(items) = value.as_array() else {
            return Err("会话列表不是数组".to_owned());
        };
        let sessions: Vec<_> = items
            .iter()
            .filter_map(Self::parse_session)
            .take(MAX_LISTED)
            .collect();
        if sessions.is_empty() && !items.is_empty() {
            return Err("会话列表里没有可识别的会话编号".to_owned());
        }
        Ok(sessions)
    }
    fn parse_session(item: &serde_json::Value) -> Option<RemoteSession> {
        Some(RemoteSession {
            id: Self::session_id(item)?,
            name: Self::field_text(item, "name", 64, "未命名"),
            shell: Self::field_text(item, "shell", 128, "未知 Shell"),
            state: Self::field_text(item, "state", 32, "unknown"),
            created_ms: item
                .get("created_at_ms")
                .and_then(serde_json::Value::as_i64)
                .unwrap_or(0),
            last_active_ms: item
                .get("last_active_ms")
                .and_then(serde_json::Value::as_i64)
                .unwrap_or(0),
        })
    }
    /// 列表里的会话编号：官方实现写成数字，也有写成十进制字符串的。
    /// 0 也留着：官方 Windows 主控会用它，只是不能拿它去接入。
    fn session_id(item: &serde_json::Value) -> Option<u32> {
        let value = item.get("session_id")?;
        let number = value
            .as_u64()
            .or_else(|| value.as_str()?.trim().parse::<u64>().ok())?;
        u32::try_from(number).ok()
    }
    /// 列表行能不能点「接入」。编号 0 在 OPEN 里表示「新建」，拿它接入只会
    /// 再开一个 Shell，所以不提供；正在用的和已经退出的会话也不接。
    fn attachable(session: &RemoteSession, active: bool, pending: bool, handle: u32) -> bool {
        session.id != 0 && session.state != "exited" && !active && !pending && session.id != handle
    }
    fn field_text(item: &serde_json::Value, key: &str, max: usize, fallback: &str) -> String {
        item.get(key)
            .and_then(serde_json::Value::as_str)
            .map(|text| Self::clean_text(text, max))
            .filter(|text| !text.is_empty())
            .unwrap_or_else(|| fallback.to_owned())
    }
    /// 列表文字来自远端，只用于显示：去掉控制字符并截断，免得换行或转义
    /// 把界面排版打乱。
    fn clean_text(text: &str, max: usize) -> String {
        text.chars().filter(|c| !c.is_control()).take(max).collect()
    }
    /// 远端 Error/OpenResp 里的说明文字，同样清掉控制字符。
    fn remote_message(payload: &[u8], fallback: &str) -> String {
        serde_json::from_slice::<serde_json::Value>(payload)
            .ok()
            .and_then(|value| {
                value["message"]
                    .as_str()
                    .map(|text| Self::clean_text(text, 500))
            })
            .filter(|text| !text.is_empty())
            .unwrap_or_else(|| fallback.to_owned())
    }
    /// 从 OPEN 的回复里取远端分配的会话编号。官方主控用的字段名不确定，
    /// 几个可能的拼写都试（和 PICO 客户端一致）；都没有就退回帧头里的
    /// 会话号——接回已有会话时远端会原样带回。
    ///
    /// 0 是合法编号：官方 Windows 主控会用它。本地只在「新建」时发 0，
    /// 那时还没有 Shell 在跑，所以两者不冲突；显式字段里的 0 优先于帧头。
    fn response_handle(value: Option<&serde_json::Value>, fallback: u32) -> u32 {
        for key in ["session_id", "resp_session_id", "respSessionId", "handle"] {
            let Some(field) = value.and_then(|v| v.get(key)) else {
                continue;
            };
            let parsed = field.as_u64().or_else(|| {
                field
                    .as_str()
                    .and_then(|text| text.trim().parse::<u64>().ok())
            });
            if let Some(id) = parsed.and_then(|number| u32::try_from(number).ok()) {
                return id;
            }
        }
        fallback
    }
    /// 远端给的毫秒时间戳，写成本地时间；解析不了就明说。
    fn stamp(ms: i64) -> String {
        if ms <= 0 {
            return "未知".into();
        }
        chrono::DateTime::from_timestamp_millis(ms)
            .map(|at| {
                at.with_timezone(&chrono::Local)
                    .format("%m-%d %H:%M")
                    .to_string()
            })
            .unwrap_or_else(|| "未知".into())
    }
    pub(crate) fn show(&self) {
        let mut s = self.lock();
        s.visible = true;
        s.auto_open_done = false;
        s.auto_open = false;
        // 打开窗口就先拉一次列表：用户点开它通常是想看看远端有哪些 Shell。
        Self::request_list_locked(&mut s);
        Self::request_repaint(&s);
    }
    /// 离开这个 Shell，但不结束它：只发 DETACH，和 tmux detach 一样。句柄
    /// 留着，下次打开按同一个句柄接回原来的 Shell（远端会重放当前屏幕）。
    pub(crate) fn detach(&self) {
        let mut s = self.lock();
        Self::detach_locked(&mut s, false);
    }
    /// 窗口里点「离开 Shell」：和关窗口一样发 DETACH，但窗口留着，用户
    /// 可以马上从列表里接回这个或别的会话。
    fn leave(&self) {
        let mut s = self.lock();
        Self::detach_locked(&mut s, true);
        Self::request_list_locked(&mut s);
    }
    fn detach_locked(s: &mut State, keep_window: bool) {
        s.epoch = s.epoch.wrapping_add(1);
        if s.active || s.pending {
            Self::send(s, FrameType::Detach, &[]);
        }
        s.active = false;
        s.pending = false;
        if !keep_window {
            s.visible = false;
        }
        s.input.clear();
        s.pending_input.clear();
        s.status = if s.handle != 0 {
            "已离开远端 Shell，它仍在远端运行；再打开会接回同一个 Shell".into()
        } else {
            "已离开远端终端".into()
        };
        Self::request_repaint(s);
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
        let (cols, rows) = (s.cols, s.rows);
        if let Some(ghostty) = s.ghostty.as_mut() {
            ghostty.reset();
            ghostty.resize(cols, rows);
        }
        if let Ok(mut grid) = s.grid.lock() {
            *grid = TerminalGrid::empty(cols, rows);
        }
        s.status = "已结束远端 Shell".into();
        Self::request_repaint(&s);
    }
    pub(crate) fn disconnected(&self) {
        let mut s = self.lock();
        s.epoch = s.epoch.wrapping_add(1);
        let was_connected = s.tx.is_some() || s.channel.is_some();
        s.tx = None;
        s.channel = None;
        s.active = false;
        s.pending = false;
        s.open_sent = false;
        s.open_new = false;
        s.input.clear();
        s.parser = vt100::Parser::new(s.rows, s.cols, 500);
        let (cols, rows) = (s.cols, s.rows);
        if let Some(ghostty) = s.ghostty.as_mut() {
            ghostty.reset();
            ghostty.resize(cols, rows);
        }
        if let Ok(mut grid) = s.grid.lock() {
            *grid = TerminalGrid::empty(cols, rows);
        }
        // 通道没了，列表也不会再有回复；留着旧列表只会显示过期的会话。
        s.sessions.clear();
        s.list_request = 0;
        s.list_error.clear();
        s.last_list = None;
        if was_connected {
            s.status = "终端连接已结束".into();
        }
        Self::request_repaint(&s);
    }
    /// 打开远端 Shell。`session` 为 0 表示让远端新建并分配编号；非 0 表示
    /// 接回那个编号的会话（列表里的「接入」和「接回上次 Shell」走这条路）。
    fn open(&self, session: u32) {
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
            Self::request_repaint(&s);
            return;
        }
        s.handle = session;
        s.auto_open = false;
        s.epoch = s.epoch.wrapping_add(1);
        let reattach = Self::prepare_open(&mut s);
        // 不再自带名字：OpenUUYC 主控按登录拒绝重名，开第二个 Shell 会被
        // 拒；官方主控自己分配名字。
        let payload = serde_json::json!({"cols":s.cols,"rows":s.rows}).to_string();
        if Self::send(&s, FrameType::Open, payload.as_bytes()) {
            s.pending = true;
            s.open_sent = true;
            s.open_new = !reattach;
            s.started = Some(std::time::Instant::now());
            s.status = if reattach {
                "正在接回上次的 Shell…".into()
            } else {
                "等待远端授权并创建 Shell…".into()
            };
        } else {
            s.status = "打开请求未发送（通道拥塞或断开）".into();
        }
        Self::request_repaint(&s);
    }
    /// 打开前把状态准备好：有句柄就按它接回上次的 Shell（远端保留着它并重
    /// 放屏幕），没有就用 0 请求，让远端分配编号。重放的是一整屏，旧的模型
    /// 内容要丢掉，否则会接在后面重复一遍。返回是不是接回。
    fn prepare_open(s: &mut State) -> bool {
        let reattach = s.handle != 0;
        s.parser = vt100::Parser::new(s.rows, s.cols, 500);
        if let Some(ghostty) = s.ghostty.as_mut() {
            ghostty.reset();
            ghostty.resize(s.cols, s.rows);
        }
        if let Ok(mut grid) = s.grid.lock() {
            *grid = TerminalGrid::empty(s.cols, s.rows);
        }
        s.pending_input.clear();
        Self::request_repaint(s);
        s.input.clear();
        reattach
    }
    /// 向远端要一次会话列表。列表请求的编号不能用 Shell 句柄：回复按请求
    /// 编号过滤，混在一起会把列表当成 Shell 输出。
    fn request_list(&self) {
        let mut s = self.lock();
        Self::request_list_locked(&mut s);
    }
    fn request_list_locked(s: &mut State) {
        if s.list_request != 0 {
            // 官方主控没有单独的列表 ACK；请求丢了就超时作废，用户可以
            // 再点刷新，而不是永远卡在「正在刷新」。
            if s.last_list.is_some_and(|at| at.elapsed() >= LIST_TIMEOUT) {
                s.list_request = 0;
                s.list_error = "会话列表请求超时".into();
            } else {
                return;
            }
        }
        if !s
            .channel
            .as_ref()
            .and_then(|c| c.upgrade())
            .is_some_and(|c| c.ready_state() == RTCDataChannelState::Open)
        {
            s.list_error = "终端通道尚未就绪，无法刷新会话列表".into();
            s.last_list = Some(std::time::Instant::now());
            return;
        }
        let Some(id) = Self::next_list_request(s) else {
            return;
        };
        // 发送成功与否都记时间，免得拥塞时每一帧都重试。
        s.last_list = Some(std::time::Instant::now());
        if Self::send_session(s, FrameType::ListSessionsReq, id, &[]) {
            s.list_request = id;
            s.list_error.clear();
        } else {
            s.list_error = "会话列表请求未发送（通道拥塞或断开）".into();
        }
    }
    /// 换一个列表请求编号：从高位段里取，跳过当前 Shell 句柄。
    fn next_list_request(s: &mut State) -> Option<u32> {
        for _ in 0..4 {
            s.list_seq = s.list_seq.wrapping_add(1);
            let id = LIST_REQUEST_BASE | (s.list_seq & 0x3fff_ffff);
            if id != s.handle {
                return Some(id);
            }
        }
        None
    }
    /// 结束列表里的任意一个远端会话。当前 Shell 走和「结束 Shell」相同的
    /// 清理；其它会话只发 CLOSE，不碰当前正在看的 Shell。
    fn end_session(&self, session: u32) {
        let mut s = self.lock();
        let current = s.handle == session && (s.active || s.pending);
        if current {
            s.epoch = s.epoch.wrapping_add(1);
            Self::send(&s, FrameType::Close, &[]);
            s.active = false;
            s.pending = false;
            s.handle = 0;
            s.input.clear();
            s.pending_input.clear();
            let (cols, rows) = (s.cols, s.rows);
            s.parser = vt100::Parser::new(rows, cols, 500);
            if let Some(ghostty) = s.ghostty.as_mut() {
                ghostty.reset();
                ghostty.resize(cols, rows);
            }
            if let Ok(mut grid) = s.grid.lock() {
                *grid = TerminalGrid::empty(cols, rows);
            }
            s.status = "已结束远端 Shell".into();
        } else {
            if Self::send_session(&s, FrameType::Close, session, &[]) {
                s.status = format!("已请求结束远端会话 {session}");
                Self::request_list_locked(&mut s);
            } else {
                s.status = "结束请求未发送（通道拥塞或断开）".into();
            }
        }
        Self::request_repaint(&s);
    }

    /// 直接发送原始按键字节。终端窗口不再用单行输入框：点击网格后键盘
    /// 事件按终端语义逐键发出，回显和光标由远端 Shell 驱动。
    fn send_bytes(&self, epoch: u64, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        let mut s = self.lock();
        if s.epoch != epoch {
            s.status = "Shell 状态已变化，未发送旧输入".into();
            Self::request_repaint(&s);
            return;
        }
        if !s.active {
            s.status = "终端尚未确认就绪，未发送输入".into();
            Self::request_repaint(&s);
            return;
        }
        Self::track_local_input(&mut s, bytes);
        Self::request_repaint(&s);
        if !Self::send(&s, FrameType::Stdin, bytes) {
            s.pending_input.clear();
            s.status = "终端输入未发送（通道拥塞、Shell 退出或断开）".into();
            Self::request_repaint(&s);
        }
    }

    /// 本地即时回显只维护“还没看到远端回显”的那一小段。远端一旦有输出，
    /// 以远端屏幕为准，避免本地与远端各画一遍。
    fn track_local_input(state: &mut State, bytes: &[u8]) {
        if bytes == b"\r" || bytes == b"\n" {
            state.pending_input.clear();
            return;
        }
        if bytes == b"\x7f" {
            state.pending_input.pop();
            return;
        }
        // 光标键、Tab、Ctrl+字母等会改变远端光标/行内容，本地不猜，
        // 交给远端回显。
        if bytes.first() == Some(&0x1b)
            || bytes == b"\t"
            || (bytes.len() == 1 && bytes[0] < 0x20)
        {
            state.pending_input.clear();
            return;
        }
        if let Ok(text) = std::str::from_utf8(bytes) {
            for character in text.chars().filter(|character| !character.is_control()) {
                state.pending_input.push(character);
            }
        }
        if state.pending_input.chars().count() > 4096 {
            state.pending_input = state.pending_input.chars().take(4096).collect();
        }
    }

    /// 按终端自己的网格绘制。SSH 只传原始字节，屏幕状态由终端模拟器维护；
    /// 这里读的就是那份网格，不再每帧生成整屏快照。返回本帧按下的原始按键，
    /// 由调用者在释放网格锁之后再发给远端。
    fn terminal_grid(
        ui: &mut egui::Ui, grid: &TerminalGrid, pending: &str,
    ) -> Vec<Vec<u8>> {
        const FONT_SIZE: f32 = 14.0;
        let font = egui::FontId::monospace(FONT_SIZE);
        let cell_width = ui.fonts_mut(|fonts| fonts.glyph_width(&font, 'W')).max(1.0);
        let cell_height = ui.fonts_mut(|fonts| fonts.row_height(&font)).max(1.0);
        let cols = f32::from(grid.cols);
        let rows = f32::from(grid.rows);
        let (rect, response) = ui.allocate_exact_size(
            egui::vec2(cols * cell_width, rows * cell_height),
            egui::Sense::click(),
        );
        let painter = ui.painter().clone();
        painter.rect_filled(rect, 2.0, DEFAULT_BG);
        let cell_rect = |col: u16, row: u16, width: f32| {
            egui::Rect::from_min_size(
                rect.min + egui::vec2(f32::from(col) * cell_width, f32::from(row) * cell_height),
                egui::vec2(width, cell_height),
            )
        };
        for row in 0..grid.rows {
            for col in 0..grid.cols {
                let index = usize::from(row) * usize::from(grid.cols) + usize::from(col);
                let Some(cell) = grid.cells.get(index) else {
                    continue;
                };
                let (_, background) = cell_colors(cell);
                if background != DEFAULT_BG {
                    let width = if cell.wide { cell_width * 2.0 } else { cell_width };
                    painter.rect_filled(cell_rect(col, row, width), 0.0, background);
                }
            }
        }
        for row in 0..grid.rows {
            let mut job = egui::text::LayoutJob::default();
            job.wrap.max_width = f32::INFINITY;
            job.wrap.max_rows = 1;
            for col in 0..grid.cols {
                let index = usize::from(row) * usize::from(grid.cols) + usize::from(col);
                let Some(cell) = grid.cells.get(index) else {
                    continue;
                };
                if cell.continuation {
                    continue;
                }
                let (foreground, _) = cell_colors(cell);
                let mut format = egui::TextFormat {
                    font_id: font.clone(),
                    color: foreground,
                    ..Default::default()
                };
                if cell.underline {
                    format.underline = egui::Stroke::new(1.0, foreground);
                }
                if cell.italic {
                    format.italics = true;
                }
                let text = if cell.text.is_empty() {
                    " "
                } else {
                    cell.text.as_str()
                };
                job.append(text, 0.0, format);
            }
            let galley = ui.fonts_mut(|fonts| fonts.layout_job(job));
            painter.galley(
                rect.min + egui::vec2(0.0, f32::from(row) * cell_height),
                galley,
                DEFAULT_FG,
            );
        }
        // 本地即时回显：远端还没回显的字符先画在光标处，光标跟到末尾。
        let (mut cursor_col, mut cursor_row) = grid.cursor;
        if !pending.is_empty() {
            let pending_font = font.clone();
            for character in pending.chars() {
                if cursor_col >= grid.cols {
                    cursor_col = 0;
                    cursor_row = cursor_row.saturating_add(1);
                }
                if cursor_row >= grid.rows {
                    break;
                }
                let glyph_width =
                    ui.fonts_mut(|fonts| fonts.glyph_width(&pending_font, character));
                let cells = (glyph_width / cell_width).round().max(1.0) as u16;
                if cursor_col.saturating_add(cells) > grid.cols {
                    cursor_col = 0;
                    cursor_row = cursor_row.saturating_add(1);
                }
                if cursor_row >= grid.rows {
                    break;
                }
                painter.text(
                    rect.min
                        + egui::vec2(
                            f32::from(cursor_col) * cell_width,
                            f32::from(cursor_row) * cell_height,
                        ),
                    egui::Align2::LEFT_TOP,
                    character,
                    pending_font.clone(),
                    LOCAL_ECHO_FG,
                );
                cursor_col = cursor_col.saturating_add(cells);
            }
        }
        if grid.cursor_visible || !pending.is_empty() {
            if cursor_col < grid.cols && cursor_row < grid.rows {
                let cursor = cell_rect(cursor_col, cursor_row, cell_width);
                let bar = egui::Rect::from_min_max(
                    egui::pos2(cursor.left(), cursor.bottom() - 2.0),
                    cursor.right_bottom(),
                );
                painter.rect_filled(bar, 0.0, DEFAULT_FG);
            }
        }
        let mut inputs = Vec::new();
        if response.clicked() {
            response.request_focus();
        }
        if response.has_focus() {
            painter.rect_stroke(
                rect,
                2.0,
                egui::Stroke::new(1.0, ui.visuals().selection.stroke.color),
                egui::StrokeKind::Inside,
            );
            let ctrl = ui.input(|input| input.modifiers.ctrl);
            let events = ui.input(|input| input.events.clone());
            for event in events {
                match event {
                    // Ctrl+letter is handled as a control byte below; egui may
                    // also emit the letter as text on some platforms.
                    egui::Event::Text(text) if !ctrl => inputs.push(text.into_bytes()),
                    egui::Event::Text(_) => {}
                    egui::Event::Paste(text) => inputs.push(text.into_bytes()),
                    egui::Event::Key {
                        key,
                        pressed: true,
                        modifiers,
                        ..
                    } => {
                        if let Some(bytes) = terminal_key(key, modifiers) {
                            inputs.push(bytes.to_vec());
                        }
                    }
                    _ => {}
                }
            }
        }
        inputs
    }

    #[cfg(test)]
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
        let (view, grid) = {
            let mut s = self.lock();
            // Remember the context so the output path can request exactly one
            // repaint when new bytes arrive; there is no 60fps timer.
            s.ctx = Some(ctx.clone());
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
                View {
                    visible: s.visible,
                    status: s.status.clone(),
                    pending_input: s.pending_input.clone(),
                    active: s.active,
                    pending: s.pending,
                    epoch: s.epoch,
                    handle: s.handle,
                    sessions: s.sessions.clone(),
                    list_error: s.list_error.clone(),
                    listing: s.list_request != 0,
                    auto_open: s.auto_open,
                },
                Arc::clone(&s.grid),
            )
        };
        let View {
            visible,
            status,
            pending_input,
            active,
            pending,
            epoch,
            handle,
            sessions,
            list_error,
            listing,
            auto_open,
        } = view;
        let mut visible = visible;
        if !visible {
            return;
        }
        let mut open = None;
        let mut end = false;
        let mut leave = false;
        let mut refresh = false;
        let mut send: Option<Vec<u8>> = None;
        let mut terminal_inputs: Vec<Vec<u8>> = Vec::new();
        let mut end_session: Option<u32> = None;
        let mut confirm_end: Option<u32> = None;
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
                     Shell 会留在远端继续运行，再打开可接回同一个 Shell。下面列出远端已经\
                     开着的会话，可以直接接入。",
                );
                ui.label(status);
                ui.horizontal(|ui| {
                    if !active && !pending {
                        if ui
                            .button("新建 Shell")
                            .on_hover_text("让远端新建一个 Shell，编号由远端分配")
                            .clicked()
                        {
                            open = Some(0);
                        }
                        if handle != 0
                            && ui
                                .button("接回上次 Shell")
                                .on_hover_text(format!("接回编号 {handle} 的会话"))
                                .clicked()
                        {
                            open = Some(handle);
                        }
                    }
                    if (active || pending)
                        && ui
                            .button("离开 Shell（不结束）")
                            .on_hover_text("只离开，Shell 继续在远端运行；窗口留着，可以再接回")
                            .clicked()
                    {
                        leave = true;
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
                ui.add_space(4.0);
                egui::ScrollArea::both()
                    .id_salt("remote-terminal-grid")
                    .max_height(500.0)
                    .auto_shrink([false, true])
                    .show(ui, |ui| {
                        // Hold the grid lock only while drawing. Input bytes
                        // are sent after the lock is released so the output
                        // path never waits on a UI callback holding state.
                        let grid = grid.lock().unwrap_or_else(|error| error.into_inner());
                        terminal_inputs.extend(Self::terminal_grid(ui, &grid, &pending_input));
                    });
                ui.label(
                    egui::RichText::new(
                        "点击终端后可直接输入；Enter 执行。关闭窗口只是离开，Shell 会留在远端继续运行。",
                    )
                    .small()
                    .weak(),
                );
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
                                if let Some(ghostty) = s.ghostty.as_mut() {
                                    ghostty.resize(cols, rows);
                                }
                                Self::refresh_grid(&mut s);
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
                        for (label, bytes) in [
                            ("Ctrl+C", "\x03"),
                            ("Ctrl+D", "\x04"),
                            ("Tab", "\t"),
                            ("↑", "\x1b[A"),
                            ("↓", "\x1b[B"),
                            ("←", "\x1b[D"),
                            ("→", "\x1b[C"),
                            ("Esc", "\x1b"),
                        ] {
                            if ui.button(label).clicked() {
                                send = Some(bytes.as_bytes().to_vec());
                            }
                        }
                    });
                });
                ui.separator();
                ui.horizontal(|ui| {
                    ui.label("远端会话");
                    if ui
                        .add_enabled(!listing, egui::Button::new("刷新"))
                        .on_hover_text("重新读取远端已经开着的终端会话")
                        .clicked()
                    {
                        refresh = true;
                    }
                    if listing {
                        ui.label("正在刷新…");
                    }
                });
                let has_error = !list_error.is_empty();
                if has_error {
                    let color = ui.visuals().error_fg_color;
                    ui.label(egui::RichText::new(list_error.as_str()).color(color));
                }
                egui::ScrollArea::vertical()
                    .max_height(160.)
                    .show(ui, |ui| {
                        if sessions.is_empty() && !has_error {
                            ui.label("远端没有可接入的会话");
                        }
                        for session in &sessions {
                            ui.horizontal(|ui| {
                                ui.label(format!(
                                    "{} · {} · {} · 编号 {}",
                                    session.name, session.shell, session.state, session.id
                                ))
                                .on_hover_text(format!(
                                    "创建于 {}；最近活动 {}",
                                    Self::stamp(session.created_ms),
                                    Self::stamp(session.last_active_ms),
                                ));
                                let attachable = Self::attachable(session, active, pending, handle);
                                let hover = if session.id == 0 {
                                    "编号 0 在请求里表示新建 Shell，不能用来接入"
                                } else {
                                    "接回这个远端会话，远端会重放它当前的屏幕"
                                };
                                if ui
                                    .add_enabled(attachable, egui::Button::new("接入"))
                                    .on_hover_text(hover)
                                    .clicked()
                                {
                                    open = Some(session.id);
                                }
                                if session.state == "exited" {
                                    // Nothing left to terminate.
                                } else if confirm_end == Some(session.id) {
                                    if ui.button("确认结束").clicked() {
                                        end_session = Some(session.id);
                                        confirm_end = None;
                                    }
                                    if ui.button("取消").clicked() {
                                        confirm_end = None;
                                    }
                                } else if ui
                                    .button("结束")
                                    .on_hover_text("结束远端 Shell（会终止进程）")
                                    .clicked()
                                {
                                    confirm_end = Some(session.id);
                                }
                            });
                        }
                    });
            });
        for bytes in terminal_inputs {
            self.send_bytes(epoch, &bytes);
        }
        if let Some(bytes) = send {
            self.send_bytes(epoch, &bytes);
        }
        if !visible {
            self.detach();
        } else if end {
            self.end();
        } else if leave {
            self.leave();
        } else if let Some(session) = open {
            self.open(session);
        } else if let Some(session) = end_session {
            self.end_session(session);
        } else if auto_open {
            // 远端没有可接回的 Shell：点开终端窗口就直接新建一个。
            self.open(0);
            self.lock().auto_open = false;
        } else if refresh {
            self.request_list();
        }

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
        assert_eq!(
            s.handle, 7,
            "the handle survives so the shell can be reattached"
        );
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
            assert!(
                Terminal::prepare_open(&mut s),
                "an existing handle means reattach"
            );
            assert_eq!(s.handle, 7);
            assert!(s.parser.screen().contents().is_empty());
        }
        // 没有句柄时保持 0：编号由远端分配，本地不再自己挑一个。
        let fresh = Terminal::default();
        let mut fresh = fresh.lock();
        assert!(!Terminal::prepare_open(&mut fresh));
        assert_eq!(fresh.handle, 0, "a new shell lets the host allocate the id");
    }
    #[test]
    fn a_new_shell_adopts_the_session_id_the_host_assigned() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 0;
            s.pending = true;
            s.open_sent = true;
            s.open_new = true;
        }
        t.receive(&Frame::encode(
            FrameType::OpenResp,
            9,
            br#"{"code":0,"session_id":9}"#,
        ));
        let s = t.lock();
        assert_eq!(s.handle, 9, "later stdout/stdin use the host's id");
        assert!(s.active);
        assert!(!s.pending);
        assert!(!s.open_sent);
        assert!(!s.open_new);
        drop(s);
        assert!(rx.try_recv().is_err(), "an accepted open closes nothing");
    }
    #[test]
    fn the_host_assigned_id_is_read_from_every_known_spelling() {
        for (payload, session) in [
            (br#"{"code":0,"session_id":4}"#.as_slice(), 4),
            (br#"{"code":0,"resp_session_id":5}"#.as_slice(), 5),
            (br#"{"code":0,"respSessionId":"6"}"#.as_slice(), 6),
            (br#"{"code":0,"handle":7}"#.as_slice(), 7),
            // 只有帧头里的会话号：接回已有会话时远端会原样带回。
            (br#"{"code":0}"#.as_slice(), 8),
            // 0 是合法编号：官方 Windows 主控会用它。
            (br#"{"code":0,"session_id":0}"#.as_slice(), 0),
            (br#"{"code":0,"respSessionId":"0"}"#.as_slice(), 0),
            (br#"{"code":0}"#.as_slice(), 0),
        ] {
            let (t, _rx) = wired();
            {
                let mut s = t.lock();
                s.pending = true;
                s.open_sent = true;
                s.open_new = true;
            }
            t.receive(&Frame::encode(FrameType::OpenResp, session, payload));
            let s = t.lock();
            assert_eq!(s.handle, session, "payload {payload:?}");
            assert!(s.active, "payload {payload:?}");
        }
    }
    #[test]
    fn a_zero_session_id_is_a_working_shell() {
        // 官方 Windows 主控用 0 当会话编号：回复只有 code 也行。
        let (t, _rx) = wired();
        {
            let mut s = t.lock();
            s.pending = true;
            s.open_sent = true;
            s.open_new = true;
        }
        t.receive(&Frame::encode(FrameType::OpenResp, 0, br#"{"code":0}"#));
        {
            let s = t.lock();
            assert!(s.active);
            assert_eq!(s.handle, 0);
            assert!(!s.pending);
        }
        // 输出按 0 过滤，和 handle 一致，所以能显示。
        t.receive(&Frame::encode(FrameType::Stdout, 0, b"ok"));
        assert_eq!(t.lock().parser.screen().contents(), "ok");
        // 别的会话号的输出仍然进不来。
        t.receive(&Frame::encode(FrameType::Stdout, 1, b"secret"));
        assert_eq!(t.lock().parser.screen().contents(), "ok");
    }
    #[test]
    fn a_late_reply_to_a_new_shell_closes_the_id_the_host_allocated() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.pending = false; // 创建超时或窗口已经关掉
            s.open_sent = true;
            s.open_new = true;
            s.handle = 0;
        }
        t.receive(&Frame::encode(
            FrameType::OpenResp,
            9,
            br#"{"code":0,"session_id":9}"#,
        ));
        assert!(!t.lock().active, "a late reply must not activate the shell");
        assert_eq!(t.lock().handle, 0);
        let frame = Frame::parse(&rx.try_recv().expect("close frame")).unwrap();
        assert_eq!(frame.kind, FrameType::Close);
        assert_eq!(
            frame.session, 9,
            "close the shell the host actually created"
        );
    }
    #[test]
    fn a_late_reply_to_a_reattach_leaves_the_session_alone() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.pending = false;
            s.open_sent = true;
            s.open_new = false;
        }
        t.receive(&Frame::encode(FrameType::OpenResp, 7, br#"{"code":0}"#));
        assert!(!t.lock().active);
        assert_eq!(t.lock().handle, 7);
        assert!(
            rx.try_recv().is_err(),
            "a reattach reply must not end a shell the user left running"
        );
    }
    #[test]
    fn a_session_list_response_populates_the_list() {
        let (t, _rx) = wired();
        let request = LIST_REQUEST_BASE | 1;
        t.lock().list_request = request;
        let payload = br#"[
            {"session_id":3,"state":"running","name":"shell-1","shell":"/bin/bash",
             "created_at_ms":10,"last_active_ms":20},
            {"session_id":"7","state":"detached","name":"shell-2","shell":"/bin/zsh",
             "created_at_ms":30,"last_active_ms":40}
        ]"#;
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            request,
            payload,
        ));
        let s = t.lock();
        assert_eq!(s.list_request, 0, "the request is no longer in flight");
        assert!(s.list_error.is_empty());
        assert_eq!(s.sessions.len(), 2);
        assert_eq!(s.sessions[0].id, 3);
        assert_eq!(s.sessions[0].name, "shell-1");
        assert_eq!(s.sessions[0].shell, "/bin/bash");
        assert_eq!(s.sessions[0].state, "running");
        assert_eq!(s.sessions[0].last_active_ms, 20);
        assert_eq!(s.sessions[1].id, 7, "a decimal string id is accepted");
        assert_eq!(s.sessions[1].created_ms, 30);
    }
    #[test]
    fn stale_list_responses_are_ignored() {
        let (t, _rx) = wired();
        // 没有请求在飞：这是过期或无关的回复。
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            11,
            br#"[{"session_id":3}]"#,
        ));
        assert!(t.lock().sessions.is_empty());
        t.lock().list_request = 11;
        // 回复的编号对不上：仍然丢掉，请求继续等。
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            12,
            br#"[{"session_id":3}]"#,
        ));
        {
            let s = t.lock();
            assert_eq!(s.list_request, 11, "the request is still in flight");
            assert!(s.sessions.is_empty());
        }
        // 官方主控可能不回请求编号（0），这种回复要收。
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            0,
            br#"[{"session_id":3}]"#,
        ));
        assert_eq!(t.lock().sessions.len(), 1);
    }
    #[test]
    fn a_list_error_is_reported_on_the_list_not_on_the_shell() {
        let (t, _rx) = wired();
        let shell_status = t.lock().status.clone();
        t.lock().list_request = 13;
        t.receive(&Frame::encode(
            FrameType::Error,
            13,
            r#"{"code":1,"message":"列表失败"}"#.as_bytes(),
        ));
        let s = t.lock();
        assert_eq!(s.list_request, 0);
        assert_eq!(s.list_error, "列表失败");
        assert_eq!(s.status, shell_status, "the shell state is untouched");
    }
    #[test]
    fn the_session_list_is_bounded_and_control_characters_are_stripped() {
        let (t, _rx) = wired();
        t.lock().list_request = 1;
        let items: Vec<_> = (1..=600)
            .map(|id| serde_json::json!({"session_id": id, "name": format!("shell\n{id}")}))
            .collect();
        let payload = serde_json::to_vec(&serde_json::Value::Array(items)).unwrap();
        t.receive(&Frame::encode(FrameType::ListSessionsResp, 1, &payload));
        let s = t.lock();
        assert_eq!(s.sessions.len(), MAX_LISTED);
        assert!(!s.sessions[0].name.contains('\n'));
        assert_eq!(s.sessions[0].shell, "未知 Shell");
    }
    #[test]
    fn a_session_zero_row_is_listed_but_never_offered_for_attach() {
        let (t, _rx) = wired();
        t.lock().list_request = 1;
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            1,
            br#"[{"session_id":0,"name":"windows","state":"running"}]"#,
        ));
        let s = t.lock();
        assert_eq!(s.sessions.len(), 1, "session zero is a real session id");
        assert_eq!(s.sessions[0].id, 0);
        assert!(!Terminal::attachable(&s.sessions[0], false, false, 0));
        assert!(!Terminal::attachable(&s.sessions[0], false, false, 5));
        let other = RemoteSession {
            id: 5,
            name: "x".into(),
            shell: "sh".into(),
            state: "running".into(),
            created_ms: 0,
            last_active_ms: 0,
        };
        assert!(Terminal::attachable(&other, false, false, 0));
        assert!(
            !Terminal::attachable(&other, false, false, 5),
            "the session already in use"
        );
        assert!(!Terminal::attachable(&other, true, false, 0));
        assert!(!Terminal::attachable(&other, false, true, 0));
        let exited = RemoteSession {
            state: "exited".into(),
            ..other
        };
        assert!(!Terminal::attachable(&exited, false, false, 0));
    }
    #[test]
    fn list_request_ids_never_collide_with_the_shell_handle() {
        let (t, _rx) = wired();
        let mut s = t.lock();
        s.handle = LIST_REQUEST_BASE | 1;
        let first = Terminal::next_list_request(&mut s).expect("a request id");
        assert_ne!(first, 0);
        assert_ne!(first, s.handle);
        let second = Terminal::next_list_request(&mut s).expect("a request id");
        assert_ne!(first, second);
    }
    #[test]
    fn a_lost_list_request_times_out_instead_of_blocking_refreshes() {
        let (t, _rx) = wired();
        let mut s = t.lock();
        s.list_request = LIST_REQUEST_BASE | 9;
        s.last_list = Some(
            std::time::Instant::now() - LIST_TIMEOUT - std::time::Duration::from_secs(1),
        );
        Terminal::request_list_locked(&mut s);
        assert_eq!(s.list_request, 0, "the lost request must not stay in flight");
        assert!(!s.list_error.is_empty());
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

    #[test]
    fn the_terminal_grid_keeps_text_colors_and_cursor() {
        let mut parser = vt100::Parser::new(4, 12, 0);
        parser.process(b"hi\x1b[31m red");
        let grid = TerminalGrid::from_parser(&parser);
        assert_eq!(grid.cols, 12);
        assert_eq!(grid.rows, 4);
        assert_eq!(grid.cursor, (6, 0));
        assert!(grid.cursor_visible);
        assert_eq!(grid.cells[0].text, "h");
        assert_eq!(grid.cells[1].text, "i");
        assert_eq!(grid.cells[3].text, "r");
        assert_eq!(grid.cells[3].fg, TerminalColor::Indexed(1));
        assert_eq!(grid.cells[1].fg, TerminalColor::Default);
    }

    #[test]
    fn a_resize_replaces_the_grid_without_stale_cells() {
        let mut parser = vt100::Parser::new(24, 80, 0);
        parser.process(b"0123456789");
        let mut grid = TerminalGrid::from_parser(&parser);
        assert_eq!(grid.cursor, (10, 0));
        parser.screen_mut().set_size(2, 5);
        grid.update_from_parser(&parser);
        assert_eq!(grid.cols, 5);
        assert_eq!(grid.rows, 2);
        assert_eq!(grid.cells.len(), 10);
    }

    #[test]
    fn terminal_keys_cover_control_and_editing_keys() {
        let plain = egui::Modifiers::default();
        assert_eq!(terminal_key(egui::Key::Enter, plain), Some(b"\r".as_slice()));
        assert_eq!(
            terminal_key(egui::Key::Backspace, plain),
            Some(b"\x7f".as_slice())
        );
        assert_eq!(
            terminal_key(egui::Key::ArrowUp, plain),
            Some(b"\x1b[A".as_slice())
        );
        assert_eq!(
            terminal_key(egui::Key::Delete, plain),
            Some(b"\x1b[3~".as_slice())
        );
        let ctrl = egui::Modifiers {
            ctrl: true,
            ..Default::default()
        };
        assert_eq!(
            terminal_key(egui::Key::C, ctrl),
            Some(b"\x03".as_slice())
        );
        assert_eq!(
            terminal_key(egui::Key::D, ctrl),
            Some(b"\x04".as_slice())
        );
        assert_eq!(terminal_key(egui::Key::A, ctrl), Some(b"\x01".as_slice()));
        assert_eq!(terminal_key(egui::Key::A, plain), None);
    }


    #[test]
    fn the_grid_ui_renders_headlessly_without_relayout_panics() {
        let ctx = egui::Context::default();
        let terminal = Terminal::default();
        {
            let mut s = terminal.lock();
            s.visible = true;
            s.active = true;
            s.status = "测试".into();
            s.parser.process(b"hello\r\n$ ");
            if let Ok(mut grid) = s.grid.lock() {
                *grid = TerminalGrid::from_parser(&s.parser);
            }
        }
        let mut input = egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(1000.0, 800.0),
            )),
            ..Default::default()
        };
        for _ in 0..2 {
            ctx.begin_pass(input.clone());
            terminal.ui(&ctx);
            let mut output = ctx.end_pass();
            output.textures_delta.clear();
            input.time = Some(0.016);
        }
    }


    #[test]
    fn the_libghostty_backend_is_used_for_the_ui_grid_when_available() {
        let t = Terminal::default();
        {
            let mut s = t.lock();
            if s.ghostty.is_none() {
                return;
            }
            s.active = true;
            s.parser.process(b"hello\x1b[32m green");
            if let Some(ghostty) = s.ghostty.as_mut() {
                ghostty.process(b"hello\x1b[32m green");
            }
        }
        let view = {
            let s = t.lock();
            grid_from_state(&s)
        };
        assert_eq!(view.cursor, (11, 0));
        assert_eq!(view.cells[0].text, "h");
        assert_eq!(view.cells[6].fg, TerminalColor::Indexed(2));
    }


    #[test]
    fn the_vt100_fallback_is_used_when_libghostty_is_unavailable() {
        let t = Terminal::default();
        let view = {
            let mut s = t.lock();
            s.ghostty = None;
            s.parser.process(b"fallback");
            grid_from_state(&s)
        };
        assert_eq!(view.cells[0].text, "f");
        assert_eq!(view.cursor, (8, 0));
    }


    #[test]
    fn ending_a_listed_session_sends_close_for_that_id_only() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
        }
        t.end_session(9);
        let frame = Frame::parse(&rx.try_recv().expect("close frame")).unwrap();
        assert_eq!(frame.kind, FrameType::Close);
        assert_eq!(frame.session, 9);
        let s = t.lock();
        assert_eq!(s.handle, 7, "the current shell must stay attached");
        assert!(s.active);
    }

    #[test]
    fn ending_the_current_session_clears_the_handle_and_grid() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
            s.parser.process(b"old");
            if let Ok(mut grid) = s.grid.lock() {
                *grid = TerminalGrid::from_parser(&s.parser);
            }
        }
        t.end_session(7);
        let frame = Frame::parse(&rx.try_recv().expect("close frame")).unwrap();
        assert_eq!(frame.kind, FrameType::Close);
        assert_eq!(frame.session, 7);
        let s = t.lock();
        assert_eq!(s.handle, 0);
        assert!(!s.active);
        assert!(s.grid.lock().unwrap().cells.iter().all(|cell| cell.text.is_empty()));
    }


    #[test]
    fn local_echo_tracks_typing_and_backspace_immediately() {
        let (t, mut rx) = wired();
        {
            let mut s = t.lock();
            s.handle = 7;
            s.active = true;
        }
        let epoch = t.lock().epoch;
        t.send_bytes(epoch, b"abc");
        assert_eq!(t.lock().pending_input, "abc");
        t.send_bytes(epoch, b"\x7f");
        assert_eq!(t.lock().pending_input, "ab");
        t.send_bytes(epoch, b"\r");
        assert_eq!(t.lock().pending_input, "");
        // The bytes still went to the remote shell; local echo only mirrors them.
        assert!(rx.try_recv().is_ok());
    }

    #[test]
    fn an_empty_session_list_asks_the_window_to_create_a_shell() {
        let (t, _rx) = wired();
        {
            let mut s = t.lock();
            s.list_request = 77;
            s.auto_open_done = false;
        }
        t.receive(&Frame::encode(FrameType::ListSessionsResp, 77, b"[]"));
        let s = t.lock();
        assert!(s.auto_open, "an empty list should auto-create a shell");
        assert!(s.auto_open_done);
    }

    #[test]
    fn a_running_session_list_does_not_auto_create_a_shell() {
        let (t, _rx) = wired();
        {
            let mut s = t.lock();
            s.list_request = 78;
            s.auto_open_done = false;
        }
        t.receive(&Frame::encode(
            FrameType::ListSessionsResp,
            78,
            br#"[{"session_id":9,"state":"running"}]"#,
        ));
        let s = t.lock();
        assert!(!s.auto_open);
        assert!(s.auto_open_done);
    }

}
