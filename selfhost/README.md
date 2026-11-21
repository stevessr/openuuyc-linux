# OpenUUYC 自托管兼容服务端

此目录实现与项目中 **Linux 被控端、桌面主控端和 PICO Android 主控端** 对接的 NRD 风格控制平面与 Engine.IO v4 / Socket.IO v4 信令服务。默认官方服务端不受影响；自托管模式必须显式开启。

**当前是独立可运行的功能兼容实现，并非网易 UU 官方服务端的完整 1:1 替代品。** 服务器不包含官方账号、短信、官方中转机房、私有 RPC 全量协议或未经验证的硬件联调能力。

## 功能矩阵

| 功能 | 状态 |
| --- | --- |
| 设备注册、稳定设备 ID、设备列表、在线状态、改名、解绑 | 已实现 |
| 独立管理员批准二维码、Bearer 登录、登出和会话撤销 | 已实现 |
| 独立访客会话、访客房间、访客被控端协助回调 | 已实现 |
| 房间创建、占用与显式抢占、Signal WS 身份、心跳、重连密钥应答 | 已实现 |
| 设备实时变更推送（改名、允许被控、版本、解绑）经 bmsg_push 分发到本账号在线房间 | 已实现 |
| Socket.IO 二进制控制握手、SOAC、ICE、gzip SDP、ForwardSetting、控制释放 | 已实现 |
| 短暂 WebSocket 中断时保持逻辑会话及 ICE 身份 | 已实现，未进行真实设备断网测试 |
| STUN、静态 TURN 凭证、coturn REST 临时凭证 | 已实现（实际中继依赖配置的 TURN 服务） |
| 9 位协助 ID、被控端在线 Salt+SHA256 验证、本机确认、收藏/最近连接 | 已实现协议路径 |
| Android 屏幕共享的主控请求与受限回执转发 | 已实现协议路径，尚需 Android 设备联调 |
| 远程重启、关机命令投递到在线且允许被控的主机 | 已实现命令投递，需主机本机授权 |
| `release/trigger` 远程更新入口 | 按协议回信封（501 明确拒绝），不再返回裸 404 |
| WoL 能力与 LAN 元数据记录 | 已实现；实际局域网唤醒未实现，接口返回 501 |
| 壁纸授权上传、本地保存、字节回读校验、绑定及分发 | 已实现 |
| 官方 UU 手机号 / 扫码中心、设备间官方账户绑定、跨账户授权 | 未实现 |
| 官方 UU 云中继、全部专有 RPC、云端机器管理 | 未实现 |
| 多租户账户隔离、跨节点状态同步、高可用分布式部署 | 未实现 |
| 真实 PICO 4 Ultra ↔ Linux 的长时稳定性、音视频、键鼠/触摸/终端联调 | 待验收 |

## 部署

需要 Python 3.12+ 或 Docker Compose、一个由两端信任的 HTTPS 域名、至少一个 STUN 地址。公网 NAT 穿透建议准备自有 TURN 服务。

