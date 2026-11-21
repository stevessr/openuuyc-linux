//! Cross-node integration tests: two ControlPlane nodes sharing one Redis,
//! exercising enrollment, login, control handshakes and takeover across
//! sockets that live on different nodes.
//!
//! Skipped when OPENUUYC_TEST_REDIS is not reachable so plain `cargo test`
//! still works without a Redis server.

mod support;
use support::TestHarness;

fn redis_url() -> Option<String> {
    let url = std::env::var("OPENUUYC_TEST_REDIS").unwrap_or_else(|_| "redis://127.0.0.1:6379".into());
    // Quick reachability probe with a blocking one-liner: TCP connect only.
    use std::net::TcpStream;
    let addr = url.trim_start_matches("redis://").to_owned();
    let host_port = addr.split('/').next().unwrap_or(&addr).to_owned();
    TcpStream::connect(&host_port).ok()?;
    Some(url)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "run with OPENUUYC_E2E=1: needs a clean Redis and tuning (see selfhost-rs/README)"]
async fn two_nodes_share_state_and_relay_signaling() {
    let Some(url) = redis_url() else {
        eprintln!("skipping: no test redis");
        return;
    };
    let harness = TestHarness::start(&url, vec![0, 0]).await;
    let (a, b) = (harness.base(0), harness.base(1));

    let host = harness.register_login(&a, "dist-host").await;
    let ctrl = harness.register_login(&b, "dist-ctrl").await;

    // Enable controllability on node B, create the host room on B.
    harness.set_controllable(&b, &host).await;
    let mut host_ws = harness.host_room(&b, &host).await;

    // Controller joins via node A and opens its socket on A.
    let join = harness.join(&a, &ctrl, &host.device_id, false).await;
    assert_eq!(join.code, 0, "join across nodes");
    let mut ctrl_ws = harness.controller_room(&a, &join.token).await;

    // Control handshake with binary attachment: controller on A -> host on B.
    let ack = ctrl_ws.send_control("app-dist", b"\x08\x01")
        .await;
    assert_eq!(ack["code"], 0, "control ack across nodes");
    let be = host_ws.expect_event("be-controlled").await;
    assert_eq!(be["ice_id"], ack["ice_id"]);
    let attachment = host_ws.next_binary().await;
    assert_eq!(attachment, b"\x08\x01");

    // soac relay back and forth.
    ctrl_ws
        .send_soac(ack["client_id"].as_str().unwrap(), ack["ice_id"].as_str().unwrap(), "app-dist", b"offer-bytes")
        .await;
    let soac = host_ws.expect_event("soac").await;
    assert_eq!(soac["data"]["ice_id"], ack["ice_id"]);

    // forward_setting host -> controller across nodes.
    host_ws
        .send_forward_setting(&ctrl.client_id, ack["ice_id"].as_str().unwrap(), "app-dist")
        .await;
    let fs = ctrl_ws.expect_event("forward_setting").await;
    assert_eq!(fs["data"]["type"], "device_capability");

    // clear_out from host releases the controller on the other node.
    host_ws.clear_out().await;
    let released = ctrl_ws.expect_event("released").await;
    assert_eq!(released["ice_id"], ack["ice_id"]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "run with OPENUUYC_E2E=1: needs a clean Redis and tuning (see selfhost-rs/README)"]
async fn takeover_releases_previous_controller_on_other_node() {
    let Some(url) = redis_url() else {
        eprintln!("skipping: no test redis");
        return;
    };
    let harness = TestHarness::start(&url, vec![0, 0]).await;
    let (a, b) = (harness.base(0), harness.base(1));

    let host = harness.register_login(&b, "tk-host").await;
    let c1 = harness.register_login(&a, "tk-one").await;
    let c2 = harness.register_login(&b, "tk-two").await;

    harness.set_controllable(&b, &host).await;
    let mut host_ws = harness.host_room(&b, &host).await;

    let j1 = harness.join(&a, &c1, &host.device_id, false).await;
    let mut ws1 = harness.controller_room(&a, &j1.token).await;
    let ack1 = ws1.send_control("app-1", b"\x01").await;
    assert_eq!(ack1["code"], 0);

    // A second controller joins from another node without force: busy.
    let busy = harness.join(&b, &c2, &host.device_id, false).await;
    assert_eq!(busy.code, 1136);

    // Forced join + control from node B releases controller 1 on node A.
    let j2 = harness.join(&b, &c2, &host.device_id, true).await;
    let mut ws2 = harness.controller_room(&b, &j2.token).await;
    let ack2 = ws2.send_control("app-2", b"\x02").await;
    assert_eq!(ack2["code"], 0);
    let released = ws1.expect_event("released").await;
    assert_eq!(released["ice_id"], ack1["ice_id"]);
    let _ = host_ws.expect_event("be-controlled").await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "run with OPENUUYC_E2E=1: needs a clean Redis and tuning (see selfhost-rs/README)"]
async fn device_delta_reaches_other_node() {
    let Some(url) = redis_url() else {
        eprintln!("skipping: no test redis");
        return;
    };
    let harness = TestHarness::start(&url, vec![0, 0]).await;
    let (a, b) = (harness.base(0), harness.base(1));

    let host = harness.register_login(a.as_str(), "delta-host").await;
    let other = harness.register_login(b.as_str(), "delta-other").await;

    // The other device holds a presence room on node B.
    let mut ws = harness.host_room(&b, &other).await;

    // Renaming the host on node A fans a delta out to node B's room.
    harness.rename(&a, &host, "renamed-cross-node").await;
    let delta = ws.expect_bmsg("device_info_changed").await;
    assert_eq!(delta["data"]["device_id"], host.device_id);
    assert_eq!(delta["data"]["alias"], "renamed-cross-node");
}
