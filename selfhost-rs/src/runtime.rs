//! Node-local peer registry with Redis-backed cross-node delivery.
//!
//! Every node keeps only its own live signaling peers in memory. Anything
//! that must reach a peer on another node — control events, device-change
//! deltas, assistance answers — is published to one Redis channel and fanned
//! back out by this node's subscriber task. Active control sessions live in
//! the shared store with lease TTLs, so release/takeover work regardless of
//! which node holds either WebSocket.

use crate::proto;
use crate::store::{Store, Ticket};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;

/// Outbound frames for one peer's writer pump.
#[derive(Debug)]
pub enum OutFrame {
    Text(String),
    Binary(Vec<u8>),
    Close(u16, &'static str),
}

/// One connected signaling peer. Identity is the room ticket: `client_id`
/// for controllers, the device id for hosts. Sending goes exclusively
/// through the mpsc sender owned by the socket pump task.
#[derive(Clone)]
pub struct Peer {
    pub ticket: Ticket,
    pub client_id: String,
    pub token: String,
    pub sender: mpsc::UnboundedSender<OutFrame>,
}

impl Peer {
    pub fn send_text(&self, text: String) {
        let _ = self.sender.send(OutFrame::Text(text));
    }

    pub fn send_event(&self, name: &str, args: &[Value], attachment: Option<&[u8]>) {
        match attachment {
            None => self.send_text(proto::encode_event(name, args, None)),
            Some(bytes) => {
                let (header, payload) = proto::encode_binary_event(name, args, bytes);
                self.send_text(header);
                let _ = self.sender.send(OutFrame::Binary(payload));
            }
        }
    }

    pub fn send_ack(&self, ack: &str, args: &[Value]) {
        self.send_text(proto::encode_ack(ack, args));
    }

    pub fn close(&self, code: u16, reason: &'static str) {
        let _ = self.sender.send(OutFrame::Close(code, reason));
    }
}

/// Events published between nodes (and loops within one node).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BusEvent {
    /// Deliver a Socket.IO event to a peer by room token wherever it lives.
    Deliver {
        token: String,
        event: String,
        args: Vec<Value>,
        attachment: Option<Vec<u8>>,
    },
    /// Account device delta for the bmsg_push fan-out to all host rooms.
    DeviceDelta { device_id: String, kind: String, fields: Value },
    /// Socket.IO binary event for a device's host room: JSON header plus
    /// the raw attachment bytes.
    HostBinary { device_id: String, event: String, header: Value, bytes: Vec<u8> },
}

/// Node-local registries. Identity keys mirror the ticket roles.
#[derive(Default)]
struct Local {
    /// host presence rooms by device id.
    hosts: HashMap<String, Peer>,
    /// controller rooms by room token.
    controllers: HashMap<String, Peer>,
}

pub struct Runtime {
    pub node_id: String,
    pub store: Store,
    pub channel: String,
    local: std::sync::Mutex<Local>,
    /// Node-local control sessions this node brokered: target -> session.
    pub controls: std::sync::Mutex<HashMap<String, ControlSession>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ControlSession {
    /// Node that claimed the shared control slot; releases must address the
    /// same owner string regardless of which node releases.
    pub controller_node: String,
    pub controller_token: String,
    pub controller_client: String,
    pub controller_device: String,
    pub host_token: String,
    pub host_client: String,
    pub ice: String,
    pub app: String,
}

impl Runtime {
    pub fn new(node_id: String, store: Store) -> Arc<Self> {
        Arc::new(Self {
            node_id,
            channel: "ouu:bus".to_owned(),
            store,
            local: std::sync::Mutex::new(Local::default()),
            controls: std::sync::Mutex::new(HashMap::new()),
        })
    }

    /// Register a host presence room; an older physical socket from the same
    /// device is superseded exactly like the Python reference.
    pub fn register_host(&self, peer: Peer) {
        let mut local = self.local.lock().unwrap();
        if let Some(previous) = local.hosts.get(&peer.ticket.device) {
            previous.close(1008, "host superseded");
        }
        local.hosts.insert(peer.ticket.device.clone(), peer);
    }

