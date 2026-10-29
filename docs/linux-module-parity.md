# Linux 模块对照与验收

参考本地 UUWay 的 README、src/native_vk_dmabuf_import.c 和终端兼容实现。
UUWay 是 Wine 桥接被控端；本项目保留原生 Rust 架构，不直接搬运其服务或修改系统配置。

以下为源码覆盖盘点，不是设备验收结果。

| 模块 | 当前入口 | 后续验收/缺口 |
| --- | --- | --- |
| 显示管理 DM | platform/linux/display | 多屏切换、模式切换失败回滚、缩放 |
| 虚拟显示器 | platform/linux/virtual_display | 当前合成器接管主要为 niri；GNOME/KDE 需独立后端 |
| GPU 视频 | decoder/vaapi、video_layer、encoder | 默认 DMA-BUF、失败回退；跨 GPU、modifier、同步与花屏实测 |
| 音频 | media/audio、linux/loopback | 同采样率减拷贝；录放设备切换、断线恢复 |
| 虚拟麦克风 | linux/virtual_audio | 当前明确不支持，需实现 PipeWire source 与生命周期 |
| 键鼠/触摸 | linux/input、features/remote_input | 权限拒绝、焦点、断开释放按键、多点触摸 |
| 中文输入 | linux/input | Fcitx5/桌面输入法的实际提交验证 |
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
```

2026-10-30 本机验证：应用专项 10 项通过（包含两个显式启用的本机测试），
发现 133 个应用入口；GIO 临时 touch 入口创建文件成功，缺失程序入口正确返回失败。
PTY 专项 10 项通过，连续 5 轮复测通过。尚未验证跨设备应用列表展示和真实窗口激活。
