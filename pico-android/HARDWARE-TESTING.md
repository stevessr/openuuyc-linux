# PICO UU 直连：真实硬件验收矩阵

此文档用于连接**本人授权控制的** UU 主机与 PICO 头显。自动 CI/JVM 测试无法访问实时 NRD 房间、受控端、Wi‑Fi 环境以及 PICO 的 OpenXR 追踪数据。

## 环境

- PICO 4 / PICO 4 Ultra / Neo3（记录型号、PICO OS、Android API、OpenXR Runtime；未测试机型必须标记未测）
- 同时准备 UU 登录手机以及正常在线、允许控制的被控电脑
- 两种网络路径：局域网，以及不同 NAT / Wi‑Fi 网络
- 记录 APK 版本、Git SHA、被控端 UU 版本与使用的编解码器

## 执行步骤与通过条件

| 项目 | 操作 | 通过条件 |
| --- | --- | --- |
| A1 启动 | `bash pico-android/scripts/pico-adb-smoke.sh <APK>` | 应用进程存活，无崩溃 |
| A2 XR | 戴上 PICO 运行应用并移动头部 | 双眼场景稳定，四边形图层正确呈现 |
| B1 UU 登录 | 在头显生成二维码，使用手机 UU 扫码并确认 | UU 设备列表加载，Token 不进入 Logcat |
| B2 鉴权 | 选择离线、不可控、有其他使用者的设备 | 禁止未经允许的远控/强制接管 |
| C1 房间 | 选择在线且可控的机器 | 依次完成房间登录、控制 ACK、WebRTC ICE |
| C2 解码 | 检查 Windows/Linux 主机桌面视频 | H.264 帧持续显示；日志提示首帧，25 秒无帧有警告；记录首帧耗时 |
| C3 输入 | 手柄指向桌面并按下/松开扳机 | 远端鼠标按预期移动、按键释放，退出后不残留按下状态 |
| C3a 滚轮 | 将手柄摇杆上下拨动 | 远端窗口滚动，死区内无误触发，松开后停止连续滚动 |
| C3b 音频 | 在远端电脑播放系统声音或测试音 | 头显能听到 WebRTC 远端音频，切到系统菜单/其他音频应用后遵守 Android 音频焦点 |
| C3c 失焦释放 | 在远端按住拖拽时打开头显系统菜单或摘下头显 | 被控端马上收到鼠标释放，恢复后不继续拖拽 |
| C4 多屏 | 主机有两个屏幕，切换可用屏幕 | 匹配正确的 video_N RTP 轨道，不把旧屏幕的 ACK 或视频混入新屏幕 |
| D1 恢复 | 在连接期间切断 Wi‑Fi，再恢复网络 | 应撤销控制，不能复用失效的旧会话 ACK；记录重新连接流程 |
| D1a 信令节点 | 令 UU 返回的首个合法信令端点无法建立连接 | 尝试下一个由 UU 房间授权的节点，不向未经授权的 URL 发送令牌 |
| D2 生命周期 | 切换应用/摘下头显/休眠和恢复 | 不崩溃，Surface 不泄露，输入权限安全复位 |
| D3 错误处理 | 登录超时、设备拒绝、房间 4xx/5xx、ICE 超时 | 清晰显示可恢复错误，不打印 Token 或登录隐私 |
| D4 乱序 ICE | 模拟 Offer 尚未发出时生成本地 ICE、Answer 尚未安装时到达远端 ICE | Candidate 不抢在 SDP 前发送/安装，也不重复；断线时队列清空 |
| D5 数据通道背压 | 模拟按住并释放鼠标时的 CONTROL 通道高缓冲 | 鼠标释放优先入队；无法释放则断开控制，不继续拖拽 |
| D6 视频轨道注册 | 测试单屏、双屏及被拒绝的视频 m-line | 仅注册 Answer 接受的轨道，5 秒无 ACK 有明确超时；没有选错视频轨道 |

## 记录模板

| 字段 | 记录 |
| --- | --- |
| APK Git SHA | |
| PICO 型号 / OS 版本 | |
| UU 主机平台 / 客户端版本 | |
| 网络（LAN/NAT/移动热点） | |
| 实测分辨率/帧率/编解码 | |
| 首帧延迟 / 输入 RTT | |
| 已通过项目 | |
| 失败步骤与复现过程 | |
| 经脱敏的错误日志链接 | |

**诊断注意**：仅收集必要日志，提供给外部人员前请删除 UU token、设备 ID、二维码跳转 URL、ICE 候选公网 IP 和任何包含账号的请求头。当前 CI 通过仅说明构建、静态协议和模拟 HTTP 行为成立，不能代替该验收矩阵。

| C5 多窗口双屏 | UU 被控电脑双显示器，分别播放不同动态图案 | 主窗与侧窗显示不同 track；远端不支持第二轨道时不出现假窗口 |
| C5a 跨窗拖拽 | 选中侧窗/主窗反复移动与点击，按住扳机移向另一窗口 | 切换前安全松开，侧窗缺失明确 ScreenSources 映射时不可发送控制 |
| C5b OpenXR 层数不足 | 在低层数 XR runtime 上启动 | 主窗仍可见，不因第二/第三 Surface 创建失败崩溃 |
| E1 Camera2 权限 | PICO 4 Ultra 点击相机预览，首次拒绝然后允许 | 权限拒绝能显示说明，允许后能正确枚举/报告实际支持情况 |
| E2 PICO 相机接口 | PICO 4 Ultra 实机 Camera2 ID 缺失或透视摄像头受限 | 显示具体不可用信息；不得默认为已打开 PICO 透视相机 |
| E3 相机生命周期 | 开启 Camera2 预览后切换应用/休眠/返回 | 相机资源关闭且无后台预览，重新点击能再次请求，不能泄漏 CameraDevice |

| E4 PICO MR 背景 | PICO 4 Ultra 开启「现实透视背景」并启动 UU 空间窗口 | 若 XR runtime 支持 Alpha Blend，真实环境在三窗口背后出现；关闭开关后恢复 VR 背景 |
| E5 XR 不支持 MR | 不提供 Alpha Blend 的 OpenXR runtime | 只使用 Opaque，主显示不受影响，日志/状态提示不支持 |

## 2026-10-27 PICO A9210 实机记录

