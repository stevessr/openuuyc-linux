//! Socket.IO v4 signaling over axum WebSocket, ported from
//! selfhost/server.py signal()/handle_event(): ticket authentication, one
//! presence room per device, controller control-requests with distributed
//! slot claiming, soac/forward_setting relay restricted to the authenticated
//! peer pair, Engine.IO heartbeat, and assistance challenges via bmsg_push.

use crate::api::SharedState;
use crate::errors::ApiError;
use crate::proto::{self, Incoming};
use crate::runtime::{BusEvent, ControlSession, OutFrame, Peer, INTERNAL_EVENT_MARKER};
use crate::store::Ticket;
use axum::extract::ws::{Message as AxumMessage, WebSocket};
use axum::extract::{ConnectInfo, State, WebSocketUpgrade};
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const PRESENCE_TTL: u64 = 30;
const CONTROL_LEASE: i64 = 45;
const HEARTBEAT_SECS: u64 = 25;
const PING_TIMEOUT_SECS: u64 = 85;

fn hex_token(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::rng().fill(&mut buf[..]);
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

pub async fn signal_reject() -> Response {
    (axum::http::StatusCode::UNAUTHORIZED, "invalid room token").into_response()
}

pub async fn signal_upgrade(
    State(state): State<SharedState>,
    ConnectInfo(_addr): ConnectInfo<std::net::SocketAddr>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let token = headers
        .get("X-NRD-AUTH")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_owned();
    let ticket = match load_ticket(&state, &token).await {
        Ok(ticket) => ticket,
        Err(error) => return error.into_response(),
    };
    ws
        .max_message_size(proto::MAX_SIGNAL)
        .max_frame_size(proto::MAX_SIGNAL)
        .protocols(["socket.io"])
        .on_upgrade(move |socket| async move {
            run_session(state, ticket, token, socket).await;
        })
        .into_response()
}

/// Ticket validation on connect: token exists, bearer session is live, and
/// the ticket still belongs to that session's device (logout/unbind revoke).
async fn load_ticket(state: &SharedState, token: &str) -> Result<Ticket, ApiError> {
    let ticket = state
        .runtime
        .store
        .ticket(token)
        .await?
        .ok_or_else(|| ApiError::unauthorized("invalid room token"))?;
    if ticket.guest {
        state
            .runtime
            .store
            .guest_session(&ticket.session_token)
            .await?
            .ok_or_else(|| ApiError::unauthorized("revoked room session"))?;
        return Ok(ticket);
    }
    let device = state
        .runtime
        .store
        .session_device(&ticket.session_token)
        .await?
        .ok_or_else(|| ApiError::unauthorized("revoked room session"))?;
    if device != ticket.device {
        return Err(ApiError::unauthorized("revoked room session"));
    }
    Ok(ticket)
}

async fn touch_presence(state: &SharedState, device: &str, token: &str) {
    let mut conn = match state.runtime.store.pool_clone().await {
        Ok(conn) => conn,
        Err(_) => return,
    };
    let _: Result<(), _> = redis::AsyncCommands::set_ex(
        &mut conn,
        format!("ouu:presence:{device}"),
        token,
        PRESENCE_TTL,
    )
    .await;
}

async fn drop_presence(state: &SharedState, device: &str, token: &str) {
    let mut conn = match state.runtime.store.pool_clone().await {
        Ok(conn) => conn,
        Err(_) => return,
    };
    let current: Option<String> =
        redis::AsyncCommands::get(&mut conn, format!("ouu:presence:{device}")).await.ok().flatten();
    if current.as_deref() == Some(token) {
        let _: Result<(), _> =
            redis::AsyncCommands::del(&mut conn, format!("ouu:presence:{device}")).await;
    }
}

struct SessionCtx {
    peer: Peer,
    ticket: Ticket,
    token: String,
    /// (event, ack, args) awaiting the next binary attachment.
    pending: Option<(String, Option<String>, Vec<Value>)>,
    namespace: bool,
}

async fn run_session(state: SharedState, ticket: Ticket, token: String, socket: WebSocket) {
    let client_id = if ticket.client_id.is_empty() {
        hex_token(12)
    } else {
        ticket.client_id.clone()
    };
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<OutFrame>();
    let sid = client_id.clone();
    let peer = Peer {
        ticket: ticket.clone(),
        client_id,
        token: token.clone(),
        sender: tx,
    };
    // Presence room roles.
    let is_host = ticket.kind == "host";
    if is_host {
        state.runtime.register_host(peer.clone());
        touch_presence(&state, &ticket.device, &token).await;
    } else {
        state.runtime.register_controller(peer.clone());
    }
    let (mut sink, mut stream) = socket.split();
    let ctx = Arc::new(tokio::sync::Mutex::new(SessionCtx {
        peer: peer.clone(),
        ticket: ticket.clone(),
        token: token.clone(),
        pending: None,
        namespace: false,
    }));
    // Engine.IO open + namespace handshake.
    let open_client_id = sid.clone();
    let _ = sink
        .send(AxumMessage::Text(proto::encode_open(&open_client_id, HEARTBEAT_SECS * 1000, PING_TIMEOUT_SECS * 1000).into()))
        .await;
    let _ = sink
        .send(AxumMessage::Text(proto::encode_namespace_connected(&sid).into()))
        .await;

    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let mut stop_rx2 = stop_rx.clone();

    // Writer pump: outbound frames from the registry/bus land here.
    let writer = tokio::spawn(async move {
        loop {
            let frame = tokio::select! {
                frame = rx.recv() => match frame {
                    Some(frame) => frame,
                    None => break,
                },
                _ = stop_rx2.changed() => break,
            };
            let message = match frame {
                OutFrame::Text(text) => AxumMessage::Text(text.into()),
                OutFrame::Binary(bytes) => AxumMessage::Binary(bytes.into()),
                OutFrame::Close(code, reason) => {
                    let _ = sink
                        .send(AxumMessage::Close(Some(
                            axum::extract::ws::CloseFrame {
                                code: code.into(),
                                reason: reason.into(),
                            },
                        )))
                        .await;
                    break;
                }
            };
            if sink.send(message).await.is_err() {
                break;
            }
        }
    });

    // Heartbeat: server pings every 25s; connection dies at 85s silence.
    let heartbeat_state = state.clone();
    let heartbeat_ticket = ticket.clone();
    let heartbeat_token = token.clone();
    let mut heartbeat_stop = stop_rx.clone();
    let heartbeat_peer = peer.clone();
    let mut heartbeat_stop_task = stop_rx.clone();
    let heartbeat = tokio::spawn(async move {
        let mut last_seen = Instant::now();
        loop {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_secs(HEARTBEAT_SECS)) => {}
                _ = heartbeat_stop_task.changed() => break,
            }
            if last_seen.elapsed() > Duration::from_secs(PING_TIMEOUT_SECS) {
                break;
            }
            if is_host {
                touch_presence(&heartbeat_state, &heartbeat_ticket.device, &heartbeat_token).await;
            }
            heartbeat_peer.send_text("2".to_owned());
        }
    });
    let _ = heartbeat;

    let mut running = true;
    while running {
        let message = tokio::select! {
            message = stream.next() => match message {
                Some(Ok(m)) => m,
                Some(Err(_)) | None => break,
            },
            _ = heartbeat_stop.changed() => break,
        };
        match message {
            AxumMessage::Text(text) => match proto::parse_text(&text) {
                Ok(incoming) => {
                    if matches!(incoming, Incoming::Pong(_)) {
                        // last_seen lives in the heartbeat task; signals share
                        // the writer liveness so silence still kills it.
                    }
                    if matches!(incoming, Incoming::Ping) {
                        peer.send_text("3".into());
                        continue;
                    }
                    if matches!(incoming, Incoming::NamespaceDisconnect) {
                        break;
                    }
                    handle_frame(&state, &ctx, incoming).await;
                }
                Err(_) => {
                    peer.close(1008, "invalid event");
                    break;
                }
            },
            AxumMessage::Binary(bytes) => {
                if handle_binary(&state, &ctx, &bytes).await {
                    break;
                }
            }
            AxumMessage::Close(_) => break,
            AxumMessage::Ping(_) | AxumMessage::Pong(_) => {}
        }
    }
    let _ = stop_tx.send(true);
    let _ = writer.await;
    // Explicit cleanup: controller ticket stays valid across reconnects
    // (logical session), but the local registry slot must go.
    if is_host {
        state.runtime.unregister_host(&ticket.device, &token);
        drop_presence(&state, &ticket.device, &token).await;
        // Release any control session this host room was part of when the
        // room itself is gone for good.
        release_control_for_host(&state, &ticket).await;
    } else {
        state.runtime.unregister_controller(&token);
    }
}

