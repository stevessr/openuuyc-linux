//! Official UU remote terminal controller (ConnectOptions.kind = 8).
//! This code controls a shell on the remote authorized device; it never runs a
//! local command. Disconnect uses DETACH, never the host's destructive CLOSE.
use super::ControllerConnection;
use crate::account::client::AuthenticatedClient;
use crate::media::{ConnectionMediaOptions, LocalDisplayInfo, TransportChoice};
use crate::session::negotiation::{ControlConnectType, ControlPurpose};
use anyhow::{Context as _, Result, bail, ensure};
use bytes::Bytes;
use serde_json::{Value, json};
use std::{collections::VecDeque, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    sync::broadcast,
};
use tokio_util::sync::CancellationToken;
use webrtc::data_channel::{RTCDataChannel, data_channel_state::RTCDataChannelState};

const MAGIC: &[u8;4] = b"TERM";
const HEADER: usize = 16;
const MAX_PAYLOAD: usize = 1024 * 1024;
const MAX_FRAMES_PER_PACKET: usize = 256;
const SEND_HIGH_WATER: usize = 1024 * 1024;
const OPEN: u8 = 1;
const STDIN: u8 = 2;
const RESIZE: u8 = 3;
const CLOSE: u8 = 4;
const STDOUT: u8 = 5;
const EXIT: u8 = 6;
const ERROR: u8 = 8;
const LIST_REQ: u8 = 9;
const LIST_RESP: u8 = 10;
/// Session id the official controller uses for `ListSessionsReq`; the host
/// does not know an invented request id.
const LIST_SESSION: u32 = 0;
/// The picker is interactive; a host that ignores ListSessionsReq should fail
/// fast enough to still offer "new shell".
const LIST_TIMEOUT: Duration = Duration::from_secs(10);
const OPEN_RESP: u8 = 13;
const DETACH: u8 = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
struct Frame { kind: u8, session: u32, payload: Vec<u8> }

fn encode(kind: u8, session: u32, payload: &[u8]) -> Result<Vec<u8>> {
    ensure!((1..=16).contains(&kind), "unknown TERM kind");
    ensure!(payload.len() <= MAX_PAYLOAD, "TERM payload exceeds 1 MiB");
    let mut data = Vec::with_capacity(HEADER + payload.len());
    data.extend_from_slice(MAGIC);
    data.extend_from_slice(&[1, kind, 0, 0]);
    data.extend_from_slice(&session.to_le_bytes());
    data.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    data.extend_from_slice(payload);
    Ok(data)
}

#[derive(Default)]
struct Decoder { pending: Vec<u8> }
impl Decoder {
    fn push(&mut self, data: &[u8]) -> Result<Vec<Frame>> {
        ensure!(
            data.len() <= MAX_PAYLOAD + HEADER
                && self.pending.len() + data.len() <= MAX_PAYLOAD + HEADER,
            "TERM receive buffer exceeded"
        );
        self.pending.extend_from_slice(data);
        let mut output = Vec::new();
        let mut pos = 0;
        while self.pending.len() - pos >= HEADER {
            let b = &self.pending[pos..];
            ensure!(&b[..4] == MAGIC && b[4] == 1, "invalid TERM header");
            ensure!((1..=16).contains(&b[5]) && b[6] == 0 && b[7] == 0, "unknown TERM kind or flags");
            let len = u32::from_le_bytes(b[12..16].try_into()?) as usize;
            ensure!(len <= MAX_PAYLOAD, "TERM frame exceeds 1 MiB");
            if b.len() < HEADER + len { break; }
            ensure!(output.len() < MAX_FRAMES_PER_PACKET, "too many coalesced TERM frames");
            output.push(Frame {
                kind: b[5],
                session: u32::from_le_bytes(b[8..12].try_into()?),
                payload: b[HEADER..HEADER + len].to_vec(),
            });
            pos += HEADER + len;
        }
        self.pending.drain(..pos);
        Ok(output)
    }
}

