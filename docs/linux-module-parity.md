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
| 中文输入 | linux/input | 已接 Fcitx5 门户，连接复用并在断开后重建；每条文本用独立短时上下文，缺真实桌面提交验收 |
| 光标 | linux/cursor_shape、features/remote_cursor | 形状、热点、缩放、多屏边界 |
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
cargo +1.95.0 test --lib platform::linux::fcitx --offline
```

2026-10-30 本机验证：应用专项 10 项通过（包含两个显式启用的本机测试），
发现 133 个应用入口；GIO 临时 touch 入口创建文件成功，缺失程序入口正确返回失败。
PTY 专项 10 项通过，连续 5 轮复测通过。尚未验证跨设备应用列表展示和真实窗口激活。

### 输入法门户文字提交

Linux 被控端的手机输入法文字（`Event::Text`）先经
`org.freedesktop.portal.Fcitx`（fcitx5 / 深度输入法等实现）的门户通路。进程复用
session D-Bus 连接，连接关闭后下次调用重建；每条文字都建立独立上下文，按
`CreateInputContext` → `SetCapability(0)` → `FocusIn` → `CommitString` → `DestroyIC`
提交。零能力位表示本客户端不提供预编辑、键盘事件或周边文本。落点由输入法决定：
当前聚焦的输入框。这条通路不触碰剪贴板、不发合成按键、也不写日志；门户不可用时
按序回退 `zwp_virtual_keyboard`（Wayland 原生窗口）与 XTest（XWayland / Xorg）。

2026-10-30 本机验证：fcitx5 5.1.23 上门户生命周期及 `SetCapability(0)` 应答正常；
Rust 单元测试覆盖缓存复用/失效与零文本拒绝（共 3 项）。尚未在真实远程会话中验证
文字落入目标输入框、与合成类输入法（如 rime）的候选状态交互，及 KDE/GNOME 其他实现。

## MR 双向控制入口

Linux 侧边栏新增独立的「MR 设备」页（组「头显与 MR」），不再挤在连接设置页顶部：
该页上半部分是「本地 MR / Android 设备 · ADB」，下半部分是原设置页的「内置 PCVR · OpenXR 优先」。
进入页面自动刷新一次设备清单，之后手动刷新；页面同时探测本机 adb / scrcpy 是否可用，
缺失时直接给出安装命令。
只允许 ADB 状态为 device 的设备打开窗口；设备按卡片列出，状态徽标区分
已授权 / 待头显授权 / 离线等；可选择只读观看或观看并控制、指定显示 ID。
依赖本机 adb 和 scrcpy，不自动打开无线调试、连接地址、修改头显设置或接管未授权设备。
每台设备最多一个独立窗口，关闭窗口停止；默认关闭音频和自动剪贴板同步。
这是明确标注的本地 ADB/scrcpy 路径，不是新增 UU 被控信令或原生 MR 合成器。

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
