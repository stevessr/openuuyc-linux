//! The Linux input engine: the same ownership contract as the Windows engine
//! (every down it sends is released on loss, interruption or disconnect) on
//! top of the X Test injector.
#[path = "niri_touch_lease.rs"]
mod niri_touch_lease;

use super::geometry::{Geometry, Rect, Screen};
use super::wire::{Event, KeyOrButton};
use crate::platform::linux::input::{self as native, Injector, Lock};
use crate::platform::{CompositorLayout, Rotation};
use anyhow::{Result, bail, ensure};
use std::{
    collections::BTreeSet,
    time::{Duration, Instant},
};

/// The UU wire button mask values, in the order X numbers them.
const BUTTONS: [(u8, u8, u8); 5] = [
    // (wire value, held bit, X button)
    (1, 1, 1),
    (2, 2, 3),
    (16, 4, 2),
    (32, 8, 8),
    (64, 16, 9),
];

pub(crate) struct Engine {
    injector: Injector,
    keys: BTreeSet<u16>,
    /// Keys released by an interruption, owed nothing further but still
    /// counted as the controller's until it lifts them.
    parked: BTreeSet<u16>,
    buttons: u8,
    touch: Option<u32>,
    wheel: [i32; 2],
    geometry: Geometry,
    last_heartbeat: Instant,
    last_input: Instant,
    last_key: Instant,
    interruptible: bool,
    interrupted: bool,
    backspace: bool,
    threshold: Duration,
    mobile: bool,
    configuration: super::config::Configuration,
    foreground: Option<Option<u32>>,
    mouse_policy: super::config::MousePolicy,
    /// Why absolute positions last went where they did, so a change is
    /// logged once rather than on every event.
    aim_space: Option<Result<(), String>>,
    /// The pointer was last placed through the absolute device, so the
    /// device's axis values are where the pointer is. After a relative move
    /// (or an absolute position that fell back to one) they are stale, and
    /// writing them again would throw the pointer back there.
    aimed: bool,
    /// 这一串触摸走哪条路（见 `touch`）。选好之后整串都走它，抬起后再清掉。
    touch_route: Option<Route>,
    touch_screen_hint: Option<i32>,
    touch_lease: Option<niri_touch_lease::Lease>,
    /// 选路的结果，只为让日志在变化时记一条（和 `aim_space` 一样）。
    touch_space: Option<Result<(), String>>,
}

/// 一串触摸走哪条路。
#[derive(Clone, Copy, PartialEq, Debug)]
enum Route {
    /// 交给虚拟多点触摸屏。旋转、屏幕宽度与坐标映射在选路时算好带过来，免得
    /// 每一帧再查一次屏幕——抬手指那一帧查不到屏幕也得照样发得出去。
    Touchscreen {
        rotation: Rotation,
        width: f64,
        map: TouchMap,
    },
    /// 退回单指指针模拟。模拟走绝对指针，落点总是对的。
    Simulated,
}

/// 客户端坐标（当前屏的比例）落到触摸设备铺开的那块输出上的比例。
///
/// 整屏是恒等映射；窗口级源只占输出的一部分，触点要按窗口在输出里的位置
/// 平移、按窗口大小缩放，合成器才会把触点送到窗口上。
#[derive(Clone, Copy, PartialEq, Debug)]
struct TouchMap {
    left: f64,
    top: f64,
    width: f64,
    height: f64,
}
impl TouchMap {
    const FULL: Self = Self {
        left: 0.0,
        top: 0.0,
        width: 1.0,
        height: 1.0,
    };
    fn apply(self, x: f64, y: f64) -> (f64, f64) {
        (self.left + x * self.width, self.top + y * self.height)
    }
    /// 窗口矩形 (left, top, width, height) 相对输出矩形的映射。
    fn of_window(
        window: (i32, i32, u32, u32),
        output: (i32, i32, u32, u32),
    ) -> Option<Self> {
        if output.2 == 0 || output.3 == 0 {
            return None;
        }
        Some(Self {
            left: f64::from(window.0.checked_sub(output.0)?) / f64::from(output.2),
            top: f64::from(window.1.checked_sub(output.1)?) / f64::from(output.3),
            width: f64::from(window.2) / f64::from(output.2),
            height: f64::from(window.3) / f64::from(output.3),
        })
    }
}

/// 这一串触摸该落到哪块输出、怎么换算。
#[derive(Debug)]
struct TouchTarget {
    output: String,
    rotation: Rotation,
    width: f64,
    map: TouchMap,
}

fn output_rect(output: &crate::platform::CompositorOutput) -> (i32, i32, u32, u32) {
    (output.left, output.top, output.width, output.height)
}

fn output_as_rect(output: &crate::platform::CompositorOutput) -> Rect {
    Rect {
        left: output.left,
        top: output.top,
        right: output.left.saturating_add(output.width as i32),
        bottom: output.top.saturating_add(output.height as i32),
    }
}

/// 这一串触摸要交给哪块输出。显示器按合成器布局里的矩形配对；窗口级源按
/// 采集给出的实时位置配到它所在的那块输出，并把窗口内的坐标换算成输出比例。
///
/// 只有「合成器必然把这块触摸屏认到目标屏上」时才敢用设备。合成器怎么给触摸屏
/// 和输出配对各家都不一样：Hyprland 找不到绑定就用当前聚焦的显示器（触点落在哪块屏
/// 取决于被控端此刻的焦点），mutter 按 EDID 与物理尺寸匹配、都匹配不上就归给内置屏，
/// KWin 又是另一套。猜错就是「主控看着这块屏、点下去落在另一块屏上」，比单指模拟更
/// 差——模拟走绝对指针，落点总是对的。所以 niri 以外的会话仍要求单屏、未旋转；
/// niri 由调用方先把 `input.touch.map-to-output` 对准目标输出（见 `ensure_touch_mapping`）。
fn touch_target(
    layout: Option<&CompositorLayout>,
    screens: &[Screen],
    current: i32,
    niri: bool,
) -> Result<TouchTarget, String> {
    use crate::platform::capture::WindowPlace;
    let layout = layout.ok_or_else(|| "读不到合成器的输出布局".to_owned())?;
    let screen = screens
        .iter()
        .find(|screen| screen.id == current)
        .ok_or_else(|| format!("当前触摸屏幕 {current} 已失效"))?;
    let width = f64::from(screen.width).max(1.0);
    if crate::platform::capture::is_window_target(current) {
        if !niri {
            return Err("窗口源没有可映射的合成器输出".into());
        }
        // 窗口在别的工作区上时先露出来，和鼠标注入走同一条路。
        let place = crate::platform::capture::window_place(current).or_else(|| {
            if crate::platform::capture::reveal_window(current) {
                crate::platform::capture::window_place(current)
            } else {
                None
            }
        });
        let Some(WindowPlace::Compositor { rect, output }) = place else {
            return Err("窗口位置未知，无法把触点落到窗口上".into());
        };
        let output = layout
            .outputs
            .iter()
            .find(|candidate| output_rect(candidate) == output)
            .ok_or_else(|| "窗口所在输出不在合成器布局里".to_owned())?;
        let name = output
            .name
            .clone()
            .ok_or_else(|| "窗口所在输出没有名字".to_owned())?;
        let map = TouchMap::of_window(rect, output_rect(output))
            .ok_or_else(|| "窗口尺寸无效".to_owned())?;
        return Ok(TouchTarget {
            output: name,
            rotation: output.rotation,
            width,
            map,
        });
    }
    if niri {
        let logical = screen
            .logical
            .ok_or_else(|| "触摸屏与合成器输出未配对".to_owned())?;
        let output = layout
            .outputs
            .iter()
            .find(|output| output_as_rect(output) == logical)
            .ok_or_else(|| "触摸屏与合成器输出未配对".to_owned())?;
        let name = output
            .name
            .clone()
            .ok_or_else(|| "触摸屏所在输出没有名字".to_owned())?;
        return Ok(TouchTarget {
            output: name,
            rotation: output.rotation,
            width,
            map: TouchMap::FULL,
        });
    }
    let [output] = layout.outputs.as_slice() else {
        return Err(format!("合成器有 {} 块屏", layout.outputs.len()));
    };
    // 「被控端只有一块屏」数的是显示器：目录里并列的窗口源不是输出。
    let monitors: Vec<&Screen> = screens
        .iter()
        .filter(|screen| !crate::platform::capture::is_window_target(screen.id))
        .collect();
    if monitors.len() != 1 || screen.id != monitors[0].id {
        return Err(format!("被控端有 {} 块屏", monitors.len()));
    }
    let logical = screen
        .logical
        .ok_or_else(|| "触摸屏与合成器输出未配对".to_owned())?;
    if output.rotation != Rotation::Normal {
        return Err(format!("输出旋转为 {:?}", output.rotation));
    }
    if logical != output_as_rect(output) {
        return Err("触摸屏与合成器输出未配对".into());
    }
    Ok(TouchTarget {
        output: output.name.clone().unwrap_or_default(),
        rotation: Rotation::Normal,
        width,
        map: TouchMap::FULL,
    })
}

