//! Waypipe 风格的窗口级采集（X11）：一个应用窗口就是一块"屏幕"。
//!
//! Xorg 会话下用 XComposite 的 NameWindowPixmap 直接读窗口自己的缓冲区：
//! 不含窗口管理器装饰、不含遮挡在它上面的其他窗口，窗口移动时不重采桌面。
//! 窗口作为普通 `Screen` 条目进入屏幕目录，现有的按屏视频轨、切屏与输入
//! 换算原样复用，主控与 PICO 端不需要任何协议改动。
//!
//! Wayland 会话没有免授权的窗口抓取协议（wlr-screencopy 只有整屏），
//! 这里明确不支持；XDG 门户的窗口投射留作后续路径。
use super::{Screen, SourceGone, WINDOW_IDENTITY_PREFIX};
use anyhow::{Context, Result, bail, ensure};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use x11rb::connection::Connection as _;
use x11rb::protocol::composite::{self, ConnectionExt as _};
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xfixes::ConnectionExt as _;
use x11rb::protocol::xproto::{
    self, ConnectionExt as _, Pixmap, Window,
};
use x11rb::rust_connection::RustConnection;

/// 目录里最多同时列出的窗口数；输入几何整体上限是 32 块屏。
const WINDOW_LIMIT: usize = 16;
/// 过滤掉通知气泡之类的极小窗口。
const MIN_SIZE: u32 = 32;

/// 一个可采集的顶层窗口。
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct WindowInfo {
    pub xid: u32,
    pub title: String,
    pub class: String,
    pub left: i32,
    pub top: i32,
    pub width: u32,
    pub height: u32,
}

pub(crate) fn identity(xid: u32) -> String {
    format!("{WINDOW_IDENTITY_PREFIX}{xid}")
}
pub(crate) fn is_window_identity(identity: &str) -> bool {
    identity.starts_with(WINDOW_IDENTITY_PREFIX)
}
fn parse_xid(identity: &str) -> Result<u32> {
    identity
        .strip_prefix(WINDOW_IDENTITY_PREFIX)
        .context("窗口标识缺少前缀")?
        .parse::<u32>()
        .context("窗口标识不是有效的 X 窗口 ID")
}

/// 进程内的 id ↔ xid 对照；目录刷新时登记，输入换算按 id 反查。
fn reverse() -> &'static Mutex<HashMap<i32, u32>> {
    static REVERSE: OnceLock<Mutex<HashMap<i32, u32>>> = OnceLock::new();
    REVERSE.get_or_init(Default::default)
}
/// 每个窗口最近一次看到的桌面矩形；采集循环每帧更新，输入注入据此换算，
/// 不必为一次指针移动重连 X。
fn geometry_cache() -> &'static Mutex<HashMap<u32, (i32, i32, u32, u32)>> {
    static CACHE: OnceLock<Mutex<HashMap<u32, (i32, i32, u32, u32)>>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}
fn remember(info: &WindowInfo, id: i32) {
    lock(reverse()).insert(id, info.xid);
    lock(geometry_cache()).insert(info.xid, (info.left, info.top, info.width, info.height));
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}

fn connect() -> Result<(RustConnection, usize)> {
    ensure!(
        !super::wayland_session(),
        "Wayland 会话没有免授权的窗口抓取协议"
    );
    crate::platform::linux::x11_connection::connect().context("连接 X11 显示失败")
}

/// 查询一个窗口当前在桌面上的矩形（客户区内容，不含 WM 装饰）。
fn geometry(
    connection: &RustConnection,
    root: Window,
    window: Window,
) -> Result<(i32, i32, u32, u32)> {
    let reply = match connection.get_geometry(window)?.reply() {
        Ok(reply) => reply,
        // 窗口已销毁或连接失效：走"源已消失"的既有恢复路径。
        Err(_) => return Err(SourceGone.into()),
    };
    let translate = match connection.translate_coordinates(window, root, 0, 0)?.reply() {
        Ok(reply) => reply,
        Err(_) => return Err(SourceGone.into()),
    };
    Ok((
        i32::from(translate.dst_x),
        i32::from(translate.dst_y),
        u32::from(reply.width),
        u32::from(reply.height),
    ))
}