struct Terminal {
    channel: Arc<RTCDataChannel>,
    inbound: broadcast::Receiver<Vec<u8>>,
    decoder: Decoder,
    ready: VecDeque<Frame>,
    events: VecDeque<Frame>,
}
impl Terminal {
    fn new(channel: Arc<RTCDataChannel>, inbound: broadcast::Receiver<Vec<u8>>) -> Self {
        Self { channel, inbound, decoder: Decoder::default(),
               ready: VecDeque::new(), events: VecDeque::new() }
    }
    async fn send(&self, kind: u8, session: u32, body: &[u8]) -> Result<()> {
        ensure!(self.channel.ready_state() == RTCDataChannelState::Open,
                "UU remote terminal channel is not open");
        // Apply SCTP backpressure rather than buffering unlimited keyboard or
        // paste data if the remote console stops reading.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while self.channel.buffered_amount().await > SEND_HIGH_WATER {
            ensure!(self.channel.ready_state() == RTCDataChannelState::Open,
                    "remote terminal channel closed during input backpressure");
            ensure!(tokio::time::Instant::now() < deadline,
                    "remote terminal data channel is backpressured");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        self.channel.send(&Bytes::from(encode(kind, session, body)?)).await?;
        Ok(())
    }
    async fn next_frame(&mut self) -> Result<Frame> {
        loop {
            if let Some(frame) = self.ready.pop_front() { return Ok(frame); }
            // RecvError::Lagged is fatal; silently dropping output corrupts ANSI
            // control sequences and can hide the result of a remote command.
            let b = self.inbound.recv().await.context(
                "remote terminal channel closed or output overflowed"
            )?;
            self.ready.extend(self.decoder.push(&b)?);
        }
    }
    async fn request(&mut self, kind: u8, reply_kind: u8, session: u32, body: &[u8]) -> Result<Frame> {
        self.request_within(kind, reply_kind, session, body, Duration::from_secs(20)).await
    }
    /// Same as [`Self::request`], with an explicit deadline. Listing is
    /// interactive: a host that does not implement ListSessionsReq must not
    /// keep the desktop picker waiting for the full OPEN deadline.
    async fn request_within(
        &mut self, kind: u8, reply_kind: u8, session: u32, body: &[u8], deadline: Duration,
    ) -> Result<Frame> {
        self.send(kind, session, body).await?;
        tokio::time::timeout(deadline, async {
            loop {
                let frame = self.next_frame().await?;
                if frame.kind == reply_kind
                    && (frame.session == session || reply_kind == OPEN_RESP
                        || (reply_kind == LIST_RESP && frame.session == 0))
                {
                    return Ok(frame);
                }
                if frame.kind == ERROR {
                    bail!("remote terminal error: {}", remote_message(&frame.payload));
                }
                if frame.kind == STDOUT || frame.kind == EXIT {
                    ensure!(self.events.len() < 256, "remote terminal event queue overflow");
                    self.events.push_back(frame);
                }
            }
        }).await.context("remote terminal request timed out")?
    }
    async fn list(&mut self) -> Result<Vec<Value>> {
        // The official controller sends ListSessionsReq with session id 0 and
        // an empty payload. A made-up request id is not a session the host
        // knows, and the official Windows host silently ignores it.
        let frame = self
            .request_within(LIST_REQ, LIST_RESP, LIST_SESSION, &[], LIST_TIMEOUT)
            .await?;
        let rows: Vec<Value> = serde_json::from_slice(&frame.payload)
            .context("remote terminal list response is not a JSON array")?;
        ensure!(rows.len() <= 512, "remote terminal session list too large");
        Ok(rows)
    }
    async fn open(&mut self, session: Option<u32>, cols: u16, rows: u16, shell: &str) -> Result<u32> {
        let frame = self.request(
            OPEN, OPEN_RESP, session.unwrap_or(0), open_payload(shell, cols, rows).as_bytes()
        ).await?;
        let value: Value = serde_json::from_slice(&frame.payload)
            .context("invalid terminal OPEN response")?;
        ensure!(
            value.get("code").and_then(Value::as_i64) == Some(0),
            "remote terminal rejected: {}", remote_message(&frame.payload)
        );
        Ok(value.get("session_id")
            .or_else(|| value.get("resp_session_id"))
            .or_else(|| value.get("handle"))
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .and_then(|n| u32::try_from(n).ok())
            .filter(|n| *n != 0)
            .or_else(|| (frame.session > 0).then_some(frame.session))
            .unwrap_or(0)) // Official Windows hosts may use session zero.
    }
    async fn input(&self, id: u32, data: &[u8]) -> Result<()> {
        for chunk in data.chunks(16 * 1024) {
            self.send(STDIN, id, chunk).await?;
        }
        Ok(())
    }
    async fn resize(&self, id: u32, cols: u16, rows: u16) -> Result<()> {
        let payload = json!({"cols":cols.clamp(1,512), "rows":rows.clamp(1,256)});
        self.send(RESIZE, id, payload.to_string().as_bytes()).await
    }
    async fn detach(&self, id: u32) {
        // CLOSE (4) kills the shell on some hosts. Detach is non-destructive.
        let _ = self.send(DETACH, id, &[]).await;
    }
    async fn close(&self, id: u32) -> Result<()> {
        self.send(CLOSE, id, &[]).await
    }
}

