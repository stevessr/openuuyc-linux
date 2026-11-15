//! Wayland 的窗口级采集：绑定 ext_foreign_toplevel_list_v1 枚举顶层窗口，
//! ext_foreign_toplevel_image_capture_source_manager_v1 加
//! ext_image_copy_capture_v1 按窗口取帧。合成器有 damage 才推帧，静止的
//! 窗口不产生重复画面；窗口内容缓冲以它自己的实际尺寸为准，窗口缩放后下一
//! 帧即跟随。
//!
//! 协议本身不给窗口位置：niri 会话上再问它的 IPC 套接字，把窗口对到它所在
//! 输出上的全局逻辑矩形（工作区视图位置 + 输出原点），输入注入据此换算。
//! niri 把自己的窗口 id 原样当作 ext_foreign_toplevel 的 identifier，所以两
//! 边按 id 精确对上，同名窗口也不会认错。对不上的窗口（不在可见工作区、或
//! 者滚出了视口）明确拒绝注入，而不是把点击投到别处。其他合成器没有实测过
//! 的 IPC，不写进来——那种会话里窗口可以看（采集照常），但输入会明确报错。
//!
//! 窗口缓冲是预乘 ARGB：透明区域按预乘后的颜色输出（不反预乘），边缘可能
//! 偏深；不透明的普通窗口不受影响。
use super::{Screen, SourceGone, WL_WINDOW_PREFIX};
use anyhow::{Context, Result, bail, ensure};
use std::collections::{HashMap, HashSet};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use wayland_client::backend::WaylandError;
use wayland_client::protocol::{wl_buffer, wl_registry, wl_shm, wl_shm_pool};
use wayland_client::{Connection, Dispatch, EventQueue, QueueHandle};
use wayland_protocols::ext::foreign_toplevel_list::v1::client::{
    ext_foreign_toplevel_handle_v1, ext_foreign_toplevel_list_v1,
};
use wayland_protocols::ext::image_capture_source::v1::client::{
    ext_foreign_toplevel_image_capture_source_manager_v1, ext_image_capture_source_v1,
};
use wayland_protocols::ext::image_copy_capture::v1::client::{
    ext_image_copy_capture_frame_v1, ext_image_copy_capture_manager_v1,
    ext_image_copy_capture_session_v1,
};
use wayland_protocols::ext::image_copy_capture::v1::client::ext_image_copy_capture_manager_v1::Options;

/// 目录里最多同时列出的窗口数；输入几何整体上限是 32 块屏。
const WINDOW_LIMIT: usize = 16;
/// 会话建立/枚举的常规往返上限。
const TIMEOUT: Duration = Duration::from_secs(5);

/// 一个顶层窗口在协议里看到的身份与内容。
#[derive(Clone, Debug, Default)]
struct Toplevel {
    identifier: String,
    title: String,
    app_id: String,
}

pub(crate) fn identity(identifier: &str) -> String {
    format!("{WL_WINDOW_PREFIX}{identifier}")
}

fn parse_identity(screen_identity: &str) -> Result<&str> {
    screen_identity
        .strip_prefix(WL_WINDOW_PREFIX)
        .context("窗口标识缺少前缀")
}

/// id → identifier 反查表（输入注入按 id 找窗口）。
fn reverse() -> &'static Mutex<HashMap<i32, String>> {
    static REVERSE: OnceLock<Mutex<HashMap<i32, String>>> = OnceLock::new();
    REVERSE.get_or_init(Default::default)
}

/// 每个窗口最近一次看到的缓冲尺寸；目录条目与输入几何都用它。
fn sizes() -> &'static Mutex<HashMap<String, (u32, u32)>> {
    static SIZES: OnceLock<Mutex<HashMap<String, (u32, u32)>>> = OnceLock::new();
    SIZES.get_or_init(Default::default)
}

/// 一个窗口此刻在合成器逻辑空间里的位置，输入注入用它换算落点。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Placement {
    /// 窗口的全局逻辑矩形：left, top, width, height。
    pub rect: (i32, i32, u32, u32),
    /// 它所在输出的全局逻辑矩形。窗口伸到这块输出之外的部位不在屏幕上，
    /// 往那里投的点击只会落到别处。
    pub output: (i32, i32, u32, u32),
}

/// 一次 niri IPC 问到的全部窗口位置，按 niri 的窗口 id。
#[derive(Debug, Default, PartialEq, Eq)]
struct Placements {
    /// niri 报出来的每个窗口，不管它在不在可见工作区；只为把「窗口不在屏幕
    /// 上」和「这个合成器对不上号」两种定位失败分开说。
    reported: HashSet<u64>,
    /// 在可见工作区上、并且 niri 给了位置的那部分。
    visible: HashMap<u64, Placement>,
}

/// 窗口位置隔多久向 niri 重问一次。摆着不动的窗口不必每帧都问，滚动的视图
/// 要跟得上：一次点击把窗口聚焦之后 niri 会把它滚进视口，下一次点击就得按
/// 新位置算。
const PLACEMENT_TTL: Duration = Duration::from_millis(100);
/// niri IPC 套接字的读写上限。合成器答一次是亚毫秒的事，它卡住时别把采集
/// 线程拖住。
const IPC_TIMEOUT: Duration = Duration::from_millis(200);

/// 最近一次问到的窗口位置和它的时刻。只由采集与目录这边刷新（见
/// [`refresh_placements`]）；输入注入只读，不在自己的线程里等合成器。
fn placements() -> &'static Mutex<Option<(Instant, Arc<Placements>)>> {
    static PLACEMENTS: OnceLock<Mutex<Option<(Instant, Arc<Placements>)>>> = OnceLock::new();
    PLACEMENTS.get_or_init(Default::default)
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}

/// 顶层列表与句柄事件的共同填充逻辑；done 之后入列，匹配中的句柄记下来。
#[derive(Default)]
struct Fill {
    /// 只在找特定窗口时设置；匹配中的句柄存进 found。
    finding: Option<String>,
    found: Option<ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1>,
    toplevels: Vec<Toplevel>,
    pending: Option<Toplevel>,
}

