//! 设备详情页「远程终端」的会话选择器。
//!
//! 点「远程终端」先列出远端主机上已经存在的 shell，让用户接回其中一个或
//! 新建一个；选定之后仍然交给系统终端模拟器去跑真正的终端窗口。清单由 GUI
//! worker 在后台拉取，这里只维护对话框状态，并用登录代际丢弃过期结果。
use super::DeviceCenterApp;
use super::StatusMessage;
use super::messages::GuiCommand;
use crate::session::controller::terminal::RemoteSession;
use crate::ui::theme::{AMBER, GREEN, MUTED, RED};
use egui::RichText;
use std::time::Instant;
use tokio::sync::mpsc::UnboundedSender;

/// 一次最多展示多少行，避免远端返回异常大的清单拖垮界面。
const MAX_ROWS: usize = 512;
/// 用户可见字符串的长度上限（按字符计）。
const MAX_TEXT: usize = 96;
/// 错误原文的展示长度上限。
const MAX_ERROR: usize = 400;

/// 用户在选择器里做出的选择，由 `DeviceCenterApp` 执行。
pub(super) enum Action {
    /// 接入已有的远端会话。
    Attach(u32),
    /// 新建一个远端 Shell。
    New,
    /// 结束一个已有的远端会话（CLOSE，会终止 Shell）。
    End(u32),
    /// 重新拉取会话清单。
    Refresh,
    /// 关闭选择器。
    Close,
}

/// 「远程终端」会话选择器的状态。
pub(super) struct Picker {
    commands: UnboundedSender<GuiCommand>,
    open: bool,
    device_id: String,
    alias: String,
    generation: u64,
    loading: bool,
    error: Option<String>,
    sessions: Vec<RemoteSession>,
    last_loaded: Option<Instant>,
    /// 正在等待主机确认结束的会话；按钮在此期间禁用。
    ending: Option<u32>,
    /// 已点「结束」、等待用户二次确认的会话。
    confirm_end: Option<u32>,
}

impl Picker {
    pub(super) fn new(commands: UnboundedSender<GuiCommand>) -> Self {
        Self {
            commands,
            open: false,
            device_id: String::new(),
            alias: String::new(),
            generation: 0,
            loading: false,
            error: None,
            sessions: Vec::new(),
            last_loaded: None,
            ending: None,
            confirm_end: None,
        }
    }

    pub(super) fn is_open(&self) -> bool {
        self.open
    }

    pub(super) fn device_id(&self) -> &str {
        &self.device_id
    }

    /// 登录代际改变（登录成功、退出、会话失效）时丢掉清单，与其它临时
    /// 对话框一样，不把上一次登录的会话带到下一次。
    pub(super) fn sync_generation(&mut self, generation: u64) {
        if self.generation != generation {
            self.close();
            self.generation = generation;
        }
    }

    pub(super) fn close(&mut self) {
        self.open = false;
        self.device_id.clear();
        self.alias.clear();
        self.loading = false;
        self.error = None;
        self.sessions.clear();
        self.last_loaded = None;
        self.ending = None;
        self.confirm_end = None;
    }

    /// 打开选择器并立即请求一次清单。代际由调用方从当前 `login_generation`
    /// 传入，刚登录就点击也不会把结果算到上一次登录上。
    pub(super) fn open(
        &mut self,
        ctx: &egui::Context,
        generation: u64,
        device_id: String,
        alias: String,
    ) {
        self.generation = generation;
        self.open = true;
        self.device_id = device_id;
        self.alias = alias;
        self.sessions.clear();
        self.error = None;
        self.last_loaded = None;
        self.ending = None;
        self.confirm_end = None;
        self.loading = true;
        ctx.request_repaint();
        if !self.request(self.generation) {
            self.loading = false;
            self.error = Some("设备后台服务已经停止".into());
        }
    }

    /// 把列会话的命令交给 worker；发送失败说明后台线程已经退出。
    pub(super) fn request(&self, generation: u64) -> bool {
        self.commands
            .send(GuiCommand::RemoteTerminalSessions {
                generation,
                target: self.device_id.clone(),
            })
            .is_ok()
    }