- Android API 34，PICO OS 5.15.8；5 GHz Wi-Fi 6。网络规格不等于串流性能已验收。
- 启动黑屏：登录 Activity 被声明为 VR，系统跳过普通 View 绘制。拆分普通登录面板与独立 OpenXR Activity 后，双眼截图确认面板可见。
- 注册失败：相同注册请求的 `platform=2` 返回 HTTP 500 / code 9999，改为该 Windows 兼容端点使用的 `platform=1` 返回 HTTP 200 / code 0，重复请求身份稳定。OS 字段仍报告 Android。
- 首次迁移身份后，旧身份生成的二维码兑换返回 HTTP 400。已将强制注册移至二维码生成前，兑换前校验身份保持一致；用户随后确认登录成功。
- 登录 UI 已改为深色双栏、二维码卡片与明确的操作状态；92 个 JVM 测试通过。
- 进入 OpenXR 后抓到 CheckJNI 崩溃：运行时返回 Global 引用，旧代码调用 `DeleteLocalRef`。修复为仅创建/释放应用自己的 Local 引用；主机 JNI 模拟测试通过。**实际视频帧、输入与局域网 ICE 路径仍待实机确认，不计为串流通过。**

## Linux 风格 UI / 持久化登录更新验收

- 已同步远端 PICO 分支 `81ec578`（保留多窗口、Camera2 权限与系统透视）及上游 Linux `b3e677b`，本地合并提交 `51b9851`。
- Linux Rust 1.95 `cargo check --locked --bin OpenUUYC` 通过；默认 nightly 版本过旧的 MSRV 报错未通过改锁文件绕开。
- Android APK、测试 APK、107 项 JVM 测试与 Lint 通过；Lint 仍有警告，不宣称零警告。
- 头显 UI fixture：搜索、筛选、排序、连接禁用、导航、600dp/1280dp 重排通过。截图位于 `docs/ui/`，均为虚构 UI 测试设备。
- 真实 AndroidKeyStore：独立命名空间的测试凭证由一轮 instrumentation 保存，另一轮独立进程读取；确认随机 IV、密文不包含原始 token/user ID、篡改拒绝与清除后无凭证。
- 自动恢复策略：网络/服务端临时失败保留密文，明确凭证失效才清除。用户首次登录后，真实 UU 账号已在冷启动以及显式 force-stop 后再次自动恢复，并加载设备列表；无需重新扫码。该账号恢复结果不代表视频首帧或 ICE 路径通过。

- 串流退出补充：抓到 libwebrtc `network_thread` 的 SIGABRT；代码审查发现 `terminateUnexpectedly()` 从 SDK 回调直接调用同步 `dispose()`。已按照 WebRTC PeerConnection 的异步销毁要求拆分立即撤销与排队清理，所有等待原生线程的释放操作移到会话锁外，并补回调退出前不执行释放、并发只入队一次的测试。**尚未用真实视频成功证明该防护解决全部退出原因。**
- 最终 Android 单测数量为 109，APK 与 Lint 通过；上面 107 是加入异步清理测试之前的阶段结果。

## network_thread 重复崩溃的进一步定位

- 新版仍在同一组 `libjingle_peerconnection_so.so` 地址 SIGABRT，否定了“只靠异步释放即可解决该崩溃”的假设。
- 对相同 BuildId 的实际 AAR 库反汇编：`0x36a9c0` 位于调用 Java `org.webrtc.NetworkMonitor.startMonitoring(Context, long, String)` 的 JNI 包装路径。
- 安装包只请求 INTERNET/CAMERA/OpenXR，没有 ACCESS_NETWORK_STATE。在旧安装包上下文中直接执行同一个 Java `NetworkMonitor.startMonitoring()`，捕获并确认包含 ACCESS_NETWORK_STATE 的 SecurityException；避免经 native JNI 包装再次把诊断变成进程崩溃。
- 修复为声明普通网络状态权限；不请求定位、Wi-Fi 扫描或修改路由器。创建 peer 前再校验权限，缺失时走可恢复错误而不是进入会导致 native abort 的回调。
- 新增独立 instrumentation `vault-phase=network`：真实 libwebrtc factory/peer/DataChannel/offer 创建及关闭循环三次，不用 UU 账号，也不冒充真实视频/ICE 互操作测试。

- 修复后实机结果：正常网络权限已由系统授予；真实 libwebrtc peer 创建、DataChannel、createOffer 与释放连续三轮通过。新版冷启动真实账号再次自动恢复。该结果证明已越过原先启动网络监控时的权限崩溃点，尚不等于远端视频首帧/输入成功。

## 默认非沉浸式控制路径

- 主操作「远程连接」路由为普通 `PanelViewerActivity`，仅显式的次操作「沉浸式连接」路由为 `XrActivity`；两者共用已经授权的 UU 连接协议，凭证仍在进程内一次性交接。
- 普通模式用 SurfaceView/EglRenderer，而非 OpenXR；窗口可缩放、保留视频比例，Surface 重建不重新加入房间；触摸/鼠标点击拖动、滚轮与失焦释放已经接线。
- 113 项 JVM 单测、Android APK/测试 APK 与 Lint 通过；增加默认 WINDOW 模式、纵横比与输入坐标回归。单测不代替普通窗口真实视频首帧和远端鼠标验收。

## 2026-10-27 沉浸式工具栏增量

- 本地 Android debug + androidTest 构建、lint 通过，126 个 JVM 测试通过（含原生虚拟显示 RPC 字段、错误 ACK、匹配设置确认、超范围设置拒绝）；5 个 Python 检查通过。
- ARM64 native 重新编译成功；JNI Surface 所有权测试与空间布局拖动锚点/缩放上下限测试通过。
- 新 APK 使用 `adb install -r` 已安装到 PICO，未卸载/清空数据。PICO 上四个 fixture 阶段（独立 Keystore 保存/跨进程恢复、真实 WebRTC 初始化与清理、合成登录设备 UI）均通过。
- 新增的空间工具栏、远程参数修改、实际多屏出图、真实虚拟屏创建以及会话抢占仍需真人端到端验证。没有为了验证抢占而断开任何已有会话。
- 佩戴测试：选择“沉浸式连接”，看下方工具栏；点“拖动 / 缩放”后扳机移动桌面、摇杆调大小，再点完成。核对编辑期间远端无鼠标拖动。更改帧率等待确认后看标签变化；虚拟屏需先确认、再观察屏幕列表新增。已有会话抢占应出现明确确认弹窗，取消不得发起抢占。

## 模拟窗口、自动编码、运行时模型探测

