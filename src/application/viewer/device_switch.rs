//! Local player navigation. Device selection never changes the UU control contract.
use std::sync::{Arc, Mutex};

use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use winit::window::WindowId;

use crate::account::api::DeviceInfo;
use crate::account::client::AuthenticatedClient;
use crate::features::stream_control::SimulatedDeviceRole;

#[derive(Clone, Debug)]
pub(crate) struct SimulatedDevicesMenu {
    pub alias: String,
    pub active_role: SimulatedDeviceRole,
    pub display_count: usize,
    pub window_count: usize,
    pub other_role_open_in_window: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SimulatedDeviceAction {
    SwitchRole(SimulatedDeviceRole),
    OpenRoleWindow(SimulatedDeviceRole),
}

pub(super) fn show_simulated_devices_section(
    ui: &mut egui::Ui,
    info: &SimulatedDevicesMenu,
) -> Option<SimulatedDeviceAction> {
    let mut action = None;
    ui.label(
        egui::RichText::new("当前主机 · 模拟设备分离")
            .size(crate::ui::theme::TINY)
            .color(crate::ui::theme::MUTED),
    );
    ui.add_space(4.0);
    let row_gap = crate::ui::theme::DEVICE_MENU_ROW_GAP;
    ui.spacing_mut().item_spacing.y = row_gap;
    for (role, count) in [
        (SimulatedDeviceRole::Displays, info.display_count),
        (SimulatedDeviceRole::Windows, info.window_count),
    ] {
        let selected = info.active_role == role;
        let label = role.device_title(&info.alias, true);
        let detail = if selected {
            "当前窗口"
        } else if info.other_role_open_in_window {
            "独立窗口已打开"
        } else if count == 0 {
            "暂无源"
        } else {
            "点击切换"
        };
        let response = ui
            .push_id(("simulated-device-role", role.short_label()), |ui| {
                crate::ui::controls::device_menu_row(
                    ui,
                    &label,
                    detail,
                    if count > 0 {
                        crate::ui::theme::GREEN
                    } else {
                        crate::ui::theme::MUTED
                    },
                    selected,
                    !selected && count > 0,
                )
            })
            .inner;
        if response.clicked() {
            action = Some(if info.other_role_open_in_window {
                SimulatedDeviceAction::OpenRoleWindow(role)
            } else {
                SimulatedDeviceAction::SwitchRole(role)
            });
            ui.close();
        }
    }
    let other = info.active_role.other();
    let other_count = match other {
        SimulatedDeviceRole::Displays => info.display_count,
        SimulatedDeviceRole::Windows => info.window_count,
    };
    let open_label = if info.other_role_open_in_window {
        format!("聚焦独立窗口「{}」", other.short_label())
    } else {
        format!("在独立窗口并排打开「{}」", other.short_label())
    };
    let open_detail = format!("{other_count} 个");
    if crate::ui::controls::menu_row(
        ui,
        &open_label,
        &open_detail,
        None,
        other_count > 0,
        false,
    )
    .on_hover_text("将显示器与应用窗口作为两个独立设备窗口并排同时查看")
    .clicked()
    {
        action = Some(SimulatedDeviceAction::OpenRoleWindow(other));
        ui.close();
    }
    action
}

pub(super) fn simulated_only_menu(
    response: &egui::Response,
    simulated: &SimulatedDevicesMenu,
) -> Option<SimulatedDeviceAction> {
    let mut action = None;
    egui::Popup::menu(response)
        .close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside)
        .gap(6.0)
        .width(crate::ui::theme::DEVICE_MENU_WIDTH)
        .show(|ui| {
            super::stream_menu::menu_style(ui);
            ui.set_width(crate::ui::theme::DEVICE_MENU_WIDTH);
            action = show_simulated_devices_section(ui, simulated);
        });
    action
}

pub(crate) struct SwitchRequest {
    pub from: String,
    pub device: DeviceInfo,
    pub window: WindowId,
    pub takeover: Option<crate::session::controller::takeover::Approval>,
}

#[derive(Clone)]
pub(crate) struct DeviceSwitcher {
    client: Arc<AuthenticatedClient>,
    current: String,
    sender: mpsc::Sender<SwitchRequest>,
    runtime: tokio::runtime::Handle,
    cancel: CancellationToken,
    state: Arc<Mutex<PickerState>>,
}