impl Fill {
    fn handle_event(
        &mut self,
        handle: &ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
        event: ext_foreign_toplevel_handle_v1::Event,
    ) {
        match event {
            ext_foreign_toplevel_handle_v1::Event::Identifier { identifier } => {
                // 找特定窗口时，匹配中的句柄记下来；别的句柄立即释放。
                let matched = self
                    .finding
                    .as_deref()
                    .is_some_and(|want| want == identifier);
                if matched {
                    self.found = Some(handle.clone());
                } else if self.finding.is_some() {
                    handle.destroy();
                }
                if let Some(pending) = self.pending.as_mut() {
                    pending.identifier = identifier;
                }
            }
            ext_foreign_toplevel_handle_v1::Event::Title { title } => {
                if let Some(pending) = self.pending.as_mut() {
                    pending.title = title;
                }
            }
            ext_foreign_toplevel_handle_v1::Event::AppId { app_id } => {
                if let Some(pending) = self.pending.as_mut() {
                    pending.app_id = app_id;
                }
            }
            ext_foreign_toplevel_handle_v1::Event::Done => {
                if let Some(entry) = self.pending.take() {
                    if !entry.identifier.is_empty() {
                        self.toplevels.push(entry);
                    }
                }
            }
            // 窗口在枚举中途关闭：丢弃这个未完成的条目，等下一个 toplevel。
            ext_foreign_toplevel_handle_v1::Event::Closed => {
                handle.destroy();
                self.pending = None;
            }
            _ => {}
        }
    }
}

// ---------------------------------------------------------------------------
// 枚举：一个连接收完顶层列表就断开。
// ---------------------------------------------------------------------------

#[derive(Default)]
struct ListState {
    fill: Fill,
}

impl Dispatch<wl_registry::WlRegistry, ()> for ListState {
    fn event(
        _: &mut Self,
        registry: &wl_registry::WlRegistry,
        event: wl_registry::Event,
        _: &(),
        _: &Connection,
        handle: &QueueHandle<Self>,
    ) {
        if let wl_registry::Event::Global { name, interface, version } = event {
            if interface == "ext_foreign_toplevel_list_v1" {
                registry.bind::<ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, _, _>(
                    name,
                    version.min(1),
                    handle,
                    (),
                );
            }
        }
    }
}

impl Dispatch<ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, ()> for ListState {
    fn event(
        state: &mut Self,
        _: &ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1,
        event: ext_foreign_toplevel_list_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let ext_foreign_toplevel_list_v1::Event::Toplevel { toplevel } = event {
            // 句柄的事件随后到达；这里只开一个待填充条目。
            let _ = toplevel;
            state.fill.pending = Some(Toplevel::default());
        }
    }

    // toplevel 事件创建新的句柄对象：声明子对象类型与用户数据，
    // 否则派发时走默认实现直接 panic。
    wayland_client::event_created_child!(ListState, ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, [
        ext_foreign_toplevel_list_v1::EVT_TOPLEVEL_OPCODE => (
            ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
            ()
        )
    ]);
}

impl Dispatch<ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1, ()> for ListState {
    fn event(
        state: &mut Self,
        handle: &ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
        event: ext_foreign_toplevel_handle_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        state.fill.handle_event(handle, event);
    }
}

/// 枚举当前的顶层窗口；顺序就是合成器给的顺序。
fn list() -> Result<Vec<Toplevel>> {
    let connection = Connection::connect_to_env().context("没有可用的 Wayland 会话")?;
    let mut queue = connection.new_event_queue::<ListState>();
    let handle = queue.handle();
    connection.display().get_registry(&handle, ());
    let mut state = ListState::default();
    // 两次往返：第一次拿全局对象并绑定列表，第二次收完窗口事件。
    queue.roundtrip(&mut state).context("读取 Wayland 全局对象失败")?;
    queue.roundtrip(&mut state).context("读取 Wayland 顶层窗口列表失败")?;
    Ok(state.fill.toplevels)
}

// ---------------------------------------------------------------------------
// 采集连接：会话的 buffer_size/shm_format/done 事件都进这个状态。
// ---------------------------------------------------------------------------