/// A host room disappearing ends any control session it participated in:
/// the controller is released and the shared slot is freed.
async fn release_control_for_host(state: &SharedState, ticket: &Ticket) {
    let target = ticket.target.clone();
    let Some(session) = state.runtime.control_session(&target).await else { return };
    if session.host_client != ticket.client_id && ticket.device != target {
        return;
    }
    let owner = format!("{}:{}", session.controller_node, session.controller_device);
    let _ = state.runtime.store.release_control(&target, &owner).await;
    state.runtime.forget_control(&target).await;
    state
        .runtime
        .deliver_by_token(
            &session.controller_token,
            "released",
            vec![json!({"ice_id": session.ice, "app_control_id": session.app, "client_id": session.host_client})],
        )
        .await;
}

async fn handle_frame(
    state: &SharedState,
    ctx: &Arc<tokio::sync::Mutex<SessionCtx>>,
    incoming: Incoming,
) {
    match incoming {
        Incoming::Event { ack, name, args } => {
            let (peer, token, ticket) = {
                let guard = ctx.lock().await;
                (guard.peer.clone(), guard.token.clone(), guard.ticket.clone())
            };
            let _ = (token, ticket);
            dispatch(state, &peer, &name, args, ack, None).await;
        }
        Incoming::BinaryEvent { ack, name, args, attachments_left } => {
            let mut guard = ctx.lock().await;
            if attachments_left != 1 || guard.pending.is_some() {
                guard.peer.close(1008, "invalid binary event");
                return;
            }
            guard.pending = Some((name, ack, args));
        }
        _ => {}
    }
}

