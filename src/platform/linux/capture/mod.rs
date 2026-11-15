//! Screen capture for the host role, with backends tried in order of
//! preference the way Sunshine picks its capture method.
//!
//! - NVIDIA NvFBC into CUDA (`nvfbc`): Xorg on NVIDIA, only while NVENC works,
//!   so frames go from the display to the encoder without leaving the GPU.
//! - X11 MIT-SHM (`x11`): Xorg sessions; no setup, no consent prompt.
//! - wlroots screencopy (`wlr`): Wayland sessions on wlroots, niri, Sway and
//!   their relatives; frames come through the compositor itself, and nobody is
//!   asked for consent.
//! - XDG ScreenCast portal + PipeWire (`portal`): Wayland sessions, and any
//!   desktop whose portal offers it; asks the local user once.
//!
//! Sunshine puts KMS first; on this driver stack it finds no scanout buffer
//! under Xorg (the NVIDIA X driver bypasses KMS), so it is not in the list
//! yet. `OPENUUYC_CAPTURE` (for example `portal` or `nvfbc,x11`) overrides the
//! order.
//!
//! Frames are BGRA, in system memory or, from NvFBC, in CUDA device memory.
mod gbm;
mod nvfbc;
mod pipewire;
mod portal;
mod wlr_screencopy;
mod wlr_window;
mod x11;
mod x11_window;

use anyhow::{Context, Result, bail, ensure};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub(crate) use crate::media::capture::{Screen, SourceGone};

/// 窗口级源（waypipe 风格）的 identity 前缀：一个应用窗口就是一块"屏幕"。
/// X11 走 XComposite，Wayland 走 ext_image_copy_capture 的顶层源。
pub(crate) const WINDOW_IDENTITY_PREFIX: &str = "x11-window/";
pub(crate) const WL_WINDOW_PREFIX: &str = "wl-window/";

/// 当前可见的应用窗口，按协议目录的屏幕形态列出。只在实际能免授权抓取的
/// 会话（X11 的 XComposite、Wayland 的 ext_image_copy_capture）返回条目；
/// 其他会话返回空或错误，由调用方决定是否展示。
pub(crate) fn window_screens() -> Result<Vec<Screen>> {
    if wayland_session() {
        wlr_window::screens()
    } else {
        x11_window::screens()
    }
}

/// 输入注入按 id 找一个窗口源时，它现在在哪，以及那个矩形在哪个坐标空间里。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum WindowPlace {
    /// X11 窗口（XComposite 源）：矩形在 X 像素空间，按屏幕位置注入。
    X11 { rect: (i32, i32, u32, u32) },
    /// Wayland 窗口：矩形在合成器逻辑空间里，和绝对指针解算落点用的是同一个
    /// 空间。`output` 是它所在输出的矩形——窗口露在这块输出之外的部位不在
    /// 屏幕上，往那里投的点击只会落到别处。
    Compositor {
        rect: (i32, i32, u32, u32),
        output: (i32, i32, u32, u32),
    },
}

/// 一个窗口源此刻的位置（left, top, width, height），供输入注入换算用。两种
/// 窗口各走各的注入路径，所以连坐标空间一起给出来。定位不到（窗口不在可见
/// 工作区、或者合成器没有这个接口）返回 None，调用方明确报错，不把点击投到
/// 过期位置。非窗口 ID 也返回 None。
pub(crate) fn window_place(id: i32) -> Option<WindowPlace> {
    if let Some(placement) = wlr_window::placement(id) {
        return Some(WindowPlace::Compositor {
            rect: placement.rect,
            output: placement.output,
        });
    }
    x11_window::live_rect(id).map(|rect| WindowPlace::X11 { rect })
}

/// 让一个当前不在屏幕上的窗口露出来（切到它所在的工作区并滚进视口），这
/// 样输入才有落点。只有 Wayland 窗口能这样做：X11 的窗口本来就在 X 的屏幕
/// 坐标里，位置未知说明它没有映射，叫醒它不是注入该做的事。返回「合成器接
/// 受了这个请求」，之后要重新取位置。
pub(crate) fn reveal_window(id: i32) -> bool {
    wlr_window::reveal(id)
}

/// 输入注入按 id 找不到窗口矩形时，用它区分"窗口源暂时无法定位"与普通屏幕。
pub(crate) fn is_window_target(id: i32) -> bool {
    x11_window::knows(id) || wlr_window::knows(id)
}

/// 窗口源是否是这个 identity（两种前缀都算）。
pub(crate) fn is_window_source(screen: &Screen) -> bool {
    screen.identity.as_deref().is_some_and(|identity| {
        identity.starts_with(WINDOW_IDENTITY_PREFIX) || identity.starts_with(WL_WINDOW_PREFIX)
    })
}

/// An X11 capture has no GPU device. The one value stands for the CPU path
/// that capture and the software encoder share, so the loop's "same device,
/// no transfer" test always holds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Device;

/// Nothing on the CPU path can be removed from under the capture.
pub(crate) fn device_lost(_device: &Device) -> bool {
    false
}

/// Linux captures on the CPU, so there is no adapter for a second device to be
/// opened on. The host only asks for one when a hardware encoder sits on a
/// different adapter than the capture, which cannot happen here.
pub(crate) fn create_device(_adapter: u64) -> Result<(Device, ())> {
    Ok((Device, ()))
}

/// GPU adapters a hardware encoder could be pinned to. None are listed: NVENC
/// runs on the GPU driving the display and VA-API on the driver's device, so
/// there is nothing to choose between and the preference stays automatic.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub(crate) struct EncodingAdapter {
    #[serde(default)]
    pub id: Option<crate::media::selection::GpuId>,
    pub luid: u64,
    pub vendor: u32,
    pub name: String,
}

pub(crate) fn encoding_adapters() -> Result<Vec<EncodingAdapter>> {
    Ok(Vec::new())
}

/// Whether the desktop session is locked. X11 has no portable answer: a
/// screen locker is just another client drawing over the root window, and the
/// capture sees exactly what it draws.
pub(crate) fn session_locked() -> Option<bool> {
    None
}

