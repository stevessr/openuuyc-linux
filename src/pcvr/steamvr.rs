//! Inspect a running SteamVR compositor without launching a runtime or game.
use anyhow::{Context, Result, ensure};
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
pub fn probe() -> Result<Probe> {
    use std::io::Read;
    let helper = install_helper()?;
    let manifest = super::runtime::steam_runtime()?.context("SteamVR runtime is not registered")?;
    let library = manifest
        .parent()
        .unwrap()
        .join("bin/linux64/libopenvr_api.so");
    ensure!(
        library.is_file(),
        "installed SteamVR client library is missing"
    );
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
