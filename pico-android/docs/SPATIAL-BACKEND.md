# PICO 官方 Spatial SDK 双后端重构

## 平台边界

2026-10-27 通过 ADB 读取的当前头显为 **PICO OS 5.15.8 / Android 14 / API 34**。
PICO 官方 Spatial SDK 示例针对 **PICO OS 6**，Spatial UI 示例的 `minSdk=35`。
因此保留现有 `:app` OS 5/OpenXR 兼容版，另建显式启用的 `:spatial` OS 6 后端；不修改头显系统，不通过 `overrideLibrary` 或降低 SDK 门槛强装。

## 官方依据与版本

- [官方示例目录](https://github.com/Pico-Developer/PICO-Spatial-SDK-Samples)，查阅版本 `3111feae0686b9a217d2d1478dc7a4e41af53fc2`。
- [Spatial UI 示例](https://github.com/Pico-Developer/PICO-Spatial-SDK-Sample-SpatialUI)，查阅版本 `9e24cdbd9bc7395f8754b2eb518d422a890057bc`。
  - `SpatialApplication` → `launch(::mainApp)`；`SpatialLaunchActivity`。
  - `DefaultWindowContainer` → `PicoTheme` → 独立 `Subwindow`。
  - 各窗口的 `Toolbar` / `SpatialPopup`，不共用一个 Canvas 菜单纹理。
  - 官方 Maven 仓库、Spatial BOM **6.1.9**、仅 OS 6 构建启用的 AGP **8.8.2**、PICO 版 Compose 替换/排除规则。
- [Spatial Video 示例](https://github.com/Pico-Developer/PICO-Spatial-SDK-Sample-SpatialVideo)，查阅版本 `2b8c03b2d0ac39bb4ceb12151b9aae6cc3f5f00c`。
  - 其 `CypressMediaPlayer` 播放本地/媒体资源，不是 UU WebRTC 接收器，不能直接拿 URL 播放器替换 UU 会话。

未把官方示例代码整包复制进仓库；新模块按其公开 API/生命周期结构实现，SDK 由官方 Maven 仓库解析。

## 目录与共用边界

- `app/`：OS 5 原生 OpenXR 兼容版，默认构建不解析 Spatial SDK。
- `spatial/`：OS 6 Kotlin/Compose Spatial SDK 预览后端，**不使用 `xr_client.cpp`，不编译旧 JNI Activity/Canvas 菜单**。
- 共用现有 `app/src/main/java/org/openuuyc/pico/uu/` 协议实现，并复用 Android 视频/指针 View、视口和颜色；通过构建时筛选共享源码（生成目录位于 build/），不维护第二套 NRD/WebRTC/凭证逻辑。
- 远端画面目前接入 `AndroidView` → `PicoPanelVideoView` → WebRTC EGL Surface；Spatial SDK 负责窗口壳、独立弹窗与工具栏。此 Surface 嵌入路径尚需 OS 6 实机验证，不能用构建通过当作已出画证据。
- 新增 `replacePrimarySurface()` 以适配系统窗口调整大小后的 Surface 重建；重绑不重新加入房间，旧输出释放和新输出创建按渲染队列排序。

## 当前 OS 6 实现与缺口

已接入：
- 官方 Application/LaunchActivity/默认窗口容器、独立子窗和各自独立画质弹窗/工具栏。
- 共用加密登录恢复、二维码登录、设备列表和显式抢占确认。
- 每设备独立 UU 会话，关闭按钮触发会话释放；视频比例随真实帧尺寸更新。
- 画质、帧率、自动编码、切屏、确认创建虚拟屏复用现有受能力约束的协议请求。

仍需迁移/验证：
- OS 6 真机画面、Surface 重建、手柄/手势点击、原生拖动/缩放与弹窗关闭生命周期。
- 手机号登录的新 Spatial UI、远程键盘/输入法。分辨率/DPI、自定义码率和鼠标/触摸切换已接入，仍需 OS 6 实机验证。
- 原有应用自绘背景、窗口整体跟随和 A/B 快捷操作的 Spatial SDK 对应能力；官方 `TabBar.followViewpoints` 不能当成所有串流窗口已支持跟随。
- 同设备多个屏幕同时分窗、分组/拆组和**真正单渲染层合成**；不能宣称换成官方窗口就自动减少合成层。
- PICO 作为 UU 被控端仍未实现。

## 构建与账号数据

```bash
# OS 5 / OpenXR，默认路径不变
 gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# OS 6 预览，显式启用官方 SDK
 gradle -PpicoSpatial=true :spatial:assembleDebug :spatial:lintDebug
```

OS 6 APK：`spatial/build/outputs/apk/debug/spatial-debug.apk`。
两个后端使用相同 application ID `org.openuuyc.pico`，属于**互斥 APK**，不是并装或运行时自动切换。
仅在 OS 6/API 35+ 设备上、使用**同一签名密钥**以更新方式安装，才能保留原账号数据及 Keystore 登录；禁止卸载或清数据来切换。
CI 临时 debug 签名不能假定跨工作流一致，发行版本必须使用同一受管签名密钥。

当前 OS 5 头显不安装此 APK，不更改其系统或已安装兼容版。

## 本轮验证（2026-10-27）

- OS 6 后端：使用实际官方 Maven SDK，`:spatial:assembleDebug` 和 `:spatial:lintDebug` 通过。
- APK 检查：minSdk/targetSdk 为35；含官方 Spatial 入口和共享 UU 会话，不含旧 PicoActivity、PicoXrToolbar 或 libopenuuyc_pico.so。
- 本地两个 APK 的签名证书一致；未在 OS 5 上安装 OS 6 APK。
- OS 5 兼容源码回归：157 项 JVM 测试、Lint 通过；9 项 Python 布局/Manifest/后端隔离检查通过。
- 新增 OS 6 独立 CI 构建/工件任务；本地验证不能称为远端 CI 已通过。
- 尚无 OS 6 物理设备运行证据，未验证其真实 UU 出画、控制、系统原生拖动/缩放和 Surface 生命周期。

## UI 完善（工作台与独立菜单）

- 原生 PICO SearchField/按钮/开关：侧栏导航、账号状态、设备搜索与在线/已打开筛选、连接中禁用、空状态与错误提示。
- 二维码卡片与登录取消；网络失败后允许重试已有加密登录，不强迫重新扫码。
- 已打开窗口页显示设备—屏幕、真实尺寸与会话状态；抢占使用独立确认弹窗。
- 各串流工具栏独立保留设备列表、鼠标/触摸模式、显示器菜单、画质菜单和断开入口。
- 画质以纵向列表、帧率选项呈现；分辨率/DPI、真实可选编码和自定义码率为子页。设置不预先标为成功，显示远端确认/拒绝/超时状态。
- 搜索/排序/筛选/帧率能力边界有独立 JVM 测试。布局与手柄指向的视觉验收仍需 OS 6 设备，不用 OS 5 的渲染截图冒充。
