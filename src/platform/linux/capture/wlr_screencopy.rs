//! Capture through the compositor's own `zwlr_screencopy` protocol: wlroots,
//! niri, Sway, Hyprland and their relatives all serve it. Unlike the portal it
//! asks the local user for nothing, and it needs no permission the session does
//! not already have.
//!
//! Frames come back in shared memory by default. The protocol's other half — a
//! dma-buf the compositor draws into — keeps the picture on the GPU, but only
//! pays off when nothing reads it back: a device buffer mapped into a process
//! on the discrete GPU of this machine reads at about 85 MB/s, so a 16 MB frame
//! takes 195 ms against 1.4 ms through shared memory. This backend therefore
//! asks for a dma-buf only when the encoder takes NV12 frames straight from the
//! GPU (VA-API), and converts them there through the same EGL/VideoProc path
//! the portal stream uses. A driver that cannot allocate or import it keeps the
//! shared-memory path, and a rotated or flipped output stays on shared memory
//! because the GPU conversion does not undo the transform.
//!
//! The compositor copies its own framebuffer, not the picture the monitor
//! shows: a rotated or flipped output arrives sideways, and the transform
//! `wl_output.geometry` advertises has to be undone here, exactly as `grim`
//! and every other screencopy client does. Left undone, a portrait monitor
//! streams a landscape picture of a sideways desktop twice over — the shape
//! is wrong and so is everything in it.
//!
//! `copy_with_damage` is used rather than `copy`: the compositor then holds the
//! frame back until something has actually changed, which is what keeps an idle
//! desktop from being encoded and sent over and over.
//!
//! The copy carries no scaling: the buffer has to be exactly the output's size,
//! and the caller scales afterwards like it does for the portal.
use super::Picture;
use super::Screen;
use super::gbm;
use anyhow::{Context, Result, bail, ensure};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::time::{Duration, Instant};
use wayland_client::backend::WaylandError;
use wayland_client::protocol::wl_output::Transform;
use wayland_client::protocol::{wl_buffer, wl_output, wl_registry, wl_shm, wl_shm_pool};
use wayland_client::{Connection, Dispatch, EventQueue, QueueHandle};
use wayland_protocols::wp::linux_dmabuf::zv1::client::{
    zwp_linux_buffer_params_v1, zwp_linux_dmabuf_v1,
};
use wayland_protocols_wlr::screencopy::v1::client::{
    zwlr_screencopy_frame_v1, zwlr_screencopy_manager_v1,
};

/// `wl_output.name` arrived at 4, and it is how a captured monitor is paired
/// with the one the caller asked for.
const OUTPUT_VERSION: u32 = 4;

/// The one thing a compositor says when it will not copy: a `failed` event,
/// which covers an output that has been turned off as well as any other
/// refusal.
const COPY_REFUSED: &str = "合成器拒绝了屏幕复制（输出可能已关闭）";

/// One output the compositor offers to copy.
struct Output {
    proxy: wl_output::WlOutput,
    name: Option<String>,
    /// The mode the output is driving, before `transform` is applied.
    mode: (u32, u32),
    transform: Transform,
}

impl Output {
    /// The size the desktop sees: the mode as the transform turns it, which is
    /// what the caller's `Screen` describes and what a frame will come back as.
    fn size(&self) -> (u32, u32) {
        match self.transform {
            Transform::_90 | Transform::_270 | Transform::Flipped90 | Transform::Flipped270 => {
                (self.mode.1, self.mode.0)
            }
            _ => self.mode,
        }
    }
}

/// The shared memory every frame is copied into, and the buffer that names it.
/// The pool and the descriptor stay alive as long as the buffer does: the
/// compositor reads the memory through them.
struct Shm {
    _fd: OwnedFd,
    pool: wl_shm_pool::WlShmPool,
    map: *mut u8,
    length: usize,
    buffer: wl_buffer::WlBuffer,
    format: u32,
    width: u32,
    height: u32,
    stride: u32,
    /// The compositor's pixels have red and blue the other way round from the
    /// BGRA the encoder wants; see `swapped_channels`.
    swap: bool,
}

// SAFETY: the mapping and Wayland proxies are exclusively owned by Desktop;
// moving that owner to the capture thread does not share the raw mapping.
unsafe impl Send for Shm {}

impl Shm {
    fn matches(&self, format: u32, width: u32, height: u32, stride: u32) -> bool {
        self.format == format
            && self.width == width
            && self.height == height
            && self.stride == stride
    }

    /// The picture as tightly packed BGRA rows, without the stride padding and
    /// with the output's transform undone.
    fn picture(&self, transform: Transform) -> Picture {
        let row = self.width as usize * 4;
        // Safe because the mapping is live for as long as `self` is, and the
        // compositor has finished with it: a `ready` event preceded this.
        let all = unsafe { std::slice::from_raw_parts(self.map, self.length) };
        let mut pixels = Vec::with_capacity(row * self.height as usize);
        if self.stride as usize == row && all.len() >= row * self.height as usize {
            pixels.extend_from_slice(&all[..row * self.height as usize]);
        } else {
            for line in all
                .chunks_exact(self.stride as usize)
                .take(self.height as usize)
            {
                // A stride narrower than a row is refused in `allocate`, so
                // this takes whole rows; the clamp is only there to keep a
                // short mapping from panicking.
                pixels.extend_from_slice(&line[..row.min(line.len())]);
            }
        }
        if self.swap {
            // The alpha byte of an `abgr8888` frame sits in byte 3 like the
            // encoder's, so only the two colour bytes move.
            for pixel in pixels.chunks_exact_mut(4) {
                pixel.swap(0, 2);
            }
        }
        let (pixels, width, height) = orient(pixels, self.width, self.height, transform);
        Picture {
            width,
            height,
            pixels,
            nv12: None,
        }
    }
}

impl Drop for Shm {
    fn drop(&mut self) {
        // wayland-client sends no destructor when a proxy is dropped, so both
        // objects have to be released by hand: `copy` replaces this pair
        // whenever the output's shape changes, and the compositor would
        // otherwise keep the buffer, the pool and the memory behind them until
        // the connection ends. The buffer goes first — the pool is where its
        // memory came from.
        self.buffer.destroy();
        self.pool.destroy();
        // Safe because the mapping was made for this length in `allocate`.
        unsafe { libc::munmap(self.map.cast(), self.length) };
    }
}

