//! Binary entry: load config, connect Redis, serve.

use openuuyc_selfhost::api::{AppState, SharedState};
use openuuyc_selfhost::runtime::Runtime;
use openuuyc_selfhost::*;
use axum::extract::ConnectInfo;
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::time::Instant;
use tracing_subscriber::EnvFilter;
use md5::Md5;
use base64::Engine as _;
use rand::Rng;
use futures_util::{SinkExt, StreamExt};
use axum::extract::ws::{Message as AxumMessage, WebSocket, WebSocketUpgrade};

use anyhow::Context;
use std::sync::Arc;

#[derive(Clone)]
struct Config {
    bind: SocketAddr,
    redis_url: String,
    public_url: String,
    admin_key: String,
    ice_urls: Vec<String>,
    turn_secret: String,
    turn_user: String,
    turn_password: String,
    wallpaper_dir: std::path::PathBuf,
    node_id: String,
    oidc_issuer: Option<String>,
    oidc_client_id: Option<String>,
    oidc_client_secret: Option<String>,
    oidc_require: bool,
}

fn load_config() -> anyhow::Result<Config> {
    let bind = std::env::var("OPENUUYC_BIND").unwrap_or_else(|_| "127.0.0.1:8844".into());
    let redis_url = std::env::var("OPENUUYC_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".into());
    let public_url = std::env::var("OPENUUYC_PUBLIC_URL").unwrap_or_default();
    anyhow::ensure!(
        public_url.starts_with("https://"),
        "OPENUUYC_PUBLIC_URL must be an HTTPS origin"
    );
    let admin_key = std::env::var("OPENUUYC_ADMIN_KEY").unwrap_or_default();
    anyhow::ensure!(admin_key.len() >= 24, "OPENUUYC_ADMIN_KEY must contain at least 24 characters");
    let ice_urls: Vec<String> = std::env::var("OPENUUYC_ICE_SERVERS")
        .unwrap_or_default()
        .split(',')
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .collect();
    anyhow::ensure!(
        !ice_urls.is_empty()
            && ice_urls.iter().all(|u| u.starts_with("stun:") || u.starts_with("turn:") || u.starts_with("turns:")),
        "configure at least one STUN/TURN URL"
    );
    let wallpaper_dir = std::env::var("OPENUUYC_DATA_DIR")
        .map(|dir| std::path::PathBuf::from(dir).join("wallpaper"))
        .unwrap_or_else(|_| std::path::PathBuf::from("./wallpaper"));
    std::fs::create_dir_all(&wallpaper_dir)?;
    let node_id = std::env::var("OPENUUYC_NODE_ID")
        .ok()
        .filter(|id| !id.is_empty())
        .unwrap_or_else(|| format!("node-{}", api::token_bytes(6)));
    let oidc_issuer = std::env::var("OPENUUYC_OIDC_ISSUER").ok().filter(|s| !s.is_empty());
    let oidc_client_id = std::env::var("OPENUUYC_OIDC_CLIENT_ID").ok().filter(|s| !s.is_empty());
    if let Some(issuer) = &oidc_issuer {
        let local = issuer.starts_with("http://127.0.0.1:")
            || issuer.starts_with("http://localhost:");
        anyhow::ensure!(
            issuer.starts_with("https://") || local,
            "OPENUUYC_OIDC_ISSUER must be an HTTPS URL (localhost http allowed for testing)"
        );
        anyhow::ensure!(
            oidc_client_id.is_some(),
            "OPENUUYC_OIDC_CLIENT_ID is required with OPENUUYC_OIDC_ISSUER"
        );
    }
    let oidc_require = matches!(
        std::env::var("OPENUUYC_OIDC_REQUIRE").as_deref(),
        Ok("1") | Ok("true") | Ok("TRUE")
    );
    if oidc_require && oidc_issuer.is_none() {
        anyhow::bail!("OPENUUYC_OIDC_REQUIRE needs OPENUUYC_OIDC_ISSUER");
    }
    Ok(Config {
        bind: bind.parse()?,
        redis_url,
        public_url,
        admin_key,
        ice_urls,
        turn_secret: std::env::var("OPENUUYC_TURN_SECRET").unwrap_or_default(),
        turn_user: std::env::var("OPENUUYC_TURN_USER").unwrap_or_default(),
        turn_password: std::env::var("OPENUUYC_TURN_PASSWORD").unwrap_or_default(),
        wallpaper_dir,
        node_id,
        oidc_issuer,
        oidc_client_id,
        oidc_client_secret: std::env::var("OPENUUYC_OIDC_CLIENT_SECRET").ok().filter(|s| !s.is_empty()),
        oidc_require,
    })
}

