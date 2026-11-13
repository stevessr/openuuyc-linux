//! Device-room `transport_rpc` delivery.
//!
//! `/api/v1/transport/{device}/rpc/request` is not answered on the HTTP call:
//! the server pushes `transport_rpc` to the target device's online room and
//! the caller waits for that push. A device holds exactly one online room, so
//! opening a second one releases the first (signaling 2005) and the peer can
//! no longer deliver its reply. Callers that need a reply therefore subscribe
//! to the room that is already online instead of creating their own.
//!
//! The inbox forwards every `transport_rpc` push; matching is the caller's
//! job because several features (power, assistance, share approval) share the
//! same delivery path.

use serde_json::Value;
use std::sync::Arc;
use tokio::sync::{broadcast, watch};

const PUSH_CAPACITY: usize = 32;

#[derive(Clone)]
pub(crate) struct Inbox(Arc<State>);

struct State {
    pushes: broadcast::Sender<Value>,
    online: watch::Sender<bool>,
}

impl Default for Inbox {
    fn default() -> Self {
        Self(Arc::new(State {
            pushes: broadcast::channel(PUSH_CAPACITY).0,
            online: watch::channel(false).0,
        }))
    }
}

impl Inbox {
    /// Called by the owner of the device's online room for every push it
    /// receives. Returns whether this was a transport RPC.
    pub(crate) fn push(&self, push: &Value) -> bool {
        if push.get("type").and_then(Value::as_str) != Some("transport_rpc") {
            return false;
        }
        // No subscriber is not an error: the room also delivers RPCs to
        // features that handle them outside this inbox.
        let _ = self.0.pushes.send(push.clone());
        true
    }

    pub(crate) fn subscribe(&self) -> broadcast::Receiver<Value> {
        self.0.pushes.subscribe()
    }

    /// Whether this process currently owns a device room that can receive RPC
    /// replies. A caller must create a room only when this is false.
    pub(crate) fn online(&self) -> bool {
        *self.0.online.borrow()
    }

    pub(crate) fn online_events(&self) -> watch::Receiver<bool> {
        self.0.online.subscribe()
    }

    pub(crate) fn set_online(&self, online: bool) {
        self.0.online.send_replace(online);
    }
}

#[cfg(test)]
mod tests {
    use super::Inbox;
    use serde_json::json;

    #[test]
    fn only_transport_rpc_pushes_enter_the_inbox() {
        let inbox = Inbox::default();
        assert!(inbox.push(&json!({"type":"transport_rpc","data":{"p":"shutdown"}})));
        assert!(!inbox.push(&json!({"type":"device_info_changed","data":{}})));
        assert!(!inbox.push(&json!({})));
    }

    #[tokio::test]
    async fn subscribers_receive_every_transport_rpc() {
        let inbox = Inbox::default();
        let mut first = inbox.subscribe();
        let mut second = inbox.subscribe();
        let push = json!({"type":"transport_rpc","data":{"p":"android_control_result"}});
        assert!(inbox.push(&push));
        assert_eq!(first.recv().await.unwrap(), push);
        assert_eq!(second.recv().await.unwrap(), push);
    }

    #[tokio::test]
    async fn online_state_is_observable() {
        let inbox = Inbox::default();
        assert!(!inbox.online());
        let mut events = inbox.online_events();
        inbox.set_online(true);
        events.changed().await.unwrap();
        assert!(*events.borrow());
        assert!(inbox.online());
        inbox.set_online(false);
        events.changed().await.unwrap();
        assert!(!inbox.online());
    }
}
