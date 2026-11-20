//! Test harness: spawns real axum nodes (tokio tasks) sharing one Redis and
//! speaks the wire protocol (REST + Engine.IO v4) like the real clients.

use axum::Router;
use openuuyc_selfhost::api::{AppState, SharedState};
use openuuyc_selfhost::runtime::Runtime;
use openuuyc_selfhost::store::Store;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use base64::Engine as _;
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Instant;

pub struct TestHarness {
    pub bases: Vec<String>,
    pub store: Store,
    pub admins: Vec<String>,
    pub _guards: Vec<tokio::task::JoinHandle<()>>,
}

pub struct TestDevice {
    pub device_id: String,
    pub client_id: String,
    pub token: String,
}

pub struct JoinReply {
    pub code: i32,
    pub token: String,
}

fn token_bytes(n: usize) -> String {
    let mut buf = vec![0u8; n];
    rand::rng().fill(&mut buf[..]);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

fn admin_key() -> String {
    "0123456789abcdefghijklmnopqrstuvwxyz012345".to_owned()
}

impl TestHarness {
    /// Spawn `ports.len()` nodes; port 0 lets the OS pick an ephemeral port.
    pub async fn start(redis_url: &str, ports: Vec<u16>) -> Self {
        static INIT: std::sync::Once = std::sync::Once::new();
        INIT.call_once(|| {
            tracing_subscriber::fmt()
                .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "debug".into()))
                .try_init()
                .ok();
        });
        let store = Store::connect(redis_url).await.expect("redis");
        let mut bases = Vec::new();
        let mut guards = Vec::new();
        let mut admins = Vec::new();
        for (index, port) in ports.into_iter().enumerate() {
            let node_id = format!("test-node-{index}");
            let runtime = Runtime::new(node_id.clone(), store.clone());
            let state: SharedState = Arc::new(AppState {
                runtime: runtime.clone(),
                public_url: "https://test.example".into(),
                signal_host: "test.example".into(),
                admin_key: admin_key(),
                ice_urls: vec!["stun:stun.test:3478".into()],
                turn_secret: String::new(),
                turn_user: String::new(),
                turn_password: String::new(),
                wallpaper_dir: std::env::temp_dir().join(format!("selfhost-test-{index}-{}", token_bytes(6))),
                admin_failures: std::sync::Mutex::new(Default::default()),
                started_at: Instant::now(),
            });
            std::fs::create_dir_all(&state.wallpaper_dir).unwrap();
            let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
            let bus_runtime = runtime.clone();
            tokio::spawn(async move { bus_runtime.run_bus(stop_rx).await });
            let router = app_router(state);
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await.unwrap();
            let base = format!("http://{}", listener.local_addr().unwrap());
            bases.push(base.clone());
            admins.push(admin_key());
            let runtime2 = runtime.clone();
            guards.push(tokio::spawn(async move {
                let shutdown = async move {
                    let _ = tokio::signal::ctrl_c().await;
                    let _ = stop_tx.send(true);
                    runtime2.shutdown_sockets();
                };
                let _ = axum::serve(listener, router.into_make_service_with_connect_info::<std::net::SocketAddr>()).with_graceful_shutdown(shutdown).await;
            }));
        }
        Self { bases, store, admins, _guards: guards }
    }

    pub fn base(&self, index: usize) -> String {
        self.bases[index].clone()
    }

    pub async fn register_login(&self, base: &str, name: &str) -> TestDevice {
        let mut client_id = name.to_lowercase().replace('-', "");
        let mut client_id = format!("{:0<12}", &client_id[..client_id.len().min(12)]);
        const ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyz0123456789";
        let suffix: String = (0..4).map(|_| ALPHABET[rand::rng().random_range(0..ALPHABET.len())] as char).collect();
        client_id = format!("{}{}", client_id, suffix);
        let client_id = client_id[..16].to_owned();
        let client = reqwest::Client::new();
        let reply: Value = client
            .post(format!("{base}/api/v1/device/windows/init"))
            .header("X-Param-client-id", client_id.as_str())
            .json(&json!({"client_id": client_id, "name": name}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(reply["code"], 0, "init: {reply}");
        let device_id = reply["data"]["device_id"].as_str().unwrap().to_owned();

        let reply: Value = client
            .post(format!("{base}/api/v1/qrcode/gen/login"))
            .header("X-Param-client-id", client_id.as_str())
            .header("X-Param-device-id", device_id.clone())
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(reply["code"], 0, "qr gen: {reply}");
        let ticket = reply["data"]["token"].as_str().unwrap().to_owned();
        let jump = reply["data"]["qrcode_jump_url"].as_str().unwrap().to_owned();
        let path = jump.split("/enroll/").nth(1).unwrap().to_owned();
        let status = client
            .post(format!("{base}/enroll/{path}"))
            .header("x-forwarded-for", format!("10.0.0.{}", token_bytes(1).len()))
            .form(&[("admin_key", admin_key())])
            .send()
            .await
            .unwrap()
            .status();
        assert_eq!(status, 200, "enroll approval status={status}");

        let reply: Value = client
            .post(format!("{base}/api/v1/login/by_qrcode"))
            .header("X-Param-client-id", client_id.as_str())
            .header("X-Param-device-id", device_id.clone())
            .json(&json!({"token": ticket, "qrcode_jump_url": jump}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        eprintln!("LOGIN RAW: {reply}");
        assert_eq!(reply["code"], 0, "login: {reply}");
        TestDevice {
            device_id,
            client_id: client_id.clone(),
            token: reply["data"]["token"].as_str().unwrap().to_owned(),
        }
    }

    pub async fn set_controllable(&self, base: &str, device: &TestDevice) {
        let client = reqwest::Client::new();
        let reply: Value = client
            .post(format!("{base}/api/v1/device/controllable"))
            .header("X-Param-client-id", device.client_id.as_str())
            .header("X-Param-device-id", device.device_id.as_str())
            .header("Authorization", format!("Bearer {}", device.token))
            .json(&json!({"controllable": true}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(reply["code"], 0);
    }

    pub async fn rename(&self, base: &str, device: &TestDevice, alias: &str) {
        let client = reqwest::Client::new();
        let reply: Value = client
            .put(format!("{base}/api/v1/device/{}", device.device_id))
            .header("X-Param-client-id", device.client_id.as_str())
            .header("X-Param-device-id", device.device_id.as_str())
            .header("Authorization", format!("Bearer {}", device.token))
            .json(&json!({"alias": alias}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(reply["code"], 0, "rename: {reply}");
    }

    pub async fn host_room(&self, base: &str, device: &TestDevice) -> TestSocket {
        let client = reqwest::Client::new();
        let reply: Value = client
            .post(format!("{base}/api/v1/room/create"))
            .header("X-Param-client-id", device.client_id.as_str())
            .header("X-Param-device-id", device.device_id.as_str())
            .header("Authorization", format!("Bearer {}", device.token))
            .json(&json!({}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(reply["code"], 0, "room create: {reply}");
        self.open_socket(base, reply["data"]["token"].as_str().unwrap()).await
    }

    pub async fn join(&self, base: &str, device: &TestDevice, target: &str, force: bool) -> JoinReply {
        let client = reqwest::Client::new();
        let reply: Value = client
            .post(format!("{base}/api/v1/room/join/by_device/{target}"))
            .header("X-Param-client-id", device.client_id.as_str())
            .header("X-Param-device-id", device.device_id.as_str())
            .header("Authorization", format!("Bearer {}", device.token))
            .json(&json!({"force_join": force}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        JoinReply {
            code: reply["code"].as_i64().unwrap_or(-1) as i32,
            token: reply["data"]["token"].as_str().unwrap_or_default().to_owned(),
        }
    }

    pub async fn controller_room(&self, base: &str, token: &str) -> TestSocket {
        self.open_socket(base, token).await
    }

    async fn open_socket(&self, base: &str, token: &str) -> TestSocket {
        let ws_url = format!("{}/socket.io/?EIO=4&transport=websocket", base.replacen("http://", "ws://", 1));
        let request = tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(ws_url).unwrap();
        let request = {
            let mut request = request;
            request.headers_mut().insert(
                "X-NRD-AUTH",
                reqwest::header::HeaderValue::from_str(token).unwrap(),
            );
            request
        };
        let (stream, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        let (mut sink, mut stream) = stream.split();
        // OPEN
        let open = stream.next().await.unwrap().unwrap();
        eprintln!("OPEN FRAME: {:?}", open);
        assert!(open.to_text().unwrap().starts_with("0{"));
        // namespace connect both ways
        sink.send(WsMessage::Text("40".into())).await.unwrap();
        let ns = stream.next().await.unwrap().unwrap();
        assert!(ns.to_text().unwrap().starts_with("40"));
        // room_info ack (device deltas may race it; drain until it arrives)
        let ack = loop {
            let frame = stream.next().await.unwrap().unwrap();
            let text = frame.to_text().unwrap();
            if text.starts_with("431[") {
                break text.to_owned();
            }
        };
        TestSocket { sink, stream }
    }
}

pub struct TestSocket {
    pub sink: futures_util::stream::SplitSink<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>, WsMessage>,
    pub stream: futures_util::stream::SplitStream<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>>,
}

impl TestSocket {
    pub async fn send_text(&mut self, text: &str) {
        self.sink.send(WsMessage::Text(text.into())).await.unwrap();
    }

    pub async fn send_binary(&mut self, bytes: &[u8]) {
        let mut frame = vec![0x04];
        frame.extend(bytes);
        self.sink.send(WsMessage::Binary(frame.into())).await.unwrap();
    }

    pub async fn next_text(&mut self) -> String {
        loop {
            let message = self.stream.next().await.expect("socket closed").unwrap();
            match message {
                WsMessage::Text(text) => return text.to_string(),
                WsMessage::Binary(_) => continue,
                WsMessage::Close(_) => panic!("unexpected close"),
                _ => continue,
            }
        }
    }

    pub async fn next_binary(&mut self) -> Vec<u8> {
        loop {
            let message = self.stream.next().await.expect("socket closed").unwrap();
            if let WsMessage::Binary(bytes) = message {
                assert_eq!(bytes[0], 0x04);
                return bytes[1..].to_vec();
            }
        }
    }
}

fn app_router(state: SharedState) -> axum::Router {
    use axum::routing::{get, post};
    Router::new()
        .route("/api/v1/device/windows/init", post(openuuyc_selfhost::api::init_windows))
        .route("/api/v1/device/android/init", post(openuuyc_selfhost::api::init_android))
        .route("/api/v1/qrcode/gen/login", post(openuuyc_selfhost::api::generate_qr))
        .route("/api/v1/qrcode/login/status", post(openuuyc_selfhost::api::qr_status))
        .route("/api/v1/login/by_qrcode", post(openuuyc_selfhost::api::login_by_qrcode))
        .route("/api/v1/user/info", get(openuuyc_selfhost::api::user_info))
        .route("/api/v1/user/logout", post(openuuyc_selfhost::api::logout))
        .route("/api/v1/device/list", get(openuuyc_selfhost::api::list_devices))
        .route("/api/v1/device/groups/of/my", get(openuuyc_selfhost::api::device_groups))
        .route("/api/v1/device/{device}/detail", get(openuuyc_selfhost::api::device_detail))
        .route("/api/v1/device/{device}", axum::routing::put(openuuyc_selfhost::api::rename_device))
        .route("/api/v1/device/{device}/unbind", post(openuuyc_selfhost::api::unbind_device))
        .route("/api/v1/device/controllable", post(openuuyc_selfhost::api::set_controllable))
        .route("/api/v1/room/create", post(openuuyc_selfhost::api::create_room))
        .route("/api/v1/room/join/by_device/{device}", post(openuuyc_selfhost::api::join_room))
        .route("/api/v1/tool/configures_with_version", post(openuuyc_selfhost::api::configures))
        .route("/api/v1/transport/{device}/rpc/request", post(openuuyc_selfhost::api::transport_rpc))
        .route("/api/v1/transport/rpc/response", post(openuuyc_selfhost::api::rpc_response))
        .route("/api/v1/release/trigger/{device}/update", post(openuuyc_selfhost::api::release_trigger))
        .route("/api/v1/guest/create", post(openuuyc_selfhost::api::guest_create))
        .route("/api/v1/guest/room/create", post(openuuyc_selfhost::api::guest_room))
        .route("/api/v1/guest/share/info", post(openuuyc_selfhost::api::assist_identity))
        .route("/api/v1/device/share/info/refresh", post(openuuyc_selfhost::api::assist_identity))
        .route("/api/v1/room/join/refetch", post(openuuyc_selfhost::api::assist_refetch))
        .route("/api/v1/device/share/saved/{kind}", get(openuuyc_selfhost::api::saved_list))
        .route("/api/v1/device/share/saved/favorites", post(openuuyc_selfhost::api::save_favorites))
        .route("/api/v1/device/share/saved/{kind}/{device}", axum::routing::delete(openuuyc_selfhost::api::delete_saved))
        .route("/api/v1/device/share/saved/recent", axum::routing::delete(openuuyc_selfhost::api::clear_recent))
        .route("/api/v1/device/set_support_wol", post(openuuyc_selfhost::api::set_wol))
        .route("/api/v1/device/wake_on_lan/info", post(openuuyc_selfhost::api::wol_info))
        .route("/api/v1/device/{device}/wake_on_lan/req", post(openuuyc_selfhost::api::wake))
        .route("/api/v1/device/{device}/reboot", post(openuuyc_selfhost::api::reboot_device))
        .route("/api/v1/tool/fp/token", get(openuuyc_selfhost::api::wallpaper_grant))
        .route("/api/v1/device/wallpaper", post(openuuyc_selfhost::api::bind_wallpaper))
        .route("/filepicker/upload/{grant}", post(openuuyc_selfhost::api::upload_wallpaper))
        .route("/media/wallpaper/{file_id}", get(openuuyc_selfhost::api::download_wallpaper))
        .route("/enroll/{ticket}", get(openuuyc_selfhost::api::approve_get).post(openuuyc_selfhost::api::approve_post))
        .route("/socket.io/", get(openuuyc_selfhost::signal::signal_upgrade))
        .route("/healthz", get(openuuyc_selfhost::api::healthz))
        .with_state(state)
}

// ---------- high-level protocol helpers on TestSocket ----------

impl TestSocket {
    pub async fn send_control(&mut self, app: &str, attachment: &[u8]) -> Value {
        let header = format!(
            "451-2{}",
            serde_json::to_string(&json!([
                "control",
                {"app_control_id": app, "app_data": {"_placeholder": true, "num": 0}, "streamer_data": "{}"}
            ]))
            .unwrap()
        );
        self.send_text(&header).await;
        self.send_binary(attachment).await;
        let ack = self.next_text().await;
        assert!(ack.starts_with("432["), "control ack: {ack}");
        let parsed: Vec<Value> = serde_json::from_str(&ack[3..]).unwrap();
        assert_eq!(parsed[0], "success");
        parsed[1].clone()
    }

    pub async fn send_soac(&mut self, other_client: &str, ice: &str, app: &str, attachment: &[u8]) {
        let header = format!(
            "451-{}",
            serde_json::to_string(&json!([
                "soac",
                {"client_id": other_client, "data": {"ice_id": ice, "app_control_id": app, "type": "offer", "gzip_sdp": {"_placeholder": true, "num": 0}}}
            ]))
            .unwrap()
        );
        self.send_text(&header).await;
        self.send_binary(attachment).await;
    }

    pub async fn send_forward_setting(&mut self, other_client: &str, ice: &str, app: &str) {
        let frame = serde_json::to_string(&json!([
            "forward_setting",
            {"client_id": other_client, "data": {"type": "device_capability", "device_capability": {"ice_id": ice, "display_info": [], "video_codec_capability": []}}}
        ]))
        .unwrap();
        self.send_text(&format!("42{frame}")).await;
    }

    pub async fn clear_out(&mut self) {
        self.send_text("42[\"clear_out\"]").await;
    }

    pub async fn expect_event(&mut self, name: &str) -> Value {
        let deadline = Instant::now() + std::time::Duration::from_secs(5);
        loop {
            assert!(Instant::now() < deadline, "timed out waiting for {name}");
            let text = tokio::time::timeout(std::time::Duration::from_secs(6), self.next_text()).await.expect("frame wait timeout");
            let rest = if let Some(r) = text.strip_prefix("42") { r } else if let Some(r) = text.strip_prefix("451-") { r } else { continue };
            let parsed: Vec<Value> = serde_json::from_str(rest).unwrap();
            if parsed[0] == name {
                return parsed.get(1).cloned().unwrap_or(Value::Null);
            }
        }
    }

    pub async fn expect_bmsg(&mut self, kind: &str) -> Value {
        let deadline = Instant::now() + std::time::Duration::from_secs(5);
        loop {
            assert!(Instant::now() < deadline, "timed out waiting for bmsg {kind}");
            let text = tokio::time::timeout(std::time::Duration::from_secs(6), self.next_text()).await.expect("frame wait timeout");
            let rest = if let Some(r) = text.strip_prefix("42") { r } else if let Some(r) = text.strip_prefix("451-") { r } else { continue };
            let parsed: Vec<Value> = serde_json::from_str(rest).unwrap();
            if parsed[0] == "bmsg_push" && parsed[1]["type"] == kind {
                return parsed[1].clone();
            }
        }
    }
}