fn intern_atom(connection: &RustConnection, name: &str) -> Option<u32> {
    connection
        .intern_atom(true, name.as_bytes())
        .ok()?
        .reply()
        .ok()
        .filter(|reply| reply.atom != x11rb::NONE)
        .map(|reply| reply.atom)
}

/// 先 EWMH 的 UTF-8 标题，再回退经典 WM_NAME；读不到就交给调用方兜底。
fn property_string(connection: &RustConnection, window: Window, atom: u32) -> Option<String> {
    let reply = connection
        .get_property(false, window, atom, xproto::AtomEnum::ANY, 0, 8192)
        .ok()?
        .reply()
        .ok()?;
    let text = String::from_utf8_lossy(&reply.value);
    let cleaned: String = text.chars().filter(|c| !c.is_control()).collect();
    let trimmed = cleaned.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

fn describe(connection: &RustConnection, window: Window) -> (String, String) {
    let net_name = intern_atom(connection, "_NET_WM_NAME");
    let wm_name = intern_atom(connection, "WM_NAME");
    let wm_class = intern_atom(connection, "WM_CLASS");
    let title = net_name
        .and_then(|atom| property_string(connection, window, atom))
        .or_else(|| wm_name.and_then(|atom| property_string(connection, window, atom)))
        .unwrap_or_default();
    let class = wm_class
        .and_then(|atom| property_string(connection, window, atom))
        .map(|value| {
            // WM_CLASS 是 "实例\0类别\0"；后一段更像应用名。
            value
                .split('\0')
                .last()
                .unwrap_or(&value)
                .trim()
                .to_owned()
        })
        .unwrap_or_default();
    (title, class)
}

fn own_pid(connection: &RustConnection, window: Window) -> Option<u32> {
    let atom = intern_atom(connection, "_NET_WM_PID")?;
    let reply = connection
        .get_property(false, window, atom, xproto::AtomEnum::CARDINAL, 0, 1)
        .ok()?
        .reply()
        .ok()?;
    let value = reply.value.as_slice().chunks_exact(4).next()?;
    Some(u32::from_le_bytes(value.try_into().ok()?))
}

/// 枚举当前可见的顶层窗口，栈顶优先。
pub(crate) fn list() -> Result<Vec<WindowInfo>> {
    let (connection, index) = connect()?;
    let setup = connection.setup();
    let root = setup.roots.get(index).context("X11 缺少根窗口")?.root;
    let tree = connection
        .query_tree(root)?
        .reply()
        .context("读取 X 窗口树失败")?;
    let self_pid = std::process::id();
    let mut found = Vec::new();
    // query_tree 自底向上；反过来就是栈顶优先。
    for window in tree.children.iter().rev() {
        if found.len() >= WINDOW_LIMIT {
            break;
        }
        let attr = match connection.get_window_attributes(*window)?.reply() {
            Ok(attr) => attr,
            Err(_) => continue,
        };
        if attr.map_state != xproto::MapState::VIEWABLE || attr.override_redirect {
            continue;
        }
        if own_pid(&connection, *window) == Some(self_pid) {
            continue;
        }
        let (left, top, width, height) = match geometry(&connection, root, *window) {
            Ok(rect) => rect,
            Err(_) => continue,
        };
        if width < MIN_SIZE || height < MIN_SIZE {
            continue;
        }
        let (title, class) = describe(&connection, *window);
        if title.is_empty() && class.is_empty() {
            continue;
        }
        if class.contains("openuuyc") || class.contains("OpenUUYC") {
            continue;
        }
        found.push(WindowInfo {
            xid: *window,
            title: if title.is_empty() {
                format!("窗口 {}", *window)
            } else {
                title
            },
            class,
            left,
            top,
            width,
            height,
        });
    }
    Ok(found)
}

/// 窗口清单按协议目录的 `Screen` 形态交给上层；identity 决定协议 ID，
/// 同一窗口在本进程内保持稳定。
pub(crate) fn screens() -> Result<Vec<Screen>> {
    let mut screens = Vec::new();
    for info in list()? {
        let id = match crate::media::capture::source_id(&identity(info.xid)) {
            Ok(id) => id,
            Err(_) => break,
        };
        remember(&info, id);
        screens.push(Screen {
            id,
            device_name: format!("win-{}", info.xid),
            display_name: info.title,
            width: info.width,
            height: info.height,
            left: info.left,
            top: info.top,
            primary: false,
            fps: 60,
            dpi_scale: None,
            hdr: false,
            adapter: 0,
            render_adapter: None,
            identity: Some(identity(info.xid)),
        });
    }
    Ok(screens)
}

/// 重新查询一个窗口源：几何与标题可能都变了；窗口没了报 SourceGone。
pub(crate) fn refresh(selected: &Screen) -> Result<Screen> {
    let identity = selected
        .identity
        .as_deref()
        .context("窗口源缺少身份标识")?;
    let xid = parse_xid(identity)?;
    let (connection, index) = connect()?;
    let root = connection
        .setup()
        .roots
        .get(index)
        .context("X11 缺少根窗口")?
        .root;
    let window = Window::from(xid);
    let (left, top, width, height) = geometry(&connection, root, window)?;
    lock(geometry_cache()).insert(xid, (left, top, width, height));
    let title = {
        let (title, _) = describe(&connection, window);
        (!title.is_empty()).then_some(title)
    };
    Ok(Screen {
        id: selected.id,
        device_name: selected.device_name.clone(),
        display_name: title.unwrap_or_else(|| selected.display_name.clone()),
        width,
        height,
        left,
        top,
        primary: false,
        fps: selected.fps,
        dpi_scale: None,
        hdr: false,
        adapter: 0,
        render_adapter: None,
        identity: selected.identity.clone(),
    })
}

/// 窗口源当前在桌面上的矩形（输入注入换算用）；非窗口 ID 返回 None。
pub(crate) fn live_rect(id: i32) -> Option<(i32, i32, u32, u32)> {
    let xid = *lock(reverse()).get(&id)?;
    let rect = *lock(geometry_cache()).get(&xid)?;
    // 保证宽高加进原点不会溢出，调用方可以直接当半开矩形用。
    let (left, top, width, height) = rect;
    left.checked_add(i32::try_from(width).ok()?)?;
    top.checked_add(i32::try_from(height).ok()?)?;
    Some(rect)
}

enum Reader {
    Shm(super::x11::Segment),
    Plain,
}

/// 一个窗口的持续采集。窗口缩放后 X 会换一块 backing pixmap，
/// 旧的 Named pixmap 随之失效，因此每次抓取先对尺寸、失败后重命名一次。
pub(crate) struct Grab {
    connection: RustConnection,
    root: Window,
    window: Window,
    pixmap: Option<Pixmap>,
    named_size: (u32, u32),
    reader: Reader,
    plain: Vec<u8>,
    length: usize,
    cursor_available: bool,
    xid: u32,
}

impl Grab {
    pub(crate) fn open(screen: &Screen) -> Result<Self> {
        let identity = screen.identity.as_deref().context("窗口源缺少身份标识")?;
        let xid = parse_xid(identity)?;
        let (connection, index) = connect()?;
        let root = connection
            .setup()
            .roots
            .get(index)
            .context("X11 缺少根窗口")?
            .root;
        // NameWindowPixmap 要求先初始化 Composite 扩展版本。
        connection
            .composite_query_version(0, 4)?
            .reply()
            .context("初始化 XComposite 失败")?;
        // NameWindowPixmap 只认已被重定向的窗口（服务器端 GetCompWindow
        // 为空即报 Match）：没有合成器的 Xorg 会话里窗口默认不被重定向，
        // 所以这里自己以 Automatic 方式重定向一次；服务器按客户端引用
        // 计数，与已有合成器的 Manual 重定向不冲突，Drop 时撤销。
        connection
            .composite_redirect_window(Window::from(xid), composite::Redirect::AUTOMATIC)?
            .check()
            .context("重定向窗口失败（Composite 重定向被拒绝）")?;
        let cursor_available = connection
            .xfixes_query_version(4, 0)
            .ok()
            .and_then(|cookie| cookie.reply().ok())
            .is_some();
        let mut grab = Self {
            connection,
            root,
            window: Window::from(xid),
            pixmap: None,
            named_size: (0, 0),
            reader: Reader::Plain,
            plain: Vec::new(),
            length: 0,
            cursor_available,
            xid,
        };
        grab.name_pixmap().context("读取窗口缓冲失败")?;
        Ok(grab)
    }

    pub(crate) fn name(&self) -> &'static str {
        "X11 窗口"
    }

    /// 让 `pixmap` 指向窗口当前的 backing pixmap。
    fn name_pixmap(&mut self) -> Result<()> {
        let pixmap = self.connection.generate_id()?;
        self.connection
            .composite_name_window_pixmap(self.window, pixmap)?
            .check()
            .context("命名窗口缓冲失败（窗口可能已关闭）")?;
        let (left, top, width, height) = geometry(&self.connection, self.root, self.window)?;
        lock(geometry_cache()).insert(self.xid, (left, top, width, height));
        let size = (width, height);
        let needed = width as usize * height as usize * 4;
        let enough = matches!(&self.reader, Reader::Shm(segment) if segment.capacity() >= needed);
        if !enough {
            match super::x11::Segment::new(&self.connection, needed) {
                Ok(segment) => self.reader = Reader::Shm(segment),
                // 共享内存不可用时退回普通 GetImage，路径与整屏采集一致。
                Err(error) => {
                    tracing::debug!(error = %format!("{error:#}"), "MIT-SHM 不可用，改用普通 GetImage");
                    self.reader = Reader::Plain;
                }
            }
        }
        self.pixmap = Some(pixmap);
        self.named_size = size;
        self.length = 0;
        Ok(())
    }

    /// 读取窗口当前画面。窗口在这之后关闭时，下一次调用报 SourceGone。
    pub(crate) fn grab(&mut self, cursor: bool) -> Result<(u32, u32)> {
        if let Err(error) = self.grab_once(cursor) {
            // 窗口缩放会让旧的 Named pixmap 失效；重命名一次再试。
            tracing::debug!(error = %format!("{error:#}"), "窗口抓取失败，重新命名窗口缓冲");
            self.name_pixmap()?;
            self.grab_once(cursor)?;
        }
        Ok(self.named_size)
    }

    fn grab_once(&mut self, cursor: bool) -> Result<()> {
        let Some(pixmap) = self.pixmap else {
            bail!("窗口缓冲尚未就绪");
        };
        let (width, height) = self.named_size;
        let (width, height) = (
            u16::try_from(width).context("窗口宽度超出 X11 范围")?,
            u16::try_from(height).context("窗口高度超出 X11 范围")?,
        );
        let length = usize::from(width) * usize::from(height) * 4;
        self.length = 0;
        match &self.reader {
            Reader::Shm(segment) => {
                ensure!(segment.capacity() >= length, "共享内存小于窗口尺寸");
                self.connection
                    .shm_get_image(
                        pixmap,
                        0,
                        0,
                        width,
                        height,
                        !0,
                        xproto::ImageFormat::Z_PIXMAP.into(),
                        segment.id(),
                        0,
                    )?
                    .reply()
                    .context("读取窗口画面失败")?;
            }
            Reader::Plain => {
                let reply = self
                    .connection
                    .get_image(xproto::ImageFormat::Z_PIXMAP, pixmap, 0, 0, width, height, !0)?
                    .reply()
                    .context("读取窗口画面失败")?;
                ensure!(reply.data.len() >= length, "窗口画面数据不完整");
                self.plain = reply.data;
            }
        }
        self.length = length;
        if cursor && self.cursor_available {
            let _ = self.draw_cursor();
        }
        Ok(())
    }

    pub(crate) fn pixels(&self) -> &[u8] {
        match &self.reader {
            Reader::Shm(segment) => &segment.pixels()[..self.length],
            Reader::Plain => &self.plain[..self.length],
        }
    }

    /// XFixes 光标画进画面；坐标换成窗口内偏移，与整屏采集同一套算法。
    fn draw_cursor(&mut self) -> Result<()> {
        let image = self.connection.xfixes_get_cursor_image()?.reply()?;
        let (width, height) = (i32::from(image.width), i32::from(image.height));
        ensure!(
            image.cursor_image.len() >= (width * height) as usize,
            "光标图像不完整"
        );
        let Some((left, top, screen_width, screen_height)) = lock(geometry_cache())
            .get(&self.xid)
            .map(|(left, top, width, height)| (*left, *top, *width as i32, *height as i32))
        else {
            bail!("窗口几何未知");
        };
        let origin_x = i32::from(image.x) - i32::from(image.xhot) - left;
        let origin_y = i32::from(image.y) - i32::from(image.yhot) - top;
        let pixels = self.pixels_mut();
        for row in 0..height {
            let y = origin_y + row;
            if !(0..screen_height).contains(&y) {
                continue;
            }
            for column in 0..width {
                let x = origin_x + column;
                if !(0..screen_width).contains(&x) {
                    continue;
                }
                let argb = image.cursor_image[(row * width + column) as usize];
                let alpha = argb >> 24;
                if alpha == 0 {
                    continue;
                }
                let offset = ((y * screen_width + x) * 4) as usize;
                if offset + 4 > pixels.len() {
                    continue;
                }
                let pixel = &mut pixels[offset..offset + 4];
                let inverse = 255 - alpha;
                for (channel, shift) in [(0usize, 0u32), (1, 8), (2, 16)] {
                    let over = (argb >> shift) & 0xff;
                    let under = u32::from(pixel[channel]);
                    pixel[channel] = (over + (under * inverse + 127) / 255).min(255) as u8;
                }
            }
        }
        Ok(())
    }

    fn pixels_mut(&mut self) -> &mut [u8] {
        match &mut self.reader {
            Reader::Shm(segment) => segment.pixels_mut(),
            Reader::Plain => &mut self.plain,
        }
    }
}

