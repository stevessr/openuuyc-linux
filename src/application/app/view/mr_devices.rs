//! MR 与头显页：本机 ADB/Android 设备投屏（scrcpy）与内置 PCVR 运行时偏好。
//! ADB 设备访问独立于 UU 账号与被控服务，页面只在 Linux 上出现。
use super::*;
use crate::platform::linux::mr_device::{self, Device};
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct State {
    devices: Vec<Device>,
    tools: Vec<(&'static str, bool)>,
    busy: bool,
    message: String,
    display: u32,
    // 进入页面自动刷新一次；此后只在用户点击刷新时再查询 ADB。
    refreshed: bool,
}
type Shared = Arc<Mutex<State>>;

fn work(state: Shared, ctx: egui::Context, operation: Option<(String, u32, bool)>) {
    std::thread::spawn(move || {
        let result = (|| -> anyhow::Result<(Option<Vec<Device>>, Vec<(&'static str, bool)>)> {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?;
            rt.block_on(async {
                if let Some((serial, display, control)) = operation {
                    mr_device::mirror(&serial, display, control).await?;
                    Ok((None, Vec::new()))
                } else {
                    let tools = mr_device::tool_check().await;
                    Ok((Some(mr_device::discover().await?), tools))
                }
            })
        })();
        let mut s = state.lock().unwrap_or_else(|e| e.into_inner());
        s.busy = false;
        match result {
            Ok((Some(devices), tools)) => {
                s.tools = tools;
                s.message = if devices.is_empty() {
                    "没有发现 ADB 设备；请连接 USB 数据线，或在头显配对无线调试后重试".into()
                } else {
                    format!("发现 {} 台设备；仅「已授权」设备可打开投屏窗口", devices.len())
                };
                s.devices = devices;
            }
            Ok((None, _)) => s.message = "已请求打开独立投屏窗口；关闭该窗口即停止投屏".into(),
            Err(error) => s.message = format!("{error:#}"),
        }
        ctx.request_repaint();
    });
}

/// 设备授权状态徽标：颜色区分可用性，一眼看出哪台能开窗口。
fn state_badge(ui: &mut egui::Ui, state: &str) {
    let (text, color) = match state {
        "device" => ("已授权", GREEN),
        "unauthorized" => ("待头显授权", AMBER),
        "offline" => ("离线", MUTED),
        "recovery" => ("Recovery", MUTED),
        "sideload" => ("Sideload", MUTED),
        "bootloader" => ("Bootloader", MUTED),
        _ => ("未知状态", MUTED),
    };
    let galley = ui.painter().layout_no_wrap(
        text.to_owned(),
        FontId::proportional(crate::ui::theme::TINY),
        color,
    );
    let (rect, _) = ui.allocate_exact_size(galley.size() + vec2(12.0, 6.0), Sense::hover());
    let painter = ui.painter();
    painter.rect_filled(rect, 4.0, color.gamma_multiply(0.16));
    painter.galley(rect.left_top() + vec2(6.0, 3.0), galley, color);
}

/// adb / scrcpy 可用性一行：缺什么直接给安装命令。
fn tools_row(ui: &mut egui::Ui, tools: &[(&'static str, bool)]) {
    if tools.is_empty() {
        return;
    }
    let missing: Vec<&str> = tools
        .iter()
        .filter(|(_, ok)| !ok)
        .map(|(name, _)| *name)
        .collect();
    ui.horizontal_wrapped(|ui| {
        for (name, ok) in tools {
            let (mark, color) = if *ok {
                ("✓", GREEN)
            } else {
                ("✗", AMBER)
            };
            ui.label(RichText::new(format!("{mark} {name}")).small().color(color));
        }
        if !missing.is_empty() {
            ui.label(
                RichText::new(format!(
                    "缺少 {}；例如 Arch/Omarchy：sudo pacman -S android-tools scrcpy",
                    missing.join("、")
                ))
                .small()
                .color(AMBER),
            );
        }
    });
}

/// 一台设备一张卡片；返回 Some((序列号, 显示 ID, 是否控制)) 表示用户请求投屏。
fn device_card(
    ui: &mut egui::Ui,
    device: &Device,
    display: u32,
    busy: bool,
) -> Option<(String, u32, bool)> {
    let mut action = None;
    let allowed = !busy && device.state == "device";
    let disabled_hint = if busy {
        "正在处理上一个请求…"
    } else {
        "先在头显上确认 ADB 调试授权，再点「刷新已连接设备」"
    };
    crate::ui::controls::section_frame()
        .inner_margin(theme::DIALOG_MARGIN)
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        ui.strong(&device.model);
                        state_badge(ui, &device.state);
                    });
                    ui.label(
                        RichText::new(&device.serial)
                            .monospace()
                            .small()
                            .color(MUTED),
                    );
                });
                ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                    if ui
                        .add_enabled(allowed, crate::ui::controls::secondary("观看并控制"))
                        .on_disabled_hover_text(disabled_hint)
                        .clicked()
                    {
                        action = Some((device.serial.clone(), display, true));
                    }
                    if ui
                        .add_enabled(allowed, crate::ui::controls::secondary("只读观看"))
                        .on_disabled_hover_text(disabled_hint)
                        .clicked()
                    {
                        action = Some((device.serial.clone(), display, false));
                    }
                });
            });
        });
    action
}

