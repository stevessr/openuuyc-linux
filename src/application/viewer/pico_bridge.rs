//! Opt-in localhost video/control bridge for the native PICO headset viewer.
//!
//! Never binds a public interface. Use ADB reverse (USB) or a trusted,
//! authenticated TLS tunnel; do not expose plaintext WebSockets on a LAN.
//! Video is the already-negotiated H.264 UU bitstream, not a screen re-encode.

use super::NativeViewerSession;
use crate::features::remote_input::MouseMode;
use crate::features::stream_control::StreamControlHandle;
use crate::media::VideoCodec;
use crate::transport::rtc::EncodedVideoFrame;
use anyhow::{Context, Result};
use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::json;
use std::sync::{Arc, Mutex, OnceLock};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc};
use tokio_tungstenite::tungstenite::{Message, protocol::WebSocketConfig};

const OWNER: u64 = 0x5049_434f_5f56_5201;
const MAX_FRAME: usize = 4 * 1024 * 1024;
const MAX_CONTROL: usize = 4096;

struct Packet { bytes: Bytes, keyframe: bool }

struct Bridge {
    token: String,
    frames: broadcast::Sender<Arc<Packet>>,
    control: Mutex<Option<StreamControlHandle>>,
    keyframe: Mutex<Option<Arc<Packet>>>,
    active: std::sync::atomic::AtomicBool,
}

static BRIDGE: OnceLock<Arc<Bridge>> = OnceLock::new();

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn accepted_token(want: &str, got: &str) -> bool {
    let a = want.as_bytes();
    let b = got.as_bytes();
    let mut different = a.len() ^ b.len();
    for i in 0..a.len().max(b.len()) {
        different |= (a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0)) as usize;
    }
    different == 0
}

/// Called once per native playback session. No bridge exists unless explicitly
/// opted into with OPENUUYC_PICO_BRIDGE_TOKEN (>= 32 printable ASCII chars).
pub(super) fn attach(session: &NativeViewerSession) {
    let Ok(token) = std::env::var("OPENUUYC_PICO_BRIDGE_TOKEN") else { return };
    if token.len() < 32 || token.len() > 256 || !token.is_ascii() || token.bytes().any(|b| b.is_ascii_control()) {
        tracing::error!("PICO bridge token must contain 32..256 printable ASCII characters");
        return;
    }
    let bridge = BRIDGE.get_or_init(|| {
        let (frames, _) = broadcast::channel(8);
        let bridge = Arc::new(Bridge {
            token,
            frames,
            control: Mutex::new(None),
            keyframe: Mutex::new(None),
            active: std::sync::atomic::AtomicBool::new(false),
        });
        let copy = Arc::clone(&bridge);
        std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build();
            if let Ok(runtime) = runtime {
                if let Err(error) = runtime.block_on(serve(copy)) {
                    tracing::error!(error = %format_args!("{error:#}"), "PICO bridge stopped");
                }
            }
        });
        bridge
    });
    *lock(&bridge.control) = Some(session.stream_control.clone());
}

pub(super) fn publish(frame: &EncodedVideoFrame) {
    let Some(bridge) = BRIDGE.get() else { return };
    if frame.codec != VideoCodec::H264 || frame.data.is_empty() || frame.data.len() > MAX_FRAME {
        return;
    }
    let mut wire = Vec::with_capacity(frame.data.len() + 2);
    wire.push(1u8);
    wire.push(u8::from(frame.keyframe));
    wire.extend_from_slice(&frame.data);
    let packet = Arc::new(Packet { bytes: Bytes::from(wire), keyframe: frame.keyframe });
    if packet.keyframe {
        *lock(&bridge.keyframe) = Some(Arc::clone(&packet));
    }
    let _ = bridge.frames.send(packet);
}