pub(super) struct Grab {
    queue: EventQueue<State>,
    state: State,
    cursor: bool,
    output: usize,
}

#[derive(Default)]
struct State {
    connection: Option<Connection>,
    manager: Option<zwlr_screencopy_manager_v1::ZwlrScreencopyManagerV1>,
    shm: Option<wl_shm::WlShm>,
    outputs: Vec<Output>,
    frame: Option<zwlr_screencopy_frame_v1::ZwlrScreencopyFrameV1>,
    /// What the frame in flight says its buffer must look like.
    wanted: Option<(u32, u32, u32, u32)>,
    shm_buffer: Option<Shm>,
    /// The compositor has finished drawing the frame already requested.
    ready: bool,
    failed: bool,
    /// `flags` said the frame is copied upside down.
    y_invert: bool,
    error: Option<String>,
    /// The transform the selected output advertises, kept here because the
    /// buffer choice happens on `State`.
    transform: Option<Transform>,
    dmabuf: Option<zwp_linux_dmabuf_v1::ZwpLinuxDmabufV1>,
    /// The (format, modifier) pairs the compositor says it can import.
    dmabuf_formats: Vec<(u32, u64)>,
    /// How many format and modifier events arrived, and at which version the
    /// global was bound; read by the live session test.
    dmabuf_formats_seen: usize,
    dmabuf_version: u32,
    /// The frame in flight offered DMA-BUF with this format and size.
    offered: Option<(u32, u32, u32)>,
    /// Set by the worker when the encoder takes NV12 frames without a readback.
    gpu_frames: bool,
    /// The buffer this grab copies into, with the device it was allocated from.
    dma: Option<(gbm::Device, gbm::Buffer, wl_buffer::WlBuffer)>,
    /// Whether the frame in flight was copied into `dma`.
    dmabuf_frame: bool,
    /// One failed attempt keeps the shared-memory path for the rest of the grab.
    dmabuf_failed: bool,
    /// Geometry the encoder asked for; `None` keeps the source size.
    target: Option<(i32, (u32, u32))>,
    /// How long the last frame waited for the compositor, and how long its
    /// pixels took to leave the mapping. Read by the live session test.
    timings: (Duration, Duration),
    // When the frame in flight was requested and when its copy was submitted,
    // so the two halves of the protocol round trip can be told apart.
    requested_at: Option<Instant>,
    copied_at: Option<Instant>,
    // Last request-to-buffer_done and copy-to-ready durations.
    phases: (Duration, Duration),
}

impl Grab {
    pub fn open(screen: &Screen, cursor: bool) -> Result<Self> {
        let connection =
            Connection::connect_to_env().context("没有可用的 Wayland 会话（WAYLAND_DISPLAY）")?;
        let mut queue = connection.new_event_queue::<State>();
        let handle = queue.handle();
        connection.display().get_registry(&handle, ());
        let mut state = State {
            connection: Some(connection),
            ..Default::default()
        };
        queue
            .roundtrip(&mut state)
            .context("读取 Wayland 全局对象失败")?;
        // The compositor answers a bind from its own event loop, so what the
        // outputs say about themselves arrives one round trip later.
        queue
            .roundtrip(&mut state)
            .context("读取 Wayland 输出描述失败")?;
        // The registry reports what it could not bind; a compositor too old
        // for `buffer_done` says so there. Refusing here leaves the next
        // candidate — the portal — to try.
        if let Some(error) = &state.error {
            bail!("{error}");
        }
        ensure!(
            state.manager.is_some(),
            "合成器没有提供 zwlr_screencopy_manager_v1"
        );
        ensure!(state.shm.is_some(), "合成器没有提供 wl_shm");
        ensure!(!state.outputs.is_empty(), "合成器没有提供任何输出");
        let output = pick(&state.outputs, screen)?;
        state.transform = Some(state.outputs[output].transform);
        tracing::info!(
            output = screen.device_name,
            mode = ?state.outputs[output].mode,
            transform = ?state.outputs[output].transform,
            "host wlr-screencopy output selected"
        );
        let mut grab = Self {
            queue,
            state,
            cursor,
            output,
        };
        grab.probe()?;
        Ok(grab)
    }

    /// Takes one frame before the grab is handed over. Serving the protocol is
    /// not the same as being able to copy: the Vulkan renderer of wlroots has
    /// no read-back at all, an output that is off answers `failed`, and a
    /// format this backend refuses is only known once a frame announces it.
    /// `Backend::select` moves on to the next candidate only when `open`
    /// returns an error, so all three have to be found out here or the portal
    /// below is never reached.
    fn probe(&mut self) -> Result<()> {
        self.request()?;
        // `buffer` and `buffer_done` leave the compositor as it handles the
        // request itself, so this answers at once even on a still desktop
        // where no frame would be drawn for a while.
        self.queue
            .roundtrip(&mut self.state)
            .context("读取 Wayland 事件失败")?;
        if let Some(error) = self.state.error.take() {
            bail!("{error}");
        }
        ensure!(!self.state.failed, "{COPY_REFUSED}");
        ensure!(self.state.wanted.is_some(), "合成器没有说明帧的缓冲格式");
        // The probe's frame is single-use and is not wanted: drop it, and let
        // the first `next` open a frame of its own. The buffer is kept, and is
        // taken up again when the next frame asks for the same shape.
        self.drop_frame();
        self.state.wanted = None;
        self.state.ready = false;
        Ok(())
    }