#[derive(Default)]
struct State {
    connection: Option<Connection>,
    shm: Option<wl_shm::WlShm>,
    toplevel_manager: Option<
        ext_foreign_toplevel_image_capture_source_manager_v1::ExtForeignToplevelImageCaptureSourceManagerV1,
    >,
    capture_manager:
        Option<ext_image_copy_capture_manager_v1::ExtImageCopyCaptureManagerV1>,
    source: Option<ext_image_capture_source_v1::ExtImageCaptureSourceV1>,
    session: Option<ext_image_copy_capture_session_v1::ExtImageCopyCaptureSessionV1>,
    frame: Option<ext_image_copy_capture_frame_v1::ExtImageCopyCaptureFrameV1>,
    /// 会话说缓冲应该长什么样（宽、高）。
    wanted: Option<(u32, u32)>,
    formats: Vec<wl_shm::Format>,
    session_done: bool,
    ready: bool,
    stopped: bool,
    error: Option<String>,
    /// 顶层列表的填充状态；找句柄与枚举共用同一条派发路径。
    fill: Fill,
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
        if let wl_registry::Event::Global { name, interface, version } = event {
            match interface.as_str() {
                "ext_foreign_toplevel_list_v1" => {
                    registry.bind::<ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, _, _>(
                        name,
                        version.min(1),
                        handle,
                        (),
                    );
                }
                "ext_foreign_toplevel_image_capture_source_manager_v1" => {
                    state.toplevel_manager = Some(
                        registry.bind::<ext_foreign_toplevel_image_capture_source_manager_v1::ExtForeignToplevelImageCaptureSourceManagerV1, _, _>(
                            name,
                            version.min(1),
                            handle,
                            (),
                        ),
                    );
                }
                "ext_image_copy_capture_manager_v1" => {
                    state.capture_manager = Some(
                        registry.bind::<ext_image_copy_capture_manager_v1::ExtImageCopyCaptureManagerV1, _, _>(
                            name,
                            version.min(1),
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
                _ => {}
            }
        }
    }
}

impl Dispatch<ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, ()> for State {
    fn event(
        state: &mut Self,
        _: &ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1,
        event: ext_foreign_toplevel_list_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let ext_foreign_toplevel_list_v1::Event::Toplevel { toplevel } = event {
            let _ = toplevel;
            state.fill.pending = Some(Toplevel::default());
        }
    }

    // toplevel 事件创建新的句柄对象：声明子对象类型与用户数据，
    // 否则派发时走默认实现直接 panic。
    wayland_client::event_created_child!(State, ext_foreign_toplevel_list_v1::ExtForeignToplevelListV1, [
        ext_foreign_toplevel_list_v1::EVT_TOPLEVEL_OPCODE => (
            ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
            ()
        )
    ]);
}

impl Dispatch<ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1, ()> for State {
    fn event(
        state: &mut Self,
        handle: &ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
        event: ext_foreign_toplevel_handle_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        state.fill.handle_event(handle, event);
    }
}

impl Dispatch<ext_image_copy_capture_session_v1::ExtImageCopyCaptureSessionV1, ()> for State {
    fn event(
        state: &mut Self,
        _: &ext_image_copy_capture_session_v1::ExtImageCopyCaptureSessionV1,
        event: ext_image_copy_capture_session_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match event {
            ext_image_copy_capture_session_v1::Event::BufferSize { width, height } => {
                state.wanted = Some((width, height));
            }
            ext_image_copy_capture_session_v1::Event::ShmFormat { format } => {
                let Ok(format) = format.into_result() else {
                    return;
                };
                state.formats.push(format);
            }
            ext_image_copy_capture_session_v1::Event::Done => state.session_done = true,
            // 窗口没了或源不再可用：走"源已消失"的既有恢复路径。
            ext_image_copy_capture_session_v1::Event::Stopped => state.stopped = true,
            _ => {}
        }
    }
}

impl Dispatch<ext_image_copy_capture_frame_v1::ExtImageCopyCaptureFrameV1, ()> for State {
    fn event(
        state: &mut Self,
        _: &ext_image_copy_capture_frame_v1::ExtImageCopyCaptureFrameV1,
        event: ext_image_copy_capture_frame_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match event {
            ext_image_copy_capture_frame_v1::Event::Ready => state.ready = true,
            ext_image_copy_capture_frame_v1::Event::Failed { reason } => {
                let reason = match reason.into_result() {
                    Ok(ext_image_copy_capture_frame_v1::FailureReason::BufferConstraints) => {
                        "缓冲约束变化，需要重新分配"
                    }
                    Ok(ext_image_copy_capture_frame_v1::FailureReason::Stopped) => "窗口源已停止",
                    _ => "窗口帧失败"
                };
                state.error = Some(format!("窗口帧失败：{reason}"));
            }
            _ => {}
        }
    }
}

wayland_client::delegate_noop!(State: ignore ext_foreign_toplevel_image_capture_source_manager_v1::ExtForeignToplevelImageCaptureSourceManagerV1);
wayland_client::delegate_noop!(State: ignore ext_image_capture_source_v1::ExtImageCaptureSourceV1);
wayland_client::delegate_noop!(State: ignore ext_image_copy_capture_manager_v1::ExtImageCopyCaptureManagerV1);
wayland_client::delegate_noop!(State: ignore wl_shm::WlShm);
wayland_client::delegate_noop!(State: ignore wl_shm_pool::WlShmPool);
wayland_client::delegate_noop!(State: ignore wl_buffer::WlBuffer);

impl State {
    fn connect() -> Result<(Connection, EventQueue<Self>, Self)> {
        let connection = Connection::connect_to_env().context("没有可用的 Wayland 会话")?;
        let mut queue = connection.new_event_queue::<Self>();
        let handle = queue.handle();
        connection.display().get_registry(&handle, ());
        let mut state = Self::default();
        queue.roundtrip(&mut state).context("读取 Wayland 全局对象失败")?;
        ensure!(
            state.toplevel_manager.is_some()
                && state.capture_manager.is_some()
                && state.shm.is_some(),
            "合成器没有提供窗口采集所需的 Wayland 协议（ext_foreign_toplevel_list / ext_image_copy_capture）"
        );
        // 事件循环（wait）要从 state 里拿连接：调用方随手丢掉自己的副本时，
        // state 里的这份副本保证底层连接不会被关闭。
        state.connection = Some(connection.clone());
        Ok((connection, queue, state))
    }

    /// 在这条连接上等 identifier 对应的顶层句柄出现。
    fn wait_for_handle(
        &mut self,
        queue: &mut EventQueue<Self>,
        identifier: &str,
    ) -> Result<ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1> {
        self.fill.finding = Some(identifier.to_owned());
        self.fill.found = None;
        let deadline = Instant::now() + TIMEOUT;
        loop {
            if let Some(handle) = &self.fill.found {
                return Ok(handle.clone());
            }
            let now = Instant::now();
            if now >= deadline {
                bail!("窗口已关闭，找不到对应的顶层句柄");
            }
            State::wait(queue, self, deadline - now)?;
        }
    }

    /// 派发事件直到有事件到达或超时。已排队的事件先处理。
    fn wait(
        queue: &mut EventQueue<Self>,
        state: &mut Self,
        timeout: Duration,
    ) -> Result<()> {
        queue.dispatch_pending(state)?;
        let connection = state.connection.as_ref().context("采集已关闭")?;
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
            // Safe because poll only writes into the one descriptor it was given.
            let ready = unsafe { libc::poll(&mut poll, 1, millis(remaining)) };
            if ready < 0 {
                let error = std::io::Error::last_os_error();
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
        queue.dispatch_pending(state)?;
        Ok(())
    }

    /// 为一个顶层窗口建会话；buffer_size/shm_format/done 在往返里就到齐。
    fn open_session(
        &mut self,
        queue: &mut EventQueue<Self>,
        handle: &ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1,
        cursor: bool,
    ) -> Result<()> {
        let qh = &queue.handle();
        let source = self
            .toplevel_manager
            .as_ref()
            .context("采集已关闭")?
            .create_source(handle, qh, ());
        self.source = Some(source.clone());
        let options = if cursor {
            Options::PaintCursors
        } else {
            Options::empty()
        };
        let session = self
            .capture_manager
            .as_ref()
            .context("采集已关闭")?
            .create_session(&source, options, qh, ());
        self.session = Some(session);
        self.wanted = None;
        self.formats.clear();
        self.session_done = false;
        self.ready = false;
        self.stopped = false;
        self.error = None;
        queue.roundtrip(self).context("读取窗口会话信息失败")?;
        ensure!(!self.stopped, "窗口源已停止");
        ensure!(self.session_done, "窗口会话没有给出缓冲信息");
        Ok(())
    }
}

/// 一个窗口的持续采集：会话、共享缓冲与在途帧。窗口缩放后合成器重新宣布
/// buffer_size，这里按新尺寸重配缓冲；窗口关闭走 stopped → SourceGone。
pub(crate) struct Grab {
    queue: EventQueue<State>,
    state: State,
    cursor: bool,
    identifier: String,
    shm_buffer: Option<Shm>,
}

impl Grab {
    pub(crate) fn open(screen: &Screen, cursor: bool) -> Result<Self> {
        let identifier = parse_identity(&screen.identity.clone().unwrap_or_default())?.to_owned();
        let (connection, mut queue, mut state) = State::connect()?;
        let _ = connection;
        let handle = state.wait_for_handle(&mut queue, &identifier)?;
        state
            .open_session(&mut queue, &handle, cursor)
            .context("打开窗口采集会话失败")?;
        let (width, height) = state.wanted.context("会话没有说明窗口缓冲尺寸")?;
        ensure!(width > 0 && height > 0, "窗口缓冲尺寸无效");
        let qh = queue.handle();
        let shm_buffer = allocate(&mut state, width, height, &qh)?;
        lock(sizes()).insert(identifier.clone(), (width, height));
        let mut grab = Self {
            queue,
            state,
            cursor,
            identifier,
            shm_buffer: Some(shm_buffer),
        };
        grab.probe()?;
        Ok(grab)
    }

    pub(crate) fn name(&self) -> &'static str {
        "Wayland 窗口"
    }

    pub(crate) fn cursor(&self) -> bool {
        self.cursor
    }

    pub(crate) fn size(&self) -> (u32, u32) {
        self.shm_buffer
            .as_ref()
            .map(|shm| (shm.width, shm.height))
            .unwrap_or((0, 0))
    }

    pub(crate) fn pixels(&self) -> &[u8] {
        self.shm_buffer
            .as_ref()
            .map(|shm| shm.pixels())
            .unwrap_or(&[])
    }

    /// 会话建立是否可用：探测一帧请求能否发出、事件是否正常。
    fn probe(&mut self) -> Result<()> {
        self.request()?;
        self.queue
            .roundtrip(&mut self.state)
            .context("读取窗口帧事件失败")?;
        if let Some(error) = self.state.error.take() {
            bail!("{error}");
        }
        ensure!(!self.state.stopped, "窗口源已停止");
        // 静止的窗口不产生帧：探测只确认会话活着，真正的画面等调用方等。
        self.drop_frame();
        Ok(())
    }

    /// 等待直到有一帧就绪或超时。返回 Some(缓冲尺寸) 表示有新画面。
    pub(crate) fn next(
        &mut self,
        _cursor: bool,
        timeout: Duration,
    ) -> Result<Option<(u32, u32)>> {
        // 窗口在桌面上的位置跟着采集循环走，输入注入读的就是这一份。
        refresh_placements();
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(error) = &self.state.error {
                bail!("{error}");
            }
            if self.state.stopped {
                return Err(SourceGone.into());
            }
            // 窗口缩放后合成器重新宣布 buffer_size：按新尺寸重配缓冲。
            self.follow_size()?;
            if std::mem::take(&mut self.state.ready) {
                let size = self.size();
                if let Some(shm) = self.shm_buffer.as_mut()
                    && shm.swap
                {
                    shm.swap_in_place();
                }
                // 在返回前先请求下一帧；请求只入队，随下次 flush 发出，
                // 不会在调用方编码这一帧时就开始画。
                self.request()?;
                return Ok(Some(size));
            }
            if self.state.frame.is_none() {
                self.request()?;
            }
            let now = Instant::now();
            if now >= deadline {
                return Ok(None);
            }
            self.wait(deadline - now)?;
        }
    }

    /// 发起一帧：帧对象一次性，attach_buffer + capture。
    fn request(&mut self) -> Result<()> {
        let qh = self.queue.handle();
        self.drop_frame();
        let session = self.state.session.clone().context("采集已关闭")?;
        let buffer = self
            .shm_buffer
            .as_ref()
            .context("窗口缓冲未就绪")?
            .buffer
            .clone();
        let frame = session.create_frame(&qh, ());
        frame.attach_buffer(&buffer);
        frame.capture();
        self.state.frame = Some(frame);
        Ok(())
    }

    fn drop_frame(&mut self) {
        if let Some(frame) = self.state.frame.take() {
            frame.destroy();
        }
    }

    /// 会话宣布的尺寸变了就重配缓冲；会话意外终止走 stopped → SourceGone，
    /// 由上层的目录刷新重开整条采集。
    fn follow_size(&mut self) -> Result<()> {
        let Some((width, height)) = self.state.wanted else {
            return Ok(());
        };
        let stale = self
            .shm_buffer
            .as_ref()
            .is_none_or(|shm| (shm.width, shm.height) != (width, height));
        if stale && width > 0 && height > 0 {
            let qh = self.queue.handle();
            let shm_buffer = allocate(&mut self.state, width, height, &qh)?;
            self.shm_buffer = Some(shm_buffer);
            lock(sizes()).insert(self.identifier.clone(), (width, height));
        }
        Ok(())
    }

    /// 派发事件直到有事件到达或超时。已排队的事件先处理。
    fn wait(&mut self, timeout: Duration) -> Result<()> {
        State::wait(&mut self.queue, &mut self.state, timeout)
    }
}

fn millis(timeout: Duration) -> i32 {
    timeout.as_millis().try_into().unwrap_or(i32::MAX)
}

// ---------------------------------------------------------------------------
// 共享缓冲：memfd + 池 + 单缓冲，与 wlr_screencopy 同一形状，无变换处理。
// ---------------------------------------------------------------------------

struct Shm {
    _fd: OwnedFd,
    pool: wl_shm_pool::WlShmPool,
    map: *mut u8,
    length: usize,
    buffer: wl_buffer::WlBuffer,
    width: u32,
    height: u32,
    /// ABGR/XBGR 的红蓝与编码器相反，读出时交换。
    swap: bool,
}

// SAFETY: the mapping and Wayland proxies are exclusively owned by Desktop;
// moving that owner to the capture thread does not share the raw mapping.
unsafe impl Send for Shm {}

impl Shm {
    fn pixels(&self) -> &[u8] {
        // Safe because the mapping is live for as long as self is, and the
        // compositor has finished with it: a ready event preceded this.
        unsafe { std::slice::from_raw_parts(self.map, self.length) }
    }

