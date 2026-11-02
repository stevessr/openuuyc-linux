//! niri window enumeration and focus through the compositor's *own* IPC.
//! No XWayland window ID guesses, shell execution, or synthetic focus hacks.
use crate::features::window_control::WindowInfo;
use anyhow::{Context, Result, anyhow, ensure};
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::time::Duration;

const MAX_REPLY: usize = 256 * 1024;

fn ipc(message: Value) -> Result<Value> {
    let socket = std::env::var_os("NIRI_SOCKET").context("not in a niri session (NIRI_SOCKET absent)")?;
    let mut stream = UnixStream::connect(socket).context("connect to niri IPC")?;
    stream.set_read_timeout(Some(Duration::from_millis(750)))?;
    stream.set_write_timeout(Some(Duration::from_millis(750)))?;
    serde_json::to_writer(&mut stream, &message)?;
    stream.write_all(b"\n")?;
    let mut line = Vec::new();
    let mut reader = BufReader::new(stream);
    // niri responds with a single JSON line. Do not wait for socket shutdown.
    reader.read_until(b'\n', &mut line)?;
    ensure!(line.len() <= MAX_REPLY, "niri IPC response too large");
    let reply: Value = serde_json::from_slice(&line).context("parse niri IPC reply")?;
    reply.get("Ok").cloned().ok_or_else(|| anyhow!("niri IPC rejected request: {}", reply.get("Err").unwrap_or(&reply)))
}

fn normalize(value: &Value) -> Result<Vec<WindowInfo>> {
    let windows = value.as_array()
        .or_else(|| value.get("Windows").and_then(Value::as_array))
        .context("niri did not return a window list")?;
    Ok(windows.iter().filter_map(|value| {
        let id = value.get("id")?.as_u64()?;
        let title = value.get("title").and_then(Value::as_str).unwrap_or("");
        let application = value.get("app_id").and_then(Value::as_str).unwrap_or("");
        // Never transmit arbitrarily large / multiline window titles to the UI.
        Some(WindowInfo {
            id: format!("niri:{id}"),
            title: title.replace(['\n', '\r'], " ").chars().take(160).collect(),
            application: application.chars().take(96).collect(),
            focused: value.get("is_focused").and_then(Value::as_bool).unwrap_or(false),
        })
    }).take(128).collect())
}

pub(crate) fn list() -> Result<Vec<WindowInfo>> {
    let mut windows = normalize(&ipc(json!("Windows"))?)?;
    windows.sort_by_key(|window| !window.focused);
    Ok(windows)
}

pub(crate) fn focus(id: &str) -> Result<()> {
    let raw = id.strip_prefix("niri:").context("not a niri window ID")?;
    let number = raw.parse::<u64>().context("invalid niri window ID")?;
    // Recheck the live inventory, so the caller cannot focus a stale or
    // arbitrary window that was not included in the current session.
    ensure!(list()?.iter().any(|w| w.id == id), "window is no longer available");
    ipc(json!({"Action": {"FocusWindow": {"id": number}}}))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn niri_window_inventory_parses_optional_titles_and_ids() {
        let items = normalize(&json!({"Windows": [
            {"id": 42, "app_id": "foot", "title": "Terminal", "is_focused": true},
            {"id": 43, "app_id": null, "title": null, "is_focused": false}
        ]})).unwrap();
        assert_eq!(items[0].id, "niri:42");
        assert_eq!(items[1].title, "");
        assert!(items[0].focused);
    }
    #[test]
    fn rejects_unknown_window_shapes() {
        assert!(normalize(&json!({"Outputs": []})).is_err());
    }
}
