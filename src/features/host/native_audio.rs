//! Optional authenticated-peer extension. RTP remains warm for immediate fallback.
use crate::media::audio::{
    encoder::{Config, create_encoder},
    sender::{BLOCK, Frame},
};
use bytes::Bytes;
use crossbeam_queue::ArrayQueue;
use std::sync::atomic::{AtomicBool, Ordering};
use std::{
    sync::{Arc, Mutex, Weak},
    time::{Duration, Instant},
};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;
use webrtc::data_channel::{RTCDataChannel, data_channel_state::RTCDataChannelState};
pub(crate) const CHANNEL: &str = "OPENUUYC_AUDIO_V1";
const MAX_BUFFER: usize = 2048;
struct Binding {
    channel: Weak<RTCDataChannel>,
    epoch: u32,
    revision: u64,
    codec: u8,
    sequence: u32,
    feedback: Instant,
    source: u64,
    encoder: Option<opusic_c::Encoder>,
    bitrate: u32,
}
pub(crate) struct NativeAudio {
    binding: Mutex<Option<Binding>>,
    frames: ArrayQueue<Frame>,
    wake: Notify,
    started: AtomicBool,
    wanted: AtomicBool,
}
impl Default for NativeAudio {
    fn default() -> Self {
        Self {
            binding: Mutex::new(None),
            frames: ArrayQueue::new(16),
            wake: Notify::new(),
            started: AtomicBool::new(false),
            wanted: AtomicBool::new(false),
        }
    }
}
fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}
pub(crate) fn pcm_packet(
    epoch: u32,
    sequence: u32,
    timestamp: u32,
    codec: u8,
    payload: &[u8],
    reset: bool,
) -> Bytes {
    let mut b = Vec::with_capacity(24 + payload.len());
    b.extend_from_slice(b"OUA1");
    b.push(1);
    b.push(codec | if reset { 0x80 } else { 0 });
    b.extend_from_slice(&(BLOCK as u16).to_le_bytes());
    b.extend_from_slice(&epoch.to_le_bytes());
    b.extend_from_slice(&sequence.to_le_bytes());
    b.extend_from_slice(&timestamp.to_le_bytes());
    b.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    b.extend_from_slice(payload);
    Bytes::from(b)
}
impl NativeAudio {
    pub(crate) fn bind(
        self: &Arc<Self>,
        channel: Arc<RTCDataChannel>,
        cancel: CancellationToken,
        permitted: impl Fn(&Frame) -> Option<crate::media::audio::encoder::Quality>
        + Send
        + Sync
        + 'static,
    ) {
        if !self.started.swap(true, Ordering::AcqRel) {
            let target = self.clone();
            let stopping = cancel.clone();
            tokio::spawn(async move {
                loop {
                    if stopping.is_cancelled() {
                        break;
                    }
                    let wake = target.wake.notified();
                    tokio::pin!(wake);
                    wake.as_mut().enable();
                    let Some(frame) = target.frames.pop() else {
                        tokio::select! {_=stopping.cancelled()=>break,_=wake=>{}};
                        continue;
                    };
                    let Some(quality) = permitted(&frame) else {
                        continue;
                    };
                    target
                        .send(&frame, quality, || permitted(&frame).is_some())
                        .await;
                }
                target.wanted.store(false, Ordering::Release);
            });
        }

        let target = self.clone();
        let weak = Arc::downgrade(&channel);
        channel.on_message(Box::new(move |message| {
            let target = target.clone();
            let weak = weak.clone();
            Box::pin(async move {
                if message.data.len() > 1024 {
                    return;
                }
                let Ok(v) = serde_json::from_slice::<serde_json::Value>(&message.data) else {
                    return;
                };
                let Some(c) = weak.upgrade() else { return };
                let epoch = v["epoch"]
                    .as_u64()
                    .filter(|v| *v > 0 && *v <= u32::MAX as u64)
                    .map(|v| v as u32);
                match v["type"].as_str() {
                    Some("start") if epoch.is_some() && v["v"].as_u64() == Some(1) => {
                        let codec = match v["codec"].as_str() {
                            Some("pcm_s16le") => 0,
                            Some("opus_lowdelay") => 1,
                            _ => return,
                        };
                        target.wanted.store(true, Ordering::Release);
                        let mut binding = lock(&target.binding);
                        if let Some(old) = binding.as_mut() {
                            if old.channel.ptr_eq(&weak) && old.epoch == epoch.unwrap() {
                                let revision = v["revision"].as_u64().unwrap_or(0);
                                if revision < old.revision {
                                    return;
                                }
                                old.revision = revision;
                                if old.codec != codec {
                                    old.codec = codec;
                                    old.encoder = None;
                                    old.source = u64::MAX;
                                }
                                old.feedback = Instant::now();
                                return;
                            }
                        }
                        *binding = Some(Binding {
                            channel: Arc::downgrade(&c),
                            epoch: epoch.unwrap(),
                            revision: v["revision"].as_u64().unwrap_or(0),
                            codec,
                            sequence: 0,
                            feedback: Instant::now(),
                            source: u64::MAX,
                            encoder: None,
                            bitrate: 128_000,
                        });
                    }
                    Some("feedback") => {
                        if let Some(b) = lock(&target.binding).as_mut() {
                            if b.channel.ptr_eq(&weak) && Some(b.epoch) == epoch {
                                let revision = v["revision"].as_u64().unwrap_or(0);
                                if revision < b.revision {
                                    return;
                                }
                                let codec = match v["codec"].as_str() {
                                    Some("pcm_s16le") => 0,
                                    Some("opus_lowdelay") => 1,
                                    _ => return,
                                };
                                b.revision = revision;
                                if b.codec != codec {
                                    b.codec = codec;
                                    b.encoder = None;
                                    b.source = u64::MAX;
                                }
                                b.feedback = Instant::now();
                                target.wanted.store(true, Ordering::Release);
                            }
                        }
                    }
                    Some("stop") => {
                        let mut b = lock(&target.binding);
                        if b.as_ref()
                            .is_some_and(|b| b.channel.ptr_eq(&weak) && Some(b.epoch) == epoch)
                        {
                            *b = None;
                            target.wanted.store(false, Ordering::Release);
                        }
                    }
                    _ => {}
                }
            })
        }));
        let target = self.clone();
        let weak = Arc::downgrade(&channel);
        tokio::spawn(async move {
            loop {
                tokio::select! {_=cancel.cancelled()=>break,_=tokio::time::sleep(Duration::from_secs(1))=>{}}
                let Some(c) = weak.upgrade() else { break };
                if c.ready_state() == RTCDataChannelState::Closed {
                    break;
                }
                if c.ready_state() == RTCDataChannelState::Open && c.buffered_amount().await < 1024
                {
                    let hello=serde_json::json!({"type":"hello","v":1,"rate":48000,"channels":2,"frames":BLOCK,"codecs":["pcm_s16le","opus_lowdelay"]}).to_string();
                    let _ =
                        tokio::time::timeout(Duration::from_millis(5), c.send_text(hello)).await;
                }
            }
            let mut b = lock(&target.binding);
            if b.as_ref().is_some_and(|b| b.channel.ptr_eq(&weak)) {
                *b = None;
                target.wanted.store(false, Ordering::Release);
            }
        });
    }
    pub(crate) fn enqueue(&self, frame: Frame, retained: usize) {
        if !self.wanted.load(Ordering::Acquire) {
            return;
        }
        while self.frames.len() >= retained.clamp(1, 16) {
            if self.frames.pop().is_none() {
                break;
            }
        }
        self.frames.force_push(frame);
        self.wake.notify_one();
    }
    pub(crate) fn bandwidth(&self) -> u32 {
        lock(&self.binding)
            .as_ref()
            .filter(|b| b.feedback.elapsed() < Duration::from_millis(1800))
            .map_or(0, |b| {
                if b.codec == 0 {
                    2_000_000
                } else {
                    b.bitrate + 400_000
                }
            })
    }
    pub(crate) async fn send(
        &self,
        frame: &Frame,
        quality: crate::media::audio::encoder::Quality,
        permitted: impl Fn() -> bool + Send + Sync,
    ) {
        let channel = {
            let b = lock(&self.binding);
            b.as_ref()
                .filter(|b| b.feedback.elapsed() < Duration::from_millis(1800))
                .and_then(|b| b.channel.upgrade())
        };
        let Some(channel) = channel else { return };
        if channel.ready_state() != RTCDataChannelState::Open
            || channel.buffered_amount().await > MAX_BUFFER
        {
            return;
        }
        let packet = {
            let mut guard = lock(&self.binding);
            let Some(b) = guard.as_mut() else { return };
            if !b.channel.ptr_eq(&Arc::downgrade(&channel))
                || b.feedback.elapsed() > Duration::from_millis(1800)
            {
                return;
            }
            let reset = b.source != frame.generation;
            b.source = frame.generation;
            let mut payload = Vec::with_capacity(480);
            if b.codec == 0 {
                for s in frame.samples {
                    let s = if s.is_finite() {
                        s.clamp(-1.0, 1.0)
                    } else {
                        0.0
                    };
                    payload.extend_from_slice(&((s * 32767.0).round() as i16).to_le_bytes());
                }
            } else {
                if reset {
                    b.encoder = None;
                }
                if b.encoder.is_none() {
                    let Ok(config) = Config::negotiated("stereo=1", Some(3)) else {
                        return;
                    };
                    let Ok(encoder) = create_encoder(config.quality(quality)) else {
                        return;
                    };
                    b.encoder = Some(encoder);
                }
                let bitrate = quality.kbps * 1000;
                if b.bitrate != bitrate {
                    if b.encoder
                        .as_mut()
                        .unwrap()
                        .set_bitrate(opusic_c::Bitrate::Value(bitrate))
                        .is_err()
                    {
                        return;
                    }
                    b.bitrate = bitrate;
                }
                let mut encoded = [0u8; 1200];
                let Ok(n) = b
                    .encoder
                    .as_mut()
                    .unwrap()
                    .encode_float_to_slice(&frame.samples, &mut encoded)
                else {
                    return;
                };
                payload.extend_from_slice(&encoded[..n]);
            }
            b.sequence = b.sequence.wrapping_add(1);
            pcm_packet(
                b.epoch,
                b.sequence,
                frame.timestamp,
                b.codec,
                &payload,
                reset,
            )
        };
        if !permitted() {
            return;
        }
        // Never stall or build a second backlog in front of warm RTP fallback.
        let _ = tokio::time::timeout(Duration::from_millis(1), channel.send(&packet)).await;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_queue_requires_negotiation_and_keeps_only_recent_frames() {
        let audio = NativeAudio::default();
        let mut frame = Frame {
            samples: [0.0; BLOCK * 2],
            timestamp: 0,
            generation: 1,
            created: Instant::now(),
        };
        audio.enqueue(frame, 3);
        assert!(audio.frames.is_empty());
        audio.wanted.store(true, Ordering::Release);
        for i in 0..10 {
            frame.timestamp = i;
            audio.enqueue(frame, 3);
        }
        let mut retained = Vec::new();
        while let Some(f) = audio.frames.pop() {
            retained.push(f.timestamp)
        }
        assert_eq!(retained, vec![7, 8, 9]);
    }
    #[test]
    fn native_audio_packet_has_bounded_explicit_layout() {
        let p = pcm_packet(7, 9, 120, 0, &[0; 480], true);
        assert_eq!(&p[..4], b"OUA1");
        assert_eq!(p.len(), 504);
        assert_eq!(p[5], 0x80);
        assert_eq!(u16::from_le_bytes(p[6..8].try_into().unwrap()), 120);
        assert_eq!(u32::from_le_bytes(p[8..12].try_into().unwrap()), 7);
        assert_eq!(u32::from_le_bytes(p[20..24].try_into().unwrap()), 480);
    }
}
