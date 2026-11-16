//! Capture/encoder worker lifecycle; owns driver objects on one thread.
use super::super::Lease;
use super::super::VideoConfig;
use super::super::capture;
use super::super::encoder;
use super::super::lock;
use super::super::output_size;
use super::Published;
use crate::features::host::format::{Backend, Color, Format};
use anyhow::Context as _;
use anyhow::Result;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::Ordering;
use std::time::Duration;
use std::time::Instant;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

mod adaptive;
mod metrics;

#[cfg(target_os = "linux")]
fn capture_backend_name(state: &CaptureState) -> String {
    state.backend.clone()
}
#[cfg(not(target_os = "linux"))]
fn capture_backend_name(state: &capture::Desktop) -> String {
    state.backend_name().to_string()
}

#[cfg(target_os = "linux")]
#[derive(Clone, Copy)]
struct CaptureConfig {
    enabled: bool,
    revision: u64,
    quality: i32,
    maximum: (u32, u32),
    fps: u32,
    cursor: bool,
    hdr: bool,
    gpu_frames: bool,
    epoch: u64,
}

#[cfg(target_os = "linux")]
#[derive(Clone)]
struct CaptureState {
    screen: capture::Screen,
    device: capture::Device,
    generation: u64,
    available: bool,
    hdr_available: bool,
    cursor: Option<crate::platform::cursor_shape::Snapshot>,
    backend: String,
}

#[cfg(target_os = "linux")]
struct Captured {
    frame: capture::Frame,
    state: CaptureState,
    revision: u64,
}

#[cfg(target_os = "linux")]
struct CaptureSlot {
    latest: Option<Captured>,
    state: CaptureState,
    replaced: u64,
    attempts: u64,
    empty: u64,
    capture_total: Duration,
    capture_max: Duration,
    wlr_wait_total: Duration,
    wlr_copy_total: Duration,
    wlr_frames: u64,
    wlr_wait_max: Duration,
    wlr_copy_max: Duration,
    wlr_stage_a: Duration,
    wlr_stage_b: Duration,
}

/// Whether a frame differs from the previous one, judged from a strided
/// sample of its pixels. A few kilobytes are read per frame, which is enough
/// to tell a moving desktop from a still one without comparing 16 MiB.
#[cfg(target_os = "linux")]
fn sampled_change(previous: &mut Option<Vec<u8>>, pixels: &[u8], width: u32, height: u32) -> bool {
    // Whole rows, spread over the frame: a small moving window spans many rows,
    // while a scatter of byte windows would step right over it. A hundred rows
    // of a 2560-wide picture are about 1 MB, which costs well under a
    // millisecond and only decides which copy request to send.
    const ROWS: usize = 100;
    let row = width as usize * 4;
    let lines = height as usize;
    if row == 0 || lines == 0 || pixels.len() < row * lines {
        return false;
    }
    let step = (lines / ROWS).max(1);
    let mut current = Vec::with_capacity(ROWS * row);
    for line in (0..lines).step_by(step).take(ROWS) {
        let start = line * row;
        current.extend_from_slice(&pixels[start..start + row]);
    }
    let changed = previous
        .as_ref()
        .is_none_or(|old| old.len() != current.len() || old != &current);
    *previous = Some(current);
    changed
}

/// Replaces an old frame instead of queueing it. A slow encoder should see the
/// newest desktop image, not build latency by draining an old FIFO.
#[cfg(target_os = "linux")]
fn replace_latest<T>(slot: &mut Option<T>, frame: T) -> bool {
    slot.replace(frame).is_some()
}

/// Capture owns Desktop and its native handles on a dedicated thread. The
/// encoder consumes a one-slot latest-frame mailbox: acquisition never waits
/// for NVENC, and a slow encoder drops stale frames rather than building a FIFO.
#[cfg(target_os = "linux")]
struct CapturePump {
    control: Arc<Mutex<CaptureConfig>>,
    slot: Arc<Mutex<CaptureSlot>>,
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

#[cfg(all(test, target_os = "linux"))]
mod capture_pump_tests {
    use super::{CaptureConfig, CapturePump, capture, encoder, replace_latest};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    #[test]
    fn the_change_sample_notices_a_small_moving_area() {
        use super::sampled_change;
        let (width, height) = (640u32, 400u32);
        let row = width as usize * 4;
        let frame = vec![0u8; row * height as usize];
        let mut previous = None;
        assert!(sampled_change(&mut previous, &frame, width, height));
        assert!(!sampled_change(&mut previous, &frame, width, height));
        // A 40x40 patch is smaller than one sampled row's step but spans rows,
        // which is why whole rows are compared rather than byte windows.
        let mut changed = frame.clone();
        for y in 100..140 {
            for x in 100..140 {
                changed[y * row + x * 4] = 0xff;
            }
        }
        assert!(sampled_change(&mut previous, &changed, width, height));
        // A geometry change cannot be compared against the old sample.
        assert!(!sampled_change(&mut previous, &frame[..row], width, height));
    }

    #[test]
    fn a_slow_consumer_never_accumulates_a_fifo() {
        let mut slot = None;
        assert!(!replace_latest(&mut slot, 1));
        assert_eq!(slot.take(), Some(1));
        assert!(!replace_latest(&mut slot, 2));
        assert!(replace_latest(&mut slot, 3));
        assert_eq!(slot.take(), Some(3));
    }

