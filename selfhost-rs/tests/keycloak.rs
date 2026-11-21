//! Keycloak/OIDC enrollment integration test with an in-process fake IdP.
//!
//! Covers the browser flow end to end: the approve page renders the
//! Keycloak link, /login bounces to the IdP authorize endpoint (PKCE +
//! state), the IdP redirects back with a code, the server exchanges it and
//! resolves identity via userinfo, and login_by_qrcode binds the device to
//! the Keycloak subject (user_id/nickname come from the IdP).

use axum::extract::{State, Query};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use std::sync::Mutex;

mod support;
use support::TestHarness;

struct FakeIdp {
    last_callback: Mutex<Option<String>>,
    last_base: Mutex<Option<String>>,
}

fn self_base(idp: std::sync::Arc<FakeIdp>) -> String {
    idp.last_base.lock().unwrap().clone().expect("idp base")
}

async fn discovery(State(idp): State<std::sync::Arc<FakeIdp>>) -> Json<Value> {
    let base = self_base(idp.clone());
    Json(json!({
        "authorization_endpoint": format!("{base}/auth"),
        "token_endpoint": format!("{base}/token"),
        "userinfo_endpoint": format!("{base}/userinfo"),
    }))
}

#[derive(serde::Deserialize)]
struct AuthQuery {
    redirect_uri: String,
    state: String,
}

async fn authorize(State(idp): State<std::sync::Arc<FakeIdp>>, Query(q): Query<AuthQuery>) -> Response {
    // A real IdP authenticates the user here; the fake one just approves.
    let callback = format!("{}?code=fake-code&state={}", q.redirect_uri, q.state);
    *idp.last_callback.lock().unwrap() = Some(callback.clone());
    Redirect::to(&callback).into_response()
}

async fn token() -> Json<Value> {
    Json(json!({"access_token": "fake-access", "token_type": "Bearer", "expires_in": 300}))
}

async fn userinfo(headers: HeaderMap) -> Response {
    match headers.get(axum::http::header::AUTHORIZATION).and_then(|v| v.to_str().ok()) {
        Some(t) if t == "Bearer fake-access" => Json(json!({
            "sub": "kc-sub-123",
            "preferred_username": "alice",
            "email": "alice@example.test"
        })).into_response(),
        _ => StatusCode::UNAUTHORIZED.into_response(),
    }
}

async fn spawn_fake_idp() -> (String, std::sync::Arc<FakeIdp>) {
    let idp = std::sync::Arc::new(FakeIdp { last_callback: Mutex::new(None), last_base: Mutex::new(None) });
    let idp_for_router = idp.clone();
    let router = Router::new()
        .route("/.well-known/openid-configuration", get(discovery))
        .route("/auth", get(authorize))
        .route("/token", post(token))
        .route("/userinfo", get(userinfo))
        .with_state(idp_for_router);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    *idp.last_base.lock().unwrap() = Some(base.clone());
    tokio::spawn(async move {
        let _ = axum::serve(listener, router).await;
    });
    (base, idp)
}

fn http() -> reqwest::Client {
    reqwest::Client::builder().build().unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "run with OPENUUYC_E2E=1"]
async fn keycloak_approval_binds_identity() {
    let (idp_base, idp) = spawn_fake_idp().await;
    let harness = TestHarness::start_with("redis://127.0.0.1:6379", vec![0], Some(idp_base.clone()), "selfhost-client", None, false).await;
    let base = harness.base(0);
    let client = http();

    // Register a device and generate a QR (no login yet).
    let client_id = "kchost0000000001";
    let reply: Value = client
        .post(format!("{base}/api/v1/device/windows/init"))
        .header("X-Param-client-id", client_id)
        .json(&json!({"client_id": client_id, "name": "kc-device"}))
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(reply["code"], 0, "{reply}");
    let device_id = reply["data"]["device_id"].as_str().unwrap().to_owned();

    let reply: Value = client
        .post(format!("{base}/api/v1/qrcode/gen/login"))
        .header("X-Param-client-id", client_id)
        .header("X-Param-device-id", &device_id)
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(reply["code"], 0, "{reply}");
    let ticket = reply["data"]["token"].as_str().unwrap().to_owned();
    let jump = reply["data"]["qrcode_jump_url"].as_str().unwrap().to_owned();

    // Approve page advertises the Keycloak login.
    let page = client.get(format!("{base}/enroll/{ticket}")).send().await.unwrap().text().await.unwrap();
    assert!(page.contains("Login with Keycloak"), "page: {page}");
    assert!(page.contains("Administrator key"), "fallback form kept");

    // Browser flow: /login redirects to the IdP authorize endpoint.
    let response = client.get(format!("{base}/enroll/{ticket}/login")).send().await.unwrap();
    assert_eq!(response.status(), StatusCode::FOUND);
    let location = response.headers().get(axum::http::header::LOCATION).unwrap().to_str().unwrap().to_owned();
    assert!(location.starts_with(&format!("{idp_base}/auth")), "location: {location}");
    assert!(location.contains("code_challenge_method=S256"));
    assert!(location.contains("state="));

    // The IdP redirects back to the server callback with a code.
    let callback_url = idp.last_callback.lock().unwrap().clone().expect("IdP saw the authorize");
    let response = client.get(callback_url).send().await.unwrap();
    assert_eq!(response.status(), 200, "callback approved");
    assert!(response.text().await.unwrap().contains("Approved by alice"));

    // The redirect consumed the state; the ticket is approved now.
    let reply: Value = client
        .post(format!("{base}/api/v1/login/by_qrcode"))
        .header("X-Param-client-id", client_id)
        .header("X-Param-device-id", &device_id)
        .json(&json!({"token": ticket, "qrcode_jump_url": jump}))
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(reply["code"], 0, "{reply}");
    // Identity comes from Keycloak, not the static "selfhost" placeholder.
    assert_eq!(reply["data"]["user_id"], "kc-sub-123");
    assert_eq!(reply["data"]["nickname"], "alice");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "run with OPENUUYC_E2E=1"]
async fn require_mode_hides_admin_key() {
    let (idp_base, _idp) = spawn_fake_idp().await;
    let harness = TestHarness::start_with("redis://127.0.0.1:6379", vec![0], Some(idp_base.clone()), "selfhost-client", None, true).await;
    let base = harness.base(0);
    let client = http();

    let client_id = "kcreq00000000001";
    let reply: Value = client
        .post(format!("{base}/api/v1/device/windows/init"))
        .header("X-Param-client-id", client_id)
        .json(&json!({"client_id": client_id, "name": "kc-req"}))
        .send().await.unwrap().json().await.unwrap();
    let device_id = reply["data"]["device_id"].as_str().unwrap().to_owned();
    let reply: Value = client
        .post(format!("{base}/api/v1/qrcode/gen/login"))
        .header("X-Param-client-id", client_id)
        .header("X-Param-device-id", &device_id)
        .send().await.unwrap().json().await.unwrap();
    let ticket = reply["data"]["token"].as_str().unwrap().to_owned();

    let page = client.get(format!("{base}/enroll/{ticket}")).send().await.unwrap().text().await.unwrap();
    assert!(page.contains("Login with Keycloak"));
    assert!(!page.contains("Administrator key"), "admin form hidden in require mode: {page}");
}