    pub fn name(&self) -> &'static str {
        "wlroots screencopy"
    }

    /// Whether the compositor draws the cursor into the pictures.
    pub fn cursor(&self) -> bool {
        self.cursor
    }

    /// Lets this grab copy into a DMA-BUF buffer instead of shared memory.
    ///
    /// Only worth it when the encoder takes the converted frame straight from
    /// the GPU: a readback of a device buffer on a discrete GPU is far slower
    /// than the shared-memory copy it would replace.
    pub fn enable_dmabuf(&mut self, on: bool) {
        // Opt-in: replacing the buffer when the output's shape changes drops
        // the one the compositor may still be drawing into, and the live
        // session test hits a protocol error there. The shared-memory path
        // stays the default until that is fixed.
        self.state.gpu_frames =
            on && std::env::var("OPENUUYC_CAPTURE_DMABUF").is_ok_and(|value| value != "0");
    }

    /// Sets the geometry the encoder wants; the GPU converter then scales
    /// straight into it instead of the caller scaling on the CPU.
    pub fn set_target(&mut self, target: Option<(i32, (u32, u32))>) {
        self.state.target = target;
    }

    /// How long the last frame waited and how long reading it from the
    /// mapping took.
    pub fn last_timings(&self) -> (Duration, Duration) {
        self.state.timings
    }

    // How long the compositor took to answer a request with buffer_done, and
    // how long its copy then took to reach ready.
    pub fn last_phases(&self) -> (Duration, Duration) {
        self.state.phases
    }

    /// What the DMA-BUF path did: the output's transform, how many
    /// (format, modifier) pairs the compositor advertised, whether the last
    /// frame offered a buffer, whether one is allocated, and whether the path
    /// was given up. Read by the live session test.
    pub fn dmabuf_report(&self) -> (Option<Transform>, u32, usize, usize, bool, bool, bool) {
        (
            self.state.transform,
            self.state.dmabuf_version,
            self.state.dmabuf_formats_seen,
            self.state.dmabuf_formats.len(),
            self.state.offered.is_some(),
            self.state.dma.is_some(),
            self.state.dmabuf_failed,
        )
    }

    pub fn next(&mut self, timeout: Duration) -> Result<Option<Picture>> {
        let deadline = Instant::now() + timeout;
        let mut waited = Duration::ZERO;
        loop {
            // Kept rather than taken: the compositor is not going to change
            // its mind on its own, and `Ok(None)` every frame would leave the
            // caller with a black picture and nothing to report.
            if let Some(error) = &self.state.error {
                bail!("{error}");
            }
            if std::mem::take(&mut self.state.ready) {
                let copy_started = Instant::now();
                let transform = self
                    .state
                    .outputs
                    .get(self.output)
                    .map_or(Transform::Normal, |output| output.transform);
                let transform = if self.state.y_invert {
                    invert(transform)
                } else {
                    transform
                };
                let picture = if self.state.dmabuf_frame {
                    match self.state.dmabuf_picture() {
                        Ok(picture) => Some(picture),
                        Err(error) => {
                            // Keep the stream alive: the next frame falls back
                            // to shared memory.
                            tracing::warn!(%error, "DMA-BUF 帧转换失败，改用共享内存");
                            self.state.dmabuf_failed = true;
                            self.state.dma = None;
                            None
                        }
                    }
                } else {
                    self.state
                        .shm_buffer
                        .as_ref()
                        .map(|shm| shm.picture(transform))
                };
                // Ask for the next frame before returning. The request is
                // only buffered: it leaves with the flush in the next `wait`,
                // and the copy itself follows once `buffer_done` answers it,
                // so nothing is drawn while the caller encodes this picture.
                // `request` deliberately does not flush. It would gain nothing
                // measurable, and in `probe` it parts the request from the
                // round trip after it, which then sends the probe's copy on
                // its own, ahead of its `destroy`: the compositor can complete
                // that copy and use up the damage the first real frame waits
                // for, and a still desktop then sends no frame at all.
                self.state.timings = (waited, copy_started.elapsed());
                self.request()?;
                if let Some(picture) = picture {
                    return Ok(Some(picture));
                }
            }
            if std::mem::take(&mut self.state.failed) {
                self.request()?;
                bail!("{COPY_REFUSED}");
            }
            if self.state.frame.is_none() {
                self.request()?;
            }
            let now = Instant::now();
            if now >= deadline {
                return Ok(None);
            }
            let wait_started = Instant::now();
            let outcome = self.wait(deadline - now);
            waited += wait_started.elapsed();
            outcome?;
        }
    }

    /// Starts one frame; the frame object is single-use, so every copy needs a
    /// new one.
    fn request(&mut self) -> Result<()> {
        let handle = self.queue.handle();
        let manager = self.state.manager.clone().context("采集已关闭")?;
        self.state.wanted = None;
        self.state.offered = None;
        self.state.dmabuf_frame = false;
        self.drop_frame();
        let output = self
            .state
            .outputs
            .get(self.output)
            .context("采集的输出已消失")?;
        self.state.requested_at = Some(Instant::now());
        self.state.copied_at = None;
        self.state.frame =
            Some(manager.capture_output(i32::from(self.cursor), &output.proxy, &handle, ()));
        Ok(())
    }

    /// Drops the frame of the previous request. wayland-client sends no
    /// destructor when a proxy is dropped, and a frame object serves one copy,
    /// so it has to be destroyed here or every copy leaves an object behind on
    /// the compositor's side.
    fn drop_frame(&mut self) {
        if let Some(frame) = self.state.frame.take() {
            frame.destroy();
        }
    }

    /// Dispatches until an event arrives or `timeout` runs out. Events already
    /// queued are handled first, so a compositor that answers at once never
    /// waits on the socket.
    fn wait(&mut self, timeout: Duration) -> Result<()> {
        self.queue.dispatch_pending(&mut self.state)?;
        let connection = self.state.connection.as_ref().context("采集已关闭")?;
        // `dispatch_pending` only drains what has already arrived; a request
        // made since the last wait is still in the outgoing buffer.
        connection.flush().context("发送 Wayland 请求失败")?;
        let Some(guard) = connection.prepare_read() else {
            return Ok(());
        };
        let mut poll = libc::pollfd {
            fd: guard.connection_fd().as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            // Safe because `poll` only writes into the one descriptor it was
            // given.
            let ready = unsafe { libc::poll(&mut poll, 1, millis(remaining)) };
            if ready < 0 {
                let error = std::io::Error::last_os_error();
                // A signal — SIGCHLD, the SIGTERM of a shutdown — cuts `poll`
                // short without anything being wrong with the capture: wait
                // out what is left of the timeout instead of reporting it as a
                // failure. The deadline is fixed, so a stream of signals
                // cannot stretch the wait.
                if error.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                drop(guard);
                bail!("等待 Wayland 事件失败：{error}");
            }
            if ready == 0 {
                // Dropping the guard releases the prepared read.
                drop(guard);
                return Ok(());
            }
            break;
        }
        match guard.read() {
            Ok(_) => {}
            // The socket can report readable with nothing left to read, and a
            // signal can interrupt the read itself.
            Err(WaylandError::Io(error))
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::Interrupted
                ) =>
            {
                return Ok(());
            }
            Err(error) => return Err(error).context("读取 Wayland 事件失败"),
        }
        self.queue.dispatch_pending(&mut self.state)?;
        Ok(())
    }
}