/// A captured picture's BGRA pixels, wherever the capture left them.
#[derive(Clone)]
pub(crate) enum Image {
    /// Tightly packed, `width * height * 4` bytes.
    Cpu {
        pixels: Arc<Vec<u8>>,
        width: u32,
        height: u32,
    },
    /// A pitched CUDA allocation on the GPU driving the display.
    Cuda(Arc<super::cuda::Buffer>),
    Nv12(Arc<super::gpu_convert::OwnedNv12>),
}
impl Image {
    pub fn size(&self) -> (u32, u32) {
        match self {
            Self::Cpu { width, height, .. } => (*width, *height),
            Self::Cuda(buffer) => (buffer.width, buffer.height),
            Self::Nv12(frame) => frame.size(),
        }
    }
    /// The pixels in system memory, read back from the GPU if need be.
    pub fn pixels(&self) -> Result<std::borrow::Cow<'_, [u8]>> {
        Ok(match self {
            Self::Cpu { pixels, .. } => std::borrow::Cow::Borrowed(pixels.as_slice()),
            Self::Cuda(buffer) => std::borrow::Cow::Owned(buffer.download()?),
            Self::Nv12(frame) => std::borrow::Cow::Owned(frame.pixels()?),
        })
    }
}

/// A backend's picture before it is scaled: tightly packed BGRA rows, or NV12
/// converted on the GPU.
pub(super) struct Picture {
    /// The size of the captured region: the BGRA `pixels` with no `nv12`, the
    /// cropped source with one. The GPU converter may already have scaled
    /// that frame, so its geometry is `nv12.size()`, not this.
    pub width: u32,
    pub height: u32,
    /// BGRA rows of `width * 4` bytes; empty when `nv12` carries the frame.
    pub pixels: Vec<u8>,
    /// The frame converted on the GPU (portal stream only), at the output
    /// geometry the worker had requested through the stream's `set_target`
    /// when the picture arrived (the crop size before any request). A quality
    /// change can make it stale.
    pub nv12: Option<Arc<super::gpu_convert::OwnedNv12>>,
}

/// One captured picture, already scaled to the size the encoder was asked for.
#[derive(Clone)]
pub(crate) struct Frame {
    pub width: u32,
    pub height: u32,
    pub image: Image,
    pub captured: Instant,
    pub is_new: bool,
    pub hdr_metadata: Option<crate::media::video_color::HdrMetadata>,
}

/// The active RandR outputs, in the protocol's terms. The identity is the
/// one the display topology uses, so screens and targets pair up exactly.
pub(crate) fn screens() -> Result<Vec<Screen>> {
    let monitors = match super::display::topology::Topology::query(true).and_then(|topology| {
        let monitors = topology.monitors();
        ensure!(!monitors.is_empty(), "没有检测到 X11 显示器");
        Ok(monitors)
    }) {
        Ok(monitors) => monitors,
        Err(error) if wayland_session() => {
            if let Some(layout) = super::display::compositor_layout_soon() {
                let screens = wayland_screens(&layout)?;
                if !screens.is_empty() {
                    tracing::warn!(error=%format!("{error:#}"),"X11 inventory unavailable; falling back to compositor screen descriptions");
                    return Ok(screens);
                }
            }
            return Err(error).context("X11 和 Wayland 屏幕清单均不可用");
        }
        Err(error) => return Err(error),
    };
    ensure!(!monitors.is_empty(), "没有检测到显示器");
    monitors
        .into_iter()
        .map(|monitor| {
            Ok(Screen {
                id: super::display::source_id(&monitor.identity)?,
                device_name: monitor.name.clone(),
                display_name: monitor.name,
                width: monitor.width,
                height: monitor.height,
                left: monitor.left,
                top: monitor.top,
                primary: monitor.primary,
                fps: monitor.hz.filter(|hz| *hz > 1).unwrap_or(60),
                // X11 has no per-output scale to report.
                dpi_scale: None,
                hdr: false,
                adapter: 0,
                render_adapter: None,
                identity: Some(monitor.identity),
            })
        })
        .collect()
}

// Use real compositor logical rectangles as a reduced-capability fallback,
// never invented physical modes or an arbitrary 1920x1080 desktop.
fn wayland_screens(layout: &crate::platform::CompositorLayout) -> Result<Vec<Screen>> {
    let mut found = Vec::new();
    for output in &layout.outputs {
        let Some(name) = output.name.as_deref().filter(|n| !n.is_empty()) else {
            continue;
        };
        if output.width == 0
            || output.height == 0
            || found.iter().any(|s: &Screen| s.device_name == name)
        {
            continue;
        }
        let identity = super::display::topology::identity(name);
        found.push(Screen {
            id: super::display::source_id(&identity)?,
            device_name: name.into(),
            display_name: format!("{name} · Wayland"),
            width: output.width,
            height: output.height,
            left: output.left,
            top: output.top,
            primary: found.is_empty(),
            // The compositor layout does not expose mode refresh. Do not turn
            // "unknown" into a false 60 Hz capture ceiling; negotiated source
            // damage and encoder capability still bound actual output.
            fps: 165,
            dpi_scale: None,
            hdr: false,
            adapter: 0,
            render_adapter: None,
            identity: Some(identity),
        });
    }
    Ok(found)
}

/// Whether this is a Wayland session, where X11 sees only XWayland clients.
pub(crate) fn wayland_session() -> bool {
    std::env::var_os("WAYLAND_DISPLAY").is_some()
        || std::env::var("XDG_SESSION_TYPE").is_ok_and(|kind| kind == "wayland")
}

/// The same physical output as `selected`, as it is now.
pub(crate) fn refresh(selected: &Screen) -> Result<Screen> {
    if let Some(identity) = selected.identity.as_deref() {
        // 窗口不在输出拓扑里：直接按它的窗口 ID 查实时几何与标题。
        if identity.starts_with(WL_WINDOW_PREFIX) {
            return wlr_window::refresh(selected);
        }
        if identity.starts_with(WINDOW_IDENTITY_PREFIX) {
            return x11_window::refresh(selected);
        }
    }
    let mut found = screens()?
        .into_iter()
        .find(|screen| screen.identity == selected.identity)
        .ok_or(SourceGone)?;
    found.id = selected.id;
    Ok(found)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    NvFbc,
    X11,
    Wlr,
    Portal,
    Window,
}
impl Kind {
    fn label(self) -> &'static str {
        match self {
            Self::NvFbc => "NvFBC",
            Self::X11 => "X11",
            Self::Wlr => "wlr-screencopy",
            Self::Portal => "XDG 门户",
            Self::Window => "X11 窗口",
        }
    }
}