/// One terminal session as the remote host lists it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RemoteSession {
    pub id: u32,
    pub name: String,
    pub shell: String,
    /// `running` | `detached` | `exited` | a host-specific value.
    pub state: String,
    pub created_ms: i64,
    pub last_active_ms: i64,
}

impl RemoteSession {
    /// Chinese state label for the interactive menu and the desktop picker.
    pub(crate) fn state_label(&self) -> &'static str {
        match self.state.as_str() {
            "running" => "运行中",
            "detached" => "已分离",
            "exited" => "已退出",
            _ => "未知",
        }
    }
}

/// Parse the JSON array returned by TERM `ListSessionsResp`.
///
/// Official and OpenUUYC hosts use the same fields (`session_id`, `name`,
/// `shell`, `state`, `created_at_ms`, `last_active_ms`); a few aliases are
/// accepted for robustness. Rows without a usable id are skipped instead of
/// being shown as session 0; an explicit session 0 is kept because official
/// Windows hosts may legitimately use it.
pub(crate) fn parse_session_list(rows: &[Value]) -> Vec<RemoteSession> {
    rows.iter().filter_map(parse_session_row).collect()
}

fn parse_session_row(row: &Value) -> Option<RemoteSession> {
    let id = ["session_id", "id", "handle"]
        .iter()
        .find_map(|key| row.get(*key))
        .and_then(|value| value.as_u64().or_else(|| value.as_str()?.parse().ok()))
        .and_then(|id| u32::try_from(id).ok())?;
    let text = |keys: &[&str]| {
        keys.iter()
            .find_map(|key| row.get(*key))
            .and_then(Value::as_str)
            .unwrap_or_default()
            .chars()
            .take(80)
            .filter(|ch| !ch.is_control())
            .collect::<String>()
    };
    let shell = text(&["shell", "command"]);
    Some(RemoteSession {
        id,
        name: text(&["name", "title"]),
        shell: if shell.is_empty() {
            "unknown".to_owned()
        } else {
            shell
        },
        state: text(&["state", "status"]).trim().to_ascii_lowercase(),
        created_ms: timestamp(row, &["created_at_ms", "created_ms"]),
        last_active_ms: timestamp(row, &["last_active_ms"]),
    })
}

fn timestamp(row: &Value, keys: &[&str]) -> i64 {
    keys.iter()
        .find_map(|key| row.get(*key))
        .and_then(|value| value.as_i64().or_else(|| value.as_str()?.parse().ok()))
        .unwrap_or(0)
}

fn remote_message(data: &[u8]) -> String {
    let Ok(json) = serde_json::from_slice::<Value>(data) else {
        return "unknown remote response".to_string();
    };
    json.get("message").and_then(Value::as_str).unwrap_or("operation refused")
        .chars().take(200).filter(|ch| !ch.is_control()).collect()
}

fn terminal_size() -> (u16,u16) {
    #[cfg(unix)] {
        let mut w = libc::winsize { ws_row:0, ws_col:0, ws_xpixel:0, ws_ypixel:0 };
        if unsafe { libc::ioctl(libc::STDOUT_FILENO, libc::TIOCGWINSZ, &mut w) } == 0
            && w.ws_col > 0 && w.ws_row > 0
        { return (w.ws_col.clamp(1,512), w.ws_row.clamp(1,256)); }
    }
    (80,24)
}

#[cfg(unix)]
struct RawMode(libc::termios);
#[cfg(unix)]
impl RawMode {
    fn enter() -> Result<Self> {
        let mut old = std::mem::MaybeUninit::<libc::termios>::uninit();
        ensure!(unsafe { libc::isatty(libc::STDIN_FILENO) } == 1,
                "interactive remote terminal requires a TTY");
        ensure!(unsafe { libc::tcgetattr(libc::STDIN_FILENO, old.as_mut_ptr()) } == 0,
                "cannot read local TTY settings");
        let old = unsafe { old.assume_init() };
        let mut raw = old;
        unsafe { libc::cfmakeraw(&mut raw) };
        ensure!(unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &raw) } == 0,
                "cannot enable raw TTY mode");
        Ok(Self(old))
    }
}
#[cfg(unix)]
impl Drop for RawMode {
    fn drop(&mut self) {
        unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &self.0) };
    }
}