impl Drop for Grab {
    fn drop(&mut self) {
        // 撤销本客户端的重定向（update 必须与重定向时一致）。合成器用
        // RedirectSubwindows 整体重定向时这里会报错，忽略即可：那种场景下
        // 本来就不需要我们自己的重定向。
        let _ = self
            .connection
            .composite_unredirect_window(self.window, composite::Redirect::AUTOMATIC);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_roundtrip_and_prefix() {
        assert!(is_window_identity(&identity(0x1a2b3c)));
        assert!(!is_window_identity("x11/eDP-1"));
        assert_eq!(parse_xid(&identity(0x1a2b3c)).unwrap(), 0x1a2b3c);
        assert!(parse_xid("x11-window/not-a-number").is_err());
        assert!(parse_xid("x11/eDP-1").is_err());
    }

    #[test]
    fn window_limit_keeps_input_geometry_valid() {
        // 输入几何把目录整体限制在 32 块屏之内；窗口清单必须留出余量。
        assert!(WINDOW_LIMIT < 32);
    }

    #[tokio::test]
    #[ignore = "needs a running X server with mapped windows"]
    async fn enumerates_and_grabs_a_window() {
        let screens = screens().unwrap();
        eprintln!("window screens: {screens:?}");
        assert!(!screens.is_empty());
        let mut grab = Grab::open(&screens[0]).unwrap();
        let (width, height) = grab.grab(false).unwrap();
        assert_eq!(grab.pixels().len(), (width * height * 4) as usize);
    }
}
