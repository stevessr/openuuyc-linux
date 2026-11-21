//! Account REST routes: the full NRD-compatible surface of selfhost/server.py
//! plus compat.py, media.py and the enrollment pages.

use crate::errors::{ApiError, ApiResult};
use crate::runtime::Runtime;
use crate::store::{Device, Store};
use axum::Json;
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use base64::Engine as _;
use md5::Md5;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Instant;

pub const MAX_BODY: usize = 1024 * 1024;
pub const MAX_WALLPAPER: usize = 10 * 1024 * 1024;

pub struct AppState {
    pub runtime: Arc<Runtime>,
    pub public_url: String,
    pub signal_host: String,
    pub admin_key: String,
    pub ice_urls: Vec<String>,
    pub turn_secret: String,
    pub turn_user: String,
    pub turn_password: String,
    pub wallpaper_dir: std::path::PathBuf,
    pub admin_failures: std::sync::Mutex<std::collections::HashMap<String, Vec<Instant>>>,
    pub started_at: Instant,
    /// Keycloak/OIDC login provider; None keeps the administrator-key-only
    /// enrollment flow.
    pub oidc: Option<Arc<crate::oidc::OidcProvider>>,
    /// When true, administrator-key enrollment is disabled entirely.
    pub oidc_require: bool,
}

pub type SharedState = Arc<AppState>;

pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn token_bytes(n: usize) -> String {
    use rand::Rng;
    let mut buf = vec![0u8; n];
    rand::rng().fill(&mut buf[..]);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

pub fn json_response(value: Value) -> Response {
    ([(axum::http::header::CONTENT_TYPE, "application/json")], value.to_string()).into_response()
}

pub fn envelope_ok(data: Value) -> Response {
    json_response(json!({"code": 0, "msg": "", "data": data}))
}

pub fn envelope_err(code: i32, msg: &str) -> Response {
    json_response(json!({"code": code, "msg": msg, "data": Value::Null}))
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        match self {
            ApiError::Envelope(data, code, msg) => {
                json_response(json!({"code": code, "msg": msg, "data": data}))
            }
            ApiError::Plain(status, text) => (status, text).into_response(),
        }
    }
}

/// Authenticated caller: bearer token plus matching identity headers.
/// Mirrors caller()/guest_caller()/host_caller() from the Python reference.
pub struct Caller {
    pub device: Device,
    pub token: String,
    pub guest: Option<(String, String)>,
}

pub async fn authenticate(
    state: &SharedState,
    headers: &HeaderMap,
    guest_allowed: bool,
) -> Result<Caller, ApiError> {
    let authorization = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let token = authorization.strip_prefix("Bearer ").unwrap_or("");
    if token.is_empty() {
        return Err(ApiError::unauthorized("invalid session"));
    }
    let header_str = |name: &str| {
        headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_owned()
    };
    let guest_id = header_str("X-Param-Guest-ID");
    let (device_id, guest) = if !guest_id.is_empty() {
        if !guest_allowed {
            return Err(ApiError::unauthorized("invalid session"));
        }
        let (guest, device) = state
            .runtime
            .store
            .guest_session(token)
            .await?
            .ok_or_else(|| ApiError::unauthorized("invalid guest session"))?;
        if guest != guest_id {
            return Err(ApiError::unauthorized("invalid guest session"));
        }
        (device, Some((guest, token.to_owned())))
    } else {
        let device = state
            .runtime
            .store
            .session_device(token)
            .await?
            .ok_or_else(|| ApiError::unauthorized("invalid session"))?;
        (device, None)
    };
    let device = state
        .runtime
        .store
        .device(&device_id)
        .await?
        .ok_or_else(|| ApiError::unauthorized("device mismatch"))?;
    if device.client_id != header_str("X-Param-client-id")
        || device.device_id != header_str("X-Param-device-id")
    {
        return Err(ApiError::unauthorized("device mismatch"));
    }
    Ok(Caller {
        device,
        token: token.to_owned(),
        guest,
    })
}