async fn render(out: &mut tokio::io::Stdout, frame: Frame, id: u32) -> Result<bool> {
    if frame.session != id && frame.session != 0 { return Ok(false); }
    match frame.kind {
        STDOUT => out.write_all(&frame.payload).await?,
        EXIT => {
            let code = serde_json::from_slice::<Value>(&frame.payload).ok()
                .and_then(|v| v.get("exit_code")?.as_i64());
            eprintln!("\n远端 Shell 已退出：{}", code.map_or("?".to_owned(), |c| c.to_string()));
            return Ok(true);
        }
        ERROR => bail!("remote terminal error: {}", remote_message(&frame.payload)),
        _ => {}
    }
    Ok(false)
}

async fn interactive(terminal: &mut Terminal, id: u32) -> Result<()> {
    let (cols, rows) = terminal_size();
    terminal.resize(id, cols, rows).await?;
    #[cfg(unix)]
    let _raw_mode = RawMode::enter()?;
    let mut stdin = tokio::io::stdin();
    let mut stdout = tokio::io::stdout();
    let mut input = [0u8; 16 * 1024];
    let mut last_size = (cols, rows);
    let mut tick = tokio::time::interval(Duration::from_millis(350));
    let interrupt = tokio::signal::ctrl_c();
    tokio::pin!(interrupt);
    loop {
        if let Some(frame) = terminal.events.pop_front() {
            if render(&mut stdout, frame, id).await? { break; }
            continue;
        }
        tokio::select! {
            frame = terminal.next_frame() => {
                if render(&mut stdout, frame?, id).await? { break; }
            }
            read = stdin.read(&mut input) => {
                let count = read.context("reading local keyboard")?;
                if count == 0 { break; }
                // Ctrl+] ends only the local attachment. Ctrl+C reaches the
                // remote shell in POSIX raw mode, just like SSH.
                if let Some(pos) = input[..count].iter().position(|b| *b == 0x1d) {
                    terminal.input(id, &input[..pos]).await?;
                    break;
                }
                terminal.input(id, &input[..count]).await?;
            }
            _ = tick.tick() => {
                let size = terminal_size();
                if size != last_size {
                    terminal.resize(id, size.0, size.1).await?;
                    last_size = size;
                }
            }
            _ = &mut interrupt => break,
        }
    }
    terminal.detach(id).await;
    stdout.flush().await?;
    Ok(())
}

/// A validated remote terminal target. Keeping the fields together lets the
/// CLI and the desktop picker share one resolution and connection path.
struct TerminalTarget {
    controller_id: String,
    device_id: String,
    platform: i32,
    version_name: String,
}

/// Resolve a bound device by exact alias or device ID and apply the same
/// permissions `run_with_client` requires before opening a controller
/// connection: never take over our own device or a device another controller
/// already holds, and only talk to a connected desktop host that allows it.
async fn resolve_terminal_target(
    client: &AuthenticatedClient, target: &str,
) -> Result<TerminalTarget> {
    let devices = client.list_devices().await?;
    let candidates: Vec<_> = devices.my_binded_devices.iter()
        .filter(|d| d.alias == target || d.device_id == target).collect();
    let device = match candidates.as_slice() {
        [] => bail!("no bound device matches the requested alias or ID: {target}"),
        [device] => *device,
        _ => bail!("ambiguous device alias: {target}; use the complete device ID"),
    };
    ensure!(device.device_id != devices.current_device.device_id, "cannot control own device");
    ensure!(matches!(device.platform, 1 | 4), "device is not a desktop host");
    ensure!(device.is_connected() && device.controllable && device.controlled_support,
            "device is offline or does not permit remote control");
    ensure!(!device.in_use_by_other_device(&devices.current_device.device_id),
            "device already has a controller; refusing implicit takeover");
    Ok(TerminalTarget {
        controller_id: devices.current_device.validated_device_id()?.to_owned(),
        device_id: device.validated_device_id()?.to_owned(),
        platform: device.platform,
        version_name: device.version_name.clone(),
    })
}

