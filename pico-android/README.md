# OpenUUYC PICO — UU 协议直连开发版

## 架构

原生 Android APK 自主运行以下代码：`UuNrdApi.kt`（匹配桌面端 `src/account/api/mod.rs` 的 NRD REST/HMAC 和二维码登录）、`UuWire.kt`（Socket.IO v4、控制握手、UU protobuf ConnectOptions/ECHO、gzip SDP）、`UuDirectSession.kt`（原生 Android libwebrtc / UU DataChannel、ICE、视频接收、原生指针输入），以及 `xr_client.cpp`（PICO OpenXR/GLES 双眼空间四边形层）。

PICO 在本机进行 UU 登录，获取设备列表和加入房间；**不需要运行 Linux OpenUUYC、不经过本机 WebSocket 桥接、无需 ADB reverse**。Android 和 PICO 头显本身通过 UU Socket.IO WebRTC 向被控主机连接。

请注意：这里是 *协议实现开发版*，编译通过不代表已经与真实 UU Windows/Linux 主机完成互操作测试。

## 使用

1. 在 Actions 的 `PICO Android OpenXR` 工作流下载 arm64 debug APK，安装到 PICO。
2. 连接互联网，打开应用，点击「使用 UU 二维码登录」，使用另一台设备的 UU 客户端扫码确认。
3. 设备列表展示在线、可被控且无占用的设备；选择目标后将直接请求 NRD 房间，执行原生信令与 WebRTC 连接。
4. 头显将显示双眼空间四边形窗口；使用已连接手柄瞄准和扳机进行远程鼠标控制。

## 功能状态

| 子系统 | 状态 |
| --- | --- |
| UU NRD 签名 REST / 设备身份注册 | 实现代码，CI 测试 HMAC/请求合约 |
| 二维码登录、设备列表、加入房间 | 实现代码，需真实账号联测 |
| Socket.IO 握手及控制请求、WebRTC ICE/SDP | 实现代码，需真实 UU 信令服务器联测 |
| WebRTC H.264 -> EglRenderer -> OpenXR Android Surface | 实现代码，需实机帧呈现验证 |
| 远程鼠标绝对坐标、按键 | 使用 UU CONTROL_DATA_CHANNEL 原生 JSON 格式；发送前检查控制通道和屏幕 ID |
| 完整 UU protobuf CaptureSetting/屏幕/心跳、Mixed KCP、音频、键盘、文件传输 | **尚未实现完整互通** |

## CI

工作流 `.github/workflows/pico-android.yml` 包含 JVM 协议单测、Android Lint、arm64 JNI/OpenXR 构建和 Linux 对照端检查。Actions **无法模拟真实 UU 服务和 PICO 追踪环境**。QR 账号有效性、信令握手顺序、H264 播放和鼠标真正控制远端机器仍需要物理头显与主机双端联测。

## 安全

NRD 登录只请求 https；信令仅使用 wss；应用通过 `usesCleartextTraffic=false` 禁止明文网络。授权令牌只保存在内存中、不写日志；当前版本退出应用需要重新扫码，不做明文自动登录。本实现仅提供已登录且可控设备的正常远程控制，不自动接管已有会话。

保留的 Linux `pico_bridge.rs` 仅作为旧实验性桥接方案，不再是原生 APK 的默认架构。