/// The capture methods to try, best first.
fn candidates() -> Result<Vec<Kind>> {
    if let Ok(order) = std::env::var("OPENUUYC_CAPTURE") {
        let kinds = order
            .split(',')
            .map(|name| match name.trim() {
                "nvfbc" => Ok(Kind::NvFbc),
                "x11" => Ok(Kind::X11),
                "wlr" => Ok(Kind::Wlr),
                "portal" => Ok(Kind::Portal),
                other => bail!("OPENUUYC_CAPTURE 中有未知的采集方式：{other}"),
            })
            .collect::<Result<Vec<_>>>()?;
        ensure!(!kinds.is_empty(), "OPENUUYC_CAPTURE 为空");
        return Ok(kinds);
    }
    Ok(if wayland_session() {
        // The compositor's own protocol first: it is the only one that copies
        // without asking, and the portal stays as the fallback for compositors
        // that do not serve it.
        vec![Kind::Wlr, Kind::Portal]
    } else {
        vec![Kind::NvFbc, Kind::X11, Kind::Portal]
    })
}

/// The candidates that can really open here, in order. A `Wlr` entry is
/// dropped when the compositor does not serve the protocol, so whatever is
/// behind it — on Mutter and KWin, the portal — is seen for what it is.
fn usable(kinds: &[Kind], wlr_available: bool) -> Vec<Kind> {
    kinds
        .iter()
        .copied()
        .filter(|kind| *kind != Kind::Wlr || wlr_available)
        .collect()
}

/// Whether opening capture now would ask the local user to share the
/// screen: the portal comes first and has no remembered grant yet. A `Wlr`
/// that cannot open leaves the portal first, so the question has to be asked
/// of the list the session would really use.
pub(crate) fn needs_consent() -> bool {
    candidates().is_ok_and(|kinds| {
        // Only a `Wlr` head can hide the portal behind it, and the probe costs
        // a connection, so it is skipped for every other order.
        let wlr = kinds.first() == Some(&Kind::Wlr) && wlr_screencopy::available();
        consent_needed(&usable(&kinds, wlr), portal::remembered())
    })
}

fn consent_needed(kinds: &[Kind], remembered: bool) -> bool {
    kinds.first() == Some(&Kind::Portal) && !remembered
}

enum Backend {
    NvFbc(nvfbc::Grab),
    X11(x11::Grab),
    Wlr(wlr_screencopy::Grab),
    Portal(portal::Grab),
    Window(WindowGrab),
}

/// 窗口采集的统一接口：X11 是拉取式（每次抓一张再比较），Wayland 是推送式
/// （合成器有 damage 才给帧）。`next` 对两种都返回"有新画面时的缓冲尺寸"，
/// 超时无新画面返回 None。
enum WindowGrab {
    X11(x11_window::Grab),
    Wl(wlr_window::Grab),
}
impl WindowGrab {
    fn open(screen: &Screen, cursor: bool) -> Result<Self> {
        if screen
            .identity
            .as_deref()
            .is_some_and(|identity| identity.starts_with(WL_WINDOW_PREFIX))
        {
            Ok(Self::Wl(wlr_window::Grab::open(screen, cursor)?))
        } else {
            Ok(Self::X11(x11_window::Grab::open(screen)?))
        }
    }
    fn name(&self) -> &'static str {
        match self {
            Self::X11(grab) => grab.name(),
            Self::Wl(grab) => grab.name(),
        }
    }
    fn cursor(&self) -> bool {
        match self {
            Self::X11(_) => false,
            Self::Wl(grab) => grab.cursor(),
        }
    }
    fn pixels(&self) -> &[u8] {
        match self {
            Self::X11(grab) => grab.pixels(),
            Self::Wl(grab) => grab.pixels(),
        }
    }
    /// 一次取帧：有新画面返回 Some(缓冲尺寸)，超时无新画面返回 None。
    fn next(&mut self, cursor: bool, timeout: Duration) -> Result<Option<(u32, u32)>> {
        match self {
            Self::X11(grab) => grab.next(cursor, timeout),
            Self::Wl(grab) => grab.next(cursor, timeout),
        }
    }
}
impl Backend {
    /// `size` is the frame size for backends that scale while capturing.
    fn open(kind: Kind, screen: &Screen, cursor: bool, size: (u32, u32)) -> Result<Self> {
        Ok(match kind {
            Kind::NvFbc => Self::NvFbc(nvfbc::Grab::open(screen, cursor, size)?),
            Kind::X11 => Self::X11(x11::Grab::open(screen)?),
            Kind::Wlr => Self::Wlr(wlr_screencopy::Grab::open(screen, cursor)?),
            Kind::Portal => Self::Portal(portal::Grab::open(screen, cursor)?),
            Kind::Window => Self::Window(WindowGrab::open(screen, cursor)?),
        })
    }
    fn kind(&self) -> Kind {
        match self {
            Self::NvFbc(_) => Kind::NvFbc,
            Self::X11(_) => Kind::X11,
            Self::Wlr(_) => Kind::Wlr,
            Self::Portal(_) => Kind::Portal,
            Self::Window(_) => Kind::Window,
        }
    }
    /// Open the first capture method that works for `screen`.
    fn select(screen: &Screen, cursor: bool, size: (u32, u32)) -> Result<Self> {
        // 窗口源不走整屏候选链：它只有一个后端，成败都一目了然。
        if is_window_source(screen) {
            return Self::open(Kind::Window, screen, cursor, size);
        }
        let mut failures = Vec::new();
        for kind in candidates()? {
            match Self::open(kind, screen, cursor, size) {
                Ok(backend) => {
                    tracing::info!(
                        backend = backend.name(),
                        screen = screen.id,
                        "host capture backend selected"
                    );
                    return Ok(backend);
                }
                Err(error) => {
                    tracing::info!(backend = kind.label(), error = %format!("{error:#}"), "host capture backend unavailable");
                    failures.push(format!("{}：{error:#}", kind.label()));
                }
            }
        }
        bail!("没有可用的屏幕采集方式（{}）", failures.join("；"))
    }
    fn name(&self) -> &'static str {
        match self {
            Self::NvFbc(grab) => grab.name(),
            Self::X11(grab) => grab.name(),
            Self::Wlr(grab) => grab.name(),
            Self::Portal(grab) => grab.name(),
            Self::Window(grab) => grab.name(),
        }
    }
}

