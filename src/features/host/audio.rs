//! One desktop sound source per authorized device connection, shared by all screens.
use super::{Lease, lock};
use crate::media::audio::{
    encoder::Config,
    sender::{BLOCK, BLOCK_DURATION, Frame, RATE, Source, Transmitter},
};
use crossbeam_queue::ArrayQueue;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

const MAX_QUEUED_BLOCKS: usize = 8; // 2.5ms blocks, bounded 20ms without RT

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum DefaultDevices {
    #[default]
    Keep,
    Microphone,
    Speakers,
    Both,
}
impl DefaultDevices {
    pub const ALL: [Self; 4] = [Self::Keep, Self::Microphone, Self::Speakers, Self::Both];
    pub fn label(self) -> &'static str {
        match self {
            Self::Keep => "全部保持原有",
            Self::Microphone => "仅调整麦克风",
            Self::Speakers => "仅调整扬声器",
            Self::Both => "都调整",
        }
    }
    pub fn microphone(self) -> bool {
        matches!(self, Self::Microphone | Self::Both)
    }
    pub fn speakers(self) -> bool {
        matches!(self, Self::Speakers | Self::Both)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub(crate) struct Device {
    pub id: String,
    pub name: String,
}
impl Device {
    pub fn validate(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            !self.id.is_empty()
                && self.id.len() <= 4096
                && !self.id.contains('\0')
                && self.name.len() <= 4096
                && !self.name.contains('\0'),
            "播放设备标识无效"
        );
        Ok(())
    }
}
#[derive(Clone, Default)]
pub(crate) struct Inventory {
    pub devices: Vec<Device>,
    pub error: Option<String>,
    pub pending: bool,
    pub updated: Option<Instant>,
}

