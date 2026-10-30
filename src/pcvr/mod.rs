//! Built-in PCVR components. Currently a tracking-only bridge, not a stereo HMD.
use anyhow::{Context, Result, ensure};
use std::path::PathBuf;
#[cfg(target_os = "linux")]
pub(crate) mod host;
pub(crate) mod protocol;

#[cfg(target_os = "linux")]
pub fn install_driver() -> Result<()> {
    let root = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .context("缺少用户数据目录")?
        .join("openuuyc/pcvr/openuuyc");
    let config = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
        .context("缺少用户配置目录")?
        .join("openvr/openvrpaths.vrpath");
    install_driver_at(&root, &config)?;
    println!(
        "已安装内置追踪桥接驱动：{}\n重启 SteamVR 后生效。当前仅为追踪诊断，不提供双眼画面，不会启动游戏。",
        root.display()
    );
    Ok(())
}
#[cfg(target_os = "linux")]
fn install_driver_at(root: &std::path::Path, config: &std::path::Path) -> Result<()> {
    std::fs::create_dir_all(root.join("bin/linux64"))?;
    atomic_write(
        &root.join("bin/linux64/driver_openuuyc.so"),
        include_bytes!(concat!(env!("OUT_DIR"), "/driver_openuuyc.so")),
    )?;
    atomic_write(
        &root.join("driver.vrdrivermanifest"),
        include_bytes!("../../drivers/pcvr/driver.vrdrivermanifest"),
    )?;
    atomic_write(
        &root.join("LICENSE.openvr"),
        include_bytes!("../../drivers/pcvr/vendor/LICENSE.openvr"),
    )?;
    // Existing runtime configuration is required; never invent/replace SteamVR paths.
    let original =
        std::fs::read(&config).context("请先安装并运行一次 SteamVR；找不到 openvrpaths.vrpath")?;
    let mut value: serde_json::Value = serde_json::from_slice(&original)?;
    ensure!(value.is_object(), "OpenVR 路径配置格式无效");
    let entry = value
        .as_object_mut()
        .unwrap()
        .entry("external_drivers")
        .or_insert(serde_json::json!([]));
    if entry.is_null() {
        *entry = serde_json::json!([]);
    }
    let list = entry
        .as_array_mut()
        .context("external_drivers 必须为数组")?;
    let path = root.canonicalize()?.to_string_lossy().into_owned();
    if !list.iter().any(|v| v.as_str() == Some(&path)) {
        list.push(serde_json::json!(path));
        // Do not overwrite an earlier backup, and detect a concurrent config change.
        let backup = config.with_extension("vrpath.openuuyc-backup");
        use std::io::Write;
        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&backup)
        {
            Ok(mut file) => {
                file.write_all(&original)?;
                file.sync_all()?;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(e.into()),
        }
        ensure!(
            std::fs::read(&config)? == original,
            "OpenVR 配置已被其他进程修改，请重试"
        );
        atomic_write(&config, &serde_json::to_vec_pretty(&value)?)?;
    }
    Ok(())
}
#[cfg(target_os = "linux")]
fn atomic_write(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    let temp = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> Result<()> {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        std::fs::rename(&temp, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(temp);
    }
    result
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    #[test]
    fn installation_preserves_runtime_and_is_idempotent() {
        let temp =
            std::env::temp_dir().join(format!("openuuyc-driver-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&temp).unwrap();
        let root = temp.join("driver");
        let config = temp.join("openvrpaths.vrpath");
        let original=br#"{"runtime":["/my/SteamVR"],"config":["/my/settings"],"external_drivers":["/existing-driver"],"version":1}"#;
        std::fs::write(&config, original).unwrap();
        install_driver_at(&root, &config).unwrap();
        let first = std::fs::read(&config).unwrap();
        install_driver_at(&root, &config).unwrap();
        assert_eq!(std::fs::read(&config).unwrap(), first);
        let json: serde_json::Value = serde_json::from_slice(&first).unwrap();
        assert_eq!(json["runtime"], serde_json::json!(["/my/SteamVR"]));
        assert_eq!(json["external_drivers"].as_array().unwrap().len(), 2);
        assert_eq!(json["external_drivers"][0], "/existing-driver");
        assert_eq!(
            std::fs::read(config.with_extension("vrpath.openuuyc-backup")).unwrap(),
            original
        );
        assert!(
            std::fs::metadata(root.join("bin/linux64/driver_openuuyc.so"))
                .unwrap()
                .len()
                > 0
        );
        // SteamVR itself writes null when no external drivers are registered.
        std::fs::write(
            &config,
            br#"{"runtime":["/my/SteamVR"],"external_drivers":null,"version":1}"#,
        )
        .unwrap();
        install_driver_at(&root, &config).unwrap();
        let json: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&config).unwrap()).unwrap();
        assert_eq!(json["external_drivers"].as_array().unwrap().len(), 1);
        assert_eq!(json["runtime"], serde_json::json!(["/my/SteamVR"]));
        std::fs::write(&config, b"{broken").unwrap();
        assert!(install_driver_at(&root, &config).is_err());
        assert_eq!(std::fs::read(&config).unwrap(), b"{broken");
        std::fs::remove_dir_all(temp).unwrap();
    }
}

pub mod stereo;

#[cfg(target_os = "linux")]
pub mod runtime;

#[cfg(target_os = "linux")]
pub mod runtime_probe;

#[cfg(target_os = "linux")]
pub(crate) mod device;

#[cfg(target_os = "linux")]
mod capture;
#[cfg(target_os = "linux")]
mod encode;

#[cfg(target_os = "linux")]
pub mod steamvr;

#[cfg(target_os = "linux")]
pub(crate) mod applications;