pub async fn read_body(headers: &HeaderMap, body: axum::body::Bytes) -> Result<Value, ApiError> {
    if body.len() > MAX_BODY {
        return Err(ApiError::too_large(MAX_BODY));
    }
    if body.is_empty() {
        return Ok(json!({}));
    }
    let content_type = headers
        .get(axum::http::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if content_type.starts_with("application/json") {
        serde_json::from_slice(&body).map_err(|_| ApiError::bad_request("expected JSON"))
    } else {
        Ok(json!({}))
    }
}

pub fn ticket_payload(state: &SharedState, token: &str) -> Value {
    json!({
        "token": token,
        "ws_connect_timeout_ms": 10000,
        "streamer_retry_delta_ms": 1000,
        "max_reconnect_delta": 120000,
        "signaling_list": [state.signal_host],
        "signaling_server": state.signal_host,
    })
}

pub fn device_status(state: &SharedState, device: &Device, online: bool, participants: Value) -> Value {
    let _ = state;
    json!({
        "device_id": device.device_id,
        "alias": device.alias,
        "platform": device.platform,
        "version_name": device.version_name,
        "wallpaper_url": device.wallpaper_url,
        "support_wol": device.support_wol,
        "status": if online { "CONNECTED" } else { "DISCONNECTED" },
        "controllable": device.controllable,
        "controlled_support": true,
        "participants_info": participants,
    })
}

/// Presence heartbeat keys are written by signal.rs every 10s with a 30s TTL.
pub async fn device_online(state: &SharedState, device_id: &str) -> Result<bool, ApiError> {
    let mut conn = state.runtime.store.pool_clone().await?;
    let exists: bool = redis::AsyncCommands::exists(&mut conn, format!("ouu:presence:{device_id}"))
        .await
        .unwrap_or(false);
    Ok(exists)
}

pub async fn participants_of(state: &SharedState, device_id: &str) -> Result<Value, ApiError> {
    let Some(owner) = state.runtime.store.control_owner(device_id).await? else {
        return Ok(json!([]));
    };
    // owner is "node:controller_device_id"; recover the controller device row.
    let controller_device = owner.split(':').nth(1).unwrap_or("");
    let Some(device) = state.runtime.store.device(controller_device).await? else {
        return Ok(json!([]));
    };
    Ok(json!([{
        "client_id": device.client_id,
        "device_id": device.device_id,
        "alias": device.alias,
        "platform": device.platform,
        "user_join_type": 1,
        "controlled_time": 0,
        "app_flag": Value::Null,
    }]))
}

// ---------- registration and login ----------

async fn init_common(
    state: &SharedState,
    headers: &HeaderMap,
    platform: i32,
    body: Value,
) -> ApiResult {
    let client_id = headers
        .get("X-Param-client-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_owned();
    if client_id.is_empty()
        || client_id.len() > 128
        || client_id != body.get("client_id").and_then(Value::as_str).unwrap_or("")
    {
        return Ok(envelope_err(400, "client-id mismatch"));
    }
    let name = body
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("OpenUUYC")
        .chars()
        .take(100)
        .collect::<String>();
    let version = headers
        .get("X-Param-VN")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let device = state
        .runtime
        .store
        .register_device(&client_id, &name, platform, version, 1024)
        .await?;
    Ok(envelope_ok(json!({"device_id": device.device_id, "alias": device.alias})))
}

pub async fn init_windows(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let body = read_body(&headers, body).await?;
    init_common(&state, &headers, 1, body).await
}

pub async fn init_android(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let body = read_body(&headers, body).await?;
    init_common(&state, &headers, 2, body).await
}

pub async fn generate_qr(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let client_id = headers
        .get("X-Param-client-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_owned();
    if client_id.is_empty() || state.runtime.store.device_by_client(&client_id).await?.is_none() {
        return Ok(envelope_err(401, "device not registered"));
    }
    let ticket = state.runtime.store.create_qr(&client_id).await?;
    Ok(envelope_ok(json!({
        "qrcode_jump_url": format!("{}/enroll/{}", state.public_url, urlencoding::encode(&ticket)),
        "token": ticket,
        "status_query_ticket": ticket,
    })))
}

pub async fn qr_approved(state: &SharedState, ticket: &str) -> Result<bool, ApiError> {
    let mut conn = state.runtime.store.pool_clone().await?;
    let exists: bool = redis::AsyncCommands::exists(&mut conn, format!("ouu:qr-approved:{ticket}"))
        .await
        .unwrap_or(false);
    Ok(exists)
}

pub async fn qr_status(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let body = read_body(&headers, body).await?;
    let token = body.get("token").and_then(Value::as_str).unwrap_or("").to_owned();
    let Some((client_id, _)) = state.runtime.store.qr_ticket(&token).await? else {
        return Ok(envelope_ok(json!({"status": 3, "login_status": 3})));
    };
    if client_id != headers.get("X-Param-client-id").and_then(|v| v.to_str().ok()).unwrap_or("") {
        return Err(ApiError::forbidden("QR bound to another device"));
    }
    if !qr_approved(&state, &token).await? {
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        return Ok(envelope_ok(json!({"status": 1, "login_status": 1})));
    }
    Ok(envelope_ok(json!({"status": 4, "login_status": 4})))
}

pub async fn login_by_qrcode(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let body = read_body(&headers, body).await?;
    let ticket = body.get("token").and_then(Value::as_str).unwrap_or("").to_owned();
    let Some((client_id, _)) = state.runtime.store.qr_ticket(&ticket).await? else {
        return Ok(envelope_err(403, "QR not authorized"));
    };
    if !qr_approved(&state, &ticket).await? {
        return Ok(envelope_err(403, "QR not authorized"));
    }
    let expected = format!("{}/enroll/{}", state.public_url, urlencoding::encode(&ticket));
    if body.get("qrcode_jump_url").and_then(Value::as_str) != Some(expected.as_str())
        || client_id
            != headers
                .get("X-Param-client-id")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
    {
        return Err(ApiError::forbidden("QR identity mismatch"));
    }
    // One-shot exchange: the Lua script returns the ticket's client_id.
    let Some(consumed_client) = state.runtime.store.consume_qr(&ticket).await? else {
        return Ok(envelope_err(403, "QR not authorized"));
    };
    if consumed_client != client_id {
        return Ok(envelope_err(403, "QR identity mismatch"));
    }
    let Some(mut device) = state.runtime.store.device_by_client(&client_id).await? else {
        // The QR's device was unbound while approval was pending.
        return Ok(envelope_err(403, "device no longer bound"));
    };
    let token = state
        .runtime
        .store
        .create_session(&device.device_id, &client_id)
        .await?;
    // Bind the approving identity (Keycloak subject) to the device so the
    // account layer shows a real owner; admin-key approvals stay blank.
    let mut user_id = "selfhost".to_owned();
    let mut nickname = "OpenUUYC selfhost".to_owned();
    if let Some((sub, name)) = state.runtime.store.qr_approver(&ticket).await? {
        if !sub.is_empty() {
            device.owner_sub = sub.clone();
            device.owner_name = name.clone();
            state.runtime.store.put_device(&device).await?;
            user_id = sub;
            nickname = name;
            state
                .runtime
                .store
                .audit_enrollment(&device.device_id, &user_id, &nickname, false)
                .await?;
        }
    }
    Ok(envelope_ok(json!({"token": token, "user_id": user_id, "nickname": nickname})))
}

// ---------- administrator enrollment ----------

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn peer_of(headers: &HeaderMap, addr: Option<std::net::SocketAddr>) -> String {
    headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next())
        .map(|v| v.trim().to_owned())
        .or_else(|| addr.map(|a| a.ip().to_string()))
        .unwrap_or_default()
}

fn admin_throttled(state: &SharedState, peer: &str) -> bool {
    let mut failures = state.admin_failures.lock().unwrap();
    let cutoff = Instant::now() - std::time::Duration::from_secs(900);
    let entries = failures.entry(peer.to_owned()).or_default();
    entries.retain(|at| *at > cutoff);
    entries.len() >= 5
}

fn admin_record_failure(state: &SharedState, peer: &str) {
    let mut failures = state.admin_failures.lock().unwrap();
    let cutoff = Instant::now() - std::time::Duration::from_secs(900);
    let entries = failures.entry(peer.to_owned()).or_default();
    entries.retain(|at| *at > cutoff);
    entries.push(Instant::now());
    if failures.len() > 4096 {
        failures.retain(|_, entries| entries.iter().any(|at| *at > cutoff));
    }
}

pub async fn approve_get(
    State(state): State<SharedState>,
    Path(ticket): Path<String>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    headers: HeaderMap,
) -> ApiResult {
    if admin_throttled(&state, &peer_of(&headers, Some(addr))) {
        return Err(ApiError::too_many("too many approval attempts"));
    }
    if state.runtime.store.qr_ticket(&ticket).await?.is_none() {
        return Err(ApiError::not_found());
    }
    let keycloak_link = match &state.oidc {
        Some(_provider) => {
            let login_path = format!("/enroll/{}/login", ticket);
            if state.oidc_require {
                format!("<p><a href=\"{}\">Login with Keycloak to approve</a></p>", login_path)
            } else {
                format!("<p><a href=\"{}\">Login with Keycloak to approve</a> or use the administrator key below.</p>", login_path)
            }
        }
        None => String::new(),
    };
    let admin_form = if state.oidc_require && state.oidc.is_some() {
        String::new()
    } else {
        "<form method=\"post\"><label>Administrator key <input type=\"password\" name=\"admin_key\" required autocomplete=\"off\"></label> <button type=\"submit\">Approve</button></form>".to_owned()
    };
    let html = format!(
        "<!doctype html><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\"><title>OpenUUYC self-host login</title><h1>Approve OpenUUYC device</h1><p>Approve only a device you control.</p>{}{}",
        keycloak_link,
        admin_form
    );
    Ok((
        [(axum::http::header::CACHE_CONTROL, "no-store")],
        axum::response::Html(html),
    )
        .into_response())
}

/// Minimal urlencoded form parser for the enrollment POST.
fn parse_form(body: &[u8]) -> std::collections::HashMap<String, String> {
    let text = String::from_utf8_lossy(body);
    text.split('&')
        .filter_map(|pair| {
            let (key, value) = pair.split_once('=')?;
            Some((
                urlencoding::decode(key).ok()?.into_owned(),
                urlencoding::decode(value).ok()?.into_owned().replace('+', " "),
            ))
        })
        .collect()
}

// ---------- OIDC (Keycloak) enrollment login ----------

/// GET /enroll/{ticket}/login — bounce the approver to Keycloak with PKCE.
pub async fn oidc_login(
    State(state): State<SharedState>,
    Path(ticket): Path<String>,
) -> ApiResult {
    let Some(provider) = &state.oidc else {
        return Err(ApiError::Plain(StatusCode::NOT_FOUND, "OIDC login not configured"));
    };
    if state.runtime.store.qr_ticket(&ticket).await?.is_none() {
        return Err(ApiError::not_found());
    }
    let endpoints = provider.endpoints().await.map_err(|error| {
        tracing::error!(%error, "OIDC discovery failed");
        ApiError::unavailable("identity provider unreachable")
    })?;
    let (verifier, challenge) = crate::oidc::OidcProvider::pkce();
    let state_param = crate::oidc::OidcProvider::nonce();
    let nonce = crate::oidc::OidcProvider::nonce();
    // Bind verifier/nonce to (ticket, state) so a forged callback cannot mix
    // sessions. TTL covers a slow human in the browser.
    let mut conn = state.runtime.store.pool_clone().await?;
    let _: Result<(), _> = redis::AsyncCommands::set_ex(
        &mut conn,
        format!("ouu:oidc-flow:{}", crate::store::hash_token(&state_param)),
        format!("{ticket}|{verifier}|{nonce}"),
        600,
    )
    .await;
    let redirect_uri = format!("{}/enroll/{}/callback", state.public_url, ticket);
    let url = provider.authorization_url(endpoints.as_ref(), &redirect_uri, &state_param, &nonce, &challenge);
    Ok((
        [(axum::http::header::LOCATION, url)],
        StatusCode::FOUND,
    )
        .into_response())
}

/// GET /enroll/{ticket}/callback?code&state — complete the OIDC code flow,
/// resolve identity via userinfo, then approve the pending ticket.
pub async fn oidc_callback(
    State(state): State<SharedState>,
    Path(ticket): Path<String>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> ApiResult {
    let Some(provider) = &state.oidc else {
        return Err(ApiError::Plain(StatusCode::NOT_FOUND, "OIDC login not configured"));
    };
    let code = query.get("code").cloned().unwrap_or_default();
    let flow_state = query.get("state").cloned().unwrap_or_default();
    if code.is_empty() || flow_state.is_empty() {
        return Err(ApiError::bad_request("missing code or state"));
    }
    // Flow state must exist and match the ticket it was minted for.
    let mut conn = state.runtime.store.pool_clone().await?;
    let flow_key = format!("ouu:oidc-flow:{}", crate::store::hash_token(&flow_state));
    let flow: Option<String> = redis::AsyncCommands::get_del(&mut conn, &flow_key)
        .await
        .unwrap_or(None);
    let Some(flow) = flow else {
        return Err(ApiError::forbidden("unknown or expired login flow"));
    };
    let Some((flow_ticket, verifier, _nonce)) = flow.split_once('|').and_then(|(t, rest)| {
        rest.split_once('|').map(|(v, n)| (t, v, n))
    }) else {
        return Err(ApiError::forbidden("corrupted login flow"));
    };
    if flow_ticket != ticket {
        return Err(ApiError::forbidden("login flow bound to another ticket"));
    }
    // Consume the code at Keycloak (secret stays server-side) and resolve
    // identity through userinfo.
    let endpoints = provider.endpoints().await.map_err(|error| {
        tracing::error!(%error, "OIDC discovery failed during callback");
        ApiError::unavailable("identity provider unreachable")
    })?;
    let redirect_uri = format!("{}/enroll/{}/callback", state.public_url, ticket);
    let confidential = state
        .oidc
        .as_ref()
        .map(|p| p.client_secret.is_some())
        .unwrap_or(false);
    let credential = if confidential {
        state.oidc.as_ref().unwrap().client_secret.as_deref().unwrap_or("").to_owned()
    } else {
        verifier.to_owned()
    };
    let tokens = provider
        .exchange_code(endpoints.as_ref(), &redirect_uri, &code, &credential, confidential)
        .await
        .map_err(|error| {
            tracing::warn!(%error, "OIDC code exchange failed");
            ApiError::forbidden("identity provider rejected the login")
        })?;
    let user = provider
        .userinfo(endpoints.as_ref(), &tokens.access_token)
        .await
        .map_err(|error| {
            tracing::warn!(%error, "OIDC userinfo failed");
            ApiError::forbidden("could not resolve the authenticated identity")
        })?;
    // Approve and stamp the approving identity.
    if !state.runtime.store.approve_qr(&ticket).await? {
        return Err(ApiError::not_found());
    }
    let display = user.display();
    state
        .runtime
        .store
        .stamp_qr_approver(&ticket, &user.sub, &display)
        .await?;
    let approver = if user.sub.is_empty() { "unknown" } else { user.sub.as_str() };
    state
        .runtime
        .store
        .audit_enrollment("(pending)", approver, &display, false)
        .await?;
    let html = format!(
        "<!doctype html><meta charset=\"utf-8\"><title>OpenUUYC self-host login</title>\
         <h1>Approved by {}</h1><p>Return to OpenUUYC to finish signing in.</p>",
        html_escape(&display)
    );
    Ok((
        [(axum::http::header::CACHE_CONTROL, "no-store")],
        axum::response::Html(html),
    )
        .into_response())
}

/// Minimal HTML escaping for identity names echoed into the approval page.
fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

pub async fn approve_post(
    State(state): State<SharedState>,
    Path(ticket): Path<String>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let peer = peer_of(&headers, Some(addr));
    if admin_throttled(&state, &peer) {
        return Err(ApiError::too_many("too many approval attempts"));
    }
    let form = parse_form(&body);
    let supplied = form.get("admin_key").map(String::as_str).unwrap_or("");
    if !constant_time_eq(supplied.as_bytes(), state.admin_key.as_bytes()) {
        admin_record_failure(&state, &peer);
        return Err(ApiError::forbidden("invalid administrator key"));
    }
    match state.runtime.store.approve_qr(&ticket).await {
        Ok(true) => {}
        Ok(false) => {
            tracing::error!("approve_post: ticket not found or expired");
            return Err(ApiError::not_found());
        }
        Err(e) => {
            tracing::error!("approve_post store error: {e:?}");
            return Err(e);
        }
    }
    state
        .runtime
        .store
        .audit_enrollment("(pending)", "admin-key", "administrator", true)
        .await?;
    state.admin_failures.lock().unwrap().remove(&peer);
    Ok((
        [(axum::http::header::CACHE_CONTROL, "no-store")],
        "Approved. Return to OpenUUYC.",
    )
        .into_response())
}

// ---------- user and device routes ----------

pub async fn user_info(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    authenticate(&state, &headers, false).await?;
    Ok(envelope_ok(json!({"user_id": "selfhost", "nickname": "OpenUUYC selfhost"})))
}

pub async fn logout(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let caller = authenticate(&state, &headers, true).await?;
    state.runtime.store.revoke_session(&caller.token).await?;
    state
        .runtime
        .store
        .forget_session_tokens(&caller.device.client_id)
        .await?;
    state
        .runtime
        .store
        .drop_tickets_of_session(&caller.token)
        .await?;
    if caller.guest.is_some() {
        state.runtime.store.revoke_guest_session(&caller.token).await?;
    }
    Ok(envelope_ok(json!({})))
}

pub async fn list_devices(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let devices = state.runtime.store.all_devices().await?;
    let mut current = None;
    let mut bound = Vec::new();
    for device in devices {
        let online = device_online(&state, &device.device_id).await?;
        let participants = participants_of(&state, &device.device_id).await?;
        let entry = device_status(&state, &device, online, participants);
        if device.device_id == caller.device.device_id {
            current = Some(entry);
        } else {
            bound.push(entry);
        }
    }
    let current = current.unwrap_or_else(|| json!(Value::Null));
    Ok(envelope_ok(json!({"current_device": current, "my_binded_devices": bound})))
}

pub async fn device_groups(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let devices = state.runtime.store.all_devices().await?;
    let mut desktop = Vec::new();
    let mut mobile = Vec::new();
    for device in devices {
        let online = device_online(&state, &device.device_id).await?;
        let participants = participants_of(&state, &device.device_id).await?;
        let entry = device_status(&state, &device, online, participants);
        match device.platform {
            2 => mobile.push(entry),
            _ => desktop.push(entry),
        }
    }
    Ok(envelope_ok(json!({
        "current_device_id": caller.device.device_id,
        "desktop_devices": desktop,
        "mobile_devices": mobile,
        "tv_devices": [],
    })))
}

pub async fn device_detail(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> ApiResult {
    authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device) {
        return Ok(envelope_err(400, "invalid device"));
    }
    let Some(row) = state.runtime.store.device(&device).await? else {
        return Ok(envelope_err(404, "device not found"));
    };
    Ok(envelope_ok(json!({"details": [
        ["Device ID", row.device_id],
        ["Platform", row.platform.to_string()],
        ["Version", row.version_name],
    ]})))
}

pub async fn rename_device(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
    body: axum::body::Bytes,
) -> ApiResult {
    authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device)
        || state.runtime.store.device(&device).await?.is_none()
    {
        return Ok(envelope_err(404, "device not found"));
    }
    let body = read_body(&headers, body).await?;
    let alias = body.get("alias").and_then(Value::as_str).unwrap_or("");
    if alias.trim().is_empty()
        || alias.chars().count() > 100
        || alias.chars().any(|c| c.is_control())
    {
        return Ok(envelope_err(400, "invalid alias"));
    }
    let mut updated = state.runtime.store.device(&device).await?.unwrap();
    updated.alias = alias.to_owned();
    state.runtime.store.put_device(&updated).await?;
    state
        .runtime
        .broadcast_device_delta(
            &device,
            "device_info_changed",
            json!({"device_id": device, "alias": alias}),
        )
        .await;
    Ok(envelope_ok(json!({"device_id": device, "alias": alias})))
}

pub async fn unbind_device(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device)
        || device == caller.device.device_id
        || state.runtime.store.device(&device).await?.is_none()
    {
        return Ok(envelope_err(400, "invalid unbind target"));
    }
    let existed = state.runtime.store.unbind_device(&device).await?;
    if !existed {
        return Ok(envelope_err(400, "invalid unbind target"));
    }
    let owner = state.runtime.store.control_owner(&device).await?;
    if let Some(owner) = owner {
        let _ = state.runtime.store.release_control(&device, &owner).await;
    }
    state
        .runtime
        .broadcast_device_delta(&device, "device_unbind", json!({"device_id": device}))
        .await;
    state.runtime.store.forget_saved_of_publisher(&device).await?;
    Ok(envelope_ok(json!({})))
}

pub async fn set_controllable(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, true).await?;
    let body = read_body(&headers, body).await?;
    let Some(controllable) = body.get("controllable").and_then(Value::as_bool) else {
        return Err(ApiError::bad_request("controllable must be boolean"));
    };
    let mut updated = caller.device.clone();
    updated.controllable = controllable;
    state.runtime.store.put_device(&updated).await?;
    if !controllable {
        let owner = state
            .runtime
            .store
            .control_owner(&caller.device.device_id)
            .await?;
        if let Some(owner) = owner {
            let _ = state
                .runtime
                .store
                .release_control(&caller.device.device_id, &owner)
                .await;
        }
    }
    state
        .runtime
        .broadcast_device_delta(
            &caller.device.device_id,
            "device_info_changed",
            json!({"device_id": caller.device.device_id, "controllable": controllable}),
        )
        .await;
    Ok(envelope_ok(json!({})))
}