    /// Register a controller room; the same token reconnecting replaces the
    /// previous socket (logical session continuity across reconnects).
    pub fn register_controller(&self, peer: Peer) {
        let mut local = self.local.lock().unwrap();
        if let Some(previous) = local.controllers.get(&peer.token) {
            previous.close(1008, "controller superseded");
        }
        local.controllers.insert(peer.token.clone(), peer);
    }

    pub fn unregister_controller(&self, token: &str) {
        self.local.lock().unwrap().controllers.remove(token);
    }

    pub fn unregister_host(&self, device: &str, token: &str) {
        let mut local = self.local.lock().unwrap();
        if local.hosts.get(device).map(|p| p.token.clone()) == Some(token.to_owned()) {
            local.hosts.remove(device);
        }
    }

    pub fn local_hosts(&self) -> Vec<Peer> {
        self.local.lock().unwrap().hosts.values().cloned().collect()
    }

    pub fn host_peer(&self, device: &str) -> Option<Peer> {
        self.local.lock().unwrap().hosts.get(device).cloned()
    }

    pub fn controller_peer(&self, token: &str) -> Option<Peer> {
        self.local.lock().unwrap().controllers.get(token).cloned()
    }

    pub fn shutdown_sockets(&self) {
        let local = self.local.lock().unwrap();
        for peer in local.hosts.values().chain(local.controllers.values()) {
            peer.close(1001, "server shutdown");
        }
    }

    /// Node-local first, then the shared Redis copy (the session may have
    /// been brokered by another node).
    pub async fn control_session(&self, target: &str) -> Option<ControlSession> {
        if let Some(session) = self.controls.lock().unwrap().get(target).cloned() {
            return Some(session);
        }
        let raw = self.store.get_control_session(target).await.ok()??;
        serde_json::from_str(&raw).ok()
    }

    pub async fn remember_control(&self, target: &str, session: ControlSession) {
        if let Ok(payload) = serde_json::to_string(&session) {
            let _ = self.store.put_control_session(target, &payload, 45).await;
        }
        self.controls.lock().unwrap().insert(target.to_owned(), session);
    }

    pub async fn forget_control(&self, target: &str) -> Option<ControlSession> {
        let _ = self.store.del_control_session(target).await;
        self.controls.lock().unwrap().remove(target)
    }

    // ---------- bus ----------

    /// Publish one event to every node (including this one).
    pub async fn publish(&self, event: BusEvent) {
        let Ok(payload) = serde_json::to_string(&event) else { return };
        if let Ok(mut conn) = self.store.pool_clone().await {
            let _: Result<usize, _> =
                redis::AsyncCommands::publish(&mut conn, self.channel.as_str(), payload).await;
        }
    }