/// 原配置里与触摸有关的设置。niri 没有按设备配置（`input { touch "NAME" ... }`
/// 会被 `niri validate` 拒绝），`map-to-output` 对全部触摸设备同时生效，所以只
/// 有一个全局值。
#[derive(Clone, Debug, Default, PartialEq)]
struct NiriTouch {
    off: bool,
    calibration: Option<Vec<String>>,
    map_to_output: Option<String>,
}

/// Niri does not expose `input.touch.map-to-output` over IPC. Read the user's
/// KDL include tree (read-only) so native touch is aimed at the right output.
fn niri_config_root() -> Option<std::path::PathBuf> {
    let socket = std::env::var_os("NIRI_SOCKET")?;
    let socket_name = std::path::Path::new(&socket).file_name()?.to_string_lossy();
    let pid = socket_name
        .strip_suffix(".sock")?
        .rsplit_once('.')?
        .1
        .parse::<u32>()
        .ok()?;
    let args = std::fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    let args = args
        .split(|b| *b == 0)
        .map(|v| String::from_utf8_lossy(v).into_owned())
        .collect::<Vec<_>>();
    let configured = args
        .iter()
        .position(|a| a == "--config")
        .and_then(|i| args.get(i + 1))
        .map(std::path::PathBuf::from)
        .or_else(|| {
            args.iter()
                .find_map(|a| a.strip_prefix("--config=").map(std::path::PathBuf::from))
        })
        .or_else(|| {
            Some(
                std::env::var_os("XDG_CONFIG_HOME")
                    .map(std::path::PathBuf::from)
                    .unwrap_or_else(|| {
                        std::path::PathBuf::from(std::env::var_os("HOME").unwrap_or_default())
                            .join(".config")
                    })
                    .join("niri/config.kdl"),
            )
        })?;
    // niri 的相对 --config 是相对它自己的当前目录，不是本进程的。
    let root = if configured.is_absolute() {
        configured
    } else {
        std::fs::read_link(format!("/proc/{pid}/cwd"))
            .ok()?
            .join(configured)
    };
    Some(std::fs::canonicalize(&root).unwrap_or(root))
}

fn read_niri_touch() -> Option<NiriTouch> {
    let root = niri_config_root()?;
    let mut tokens = Vec::new();
    expand_niri_config(&root, &mut std::collections::HashSet::new(), &mut tokens, 0);
    Some(niri_touch_from_tokens(&tokens))
}

/// 配置里最后一段 `input { touch { ... } }` 生效：niri 合并多个 `input` 段时
/// 整段替换 `touch`，不会把前面的字段并进来。
fn niri_touch_from_tokens(tokens: &[String]) -> NiriTouch {
    let mut touch = NiriTouch::default();
    let mut i = 0;
    while i + 1 < tokens.len() {
        if tokens[i] != "input" || tokens[i + 1] != "{" {
            i += 1;
            continue;
        }
        let Some(end) = kdl_block_end(tokens, i + 1) else {
            break;
        };
        let mut child = i + 2;
        while child + 1 < end {
            if tokens[child] == "touch" && tokens[child + 1] == "{" {
                let Some(close) = kdl_block_end(tokens, child + 1) else {
                    break;
                };
                let mut found = NiriTouch::default();
                let mut p = child + 2;
                while p < close {
                    match tokens[p].as_str() {
                        "off" => {
                            found.off = true;
                            p += 1;
                        }
                        "calibration-matrix" => {
                            let mut values = Vec::new();
                            p += 1;
                            while p < close
                                && values.len() < 6
                                && tokens[p].parse::<f64>().is_ok()
                            {
                                values.push(tokens[p].clone());
                                p += 1;
                            }
                            found.calibration = Some(values);
                        }
                        "map-to-output" => {
                            found.map_to_output = tokens.get(p + 1).cloned();
                            p += 2;
                        }
                        _ => p += 1,
                    }
                }
                touch = found;
                child = close + 1;
            } else if tokens[child] == "{" {
                match kdl_block_end(tokens, child) {
                    Some(close) => child = close + 1,
                    None => break,
                }
            } else {
                child += 1;
            }
        }
        i = end + 1;
    }
    touch
}

/// 临时改 Niri 的触摸映射默认开启；`OPENUUYC_NIRI_TOUCH_REMAP=0`（旧的
/// `OPENUUYC_NIRI_TEMP_TOUCH_MAP=0` 同样）关掉，触摸退回单指模拟。
fn touch_remap_enabled() -> bool {
    let value = std::env::var("OPENUUYC_NIRI_TOUCH_REMAP")
        .or_else(|_| std::env::var("OPENUUYC_NIRI_TEMP_TOUCH_MAP"))
        .ok();
    !matches!(
        value.as_deref(),
        Some("0" | "false" | "off" | "no" | "False" | "OFF")
    )
}

fn expand_niri_config(
    path: &std::path::Path,
    seen: &mut std::collections::HashSet<std::path::PathBuf>,
    out: &mut Vec<String>,
    depth: usize,
) {
    if depth > 16 {
        return;
    }
    let Ok(path) = std::fs::canonicalize(path) else {
        return;
    };
    if !seen.insert(path.clone()) {
        return;
    }
    let Ok(source) = std::fs::read_to_string(&path) else {
        return;
    };
    let tokens = kdl_tokens(&source);
    let mut i = 0;
    while i < tokens.len() {
        if tokens[i] == "include"
            && let Some(include) = tokens.get(i + 1)
        {
            expand_niri_config(
                &path
                    .parent()
                    .unwrap_or(std::path::Path::new("."))
                    .join(include),
                seen,
                out,
                depth + 1,
            );
            i += 2;
        } else {
            out.push(tokens[i].clone());
            i += 1;
        }
    }
}

fn kdl_block_end(tokens: &[String], open: usize) -> Option<usize> {
    let mut depth = 0usize;
    for (index, token) in tokens.iter().enumerate().skip(open) {
        match token.as_str() {
            "{" => depth += 1,
            "}" => {
                depth = depth.checked_sub(1)?;
                if depth == 0 {
                    return Some(index);
                }
            }
            _ => (),
        }
    }
    None
}

fn kdl_tokens(source: &str) -> Vec<String> {
    let bytes = source.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_whitespace() || bytes[i] == b';' {
            i += 1;
            continue;
        }
        if bytes[i..].starts_with(b"//") {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
            continue;
        }
        if bytes[i..].starts_with(b"/*") {
            i += 2;
            while i + 1 < bytes.len() && !bytes[i..].starts_with(b"*/") {
                i += 1;
            }
            i = (i + 2).min(bytes.len());
            continue;
        }
        if matches!(bytes[i], b'{' | b'}') {
            out.push((bytes[i] as char).to_string());
            i += 1;
            continue;
        }
        if bytes[i] == b'"' {
            i += 1;
            let mut value = Vec::new();
            while i < bytes.len() && bytes[i] != b'"' {
                if bytes[i] == b'\\' && i + 1 < bytes.len() {
                    i += 1;
                }
                value.push(bytes[i]);
                i += 1;
            }
            if i < bytes.len() {
                i += 1;
            }
            out.push(String::from_utf8_lossy(&value).into_owned());
            continue;
        }
        let start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && !matches!(bytes[i], b'{' | b'}' | b';')
        {
            i += 1;
        }
        if start < i {
            out.push(String::from_utf8_lossy(&bytes[start..i]).into_owned());
        } else {
            i += 1;
        }
    }
    out
}