pub async fn create_room(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let _ = read_body(&headers, body).await?;
    let ticket = crate::store::Ticket {
        kind: "host".into(),
        device: caller.device.device_id.clone(),
        target: caller.device.device_id.clone(),
        force: false,
        guest: false,
        session_token: caller.token.clone(),
        issued_by: state.runtime.node_id.clone(),
        client_id: caller.device.client_id.clone(),
    };
    let token = state.runtime.store.create_ticket(ticket, 3600, 8192).await?;
    Ok(envelope_ok(ticket_payload(&state, &token)))
}

pub async fn guest_create(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let _ = read_body(&headers, body).await?;
    let (guest_id, token) = state
        .runtime
        .store
        .create_guest_session(&caller.device.device_id, 24 * 3600, 16)
        .await?;
    Ok(envelope_ok(json!({"guest_id": guest_id, "token": token})))
}

pub async fn guest_room(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, true).await?;
    let _ = read_body(&headers, body).await?;
    let guest_id = caller
        .guest
        .as_ref()
        .map(|(id, _)| id.clone())
        .unwrap_or_default();
    let ticket = crate::store::Ticket {
        kind: "host".into(),
        device: caller.device.device_id.clone(),
        target: caller.device.device_id.clone(),
        force: false,
        guest: true,
        session_token: caller.token.clone(),
        issued_by: state.runtime.node_id.clone(),
        client_id: guest_id,
    };
    let token = state.runtime.store.create_ticket(ticket, 3600, 8192).await?;
    Ok(envelope_ok(ticket_payload(&state, &token)))
}