    /// Subscribe and fan events out until stopped. Every node publishes to
    /// the same channel; each node applies every event idempotently (Deliver
    /// only reaches peers the publisher could not reach locally).
    pub async fn run_bus(self: Arc<Self>, mut stop: tokio::sync::watch::Receiver<bool>) {
        let client = match redis::Client::open(self.store.redis_url()) {
            Ok(client) => client,
            Err(error) => {
                tracing::error!(%error, "bus subscriber cannot open redis");
                return;
            }
        };
        loop {
            if *stop.borrow() {
                return;
            }
            let attempt = async {
                let mut pubsub = client.get_async_pubsub().await?;
                pubsub.subscribe(self.channel.as_str()).await?;
                let mut stream = pubsub.on_message();
                while let Some(message) = stream.next().await {
                    let payload: String = message.get_payload()?;
                    match serde_json::from_str::<BusEvent>(&payload) {
                        Ok(event) => self.apply_bus_event(event).await,
                        Err(error) => tracing::debug!(%error, %payload, "bus frame failed to parse"),
                    }
                    if *stop.borrow() {
                        return Ok::<(), anyhow::Error>(());
                    }
                }
                Ok::<(), anyhow::Error>(())
            };
            match attempt.await {
                Ok(()) => return,
                Err(error) => {
                    tracing::warn!(%error, "bus subscription dropped; reconnecting in 2s");
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
            }
        }
    }

    async fn apply_bus_event(&self, event: BusEvent) {
        match event {
            BusEvent::Deliver { token, event, args, attachment } => {
                if let Some(peer) = self.controller_peer(&token) {
                    peer.send_event(&event, &args, attachment.as_deref());
                }
            }
            BusEvent::HostBinary { device_id, event, header, bytes } => {
                tracing::debug!(%device_id, %event, found = self.host_peer(&device_id).is_some(), "bus host binary");
                if let Some(host) = self.host_peer(&device_id) {
                    host.send_event(&event, &[header], Some(&bytes));
                }
            }
            BusEvent::DeviceDelta { device_id, kind, fields } => {
                tracing::debug!(%device_id, %kind, "bus device delta");
                if kind == INTERNAL_EVENT_MARKER {
                    // Raw delivery envelope: resolve the event/args and send.
                    if let Some(deliver) = fields.get("__deliver") {
                        let event = deliver.get("event").and_then(Value::as_str).unwrap_or("");
                        let args = deliver.get("args").cloned().unwrap_or_else(|| json!([]));
                        if let Some(host) = self.host_peer(&device_id) {
                            host.send_event(
                                event,
                                args.as_array().map(Vec::as_slice).unwrap_or(&[]),
                                None,
                            );
                        }
                    }
                    return;
                }
                // Every online presence room receives every account device
                // delta: the Rust client's presence handler consumes all of
                // them (single-owner account model).
                let payload = merge_device_id(&fields, &device_id);
                let args = json!({"type": kind, "data": payload});
                for host in self.local_hosts() {
                    host.send_event("bmsg_push", &[args.clone()], None);
                }
            }
        }
    }

    /// Deliver an event to the controller room owning this token on any node.
    pub async fn deliver_by_token(&self, token: &str, event: &str, args: Vec<Value>) {
        self.deliver_by_token_with_attachment(token, event, args, None).await;
    }

    pub async fn deliver_by_token_with_attachment(
        &self,
        token: &str,
        event: &str,
        args: Vec<Value>,
        attachment: Option<Vec<u8>>,
    ) {
        if let Some(peer) = self.controller_peer(token) {
            peer.send_event(event, &args, attachment.as_deref());
            return;
        }
        self.publish(BusEvent::Deliver {
            token: token.to_owned(),
            event: event.to_owned(),
            args,
            attachment,
        })
        .await;
    }


    /// Deliver a Socket.IO event to a device's presence room wherever it
    /// lives: locally first, then over the bus by publishing to the node
    /// that owns the presence key. Cross-node host delivery rides the same
    /// bus channel; the owning node's subscriber re-resolves the device.
    pub async fn deliver_to_device(&self, device: &str, event: &str, args: Vec<Value>) {
        if let Some(peer) = self.host_peer(device) {
            peer.send_event(event, &args, None);
            return;
        }
        // Publish with the device encoded so every node can try locally.
        self.publish(BusEvent::DeviceDelta {
            device_id: device.to_owned(),
            kind: INTERNAL_EVENT_MARKER.into(),
            fields: json!({"__deliver": {"event": event, "args": args}}),
        })
        .await;
    }

    /// Fan a device delta to the device's presence room on every node
    /// (exactly one node holds it; the rest ignore the marker).
    pub async fn broadcast_device_delta(&self, device_id: &str, kind: &str, fields: Value) {
        let payload = merge_device_id(&fields, device_id);
        let args = json!({"type": kind, "data": payload});
        for host in self.local_hosts() {
            host.send_event("bmsg_push", &[args.clone()], None);
        }
        self.publish(BusEvent::DeviceDelta {
            device_id: device_id.to_owned(),
            kind: kind.to_owned(),
            fields,
        })
        .await;
    }
}

/// DeviceDelta data must always carry device_id (DeviceChange::parse requires
/// it); merge it once here for both local and remote fan-outs.
fn merge_device_id(fields: &Value, device_id: &str) -> Value {
    let mut payload = fields.clone();
    if let Some(object) = payload.as_object_mut() {
        object.insert("device_id".into(), json!(device_id));
    }
    payload
}

/// Internal marker distinguishing raw delivery envelopes from real deltas.
pub const INTERNAL_EVENT_MARKER: &str = "::internal-deliver::";