/// Undoes the output's transform, which the compositor leaves to the client:
/// what arrives is the framebuffer, and a monitor standing on its side shows up
/// in it lying down. The mapping is `wl_output`'s own, read as "the framebuffer
/// pixel this picture pixel is", and `Normal` — the one case that needs no work
/// — passes the rows straight through. `orient_reference` in the tests spells
/// the mapping out pixel by pixel; this is the same mapping, done fast.
///
/// Pixels move as whole four-byte units. The three transforms that keep the
/// shape move whole rows. A quarter turn makes each picture row out of a
/// framebuffer column, and walking a 2560-pixel column touches a new cache
/// line, 10 KB from the last, on every read; that cost 17 ms a frame at
/// 2560x1600. `turn` goes through the picture in 64x64 tiles instead, so the
/// 64 framebuffer rows a tile reads from stay in cache while it is filled.
fn orient(source: Vec<u8>, width: u32, height: u32, transform: Transform) -> (Vec<u8>, u32, u32) {
    let (w, h) = (width as usize, height as usize);
    // Which of `turn`'s and `mirror`'s variants undoes this transform; any
    // transform a later revision adds is passed through like `Normal`.
    let out = match transform {
        Transform::_90 => turn::<false, true>(pixels(&source, w, h), w, h),
        Transform::_270 => turn::<true, false>(pixels(&source, w, h), w, h),
        Transform::Flipped90 => turn::<false, false>(pixels(&source, w, h), w, h),
        Transform::Flipped270 => turn::<true, true>(pixels(&source, w, h), w, h),
        Transform::_180 => mirror(pixels(&source, w, h), w, h, true, true),
        Transform::Flipped => mirror(pixels(&source, w, h), w, h, true, false),
        Transform::Flipped180 => mirror(pixels(&source, w, h), w, h, false, true),
        _ => return (source, width, height),
    };
    let turned = matches!(
        transform,
        Transform::_90 | Transform::_270 | Transform::Flipped90 | Transform::Flipped270
    );
    let (out_width, out_height) = if turned {
        (height, width)
    } else {
        (width, height)
    };
    (out.into_flattened(), out_width, out_height)
}

/// The frame's `width * height` pixels, four bytes each.
fn pixels(source: &[u8], width: usize, height: usize) -> &[[u8; 4]] {
    &source.as_chunks::<4>().0[..width * height]
}

/// The side length of the square tiles `turn` works through: 64 framebuffer
/// rows of 64 pixels are 16 KB, which fits in the L1 cache alongside the tile
/// being written.
const TILE: usize = 64;

/// A quarter turn, with or without a mirror. The picture is `height` wide and
/// `width` tall, and its row `y` is framebuffer column `y` — or `width - 1 -
/// y` with `FROM_RIGHT` — read from the top down, or from the bottom up with
/// `FROM_BOTTOM`.
fn turn<const FROM_RIGHT: bool, const FROM_BOTTOM: bool>(
    source: &[[u8; 4]],
    width: usize,
    height: usize,
) -> Vec<[u8; 4]> {
    let (out_width, out_height) = (height, width);
    let mut out = vec![[0; 4]; out_width * out_height];
    for top in (0..out_height).step_by(TILE) {
        for left in (0..out_width).step_by(TILE) {
            let right = (left + TILE).min(out_width);
            for y in top..(top + TILE).min(out_height) {
                let column = if FROM_RIGHT { width - 1 - y } else { y };
                let row = &mut out[y * out_width + left..y * out_width + right];
                for (x, pixel) in (left..right).zip(row) {
                    let line = if FROM_BOTTOM { height - 1 - x } else { x };
                    *pixel = source[line * width + column];
                }
            }
        }
    }
    out
}

/// A half turn or a mirror: the shape stays, and every picture row is a whole
/// framebuffer row, taken from the bottom up with `bottom_up` and read right
/// to left with `reversed`.
fn mirror(
    source: &[[u8; 4]],
    width: usize,
    height: usize,
    reversed: bool,
    bottom_up: bool,
) -> Vec<[u8; 4]> {
    let mut out = Vec::with_capacity(width * height);
    for y in 0..height {
        let line = if bottom_up { height - 1 - y } else { y };
        let row = &source[line * width..(line + 1) * width];
        if reversed {
            out.extend(row.iter().rev());
        } else {
            out.extend_from_slice(row);
        }
    }
    out
}

/// `Y_INVERT` says the compositor copied the framebuffer bottom-up, and
/// undoing that is the same as swapping the output's transform for its mirror
/// image: flipping the source of `orient` vertically and then applying `t`
/// lands on the pixel this `t'` picks out of the original source, for all
/// eight transforms. Nothing is moved, only the mapping changes, which also
/// keeps a flag that no compositor is known to send from costing a pass over
/// every frame.
fn invert(transform: Transform) -> Transform {
    match transform {
        Transform::Normal => Transform::Flipped180,
        Transform::Flipped180 => Transform::Normal,
        Transform::_90 => Transform::Flipped90,
        Transform::Flipped90 => Transform::_90,
        Transform::_180 => Transform::Flipped,
        Transform::Flipped => Transform::_180,
        Transform::_270 => Transform::Flipped270,
        Transform::Flipped270 => Transform::_270,
        // Whatever a later revision adds: left alone rather than guessed at.
        other => other,
    }
}

/// The plain reading of `Y_INVERT`, row by row. It is not used to draw
/// anything; it is what `invert` is held against in the tests.
#[cfg(test)]
fn invert_rows(pixels: &mut [u8], width: usize, height: usize) {
    let row = width * 4;
    for y in 0..height / 2 {
        let (top, bottom) = (y * row, (height - 1 - y) * row);
        for x in 0..row {
            pixels.swap(top + x, bottom + x);
        }
    }
}

fn millis(timeout: Duration) -> i32 {
    timeout.as_millis().min(i32::MAX as u128) as i32
}

