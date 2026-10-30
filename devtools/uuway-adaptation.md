# UUWay 功能对照与原生实现

参考本地 `../UUWay` 的 `8b7f6c1`，按功能需求完善 OpenUUYC；不引入其 Wine DLL、注入器或服务，不改变当前项目许可。下面新增代码为原生实现，不复制 UUWay 的 AGPL 源码。

## 本轮补齐

- **终端控制序列**：现有 OSC 3008 过滤继续保留。对于未判定的 ESC/引导前缀，PTY 空闲 50 ms 后释放，避免单独 ESC 永久滞留；已识别的 OSC 正文不会因空闲而泄漏给客户端。结束与分包处理保持不变。
- **服务环境下的 shell/home**：优先保留有效的 `$SHELL`，缺失、目录或不可执行路径回退当前用户的 NSS 登录 shell，最后才用平台默认值；HOME 无效时使用当前用户 home。通过 `getpwuid_r` 查询，避免多线程共享 passwd 缓冲。子进程的 SHELL 与实际启动程序一致，不修改父进程环境。
- **只读环境自检**：`bash devtools/linux-doctor.sh` 或追加 `--json`。检测真实 X11 连接和同用户 socket 候选，拒绝 SSH 显示隐式切换、报告多会话歧义；检测 Wayland/PipeWire/音频连接、render/uinput 权限和用户服务。工具存在与连接成功分开报告，外部命令每项超时 3 秒。不安装软件、不启动服务、不自动授权、不采集屏幕。

## 现有能力，不重复引入桥接实现

已有多屏、屏幕恢复、终端 PTY、Unicode 虚拟键盘输入、剪贴板、文件及采集后端回退，应沿用原生协议路径。UUWay 的 GNOME/Mutter、Fcitx 插件、GPU 通道与 Wine 接口不能直接代替 OpenUUYC 后端；后续移植需要分别适配且验证桌面/驱动。

## 验证

```sh
python3 -m unittest discover -s devtools -p test_linux_doctor.py -v
SHELL=/bin/sh cargo +1.95.0 test --offline platform::posix::terminal --lib -- --test-threads=1
bash devtools/linux-doctor.sh --json
```

自检成功不代表硬件编码、远程输入或实际串流已验收。JSON 只输出白名单状态，不转储环境变量、认证 cookie、账户或服务日志。