    /// ABGR/XBGR 的红蓝与编码器相反：帧就绪后就地把红蓝换过来。映射是本
    /// 进程自己的 memfd，写入安全；BGRA 布局不进这个函数。
    fn swap_in_place(&mut self) {
        // Safe because the mapping is live for as long as self is.
        let pixels = unsafe { std::slice::from_raw_parts_mut(self.map, self.length) };
        for pixel in pixels.chunks_exact_mut(4) {
            pixel.swap(0, 2);
        }
    }
}

impl Drop for Shm {
    fn drop(&mut self) {
        self.buffer.destroy();
        self.pool.destroy();
        // Safe because the mapping was made for this length in allocate.
        unsafe { libc::munmap(self.map.cast(), self.length) };
    }
}

/// 选一个内存布局是 BGRA 的格式（编码器就要这个字节序）；ABGR/XBGR 系的
/// 红蓝相反，读出时交换；其余一律拒绝。
fn chosen_format(formats: &[wl_shm::Format]) -> Result<(wl_shm::Format, bool)> {
    use wayland_client::protocol::wl_shm::Format;
    for format in [
        Format::Argb8888,
        Format::Xrgb8888,
        Format::Abgr8888,
        Format::Xbgr8888,
    ] {
        if formats.contains(&format) {
            let swap = matches!(format, Format::Abgr8888 | Format::Xbgr8888);
            return Ok((format, swap));
        }
    }
    bail!("合成器没有提供可读的共享内存像素格式（需要 32 位 ARGB/XRGB/ABGR/XBGR）")
}

fn allocate(
    state: &mut State,
    width: u32,
    height: u32,
    handle: &QueueHandle<State>,
) -> Result<Shm> {
    let (format, swap) = chosen_format(&state.formats)?;
    let stride = (width as usize)
        .checked_mul(4)
        .context("窗口宽度超出共享内存范围")?;
    let length = stride
        .checked_mul(height as usize)
        .context("窗口尺寸超出共享内存范围")?;
    ensure!(length > 0 && length <= i32::MAX as usize, "窗口缓冲尺寸无效");
    let shm = state.shm.as_ref().context("采集已关闭")?;
    let name = c"openuuyc-window-capture";
    // Safe because the name is a valid C string and the flags are plain bits.
    let fd = unsafe { libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC) };
    if fd < 0 {
        bail!("无法创建共享内存：{}", std::io::Error::last_os_error());
    }
    // Safe because memfd_create returned a fresh descriptor on success.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    // Safe because fd is a live descriptor and the length was just computed.
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
    // owns, and it is unmapped again in Shm::drop.
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
        width,
        height,
        swap,
    })
}