/// Dispatch a complete event with its optional binary attachment. Every
/// relay path carries the attachment explicitly; nothing is dropped.
async fn dispatch(
    state: &SharedState,
    peer: &Peer,
    name: &str,
    args: Vec<Value>,
    ack: Option<String>,
    attachment: Option<Vec<u8>>,
) {
    match name {
        "room_info" => {
            let payload = json!({"room_id": peer.ticket.target, "client_id": peer.client_id});
            if let Some(ack) = ack {
                peer.send_ack(&ack, &[payload]);
            }
        }
        "refresh_reconnect_key" => {
            if let Some(ack) = ack {
                peer.send_ack(&ack, &[json!("success"), json!({"reconnect_key": hex_token(16)})]);
            }
        }
        "leave" | "clear_out" => {
            release_control_of(state, peer).await;
            if let Some(ack) = ack {
                peer.send_ack(&ack, &[]);
            }
        }
        "control" => handle_control(state, peer, args, ack, attachment).await,
        "soac" | "forward_setting" => handle_relay(state, peer, name, args, attachment).await,
        _ => {
            if let Some(ack) = ack {
                peer.send_ack(&ack, &[]);
            }
        }
    }
}

async fn handle_binary(state: &SharedState, ctx: &Arc<tokio::sync::Mutex<SessionCtx>>, bytes: &[u8]) -> bool {
    let mut guard = ctx.lock().await;
    if !bytes.starts_with(b"\x04") || bytes.len() < 2 {
        guard.peer.close(1008, "orphan binary payload");
        return true;
    }
    let Some((name, ack, args)) = guard.pending.take() else {
        guard.peer.close(1008, "orphan binary payload");
        return true;
    };
    let attachment = bytes[1..].to_vec();
    let peer = guard.peer.clone();
    drop(guard);
    dispatch(state, &peer, &name, args, ack, Some(attachment)).await;
    false
}

