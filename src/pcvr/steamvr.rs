//! Inspect or attach to a running SteamVR compositor without launching a
//! runtime or a game. The helper process never starts SteamVR, never takes
//! scene focus and is never a global runtime change.
use anyhow::{Context, Result, bail, ensure};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::{
    path::PathBuf,
    process::Stdio,
    time::{Duration, Instant},
};
const HELPER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/openuuyc-steamvr-mirror"));
#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct Probe {
    pub available: bool,
    #[serde(default)]
    pub scene_pid: u32,
    #[serde(default)]
    pub renderer_pid: u32,
    #[serde(default)]
    pub openvr_error: Option<i32>,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub mode: Option<String>,
}
fn install_helper() -> Result<PathBuf> {
    use sha2::{Digest, Sha256};
    use std::io::Write;
    ensure!(
        HELPER.starts_with(b"\x7fELF"),
        "SteamVR mirror helper was not built; SDL2/OpenGL development files are required"
    );
    let root = super::runtime::components_root()?
        .parent()
        .unwrap()
        .join("tools");
    std::fs::create_dir_all(&root)?;
    let meta = std::fs::symlink_metadata(&root)?;
    ensure!(
        meta.is_dir() && meta.uid() == unsafe { libc::getuid() } && meta.mode() & 0o022 == 0,
        "unsafe PCVR helper directory"
    );
    let path = root.join(format!("steamvr-mirror-{:x}", Sha256::digest(HELPER)));
    if !path.exists() {
        let temporary = root.join(format!("mirror-{}.tmp", uuid::Uuid::new_v4()));
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o700)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&temporary)?;
        let result = (|| -> Result<()> {
            file.write_all(HELPER)?;
            file.sync_all()?;
            std::fs::rename(&temporary, &path)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(&temporary);
        }
        result?;
    }
    let meta = std::fs::symlink_metadata(&path)?;
    ensure!(
        meta.is_file()
            && meta.uid() == unsafe { libc::getuid() }
            && meta.mode() & 0o022 == 0
            && meta.len() == HELPER.len() as u64
            && std::fs::read(&path)? == HELPER,
        "PCVR helper integrity check failed"
    );
    Ok(path)
}
fn client_library() -> Result<PathBuf> {
    let manifest = super::runtime::steam_runtime()?.context("SteamVR runtime is not registered")?;
    let library = manifest
        .parent()
        .unwrap()
        .join("bin/linux64/libopenvr_api.so");
    ensure!(
        library.is_file(),
        "installed SteamVR client library is missing"
    );
    Ok(library)
}
pub fn probe() -> Result<Probe> {
    use std::io::Read;
    let helper = install_helper()?;
    let library = client_library()?;
    let mut child = std::process::Command::new(helper)
        .arg("--library")
        .arg(library)
        .arg("--probe")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("SteamVR background probe timed out");
        }
        std::thread::sleep(Duration::from_millis(20));
    };
    let mut output = String::new();
    child
        .stdout
        .take()
        .context("probe stdout missing")?
        .take(32769)
        .read_to_string(&mut output)?;
    ensure!(
        status.success() && output.len() <= 32768,
        "SteamVR background probe failed"
    );
    output
        .lines()
        .find_map(|line| serde_json::from_str::<Probe>(line).ok())
        .context("SteamVR probe returned no valid result")
}
/// The helper's attach announcement, printed only after both GL mirror
/// textures exist and the focused scene was accepted.
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Attached {
    attached: bool,
    #[serde(default)]
    scene_pid: u32,
    #[serde(default)]
    _renderer_pid: u32,
}
/// The helper writes exactly one failure line; map it instead of forwarding
/// English internals. Anything unknown stays a generic failure.
fn explain(stderr: &str) -> &'static str {
    if stderr.contains("no running VR scene") {
        "SteamVR 当前没有正在运行的 VR 场景；请先进入一个 VR 应用（不会自动启动 SteamVR）"
    } else if stderr.contains("scene changed") {
        "SteamVR 场景已变化；请重新附加"
    } else if stderr.contains("readback failed") {
        "SteamVR 镜像 GPU 回读失败"
    } else if stderr.contains("GL mirror texture") {
        "SteamVR 镜像纹理不可用"
    } else if stderr.contains("SDL") || stderr.contains("OpenGL") {
        "SteamVR 镜像需要可用的 SDL/OpenGL 隐藏窗口"
    } else {
        "SteamVR 镜像辅助程序异常退出"
    }
}
/// One live background attach. The helper mirrors the eyes the focused scene
/// already presents into the host-owned export; the host session aligns and
/// encodes them. Ownership is the caller's: dropping this stops the helper.
pub(crate) struct Attach {
    child: std::process::Child,
    stderr: Option<std::process::ChildStderr>,
    scene_pid: u32,
    failure: Option<&'static str>,
}
impl Attach {
    pub(crate) fn start(export: &std::path::Path) -> Result<Self> {
        ensure!(
            export.is_absolute() && export.is_file(),
            "host stereo export must exist before attaching"
        );
        let mut child = std::process::Command::new(install_helper()?)
            .arg("--library")
            .arg(client_library()?)
            .env("OPENUUYC_STEREO_CAPTURE", export)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .context("无法启动 SteamVR 镜像辅助程序")?;
        let stdout = child.stdout.take().context("mirror stdout missing")?;
        let stderr = child.stderr.take();
        let (sender, receiver) = std::sync::mpsc::channel::<String>();
        std::thread::spawn(move || {
            use std::io::BufRead;
            // Keep draining after the handshake: a full pipe must never block
            // the helper, and a dropped receiver simply ends this reader.
            for line in std::io::BufReader::new(stdout)
                .lines()
                .map_while(|l| l.ok())
            {
                let _ = sender.send(line);
            }
        });
        let mut attach = Self {
            child,
            stderr,
            scene_pid: 0,
            failure: None,
        };
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            let outcome = if remaining.is_zero() {
                Err(std::sync::mpsc::RecvTimeoutError::Timeout)
            } else {
                receiver.recv_timeout(remaining)
            };
            match outcome {
                Ok(line) => {
                    if let Ok(attached) = serde_json::from_str::<Attached>(&line) {
                        ensure!(attached.attached, "SteamVR 镜像拒绝了附加请求");
                        ensure!(attached.scene_pid != 0, "SteamVR 镜像没有附加到具体场景");
                        attach.scene_pid = attached.scene_pid;
                        return Ok(attach);
                    }
                    if let Ok(probe) = serde_json::from_str::<Probe>(&line) {
                        if !probe.available {
                            attach.stop();
                            bail!(
                                "无法连接 SteamVR（openvr_error={}）：请先启动 SteamVR；本操作不会自动启动它",
                                probe.openvr_error.unwrap_or(0)
                            );
                        }
                    }
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                    attach.stop();
                    bail!("SteamVR 镜像辅助程序握手超时");
                }
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    let reason = attach.failure_text();
                    attach.stop();
                    bail!("{reason}");
                }
            }
        }
    }
    pub(crate) fn scene_pid(&self) -> u32 {
        self.scene_pid
    }
    /// `None` while the helper still runs; otherwise the reason it ended,
    /// cached so every loop iteration reports the same real failure.
    pub(crate) fn observe_failure(&mut self) -> Option<&'static str> {
        if self.failure.is_none() {
            match self.child.try_wait() {
                Ok(Some(_)) => self.failure = Some(self.failure_text()),
                Ok(None) => {}
                Err(_) => self.failure = Some("SteamVR 镜像进程状态不可用"),
            }
        }
        self.failure
    }
    /// Call only after the helper ended (or after [`Self::stop`]).
    fn failure_text(&mut self) -> &'static str {
        use std::io::Read;
        let mut text = String::new();
        if let Some(stderr) = self.stderr.take() {
            let _ = stderr.take(4096).read_to_string(&mut text);
        }
        tracing::debug!(stderr = %text, "SteamVR mirror helper ended");
        explain(&text)
    }
    pub(crate) fn stop(&mut self) {
        // `observe_failure` may already have reaped the helper. Never signal
        // its old PID after that point: the kernel may have reused it.
        if !signal_if_running(&mut self.child, |pid| unsafe {
            libc::kill(pid as libc::pid_t, libc::SIGTERM);
        }) {
            return;
        }
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline {
            match self.child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) => std::thread::sleep(Duration::from_millis(10)),
                Err(_) => return,
            }
        }
        match self.child.try_wait() {
            Ok(None) => {
                let _ = self.child.kill();
                let _ = self.child.wait();
            }
            Ok(Some(_)) | Err(_) => {}
        }
    }
}
fn signal_if_running(child: &mut std::process::Child, signal: impl FnOnce(u32)) -> bool {
    match child.try_wait() {
        Ok(None) => {
            signal(child.id());
            true
        }
        Ok(Some(_)) | Err(_) => false,
    }
}
impl Drop for Attach {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_already_reaped_helper_is_not_signaled_again() {
        let mut child = std::process::Command::new("true").spawn().unwrap();
        assert!(child.wait().unwrap().success());

