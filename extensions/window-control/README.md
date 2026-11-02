# 私有窗口远控拓展（Windows + niri/Linux）

此拓展仅供 **OpenUUYC ↔ OpenUUYC** 使用，沿现有已建立的 WebRTC
`TEXT_DATA_CHANNEL` 发送带 `OUWC/1\0` 魔数的 JSON 消息。它不是网易 UU 官方协议；
官方客户端不会响应，不能据此宣称官方互通。它不监听额外端口，不需要管理员、root
或驱动，也不会开启新的远程登录入口。

## 现阶段提供

- 被控端：**Windows** 枚举当前桌面会话可见的顶层窗口，尝试使用
  `SetForegroundWindow` 激活所选窗口。Windows 前台锁定规则可能拒绝激活。
- 被控端：**niri / Wayland** 通过同一桌面用户的 `NIRI_SOCKET` 与 niri IPC
  通信，执行 `Windows` 查询和 `Action.FocusWindow`；使用 niri 稳定窗口 ID
  而非 XWayland 句柄。
- 主控端：播放窗口 → 设置 → 高级设置 → **远端窗口**，刷新并选择窗口。
  原有屏幕采集和鼠标/键盘注入仍然用于观看和操作激活后的窗口。
- 限制：**还不是独立单窗口视频流，也没有按窗口隔离输入**；窗口可能遮挡其他内容，
  显示器切换仍按 UU 的 screen-id 工作。GNOME/KDE Wayland 和 X11 的本机窗口
  枚举暂未实现。PICO 官方/原生客户端暂未加入此私有扩展。

## 启用和卸载（在被控电脑本机执行）

Linux/niri（运行在桌面登录用户身份，禁止 sudo）：

```sh
sh extensions/window-control/install-linux.sh install
# 取消授权
sh extensions/window-control/install-linux.sh remove
```

Windows PowerShell（普通用户）：

```powershell
powershell -ExecutionPolicy Bypass -File extensions/window-control/install-windows.ps1 -Action Install
# 取消授权
powershell -ExecutionPolicy Bypass -File extensions/window-control/install-windows.ps1 -Action Remove
```

启用标志位于 Linux `$XDG_DATA_HOME/OpenUUYC/private-window-control.enabled`
（默认 `~/.local/share`）或 Windows
`%LOCALAPPDATA%\OpenUUYC\private-window-control.enabled`。不要从网络共享运行
安装脚本。标志由安装脚本本机创建，**单独标志不意味着已授权的远控连接**：每次执行
还需当前主机连接合法、CONTROL 输入通道已绑定且未故障。关闭标志后，下一条请求
即时拒绝；旧窗口列表需要断线重连清除。

## 安全边界

- 默认关闭；列表/聚焦操作均在被控主机上检查启用状态和现有会话输入许可。
- 拒绝未识别 action、越界/伪造 ID、失效窗口；检查请求与响应体积并限制返回数量。
- niri IPC 不使用 shell，不执行远端传入的命令；Windows 不提升进程权限，不进行
  注入、模拟 Secure Desktop 或绕过操作系统的前台限制。
- **这不是按窗口隔离的安全沙箱**：已有桌面键鼠控制权限仍可以操作其他程序。
  远端可写文件的用户也可能改写该用户的启用标志，所以不要把标志当作独立身份验证；
  必须依赖已授权的 UU 会话和被控端自身访问许可。
- 不提供无人值守提权或 Windows 登录界面窗口枚举。强安全隔离的单窗口投屏需另做
  portal 级选择授权、窗体采集与窗口几何映射；不能简单对桌面画面裁剪代替。