/// A capture of one monitor, kept open between frames.
pub(crate) struct Desktop {
    pub device: Device,
    pub screen: Screen,
    pub generation: u64,
    pub available: bool,
    /// The pointer as of the latest `next`, for the cursor channel.
    pub cursor: Option<super::cursor_shape::Snapshot>,
    sampler: super::cursor_shape::Sampler,
    backend: Backend,
    /// The last system-memory picture, which X11 compares against.
    last: Option<Arc<Vec<u8>>>,
    last_size: (u32, u32),
    refreshed: Instant,
    /// Keeps the display on and the machine awake while captured. Declared
    /// last so it outlives the capture resources, as on Windows.
    _power: Option<super::power_request::Request>,
}

impl Desktop {
    pub fn open_selected(selected: &Screen) -> Result<Self> {
        // Unlike Windows, a desktop without the idle-inhibit interface does
        // not stop the capture; the session works, the screen may blank.
        let power = super::power_request::Request::capture()
            .inspect_err(|error| {
                tracing::warn!(error = %format!("{error:#}"),
                    "capture automatic display-off/sleep prevention unavailable")
            })
            .ok();
        let screen = refresh(selected)?;
        let size = (screen.width & !1, screen.height & !1);
        let backend = Backend::select(&screen, false, size)?;
        Ok(Self {
            device: Device,
            screen,
            generation: 0,
            available: true,
            cursor: None,
            sampler: Default::default(),
            backend,
            last: None,
            last_size: (0, 0),
            refreshed: Instant::now(),
            _power: power,
        })
    }