/// The output the caller asked for, by the name the compositor gives it (the
/// same `DP-1` the display topology uses). A compositor too old to name its
/// outputs is matched by size, and a single-output desktop needs no match.
fn pick(outputs: &[Output], screen: &Screen) -> Result<usize> {
    if let Some(index) = outputs
        .iter()
        .position(|output| output.name.as_deref() == Some(screen.device_name.as_str()))
    {
        return Ok(index);
    }
    if outputs.len() == 1 {
        return Ok(0);
    }
    if let Some(index) = outputs
        .iter()
        .position(|output| output.name.is_none() && output.size() == (screen.width, screen.height))
    {
        return Ok(index);
    }
    bail!(
        "合成器没有提供名为 {} 的输出（可用：{}）",
        screen.device_name,
        outputs
            .iter()
            .map(|output| output.name.as_deref().unwrap_or("未命名"))
            .collect::<Vec<_>>()
            .join("、")
    )
}

/// Whether this compositor serves the protocol at all. One connection and one
/// registry round trip: no frame is asked for, so nobody is asked for
/// anything. The startup probe uses this to tell a compositor that will copy
/// from one that will fall through to the portal.
pub(crate) fn available() -> bool {
    let Ok(connection) = Connection::connect_to_env() else {
        return false;
    };
    let mut queue = connection.new_event_queue::<State>();
    let handle = queue.handle();
    connection.display().get_registry(&handle, ());
    let mut state = State {
        connection: Some(connection),
        ..Default::default()
    };
    queue.roundtrip(&mut state).is_ok() && state.manager.is_some()
}

impl Dispatch<wl_registry::WlRegistry, ()> for State {
    fn event(
        state: &mut Self,
        registry: &wl_registry::WlRegistry,
        event: wl_registry::Event,
        _: &(),
        _: &Connection,
        handle: &QueueHandle<Self>,
    ) {
        if let wl_registry::Event::Global {
            name,
            interface,
            version,
        } = event
        {
            match interface.as_str() {
                "zwlr_screencopy_manager_v1" => {
                    if version < 3 {
                        // The copy is asked for from `buffer_done`, which
                        // arrived at 3: an older compositor would never
                        // deliver a frame, and the caller would sit on a black
                        // picture with nothing to report. Every compositor
                        // that serves the protocol today is at 3 or above, so
                        // this is a guard, not a fix.
                        state.error = Some(format!(
                            "合成器的 zwlr_screencopy_manager_v1 只有版本 {version}，缺少 buffer_done，无法采集"
                        ));
                        return;
                    }
                    state.manager = Some(
                        registry.bind::<zwlr_screencopy_manager_v1::ZwlrScreencopyManagerV1, _, _>(
                            name,
                            version.min(3),
                            handle,
                            (),
                        ),
                    );
                }
                "wl_shm" => {
                    state.shm = Some(registry.bind::<wl_shm::WlShm, _, _>(
                        name,
                        version.min(1),
                        handle,
                        (),
                    ));
                }
                "zwp_linux_dmabuf_v1" => {
                    // Version 3 is the last one that announces formats and
                    // modifiers as events; from 4 the compositor sends a
                    // feedback object instead, which this backend does not
                    // read. Binding 3 keeps the list a buffer needs.
                    if version >= 3 {
                        state.dmabuf_version = 3;
                        state.dmabuf = Some(
                            registry.bind::<zwp_linux_dmabuf_v1::ZwpLinuxDmabufV1, _, _>(
                                name,
                                state.dmabuf_version,
                                handle,
                                (),
                            ),
                        );
                    }
                }
                "wl_output" => state.outputs.push(Output {
                    proxy: registry.bind::<wl_output::WlOutput, _, _>(
                        name,
                        version.min(OUTPUT_VERSION),
                        handle,
                        (),
                    ),
                    name: None,
                    mode: (0, 0),
                    transform: Transform::Normal,
                }),
                _ => {}
            }
        }
    }
}