impl Engine {
    /// `hardware`, `origin` and `privileged` describe a Windows session; the X
    /// session injector is the same for every caller.
    pub fn new(
        _hardware: bool,
        policy: super::wire::Policy,
        _origin: u32,
        _privileged: bool,
    ) -> Result<Self> {
        let now = Instant::now();
        Ok(Self {
            injector: Injector::open()?,
            keys: BTreeSet::new(),
            parked: BTreeSet::new(),
            buttons: 0,
            touch: None,
            wheel: [0; 2],
            geometry: Default::default(),
            last_heartbeat: now,
            last_input: now,
            last_key: now,
            interruptible: false,
            interrupted: false,
            backspace: false,
            threshold: Duration::from_millis(if matches!(policy, super::wire::Policy::Windows) {
                150
            } else {
                300
            }),
            mobile: matches!(policy, super::wire::Policy::Mobile),
            configuration: Default::default(),
            foreground: None,
            mouse_policy: Default::default(),
            aim_space: None,
            touch_route: None,
            touch_screen_hint: None,
            touch_lease: None,
            touch_space: None,
            aimed: false,
        })
    }
    pub fn backend(&self) -> &'static str {
        "X11 XTest · 当前用户"
    }
    /// Ctrl+Alt+Del is an ordinary key chord to an X session, so nothing is
    /// ever withheld for a secure-attention path.
    pub fn take_sas(&mut self) -> bool {
        false
    }
    /// 请合成器重新判定一次指针底下是谁：把指针在原地重报一次。Wayland 的拖放接取
    /// 面摆在指针底下，而合成器只在指针真的动过之后才重判（见
    /// `platform::linux::input::realign_pointer`）。
    ///
    /// 只在指针最后一次是经绝对设备摆的位置时才重报：设备轴上的值只在那时与指针
    /// 一致，相对移动之后再写它，指针会被甩回旧位置。被控本机的人在最后一次注入
    /// 之后自己动过鼠标，轴上的值同样会过期，这里看不出来；拖出时被控端一般没人
    /// 碰鼠标，所以不另做处理。
    pub fn realign_pointer(&mut self) -> Result<bool> {
        if !self.aimed {
            return Ok(false);
        }
        self.injector.realign()
    }
    pub fn configure(&mut self, configuration: super::config::Configuration) -> Result<()> {
        configuration.validate()?;
        self.configuration = configuration;
        self.foreground = None;
        if !self.configuration.keyboard_interrupts {
            self.interruptible = false;
        }
        self.observe_foreground();
        Ok(())
    }
    pub fn mouse_policy(&self) -> super::config::MousePolicy {
        self.mouse_policy.clone()
    }
    /// Apply the server's per-application mouse rule to the focused program,
    /// matched by executable name as on Windows.
    fn observe_foreground(&mut self) {
        let pid = self.injector.foreground();
        if self.foreground == Some(pid) {
            return;
        }
        self.foreground = Some(pid);
        let name = pid
            .and_then(|pid| std::fs::read_link(format!("/proc/{pid}/exe")).ok())
            .and_then(|path| {
                path.file_name()
                    .map(|name| name.to_string_lossy().to_lowercase())
            });
        let mode = name
            .as_ref()
            .and_then(|name| self.configuration.games.get(name))
            .map_or(2, |rule| rule.mode);
        if self.mouse_policy.mode.unwrap_or(2) == mode {
            return;
        }
        let mut restore = None;
        // The pointer is read from X, so it is compared with X's rectangle:
        // the compositor's logical one is a different space.
        if mode == 2
            && let Ok(screen) = self.geometry.screen(None)
            && let Ok(rect) = screen.rect()
            && let Ok((x, y)) = self.injector.pointer()
            && (rect.left..rect.right).contains(&x)
            && (rect.top..rect.bottom).contains(&y)
        {
            restore = Some([
                f64::from(x - rect.left) / f64::from(screen.width),
                f64::from(y - rect.top) / f64::from(screen.height),
            ]);
        }
        self.mouse_policy = super::config::MousePolicy {
            mode: Some(mode),
            restore,
        };
    }

    /// The compositor's layout bounds when absolute positions can be aimed in
    /// it, or `None` to aim in X's rectangles. A change of answer is logged.
    fn compositor_bounds(&mut self) -> Option<native::Desktop> {
        if !self.injector.has_absolute_pointer() {
            return None;
        }
        let layout = crate::platform::display::compositor_layout();
        // 窗口级源不是输出，合成器布局里本来就没有它的名字。它走自己的定位
        // （见 `absolute_window`），不参与这里的逐屏配对：否则目录里只要列
        // 着一个窗口，所有显示器的绝对定位都会被它一起否决。
        let monitors = monitors(
            &self.geometry.screens,
            crate::platform::capture::is_window_target,
        );
        let chosen = compositor_bounds(&monitors, layout.as_deref());
        let state = chosen.as_ref().map(|_| ()).map_err(Clone::clone);
        if self.aim_space.as_ref() != Some(&state) {
            match &state {
                Ok(()) => tracing::info!("绝对定位按合成器布局（xdg-output）解算"),
                Err(reason) => tracing::warn!(%reason, "绝对定位改用按指针位置换算的相对移动"),
            }
            self.aim_space = Some(state);
        }
        chosen.ok()
    }

    /// 合成器全局布局的外接矩形，绝对指针就是按它解算的。窗口级源不参与
    /// [`Self::compositor_bounds`] 的逐屏配对，但它的位置本来就在这个空间
    /// 里，只要有这块范围就能定位。
    fn layout_bounds(&self) -> Option<native::Desktop> {
        if !self.injector.has_absolute_pointer() {
            return None;
        }
        native::layout_bounds(crate::platform::display::compositor_layout()?.as_ref())
    }

    pub fn synchronize(&mut self, geometry: Geometry) -> Result<()> {
        if geometry != self.geometry {
            self.release()?;
            self.geometry = geometry;
        }
        self.observe_foreground();
        Ok(())
    }
    fn key(&mut self, key: u16, down: bool) -> Result<()> {
        if down {
            self.injector.key(key, true)?;
            self.parked.remove(&key);
            self.keys.insert(key);
        } else if self.keys.remove(&key) {
            if let Err(error) = self.injector.key(key, false) {
                self.keys.insert(key);
                return Err(error);
            }
        } else {
            self.parked.remove(&key);
        }
        Ok(())
    }
    fn button(&mut self, button: u8, down: bool) -> Result<()> {
        let (_, bit, native) = BUTTONS
            .into_iter()
            .find(|(wire, _, _)| *wire == button)
            .ok_or_else(|| anyhow::anyhow!("无效鼠标按键"))?;
        if !down && self.buttons & bit == 0 {
            return Ok(());
        }
        self.injector.button(native, down)?;
        if down {
            self.buttons |= bit;
        } else {
            self.buttons &= !bit;
        }
        Ok(())
    }
    fn absolute(&mut self, screen: Option<i32>, x: f64, y: f64) -> Result<()> {
        let selected = self.geometry.screen(screen)?;
        let id = selected.id;
        let (logical, rect) = (selected.logical, selected.rect());
        if screen.is_some() && self.touch_route.is_none() {
            self.touch_screen_hint = Some(id);
        }
        // 窗口级源（waypipe 风格）不在合成器的输出拓扑里，目录里那个矩形也
        // 不是它的位置：它现在在哪由采集那边实时给出。
        if crate::platform::capture::is_window_target(id) {
            return self.absolute_window(id, x, y);
        }
        // Every screen in the compositor's space, or every screen in X's:
        // a position named in one and resolved in the other lands off by the
        // output's scale.
        if let Some(bounds) = self.compositor_bounds()
            && let Some(logical) = logical
        {
            let (px, py) = point_in(logical, x, y);
            let result = self.injector.aim(px, py, bounds);
            self.aimed = result.is_ok();
            return result;
        }
        self.aimed = false;
        let (px, py) = point_in(rect?, x, y);
        self.injector.move_to(px, py)
    }

    /// 窗口级源上的绝对定位。位置按采集循环缓存的实时几何换算；定位不到
    /// 时先请合成器把窗口露出来（见 `reveal_window`），再算一次。窗口源可以
    /// 在别的工作区上被采集，但指针只能落到屏幕上看得见的地方。
    fn absolute_window(&mut self, id: i32, x: f64, y: f64) -> Result<()> {
        use crate::platform::capture::WindowPlace;
        let place = match crate::platform::capture::window_place(id) {
            Some(place) => place,
            None => {
                // 窗口在别的工作区、或者整个滚出了视口：屏幕上没有它，指针
                // 也够不着。让合成器切过去并滚进视口，按新位置再算一次。
                if crate::platform::capture::reveal_window(id)
                    && let Some(place) = crate::platform::capture::window_place(id)
                {
                    place
                } else {
                    self.aimed = false;
                    bail!("窗口位置未知（可能不在可见工作区），无法注入输入");
                }
            }
        };
        match place {
            // X 像素空间里的矩形：Xorg 会话由 XTest 直接摆到那个位置。
            WindowPlace::X11 { rect } => {
                self.aimed = false;
                let (px, py) = point_in(window_rect(rect)?, x, y);
                self.injector.move_to(px, py)
            }
            WindowPlace::Compositor { rect, output } => {
                let (px, py) = point_in(window_rect(rect)?, x, y);
                // 窗口伸到它所在输出之外的部位（被滚出视口的那一截）在屏幕
                // 上没有位置，投过去只会落到别的窗口或别的屏上。
                let shown = window_rect(output)?;
                if !((shown.left..shown.right).contains(&px)
                    && (shown.top..shown.bottom).contains(&py))
                {
                    self.aimed = false;
                    bail!("窗口的这一部分不在屏幕上（滚出了视口），无法注入输入");
                }
                // 窗口位置和绝对指针在同一个空间里，直接交给合成器解算。退回
                // 相对移动要先读当前指针位置，而 Wayland 上读到的是 XWayland
                // 的那一份，指针在原生窗口上时它是过期的；更不能拿合成器的
                // 坐标去按 X 的坐标注入。没有布局就明确报错。
                let Some(bounds) = self.layout_bounds() else {
                    self.aimed = false;
                    bail!("合成器布局未知，无法按窗口位置注入输入");
                };
                let result = self.injector.aim(px, py, bounds);
                self.aimed = result.is_ok();
                result
            }
        }
    }

    /// 按屏幅折算相对位移时用的尺寸。Wayland 窗口源用它现在的逻辑尺寸：目
    /// 录里那个是捕获缓冲的物理像素，缩放过的输出上比指针走的逻辑像素大。
    fn extent(&self, screen: &Screen) -> (f64, f64) {
        use crate::platform::capture::WindowPlace;
        match crate::platform::capture::window_place(screen.id) {
            Some(WindowPlace::Compositor { rect, .. }) => (f64::from(rect.2), f64::from(rect.3)),
            _ => (f64::from(screen.width), f64::from(screen.height)),
        }
    }
    fn wheel(&mut self, horizontal: bool, delta: i32) -> Result<()> {
        // 120 units per notch as on Windows. X wheels only click, so partial
        // (touchpad) increments accumulate until they make a whole notch.
        let slot = &mut self.wheel[usize::from(horizontal)];
        if (*slot > 0) != (delta > 0) {
            *slot = 0;
        }
        *slot = slot.saturating_add(delta);
        while slot.unsigned_abs() >= 120 {
            let positive = *slot > 0;
            self.injector.wheel_notch(horizontal, positive)?;
            *slot -= if positive { 120 } else { -120 };
        }
        Ok(())
    }
    /// 触摸。合成的触摸屏设备才是这些触点该去的地方：合成器拿到的是一个真正的
    /// 多点设备，触点有几个就是几个，双指滚动、捏合、手势都是真的，而不是把一个
    /// 触点翻成鼠标左键。
    ///
    /// 走哪条路在一串触摸开始时定好、整串都走它：中途换路会让已经按下的触点在原来
    /// 那条路上没人抬起。设备那条路有前提（合成器必然把触摸屏认到唯一那块屏上，见
    /// `route_for`），不满足就整串走单指模拟——模拟走绝对指针，落点总是对的。
    ///
    /// 没有设备时（Xorg 会话，或者 Wayland 下没有 `/dev/uinput` 写权限）在选路时
    /// 就定成模拟。只有设备在一串触摸中途写入失败被丢掉时，`deliver_touch` 才会
    /// 返回 false，这一串余下的触点改走模拟：设备移除时合成器已经把它上面的触点
    /// 全部收尾，模拟从下一个触点重新按下。
    fn touch(&mut self, kind: u8, points: &[super::wire::Point]) -> Result<()> {
        let route = match self.touch_route {
            Some(route) => route,
            None => {
                let route = self.choose_route();
                self.touch_route = Some(route);
                route
            }
        };
        // 3 是抬起、4 是取消：这一串到此为止，下一串重新选路。
        if kind >= 3 {
            self.touch_route = None;
        }
        if let Route::Touchscreen {
            rotation,
            width,
            map,
        } = route
            && self.deliver_touch(kind, points, rotation, width, map)?
        {
            // 触摸屏不经绝对指针，可合成器会让光标跟着触点走（sway 在触点底下的
            // 表面不收触摸时就拿它模拟指针），绝对指针轴上那个位置也就过期了。
            self.aimed = false;
            return Ok(());
        }
        if kind == 1 || kind >= 3 {
            if self.touch.take().is_some() {
                self.button(1, false)?;
            }
            if kind >= 3 {
                return Ok(());
            }
        }
        let primary = match self.touch {
            Some(id) => points.iter().find(|p| p.id == id),
            None => points.first(),
        };
        let Some(point) = primary else {
            if self.touch.take().is_some() {
                self.button(1, false)?;
            }
            return Ok(());
        };
        self.absolute(self.touch_screen_hint, point.x, point.y)?;
        if self.touch.is_none() {
            self.button(1, true)?;
            self.touch = Some(point.id);
        }
        Ok(())
    }
    /// 选这一串触摸走哪条路，判定变化时记一条日志（和 `aim_space` 一样只记一次）。
    fn choose_route(&mut self) -> Route {
        let current = self.touch_screen_hint.unwrap_or(self.geometry.current);
        let is_niri = std::env::var_os("NIRI_SOCKET").is_some();
        let layout = crate::platform::display::compositor_layout();
        let decision = if !self.injector.has_touchscreen() {
            Err("没有虚拟触摸屏".into())
        } else {
            match touch_target(layout.as_deref(), &self.geometry.screens, current, is_niri) {
                Ok(target) => {
                    let aim = if is_niri {
                        // Niri 只有一个全局触摸映射；先把它对准这一串触摸要去
                        // 的输出，合成器才会把触点派发到那块屏上。
                        self.ensure_touch_mapping(&target.output)
                    } else {
                        Ok(())
                    };
                    aim.map(|()| Route::Touchscreen {
                        rotation: target.rotation,
                        width: target.width,
                        map: target.map,
                    })
                }
                Err(reason) => Err(reason),
            }
        };
        let state = decision.as_ref().map(|_| ()).map_err(Clone::clone);
        if self.touch_space.as_ref() != Some(&state) {
            match &state {
                Ok(()) => tracing::info!("触摸交给虚拟多点触摸屏"),
                Err(reason) => tracing::info!(%reason, "触摸退回单指指针模拟"),
            }
            self.touch_space = Some(state);
        }
        decision.unwrap_or(Route::Simulated)
    }

    /// 让 Niri 把触摸设备映射到这块输出。已经对上就不动；没对上时写一份临时
    /// 配置（包含用户原配置，保留触摸的其他设置）并加载，断开或换屏时恢复。
    fn ensure_touch_mapping(&mut self, output: &str) -> Result<(), String> {
        let Some(touch) = read_niri_touch() else {
            return Err("读不到 Niri 的触摸配置".into());
        };
        if touch.off {
            return Err("Niri 配置关闭了触摸".into());
        }
        // 已经有一份临时配置在生效时，以它为准：原配置里写的是哪块屏都不
        // 代表 niri 现在的映射，临时配置会一直盖着它直到会话结束。
        if let Some(lease) = &self.touch_lease {
            if lease.maps_to(output) {
                return Ok(());
            }
        } else if touch.map_to_output.as_deref() == Some(output) {
            return Ok(());
        }
        if !touch_remap_enabled() {
            return Err(match &touch.map_to_output {
                Some(mapped) => format!("Niri 触摸映射到 {mapped}，临时映射已关闭"),
                None => "Niri 没有配置触摸输出，临时映射已关闭".into(),
            });
        }
        let root = niri_config_root().ok_or_else(|| "找不到 Niri 配置".to_owned())?;
        if !root.is_file() {
            // 没有配置文件时 niri 用内置默认值；拿一份只含触摸覆盖的临时配置
            // 去替换它会把别的设置一起冲掉，不如退回单指模拟。
            return Err("Niri 没有配置文件，无法临时映射触摸".into());
        }
        if self.touch_lease.is_none() {
            self.touch_lease = Some(
                niri_touch_lease::Lease::new(
                    root,
                    niri_touch_lease::TouchSettings {
                        off: touch.off,
                        calibration: touch.calibration.clone(),
                    },
                )
                .map_err(|error| format!("临时触摸映射不可用：{error}"))?,
            );
        }
        self.touch_lease
            .as_mut()
            .expect("just created")
            .map_to(output)
            .map_err(|error| format!("临时触摸映射失败：{error}"))
    }
    /// 把触点交给触摸屏设备。旋转、屏幕宽度与坐标映射由 `touch_target` 在选路时
    /// 算好带过来，这一帧不再查屏幕。返回 false 表示设备已经在这一串中途被丢掉，
    /// 调用方退回模拟。
    fn deliver_touch(
        &mut self,
        kind: u8,
        points: &[super::wire::Point],
        rotation: Rotation,
        width: f64,
        map: TouchMap,
    ) -> Result<bool> {
        let touches = points
            .iter()
            .map(|point| {
                // 窗口级源只占输出的一部分：先把窗口内的比例换算到输出比例，
                // 合成器才会把触点送到窗口上。
                let (x, y) = map.apply(point.x, point.y);
                native::Touch {
                    id: point.id,
                    x: x as f32,
                    y: y as f32,
                    // 接触椭圆的长轴半径，按屏幕宽度换算成触摸屏的量程。libinput 处理
                    // 触摸屏的那条路径不读这个轴（`ABS_MT_TOUCH_MAJOR`），公开接口里也
                    // 没有接触面积，所以报了也没有人看得见；留着只是照实体设备的样子
                    // 报一个值。旋转过的屏上两条轴的半径混在一起，取平均。
                    radius: (((point.radius_x + point.radius_y) / 2.0) / width) as f32,
                }
            })
            .collect::<Vec<_>>();
        self.injector.touch(kind, &touches, rotation)
    }

    fn chord(&mut self, keys: &[u16], permitted: &impl Fn() -> bool) -> Result<()> {
        for &key in keys {
            ensure!(permitted(), "快捷输入已撤销");
            self.key(key, true)?;
        }
        for &key in keys.iter().rev() {
            self.key(key, false)?;
        }
        Ok(())
    }
    /// Text events contain characters already committed by the controller's IME,
    /// or an explicit mobile paste. Never feed them through the host IME again.
    fn paste_text(&mut self, text: &str, permitted: &impl Fn() -> bool) -> Result<()> {
        if text.is_empty() {
            return Ok(());
        }
        let shortcut = self.injector.paste_shortcut();
        crate::features::clipboard::publish_input_text(text, permitted)?;
        // These may still be held by the Ctrl+V that requested text on the peer.
        // Use the engine's ownership set: X11 cannot see Wayland's uinput state.
        let held: Vec<_> = self
            .keys
            .iter()
            .copied()
            .filter(|key| matches!(*key, 0x10..=0x12 | 0x5b | 0x5c | 0xa0..=0xa5))
            .collect();
        // Re-pressing a held V after restoring Ctrl would paste a second time.
        // Park it until the peer releases it, just like an interrupted key.
        if self.keys.contains(&0x56) {
            self.key(0x56, false)?;
            self.parked.insert(0x56);
        }
        for &key in &held {
            self.key(key, false)?;
        }
        let result = (|| {
            self.chord(shortcut, permitted)?;
            self.injector.flush()?;
            // Paste is asynchronous in the target. Keep this selection stable
            // before a subsequent text_input can replace it (including one-char
            // phone IME commits). Do not restore peer modifiers during the paste.
            let until = Instant::now() + Duration::from_millis(100);
            while Instant::now() < until {
                ensure!(permitted(), "文字粘贴已撤销");
                std::thread::sleep(Duration::from_millis(5));
            }
            Ok(())
        })();
        // Failed chords remain owned until release(), so no key is lost on error.
        if result.is_ok() && permitted() {
            for key in held {
                ensure!(permitted(), "文字粘贴已撤销");
                self.key(key, true)?;
            }
        }
        result
    }

    pub fn apply(
        &mut self,
        event: Event,
        geometry: Geometry,
        permitted: impl Fn() -> bool,
    ) -> Result<()> {
        let result = self.apply_inner(event, geometry, &permitted);
        let flushed = self.injector.flush();
        result.and(flushed)
    }
    fn apply_inner(
        &mut self,
        event: Event,
        geometry: Geometry,
        permitted: &impl Fn() -> bool,
    ) -> Result<()> {
        ensure!(permitted(), "被控输入许可已撤销");
        self.synchronize(geometry)?;
        ensure!(permitted(), "被控输入许可已撤销");
        if matches!(event, Event::Heartbeat) {
            self.last_heartbeat = Instant::now();
            return Ok(());
        }
        self.last_input = Instant::now();
        match event {
            Event::Command(command) => {
                use super::wire::Command;
                self.release()?;
                ensure!(permitted(), "被控输入许可已撤销");
                match command {
                    // Show the desktop / the window overview with the Super
                    // shortcuts GNOME and KDE bind by default.
                    Command::Desktop => self.chord(&[0x5b, 0x44], permitted)?,
                    Command::TaskView => self.chord(&[0x5b], permitted)?,
                    Command::TaskManager => native::system_monitor()?,
                    Command::Lock => native::lock_session()?,
                }
            }
            Event::Heartbeat => {}
            Event::Key {
                key,
                down,
                interruptible,
                toggle,
            } => {
                if down && key == 0x4c && [0x5b, 0x5c].iter().any(|k| self.keys.contains(k)) {
                    // Win+L locks the session on Windows; do the same here.
                    self.release()?;
                    ensure!(permitted(), "被控输入许可已撤销");
                    return native::lock_session();
                }
                self.last_key = self.last_input;
                self.interruptible = interruptible && self.configuration.keyboard_interrupts;
                self.interrupted = false;
                self.backspace = key == 8 && down;
                match (toggle, Lock::from_virtual_key(key)) {
                    (Some(wanted), Some(lock)) => {
                        if !down && self.injector.toggled(lock)? != wanted {
                            self.key(key, true)?;
                            self.key(key, false)?;
                        }
                    }
                    _ => self.key(key, down)?,
                }
            }
            Event::Button { button, down } => self.button(button, down)?,
            Event::Click { button } => {
                self.button(button, true)?;
                self.button(button, false)?;
            }
            Event::Relative { x, y } => {
                self.aimed = false;
                ensure!(
                    x.unsigned_abs() <= 1_000_000 && y.unsigned_abs() <= 1_000_000,
                    "相对位移过大"
                );
                self.injector.move_by(x, y)?;
            }
            Event::RelativeScaled { screen, x, y } => {
                self.aimed = false;
                let (width, height) = self.extent(self.geometry.screen(screen)?);
                self.injector
                    .move_by((width * x).round() as i32, (height * y).round() as i32)?;
            }
            Event::Absolute { screen, x, y } => self.absolute(screen, x, y)?,
            Event::Wheel { x, y } => {
                let (horizontal, delta) = if x.unsigned_abs() > y.unsigned_abs() {
                    (true, x)
                } else {
                    (false, y)
                };
                if delta != 0 {
                    self.wheel(horizontal, delta)?;
                }
            }
            Event::Text(text) => self.paste_text(&text, permitted)?,
            Event::Combination { down, keys } => {
                for item in keys {
                    ensure!(permitted(), "组合输入已取消");
                    match item {
                        KeyOrButton::Key(k) => self.key(k, down)?,
                        KeyOrButton::Button(b) => self.button(b, down)?,
                    }
                }
            }
            Event::Touch { kind, points } => self.touch(kind, &points)?,
        }
        Ok(())
    }
    pub fn tick(&mut self) -> Result<()> {
        self.synchronize(self.geometry.clone())?;
        if self.interruptible && !self.interrupted && !self.keys.is_empty() {
            let heartbeat = self.last_heartbeat.elapsed();
            let timeout = heartbeat > self.threshold && self.last_input <= self.last_heartbeat;
            if timeout
                || (self.backspace
                    && heartbeat > Duration::from_millis(100)
                    && self.last_key.elapsed() > Duration::from_millis(100))
            {
                self.neutral_keys(self.backspace || self.mobile)?;
                self.interrupted = true;
            }
        }
        self.injector.flush()
    }
    pub fn release(&mut self) -> Result<()> {
        let mut failure = self.neutral_keys(true).err();
        for (button, bit, _) in BUTTONS {
            if self.buttons & bit != 0
                && let Err(e) = self.button(button, false)
            {
                failure = Some(e);
            }
        }
        // 触摸屏上的触点也要抬起来，否则合成器会一直以为有一根手指按着。
        if let Err(e) = self.injector.release_touch() {
            failure = Some(e);
        }
        self.touch = None;
        self.touch_route = None;
        self.touch_screen_hint = None;
        self.touch_lease.take();
        self.wheel = [0; 2];
        self.interruptible = false;
        self.backspace = false;
        self.injector.forget_aim();
        self.aimed = false;
        let flushed = self.injector.flush();
        match failure {
            Some(e) => Err(e),
            None => flushed,
        }
    }
    /// Lift every held key. Unless `clear`, the keys stay parked as the
    /// controller's, so its eventual release is absorbed instead of repeated.
    fn neutral_keys(&mut self, clear: bool) -> Result<()> {
        let mut failure = None;
        for key in self.keys.clone() {
            match self.key(key, false) {
                Ok(()) if !clear => {
                    self.parked.insert(key);
                }
                Ok(()) => {}
                Err(e) => failure = Some(e),
            }
        }
        if clear {
            self.parked.clear();
        }
        match failure {
            Some(e) => Err(e),
            None => Ok(()),
        }
    }
}
impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.release();
    }
}

