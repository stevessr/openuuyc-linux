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
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, sync::broadcast};
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
const STDOUT: u8 = 5;
const EXIT: u8 = 6;
const ERROR: u8 = 8;
const LIST_REQ: u8 = 9;
const LIST_RESP: u8 = 10;
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
    next_request: u32,
}
impl Terminal {
    fn new(channel: Arc<RTCDataChannel>, inbound: broadcast::Receiver<Vec<u8>>) -> Self {
        Self { channel, inbound, decoder: Decoder::default(),
               ready: VecDeque::new(), events: VecDeque::new(), next_request: 1 }
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
        self.send(kind, session, body).await?;
        tokio::time::timeout(Duration::from_secs(20), async {
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
        let request_id = self.next_request;
        self.next_request = self.next_request.wrapping_add(1).max(1);
        let frame = self.request(LIST_REQ, LIST_RESP, request_id, &[]).await?;
        let rows: Vec<Value> = serde_json::from_slice(&frame.payload)
            .context("remote terminal list response is not a JSON array")?;
        ensure!(rows.len() <= 512, "remote terminal session list too large");
        Ok(rows)
    }
    async fn open(&mut self, session: Option<u32>, cols: u16, rows: u16) -> Result<u32> {
        let options = json!({"cols":cols.clamp(1,512), "rows":rows.clamp(1,256), "option":"none"});
        let frame = self.request(
            OPEN, OPEN_RESP, session.unwrap_or(0), options.to_string().as_bytes()
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

/// Never forcibly take over an existing controller. The host decides whether
/// this authenticated UU account is allowed to open remote shell sessions.
pub async fn run_saved(target: String, list_only: bool, attach: Option<u32>) -> Result<()> {
    let client = AuthenticatedClient::from_saved_session()?;
    let result = run_with_client(&client, &target, list_only, attach).await;
    client.close().await;
    result
}
async fn run_with_client(
    client: &AuthenticatedClient, target: &str, list_only: bool, attach: Option<u32>
) -> Result<()> {
    ensure!(!(list_only && attach.is_some()), "--list conflicts with --attach");
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
    ensure!(device.participant_count() == 0,
            "device already has a controller; refusing implicit takeover");
    let controller_id = devices.current_device.validated_device_id()?.to_owned();
    let device_id = device.validated_device_id()?.to_owned();
    let profile = ConnectionMediaOptions::default()
        .resolve(crate::media::detect_local_display().unwrap_or(LocalDisplayInfo::FALLBACK))?;
    let policy = client.feature_catalog().policy(device.platform, &device.version_name);
    let room = client.join_device(&device_id, false).await?;
    let cancel = CancellationToken::new();
    let connection = ControllerConnection::establish(
        room, &controller_id, profile, policy,
        ControlConnectType::Normal, TransportChoice::Auto, None,
        crate::media::audio::AudioSettings { volume: 0, muted: true },
        None, &cancel, ControlPurpose::Terminal
    ).await.context("establish official UU terminal session")?;
    let operated = operate(&connection, list_only, attach).await;
    let closed = connection.close().await;
    operated?;
    closed
}
async fn operate(connection: &ControllerConnection, list_only: bool, attach: Option<u32>) -> Result<()> {
    let (channel, rx) = connection.peer.terminal_transport()?;
    tokio::time::timeout(Duration::from_secs(12), async {
        while channel.ready_state() != RTCDataChannelState::Open {
            ensure!(channel.ready_state() != RTCDataChannelState::Closed, "terminal channel closed");
            tokio::time::sleep(Duration::from_millis(40)).await;
        }
        Ok::<(), anyhow::Error>(())
    }).await.context("terminal data channel open timeout")??;
    let mut terminal = Terminal::new(channel, rx);
    if list_only {
        let rows = terminal.list().await?;
        if rows.is_empty() { println!("远端没有终端会话"); }
        for row in rows {
            let id = row.get("session_id").and_then(Value::as_u64).unwrap_or(0);
            let name = row.get("name").and_then(Value::as_str).unwrap_or("");
            let shell = row.get("shell").and_then(Value::as_str).unwrap_or("unknown");
            println!("{id}\t{}\t{}", name.replace(['\r','\n','\t'], " "), shell.replace(['\r','\n','\t'], " "));
        }
        return Ok(());
    }
    if let Some(id) = attach {
        ensure!(id != 0, "attach requires a nonzero session ID");
        let sessions = terminal.list().await?;
        ensure!(sessions.iter().any(|s| s.get("session_id").and_then(Value::as_u64) == Some(id as u64)),
                "remote terminal session no longer exists");
    }
    let (cols,rows) = terminal_size();
    let id = terminal.open(attach, cols, rows).await?;
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
}
