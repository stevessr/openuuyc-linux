//! Local, explicitly ADB-authorized Android/MR device management.
//! Does not publish a UU host, alter debugging settings or open network ports.
use anyhow::{Context, Result, ensure};
use std::{process::Stdio, time::Duration};

static MIRRORS: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> =
    std::sync::OnceLock::new();
struct MirrorLease(String);
impl MirrorLease {
    fn acquire(serial: &str) -> Result<Self> {
        let mut active = MIRRORS
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        ensure!(
            active.insert(serial.to_owned()),
            "此设备已有投屏窗口，请先关闭原窗口再切换模式或显示 ID"
        );
        Ok(Self(serial.to_owned()))
    }
}
impl Drop for MirrorLease {
    fn drop(&mut self) {
        MIRRORS
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.0);
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Device {
    pub serial: String,
    pub state: String,
    pub model: String,
}
pub(crate) fn parse_devices(text: &str) -> Vec<Device> {
    text.lines()
        .filter_map(|line| {
            let mut words = line.split_whitespace();
            let serial = words.next()?;
            let state = words.next()?;
            if serial == "List" || serial.starts_with('*') || !valid_serial(serial) {
                return None;
            }
            if !matches!(
                state,
                "device" | "offline" | "unauthorized" | "recovery" | "sideload" | "bootloader"
            ) {
                return None;
            }
            let model = words
                .find_map(|w| w.strip_prefix("model:"))
                .unwrap_or("Android / MR")
                .replace('_', " ");
            Some(Device {
                serial: serial.into(),
                state: state.into(),
                model,
            })
        })
        .collect()
}
fn valid_serial(serial: &str) -> bool {
    !serial.is_empty()
        && serial.len() <= 256
        && !serial.starts_with('-')
        && serial
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".:_-[]".contains(&b))
}
pub(crate) async fn discover() -> Result<Vec<Device>> {
    let output = tokio::time::timeout(
        Duration::from_secs(8),
        tokio::process::Command::new("adb")
            .args(["devices", "-l"])
            .stdin(Stdio::null())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .context("ADB 查询超时")?
    .context("找不到 adb，请安装 Android platform-tools")?;
    ensure!(
        output.status.success(),
        "ADB 查询失败：{}",
        String::from_utf8_lossy(&output.stderr)
    );
    Ok(parse_devices(&String::from_utf8_lossy(&output.stdout)))
}
pub(crate) async fn mirror(serial: &str, display: u32, control: bool) -> Result<()> {
    ensure!(valid_serial(serial), "无效设备标识");
    ensure!(
        discover()
            .await?
            .iter()
            .any(|d| d.serial == serial && d.state == "device"),
        "设备已断开或未授权，请刷新并在头显确认 ADB 授权"
    );
    let lease = MirrorLease::acquire(serial)?;
    let mut command = std::process::Command::new("scrcpy");
    command.args([
        format!("--serial={serial}"),
        format!("--display-id={display}"),
        "--no-audio".into(),
        "--max-size=1600".into(),
        "--max-fps=60".into(),
        "--window-title=OpenUUYC MR · ADB".into(),
        "--no-clipboard-autosync".into(),
    ]);
    if !control {
        command.arg("--no-control");
    }
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .context("无法启动 scrcpy，请安装后重试")?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
    loop {
        if let Some(status) = child.try_wait()? {
            ensure!(
                status.success(),
                "scrcpy 启动失败：设备捕获或输入可能不受当前固件支持"
            );
            break;
        }
        if tokio::time::Instant::now() >= deadline {
            std::thread::spawn(move || {
                let _lease = lease;
                let _ = child.wait();
            });
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_authorized_and_unavailable_devices() {
        let list = parse_devices(
            "List of devices attached\n192.168.3.52:5555 device product:sparrow model:A9210 transport_id:1\nabc unauthorized\nusb offline\n* daemon started successfully\n",
        );
        assert_eq!(list.len(), 3);
        assert_eq!(list[0].model, "A9210");
        assert_eq!(list[1].state, "unauthorized");
    }
    #[test]
    fn mirror_ownership_is_released_on_exit() {
        let lease = MirrorLease::acquire("test-only-device").unwrap();
        assert!(MirrorLease::acquire("test-only-device").is_err());
        drop(lease);
        assert!(MirrorLease::acquire("test-only-device").is_ok());
    }
    #[test]
    fn rejects_option_and_shell_shapes() {
        for serial in ["-d", "x;reboot", "x\ny", "", "$(id)"] {
            assert!(!valid_serial(serial));
        }
        assert!(valid_serial("192.168.3.52:5555"));
    }
    #[tokio::test]
    #[ignore = "queries devices already authorized on the local ADB server"]
    async fn connected_device_inventory() {
        let devices = discover().await.unwrap();
        assert!(devices.iter().any(|d| d.state == "device"));
        eprintln!("ADB inventory: {devices:?}");
    }
}