        let mut signaled = false;
        assert!(!signal_if_running(&mut child, |_| signaled = true));
        assert!(!signaled);
    }
}
/// Take this machine's single PCVR ownership lock (the same `publisher.lock`
/// every backend takes) and create the mirror's private export inside 0700
/// directories. The caretaker keeps the lock for as long as it is stored;
/// a second session or a manual diagnostic is refused, never raced.
pub(crate) fn open_export() -> Result<(super::capture::Export, std::fs::File)> {
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::io::AsRawFd;
    let root = PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR").context("缺少 XDG_RUNTIME_DIR")?);
    let base = root.join("openuuyc-pcvr");
    super::device::private_dir(&base)?;
    let dir = base.join("steamvr");
    super::device::private_dir(&dir)?;
    let lock = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(base.join("publisher.lock"))?;
    let meta = lock.metadata()?;
    ensure!(
        meta.is_file() && meta.uid() == unsafe { libc::getuid() } && meta.mode() & 0o077 == 0,
        "发布锁不安全"
    );
    ensure!(
        unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "其他 OpenUUYC 进程正在使用内置 VR"
    );
    let export = super::capture::Export::create(&dir)?;
    Ok((export, lock))
}
/// Manual host-side diagnostic: take the PCVR ownership lock, create the
/// private export and stream an existing SteamVR scene into it until
/// interrupted. Never starts SteamVR, and a live session keeps the lock so
/// this refuses instead of stealing its export.
pub fn attach_diagnostic() -> Result<()> {
    let (export, _lock) = open_export()?;
    let mut attach = Attach::start(export.path())?;
    println!(
        "{}",
        serde_json::json!({"attached": true, "scene_pid": attach.scene_pid()})
    );
    let mut previous = 0;
    let mut frames = 0u64;
    let mut last = Instant::now();
    loop {
        std::thread::sleep(Duration::from_millis(20));
        if let Some(reason) = attach.observe_failure() {
            bail!("{reason}");
        }
        if let Ok(Some(raw)) = super::capture::read_latest(&export.file, previous) {
            previous = raw.sequence;
            frames += 1;
            if last.elapsed() >= Duration::from_secs(1) {
                last = Instant::now();
                println!(
                    "{}",
                    serde_json::json!({"frames": frames, "width": raw.width,
                        "height": raw.height, "origin": raw.origin,
                        "head_pose": raw.head.is_some()})
                );
            }
        }
    }
}