impl Dispatch<zwp_linux_dmabuf_v1::ZwpLinuxDmabufV1, ()> for State {
    fn event(
        state: &mut Self,
        _: &zwp_linux_dmabuf_v1::ZwpLinuxDmabufV1,
        event: zwp_linux_dmabuf_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        // Only the modifier event matters: it is what says this format can be
        // imported with this layout, and GBM has to be asked for exactly that.
        match event {
            zwp_linux_dmabuf_v1::Event::Format { .. } => state.dmabuf_formats_seen += 1,
            zwp_linux_dmabuf_v1::Event::Modifier {
                format,
                modifier_hi,
                modifier_lo,
            } => {
                state.dmabuf_formats_seen += 1;
                let modifier = (u64::from(modifier_hi) << 32) | u64::from(modifier_lo);
                state.dmabuf_formats.push((format, modifier));
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_output::WlOutput, ()> for State {
    fn event(
        state: &mut Self,
        output: &wl_output::WlOutput,
        event: wl_output::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        let Some(entry) = state
            .outputs
            .iter_mut()
            .find(|entry| entry.proxy == *output)
        else {
            return;
        };
        match event {
            wl_output::Event::Name { name } => entry.name = Some(name),
            // The transform lives here and nowhere else: no later protocol
            // revision reports it another way.
            wl_output::Event::Geometry { transform, .. } => {
                if let Ok(transform) = transform.into_result() {
                    entry.transform = transform;
                }
            }
            wl_output::Event::Mode {
                width,
                height,
                flags,
                ..
            } if flags
                .into_result()
                .is_ok_and(|flags| flags.contains(wl_output::Mode::Current)) =>
            {
                entry.mode = (width.max(0) as u32, height.max(0) as u32)
            }
            _ => {}
        }
    }
}

impl Dispatch<zwlr_screencopy_frame_v1::ZwlrScreencopyFrameV1, ()> for State {
    fn event(
        state: &mut Self,
        _: &zwlr_screencopy_frame_v1::ZwlrScreencopyFrameV1,
        event: zwlr_screencopy_frame_v1::Event,
        _: &(),
        _: &Connection,
        handle: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_screencopy_frame_v1::Event::Buffer {
                format,
                width,
                height,
                stride,
            } => {
                let Ok(format) = format.into_result() else {
                    state.error = Some("合成器给出了未知的共享内存像素格式".into());
                    return;
                };
                state.wanted = Some((format as u32, width, height, stride));
            }
            zwlr_screencopy_frame_v1::Event::BufferDone => {
                if let Some(error) = state.copy(handle).err() {
                    state.error = Some(format!("{error:#}"));
                }
            }
            zwlr_screencopy_frame_v1::Event::Flags { flags } => {
                state.y_invert = flags
                    .into_result()
                    .is_ok_and(|flags| flags.contains(zwlr_screencopy_frame_v1::Flags::YInvert));
            }
            zwlr_screencopy_frame_v1::Event::LinuxDmabuf {
                format,
                width,
                height,
            } => state.offered = Some((format, width, height)),
            zwlr_screencopy_frame_v1::Event::Ready { .. } => {
                if let Some(copied) = state.copied_at.take() {
                    state.phases.1 = copied.elapsed();
                }
                state.ready = true;
            }
            zwlr_screencopy_frame_v1::Event::Failed => state.failed = true,
            // A dma-buf offer is not taken up: see the module comment.
            _ => {}
        }
    }
}

wayland_client::delegate_noop!(State: ignore wl_shm_pool::WlShmPool);
wayland_client::delegate_noop!(State: ignore wl_buffer::WlBuffer);
wayland_client::delegate_noop!(State: ignore wl_shm::WlShm);
wayland_client::delegate_noop!(State: ignore zwlr_screencopy_manager_v1::ZwlrScreencopyManagerV1);
wayland_client::delegate_noop!(State: ignore zwp_linux_buffer_params_v1::ZwpLinuxBufferParamsV1);

impl State {
    /// Points the compositor at the shared buffer for this frame, allocating it
    /// on the first frame and whenever the output's shape changes.
    fn copy(&mut self, handle: &QueueHandle<Self>) -> Result<()> {
        self.dmabuf_frame = false;
        if self.gpu_frames
            && !self.dmabuf_failed
            && let Some((format, width, height)) = self.offered
        {
            match self.copy_dmabuf(format, width, height, handle) {
                Ok(()) => {
                    self.dmabuf_frame = true;
                    return Ok(());
                }
                Err(error) => {
                    // One failure is enough: the rest of this grab keeps the
                    // shared-memory path instead of retrying every frame.
                    tracing::debug!(%error, "DMA-BUF 采集不可用，改用共享内存");
                    self.dmabuf_failed = true;
                    self.dma = None;
                }
            }
        }
        let (format, width, height, stride) = self.wanted.context("帧没有说明缓冲格式")?;
        ensure!(width > 0 && height > 0 && stride > 0, "帧的缓冲尺寸无效");
        let stale = self
            .shm_buffer
            .as_ref()
            .is_none_or(|shm| !shm.matches(format, width, height, stride));
        if stale {
            let shm = self.shm.as_ref().context("采集已关闭")?;
            self.shm_buffer = Some(allocate(shm, format, width, height, stride, handle)?);
        }
        let shm = self.shm_buffer.as_ref().context("没有共享缓冲")?;
        if let Some(requested) = self.requested_at.take() {
            self.phases.0 = requested.elapsed();
        }
        self.copied_at = Some(Instant::now());
        if let Some(frame) = &self.frame {
            frame.copy_with_damage(&shm.buffer);
        }
        Ok(())
    }
}

/// Whether the compositor's pixels arrive with red and blue the other way
/// round, for the formats this backend takes. `argb8888` and `xrgb8888` store
/// B,G,R,X — what the encoder wants. `abgr8888` and `xbgr8888` store R,G,B,X
/// and have to be swapped. Everything else is refused: a 10-bit format read as
/// 8-bit is noise, and a YUV one is not even the right shape.
fn swapped_channels(format: u32) -> Result<bool> {
    use wayland_client::protocol::wl_shm::Format;
    Ok(match Format::try_from(format) {
        Ok(Format::Argb8888) | Ok(Format::Xrgb8888) => false,
        Ok(Format::Abgr8888) | Ok(Format::Xbgr8888) => true,
        _ => bail!("不支持的共享内存像素格式 {format:#x}（只支持 32 位 ARGB/XRGB/ABGR/XBGR）"),
    })
}

/// The shared memory the compositor writes into: a memfd, mapped here and
/// exported as a pool with a single buffer over the whole of it.
impl State {
    /// Points the compositor at a DMA-BUF buffer for this frame, allocating one
    /// for the compositor's advertised format and modifier on the first frame
    /// and whenever the output's shape changes.
    fn copy_dmabuf(
        &mut self,
        format: u32,
        width: u32,
        height: u32,
        handle: &QueueHandle<Self>,
    ) -> Result<()> {
        ensure!(width > 0 && height > 0, "帧的 DMA-BUF 尺寸无效");
        // The GPU conversion does not undo a transform, and the shared-memory
        // path already handles one, so a rotated output keeps that path.
        ensure!(
            self.transform.unwrap_or(Transform::Normal) == Transform::Normal && !self.y_invert,
            "旋转或翻转的输出不走 DMA-BUF"
        );
        let dmabuf = self
            .dmabuf
            .as_ref()
            .context("合成器没有提供 linux-dmabuf")?;
        let stale = self.dma.as_ref().is_none_or(|(_, buffer, _)| {
            buffer.format() != format || buffer.width() != width || buffer.height() != height
        });
        if stale {
            let modifiers: Vec<u64> = self
                .dmabuf_formats
                .iter()
                .filter(|(advertised, _)| *advertised == format)
                .map(|(_, modifier)| *modifier)
                .collect();
            ensure!(!modifiers.is_empty(), "合成器没有公布该格式的 modifier");
            // No CUDA device to match here yet: the DMA-BUF buffer is
            // converted through VA-API, so any render node will do.
            let device = gbm::Device::open(None)?;
            let buffer = device.allocate(width, height, format, &modifiers)?;
            let params = dmabuf.create_params(handle, ());
            // The protocol carries the modifier as two 32-bit halves.
            params.add(
                buffer.fd(),
                0,
                buffer.offset(),
                buffer.stride(),
                u32::try_from(buffer.modifier() >> 32).unwrap_or(0),
                buffer.modifier() as u32,
            );
            let wl = params.create_immed(
                i32::try_from(width)?,
                i32::try_from(height)?,
                format,
                zwp_linux_buffer_params_v1::Flags::empty(),
                handle,
                (),
            );
            params.destroy();
            self.dma = Some((device, buffer, wl));
        }
        let (_, _, wl) = self.dma.as_ref().context("没有 DMA-BUF 缓冲")?;
        if let Some(frame) = &self.frame {
            frame.copy(wl);
        }
        Ok(())
    }

    /// The DMA-BUF frame converted on the GPU into the encoder's NV12 surface.
    fn dmabuf_picture(&self) -> Result<Picture> {
        let (_, buffer, _) = self.dma.as_ref().context("没有 DMA-BUF 缓冲")?;
        let (width, height) = (buffer.width(), buffer.height());
        let target = match self.target {
            Some((quality, maximum)) => {
                let size = crate::media::geometry::output_size(width, height, quality);
                crate::media::geometry::fit_size(size.0, size.1, maximum)
            }
            None => (width, height),
        };
        let rgb = super::super::gpu_convert::RgbDma {
            fd: buffer.raw_fd(),
            width,
            height,
            pitch: buffer.stride(),
            offset: buffer.offset(),
            fourcc: buffer.format(),
            modifier: buffer.modifier(),
        };
        let frame = CONVERTER.with(|cell| -> Result<_> {
            let entry = cell.get_or_init(|| {
                super::super::cuda::initialize_before_va();
                let display = cros_libva::Display::open()
                    .ok_or_else(|| "找不到 VA-API render node".to_string())?;
                super::super::gpu_convert::Converter::new(display)
                    .map_err(|error| format!("{error:#}"))
            });
            let converter = entry.as_ref().map_err(|error| anyhow::anyhow!("{error}"))?;
            converter.convert(&rgb, (0, 0, width, height), target)
        })?;
        Ok(Picture {
            width: target.0,
            height: target.1,
            pixels: Vec::new(),
            nv12: Some(frame),
        })
    }
}

thread_local! {
    static CONVERTER: std::cell::OnceCell<
        Result<super::super::gpu_convert::Converter, String>,
    > = const { std::cell::OnceCell::new() };
}

fn allocate(
    shm: &wl_shm::WlShm,
    format: u32,
    width: u32,
    height: u32,
    stride: u32,
    handle: &QueueHandle<State>,
) -> Result<Shm> {
    use wayland_client::protocol::wl_shm::Format;
    let format = Format::try_from(format)
        .map_err(|()| anyhow::anyhow!("未知的共享内存像素格式 {format:#x}"))?;
    let swap = swapped_channels(format as u32)?;
    // A row of this buffer is four bytes per pixel wide, and a compositor that
    // says otherwise is describing something this backend cannot read: every
    // compositor in use writes `4 * width`, but `picture` indexes rows by that
    // width, so it is checked rather than trusted.
    ensure!(
        u64::from(stride) >= u64::from(width) * 4,
        "合成器给出的行距 {stride} 小于一行的宽度 {}",
        u64::from(width) * 4
    );
    let length = stride as usize * height as usize;
    let name = c"openuuyc-screencopy";
    // Safe because the name is a valid C string and the flags are plain bits.
    let fd = unsafe { libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC) };
    if fd < 0 {
        bail!("无法创建共享内存：{}", std::io::Error::last_os_error());
    }
    // Safe because `memfd_create` returned a fresh descriptor on success.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    // Safe because `fd` is a live descriptor and the length was just computed.
    if unsafe { libc::ftruncate(fd.as_raw_fd(), length as libc::off_t) } < 0 {
        bail!("无法调整共享内存大小：{}", std::io::Error::last_os_error());
    }
    let pool = shm.create_pool(fd.as_fd(), length as i32, handle, ());
    let buffer = pool.create_buffer(
        0,
        width as i32,
        height as i32,
        stride as i32,
        format,
        handle,
        (),
    );
    // Safe because the mapping covers the whole of a descriptor this process
    // owns, and it is unmapped again in `Shm::drop`.
    let map = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            length,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_SHARED,
            fd.as_raw_fd(),
            0,
        )
    };
    if map == libc::MAP_FAILED {
        bail!("无法映射共享内存：{}", std::io::Error::last_os_error());
    }
    Ok(Shm {
        _fd: fd,
        pool,
        map: map.cast(),
        length,
        buffer,
        format: format as u32,
        width,
        height,
        stride,
        swap,
    })
}

