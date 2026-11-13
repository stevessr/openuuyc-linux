//! User-level "install to system" entry for portable Linux builds.
//!
//! Windows has the component installer; Linux packages handle system-wide
//! installs. This page covers the common case: a portable tarball/binary that
//! should get a desktop entry, icon and `uuremote://` handler.
use super::*;
use crate::platform::linux::install::{self, Status};
use std::sync::mpsc::{self, Receiver};

enum Operation {
    Install,
    Uninstall,
}

#[derive(Default)]
pub(in crate::application::app) struct Manager {
    status: Option<Result<Status, String>>,
    pending: Option<Receiver<Result<Status, String>>>,
    error: Option<String>,
    confirm: Option<Operation>,
}

impl Manager {
    fn poll(&mut self) {
        let Some(pending) = self.pending.as_ref() else {
            return;
        };
        match pending.try_recv() {
            Ok(result) => {
                self.pending = None;
                match result {
                    Ok(status) => {
                        self.status = Some(Ok(status));
                        self.error = None;
                    }
                    Err(error) => self.error = Some(error),
                }
            }
            Err(mpsc::TryRecvError::Empty) => (),
            Err(mpsc::TryRecvError::Disconnected) => {
                self.pending = None;
                self.error = Some("安装任务已中断".into());
            }
        }
    }

    fn start(&mut self, operation: Operation, ctx: egui::Context) {
        if self.pending.is_some() {
            return;
        }
        let (tx, rx) = mpsc::channel();
        self.pending = Some(rx);
        self.error = None;
        std::thread::spawn(move || {
            let result = match operation {
                Operation::Install => install::install(),
                Operation::Uninstall => install::uninstall(),
            }
            .map_err(|error| format!("{error:#}"));
            let _ = tx.send(result);
            ctx.request_repaint();
        });
    }

    pub(super) fn render(&mut self, ui: &mut egui::Ui) {
        self.poll();
        if self.status.is_none() && self.pending.is_none() {
            self.status = Some(Ok(install::status()));
        }
        section(ui, "安装到系统");
        let current = std::env::current_exe().unwrap_or_default();
        match self.status.as_ref() {
            Some(Ok(status)) if status.installed => {
                let up_to_date = status.binary == current;
                form_row(
                    ui,
                    "当前状态",
                    if up_to_date {
                        "已安装到用户目录"
                    } else {
                        "已安装，但当前运行的是另一个文件"
                    },
                    |ui| {
                        ui.label(
                            RichText::new(status.binary.display().to_string())
                                .monospace()
                                .small(),
                        );
                    },
                );
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(
                            self.pending.is_none(),
                            crate::ui::controls::primary(if up_to_date {
                                "重新安装"
                            } else {
                                "更新安装"
                            }),
                        )
                        .clicked()
                    {
                        self.confirm = Some(Operation::Install);
                    }
                    if ui
                        .add_enabled(
                            self.pending.is_none(),
                            crate::ui::controls::secondary("卸载"),
                        )
                        .on_hover_text("移除用户目录里的程序、桌面入口和图标，保留账号与设置")
                        .clicked()
                    {
                        self.confirm = Some(Operation::Uninstall);
                    }
                });
            }
            Some(Ok(status)) => {
                form_row(ui, "当前状态", "便携运行", |ui| {
                    ui.label(
                        RichText::new(status.current.display().to_string())
                            .monospace()
                            .small(),
                    );
                });
                if ui
                    .add_enabled(
                        self.pending.is_none(),
                        crate::ui::controls::primary("安装到系统"),
                    )
                    .clicked()
                {
                    self.confirm = Some(Operation::Install);
                }
            }
            Some(Err(error)) => {
                ui.colored_label(AMBER, format!("读取安装状态失败：{error}"));
            }
            None => {
                ui.spinner();
            }
        }
        ui.label(
            RichText::new(
                "安装到 ~/.local/bin、桌面入口和图标，并登记 uuremote:// 链接；不需要 root，卸载不会删除账号与设置。",
            )
            .small()
            .color(MUTED),
        );
        if self.pending.is_some() {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label(RichText::new("正在处理…").small().color(MUTED));
            });
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_millis(200));
        }
        if let Some(error) = &self.error {
            ui.colored_label(AMBER, format!("安装失败：{error}"));
        }
        self.confirm_modal(ui.ctx().clone());
    }

    fn confirm_modal(&mut self, ctx: egui::Context) {
        let Some(operation) = self.confirm.as_ref() else {
            return;
        };
        let install = matches!(operation, Operation::Install);
        let mut accept = false;
        let mut dismiss = false;
        egui::Modal::new(egui::Id::new("linux-install-confirm"))
            .frame(crate::ui::controls::dialog_frame())
            .show(&ctx, |ui| {
                ui.set_width(430.0);
                dismiss = crate::ui::controls::dialog_header(
                    ui,
                    if install { "安装到系统" } else { "卸载" },
                    crate::ui::controls::DialogIcon::Warning,
                    true,
                );
                if install {
                    ui.label(
                        "把当前程序复制到 ~/.local/bin/OpenUUYC，写入桌面入口、图标，并让系统用本程序打开 uuremote:// 链接。",
                    );
                    ui.label("不会修改账号、设置或驱动，也不需要管理员权限。");
                } else {
                    ui.label("移除用户目录里的程序、桌面入口和图标；账号、设置与本机数据保留。");
                }
                let (yes, close) = crate::ui::controls::dialog_actions(
                    ui,
                    Some(crate::ui::controls::DialogAction::new(if install {
                        "安装"
                    } else {
                        "卸载"
                    })),
                    Some("取消"),
                );
                accept = yes;
                dismiss |= close;
            });
        if accept {
            let operation = self.confirm.take().unwrap();
            self.start(operation, ctx);
        } else if dismiss {
            self.confirm = None;
        }
    }
}
