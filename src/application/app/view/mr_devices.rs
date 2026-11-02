//! ADB device access is separate from the UU account/device list.
use crate::platform::linux::mr_device::{self, Device};
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct State {
    devices: Vec<Device>,
    busy: bool,
    message: String,
    display: u32,
}
type Shared = Arc<Mutex<State>>;

fn work(state: Shared, ctx: egui::Context, operation: Option<(String, u32, bool)>) {
    std::thread::spawn(move || {
        let result = (|| -> anyhow::Result<Option<Vec<Device>>> {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?;
            rt.block_on(async {
                if let Some((serial, display, control)) = operation {
                    mr_device::mirror(&serial, display, control).await?;
                    Ok(None)
                } else {
                    Ok(Some(mr_device::discover().await?))
                }
            })
        })();
        let mut s = state.lock().unwrap_or_else(|e| e.into_inner());
        s.busy = false;
        match result {
            Ok(Some(devices)) => {
                s.message = if devices.is_empty() {
                    "没有发现 ADB 设备；请连接 USB 或使用已配对的无线调试".into()
                } else {
                    format!("发现 {} 台设备；仅已授权设备可操作", devices.len())
                };
                s.devices = devices;
            }
            Ok(None) => s.message = "已请求打开独立 MR 画面窗口；尚未确认画面与输入".into(),
            Err(error) => s.message = format!("{error:#}"),
        }
        ctx.request_repaint();
    });
}
pub(super) fn show(ui: &mut egui::Ui) {
    super::section(ui, "本地 MR / Android 设备 · ADB");
    ui.small(
        "独立于 UU 被控服务。需头显明确授权 ADB，并安装 adb / scrcpy；不自动开启调试或网络端口。",
    );
    ui.small("只投射固件允许的 Android 显示内容，不保证完整 MR 合成画面；默认不传音频、不自动同步剪贴板。");
    ui.small(
        "关闭独立投屏窗口即可停止。每台设备仅打开一个窗口，切换模式或显示 ID 前请先关闭原窗口。",
    );
    let id = egui::Id::new("local-mr-adb-state");
    let state = ui
        .ctx()
        .data_mut(|d| d.get_temp_mut_or_default::<Shared>(id).clone());
    let mut s = state.lock().unwrap_or_else(|e| e.into_inner());
    let mut action = None;
    if ui
        .add_enabled(!s.busy, egui::Button::new("刷新已连接设备"))
        .clicked()
    {
        action = Some(None);
    }
    ui.horizontal(|ui| {
        ui.label("显示 ID");
        ui.add(egui::DragValue::new(&mut s.display).range(0..=10000));
        ui.small("默认 0；其他面板 ID 可用 scrcpy --list-displays 查询");
    });
    for device in &s.devices {
        ui.horizontal_wrapped(|ui| {
            ui.label(format!(
                "{} · {} · {}",
                device.model, device.serial, device.state
            ));
            let allowed = !s.busy && device.state == "device";
            if ui
                .add_enabled(allowed, egui::Button::new("只读观看"))
                .clicked()
            {
                action = Some(Some((device.serial.clone(), s.display, false)));
            }
            if ui
                .add_enabled(allowed, egui::Button::new("观看并控制"))
                .clicked()
            {
                action = Some(Some((device.serial.clone(), s.display, true)));
            }
        });
    }
    if s.busy {
        ui.spinner();
    }
    if !s.message.is_empty() {
        ui.label(&s.message);
    }
    if let Some(operation) = action {
        s.busy = true;
        drop(s);
        work(state, ui.ctx().clone(), operation);
    }
}