#[cfg(test)]
mod tests {
    use super::orient;
    use wayland_client::protocol::wl_output::Transform;

    /// A `width`x`height` BGRA picture whose pixels count up from 1, one value
    /// per pixel repeated across its four bytes.
    fn numbered(width: u32, height: u32) -> Vec<u8> {
        (1..=(width * height))
            .flat_map(|value| [value as u8; 4])
            .collect()
    }

    /// The value of each pixel, in order.
    fn values(pixels: &[u8]) -> Vec<u8> {
        pixels.chunks_exact(4).map(|pixel| pixel[0]).collect()
    }

    const ALL: [Transform; 8] = [
        Transform::Normal,
        Transform::_90,
        Transform::_180,
        Transform::_270,
        Transform::Flipped,
        Transform::Flipped90,
        Transform::Flipped180,
        Transform::Flipped270,
    ];

    /// `orient` written the plain way, one pixel at a time straight from
    /// `wl_output`'s mapping: what the tiled version is held against.
    fn orient_reference(
        source: &[u8],
        width: u32,
        height: u32,
        transform: Transform,
    ) -> (Vec<u8>, u32, u32) {
        let turned = matches!(
            transform,
            Transform::_90 | Transform::_270 | Transform::Flipped90 | Transform::Flipped270
        );
        let (out_width, out_height) = if turned {
            (height, width)
        } else {
            (width, height)
        };
        let (last_x, last_y) = (width as i32 - 1, height as i32 - 1);
        let source_at = |x: i32, y: i32| match transform {
            Transform::_90 => (y, last_y - x),
            Transform::_180 => (last_x - x, last_y - y),
            Transform::_270 => (last_x - y, x),
            Transform::Flipped => (last_x - x, y),
            Transform::Flipped90 => (y, x),
            Transform::Flipped180 => (x, last_y - y),
            Transform::Flipped270 => (last_x - y, last_y - x),
            _ => (x, y),
        };
        let mut out = vec![0u8; out_width as usize * out_height as usize * 4];
        for y in 0..out_height as i32 {
            for x in 0..out_width as i32 {
                let (from_x, from_y) = source_at(x, y);
                let from = (from_y as usize * width as usize + from_x as usize) * 4;
                let to = (y as usize * out_width as usize + x as usize) * 4;
                out[to..to + 4].copy_from_slice(&source[from..from + 4]);
            }
        }
        (out, out_width, out_height)
    }