    pub(super) fn refresh(&mut self, generation: u64) {
        if !self.open {
            return;
        }
        self.loading = true;
        self.error = None;
        if !self.request(generation) {
            self.loading = false;
            self.error = Some("设备后台服务已经停止".into());
        }
    }

    /// 启动失败时保留清单，把原因显示在对话框里。
    pub(super) fn set_error(&mut self, error: String) {
        self.loading = false;
        self.error = Some(clean(&error, MAX_ERROR));
    }

    /// 二次确认后结束一个远端会话。成功/失败都通过 worker 事件回到这里。
    pub(super) fn end(&mut self, generation: u64, session: u32) -> bool {
        if !self.open || self.ending.is_some() {
            return false;
        }
        self.confirm_end = None;
        self.ending = Some(session);
        self.error = None;
        let sent = self
            .commands
            .send(GuiCommand::RemoteTerminalEnd {
                generation,
                target: self.device_id.clone(),
                session,
            })
            .is_ok();
        if !sent {
            self.ending = None;
            self.error = Some("设备后台服务已经停止".into());
        }
        sent
    }

    /// 结束请求的回复：成功后刷新清单，失败保留清单并显示原因。
    pub(super) fn apply_end_result(
        &mut self,
        current_generation: u64,
        generation: u64,
        target: &str,
        session: u32,
        result: std::result::Result<(), String>,
    ) {
        if !self.open
            || generation != self.generation
            || generation != current_generation
            || target != self.device_id
            || self.ending != Some(session)
        {
            return;
        }
        self.ending = None;
        match result {
            Ok(()) => self.refresh(current_generation),
            Err(error) => self.error = Some(clean(&format!("结束会话失败：{error}"), MAX_ERROR)),
        }
    }

    /// 应用 worker 返回的结果；代际或目标不匹配的过期结果直接丢弃。
    pub(super) fn apply_result(
        &mut self,
        current_generation: u64,
        generation: u64,
        target: &str,
        result: std::result::Result<Vec<RemoteSession>, String>,
    ) -> bool {
        if !self.open
            || generation != self.generation
            || generation != current_generation
            || target != self.device_id
        {
            return false;
        }
        let first_load = self.last_loaded.is_none();
        self.loading = false;
        self.last_loaded = Some(Instant::now());
        match result {
            Ok(sessions) => {
                let sessions = sanitize(sessions);
                // 远端没有可接回的 Shell：首次打开时直接新建，不把空清单
                // 摆在用户面前。只有首次加载这样做，手动刷新不会反复开。
                let auto_new =
                    first_load && !sessions.iter().any(|session| session.state != "exited");
                self.sessions = sessions;
                self.error = None;
                auto_new
            }
            Err(error) => {
                self.sessions.clear();
                self.error = Some(clean(&describe_list_error(&error), MAX_ERROR));
                false
            }
        }
    }

