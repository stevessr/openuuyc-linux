//! Authorized remote microphone via a temporary PulseAudio/PipeWire-Pulse source.
//! No physical microphone is captured and no default device changes on open.
use anyhow::{Context, Result, bail, ensure};
use std::{
    io::Read,
    os::fd::AsRawFd,
    process::{Child, ChildStdin, Command, Stdio},
    sync::{Arc, Mutex, Weak},
    time::{Duration, Instant},
};

pub(crate) struct AudioPriority {
    _guard: super::audio_thread::RealtimeGuard,
}
impl AudioPriority {
    pub fn enter() -> Result<Self> {
        Ok(Self {
            _guard: super::audio_thread::RealtimeGuard::enter()?,
        })
    }
}
#[derive(Clone, Copy, Default)]
pub(crate) struct State {
    pub speaker_running: u32,
    pub microphone_running: u32,
    pub microphone_frames: u32,
    pub generation: u64,
    pub microphone_underrun: u64,
}
fn command(args: &[&str]) -> Result<String> {
    let mut child = Command::new("pactl")
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .context("需要 pactl 与可连接的 PulseAudio/PipeWire-Pulse 服务")?;
    let deadline = Instant::now() + Duration::from_secs(2);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            bail!("音频服务请求超时")
        }
        std::thread::sleep(Duration::from_millis(10));
    };
    ensure!(
        status.success(),
        "音频服务拒绝 {}",
        args.first().unwrap_or(&"request")
    );
    let mut output = String::new();
    if let Some(stdout) = child.stdout.take() {
        stdout.take(8192).read_to_string(&mut output)?;
    }
    Ok(output.trim().to_owned())
}
fn source_name() -> String {
    format!("openuuyc_remote_mic_{}", std::process::id())
}
fn module(args: &[&str]) -> Result<u32> {
    command(args)?.parse().context("音频服务未返回有效模块编号")
}
struct Device {
    sink_module: u32,
    source_module: Option<u32>,
    child: Option<Child>,
    stdin: Option<ChildStdin>,
    route: Option<Arc<DefaultSource>>,
    enabled: bool,
    queued: f64,
    clock: Instant,
    generation: u64,
}
impl Device {
    fn open() -> Result<Self> {
        let source = source_name();
        let sink = format!("{source}_sink");
        let id = module(&[
            "load-module",
            "module-null-sink",
            &format!("sink_name={sink}"),
            "format=float32le",
            "rate=48000",
            "channels=2",
            "sink_properties=device.description=OpenUUYC_Remote_Microphone_Transport",
        ])?;
        let mut device = Self {
            sink_module: id,
            source_module: None,
            child: None,
            stdin: None,
            route: None,
            enabled: false,
            queued: 0.0,
            clock: Instant::now(),
            generation: 1,
        };
        device.source_module = Some(module(&[
            "load-module",
            "module-remap-source",
            &format!("master={sink}.monitor"),
            &format!("source_name={source}"),
            "source_properties=device.description=OpenUUYC_Remote_Microphone",
        ])?);
        command(&["set-source-mute", &source, "1"])?;
        let mut child = Command::new("pacat")
            .args([
                "--playback",
                &format!("--device={sink}"),
                "--raw",
                "--format=float32le",
                "--rate=48000",
                "--channels=2",
                "--latency-msec=10",
                "--client-name=OpenUUYC",
                "--stream-name=Remote microphone",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .context("启动虚拟麦克风 PCM 传输失败（需要 pacat）")?;
        let stdin = child.stdin.take().context("虚拟麦克风缺少 PCM 管道")?;
        // Retain the child before any fallible setup so Drop always reaps it.
        device.child = Some(child);
        let fd = stdin.as_raw_fd();
        // SAFETY: fd belongs to live stdin; no concurrent descriptor mutations.
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        ensure!(
            flags >= 0 && unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } >= 0,
            "设置非阻塞音频管道失败"
        );
        device.stdin = Some(stdin);
        Ok(device)
    }
    fn state(&mut self) -> Result<State> {
        ensure!(
            self.child
                .as_mut()
                .context("麦克风传输已关闭")?
                .try_wait()?
                .is_none(),
            "麦克风 PCM 传输进程已退出"
        );
        let now = Instant::now();
        self.queued =
            (self.queued - now.duration_since(self.clock).as_secs_f64() * 48000.0).max(0.0);
        self.clock = now;
        Ok(State {
            microphone_running: u32::from(self.enabled),
            microphone_frames: self.queued.ceil() as u32,
            generation: self.generation,
            ..State::default()
        })
    }
}
impl Drop for Device {
    fn drop(&mut self) {
        self.route.take();
        self.stdin.take();
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        if let Some(id) = self.source_module {
            let _ = command(&["unload-module", &id.to_string()]);
        }
        let _ = command(&["unload-module", &self.sink_module.to_string()]);
    }
}
static DEVICE: Mutex<Weak<Mutex<Device>>> = Mutex::new(Weak::new());
pub(crate) struct Bridge(Arc<Mutex<Device>>);
impl Bridge {
    pub fn open() -> Result<Self> {
        let mut current = DEVICE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(device) = current.upgrade() {
            return Ok(Self(device));
        }
        let device = Arc::new(Mutex::new(Device::open()?));
        *current = Arc::downgrade(&device);
        Ok(Self(device))
    }
    pub fn state(&mut self) -> Result<State> {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).state()
    }
    pub fn enable(&mut self, speaker: bool, microphone: bool) -> Result<State> {
        ensure!(!speaker, "Linux 麦克风桥不提供扬声器输出");
        let mut d = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if d.enabled != microphone {
            command(&[
                "set-source-mute",
                &source_name(),
                if microphone { "0" } else { "1" },
            ])?;
            if microphone && std::env::var("OPENUUYC_REMOTE_MIC_DEFAULT").as_deref() == Ok("1") {
                match acquire_default_source() {
                    Ok(route) => d.route = Some(route),
                    Err(error) => {
                        let _ = command(&["set-source-mute", &source_name(), "1"]);
                        return Err(error);
                    }
                }
            } else if !microphone {
                d.route.take();
            }
            d.enabled = microphone;
            d.queued = 0.0;
            d.clock = Instant::now();
            d.generation += 1;
        }
        d.state()
    }
    pub fn wait(&mut self, _previous: &State, timeout_ms: u32) -> Result<Option<State>> {
        std::thread::sleep(Duration::from_millis(u64::from(timeout_ms).min(2)));
        Ok(Some(self.state()?))
    }
    pub fn write(&mut self, state: &State, samples: &[f32]) -> Result<()> {
        let mut d = self.0.lock().unwrap_or_else(|e| e.into_inner());
        ensure!(
            d.enabled && state.generation == d.generation,
            "麦克风已静音或代次失效"
        );
        ensure!(
            samples.len() <= 240 && samples.len() % 2 == 0,
            "无效麦克风 PCM 块"
        );
        let mut bytes = Vec::with_capacity(samples.len() * 4);
        for sample in samples {
            bytes.extend_from_slice(&if sample.is_finite() { *sample } else { 0.0 }.to_le_bytes());
        }
        let fd = d.stdin.as_ref().context("麦克风 PCM 管道关闭")?.as_raw_fd();
        // SAFETY: valid pipe fd, initialized bytes; <=960 bytes < PIPE_BUF, so
        // a nonblocking write is atomic or fails rather than queuing stale PCM.
        let written = unsafe { libc::write(fd, bytes.as_ptr().cast(), bytes.len()) };
        if written < 0 {
            return Err(std::io::Error::last_os_error()).context("麦克风 PCM 管道拥塞或断开");
        }
        ensure!(written as usize == bytes.len(), "麦克风 PCM 写入不完整");
        d.state()?;
        d.queued += samples.len() as f64 / 2.0;
        Ok(())
    }
}
pub(crate) fn unavailable(_error: &anyhow::Error) -> bool {
    true
}

