//! Installed Steam manifests, including additional library folders.
//! Parse quoted KeyValues tokens; never execute manifest contents.
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};
fn tokens(text: &str) -> Option<Vec<String>> {
    let mut chars = text.chars().peekable();
    let mut out = Vec::new();
    while let Some(c) = chars.next() {
        if c == '/' && chars.peek() == Some(&'/') {
            for c in chars.by_ref() {
                if c == '\n' {
                    break;
                }
            }
        } else if c == '"' {
            let mut token = String::new();
            let mut closed = false;
            while let Some(c) = chars.next() {
                match c {
                    '"' => {
                        closed = true;
                        break;
                    }
                    '\\' => token.push(chars.next()?),
                    _ => token.push(c),
                }
            }
            if !closed {
                return None;
            }
            out.push(token);
        }
    }
    Some(out)
}
fn read(path: &Path) -> Option<String> {
    let meta = path.metadata().ok()?;
    if !meta.is_file() || meta.len() > 1024 * 1024 {
        return None;
    }
    std::fs::read_to_string(path).ok()
}
fn manifest(text: &str) -> Option<(u32, String)> {
    let t = tokens(text)?;
    let value = |key| t.windows(2).find(|p| p[0] == key).map(|p| p[1].clone());
    let id = value("appid")?.parse::<u32>().ok()?;
    let name = value("name")?;
    (id > 0 && !name.is_empty() && name.len() <= 160 && !name.chars().any(char::is_control))
        .then_some((id, name))
}
pub(super) fn installed(home: &Path) -> BTreeMap<u32, String> {
    let mut roots = vec![
        home.join(".local/share/Steam"),
        home.join(".steam/steam"),
        home.join(".var/app/com.valvesoftware.Steam/.local/share/Steam"),
    ];
    for root in roots.clone() {
        if let Some(text) = read(&root.join("steamapps/libraryfolders.vdf")) {
            if let Some(tokens) = tokens(&text) {
                roots.extend(
                    tokens
                        .windows(2)
                        .filter(|p| p[0] == "path")
                        .map(|p| PathBuf::from(&p[1]))
                        .filter(|p| p.is_absolute()),
                );
            }
        }
    }
    let mut out = BTreeMap::new();
    for root in roots {
        let Ok(entries) = std::fs::read_dir(root.join("steamapps")) else {
            continue;
        };
        for entry in entries.flatten() {
            let filename = entry.file_name();
            let Some(filename) = filename.to_str() else {
                continue;
            };
            let Some(file_id) = filename
                .strip_prefix("appmanifest_")
                .and_then(|s| s.strip_suffix(".acf"))
                .and_then(|s| s.parse::<u32>().ok())
            else {
                continue;
            };
            if let Some((id, name)) = read(&entry.path()).and_then(|s| manifest(&s)) {
                if file_id == id {
                    out.entry(id).or_insert(name);
                }
            }
        }
    }
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reads_names_not_commands() {
        assert_eq!(
            manifest(r#""AppState" { "appid" "438100" "name" "VRChat" }"#),
            Some((438100, "VRChat".into()))
        );
        assert!(manifest(r#""appid" "sh;id" "name" "bad""#).is_none());
        assert!(manifest(r#""appid" "1" "name" "truncated"#).is_none());
    }
    #[test]
    fn handles_comments_and_escapes() {
        assert_eq!(
            tokens("// comment\n\"path\" \"/a/b\"").unwrap(),
            vec!["path", "/a/b"]
        );
        assert_eq!(
            tokens(r#""name" "Game \"test\"""#).unwrap(),
            vec!["name", "Game \"test\""]
        );
    }
}