    /// 渲染模态对话框；对话框关闭或选中条目时返回对应的动作。
    pub(super) fn show(&mut self, ctx: &egui::Context) -> Option<Action> {
        if !self.open {
            return None;
        }
        let mut action = None;
        let mut confirm_end = self.confirm_end;
        let response = egui::Modal::new(egui::Id::new("remote-terminal-picker"))
            .frame(crate::ui::controls::dialog_frame())
            .show(ctx, |ui| {
                // Never ask for a negative width on a very narrow window.
                ui.set_width(560.0_f32.min((ctx.content_rect().width() - 60.0).max(320.0)));
                if crate::ui::controls::dialog_header(
                    ui,
                    "远程终端",
                    crate::ui::controls::DialogIcon::Info,
                    true,
                ) {
                    action = Some(Action::Close);
                }
                ui.add(egui::Label::new(RichText::new(&self.alias).strong()).wrap());
                ui.label(
                    RichText::new(&self.device_id)
                        .monospace()
                        .small()
                        .color(MUTED),
                );
                ui.label(
                    RichText::new(
                        "选择要接回的远端 Shell，或新建一个。关闭系统终端只会断开这一端，远端 Shell 继续运行。",
                    )
                    .small()
                    .color(MUTED),
                );
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(
                            !self.loading,
                            crate::ui::controls::secondary("刷新"),
                        )
                        .clicked()
                    {
                        action = Some(Action::Refresh);
                    }
                    if self.loading {
                        ui.spinner();
                        ui.label(RichText::new("正在读取远端终端会话…").small().color(MUTED));
                    } else if let Some(at) = self.last_loaded {
                        let seconds = at.elapsed().as_secs();
                        let text = if seconds < 5 {
                            "刚刚更新".to_owned()
                        } else {
                            format!("{seconds} 秒前更新")
                        };
                        ui.label(RichText::new(text).small().color(MUTED));
                    }
                });
                if let Some(error) = &self.error {
                    ui.add_space(6.0);
                    ui.colored_label(AMBER, format!("读取会话失败：{error}"));
                }
                if let Some(session) = self.ending {
                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(
                            RichText::new(format!("正在结束会话 {session}…"))
                                .small()
                                .color(MUTED),
                        );
                    });
                }
                if let Some(session) = confirm_end {
                    let name = self
                        .sessions
                        .iter()
                        .find(|row| row.id == session)
                        .map(row_name)
                        .unwrap_or_else(|| format!("编号 {session}"));
                    ui.add_space(6.0);
                    ui.colored_label(
                        RED,
                        format!(
                            "确认结束「{name}」（编号 {session}）？远端 Shell 会被终止，未保存的内容会丢失。"
                        ),
                    );
                    ui.horizontal(|ui| {
                        if ui
                            .add_enabled(
                                self.ending.is_none(),
                                crate::ui::controls::primary("确认结束"),
                            )
                            .clicked()
                        {
                            action = Some(Action::End(session));
                            confirm_end = None;
                        }
                        if ui.button("取消").clicked() {
                            confirm_end = None;
                        }
                    });
                }
                ui.add_space(8.0);
                if self.sessions.is_empty() {
                    if !self.loading {
                        ui.label(
                            RichText::new(if self.error.is_some() {
                                "可以刷新重试，或直接新建 Shell。"
                            } else {
                                "远端没有可接回的终端会话。"
                            })
                            .small()
                            .color(MUTED),
                        );
                    }
                } else {
                    egui::ScrollArea::vertical()
                        .max_height(260.0)
                        .show(ui, |ui| {
                            for session in &self.sessions {
                                ui.horizontal(|ui| {
                                    ui.label(RichText::new(row_name(session)).strong());
                                    ui.label(
                                        RichText::new(session.state_label())
                                            .small()
                                            .color(state_color(session)),
                                    );
                                    ui.with_layout(
                                        egui::Layout::right_to_left(egui::Align::Center),
                                        |ui| {
                                            // 0 在协议里表示「新建」，列出来的
                                            // 0 号会话接不回去，只能新建。
                                            let (can_attach, hint) = if session.id == 0 {
                                                (false, "编号 0 的会话无法接回（0 表示新建）")
                                            } else if session.state == "exited" {
                                                (false, "已退出的会话不能接入")
                                            } else {
                                                (true, "")
                                            };
                                            if ui
                                                .add_enabled(
                                                    self.ending.is_none()
                                                        && session.state != "exited",
                                                    crate::ui::controls::secondary("结束"),
                                                )
                                                .on_disabled_hover_text(if session.state == "exited" {
                                                    "会话已经退出"
                                                } else {
                                                    "结束远端 Shell（会终止进程）"
                                                })
                                                .clicked()
                                            {
                                                confirm_end = Some(session.id);
                                            }
                                            if ui
                                                .add_enabled(
                                                    can_attach,
                                                    crate::ui::controls::secondary("接入"),
                                                )
                                                .on_disabled_hover_text(hint)
                                                .clicked()
                                            {
                                                action = Some(Action::Attach(session.id));
                                            }
                                            ui.label(
                                                RichText::new(format!("编号 {}", session.id))
                                                    .small()
                                                    .color(MUTED),
                                            );
                                        },
                                    );
                                });
                                ui.label(
                                    RichText::new(format!(
                                        "{} · 创建于 {}",
                                        row_shell(session),
                                        stamp(session.created_ms)
                                    ))
                                    .small()
                                    .color(MUTED),
                                );
                                ui.add_space(6.0);
                            }
                        });
                }
                let (accept, dismiss) = crate::ui::controls::dialog_actions(
                    ui,
                    Some(crate::ui::controls::DialogAction::new("新建 Shell")),
                    Some("取消"),
                );
                if accept {
                    action = Some(Action::New);
                }
                if dismiss {
                    action = Some(Action::Close);
                }
            });
        if action.is_none() && response.should_close() {
            action = Some(Action::Close);
        }
        if !matches!(action, Some(Action::End(_))) {
            self.confirm_end = confirm_end;
        }
        action
    }
}

