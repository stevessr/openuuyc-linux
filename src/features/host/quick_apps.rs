//! OpenUUYC-only quick launch extension on the authenticated peer, not guessed UU RPCs.
#[cfg(target_os = "linux")]
mod desktop;

use super::Lease;
use anyhow::{Result, ensure};
use bytes::Bytes;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    process::Stdio,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;
use webrtc::data_channel::RTCDataChannel;
pub(crate) const CHANNEL: &str = "OPENUUYC_QUICK_APPS_V1";
#[derive(Clone, Serialize)]
struct App {
    id: String,
    name: String,
    vr: bool,
    enabled: bool,
}
#[derive(Clone)]
enum Target {
    Desktop(PathBuf),
    Vrchat,
}
impl Target {
    fn ensure_available(&self, explicit_launch_only: bool) -> Result<()> {
        ensure!(
            explicit_launch_only || !matches!(self, Self::Vrchat),
            "内置 VR 的双眼传输和追踪链路尚未就绪；未启动 VRChat，不会调用外部串流程序"
        );
        Ok(())
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    id: u32,
    op: String,
    #[serde(default)]
    app: String,
    #[serde(default)]
    launch_only_ack: bool,
    #[serde(default)]
    backend: String,
}
fn desktop_name(text: &str) -> Option<String> {
    #[cfg(target_os = "linux")]
    {
        let locale = ["LC_ALL", "LC_MESSAGES", "LANG"]
            .into_iter()
            .filter_map(|key| std::env::var(key).ok())
            .find(|s| !s.is_empty())
            .unwrap_or_default();
        desktop::name(
            text,
            &locale,
            &std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default(),
            desktop::executable,
        )
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = text;
        None
    }
}
fn desktop_vr(text: &str) -> bool {
    let mut main = false;
    for line in text.lines().map(str::trim) {
        if line.starts_with('[') {
            main = line == "[Desktop Entry]";
            continue;
        }
        if main
            && (line == "X-OpenUUYC-VR=true"
                || line
                    .strip_prefix("Categories=")
                    .is_some_and(|v| v.split(';').any(|c| c == "VR")))
        {
            return true;
        }
    }
    false
}
fn catalog() -> BTreeMap<String, (App, Target)> {
    use sha2::{Digest, Sha256};
    let mut out = BTreeMap::new();
    #[cfg(target_os = "linux")]
    {
        let home = std::env::var_os("HOME").map(PathBuf::from);
        let data_home = std::env::var_os("XDG_DATA_HOME").map(PathBuf::from);
        let data_dirs = std::env::var("XDG_DATA_DIRS").ok();
        let roots = desktop::roots(home.as_deref(), data_home.as_deref(), data_dirs.as_deref());
        let mut seen = std::collections::HashSet::new();
        for root in roots {
            let Ok(root) = root.canonicalize() else {
                continue;
            };
            let mut pending = vec![root.clone()];
            let mut entries = Vec::new();
            while let Some(dir) = pending.pop() {
                let Ok(children) = std::fs::read_dir(&dir) else {
                    continue;
                };
                for entry in children.flatten() {
                    if entry.file_type().is_ok_and(|t| t.is_dir()) {
                        pending.push(entry.path());
                    } else if entry.path().extension().and_then(|s| s.to_str()) == Some("desktop") {
                        entries.push(entry.path());
                    }
                }
            }
            entries.sort();
            for entry in entries {
                let desktop_id = entry
                    .strip_prefix(&root)
                    .unwrap_or(&entry)
                    .to_string_lossy()
                    .replace('/', "-");
                if !seen.insert(desktop_id.clone()) {
                    continue;
                }
                let Ok(path) = entry.canonicalize() else {
                    continue;
                };
                if std::fs::metadata(&path).map_or(true, |m| !m.is_file() || m.len() > 65536) {
                    continue;
                }
                let Ok(text) = std::fs::read_to_string(&path) else {
                    continue;
                };
                let Some(name) = desktop_name(&text) else {
                    continue;
                };
                let id = format!("desktop-{:x}", Sha256::digest(desktop_id.as_bytes()));
                out.insert(
                    id.clone(),
                    (
                        App {
                            id,
                            vr: desktop_vr(&text) || name.to_ascii_lowercase().contains("vrchat"),
                            name,
                            enabled: true,
                        },
                        Target::Desktop(path),
                    ),
                );
            }
        }
        if home.is_some_and(|h| {
            [h.join(".local/share/Steam"), h.join(".steam/steam")]
                .iter()
                .any(|p| p.join("steamapps/appmanifest_438100.acf").is_file())
        }) {
            out.insert(
                "vrchat-vr".into(),
                (
                    App {
                        id: "vrchat-vr".into(),
                        name: "VRChat · 仅启动（无内置 VR 画面）".into(),
                        vr: true,
                        enabled: true,
                    },
                    Target::Vrchat,
                ),
            );
        }
    }
    out
}
async fn spawn(program: &str, args: &[&std::ffi::OsStr]) -> Result<()> {
    use anyhow::Context;
    let mut child = tokio::process::Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("无法运行应用启动器 {program}"))?;
    // GIO can start successfully but immediately reject a missing executable
    // or malformed desktop entry. Do not report that as a successful launch.
    match tokio::time::timeout(Duration::from_secs(3), child.wait()).await {
        Ok(status) => ensure!(
            status?.success(),
            "应用启动器 {program} 返回失败，请检查应用是否仍已安装"
        ),
        Err(_) => {
            // A launcher may itself remain as the application. Keep it alive,
            // reap it independently, and only acknowledge dispatch, not a window.
            tokio::spawn(async move {
                let _ = child.wait().await;
            });
        }
    }
    Ok(())
}
async fn send(channel: &RTCDataChannel, value: serde_json::Value) -> Result<()> {
    channel
        .send_text_bytes(&Bytes::from(serde_json::to_vec(&value)?))
        .await?;
    Ok(())
}
pub(crate) fn bind(
    channel: Arc<RTCDataChannel>,
    lease: Lease,
    connected: Arc<AtomicBool>,
    stop: CancellationToken,
) {
    let cancel = stop.child_token();
    let (tx, mut rx) = tokio::sync::mpsc::channel(8);
    let invalid = cancel.clone();
    channel.on_message(Box::new(move |m| {
        if m.data.len() > 4096 {
            invalid.cancel();
            return Box::pin(async {});
        }
        match serde_json::from_slice::<Request>(&m.data) {
            Ok(r) => {
                if tx.try_send(r).is_err() {
                    invalid.cancel()
                }
            }
            Err(_) => invalid.cancel(),
        }
        Box::pin(async {})
    }));
    let closed = cancel.clone();
    channel.on_close(Box::new(move || {
        closed.cancel();
        Box::pin(async {})
    }));
    let weak = Arc::downgrade(&channel);
    tokio::spawn(async move {
        let mut apps = BTreeMap::new();
        let mut tick = tokio::time::interval(Duration::from_millis(500));
        let mut last_permission = None;
        let mut last_launch = None;
        loop {
            let allowed = lease.terminal_allowed() && connected.load(Ordering::Acquire);
            let Some(ch) = weak.upgrade() else { break };
            let request = tokio::select! {_=cancel.cancelled()=>break,_=tick.tick()=>{
                if ch.ready_state()==webrtc::data_channel::data_channel_state::RTCDataChannelState::Open && last_permission!=Some(allowed) {
                    if send(&ch,serde_json::json!({"id":0,"type":"hello","allowed":allowed,"message":"快速启动沿用本机远程终端授权；仅接受已列出的应用 ID"})).await.is_err(){break}
                    last_permission=Some(allowed);
                }
                continue
            },r=rx.recv()=>match r {Some(r)=>r,None=>break}};
            let id = request.id;
            let result:Result<()>=async {
                ensure!(lease.terminal_allowed() && connected.load(Ordering::Acquire),"远端未授权快速启动：请在 Linux 被控设置允许远程终端；访客会话不支持");
                match request.op.as_str() {
                    #[cfg(target_os="linux")]
                    "vr-runtime"=>{
                        let info=tokio::task::spawn_blocking(|| -> Result<serde_json::Value> {
                            use crate::pcvr::runtime;
                            Ok(serde_json::json!({"preference":runtime::preference()?,"status":runtime::status(),"steam_options":runtime::plan().and_then(|p|p.steam_options(runtime::Api::OpenVr)).ok(),"stream_ready":false}))
                        }).await??;
                        send(&ch,serde_json::json!({"id":id,"type":"vr-runtime","runtime":info})).await?;
                    },
                    #[cfg(target_os="linux")]
                    "vr-runtime-set"=>{
                        use crate::pcvr::runtime::{self,Preference};
                        let preference=match request.backend.as_str() {"open_xr_first"=>Preference::OpenXrFirst,"open_xr_only"=>Preference::OpenXrOnly,"steam_vr"=>Preference::SteamVr,_=>anyhow::bail!("不支持的 VR 后端策略")};
                        ensure!(lease.terminal_allowed() && connected.load(Ordering::Acquire),"权限已撤销");
                        runtime::set_preference(preference)?;
                        send(&ch,serde_json::json!({"id":id,"type":"vr-runtime-set","message":"后端策略已保存，下次 VR 连接生效；不会切换全局运行时"})).await?;
                    },
                    "list"=>{
                        apps=tokio::task::spawn_blocking(catalog).await?;
                        ensure!(lease.terminal_allowed() && connected.load(Ordering::Acquire),"权限已撤销");
                        let mut entries:Vec<_>=apps.values().map(|(a,_)|a.clone()).collect();
                        entries.sort_by_key(|a|(!a.name.to_ascii_lowercase().starts_with("vrchat"),!a.vr,a.name.to_lowercase()));
                        send(&ch,serde_json::json!({"id":id,"type":"list","apps":entries})).await?;
                    },
                    "launch"=>{
                        ensure!(last_launch.is_none_or(|t:Instant|t.elapsed()>Duration::from_secs(2)),"启动请求过于频繁");
                        let (app,target)=apps.get(&request.app).cloned().ok_or_else(||anyhow::anyhow!("请刷新应用列表后重试"))?;
                        ensure!(!app.vr || request.launch_only_ack,"此入口仅启动远端 VR 应用，不提供双眼串流；请明确确认后重试");
                        target.ensure_available(request.launch_only_ack)?;
                        last_launch=Some(Instant::now());
                        match target {
                            Target::Desktop(path)=>{
                                ensure!(lease.terminal_allowed() && connected.load(Ordering::Acquire),"权限已撤销");
                                ensure!(std::fs::metadata(&path).is_ok_and(|m| m.is_file() && m.len() <= 65536), "应用入口已移除或无效，请刷新列表");
                                let text = std::fs::read_to_string(&path)?;
                                ensure!(desktop_name(&text).is_some(), "应用已隐藏或不可用，请刷新列表");
                                spawn("gio",&[std::ffi::OsStr::new("launch"),path.as_os_str()]).await?;
                            },
                            Target::Vrchat=>{
                                ensure!(lease.terminal_allowed() && connected.load(Ordering::Acquire),"权限已撤销");
                                spawn("steam",&[std::ffi::OsStr::new("-applaunch"),std::ffi::OsStr::new("438100")]).await?;
                            }
                        }
                        send(&ch,serde_json::json!({"id":id,"type":"started","message":if app.vr {"已请求在远端启动 VR 应用；OpenUUYC 尚无双眼游戏画面，未确认 VR 首帧"} else {"启动请求已交给系统；尚未确认应用窗口"}})).await?;
                    },_=>anyhow::bail!("未知快速启动操作")
                };Ok(())
            }.await;
            if let Err(error) = result {
                let _ = send(
                    &ch,
                    serde_json::json!({"id":id,"type":"error","message":error.to_string()}),
                )
                .await;
            }
        }
        cancel.cancel();
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[cfg(target_os = "linux")]
    fn desktop_entries_are_data_not_shell_commands() {
        assert_eq!(
            desktop_name("[Desktop Entry]\nType=Application\nName=Editor\nExec=editor %U"),
            Some("Editor".into())
        );
        assert_eq!(
            desktop_name(
                "[Desktop Entry]\nType=Application\nName=Hidden\nExec=true\nNoDisplay=true"
            ),
            None
        );
        assert_eq!(
            desktop_name("[Other]\nType=Application\nName=Wrong\nExec=true"),
            None
        );
    }
    #[test]
    #[cfg(target_os = "linux")]
    #[ignore = "reads installed applications from this desktop session"]
    fn installed_catalog_smoke() {
        let apps = catalog();
        assert!(
            !apps.is_empty(),
            "no installed applications were discovered"
        );
        eprintln!("discovered {} launch entries", apps.len());
        for (id, (app, target)) in &apps {
            assert_eq!(id, &app.id);
            assert!(!app.name.is_empty());
            if let Target::Desktop(path) = target {
                assert!(path.is_absolute() && path.is_file());
            }
        }
        for (app, _) in apps.values().take(10) {
            eprintln!("app: {}", app.name);
        }
    }

    #[tokio::test]
    #[cfg(target_os = "linux")]
    #[ignore = "launches a harmless local GIO desktop-entry fixture"]
    async fn gio_launch_reports_success_and_failure() {
        let directory =
            std::env::temp_dir().join(format!("openuuyc-app-test-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("test app.desktop");
        let marker = directory.join("launched marker");
        std::fs::write(&path, format!(
            "[Desktop Entry]\nType=Application\nName=OpenUUYC test\nExec=/usr/bin/touch \"{}\"\nTerminal=false\n", marker.display()
        )).unwrap();
        spawn("gio", &[std::ffi::OsStr::new("launch"), path.as_os_str()])
            .await
            .unwrap();
        for _ in 0..100 {
            if marker.is_file() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(marker.is_file(), "GIO did not launch the fixture");
        std::fs::write(
            &path,
            "[Desktop Entry]\nType=Application\nName=Missing\nExec=/openuuyc-missing-test-binary\n",
        )
        .unwrap();
        assert!(
            spawn("gio", &[std::ffi::OsStr::new("launch"), path.as_os_str()])
                .await
                .is_err()
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn vr_launch_requires_explicit_launch_only_consent() {
        assert!(Target::Vrchat.ensure_available(false).is_err());
        assert!(Target::Vrchat.ensure_available(true).is_ok());
        assert!(desktop_vr("[Desktop Entry]\nX-OpenUUYC-VR=true"));
        assert!(!desktop_vr("[Other]\nX-OpenUUYC-VR=true"));
        assert!(
            Target::Desktop(PathBuf::from("/test.desktop"))
                .ensure_available(false)
                .is_ok()
        );
    }
    #[test]
    fn requests_do_not_accept_arbitrary_commands() {
        assert!(
            serde_json::from_str::<Request>(r#"{"id":1,"op":"launch","app":"x","command":"sh"}"#)
                .is_err()
        );
    }
}
