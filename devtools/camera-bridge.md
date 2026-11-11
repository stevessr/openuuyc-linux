# Linux 摄像头转发（手动工具）

`devtools/camera-bridge.py` 使用 FFmpeg 将本机 V4L2 摄像头编码为 H.264/MPEG-TS，
通过已有 TCP 端口转发送到远端，再解码写入 v4l2loopback。当前不是内置 GUI 摄像头功能，
不支持 Windows 虚拟摄像头或 PICO 透视摄像头。

## 安装后的命令

包含此工具的新 Linux 发布包安装后可直接运行 `openuuyc-camera-bridge`；
以下示例中的 `python3 devtools/camera-bridge.py` 可替换为该命令。
依赖 Python 3、FFmpeg，接收端另需 v4l2loopback。它们是可选依赖，
不会因打开主程序而启动摄像头。旧版二进制包可能尚不包含此命令。

## 使用

1. 两端安装支持 V4L2、libx264 的 FFmpeg。接收端预先安装并配置 v4l2loopback，
   准备专用虚拟摄像头，例如 `/dev/video10`，确保当前用户可写。
   工具不执行 sudo、不安装内核模块，也不应把真实采集设备当作接收目标。
2. 接收端启动：
   ```sh
   python3 devtools/camera-bridge.py receive --device /dev/video10
   ```
3. OpenUUYC 中为该远端创建 **TCP** 规则：本机 `127.0.0.1:19001` →
   远端 `127.0.0.1:19001`。开启远端端口转发许可并确认服务连接成功。
4. 发送端明确选择真实摄像头后启动：
   ```sh
   python3 devtools/camera-bridge.py send --device /dev/video0
   ```
5. 在远端应用中选择该虚拟摄像头。两端默认 1280×720、30 FPS；可用
   `--width`、`--height`、`--fps` 调整，源设备必须支持所选采集模式。

加 `--dry-run` 仅打印命令，不打开设备。Ctrl+C 停止对应进程；断线后工具退出，
重新使用时先重启接收端，再启动发送端。不自动后台重连或持久化摄像头授权。
接收器只接受一个连接，应用占用、设备不支持格式、端口冲突均由 FFmpeg 报错。

## 边界与验证

仅监听回环地址，跨机器认证及传输由 OpenUUYC 负责；本机其他进程仍可连接该端口。
不录制文件、不转发音频。TCP 拥塞会增加画面延迟；目前未完成跨机器真摄像头验收。
已添加命令构造与参数边界测试：
`python3 -m unittest discover -s devtools -p test_camera_bridge.py`。
测试不证明摄像头驱动、虚拟摄像头消费者或实际网络可用。

协议和设备参数依据 [FFmpeg TCP 文档](https://ffmpeg.org/ffmpeg-protocols.html#tcp)
及 [V4L2 设备文档](https://ffmpeg.org/ffmpeg-devices.html)。

### 媒体回归测试

运行 `python3 -m unittest discover -s devtools -p 'test_camera*.py'`。
媒体测试复用工具生成的真实 FFmpeg 编码、TCP、解码与像素转换参数，只把
V4L2 输入替换为合成画面、输出替换为帧校验和。检查至少 18 帧到达且画面变化，
不读取摄像头、不安装内核模块。该测试已在本机通过，并加入独立 CI 工作流；
CI 远端运行、OpenUUYC 跨机器隧道和虚拟摄像头设备仍待验证。
端口转发界面的「摄像头 TCP」按钮可直接生成对应规则草稿，保存前仍可编辑。

启动前还会通过 `VIDIOC_QUERYCAP` 检查所选节点的单平面采集/输出能力，
优先使用节点自身的 `device_caps`，避免把摄像头元数据节点当作视频源。
该检查不启动采集；实际分辨率、帧率与像素格式的协商仍由 FFmpeg 完成。
本机只验证了物理摄像头能力识别及对错误接收目标的拒绝，没有采集真实画面。

### 采集格式

先运行 `openuuyc-camera-bridge formats --device /dev/video0` 查询设备声明的
格式和分辨率；该模式只枚举，不创建网络输出或启动编码。FFmpeg 的枚举路径可能在
打印列表后返回非零并显示 `Error opening input file`，请同时检查前面的格式列表；
若没有列表，检查权限、设备占用和节点能力，不能将失败视为支持该格式。

例如设备支持 MJPEG 时，可用：
`openuuyc-camera-bridge send --device /dev/video0 --input-format mjpeg`。
可选 `auto`（默认）、`mjpeg`、`yuyv422`、`nv12`，格式是否支持取决于真实设备。
本机摄像头已成功枚举出 MJPEG 与 YUYV，包含 1280×720；未据此断言支持 30 FPS，
也未打开实际视频采集。格式参数只允许用于发送端，接收端仍输出 YUYV 虚拟摄像头。

### Linux 界面启动

端口转发窗口顶部的「Linux 摄像头发送」面板可选择 V4L2 设备、输入格式及已监听的
IPv4 回环/通配 TCP 规则，显式点击启动后发送视频，默认 720p/30 FPS，也可选 480p/30、1080p/30 或 720p/60。
仍需先在远端运行接收器。此入口使用随程序嵌入的工具代码，需要 Python 3 和 FFmpeg，
无需另外下载脚本。停止、关闭管理窗口、规则修改/停用或转发断连都会终止本机采集。
重连不会自动重新开启摄像头。进程存活状态不代表远端虚拟摄像头已出图。

GUI 采集进程设置 Linux 父进程退出信号，主程序异常退出时也会终止发送进程；
此保护针对界面启动路径，独立 CLI 由启动它的用户管理。
错误详情只保留最后 8 KiB，可在面板展开查看，便于识别设备格式或连接失败。