\`\`\`sh
cd selfhost
cp .env.example .env
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
# 修改 .env: OPENUUYC_PUBLIC_URL / OPENUUYC_ADMIN_KEY / OPENUUYC_ICE_SERVERS
docker compose up --build -d
\`\`\`

Compose 默认只在宿主机的 \`127.0.0.1:8844\` 监听。示例 Caddy 配置：

\`\`\`caddyfile
uu.example.com {
    reverse_proxy 127.0.0.1:8844
}
\`\`\`

需要 DNS 和有效 HTTPS 证书。不要将未启用 TLS 的 Python 服务直接暴露公网。

### TURN 中继配置

如只需要内网或支持 UDP P2P 的网络：

\`\`\`dotenv
OPENUUYC_ICE_SERVERS=stun:stun.l.google.com:19302
\`\`\`

在限制 UDP 的网络，需要可被两端访问的 TURN。推荐独立部署 coturn，并配置 \`use-auth-secret\` 与一段高熵 \`static-auth-secret\`：

\`\`\`dotenv
OPENUUYC_ICE_SERVERS=stun:turn.example.com:3478,turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp
OPENUUYC_TURN_SECRET=REPLACE_WITH_COTURN_STATIC_AUTH_SECRET
\`\`\`

每次控制握手都会签发一个 1 小时有效的 TURN REST 用户名及 HMAC-SHA1 凭证。其他 TURN 实现可以改用 \`OPENUUYC_TURN_USER\` 和 \`OPENUUYC_TURN_PASSWORD\`。自托管控制端不代理媒体流；TURN 服务器负责可能发生的中继和实际带宽开销。

### Linux 连接方式

为使用自托管环境的所有 OpenUUYC 进程设置同一地址：

\`\`\`sh
OPENUUYC_NRD_BASE_URL=https://uu.example.com OpenUUYC login
OPENUUYC_NRD_BASE_URL=https://uu.example.com OpenUUYC
\`\`\`

Linux 客户端在该模式下不会把独立注册信息回退提交到官方 NRD 端点。扫码后打开本服务提供的 \`/enroll/...\` 页面并输入管理员密钥，才能完成设备批准。凭证保存在客户端自己的系统密钥库。

### PICO Android 构建

\`\`\`sh
cd pico-android
./gradlew assembleDebug -PopenuuycNrdBase=https://uu.example.com
\`\`\`

无此 Gradle 属性时 APK 使用官方端点。切换服务端前应使用**独立的应用数据身份/测试配置**，避免复用已缓存的官方账号和设备身份。本服务不能验证或使用官方 UU 登录凭证。

## 远程协助验证

服务端不保存被控端原始密码，也不将预置哈希当作实时验证。主控查询 9 位设备协助 ID 时，服务端通过 \`bmsg_push\` 向在线被控端请求连接模式；输入验证码后，服务端生成随机盐，再请求被控端返回 SHA-256(盐 + 验证码) 校验值。若当前被控端要求本机确认，主控连接必须等到被控端 UI 实际回传批准。

测试覆盖验证码错误、失败次数限制、单次确认、访客模式、撤销和连线。主机锁屏、不同窗口管理器与真实确认 UI 仍需实测。

## 壁纸服务

服务端以短期、一次性上传授权接收最多 10 MiB PNG/JPEG/WebP 文件，在数据目录的 \`wallpaper/\` 下保存；文件返回不带上传 Token 的随机 URL。Linux 客户端只会在显式自托管模式下信任与 NRD 服务相同 HTTPS 来源的上传及发布 URL，仍对下载后的内容逐字节验证。

壁纸公开 URL 为不可猜测标识，**不是用户身份验证接口**。发布图片可能包含桌面隐私；生产环境应完善删除、生命周期管理与审计。

## 运行检查

\`\`\`sh
python3 -m pip install -r selfhost/requirements.txt
python3 -m unittest discover -s selfhost -p 'test_*.py' -v
docker build -t openuuyc-selfhost:ci selfhost
\`\`\`

\`GET /healthz\` 只表示 HTTP 进程正常，不表示任何实际设备或 WebRTC 链路已经连通。GitHub Actions 会运行协议测试和 Docker 构建，Rust Linux/PICO 构建在各自已有工作流检查。

## 安全与部署边界

- 目前是**单管理员、单逻辑账号**：所有经过管理员二维码批准的正式设备都属于同一拥有者。被控端开启允许控制后，同一账号的设备可以请求强制抢占；不能作为互不信任用户的公共平台。
- 管理员批准页对每个来源 IP 有 15 分钟 5 次的密码试错退避；反向代理限速仍是第一道防线。
- 账号 Bearer Token 和设备信息保存在 SQLite 数据目录，上传文件权限受容器运行用户限制。请限制数据目录和备份权限。
- 远程协助校验采用随机盐和主机在线回调，按主控设备和目标主机对失败次数限速；生产部署仍需要反向代理按 IP 进行请求速率限制及监控。
- 房间 token 和控制会话属于单实例内存状态，进程重启后需要客户端重建房间。不要直接运行多个负载均衡实例。
- 访客 Bearer 有效期 24 小时；授权房间与信令事件都受服务端会话及目标设备匹配规则约束。
- 网络内传输画面、输入与音频由现有 WebRTC 数据通道及端到端身份协商处理；本服务不保证官方 UU 原有的所有媒体功能已经通过测试。

优先硬件验收顺序：Linux ↔ PICO 同局域网视频/音频/输入、P2P NAT、coturn TCP/TLS、网络切换、断线自动恢复、远程终端与文件传输、被控确认、压力与安全测试。
