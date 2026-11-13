//! Install the running build into the user's desktop environment.
//!
//! This is the GUI/CLI counterpart of `install-omarchy.sh` for people who run
//! a portable build: it copies the current binary to `~/.local/bin`, writes a
//! desktop entry and icon, and registers the `uuremote://` handler. Nothing
//! here needs root, and uninstalling keeps user data.
use anyhow::{Context as _, Result, bail};
use std::path::{Path, PathBuf};

const APP: &str = "OpenUUYC";
const DESKTOP: &str = "openuuyc.desktop";
const ICON: &str = "openuuyc.png";
const ICON_BYTES: &[u8] = include_bytes!("../../../assets/icon-256.png");

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Status {
    pub installed: bool,
    pub binary: PathBuf,
    pub desktop: PathBuf,
    pub icon: PathBuf,
    pub current: PathBuf,
}

#[derive(Clone, Debug)]
struct Paths {
    binary: PathBuf,
    desktop: PathBuf,
    icon: PathBuf,
}

pub(crate) fn status() -> Status {
    let paths = paths();
    Status {
        installed: paths.binary.is_file() && paths.desktop.is_file(),
        binary: paths.binary,
        desktop: paths.desktop,
        icon: paths.icon,
        current: std::env::current_exe().unwrap_or_default(),
    }
}

pub(crate) fn install() -> Result<Status> {
    let paths = paths();
    let current = std::env::current_exe().context("找不到当前可执行文件")?;
    install_from(&current, &paths)?;
    refresh_caches(&paths);
    Ok(status())
}

pub(crate) fn uninstall() -> Result<Status> {
    let paths = paths();
    remove_file(&paths.binary)?;
    remove_file(&paths.desktop)?;
    remove_file(&paths.icon)?;
    refresh_caches(&paths);
    Ok(status())
}

fn install_from(current: &Path, paths: &Paths) -> Result<()> {
    let Some(parent) = paths.binary.parent() else {
        bail!("安装路径没有父目录：{}", paths.binary.display());
    };
    std::fs::create_dir_all(parent)
        .with_context(|| format!("创建 {} 失败", parent.display()))?;
    if current != paths.binary {
        // Write a sibling and rename: replacing a running binary in place can
        // fail with ETXTBSY on Linux.
        let staged = paths.binary.with_extension("new");
        std::fs::copy(current, &staged).with_context(|| {
            format!(
                "复制 {} 到 {} 失败",
                current.display(),
                staged.display()
            )
        })?;
        set_executable(&staged)?;
        std::fs::rename(&staged, &paths.binary)
            .with_context(|| format!("安装到 {} 失败", paths.binary.display()))?;
    }
    let desktop_dir = paths
        .desktop
        .parent()
        .context("桌面入口路径没有父目录")?;
    std::fs::create_dir_all(desktop_dir)
        .with_context(|| format!("创建 {} 失败", desktop_dir.display()))?;
    std::fs::write(&paths.desktop, desktop_entry(&paths.binary))
        .with_context(|| format!("写入 {} 失败", paths.desktop.display()))?;
    let icon_dir = paths.icon.parent().context("图标路径没有父目录")?;
    std::fs::create_dir_all(icon_dir)
        .with_context(|| format!("创建 {} 失败", icon_dir.display()))?;
    std::fs::write(&paths.icon, ICON_BYTES)
        .with_context(|| format!("写入 {} 失败", paths.icon.display()))?;
    Ok(())
}

fn desktop_entry(binary: &Path) -> String {
    // Quote the executable path so spaces and other characters survive the
    // desktop-entry Exec parsing rules.
    format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name={APP}\n\
         GenericName=Remote Desktop\n\
         GenericName[zh_CN]=远程桌面\n\
         Comment=Third-party UU Remote client\n\
         Comment[zh_CN]=UU 远程第三方客户端\n\
         Exec=\"{}\" %u\n\
         Icon=openuuyc\n\
         Terminal=false\n\
         Categories=Network;RemoteAccess;\n\
         Keywords=remote;desktop;uu;远程;\n\
         StartupNotify=true\n\
         StartupWMClass=OpenUUYC\n\
         MimeType=x-scheme-handler/uuremote;\n",
        binary.display()
    )
}

fn paths() -> Paths {
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"));
    let data = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .unwrap_or_else(|| home.join(".local/share"));
    Paths {
        binary: home.join(".local/bin").join(APP),
        desktop: data.join("applications").join(DESKTOP),
        icon: data
            .join("icons/hicolor/256x256/apps")
            .join(ICON),
    }
}

fn set_executable(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        let mut permissions = std::fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

fn remove_file(path: &Path) -> Result<()> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("删除 {} 失败", path.display())),
    }
}

/// Desktop caches are optional; a missing helper must not fail the install.
fn refresh_caches(paths: &Paths) {
    if let Some(applications) = paths.desktop.parent()
        && let Ok(database) = std::process::Command::new("update-desktop-database")
            .arg(applications)
            .status()
    {
        let _ = database;
    }
    if let Some(theme) = paths.icon.parent().and_then(|path| path.parent()) {
        if let Some(hicolor) = theme.parent() {
            let _ = std::process::Command::new("gtk-update-icon-cache")
                .arg("-q")
                .arg("-t")
                .arg("-f")
                .arg(hicolor)
                .status();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_entry_quotes_the_binary_and_keeps_the_scheme_handler() {
        let entry = desktop_entry(Path::new("/home/u/My Apps/OpenUUYC"));
        assert!(entry.contains("Exec=\"/home/u/My Apps/OpenUUYC\" %u"));
        assert!(entry.contains("Icon=openuuyc"));
        assert!(entry.contains("MimeType=x-scheme-handler/uuremote;"));
        assert!(entry.contains("StartupWMClass=OpenUUYC"));
    }

    #[test]
    fn install_and_uninstall_round_trip_in_a_temporary_home() {
        let root = std::env::temp_dir().join(format!(
            "openuuyc-install-test-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let source = root.join("source/OpenUUYC");
        std::fs::create_dir_all(source.parent().unwrap()).unwrap();
        std::fs::write(&source, b"binary").unwrap();
        let paths = Paths {
            binary: root.join("home/.local/bin/OpenUUYC"),
            desktop: root.join("home/.local/share/applications/openuuyc.desktop"),
            icon: root
                .join("home/.local/share/icons/hicolor/256x256/apps/openuuyc.png"),
        };
        install_from(&source, &paths).unwrap();
        assert!(paths.binary.is_file());
        assert!(paths.desktop.is_file());
        assert!(paths.icon.is_file());
        assert_eq!(std::fs::read(&paths.binary).unwrap(), b"binary");
        remove_file(&paths.binary).unwrap();
        remove_file(&paths.desktop).unwrap();
        remove_file(&paths.icon).unwrap();
        assert!(!paths.binary.exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