#[derive(Clone, Default)]
struct PickerState {
    devices: Vec<DeviceInfo>,
    loading: bool,
    switching: bool,
    error: Option<String>,
    takeover: Option<(WindowId, DeviceInfo)>,
}

impl DeviceSwitcher {
    pub(crate) fn new(
        client: Arc<AuthenticatedClient>,
        current: String,
        sender: mpsc::Sender<SwitchRequest>,
        cancel: CancellationToken,
    ) -> Self {
        Self {
            client,
            current,
            sender,
            runtime: tokio::runtime::Handle::current(),
            cancel,
            state: Arc::new(Mutex::new(PickerState::default())),
        }
    }

    pub(crate) fn failed(&self, message: String) {
        let mut state = super::mutex_lock(&self.state);
        state.switching = false;
        state.error = Some(message);
    }

    pub(crate) fn require_takeover(&self, device: DeviceInfo, window: WindowId) {
        let mut state = super::mutex_lock(&self.state);
        state.switching = false;
        state.error = None;
        state.takeover = Some((window, device));
    }

    pub(super) fn cancel_takeover(&self, window: WindowId) {
        let mut state = super::mutex_lock(&self.state);
        if state
            .takeover
            .as_ref()
            .is_some_and(|(owner, _)| *owner == window)
        {
            state.takeover = None;
        }
    }

    fn request_switch(
        &self,
        device: DeviceInfo,
        window: WindowId,
        takeover: Option<crate::session::controller::takeover::Approval>,
    ) {
        let mut state = super::mutex_lock(&self.state);
        if state.switching || self.cancel.is_cancelled() {
            return;
        }
        state.switching = true;
        state.error = None;
        if self
            .sender
            .try_send(SwitchRequest {
                from: self.current.clone(),
                device,
                window,
                takeover,
            })
            .is_err()
        {
            state.switching = false;
            state.error = Some("连接已结束，请重新打开观看窗口".into());
        }
    }

    pub(super) fn is_switching(&self) -> bool {
        let state = super::mutex_lock(&self.state);
        state.switching || state.takeover.is_some()
    }

    fn refresh(&self, ctx: &egui::Context) {
        {
            let mut state = super::mutex_lock(&self.state);
            if state.loading || state.switching {
                return;
            }
            state.loading = true;
            state.error = None;
        }
        let this = self.clone();
        let ctx = ctx.clone();
        self.runtime.spawn(async move {
            let result = tokio::select! {
                _ = this.cancel.cancelled() => return,
                result = tokio::time::timeout(std::time::Duration::from_secs(15), this.client.list_devices()) => result,
            };
            let mut state = super::mutex_lock(&this.state);
            state.loading = false;
            match result {
                Ok(Ok(list)) => {
                    state.devices = list.my_binded_devices.into_iter().filter(|device| {
                        device.is_connected()
                            && matches!(device.platform, 1 | 4)
                            && device.device_id != list.current_device.device_id
                            && device.validated_device_id().is_ok()
                    }).collect();
                    state.devices.sort_by(|a, b| {
                        (a.device_id != this.current, &a.alias, &a.device_id)
                            .cmp(&(b.device_id != this.current, &b.alias, &b.device_id))
                    });
                    state.devices.dedup_by(|a, b| a.device_id == b.device_id);
                }
                Ok(Err(error)) => state.error = Some(format!("获取设备失败：{error}")),
                Err(_) => state.error = Some("获取设备超时，请重试".into()),
            }
            ctx.request_repaint();
        });
    }

    #[allow(dead_code)]
    pub(super) fn menu(&self, response: &egui::Response, window: WindowId) {
        let _ = self.menu_with_simulated(response, window, None);
    }