    pub fn backend_name(&self) -> &'static str {
        self.backend.name()
    }

    /// Why no picture has come yet, when the backend can tell: the portal
    /// reports how far its PipeWire stream got. The others capture on request
    /// and have nothing to wait for.
    pub fn waiting(&self) -> Option<String> {
        match &self.backend {
            Backend::Portal(grab) => Some(grab.stage()),
            _ => None,
        }
    }

    /// Neither backend captures HDR.
    pub fn hdr_available(&self) -> bool {
        false
    }

    /// The next picture, or `None` when nothing changed before `timeout` ms.
    /// Lets the capture path keep frames on the GPU.
    ///
    /// Only the wlroots backend has a second form: a DMA-BUF buffer that the
    /// compositor draws into and the GPU converter turns into NV12. It is worth
    /// switching on only when the encoder reads that surface without a
    /// readback; otherwise the shared-memory copy is far cheaper.
    pub(crate) fn set_gpu_frames(&mut self, on: bool) {
        if let Backend::Wlr(grab) = &mut self.backend {
            grab.enable_dmabuf(on);
        }
    }

    pub fn next(
        &mut self,
        timeout: u32,
        quality: i32,
        cursor: bool,
        _hdr: bool,
        maximum: (u32, u32),
    ) -> Result<Option<Frame>> {
        if self.refreshed.elapsed() >= Duration::from_secs(1) {
            let current = refresh(&self.screen)?;
            self.refreshed = Instant::now();
            self.screen.display_name.clone_from(&current.display_name);
            if current != self.screen {
                let size = output(current.width, current.height, quality, maximum);
                let backend = Backend::open(self.backend.kind(), &current, cursor, size)
                    .or_else(|_| Backend::select(&current, cursor, size))?;
                self.backend = backend;
                self.screen = current;
                self.generation = self.generation.wrapping_add(1);
                self.last = None;
            }
        }
        // The portal and wlr-screencopy draw the cursor or leave it out for the
        // whole session; a change of request moves to a session with the other
        // mode.
        if let Backend::Portal(grab) = &self.backend
            && grab.cursor() != cursor
        {
            self.backend = Backend::Portal(portal::Grab::open(&self.screen, cursor)?);
            self.last = None;
        }
        if let Backend::Wlr(grab) = &self.backend
            && grab.cursor() != cursor
        {
            self.backend = Backend::Wlr(wlr_screencopy::Grab::open(&self.screen, cursor)?);
            self.last = None;
        }
        // NvFBC scales and draws the cursor as it captures; both are fixed
        // per session, so a different request opens a new one.
        let wanted = output(self.screen.width, self.screen.height, quality, maximum);
        if let Backend::NvFbc(grab) = &self.backend
            && !grab.matches(cursor, wanted)
        {
            self.backend = Backend::NvFbc(nvfbc::Grab::open(&self.screen, cursor, wanted)?);
        }
        // The portal and DMA-BUF converters derive their output from each
        // picture's own size. Hand them the current request so a controller
        // quality that differs from the source is scaled on the GPU instead of
        // read back to the CPU.
        if let Backend::Portal(grab) = &self.backend {
            grab.set_target(Some((quality, maximum)));
        }
        if let Backend::Wlr(grab) = &mut self.backend {
            grab.set_target(Some((quality, maximum)));
        }
        self.cursor = match self.sampler.sample() {
            Ok(pointer) => Some(pointer),
            Err(error) => {
                tracing::debug!(%error, "host cursor sampling failed");
                None
            }
        };
        let deadline = Instant::now() + Duration::from_millis(u64::from(timeout));
        let result = match &mut self.backend {
            // The driver waits for a new frame and delivers it on the GPU.
            Backend::NvFbc(grab) => {
                return match grab.next(timeout) {
                    Ok(frame) => {
                        self.available = true;
                        Ok(frame.map(|buffer| Frame {
                            width: buffer.width,
                            height: buffer.height,
                            image: Image::Cuda(buffer),
                            captured: Instant::now(),
                            is_new: true,
                            hdr_metadata: None,
                        }))
                    }
                    Err(error) => {
                        self.available = false;
                        Err(error)
                    }
                };
            }
            Backend::X11(grab) => loop {
                if let Err(error) = grab.grab(&self.screen, cursor) {
                    break Err(error);
                }
                let size = output(self.screen.width, self.screen.height, quality, maximum);
                let image = scale(grab.pixels(), (self.screen.width, self.screen.height), size);
                // X11 cannot say whether anything changed; compare.
                if self.last_size != size
                    || self
                        .last
                        .as_deref()
                        .is_none_or(|last| differs(last, &image))
                {
                    break Ok(Some((size, image)));
                }
                let now = Instant::now();
                if now >= deadline {
                    break Ok(None);
                }
                std::thread::sleep((deadline - now).min(Duration::from_millis(8)));
            },
            // 窗口缓冲以它自己的实际尺寸为准：窗口缩放后不用等目录刷新，
            // 下一帧就跟随新比例。X11 拉取、Wayland 推送，统一成"有新画面才返回"。
            Backend::Window(grab) => {
                if grab.cursor() != cursor {
                    self.backend = Backend::Window(WindowGrab::open(&self.screen, cursor)?);
                    self.last = None;
                    return Ok(None);
                }
                let grabbed =
                    match grab.next(cursor, deadline.saturating_duration_since(Instant::now()))? {
                        Some(size) => size,
                        // 超时没有新画面：这一轮就不出帧。
                        None => return Ok(None),
                    };
                let size = output(grabbed.0, grabbed.1, quality, maximum);
                let image = scale(grab.pixels(), grabbed, size);
                Ok(Some((size, image)))
            }
            // The compositor answers a copy request only when it next draws,
            // so a still desktop reports no picture.
            Backend::Wlr(grab) => {
                match grab.next(deadline.saturating_duration_since(Instant::now())) {
                    Ok(Some(picture)) => {
                        // A DMA-BUF frame already went through the GPU
                        // converter, which produced the requested geometry.
                        if let Some(frame) = picture.nv12 {
                            let size = frame.size();
                            self.available = true;
                            self.last_size = size;
                            return Ok(Some(Frame {
                                width: size.0,
                                height: size.1,
                                image: Image::Nv12(frame),
                                captured: Instant::now(),
                                is_new: true,
                                hdr_metadata: None,
                            }));
                        }
                        let size = output(picture.width, picture.height, quality, maximum);
                        let image = if size == (picture.width, picture.height) {
                            picture.pixels
                        } else {
                            scale(&picture.pixels, (picture.width, picture.height), size)
                        };
                        Ok(Some((size, image)))
                    }
                    Ok(None) => Ok(None),
                    Err(error) => Err(error),
                }
            }
            // PipeWire only delivers a picture when the monitor changed.
            Backend::Portal(grab) => {
                match grab.next(deadline.saturating_duration_since(Instant::now())) {
                    Ok(Some(picture)) => {
                        let size = output(picture.width, picture.height, quality, maximum);
                        if let Some(frame) = picture.nv12 {
                            // The converter already produced `size`; a mismatch
                            // would mean a stale target, so keep the GPU frame
                            // only when it matches and fall back otherwise.
                            if size == frame.size() {
                                self.available = true;
                                self.last_size = size;
                                return Ok(Some(Frame {
                                    width: size.0,
                                    height: size.1,
                                    image: Image::Nv12(frame),
                                    captured: Instant::now(),
                                    is_new: true,
                                    hdr_metadata: None,
                                }));
                            }
                            // The converter produced a different geometry than
                            // the current request (a stale target during a
                            // quality change). Scale from the frame's own size,
                            // not the source crop, and let the next frame use
                            // the refreshed target.
                            let pixels = frame.pixels()?;
                            let from = frame.size();
                            Ok(Some((
                                size,
                                if from == size {
                                    pixels
                                } else {
                                    scale(&pixels, from, size)
                                },
                            )))
                        } else {
                            let image = if size == (picture.width, picture.height) {
                                picture.pixels
                            } else {
                                scale(&picture.pixels, (picture.width, picture.height), size)
                            };
                            Ok(Some((size, image)))
                        }
                    }
                    Ok(None) => Ok(None),
                    Err(e) => Err(e),
                }
            }
        };
        match result {
            Ok(Some((size, image))) => {
                self.available = true;
                let image = Arc::new(image);
                self.last = Some(image.clone());
                self.last_size = size;
                Ok(Some(Frame {
                    width: size.0,
                    height: size.1,
                    image: Image::Cpu {
                        pixels: image,
                        width: size.0,
                        height: size.1,
                    },
                    captured: Instant::now(),
                    is_new: true,
                    hdr_metadata: None,
                }))
            }
            Ok(None) => {
                self.available = true;
                Ok(None)
            }
            Err(error) => {
                self.available = false;
                Err(error)
            }
        }
    }
}

/// The encoded size for a source of `width`x`height` at `quality`.
fn output(width: u32, height: u32, quality: i32, maximum: (u32, u32)) -> (u32, u32) {
    let size = crate::media::geometry::output_size(width, height, quality);
    crate::media::geometry::fit_size(size.0, size.1, maximum)
}

/// Whether a freshly captured frame differs from the last one.
///
/// X11 cannot say whether anything changed, so the frames are compared. A
/// strided sample answers "changed" after a few kilobytes while the desktop is
/// moving, which is the streaming case; the full compare only runs for a still
/// picture, where the answer is "unchanged" and the poll is cheap anyway.
fn differs(previous: &[u8], image: &[u8]) -> bool {
    if previous.len() != image.len() {
        return true;
    }
    const SAMPLES: usize = 64;
    const WINDOW: usize = 64;
    if image.len() < WINDOW * 2 {
        return previous != image;
    }
    let step = ((image.len() / SAMPLES).max(WINDOW) & !3).max(4);
    let last = image.len() - WINDOW;
    for at in (0..last).step_by(step).take(SAMPLES) {
        if previous[at..at + WINDOW] != image[at..at + WINDOW] {
            return true;
        }
    }
    previous != image
}

