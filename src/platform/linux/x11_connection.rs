//! Recover stale local DISPLAY values without mutating process-wide environment.
use anyhow::{Context, Result, bail, ensure};
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::sync::Mutex;
use x11rb::{connection::Connection as _, rust_connection::RustConnection};
static LAST_LOCAL: Mutex<Option<String>> = Mutex::new(None);
fn local(display: &str) -> bool {
    display.starts_with(':') || display.starts_with("unix:") || display.starts_with("unix/:")
}
fn connect_one(display: &str) -> Result<(RustConnection, usize)> {
    // x11rb computes a TCP port even for local displays; reject overflow
    // before passing an invalid environment value to the dependency.
    if let Some((_, suffix)) = display.rsplit_once(':') {
        if let Ok(number) = suffix.split('.').next().unwrap_or("").parse::<u32>() {
            ensure!(number <= u16::MAX as u32 - 6000, "X11 显示编号超出有效范围");
        }
    }
    let (c, s) = x11rb::connect(Some(display))?;
    ensure!(c.setup().roots.get(s).is_some(), "X11 显示未提供有效屏幕");
    Ok((c, s))
}
fn choose<T>(
    candidates: impl IntoIterator<Item = String>,
    mut probe: impl FnMut(&str) -> Result<T>,
) -> Result<(String, T)> {
    let mut found = None;
    for name in candidates {
        if let Ok(value) = probe(&name) {
            ensure!(
                found.is_none(),
                "检测到多个可用本地显示服务，请指定 DISPLAY，避免连接错误屏幕"
            );
            found = Some((name, value));
        }
    }
    found.context("没有检测到可连接的本用户本地显示服务")
}
pub(crate) fn connect() -> Result<(RustConnection, usize)> {
    let requested = std::env::var("DISPLAY").ok();
    connect_detect(
        requested.as_deref(),
        std::env::var_os("WAYLAND_DISPLAY").is_some()
            || matches!(
                std::env::var("XDG_SESSION_TYPE").as_deref(),
                Ok("x11" | "wayland")
            ),
    )
}
fn connect_detect(requested: Option<&str>, desktop: bool) -> Result<(RustConnection, usize)> {
    let reason = if let Some(display) = requested {
        match connect_one(display) {
            Ok(value) => return Ok(value),
            Err(error) => {
                // An explicit SSH/TCP DISPLAY is not permission to capture a local desktop.
                if !local(display) {
                    return Err(error).context("指定的远程 X11 显示无法连接；不自动切换本机屏幕");
                }
                format!("{error:#}")
            }
        }
    } else {
        ensure!(desktop, "未设置 DISPLAY，且不在可识别的图形会话中");
        "DISPLAY 未设置".into()
    };
    if let Some(name) = LAST_LOCAL.lock().unwrap_or_else(|e| e.into_inner()).clone() {
        if let Ok(value) = connect_one(&name) {
            return Ok(value);
        }
    }
    let uid = unsafe { libc::geteuid() };
    let mut candidates = Vec::new();
    for entry in std::fs::read_dir("/tmp/.X11-unix")
        .context("无法检查本地 X11 显示服务")?
        .flatten()
    {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        let Some(number) = name.strip_prefix('X').and_then(|n| n.parse::<u16>().ok()) else {
            continue;
        };
        let Ok(meta) = std::fs::symlink_metadata(entry.path()) else {
            continue;
        };
        if meta.file_type().is_socket() && meta.uid() == uid {
            candidates.push(format!(":{number}"));
        }
    }
    candidates.sort();
    candidates.dedup();
    candidates.truncate(16);
    match choose(candidates, connect_one) {
        Ok((name, value)) => {
            tracing::warn!(requested=?requested,selected=%name,"stale DISPLAY recovered using authenticated local X11 connection");
            *LAST_LOCAL.lock().unwrap_or_else(|e| e.into_inner()) = Some(name);
            Ok(value)
        }
        Err(error) => bail!("自动显示检测失败：{error:#}（原显示：{reason}）"),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn skips_unreachable_display_and_selects_only_authenticated_candidate() {
        let (name, value) = choose([":9".into(), ":0".into()], |n| {
            if n == ":0" {
                Ok(42)
            } else {
                bail!("unreachable")
            }
        })
        .unwrap();
        assert_eq!(name, ":0");
        assert_eq!(value, 42);
    }
    #[test]
    fn multiple_sessions_are_not_silently_confused() {
        assert!(choose([":0".into(), ":2".into()], |_| Ok(())).is_err());
    }
    #[test]
    fn ssh_forwarding_is_not_treated_as_a_local_desktop() {
        assert!(!local("localhost:10.0"));
        assert!(!local("remote:0"));
        assert!(local(":1"));
        assert!(local("unix/:0"));
    }
    #[test]
    fn empty_inventory_is_a_real_error_not_a_fake_screen() {
        assert!(choose(Vec::<String>::new(), |_| Ok(())).is_err());
    }
    #[test]
    #[ignore = "requires the logged-in user's local Xwayland; explicit opt-in"]
    fn live_stale_display_recovers_without_environment_mutation() {
        let original = std::env::var_os("DISPLAY");
        let (c, s) = connect_detect(Some(":99"), true).unwrap();
        assert!(c.setup().roots[s].width_in_pixels > 0);
        assert_eq!(original, std::env::var_os("DISPLAY"));
    }
}
