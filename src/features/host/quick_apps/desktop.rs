//! Desktop-entry discovery only. Exec expansion/activation belongs to GIO,
//! never to a shell or a remote-supplied command line.
use std::os::unix::fs::PermissionsExt;
use std::{
    collections::{BTreeMap, HashSet},
    path::{Path, PathBuf},
};

pub(super) fn roots(
    home: Option<&Path>,
    data_home: Option<&Path>,
    data_dirs: Option<&str>,
) -> Vec<PathBuf> {
    let mut roots = Vec::new();
    if let Some(data) = data_home
        .filter(|p| p.is_absolute())
        .map(Path::to_path_buf)
        .or_else(|| home.map(|p| p.join(".local/share")))
    {
        roots.push(data.join("applications"));
    }
    for dir in data_dirs
        .filter(|s| !s.is_empty())
        .unwrap_or("/usr/local/share:/usr/share")
        .split(':')
    {
        let path = Path::new(dir);
        if path.is_absolute() {
            roots.push(path.join("applications"));
        }
    }
    // Some sessions do not export packaging-specific XDG directories.
    if let Some(home) = home {
        roots.push(home.join(".local/share/flatpak/exports/share/applications"));
    }
    roots.push("/var/lib/flatpak/exports/share/applications".into());
    roots.push("/var/lib/snapd/desktop/applications".into());
    let mut seen = HashSet::new();
    roots.retain(|p| seen.insert(p.clone()));
    roots
}

fn locale_keys(locale: &str) -> Vec<String> {
    let (base, modifier) = locale
        .split_once('@')
        .map_or((locale, None), |(a, b)| (a, Some(b)));
    let base = base.split('.').next().unwrap_or(base);
    let lang = base.split('_').next().unwrap_or(base);
    let mut keys = Vec::new();
    if let Some(m) = modifier {
        keys.push(format!("{base}@{m}"));
    }
    keys.push(base.to_owned());
    if base != lang {
        if let Some(m) = modifier {
            keys.push(format!("{lang}@{m}"));
        }
        keys.push(lang.to_owned());
    }
    keys
}

fn unescape(value: &str) -> Option<String> {
    let mut out = String::new();
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        out.push(if c == '\\' {
            match chars.next()? {
                's' => ' ',
                'n' => '\n',
                't' => '\t',
                'r' => '\r',
                '\\' => '\\',
                _ => return None,
            }
        } else {
            c
        });
    }
    Some(out)
}

pub(super) fn executable(value: &str) -> bool {
    let check = |p: &Path| {
        p.is_file()
            && p.metadata()
                .is_ok_and(|m| m.permissions().mode() & 0o111 != 0)
    };
    let path = Path::new(value);
    if path.is_absolute() {
        return check(path);
    }
    if value.is_empty() || value.contains('/') {
        return false;
    }
    std::env::var_os("PATH")
        .is_some_and(|paths| std::env::split_paths(&paths).any(|p| check(&p.join(value))))
}

pub(super) fn name(
    text: &str,
    locale: &str,
    desktop: &str,
    available: impl Fn(&str) -> bool,
) -> Option<String> {
    let mut main = false;
    let mut fields = BTreeMap::new();
    for line in text.lines().map(str::trim) {
        if line.starts_with('[') {
            main = line == "[Desktop Entry]";
            continue;
        }
        if !main || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            fields.insert(key.trim(), value.trim());
        }
    }
    let get = |key| fields.get(key).copied().unwrap_or("");
    if get("Type") != "Application" || get("Hidden") == "true" || get("NoDisplay") == "true" {
        return None;
    }
    if get("Exec").is_empty() && get("DBusActivatable") != "true" {
        return None;
    }
    for current in desktop.split(':').filter(|s| !s.is_empty()) {
        if get("OnlyShowIn").split(';').any(|s| s == current) {
            break;
        }
        if get("NotShowIn").split(';').any(|s| s == current) {
            return None;
        }
    }
    if !get("OnlyShowIn").is_empty()
        && !desktop
            .split(':')
            .any(|d| !d.is_empty() && get("OnlyShowIn").split(';').any(|s| s == d))
    {
        return None;
    }
    if !get("TryExec").is_empty() && !available(&unescape(get("TryExec"))?) {
        return None;
    }
    let base = get("Name");
    if base.is_empty() {
        return None;
    }
    let selected = locale_keys(locale)
        .iter()
        .find_map(|key| fields.get(format!("Name[{key}]").as_str()).copied())
        .unwrap_or(base);
    let name = unescape(selected)?;
    (!name.is_empty() && name.len() <= 160 && !name.chars().any(char::is_control)).then_some(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    const BASE: &str = "[Desktop Entry]\nType=Application\nName=Editor\nExec=editor %U\n";
    #[test]
    fn locale_fallback() {
        let text = format!("{BASE}Name[zh]=编辑器\nName[zh_CN]=中国编辑器\nName[sr@Latn]=Latin\n");
        assert_eq!(
            name(&text, "zh_CN.UTF-8", "niri", |_| true).as_deref(),
            Some("中国编辑器")
        );
        assert_eq!(
            name(&text, "zh_HK.UTF-8", "niri", |_| true).as_deref(),
            Some("编辑器")
        );
        assert_eq!(
            name(&text, "sr_YU.UTF-8@Latn", "niri", |_| true).as_deref(),
            Some("Latin")
        );
    }
    #[test]
    fn visibility_and_tryexec() {
        for extra in [
            "Hidden=true",
            "NoDisplay=true",
            "OnlyShowIn=GNOME;",
            "NotShowIn=niri;",
            "TryExec=missing",
        ] {
            assert!(
                name(&format!("{BASE}{extra}"), "C", "niri", |_| false).is_none(),
                "{extra}"
            );
        }
        assert!(
            name(
                &format!("{BASE}OnlyShowIn=GNOME;"),
                "C",
                "GNOME:Unity",
                |_| true
            )
            .is_some()
        );
    }
    #[test]
    fn dbus_only_and_escaped_name() {
        assert_eq!(
            name(
                "[Desktop Entry]\nType=Application\nName=My\\sApp\nDBusActivatable=true",
                "C",
                "",
                |_| true
            )
            .as_deref(),
            Some("My App")
        );
        assert!(
            name(
                "[Desktop Entry]\nType=Application\nName=Bad\\nName\nExec=true",
                "C",
                "",
                |_| true
            )
            .is_none()
        );
    }
    #[test]
    fn xdg_priority_and_relative_paths() {
        let r = roots(
            Some(Path::new("/home/test")),
            Some(Path::new("bad")),
            Some("/opt/apps:/usr/share:/opt/apps:relative"),
        );
        assert_eq!(
            &r[..3],
            &[
                PathBuf::from("/home/test/.local/share/applications"),
                "/opt/apps/applications".into(),
                "/usr/share/applications".into()
            ]
        );
        let r = roots(None, None, None);
        assert_eq!(r[0], PathBuf::from("/usr/local/share/applications"));
    }
    #[test]
    fn installed_program_check() {
        assert!(executable("/bin/sh"));
        assert!(!executable("/"));
        assert!(!executable("absent-openuuyc-test-program"));
    }
}
