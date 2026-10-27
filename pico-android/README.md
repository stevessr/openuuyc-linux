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
| WebRTC H.264 -> EglRenderer -> OpenXR Android Surface | 对齐桌面端的 5 条接收视频 m-line，按当前屏幕选定视频轨道，增加首帧/黑屏监测；需实机验证 |
| WebRTC 远端音轨 | 接收音轨并管理 Android 音频焦点，需 PICO 实机听音验证 |
| 远程鼠标移动、扳机按键、摇杆滚动 | 使用 UU CONTROL_DATA_CHANNEL 原生 JSON 格式；OpenXR 失焦会主动释放按键；滚动绑定 PICO 摇杆 Y 轴，仍需实机验证 |
| UU CaptureSetting 初始化请求、PB ECHO、屏幕列表解析 | 注册 SDP 成功接受的全部视频轨道并等待 ACK；屏幕 ID/track 关联与输入授权有状态机测试 |
| Mixed KCP、麦克风、键盘、文件传输、完整异常恢复 | **尚未实现完整互通** |

## CI

工作流 `.github/workflows/pico-android.yml` 包含 JVM 协议单测、Android Lint、arm64 JNI/OpenXR 构建和 Linux 对照端检查。Actions **无法模拟真实 UU 服务和 PICO 追踪环境**。QR 账号有效性、信令握手顺序、H264 播放和鼠标真正控制远端机器仍需要物理头显与主机双端联测。

## 可靠性与输入

- UU 房间的多个已授权信令节点支持**命名空间建立前**的自动切换；连接建立后不重放可能改变远端状态的请求。
- 房间控制握手 30 秒、WebRTC ICE 35 秒超时会终止失效会话；发送采集请求 25 秒无首帧仅告警，避免主机短暂停止输出被强制断开。
- 二维码确认后会强制刷新设备注册；轮询间隔 1 秒，重入登录与旧会话回调不会覆盖当前连接。
- PICO 4 / 4S / Neo3 手柄摇杆 Y 轴触发滚轮事件（120 单位，120ms 节流，45% 死区）；头显失焦、停止运行、屏幕切换时释放远端按键。
- 远端音频使用 Android WebRTC 接收，获得音频焦点才启用音轨；音频焦点丢失会停止播放。


### SDP / ICE / 数据通道完整性

- 等待 UU 原生控制 ACK 后生成 WebRTC Offer；本地 ICE Candidate 在完整 Offer（二进制 gzip 附件）已入队后发送，远端候选则等待 SDP Answer 安装成功。每个方向最多保留 128 个待处理候选，重复项合并，旧会话候选在退出时清理。
- 按桌面协议创建 1 条音频及 5 条独立接收视频 m-line。根据 Answer 中未被拒绝（端口非零）的轨道构建 `SendVideoTrack` 注册列表，不抢先在 SDP 安装前发起视频注册。
- 收到匹配的 `SendVideoTrackRsp` 后才请求视频采集；5 秒无注册 ACK 或控制通道断开会结束失效的会话。
- 远端屏幕切换后重新选择 `video_N` 对应的渲染轨道，不允许迟到的其他轨道覆盖当前画面。
- 遥控鼠标松开优先于位置移动消息。在背压、屏幕切换、XR 失焦与通道关闭情况下尽力释放；释放入队失败则关闭会话，不继续发送控制输入。
- ICE 首次建立最多等待 35 秒，已连接后断线提供 15 秒恢复窗口；如未恢复则关闭失效连接。上述行为有独立 JVM 回归测试，尚须 PICO 设备/UU 主机真实联测。

## 安全

NRD 登录只请求 https；信令仅使用 wss；应用通过 `usesCleartextTraffic=false` 禁止明文网络。授权令牌只保存在内存中、不写日志；当前版本退出应用需要重新扫码，不做明文自动登录。本实现仅提供已登录且可控设备的正常远程控制，不自动接管已有会话。

保留的 Linux `pico_bridge.rs` 仅作为旧实验性桥接方案，不再是原生 APK 的默认架构。

## 测试与验收

所有 JVM 协议测试在无生产 UU 账号的 CI 中运行；包括协议格式、QR/NRD、Socket.IO、ICE 时序、视频轨道绑定、WebRTC 状态与鼠标输入背压等测试类。CI 自动保存 XML/HTML 报告，即使单测失败也可以在 Actions Artifacts 中定位具体断言。

- **自动测试**：HMAC 签名的时间、HTTP 方法、路径、请求体、设备身份绑定；MockWebServer 上的真实 HTTP 请求、身份作用域、拒绝重定向、脱敏、4 MiB 响应限额。
- **协议回归**：UU Socket.IO ACK、protobuf ECHO、视频轨道 ACK、屏幕 ID 与轨道绑定、SDP 重写/gzip、异常 protobuf 和有界随机输入。
- **状态机回归**：只允许 CONTROL 通道完成 PB 握手，ACK 必须匹配请求序号，收到轨道成功 ACK 才能请求采集；切换显示器/断线立刻撤销输入权限。
- **实机基础冒烟**：连接 PICO USB 调试后运行 `bash pico-android/scripts/pico-adb-smoke.sh [debug.apk]`，检查安装、Activity 启动、进程存活与崩溃日志。该脚本不等于 UU 账号、ICE、视频及手柄端到端测试。
- **实机端到端验收**：依照 `pico-android/HARDWARE-TESTING.md` 对照真实 UU 被控主机逐项检查。

当前未提供可公开的 UU 测试账号、远端机器和 PICO 设备，CI 无法验证真实远程控制成功。请勿把纯协议测试通过等同于能生产使用。

## 多窗口（PICO OpenXR）

PICO 原生客户端目前按 XR runtime 支持的合成层上限创建**最多 3 个独立空间面板**：中间为 UU 当前选择的屏幕轨道，左右分别绑定其他已解码且确实存在的 `video_N` 轨道。每个面板都有独立的 Android Surface、WebRTC EglRenderer、OpenXR Surface Swapchain；空间定位器按面板命中计算鼠标坐标。没有对应视频轨道时不显示空白面板；不将重复 RTP 轨道当成新窗口。

仅使用 UU `ScreenSources` 明确返回的 `screen_id ↔ video_track_index` 映射控制侧窗；映射未知的侧窗只能观看，不能将鼠标输入错误发送到默认显示器。跨窗移动、失焦时释放按键；本实现暂不保证 UU 主机真的同时推送多个视频轨道，需真实双/三屏主机测试。

## PICO 4 Ultra 相机接口与权限

已补 Android `CAMERA` 按需权限请求（不再安装即索要）、Camera2 设备枚举与主动预览入口；打开预览后，若系统不公开 Camera2 ID，应用显示设备不支持的具体原因而不是无响应。页面可缩放，预览失焦/切换应用后会关闭摄像头释放资源。

**PICO 4 Ultra 透视彩色相机不保证暴露为 Android Camera2**：消费版 PICO 的 XR 透视摄像头须经 PICO SDK 对应的专用 XR 相机扩展与平台权限才能获取。当前 Camera2 代码是可用设备的预览路径和错误诊断，**不是** `XR_PICO_camera_image` 透视相机接入的替代品。未经真实头显验证前不得视为原生透视取流修复完成。