pub async fn join_room(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    if !crate::proto::valid_device_id(&device) || device == caller.device.device_id {
        return Ok(envelope_err(400, "invalid target"));
    }
    let Some(target) = state.runtime.store.device(&device).await? else {
        return Ok(envelope_err(404, "target offline or disabled"));
    };
    if !target.controllable || !device_online(&state, &device).await? {
        return Ok(envelope_err(404, "target offline or disabled"));
    }
    let force = body
        .get("force_join")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if state.runtime.store.control_owner(&device).await?.is_some() && !force {
        return Ok(envelope_err(1136, "device busy"));
    }
    let ticket = crate::store::Ticket {
        kind: "controller".into(),
        device: caller.device.device_id.clone(),
        target: device.clone(),
        force,
        guest: false,
        session_token: caller.token.clone(),
        issued_by: state.runtime.node_id.clone(),
        client_id: caller.device.client_id.clone(),
    };
    let token = state.runtime.store.create_ticket(ticket, 600, 8192).await?;
    Ok(envelope_ok(ticket_payload(&state, &token)))
}

pub async fn configures(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    authenticate(&state, &headers, false).await?;
    let _ = read_body(&headers, body).await?;
    Ok(envelope_ok(json!({})))
}

pub async fn release_trigger(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let _ = caller;
    if !crate::proto::valid_device_id(&device) {
        return Ok(envelope_err(400, "invalid device"));
    }
    if state.runtime.store.device(&device).await?.is_none() {
        return Ok(envelope_err(404, "device not found"));
    }
    Ok(envelope_err(
        501,
        "self-host does not support remote controlled updates",
    ))
}

