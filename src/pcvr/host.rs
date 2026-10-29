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
    #[serde(default)]
    calibration: Option<super::device::Calibration>,
}
static OWNED: AtomicBool = AtomicBool::new(false);
#[derive(Default)]
struct State {
    socket: Option<UnixDatagram>,
    broker: Option<super::device::Broker>,
    publisher_lock: Option<std::fs::File>,
    sequence: u64,
    frames: u64,
    last: Option<Instant>,
    backend: Option<&'static str>,
}
pub(crate) struct Session {
    lease: Lease,
    connected: Arc<AtomicBool>,
    state: Mutex<State>,
    tracking_ready: AtomicBool,
    video: Mutex<Option<std::sync::Weak<RTCDataChannel>>>,
    keyframe_requested: AtomicBool,
    video_send: tokio::sync::Mutex<()>,
}
impl Session {
    pub(crate) fn new(lease: Lease, connected: Arc<AtomicBool>) -> Arc<Self> {
        Arc::new(Self {
            lease,
            connected,
            state: Mutex::new(State::default()),
            tracking_ready: AtomicBool::new(false),
            video: Mutex::new(None),
            keyframe_requested: AtomicBool::new(true),
            video_send: tokio::sync::Mutex::new(()),
        })
    }
    fn allowed(&self) -> bool {
        self.lease.terminal_allowed() && self.connected.load(Ordering::Acquire)
    }
    fn stop(&self) {
        let mut state = lock(&self.state);
        if state.socket.take().is_some() || state.broker.take().is_some() {
            OWNED.store(false, Ordering::Release);
        }
        *state = State::default();
        self.keyframe_requested.store(true, Ordering::Release);
    }
    fn start(&self, calibration: Option<&super::device::Calibration>) -> Result<()> {
        ensure!(
            self.allowed(),
            "当前会话未获远程终端授权，不能启用 VR 追踪桥接"
        );
        ensure!(
            self.tracking_ready.load(Ordering::Acquire),
            "追踪数据通道尚未就绪"
        );
        let mut state = lock(&self.state);
        if state.socket.is_some() || state.broker.is_some() {
            return Ok(());
        }
        let root = std::path::PathBuf::from(
            std::env::var_os("XDG_RUNTIME_DIR").context("缺少 XDG_RUNTIME_DIR")?,
        );
        let preference = super::runtime::preference()?;
        if preference != super::runtime::Preference::SteamVr
            && super::runtime::device_component_available()?
        {
            let calibration = calibration.context("PICO 尚未上报真实眼位/FOV；请唤醒头显后重试")?;
            calibration.validate()?;
            ensure!(
                OWNED
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                    .is_ok(),
                "另一个会话正在使用内置 VR"
            );
            match super::device::Broker::create(&root, calibration.clone()) {
                Ok(broker) => {
                    state.broker = Some(broker);
                    state.backend = Some("openxr-pico-device");
                    return Ok(());
                }
                Err(error) => {
                    OWNED.store(false, Ordering::Release);
                    return Err(error);
                }
            }
        }
        ensure!(
            preference != super::runtime::Preference::OpenXrOnly,
            "PICO OpenXR 设备组件尚未安装；仅 OpenXR 模式不会回退 SteamVR"
        );
        let path = super::runtime::steam_tracking_socket(&root)?;
        let backend = "steamvr-tracking-fallback";
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
        state.backend = Some(backend);
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
        if let Some(broker) = state.broker.as_mut() {
            if broker.publish(data, true).is_ok() {
                state.sequence = sequence;
                state.frames += 1;
                state.last = Some(Instant::now());
            }
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
    /// Frame producer entry point; not called until a real GPU backend owns the session.
    /// Never queues old access units behind a slow peer.
    pub(crate) async fn send_stereo(&self, frame: &super::stereo::Frame) -> Result<()> {
        ensure!(
            self.allowed() && {
                let state = lock(&self.state);
                state.socket.is_some() || state.broker.is_some()
            },
            "stereo session is not authorized/started"
        );
        let video = lock(&self.video)
            .as_ref()
            .and_then(std::sync::Weak::upgrade)
            .context("stereo media channel is absent")?;
        ensure!(
            video.ready_state()
                == webrtc::data_channel::data_channel_state::RTCDataChannelState::Open,
            "stereo media channel is not open"
        );
        ensure!(
            !self.keyframe_requested.load(Ordering::Acquire) || frame.keyframe,
            "stereo recovery requires an IDR"
        );
        let _sending = self
            .video_send
            .try_lock()
            .context("stereo frame producer is already sending")?;
        let packets = frame.fragments()?;
        if frame.keyframe {
            self.keyframe_requested.store(false, Ordering::Release);
        }
        let deadline = tokio::time::Instant::now() + Duration::from_millis(16);
        for packet in packets {
            let started = {
                let state = lock(&self.state);
                state.socket.is_some() || state.broker.is_some()
            };
            if !self.allowed() || !started || video.buffered_amount().await > 256 * 1024 {
                self.keyframe_requested.store(true, Ordering::Release);
                anyhow::bail!("stereo deadline/backpressure drop");
            }
            match tokio::time::timeout_at(deadline, video.send(&bytes::Bytes::from(packet))).await {
                Ok(Ok(_)) => {}
                _ => {
                    self.keyframe_requested.store(true, Ordering::Release);
                    anyhow::bail!("stereo media send failed/deadline expired");
                }
            }
        }
        Ok(())
    }
    pub(crate) fn bind(self: &Arc<Self>, channel: Arc<RTCDataChannel>, parent: CancellationToken) {
        if channel.label() == super::stereo::CHANNEL {
            *lock(&self.video) = Some(Arc::downgrade(&channel));
            let this = self.clone();
            channel.on_close(Box::new(move || {
                this.stop();
                *lock(&this.video) = None;
                Box::pin(async {})
            }));
            return;
        }
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
            if !m.is_string || m.data.len() > 2048 {
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
                        Some("start") => this
                            .start(
                                request
                                    .as_ref()
                                    .ok()
                                    .and_then(|r| r.as_ref())
                                    .and_then(|r| r.calibration.as_ref()),
                            )
                            .err()
                            .map(|e| e.to_string()),
                        Some("video-keyframe") => {
                            this.keyframe_requested.store(true, Ordering::Release);
                            None
                        }
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
                    serde_json::json!({"version":1,"id":id,"tracking":(state.socket.is_some() || state.broker.is_some()),"backend":state.backend,"stereo":false,"frames":state.frames,"fresh":state.last.is_some_and(|t|t.elapsed()<Duration::from_millis(250)),"error":last_error,"message":"内置追踪诊断；双眼画面尚未实现，不启动 VRChat"})
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

#[cfg(test)]
mod stereo_async_tests {
    use super::*;
    #[test]
    fn stereo_sender_can_run_on_the_peer_executor() {
        fn prove(session: &Session, frame: &super::super::stereo::Frame) {
            fn assert_send<T: Send>(_: T) {}
            assert_send(session.send_stereo(frame));
        }
        let _ = prove;
    }
}