#[derive(Clone, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub(crate) struct Status {
    pub configured: bool,
    pub capturing: bool,
    pub device: String,
    pub error: Option<String>,
    pub packets: u64,
    #[serde(default)]
    pub target_bitrate: u32,
    #[serde(default)]
    pub rt_requested: bool,
    #[serde(default)]
    pub rt_active: bool,
    #[serde(default)]
    pub rt_error: Option<String>,
    #[serde(default)]
    pub queue_budget_us: u64,
}
struct Shared {
    lease: Lease,
    cancel: CancellationToken,
    connected: Arc<AtomicBool>,
    encoding: Mutex<Option<Config>>,
    quality_control: AtomicBool,
    remote_quality: AtomicU32,
    quality_report: Mutex<(u64, Option<(crate::media::audio::encoder::Quality, u32)>)>,
    status: Mutex<Status>,
    generation: AtomicU64,
    active: AtomicBool,
    media_allowed: AtomicBool,
    recover: AtomicBool,
    stopped: AtomicBool,
    native: Arc<super::native_audio::NativeAudio>,
    rt_requested: bool,
    budget_ns: AtomicU64,
    frames: ArrayQueue<Frame>,
    wake: Notify,
    report_wake: Notify,
    pub transmitter: Transmitter,
    transport: super::transport::Transport,
}
#[derive(Clone)]
pub(crate) struct Audio(Arc<Shared>);
impl Audio {
    pub(super) fn bind_native(
        &self,
        channel: Arc<webrtc::data_channel::RTCDataChannel>,
        cancel: CancellationToken,
    ) {
        let source = Arc::downgrade(&self.0);
        self.0.native.bind(channel, cancel, move |frame| {
            let source = Audio(source.upgrade()?);
            (source.current(frame.generation)
                && frame.created.elapsed() <= BLOCK_DURATION + source.backlog_budget())
            .then(|| source.quality())
        });
    }
    pub fn new(
        lease: Lease,
        cancel: CancellationToken,
        connected: Arc<AtomicBool>,
        transport: super::transport::Transport,
    ) -> Self {
        Self(Arc::new(Shared {
            lease,
            cancel,
            connected,
            encoding: Mutex::default(),
            quality_control: AtomicBool::new(false),
            remote_quality: AtomicU32::new(0),
            quality_report: Mutex::new((0, None)),
            status: Mutex::default(),
            generation: AtomicU64::new(0),
            active: AtomicBool::new(false),
            media_allowed: AtomicBool::new(true),
            recover: AtomicBool::new(false),
            stopped: AtomicBool::new(false),
            // Loopback produces 2.5ms blocks. Bound capture backlog instead
            // of stale desktop sound when transport/encoding briefly stalls.
            native: Arc::new(super::native_audio::NativeAudio::default()),
            rt_requested: {
                #[cfg(target_os = "linux")]
                {
                    crate::platform::audio_thread::rt_requested()
                }
                #[cfg(not(target_os = "linux"))]
                {
                    false
                }
            },
            budget_ns: AtomicU64::new(16_666_667),
            frames: ArrayQueue::new(16),
            wake: Notify::new(),
            report_wake: Notify::new(),
            transmitter: Transmitter::default(),
            transport,
        }))
    }
    pub fn enable_quality_control(&self, enabled: bool, audio_only: bool) {
        self.0.quality_control.store(enabled, Ordering::Release);
        if enabled && audio_only {
            self.0.remote_quality.store(256, Ordering::Relaxed);
        }
    }
    fn quality(&self) -> crate::media::audio::encoder::Quality {
        let kbps = self.0.remote_quality.load(Ordering::Relaxed);
        if kbps == 0 {
            self.0.lease.audio_quality()
        } else {
            crate::media::audio::encoder::Quality { kbps }
        }
    }
    fn quality_status(&self) -> Option<(u64, crate::media::audio::encoder::Quality, Config)> {
        let mut report = lock(&self.0.quality_report);
        let quality = self.quality();
        let config = (*lock(&self.0.encoding))?.quality(quality);
        let values = (quality, config.bitrate);
        if report.1 != Some(values) {
            report.0 = report.0.wrapping_add(1);
            report.1 = Some(values);
        }
        Some((report.0, quality, config))
    }
    pub fn quality_hello(&self) -> Option<Vec<u8>> {
        if !self.0.quality_control.load(Ordering::Acquire) {
            return None;
        }
        let (revision, quality, config) = self.quality_status()?;
        Some(crate::protocol::audio_control::encode(
            crate::protocol::audio_control::Message::Hello {
                revision,
                kbps: quality.kbps,
                effective_bps: config.bitrate,
            },
        ))
    }
    pub fn quality_request(&self, bytes: &[u8]) -> anyhow::Result<Option<Vec<u8>>> {
        use crate::protocol::audio_control::{self, Message};
        if !self.0.quality_control.load(Ordering::Acquire) {
            return Ok(None);
        }
        let Some(message) = audio_control::decode(bytes)? else {
            return Ok(None);
        };
        let Message::Set { request, kbps } = message else {
            anyhow::bail!("unexpected audio control message");
        };
        let result = (|| -> anyhow::Result<_> {
            crate::media::audio::encoder::Quality { kbps }.validate()?;
            anyhow::ensure!(self.allowed(), "当前会话未开放桌面声音");
            self.0.remote_quality.store(kbps, Ordering::Relaxed);
            self.0.wake.notify_one();
            self.quality_status()
                .ok_or_else(|| anyhow::anyhow!("音频轨道未协商"))
        })();
        Ok(Some(audio_control::encode(match result {
            Ok((revision, quality, config)) => Message::Applied {
                request,
                revision,
                kbps: quality.kbps,
                effective_bps: config.bitrate,
            },
            Err(error) => Message::Rejected {
                request,
                message: error.to_string(),
            },
        })))
    }
    pub fn configure(&self, encoding: Option<Config>) {
        let mut current = lock(&self.0.encoding);
        if *current != encoding {
            self.0.recover.store(false, Ordering::Release);
            self.invalidate();
            *current = encoding;
            tracing::info!(?encoding, "host desktop audio negotiated");
        }
    }
    pub fn set_media_allowed(&self, allowed: bool) {
        if self.0.media_allowed.swap(allowed, Ordering::AcqRel) != allowed {
            self.invalidate();
        }
    }
    pub fn allowed(&self) -> bool {
        self.0.media_allowed.load(Ordering::Acquire)
            && self.0.lease.requested()
            && self.encoding().is_some()
            && !self.0.cancel.is_cancelled()
    }
    fn invalidate(&self) {
        self.0.active.store(false, Ordering::Release);
        self.0.generation.fetch_add(1, Ordering::AcqRel);
        while self.0.frames.pop().is_some() {}
        self.0.wake.notify_one();
        self.0.report_wake.notify_one();
    }
    pub fn transport_lost(&self) {
        self.invalidate();
    }
    pub fn transmitter(&self) -> &Transmitter {
        &self.0.transmitter
    }
    fn open(
        &self,
        endpoint: &crate::platform::loopback::Endpoint,
        generation: u64,
        origin: Instant,
    ) -> anyhow::Result<crate::platform::loopback::Capture> {
        let source = self.clone();
        let clock = Mutex::new(None::<(Instant, u32)>);
        self.0.active.store(true, Ordering::Release);
        let capture = crate::platform::loopback::open(
            endpoint,
            Arc::new(move |samples, at| {
                if !source.current(generation) {
                    return;
                }
                let mut clock = lock(&clock);
                let timestamp = capture_timestamp(*clock, at, origin);
                *clock = Some((at, timestamp));
                drop(clock);
                let retained = if source.0.rt_requested {
                    rt_queue_blocks(Duration::from_nanos(
                        source.0.budget_ns.load(Ordering::Relaxed),
                    ))
                } else {
                    MAX_QUEUED_BLOCKS
                };
                while source.0.frames.len() >= retained {
                    if source.0.frames.pop().is_none() {
                        break;
                    }
                }
                let frame = Frame {
                    samples,
                    timestamp,
                    generation,
                    created: at,
                };
                source.0.native.enqueue(frame, retained);
                source.0.frames.force_push(frame);
                source.0.wake.notify_one();
            }),
        )?;

        let mut status = lock(&self.0.status);
        status.device = endpoint.name();
        status.capturing = false;
        status.error = None;
        Ok(capture)
    }
    pub fn run(&self) {
        #[cfg(target_os = "linux")]
        let _realtime = if self.0.rt_requested {
            let result = crate::platform::audio_thread::RealtimeGuard::enter();
            let mut status = lock(&self.0.status);
            status.rt_requested = true;
            match result {
                Ok(guard) => {
                    status.rt_active = true;
                    Some(guard)
                }
                Err(error) => {
                    status.rt_error = Some(format!("{error:#}"));
                    tracing::warn!(%error,"audio RT unavailable; ordinary scheduling retained");
                    None
                }
            }
        } else {
            None
        };
        use crate::platform::loopback::{Capture, Devices, Endpoint};
        let origin = Instant::now();
        let mut choice = self.0.lease.audio_device_changes();
        let mut selected_device = choice.borrow_and_update().clone();
        let mut stream: Option<Capture> = None;
        let mut devices: Option<Devices> = None;
        let mut endpoint: Option<Endpoint> = None;
        let mut generation = self.0.generation.load(Ordering::Acquire);
        let mut retry = Retry::new(origin);
        let mut next_device_check = origin;
        let mut next_status = origin;
        let mut started = None;
        while !self.0.cancel.is_cancelled() && self.0.lease.requested() {
            let now = Instant::now();
            if choice.has_changed().unwrap_or(false) {
                let selected = choice.borrow_and_update().clone();
                if selected.as_ref().map(|d| &d.id) != selected_device.as_ref().map(|d| &d.id) {
                    self.0.recover.store(false, Ordering::Release);
                    // Changing from default to the same physical endpoint only
                    // changes the selection policy; keep its live stream intact.
                    retry.reset(now);
                    next_device_check = now;
                }
                selected_device = selected;
            }
            let wanted = self.allowed() && self.0.connected.load(Ordering::Acquire);
            let next = self.0.generation.load(Ordering::Acquire);
            if (!wanted && (stream.is_some() || devices.is_some() || endpoint.is_some()))
                || next != generation
            {
                self.invalidate();
                stream = None;
                started = None;
                generation = self.0.generation.load(Ordering::Acquire);
                if self.0.recover.swap(false, Ordering::AcqRel) && wanted {
                    retry.failed(now);
                } else {
                    retry.reset(now);
                }
                next_device_check = now;
                lock(&self.0.status).capturing = false;
                if !wanted {
                    endpoint = None;
                    devices = None;
                    lock(&self.0.status).device.clear();
                }
            }
            let result = (|| -> anyhow::Result<()> {
                if !wanted {
                    return Ok(());
                }
                if devices.is_none() && retry.ready(now) {
                    devices = Some(Devices::new()?);
                    next_device_check = now;
                }
                if let Some(monitor) = &devices {
                    let changed = monitor.changes(endpoint.as_ref().map(|e| e.id.as_str()));
                    if changed.refresh || now >= next_device_check {
                        let selected =
                            monitor.endpoint(selected_device.as_ref().map(|d| d.id.as_str()))?;
                        let different =
                            endpoint.as_ref().map(|e| &e.id) != selected.as_ref().map(|e| &e.id);
                        if different || changed.rebuild {
                            self.invalidate();
                            stream = None;
                            started = None;
                            generation = self.0.generation.load(Ordering::Acquire);
                            lock(&self.0.status).capturing = false;
                            retry.wake(now);
                        }
                        // Notices while no endpoint is available can signal a device's return.
                        if changed.refresh && stream.is_none() {
                            retry.wake(now);
                        }
                        endpoint = selected;
                        next_device_check = now + Duration::from_secs(2);
                    }
                }
                if stream.is_none() && retry.ready(now) {
                    if let Some(endpoint) = &endpoint {
                        let capture = self.open(endpoint, generation, origin)?;
                        if !self.current(generation) {
                            return Ok(());
                        }
                        stream = Some(capture);
                        started = Some(now);
                        tracing::info!("host desktop audio capture opened");
                    } else {
                        self.invalidate();
                        generation = self.0.generation.load(Ordering::Acquire);
                        let mut status = lock(&self.0.status);
                        status.device.clear();
                        status.capturing = false;
                        status.error = Some(selected_device.as_ref().map_or_else(
                            || "没有可用的默认播放设备".into(),
                            |device| format!("所选播放设备未连接：{}", device.name),
                        ));
                        retry.failed(now);
                    }
                }
                if let Some(capture) = &mut stream {
                    capture.poll()?;
                    lock(&self.0.status).capturing = capture.producing();
                    if started.is_some_and(|at| {
                        now.saturating_duration_since(at) >= Duration::from_secs(2)
                    }) {
                        retry.reset(now);
                    }
                }
                Ok(())
            })();
            if let Err(error) = result {
                self.invalidate();
                stream = None;
                started = None;
                endpoint = None;
                devices = None;
                generation = self.0.generation.load(Ordering::Acquire);
                retry.failed(now);
                next_device_check = now;
                let message = format!("桌面声音暂不可用：{error:#}");
                let mut status = lock(&self.0.status);
                if status.error.as_ref() != Some(&message) {
                    tracing::warn!(device = %status.device, error = %format!("{error:#}"),"host desktop audio rebuilding; video retained");
                }
                status.error = Some(message);
                status.capturing = false;
                status.device.clear();
            }
            self.0
                .transport
                .audio_budget(if stream.is_some() && wanted {
                    self.encoding()
                        .map_or(0, |c| {
                            c.bitrate
                                .saturating_add(80 * 8 * RATE / c.packet_samples as u32)
                        })
                        .saturating_add(self.0.native.bandwidth())
                } else {
                    0
                });
            if now >= next_status {
                let budget = rt_frame_budget(self.0.lease.audio_refresh_rate());
                self.0
                    .budget_ns
                    .store(budget.as_nanos() as u64, Ordering::Relaxed);
                next_status = now + Duration::from_millis(250);
                let mut status = lock(&self.0.status);
                status.queue_budget_us = if self.0.rt_requested {
                    budget.as_micros() as u64
                } else {
                    20_000
                };
                status.configured = self.encoding().is_some();
                status.packets = self.0.transmitter.sent.load(Ordering::Relaxed);
                status.target_bitrate = self.encoding().map_or(0, |config| config.bitrate);
                self.0.lease.audio_status(status.clone());
            }
            std::thread::park_timeout(Duration::from_millis(if wanted {
                if self.0.rt_requested { 1 } else { 5 }
            } else {
                50
            }));
        }
        self.invalidate();
        drop(stream);
        drop(endpoint);
        drop(devices);
        self.0.transport.audio_budget(0);
        self.0.stopped.store(true, Ordering::Release);
        self.0.wake.notify_one();
        self.0.report_wake.notify_one();
        tracing::info!(
            packets = self.0.transmitter.sent.load(Ordering::Relaxed),
            "host desktop audio closed"
        );
    }
}
fn capture_timestamp(previous: Option<(Instant, u32)>, at: Instant, origin: Instant) -> u32 {
    match previous {
        Some((last, tick))
            if at >= last
                && at.duration_since(last) <= BLOCK_DURATION + Duration::from_millis(2) =>
        {
            tick.wrapping_add(BLOCK as u32)
        }
        _ => {
            (at.saturating_duration_since(origin).as_nanos() * u128::from(RATE) / 1_000_000_000)
                as u32
        }
    }
}
fn rt_frame_budget(fps: u32) -> Duration {
    Duration::from_secs_f64(1.0 / fps.clamp(30, 240) as f64).max(BLOCK_DURATION)
}
fn rt_queue_blocks(budget: Duration) -> usize {
    budget
        .as_micros()
        .div_ceil(BLOCK_DURATION.as_micros())
        .clamp(1, 16) as usize
}
impl Source for Audio {
    fn backlog_budget(&self) -> Duration {
        if self.0.rt_requested {
            Duration::from_nanos(self.0.budget_ns.load(Ordering::Relaxed))
        } else {
            Duration::from_millis(40)
        }
    }
    fn current(&self, generation: u64) -> bool {
        self.0.active.load(Ordering::Acquire)
            && self.0.generation.load(Ordering::Acquire) == generation
            && self.0.connected.load(Ordering::Acquire)
            && self.allowed()
    }
    fn stopped(&self) -> &AtomicBool {
        &self.0.stopped
    }
    fn encoding(&self) -> Option<Config> {
        let config = *lock(&self.0.encoding);
        config.map(|config| config.quality(self.quality()))
    }
    fn frames(&self) -> &ArrayQueue<Frame> {
        &self.0.frames
    }
    fn wake(&self) -> &Notify {
        &self.0.wake
    }
    fn report_wake(&self) -> &Notify {
        &self.0.report_wake
    }
    fn fault(&self, error: String) {
        self.send_error(error);
        self.0.recover.store(true, Ordering::Release);
        self.invalidate();
    }
    fn send_error(&self, error: String) {
        lock(&self.0.status).error = Some(error);
    }
}

