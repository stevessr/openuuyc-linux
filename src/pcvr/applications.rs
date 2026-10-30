//! Owner-only process inventory. Return names/PIDs, never command lines or maps.
use std::{io::Read, os::unix::fs::MetadataExt};
#[derive(serde::Serialize)]
pub(crate) struct Application {
    pub pid: u32,
    pub name: String,
    pub backend: &'static str,
}
fn owned_runtime(maps: &[u8]) -> bool {
    String::from_utf8_lossy(maps).lines().any(|line| {
        line.contains("/libopenxr_openuuyc.so") && line.contains("/openuuyc/pcvr/components/")
    })
}
pub(crate) fn running() -> Vec<Application> {
    let Ok(processes) = std::fs::read_dir("/proc") else {
        return Vec::new();
    };
    let uid = unsafe { libc::getuid() };
    let mut out = Vec::new();
    for process in processes.flatten() {
        let Some(pid) = process
            .file_name()
            .to_str()
            .and_then(|s| s.parse::<u32>().ok())
        else {
            continue;
        };
        if !process.metadata().is_ok_and(|m| m.uid() == uid) {
            continue;
        }
        let Ok(file) = std::fs::File::open(process.path().join("maps")) else {
            continue;
        };
        let mut maps = Vec::new();
        if file.take(16 * 1024 * 1024).read_to_end(&mut maps).is_err() || !owned_runtime(&maps) {
            continue;
        }
        let name = std::fs::read_to_string(process.path().join("comm"))
            .unwrap_or_default()
            .chars()
            .filter(|c| !c.is_control())
            .take(80)
            .collect::<String>();
        out.push(Application {
            pid,
            name,
            backend: "owned-openxr",
        });
        if out.len() >= 256 {
            break;
        }
    }
    out.sort_by_key(|app| app.pid);
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn inventory_requires_owned_runtime_not_just_a_vr_name() {
        assert!(owned_runtime(b"000 r-xp 0 0 1 /home/a/.local/share/openuuyc/pcvr/components/test/lib/libopenxr_openuuyc.so\n"));
        assert!(!owned_runtime(
            b"000 r-xp 0 0 1 /external/libopenxr_openuuyc.so\n"
        ));
        assert!(!owned_runtime(b"VRChat.exe\n"));
    }
}