fn app_router(state: SharedState) -> Router {
    Router::new()
        // core (server.py)
        .route("/api/v1/device/windows/init", post(api::init_windows))
        .route("/api/v1/device/android/init", post(api::init_android))
        .route("/api/v1/qrcode/gen/login", post(api::generate_qr))
        .route("/api/v1/qrcode/login/status", post(api::qr_status))
        .route("/api/v1/login/by_qrcode", post(api::login_by_qrcode))
        .route("/api/v1/user/info", get(api::user_info))
        .route("/api/v1/user/logout", post(api::logout))
        .route("/api/v1/device/list", get(api::list_devices))
        .route("/api/v1/device/groups/of/my", get(api::device_groups))
        .route("/api/v1/device/{device}/detail", get(api::device_detail))
        .route("/api/v1/device/{device}", axum::routing::put(api::rename_device))
        .route("/api/v1/device/{device}/unbind", post(api::unbind_device))
        .route("/api/v1/device/controllable", post(api::set_controllable))
        .route("/api/v1/room/create", post(api::create_room))
        .route("/api/v1/room/join/by_device/{device}", post(api::join_room))
        .route("/api/v1/tool/configures_with_version", post(api::configures))
        .route("/api/v1/transport/{device}/rpc/request", post(api::transport_rpc))
        .route("/api/v1/transport/rpc/response", post(api::rpc_response))
        .route("/api/v1/release/trigger/{device}/update", post(api::release_trigger))
        // compat (compat.py)
        .route("/api/v1/guest/create", post(api::guest_create))
        .route("/api/v1/guest/room/create", post(api::guest_room))
        .route("/api/v1/guest/share/info", post(api::assist_identity))
        .route("/api/v1/device/share/info/refresh", post(api::assist_identity))
        .route("/api/v1/room/join/refetch", post(api::assist_refetch))
        .route("/api/v1/device/share/saved/{kind}", get(api::saved_list))
        .route("/api/v1/device/share/saved/favorites", post(api::save_favorites))
        .route("/api/v1/device/share/saved/{kind}/{device}", axum::routing::delete(api::delete_saved))
        .route("/api/v1/device/share/saved/recent", axum::routing::delete(api::clear_recent))
        .route("/api/v1/device/set_support_wol", post(api::set_wol))
        .route("/api/v1/device/wake_on_lan/info", post(api::wol_info))
        .route("/api/v1/device/{device}/wake_on_lan/req", post(api::wake))
        .route("/api/v1/device/{device}/reboot", post(api::reboot_device))
        // media (media.py)
        .route("/api/v1/tool/fp/token", get(api::wallpaper_grant))
        .route("/api/v1/device/wallpaper", post(api::bind_wallpaper))
        .route("/filepicker/upload/{grant}", post(api::upload_wallpaper))
        .route("/media/wallpaper/{file_id}", get(api::download_wallpaper))
        // enrollment
        .route("/enroll/{ticket}", get(api::approve_get).post(api::approve_post))
        .route("/enroll/{ticket}/login", get(api::oidc_login))
        .route("/enroll/{ticket}/callback", get(api::oidc_callback))
        // signaling
        .route("/socket.io/", get(signal::signal_upgrade))
        .route("/healthz", get(api::healthz))
        .fallback(|| async { axum::http::StatusCode::NOT_FOUND })
        .with_state(state)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    let config = load_config()?;
    let store = store::Store::connect(&config.redis_url).await?;
    let runtime = Runtime::new(config.node_id.clone(), store);
    let oidc = match (&config.oidc_issuer, &config.oidc_client_id) {
        (Some(issuer), Some(client_id)) => Some(Arc::new(oidc::OidcProvider::new(
            issuer,
            client_id,
            config.oidc_client_secret.clone(),
        ))),
        _ => None,
    };
    let state: SharedState = Arc::new(AppState {
        runtime: runtime.clone(),
        public_url: config.public_url.clone(),
        signal_host: config.public_url.trim_start_matches("https://").to_owned(),
        admin_key: config.admin_key,
        ice_urls: config.ice_urls,
        turn_secret: config.turn_secret,
        turn_user: config.turn_user,
        turn_password: config.turn_password,
        wallpaper_dir: config.wallpaper_dir,
        admin_failures: std::sync::Mutex::new(std::collections::HashMap::new()),
        started_at: Instant::now(),
        oidc,
        oidc_require: config.oidc_require,
    });
    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let bus_runtime = runtime.clone();
    let bus = tokio::spawn(async move { bus_runtime.run_bus(stop_rx).await });
    tracing::info!(node = %config.node_id, bind = %config.bind, "selfhost node starting");
    let listener = tokio::net::TcpListener::bind(config.bind).await?;
    let router = app_router(state).into_make_service_with_connect_info::<SocketAddr>();
    let stop_tx2 = stop_tx.clone();
    let runtime2 = runtime.clone();
    let shutdown = async move {
        let _ = tokio::signal::ctrl_c().await;
        tracing::info!("shutdown signal received");
        let _ = stop_tx2.send(true);
        runtime2.shutdown_sockets();
    };
    let result = axum::serve(listener, router).with_graceful_shutdown(shutdown).await;
    let _ = bus.await;
    result.map_err(|error| anyhow::anyhow!("server error: {error}"))
}
