//! Wayland 的窗口级采集：绑定 ext_foreign_toplevel_list_v1 枚举顶层窗口，
//! ext_foreign_toplevel_image_capture_source_manager_v1 加
//! ext_image_copy_capture_v1 按窗口取帧。合成器有 damage 才推帧，静止的
//! 窗口不产生重复画面；窗口内容缓冲以它自己的实际尺寸为准，窗口缩放后下一
//! 帧即跟随。
//!
//! 协议本身不给窗口位置：niri 会话上再按 niri msg 的 IPC 把窗口对到它所在
//! 输出上的全局逻辑矩形（工作区视图位置 + 输出原点），输入注入据此换算；对
//! 不上的窗口（不在可见工作区）明确拒绝注入，而不是把点击投到别处。其他合
//! 成器没有实测过的 IPC，不写进来——那种会话里窗口可以看（采集照常），但输
//! 入会明确报错。
//!
//! 窗口缓冲是预乘 ARGB：透明区域按预乘后的颜色输出（不反预乘），边缘可能
//! 偏深；不透明的普通窗口不受影响。
use super::{Screen, SourceGone, WL_WINDOW_PREFIX};
use anyhow::{Context, Result, bail, ensure};
use std::collections::HashMap;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::sync::{Mutex, OnceLock};
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

/// niri 会话上窗口的全局逻辑矩形；live_rect 用它给输入换算。
/// 键是 (app_id, 标题, 缓冲尺寸)，与枚举到的窗口按内容对上。
fn positions() -> &'static Mutex<HashMap<(String, String), (i32, i32, u32, u32)>> {
    static POSITIONS: OnceLock<Mutex<HashMap<(String, String), (i32, i32, u32, u32)>>> =
        OnceLock::new();
    POSITIONS.get_or_init(Default::default)
}

fn titles() -> &'static Mutex<HashMap<String, (String, String)>> {
    static TITLES: OnceLock<Mutex<HashMap<String, (String, String)>>> = OnceLock::new();
    TITLES.get_or_init(Default::default)
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
        register(id, &toplevel, (width, height));
        let position = niri_rect(&toplevel.app_id, &toplevel.title);
        let (left, top) = position.map(|rect| (rect.0, rect.1)).unwrap_or((0, 0));
        found.push(Screen {
            id,
            device_name: format!("win-{}", short(&toplevel.identifier)),
            display_name: display_name(&toplevel),
            width,
            height,
            left,
            top,
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
    let identity_owned = selected.identity.clone().unwrap_or_default();
    let identifier = parse_identity(&identity_owned)?;
    let toplevels = list()?;
    let toplevel = toplevels
        .iter()
        .find(|entry| entry.identifier == identifier)
        .context("窗口已关闭")?;
    let size = cached_size(toplevel).context("窗口还没有可采集的缓冲尺寸")?;
    let position = niri_rect(&toplevel.app_id, &toplevel.title);
    let (left, top) = position.map(|rect| (rect.0, rect.1)).unwrap_or((0, 0));
    Ok(Screen {
        id: selected.id,
        device_name: selected.device_name.clone(),
        display_name: display_name(toplevel),
        width: size.0,
        height: size.1,
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

/// 窗口源当前在桌面上的全局逻辑矩形（输入注入用）。X11 的 rect 是 X 像素，
/// 这里的 rect 是合成器逻辑空间，各走各的注入路径。
pub(crate) fn live_rect(id: i32) -> Option<(i32, i32, u32, u32)> {
    let identifier = lock(reverse()).get(&id).cloned()?;
    let (app_id, title) = lock(titles()).get(&identifier).cloned()?;
    lock(positions()).get(&(app_id, title)).copied()
}

/// 输入注入按 id 找窗口时用它判断这是不是窗口源。
pub(crate) fn knows(id: i32) -> bool {
    lock(reverse()).contains_key(&id)
}

/// 枚举时登记 id → identifier → 标题/应用，并算好 niri 的全局逻辑矩形。
fn register(id: i32, toplevel: &Toplevel, size: (u32, u32)) {
    lock(reverse()).insert(id, toplevel.identifier.clone());
    lock(titles()).insert(
        toplevel.identifier.clone(),
        (toplevel.app_id.clone(), toplevel.title.clone()),
    );
    if let Some(rect) = niri_rect(&toplevel.app_id, &toplevel.title) {
        lock(positions()).insert(
            (toplevel.app_id.clone(), toplevel.title.clone()),
            rect,
        );
    }
}

/// niri 会话上窗口在桌面上的全局逻辑矩形：工作区视图内的位置加输出原点，
/// 尺寸用 IPC 的逻辑值（捕获缓冲是物理像素，缩放会话里两者不相等）。
/// 只算可见工作区上的窗口；合成器没有这个 IPC（或窗口对不上）时返回
/// None，输入注入会明确报错。窗口按 app_id + 标题匹配，同名窗口取第一个。
fn niri_rect(app_id: &str, title: &str) -> Option<(i32, i32, u32, u32)> {
    let layout = crate::platform::display::compositor_layout()?;
    let windows = niri_json(&["windows", "--json"])?;
    let workspaces = niri_json(&["workspaces", "--json"])?;
    // workspace_id → 输出名，只要可见工作区。
    let mut outputs = HashMap::new();
    for workspace in workspaces.as_array()? {
        let active = workspace.get("is_active").and_then(|v| v.as_bool())?;
        if !active {
            continue;
        }
        let id = workspace.get("id").and_then(|v| v.as_i64())?;
        let output = workspace.get("output").and_then(|v| v.as_str())?.to_owned();
        outputs.insert(id, output);
    }
    for window in windows.as_array()? {
        let window_app_id = window.get("app_id").and_then(|v| v.as_str())?;
        let window_title = window.get("title").and_then(|v| v.as_str())?;
        if window_app_id != app_id || window_title != title {
            continue;
        }
        let window_layout = window.get("layout")?;
        let window_size = window_layout.get("window_size").and_then(|v| v.as_array())?;
        let width = window_size.first().and_then(|v| v.as_u64())? as u32;
        let height = window_size.get(1).and_then(|v| v.as_u64())? as u32;
        let workspace_id = window.get("workspace_id").and_then(|v| v.as_i64())?;
        let output_name = outputs.get(&workspace_id)?;
        let output = layout.output(output_name)?;
        let tile = window_layout
            .get("tile_pos_in_workspace_view")
            .and_then(|v| v.as_array())?;
        let left = tile.first().and_then(|v| v.as_f64())? as i32 + output.left;
        let top = tile.get(1).and_then(|v| v.as_f64())? as i32 + output.top;
        return Some((left, top, width, height));
    }
    None
}

/// niri msg 的 JSON 输出；niri 不在（其他合成器）时返回 None。
fn niri_json(args: &[&str]) -> Option<serde_json::Value> {
    let output = std::process::Command::new("niri")
        .args(args)
        .stdin(std::process::Stdio::null())
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    serde_json::from_slice(&output.stdout).ok()
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