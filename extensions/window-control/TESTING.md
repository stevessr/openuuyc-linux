# 验收与回归

## Linux / niri

1. 在 niri 登录会话中运行 `niri msg --json windows`，确认有至少两个真实窗口。
2. 不安装私有拓展时，使用已授权 OpenUUYC 主控连接，进入“高级设置 → 远端窗口”：
   应看到拒绝响应，不得泄露任何窗口标题。
3. 在被控端运行 `sh extensions/window-control/install-linux.sh install`；不依赖 sudo、
   /dev/uinput 或现有 vkms；重连主控并刷新窗口列表。
4. 选择不同工作区的窗口，验证 niri 本地焦点改变；被控端关闭窗口后再选择旧
   条目，应收到拒绝而不是选择新窗口。检查窗口标题中 Unicode 与换行字符。
5. 被控端断开/撤销许可/卸载拓展，已打开的远控端不能再查询或聚焦窗口。
6. 断开连接，主控此前缓存的列表应清除。未配 `NIRI_SOCKET` 的 Linux 会话应
   返回“不在 niri 会话”而非默默控制 XWayland 窗口。

## Windows

1. 以普通用户执行 `install-windows.ps1 -Action Install` 并重启 OpenUUYC。
2. 被控当前登录用户桌面打开记事本与文件资源管理器，主控查询列表并尝试激活；
   Windows 前台锁限制下可以报错，不允许强行绕过锁定。
3. 关闭目标窗口、切换用户/锁屏和卸载拓展后重试，应失败且不会提权。
4. 从官方 UU 客户端连接时仍按原来协议工作；此私有消息不应被发给官方客户端。

## 自动化

- `cargo test --locked --lib window_control` 测试协议编解码、非法动作、缺失
  权限与 niri 列表解析（不需要真实 niri）。
- `cargo test --locked --lib stream_control::window_extension` 覆盖请求拒绝、超时清缓存及忽略迟到回执。
- PR 的 Linux deb 工作流覆盖 Linux 构建，Windows 专项工作流执行
  `cargo check --locked --lib` 和 `cargo test --locked --lib window_control`。
- GitHub Actions 的通过只证明编译/合成测试；真正的远程窗口切换还需两台实体
  设备与实际 WebRTC/UU 协议连接验收。