pub async fn healthz() -> Response {
    json_response(json!({"status": "ok"}))
}

// ---------- assistance (compat.py + assistance_live.py protocol paths) ----------

pub async fn assist_identity(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let caller = authenticate(&state, &headers, true).await?;
    let connect_id = state
        .runtime
        .store
        .assist_identity(&caller.device.device_id)
        .await?;
    Ok(envelope_ok(json!({"connect_id": connect_id})))
}

pub async fn assist_refetch(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    let token = body.get("token").and_then(Value::as_str).unwrap_or("");
    let Some(ticket) = state.runtime.store.ticket(token).await? else {
        return Ok(envelope_err(403, "invalid refetch ticket"));
    };
    if ticket.kind != "controller" || ticket.device != caller.device.device_id {
        return Ok(envelope_err(403, "invalid refetch ticket"));
    }
    let Some(row) = state.runtime.store.assist_row(&ticket.target).await? else {
        return Ok(envelope_err(404, "assist target missing"));
    };
    let Some(target) = state.runtime.store.device(&ticket.target).await? else {
        return Ok(envelope_err(404, "assist target missing"));
    };
    Ok(envelope_ok(json!({
        "token": token,
        "signaling_list": [state.signal_host],
        "share_id": row.connect_id,
        "publisher_platform": target.platform,
        "publisher_device_id": target.device_id,
        "publisher_version_name": target.version_name,
        "device_name": target.alias,
        "ws_connect_timeout_ms": 10000,
        "streamer_retry_delta_ms": 1000,
    })))
}