/// Bilinear BGRA scaling, or a crop to even dimensions when the size already
/// fits. The encoder takes exactly the size it was opened with. The output is
/// opaque whatever the source's fourth byte held (X leaves it undefined).
///
/// A capture larger than the stream (a 4K-class screen at the highest
/// quality, which caps at 3840x2160) is scaled on every frame, so the work is
/// split by rows across the cores; each output row depends only on the source.
fn scale(source: &[u8], from: (u32, u32), to: (u32, u32)) -> Vec<u8> {
    let (source_width, source_height) = (from.0 as usize, from.1 as usize);
    let (width, height) = (to.0 as usize, to.1 as usize);
    if width <= source_width
        && height <= source_height
        && (source_width - width) <= 1
        && (source_height - height) <= 1
    {
        // Grow the frame instead of zeroing it: every byte below is written,
        // and the zero pass cost a full-size memset per frame.
        let mut out = Vec::with_capacity(width * height * 4);
        for y in 0..height {
            let start = y * source_width * 4;
            let begin = out.len();
            out.extend_from_slice(&source[start..start + width * 4]);
            for pixel in out[begin..].chunks_exact_mut(4) {
                pixel[3] = 0xff;
            }
        }
        return out;
    }
    let length = width * height * 4;
    // Every output channel, including alpha, is written by scale_row. Keeping
    // the backing bytes uninitialized avoids clearing a full output frame only
    // to overwrite it immediately in the row workers.
    let mut out: Vec<std::mem::MaybeUninit<u8>> = Vec::with_capacity(length);
    out.resize_with(length, std::mem::MaybeUninit::uninit);
    let columns = taps(source_width, width);
    let rows = taps(source_height, height);
    let row_bytes = width * 4;
    // Below about a megapixel the threads cost more than they save.
    let threads = if width * height < 1 << 20 {
        1
    } else {
        std::thread::available_parallelism().map_or(1, |n| n.get().min(8))
    };
    let band = height.div_ceil(threads);
    std::thread::scope(|scope| {
        for (index, chunk) in out.chunks_mut(band * row_bytes).enumerate() {
            let (columns, rows) = (&columns, &rows);
            let mut work = move || {
                for (offset, line) in chunk.chunks_exact_mut(row_bytes).enumerate() {
                    scale_row(
                        source,
                        source_width,
                        columns,
                        rows[index * band + offset],
                        line,
                    );
                }
            };
            if index + 1 == threads.min(height.div_ceil(band)) {
                work();
            } else {
                scope.spawn(work);
            }
        }
    });
    // SAFETY: the worker covers every row exactly once; scale_row writes all
    // four channels of every output pixel before the scope joins successfully.
    // No initialized output vector is created if a worker panics.
    let mut initialized = std::mem::ManuallyDrop::new(out);
    unsafe {
        Vec::from_raw_parts(
            initialized.as_mut_ptr().cast::<u8>(),
            initialized.len(),
            initialized.capacity(),
        )
    }
}

/// For each output position along one axis: the two source positions it
/// blends and the weight of the second, in 16.16 fixed point, sampling pixel
/// centres.
fn taps(source: usize, output: usize) -> Vec<(usize, usize, u32)> {
    let step = ((source as u64) << 16) / output as u64;
    let last = source - 1;
    (0..output)
        .map(|position| {
            let at = ((position as u64 * step) + (step >> 1)).saturating_sub(1 << 15);
            let first = ((at >> 16) as usize).min(last);
            (first, (first + 1).min(last), (at & 0xffff) as u32)
        })
        .collect()
}