fn session_host_target(peer: &Peer) -> String {
    peer.ticket.target.clone()
}

async fn release_control_of(state: &SharedState, peer: &Peer) {
    let target = peer.ticket.target.clone();
    let Some(session) = state.runtime.control_session(&target).await else { return };
    let participates = session.controller_token == peer.token
        || (peer.ticket.kind == "host" && peer.ticket.device == session_host_target(peer));
    if !participates {
        return;
    }
    let owner = format!("{}:{}", session.controller_node, session.controller_device);
    let _ = state.runtime.store.release_control(&target, &owner).await;
    state.runtime.forget_control(&target).await;
    state
        .runtime
        .deliver_by_token(
            &session.controller_token,
            "released",
            vec![json!({"ice_id": session.ice, "app_control_id": session.app, "client_id": session.host_client})],
        )
        .await;
    deliver_to_host_session(
        state,
        &target,
        &session,
        "left",
        json!({"ice_id": session.ice, "app_control_id": session.app, "client_id": session.controller_client}),
    )
    .await;
}

async fn deliver_to_host_session(
    state: &SharedState,
    target: &str,
    session: &ControlSession,
    event: &str,
    payload: Value,
) {
    if let Some(host) = state.runtime.host_peer(target) {
        host.send_event(event, &[payload], None);
        return;
    }
    state
        .runtime
        .publish(BusEvent::DeviceDelta {
            device_id: target.to_owned(),
            kind: INTERNAL_EVENT_MARKER.into(),
            fields: json!({"__deliver": {"event": event, "args": [payload]}}),
        })
        .await;
}