/// Establish the short-lived kind=8 connection used for terminal traffic.
///
/// This is the official data-only terminal connection: no audio/video m-lines
/// and no desktop capture. Offering media here would make the host treat it as
/// a desktop controller and start a full stream.
async fn connect_terminal(
    client: &AuthenticatedClient, target: &TerminalTarget,
) -> Result<ControllerConnection> {
    let mut profile = ConnectionMediaOptions::default()
        .resolve(crate::media::detect_local_display().unwrap_or(LocalDisplayInfo::FALLBACK))?;
    profile.data_only = true;
    let policy = client.feature_catalog().policy(target.platform, &target.version_name);
    let room = client.join_device(&target.device_id, false).await?;
    let cancel = CancellationToken::new();
    ControllerConnection::establish(
        room, &target.controller_id, profile, policy,
        ControlConnectType::Normal, TransportChoice::Auto, None,
        // Terminal connections are data-only; do not carry the desktop
        // viewer's "start muted" default into them. Launching a terminal must
        // never mute the host or an existing stream.
        crate::media::audio::AudioSettings {
            volume: 100,
            muted: false,
        },
        None, &cancel, ControlPurpose::Terminal, false
    ).await.context("establish official UU terminal session")
}

/// Wait until the host opens the terminal data channel and hand it to a
/// [`Terminal`].
async fn terminal_channel(
    connection: &ControllerConnection,
) -> Result<(Arc<RTCDataChannel>, broadcast::Receiver<Vec<u8>>)> {
    let (channel, rx) = connection.peer.terminal_transport()?;
    tokio::time::timeout(Duration::from_secs(12), async {
        while channel.ready_state() != RTCDataChannelState::Open {
            ensure!(channel.ready_state() != RTCDataChannelState::Closed, "terminal channel closed");
            tokio::time::sleep(Duration::from_millis(40)).await;
        }
        Ok::<(), anyhow::Error>(())
    }).await.context("terminal data channel open timeout")??;
    Ok((channel, rx))
}

/// List the sessions the target device may show this account. The connection
/// is closed again before returning; the desktop worker calls this before
/// offering its picker.
pub(crate) async fn list_sessions_with_client(
    client: &AuthenticatedClient, target: &str,
) -> Result<Vec<RemoteSession>> {
    let target = resolve_terminal_target(client, target).await?;
    let connection = connect_terminal(client, &target).await?;
    let listed: Result<Vec<RemoteSession>> = async {
        let (channel, rx) = terminal_channel(&connection).await?;
        let mut terminal = Terminal::new(channel, rx);
        Ok(parse_session_list(&terminal.list().await?))
    }.await;
    let closed = connection.close().await;
    let sessions = listed?;
    closed?;
    Ok(sessions)
}

/// Ask the host to end one listed session (TERM CLOSE). This is the
/// destructive action behind the management UI's "结束" button; unlike a
/// detach, it terminates the remote shell. The connection is short-lived and
/// closed again immediately.
pub(crate) async fn close_session_with_client(
    client: &AuthenticatedClient, target: &str, session: u32,
) -> Result<()> {
    let target = resolve_terminal_target(client, target).await?;
    let connection = connect_terminal(client, &target).await?;
    let ended: Result<()> = async {
        let (channel, rx) = terminal_channel(&connection).await?;
        let mut terminal = Terminal::new(channel, rx);
        terminal.close(session).await?;
        // The host may answer with EXIT/ERROR. A host that does not is still
        // considered to have received CLOSE; do not hold the dialog forever.
        let _ = tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                match terminal.next_frame().await? {
                    Frame { kind: EXIT | ERROR, .. } => return Ok::<(), anyhow::Error>(()),
                    _ => {}
                }
            }
        })
        .await;
        Ok(())
    }
    .await;
    let closed = connection.close().await;
    ended?;
    closed?;
    Ok(())
}

/// Official `OpenPayload`: `shell`, `cols` and `rows` are required and
/// `option` defaults to `"none"`.
fn open_payload(shell: &str, cols: u16, rows: u16) -> String {
    json!({
        "shell": shell,
        "cols": cols.clamp(1,512),
        "rows": rows.clamp(1,256),
        "option": "none",
    })
    .to_string()
}

/// Shell name the official controller requests for the target platform:
/// `zsh` only for macOS (platform 4), `powershell` for the Windows desktop
/// hosts this path accepts.
fn requested_shell(platform: i32) -> &'static str {
    if platform == 4 {
        "zsh"
    } else {
        "powershell"
    }
}

