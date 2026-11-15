# Linux 模块对照与验收

参考本地 UUWay 的 README、src/native_vk_dmabuf_import.c 和终端兼容实现。
UUWay 是 Wine 桥接被控端；本项目保留原生 Rust 架构，不直接搬运其服务或修改系统配置。

以下为源码覆盖盘点，不是设备验收结果。

| 模块 | 当前入口 | 后续验收/缺口 |
| --- | --- | --- |
| 显示管理 DM | platform/linux/display | Xorg 物理模式及 niri 虚拟/兜底模式切换均需目标屏新尺寸首帧确认；30 秒未确认回滚；缩放仍缺 |
| 虚拟显示器 | platform/linux/virtual_display | 当前合成器接管主要为 niri；GNOME/KDE 需独立后端 |
| GPU 视频 | decoder/vaapi、video_layer、encoder | 默认 DMA-BUF、失败回退；跨 GPU、modifier、同步与花屏实测 |
| 音频 | media/audio、linux/loopback | 同采样率减拷贝；录放设备切换、断线恢复 |
| 虚拟麦克风 | linux/virtual_audio | 当前明确不支持，需实现 PipeWire source 与生命周期 |
| 键鼠/触摸 | linux/input、features/remote_input | 权限拒绝、焦点、断开释放按键、多点触摸 |
| 中文输入 | host/input、features/clipboard | 已提交文本整段粘贴，键盘事件仍经过本机输入法；文本光标识别见下文，需验证原生 Wayland 与 XWayland 实际应用 |
| 光标 | linux/cursor_shape、features/remote_cursor | 形状、热点、缩放、多屏边界；窗口源按采集循环实时几何上报，原生 Wayland 输入框识别走 AT-SPI 回退 |
| 剪贴板 | features/clipboard | 双向文本、循环抑制、Wayland 权限 |
| 文件传输 | features/file_transfer、linux/transfer | 双向目录、取消、冲突、路径安全 |
| 拖放 | linux/drag_drop | Wayland 当前明确拒绝；不能以 X11 实现宣称支持 |
| 终端 | platform/posix/terminal | 登录环境、多会话、OSC 3008 兼容、断开清理 |
| 服务管理 | linux/host_service | 用户图形会话自启动；尚非无人登录系统服务 |
| 更新与诊断 | features/remote_upgrade、devtools/linux-doctor.sh | 更新失败恢复；诊断仅只读能力盘点 |

执行只读盘点：`bash devtools/linux-doctor.sh`。
不得把命令存在、编译通过或服务 active 当作实际串流成功。
后续按模块分别提交实现与测试，不将全部模块标为完成。

## 快速启动应用

Linux 被控端通过已认证的 `OPENUUYC_QUICK_APPS_V1` 扩展列出与启动应用，
继续沿用远程终端授权，不接受任意远程命令。官方客户端没有此扩展时不会自动获得应用列表。

- 按 XDG 用户目录、配置的系统目录顺序去重；高优先级 Hidden 入口继续遮蔽低优先级入口。
- 名称匹配 LC_ALL / LC_MESSAGES / LANG，并支持地区、语言与 modifier 回退。
- 处理 OnlyShowIn、NotShowIn、TryExec 与 DBusActivatable；Exec 参数仍由 GIO 解释。
- 标识基于 desktop-file ID，不依赖符号链接实际指向的版本目录。
- 启动前重新检查入口；GIO 明确退出失败时返回错误，不误报成功。
- VRChat 桌面入口保持原始启动方式；Steam 检测入口独立保留。
- 启动器接受请求并不证明窗口或 VR 首帧已出现。

