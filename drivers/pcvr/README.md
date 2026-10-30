# OpenUUYC 内置 PCVR（第一阶段：追踪桥接）

本目录由 OpenUUYC 构建、打包和管理，不启动 WiVRn、ALVR、WayVR 或其他独立串流客户端。SteamVR 是当前桥接目标运行时，不是另一个串流客户端。

## 已实现

- Linux 构建时编译 `driver_openuuyc.so`，嵌入 OpenUUYC 主程序，无需用户另行下载驱动。
- Linux「被控设置 → 内置 PCVR」可安装/更新、注册驱动；同一操作也可用 `OpenUUYC pico-vr builtin-install`。保留既有运行时和外部驱动，首次修改前创建 `openvrpaths.vrpath.openuuyc-backup`，重复安装不重复注册。不自动重启 SteamVR 或启动游戏。
- PICO「沉浸菜单 → 应用 → 内置 VR 追踪诊断」显式开启/停止；默认关闭。
- 独立可靠控制通道 `OPENUUYC_VR_CONTROL_V1`，独立不重传、不排序的追踪通道 `OPENUUYC_VR_TRACKING_V1`，使用现有 UU WebRTC 身份验证和加密链路。
- 追踪头部及左右手 **grip pose**，不是菜单相对位置或 aim 射线。使用 OpenXR LOCAL 空间，窗口拖动/跟随不改变追踪坐标；带有效位、预测显示时间、线/角速度和两个数字按键位。暂不注入手柄按键或骨骼。
- 复用当前有效会话的远程终端授权（不支持访客）；撤销权限、断连和停止均停止发布。进程内所有权与跨进程文件锁防止并发占用。
- 本机 Unix datagram IPC 位于 `$XDG_RUNTIME_DIR/openuuyc-pcvr/tracking.sock`，目录 0700、socket 0600；不监听额外公网端口。
- 驱动暴露 **三个 GenericTracker 诊断设备**，不是 HMD，不抢占现有头显。250ms 无有效帧自动报告断开；驱动正常退出释放 socket，崩溃后通过排他锁回收旧 socket。

## 尚未实现，不能据此游玩 VRChat

Linux 双眼 GPU 纹理导出/编码与端到端媒体同步、时钟校准/预测、真实眼位/FOV/IPD 协商、OpenVR 手柄输入/震动。SBS 分片接收、硬件解码和双眼投影已有代码与本地测试，但还没有真实游戏帧源接入。服务端仍报告 `stereo: false`；VRChat 快速入口仅可明确确认后在远端启动，不代表可玩 PCVR。

## 协议与检查

`pico-android/app/src/main/cpp/pcvr_protocol.h` 是原生端与驱动共用的 v1 wire 定义。小端、固定 224 字节；头 32 字节 + 三组 64 字节设备状态。米、右手坐标、xyzw 四元数。远端 XrTime 不直接与 Linux monotonic 时钟相减；当前不是延迟测量或完整预测实现。Linux 按序号拒绝重放/乱序帧，校验尺寸、版本、有效位、有限值、四元数范数和范围。

```sh
cargo +1.95.0 check --lib --bin OpenUUYC --offline
cargo +1.95.0 test --lib pcvr::protocol --offline
c++ -std=c++17 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Wall -Wextra -Werror -Wno-unused-parameter \
  drivers/pcvr/tests/driver_test.cpp -o /tmp/openuuyc-pcvr-test
/tmp/openuuyc-pcvr-test
```

驱动测试使用模拟 OpenVR host 和真实 Unix socket，覆盖姿态/速度转换、非法包、失联、排他占用、清理与崩溃遗留 socket；不等于 SteamVR/头显实机验证。

OpenVR ABI 依据 Valve 官方 SDK v2.12.14，来源与许可见 `vendor/`。下一阶段应实现真实 GPU 帧提交后再增加 HMD/display component，不能仅靠添加假的显示参数启用 VRChat。

## 后续本地验证进展

已加入使用真实 runtime 眼位/FOV 的双眼 GLES 投影探针（本地网格，不是远端媒体）和内部 debug 仪器测试。控制协议改为带 `id` 的 `{ "id": 1, "op": "start" }` / `stop` 请求；周期状态使用 id=0，仅能撤销授权，不能自行开启追踪。PICO 侧限制单个会话占用；迟到回复、停止后的旧回复与超时均不能重新开启。详见 `pico-android/docs/PCVR-VALIDATION-2026-10-29.md` 的实际测试范围。

## OpenXR-first 后端集成

默认策略已改为内置 OpenXR 优先，SteamVR 仅作备用。Monado/xrizer 源码组件已经实际构建、安装，进程级运行时选择与原生 loader 探针详见 [OpenXR 组件](../openxr/README.md)。此目录的 OpenVR 驱动仅是备用追踪诊断，不再作为必须依赖的主路线。后续 OpenXR 设备驱动已通过私有共享快照接入，详见 OpenXR 文档的 device bridge follow-up；仍不能将设备可识别解释为双眼视频链路就绪。

双眼接收基础：单路 SBS access unit 保持左右眼配对，携带每眼渲染 pose/FOV；最大 4 MiB、48 KB 分片、丢失/过期/乱序恢复要求 IDR。PICO 使用硬件 MediaCodec 向一个 Android Surface swapchain 输出，两份 ProjectionView 引用同一图像的左右区域；使用码流携带的渲染视图而不是把当前头姿误当渲染姿态。250 ms 无解码帧停止提交，保留工具栏/射线，禁止点击隐藏的桌面四边形。该路径尚未经过真实头显/游戏帧验证。

### SteamVR 既有场景附加：开发状态

已加入基于官方 OpenVR v2.12.14 客户端头文件的背景镜像读取器，源文件和 SHA-256
记录于 vendor/openvr-client.lock.json，复用 Valve BSD-3-Clause 声明。
不分发 SteamVR 客户端二进制，也不启动 SteamVR、游戏或切换全局运行时。

已实现代码路径：
- 动态加载本机已安装的 OpenVR API，以 VRApplication_Background 初始化；
- 查询当前场景 PID/最后提交帧 PID，场景切换时终止当前附加；
- 通过 GetMirrorTextureGL 与显式共享纹理锁读取两眼，使用隐藏 GL 上下文缩小回读；
- 检查两眼之间的帧号/场景未改变，保留真实合成器时间、渲染头姿和眼位/FOV；
- 将外部坐标空间明确标记为 origin=1，宿主目前拒绝未对齐的外部姿态，
  防止直接当作 PICO LOCAL 投影而造成错误视点。

可执行只读诊断：

    OpenUUYC pico-vr steamvr-mirror-probe

构建镜像辅助程序需要 SDL2/OpenGL 开发文件。缺少时基础客户端仍可构建，
诊断命令明确报告未构建辅助程序，不静默退回桌面镜像。
当前仅验证 SteamVR 未运行时的 Background 错误返回与不启动服务，以及姿态数学/
外部坐标拒绝测试；尚未验证活动 SteamVR 场景的真实 GPU 取帧。
外部坐标对齐、附加控制权/活动 HMD 校验、接入授权会话及 PICO 实机出图仍未完成。
