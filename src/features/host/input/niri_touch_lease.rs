//! Explicit opt-in temporary Niri configuration. Original files are never edited.
use anyhow::{Context, Result, ensure};
use std::{
    path::PathBuf,
    process::{Command, Stdio},
    sync::Mutex,
};
static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static OWNER: Mutex<Option<PathBuf>> = Mutex::new(None);
pub(super) struct Lease {
    original: PathBuf,
    temporary: PathBuf,
    output: Option<String>,
}
impl Lease {
    pub(super) fn new(original: PathBuf) -> Result<Self> {
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
        let original = self.original.to_str().context("配置路径无效")?;
        let source = format!(
            "include {}\ninput {{\n touch {{\n map-to-output {}\n }}\n}}\n",
            serde_json::to_string(original)?,
            serde_json::to_string(output)?
        );
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
        self.output = Some(output.to_owned());
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
