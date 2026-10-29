//! Authenticated per-peer ownership of the in-process PCVR tracking bridge.
use super::protocol;
use crate::features::host::{Lease, lock};
use anyhow::{Context, Result, ensure};
use std::{
    os::unix::{
        fs::{FileTypeExt, MetadataExt},
        net::UnixDatagram,
    },
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;
use webrtc::data_channel::RTCDataChannel;
pub(crate) const CONTROL: &str = "OPENUUYC_VR_CONTROL_V1";
pub(crate) const TRACKING: &str = "OPENUUYC_VR_TRACKING_V1";
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct ControlRequest {
    id: u32,
    op: String,
}
static OWNED: AtomicBool = AtomicBool::new(false);
#[derive(Default)]
struct State {
    socket: Option<UnixDatagram>,
    publisher_lock: Option<std::fs::File>,
    sequence: u64,
    frames: u64,
    last: Option<Instant>,
}
pub(crate) struct Session {
    lease: Lease,
    connected: Arc<AtomicBool>,
    state: Mutex<State>,
    tracking_ready: AtomicBool,
}
impl Session {
    pub(crate) fn new(lease: Lease, connected: Arc<AtomicBool>) -> Arc<Self> {
        Arc::new(Self {
            lease,
            connected,
            state: Mutex::new(State::default()),
            tracking_ready: AtomicBool::new(false),
        })
    }
    fn allowed(&self) -> bool {
        self.lease.terminal_allowed() && self.connected.load(Ordering::Acquire)
    }
    fn stop(&self) {
        let mut state = lock(&self.state);
        if state.socket.take().is_some() {
            OWNED.store(false, Ordering::Release);
        }
        *state = State::default();
    }
    fn start(&self) -> Result<()> {
        ensure!(
            self.allowed(),
            "当前会话未获远程终端授权，不能启用 VR 追踪桥接"
        );
        ensure!(
            self.tracking_ready.load(Ordering::Acquire),
            "追踪数据通道尚未就绪"
        );
        let mut state = lock(&self.state);
        if state.socket.is_some() {
            return Ok(());
        }
        let path = std::path::PathBuf::from(
            std::env::var_os("XDG_RUNTIME_DIR").context("缺少 XDG_RUNTIME_DIR")?,
        )
        .join("openuuyc-pcvr/tracking.sock");
        let directory = std::fs::symlink_metadata(path.parent().unwrap())?;
        // The socket is local-only and private to this uid, never a public UDP port.
        let uid = unsafe { libc::getuid() };
        ensure!(
            directory.is_dir() && directory.uid() == uid && directory.mode() & 0o077 == 0,
            "VR 运行目录不安全"
        );
        let metadata = std::fs::symlink_metadata(&path)
            .context("内置驱动尚未就绪：先安装 OpenUUYC pico-vr builtin-install，再重启 SteamVR")?;
        ensure!(
            metadata.file_type().is_socket()
                && metadata.uid() == uid
                && metadata.mode() & 0o077 == 0,
            "VR socket 不安全"
        );
        use std::os::unix::{fs::OpenOptionsExt, io::AsRawFd};
        let publisher = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path.parent().unwrap().join("publisher.lock"))?;
        let meta = publisher.metadata()?;
        ensure!(
            meta.is_file() && meta.uid() == uid && meta.mode() & 0o077 == 0,
            "发布锁不安全"
        );
        ensure!(
            unsafe { libc::flock(publisher.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
            "其他 OpenUUYC 进程正在使用内置 VR"
        );
        let socket = UnixDatagram::unbound()?;
        socket.set_nonblocking(true)?;
        socket.connect(path)?;
        ensure!(
            OWNED
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_ok(),
            "另一个会话正在使用内置 VR"
        );
        state.socket = Some(socket);
        state.publisher_lock = Some(publisher);
        state.sequence = 0;
        state.frames = 0;
        state.last = None;
        Ok(())
    }
    fn frame(&self, data: &[u8]) {
        if !self.allowed() {
            self.stop();
            return;
        }
        let Ok(sequence) = protocol::sequence(data) else {
            return;
        };
        let mut state = lock(&self.state);
        if sequence <= state.sequence {
            return;
        }
        let Some(socket) = state.socket.as_ref() else {
            return;
        };
        match socket.send(data) {
            Ok(protocol::FRAME_BYTES) => {
                state.sequence = sequence;
                state.frames += 1;
                state.last = Some(Instant::now());
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
            _ => {
                state.socket = None;
                state.publisher_lock = None;
                OWNED.store(false, Ordering::Release);
            }
        }
    }
    pub(crate) fn bind(self: &Arc<Self>, channel: Arc<RTCDataChannel>, parent: CancellationToken) {
        if channel.label() == TRACKING {
            self.tracking_ready.store(true, Ordering::Release);
            let this = self.clone();
            channel.on_message(Box::new(move |message| {
                if !message.is_string {
                    this.frame(&message.data);
                }
                Box::pin(async {})
            }));
            let this = self.clone();
            channel.on_close(Box::new(move || {
                this.tracking_ready.store(false, Ordering::Release);
                this.stop();
                Box::pin(async {})
            }));
            return;
        }
        let stop = parent.child_token();
        let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(4);
        let invalid = stop.clone();
        channel.on_message(Box::new(move |m| {
            if !m.is_string || m.data.len() > 256 {
                invalid.cancel();
                return Box::pin(async {});
            }
            match std::str::from_utf8(&m.data) {
                Ok(s) => {
                    if tx.try_send(s.to_owned()).is_err() {
                        invalid.cancel()
                    }
                }
                Err(_) => invalid.cancel(),
            }
            Box::pin(async {})
        }));
        let closed = stop.clone();
        channel.on_close(Box::new(move || {
            closed.cancel();
            Box::pin(async {})
        }));
        let this = self.clone();
        let weak = Arc::downgrade(&channel);
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            let mut last_error: Option<String> = None;
            loop {
                let request = tokio::select! {_=stop.cancelled()=>break,_=tick.tick()=>None,r=rx.recv()=>match r {Some(s)=>Some(s),None=>break}};
                if !this.allowed() {
                    this.stop();
                }
                let request = request
                    .as_deref()
                    .map(serde_json::from_str::<ControlRequest>)
                    .transpose();
                let (id, op) = match &request {
                    Ok(Some(r)) => (r.id, Some(r.op.as_str())),
                    _ => (0, None),
                };
                if let Err(e) = &request {
                    last_error = Some(format!("VR 请求无效：{e}"));
                }
                if op.is_some() {
                    last_error = match op {
                        Some("start") => this.start().err().map(|e| e.to_string()),
                        Some("stop") => {
                            this.stop();
                            None
                        }
                        None => None,
                        _ => Some("未知 VR 控制请求".into()),
                    };
                }
                let status = {
                    let state = lock(&this.state);
                    serde_json::json!({"version":1,"id":id,"tracking":state.socket.is_some(),"stereo":false,"frames":state.frames,"fresh":state.last.is_some_and(|t|t.elapsed()<Duration::from_millis(250)),"error":last_error,"message":"内置追踪诊断；双眼画面尚未实现，不启动 VRChat"})
                };
                let Some(ch) = weak.upgrade() else { break };
                if ch.ready_state()
                    == webrtc::data_channel::data_channel_state::RTCDataChannelState::Open
                    && ch.send_text(status.to_string()).await.is_err()
                {
                    break;
                }
            }
            this.stop();
        });
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        self.stop();
    }
}