pub async fn saved_list(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(kind): Path<String>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let favorites = match kind.as_str() {
        "favorites" => true,
        "recent" => false,
        _ => return Ok(envelope_err(400, "invalid saved list")),
    };
    let rows = state
        .runtime
        .store
        .saved_list(&caller.device.device_id, favorites)
        .await?;
    Ok(envelope_ok(json!({"saved": rows})))
}

pub async fn save_favorites(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    let Some(items) = body.get("favorites").and_then(Value::as_array) else {
        return Ok(envelope_err(400, "invalid favorites"));
    };
    if items.len() > 100 {
        return Ok(envelope_err(400, "invalid favorites"));
    }
    for item in items {
        let connect_id = item.get("connect_id").and_then(Value::as_str).unwrap_or("");
        let remark = item.get("remark").and_then(Value::as_str).unwrap_or("");
        if remark.chars().count() > 100 {
            return Ok(envelope_err(400, "invalid favorite"));
        }
        let Some(publisher) = state.runtime.store.device_by_connect(connect_id).await? else {
            return Ok(envelope_err(400, "invalid favorite"));
        };
        state
            .runtime
            .store
            .save_favorite(&caller.device.device_id, &publisher, connect_id, remark)
            .await?;
    }
    let rows = state
        .runtime
        .store
        .saved_list(&caller.device.device_id, true)
        .await?;
    Ok(envelope_ok(json!({"saved": rows})))
}