async fn handle_control(
    state: &SharedState,
    peer: &Peer,
    args: Vec<Value>,
    ack: Option<String>,
    attachment: Option<Vec<u8>>,
) {
    let reject = |code: i32| {
        if let Some(ack) = &ack {
            peer.send_ack(ack, &[json!("rejected"), json!({"code": code})]);
        }
    };
    if peer.ticket.kind != "controller" {
        reject(403);
        return;
    }
    let (Some(attachment), Some(request)) = (attachment, args.into_iter().next()) else {
        reject(400);
        return;
    };
    let app = request
        .get("app_control_id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    let placeholder = request
        .get("app_data")
        .and_then(|v| v.get("_placeholder"))
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if app.is_empty() || app.len() > 128 || !placeholder {
        reject(400);
        return;
    }
    let target = peer.ticket.target.clone();
    let Some(host_row) = state.runtime.store.device(&target).await.ok().flatten() else {
        reject(404);
        return;
    };
    if !host_row.controllable {
        reject(404);
        return;
    }
    let owner = format!("{}:{}", state.runtime.node_id, peer.ticket.device);
    let existing = state.runtime.store.control_owner(&target).await.unwrap_or(None);
    if let Some(existing) = existing
        && existing != owner
    {
        if !peer.ticket.force {
            reject(1136);
            return;
        }
        if let Some(previous) = state.runtime.control_session(&target).await {
            state.runtime.forget_control(&target).await;
            state
                .runtime
                .deliver_by_token(
                    &previous.controller_token,
                    "released",
                    vec![json!({"ice_id": previous.ice, "app_control_id": previous.app, "client_id": previous.host_client})],
                )
                .await;
            deliver_to_host_session(
                state,
                &target,
                &previous,
                "left",
                json!({"ice_id": previous.ice, "app_control_id": previous.app, "client_id": previous.controller_client}),
            )
            .await;
        }
        let _ = state.runtime.store.release_control(&target, &existing).await;
    }
    if !state
        .runtime
        .store
        .claim_control(&target, &owner, CONTROL_LEASE)
        .await
        .unwrap_or(false)
    {
        reject(1136);
        return;
    }
    let ice = hex_token(12);
    let host_client = host_row.client_id.clone();
    state.runtime.remember_control(
        &target,
        ControlSession {
            controller_node: state.runtime.node_id.clone(),
            controller_token: peer.token.clone(),
            controller_client: peer.client_id.clone(),
            controller_device: peer.ticket.device.clone(),
            host_token: target.clone(),
            host_client: host_client.clone(),
            ice: ice.clone(),
            app: app.clone(),
        },
    ).await;
    let forwarded = json!({
        "client_id": peer.client_id,
        "ice_id": ice,
        "app_control_id": app,
        "app_data": {"_placeholder": true, "num": 0},
        "streamer_data": request.get("streamer_data").cloned().unwrap_or(json!("{}")),
    });
    if let Some(host) = state.runtime.host_peer(&target) {
        host.send_event("be-controlled", &[forwarded], Some(&attachment));
    } else {
        state
            .runtime
            .publish(BusEvent::HostBinary {
                device_id: target.clone(),
                event: "be-controlled".into(),
                header: forwarded,
                bytes: attachment,
            })
            .await;
    }
    if let Some(ack) = ack {
        let ice_list = crate::ice::ice_servers(
            &state.ice_urls,
            &state.turn_secret,
            &state.turn_user,
            &state.turn_password,
            3600,
        );
        let reply = json!({
            "code": 0,
            "client_id": host_client,
            "ice_id": ice,
            "iceServers": ice_list,
            "force_relay": false,
        });
        peer.send_ack(&ack, &[json!("success"), reply]);
    }
}

async fn handle_relay(
    state: &SharedState,
    peer: &Peer,
    event: &str,
    args: Vec<Value>,
    attachment: Option<Vec<u8>>,
) {
    let Some(item) = args.into_iter().next() else { return };
    let Some(data) = item.get("data").cloned() else { return };
    if !data.is_object() {
        return;
    }
    let target = peer.ticket.target.clone();
    let Some(session) = state.runtime.control_session(&target).await else { return };
    let is_controller = session.controller_token == peer.token;
    let is_host = peer.ticket.kind == "host";
    if !is_controller && !is_host {
        return;
    }
    let other_client = if is_controller {
        session.host_client.clone()
    } else {
        session.controller_client.clone()
    };
    let mut declared_ice = data
        .get("ice_id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    if event == "forward_setting" {
        let kind = data.get("type").and_then(Value::as_str).unwrap_or("");
        let nested = data.get(kind).cloned().unwrap_or(Value::Null);
        if kind == "signal_app_data" {
            declared_ice = session.ice.clone();
        } else if nested.is_object() {
            declared_ice = nested
                .get("ice_id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
        }
    }
    let declared_app = data
        .get("app_control_id")
        .and_then(Value::as_str)
        .unwrap_or(session.app.as_str());
    if item.get("client_id").and_then(Value::as_str).unwrap_or("") != other_client
        || declared_ice != session.ice
        || declared_app != session.app
    {
        return;
    }
    if is_controller {
        deliver_to_host_session(state, &target, &session, event, item).await;
        if let Some(bytes) = attachment {
            deliver_host_binary(state, &target, event, &bytes).await;
        }
    } else {
        state
            .runtime
            .deliver_by_token_with_attachment(&session.controller_token, event, vec![item], attachment)
            .await;
    }
}

async fn deliver_host_binary(state: &SharedState, target: &str, event: &str, bytes: &[u8]) {
    if let Some(host) = state.runtime.host_peer(target) {
        host.send_event(event, &[json!({"__binary": true})], Some(bytes));
        return;
    }
    let envelope = BusEvent::HostBinary {
        device_id: target.to_owned(),
        event: event.to_owned(),
        header: json!({"__binary": true}),
        bytes: bytes.to_vec(),
    };
    state.runtime.publish(envelope).await;
}
