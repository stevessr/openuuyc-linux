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
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
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
    #[serde(default)]
    generation: u32,
    #[serde(default)]
    backend: Option<String>,
}
/// What a `start` request asks for. The default keeps the configured backend
/// policy; `steamvr` is an explicit attach to a running SteamVR scene and
/// never degrades silently into the tracking-only fallback.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Requested {
    Default,
    SteamVr,
}
impl Requested {
    fn parse(request: Option<&ControlRequest>) -> Result<Self> {
        match request.and_then(|r| r.backend.as_deref()) {
            None => Ok(Self::Default),
            Some("steamvr") => Ok(Self::SteamVr),
            Some(other) => anyhow::bail!("不支持的后端请求 {other:?}"),
        }
    }
}
/// Whether this session currently owns a real VR producer of any backend.
fn started(state: &State) -> bool {
    state.socket.is_some() || state.broker.is_some() || state.attach.is_some()
}
/// What a `start` request means while this session already runs.
#[derive(Debug, PartialEq, Eq)]
enum Running {
    /// The same live attach, or any default start: only acknowledge it.
    Confirm,
    /// The SteamVR helper ended and must be swapped for a new one.
    Replace,
    /// A tracking-only session: an explicit stop comes first.
    Refuse,
}
fn running(backend: Requested, attach: Option<bool>) -> Running {
    match (backend, attach) {
        (Requested::Default, _) => Running::Confirm,
        (Requested::SteamVr, Some(true)) => Running::Confirm,
        (Requested::SteamVr, Some(false)) => Running::Replace,
        (Requested::SteamVr, None) => Running::Refuse,
    }
}
/// Whether `start_mirror` begins a session's first attach or replaces a
/// helper that ended. Ownership, the running-session check and the failure
/// semantics differ: a fresh attach that fails leaves nothing owned, while a
/// failed replacement ends the degraded session instead of leaving it
/// half-configured with a stale backend.
#[derive(Clone, Copy, PartialEq, Eq)]
enum MirrorStart {
    Fresh,
    Replace,
}
fn tracking_fresh(last: Option<Instant>) -> bool {
    last.is_some_and(|time| time.elapsed() < Duration::from_millis(250))
}
fn video_recent(last: Option<(u32, Instant)>, generation: u32) -> bool {
    generation > 0
        && last.is_some_and(|(epoch, time)| {
            epoch == generation && time.elapsed() < Duration::from_millis(500)
        })
}
static NEXT_GENERATION: AtomicU32 = AtomicU32::new(1);
static OWNED: AtomicBool = AtomicBool::new(false);
#[derive(Default)]
struct State {
    socket: Option<UnixDatagram>,
    broker: Option<super::device::Broker>,
    export: Option<super::capture::Export>,
    /// Live background attach to an existing SteamVR scene, if any.
    attach: Option<super::steamvr::Attach>,
    generation: u32,
    publisher_lock: Option<std::fs::File>,
    sequence: u64,
    frames: u64,
    last: Option<Instant>,
    backend: Option<&'static str>,
    head_valid: bool,
    /// Tracked head pose in the headset's LOCAL space, with `last` freshness.
    head: Option<[f32; 7]>,
    /// Per-generation registration of an external compositor's space.
    align: Option<(u32, super::align::Align)>,
    /// The current generation's eyes come from an external compositor.
    external: bool,
    video_rate: super::pacing::Rate,
    tracking_version: u32,
    captured_frames: u64,
    encoded_frames: u64,
    producer_error: Option<&'static str>,
}
pub(crate) struct Session {
    lease: Lease,
    connected: Arc<AtomicBool>,
    state: Mutex<State>,
    tracking_ready: AtomicBool,
    video: Mutex<Option<std::sync::Weak<RTCDataChannel>>>,
    control: Mutex<Option<std::sync::Weak<RTCDataChannel>>>,
    configured_video: Mutex<Option<(u32, u16, u16)>>,
    video_ack: AtomicU32,