/// Never forcibly take over an existing controller. The host decides whether
/// this authenticated UU account is allowed to open remote shell sessions.
pub async fn run_saved(
    target: String, list_only: bool, attach: Option<u32>, handle: Option<u32>, pick: bool,
) -> Result<()> {
    let client = AuthenticatedClient::from_saved_session()?;
    let result = run_with_client(&client, &target, list_only, attach, handle, pick).await;
    client.close().await;
    result
}
async fn run_with_client(
    client: &AuthenticatedClient, target: &str, list_only: bool, attach: Option<u32>,
    handle: Option<u32>, pick: bool
) -> Result<()> {
    ensure!(!(list_only && (attach.is_some() || handle.is_some() || pick)),
            "--list conflicts with --attach, --handle and --pick");
    ensure!(!(pick && (attach.is_some() || handle.is_some())),
            "--pick conflicts with --attach and --handle");
    ensure!(handle.is_none_or(|handle| handle != 0), "--handle requires a nonzero session ID");
    let target = resolve_terminal_target(client, target).await?;
    let shell = requested_shell(target.platform);
    let connection = connect_terminal(client, &target).await?;
    let operated = operate(&connection, list_only, attach, handle, pick, shell).await;
    let closed = connection.close().await;
    operated?;
    closed
}
/// One answer to the interactive session menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Choice {
    New,
    /// Zero-based index into the sessions the menu showed.
    Attach(usize),
    Refresh,
    Quit,
    Invalid,
}

/// Parse one line of the interactive menu. `len` is how many sessions the
/// menu just printed, so `1..=len` selects a session and `0` starts a new
/// shell. Anything else is invalid and the caller asks again.
fn parse_choice(input: &str, len: usize) -> Choice {
    let choice = input.trim();
    if choice.eq_ignore_ascii_case("q") || choice.eq_ignore_ascii_case("quit")
        || choice.eq_ignore_ascii_case("exit")
    {
        return Choice::Quit;
    }
    if choice.eq_ignore_ascii_case("r") || choice.eq_ignore_ascii_case("refresh") {
        return Choice::Refresh;
    }
    let Ok(number) = choice.parse::<u32>() else { return Choice::Invalid };
    match number {
        0 => Choice::New,
        number if number as usize <= len => Choice::Attach(number as usize - 1),
        _ => Choice::Invalid,
    }
}

/// What the user picked at the interactive prompt.
enum Picked {
    New,
    Attach(u32),
    Quit,
}

/// The numbered menu printed before prompting.
fn menu_text(sessions: &[RemoteSession]) -> String {
    let mut text = String::new();
    for (index, session) in sessions.iter().enumerate() {
        let name = if session.name.is_empty() { "(未命名)" } else { &session.name };
        text.push_str(&format!(
            "{}) {name}  {}  [{}]  编号 {}\n",
            index + 1,
            session.shell,
            session.state_label(),
            session.id
        ));
    }
    text.push_str("0) 新建 Shell\n");
    text
}

/// Split a host list into sessions the menu may offer for attach and how many
/// rows it had to hide. Session 0 is not attachable: OPEN with session 0 means
/// "start a new shell", so offering it would silently open another shell.
fn attachable_sessions(sessions: Vec<RemoteSession>) -> (Vec<RemoteSession>, usize) {
    let total = sessions.len();
    let attachable: Vec<_> = sessions.into_iter().filter(|session| session.id != 0).collect();
    let skipped = total - attachable.len();
    (attachable, skipped)
}

/// List sessions and report the attachable ones, printing why the menu cannot
/// be shown when there are none.
async fn attachable_list(terminal: &mut Terminal) -> Result<Option<Vec<RemoteSession>>> {
    let (sessions, skipped) = attachable_sessions(parse_session_list(&terminal.list().await?));
    if sessions.is_empty() {
        if skipped == 0 {
            println!("远端没有可接回的终端会话，将新建 Shell。");
        } else {
            println!("远端没有可接回的终端会话（{skipped} 个会话编号为 0，无法接回），将新建 Shell。");
        }
        return Ok(None);
    }
    Ok(Some(sessions))
}

/// List the remote sessions on the already-open channel and let the user pick
/// one to attach to, refresh, or start a new shell. EOF and `q` leave without
/// opening anything, so a closed stdin never creates a shell by accident.
async fn pick_session(terminal: &mut Terminal) -> Result<Picked> {
    let Some(mut sessions) = attachable_list(terminal).await? else {
        return Ok(Picked::New);
    };
    let mut stdin = BufReader::new(tokio::io::stdin());
    let mut stdout = tokio::io::stdout();
    loop {
        stdout.write_all(menu_text(&sessions).as_bytes()).await?;
        stdout.write_all("0 新建，r 刷新，q 退出 > ".as_bytes()).await?;
        stdout.flush().await?;
        let mut line = String::new();
        if stdin.read_line(&mut line).await.context("读取终端选择")? == 0 {
            println!();
            return Ok(Picked::Quit);
        }
        match parse_choice(&line, sessions.len()) {
            Choice::New => return Ok(Picked::New),
            Choice::Attach(index) => return Ok(Picked::Attach(sessions[index].id)),
            Choice::Refresh => match attachable_list(terminal).await? {
                None => return Ok(Picked::New),
                Some(refreshed) => {
                    sessions = refreshed;
                    println!("已刷新。");
                }
            },
            Choice::Quit => return Ok(Picked::Quit),
            Choice::Invalid => {
                println!("无效选择：{}；请输入编号、r 或 q。", line.trim());
            }
        }
    }
}