    /// Capture and a hardware encoder together on the live Wayland desktop.
    /// OPENUUYC_TEST_ENCODER=intel/amd selects VA-API; the default is NVENC.
    /// Keep a native,
    /// focused animation visible on the selected output during this test; idle
    /// and unfocused surfaces are throttled by the compositor and are not a
    /// useful source-FPS benchmark. This separates capture from encoder limits.
    #[test]
    #[ignore = "requires a Wayland session and a hardware encoder"]
    fn live_capture_and_nvenc_rate_at_144hz() {
        use crate::features::host::format::{Backend, Color, Format};
        use crate::media::encoding::{QualityTarget, Rate};

        let screen = capture::screens()
            .unwrap()
            .into_iter()
            .max_by_key(|screen| {
                (
                    screen.fps,
                    u64::from(screen.width) * u64::from(screen.height),
                )
            })
            .unwrap();
        let capture_only = std::env::var_os("OPENUUYC_TEST_CAPTURE_ONLY").is_some();
        // Which hardware encoder to measure. The VA-API encoder keeps a DMA-BUF
        // on the GPU, so it is the only one that can skip the per-frame copy
        // into the encoder; NVENC cannot import one.
        let backend = match std::env::var("OPENUUYC_TEST_ENCODER").as_deref() {
            Ok("intel") => Backend::Intel,
            Ok("amd") => Backend::Amd,
            _ => Backend::Nvidia,
        };
        let gpu_frames = matches!(backend, Backend::Intel | Backend::Amd);
        capture::set_gpu_frames_wanted(gpu_frames);
        let desktop = capture::Desktop::open_selected(&screen).unwrap();
        eprintln!(
            "144Hz test source name={} identity={:?} size={}x{} fps_cap={} backend={} encoder={:?} gpu_frames={gpu_frames}",
            screen.device_name,
            screen.identity,
            screen.width,
            screen.height,
            screen.fps,
            desktop.backend_name(),
            backend,
        );
        let size = crate::media::geometry::output_size(screen.width, screen.height, 4);
        let device = desktop.device.clone();
        let mut encoder = (!capture_only)
            .then(|| {
                encoder::Encoder::hardware_format(
                    &device,
                    backend,
                    size,
                    Format::AVC,
                    Rate {
                        target: 80_000_000,
                        peak: 80_000_000,
                        fps: 144,
                        quality: 4,
                        quality_target: QualityTarget {
                            bitrate: 80_000_000,
                            fps: 144,
                        },
                    },
                    Color::Sdr,
                )
            })
            .transpose()
            .unwrap();
        let pump = CapturePump::new(desktop, Arc::new(Mutex::new(None))).unwrap();
        pump.configure(CaptureConfig {
            enabled: true,
            revision: 1,
            quality: 4,
            maximum: (3840, 2160),
            fps: 144,
            cursor: false,
            hdr: false,
            gpu_frames,
            epoch: 0,
        });
        let started = std::time::Instant::now();
        let mut frames = 0u64;
        let mut encoded = 0u64;
        let mut distinct_images = 0u64;
        let mut gpu_images = 0u64;
        let mut previous_hash = None::<u64>;
        let mut encode_time = Duration::ZERO;
        while started.elapsed() < Duration::from_secs(4) {
            if let Some(packet) = pump.take_latest().filter(|p| p.revision == 1) {
                frames += 1;
                gpu_images += u64::from(matches!(packet.frame.image, capture::Image::Nv12(_)));
                if !matches!(packet.frame.image, capture::Image::Nv12(_))
                    && let Ok(pixels) = packet.frame.image.pixels()
                {
                    // Sample a 32x32 grid instead of scanning the whole
                    // 16 MiB image: this test must measure capture cadence, not
                    // make the debug-build consumer CPU-bound doing its hash.
                    let (width, height) =
                        (packet.frame.width as usize, packet.frame.height as usize);
                    let mut hash = 0xcbf29ce484222325u64;
                    for y in (0..height).step_by((height / 32).max(1)) {
                        for x in (0..width).step_by((width / 32).max(1)) {
                            let offset = (y * width + x) * 4;
                            if let Some(byte) = pixels.get(offset) {
                                hash = (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3);
                            }
                        }
                    }
                    if previous_hash.is_some_and(|previous| previous != hash) {
                        distinct_images += 1;
                    }
                    previous_hash = Some(hash);
                }
                if let Some(encoder) = encoder.as_mut() {
                    let begin = std::time::Instant::now();
                    encoded += encoder
                        .encode(&packet.frame.image, frames as i64 * 69_444, false)
                        .unwrap()
                        .len() as u64;
                    encode_time += begin.elapsed();
                }
            } else {
                std::thread::sleep(Duration::from_micros(200));
            }
        }
        let elapsed = started.elapsed().as_secs_f64();
        let (
            attempts,
            _empty,
            capture_time,
            capture_max,
            wlr_wait,
            wlr_copy,
            wlr_frames,
            wlr_wait_max,
            wlr_copy_max,
            stage_a,
            stage_b,
        ) = pump.stats();
        println!(
            "live 144Hz pipeline source_fps_cap={} polls={attempts} captured={frames} encoded={encoded} capture_only={capture_only} gpu_images={gpu_images} distinct_images={distinct_images} capture_fps={:.1} encoded_fps={:.1} capture_avg_ms={:.2} capture_max_ms={:.2} wlr_frames={wlr_frames} wlr_wait_avg_ms={:.2} wlr_wait_max_ms={:.2} wlr_copy_avg_ms={:.2} wlr_copy_max_ms={:.2} stage_request_ms={:.2} stage_copy_ms={:.2} encode_avg_ms={:.2}",
            screen.fps,
            frames as f64 / elapsed,
            encoded as f64 / elapsed,
            capture_time.as_secs_f64() * 1000.0 / attempts.max(1) as f64,
            capture_max.as_secs_f64() * 1000.0,
            wlr_wait.as_secs_f64() * 1000.0 / wlr_frames.max(1) as f64,
            wlr_wait_max.as_secs_f64() * 1000.0,
            wlr_copy.as_secs_f64() * 1000.0 / wlr_frames.max(1) as f64,
            wlr_copy_max.as_secs_f64() * 1000.0,
            stage_a.as_secs_f64() * 1000.0,
            stage_b.as_secs_f64() * 1000.0,
            encode_time.as_secs_f64() * 1000.0 / encoded.max(1) as f64,
        );
        if let Some(encoder::Encoder::Vaapi(encoder, _)) = encoder.as_mut() {
            let (import_or_upload, driver_encode) = encoder.take_stage_totals();
            println!(
                "VA-API stages import_or_upload_avg_ms={:.2} driver_encode_avg_ms={:.2}",
                import_or_upload.as_secs_f64() * 1000.0 / encoded.max(1) as f64,
                driver_encode.as_secs_f64() * 1000.0 / encoded.max(1) as f64,
            );
        }
        assert!(frames > 0 && (capture_only || encoded > 0));
    }

