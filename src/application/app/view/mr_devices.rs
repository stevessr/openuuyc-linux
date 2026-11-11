//! Native PICO streaming guidance and PCVR runtime preferences.
use super::*;

fn connection_section(ui: &mut egui::Ui) {
    section(ui, "PICO 原生串流 · UU 直连");
    ui.label("在头显中打开 OpenUUYC PICO，登录 UU 账号并选择这台电脑，然后点击「沉浸式连接」。");
    ui.label("桌面与应用窗口：连接后在头显的屏幕菜单中选择目标，画面通过 UU WebRTC 视频轨传输。");
    ui.label("PCVR 双眼画面：在头显沉浸菜单中选择「附加已有 VR 应用 → 附加 SteamVR 运行中场景」。电脑上需已有正在渲染的 SteamVR 场景。");
    ui.label(RichText::new("双眼串流与追踪仍需真实游戏和头显联测；下方运行时与驱动设置用于准备电脑端环境。").small().color(MUTED));
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
            connection_section(ui);
            pcvr_section(self, ui);
        });
    }
}