pub async fn delete_saved(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path((kind, device)): Path<(String, String)>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device) {
        return Ok(envelope_err(400, "invalid saved device"));
    }
    match kind.as_str() {
        "favorites" => {
            state
                .runtime
                .store
                .unfavorite(&caller.device.device_id, &device)
                .await?
        }
        "recent" => {
            state
                .runtime
                .store
                .clear_recent_of(&caller.device.device_id, Some(&device))
                .await?
        }
        _ => return Ok(envelope_err(400, "invalid saved device")),
    }
    Ok(envelope_ok(json!({})))
}

pub async fn clear_recent(State(state): State<SharedState>, headers: HeaderMap) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    state
        .runtime
        .store
        .clear_recent_of(&caller.device.device_id, None)
        .await?;
    Ok(envelope_ok(json!({})))
}

// ---------- power and transport RPC ----------

/// Push a bmsg event to the device's presence room wherever it lives.
/// Returns false when the room is offline or control is required but absent.
pub async fn deliver_to_host(
    state: &SharedState,
    device: &str,
    payload: Value,
    require_control: bool,
) -> Result<bool, ApiError> {
    let Some(row) = state.runtime.store.device(device).await? else {
        return Ok(false);
    };
    if require_control && !row.controllable {
        return Ok(false);
    }
    if !device_online(state, device).await? {
        return Ok(false);
    }
    state
        .runtime
        .deliver_to_device(device, "bmsg_push", vec![payload])
        .await;
    Ok(true)
}

pub async fn reboot_device(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device) || device == caller.device.device_id {
        return Ok(envelope_err(400, "invalid target"));
    }
    if state.runtime.store.device(&device).await?.is_none() {
        return Ok(envelope_err(400, "invalid target"));
    }
    let delivered = deliver_to_host(
        &state,
        &device,
        json!({"type": "device_reboot", "data": {}}),
        true,
    )
    .await?;
    if delivered {
        Ok(envelope_ok(json!({})))
    } else {
        Ok(envelope_err(404, "host is unavailable"))
    }
}

pub async fn transport_rpc(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
    body: axum::body::Bytes,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    if !crate::proto::valid_device_id(&device) || device == caller.device.device_id {
        return Ok(envelope_err(400, "invalid RPC target"));
    }
    let Some(target) = state.runtime.store.device(&device).await? else {
        return Ok(envelope_err(404, "RPC target does not exist"));
    };
    let body = read_body(&headers, body).await?;
    let action = body.get("p").and_then(Value::as_str).unwrap_or("");
    match action {
        "shutdown" => {
            if !matches!(target.platform, 1 | 4) {
                return Ok(envelope_err(400, "not a desktop"));
            }
        }
        "android_control_request" | "android_control_result" => {
            let details = body.get("data").cloned().unwrap_or(Value::Null);
            let info = details.get("device_info").cloned().unwrap_or(Value::Null);
            if info.get("device_id").and_then(Value::as_str)
                != Some(caller.device.device_id.as_str())
            {
                return Ok(envelope_err(400, "invalid Android approval request"));
            }
        }
        _ => return Ok(envelope_err(501, "unsupported RPC")),
    }
    let require_control = action == "shutdown";
    let delivered = deliver_to_host(
        &state,
        &device,
        json!({"type": "transport_rpc", "data": body}),
        require_control,
    )
    .await?;
    if delivered {
        Ok(envelope_ok(json!({})))
    } else {
        Ok(envelope_err(404, "target unavailable"))
    }
}

pub async fn rpc_response(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    authenticate(&state, &headers, false).await?;
    let _ = read_body(&headers, body).await?;
    Ok(envelope_ok(json!({})))
}

// ---------- WoL ----------

pub async fn set_wol(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let mut caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    let Some(support) = body.get("support_wol").and_then(Value::as_bool) else {
        return Ok(envelope_err(400, "invalid WoL state"));
    };
    caller.device.support_wol = support;
    state.runtime.store.put_device(&caller.device).await?;
    Ok(envelope_ok(json!({})))
}

pub async fn wol_info(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let mut caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    if serde_json::to_string(&body).unwrap_or_default().len() > 32768 {
        return Ok(envelope_err(400, "LAN data too large"));
    }
    let support = caller.device.support_wol;
    caller.device.lan_info = serde_json::to_string(&body).unwrap_or_else(|_| "{}".into());
    state.runtime.store.put_device(&caller.device).await?;
    Ok(envelope_ok(json!({"support_wol": support})))
}

pub async fn wake(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    let _ = (caller, device);
    Ok(envelope_err(501, "no configured local wake helper"))
}

// ---------- wallpaper (media.py) ----------