- 已替换全桌面编辑模式为独立透明窗口装饰：标题栏/底部条抓取、右下角缩放、抓取转向进行水平旋转；工具栏可切换 VIEW 跟随视角 / LOCAL 空间固定。该交互为自实现，不是 PICO 系统窗口 API。仍需佩戴验证。
- UU 自动编码 `codec_type=0` 已作为默认选项，普通窗口设置和 XR 工具栏同步支持。新增自动编码线协议与菜单命中边界测试，合计 130 个 JVM 测试通过；lint、ARM64 构建、5 个 Python 检查通过。
- 新 APK 保留数据安装成功；设备 Keystore 保存/恢复、真实 WebRTC 初始化、UI fixture 四阶段通过。
- 头显真实 loader 扩展探测通过：`XR_BD_controller_interaction`、`XR_EXT_hand_interaction`、`XR_EXT_hand_tracking`、`XR_EXT_interaction_render_model`、`XR_EXT_render_model`。这是运行时声明支持；尚未创建/读取具体交互模型，也未完成模型绘制，不能当作手/手柄已渲染的证明。

## 后续增量证据

- 真实 PICO 硬件探测：CPU `QTI SXR2230P`；内存 `11736 MiB`；GPU `Adreno (TM) 740`；系统默认显示模式 `4320x2160`。这些是设备读取结果，不是硬编码值。Android 硬件报告 + 原有设备测试四阶段通过。
- 更新已采用保留数据安装；134 个 JVM 测试、Android lint 与 ARM64 native 编译通过。新窗口 atlas、四角缩放、独立悬停、关闭窗口、自定义切屏、真正房间锁释放仍需真人端到端确认。
- Linux 活跃日志确认虚拟触摸屏可用，但当前全局 Niri 触摸映射 `DP-1` 与远控屏不匹配，主动退回指针模拟。
- 活跃 Niri 二进制拒绝具名 touch 配置，尽管本地另一个 Niri 源码树支持；未写入这种不兼容配置，也未重启合成器。
- 获用户明确允许后，测试运行时临时 KDL：校验、加载 `HDMI-A-1` 全局触摸映射、加载原配置恢复均成功；原配置文件未修改。代码加入 opt-in 会话租约与进程退出看护恢复。

- 设备注册成功回应已核对：本机仅在接口成功后写入的硬件上报缓存包含 `QTI SXR2230P`、`11736 MiB`、`Adreno (TM) 740` 以及成功时间；不是只读到设备属性却未发送。
- Linux 11 项输入引擎测试最终通过（曾发现旧测试的未配对/旋转错误优先级不一致，已调整为先检查配对）；release 构建通过并启动新进程，已核实 `OPENUUYC_NIRI_TEMP_TOUCH_MAP=1`。
- XR 工具栏补充鼠标/触摸模式开关：触摸模式扳机映射成 UU 原生单指开始/更新/结束包；不是将鼠标按钮换个标签。普通窗口已有多触点模式。需要实际接触测试，不能以注入代码编译成功代替客户端收到 wl_touch 的证据。

## 视角/深度/菜单与三键盘模式

- JVM 138 个测试通过，包含完整键盘键值覆盖、键中心命中、修饰键关闭释放和新的 400px 菜单坐标；Android lint 通过。
- 新增 native 深度测试：近远方向、0.45/6 米边界，以及非世界 Z 方向的点击视角基准均通过；ARM64 native 编译通过。
- 菜单可拖动、带点击时 VIEW 偏移的跟随空间、抓取摇杆深度、三模式键盘属于新增佩戴交互，不能以构建结果代替真人验证；尤其系统 IME 的沉浸式显示仍未确认。

## 握持拖动 / 所有窗口用户圆心 / 焦点与射线

- 新增 pure-native 测试通过：握持 350ms 阈值、切换目标重新计时、捕获后不换目标、松开清除；不同窗口宽度与菜单的用户圆心球面拖动、背面方向；射线 billboard 基向量正交与退化方向处理。
- ARM64 native 编译通过；JVM 138 项测试和 lint 继续通过。触摸点、真实空间射线、握持手感与其他桌面/菜单一致性仍需佩戴验证。

## 每屏设置、比例与背景增量

- 新增 native 横屏、竖屏、方屏、超宽屏 100 英寸对角线边界测试通过；窗口比例来自实际解码帧，而不是统一 16:9。
- 每屏设置请求新增目标 screen_id 线协议测试。虚拟屏创建仍是请求/ACK/实际 ScreenSources 更新三步，不以按钮点击代替完成。
- 默认非 MR 黑背景，背景合成层与窗口设置展开位置均需实际佩戴验证。未实现图片/全景背景选择。
- 触摸懒映射按用户澄清限定在实际触摸目标改变时，当前已映射输出复用；没有把“菜单切屏”当作写入全局映射的触发条件。

- 本轮最终验证：139 项 JVM 测试通过，lint/ARM64 构建通过，PICO 保留数据安装成功。Linux 11 项输入引擎测试通过，release 构建通过；新本地进程已核实启用临时映射 opt-in。
- 仍需真人确认：竖屏/横屏实际画面比例、标题对应关系、顶部菜单命中和每屏参数、真实虚拟显示器创建、非 MR 背景及跨屏原生触摸。未提供图片背景导入。

### Immersive home, SMS and layout recovery (2026-10-27)
- Menu → 主页面 lists immersive connections only; additional targets reserve an unused
  pane, without replacing a visible stream. Verify two real devices concurrently;
  fixtures do not prove simultaneous live streams. Busy targets require confirmation.
- Phone login uses official mobile-code/by-mobile endpoints with agreement consent,
  resend cooldown and existing encrypted account storage. Sending an SMS requires
  the user's explicit button press; actual SMS delivery/login remains unverified.
- Initial layout and Reset Layout anchor all windows/menu at current head position
  and horizontal facing, rather than assuming LOCAL origin equals eye position.
  Test sitting/standing and facing away from the tracking origin; verify visibility,
  menu hit testing, follow on/off, then Reset Layout. Composition errors are logged.

- 2026-10-27 更新：移除握持 350ms 阈值；首次输入帧立即抓取，保持目标直至释放，释放后无冷却。对应 native 回归测试通过；实机手柄延迟仍需体验确认。

### 环境与跟随复测
1. 原地转头后进入 XR，开启跟随；菜单和桌面在开启瞬间不应跳动，转头后保持各自相对位置。
2. 跟随中用扳机操作菜单，右手 B 关闭；继续转头验证桌面和菜单都固定在关闭瞬间的位置。
3. 抓住菜单拖动条，摇杆推远/拉近；检查远近端限位和按钮点击不误触。
4. 环境 → 透视可见度：0/50/100%，分别确认背景覆盖、混合、完整透视，桌面不透明度不变。
5. 导入本地图片、取消选择、导入损坏/超16MiB文件，再重启验证保存。不得把 fixture 当作这些真实 XR 交互的通过证据。

