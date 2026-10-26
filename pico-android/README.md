# OpenUUYC PICO 原生头显客户端

这一目录是 **Android/arm64 OpenXR 客户端（APK）**，不是 Linux WayVR 窗口映射。原生引擎通过 PICO OpenXR 运行时建立双眼空间四边形图层，`XR_KHR_android_surface_swapchain` 直接返回 Android Surface，MediaCodec 以 H.264 硬解码输出到图层；控制器通过 OpenXR Action（PICO 4/4S/Neo3、simple_controller 回退）获得 aim/trigger 并映射为屏幕绝对坐标与鼠标按键。

## 支持的连接方式

现阶段桥接的 **UU 登录、设备发现、WebRTC/ICE、视频协商与输入发送均仍由 Linux OpenUUYC 主控端负责**。头显是 Linux 主控的原生 OpenXR 显示/交互终端，不是直接登录 UU 云的独立第三方客户端。

为了避免在 LAN 明文传输令牌、屏幕与控制事件，Linux 桥接程序固定仅监听 `127.0.0.1:18765`；**必须**通过 USB ADB reverse 或自行建立安全 TLS 隧道接入。不能直接用 PICO 的局域网 IP 访问桥接。不要在公共网络开放/映射 18765 端口。

### USB ADB 操作流程

1. 在 Linux 上构建最新 OpenUUYC，运行 `OPENUUYC_PICO_BRIDGE_TOKEN="$(openssl rand -hex 32)" OpenUUYC gui --codec h264`。**注意保存这次生成的令牌**，头显端需要填写同一个令牌。更建议生成并以当前 shell 会话变量复用：

   ```sh
   export OPENUUYC_PICO_BRIDGE_TOKEN="$(openssl rand -hex 32)"
   printf 'PICO pairing token: %s\n' "$OPENUUYC_PICO_BRIDGE_TOKEN"
   OpenUUYC gui --codec h264
   ```

2. 以原有 UU 登录与远控界面连接目标电脑、开启远端输入。初始化观看窗口时桥接服务器才会启动。服务监听 127.0.0.1:18765。
3. 在 PICO 开发者模式开启 USB 调试并连接主机，`adb devices` 检查授权，再执行 `adb reverse tcp:18765 tcp:18765`。
4. 从 [PICO Android OpenXR](../../.github/workflows/pico-android.yml) Actions 下载 `openuuyc-pico-debug-arm64` APK，`adb install -r app-debug.apk`。
5. 在头显启动 **OpenUUYC PICO**，输入 Linux 上生成的令牌，点击开始。VR 内出现远控屏幕，用右手柄射线瞄准、扳机点击；无右手柄时可用左手柄。

开发者本机可直接 `cd pico-android && gradle :app:testDebugUnitTest :app:assembleDebug`，需要 JDK 17、Android SDK 35、NDK 27.2、CMake 3.22.1。CI 会编译 OpenXR C++、验证 Android H.264 Annex-B 解析与 Linux Rust 桥接层的单元测试，并上传可安装的 arm64 APK。无实体设备时 CI 不能证明 OpenXR 运行时、手柄映射、延迟、热插拔或 MediaCodec 视频播放正确。

## 边界与后续

- **已有**：标准 OpenXR 实例/会话/生命周期、GLES3、Surface swapchain、立体空间层显示、MediaCodec H.264、控制器 6DoF 射线与 trigger 映射、认证/控制释放、桥接单连接、GitHub Actions 构建和测试。
- **尚未有**：独立 PICO APK 直接向 UU 服务登录/建立 WebRTC、音频输出与麦克风回传、手部骨骼追踪、游戏手柄全按键、HDR/AV1/HEVC、无损全真彩与 PICO 原生低延迟调优、多窗口/VR 立体远端内容。独立直连需要拆出可移植 Rust UU 协议核心并建立 JNI/FFI；不应把这个桥接原型包装成以上功能已经完成。
- 网络协议：首个文本消息 `{"type":"auth","token":"..."}`，验证后收到 `hello(version=1,codec=h264)`；二进制帧格式为字节 0 值 1、字节 1 为关键帧标记、后续为 H.264 Annex-B。控制消息 `control(enabled)`、`pointer(u,v,down)`、`key(vk,down)` 与 `scroll(delta)` 使用 UTF-8 JSON。远端输入本身继续经过既有 UU 控制通道和安全校验。

兼容设备以官方 PICO OpenXR SDK 为准（Neo3、PICO 4 系列、4 Ultra，OS 5.13+ 为统一开发基线）。实际需针对不同固件检查与适配。