pub async fn wallpaper_grant(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> ApiResult {
    let caller = authenticate(&state, &headers, false).await?;
    if query.get("filetype").map(String::as_str) != Some("1") {
        return Ok(envelope_err(400, "unsupported file type"));
    }
    let ticket = token_bytes(24);
    let bearer = token_bytes(30);
    let mut conn = state.runtime.store.pool_clone().await?;
    let _: Result<(), _> = redis::AsyncCommands::set_ex(
        &mut conn,
        format!("ouu:wallpaper-grant:{}", crate::store::hash_token(&ticket)),
        format!("{}|{}", caller.device.device_id, bearer),
        120,
    )
    .await;
    Ok(envelope_ok(json!({
        "token": bearer,
        "req_url": format!("{}/filepicker/upload/{}", state.public_url, ticket),
        "ttl": 120,
        "max_size": MAX_WALLPAPER + 1,
    })))
}

pub async fn upload_wallpaper(
    State(state): State<SharedState>,
    Path(grant): Path<String>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let key = format!("ouu:wallpaper-grant:{}", crate::store::hash_token(&grant));
    let mut conn = state.runtime.store.pool_clone().await?;
    // GETDEL makes every grant one-shot across nodes.
    let raw: Option<String> = redis::AsyncCommands::get_del(&mut conn, &key)
        .await
        .unwrap_or(None);
    let Some(raw) = raw else {
        return Err(ApiError::unauthorized("invalid file grant"));
    };
    let Some((device_id, bearer)) = raw.split_once('|') else {
        return Err(ApiError::unauthorized("invalid file grant"));
    };
    let supplied = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if !constant_time_eq(supplied.as_bytes(), bearer.as_bytes()) {
        return Err(ApiError::unauthorized("invalid file grant"));
    }
    if body.is_empty() || body.len() > MAX_WALLPAPER {
        return Err(ApiError::too_large(MAX_WALLPAPER));
    }
    let Some(mime) = image_mime(&body) else {
        return Err(ApiError::unsupported("unsupported wallpaper encoding"));
    };
    let file_id = token_bytes(21);
    let path = state.wallpaper_dir.join(&file_id);
    if tokio::fs::write(&path, &body).await.is_err() {
        return Err(ApiError::unavailable("wallpaper storage unavailable"));
    }
    let mut hasher = Sha256::new();
    hasher.update(&body);
    let sha256 = format!("{:x}", hasher.finalize());
    let mut md5 = Md5::new();
    md5.update(&body);
    let md5hex = format!("{:x}", md5.finalize());
    let _: Result<(), _> = redis::AsyncCommands::set_ex(
        &mut conn,
        format!("ouu:wallpaper-file:{file_id}"),
        json!({
            "device_id": device_id,
            "size": body.len(),
            "mime": mime,
            "sha256": sha256,
        })
        .to_string(),
        7 * 24 * 3600,
    )
    .await;
    Ok((
        [(axum::http::header::CACHE_CONTROL, "no-store")],
        Json(json!({
            "url": format!("{}/media/wallpaper/{}", state.public_url, file_id),
            "fsize": body.len(),
            "md5": md5hex,
            "mime": mime,
        })),
    )
        .into_response())
}

pub async fn download_wallpaper(State(state): State<SharedState>, Path(file_id): Path<String>) -> Response {
    let safe = file_id
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if !safe {
        return StatusCode::NOT_FOUND.into_response();
    }
    let mut conn = match state.runtime.store.pool_clone().await {
        Ok(conn) => conn,
        Err(_) => return StatusCode::SERVICE_UNAVAILABLE.into_response(),
    };
    let raw: Option<String> = redis::AsyncCommands::get(&mut conn, format!("ouu:wallpaper-file:{file_id}"))
        .await
        .unwrap_or(None);
    let Some(raw) = raw else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let Ok(metadata) = serde_json::from_str::<Value>(&raw) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let path = state.wallpaper_dir.join(&file_id);
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            [
                (
                    axum::http::header::CONTENT_TYPE,
                    metadata["mime"]
                        .as_str()
                        .unwrap_or("application/octet-stream")
                        .to_owned(),
                ),
                (axum::http::header::CACHE_CONTROL, "no-store".to_owned()),
                (
                    axum::http::header::HeaderName::from_static("x-content-type-options"),
                    "nosniff".to_owned(),
                ),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

pub async fn bind_wallpaper(
    State(state): State<SharedState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> ApiResult {
    let mut caller = authenticate(&state, &headers, false).await?;
    let body = read_body(&headers, body).await?;
    let url = body
        .get("wallpaper_url")
        .and_then(Value::as_str)
        .unwrap_or("");
    let prefix = format!("{}/media/wallpaper/", state.public_url);
    let Some(file_id) = url.strip_prefix(&prefix) else {
        return Ok(envelope_err(403, "wallpaper must belong to this server"));
    };
    let mut conn = state.runtime.store.pool_clone().await?;
    let raw: Option<String> = redis::AsyncCommands::get(&mut conn, format!("ouu:wallpaper-file:{file_id}"))
        .await
        .unwrap_or(None);
    let Some(raw) = raw else {
        return Ok(envelope_err(403, "wallpaper upload owner mismatch"));
    };
    let metadata: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
    if metadata["device_id"].as_str() != Some(caller.device.device_id.as_str()) {
        return Ok(envelope_err(403, "wallpaper upload owner mismatch"));
    }
    caller.device.wallpaper_url = url.to_owned();
    state.runtime.store.put_device(&caller.device).await?;
    Ok(envelope_ok(json!({})))
}

pub fn image_mime(image: &[u8]) -> Option<&'static str> {
    if image.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some("image/png");
    }
    if image.starts_with(b"\xff\xd8\xff") && image.ends_with(b"\xff\xd9") {
        return Some("image/jpeg");
    }
    if image.starts_with(b"RIFF") && image.len() > 12 && &image[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}