### 2026-10-27 原生菜单抓取 / 三维朝向 / A 跟随
- 内侧握持扳机指向菜单任意位置即可立即抓取；释放结束，期间不执行菜单按钮。菜单拖动条的食指扳机操作保留。
- 桌面和菜单增加 pitch，绘制、射线命中、四角缩放与抓取锚点共用同一三维基向量；上下移动不再仅平移且保持竖直。
- 右手 A 在 OpenXR 输入线程按实际按键时间捕获布局相对 VIEW 的姿态并切换跟随；B 关闭。历史追踪时间不可用时才尝试当前帧，均无有效追踪则不启用。
- 原生测试覆盖上下球面轨道、朝向用户的法线、非中心抓取锚点及倾斜后射线坐标。实机 A/B 和菜单握持须人工验证。

### 程序生成背景复测
- JVM 覆盖全部十种预设：尺寸、不透明像素、同种子确定性、七种生成图案更换种子的变化、无效预设回退和尺寸分配上限。
- 设备需人工检查环境 → 背景库中各背景显示、MR 混合、25–100%亮度、重新生成、重启恢复及切回自定义图片。构建/安装成功不等于这些视觉交互已通过。

### 跟随反复切换、第五屏与截图风格菜单修订
- 跟随采用当前 LOCAL 摄像头姿态与固定捕获偏移的显式组合，合成和命中均使用 LOCAL 空间。原生回归测试覆盖200次开启/关闭等价姿态变换，检查位置不漂移。
- EGL 输出初始化移到独立队列且不持有会话锁；销毁排在初始化之后、在非UI线程执行。额外设备切换 Surface 的等待也移出菜单线程。
- 画质采用截图风格的纵向弹出面板，配套真实物理比例与点击区域；设备/显示器选择为列表。窗口标题分设备下拉、显示器标签、画质与新增虚拟屏入口。
- PICO Canvas fixture 已生成 xr-quality-fixture.png 并人工查看布局；这是合成数据的Android绘制验证，不是真实XR画质菜单截图。设备UI/Keystore/RTC启动fixture通过。
- 必须人工复测：初始化后首次跟随、转头/位移后反复A/B至少20次、连续打开五个实际屏幕、菜单是否保持响应和各轨道是否真正出首帧。当前日志未捕获用户当时的第五屏卡顿，不能把线程路径修正当作根因已确认。

### 普通串流 ANR 与沉浸指针 / 主菜单修复（2026-10-27）

- 从头显退出记录及 ANR 堆栈确认两次 `PanelViewerActivity` 输入分发超时（22:50）：主线程悬停事件等待会话锁；持锁线程阻塞在 `PeerConnection.nativeAddIceCandidate`；WebRTC 信令线程的 Observer 同时等待同一锁。
- Native Observer/SDP/DataChannel 回调先投递到有界串行队列，不在 WebRTC 信令线程上争用会话锁；DataChannel 直接内存先复制再异步处理，关闭后丢弃旧事件。
- 普通窗口的 Surface 重绑统一走渲染队列，避免持锁等待 `releaseEglSurface`。XR 转移 Surface 时仍在后台同步完成旧输出释放，保证不同时绑定两个 EGL producer。
- JVM 回归模拟“持锁调用 JNI，JNI 等待回调”的锁链；设备 RTC fixture 使用真实 createOffer 回调检查 native 回调可先返回、延迟状态处理后取锁。
- 六个视频内容区域不画额外射线；菜单、窗口组件及自由空间显示射线和面向摄像头的焦点圆点，按下变蓝。按运行时合成层预算优先活动手柄；有余量时同时显示另一手柄。
- 两手先评估再选输入手：按下事件优先，空闲的另一只手不能阻止菜单命中；拖动时保持原手柄捕获。主页面/环境/退出等全局菜单操作不再依赖活动串流存在。
- 原生回归覆盖六屏内容与菜单 ID 区分、控件归属、双手菜单优先级及捕获不被抢走；新增设备 fixture 检查无串流时主页面/环境仍可点击。
- 实际普通 UU 连接是否不再 ANR、沉浸式射线与圆点位置及主菜单手柄点击，仍须用户实机复测；基础 fixture 不是这些真实操作的通过证据。

## Portrait stream sampling correction (2026-10-27)

- OS 5 native video Surface swapchains now use an orientation-neutral square edge,
  capped at 2160 and clamped to the runtime's reported image limits. Composition
  samples the entire Surface; physical quad aspect still follows decoded frames.
  This avoids downsampling portrait video into the old 1920×1080 landscape target.
- Unconfigured portrait screens request a larger capture height only within the
  advertised codec capability intersection. Explicit user settings are preserved;
  requests remain pending until the existing remote acknowledgement path confirms.
- Native resolution tests and four JVM portrait-planner tests cover runtime limits,
  landscape stability, manual overrides and missing capabilities. Local Android
  build/JVM/Lint validation does not establish live remote portrait sharpness.
- Microphone and plain-text clipboard toolbar controls are included in this build.
  Both are opt-in; microphone capture requires Android permission, negotiated send
  direction, remote driver acknowledgement/open event and foreground app state.
  Remote microphone interoperability and live clipboard transfer remain unverified.

## Monitor header popup placement (2026-10-28)

- Header quality, device, display and virtual-display menus anchor above their
  originating monitor. Nested landscape/portrait pages recompute clearance using
  actual popup height and the monitor's yaw/pitch basis, including portrait screens.
- Anchored menus track the monitor; explicit menu dragging detaches the anchor.
  Root navigation retains its independent menu placement. Device-list refresh does
  not reset the window anchor. Mic/clipboard switches do not move any popup.
- Native geometry tests cover landscape/portrait hosts and popups, yaw and pitch;
  these are not a substitute for headset pointer/visual acceptance.

### Menu usability follow-up

- Parameter submenus use portrait lists with selected values, eight-row paging,
  explicit back navigation and an empty-capability message. Bitrate uses a readable
  portrait slider with separate apply/cancel actions; pending requests still await ACK.
- Screen selection can page beyond the first eight displays and opens on the current
  display's page. Refresh clamps the page after topology changes. Navigating between
  device, quality, display and virtual-display pages clears stale confirmation state.
- Added pure hit-test checks for list spacing, empty-list navigation and invalid rays.
  Headset visual/interaction validation remains pending while ADB is disconnected.

## Android handset layout groundwork

- Below 600 dp, use bottom navigation with a separate account action instead of
  squeezing a desktop sidebar into a handset. Wider PICO panels retain the sidebar.