    pub(super) fn menu_with_simulated(
        &self,
        response: &egui::Response,
        window: WindowId,
        simulated: Option<&SimulatedDevicesMenu>,
    ) -> Option<SimulatedDeviceAction> {
        let mut simulated_action = None;
        let takeover = super::mutex_lock(&self.state).takeover.clone();
        if let Some((owner, device)) = takeover
            && owner == window
            && let Some(confirmed) =
                crate::session::controller::takeover::confirmation(&response.ctx, &device)
        {
            super::mutex_lock(&self.state).takeover = None;
            if confirmed {
                let approval = crate::session::controller::takeover::Approval::confirmed(&device);
                self.request_switch(device, window, Some(approval));
            }
        }
        if response.clicked() {
            self.refresh(&response.ctx);
        }
        egui::Popup::menu(response)
            .close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside)
            .gap(6.0)
            .width(crate::ui::theme::DEVICE_MENU_WIDTH)
            .show(|ui| {
                super::stream_menu::menu_style(ui);
                ui.set_width(crate::ui::theme::DEVICE_MENU_WIDTH);
                if let Some(info) = simulated {
                    simulated_action = show_simulated_devices_section(ui, info);
                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);
                }
                let state = super::mutex_lock(&self.state).clone();
                if crate::ui::controls::device_menu_header(ui, state.loading || state.switching) {
                    self.refresh(ui.ctx());
                }
                ui.add_space(4.0);
                crate::ui::controls::observe_notice(
                    ui.ctx(),
                    "viewer-device-switch",
                    "切换设备失败",
                    crate::ui::controls::DialogIcon::Error,
                    state.error.as_deref(),
                );
                crate::ui::controls::progress_notice(
                    ui.ctx(),
                    "switch-device-progress",
                    "切换设备",
                    state.switching.then_some("正在检查目标设备…"),
                );
                if state.devices.is_empty() && !state.loading {
                    ui.weak("没有其他在线电脑");
                }
                // Reserve the actual row height after an asynchronous refresh.
                // Otherwise the popup's previous loading size constrains the scroll
                // viewport to its 64px minimum and clips even a three-device list.
                let row_height = crate::ui::theme::DEVICE_MENU_ROW_HEIGHT;
                let row_gap = crate::ui::theme::DEVICE_MENU_ROW_GAP;
                let list_height = (state.devices.len() as f32 * (row_height + row_gap) - row_gap)
                    .max(0.0)
                    .min(7.0 * (row_height + row_gap) - row_gap)
                    .min((ui.ctx().content_rect().height() - 120.0).max(row_height));
                if !state.devices.is_empty() {
                    egui::ScrollArea::vertical()
                        .min_scrolled_height(list_height)
                        .max_height(list_height)
                        .auto_shrink([false, true])
                        .show(ui, |ui| {
                            ui.spacing_mut().item_spacing.y = row_gap;
                            for device in &state.devices {
                                let selected = device.device_id == self.current;
                                let unavailable =
                                    if !device.controlled_support || !device.controllable {
                                        Some("暂不可连接")
                                    } else {
                                        None
                                    };
                                let alias = if device.alias.is_empty() {
                                    &device.device_id
                                } else {
                                    &device.alias
                                };
                                let duplicate = state
                                    .devices
                                    .iter()
                                    .filter(|d| d.alias == device.alias)
                                    .count()
                                    > 1;
                                let label = if duplicate {
                                    format!(
                                        "{} · {}",
                                        alias,
                                        &device.device_id[device.device_id.len() - 4..]
                                    )
                                } else {
                                    alias.to_owned()
                                };
                                let occupied = device.in_use_by_other_device(&self.client.device_id())
                                    && !selected
                                    && !crate::session::controller::has_gui_connection(
                                        &self.client.device_id(),
                                        &device.device_id,
                                    );
                                let device_status =
                                    crate::application::app::device_status::connection_status(
                                        device,
                                        &self.client.device_id(),
                                    );
                                let detail = device_status.label();
                                let response = ui
                                    .push_id(&device.device_id, |ui| {
                                        crate::ui::controls::device_menu_row(
                                            ui,
                                            &label,
                                            detail,
                                            device_status.color(),
                                            selected,
                                            !selected
                                                && unavailable.is_none()
                                                && !state.loading
                                                && !state.switching,
                                        )
                                    })
                                    .inner;
                                if response.clicked() {
                                    if occupied {
                                        self.require_takeover(device.clone(), window);
                                        ui.close();
                                    } else {
                                        self.request_switch(device.clone(), window, None);
                                    }
                                }
                            }
                        });
                }
            });
        simulated_action
    }
}