依据：[Desktop Entry keys](https://specifications.freedesktop.org/desktop-entry/latest/recognized-keys.html)、
[localized values](https://specifications.freedesktop.org/desktop-entry/latest/localized-keys.html)。

本机专项验证（后两个测试会扫描本机目录并启动临时 touch 测试入口，不启动用户应用）：

```bash
cargo +1.95.0 test --lib features::host::quick_apps --offline -- --include-ignored --nocapture
```

2026-10-30 本机验证：应用专项 10 项通过（包含两个显式启用的本机测试），
发现 133 个应用入口；GIO 临时 touch 入口创建文件成功，缺失程序入口正确返回失败。
PTY 专项 10 项通过，连续 5 轮复测通过。尚未验证跨设备应用列表展示和真实窗口激活。

### 已提交文字与原生粘贴

Linux 被控端将 `text_input`（`Event::Text`）视为控制端已提交的 Unicode 文本，
包括手机输入法确认的中文和原生粘贴。整段文本由剪贴板工作线程发布，确认取得所有权后
发送一次粘贴快捷键；不再逐字改写 X11 键位表、创建虚拟键盘或调用 Fcitx 上下文。
物理键盘事件仍按键位注入，因此继续使用 Linux 本机的键盘布局与输入法。

默认快捷键为 Ctrl+V；能识别的终端使用 Ctrl+Shift+V，让终端按原生粘贴处理多行文本。
此操作会更新本机剪贴板，只检查输入许可，不依赖自动剪贴板同步开关；应用需支持对应
快捷键。通过既有工作线程保留 Wayland / X11 选择区所有权及循环抑制，取消或超时的
排队写入会作废。每次粘贴后保留 100 ms 给目标应用读取选择区，再处理下一条输入。

专项测试（最后一条使用隔离 X11 显示，不向当前桌面输入）：

```bash
cargo test --lib features::host::input
cargo test --lib input_text_
cargo test --lib paste_uses_clipboard_shortcuts
xvfb-run -a env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE -u NIRI_SOCKET -u HYPRLAND_INSTANCE_SIGNATURE cargo test --lib committed_text_pastes_whole_without_character_keys -- --ignored
```

仍需真实手机到 Linux 的原生 Wayland / XWayland 应用验收，尤其是自定义粘贴快捷键、
输入法候选尚未确认及目标应用繁忙超过选择区保留时间的情况。

## MR 与头显入口

Linux 侧边栏「MR 设备」页现展示 PICO 原生 UU 直连说明与内置 PCVR 运行时偏好。
已移除本页 ADB/scrcpy 投屏入口及自动 ADB 查询。头显在自己的 OpenUUYC PICO
客户端登录并连接电脑；电脑端此页用于运行时与驱动准备。

PICO 观看远端的 localhost 桥接路径补充了有界响应队列、握手超时、
拥塞后关键帧恢复、异常退出的 writer 清理、会话替换及切屏输入释放。
只读连接关闭时不应禁用本机控制；射线失效的松键仍需释放拖动。

2026-10-30，已授权 PICO A9210 / Android 14 本机验证：
scrcpy 查询到显示 ID 0、2、23；
只读显示 0 实际投屏五秒，VA-API 解码 1600×800，日志 59–60 FPS。
未写入录制文件、未注入输入、未同步剪贴板。
尚未验证 UI 点击到独立窗口的完整路径、实际键鼠输入和真人佩戴的 MR 交互质量。

scrcpy 参数依据：
https://github.com/Genymobile/scrcpy/blob/master/doc/connection.md
https://github.com/Genymobile/scrcpy/blob/master/doc/control.md

## 窗口级取流（waypipe 风格）

Linux 被控（Xorg）新增 `capture/x11_window.rs`：用 XComposite
`NameWindowPixmap` 按窗口读取客户区缓冲（MIT-SHM `GetImage`，失败退普通
GetImage），窗口作为普通 `Screen` 条目（identity `x11-window/<XID>`）注入
`Inventory::read` 的屏幕目录，于是按屏视频轨、切屏菜单与 PICO 空间窗口
零协议改动即可选择应用窗口。窗口缩放后 X 更换 backing pixmap，采集按
`SourceGone` 同级的失败重命名一次再抓；窗口关闭走既有"源已消失"恢复路径。

输入注入：`engine_linux::absolute` 对窗口源优先用采集循环缓存的实时窗口
矩形（每帧更新），目录快照只作兜底；X11 的矩形在 X 像素空间，Wayland 的
在合成器逻辑空间，各走各的注入路径。

### 窗口独立设备上报

被控端设置新增 `window_device`（「窗口独立设备」，默认关闭，持久化在
`features/host/settings.rs` 的 Record）。开启后，`publisher::screen_states`
只对 `Screen::is_window_source()`（identity 前缀 `x11-window/` 或
`wl-window/`）的条目写 OpenUUYC 私有字段 `PbScreen.openuuyc_device`
（tag 101；`PbScreenDevice{version=1,name="应用窗口",kind=1}`）。显示器不写该
字段；官方主控忽略未知字段，行为不变。

主控端（Rust 播放器与 PICO）解析该字段后，把窗口源标签显示为
「应用窗口 · 窗口标题」，独立窗口标题也使用该标签；普通屏、虚拟屏、超级屏
标签不变。该字段只影响展示与分组，screen id、视频轨、输入注入和官方互通
均不变。开关关闭时不发字段，等同于旧行为。

边界：只做展示/分组，不是第二台真实设备，也不建立独立账号设备或独立会话；
官方 UU 主控不会显示这个分组。

## 窗口级取流（Wayland）

Wayland 侧新增 `capture/wlr_window.rs`：绑定 `ext_foreign_toplevel_list_v1`
枚举顶层窗口，`ext_foreign_toplevel_image_capture_source_manager_v1` 加
`ext_image_copy_capture_v1` 按顶层源建会话取帧（SHM 缓冲，合成器有 damage
才推帧；`paint_cursors` 按光标偏好随会话建立）。窗口缩放后合成器重新宣布
buffer_size，采集按新尺寸重配缓冲；窗口关闭走 session `stopped` →
`SourceGone` 的既有恢复路径。窗口作为普通 `Screen` 条目（identity
`wl-window/<identifier>`）注入同一份屏幕目录，主控与 PICO 零协议改动。

niri 会话上的输入定位：直接问 niri 的 IPC 套接字（`Windows`/`Workspaces`），
按 niri 的窗口 id 精确对上（它把窗口 id 原样当作 ext_foreign_toplevel 的
identifier，同名窗口不会认错）。IPC 给出窗口在其工作区视图内的逻辑位置、
所在输出与输出原点；捕获缓冲是物理像素，IPC 是逻辑像素，150% 缩放会话里
两者不相等，位置一律用 IPC 的逻辑值。窗口源可以在别的工作区上被采集，但
指针只能落到屏幕上看得见的地方：定位不到时先让合成器把窗口切到可见位置
（niri 的 `FocusWindow` 会切工作区并滚进视口，同一窗口每秒最多试一次），
再按新位置换算。其他合成器没有实测过的 IPC 不接：能枚举能看，输入注入
明确报错而非投错位置。

### 输入框识别与虚拟键盘

控制端（PICO 智能输入法、手机端虚拟键盘）只认被控端 CONTROL 通道上的
`cursorType == 32513`（Windows I-beam）与 `cursorScreen`。Linux 被控端：

- X11/XWayland：`platform/linux/cursor_shape.rs` 用 XFixes 读光标名，先
  `trim + 小写` 归一化再映射；I-beam 组包含 `xterm`、`text`、`ibeam`、
  `i-beam`、`text_cursor`、`text-cursor`、`wayland-text`、`vertical-text`、
  `vertical_text`。未知名字仍报 0，不臆造形状。
- 光标发布（`features/host/peer/cursor.rs`）改用采集循环 `Published.screen`
  的实时几何匹配指针所在源，窗口源移动/缩放后下一帧即生效，不再等 1 秒目录
  刷新。
- 原生 Wayland：XFixes 看不到合成器光标，新增
  `platform/linux/text_focus.rs` 经 AT-SPI 无障碍总线订阅焦点事件，查询
  `Accessible.GetRole/GetState` 与 `Component.GetExtents`，识别
  ENTRY/PASSWORD_TEXT/TEXT/EDITBAR/TERMINAL/DOCUMENT_* 且同时
  FOCUSED+EDITABLE+SHOWING+VISIBLE 的对象。Wayland 会话里，只要被观看屏幕
  上有聚焦输入框就按文本光标上报；X11 仍要求指针落在输入框矩形内。
  `OPENUUYC_ATSPI=0` 关闭，无无障碍总线时静默退回 XFixes。
- 合成器原生通道（`platform/linux/compositor_focus.rs`）：Wayland 没有给第三方
  观测他人文本焦点的协议（`zwp_text_input_v3` 只覆盖自身 surface，
  `zwp_input_method_v2` 与用户输入法互斥），所以只取合成器本就公开的焦点信息。
  niri 走 `NIRI_SOCKET`，一次连接取回 `FocusedWindow`、`Workspaces`、`Outputs`，
  把 workspace view 坐标加回输出逻辑原点；Hyprland 走
  `hyprctl -j activewindow`。两者给出聚焦窗口的全局矩形，用于把 AT-SPI 的
  窗口相对坐标（`COORD_TYPE_WINDOW`）换算成屏幕坐标，并丢弃不与聚焦窗口相交
  的迟到焦点事件。KWin（Plasma）走 D-Bus
  `org.kde.kwin.VirtualKeyboard` 的 `active` 与
  `activeClientSupportsTextInput`，直接回答“当前是否有文本输入焦点”，
  无障碍总线拿不到矩形时按聚焦窗口上报。探测在独立线程里每 250 ms 刷新一次
  快照（合成器 IPC 是往返调用，实测未缓存最差约 5 ms），光标循环只读快照，
  实测 30 Hz 热路径约 50-200 ns；缺任一通道只是少一层信息。
- 只读事件：不读取文字、不记录事件内容、不注入也不修改无障碍开关。

边界：需要应用开启无障碍（GTK/Qt 通常受桌面无障碍开关控制）；AT-SPI 与
XFixes 的坐标空间在分数缩放、多屏混合缩放下可能不一致；只读文档或未暴露
EDITABLE 的控件按非输入框处理；KWin 的属性由 Plasma 虚拟键盘驱动，未启用
虚拟键盘的桌面上它只会给出 false，因此只把 true 当正向信号；Wayland 的
“聚焦即文本光标”是保守启发式，
可能在被观看屏幕上没有输入框时仍报文本光标。真实 GTK/Qt 应用与 PICO 智能
输入法仍待桌面验收。

### 触摸映射（niri）

niri 没有按设备配置输入（`input { touch "NAME" ... }` 会被 `niri validate`
拒绝），`input.touch.map-to-output` 对全部触摸设备同时生效，未配置时映射到
第一块输出。被控端在每一串触摸开始时把目标输出与当前映射对齐：不一致时写
一份包含用户原配置的临时配置（保留 touch 的 `off` 与 `calibration-matrix`），
只覆盖 `map-to-output`，校验后用 `niri msg action load-config-file` 加载；
会话释放时恢复原配置，进程异常退出由看门狗恢复。窗口级源先切到可见工作区，
再按窗口在输出里的实时矩形把触点从窗口比例换算到输出比例，原生多点也能落到
窗口上。`OPENUUYC_NIRI_TOUCH_REMAP=0`（旧名 `OPENUUYC_NIRI_TEMP_TOUCH_MAP=0`）
关闭临时映射，触摸退回单指指针模拟。

边界：预乘 ARGB 不反预乘（半透明窗口边缘偏深）；会话探测用的窗口尺寸
缓存按 identifier 记录，目录刷新只探新窗口。实机（niri 26.04）验证：
枚举 9 个窗口标题正确、抓帧 2066x1558、探尺寸到帧 0.3 秒内完成。

### 应用列表空白的实机修复

2026-10-30：将当前已授权、非访客设备的只读应用目录与终端执行权限分离。
启动应用和更改 VR 后端仍须终端权限；目录无权限不会静默显示空页，XR 菜单给出明确错误和重试。
新增 Steam manifest 和附加库扫描，桌面快捷方式之外也列出 SteamVR、VRChat 等已安装项目。
Linux 新二进制已替换旧运行进程，PICO 修复 APK 已覆盖安装。
实际从 PICO 设备页建立纯数据连接：日志记录 allowed=false、catalog_allowed=true，返回 count=138；
头显 UI 层级确认显示「138 个可用应用」及 SteamVR、Alacritty、Antigravity 等按钮。
Android 目录/启动权限策略测试 2 项通过；Steam 清单解析测试 2 项通过。
本次未开启终端权限，未启动任何远端应用，也未宣称 PCVR 游戏串流已完成。

### VRChat OSC 追踪转发

端口转发窗口的「VRChat OSC」按钮创建 UDP 规则草稿，默认把本机
`127.0.0.1:9000` 转发至被控机器 `127.0.0.1:9000`。确认保存后，
将本机追踪软件的 OSC 输出设为该本地端点；远端开启端口转发许可，
并在 VRChat 内启用 OSC、完成追踪器校准。两端均需支持 UDP 转发的 OpenUUYC。
若本机 9000 已占用，可在草稿中改为 19000，并同步修改追踪软件的发送目标。
来自局域网硬件的输入需自行将监听地址改为对应网卡地址并配置防火墙。

这条通路透传现成的 OSC 数据，不从摄像头估算骨骼，也不自动转换 OpenXR 姿态。
不转发 OSCQuery/mDNS 发现或 VRChat 的固定 9001 输出；发送端需手动指定目标。
UDP 数据目前复用可靠通道，拥塞时可能增加追踪延迟，尚未完成真机全身追踪验收。
默认端口与追踪格式见 [OSC Overview](https://docs.vrchat.com/docs/osc-overview)
和 [OSC Trackers](https://docs.vrchat.com/docs/osc-trackers)。

Linux 摄像头手动转发工具见 [camera-bridge.md](../devtools/camera-bridge.md)。

OSC 预设默认勾选「丢弃本地排队超过 100 ms 的 UDP 数据」。该选项随规则保存，
普通新规则及旧规则默认关闭；编辑 UDP 规则时可自行切换。它只清理本机等待握手或
发送的旧数据，不清理 SCTP 已提交的数据，也不保证端到端延迟上限。

UDP 规则流量栏附带本地丢弃包数（截断时悬停查看），计入入队失败、源会话数上限、
超长数据报与过期丢弃；统计在该规则监听实例重启时重置。它不是远端收包数或网络丢包率。
