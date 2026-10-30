//! Small real PNG thumbnails only; no remote paths or network icon requests.
use std::path::{Path, PathBuf};
pub(super) fn key(text: &str) -> Option<&str> {
    let mut main = false;
    for line in text.lines().map(str::trim) {
        if line.starts_with('[') {
            main = line == "[Desktop Entry]";
        } else if main {
            if let Some(icon) = line.strip_prefix("Icon=") {
                return Some(icon);
            }
        }
    }
    None
}
pub(super) fn png(name: &str) -> Option<String> {
    use base64::Engine;
    let home = std::env::var_os("HOME").map(PathBuf::from);
    let mut paths = Vec::new();
    if Path::new(name).is_absolute() {
        paths.push(PathBuf::from(name));
    } else {
        if name.contains('/') || name.contains("..") {
            return None;
        }
        let mut roots = Vec::new();
        if let Some(home) = home {
            roots.extend([home.join(".local/share/icons"), home.join(".icons")]);
        }
        roots.push("/usr/share/icons".into());
        for root in roots {
            for theme in ["hicolor", "Adwaita", "breeze", "Papirus"] {
                for size in [
                    "32x32", "48x48", "64x64", "24x24", "16x16", "128x128", "256x256",
                ] {
                    paths.push(
                        root.join(theme)
                            .join(size)
                            .join("apps")
                            .join(format!("{name}.png")),
                    );
                }
            }
        }
        paths.push(PathBuf::from("/usr/share/pixmaps").join(format!("{name}.png")));
    }
    for path in paths {
        let Ok(meta) = path.metadata() else { continue };
        if !meta.is_file() || meta.len() > 1024 * 1024 {
            continue;
        }
        let Ok(reader) = image::ImageReader::open(path) else {
            continue;
        };
        let mut reader = reader;
        let mut limits = image::Limits::default();
        limits.max_image_width = Some(1024);
        limits.max_image_height = Some(1024);
        reader.limits(limits);
        let Ok(image) = reader.decode() else { continue };
        let mut output = std::io::Cursor::new(Vec::new());
        if image
            .thumbnail(32, 32)
            .write_to(&mut output, image::ImageFormat::Png)
            .is_err()
        {
            continue;
        }
        if output.get_ref().len() <= 4096 {
            return Some(base64::engine::general_purpose::STANDARD.encode(output.into_inner()));
        }
    }
    None
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn section_scoped_icon() {
        assert_eq!(
            key("[Desktop Entry]\nIcon=steam_icon_438100\n[Other]\nIcon=wrong"),
            Some("steam_icon_438100")
        );
        assert_eq!(key("[Other]\nIcon=bad"), None);
        assert!(png("../bad").is_none());
    }
}