struct DefaultSource {
    previous: String,
}
impl Drop for DefaultSource {
    fn drop(&mut self) {
        // Do not override a device the user selected during the session.
        if command(&["get-default-source"]).is_ok_and(|v| v == source_name()) {
            let _ = command(&["set-default-source", &self.previous]);
        }
    }
}
static ROUTE: Mutex<Weak<DefaultSource>> = Mutex::new(Weak::new());
fn acquire_default_source() -> Result<Arc<DefaultSource>> {
    let mut route = ROUTE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(existing) = route.upgrade() {
        return Ok(existing);
    }
    let previous = command(&["get-default-source"])?;
    ensure!(
        previous != source_name(),
        "原默认麦克风已经指向远端源，无法可靠恢复"
    );
    command(&["set-default-source", &source_name()])?;
    let lease = Arc::new(DefaultSource { previous });
    *route = Arc::downgrade(&lease);
    Ok(lease)
}
#[derive(Default)]
pub(crate) struct Routing {
    lease: Option<Arc<DefaultSource>>,
}
impl Routing {
    pub fn apply(&mut self, speakers: bool, microphone: bool) -> Result<()> {
        if microphone && self.lease.is_none() {
            self.lease = Some(acquire_default_source()?);
        } else if !microphone {
            self.lease.take();
        }
        ensure!(
            !speakers,
            "Linux 暂不自动切换远端扬声器默认设备；麦克风设置独立生效"
        );
        Ok(())
    }
    pub fn maintain(&mut self) -> Result<()> {
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "local PulseAudio synthetic signal only; never captures physical microphone"]
    fn live_virtual_source_carries_generated_pcm() {
        let mut bridge = Bridge::open().unwrap();
        let state = bridge.enable(false, true).unwrap();
        use std::os::unix::fs::OpenOptionsExt;
        struct Recording(std::path::PathBuf);
        impl Drop for Recording {
            fn drop(&mut self) {
                let _ = std::fs::remove_file(&self.0);
            }
        }
        let recording = Recording(
            std::env::temp_dir().join(format!("openuuyc-mic-test-{}", uuid::Uuid::new_v4())),
        );
        let path = &recording.0;
        let file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)
            .unwrap();
        let mut recorder = Command::new("parec")
            .args([
                &format!("--device={}", source_name()),
                "--raw",
                "--format=float32le",
                "--rate=48000",
                "--channels=2",
                "--latency-msec=10",
            ])
            .stdout(file)
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        for block in 0..400 {
            let mut pcm = [0.0f32; 240];
            for (i, sample) in pcm.iter_mut().enumerate() {
                *sample = ((block * 120 + i / 2) as f32 * 440.0 * std::f32::consts::TAU / 48000.0)
                    .sin()
                    * 0.25;
            }
            bridge.write(&state, &pcm).unwrap();
            std::thread::sleep(Duration::from_micros(2500));
        }
        let _ = recorder.kill();
        let _ = recorder.wait();
        let bytes = std::fs::read(&path).unwrap();
        assert!(
            bytes
                .chunks_exact(4)
                .any(|b| f32::from_le_bytes(b.try_into().unwrap()).abs() > 0.1),
            "virtual source did not deliver generated PCM"
        );
        bridge.enable(false, false).unwrap();
    }
    #[test]
    #[ignore = "explicit temporary default source switch and restoration"]
    fn live_default_input_lease_restores_previous_device() {
        let _bridge = Bridge::open().unwrap();
        let previous = command(&["get-default-source"]).unwrap();
        {
            let _lease = acquire_default_source().unwrap();
            assert_eq!(command(&["get-default-source"]).unwrap(), source_name());
        }
        assert_eq!(command(&["get-default-source"]).unwrap(), previous);
    }
    #[test]
    #[ignore = "requires local PipeWire-Pulse; synthetic silence only, no default changes"]
    fn live_virtual_source_accepts_silence_and_releases_modules() {
        let mut bridge = Bridge::open().unwrap();
        let state = bridge.enable(false, true).unwrap();
        assert_eq!(state.microphone_running, 1);
        bridge.write(&state, &[0.0; 240]).unwrap();
        let muted = bridge.enable(false, false).unwrap();
        assert_eq!(muted.microphone_running, 0);
        assert!(bridge.write(&state, &[0.0; 240]).is_err());
        drop(bridge);
        assert!(
            !command(&["list", "short", "sources"])
                .unwrap()
                .contains(&source_name())
        );
    }
}