    #[test]
    fn tiles_land_where_the_plain_mapping_puts_them() {
        use super::{TILE, invert, invert_rows};
        // Neither side a multiple of the tile, and more than one tile each
        // way, so full tiles and both kinds of edge tile are all crossed.
        let (width, height) = (2 * TILE as u32 + 3, TILE as u32 + 13);
        // Every pixel distinct in all four bytes, so a pixel that lands in the
        // wrong place, or arrives with its bytes reordered, shows.
        let source: Vec<u8> = (0..width * height)
            .flat_map(|index| (index.wrapping_mul(0x0101_0101) ^ 0x8040_2010).to_le_bytes())
            .collect();
        for transform in ALL {
            assert_eq!(
                orient(source.clone(), width, height, transform),
                orient_reference(&source, width, height, transform),
                "{transform:?}"
            );
            // A `Y_INVERT` frame through `invert`, against the frame flipped
            // by hand and given the plain mapping.
            let mut memory = source.clone();
            invert_rows(&mut memory, width as usize, height as usize);
            assert_eq!(
                orient(source.clone(), width, height, invert(transform)),
                orient_reference(&memory, width, height, transform),
                "inverted {transform:?}"
            );
        }
    }

    #[test]
    fn normal_leaves_the_rows_alone() {
        let (pixels, width, height) = orient(numbered(2, 3), 2, 3, Transform::Normal);
        assert_eq!((width, height), (2, 3));
        assert_eq!(values(&pixels), vec![1, 2, 3, 4, 5, 6]);
    }

    #[test]
    fn turning_swaps_the_shape_and_keeps_every_pixel() {
        let source = numbered(4, 3);
        let mut seen = Vec::new();
        for transform in [
            Transform::_90,
            Transform::_180,
            Transform::_270,
            Transform::Flipped,
            Transform::Flipped90,
            Transform::Flipped180,
            Transform::Flipped270,
        ] {
            let (pixels, width, height) = orient(source.clone(), 4, 3, transform);
            let turned = matches!(
                transform,
                Transform::_90 | Transform::_270 | Transform::Flipped90 | Transform::Flipped270
            );
            assert_eq!(
                (width, height),
                if turned { (3, 4) } else { (4, 3) },
                "{transform:?} size"
            );
            let mut sorted = values(&pixels);
            sorted.sort_unstable();
            assert_eq!(
                sorted,
                (1..=12).collect::<Vec<u8>>(),
                "{transform:?} lost or duplicated a pixel"
            );
            seen.push(values(&pixels));
        }
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), 7, "a transform repeats another one");
    }

    #[test]
    fn a_quarter_turn_is_clockwise_and_270_undoes_it() {
        let source = numbered(2, 3);
        let (turned, width, height) = orient(source.clone(), 2, 3, Transform::_90);
        assert_eq!((width, height), (3, 2));
        assert_eq!(values(&turned), vec![5, 3, 1, 6, 4, 2]);
        let (back, width, height) = orient(turned, 3, 2, Transform::_270);
        assert_eq!((width, height), (2, 3));
        assert_eq!(back, source);
    }

    #[test]
    fn a_flipped_quarter_turn_is_the_plain_one_mirrored() {
        let source = numbered(2, 3);
        let (plain, _, _) = orient(source.clone(), 2, 3, Transform::_90);
        let (flipped, _, _) = orient(source.clone(), 2, 3, Transform::Flipped90);
        assert_eq!(values(&plain), vec![5, 3, 1, 6, 4, 2]);
        assert_eq!(values(&flipped), vec![1, 3, 5, 2, 4, 6]);
        let (plain, _, _) = orient(source.clone(), 2, 3, Transform::_270);
        let (flipped, _, _) = orient(source, 2, 3, Transform::Flipped270);
        assert_eq!(values(&plain), vec![2, 4, 6, 1, 3, 5]);
        assert_eq!(values(&flipped), vec![6, 4, 2, 5, 3, 1]);
    }

    #[test]
    fn only_the_two_byte_orders_of_32_bit_are_taken() {
        use super::swapped_channels;
        use wayland_client::protocol::wl_shm::Format;
        // What the encoder reads as BGRA already.
        assert!(!swapped_channels(Format::Argb8888 as u32).unwrap());
        assert!(!swapped_channels(Format::Xrgb8888 as u32).unwrap());
        // Red and blue the other way round, which `picture` swaps.
        assert!(swapped_channels(Format::Abgr8888 as u32).unwrap());
        assert!(swapped_channels(Format::Xbgr8888 as u32).unwrap());
        // 10-bit, YUV and anything unknown is refused rather than misread.
        assert!(swapped_channels(Format::Xbgr2101010 as u32).is_err());
        assert!(swapped_channels(Format::Rgb565 as u32).is_err());
        assert!(swapped_channels(Format::Nv12 as u32).is_err());
        assert!(swapped_channels(0xdead_beef).is_err());
    }

    #[test]
    fn flipping_mirrors_without_turning() {
        let (pixels, _, _) = orient(numbered(3, 2), 3, 2, Transform::Flipped);
        assert_eq!(values(&pixels), vec![3, 2, 1, 6, 5, 4]);
        let (pixels, _, _) = orient(numbered(3, 2), 3, 2, Transform::Flipped180);
        assert_eq!(values(&pixels), vec![4, 5, 6, 1, 2, 3]);
    }

    #[test]
    fn an_inverted_frame_is_a_flipped_transform() {
        use super::{invert, invert_rows};
        let source = numbered(4, 3);
        for transform in [
            Transform::Normal,
            Transform::_90,
            Transform::_180,
            Transform::_270,
            Transform::Flipped,
            Transform::Flipped90,
            Transform::Flipped180,
            Transform::Flipped270,
        ] {
            // The same frame, read two ways: flip the framebuffer the
            // compositor laid out bottom-up and keep the transform, or leave
            // the pixels alone and move the transform to its mirror.
            let mut memory = source.clone();
            invert_rows(&mut memory, 4, 3);
            let (flipped, width, height) = orient(memory, 4, 3, transform);
            let (mapped, mapped_width, mapped_height) =
                orient(source.clone(), 4, 3, invert(transform));
            assert_eq!(
                (width, height),
                (mapped_width, mapped_height),
                "{transform:?}"
            );
            assert_eq!(flipped, mapped, "{transform:?}");
            assert_eq!(invert(invert(transform)), transform, "{transform:?}");
        }
    }
}
