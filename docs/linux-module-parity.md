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

Linux 设置页增加「本地 MR / Android 设备 · ADB」：
手动刷新已连接设备，只允许 ADB 状态为 device 的设备打开窗口；
可选择只读观看或观看并控制、指定显示 ID。
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
