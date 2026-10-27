//! PICO headset desktop-mode integration on Linux.
//!
//! WiVRn provides the OpenXR runtime/headset transport; WayVR presents the
//! existing native OpenUUYC window in the headset and translates controller
//! interaction into desktop pointer/keyboard input. This module does not
//! pretend to be a standalone PICO OS client or a stereoscopic video decoder.

use anyhow::{Context, Result};
use std::env;
use std::ffi::OsStr;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const WIVRN_DASHBOARD: &str = "wivrn-dashboard";
const WAYVR: &str = "wayvr";

fn find_executable(name: &str) -> Option<PathBuf> {
    env::split_paths(&env::var_os("PATH")?)
        .map(|dir| dir.join(name))
        .find(|path| path.is_file() && is_executable(path))
}

fn is_executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    fs::metadata(path)
        .is_ok_and(|meta| meta.permissions().mode() & 0o111 != 0)
}

/// Use the process's argv[0] rather than /proc/PID/comm, which truncates the
/// 15+-character "wivrn-dashboard" executable name.
fn running_process(name: &str) -> bool {
    let Ok(processes) = fs::read_dir("/proc") else {
        return false;
    };
    processes.flatten().any(|entry| {
        if !entry
            .file_name()
            .to_string_lossy()
            .bytes()
            .all(|byte| byte.is_ascii_digit())
        {
            return false;
        }
        fs::read(entry.path().join("cmdline")).is_ok_and(|cmdline| {
            cmdline
                .split(|b| *b == 0)
                .next()
                .filter(|arg0| !arg0.is_empty())
                .is_some_and(|arg0| {
                    Path::new(OsStr::from_bytes(arg0))
                        .file_name()
                        .is_some_and(|filename| filename == OsStr::new(name))
                })
        })
    })
}

use std::os::unix::ffi::OsStrExt;

fn runtime_manifest() -> Option<PathBuf> {
    if let Some(manifest) = env::var_os("XR_RUNTIME_JSON") {
        // An explicitly configured runtime takes precedence, including when
        // invalid; do not silently claim that a fallback runtime was chosen.
        return Some(PathBuf::from(manifest));
    }
    let config = env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")));
    let mut candidates = Vec::new();
    if let Some(config) = config {
        candidates.push(config.join("openxr/1/active_runtime.json"));
    }
    candidates.push(PathBuf::from("/etc/xdg/openxr/1/active_runtime.json"));
    candidates.push(PathBuf::from("/usr/local/share/openxr/1/active_runtime.json"));
    candidates.push(PathBuf::from("/usr/share/openxr/1/active_runtime.json"));
    candidates.into_iter().find(|path| path.is_file())
}

fn parse_runtime_name(contents: &[u8]) -> Result<String> {
    let value: serde_json::Value =
        serde_json::from_slice(contents).context("无效的 OpenXR runtime JSON")?;
    let runtime = value.get("runtime").context("OpenXR manifest 缺少 runtime 字段")?;
    let library = runtime
        .get("library_path")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .context("OpenXR manifest 缺少 runtime.library_path")?;
    Ok(runtime
        .get("name")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .unwrap_or(library)
        .to_owned())
}

fn runtime_name(manifest: &Path) -> Result<String> {
    let contents = fs::read(manifest)
        .with_context(|| format!("无法读取 OpenXR runtime: {}", manifest.display()))?;
    parse_runtime_name(&contents).with_context(|| format!("清单路径: {}", manifest.display()))
}

pub(crate) fn doctor() -> Result<()> {
    println!("PICO VR / Linux 桌面模式检查（OpenXR + WiVRn + WayVR）");
    for (name, description) in [
        (WIVRN_DASHBOARD, "WiVRn 配对管理"),
        (WAYVR, "WayVR 桌面 VR 映射"),
    ] {
        match find_executable(name) {
            Some(path) => println!("[OK] {description}: {}", path.display()),
            None => println!("[缺失] {description}: 未找到 {name} (PATH)"),
        }
    }
    match runtime_manifest() {
        Some(path) => match runtime_name(&path) {
            Ok(name) => println!("[OK] OpenXR runtime 清单: {} ({name})", path.display()),
            Err(error) => println!("[警告] OpenXR runtime 清单: {error:#}"),
        },
        None => println!("[提示] 暂无活跃 OpenXR runtime；WiVRn 通常在头显连上后才激活"),
    }
    println!(
        "WiVRn 管理器: {}；WayVR: {}",
        if running_process(WIVRN_DASHBOARD) { "运行中" } else { "未检测到" },
        if running_process(WAYVR) { "运行中" } else { "未检测到" }
    );
    println!("检查不表示头显已经连接；先在 PICO 上运行与 PC 端版本匹配的 WiVRn 客户端。");
    Ok(())
}

fn spawn_detached(path: &Path) -> Result<()> {
    Command::new(path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("无法启动 {}", path.display()))?;
    Ok(())
}

pub(crate) fn setup() -> Result<()> {
    let dashboard = find_executable(WIVRN_DASHBOARD)
        .context("没有安装 wivrn-dashboard；请先安装 WiVRn，再在 PICO 头显中安装匹配版本的 WiVRn 客户端")?;
    if !running_process(WIVRN_DASHBOARD) {
        spawn_detached(&dashboard)?;
    }
    println!("WiVRn 管理器已启动或已在运行。请在管理器完成 PICO 配对。");
    Ok(())
}

pub(crate) fn launch(no_dashboard: bool) -> Result<()> {
    let wayvr = find_executable(WAYVR)
        .context("找不到 WayVR；请安装 wayvr，运行 OpenUUYC pico-vr doctor 检查环境")?;
    if !no_dashboard {
        if let Some(dashboard) = find_executable(WIVRN_DASHBOARD) {
            if !running_process(WIVRN_DASHBOARD) {
                spawn_detached(&dashboard)?;
            }
        } else {
            eprintln!("未找到 wivrn-dashboard：请自行启动 WiVRn 并连接 PICO 头显");
        }
    }
    if running_process(WAYVR) {
        println!("WayVR 已在运行，将沿用现有 OpenXR 桌面映射。");
        return Ok(());
    }
    // '--wait' is explicitly supported by WayVR: it waits for an available
    // runtime, so launching before the headset has connected is safe.
    Command::new(&wayvr)
        .arg("--wait")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("无法启动 {}", wayvr.display()))?;
    // Intentionally do not kill WayVR when this CLI invocation exits: an
    // existing OpenUUYC GUI may receive an activation and outlive us.
    println!("WayVR 已启动，OpenUUYC 窗口将通过桌面映射在头显内可用。");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_openxr_manifest_reports_runtime_name() {
        assert_eq!(
            parse_runtime_name(
                br#"{"file_format_version":"1.0.0","runtime":{"name":"WiVRn","library_path":"libopenxr_wivrn.so"}}"#
            ).unwrap(),
            "WiVRn"
        );
    }

    #[test]
    fn runtime_manifest_uses_library_when_name_is_absent() {
        assert_eq!(
            parse_runtime_name(br#"{"runtime":{"library_path":"libopenxr_runtime.so"}}"#).unwrap(),
            "libopenxr_runtime.so"
        );
    }

    #[test]
    fn runtime_manifest_rejects_missing_library_and_invalid_json() {
        assert!(parse_runtime_name(br#"{"runtime":{"name":"WiVRn"}}"#).is_err());
        assert!(parse_runtime_name(b"not json").is_err());
    }

    #[test]
    fn missing_executable_does_not_false_positive() {
        assert!(find_executable("openuuyc-no-such-vr-binary-7f4b9a").is_none());
    }
}