// ---------------------------------------------------------------------------
// 目录与输入几何：niri 会话把窗口对到输出上的全局逻辑矩形。
// ---------------------------------------------------------------------------

/// 目录条目按协议列出；尺寸走缓存，没见过的窗口当场探测一次。
pub(crate) fn screens() -> Result<Vec<Screen>> {
    refresh_placements();
    let toplevels = list()?;
    let mut found = Vec::new();
    for toplevel in toplevels {
        if found.len() >= WINDOW_LIMIT {
            break;
        }
        let Some((width, height)) = cached_size(&toplevel) else {
            continue;
        };
        let id = crate::media::capture::source_id(&identity(&toplevel.identifier))?;
        register(id, &toplevel);
        found.push(Screen {
            id,
            device_name: format!("win-{}", short(&toplevel.identifier)),
            display_name: display_name(&toplevel),
            width,
            height,
            // 窗口源不在显示器拓扑里：这个原点只表示「没有」，输入换算一律
            // 走实时的窗口位置（见 [`placement`]）。跟着窗口动的原点还会让
            // 输入几何每滚一下就变一次，持有的按键和鼠标键都被跟着放开。
            left: 0,
            top: 0,
            primary: false,
            fps: 60,
            dpi_scale: None,
            hdr: false,
            adapter: 0,
            render_adapter: None,
            identity: Some(identity(&toplevel.identifier)),
        });
    }
    Ok(found)
}

fn short(identifier: &str) -> String {
    identifier.chars().take(12).collect()
}

fn display_name(toplevel: &Toplevel) -> String {
    if toplevel.title.is_empty() {
        toplevel.app_id.clone()
    } else {
        toplevel.title.clone()
    }
}

/// 缓存里没有的窗口开一次会话探尺寸；会话随连接一起销毁。
fn cached_size(toplevel: &Toplevel) -> Option<(u32, u32)> {
    if let Some(size) = lock(sizes()).get(&toplevel.identifier).copied() {
        return (size.0 > 0 && size.1 > 0).then_some(size);
    }
    let (connection, mut queue, mut state) = State::connect().ok()?;
    let _ = connection;
    let handle = state.wait_for_handle(&mut queue, &toplevel.identifier).ok()?;
    state.open_session(&mut queue, &handle, false).ok()?;
    let (width, height) = state.wanted?;
    if width == 0 || height == 0 {
        return None;
    }
    lock(sizes()).insert(toplevel.identifier.clone(), (width, height));
    Some((width, height))
}

/// 重新查询一个窗口源：标题/尺寸可能变了；窗口没了报 SourceGone。
pub(crate) fn refresh(selected: &Screen) -> Result<Screen> {
    refresh_placements();
    let identity_owned = selected.identity.clone().unwrap_or_default();
    let identifier = parse_identity(&identity_owned)?;
    let toplevels = list()?;
    let toplevel = toplevels
        .iter()
        .find(|entry| entry.identifier == identifier)
        .context("窗口已关闭")?;
    let size = cached_size(toplevel).context("窗口还没有可采集的缓冲尺寸")?;
    Ok(Screen {
        id: selected.id,
        device_name: selected.device_name.clone(),
        display_name: display_name(toplevel),
        width: size.0,
        height: size.1,
        // 与目录条目一致：窗口源没有拓扑原点（见 [`screens`]）。
        left: 0,
        top: 0,
        primary: false,
        fps: selected.fps,
        dpi_scale: None,
        hdr: false,
        adapter: 0,
        render_adapter: None,
        identity: selected.identity.clone(),
    })
}

/// 窗口源此刻在桌面上的位置（输入注入用），在合成器的逻辑空间里。只读缓
/// 存，不等合成器：缓存由采集循环和目录刷新保持新鲜。定位不到返回 None，
/// 调用方明确报错，而不是把点击投到别处。
pub(crate) fn placement(id: i32) -> Option<Placement> {
    let identifier = lock(reverse()).get(&id).cloned()?;
    let snapshot = lock(placements()).as_ref().map(|(_, found)| found.clone());
    let found = placement_in(snapshot.as_deref(), &identifier);
    note_placement(id, found.as_ref().err().copied());
    found.ok()
}

/// 在一份位置快照里找一个窗口；找不到时说明是哪一种找不到。
fn placement_in(
    snapshot: Option<&Placements>,
    identifier: &str,
) -> Result<Placement, &'static str> {
    let snapshot = snapshot.ok_or("合成器没有可用的窗口位置接口（目前只有 niri）")?;
    // niri 的 identifier 就是它的窗口 id；别的形式对不上号。
    let window = identifier
        .parse::<u64>()
        .ok()
        .filter(|window| snapshot.reported.contains(window))
        .ok_or("合成器的窗口清单里没有这个窗口")?;
    snapshot
        .visible
        .get(&window)
        .copied()
        .ok_or("窗口不在可见工作区，或者已经滚出视口")
}