fn scale_row(
    source: &[u8],
    source_width: usize,
    columns: &[(usize, usize, u32)],
    (y0, y1, fy): (usize, usize, u32),
    line: &mut [std::mem::MaybeUninit<u8>],
) {
    let top_row = &source[y0 * source_width * 4..(y0 + 1) * source_width * 4];
    let bottom_row = &source[y1 * source_width * 4..(y1 + 1) * source_width * 4];
    let (wy0, wy1) = (u64::from(0x10000 - fy), u64::from(fy));
    for (pixel, &(x0, x1, fx)) in line.chunks_exact_mut(4).zip(columns) {
        let (a, b) = (&top_row[x0 * 4..x0 * 4 + 4], &top_row[x1 * 4..x1 * 4 + 4]);
        let (c, d) = (
            &bottom_row[x0 * 4..x0 * 4 + 4],
            &bottom_row[x1 * 4..x1 * 4 + 4],
        );
        let (wx0, wx1) = (0x10000 - fx, fx);
        for channel in 0..3 {
            let top = u32::from(a[channel]) * wx0 + u32::from(b[channel]) * wx1;
            let bottom = u32::from(c[channel]) * wx0 + u32::from(d[channel]) * wx1;
            let value = ((u64::from(top >> 8) * wy0 + u64::from(bottom >> 8) * wy1) >> 24) as u32;
            pixel[channel].write(value.min(255) as u8);
        }
        pixel[3].write(0xff);
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn frame_is_send_for_latest_frame_handoff() {
        fn assert_send<T: Send>() {}
        assert_send::<super::Frame>();
        assert_send::<super::Desktop>();
    }

    use super::{Kind, consent_needed, scale, usable};

    #[test]
    fn only_an_unremembered_portal_asks_the_user() {
        assert!(consent_needed(&[Kind::Portal], false));
        assert!(!consent_needed(&[Kind::Portal], true));
        // X11 sessions reach the portal only after NvFBC and MIT-SHM fail.
        assert!(!consent_needed(
            &[Kind::NvFbc, Kind::X11, Kind::Portal],
            false
        ));
    }

    #[test]
    fn a_wlr_that_cannot_open_leaves_the_portal_in_front() {
        // Neither Mutter nor KWin serves `zwlr_screencopy_manager_v1`: the
        // startup probe must not fall through to the portal and pop its
        // dialog, which is what #1's guard exists to prevent.
        assert!(consent_needed(
            &usable(&[Kind::Wlr, Kind::Portal], false),
            false
        ));
        assert!(!consent_needed(
            &usable(&[Kind::Wlr, Kind::Portal], true),
            false
        ));
        // A grant that is already remembered stays quiet either way.
        assert!(!consent_needed(
            &usable(&[Kind::Wlr, Kind::Portal], false),
            true
        ));
        // An X11 order has no wlr entry to drop.
        assert!(!consent_needed(
            &usable(&[Kind::NvFbc, Kind::X11, Kind::Portal], false),
            false
        ));
    }

    #[test]
    fn crop_to_even_keeps_pixels() {
        let source: Vec<u8> = (0..3 * 3 * 4).map(|v| v as u8).collect();
        let out = scale(&source, (3, 3), (2, 2));
        let opaque = |pixels: &[u8]| -> Vec<u8> {
            pixels
                .chunks_exact(4)
                .flat_map(|p| [p[0], p[1], p[2], 0xff])
                .collect()
        };
        assert_eq!(&out[..8], &opaque(&source[..8])[..]);
        assert_eq!(&out[8..16], &opaque(&source[12..20])[..]);
    }

    /// The single-threaded scaler `scale` replaced; its output is the contract.
    fn reference(source: &[u8], from: (u32, u32), to: (u32, u32)) -> Vec<u8> {
        let (source_width, source_height) = (from.0 as usize, from.1 as usize);
        let (width, height) = (to.0 as usize, to.1 as usize);
        let mut out = vec![0u8; width * height * 4];
        if width <= source_width
            && height <= source_height
            && (source_width - width) <= 1
            && (source_height - height) <= 1
        {
            for y in 0..height {
                let start = y * source_width * 4;
                out[y * width * 4..(y + 1) * width * 4]
                    .copy_from_slice(&source[start..start + width * 4]);
            }
            return out;
        }
        // 16.16 fixed point, sampling pixel centres.
        let step_x = ((source_width as u64) << 16) / width as u64;
        let step_y = ((source_height as u64) << 16) / height as u64;
        let max_x = source_width - 1;
        let max_y = source_height - 1;
        for y in 0..height {
            let sy = ((y as u64 * step_y) + (step_y >> 1)).saturating_sub(1 << 15);
            let y0 = ((sy >> 16) as usize).min(max_y);
            let y1 = (y0 + 1).min(max_y);
            let fy = (sy & 0xffff) as u32;
            for x in 0..width {
                let sx = ((x as u64 * step_x) + (step_x >> 1)).saturating_sub(1 << 15);
                let x0 = ((sx >> 16) as usize).min(max_x);
                let x1 = (x0 + 1).min(max_x);
                let fx = (sx & 0xffff) as u32;
                let at = |px: usize, py: usize| (py * source_width + px) * 4;
                let (a, b, c, d) = (at(x0, y0), at(x1, y0), at(x0, y1), at(x1, y1));
                let o = (y * width + x) * 4;
                for channel in 0..4 {
                    let top = u32::from(source[a + channel]) * (0x10000 - fx)
                        + u32::from(source[b + channel]) * fx;
                    let bottom = u32::from(source[c + channel]) * (0x10000 - fx)
                        + u32::from(source[d + channel]) * fx;
                    let value = ((u64::from(top >> 8) * u64::from(0x10000 - fy)
                        + u64::from(bottom >> 8) * u64::from(fy))
                        >> 24) as u32;
                    out[o + channel] = value.min(255) as u8;
                }
            }
        }
        out
    }

    #[test]
    fn the_threaded_scaler_matches_the_reference_byte_for_byte() {
        // A cheap deterministic noise, so every channel and weight is exercised.
        let mut state = 0x2545_f491_u32;
        let mut noise = |length: usize| -> Vec<u8> {
            (0..length)
                .map(|_| {
                    state ^= state << 13;
                    state ^= state >> 17;
                    state ^= state << 5;
                    state as u8
                })
                .collect()
        };
        for (from, to) in [
            ((4096, 2560), (3456, 2160)),
            ((1920, 1080), (1280, 720)),
            ((1366, 768), (1280, 720)),
            ((5120, 1440), (3840, 1080)),
            ((640, 480), (1280, 960)),
            ((7, 5), (4, 2)),
            ((3, 3), (2, 2)),
        ] {
            let source = noise((from.0 * from.1 * 4) as usize);
            let (ours, theirs) = (scale(&source, from, to), reference(&source, from, to));
            // Colour exactly as before; alpha is now always opaque.
            assert!(
                ours.chunks_exact(4)
                    .zip(theirs.chunks_exact(4))
                    .all(|(a, b)| a[..3] == b[..3] && a[3] == 0xff),
                "{from:?} -> {to:?}"
            );
        }
    }

    #[test]
    fn downscale_of_flat_colour_is_flat() {
        let source = [10u8, 20, 30, 255].repeat(64 * 48);
        let out = scale(&source, (64, 48), (32, 24));
        assert!(out.chunks_exact(4).all(|p| p == [10, 20, 30, 255]));
    }
}

#[cfg(test)]
mod session_tests {
    /// Needs a desktop session: `OPENUUYC_CAPTURE=portal cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn captures_frames_for_a_few_seconds() {
        let screens = super::screens().unwrap();
        let mut desktop = super::Desktop::open_selected(&screens[0]).unwrap();
        println!("backend: {}", desktop.backend_name());
        let started = std::time::Instant::now();
        let (mut frames, mut size) = (0, None);
        let (mut wait, mut copy) = (std::time::Duration::ZERO, std::time::Duration::ZERO);
        while started.elapsed() < std::time::Duration::from_secs(4) {
            if let Some(frame) = desktop.next(50, 4, false, false, (3840, 2160)).unwrap() {
                frames += 1;
                size = Some((frame.width, frame.height));
                if let super::Backend::Wlr(grab) = &desktop.backend {
                    let (waited, copied) = grab.last_timings();
                    wait += waited;
                    copy += copied;
                }
            }
        }
        let per_frame = |total: std::time::Duration| {
            if frames == 0 {
                0.0
            } else {
                total.as_secs_f64() * 1000.0 / f64::from(frames)
            }
        };
        println!(
            "frames: {frames} size: {size:?} wait_ms: {:.2} copy_ms: {:.2}",
            per_frame(wait),
            per_frame(copy)
        );
        assert!(frames > 0);
    }

    /// Needs a Wayland session whose compositor serves `zwlr_screencopy` and a
    /// VA-API encoder: `cargo test --lib session_tests::captures_dmabuf_frames -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn captures_dmabuf_frames() {
        for screen in super::screens().unwrap() {
            let mut desktop = match super::Desktop::open_selected(&screen) {
                Ok(desktop) => desktop,
                Err(error) => {
                    println!("{}: open failed: {error:#}", screen.display_name);
                    continue;
                }
            };
            let backend = desktop.backend_name();
            desktop.set_gpu_frames(true);
            let started = std::time::Instant::now();
            let (mut frames, mut nv12) = (0, 0);
            while started.elapsed() < std::time::Duration::from_secs(2) {
                if let Some(frame) = desktop.next(50, 4, false, false, (3840, 2160)).unwrap() {
                    frames += 1;
                    if matches!(frame.image, super::Image::Nv12(_)) {
                        nv12 += 1;
                    }
                }
            }
            let state = match &desktop.backend {
                super::Backend::Wlr(grab) => {
                    let (transform, version, seen, formats, offered, buffer, failed) =
                        grab.dmabuf_report();
                    format!(
                        "transform={transform:?} version={version} seen={seen} formats={formats} offered={offered} buffer={buffer} failed={failed}"
                    )
                }
                _ => "n/a".into(),
            };
            println!(
                "{}: backend={backend} frames={frames} nv12={nv12} {state}",
                screen.display_name
            );
        }
    }

    /// Needs an Xorg session with a mapped window (Xvfb also works):
    /// `cargo test --lib session_tests::captures_window_frames -- --ignored --nocapture`.
    /// 走通目录注入 → Backend::Window → 抓取/缩放/去重 → 秒级 refresh 的整条管线。
    #[test]
    #[ignore]
    fn captures_window_frames() {
        let windows = super::window_screens().expect("window sources");
        assert!(!windows.is_empty(), "no window sources listed");
        let mut desktop = super::Desktop::open_selected(&windows[0]).unwrap();
        println!(
            "backend: {} · {} ({}x{})",
            desktop.backend_name(),
            desktop.screen.display_name,
            desktop.screen.width,
            desktop.screen.height
        );
        let started = std::time::Instant::now();
        let (mut frames, mut size) = (0, None);
        while started.elapsed() < std::time::Duration::from_secs(2) {
            if let Some(frame) = desktop.next(50, 4, false, false, (3840, 2160)).unwrap() {
                frames += 1;
                size = Some((frame.width, frame.height));
            }
        }
        println!("frames: {frames} size: {size:?}");
        assert!(frames > 0);
    }
}