fn adb_section(ui: &mut egui::Ui) {
    section(ui, "本地 MR / Android 设备 · ADB");
    ui.small(
        "用本机 adb + scrcpy 把头显或 Android 设备投到独立窗口：需在设备系统里自己开启并确认 ADB 调试授权；不会自动打开调试开关、无线端口或接管未授权设备。",
    );
    ui.small(
        "只投射固件允许的 Android 显示内容，不保证完整 MR 合成画面；默认不传音频、不自动同步剪贴板；每台设备仅一个窗口，切换模式或显示 ID 前先关闭原窗口。",
    );
    let id = egui::Id::new("local-mr-adb-state");
    let state = ui
        .ctx()
        .data_mut(|d| d.get_temp_mut_or_default::<Shared>(id).clone());
    let mut s = state.lock().unwrap_or_else(|e| e.into_inner());
    // None 表示刷新设备清单，Some 表示为指定设备开投屏窗口。
    let mut request: Option<Option<(String, u32, bool)>> = None;
    if !s.refreshed && !s.busy {
        s.refreshed = true;
        request = Some(None);
    }
    ui.add_space(6.0);
    let mut refresh_clicked = false;
    ui.horizontal(|ui| {
        refresh_clicked = ui
            .add_enabled(!s.busy, crate::ui::controls::secondary("刷新已连接设备"))
            .clicked();
        ui.label("显示 ID");
        ui.add(egui::DragValue::new(&mut s.display).range(0..=10000))
            .on_hover_text("要投射的 Android 显示器编号；默认 0 为主屏");
        ui.small("默认 0；其他屏（如虚拟屏）可用 scrcpy --list-displays 查询");
    });
    if refresh_clicked && !s.busy {
        request = Some(None);
    }
    tools_row(ui, &s.tools);
    for device in &s.devices {
        if let Some(operation) = device_card(ui, device, s.display, s.busy) {
            request = Some(Some(operation));
        }
    }
    if s.busy {
        ui.horizontal(|ui| {
            ui.spinner();
            ui.label(RichText::new("正在与 ADB 通信…").small().color(MUTED));
        });
    }
    if !s.message.is_empty() {
        ui.label(&s.message);
    }
    if let Some(operation) = request {
        s.busy = true;
        drop(s);
        work(state, ui.ctx().clone(), operation);
    }
}

fn pcvr_section(app: &mut DeviceCenterApp, ui: &mut egui::Ui) {
    section(ui, "内置 PCVR · OpenXR 优先");
    use crate::pcvr::runtime::{self, Preference};
    let current = runtime::preference();
    if let Err(error) = &current {
        ui.label(egui::RichText::new(error.to_string()).color(theme::AMBER));
    }
    let old = current.unwrap_or_default();
    let mut selected = old;
    form_row(ui, "运行时偏好", "仅为游戏进程选择运行时，不改全局 OpenXR", |ui| {
        egui::ComboBox::from_id_salt("pcvr-runtime-preference")
            .width(238.0)
            .selected_text(selected.label())
            .show_ui(ui, |ui| {
                for preference in Preference::ALL {
                    ui.selectable_value(&mut selected, preference, preference.label());
                }
            });
    });
    if selected != old {
        if let Err(error) = runtime::set_preference(selected) {
            app.status = StatusMessage::error(error.to_string());
        }
    }
    ui.label(runtime::status());
    ui.add_space(4.0);
    if ui
        .add(crate::ui::controls::secondary(
            "复制 VRChat / OpenVR 的 Steam 启动选项",
        ))
        .clicked()
    {
        match runtime::plan().and_then(|plan| plan.steam_options(runtime::Api::OpenVr)) {
            Ok(options) => {
                ui.ctx().copy_text(options);
                app.status = StatusMessage::success(
                    "已复制；粘贴到该游戏的 Steam 启动选项，不会修改全局运行时",
                );
            }
            Err(error) => app.status = StatusMessage::error(error.to_string()),
        }
    }
    ui.small("Steam 已运行时需使用该游戏的启动选项，不能只给 steam -applaunch 设置环境。");
    ui.label("双眼链路已有代码与本地测试：内置 OpenXR 走受管设备与合成器导出，SteamVR 既有场景可在 PICO 沉浸菜单「附加已有 VR 应用 → 附加 SteamVR 运行中场景」请求只读双眼镜像（不会启动 SteamVR/游戏）。真实游戏/头显验收仍未完成。");
    ui.small("SteamVR 镜像需要本机 SteamVR 已在运行且场景正在渲染；命令行诊断：OpenUUYC pico-vr steamvr-mirror-probe / steamvr-mirror-attach。");
    if ui
        .add(crate::ui::controls::secondary(
            "安装 / 更新 OpenUUYC 内置 OpenVR 驱动",
        ))
        .clicked()
    {
        app.status = match crate::pcvr::install_driver() {
            Ok(()) => StatusMessage::success(
                "内置追踪驱动已安装；请自行重启 SteamVR 后，在 PICO 沉浸菜单启用追踪诊断（追踪驱动不是双眼镜像）",
            ),
            Err(e) => StatusMessage::error(e.to_string()),
        };
    }
}

impl DeviceCenterApp {
    pub(super) fn mr_devices_page(&mut self, ui: &mut egui::Ui) {
        ui.label(RichText::new("MR 与头显").size(theme::TITLE).strong());
        ui.add_space(18.0);
        self.alert(ui);
        crate::ui::controls::page_scroll("center-mr-scroll").show(ui, |ui| {
            adb_section(ui);
            pcvr_section(self, ui);
        });
    }
}
