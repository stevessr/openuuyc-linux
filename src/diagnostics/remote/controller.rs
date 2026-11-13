use super::*;
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use tokio::io::AsyncWriteExt;
use tokio::sync::mpsc;

#[derive(Clone, Copy, Default)]
pub(crate) enum Phase {
    #[default]
    Connecting,
    Preparing,
    Downloading,
    Saving,
}
#[derive(Clone, Default)]
pub(crate) struct Snapshot {
    pub phase: Phase,
    pub supported: bool,
    pub allowed: bool,
    pub busy: bool,
    pub cancelled: bool,
    pub progress: bundle::Progress,
    pub path: Option<PathBuf>,
    pub error: Option<String>,
    pub warnings: usize,
}
#[derive(Default)]
struct State {
    generation: u64,
    view: Snapshot,
    commands: Option<mpsc::Sender<()>>,
    stop: CancellationToken,
    job: CancellationToken,
}
#[derive(Clone, Default)]
pub(crate) struct Controller(Arc<Mutex<State>>);
impl Controller {
    pub fn snapshot(&self) -> Snapshot {
        lock(&self.0).view.clone()
    }
    pub fn is_closed(&self) -> bool {
        lock(&self.0).stop.is_cancelled()
    }
    pub fn start(&self) -> Result<()> {
        let mut s = lock(&self.0);
        ensure!(
            s.view.supported && s.view.allowed,
            "远端不支持诊断包获取，或未允许文件访问"
        );
        ensure!(!s.view.busy, "正在获取诊断包");
        s.commands
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("诊断通道已关闭"))?
            .try_send(())?;
        s.job = CancellationToken::new();
        s.view.busy = true;
        s.view.phase = Phase::Preparing;
        s.view.path = None;
        s.view.error = None;
        s.view.warnings = 0;
        s.view.progress = bundle::Progress {
            stage: "等待远端生成诊断包".into(),
            completed: 0,
            total: 0,
        };
        Ok(())
    }
    pub fn cancel(&self) {
        lock(&self.0).job.cancel();
    }
    pub fn close(&self) {
        lock(&self.0).stop.cancel();
    }
    fn update(&self, generation: u64, f: impl FnOnce(&mut Snapshot)) {
        let mut s = lock(&self.0);
        if s.generation == generation {
            f(&mut s.view)
        }
    }
    /// Bind the low-priority BINARY channel.
    ///
    /// That channel is shared with the official `kind = 8` remote terminal: the
    /// official controller sends and receives `TERM` frames on it. Only one
    /// `on_message` handler can exist per data channel, so the diagnostics
    /// decoder must forward terminal frames instead of dropping them as
    /// "unrelated" packets — otherwise OPEN/LIST replies never reach the
    /// terminal and every remote terminal request times out.
    pub fn bind(
        &self,
        channel: &Arc<RTCDataChannel>,
        terminal: tokio::sync::broadcast::Sender<Vec<u8>>,
    ) {
        let stop = CancellationToken::new();
        let (tx, mut rx) = mpsc::channel(8);
        let (commands, mut requests) = mpsc::channel(1);
        let generation = {
            let mut s = lock(&self.0);
            s.stop.cancel();
            s.generation = s.generation.wrapping_add(1);
            s.view = Snapshot::default();
            s.commands = Some(commands);
            s.stop = stop.clone();
            s.generation
        };
        let input_stop = stop.clone();
        let terminal_frames = terminal;
        channel.on_message(Box::new(move |message| {
            // The terminal owns every TERM frame on this shared channel. Never
            // log or decode its payload here: it carries keystrokes and shell
            // output.
            match route_frame(&message.data) {
                Routed::Terminal(frame) => {
                    let _ = terminal_frames.send(frame.to_vec());
                }
                Routed::Diagnostics(_) => match decode(&message.data) {
                    Ok(packet) => {
                        if tx.try_send(packet).is_err() {
                            input_stop.cancel()
                        }
                    }
                    Err(_) => input_stop.cancel(),
                },
                Routed::Ignore => {}
            }
            Box::pin(async {})
        }));
        let closing = stop.clone();
        channel.on_close(Box::new(move || {
            closing.cancel();
            Box::pin(async {})
        }));
        let weak = Arc::downgrade(channel);
        let owner = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _=stop.cancelled()=>break,
                    packet=rx.recv()=>match packet {
                        Some(Packet::Control(_,Message::Hello{allowed}))=>owner.update(generation,|v|{v.supported=true;v.allowed=allowed}),
                        Some(_)=>{},None=>break,
                    },
                    request=requests.recv()=>{
                        if request.is_none(){break}
                        let Some(channel)=weak.upgrade() else{break};
                        let id=*uuid::Uuid::new_v4().as_bytes();
                        let job=lock(&owner.0).job.clone();
                        let result=tokio::select! {
                            _=stop.cancelled()=>Err(anyhow::anyhow!("诊断连接已关闭")),
                            _=job.cancelled()=>Err(anyhow::anyhow!("已取消获取远端诊断包")),
                            result=download(&owner,generation,&channel,id,&mut rx,&stop,&job)=>result,
                        };
                        if result.is_err() && !stop.is_cancelled() {let _=send(&channel,id,Message::Cancel).await;}
                        owner.update(generation,|v|{
                            v.busy=false;
                            match result {Ok((path,warnings))=>{v.path=Some(path);v.warnings=warnings;v.progress=bundle::Progress{stage:"已保存远端诊断包".into(),completed:1,total:1}},Err(e)=>v.error=Some(format!("{e:#}"))}
                        });
                    }
                }
            }
            owner.update(generation, |v| {
                v.supported = false;
                v.allowed = false;
                if v.busy {
                    v.error = Some("连接已关闭，诊断包未完成".into());
                    v.busy = false;
                }
            });
        });
    }
}
struct Partial(PathBuf);
impl Drop for Partial {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
async fn receive(
    rx: &mut mpsc::Receiver<Packet>,
    stop: &CancellationToken,
    job: &CancellationToken,
) -> Result<Packet> {
    tokio::select! {_ = job.cancelled()=>anyhow::bail!("已取消获取远端诊断包"), p=next(rx,stop)=>p}
}
async fn download(
    owner: &Controller,
    generation: u64,
    channel: &RTCDataChannel,
    id: Id,
    rx: &mut mpsc::Receiver<Packet>,
    stop: &CancellationToken,
    job: &CancellationToken,
) -> Result<(PathBuf, usize)> {
    ensure!(
        !stop.is_cancelled() && !job.is_cancelled(),
        "已取消获取远端诊断包"
    );
    send(channel, id, Message::Request).await?;
    let (size, expected, warnings) = loop {
        match receive(rx, stop, job).await? {
            Packet::Control(other, Message::Progress(progress)) if other == id => {
                owner.update(generation, |v| {
                    v.phase = Phase::Preparing;
                    v.progress = progress;
                })
            }
            Packet::Control(
                other,
                Message::Begin {
                    size,
                    sha256,
                    warnings,
                    ..
                },
            ) if other == id => break (size, sha256, warnings),
            Packet::Control(other, Message::Error { message }) if other == id => {
                anyhow::bail!("{message}")
            }
            Packet::Control(other, _) | Packet::Data(other, _, _) if other != id => {}
            _ => anyhow::bail!("远端诊断响应顺序无效"),
        }
    };
    ensure!(
        size > 0
            && size <= bundle::ARCHIVE_LIMIT
            && expected.len() == 64
            && expected.bytes().all(|b| b.is_ascii_hexdigit()),
        "远端诊断包长度或校验值无效"
    );
    let directory = bundle::output_directory()?;

    let path = directory.join(format!(
        "OpenUUYC-remote-diagnostics-{}-{}.zip",
        chrono::Utc::now().format("%Y%m%dT%H%M%SZ"),
        uuid::Uuid::from_bytes(id).simple()
    ));
    let temporary = path.with_extension("zip.partial");
    let (partial, file) = tokio::task::spawn_blocking(move || -> Result<_> {
        std::fs::create_dir_all(&directory)?;
        let file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        // If the awaiting task is cancelled, the returned guard still cleans up.
        Ok((Partial(temporary), file))
    })
    .await??;
    let mut file = tokio::fs::File::from_std(file);
    let mut hash = Sha256::new();
    let mut offset = 0;
    owner.update(generation, |v| {
        v.phase = Phase::Downloading;
        v.progress = bundle::Progress {
            stage: "正在下载远端诊断包".into(),
            completed: 0,
            total: size,
        }
    });
    send(channel, id, Message::Ack { offset }).await?;
    while offset < size {
        match receive(rx, stop, job).await? {
            Packet::Data(other, begin, data) if other == id => {
                ensure!(
                    begin == offset && offset + data.len() as u64 <= size,
                    "诊断包数据偏移或长度无效"
                );
                file.write_all(&data).await?;
                hash.update(&data);
                offset += data.len() as u64;
                owner.update(generation, |v| v.progress.completed = offset);
                send(channel, id, Message::Ack { offset }).await?;
            }
            Packet::Control(other, Message::Error { message }) if other == id => {
                anyhow::bail!("{message}")
            }
            Packet::Control(other, _) | Packet::Data(other, _, _) if other != id => {}
            _ => anyhow::bail!("远端诊断传输中断"),
        }
    }
    match receive(rx, stop, job).await? {
        Packet::Control(other, Message::Finish) if other == id => {}
        _ => anyhow::bail!("诊断传输未完整结束"),
    }
    ensure!(
        format!("{:x}", hash.finalize()) == expected,
        "诊断包校验失败"
    );
    owner.update(generation, |v| {
        v.phase = Phase::Saving;
        v.progress = bundle::Progress {
            stage: "正在保存并校验诊断包".into(),
            completed: 0,
            total: 0,
        }
    });
    file.flush().await?;
    file.sync_all().await?;
    drop(file);
    ensure!(
        !stop.is_cancelled() && !job.is_cancelled(),
        "诊断获取已取消"
    );
    // No suspension at publication: cancellation cannot leave a renamed result
    // whose completion was discarded by the caller.
    std::fs::rename(&partial.0, &path)?;
    Ok((path, warnings))
}

/// Official UU `TERM` frame magic. The terminal protocol (controller side)
/// encodes every frame with this prefix; the shared BINARY channel also
/// carries `OUDIAG01` diagnostics packets.
fn is_terminal_frame(data: &[u8]) -> bool {
    data.starts_with(b"TERM")
}

/// Which handler owns one message on the shared low-priority BINARY channel.
#[derive(Debug, PartialEq, Eq)]
enum Routed<'a> {
    Terminal(&'a [u8]),
    Diagnostics(&'a [u8]),
    Ignore,
}

/// Exactly one `on_message` can exist per data channel, so the dispatch order
/// is the contract: terminal first, diagnostics second, everything else
/// dropped. Terminal frames must never be offered to the diagnostics decoder.
fn route_frame(data: &[u8]) -> Routed<'_> {
    if is_terminal_frame(data) {
        Routed::Terminal(data)
    } else if data.starts_with(MAGIC) {
        Routed::Diagnostics(data)
    } else {
        Routed::Ignore
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_frames_are_not_diagnostics() {
        // A TERM header plus a kind byte must be routed to the terminal.
        assert!(is_terminal_frame(b"TERM\x01\x05\x00\x00"));
        assert!(!is_terminal_frame(b"OUDIAG01"));
        assert!(!is_terminal_frame(b""));
        assert!(!is_terminal_frame(b"TER"));
    }

    /// The shared channel has one handler; this is the dispatch it performs.
    #[test]
    fn the_shared_binary_channel_dispatches_terminal_before_diagnostics() {
        let term = b"TERM\x01\x0a\x00\x00\x00\x00\x00\x00\x02\x00\x00\x00[]";
        assert!(matches!(route_frame(term), Routed::Terminal(frame) if frame == term));
        let diag = b"OUDIAG01{\"type\":\"hello\"}";
        assert!(matches!(route_frame(diag), Routed::Diagnostics(frame) if frame == diag));
        assert_eq!(route_frame(b"random bytes"), Routed::Ignore);
        assert_eq!(route_frame(b""), Routed::Ignore);
        // A diagnostics-looking prefix inside a TERM frame must not win.
        assert!(matches!(route_frame(term), Routed::Terminal(_)));
    }
}