- Device cards use a primary desktop-entry action, keep immersive entry explicit,
  and show only actual device-list metadata in details (no invented hardware data).
- Extended Android fixtures cover 390 dp -> wide layout round trips. Compilation
  is not device execution: ADB is currently disconnected, so handset screenshots,
  dialogs and live connection acceptance remain unverified. Official Android UU
  reference screenshots are still needed for exact visual parity.

## Immersive material and text-sampling pass

- Shared rounded spatial surfaces use subtle gradients, inset silhouettes and fine
  borders; selected/hover/pressed controls have distinct colors. Root actions now
  use consistent line icons and a red disconnect affordance. No blur, readback or
  additional compositor layers are used.
- Menu Surface resolution is 1200×2000 (previously 1200×400). Rendering scales the
  existing 1200×400 logical coordinates to actual Surface height, so portrait menus
  use full vertical samples without changing native normalized input coordinates.
  Main/menu physical aspect and monitor-above anchoring remain unchanged.
- Added Android rendering fixtures for quality, list and bitrate panels, including
  transparent-corner/material-alpha assertions and PNG capture. These fixtures have
  not been executed or visually inspected without a connected Android device.


## Unified MR menu chrome

- The shared surface material now uses actual source alpha with a dark translucent
  body and a lit rim, not opaque desktop cards or simulated camera blur. Native menu
  quads enable premultiplied source-alpha blending; video quads remain opaque.
- Root menus, parameter lists, quality/device menus, custom keyboard keys and all
  stream title-bar controls share the same material and interaction states. Atlas
  drawing uses a non-clearing sheet primitive so one header cannot erase others.
- Rendering fixtures now assert translucent content rather than opaque content.
  APK/test APK compilation is distinct from executing these fixtures or verifying
  compositor appearance on PICO; those checks still require a connected headset.

### Default independent-menu distance

The independent menu now starts at local Z = -2.8 m instead of -2 m. Launch,
layout reset, return-home and closed-host fallback all use `default_menu_panel()`.
Width stays 1.65 m; monitor-attached popup placement and manual depth adjustment
are unchanged. Native geometry assertions cover the default pose; perceived comfort
and reachability still require headset validation.

## Android controlled-host capture foundation (not UU host interoperability)

- Settings -> capture authorization opens a private Activity and fresh system
  MediaProjection consent. The private mediaProjection foreground service captures
  WebRTC video frames locally, counts frames and tracks actual dimensions. It does
  not store pixels, create a network listener, send media or advertise UU online.
- Persistent notification and Activity expose stop. Grant cancellation, system
  revocation, startup failure, 15-second no-frame timeout and service destruction
  stop/release capture, texture helper and EGL. START_NOT_STICKY prevents unattended
  restart; projection grants are not persisted. A process-wide busy guard prevents
  a fast restart from racing old capture cleanup. No microphone capture is enabled.
- Full UU hosting remains incomplete: account-bound host rendezvous/publication,
  authenticated incoming-session policy, media send negotiation, UU control RPCs and
  explicitly authorized Accessibility input are not yet implemented. No capability
  flag was changed to pretend these are available. PICO MR/system capture scope
  must be checked on hardware, not inferred from MediaProjection support.
- Size-planner JVM tests and private-component manifest checks are local tests.
  No connected headset is available for actual permission/capture/stop validation.

## 内置 PCVR 第一阶段（追踪诊断，非游戏串流验收）

1. 在新版 Linux OpenUUYC 被控设置安装内置驱动，自行重启 SteamVR；无需打开外部串流客户端。
2. 同账号 PICO 连接本机，Linux 明确允许远程终端授权。在沉浸菜单「应用 → 内置 VR」开启追踪诊断。
3. 观察计数递增及 fresh 状态；移动头部与左右手，验证三个 GenericTracker 的姿态，不应出现或切换假的 HMD。
4. 拖动/跟随菜单和窗口，确认 LOCAL 空间追踪不跟随菜单变换。
5. 关闭追踪、断连、撤销权限、头显失焦分别测试；驱动最多 250ms 后报告失联。重新连接后不得有遗留占用。
6. 同时第二个会话/第二个 OpenUUYC 进程尝试开启，必须拒绝；驱动退出或崩溃重启应正常回收 socket。
7. 本节只验收 GenericTracker 追踪诊断，不把追踪设备当作 HMD，也不以它证明可玩；SteamVR 双眼镜像、OpenXR 输入/震动与真实头显的单独验收见下文，不因代码或模拟 fixture 通过而判定通过。

### 2026-10-29 file management completion checkpoint (local)

- Android: `assembleDebug`, JVM tests (213), and `lintDebug` passed in the temporary SDK/NDK build project using the repository sources.
- Native geometry/input: all 14 standalone C++ test programs passed (JNI includes supplied for the surface callback test); Python launcher/protocol integration checks: 14 passed.
- Rust focused regressions: quick-app authorization/catalog (3), PCVR tracking bridge (3), audio queue freshness (1) passed.
- New FTP tests exercise official request framing, compressed/plain directory parity, path validation, upload block/save acknowledgements, download completion/length checks, and cancellation against a simulated peer.
- The latest single-file transfer APK has NOT been validated against the real PICO/UU host. ADB discovery currently has no device. These results are not evidence of upload/download interoperability on the headset, folder batch transfer, persistent cross-session resume, or a complete PCVR HMD transport.

### 2026-10-30 device bridge checkpoint

- Commit `82d34dc` contains the preceding OpenXR component/receiver foundation. Follow-up device bridge work is separate and still in progress.
- Monado PICO builder, owner-only seqlock broker, real runtime eye/FOV calibration reporting and per-session publication have been added. Sixteen focused Rust PCVR tests pass; the Android APK/JVM tests/lint and arm64 native library build pass.
- The real OpenXR loader + overlaid Monado successfully creates a headless HMD session in a temporary synthetic IPC fixture, and `xrLocateSpace` returns (1, 2, 3); stale source rejection passes. This is not real-device pose evidence.
- The updated component bundle was installed, and the updated APK was successfully installed on PICO at `192.168.3.52:5555`. A new local debug host GUI was started for the requested live test; no permissions were granted automatically.
- The real headset user test and GPU stereo/video path are not yet verified/connected. No SteamVR requirement was introduced.

### Floating quick-menu stick release
- Left X / right A summons the floating menu; repeating the same key hides it.
- Deflect the invoking hand's stick beyond 60%, then return below 25% to execute the highlighted item once. A stick press/trigger confirmation is not required.
- Below-threshold motion must not execute anything. Closing the menu or losing stick tracking cancels the pending selection. Verify both hands and submenu navigation on hardware.
- Application directories use data-only SDP (zero media tracks). Existing connections wait for capability readiness; unsupported official UU quick-app peers must report unsupported instead of requesting audio/video.

