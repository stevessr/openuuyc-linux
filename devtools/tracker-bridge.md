# 标准化体感姿态接入 VRChat

`tracker-bridge.py` 把追踪程序输出的 JSONL 转换成 VRChat OSC bundle，并发送到
本机 UDP 端口转发监听器。已有 OSC 输出的 SlimeVR 等程序无需此转换工具。
它不是追踪硬件驱动，也不提供摄像头骨骼估计、空间校准或坐标自动转换。

1. 在远端 VRChat 启用 OSC，并按实际追踪设备完成校准。
2. 在 OpenUUYC 端口转发创建「VRChat OSC」规则，两端均使用支持 UDP 的版本。
3. 将真实追踪生产者的 JSONL 标准输出接到工具：
   `your-tracker-producer | python3 devtools/tracker-bridge.py --port 9000`。
   这里的 `your-tracker-producer` 代表自行适配的追踪程序，不是项目提供的命令。

每行是完整 JSON 对象，例如以下仅说明格式，不能用作真实追踪结果：

```json
{"trackers":[{"id":1,"position":[0.1,1.0,0.2],"rotation":[0,90,0]}]}
```

生产者须输出 Unity 左手世界坐标，Y 向上、位置单位米，欧拉角单位度，旋转顺序 Z-X-Y。
支持 ID 1—8，以及可选 `"head"` 对齐参考；body 节点要求位置和旋转都有，head 可只发其一。
ID 是稳定的追踪器编号，不是这里预设的身体部位；由 VRChat 校准对应关系。
`"tracked":false` 会跳过该节点，不发送原点替代；工具不重复发送最后一帧。
没有有效节点则不发送，VRChat 的超时行为由游戏自身控制。

每行最多 64 KiB，每帧最多 9 个节点；无效输入报错退出，不发送该帧的部分节点。
只向 127.0.0.1 发包，Ctrl+C 或输入 EOF 停止。管道和可靠传输仍可能排队，
生产者应只输出实时帧，不要回放积压数据。默认不启用源帧过期检查；可按下述方式启用。

验证：6 项 Python 测试包含实际本机 UDP 收包和 OSC 字节解码检查；未做远端 VRChat 验收。
协议依据：[VRChat OSC Trackers](https://docs.vrchat.com/docs/osc-trackers)。

## 可选源帧过期过滤

生产者可在帧顶层附加 `"monotonic_ns"`，值为采样时的同机单调时钟纳秒数
（Python `time.monotonic_ns()` / Linux `CLOCK_MONOTONIC`）。使用
`--max-age-ms 100` 后每帧必须带该字段，满 100 ms 的旧帧会被丢弃。
携带时间戳的重复、乱序帧也会被丢弃；未来时间戳报错退出，避免混用跨机器时钟。
这不是时钟同步协议，不接受另一个设备的开机时间作为同机时间。
时间戳应在采样时生成，不能在读取积压队列时重新打点。

新安装包提供 `openuuyc-tracker-bridge`，可替换示例中的 Python 源码命令。
仅依赖 Python 3；安装不会启动采集或修改 VRChat 设置。

## OpenXR 数据输入

使用 `--space openxr` 时，输入位置仍以米为单位，但使用 OpenXR 右手坐标，
`rotation` 改为 `[x,y,z,w]` 单位四元数。工具翻转 Z 坐标并转换旋转到 Unity
Z-X-Y 欧拉角；默认 `--space unity` 的三分量角度输入保持不变。
所有节点必须来自同一个参考空间，不能混合 VIEW、LOCAL、STAGE 数据。
不自动推算地板高度或做跨空间标定，头部参考也必须由生产者提供真实姿态。
归一化误差仅允许小范围容差，零、非有限及明显未归一化四元数会被拒绝。

转换测试包括坐标反射、三个轴的 90° 旋转、万向节锁分支以及 100 组复合旋转的
矩阵重建比较；这些测试不等于已接入真实 OpenXR 全身追踪设备。

## 本机 SteamVR 追踪器生产者

项目提供 `drivers/pcvr/tracker_source.cpp`，可直接采集已运行 SteamVR 中的
GenericTracker，无需自行编写 JSONL 生产者：

```sh
c++ -std=c++17 -O2 -Wall -Wextra -Werror drivers/pcvr/tracker_source.cpp \
  -ldl -pthread -o openuuyc-tracker-source
./openuuyc-tracker-source /绝对路径/libopenvr_api.so |
  openuuyc-tracker-bridge --space openxr --max-age-ms 100 --port 9000
```

库应来自已安装的 SteamVR，例如其 `bin/linux64/libopenvr_api.so`，不是任意下载的库。
生产者以 Background 类型连接，不启动 SteamVR、不切换全局运行时或启动游戏；
仅清除自身进程的运行时覆盖环境变量。采集 Standing 空间的真实 GenericTracker，
不把控制器或 HMD 眼位当作身体追踪器。状态无效时输出 `tracked:false`。
每进程最多 8 个追踪器，按序列号保持 ID，断连后不把该 ID 分配给其他设备。
序列号仅保存在内存，不写入 JSONL。重启生产者后 ID 顺序可能变化，需重新检查校准。
不自动设置身体角色，仍由远端 VRChat 校准。采样约 90 Hz，时间戳为查询时的本机
单调时钟，不是传感器的硬件采样时刻。Ctrl+C 或下游管道关闭可停止。

已验证编译和运行时不可用时的明确失败；本机 SteamVR 当前未运行，未采到真实追踪姿态。
该 C++ 工具已加入新 Linux 包构建，安装后的命令为 `openuuyc-tracker-source`。
从源码直接运行 `cargo deb` 前，须先按上述 C++ 命令将工具编译到
`target/release/openuuyc-tracker-source`；CI 和 AUR 源码包会自动执行此步骤。

### 固定追踪器编号

可在库路径之后重复指定 `--tracker ID=SERIAL`，例如：

```sh
openuuyc-tracker-source /绝对路径/libopenvr_api.so \
  --tracker 1=实际腰部追踪器序列号 --tracker 2=实际左脚追踪器序列号 |
  openuuyc-tracker-bridge --space openxr --max-age-ms 100
```

这里的身体部位只是示例；编号含义仍取决于 VRChat 校准，不自动推断。
只要使用至少一个显式映射，未列出的追踪器就不会采集；ID 必须为 1—8，
同一 ID 或序列号不能重复，错误在加载运行时前报告。保留同一组参数即可在
重启后维持编号，参数排列顺序不会改变编号。不带映射时保留自动编号模式。
序列号会出现在命令行参数中，但不会进入姿态 JSONL；分享命令时可自行脱敏。
