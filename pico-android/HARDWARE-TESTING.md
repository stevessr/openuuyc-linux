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