- Crisp-release guard: once the stick starts returning (8% below its held peak), it must reach centre within 160 ms. Pausing for more than 65 ms, outward reversal over 8%, or a tracking sample gap over 80 ms cancels. A cancelled gesture must return to centre before re-arming; validate thresholds against actual controller spring behaviour.

### View-parallel drag and radial lists
- While dragging a desktop or floating menu, its local right/up/normal track the current VIEW orientation, including roll. The grabbed UV stays on the controller ray's user-centred sphere. Verify while turning, nodding, tilting and translating the head.
- The immersive hub uses eight annular sectors. All device/screen/app/setting lists share that hit geometry, with six data sectors and reserved previous/next sectors. Centre title reports the page. Empty sectors, wedge gaps and disabled page controls must not execute on spring release.
- Keyboard key grids retain their typing layout; they are not list menus. Windowed Android menus remain unchanged.

### Voice microphone and input styles
- Voice page is an explicit microphone, not an IME instruction. Short click starts, a second click ends; a hold of at least 300 ms ends on release. Partial/final results remain local until the user ends the gesture; changing mode, losing focus or closing the session cancels. Speech requests never enable remote microphone forwarding.
- Android SpeechRecognizer requires an installed RecognitionService and user microphone permission. On 2026-10-30, the connected PICO reported no RecognitionService and a null voice_recognition_service setting; actual recognition remains blocked on a provider, not accepted as tested.
- Input type and pointer appearance are separate submenus. Win7/Win11 touch hints are client-side visuals, not assertions about the remote OS. Remote/local cursor modes use UU capture-cursor configuration; adaptive uses the remote text-caret indication and local arrow otherwise. Verify cursor-setting acknowledgements and both simultaneous controllers.

### PICO system IME invocation repair (2026-10-30)
- Checked the official PICO Unity SDK source (`XR/Editor/PXR_BuildProcessor.cs`, `XR/Runtime/Android/PxrPlatform.aar`) and the connected OS 5.15.8 default IME (`com.bytedance.pico.inputmethod/.PicoIME`). Do not copy Unity application-wide VR metadata into this mixed panel/OpenXR app or invent a Kotlin PXR keyboard API.
- Native Android route: a real EditText/editor window, focus/token gate, one input-connection restart per request, bounded show retries and IME result acknowledgement. A voice-only dialog is replaced before requesting a text keyboard. IME acknowledgement is not proof that its immersive overlay is visible.
- Installed repair and ran `native-ime` instrumentation on the connected PICO: input connection bound; local Unicode composition/confirm/cancel and voice-dialog-to-editor transition passed. No speech recording or remote text transport was used. Actual immersive visual placement/controller typing still needs a worn-headset test.
- Platform references: https://github.com/Pico-Developer/PICO-Unity-SDK/blob/main/XR/Editor/PXR_BuildProcessor.cs and https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility .

### Physical full keyboard / Codex CLI voice (2026-10-30)
- Full mode now shares one 104-position ANSI geometry for drawing and hit tests: staggered rows and real key widths; separated F-key groups, navigation block, inverted-T arrows and numeric keypad. Num0 is double width, Num+ and NumEnter are double height. The immersive full-keyboard quad uses 1200:480 geometry instead of squeezing it into the old 1200:400 panel.
- Left/right modifiers have independent VK mappings; the current UU VK-only route maps NumEnter to Return, not a distinct enhanced scan code. Codex voice releases this keyboard's latched modifiers, sends a plain F8 down/up and never uses PICO ASR or automatically enables microphone forwarding. The remote Codex composer must be focused and support Voice; actual recording is not inferred from sending the shortcut.
- Binding verified against official Codex CLI rust-v0.165.0 `codex-rs/tui/src/keymap.rs`: F8 toggle_voice, F9 toggle_voice_mute. https://github.com/openai/codex/blob/rust-v0.165.0/codex-rs/tui/src/keymap.rs . Older/custom keymaps may differ.
- 245 JVM tests and 16 native geometry tests passed. `full-keyboard` on-device fixture rendered the physical layout, verified every key centre and right-modifier/F8 event sequence without sending remote keys or recording audio. Worn-headset usability/real Codex recognition remain separate acceptance steps.
- No application guardian/boundary layer or API to force its visibility is present. Runtime-owned PICO safety notifications are not disabled; optional background floor grids remain decorative background presets, not a guardian.

### Non-stream connection roles (2026-10-30)
- Application/file data connections previously sent desktop kind=1 despite omitting all media m-lines. They now send UU file/data kind=5; dedicated terminals retain native kind=8; desktop kind=1 stays unchanged. Pure data does not advertise the OpenUUYC audio-control extension. Both kinds remain zero-media SDP offers.
- File UI waits for CONTROL PB ECHO + open CONTROL/TEXT, rather than treating TEXT alone as readiness. Ended cached sessions are not reused. Refusal ACKs preserve a numeric remote code and are not misclassified as malformed signaling; diagnostics contain only ACK status/code, identifier-presence booleans and ICE-server count, never identifier values/credentials/peer messages.
- 247 JVM tests, APK and Lint passed; installed on the connected PICO. The saved prior failure ended before peer creation with generic signaling parse failure; a fresh per-device user attempt is needed to validate the corrected roles and obtain any actual refusal code. No remote permission is bypassed.

### PCVR 本地回归（2026-10-31，非实机验收）
- 真实图形合成器 fixture 通过：`drivers/openxr/tests/stereo_render_fixture.py` +
  `stereo_render_smoke.cpp` 以当前 active 组件 bundle 创建真实 OpenGL OpenXR 会话，
  90 帧红/绿双眼经合成器回读为 OUST v2（642×512/眼，左红右绿）；本机用
  `DISPLAY=:0` 与 `__NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia`。
- `device_fixture.py`、`controller_fixture.py`、`device_reattach_fixture.py` 通过：
  注入头姿 (1,2,3) 可经 `xrLocateSpace` 读到、失效发布端不能新建设备；双手 action
  路径含独立 grip/aim 与模拟量、追踪丢失即释放、40 字节 OUHP 震动含时长上限；
  兼容新 inode 同会话重连、光学参数不兼容被拒。
- 主机 `cargo test --lib`：315 通过 0 失败；PCVR 子集 53 通过（含等待原因映射、
  附加替换与 never-published 判定）。这些都不替代真实 PICO 头显解码/投影、真实
  游戏帧、延迟与配准观感的实机验收。