#[cfg(test)]
mod stage_tests {
    /// Needs an X session: what each step of the X11 path costs per frame at
    /// the highest quality (a screen above 3840x2160 is scaled).
    #[test]
    #[ignore]
    fn x11_capture_stages() {
        let screens = super::screens().unwrap();
        let screen = &screens[0];
        let mut grab = super::x11::Grab::open(screen).unwrap();
        let size = super::output(screen.width, screen.height, 4, (3840, 2160));
        let n = 20u32;
        let (mut tg, mut ts, mut tc) = (
            std::time::Duration::ZERO,
            std::time::Duration::ZERO,
            std::time::Duration::ZERO,
        );
        let mut last: Option<Vec<u8>> = None;
        for _ in 0..n {
            let t = std::time::Instant::now();
            grab.grab(screen, true).unwrap();
            tg += t.elapsed();
            let t = std::time::Instant::now();
            let image = super::scale(grab.pixels(), (screen.width, screen.height), size);
            ts += t.elapsed();
            let t = std::time::Instant::now();
            let _same = last.as_deref() == Some(image.as_slice());
            tc += t.elapsed();
            last = Some(image);
        }
        println!(
            "{}x{} -> {}x{}: grab {:.1} ms, scale {:.1} ms, compare {:.1} ms",
            screen.width,
            screen.height,
            size.0,
            size.1,
            tg.as_secs_f64() * 1000.0 / f64::from(n),
            ts.as_secs_f64() * 1000.0 / f64::from(n),
            tc.as_secs_f64() * 1000.0 / f64::from(n)
        );
    }
}

#[cfg(test)]
mod screen_fallback_tests {
    use super::*;
    #[test]
    fn compositor_fallback_preserves_real_names_geometry_and_stable_identity() {
        let output = crate::platform::CompositorOutput {
            name: Some("DP-1".into()),
            left: -1920,
            top: 12,
            width: 1920,
            height: 1080,
            rotation: Default::default(),
        };
        let layout = crate::platform::CompositorLayout {
            outputs: vec![output.clone(), output],
        };
        let screens = wayland_screens(&layout).unwrap();
        assert_eq!(screens.len(), 1);
        assert_eq!(screens[0].left, -1920);
        assert_eq!(screens[0].width, 1920);
        assert_eq!(screens[0].identity.as_deref(), Some("x11/DP-1"));
        assert_eq!(
            screens[0].fps, 165,
            "unknown compositor mode must not cap high refresh"
        );
        assert!(!screens[0].hdr);
    }
    #[test]
    fn missing_outputs_do_not_become_a_fake_default_monitor() {
        assert!(wayland_screens(&Default::default()).unwrap().is_empty());
    }

    #[test]
    fn change_detection_samples_first_then_compares_fully() {
        let frame = vec![0u8; 256 * 1024];
        assert!(!differs(&frame, &frame.clone()));
        // A difference inside a sampled window is answered without the full
        // compare, which is the streaming case.
        let mut early = frame.clone();
        early[0] = 1;
        assert!(differs(&frame, &early));
        // A difference the samples miss still has to be reported.
        let mut late = frame.clone();
        let end = late.len() - 1;
        late[end] = 1;
        assert!(differs(&frame, &late));
        // A geometry change is a change even when the bytes agree.
        assert!(differs(&frame, &frame[..128]));
    }
}