impl DeviceCenterApp {
    /// 执行选择器返回的动作：刷新、关闭，或把选中的会话交给系统终端。
    pub(super) fn handle_remote_terminal_action(&mut self, action: Action) {
        match action {
            Action::Refresh => self.remote_terminal.refresh(self.login_generation),
            Action::Close => self.remote_terminal.close(),
            Action::Attach(id) if self.remote_terminal.is_open() => {
                self.launch_remote_terminal(Some(id));
            }
            Action::New if self.remote_terminal.is_open() => self.launch_remote_terminal(None),
            Action::End(id) if self.remote_terminal.is_open() => {
                self.remote_terminal.end(self.login_generation, id);
            }
            _ => {}
        }
    }

    pub(super) fn launch_remote_terminal(&mut self, attach: Option<u32>) {
        let device_id = self.remote_terminal.device_id().to_owned();
        match crate::features::host::terminal::local::open_remote_in_terminal(&device_id, attach) {
            Ok(()) => {
                self.remote_terminal.close();
                self.status = StatusMessage::success(match attach {
                    Some(id) => format!("正在系统终端中接入会话 {id}"),
                    None => "正在系统终端中新建 Shell".into(),
                });
            }
            Err(error) => {
                // 终端模拟器起不来时保留清单，把原因显示在对话框里。
                tracing::warn!(%error, "failed to open official UU terminal emulator");
                self.remote_terminal.set_error(format!("{error:#}"));
            }
        }
    }
}

/// 把「远端不回应清单」说成人话：这类主机仍可直接新建 Shell，不必让用户
/// 以为是启动失败。
fn describe_list_error(error: &str) -> String {
    if error.contains("timed out") {
        "远端没有回应会话清单请求（这台主机可能不支持列出会话）；可以直接新建 Shell。".into()
    } else {
        error.to_owned()
    }
}

/// 只保留可显示的行数和字符串，去掉控制字符。
fn sanitize(sessions: Vec<RemoteSession>) -> Vec<RemoteSession> {
    sessions
        .into_iter()
        .take(MAX_ROWS)
        .map(|mut session| {
            session.name = clean(&session.name, MAX_TEXT);
            session.shell = clean(&session.shell, MAX_TEXT);
            session.state = clean(&session.state, 32);
            session
        })
        .collect()
}

fn clean(value: &str, limit: usize) -> String {
    value
        .chars()
        .filter(|c| !c.is_control())
        .take(limit)
        .collect()
}

fn row_name(session: &RemoteSession) -> String {
    let name = clean(&session.name, MAX_TEXT);
    if name.trim().is_empty() {
        "未命名会话".into()
    } else {
        name
    }
}

fn row_shell(session: &RemoteSession) -> String {
    let shell = clean(&session.shell, MAX_TEXT);
    if shell.trim().is_empty() {
        "未知 Shell".into()
    } else {
        shell
    }
}