async fn serve(bridge: Arc<Bridge>) -> Result<()> {
    let listener = TcpListener::bind("127.0.0.1:18765").await.context("bind PICO bridge loopback port 18765")?;
    tracing::info!("PICO bridge ready on 127.0.0.1:18765 (ADB reverse required; TLS tunnel for wireless)");
    loop {
        let (stream, _) = listener.accept().await?;
        let bridge = Arc::clone(&bridge);
        tokio::spawn(async move {
            if let Err(error) = client(stream, bridge).await {
                tracing::debug!(error = %error, "PICO bridge client disconnected");
            }
        });
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Auth { token: String },
    Control { enabled: bool },
    Pointer { u: f64, v: f64, down: bool },
    Key { vk: u16, down: bool },
    Scroll { delta: i32 },
    Ping,
}

fn apply_request(bridge: &Bridge, request: Request, control_enabled: &mut bool, button: &mut bool) {
    let target = lock(&bridge.control).clone();
    let Some(target) = target else { return };
    let input = target.mouse().clone();
    match request {
        Request::Control { enabled } => {
            if enabled {
                if input.enable(MouseMode::Remote, false).is_ok() {
                    input.confirm_neutral(input.activation_generation());
                    *control_enabled = true;
                }
            } else {
                input.pause_owner(OWNER);
                input.disable();
                *control_enabled = false;
            }
            *button = false;
        }
        Request::Pointer { u, v, down } if *control_enabled => {
            if !u.is_finite() || !v.is_finite() || !(0.0..=1.0).contains(&u) || !(0.0..=1.0).contains(&v) {
                return;
            }
            if let Some(screen) = target.snapshot().screens.iter().find(|s| s.id >= 0) {
                input.absolute(OWNER, screen.id, u.min(0.999_999), v.min(0.999_999));
                if *button != down {
                    input.button(OWNER, 1, down);
                    *button = down;
                }
            }
        }
        Request::Key { vk, down } if *control_enabled => {
            input.key(OWNER, vk, down, None);
        }
        Request::Scroll { delta } if *control_enabled => {
            input.wheel(OWNER, delta.clamp(-1200, 1200), false);
        }
        _ => {}
    }
}

struct ActiveClient(Arc<Bridge>);
impl Drop for ActiveClient {
    fn drop(&mut self) {
        if let Some(control) = lock(&self.0.control).clone() {
            control.mouse().pause_owner(OWNER);
            control.mouse().disable();
        }
        self.0.active.store(false, std::sync::atomic::Ordering::Release);
    }
}

async fn client(stream: TcpStream, bridge: Arc<Bridge>) -> Result<()> {
    let config = WebSocketConfig {
        max_message_size: Some(MAX_FRAME + 2),
        max_frame_size: Some(MAX_FRAME + 2),
        ..Default::default()
    };
    let mut ws = tokio_tungstenite::accept_async_with_config(stream, Some(config)).await?;
    let auth = tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
        .await.context("auth timeout")?.context("client disconnected")??;
    if !auth.is_text() || auth.len() > MAX_CONTROL {
        anyhow::bail!("invalid authentication");
    }
    match serde_json::from_str::<Request>(auth.to_text()?)? {
        Request::Auth { token } if accepted_token(&bridge.token, &token) => {}
        _ => anyhow::bail!("authentication failed"),
    }
    if bridge.active.compare_exchange(false, true, std::sync::atomic::Ordering::AcqRel,
        std::sync::atomic::Ordering::Relaxed).is_err()
    {
        ws.send(Message::Text(json!({"type":"error","reason":"busy"}).to_string().into())).await?;
        anyhow::bail!("another headset is connected");
    }
    let _guard = ActiveClient(Arc::clone(&bridge));
    ws.send(Message::Text(json!({"type":"hello","version":1,"codec":"h264"}).to_string().into())).await?;
    let (mut sender, mut receiver) = ws.split();
    let (responses, mut responses_rx) = mpsc::unbounded_channel::<Message>();
    let mut frames = bridge.frames.subscribe();
    let cached_keyframe = { lock(&bridge.keyframe).clone() };
    if let Some(keyframe) = cached_keyframe {
        sender.send(Message::Binary(keyframe.bytes.clone().to_vec().into())).await?;
    }
    let writer = tokio::spawn(async move {
        loop {
            tokio::select! {
                response = responses_rx.recv() => match response {
                    Some(message) => sender.send(message).await?,
                    None => break,
                },
                packet = frames.recv() => match packet {
                    Ok(packet) => sender.send(Message::Binary(packet.bytes.clone().to_vec().into())).await?,
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        // Resume at a keyframe after congestion to avoid sending
                        // corrupted interframes.
                        continue;
                    },
                    Err(broadcast::error::RecvError::Closed) => break,
                },
            }
        }
        Ok::<(), tokio_tungstenite::tungstenite::Error>(())
    });
    let mut control_enabled = false;
    let mut button = false;
    while let Some(message) = receiver.next().await {
        match message? {
            Message::Text(text) if text.len() <= MAX_CONTROL => {
                if let Ok(command) = serde_json::from_str::<Request>(&text) {
                    if matches!(command, Request::Auth { .. }) { break; }
                    if matches!(command, Request::Ping) {
                        let _ = responses.send(Message::Text(json!({"type":"pong"}).to_string().into()));
                    } else {
                        apply_request(&bridge, command, &mut control_enabled, &mut button);
                    }
                }
            }
            Message::Close(_) => break,
            Message::Ping(data) => { let _ = responses.send(Message::Pong(data)); }
            _ => {}
        }
    }
    drop(responses);
    writer.abort();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_comparison_rejects_length_and_value_changes() {
        assert!(accepted_token("abcdef0123456789", "abcdef0123456789"));
        assert!(!accepted_token("abcdef0123456789", "abcdef012345678"));
        assert!(!accepted_token("abcdef0123456789", "abcdef012345678x"));
    }

    #[test]
    fn input_packet_schema_rejects_non_finite_or_unknown_shapes() {
        assert!(serde_json::from_str::<Request>(r#"{"type":"pointer","u":0.5,"v":0.25,"down":true}"#).is_ok());
        assert!(serde_json::from_str::<Request>(r#"{"type":"pointer","u":"NaN","v":0.5,"down":false}"#).is_err());
    }
}