/// 一个窗口源定位得到还是定位不到，只在变化时记一条：输入事件一秒上百个，
/// 每个都记会刷屏。
fn note_placement(id: i32, failure: Option<&'static str>) {
    static FAILING: Mutex<Option<(i32, &'static str)>> = Mutex::new(None);
    let mut failing = lock(&FAILING);
    match failure {
        Some(reason) if *failing != Some((id, reason)) => {
            *failing = Some((id, reason));
            tracing::warn!(screen = id, reason, "窗口源定位不到，按位置注入的输入会被拒绝");
        }
        None if failing.is_some_and(|(screen, _)| screen == id) => {
            *failing = None;
            tracing::info!(screen = id, "窗口源重新定位到了");
        }
        _ => {}
    }
}

/// 输入注入按 id 找窗口时用它判断这是不是窗口源。
pub(crate) fn knows(id: i32) -> bool {
    lock(reverse()).contains_key(&id)
}

/// 枚举时登记 id → identifier，输入注入按屏幕 id 找回窗口。
fn register(id: i32, toplevel: &Toplevel) {
    lock(reverse()).insert(id, toplevel.identifier.clone());
}

/// 让窗口位置缓存不过期：过了 [`PLACEMENT_TTL`] 就向 niri 重问一次。niri 不
/// 在（别的合成器）或者问不到时把缓存清掉——旧位置不能继续当作现况用。
fn refresh_placements() {
    if lock(placements())
        .as_ref()
        .is_some_and(|(taken, _)| taken.elapsed() < PLACEMENT_TTL)
    {
        return;
    }
    refresh_placements_now();
}

/// 不管缓存多新都重问一次。合成器刚被要求把窗口挪进视口之后用它：输入注
/// 入要按新的位置算，等不到下一个采集帧。
fn refresh_placements_now() {
    // 不拿着锁去问合成器：输入注入那边读缓存，不该等这一次往返。
    let found = query_placements().map(|found| (Instant::now(), Arc::new(found)));
    *lock(placements()) = found;
}

/// 问一次 niri：每个窗口在哪个工作区、瓦片摆在视图的什么位置。
fn query_placements() -> Option<Placements> {
    // 没有 niri 的会话连套接字都没有，不必再去要合成器布局。
    std::env::var_os("NIRI_SOCKET")?;
    // 第一次问可能早于布局监视线程发布第一份描述（比如测试进程刚起来）。
    // 等这一份是有限次的：发布过之后 [`layout_soon`] 直接读快照。
    let layout = crate::platform::display::compositor_layout_soon()?;
    let windows = niri_request("Windows")?;
    let workspaces = niri_request("Workspaces")?;
    Some(placements_of(&layout, &windows, &workspaces))
}

/// 把一个窗口切到可见位置：niri 的 `FocusWindow` 会切到它所在的工作区，并把
/// 它滚进视口。窗口源可以在别的工作区上被采集，但指针只能落到屏幕上看得见
/// 的地方——不先把它露出来，窗口级输入就没有落点。
///
/// 只在窗口确实在 niri 的清单里、但当前不可见时试；同一个窗口最多每秒试一
/// 次，免得输入事件把 IPC 打满。成功之后立刻重问位置，调用方马上就能算落点。
pub(crate) fn reveal(id: i32) -> bool {
    let Some(identifier) = lock(reverse()).get(&id).cloned() else {
        return false;
    };
    let Ok(window) = identifier.parse::<u64>() else {
        // 别的合成器的 identifier 不是 niri 的窗口 id，没有能对上的窗口。
        return false;
    };
    let known = lock(placements())
        .as_ref()
        .is_some_and(|(_, found)| found.reported.contains(&window));
    if !known || !allow_reveal(window) {
        return false;
    }
    tracing::info!(screen = id, window, "窗口不在屏幕上，切到它所在的工作区以便注入输入");
    if !niri_action(&focus_request(window)) {
        return false;
    }
    refresh_placements_now();
    true
}

/// niri 的「聚焦这个窗口」动作：切到它所在的工作区，并把它滚进视口。
fn focus_request(window: u64) -> String {
    format!(r#"{{"FocusWindow":{{"id":{window}}}}}"#)
}

/// 同一个窗口的「切到可见位置」最多每秒试一次。输入事件一秒上百个，每次都
/// 问一遍合成器只会白等；合成器没做到时（窗口固定在别处）也别一直重试。
fn allow_reveal(window: u64) -> bool {
    const RETRY: Duration = Duration::from_secs(1);
    static LAST: Mutex<Option<(u64, Instant)>> = Mutex::new(None);
    let mut last = lock(&LAST);
    if last.is_some_and(|(id, at)| id == window && at.elapsed() < RETRY) {
        return false;
    }
    *last = Some((window, Instant::now()));
    true
}

/// 把 niri 的窗口与工作区清单换算成全局逻辑矩形。只算可见工作区（每块输出
/// 上当前显示的那个）上的窗口；别的工作区上的窗口 niri 也报位置，但那是它
/// 自己工作区视图里的位置，屏幕上并没有它。
fn placements_of(
    layout: &crate::platform::CompositorLayout,
    windows: &serde_json::Value,
    workspaces: &serde_json::Value,
) -> Placements {
    use serde_json::Value;
    // 可见工作区的 id → 它所在的输出。
    let shown: HashMap<i64, &crate::platform::CompositorOutput> = workspaces
        .as_array()
        .into_iter()
        .flatten()
        .filter(|workspace| workspace.get("is_active").and_then(Value::as_bool) == Some(true))
        .filter_map(|workspace| {
            let id = workspace.get("id").and_then(Value::as_i64)?;
            let output = workspace.get("output").and_then(Value::as_str)?;
            Some((id, layout.output(output)?))
        })
        .collect();
    let mut placements = Placements::default();
    // 一个窗口的字段不全（没有 app_id 的窗口、没有位置的窗口）只跳过它自己。
    for window in windows.as_array().into_iter().flatten() {
        let Some(id) = window.get("id").and_then(Value::as_u64) else {
            continue;
        };
        placements.reported.insert(id);
        let Some(output) = window
            .get("workspace_id")
            .and_then(Value::as_i64)
            .and_then(|workspace| shown.get(&workspace))
        else {
            continue;
        };
        if let Some(rect) = window_rect(window, output) {
            placements.visible.insert(
                id,
                Placement {
                    rect,
                    output: (output.left, output.top, output.width, output.height),
                },
            );
        }
    }
    placements
}

/// 一个窗口的全局逻辑矩形：瓦片在工作区视图里的位置，加上窗口在瓦片里的偏
/// 移（边框之类），再加输出原点；尺寸用 IPC 的逻辑值（捕获缓冲是物理像素，
/// 缩放的输出上两者不相等）。整个滚出视口的瓦片 niri 不给位置，返回 None。
fn window_rect(
    window: &serde_json::Value,
    output: &crate::platform::CompositorOutput,
) -> Option<(i32, i32, u32, u32)> {
    use serde_json::Value;
    let layout = window.get("layout")?;
    let pair = |name: &str| -> Option<(f64, f64)> {
        let values = layout.get(name)?.as_array()?;
        Some((values.first()?.as_f64()?, values.get(1)?.as_f64()?))
    };
    let tile = pair("tile_pos_in_workspace_view")?;
    let offset = pair("window_offset_in_tile").unwrap_or((0.0, 0.0));
    let size = layout.get("window_size").and_then(Value::as_array)?;
    let extent = |index: usize| -> Option<u32> {
        u32::try_from(size.get(index)?.as_u64()?)
            .ok()
            .filter(|extent| *extent > 0 && *extent <= 65536)
    };
    let (width, height) = (extent(0)?, extent(1)?);
    // 位置四舍五入到整像素；越界的值（不该有）不参与换算。
    let whole = |value: f64, origin: i32| -> Option<i32> {
        let value = value.round();
        (value.is_finite() && value.abs() <= 1_000_000.0)
            .then(|| (value as i32).checked_add(origin))
            .flatten()
    };
    let left = whole(tile.0 + offset.0, output.left)?;
    let top = whole(tile.1 + offset.1, output.top)?;
    // 保证宽高加进原点不会溢出，调用方可以直接当半开矩形用。
    left.checked_add(width as i32)?;
    top.checked_add(height as i32)?;
    Some((left, top, width, height))
}

/// 向 niri 的 IPC 套接字发一句请求，读回它那一行 JSON 回复。不经 `niri msg`：
/// 位置每秒要问好几次，犯不着每次起一个进程。niri 不在、连不上、超时或者
/// 回复不是 JSON 都返回 None。
fn niri_call(request: &str) -> Option<serde_json::Value> {
    use std::io::{BufRead, BufReader, Read, Write};
    let path = std::env::var_os("NIRI_SOCKET")?;
    let mut stream = std::os::unix::net::UnixStream::connect(path).ok()?;
    stream.set_read_timeout(Some(IPC_TIMEOUT)).ok()?;
    stream.set_write_timeout(Some(IPC_TIMEOUT)).ok()?;
    stream
        .write_all(format!("{request}\n").as_bytes())
        .ok()?;
    // 回复是一行 JSON；封个顶，免得对面不换行时一直读下去。
    let mut line = String::new();
    BufReader::new(stream.take(8 << 20))
        .read_line(&mut line)
        .ok()?;
    serde_json::from_str(&line).ok()
}

/// 一个单元请求（`"Windows"` 这样），拿回 `Ok` 里同名的那一项。
fn niri_request(request: &str) -> Option<serde_json::Value> {
    let mut reply = niri_call(&format!("\"{request}\""))?;
    Some(reply.get_mut("Ok")?.get_mut(request)?.take())
}

/// 一个动作请求（`{"Action":...}`），合成器答 `{"Ok":"Handled"}` 才算做到。
fn niri_action(action: &str) -> bool {
    niri_call(&format!(r#"{{"Action":{action}}}"#))
        .is_some_and(|reply| reply.get("Ok").is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_roundtrip_and_prefix() {
        let id = identity("abc-123");
        assert!(id.starts_with(WL_WINDOW_PREFIX));
        assert_eq!(parse_identity(&id).unwrap(), "abc-123");
        assert!(parse_identity("x11-window/abc").is_err());
    }

    #[test]
    fn chosen_format_prefers_bgra_memory_layout() {
        let formats = vec![
            wayland_client::protocol::wl_shm::Format::Abgr8888,
            wayland_client::protocol::wl_shm::Format::Argb8888,
        ];
        assert_eq!(
            chosen_format(&formats).unwrap(),
            (
                wayland_client::protocol::wl_shm::Format::Argb8888,
                false,
            ),
        );
        assert!(chosen_format(&[]).is_err());
    }

    fn output(
        name: &str,
        left: i32,
        top: i32,
        width: u32,
        height: u32,
    ) -> crate::platform::CompositorOutput {
        crate::platform::CompositorOutput {
            name: Some(name.into()),
            left,
            top,
            width,
            height,
            rotation: Default::default(),
        }
    }

    /// 开发机上 niri 的真实回复（裁剪过）：三块输出，eDP-1 缩放 1.5、摆在
    /// (1200, 1067)；工作区 6 上的视图正对着 50 号窗口。
    fn sample() -> (
        crate::platform::CompositorLayout,
        serde_json::Value,
        serde_json::Value,
    ) {
        let layout = crate::platform::CompositorLayout {
            outputs: vec![
                output("DP-1", 0, 0, 1200, 1920),
                output("HDMI-A-1", 1200, 0, 1706, 1066),
                output("eDP-1", 1200, 1067, 1706, 1066),
            ],
        };
        let workspaces = serde_json::json!([
            {"id": 3, "idx": 3, "name": null, "output": "HDMI-A-1", "is_active": true},
            {"id": 10, "idx": 2, "name": null, "output": "HDMI-A-1", "is_active": false},
            {"id": 6, "idx": 2, "name": null, "output": "eDP-1", "is_active": true},
        ]);
        let windows = serde_json::json!([
            // 没有 app_id、整个滚出视口（niri 不给位置）的窗口排在最前面。
            {"id": 24, "title": "OpenUUYC · 控制中心", "app_id": null, "workspace_id": 6,
             "layout": {"window_size": [1024, 764], "tile_pos_in_workspace_view": null,
                        "window_offset_in_tile": [0.0, 0.0]}},
            // 不在可见工作区：niri 照样报位置，但屏幕上没有它。
            {"id": 19, "title": "Steam", "app_id": "steam", "workspace_id": 10,
             "layout": {"window_size": [1358, 1059],
                        "tile_pos_in_workspace_view": [-775.3333333333334, 4.0],
                        "window_offset_in_tile": [0.0, 0.0]}},
            {"id": 33, "title": "Edge", "app_id": "microsoft-edge", "workspace_id": 3,
             "layout": {"window_size": [1358, 1059],
                        "tile_pos_in_workspace_view": [174.66666666666666, 4.0],
                        "window_offset_in_tile": [0.0, 0.0]}},
            // 两个同名的 kitty：45 大半滚出视口左边，50 在正中、带 2x3 的边框偏移。
            {"id": 45, "title": "Claude Code", "app_id": "kitty", "workspace_id": 6,
             "layout": {"window_size": [1193, 1019],
                        "tile_pos_in_workspace_view": [-770.6666666666666, 44.0],
                        "window_offset_in_tile": [0.0, 0.0]}},
            {"id": 50, "title": "Claude Code", "app_id": "kitty", "workspace_id": 6,
             "layout": {"window_size": [1193, 1019],
                        "tile_pos_in_workspace_view": [426.6666666666667, 44.0],
                        "window_offset_in_tile": [2.0, 3.0]}},
        ]);
        (layout, windows, workspaces)
    }

    #[test]
    fn windows_are_placed_by_id_in_the_compositor_layout() {
        let (layout, windows, workspaces) = sample();
        let found = placements_of(&layout, &windows, &workspaces);
        // 报出来的窗口一个不少，哪怕它没有位置、没有 app_id。
        assert_eq!(found.reported, HashSet::from([24, 19, 33, 45, 50]));
        // 视图里的位置加输出原点。
        assert_eq!(
            found.visible[&33],
            Placement {
                rect: (1375, 4, 1358, 1059),
                output: (1200, 0, 1706, 1066),
            }
        );
        // 同名窗口按 id 区分；窗口在瓦片里的偏移（边框）也算进去。
        assert_eq!(found.visible[&50].rect, (1629, 1114, 1193, 1019));
        assert_eq!(found.visible[&45].rect, (429, 1111, 1193, 1019));
        assert_eq!(found.visible[&45].output, (1200, 1067, 1706, 1066));
        // 别的工作区上的、整个滚出视口的，屏幕上都没有它。
        assert_eq!(found.visible.len(), 3);
    }

    #[test]
    fn an_unplaceable_window_says_why() {
        let (layout, windows, workspaces) = sample();
        let found = placements_of(&layout, &windows, &workspaces);
        assert_eq!(placement_in(Some(&found), "50"), Ok(found.visible[&50]));
        // 在清单里、但屏幕上没有：别的工作区上的窗口，滚出视口的窗口。
        for hidden in ["19", "24"] {
            let reason = placement_in(Some(&found), hidden).unwrap_err();
            assert!(reason.contains("可见工作区"), "{hidden}: {reason}");
        }
        // 清单里没有的 id、不是 id 的 identifier（别的合成器）都对不上号。
        for unknown in ["7", "f3a9c0de11", "-50", ""] {
            let reason = placement_in(Some(&found), unknown).unwrap_err();
            assert!(reason.contains("清单"), "{unknown:?}: {reason}");
        }
        assert!(placement_in(None, "50").unwrap_err().contains("niri"));
    }

    #[test]
    fn malformed_replies_place_nothing() {
        let (layout, windows, workspaces) = sample();
        for odd in [
            serde_json::json!(null),
            serde_json::json!({"Windows": []}),
            // 没有 id、不是对象、尺寸为零、位置不是数。
            serde_json::json!([
                {"title": "x", "workspace_id": 6},
                5,
                {"id": 9, "workspace_id": 6,
                 "layout": {"window_size": [0, 10], "tile_pos_in_workspace_view": [0.0, 0.0]}},
                {"id": 8, "workspace_id": 6,
                 "layout": {"window_size": [10, 10], "tile_pos_in_workspace_view": ["a", 0.0]}},
            ]),
        ] {
            let found = placements_of(&layout, &odd, &workspaces);
            assert!(found.visible.is_empty(), "{odd}");
        }
        // 工作区清单读不出来时，没有哪个工作区算可见。
        let found = placements_of(&layout, &windows, &serde_json::json!("oops"));
        assert_eq!(found.reported.len(), 5);
        assert!(found.visible.is_empty());
    }

    #[test]
    fn focus_request_uses_niris_window_id() {
        let request = focus_request(50);
        assert_eq!(request, r#"{"FocusWindow":{"id":50}}"#);
        // 外层动作包由 niri_action 加，拼起来是 niri 的 Request 形状。
        let full = format!(r#"{{"Action":{request}}}"#);
        let parsed: serde_json::Value = serde_json::from_str(&full).unwrap();
        assert_eq!(parsed["Action"]["FocusWindow"]["id"], 50);
    }

    #[test]
    fn reveal_is_rate_limited_per_window() {
        // 用别的测试不会碰到的窗口 id：限频状态是进程里共享的。
        assert!(allow_reveal(9_000_001));
        assert!(!allow_reveal(9_000_001), "同一个窗口一秒内只试一次");
        assert!(allow_reveal(9_000_002));
    }

    /// 需要 niri 会话：`cargo test --lib wlr_window -- --ignored --nocapture`。
    #[test]
    #[ignore = "queries the local compositor; needs a running niri session"]
    fn live_placements_cover_the_listed_toplevels() {
        let found = query_placements().expect("niri IPC");
        let toplevels = list().unwrap();
        assert!(!toplevels.is_empty(), "no toplevels listed");
        for toplevel in &toplevels {
            eprintln!(
                "{:>4} {:<28} -> {:?}",
                toplevel.identifier,
                toplevel.title.chars().take(28).collect::<String>(),
                placement_in(Some(&found), &toplevel.identifier)
            );
            // niri 把窗口 id 当作 identifier：协议列出来的每个窗口都在它的清单里。
            let id = toplevel.identifier.parse::<u64>().expect("identifier is a window id");
            assert!(found.reported.contains(&id), "{id} missing from niri's list");
        }
        // 每个有位置的窗口，都和它所在的输出有交集。
        for (id, placement) in &found.visible {
            let ((left, top, width, height), (ol, ot, ow, oh)) = (placement.rect, placement.output);
            assert!(
                left < ol + ow as i32
                    && left + width as i32 > ol
                    && top < ot + oh as i32
                    && top + height as i32 > ot,
                "{id}: {placement:?}"
            );
        }
        assert!(!found.visible.is_empty());
    }

    /// 需要 niri 会话与已映射的窗口：`cargo test --lib wlr_window -- --ignored --nocapture`。
    #[test]
    #[ignore = "queries the local compositor; needs a running niri session"]
    fn enumerates_sources_and_grabs_a_frame() {
        let sources = crate::platform::capture::window_screens().unwrap();
        eprintln!("window sources: {sources:?}");
        assert!(!sources.is_empty(), "no window sources listed");
        let mut desktop = crate::platform::capture::Desktop::open_selected(&sources[0]).unwrap();
        let frame = desktop
            .next(500, 4, false, false, (u32::MAX, u32::MAX))
            .unwrap()
            .expect("no frame within 500ms");
        eprintln!(
            "frame: {}x{} backend {}",
            frame.width,
            frame.height,
            desktop.backend_name()
        );
        assert!(frame.width > 0 && frame.height > 0);
    }
}