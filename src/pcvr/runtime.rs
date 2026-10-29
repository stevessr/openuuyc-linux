//! Owned OpenXR component bundle and per-process runtime policy. No global XR switch.
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    ffi::{OsStr, OsString},
    path::{Component, Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Preference {
    #[default]
    OpenXrFirst,
    OpenXrOnly,
    SteamVr,
}
impl Preference {
    pub const ALL: [Self; 3] = [Self::OpenXrFirst, Self::OpenXrOnly, Self::SteamVr];
    pub fn label(self) -> &'static str {
        match self {
            Self::OpenXrFirst => "OpenXR 优先，SteamVR 备用",
            Self::OpenXrOnly => "仅使用内置 OpenXR",
            Self::SteamVr => "SteamVR（备用）",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Api {
    OpenXr,
    OpenVr,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Backend {
    OpenXr,
    SteamVr,
}
#[derive(Serialize, Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct Config {
    #[serde(default)]
    preference: Preference,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    version: u32,
    arch: String,
    monado_revision: String,
    xrizer_revision: String,
    files: BTreeMap<String, String>,
}
#[derive(Clone, Debug)]
pub struct Plan {
    pub backend: Backend,
    pub manifest: PathBuf,
    pub xrizer: Option<PathBuf>,
    pub probe: PathBuf,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Probe {
    pub version: u32,
    pub instance: bool,
    pub system: bool,
    pub instance_result: i32,
    pub system_result: i32,
}

fn config_root() -> Result<PathBuf> {
    std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".config")))
        .context("缺少用户配置目录")
}
pub fn components_root() -> Result<PathBuf> {
    Ok(std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".local/share")))
        .context("缺少用户数据目录")?
        .join("openuuyc/pcvr/components"))
}
pub fn preference() -> Result<Preference> {
    let path = config_root()?.join("openuuyc/pcvr.json");
    match std::fs::read(&path) {
        Ok(bytes) => Ok(serde_json::from_slice::<Config>(&bytes)?.preference),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Preference::default()),
        Err(e) => Err(e.into()),
    }
}
pub fn set_preference(preference: Preference) -> Result<()> {
    let path = config_root()?.join("openuuyc/pcvr.json");
    std::fs::create_dir_all(path.parent().unwrap())?;
    super::atomic_write(&path, &serde_json::to_vec_pretty(&Config { preference })?)
}
fn safe_relative(value: &str) -> Result<&Path> {
    let path = Path::new(value);
    ensure!(
        !value.is_empty()
            && !value.contains('\\')
            && path.components().all(|c| matches!(c, Component::Normal(_))),
        "组件路径不安全"
    );
    Ok(path)
}
fn revision(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|v| v.is_ascii_hexdigit())
}
fn hash(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    ensure!(file.metadata()?.len() <= 256 * 1024 * 1024, "组件文件过大");
    let mut hasher = Sha256::new();
    let mut buffer = [0; 65536];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        };
        hasher.update(&buffer[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
fn bundle(root: &Path) -> Result<(Manifest, Plan)> {
    let canonical = root.canonicalize()?;
    let metadata = std::fs::read(root.join("bundle.json"))?;
    ensure!(metadata.len() <= 1024 * 1024, "组件清单过大");
    let manifest: Manifest = serde_json::from_slice(&metadata)?;
    ensure!(
        manifest.version == 1
            && manifest.arch == std::env::consts::ARCH
            && revision(&manifest.monado_revision)
            && revision(&manifest.xrizer_revision),
        "组件版本、来源或架构不匹配"
    );
    ensure!(manifest.files.len() <= 4096, "组件清单过大");
    for (name, expected) in &manifest.files {
        ensure!(
            expected.len() == 64 && expected.bytes().all(|c| c.is_ascii_hexdigit()),
            "组件哈希无效"
        );
        let path = root.join(safe_relative(name)?);
        ensure!(
            path.canonicalize()?.starts_with(&canonical)
                && !std::fs::symlink_metadata(&path)?.file_type().is_symlink(),
            "组件文件逃出安装目录"
        );
        ensure!(&hash(&path)? == expected, "组件文件损坏：{name}");
    }
    let client = if std::env::consts::ARCH == "aarch64" {
        "xrizer/bin/linuxarm64/vrclient.so"
    } else {
        "xrizer/bin/linux64/vrclient.so"
    };
    for name in [
        "openxr/openxr_openuuyc.json",
        "lib/libopenxr_openuuyc.so",
        client,
        "bin/openuuyc-openxr-probe",
    ] {
        ensure!(manifest.files.contains_key(name), "缺少组件文件：{name}");
    }
    let runtime = root.join("openxr/openxr_openuuyc.json");
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(&runtime)?)?;
    let library = json["runtime"]["library_path"]
        .as_str()
        .context("OpenXR 清单缺少库路径")?;
    ensure!(
        runtime.parent().unwrap().join(library).canonicalize()?
            == root.join("lib/libopenxr_openuuyc.so").canonicalize()?,
        "OpenXR 清单指向未管理的运行库"
    );
    Ok((
        manifest,
        Plan {
            backend: Backend::OpenXr,
            manifest: runtime,
            xrizer: Some(root.join("xrizer")),
            probe: root.join("bin/openuuyc-openxr-probe"),
        },
    ))
}
fn active_bundle() -> Result<Option<PathBuf>> {
    let root = components_root()?;
    let data = match std::fs::read(root.join("active.json")) {
        Ok(v) => v,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let name: String = serde_json::from_slice(&data)?;
    ensure!(
        name.len() <= 100 && name.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-'),
        "活动组件目录无效"
    );
    let path = root.join(name);
    ensure!(
        path.canonicalize()?.starts_with(root.canonicalize()?),
        "活动组件路径不安全"
    );
    Ok(Some(path))
}
fn steam_runtime() -> Result<Option<PathBuf>> {
    let path = config_root()?.join("openvr/openvrpaths.vrpath");
    let data = match std::fs::read(path) {
        Ok(v) => v,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let json: serde_json::Value = serde_json::from_slice(&data)?;
    Ok(json["runtime"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|p| p.as_str())
        .map(|p| Path::new(p).join("steamxr_linux64.json"))
        .find(|p| p.is_file()))
}
fn select(
    preference: Preference,
    openxr: Option<Plan>,
    steam: Option<PathBuf>,
    probe: Option<PathBuf>,
) -> Result<Plan> {
    if preference != Preference::SteamVr {
        if let Some(plan) = openxr {
            return Ok(plan);
        };
        ensure!(
            preference != Preference::OpenXrOnly,
            "内置 OpenXR 组件尚未安装"
        );
    }
    let manifest = steam.context("没有可用后端：内置 OpenXR 未安装，SteamVR 备用也不可用")?;
    Ok(Plan {
        backend: Backend::SteamVr,
        manifest,
        xrizer: None,
        probe: probe.unwrap_or_default(),
    })
}
pub fn plan() -> Result<Plan> {
    let preference = preference()?;
    if preference == Preference::SteamVr {
        return select(preference, None, steam_runtime()?, None);
    }
    let owned = active_bundle()?
        .map(|p| bundle(&p).map(|(_, plan)| plan))
        .transpose()?;
    let probe = owned.as_ref().map(|p| p.probe.clone());
    select(preference, owned, steam_runtime()?, probe)
}

impl Plan {
    pub fn apply(&self, command: &mut Command, api: Api) -> Result<()> {
        command
            .env_remove("XR_RUNTIME_JSON")
            .env_remove("VR_OVERRIDE");
        command.env("XR_RUNTIME_JSON", &self.manifest);
        if api == Api::OpenVr && self.backend == Backend::OpenXr {
            let root = self.xrizer.as_ref().context("缺少 OpenVR→OpenXR 兼容库")?;
            command.env("VR_OVERRIDE", root);
        }
        Ok(())
    }
    pub fn steam_options(&self, api: Api) -> Result<String> {
        fn quote(value: &OsStr) -> String {
            format!("'{}'", value.to_string_lossy().replace('\'', "'\\''"))
        }
        let mut text = format!(
            "env -u VR_OVERRIDE XR_RUNTIME_JSON={} ",
            quote(self.manifest.as_os_str())
        );
        if api == Api::OpenVr && self.backend == Backend::OpenXr {
            text.push_str(&format!(
                "VR_OVERRIDE={} ",
                quote(self.xrizer.as_ref().context("缺少 xrizer")?.as_os_str())
            ));
        }
        text.push_str("%command%");
        Ok(text)
    }
    pub fn probe(&self) -> Result<Probe> {
        use std::io::Read;
        let mut command = if self.probe.is_file() {
            Command::new(&self.probe)
        } else {
            let mut child = Command::new(std::env::current_exe()?);
            child.args(["pico-vr", "runtime-probe"]);
            child
        };
        self.apply(&mut command, Api::OpenXr)?;
        let mut child = command
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .context("无法启动随包的运行时探针")?;
        let mut pipe = child.stdout.take().unwrap();
        let reader = std::thread::spawn(move || {
            let mut output = Vec::new();
            pipe.by_ref()
                .take(8192)
                .read_to_end(&mut output)
                .map(|_| output)
        });
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if child.try_wait()?.is_some() {
                break;
            }
            if Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                let _ = reader.join();
                anyhow::bail!("运行时探针超时，未启动游戏")
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let data = reader
            .join()
            .map_err(|_| anyhow::anyhow!("探针输出读取失败"))??;
        let result: Probe = serde_json::from_slice(&data).context("运行时探针返回无效状态")?;
        ensure!(result.version == 1, "探针协议不兼容");
        Ok(result)
    }
}
pub fn run(api: Api, argv: &[OsString]) -> Result<()> {
    let mut plan = plan()?;
    let mut probe = plan.probe()?;
    if !probe.system
        && probe.instance
        && plan.backend == Backend::OpenXr
        && preference()? == Preference::OpenXrFirst
    {
        if let Some(manifest) = steam_runtime()? {
            eprintln!("内置 OpenXR 尚未发现头显，尝试 SteamVR 备用；不修改全局运行时");
            plan = Plan {
                backend: Backend::SteamVr,
                manifest,
                xrizer: None,
                probe: plan.probe.clone(),
            };
            probe = plan.probe()?;
        }
    }
    ensure!(
        probe.instance && probe.system,
        "运行时组件可加载={}，头显可用={}：PICO 设备/合成器尚未就绪；未启动游戏",
        probe.instance,
        probe.system
    );
    if api == Api::OpenVr && plan.backend == Backend::OpenXr {
        ensure!(
            config_root()?.join("openvr/openvrpaths.vrpath").is_file(),
            "xrizer 需要有效 OpenVR 路径文件；请显式执行 components-init-openvr（不需要安装 SteamVR）"
        );
    }
    let executable = argv.first().context("缺少应用命令")?;
    let mut command = Command::new(executable);
    command.args(&argv[1..]);
    plan.apply(&mut command, api)?;
    let status = command.status()?;
    ensure!(status.success(), "VR 应用退出：{status}");
    Ok(())
}
pub fn status() -> String {
    let label = preference()
        .map(|p| p.label().to_owned())
        .unwrap_or_else(|e| format!("设置错误：{e}"));
    match active_bundle() {
        Ok(Some(path)) => format!(
            "{label} · OpenXR 组件已安装：{}（启动前完整校验；PICO 设备后端仍在开发）",
            path.display()
        ),
        Ok(None) => format!("{label} · 内置 OpenXR 组件未安装"),
        Err(error) => format!("{label} · {error}"),
    }
}

pub fn install(source: &Path) -> Result<PathBuf> {
    let (metadata, _) = bundle(source)?;
    let base = components_root()?;
    std::fs::create_dir_all(&base)?;
    let content = hash(&source.join("bundle.json"))?;
    let name = format!(
        "{}-{}-{}",
        &metadata.monado_revision[..12],
        &metadata.xrizer_revision[..12],
        &content[..12]
    );
    let dest = base.join(&name);
    if !dest.exists() {
        let temp = base.join(format!("install-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&temp)?;
        let result = (|| -> Result<()> {
            for relative in metadata.files.keys() {
                let path = temp.join(safe_relative(relative)?);
                std::fs::create_dir_all(path.parent().unwrap())?;
                std::fs::copy(source.join(relative), path)?;
            }
            std::fs::copy(source.join("bundle.json"), temp.join("bundle.json"))?;
            bundle(&temp)?;
            std::fs::rename(&temp, &dest)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_dir_all(&temp);
        }
        result?;
    }
    bundle(&dest)?;
    let active = base.join("active.json");
    if active.is_file() && !base.join("active.json.backup").exists() {
        std::fs::copy(&active, base.join("active.json.backup"))?;
    }
    super::atomic_write(&active, &serde_json::to_vec(&name)?)?;
    Ok(dest)
}
pub fn init_openvr() -> Result<()> {
    let plan = plan()?;
    let xrizer = plan.xrizer.context("当前不是内置 OpenXR 后端")?;
    let path = config_root()?.join("openvr/openvrpaths.vrpath");
    std::fs::create_dir_all(path.parent().unwrap())?;
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .context("已有 OpenVR 配置，拒绝覆盖；继续使用 VR_OVERRIDE 即可")?;
    file.write_all(&serde_json::to_vec_pretty(
        &serde_json::json!({"version":1,"runtime":[xrizer],"external_drivers":[]}),
    )?)?;
    file.sync_all()?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn owned() -> Plan {
        Plan {
            backend: Backend::OpenXr,
            manifest: "/home/test/openxr.json".into(),
            xrizer: Some("/home/test/xrizer".into()),
            probe: "/home/test/probe".into(),
        }
    }
    #[test]
    fn default_prefers_openxr_and_allows_explicit_steam_fallback() {
        assert_eq!(Preference::default(), Preference::OpenXrFirst);
        assert_eq!(
            select(
                Preference::OpenXrFirst,
                Some(owned()),
                Some("steam.json".into()),
                Some("probe".into())
            )
            .unwrap()
            .backend,
            Backend::OpenXr
        );
        assert_eq!(
            select(
                Preference::OpenXrFirst,
                None,
                Some("steam.json".into()),
                Some("probe".into())
            )
            .unwrap()
            .backend,
            Backend::SteamVr
        );
        assert!(
            select(
                Preference::OpenXrOnly,
                None,
                Some("steam.json".into()),
                Some("probe".into())
            )
            .is_err()
        );
        assert_eq!(
            select(
                Preference::SteamVr,
                Some(owned()),
                Some("steam.json".into()),
                Some("probe".into())
            )
            .unwrap()
            .backend,
            Backend::SteamVr
        );
    }
    #[test]
    fn child_environment_is_scoped_and_removes_stale_overrides() {
        let mut child = Command::new("game");
        owned().apply(&mut child, Api::OpenVr).unwrap();
        let values: Vec<_> = child.get_envs().collect();
        assert!(
            values.iter().any(|(key, value)| *key == "VR_OVERRIDE"
                && *value == Some(OsStr::new("/home/test/xrizer")))
        );
        let steam = Plan {
            backend: Backend::SteamVr,
            manifest: "steam.json".into(),
            xrizer: None,
            probe: "probe".into(),
        };
        steam.apply(&mut child, Api::OpenVr).unwrap();
        assert!(
            child
                .get_envs()
                .any(|(key, value)| key == "VR_OVERRIDE" && value.is_none())
        );
    }
    #[test]
    fn rejects_paths_that_escape_a_bundle() {
        for name in ["../a", "/tmp/lib.so", "a/../../b", "a\\b", ""] {
            assert!(safe_relative(name).is_err())
        }
    }
    #[test]
    fn steam_launch_options_quote_paths_without_changing_global_config() {
        let mut plan = owned();
        plan.manifest = "/home/a'b/runtime.json".into();
        let options = plan.steam_options(Api::OpenVr).unwrap();
        assert!(options.contains("a'\\''b"));
        assert!(options.ends_with("%command%"));
        assert!(options.contains("VR_OVERRIDE="));
    }
}

/// Resolve only a currently listening local component endpoint, never invent an HMD.
pub(crate) fn tracking_socket(
    root: &Path,
    preference: Preference,
) -> Result<(PathBuf, &'static str)> {
    use std::os::unix::fs::FileTypeExt;
    let openxr = root.join("openuuyc-pcvr/openxr/tracking.sock");
    let steam = root.join("openuuyc-pcvr/tracking.sock");
    let socket =
        |path: &Path| std::fs::symlink_metadata(path).is_ok_and(|m| m.file_type().is_socket());
    if preference != Preference::SteamVr && socket(&openxr) {
        return Ok((openxr, "openxr"));
    }
    ensure!(
        preference != Preference::OpenXrOnly,
        "OpenXR PICO 设备组件尚未运行；仅 OpenXR 模式不会回退 SteamVR"
    );
    ensure!(
        socket(&steam),
        "OpenXR PICO 设备端点未就绪，SteamVR 备用追踪驱动也未运行"
    );
    Ok((steam, "steamvr-tracking-fallback"))
}

#[cfg(test)]
mod endpoint_tests {
    use super::*;
    #[test]
    fn tracking_prefers_openxr_and_never_falls_back_in_only_mode() {
        let root =
            std::env::temp_dir().join(format!("openuuyc-runtime-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(root.join("openuuyc-pcvr/openxr")).unwrap();
        let steam =
            std::os::unix::net::UnixDatagram::bind(root.join("openuuyc-pcvr/tracking.sock"))
                .unwrap();
        assert_eq!(
            tracking_socket(&root, Preference::OpenXrFirst).unwrap().1,
            "steamvr-tracking-fallback"
        );
        assert!(tracking_socket(&root, Preference::OpenXrOnly).is_err());
        let openxr =
            std::os::unix::net::UnixDatagram::bind(root.join("openuuyc-pcvr/openxr/tracking.sock"))
                .unwrap();
        assert_eq!(
            tracking_socket(&root, Preference::OpenXrFirst).unwrap().1,
            "openxr"
        );
        assert_eq!(
            tracking_socket(&root, Preference::SteamVr).unwrap().1,
            "steamvr-tracking-fallback"
        );
        drop((steam, openxr));
        std::fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
mod bundle_tests {
    use super::*;
    #[test]
    fn verified_bundle_rejects_tampering_and_wrong_architecture() {
        let root = std::env::temp_dir().join(format!("openuuyc-bundle-{}", uuid::Uuid::new_v4()));
        let client = if std::env::consts::ARCH == "aarch64" {
            "xrizer/bin/linuxarm64/vrclient.so"
        } else {
            "xrizer/bin/linux64/vrclient.so"
        };
        let mut files = BTreeMap::new();
        for name in [
            "lib/libopenxr_openuuyc.so",
            client,
            "bin/openuuyc-openxr-probe",
        ] {
            let path = root.join(name);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, b"test component").unwrap();
            files.insert(name.to_owned(), hash(&path).unwrap());
        }
        std::fs::create_dir_all(root.join("openxr")).unwrap();
        let runtime = root.join("openxr/openxr_openuuyc.json");
        std::fs::write(&runtime,br#"{"file_format_version":"1.0.0","runtime":{"library_path":"../lib/libopenxr_openuuyc.so"}}"#).unwrap();
        files.insert(
            "openxr/openxr_openuuyc.json".into(),
            hash(&runtime).unwrap(),
        );
        let mut manifest = Manifest {
            version: 1,
            arch: std::env::consts::ARCH.into(),
            monado_revision: "a".repeat(40),
            xrizer_revision: "b".repeat(40),
            files,
        };
        let save = |manifest: &Manifest| {
            std::fs::write(
                root.join("bundle.json"),
                serde_json::to_vec(manifest).unwrap(),
            )
            .unwrap()
        };
        save(&manifest);
        assert!(bundle(&root).is_ok());
        std::fs::write(root.join(client), b"tampered").unwrap();
        assert!(bundle(&root).is_err());
        std::fs::write(root.join(client), b"test component").unwrap();
        manifest.arch = "wrong".into();
        save(&manifest);
        assert!(bundle(&root).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn remote_setting_names_match_the_android_choices() {
        assert_eq!(
            serde_json::to_string(&Preference::OpenXrFirst).unwrap(),
            "\"open_xr_first\""
        );
        assert_eq!(
            serde_json::to_string(&Preference::OpenXrOnly).unwrap(),
            "\"open_xr_only\""
        );
        assert_eq!(
            serde_json::to_string(&Preference::SteamVr).unwrap(),
            "\"steam_vr\""
        );
    }
}