    keyframe_requested: AtomicBool,
    video_send: tokio::sync::Mutex<()>,
    producer: tokio::sync::mpsc::Sender<super::stereo::Frame>,
    sent_video_frames: AtomicU64,
    last_video: Mutex<Option<(u32, Instant)>>,
}
impl Session {
    pub(crate) fn new(lease: Lease, connected: Arc<AtomicBool>) -> Arc<Self> {
        let (producer, mut frames) = tokio::sync::mpsc::channel::<super::stereo::Frame>(2);
        let session = Arc::new(Self {
            lease,
            connected,
            state: Mutex::new(State::default()),
            tracking_ready: AtomicBool::new(false),
            video: Mutex::new(None),
            control: Mutex::new(None),
            configured_video: Mutex::new(None),
            video_ack: AtomicU32::new(0),
            keyframe_requested: AtomicBool::new(true),
            video_send: tokio::sync::Mutex::new(()),
            producer,
            sent_video_frames: AtomicU64::new(0),
            last_video: Mutex::new(None),
        });
        let weak = Arc::downgrade(&session);
        tokio::spawn(async move {
            while let Some(frame) = frames.recv().await {
                let Some(session) = weak.upgrade() else { break };
                if let Err(error) = session.send_stereo(&frame).await {
                    session.keyframe_requested.store(true, Ordering::Release);
                    tracing::debug!(%error,"stereo producer frame dropped");
                }
            }
        });
        let weak = Arc::downgrade(&session);
        std::thread::spawn(move || {
            let mut generation = 0;
            let mut previous = 0;
            let mut encoder: Option<super::encode::Encoder> = None;
            // When the last real export frame arrived, for stall diagnostics.
            let mut last_export = Instant::now();
            while let Some(session) = weak.upgrade() {
                let began = Instant::now();
                let (rate, source, attach_failure, epoch, mirrored) = {
                    let mut state = lock(&session.state);
                    // A dead helper reports its real failure; a live one that
                    // cannot produce frames yet reports why, instead of leaving
                    // the headset waiting without a reason. Both clear as soon
                    // as a frame is really encoded.
                    let attach_failure = match state.attach.as_mut() {
                        None => None,
                        Some(attach) => match attach.observe_failure() {
                            Some(reason) => Some(reason),
                            None => attach.observe_wait(),
                        },
                    };
                    let source = state
                        .export
                        .as_ref()
                        .filter(|_| state.head_valid && tracking_fresh(state.last))
                        .and_then(|export| export.file.try_clone().ok())
                        .map(|f| (state.generation, f));
                    let mirrored = state.attach.is_some();
                    (
                        state.video_rate,
                        source,
                        attach_failure,
                        state.generation,
                        mirrored,
                    )
                };
                if let Some(reason) = attach_failure {
                    session.producer_status(epoch, Some(reason), false, false);
                }
                if session.allowed() {
                    if let Some((epoch, file)) = source {
                        if epoch != generation {
                            generation = epoch;
                            previous = 0;
                            last_export = Instant::now();
                            encoder = match super::encode::Encoder::new(epoch, rate.fps) {
                                Ok(encoder) => Some(encoder),
                                Err(error) => {
                                    tracing::warn!(%error,"PCVR encoder initialization failed");
                                    session.producer_status(
                                        epoch,
                                        Some("双眼编码器初始化失败"),
                                        false,
                                        false,
                                    );
                                    None
                                }
                            };
                        }
                        let mut new_frame = false;
                        let raw = match super::capture::read_latest(&file, previous) {
                            Ok(Some(raw)) if !super::capture::fresh(raw.predicted_time) => {
                                previous = raw.sequence;
                                session.keyframe_requested.store(true, Ordering::Release);
                                session.producer_status(
                                    epoch,
                                    Some("合成器双眼帧已过期，等待新画面"),
                                    false,
                                    false,
                                );
                                None
                            }
                            Ok(Some(raw)) => {
                                new_frame = true;
                                Some(raw)
                            }
                            Ok(None) => None,
                            Err(error) => {
                                session.producer_status(
                                    epoch,
                                    Some("合成器双眼帧读取或校验失败"),
                                    false,
                                    false,
                                );
                                tracing::debug!(%error,"PCVR compositor export rejected");
                                None
                            }
                        };
                        if new_frame {
                            last_export = Instant::now();
                        } else if !mirrored
                            && last_export.elapsed() > Duration::from_secs(5)
                            && super::capture::never_published(&file)
                        {
                            // The owned compositor has not written a single
                            // complete frame: say so instead of only "waiting",
                            // because the application is probably not rendering
                            // on this runtime at all.
                            session.producer_status(
                                epoch,
                                Some("合成器尚未导出任何双眼帧：目标应用可能未运行在内置 OpenXR 组件上，或尚未提交画面"),
                                false,
                                false,
                            );
                        }
                        let raw = raw.and_then(|raw| session.aligned_frame(epoch, raw));
                        if let Some(raw) = raw {
                            previous = raw.sequence;
                            session.producer_status(epoch, None, true, false);
                            let cancel = Arc::new(AtomicBool::new(false));
                            if let Some(encoder) = encoder.as_mut() {
                                match encoder.encode(
                                    raw,
                                    session.stereo_keyframe_requested(),
                                    &cancel,
                                ) {
                                    Ok(Some(frame)) => {
                                        session.producer_status(epoch, None, false, true);
                                        let _ = session.submit_stereo(frame);
                                    }
                                    Ok(None) => {}
                                    Err(error) => {
                                        session.keyframe_requested.store(true, Ordering::Release);
                                        session.producer_status(
                                            epoch,
                                            Some("双眼视频编码失败"),
                                            false,
                                            false,
                                        );
                                        tracing::debug!(%error,"stereo encode failed");
                                    }
                                }
                            }
                        }
                    } else {
                        // The receiver may discard references while tracking is absent.
                        session.keyframe_requested.store(true, Ordering::Release);
                    }
                } else {
                    session.stop();
                }
                drop(session);
                let remaining = rate.remaining(began.elapsed());
                if !remaining.is_zero() {
                    std::thread::sleep(remaining);
                }
            }
        });
        let weak = Arc::downgrade(&session);
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_millis(10));
            loop {
                tick.tick().await;
                let Some(session) = weak.upgrade() else { break };
                if !session.allowed() {
                    continue;
                }
                let (generation, pulses) = {
                    let state = lock(&session.state);
                    (
                        state.generation,
                        state
                            .broker
                            .as_ref()
                            .map(|b| b.haptics())
                            .unwrap_or([None, None]),
                    )
                };
                let channel = lock(&session.control)
                    .as_ref()
                    .and_then(std::sync::Weak::upgrade);
                if let Some(channel) = channel {
                    for pulse in pulses.into_iter().flatten() {
                        if !session.allowed() || lock(&session.state).generation != generation {
                            break;
                        }
                        let request = serde_json::json!({"type":"haptic","generation":generation,"hand":pulse.hand,"amplitude":pulse.amplitude,"frequency":pulse.frequency,"duration_ms":pulse.duration_ms});
                        let _ = tokio::time::timeout(
                            Duration::from_millis(16),
                            channel.send_text(request.to_string()),
                        )
                        .await;
                    }
                }
            }
        });
        session
    }
    fn producer_status(
        &self,
        generation: u32,
        error: Option<&'static str>,
        captured: bool,
        encoded: bool,
    ) {
        let mut state = lock(&self.state);
        if state.generation != generation {
            return;
        }
        state.captured_frames += u64::from(captured);
        state.encoded_frames += u64::from(encoded);
        // A successful capture alone cannot clear a persistent encoder failure.
        if error.is_some() || encoded {
            state.producer_error = error;
        }
    }
    /// Real compositor/encoder producers must never block the render thread.
    /// Overflow invalidates interframes until the encoder supplies a new IDR.
    pub(crate) fn submit_stereo(&self, frame: super::stereo::Frame) -> Result<()> {
        frame.validate()?;
        ensure!(self.allowed(), "stereo producer permission revoked");
        ensure!(
            lock(&self.state).generation == frame.generation,
            "stale stereo producer generation"
        );
        if self.producer.try_send(frame).is_err() {
            self.keyframe_requested.store(true, Ordering::Release);
            anyhow::bail!("stereo producer queue is full or closed");
        }
        Ok(())
    }
    pub(crate) fn stereo_keyframe_requested(&self) -> bool {
        self.keyframe_requested.load(Ordering::Acquire)
    }
    fn stereo_streaming(&self) -> bool {
        if !self.allowed() {
            return false;
        }
        let generation = {
            let state = lock(&self.state);
            if !state.head_valid || !tracking_fresh(state.last) {
                return false;
            }
            state.generation
        };
        video_recent(*lock(&self.last_video), generation)
    }
    fn allowed(&self) -> bool {
        self.lease.app_launch_allowed() && self.connected.load(Ordering::Acquire)
    }
    fn stop(&self) {
        *lock(&self.last_video) = None;
        *lock(&self.configured_video) = None;
        self.video_ack.store(0, Ordering::Release);
        self.keyframe_requested.store(true, Ordering::Release);
        let mut state = lock(&self.state);
        let socket = state.socket.take().is_some();
        let broker = state.broker.take().is_some();
        let attach = state.attach.take();
        let attached = attach.is_some();
        *state = State::default();
        drop(state);
        // Signal the helper outside the state lock: it may need a moment to
        // release its GL shared textures and must not stall other threads.
        drop(attach);
        if socket || broker || attached {
            OWNED.store(false, Ordering::Release);
        }
        self.keyframe_requested.store(true, Ordering::Release);
    }
    fn start(
        &self,
        calibration: Option<&super::device::Calibration>,
        backend: Requested,
    ) -> Result<()> {
        ensure!(
            self.allowed(),
            "当前会话未获应用快捷启动授权，不能启用 VR 桥接"
        );
        ensure!(
            self.tracking_ready.load(Ordering::Acquire),
            "追踪数据通道尚未就绪"
        );
        let mut state = lock(&self.state);
        if started(&state) {
            let live = state
                .attach
                .as_mut()
                .map(|attach| attach.observe_failure().is_none());
            match running(backend, live) {
                Running::Confirm => return Ok(()),
                Running::Refuse => anyhow::bail!(
                    "当前 VR 连接已在运行（仅追踪）；请先停止再附加 SteamVR 场景"
                ),
                Running::Replace => {
                    // The helper ended; a repeated request swaps in a new one.
                    drop(state);
                    return self.start_mirror(MirrorStart::Replace);
                }
            }
        }
        if backend == Requested::SteamVr {
            // The helper handshake runs outside the state lock: a slow or
            // absent SteamVR must not stall tracking, status or the worker.
            drop(state);
            return self.start_mirror(MirrorStart::Fresh);
        }
        let root = std::path::PathBuf::from(
            std::env::var_os("XDG_RUNTIME_DIR").context("缺少 XDG_RUNTIME_DIR")?,
        );
        let preference = super::runtime::preference()?;
        if preference != super::runtime::Preference::SteamVr
            && super::runtime::device_component_available()?
        {
            ensure!(
                super::runtime::stereo_component_available()?,
                "当前 OpenXR 组件没有双眼读回出口，请安装 stereo_cpu_v2 组件"
            );
            let calibration = calibration.context("PICO 尚未上报真实眼位/FOV；请唤醒头显后重试")?;
            calibration.validate()?;
            let video_rate = super::pacing::Rate::new(calibration.refresh_hz)?;
            let tracking_version = if super::runtime::controller_component_available()? {
                2
            } else {
                1
            };
            ensure!(
                OWNED
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                    .is_ok(),
                "另一个会话正在使用内置 VR"
            );
            match super::device::Broker::create(&root, calibration.clone()) {
                Ok(broker) => {
                    let generation = NEXT_GENERATION.fetch_add(1, Ordering::AcqRel);
                    let export = super::capture::Export::create(&root.join("openuuyc-pcvr/openxr"));
                    match export {
                        Ok(export) if generation > 0 && generation <= i32::MAX as u32 => {
                            state.export = Some(export);
                            state.generation = generation;
                        }
                        result => {
                            drop(broker);
                            OWNED.store(false, Ordering::Release);
                            return Err(result.err().unwrap_or_else(|| {
                                anyhow::anyhow!("stereo generation exhausted")
                            }));
                        }
                    }
                    state.video_rate = video_rate;
                    state.tracking_version = tracking_version;
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
    /// Attach the background mirror to a running SteamVR scene as this
    /// session's stereo producer. The helper handshake and the cross-process
    /// ownership lock are taken first, without the state lock; only a session
    /// that is still live and tracked installs the attach.
    fn start_mirror(&self, mode: MirrorStart) -> Result<()> {
        ensure!(
            super::runtime::preference()? != super::runtime::Preference::OpenXrOnly,
            "当前策略为仅内置 OpenXR；先在「此连接的 VR 后端」选择 SteamVR 再来附加"
        );
        if mode == MirrorStart::Fresh {
            ensure!(
                OWNED
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                    .is_ok(),
                "另一个会话正在使用内置 VR"
            );
        } else {
            // Ownership is already this session's. Release the ended helper
            // and its export before open_export() takes the flock again.
            let mut state = lock(&self.state);
            let ended = state.attach.take();
            state.export = None;
            state.publisher_lock = None;
            drop(state);
            drop(ended);
        }
        let setup = super::steamvr::open_export().and_then(|(export, ownership)| {
            super::steamvr::Attach::start(export.path()).map(|attach| (export, ownership, attach))
        });
        let (export, ownership, attach) = match setup {
            Ok(setup) => setup,
            Err(error) => {
                if mode == MirrorStart::Fresh {
                    OWNED.store(false, Ordering::Release);
                } else {
                    // The request was explicit: never leave the running
                    // session attached to a half-torn-down bridge. The
                    // headset sees the same failure the caller returns.
                    self.stop();
                }
                return Err(error);
            }
        };
        let mut state = lock(&self.state);
        let installing = match mode {
            // A first attach must not meet a session that already started;
            // a replacement needs the very session it is repairing.
            MirrorStart::Fresh => !started(&state),
            MirrorStart::Replace => started(&state),
        };
        if !self.allowed()
            || !self.tracking_ready.load(Ordering::Acquire)
            || !OWNED.load(Ordering::Acquire)
            || !installing
        {
            drop(state);
            drop(attach);
            OWNED.store(false, Ordering::Release);
            anyhow::bail!("VR 会话已在附加期间停止；请重试");
        }
        let generation = NEXT_GENERATION.fetch_add(1, Ordering::AcqRel);
        if generation == 0 || generation > i32::MAX as u32 {
            drop(state);
            drop(attach);
            OWNED.store(false, Ordering::Release);
            anyhow::bail!("stereo generation exhausted");
        }
        state.generation = generation;
        state.export = Some(export);
        state.publisher_lock = Some(ownership);
        state.attach = Some(attach);
        // A mirror has no calibrated headset cadence: 90 Hz only budgets the
        // latest-only reader, it is not a claim about the scene's own rate.
        state.video_rate = super::pacing::Rate::new(90.0)?;
        state.tracking_version = 2;
        state.backend = Some("steamvr-mirror");
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
        let mut compatibility = [0u8; protocol::FRAME_BYTES];
        let data = if state.tracking_version < 2 && data.len() == protocol::FRAME_V2_BYTES {
            compatibility.copy_from_slice(&data[..protocol::FRAME_BYTES]);
            compatibility[4..8].copy_from_slice(&1u32.to_le_bytes());
            for offset in [32, 96, 160] {
                compatibility[offset] &= 15;
            }
            &compatibility[..]
        } else {
            data
        };
        // A mirror session has no downstream sink and never injects devices
        // into SteamVR, but still needs the fresh head pose for frame gating
        // and external-space alignment.
        let accepted = if let Some(broker) = state.broker.as_mut() {
            broker.publish(data, true).is_ok()
        } else if let Some(socket) = state.socket.as_ref() {
            // The legacy SteamVR tracker protocol remains v1, never send its v2 tail.
            let mut legacy = [0u8; protocol::FRAME_BYTES];
            legacy.copy_from_slice(&data[..protocol::FRAME_BYTES]);
            legacy[4..8].copy_from_slice(&1u32.to_le_bytes());
            for offset in [32, 96, 160] {
                legacy[offset] &= 15;
            }
            match socket.send(&legacy) {
                Ok(protocol::FRAME_BYTES) => true,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => false,
                _ => {
                    state.socket = None;
                    state.publisher_lock = None;
                    OWNED.store(false, Ordering::Release);
                    false
                }
            }
        } else {
            state.attach.is_some()
        };
        if accepted {
            state.sequence = sequence;
            state.frames += 1;
            state.last = Some(Instant::now());
            state.head_valid = protocol::head_valid(data);
            state.head = protocol::head_pose(data);
        }
    }
    /// Register an external compositor's space once per generation from a
    /// fresh headset head pose, then carry this frame's eye views into LOCAL.
    /// A frame that cannot be placed is dropped with a visible producer error,
    /// never encoded as if it were already headset-relative.
    fn aligned_frame(
        &self,
        epoch: u32,
        raw: super::capture::RawFrame,
    ) -> Option<super::capture::RawFrame> {
        if raw.origin == 0 {
            return Some(raw);
        }
        let mut state = lock(&self.state);
        if state.generation != epoch {
            return None;
        }
        let align = match state.align.filter(|(generation, _)| *generation == epoch) {
            Some((_, align)) => align,
            None => {
                let head = state
                    .head
                    .filter(|_| state.head_valid && tracking_fresh(state.last));
                let Some(head) = head else {
                    state.producer_error = Some("外部合成器坐标对齐需要有效的头显追踪");
                    return None;
                };
                let Some(external) = raw.head else {
                    state.producer_error = Some("外部合成器双眼帧缺少渲染头姿，无法对齐");
                    return None;
                };
                let Some(align) = super::align::Align::register(&head, &external) else {
                    state.producer_error = Some("外部合成器头姿无法完成坐标对齐");
                    return None;
                };
                state.align = Some((epoch, align));
                align
            }
        };
        let Ok(raw) = super::capture::ensure_aligned(raw, Some(&align)) else {
            state.producer_error = Some("外部合成器坐标对齐失败");
            return None;
        };
        state.external = true;
        Some(raw)
    }
    /// Send a real compositor frame from the authorized CPU/GPU encoder.
    /// Never queues old access units behind a slow peer.
    pub(crate) async fn send_stereo(&self, frame: &super::stereo::Frame) -> Result<()> {
        ensure!(
            self.allowed() && {
                let state = lock(&self.state);
                started(&state) && state.head_valid && tracking_fresh(state.last)
            },
            "stereo session is not authorized/started"
        );
        ensure!(
            super::capture::fresh(frame.predicted_time),
            "stereo frame expired before send"
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
        ensure!(
            lock(&self.state).generation == frame.generation,
            "stale stereo sender generation"
        );
        let config = (frame.generation, frame.width, frame.height);
        let needs_config = *lock(&self.configured_video) != Some(config)
            || self.video_ack.load(Ordering::Acquire) != frame.generation;
        if needs_config {
            let control = lock(&self.control)
                .as_ref()
                .and_then(std::sync::Weak::upgrade)
                .context("VR control channel unavailable for decoder configuration")?;
            control.send_text(serde_json::json!({"type":"video-config","version":1,
                "generation":frame.generation,"width":frame.width,"height":frame.height,"codec":frame.codec}).to_string()).await?;
            *lock(&self.configured_video) = Some(config);
        }
        ensure!(
            self.video_ack.load(Ordering::Acquire) == frame.generation,
            "waiting for headset decoder configuration acknowledgement"
        );
        let packets = frame.fragments()?;
        if frame.keyframe {
            self.keyframe_requested.store(false, Ordering::Release);
        }
        let deadline = tokio::time::Instant::now() + Duration::from_millis(16);
        for packet in packets {
            let live = {
                let state = lock(&self.state);
                started(&state) && state.head_valid && tracking_fresh(state.last)
            };
            if !self.allowed()
                || !super::capture::fresh(frame.predicted_time)
                || !live
                || lock(&self.state).generation != frame.generation
                || video.buffered_amount().await > 256 * 1024
            {
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
        // A stop/new generation may have raced the final await. Never mark a
        // new session ready or count old completion against its measured rate.
        ensure!(
            self.allowed(),
            "stereo session permission changed before completion"
        );
        let state = lock(&self.state);
        ensure!(
            state.generation == frame.generation,
            "stereo session changed before completion"
        );
        self.sent_video_frames.fetch_add(1, Ordering::Relaxed);
        *lock(&self.last_video) = Some((frame.generation, Instant::now()));
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
        *lock(&self.control) = Some(Arc::downgrade(&channel));
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
            let mut send_rate = super::pacing::Meter::default();
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
                        Some("start") => {
                            let parsed = request.as_ref().ok().and_then(|r| r.as_ref());
                            match Requested::parse(parsed) {
                                Ok(backend) => {
                                    let calibration = parsed.and_then(|r| r.calibration.clone());
                                    let this = this.clone();
                                    // The SteamVR attach handshake is blocking;
                                    // never run it on the async control task.
                                    match tokio::task::spawn_blocking(move || {
                                        this.start(calibration.as_ref(), backend)
                                    })
                                    .await
                                    {
                                        Ok(Ok(())) => None,
                                        Ok(Err(error)) => Some(error.to_string()),
                                        Err(_) => Some("VR 启动任务未能完成".into()),
                                    }
                                }
                                Err(error) => Some(error.to_string()),
                            }
                        }
                        Some("video-configured") => {
                            let generation = request
                                .as_ref()
                                .ok()
                                .and_then(|r| r.as_ref())
                                .map(|r| r.generation)
                                .unwrap_or(0);
                            let current = lock(&this.state).generation;
                            let pending = lock(&this.configured_video).map(|c| c.0);
                            if generation > 0
                                && current == generation
                                && pending == Some(generation)
                            {
                                this.video_ack.store(generation, Ordering::Release);
                                this.keyframe_requested.store(true, Ordering::Release);
                            }
                            None
                        }
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
                let streaming = this.stereo_streaming();
                let status = {
                    let mut state = lock(&this.state);
                    // "mirror" lets the headset tell a live attach from one
                    // whose helper ended: only the latter may be re-requested
                    // without dropping a working decoder.
                    let mirror = match state.attach.as_mut() {
                        None => "none",
                        Some(attach) => {
                            if attach.observe_failure().is_none() {
                                "running"
                            } else {
                                "ended"
                            }
                        },
                    };
                    let observed = send_rate.sample(
                        state.generation,
                        Instant::now(),
                        this.sent_video_frames.load(Ordering::Relaxed),
                    );
                    // `stage` is a demand of the OpenXR device bridge only
                    // (xrizer needs a floor); the mirror backend never asks.
                    let stage = state.broker.as_ref().map_or(true, |b| b.has_stage());
                    serde_json::json!({"version":1,"id":id,"send_fps":observed,"tracking":started(&state),"tracking_version":state.tracking_version.max(1),"stage":stage,"generation":state.generation,"backend":state.backend,"stereo":streaming,"video_frames":this.sent_video_frames.load(Ordering::Relaxed),"frames":state.frames,"fresh":state.last.is_some_and(|t|t.elapsed()<Duration::from_millis(250)),"error":last_error,"video_error":state.producer_error,"target_fps":state.video_rate.refresh_hz,"captured_frames":state.captured_frames,"encoded_frames":state.encoded_frames,"external":state.external,"scene_pid":state.attach.as_ref().map_or(0,|a|a.scene_pid()),"mirror":mirror,"decoder_ready":this.video_ack.load(Ordering::Acquire)==state.generation && state.generation>0,"head_valid":state.head_valid,"message":if streaming {"双眼编码帧正在发送；仍需头显确认解码与显示"} else {"等待真实合成器双眼编码帧；当前不能宣称 PCVR 可用"}})
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
    #[test]
    fn decoder_acknowledgement_has_a_generation() {
        let request: super::ControlRequest =
            serde_json::from_str(r#"{"id":0,"op":"video-configured","generation":7}"#).unwrap();
        assert_eq!(request.generation, 7);
        let legacy: super::ControlRequest =
            serde_json::from_str(r#"{"id":1,"op":"start"}"#).unwrap();
        assert_eq!(legacy.generation, 0);
        assert!(
            serde_json::from_str::<super::ControlRequest>(
                r#"{"id":0,"op":"video-configured","generation":-1}"#
            )
            .is_err()
        );
    }

    use super::*;
    #[test]
    fn old_generation_completion_cannot_make_new_video_ready() {
        let now = Instant::now();
        assert!(video_recent(Some((7, now)), 7));
        assert!(!video_recent(Some((7, now)), 8));
        assert!(!video_recent(Some((0, now)), 0));
        assert!(!video_recent(None, 7));
        assert!(!video_recent(
            Some((7, now - Duration::from_millis(501))),
            7
        ));
    }
    #[test]
    fn tracking_timeout_never_treats_missing_or_old_samples_as_fresh() {
        assert!(!tracking_fresh(None));
        assert!(tracking_fresh(Some(Instant::now())));
        assert!(!tracking_fresh(Some(
            Instant::now() - Duration::from_millis(251)
        )));
    }
    #[test]
    fn stereo_sender_can_run_on_the_peer_executor() {
        fn prove(session: &Session, frame: &super::super::stereo::Frame) {
            fn assert_send<T: Send>(_: T) {}
            assert_send(session.send_stereo(frame));
        }
        let _ = prove;
    }
    #[test]
    fn only_known_backends_are_accepted_for_a_start() {
        let mirror: ControlRequest =
            serde_json::from_str(r#"{"id":4,"op":"start","backend":"steamvr"}"#).unwrap();
        assert_eq!(Requested::parse(Some(&mirror)).unwrap(), Requested::SteamVr);
        let plain: ControlRequest = serde_json::from_str(r#"{"id":4,"op":"start"}"#).unwrap();
        assert_eq!(Requested::parse(Some(&plain)).unwrap(), Requested::Default);
        assert_eq!(Requested::parse(None).unwrap(), Requested::Default);
        let unknown: ControlRequest =
            serde_json::from_str(r#"{"id":4,"op":"start","backend":"wivrn"}"#).unwrap();
        assert!(Requested::parse(Some(&unknown)).is_err());
    }
    #[test]
    fn a_repeated_start_confirms_replaces_or_refuses_never_stacks() {
        use Requested::{Default, SteamVr};
        // A default start on any running session is only acknowledged.
        assert_eq!(running(Default, None), Running::Confirm);
        assert_eq!(running(Default, Some(true)), Running::Confirm);
        assert_eq!(running(Default, Some(false)), Running::Confirm);
        // The same live mirror is confirmed; an ended one is replaced, and a
        // tracking-only session must be stopped explicitly first.
        assert_eq!(running(SteamVr, Some(true)), Running::Confirm);
        assert_eq!(running(SteamVr, Some(false)), Running::Replace);
        assert_eq!(running(SteamVr, None), Running::Refuse);
    }
}
