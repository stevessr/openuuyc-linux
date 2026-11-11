//! Explicit, window-owned Linux camera capture. Never starts with a saved rule.
use super::{Protocol, service::Handle};
use std::process::{Child, Command, Stdio};
use std::io::Read;
use std::os::unix::process::CommandExt;
use std::sync::{Arc, Mutex};

fn terminate_with_parent(command: &mut Command) {
    let parent = unsafe { libc::getpid() };
    // SAFETY: only async-signal-safe libc calls run between fork and exec.
    unsafe {
        command.pre_exec(move || {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            // The parent may have died between fork and installing the signal.
            if libc::getppid() != parent { libc::_exit(125); }
            Ok(())
        });
    }
}

fn append_error(buffer: &mut Vec<u8>, bytes: &[u8]) {
    const LIMIT: usize = 8192;
    buffer.extend_from_slice(bytes);
    if buffer.len() > LIMIT { buffer.drain(..buffer.len() - LIMIT); }
}

fn eligible(rule: &super::Rule, status: &super::RuleStatus) -> bool {
    rule.protocol == Protocol::Tcp && rule.enabled && status.listening
        && rule.local_addr.is_ipv4()
        && (rule.local_addr.is_loopback() || rule.local_addr.is_unspecified())
}

pub(super) struct Capture {
    device: String,
    rule: Option<u64>,
    child: Option<Child>,
    active_rule: Option<super::Rule>,
    format: String,
    profile: (u32, u32, u32),
    message: String,
    errors: Arc<Mutex<Vec<u8>>>,
}
impl Default for Capture {
    fn default() -> Self {
        Self { device: "/dev/video0".into(), rule: None, child: None, active_rule: None, format: "auto".into(), profile: (1280, 720, 30), message: String::new(), errors: Arc::default() }
    }
}
impl Capture {
    fn stop(&mut self) {
        self.active_rule = None;
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
    fn enforce_route(&mut self, state: &super::service::Snapshot) {
        if self.child.is_some() && (!state.connected || !state.enabled ||
            !state.rules.iter().any(|(rule, status)| eligible(rule, status)
                && self.active_rule.as_ref() == Some(rule))) {
            self.stop();
            self.message = "转发不可用，已停止摄像头".into();
        }
    }
    pub(super) fn show(&mut self, ui: &mut egui::Ui, handle: &Handle) {
        let state = handle.snapshot();
        self.enforce_route(&state);
        let selected = state.rules.iter().find(|(rule, status)|
            Some(rule.id) == self.rule && eligible(rule, status));
        if let Some(child) = self.child.as_mut() {
            match child.try_wait() {
                Ok(Some(status)) => {
                    self.message = format!("采集进程已退出：{status}；请检查设备、格式及远端接收器");
                    self.child = None;
                    self.active_rule = None;
                }
                Err(error) => {
                    self.message = format!("无法查询采集进程：{error}");
                    self.stop();
                }
                Ok(None) => {}
            }
        }
        egui::CollapsingHeader::new(if self.child.is_some() { "● Linux 摄像头正在发送（展开可停止）" } else { "Linux 摄像头发送（实验性）" }).id_salt("camera-capture-panel").show(ui, |ui| {
            ui.label("先在远端启动虚拟摄像头接收器。仅转发视频，不录制文件；关闭本窗口即停止采集。");
            ui.add_enabled_ui(self.child.is_none(), |ui| {
                ui.horizontal(|ui| {
                    ui.label("设备");
                    ui.add(egui::TextEdit::singleline(&mut self.device).desired_width(130.0));
                    egui::ComboBox::from_id_salt("camera-input-format")
                        .selected_text(&self.format).show_ui(ui, |ui| {
                            for format in ["auto", "mjpeg", "yuyv422", "nv12"] {
                                ui.selectable_value(&mut self.format, format.to_owned(), format);
                            }
                        });
                    egui::ComboBox::from_id_salt("camera-forward-rule")
                        .selected_text(selected.map(|(rule, _)| rule.name.as_str()).unwrap_or("选择已监听的 TCP 规则"))
                        .show_ui(ui, |ui| {
                            for (rule, status) in &state.rules {
                                if eligible(rule, status) {
                                    ui.selectable_value(&mut self.rule, Some(rule.id), &rule.name);
                                }
                            }
                        });
                });
            });
            ui.add_enabled_ui(self.child.is_none(), |ui| {
                egui::ComboBox::from_id_salt("camera-capture-profile")
                    .selected_text(format!("{}×{} / {} FPS", self.profile.0, self.profile.1, self.profile.2))
                    .show_ui(ui, |ui| {
                        for profile in [(640,480,30), (1280,720,30), (1920,1080,30), (1280,720,60)] {
                            ui.selectable_value(&mut self.profile, profile,
                                format!("{}×{} / {} FPS", profile.0, profile.1, profile.2));
                        }
                    });
                ui.label("设备需支持所选模式；远端接收器的输出尺寸需单独设置。");
            });
            if self.child.is_some() {
                if ui.button("停止摄像头").clicked() {
                    self.stop();
                    self.message = "摄像头已停止".into();
                }
            } else if ui.add_enabled(state.connected && state.enabled && selected.is_some(), egui::Button::new("启动摄像头")).clicked() {
                if let Some((rule, _)) = selected {
                    // No shell interpolation. The bundled script validates the device,
                    // queries V4L2 capabilities, then execs FFmpeg in the same process.
                    let mut command = Command::new("python3");
                    terminate_with_parent(&mut command);
                    self.errors = Arc::default();
                    match command.args(["-c", include_str!("../../../devtools/camera-bridge.py"), "send", "--device"])
                        .arg(&self.device).arg("--input-format").arg(&self.format).arg("--port").arg(rule.local_port.to_string())
                        .arg("--width").arg(self.profile.0.to_string())
                        .arg("--height").arg(self.profile.1.to_string())
                        .arg("--fps").arg(self.profile.2.to_string())
                        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped()).spawn() {
                        Ok(mut child) => {
                            let errors = self.errors.clone();
                            if let Some(mut stderr) = child.stderr.take() {
                                std::thread::spawn(move || {
                                    let mut chunk = [0u8; 1024];
                                    loop {
                                        match stderr.read(&mut chunk) {
                                            Ok(0) | Err(_) => break,
                                            Ok(count) => append_error(&mut super::lock(&errors), &chunk[..count]),
                                        }
                                    }
                                });
                            }
                            self.active_rule = Some(rule.clone()); self.child = Some(child);
                            self.message = "采集进程已启动；远端是否出图需另行确认".into();
                        }
                        Err(error) => self.message = format!("无法启动摄像头工具（需 Python 3 与 FFmpeg）：{error}"),
                    }
                }
            }
            if !self.message.is_empty() { ui.label(&self.message); }
            let errors = super::lock(&self.errors);
            if !errors.is_empty() {
                egui::CollapsingHeader::new("采集错误详情").show(ui, |ui| {
                    egui::ScrollArea::vertical().max_height(100.0).show(ui, |ui| {
                        ui.monospace(String::from_utf8_lossy(&errors));
                    });
                });
            }
        });
    }
}
impl Drop for Capture {
    fn drop(&mut self) { self.stop(); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn changed_or_unavailable_route_stops_capture_without_restarting_it() {
        let rule = super::super::Rule {
            id: 1, name: "camera".into(), local_addr: "127.0.0.1".parse().unwrap(),
            local_port: 19001, target: "127.0.0.1".parse().unwrap(), remote_port: 19001,
            enabled: true, protocol: Protocol::Tcp, udp_realtime: false,
        };
        for mutation in 0..5 {
            let mut state = super::super::service::Snapshot::default();
            state.enabled = true;
            state.connected = true;
            let mut status = super::super::RuleStatus::default();
            status.listening = true;
            state.rules.push((rule.clone(), status));
            let mut capture = Capture::default();
            capture.rule = Some(rule.id);
            capture.active_rule = Some(rule.clone());
            capture.child = Some(Command::new("sleep").arg("30").spawn().unwrap());
            capture.enforce_route(&state);
            assert!(capture.child.is_some());
            match mutation {
                0 => state.enabled = false,
                1 => state.connected = false,
                2 => state.rules[0].1.listening = false,
                3 => state.rules[0].0.remote_port = 19002,
                _ => state.rules.clear(),
            }
            capture.enforce_route(&state);
            assert!(capture.child.is_none());
            state.connected = true;
            state.enabled = true;
            capture.enforce_route(&state);
            assert!(capture.child.is_none());
        }
    }

    #[test]
    fn error_tail_is_bounded() {
        let mut buffer = Vec::new();
        append_error(&mut buffer, &vec![b'a'; 8192]);
        append_error(&mut buffer, b"last error");
        assert_eq!(buffer.len(), 8192);
        assert!(buffer.ends_with(b"last error"));
    }

    #[test]
    fn child_inherits_parent_death_protection() {
        let mut command = Command::new("python3");
        command.args(["-c", "import ctypes; c=ctypes.CDLL(None); v=ctypes.c_int(); assert c.prctl(2,ctypes.byref(v),0,0,0)==0; assert v.value==9"]);
        terminate_with_parent(&mut command);
        assert!(command.status().unwrap().success());
    }

    #[test]
    fn closing_capture_terminates_and_reaps_its_process() {
        let child = Command::new("sleep").arg("30").spawn().unwrap();
        let pid = child.id();
        let mut capture = Capture::default();
        capture.child = Some(child);
        drop(capture);
        assert_eq!(unsafe { libc::kill(pid as i32, 0) }, -1);
        assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::ESRCH));
    }
}