async fn operate(
    connection: &ControllerConnection, list_only: bool, attach: Option<u32>,
    handle: Option<u32>, pick: bool, shell: &str
) -> Result<()> {
    let (channel, rx) = terminal_channel(connection).await?;
    let mut terminal = Terminal::new(channel, rx);
    if list_only {
        let sessions = parse_session_list(&terminal.list().await?);
        if sessions.is_empty() { println!("远端没有终端会话"); }
        for session in sessions {
            println!("{}\t{}\t{}", session.id, session.name, session.shell);
        }
        return Ok(());
    }
    if let Some(id) = attach {
        ensure!(id != 0, "attach requires a nonzero session ID");
        let sessions = parse_session_list(&terminal.list().await?);
        ensure!(sessions.iter().any(|session| session.id == id),
                "remote terminal session no longer exists");
    }
    let attach = if pick {
        match pick_session(&mut terminal).await? {
            Picked::Quit => return Ok(()),
            Picked::New => None,
            Picked::Attach(id) => Some(id),
        }
    } else {
        attach
    };
    let (cols,rows) = terminal_size();
    // --handle 不先查清单：句柄不存在时远端会用它新建，这正是「接回上次的
    // 会话，没有就开一个」的意思。--attach 仍然要求会话确实在。
    let id = terminal.open(attach.or(handle), cols, rows, shell).await?;
    eprintln!("已连接 UU 官方远程终端（会话 {id}），Ctrl+] 分离，不结束远端 Shell。");
    interactive(&mut terminal, id).await
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn official_v1_header_and_fragmented_frames() {
        let data = encode(OPEN, 42, br#"{"option":"none"}"#).unwrap();
        assert_eq!(&data[..5], b"TERM\x01");
        assert_eq!(data[5], OPEN);
        let mut decoder = Decoder::default();
        assert!(decoder.push(&data[..7]).unwrap().is_empty());
        assert!(decoder.push(&data[7..15]).unwrap().is_empty());
        assert_eq!(decoder.push(&data[15..]).unwrap(),
            vec![Frame{kind:OPEN,session:42,payload:br#"{"option":"none"}"#.to_vec()}]);
    }
    #[test]
    fn coalesced_output_preserves_binary_bytes() {
        let mut data = encode(STDOUT, 1, b"\x1b[2J\xff\x00").unwrap();
        data.extend(encode(EXIT, 1, br#"{"exit_code":0}"#).unwrap());
        let result = Decoder::default().push(&data).unwrap();
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].payload, b"\x1b[2J\xff\x00");
        assert_eq!(result[1].kind, EXIT);
    }
    #[test]
    fn malformed_and_oversize_are_rejected() {
        let mut data = encode(STDIN, 1, b"ok").unwrap();
        data[12..16].copy_from_slice(&(MAX_PAYLOAD as u32 + 1).to_le_bytes());
        assert!(Decoder::default().push(&data).is_err());
        assert!(encode(STDIN, 1, &vec![0; MAX_PAYLOAD+1]).is_err());
        let mut coalesced = Vec::new();
        for _ in 0..MAX_FRAMES_PER_PACKET + 1 {
            coalesced.extend(encode(STDOUT, 1, b"").unwrap());
        }
        assert!(Decoder::default().push(&coalesced).is_err());
        let mut bad = encode(OPEN, 0, b"").unwrap();
        bad[4] = 2;
        assert!(Decoder::default().push(&bad).is_err());
        bad[4] = 1; bad[6] = 1;
        assert!(Decoder::default().push(&bad).is_err());
    }
    #[test]
    fn remote_error_strips_controls() {
        assert_eq!(remote_message(br#"{"message":"denied\npassword"}"#), "deniedpassword");
    }
    #[test]
    fn session_list_parses_official_rows() {
        let rows = vec![
            json!({
                "session_id": 17, "name": "work", "shell": "/bin/bash",
                "state": "running", "created_at_ms": 1_700_000_000_000i64,
                "last_active_ms": 1_700_000_001_000i64,
            }),
            json!({"session_id": "23", "name": "build", "state": "detached"}),
        ];
        assert_eq!(parse_session_list(&rows), vec![
            RemoteSession {
                id: 17, name: "work".into(), shell: "/bin/bash".into(),
                state: "running".into(),
                created_ms: 1_700_000_000_000, last_active_ms: 1_700_000_001_000,
            },
            RemoteSession {
                id: 23, name: "build".into(), shell: "unknown".into(),
                state: "detached".into(), created_ms: 0, last_active_ms: 0,
            },
        ]);
    }
    #[test]
    fn session_list_skips_unusable_rows_but_keeps_session_zero() {
        let rows = vec![
            json!({"name": "missing id"}),
            json!({"session_id": 0, "name": "windows", "state": "running"}),
            json!({"session_id": 4_294_967_296u64}),
            json!("not an object"),
        ];
        let sessions = parse_session_list(&rows);
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].id, 0);
        assert_eq!(sessions[0].state_label(), "运行中");
    }
    #[test]
    fn the_open_request_carries_the_official_shell_and_geometry() {
        assert_eq!(requested_shell(1), "powershell");
        assert_eq!(requested_shell(4), "zsh");
        assert_eq!(requested_shell(2), "powershell");
        let payload: serde_json::Value =
            serde_json::from_str(&open_payload("powershell", 0, 9999)).unwrap();
        assert_eq!(payload["shell"], "powershell");
        assert_eq!(payload["cols"], 1);
        assert_eq!(payload["rows"], 256);
        assert_eq!(payload["option"], "none");
    }

    /// The official controller sends ListSessionsReq with session id 0.
    #[test]
    fn list_requests_use_session_zero() {
        assert_eq!(LIST_SESSION, 0);
    }

    #[test]
    fn session_state_labels_cover_known_and_unknown_states() {
        let session = |state: &str| RemoteSession {
            id: 1, name: String::new(), shell: String::new(),
            state: state.to_owned(), created_ms: 0, last_active_ms: 0,
        };
        assert_eq!(session("running").state_label(), "运行中");
        assert_eq!(session("detached").state_label(), "已分离");
        assert_eq!(session("exited").state_label(), "已退出");
        assert_eq!(session("something-new").state_label(), "未知");
    }
    #[test]
    fn menu_choice_parses_numbers_refresh_and_quit() {
        assert_eq!(parse_choice("0", 3), Choice::New);
        assert_eq!(parse_choice(" 1 ", 3), Choice::Attach(0));
        assert_eq!(parse_choice("3", 3), Choice::Attach(2));
        assert_eq!(parse_choice("4", 3), Choice::Invalid);
        assert_eq!(parse_choice("0", 0), Choice::New);
        assert_eq!(parse_choice("1", 0), Choice::Invalid);
        assert_eq!(parse_choice("r", 3), Choice::Refresh);
        assert_eq!(parse_choice("R", 3), Choice::Refresh);
        assert_eq!(parse_choice("refresh", 3), Choice::Refresh);
        assert_eq!(parse_choice("q", 3), Choice::Quit);
        assert_eq!(parse_choice("quit", 3), Choice::Quit);
        assert_eq!(parse_choice("", 3), Choice::Invalid);
        assert_eq!(parse_choice("-1", 3), Choice::Invalid);
        assert_eq!(parse_choice("abc", 3), Choice::Invalid);
    }
    #[test]
    fn session_zero_is_never_offered_for_attach() {
        let rows = vec![
            json!({"session_id": 0, "name": "windows"}),
            json!({"session_id": 3, "name": "work", "state": "running"}),
        ];
        let (sessions, skipped) = attachable_sessions(parse_session_list(&rows));
        assert_eq!(skipped, 1);
        assert_eq!(sessions.iter().map(|session| session.id).collect::<Vec<_>>(), vec![3]);
        // 只有编号 0 时菜单为空，调用方会直接新建 Shell。
        let (sessions, skipped) = attachable_sessions(parse_session_list(&rows[..1]));
        assert!(sessions.is_empty());
        assert_eq!(skipped, 1);
    }
    #[test]
    fn menu_lists_sessions_and_the_new_shell_choice() {
        let sessions = vec![RemoteSession {
            id: 17, name: "work".into(), shell: "/bin/bash".into(),
            state: "running".into(), created_ms: 0, last_active_ms: 0,
        }];
        assert_eq!(
            menu_text(&sessions),
            "1) work  /bin/bash  [运行中]  编号 17\n0) 新建 Shell\n"
        );
    }
}