struct Retry {
    at: Instant,
    failures: u32,
}
impl Retry {
    fn new(now: Instant) -> Self {
        Self {
            at: now,
            failures: 0,
        }
    }
    fn ready(&self, now: Instant) -> bool {
        now >= self.at
    }
    fn wake(&mut self, now: Instant) {
        self.at = now;
    }
    fn reset(&mut self, now: Instant) {
        self.failures = 0;
        self.at = now;
    }
    fn failed(&mut self, now: Instant) {
        let delay = [100, 250, 500, 1000][self.failures.min(3) as usize];
        self.failures = self.failures.saturating_add(1);
        self.at = now + Duration::from_millis(delay);
    }
}

#[cfg(test)]
mod latency_tests {
    #[test]
    fn capture_clock_keeps_short_quanta_and_real_gaps() {
        let origin = std::time::Instant::now();
        let last = origin + std::time::Duration::from_millis(10);
        assert_eq!(
            super::capture_timestamp(Some((last, 480)), last + super::BLOCK_DURATION, origin),
            600
        );
        assert_eq!(
            super::capture_timestamp(
                Some((last, 480)),
                origin + std::time::Duration::from_millis(30),
                origin
            ),
            1440
        );
    }
    #[test]
    fn rt_budget_tracks_video_rate_without_slicing_audio_quanta() {
        for (fps, blocks) in [(30, 14), (60, 7), (90, 5), (120, 4), (165, 3), (240, 2)] {
            let budget = super::rt_frame_budget(fps);
            assert_eq!(super::rt_queue_blocks(budget), blocks);
            assert!(budget >= super::BLOCK_DURATION);
        }
        assert!(super::rt_frame_budget(60).as_micros() >= 16_666);
    }
    #[test]
    fn overloaded_loopback_retains_only_twenty_ms() {
        let queue = crossbeam_queue::ArrayQueue::new(super::MAX_QUEUED_BLOCKS);
        for block in 0..10 {
            queue.force_push(block);
        }
        let mut retained = Vec::new();
        while let Some(block) = queue.pop() {
            retained.push(block);
        }
        assert_eq!(retained, (2..10).collect::<Vec<_>>());
    }
}