### OpenVR（SteamVR 既有场景）双眼镜像验收（实现已合入，未实机验收）
- 前置：本机 SteamVR 已运行且有一个聚焦、正在提交帧的 VR 场景；本功能不会启动
  SteamVR 或游戏，也不会切换全局运行时。主机需已安装组件包（镜像 helper 内嵌在
  OpenUUYC 可执行文件中，来源哈希记录于 `drivers/pcvr/vendor/openvr-client.lock.json`）。
- 头显：沉浸菜单 → 应用与 VR 游戏 → 附加已有 VR 应用 → 「附加 SteamVR 运行中场景 · 双眼镜像」。
- 通过条件：状态显示 `steamvr-mirror`；`captured_frames`、`encoded_frames`、`video_frames`
  递增且 `video_error` 为空；头显看到左右眼画面与头动一致（附着眼睛相对头显的真实偏移，
  不是把画面贴在脸上）；停止连接后 helper 退出、私有导出文件被删除、无残留进程与锁。
- 负例：SteamVR 未运行、没有聚焦场景、场景中途切换时必须给出明确原因；不得自动启动
  SteamVR/游戏，不得静默退化为仅追踪模式，也不得把桌面画面当作镜像。
- 停滞原因：附加成功但一直无帧时（例如 SteamVR 没有可用 HMD 姿态、场景不提交新帧），
  头显状态必须显示具体原因（`video_error`，如“未提供有效头显姿态”），不能只停在
  “等待真实双眼帧”；原因不得被伪造帧或猜测坐标绕过，出帧后自动消失。
- 重新附加：镜像存活时再次点同一菜单项只确认（状态保持）；手动退出 SteamVR 让 helper
  结束后（状态 `mirror: ended`、`video_error` 给出原因）再次点击必须替换 helper 并进入
  新 generation，画面恢复；替换失败时会话被停止并显示原因，不得留下半配置状态。
  已在「附加当前应用（仅追踪）」会话中点击时必须提示先「停止连接」，不得叠加第二后端。
- 不包含：向 SteamVR 注入 PICO 头显/手柄（镜像只读）、配准精度/延迟/预测验收、
  长时间稳定性；这些必须在真机上另行测量并记录。

### PCVR producer-stage diagnostics and stale-frame rejection
- Host status separately reports `captured_frames`, `encoded_frames`, `video_frames`, `decoder_ready`, `head_valid`, and nonfatal `video_error`. A producer failure must remain visible without revoking the tracking session (which would prevent recovery).
- No valid head pose, a tracking sample older than 250 ms, or an absent publisher stops video production/sending. Recovery requests an IDR. Export timestamps use host CLOCK_MONOTONIC: reject frames older than 250 ms or more than 100 ms in the future; do not compare against the headset clock.
- Hardware acceptance still requires actual VRChat stereo output, matching head motion, decoding and projection in PICO. Process/library presence and synthetic IPC/codec tests do not establish this.

### 2026-10-30 graphical compositor checkpoint
- Real OpenXR OpenGL sessions submitted 90 synthetic projection frames; OUST v2 exported distinct left-red/right-green pixels at 642x512 per eye. The same test passed using the installed, hash-verified component bundle, not only the development manifest.
- The Intel-GL/NVIDIA-Vulkan mismatch reproduced a driver crash during opaque-FD swapchain import. `gl_gpu_identity_v1` now returns `XR_ERROR_GRAPHICS_DEVICE_INVALID` before import; the negative test confirms no stereo publication. Matching NVIDIA GL/Vulkan still passes.
- Latest host diagnostics/expiry checks passed 26 PCVR tests and the host binary build; the GUI was restarted with that binary. Android JVM tests passed 247 cases and the updated APK was installed. No launch/terminal grant was enabled automatically.
- VRChat's existing generated Steam options pointed at an older component bundle. With Steam stopped, the exact generated command was upgraded to the current bundle with a private verified full-config backup. Custom launch options remain protected by exact-match refusal.
- Still unverified: VRChat gameplay stereo on PICO, actual head-motion alignment, complete controller actions/haptics and sustained latency/performance. These synthetic graphical tests do not close those acceptance items.

### 2026-10-30 controller transport checkpoint
- OUVR v2 now carries measured aim poses separately from grip, analog trigger/squeeze and stick, digital X/Y/A/B/menu/stick-click, and actual orientation/position TRACKED bits. V1 peers receive an explicitly downgraded prefix, with no synthesized aim or analog values.
- Monado exposes two Touch-compatible logical devices (not a claim of physical Oculus hardware). Real headless OpenXR action tests passed for both grip/aim positions, tracked flags, trigger/stick/button states, and release/invalid poses after controller tracking loss. The installed immutable component bundle passed the same test; v1 same-session reattachment and changed-optics rejection still pass.
- Real graphical stereo export still passes with the controller-enabled installed runtime: 90 synthetic red/green projection frames, separate 642x512 eye readback. This is not VRChat or headset acceptance.
- Local menu input cannot leak trigger/stick/button actions into the game; a neutral sample is required to return control. While PCVR is active, X+A together toggle the local toolbar, leaving individual X/A for the game. Verify this shortcut and focus transitions on physical hardware before acceptance.
- Android JVM tests passed 249 cases, native arm64 compilation and ASan/UBSan controller protocol/routing tests passed, and the updated APK was installed. Remaining: physical controller behavior in VRChat, game/headset stereo, haptics, prediction/latency and sustained performance.

### PCVR haptic feedback acceptance checklist
- Game OpenXR vibration output -> generation-bound private host socket -> authorized control channel -> current PICO owner -> native OpenXR output action is now implemented. Real OpenXR apply/stop/minimum-duration requests reach the socket for both hands in the synthetic fixture.
- On actual PICO, verify left/right selection, amplitude/frequency, short pulse, explicit stop and bounded duration. Opening the local toolbar, losing XR focus, disconnecting or stopping PCVR must stop vibration. Reconnect must not replay queued old-generation pulses.
- Physical vibration is NOT yet verified. The fixture, parser tests and native policy tests do not prove actuator behavior or VRChat interoperability.
- Current verification checkpoint: Rust PCVR suite 31 passed; Android JVM suite 251 passed; arm64 native build and ASan/UBSan haptic policy tests passed. The installed hash-verified runtime passed the real OpenXR socket-output test and 90-frame graphical stereo regression. Updated APK installation succeeded. These are scoped checks, not physical haptic or VRChat acceptance.

