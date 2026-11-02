//! Opt-in private window-control wire protocol for two OpenUUYC peers.
//! This is NOT part of the official UU protocol. Never interpret it on CONTROL.
//! A local per-user enable marker and an active, permitted input session are
//! both necessary before the controlled peer lists or focuses windows.
use serde::{Deserialize, Serialize};

const MAGIC: &[u8] = b"OUWC/1\0";
const MAX_PACKET: usize = 128 * 1024;
const MAX_WINDOWS: usize = 64;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct WindowInfo {
    pub id: String,
    pub title: String,
    pub application: String,
    pub focused: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Request {
    pub request_id: u64,
    pub action: String,
    #[serde(default)]
    pub window_id: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub(crate) struct Response {
    pub request_id: u64,
    pub ok: bool,
    #[serde(default)]
    pub windows: Vec<WindowInfo>,
    #[serde(default)]
    pub error: Option<String>,
}

pub(crate) fn is_packet(bytes: &[u8]) -> bool {
    bytes.starts_with(MAGIC)
}

fn decode<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> anyhow::Result<T> {
    anyhow::ensure!(is_packet(bytes) && bytes.len() <= MAX_PACKET, "invalid window extension packet");
    Ok(serde_json::from_slice(&bytes[MAGIC.len()..])?)
}

fn encode<T: Serialize>(message: &T) -> anyhow::Result<Vec<u8>> {
    let mut bytes = MAGIC.to_vec();
    serde_json::to_writer(&mut bytes, message)?;
    anyhow::ensure!(bytes.len() <= MAX_PACKET, "window extension packet exceeds limit");
    Ok(bytes)
}

pub(crate) fn encode_request(request: &Request) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(request.request_id != 0, "request ID must be nonzero");
    anyhow::ensure!(matches!(request.action.as_str(), "list" | "focus"), "unsupported window action");
    if request.action == "focus" {
        anyhow::ensure!(request.window_id.as_deref().is_some_and(|s| s.len() <= 40 && !s.is_empty()), "invalid window ID");
    }
    encode(request)
}

pub(crate) fn decode_response(bytes: &[u8]) -> anyhow::Result<Response> {
    let result: Response = decode(bytes)?;
    anyhow::ensure!(result.request_id != 0 && result.windows.len() <= MAX_WINDOWS, "invalid window response");
    Ok(result)
}

/// An explicit *per-user* opt-in. This marker is not authentication:
/// authorization still belongs to the active host session.
pub fn enabled() -> bool {
    crate::platform::paths::require_local_app_data()
        .ok()
        .is_some_and(|root| root.join("OpenUUYC/private-window-control.enabled").is_file())
}

/// Install or revoke the per-user extension permission. Never requires UAC,
/// sudo, a persistent service, or a native driver.
pub fn set_enabled(value: bool) -> anyhow::Result<()> {
    let path = crate::platform::paths::require_local_app_data()?
        .join("OpenUUYC/private-window-control.enabled");
    if value {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, "enabled")?;
    } else if let Err(error) = std::fs::remove_file(&path) {
        if error.kind() != std::io::ErrorKind::NotFound { return Err(error.into()); }
    }
    Ok(())
}

pub(crate) fn serve(bytes: &[u8], session_authorized: bool) -> Option<Vec<u8>> {
    if !is_packet(bytes) {
        return None;
    }
    // Even malformed packets receive at most a short denial, with no action.
    let request: Request = match decode(bytes) {
        Ok(req) if req.request_id != 0 => req,
        _ => return Some(Vec::new()),
    };
    let mut response = Response { request_id: request.request_id, ..Default::default() };
    let result = (|| -> anyhow::Result<()> {
        anyhow::ensure!(session_authorized && enabled(), "window extension is disabled or remote input is not authorized");
        match request.action.as_str() {
            "list" => {
                #[cfg(any(windows, target_os = "linux"))]
                { response.windows = crate::platform::window_control::list()?.into_iter().take(MAX_WINDOWS).collect(); }
                #[cfg(not(any(windows, target_os = "linux")))]
                anyhow::bail!("window control is not supported on this platform");
                response.ok = true;
            }
            "focus" => {
                let id = request.window_id.as_deref().ok_or_else(|| anyhow::anyhow!("missing window ID"))?;
                anyhow::ensure!(!id.is_empty() && id.len() <= 40, "invalid window ID");
                #[cfg(any(windows, target_os = "linux"))]
                crate::platform::window_control::focus(id)?;
                #[cfg(not(any(windows, target_os = "linux")))]
                anyhow::bail!("window control is not supported on this platform");
                response.ok = true;
            }
            _ => anyhow::bail!("unsupported window action"),
        }
        Ok(())
    })();
    if let Err(error) = result {
        response.error = Some(format!("{error:#}").chars().take(240).collect());
    }
    Some(encode(&response).unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn wire_round_trip() {
        let request = Request { request_id: 7, action: "list".into(), window_id: None };
        let payload = encode_request(&request).unwrap();
        assert!(is_packet(&payload));
        let round: Request = decode(&payload).unwrap();
        assert_eq!(round.request_id, 7);
        let reply = encode(&Response { request_id: 7, ok: true, ..Default::default() }).unwrap();
        assert!(decode_response(&reply).unwrap().ok);
    }
    #[test]
    fn malformed_and_excessively_large_packets_do_not_execute() {
        assert!(serve(b"OUWC/1\0not json", true).unwrap().is_empty());
        let oversized = [MAGIC, &vec![b'x'; MAX_PACKET]].concat();
        assert!(serve(&oversized, true).unwrap().is_empty());
    }
    #[test]
    fn requests_do_not_accept_unknown_actions_or_empty_targets() {
        let bad = Request { request_id: 1, action: "exec".into(), window_id: None };
        assert!(encode_request(&bad).is_err());
        let bad = Request { request_id: 1, action: "focus".into(), window_id: Some(String::new()) };
        assert!(encode_request(&bad).is_err());
    }
    #[test]
    fn denied_session_returns_no_window_data() {
        let payload = encode_request(&Request { request_id: 4, action: "list".into(), window_id: None }).unwrap();
        let reply = decode_response(&serve(&payload, false).unwrap()).unwrap();
        assert!(!reply.ok);
        assert!(reply.windows.is_empty());
    }
}
