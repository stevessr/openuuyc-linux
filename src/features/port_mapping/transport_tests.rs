//! Real local ICE/DTLS/SCTP coverage, without account credentials or devices.
use super::*;
use webrtc::{api::APIBuilder, peer_connection::configuration::RTCConfiguration};

#[tokio::test]
async fn real_data_channel_preserves_datagrams_and_skips_expired_submission() -> Result<()> {
    let api = APIBuilder::new().build();
    let offer = api.new_peer_connection(RTCConfiguration::default()).await?;
    let answer = api.new_peer_connection(RTCConfiguration::default()).await?;
    let result = tokio::time::timeout(Duration::from_secs(30), async {
        let receiver = Arc::new(Transport::default());
        let (tx, mut packets) = mpsc::channel(8);
        receiver.register((1, 1), Route {
            sender: tx,
            budget: Arc::new(Semaphore::new(131_072)),
            stop: CancellationToken::new(),
            remote_fin: Arc::default(),
            fault: Arc::default(),
        })?;
        let (remote_tx, mut remote_rx) = mpsc::channel(1);
        let incoming = receiver.clone();
        answer.on_data_channel(Box::new(move |channel| {
            let receiver = incoming.clone();
            let remote_tx = remote_tx.clone();
            Box::pin(async move {
                channel.on_message(Box::new(move |message| {
                    let receiver = receiver.clone();
                    Box::pin(async move {
                        receiver.receive(&message.data).expect("valid forwarding frame");
                    })
                }));
                let _ = remote_tx.send(channel).await;
            })
        }));
        let channel = offer.create_data_channel("FILE", None).await?;
        let mut gathered = offer.gathering_complete_promise().await;
        offer.set_local_description(offer.create_offer(None).await?).await?;
        gathered.recv().await;
        answer.set_remote_description(offer.local_description().await.unwrap()).await?;
        let mut gathered = answer.gathering_complete_promise().await;
        answer.set_local_description(answer.create_answer(None).await?).await?;
        gathered.recv().await;
        offer.set_remote_description(answer.local_description().await.unwrap()).await?;
        let remote = remote_rx.recv().await.context("missing remote channel")?;
        while channel.ready_state() != RTCDataChannelState::Open
            || remote.ready_state() != RTCDataChannelState::Open {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        let sender = Transport::default();
        sender.bind(&channel);
        let guard = sender.send_lock.lock().await;
        let submitted = sender.send_until(1, 1, 18, Bytes::from_static(b"expired"),
            Some(tokio::time::Instant::now() + Duration::from_millis(10))).await?;
        assert!(!submitted);
        drop(guard);
        // FIN acts as an ordered barrier: an incorrectly submitted stale packet
        // would appear before one of these expected messages, failing the test.
        for payload in [Bytes::new(), Bytes::from_static(b"/tracking\0\0\0"),
            Bytes::from(vec![0xa5; 65_507])] {
            sender.send(1, 1, 18, payload.clone()).await?;
            let received = packets.recv().await.context("missing datagram")?;
            assert_eq!(received.frame.kind, 18);
            assert_eq!(received.frame.payload, payload);
        }
        sender.send(1, 1, 19, Bytes::new()).await?;
        assert_eq!(packets.recv().await.context("missing FIN")?.frame.kind, 19);
        sender.close();
        receiver.close();
        Ok::<_, anyhow::Error>(())
    }).await.context("local WebRTC test timed out");
    // Also clean up ICE tasks when a fallible operation or timeout fails.
    let close_offer = offer.close().await;
    let close_answer = answer.close().await;
    result??;
    close_offer?;
    close_answer?;
    Ok(())
}
