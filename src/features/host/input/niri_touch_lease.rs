//! Explicit opt-in temporary Niri configuration. Original files are never edited.
//!
//! Niri has no per-device touch configuration (an `input { touch "NAME" ... }`
//! block is rejected by `niri validate`), and `input.touch.map-to-output`
//! applies to every touch device at once. Native multi-touch therefore only
//! reaches the configured output. When the controller watches another screen,
//! this lease writes a temporary config that includes the user's real one and
//! overrides just the touch mapping, loads it, and restores the real config on
//! release. Everything else in the user's config (touch `off`,
//! `calibration-matrix`) is copied over so the physical panel keeps its own
//! behaviour apart from the output it is pointed at.
use anyhow::{Context, Result, ensure};
use std::{
    path::PathBuf,
    process::{Command, Stdio},
    sync::Mutex,
};
static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static OWNER: Mutex<Option<PathBuf>> = Mutex::new(None);

/// 原配置里与触摸有关、但临时配置必须保留的设置。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(super) struct TouchSettings {
    pub off: bool,
    pub calibration: Option<Vec<String>>,
}

pub(super) struct Lease {
    original: PathBuf,
    temporary: PathBuf,
    output: Option<String>,
    touch: TouchSettings,
}
impl Lease {
    pub(super) fn new(original: PathBuf, touch: TouchSettings) -> Result<Self> {
        let mut owner = OWNER
            .lock()
            .map_err(|_| anyhow::anyhow!("触摸映射锁不可用"))?;
        ensure!(owner.is_none(), "另一会话正在使用临时触摸映射");
        let root = std::env::var_os("XDG_RUNTIME_DIR")
            .map(PathBuf::from)
            .context("缺少 XDG_RUNTIME_DIR")?;
        let temporary = root.join(format!(
            "openuuyc-touch-{}-{}.kdl",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        *owner = Some(temporary.clone());
        Ok(Self {
            original,
            temporary,
            output: None,
            touch,
        })
    }
    pub(super) fn maps_to(&self, output: &str) -> bool {
        self.output.as_deref() == Some(output)
    }
    pub(super) fn map_to(&mut self, output: &str) -> Result<()> {
        if self.maps_to(output) {
            return Ok(());
        }
        ensure!(
            output
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || "-_. ".contains(c)),
            "输出名称无效"
        );
        let source = touch_config(&self.original, output, &self.touch)?;
        std::fs::write(&self.temporary, source)?;
        ensure!(
            Command::new("niri")
                .args(["validate", "-c"])
                .arg(&self.temporary)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()?
                .success(),
            "临时触摸配置校验失败"
        );
        ensure!(
            Command::new("niri")
                .args(["msg", "action", "load-config-file", "--path"])
                .arg(&self.temporary)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()?
                .success(),
            "临时触摸映射加载失败"
        );
        let first = self.output.is_none();
        self.output = Some(output.to_owned());
        if first {
            // A process crash must not leave physical touch remapped indefinitely.
            Command::new("sh").args(["-c","while kill -0 \"$1\" 2>/dev/null && test -f \"$2\"; do sleep 1; done; kill -0 \"$1\" 2>/dev/null && exit 0; if test -f \"$2\"; then niri msg action load-config-file --path \"$3\" >/dev/null 2>&1; rm -f -- \"$2\"; fi","openuuyc-touch-watchdog"])
                .arg(std::process::id().to_string()).arg(&self.temporary).arg(&self.original)
                .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
        }
        tracing::info!(%output,"会话临时启用 Niri 原生触摸映射；断开后恢复原配置");
        std::thread::sleep(std::time::Duration::from_millis(80));
        Ok(())
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        if let Ok(mut owner) = OWNER.lock() {
            if owner.as_ref() == Some(&self.temporary) {
                if self.output.is_some() {
                    let restored = Command::new("niri")
                        .args(["msg", "action", "load-config-file", "--path"])
                        .arg(&self.original)
                        .stdout(Stdio::null())
                        .stderr(Stdio::null())
                        .status();
                    if !restored.is_ok_and(|s| s.success()) {
                        tracing::warn!("恢复 Niri 触摸配置失败，可手动重新加载原配置");
                        return;
                    }
                }
                let _ = std::fs::remove_file(&self.temporary);
                *owner = None;
            }
        }
    }
}

/// 临时配置：包含用户原配置，只把触摸的目标输出换掉；原配置里与触摸有关的
/// 其他设置原样带过去（niri 合并时整个 `touch` 段被替换，不会自动保留）。
fn touch_config(original: &std::path::Path, output: &str, touch: &TouchSettings) -> Result<String> {
    let original = original.to_str().context("配置路径无效")?;
    let mut body = String::new();
    if touch.off {
        body.push_str("off\n");
    }
    if let Some(calibration) = &touch.calibration {
        ensure!(
            calibration
                .iter()
                .all(|value| value.parse::<f64>().is_ok_and(f64::is_finite)),
            "触摸校准参数无效"
        );
        body.push_str("calibration-matrix ");
        body.push_str(&calibration.join(" "));
        body.push('\n');
    }
    body.push_str(&format!(
        "map-to-output {}\n",
        serde_json::to_string(output)?
    ));
    Ok(format!(
        "include {}\ninput {{\n    touch {{\n{}    }}\n}}\n",
        serde_json::to_string(original)?,
        body.lines()
            .map(|line| format!("        {line}\n"))
            .collect::<String>()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn the_temporary_config_keeps_the_other_touch_settings() {
        let config = touch_config(
            std::path::Path::new("/home/example/.config/niri/config.kdl"),
            "eDP-1",
            &TouchSettings {
                off: false,
                calibration: Some(vec!["1".into(), "0".into(), "0".into(), "0".into(), "1".into(), "0".into()]),
            },
        )
        .unwrap();
        assert!(config.contains("include \"/home/example/.config/niri/config.kdl\""));
        assert!(config.contains("calibration-matrix 1 0 0 0 1 0"));
        assert!(config.contains("map-to-output \"eDP-1\""));
        assert!(!config.contains("off"));
    }
    #[test]
    fn a_disabled_or_malformed_touch_section_is_carried_over_or_refused() {
        let config = touch_config(
            std::path::Path::new("/etc/niri.kdl"),
            "HDMI-A-1",
            &TouchSettings {
                off: true,
                calibration: None,
            },
        )
        .unwrap();
        assert!(config.contains("off"));
        assert!(config.contains("map-to-output \"HDMI-A-1\""));
        assert!(
            touch_config(
                std::path::Path::new("/etc/niri.kdl"),
                "DP-1",
                &TouchSettings {
                    off: false,
                    calibration: Some(vec!["1".into(), "x".into()]),
                },
            )
            .is_err()
        );
    }
}