### PCVR Android Surface ownership and failure handling
- XR_KHR_android_surface_swapchain requires format/sampleCount/faceCount/arraySize/mipCount to be zero; these remain zero. The ordinary-swapchain rule requiring nonzero counts must not be applied to this extension.
- PCVR now matches normal video-window JNI ownership handling: borrow the runtime's Surface reference, create a new local reference for the Java callback, and delete only that owned local reference. The prior stereo branch directly deleted the runtime-returned reference.
- Creation rejects null session/factory/output pointers and successful calls with missing handles/Surface. A callback/creation failure is reported with the OpenXR result and matching generation, stops that VR connection, and retains the error until retry rather than waiting indefinitely for decoding. A delayed error cannot stop a different current generation/owner.
- ASan/UBSan mock-runtime/JNI tests cover zero-field extension parameters, borrowed-reference safety, local-reference allocation failure, Java callback exception cleanup, missing Surface rollback, invalid space and successful retry. They do not prove headset Surface creation, MediaCodec output, or VRChat stereo.

### 2026-10-30 STAGE / Proton startup checkpoint
- Reproduced an actual blocker using VRChat's API DLL in a separate Windows diagnostic: xrizer panicked while unconditionally creating unsupported STAGE. PICO now reports a measured runtime STAGE-to-LOCAL transform when valid; the host validates and exports it. No floor is synthesized when the headset has no valid floor space.
- The xrizer compatibility overlay now returns missing-space errors without a panic. A real headless OpenXR fixture verifies the synthetic 1.6 m floor transform, controller inputs and haptic output. A separate Proton diagnostic passes background initialization, all five interface versions referenced by XRSDKOpenVR.dll, and shutdown; missing floor returns a normal nonzero init error. The positive case also passes inside SteamLinuxRuntime_4.
- Still required on hardware: confirm PICO's actual STAGE capture, ground/head/hand alignment, VRChat gameplay stereo and control. The diagnostic loads the API DLL, not the VRChat game, and does not establish any of those acceptance items.

### PCVR calibrated cadence checkpoint
- Frame scheduling and the software H.264 rate hint now follow real 30..165 Hz headset calibration instead of fixed 30 fps/33 ms sleeps. Readback/encoding time is subtracted from the frame budget. Slow processing uses latest-only frames, not a catch-up queue.
- Status shows target Hz separately from measured host send fps. The latter does not establish decode/display fps or latency. Physical headset throughput still needs measurement.
- Export consumption now uses completed-publication serials rather than a producer-local frame ID, so restarting an attached application cannot stall reception until its new frame count catches up with the old process.
- Stream-ready timestamps are now generation-bound, and sender completion rechecks the generation after its final asynchronous send. A stop/restart race cannot make a new session appear to have recent video or count an old completion against its rate window.
- Validation for this cadence change: 37 focused PCVR tests passed, plus a separate 12-test source harness covering the latest bulk eye reads, restart serial handling, real H.264 metadata, transport validation and pacing/meter logic. Android JVM tests passed 254 cases and the APK update succeeded. These checks do not prove a sustained physical 72/90/120/165 fps stream.

### On-device initialization probe and tracking-v2 counters
- `pcvr-init` instrumentation now creates a real SBS Android Surface swapchain and passes the runtime-borrowed Surface through an owned JNI local reference to a dedicated validation callback. It never configures a remote session or claims decoded video. The report separately records creation/reference kind, STAGE support, and valid STAGE sample counters; no raw pose or room imagery is stored.
- Fixed `PicoPcvrProbeStats` rejecting all 400-byte v2 tracking packets. V1/v2 are accepted with the matching version/size, preventing false zero-head/hand diagnostics after the transport upgrade.
- A diagnostic Activity now self-terminates after 75 seconds even if a system readiness dialog causes instrumentation to miss its launch. Normal remote sessions do not use this debug path.
- Actual device attempt: `pcvr-init` failed to observe XrActivity launch within 10 seconds. System state reported `com.picovr.systemext/...HandDialogActivity` and UIAutomator could not obtain an interactive window. No initialization report was produced. This is not evidence that Surface creation or floor capture passed or failed; those steps were not reached. User wake/readiness interaction is required before retry.
- The updated native library and Android/instrumentation APKs built; JVM tests passed 257 cases including the final diagnostic timeout change. No VRChat or SteamVR scene was started.

### D3D11/Proton actual-pixel checkpoint
- Added an isolated Windows D3D11 OpenVR scene diagnostic: recommended DXGI adapter, two actual red/green GPU textures, 90 stereo submissions, and explicit normal shutdown. It runs inside SteamLinuxRuntime_4 using VRChat's unchanged API DLL, not its game executable or game prefix.
- Old runtime/control case returned successful Submit for all 90 frames but exported only one black/transparent pair. The projection-only capture fix produces real, independent red/green pixels through the same xrizer/D3D11 path. An empty/background compositor frame no longer claims the export before real projection content exists.
- The test requires observed complete colored exports and clean shutdown, so Submit success alone cannot pass. Private diagnostic stderr and xrizer traces are retained under its marked prefix for failures. Only that diagnostic Wine prefix is stopped during cleanup.
- A candidate xrizer cleanup change was evaluated but not shipped: the existing xrizer shuts down normally once the diagnostic releases GPU resources after OpenVR shutdown. Production xrizer remains unchanged from the previously verified stage-space bundle.
- This does not establish actual VRChat scenes, PICO hardware decoding, motion alignment or sustained physical frame rate. Headset readiness confirmation is still pending.

### Decoder residence-time and access-unit sequencing
- Frame reassembly records the first fragment's local arrival time. Queueing and hardware decode/callback delay share a 250 ms local deadline; old output is discarded before Surface release, and delayed render callbacks cannot refresh old pose metadata. Codec callbacks from a replaced MediaCodec instance are ignored. These checks do not compare remote host timestamps to the headset clock or establish total network/motion-to-photon latency.
- Pending decoder metadata remains bounded at eight entries. Capacity/duplicate checks precede input-buffer acquisition, avoiding an acquired-but-unqueued input buffer when metadata is full. Stale output requests a fresh keyframe instead of publishing old views as newly rendered.
- Encoder wire sequence now counts produced encoded access units, not capture publications. Latest-only capture skips are legitimate before encoding and no longer trigger false receiver loss/IDR recovery. Actual lost encoded units still create a wire gap and require recovery.
- Focused tests cover stale/late callbacks, queue bounds, metadata reset, first-fragment timestamp preservation and nonconsecutive capture IDs producing consecutive encoded IDs. Physical decoder stall/recovery and VRChat playback still require headset verification.