/// A position given as a fraction of `rect`, on a whole pixel inside it.
fn point_in(rect: Rect, x: f64, y: f64) -> (i32, i32) {
    let px = (f64::from(rect.left) + f64::from(rect.right - rect.left) * x)
        .round()
        .clamp(f64::from(rect.left), f64::from(rect.right - 1));
    let py = (f64::from(rect.top) + f64::from(rect.bottom - rect.top) * y)
        .round()
        .clamp(f64::from(rect.top), f64::from(rect.bottom - 1));
    (px as i32, py as i32)
}

/// 采集那边给出的 (left, top, width, height)，换成这里换算用的半开矩形。
fn window_rect((left, top, width, height): (i32, i32, u32, u32)) -> Result<Rect> {
    // 空矩形里没有可以落点的像素。
    ensure!(width > 0 && height > 0, "窗口尺寸无效");
    let edge = |origin: i32, extent: u32| {
        i32::try_from(extent)
            .ok()
            .and_then(|extent| origin.checked_add(extent))
            .ok_or_else(|| anyhow::anyhow!("窗口坐标溢出"))
    };
    Ok(Rect {
        left,
        top,
        right: edge(left, width)?,
        bottom: edge(top, height)?,
    })
}

/// 屏幕清单里是显示器的那一部分。目录把窗口级源（waypipe 风格）和显示器并
/// 列，但窗口不是输出：合成器布局里没有它，触摸屏也映射不到它上面。
fn monitors(screens: &[Screen], is_window: impl Fn(i32) -> bool) -> Vec<Screen> {
    screens
        .iter()
        .filter(|screen| !is_window(screen.id))
        .cloned()
        .collect()
}