    /// Live end-to-end test for the producer and the overwrite policy.
    #[test]
    #[ignore = "needs a Wayland desktop session"]
    fn producer_keeps_capturing_while_consumer_is_slow() {
        let screen = capture::screens().unwrap().remove(0);
        let desktop = capture::Desktop::open_selected(&screen).unwrap();
        let pump = CapturePump::new(desktop, Arc::new(Mutex::new(None))).unwrap();
        pump.configure(CaptureConfig {
            enabled: true,
            revision: 1,
            quality: 4,
            maximum: (3840, 2160),
            fps: 144,
            cursor: false,
            hdr: false,
            gpu_frames: false,
            epoch: 0,
        });
        std::thread::sleep(Duration::from_secs(2)); // deliberately don't consume
        let (attempts, _empty, _elapsed, _max, _wait, _copy, _frames, _wait_max, _copy_max, _a, _b) =
            pump.stats();
        let replaced = pump.replaced();
        let latest = pump.take_latest();
        let frames = replaced + u64::from(latest.is_some());
        println!(
            "producer_attempts={attempts} frames_delivered={frames} overwritten={replaced} latest={}",
            latest.is_some()
        );
        assert!(attempts >= 200);
        assert!(replaced > 0 || latest.is_some());
    }
}

#[cfg(target_os = "linux")]
impl CapturePump {
    fn new(
        mut desktop: capture::Desktop,
        pointer: Arc<Mutex<Option<crate::platform::cursor_shape::Snapshot>>>,
    ) -> Result<Self> {
        let state = CaptureState {
            screen: desktop.screen.clone(),
            device: desktop.device.clone(),
            generation: desktop.generation,
            available: desktop.available,
            hdr_available: desktop.hdr_available(),
            cursor: desktop.cursor.clone(),
            backend: desktop.backend_name().to_string(),
        };
        let slot = Arc::new(Mutex::new(CaptureSlot {
            latest: None,
            state,
            replaced: 0,
            attempts: 0,
            empty: 0,
            capture_total: Duration::ZERO,
            capture_max: Duration::ZERO,
            wlr_wait_total: Duration::ZERO,
            wlr_copy_total: Duration::ZERO,
            wlr_frames: 0,
            wlr_wait_max: Duration::ZERO,
            wlr_copy_max: Duration::ZERO,
            wlr_stage_a: Duration::ZERO,
            wlr_stage_b: Duration::ZERO,
        }));
        let control = Arc::new(Mutex::new(CaptureConfig {
            enabled: false,
            revision: 0,
            quality: 1,
            maximum: (1920, 1080),
            fps: 30,
            cursor: false,
            hdr: false,
            gpu_frames: false,
            epoch: 0,
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let thread_slot = slot.clone();
        let thread_control = control.clone();
        let thread_stop = stop.clone();
        let thread = std::thread::Builder::new()
            .name("openuuyc-capture".into())
            .spawn(move || {
                let mut next = Instant::now();
                let mut sample: Option<Vec<u8>> = None;
                let mut last_change: Option<Instant> = None;
                let mut plain_copy = false;
                while !thread_stop.load(Ordering::Acquire) {
                    let cfg = *lock(&thread_control);
                    if !cfg.enabled {
                        std::thread::sleep(Duration::from_millis(2));
                        next = Instant::now();
                        continue;
                    }
                    let now = Instant::now();
                    if now < next {
                        std::thread::sleep(
                            next.saturating_duration_since(now)
                                .min(Duration::from_millis(2)),
                        );
                        continue;
                    }
                    let period = Duration::from_secs_f64(1.0 / f64::from(cfg.fps.max(1)));
                    next = if now.saturating_duration_since(next) >= period {
                        now + period
                    } else {
                        next + period
                    };
                    desktop.set_gpu_frames(cfg.gpu_frames);
                    // A damage-gated copy withholds ready until the output
                    // changes, which caps delivery below the refresh rate while
                    // the desktop moves; an immediate copy keeps up but copies
                    // every frame even when nothing changed. Switch on a sampled
                    // comparison, which costs a few kilobytes per frame.
                    // While the desktop moves, copy immediately: a damage-gated
                    // request withholds ready for more than an output period.
                    // Once nothing has changed for half a second, go back to
                    // the damage-gated request so a still desktop stops paying
                    // for a full-frame copy on every poll; the next damaged
                    // frame then switches it on again.
                    desktop.set_plain_copy(plain_copy);
                    let started = Instant::now();
                    let result = desktop.next(
                        (period.as_millis() as u32).clamp(1, 50),
                        cfg.quality,
                        cfg.cursor,
                        cfg.hdr,
                        cfg.maximum,
                    );
                    let elapsed = started.elapsed();
                    let timings = desktop.last_timings();
                    let phases = desktop.last_phases();
                    if let Ok(Some(frame)) = &result {
                        if !plain_copy {
                            // A damage-gated request answered, so something
                            // changed: switch to immediate copies.
                            last_change = Some(Instant::now());
                        } else if let capture::Image::Cpu { pixels, .. } = &frame.image
                            // A GPU frame stays on the GPU: reading it back here
                            // would cost more than the sampling decides.
                            && sampled_change(&mut sample, pixels, frame.width, frame.height)
                        {
                            last_change = Some(Instant::now());
                        }
                        plain_copy =
                            last_change.is_some_and(|at| at.elapsed() < Duration::from_millis(500));
                    }
                    let frame = match result {
                        Ok(frame) => frame,
                        Err(error) => {
                            tracing::warn!(%error, "capture producer failed");
                            None
                        }
                    };
                    *lock(&pointer) = desktop.cursor.clone();
                    let state = CaptureState {
                        screen: desktop.screen.clone(),
                        device: desktop.device.clone(),
                        generation: desktop.generation,
                        available: desktop.available,
                        hdr_available: desktop.hdr_available(),
                        cursor: desktop.cursor.clone(),
                        backend: desktop.backend_name().to_string(),
                    };
                    // Serialize publication with configure(): a capture that
                    // was in flight during a pause/settings change cannot put
                    // its stale frame back into the slot after invalidation.
                    let current = lock(&thread_control);
                    let publish = current.enabled && current.epoch == cfg.epoch;
                    let mut guard = lock(&thread_slot);
                    guard.attempts = guard.attempts.saturating_add(1);
                    guard.capture_total += elapsed;
                    guard.capture_max = guard.capture_max.max(elapsed);
                    if frame.is_none() {
                        guard.empty = guard.empty.saturating_add(1);
                    } else if desktop.backend_name() == "wlroots screencopy" {
                        guard.wlr_wait_total += timings.0;
                        guard.wlr_copy_total += timings.1;
                        guard.wlr_wait_max = guard.wlr_wait_max.max(timings.0);
                        guard.wlr_stage_a = phases.0;
                        guard.wlr_stage_b = phases.1;
                        guard.wlr_copy_max = guard.wlr_copy_max.max(timings.1);
                        guard.wlr_frames = guard.wlr_frames.saturating_add(1);
                    }
                    guard.state = state.clone();
                    if publish && let Some(frame) = frame {
                        if replace_latest(
                            &mut guard.latest,
                            Captured {
                                frame,
                                state,
                                revision: cfg.revision,
                            },
                        ) {
                            guard.replaced = guard.replaced.saturating_add(1);
                        }
                    }
                }
            })
            .context("spawn capture producer")?;
        Ok(Self {
            control,
            slot,
            stop,
            thread: Some(thread),
        })
    }

    fn configure(&self, cfg: CaptureConfig) {
        let mut control = lock(&self.control);
        let invalidate = !cfg.enabled
            || control.enabled != cfg.enabled
            || control.revision != cfg.revision
            || control.quality != cfg.quality
            || control.maximum != cfg.maximum
            || control.fps != cfg.fps
            || control.cursor != cfg.cursor
            || control.hdr != cfg.hdr
            || control.gpu_frames != cfg.gpu_frames;
        let mut cfg = cfg;
        cfg.epoch = control.epoch.wrapping_add(if invalidate { 1 } else { 0 });
        *control = cfg;
        if invalidate {
            // Keep lock order (control, then slot) identical to publication so
            // a just-finished old capture cannot repopulate the slot after clear.
            lock(&self.slot).latest = None;
        }
    }

    fn state(&self) -> CaptureState {
        lock(&self.slot).state.clone()
    }

    fn hdr_available(&self) -> bool {
        lock(&self.slot).state.hdr_available
    }

    fn take_latest(&self) -> Option<Captured> {
        lock(&self.slot).latest.take()
    }

    fn replaced(&self) -> u64 {
        lock(&self.slot).replaced
    }

    fn stats(
        &self,
    ) -> (
        u64,
        u64,
        Duration,
        Duration,
        Duration,
        Duration,
        u64,
        Duration,
        Duration,
        Duration,
        Duration,
    ) {
        let slot = lock(&self.slot);
        (
            slot.attempts,
            slot.empty,
            slot.capture_total,
            slot.capture_max,
            slot.wlr_wait_total,
            slot.wlr_copy_total,
            slot.wlr_frames,
            slot.wlr_wait_max,
            slot.wlr_copy_max,
            slot.wlr_stage_a,
            slot.wlr_stage_b,
        )
    }
}

#[cfg(target_os = "linux")]
impl Drop for CapturePump {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            if thread.join().is_err() {
                tracing::warn!("capture producer panicked during shutdown");
            }
        }
        lock(&self.slot).latest = None;
    }
}

pub(super) fn capture_loop(
    mut screen: capture::Screen,
    handle: Lease,
    cancel: CancellationToken,
    encode_cancel: Arc<AtomicBool>,
    connected: Arc<AtomicBool>,
    business_ready: Arc<AtomicBool>,
    config: Arc<Mutex<VideoConfig>>,
    keyframe: Arc<AtomicBool>,
    sender: mpsc::Sender<encoder::Encoded>,
    started: Instant,
    transport: crate::features::host::transport::Transport,
    negotiated: Arc<crate::features::host::format::Negotiated>,
    publication: tokio::sync::watch::Sender<Published>,
    pointer: Arc<Mutex<Option<crate::platform::cursor_shape::Snapshot>>>,
) -> Result<()> {
    let _activity = crate::platform::host_service::activity::Work::new();
    let mut runtime = encoder::Runtime::new()?;
    #[cfg(target_os = "linux")]
    let mut desktop = None::<CapturePump>;
    #[cfg(not(target_os = "linux"))]
    let mut desktop = None::<capture::Desktop>;
    let mut encoder = None::<encoder::Encoder>;
    let mut current: Option<((u32, u32), ((u64, Backend, Format), Color))> = None;
    let mut next = Instant::now();
    let mut paced_fps = 0;
    let mut cached = None::<capture::Frame>;
    let mut last_frame = None::<Instant>;
    let mut failed = std::collections::HashSet::new();
    let mut encode_errors = 0u32;
    let mut adaptive_encoder = adaptive::Adaptive::default();
    let mut adaptive_workload = None;
    let mut requested = None;
    let mut automatic = None::<crate::features::host::parameters::AutoQuality>;
    let mut initial_auto = true;
    let mut admission = crate::features::host::parameters::WindowAdmission::default();
    let mut generation = 0;
    let mut encoding_device = None::<(u64, capture::Device)>;
    let mut transfer = None::<crate::features::host::transfer::Transfer>;
    let mut frame_metadata = std::collections::BTreeMap::new();
    let mut last_diagnostics = None;
    let mut metrics = metrics::Metrics::default();
    #[cfg(target_os = "linux")]
    let mut observed_replaced = 0u64;
    #[cfg(target_os = "linux")]
    let mut observed_capture_stats = (
        0u64,
        0u64,
        Duration::ZERO,
        Duration::ZERO,
        Duration::ZERO,
        0u64,
        Duration::ZERO,
        Duration::ZERO,
    );
    let mut paused_capture_at = None::<Instant>;

    while !cancel.is_cancelled() && handle.requested() {
        let iteration_started = Instant::now();
        let mut wanted = *lock(&config);
        if !negotiated.permits_format(wanted.format) {
            let previous = wanted.revision;
            let chroma = wanted.format.chroma;
            let hdr = wanted.color.is_hdr();
            negotiated.apply(
                &mut wanted,
                None,
                chroma,
                hdr,
                (screen.width, screen.height),
            )?;
            let mut active = lock(&config);
            if active.revision != previous {
                continue;
            }
            wanted.revision = previous.wrapping_add(1);
            *active = wanted;
        }

        let requested_hdr = wanted.color.is_hdr();
        if desktop.is_none() && transport.media_ready() && wanted.sending && wanted.capturing {
            // The authorized track is already negotiated. Prepare its native
            // capture while ICE/DTLS is still connecting; the gate below still
            // forbids frame acquisition/encoding/sending until transport is up.
            // Nothing is kept warm outside this track's lease/cancellation scope.
            let opened_at = Instant::now();
            let connecting = !connected.load(Ordering::Acquire);
            runtime.begin_gpu_work(negotiated.gpu_compatibility);
            let opened = capture::Desktop::open_selected(&screen)?;
            generation = opened.generation;
            #[cfg(target_os = "linux")]
            {
                desktop = Some(CapturePump::new(opened, pointer.clone())?);
            }
            #[cfg(not(target_os = "linux"))]
            {
                desktop = Some(opened);
            }
            tracing::info!(
                elapsed_ms = opened_at.elapsed().as_millis(),
                connecting,
                "host capture preparation completed"
            );
            // Native setup may overlap incoming CaptureSetting changes. Read
            // the current revision before configuring or encoding any frame.
            continue;
        }
        let hdr_available = desktop.as_ref().is_some_and(|desktop| {
            #[cfg(target_os = "linux")]
            {
                desktop.hdr_available()
            }
            #[cfg(not(target_os = "linux"))]
            {
                desktop.hdr_available()
            }
        });
        if desktop.is_some() {
            // T CCDCA0/CF3450 gate HDR on the actual source, not only the
            // requested setting. Keep the request so same-source HDR recovery
            // can re-enable the negotiated format without rewriting user intent.
            negotiated.apply_source(&mut wanted, (screen.width, screen.height), hdr_available)?;
        }
        let settings = (
            wanted.revision,
            wanted.quality,
            wanted.auto_quality,
            wanted.bitrate,
            wanted.fps,
        );
        let changed = requested != Some(settings);
        if changed {
            adaptive_encoder = adaptive::Adaptive::default();
            cached = None;
            automatic = (wanted.quality == 5).then(|| {
                crate::features::host::parameters::AutoQuality::new(
                    wanted.auto_quality,
                    wanted.maximum_quality,
                )
            });
            initial_auto = true;
            requested = Some(settings);
        }
        let candidate_ids = || {
            negotiated.choices.iter().map(|choice| {
                (
                    choice.capability.adapter,
                    choice.capability.backend,
                    choice.capability.format,
                )
            })
        };
        let source_adapter = screen.render_adapter.unwrap_or(screen.adapter);
        let baseline = negotiated
            .candidate(
                &wanted,
                (screen.width, screen.height),
                source_adapter,
                &failed,
            )
            .context("本会话的协商编码候选已全部失败")?;
        let desired =
            crate::media::geometry::output_size(screen.width, screen.height, wanted.quality);
        let baseline_size = crate::media::geometry::fit_size(
            desired.0,
            desired.1,
            baseline.maximum_for(wanted.requested_maximum),
        );
        let mut compatible_failed = failed.clone();
        for choice in &negotiated.choices {
            let size = crate::media::geometry::fit_size(
                desired.0,
                desired.1,
                choice.maximum_for(wanted.requested_maximum),
            );
            if choice.capability.format != baseline.capability.format
                || size != baseline_size
                || choice.fps < wanted.fps.min(baseline.fps).min(screen.fps)
                || (baseline.capability.backend != Backend::Software
                    && choice.capability.backend == Backend::Software)
            {
                compatible_failed.insert((
                    choice.capability.adapter,
                    choice.capability.backend,
                    choice.capability.format,
                ));
            }
        }
        let excluded = adaptive_encoder.exclusions(candidate_ids(), &compatible_failed);
        let trial = negotiated.candidate(
            &wanted,
            (screen.width, screen.height),
            source_adapter,
            &excluded,
        );
        // Performance trials must never turn a slow encoder into a failed track.
        let candidate = trial
            .or_else(|| {
                adaptive_encoder.finish_trials(Instant::now());
                let excluded = adaptive_encoder.exclusions(candidate_ids(), &compatible_failed);
                negotiated.candidate(
                    &wanted,
                    (screen.width, screen.height),
                    source_adapter,
                    &excluded,
                )
            })
            .or_else(|| {
                negotiated.candidate(
                    &wanted,
                    (screen.width, screen.height),
                    source_adapter,
                    &failed,
                )
            })
            .context("本会话的协商编码候选已全部失败")?;
        let hardware =
            candidate.capability.backend != crate::features::host::format::Backend::Software;
        let candidate_id = (
            candidate.capability.adapter,
            candidate.capability.backend,
            candidate.capability.format,
        );
        wanted.format = candidate.capability.format;
        if wanted.format.depth < 10 {
            wanted.color = crate::features::host::format::Color::Sdr;
        }
        wanted.maximum = candidate.maximum_for(wanted.requested_maximum);
        wanted.fps = wanted.fps.min(candidate.fps).min(screen.fps).max(1);
        if changed {
            tracing::info!(
                requested_fps = wanted.requested_fps,
                configured_fps = wanted.fps,
                configured_limit = wanted.fps_limit,
                monitor_fps_cap = screen.fps,
                encoder_fps_cap = candidate.fps,
                negotiated_maximum_fps = wanted.maximum_fps,
                width = wanted.maximum.0,
                height = wanted.maximum.1,
                ?candidate.capability.backend,
                "host frame rate negotiated"
            );
        }
        metrics.report(wanted.fps, screen.adapter, candidate.capability.adapter);
        let period = Duration::from_secs_f64(1.0 / f64::from(wanted.fps));
        if paced_fps != wanted.fps {
            paced_fps = wanted.fps;
            next = Instant::now();
        }
        let maximum_quality = negotiated.maximum_quality(
            wanted.format,
            (screen.width, screen.height),
            wanted.maximum,
        );
        let budget_size =
            crate::media::geometry::fit_size(screen.width, screen.height, wanted.maximum);
        if let Some(auto) = automatic.as_mut() {
            auto.limit(maximum_quality);
            let sample = transport.automatic_rates();
            if wanted.sending
                && wanted.capturing
                && connected.load(Ordering::Acquire)
                && auto
                    .observe(
                        Instant::now(),
                        wanted.format,
                        budget_size,
                        wanted.fps,
                        last_frame.is_some_and(|at| at.elapsed() < Duration::from_secs(1)),
                        sample,
                    )
                    .is_some()
            {
                initial_auto = false;
            }
            wanted.quality = auto.quality();
        } else if wanted.quality != 6 {
            // The fallback encoder's real size limit also limits the fixed
            // quality budget and QoS label, not only the GPU texture size.
            wanted.quality = wanted.quality.min(maximum_quality);
        }
        transport.fps(wanted.fps);
        let bounds = if automatic.is_some() {
            crate::features::host::parameters::automatic(
                wanted.format,
                wanted.quality,
                budget_size,
                wanted.fps,
                initial_auto,
            )
        } else {
            crate::features::host::parameters::fixed(
                wanted.format,
                wanted.quality,
                wanted.bitrate,
                budget_size,
                wanted.fps,
            )
        };
        let quality_target = crate::features::host::format::QualityTarget {
            bitrate: bounds.maximum,
            fps: wanted.fps,
        };
        if wanted.sending && wanted.capturing {
            transport.quality(automatic.is_some(), wanted.quality, changed);
            transport.configure(bounds);
        } else {
            transport.pause();
        }
        if !connected.load(Ordering::Acquire)
            || !business_ready.load(Ordering::Acquire)
            || !transport.media_ready()
            || !wanted.sending
            || !wanted.capturing
        {
            #[cfg(target_os = "linux")]
            if let Some(pump) = desktop.as_ref() {
                pump.configure(CaptureConfig {
                    enabled: false,
                    revision: wanted.revision,
                    quality: wanted.quality,
                    maximum: wanted.maximum,
                    fps: wanted.fps,
                    cursor: wanted.cursor_capture,
                    hdr: wanted.color.is_hdr(),
                    gpu_frames: false,
                    epoch: 0,
                });
            }
            if !wanted.capturing {
                if last_diagnostics.take().is_some() {
                    handle.video(None);
                }
                desktop = None;
                encoder = None;
                current = None;
                transfer = None;
                encoding_device = None;
                publication.send_if_modified(|state| {
                    if state.capturing || state.visible {
                        state.capturing = false;
                        state.visible = false;
                        true
                    } else {
                        false
                    }
                });
            }
            cached = None;
            if !wanted.capturing {
                runtime.end_gpu_work();
            }
            last_frame = None;
            keyframe.store(true, Ordering::Release);
            next = Instant::now();
            std::thread::sleep(Duration::from_millis(20));
            continue;
        }
        if Instant::now() < next {
            std::thread::sleep(
                next.saturating_duration_since(Instant::now())
                    .min(Duration::from_millis(20)),
            );
            continue;
        }
        if sender.capacity() == 0 {
            metrics.backpressure_waits += 1;
            std::thread::sleep(Duration::from_millis(2));
            continue;
        }
        if desktop.is_none() {
            runtime.begin_gpu_work(negotiated.gpu_compatibility);
            let opened = capture::Desktop::open_selected(&screen)?;
            generation = opened.generation;
            #[cfg(target_os = "linux")]
            {
                desktop = Some(CapturePump::new(opened, pointer.clone())?);
            }
            #[cfg(not(target_os = "linux"))]
            {
                desktop = Some(opened);
            }
            // Readiness can change after the preparation gate above. Apply the
            // same policy and reread settings after either native open path.
            continue;
        }
        if transport.media_rate().min(bounds.maximum)
            < crate::features::host::parameters::MIN_ENCODER_RATE
        {
            // Keep source/cursor/recovery observations alive during a transport
            // pause, without preprocessing the desktop at the full video rate.
            // Do not advance WindowAdmission: it counts actual candidate frames.
            #[cfg(target_os = "linux")]
            if let Some(pump) = desktop.as_ref() {
                pump.configure(CaptureConfig {
                    enabled: false,
                    revision: wanted.revision,
                    quality: wanted.quality,
                    maximum: wanted.maximum,
                    fps: wanted.fps,
                    cursor: wanted.cursor_capture,
                    hdr: wanted.color.is_hdr(),
                    gpu_frames: false,
                    epoch: 0,
                });
            }
            if paused_capture_at.is_some_and(|at| at.elapsed() < Duration::from_millis(100)) {
                std::thread::sleep(Duration::from_millis(10));
                continue;
            }
            paused_capture_at = Some(Instant::now());
        } else {
            paused_capture_at = None;
        }
        let capture_started = Instant::now();
        // This deadline paces the encoder consumer. On Linux, the capture
        // producer runs independently and leaves only its latest frame here.
        next = if capture_started.saturating_duration_since(next) >= period {
            capture_started + period
        } else {
            next + period
        };
        #[cfg(target_os = "linux")]
        let (captured, capture_state) = {
            let pump = desktop.as_ref().context("缺少采集源")?;
            let gpu_frames = matches!(encoder.as_ref(), Some(encoder::Encoder::Vaapi(..)));
            pump.configure(CaptureConfig {
                enabled: true,
                revision: wanted.revision,
                quality: wanted.quality,
                maximum: wanted.maximum,
                fps: wanted.fps,
                cursor: wanted.cursor_capture,
                hdr: wanted.color.is_hdr(),
                gpu_frames,
                epoch: 0,
            });
            let replaced = pump.replaced();
            metrics.capture_replaced += replaced.saturating_sub(observed_replaced);
            observed_replaced = replaced;
            let (
                attempts,
                empty,
                capture_total,
                capture_max,
                wlr_wait,
                wlr_copy,
                wlr_frames,
                wlr_wait_max,
                wlr_copy_max,
                _stage_a,
                _stage_b,
            ) = pump.stats();
            let attempt_delta = attempts.saturating_sub(observed_capture_stats.0);
            let empty_delta = empty.saturating_sub(observed_capture_stats.1);
            let elapsed_delta = capture_total.saturating_sub(observed_capture_stats.2);
            let wlr_wait_delta = wlr_wait.saturating_sub(observed_capture_stats.3);
            let wlr_copy_delta = wlr_copy.saturating_sub(observed_capture_stats.4);
            let wlr_frame_delta = wlr_frames.saturating_sub(observed_capture_stats.5);
            metrics
                .capture
                .record_batch(attempt_delta, elapsed_delta, capture_max);
            metrics
                .wlr_wait
                .record_batch(wlr_frame_delta, wlr_wait_delta, wlr_wait_max);
            metrics
                .wlr_copy
                .record_batch(wlr_frame_delta, wlr_copy_delta, wlr_copy_max);
            metrics.empty_captures += empty_delta;
            observed_capture_stats = (
                attempts,
                empty,
                capture_total,
                wlr_wait,
                wlr_copy,
                wlr_frames,
                wlr_wait_max,
                wlr_copy_max,
            );
            let status = pump.state();
            let packet = pump
                .take_latest()
                .filter(|packet| packet.revision == wanted.revision);
            if let Some(packet) = packet {
                metrics
                    .capture_to_encode
                    .record(packet.frame.captured.elapsed());
                (Some(packet.frame), packet.state)
            } else {
                (None, status)
            }
        };
        #[cfg(not(target_os = "linux"))]
        let (captured, capture_state) = {
            let wait_ms = ((1000.0 / f64::from(wanted.fps)).round() as u32).max(1);
            let captured = desktop.as_mut().context("缺少采集源")?.next(
                wait_ms,
                wanted.quality,
                wanted.cursor_capture,
                wanted.color.is_hdr(),
                wanted.maximum,
            );
            metrics.capture.record(capture_started.elapsed());
            (captured?, desktop.as_ref().context("缺少采集源")?)
        };
        *lock(&pointer) = capture_state.cursor.clone();
        if generation != capture_state.generation {
            adaptive_encoder = adaptive::Adaptive::default();
            generation = capture_state.generation;
            encoder = None;
            cached = None;
            current = None;
            last_frame = None;
            transfer = None;
            keyframe.store(true, Ordering::Release);
        }
        screen = capture_state.screen.clone();
        // A selected source remains attached during temporary capture loss.
        // Removing its track here can make the receiver unsubscribe and prevent
        // the next capture attempt. Visibility has its own permission report.
        publication.send_if_modified(|state| {
            let changed = state.screen != screen || !state.capturing;
            if state.screen != screen {
                state.screen.clone_from(&screen);
            }
            state.capturing = true;
            changed
        });
        handle.source(&screen, &capture_backend_name(&capture_state));
        if wanted.color.is_hdr()
            != (requested_hdr && capture_state.hdr_available && wanted.format.depth == 10)
        {
            // Capture recovery or source refresh changed the effective format.
            // Rebuild preprocessing/encoder before admitting this frame.
            cached = None;
            continue;
        }
        if !capture_state.available {
            cached = None;
            publication.send_if_modified(|state| {
                if state.visible {
                    state.visible = false;
                    true
                } else {
                    false
                }
            });
            std::thread::sleep(Duration::from_millis(20));
            continue;
        }
        let frame = match captured {
            Some(frame) => {
                metrics.new_frames += u64::from(frame.is_new);
                cached = Some(frame.clone());
                frame
            }
            None => {
                #[cfg(not(target_os = "linux"))]
                {
                    metrics.empty_captures += 1;
                }
                let Some(mut frame) = cached.clone() else {
                    continue;
                };
                // A static desktop needs no repeated inter frame. Keep one
                // low-rate refresh for decoder continuity, but honor an
                // immediate receiver keyframe request without waiting.
                let refresh_due =
                    last_frame.is_none_or(|at| at.elapsed() >= Duration::from_secs(1));
                if !keyframe.load(Ordering::Acquire) && !refresh_due {
                    continue;
                }
                frame.captured = Instant::now();
                frame.is_new = false;
                frame
            }
        };
        let peak = transport.media_rate().min(bounds.maximum);
        // A zero/tiny allocation is a transport pause, not an encoder failure.
        // Do not fabricate bandwidth by clamping it back to a quality floor.
        let codec_minimum = crate::features::host::parameters::MIN_ENCODER_RATE;
        if peak < codec_minimum {
            continue;
        }
        let (media_rate, discard_frame) =
            admission.next(peak, codec_minimum, transport.cwnd_ratio());
        if discard_frame {
            metrics.admission_drops += 1;
            continue;
        }
        let size = output_size(frame.width, frame.height, wanted.quality);
        let workload = (size, wanted.fps, wanted.format, wanted.color);
        if adaptive_workload != Some(workload) {
            adaptive_encoder = adaptive::Adaptive::default();
            adaptive_workload = Some(workload);
        }
        let source_device = &capture_state.device;
        // NVENC owns a separate D3D11 context even on the capture adapter.
        // Transfer explicitly orders capture/preprocessing before encoding,
        // instead of relying on NVENC's handling of the capture context.
        let isolated_nvenc = hardware
            && candidate.capability.backend == crate::features::host::format::Backend::Nvidia;
        let device =
            if !hardware || (!isolated_nvenc && candidate.capability.adapter == screen.adapter) {
                source_device.clone()
            } else {
                if encoding_device
                    .as_ref()
                    .is_none_or(|(adapter, _)| *adapter != candidate.capability.adapter)
                {
                    match capture::create_device(candidate.capability.adapter) {
                        Ok((device, _)) => {
                            tracing::info!(
                                source_adapter = screen.adapter,
                                encoder_adapter = candidate.capability.adapter,
                                isolated_nvenc,
                                "host encoding device created independently of capture"
                            );
                            encoding_device = Some((candidate.capability.adapter, device))
                        }
                        Err(error) => {
                            tracing::warn!(%error,?candidate_id,"encoder adapter failed");
                            failed.insert(candidate_id);
                            continue;
                        }
                    }
                }
                encoding_device.as_ref().unwrap().1.clone()
            };
        if current.is_some_and(|(old_size, old_candidate)| {
            (old_size != size || old_candidate != (candidate_id, wanted.color))
                && old_candidate.0.0 == candidate_id.0
                && old_candidate.0.1 == candidate_id.1
        }) && let Some(active) = encoder.as_mut()
        {
            let rate = crate::features::host::format::Rate {
                target: bounds.initial.max(300_000),
                peak: bounds.initial.max(300_000),
                fps: wanted.fps,
                quality: wanted.quality,
                quality_target,
            };
            match active.reconfigure_stream(&device, size, wanted.format, rate, wanted.color) {
                Ok(true) => {
                    handle.encoder(
                        active.implementation(),
                        active.maximum_size(),
                        &screen,
                        &capture_backend_name(&capture_state),
                    );
                    current = Some((size, (candidate_id, wanted.color)));
                    frame_metadata.clear();
                    encode_errors = 0;
                    keyframe.store(true, Ordering::Release);
                }
                Ok(false) => {}
                Err(error) => {
                    tracing::warn!(%error, ?size, ?candidate_id, "host encoder stream reconfiguration rejected; rebuilding candidate")
                }
            }
        }
        if current.is_none_or(|(old_size, old_candidate)| {
            old_size != size || old_candidate != (candidate_id, wanted.color)
        }) {
            let rebuilding = Instant::now();
            // Release before replacement to avoid consuming a second driver
            // session solely for a size/candidate transition (T C2D840).
            drop(encoder.take());
            let close_ms = rebuilding.elapsed().as_secs_f64() * 1000.;
            let created = if hardware {
                encoder::Encoder::hardware_format(
                    &device,
                    candidate.capability.backend,
                    size,
                    wanted.format,
                    crate::features::host::format::Rate {
                        target: bounds.initial.max(300_000),
                        peak: bounds.initial.max(300_000),
                        fps: wanted.fps,
                        quality: wanted.quality,
                        quality_target,
                    },
                    wanted.color,
                )
            } else {
                encoder::Encoder::software_format(
                    &device,
                    size,
                    wanted.format,
                    crate::features::host::format::Rate {
                        target: bounds.initial.max(300_000),
                        peak: bounds.initial.max(300_000),
                        fps: wanted.fps,
                        quality: wanted.quality,
                        quality_target,
                    },
                )
            };
            encoder = match created {
                Ok(created) => {
                    tracing::info!(
                        ?size,
                        ?candidate_id,
                        close_ms,
                        elapsed_ms = rebuilding.elapsed().as_secs_f64() * 1000.,
                        "host encoder replacement completed"
                    );
                    handle.encoder(
                        created.implementation(),
                        created.maximum_size(),
                        &screen,
                        &capture_backend_name(&capture_state),
                    );
                    Some(created)
                }
                Err(error) => {
                    tracing::warn!(%error,?candidate_id,"host encoder initialization failed; disabling this candidate");
                    failed.insert(candidate_id);
                    encoder = None;
                    cached = None;
                    current = None;
                    continue;
                }
            };
            current = Some((size, (candidate_id, wanted.color)));
            frame_metadata.clear();
            encode_errors = 0;
            keyframe.store(true, Ordering::Release);
        }
        let active_encoder = encoder.as_mut().context("缺少编码器")?;
        let needs_transfer = device != *source_device;
        let transfer_started = Instant::now();
        let prepared = (|| -> Result<Option<crate::features::host::transfer::Delivery>> {
            if !needs_transfer {
                return Ok(None);
            }
            if transfer
                .as_ref()
                .is_none_or(|t| !t.matches(source_device, &device, &frame))
            {
                transfer = Some(crate::features::host::transfer::Transfer::new(
                    source_device,
                    &device,
                    &frame,
                )?);
            }
            transfer
                .as_mut()
                .unwrap()
                .copy(&frame, || !encode_cancel.load(Ordering::Acquire))
        })();
        if needs_transfer {
            metrics.transfer.record(transfer_started.elapsed());
        }
        if needs_transfer && prepared.as_ref().is_ok_and(|frame| frame.is_none()) {
            metrics.transfer_busy += 1;
            continue;
        }
        let encoded = (|| -> Result<_> {
            // Delivery failures share the source-loss/candidate-recovery exits.
            let delivery = prepared?;
            let rate_started = Instant::now();
            let reconfigured = active_encoder.configure_rate(crate::features::host::format::Rate {
                target: media_rate,
                peak,
                fps: wanted.fps,
                quality: wanted.quality,
                quality_target,
            });
            metrics.rate.record(rate_started.elapsed());
            if reconfigured? {
                keyframe.store(true, Ordering::Release);
            }
            // Network requests are rate-limited at their RTCP entry. Explicit
            // quality/source controls and failed inputs must not wait 600ms.
            let force = keyframe.swap(false, Ordering::AcqRel);
            let encode_started = Instant::now();
            let timestamp = (frame.captured.duration_since(started).as_nanos() / 100) as i64;
            frame_metadata.insert(
                timestamp,
                (
                    frame.captured,
                    encode_started,
                    frame.is_new,
                    frame.hdr_metadata,
                ),
            );
            while frame_metadata.len() > 2048 {
                frame_metadata.pop_first();
            }
            let encoded = active_encoder.encode_cancellable(
                delivery.as_ref().map_or(&frame.image, |d| &d.frame.image),
                timestamp,
                force,
                &encode_cancel,
            );
            let encode_elapsed = encode_started.elapsed();
            metrics.encode.record(encode_elapsed);
            let encoded = encoded?;
            for output in &encoded {
                if let Some(actual) = crate::media::video_format::parse_stream_format(
                    output.format.codec.media(),
                    &output.data,
                ) {
                    anyhow::ensure!(
                        actual.chroma_format_idc == output.format.chroma
                            && actual.bit_depth_luma == output.format.depth
                            && actual.bit_depth_chroma == output.format.depth
                            && (actual.visible_width, actual.visible_height) == size,
                        "编码器实际输出偏离协商格式"
                    );
                }
            }
            if frame.is_new && !force && !encoded.is_empty() {
                if let Some(average) =
                    adaptive_encoder.observe(candidate_id, Instant::now(), encode_elapsed, period)
                {
                    tracing::info!(
                        ?candidate_id,
                        average_ms = average.as_secs_f64() * 1000.0,
                        budget_ms = period.as_secs_f64() * 1000.0,
                        "host encoder performance trial updated"
                    );
                }
            }
            Ok((encoded, force, Instant::now()))
        })();
        metrics.work.record(iteration_started.elapsed());
        if cancel.is_cancelled() || !handle.requested() {
            break;
        }
        let (encoded, force, encode_finished) = match encoded {
            Ok(encoded) => {
                encode_errors = 0;
                encoded
            }
            Err(error) => {
                if capture::device_lost(source_device) {
                    tracing::warn!(%error,"capture graphics device was removed; recreating selected source");
                    encoder = None;
                    current = None;
                    cached = None;
                    desktop = None;
                    transfer = None;
                    encoding_device = None;
                    last_frame = None;
                    publication.send_if_modified(|state| {
                        if state.visible {
                            state.visible = false;
                            true
                        } else {
                            false
                        }
                    });
                    keyframe.store(true, Ordering::Release);
                    continue;
                }
                encode_errors += 1;
                tracing::warn!(%error,hardware,consecutive=encode_errors,"host encoding failed");
                keyframe.store(true, Ordering::Release);
                next = Instant::now() + Duration::from_secs_f64(1.0 / f64::from(wanted.fps));
                if encode_errors >= 10
                    || error.downcast_ref::<encoder::SwitchCandidate>().is_some()
                    || capture::device_lost(&device)
                {
                    // T C2C610: disable the failed candidate. Do not endlessly
                    // reopen that same encoder after its consecutive failures.
                    failed.insert(candidate_id);
                    encoder = None;
                    current = None;
                    cached = None;
                    last_frame = None;
                }
                continue;
            }
        };
        metrics.encoded_frames += encoded.len() as u64;
        if force && !encoded.iter().any(|frame| frame.keyframe) {
            keyframe.store(true, Ordering::Release);
        }
        last_frame = Some(frame.captured);
        if !encoded.is_empty() {
            let diagnostics = super::super::ActiveEncoding {
                preferred_gpu: negotiated.preference().0,
                preferred_codec: negotiated.preference().1,
                selection: if candidate_id
                    != (
                        baseline.capability.adapter,
                        baseline.capability.backend,
                        baseline.capability.format,
                    ) {
                    crate::media::selection::SelectionReason::PerformanceFallback
                } else {
                    negotiated.selection_reason(&candidate.capability, !failed.is_empty())
                },
                backend: candidate.capability.backend,
                adapter: candidate.capability.adapter,
                format: wanted.format,
                size,
                fps: wanted.fps,
                target_bps: media_rate,
            };
            if last_diagnostics != Some(diagnostics) {
                handle.video(Some(diagnostics));
                last_diagnostics = Some(diagnostics);
            }
            let mut active = lock(&config);
            if active.revision == wanted.revision {
                active.reported_quality = wanted.quality;
            }
            let state = Published {
                screen: screen.clone(),
                capturing: true,
                visible: true,
                quality: wanted.quality,
                fps: wanted.fps,
                format: wanted.format,
                budget_size,
                encoder: Some(candidate.capability.backend),
                capture: capture_backend_name(&capture_state),
            };
            publication.send_if_modified(|old| {
                if *old != state {
                    *old = state;
                    true
                } else {
                    false
                }
            });
        }
        for mut frame in encoded {
            if let Some((captured, input_started, is_new, metadata)) =
                frame_metadata.remove(&frame.timestamp_100ns)
            {
                frame.is_new = is_new;
                frame.color.hdr_metadata = if frame.color.transfer == 16 {
                    metadata
                } else {
                    None
                };
                frame.timing = Some(encoder::FrameTiming {
                    captured,
                    encode_started: input_started,
                    encode_finished,
                });
            }
            // No B frames: older metadata cannot belong to a later output.
            while frame_metadata
                .first_key_value()
                .is_some_and(|(&timestamp, _)| timestamp < frame.timestamp_100ns)
            {
                frame_metadata.pop_first();
            }
            sender.blocking_send(frame).context("画面发送队列已关闭")?;
        }
    }
    Ok(())
}