fn state_color(session: &RemoteSession) -> egui::Color32 {
    match session.state.as_str() {
        "running" => GREEN,
        "exited" => RED,
        _ => MUTED,
    }
}

/// 毫秒时间戳按本地时区显示，与终端会话页的写法一致。
fn stamp(milliseconds: i64) -> String {
    chrono::DateTime::from_timestamp_millis(milliseconds)
        .map(|time| {
            time.with_timezone(&chrono::Local)
                .format("%Y-%m-%d %H:%M")
                .to_string()
        })
        .unwrap_or_else(|| "未知".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_list_timeout_tells_the_user_a_new_shell_is_still_possible() {
        let text = describe_list_error("remote terminal request timed out: deadline has elapsed");
        assert!(text.contains("不支持列出会话"), "{text}");
        assert!(text.contains("新建 Shell"), "{text}");
    }

    #[test]
    fn other_errors_keep_their_original_text() {
        assert_eq!(describe_list_error("permission denied"), "permission denied");
    }

    fn picker_with_session() -> (Picker, tokio::sync::mpsc::UnboundedReceiver<GuiCommand>) {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let mut picker = Picker::new(tx);
        picker.open = true;
        picker.device_id = "device-1".into();
        picker.alias = "测试设备".into();
        picker.generation = 4;
        picker.sessions = vec![RemoteSession {
            id: 9,
            name: "session".into(),
            shell: "powershell".into(),
            state: "running".into(),
            created_ms: 0,
            last_active_ms: 0,
        }];
        (picker, rx)
    }

    #[test]
    fn ending_a_session_sends_close_and_refreshes_after_success() {
        let (mut picker, mut commands) = picker_with_session();
        assert!(picker.end(4, 9));
        assert_eq!(picker.ending, Some(9));
        match commands.try_recv().expect("end command") {
            GuiCommand::RemoteTerminalEnd {
                generation,
                target,
                session,
            } => {
                assert_eq!(generation, 4);
                assert_eq!(target, "device-1");
                assert_eq!(session, 9);
            }
            _ => panic!("expected RemoteTerminalEnd"),
        }
        picker.apply_end_result(4, 4, "device-1", 9, Ok(()));
        assert_eq!(picker.ending, None);
        assert!(picker.loading);
        match commands.try_recv().expect("refresh command") {
            GuiCommand::RemoteTerminalSessions { generation, target } => {
                assert_eq!(generation, 4);
                assert_eq!(target, "device-1");
            }
            _ => panic!("expected RemoteTerminalSessions"),
        }
    }

    #[test]
    fn a_failed_end_keeps_the_list_and_reports_the_error() {
        let (mut picker, _commands) = picker_with_session();
        assert!(picker.end(4, 9));
        picker.apply_end_result(4, 4, "device-1", 9, Err("permission denied".into()));
        assert_eq!(picker.ending, None);
        assert_eq!(picker.sessions.len(), 1);
        assert!(picker.error.as_deref().unwrap_or_default().contains("permission denied"));
    }


    #[test]
    fn an_empty_remote_list_auto_starts_a_new_shell_only_on_first_load() {
        let (mut picker, _commands) = picker_with_session();
        picker.sessions.clear();
        picker.last_loaded = None;
        assert!(
            picker.apply_result(4, 4, "device-1", Ok(Vec::new())),
            "first empty load should start a shell"
        );
        // A manual refresh must not keep spawning shells.
        assert!(!picker.apply_result(4, 4, "device-1", Ok(Vec::new())));
    }

    #[test]
    fn a_running_remote_session_does_not_auto_start_a_shell() {
        let (mut picker, _commands) = picker_with_session();
        picker.last_loaded = None;
        assert!(!picker.apply_result(
            4,
            4,
            "device-1",
            Ok(vec![RemoteSession {
                id: 9,
                name: "session".into(),
                shell: "powershell".into(),
                state: "running".into(),
                created_ms: 0,
                last_active_ms: 0,
            }])
        ));
    }

}