/// The bounds an absolute pointer covers, when every screen has its place in
/// the compositor's layout and lies inside it; otherwise why not. All or
/// nothing: one screen aimed in X's rectangles beside others aimed in the
/// compositor's would mix two spaces.
fn compositor_bounds(
    screens: &[Screen],
    layout: Option<&crate::platform::CompositorLayout>,
) -> Result<native::Desktop, String> {
    let layout = layout.ok_or("合成器没有提供输出布局（xdg-output）")?;
    let bounds = native::layout_bounds(layout).ok_or("合成器布局里没有输出")?;
    if screens.is_empty() {
        return Err("没有可定位的屏幕".into());
    }
    let (left, top) = (i64::from(bounds.left), i64::from(bounds.top));
    let (right, bottom) = (
        left + i64::from(bounds.width),
        top + i64::from(bounds.height),
    );
    for screen in screens {
        let Some(logical) = screen.logical else {
            return Err(format!("屏幕 {} 在合成器布局里没有同名输出", screen.id));
        };
        let inside = logical.left < logical.right
            && logical.top < logical.bottom
            && i64::from(logical.left) >= left
            && i64::from(logical.top) >= top
            && i64::from(logical.right) <= right
            && i64::from(logical.bottom) <= bottom;
        if !inside {
            return Err(format!("屏幕 {} 的合成器位置不在布局范围内", screen.id));
        }
    }
    Ok(bounds)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::platform::{CompositorLayout, CompositorOutput};

    /// Runs against an isolated X server so no real desktop receives input.
    #[test]
    #[ignore = "run under xvfb-run with WAYLAND_DISPLAY and XDG_SESSION_TYPE unset"]
    fn committed_text_pastes_whole_without_character_keys() {
        use x11rb::connection::Connection;
        use x11rb::protocol::Event as XEvent;
        use x11rb::protocol::xproto::{
            ConnectionExt, CreateWindowAux, EventMask, InputFocus, WindowClass,
        };
        let (connection, screen) = x11rb::connect(None).unwrap();
        let root = connection.setup().roots[screen].root;
        let window = connection.generate_id().unwrap();
        connection
            .create_window(
                x11rb::COPY_DEPTH_FROM_PARENT,
                window,
                root,
                0,
                0,
                200,
                100,
                0,
                WindowClass::INPUT_OUTPUT,
                0,
                &CreateWindowAux::new().event_mask(EventMask::KEY_PRESS | EventMask::KEY_RELEASE),
            )
            .unwrap()
            .check()
            .unwrap();
        connection.map_window(window).unwrap().check().unwrap();
        let atom = |name: &[u8]| {
            connection
                .intern_atom(false, name)
                .unwrap()
                .reply()
                .unwrap()
                .atom
        };
        let clipboard = atom(b"CLIPBOARD");
        let utf8 = atom(b"UTF8_STRING");
        let property = atom(b"UU_TEST_PASTE");
        let mut engine = Engine::new(false, super::super::wire::Policy::Mobile, 0, false).unwrap();
        engine.key(0xa2, true).unwrap();
        engine.key(0xa0, true).unwrap();
        engine.key(0x56, true).unwrap();
        connection
            .set_input_focus(InputFocus::PARENT, window, x11rb::CURRENT_TIME)
            .unwrap()
            .check()
            .unwrap();
        let samples = vec![
            "完整的中文输入，不能只剩最后一个字。".to_owned(),
            (0x4e00..0x4f00)
                .map(|c| char::from_u32(c).unwrap())
                .collect::<String>()
                + &"
中文😀 abc	END
"
                .repeat(800),
            "中".to_owned(),
            "文".to_owned(),
        ];
        let count = samples.len();
        let target = std::thread::spawn(move || {
            let mut received = Vec::new();
            let deadline = Instant::now() + Duration::from_secs(10);
            while received.len() < count {
                assert!(Instant::now() < deadline, "paste target timed out");
                match connection.poll_for_event().unwrap() {
                    Some(XEvent::KeyPress(event)) => {
                        // No character keys other than the paste shortcut may arrive.
                        assert!(
                            matches!(event.detail, 29 | 37 | 50 | 55 | 62 | 105),
                            "unexpected character key {}",
                            event.detail
                        );
                        if event.detail == 55 {
                            assert_ne!(u16::from(event.state) & 4, 0, "Ctrl is required");
                            assert_eq!(u16::from(event.state) & 1, 0, "peer Shift leaked");
                            connection
                                .convert_selection(
                                    window,
                                    clipboard,
                                    utf8,
                                    property,
                                    x11rb::CURRENT_TIME,
                                )
                                .unwrap()
                                .check()
                                .unwrap();
                        }
                    }
                    Some(XEvent::SelectionNotify(event)) => {
                        assert_ne!(
                            event.property, 0,
                            "clipboard was not published before paste"
                        );
                        let reply = connection
                            .get_property(true, window, property, utf8, 0, u32::MAX)
                            .unwrap()
                            .reply()
                            .unwrap();
                        received.push(String::from_utf8(reply.value).unwrap());
                    }
                    _ => std::thread::sleep(Duration::from_millis(1)),
                }
            }
            received
        });
        for sample in &samples {
            engine.paste_text(sample, &|| true).unwrap();
            assert!(engine.keys.contains(&0xa2));
            assert!(engine.keys.contains(&0xa0));
            assert!(!engine.keys.contains(&0x56));
        }
        engine.release().unwrap();
        assert_eq!(target.join().unwrap(), samples);
    }

    fn output(
        name: Option<&str>,
        left: i32,
        top: i32,
        width: u32,
        height: u32,
    ) -> CompositorOutput {
        CompositorOutput {
            name: name.map(Into::into),
            left,
            top,
            width,
            height,
            rotation: crate::platform::Rotation::default(),
        }
    }

    fn screen(id: i32, x11: (i32, i32, u32, u32), logical: Option<&CompositorOutput>) -> Screen {
        Screen {
            id,
            left: x11.0,
            top: x11.1,
            width: x11.2,
            height: x11.3,
            logical: logical.map(|o| Rect {
                left: o.left,
                top: o.top,
                right: o.left + o.width as i32,
                bottom: o.top + o.height as i32,
            }),
        }
    }

    /// The layout from the PR description: DP-3 at 1.6x rotated, DP-1 at 1.35x.
    fn scaled() -> CompositorLayout {
        CompositorLayout {
            outputs: vec![
                output(Some("DP-3"), 0, 0, 1000, 1600),
                output(Some("DP-1"), 1000, 0, 3793, 1600),
            ],
        }
    }

    #[test]
    fn a_single_unrotated_screen_takes_the_touchscreen() {
        let layout = CompositorLayout {
            outputs: vec![output(Some("DP-1"), 0, 0, 1920, 1080)],
        };
        let screens = vec![screen(0, (0, 0, 1920, 1080), layout.outputs.first())];
        let target = touch_target(Some(&layout), &screens, 0, false).unwrap();
        assert_eq!(target.output, "DP-1");
        assert_eq!(target.rotation, Rotation::Normal);
        assert_eq!(target.width, 1920.0);
        assert_eq!(target.map, TouchMap::FULL);
    }

    /// niri 以外的合成器：多屏、旋转、读不到布局，一律退回单指模拟。合成器怎么
    /// 把触摸屏认到哪块屏上各家都不一样，猜错就是「主控看着这块屏、点下去落在
    /// 另一块屏上」，比单指模拟更差（模拟走绝对指针，落点总是对的）。
    #[test]
    fn anything_but_a_single_unrotated_screen_falls_back_to_simulation() {
        let single = CompositorLayout {
            outputs: vec![output(Some("DP-1"), 0, 0, 1920, 1080)],
        };
        let one = vec![screen(0, (0, 0, 1920, 1080), single.outputs.first())];
        assert!(touch_target(None, &one, 0, false).is_err(), "没有布局");
        assert!(
            touch_target(Some(&scaled()), &one, 0, false).is_err(),
            "合成器两块屏"
        );

        let mut two = one.clone();
        two.push(screen(1, (0, 0, 1920, 1080), single.outputs.first()));
        assert!(
            touch_target(Some(&single), &two, 0, false).is_err(),
            "被控端两块屏"
        );

        let mut turned = single.clone();
        turned.outputs[0].rotation = Rotation::Rotate90;
        assert!(
            touch_target(Some(&turned), &one, 0, false).is_err(),
            "屏幕转过 90 度"
        );
    }

    /// 旋转只看合成器那块输出：屏幕的名字和 `wl_output` 的没配上时，屏幕这边看不出
    /// 输出转过，也照样退回模拟。
    #[test]
    fn a_rotated_output_falls_back_even_when_the_screen_is_unpaired() {
        let mut layout = CompositorLayout {
            outputs: vec![output(Some("DP-1"), 0, 0, 1080, 1920)],
        };
        layout.outputs[0].rotation = Rotation::Rotate90;
        let unpaired = vec![screen(0, (0, 0, 1080, 1920), None)];
        assert!(unpaired[0].logical.is_none());
        let error = touch_target(Some(&layout), &unpaired, 0, false).unwrap_err();
        assert!(error.contains("未配对"), "{error}");
    }

    /// niri 只有一个全局触摸映射：每块显示器各自解析到自己的输出，调用方再把
    /// 触摸设备临时映射过去。旋转按输出带进路由。
    #[test]
    fn niri_touch_resolves_each_screen_to_its_own_output() {
        let mut layout = scaled();
        layout.outputs[1].rotation = Rotation::Rotate90;
        let screens = [
            screen(1, (0, 0, 1600, 2560), layout.output("DP-3")),
            screen(2, (1000, 0, 5120, 2160), layout.output("DP-1")),
        ];
        let left = touch_target(Some(&layout), &screens, 1, true).unwrap();
        assert_eq!(left.output, "DP-3");
        assert_eq!(left.rotation, Rotation::Normal);
        let right = touch_target(Some(&layout), &screens, 2, true).unwrap();
        assert_eq!(right.output, "DP-1");
        assert_eq!(right.rotation, Rotation::Rotate90);
        assert_eq!(right.width, 5120.0);
        assert_eq!(right.map, TouchMap::FULL);
    }

    /// 窗口级源只占输出的一部分：客户端给的窗口内比例要平移、缩放成输出比例，
    /// 合成器才会把触点送到窗口上。
    #[test]
    fn a_window_maps_its_own_rectangle_onto_the_output() {
        // 1375,4 1358x1059 的窗口在 1200,0 1706x1066 的输出上（开发机真实值）。
        let map = TouchMap::of_window((1375, 4, 1358, 1059), (1200, 0, 1706, 1066)).unwrap();
        assert!((map.left - 175.0 / 1706.0).abs() < 1e-9);
        assert!((map.width - 1358.0 / 1706.0).abs() < 1e-9);
        let (x, y) = map.apply(0.0, 0.0);
        assert!((x - 175.0 / 1706.0).abs() < 1e-9);
        assert!((y - 4.0 / 1066.0).abs() < 1e-9);
        // 窗口中心落在输出上的同一个相对位置。
        let (cx, cy) = map.apply(0.5, 0.5);
        assert!((cx - (175.0 + 679.0) / 1706.0).abs() < 1e-9);
        assert!((cy - (4.0 + 529.5) / 1066.0).abs() < 1e-9);
        assert!(TouchMap::of_window((0, 0, 10, 10), (0, 0, 0, 10)).is_none());
    }

    #[test]
    fn the_niri_touch_section_is_read_from_the_last_input_block() {
        let tokens = kdl_tokens(
            r#"input {
                touch { map-to-output "DP-1" calibration-matrix 1 0 0 0 1 0 }
            }
            input {
                touch { off map-to-output "eDP-1" }
            }"#,
        );
        let touch = niri_touch_from_tokens(&tokens);
        assert!(touch.off);
        assert_eq!(touch.map_to_output.as_deref(), Some("eDP-1"));
        // 后一段整个替换了 touch，前一段的校准不会混进来。
        assert_eq!(touch.calibration, None);
    }

    #[test]
    fn the_niri_touch_section_keeps_calibration_and_ignores_unknown_children() {
        let tokens = kdl_tokens(
            r#"input {
                touch {
                    calibration-matrix 1.0 0.0 0.0 0.0 1.0 0.0
                    map-to-output "DP-1"
                }
            }"#,
        );
        let touch = niri_touch_from_tokens(&tokens);
        assert!(!touch.off);
        assert_eq!(touch.map_to_output.as_deref(), Some("DP-1"));
        assert_eq!(
            touch.calibration.as_deref(),
            Some(
                ["1.0", "0.0", "0.0", "0.0", "1.0", "0.0"]
                    .map(str::to_owned)
                    .as_slice()
            )
        );
    }

    #[test]
    fn every_screen_paired_aims_in_the_compositor_layout() {
        let layout = scaled();
        let screens = [
            screen(1, (0, 0, 1600, 2560), layout.output("DP-3")),
            screen(2, (1000, 0, 5120, 2160), layout.output("DP-1")),
        ];
        let bounds = compositor_bounds(&screens, Some(&layout)).unwrap();
        assert_eq!(
            bounds,
            native::Desktop {
                left: 0,
                top: 0,
                width: 4793,
                height: 1600
            }
        );
        // The rotated output is named by its logical rectangle alone: the
        // centre of DP-3 is the centre of 1000x1600, not of X's 1600x2560.
        let logical = screens[0].logical.unwrap();
        assert_eq!(point_in(logical, 0.5, 0.5), (500, 800));
        assert_eq!(point_in(logical, 1.0, 1.0), (999, 1599));
    }

    #[test]
    fn one_unpaired_screen_falls_back_for_all() {
        let layout = scaled();
        let screens = [
            screen(1, (0, 0, 1600, 2560), layout.output("DP-3")),
            screen(2, (1000, 0, 5120, 2160), None),
        ];
        let error = compositor_bounds(&screens, Some(&layout)).unwrap_err();
        assert!(error.contains("屏幕 2"), "{error}");
        assert!(compositor_bounds(&screens[..1], None).is_err());
        assert!(compositor_bounds(&[], Some(&layout)).is_err());
    }

    /// 目录里并列着窗口级源（它没有同名输出）时，显示器照样按合成器布局定位：
    /// 窗口不是输出，不参与逐屏配对。漏掉这一条，开着任何一个应用窗口的会话
    /// 里所有显示器都会退回相对移动。
    #[test]
    fn window_sources_do_not_veto_the_monitors() {
        let layout = scaled();
        let screens = [
            screen(1, (0, 0, 1600, 2560), layout.output("DP-3")),
            screen(2, (1000, 0, 5120, 2160), layout.output("DP-1")),
            // 一个窗口源：目录里没有原点，合成器布局里也没有它。
            screen(3, (0, 0, 1790, 1529), None),
        ];
        assert!(compositor_bounds(&screens, Some(&layout)).is_err());
        let only = monitors(&screens, |id| id == 3);
        assert_eq!(only.len(), 2);
        assert!(compositor_bounds(&only, Some(&layout)).is_ok());

        // 触摸屏那条路上「被控端只有一块屏」数的也是显示器。
        let single = CompositorLayout {
            outputs: vec![output(Some("DP-1"), 0, 0, 1920, 1080)],
        };
        let listed = [
            screen(0, (0, 0, 1920, 1080), single.outputs.first()),
            screen(3, (0, 0, 800, 600), None),
        ];
        // 触摸屏那条路上「被控端只有一块屏」数的也是显示器（调用方先滤掉
        // 窗口源）；窗口源本身没有输出可映射，触摸走单指模拟。
        let only_monitors = monitors(&listed, |id| id == 3);
        assert!(touch_target(Some(&single), &only_monitors, 0, false).is_ok());
    }

    #[test]
    fn a_window_rectangle_must_hold_a_pixel() {
        let rect = window_rect((1627, 1111, 1193, 1019)).unwrap();
        assert_eq!(
            rect,
            Rect {
                left: 1627,
                top: 1111,
                right: 2820,
                bottom: 2130
            }
        );
        // 四角都落在窗口自己的矩形里，右下角是最后一个像素而不是它外面那个。
        assert_eq!(point_in(rect, 0.0, 0.0), (1627, 1111));
        assert_eq!(point_in(rect, 1.0, 1.0), (2819, 2129));
        assert!(window_rect((0, 0, 0, 10)).is_err(), "空矩形");
        assert!(window_rect((i32::MAX - 5, 0, 10, 10)).is_err(), "溢出");
    }

    #[test]
    fn a_screen_outside_the_layout_is_refused() {
        let layout = scaled();
        let stray = output(Some("HDMI-1"), 5000, 0, 1920, 1080);
        let screens = [
            screen(1, (0, 0, 1600, 2560), layout.output("DP-3")),
            screen(2, (5000, 0, 1920, 1080), Some(&stray)),
        ];
        assert!(compositor_bounds(&screens, Some(&layout)).is_err());
    }

    #[test]
    fn unnamed_outputs_and_negative_origins_widen_the_bounds() {
        let layout = CompositorLayout {
            outputs: vec![
                output(Some("eDP-1"), 0, 0, 1280, 800),
                output(None, -1920, -200, 1920, 1080),
            ],
        };
        let screens = [screen(1, (0, 0, 2560, 1600), layout.output("eDP-1"))];
        assert_eq!(
            compositor_bounds(&screens, Some(&layout)).unwrap(),
            native::Desktop {
                left: -1920,
                top: -200,
                width: 3200,
                height: 1080
            }
        );
    }

    /// 需要 niri 会话和可写的 `/dev/uinput`：真的加载一份临时配置把触摸映射
    /// 到另一块输出，再恢复原配置。会短暂重载 niri 配置。
    #[test]
    #[ignore = "reloads the live niri config twice and restores it"]
    fn live_touch_mapping_moves_and_restores() {
        let mut engine =
            Engine::new(false, super::super::wire::Policy::Mobile, 0, false).expect("engine");
        engine
            .ensure_touch_mapping("eDP-1")
            .expect("map touch to eDP-1");
        assert!(
            engine
                .touch_lease
                .as_ref()
                .is_some_and(|lease| lease.maps_to("eDP-1"))
        );
        engine
            .ensure_touch_mapping("DP-1")
            .expect("map touch to DP-1");
        assert!(
            engine
                .touch_lease
                .as_ref()
                .is_some_and(|lease| lease.maps_to("DP-1"))
        );
        engine.release().expect("release restores the original config");
        assert!(engine.touch_lease.is_none());
    }
}
