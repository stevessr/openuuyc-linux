# OpenUUYC-owned OpenXR components

## Backend policy

Default: **OpenXR first, SteamVR fallback**. `openxr-only` forbids fallback; `steamvr` explicitly selects the backup. A corrupt owned bundle is an error, not permission to use another runtime. An initialized OpenXR runtime without a headset can fall back to the installed SteamVR runtime; the selected runtime must actually expose a system before the launcher starts a game.

The policy is available in Linux host settings and through each authorized connection's app menu (including the XR controller menu). A setting applies to the next connection/launch, not an active VR session. These extension controls require the host's remote-terminal permission.

## Actual components

- Monado `59be345db769a89c93d1bdf6ed8316fa6209d8ae`, built as `libopenxr_openuuyc.so`, **in-process**, no service/dashboard executable, no stock simulated/remote/physical-hardware driver enabled; the follow-up source overlay adds the owned PICO device builder.
- xrizer `0519ded7421e608805d710be01fdc51303eda16a`, a separately licensed OpenVR→OpenXR shared library loaded in the game, **not linked into the OpenUUYC host**.
- A tiny owned OpenXR loader probe, run in a bounded child process, isolates runtime initialization/crashes from the GUI. The host can also probe through its internal `runtime-probe` subcommand when no bundle is installed.
- Source revisions, SHA-256 of every packaged file, upstream license notices and corresponding pinned source archives are included in the component bundle.

Sources: [Monado](https://gitlab.freedesktop.org/monado/monado), [xrizer](https://github.com/Supreeeme/xrizer). Preserve their respective licenses and attribution. This is not WiVRn/ALVR being launched behind the scenes.

## Build/install (explicit, no root)

Build dependencies: C++ compiler, Python 3.12+, CMake/Ninja, Rust 1.95, Vulkan/OpenXR loader headers and libraries, Eigen and Monado's configured system dependencies. The source build downloads only pinned source, never prebuilt executables. A new work/output directory avoids modifying live components.

```sh
python3 drivers/openxr/build_components.py \
  --work /tmp/openuuyc-openxr-build \
  --output /tmp/openuuyc-openxr-bundle
OpenUUYC pico-vr components-install /tmp/openuuyc-openxr-bundle
OpenUUYC pico-vr runtime-preference openxr-first
OpenUUYC pico-vr runtime-status
```

The installer uses versioned directories below `$XDG_DATA_HOME/openuuyc/pcvr/components`, verifies the files, then switches **its own** `active.json` pointer. It preserves an earlier pointer backup. It does not rewrite the system/user `openxr/1/active_runtime.json`, does not overwrite `openvrpaths.vrpath`, and does not restart the running host or a game.

Native OpenXR application:

```sh
OpenUUYC pico-vr run -- /path/to/application
```

OpenVR application:

```sh
OpenUUYC pico-vr run --openvr -- /path/to/application
```

When Steam is already running, environment variables set on `steam -applaunch` are not a reliable game environment. Copy **the per-game launch options** from Linux settings, the Android app menu, or `runtime-status`, and paste them into that game's Steam launch options. The options select `XR_RUNTIME_JSON` and, for OpenVR, `VR_OVERRIDE`, only for the game process. User-home component paths remain usable in the Steam Linux Runtime container; arbitrary system paths may need separate container mappings.

xrizer requires a valid `openvrpaths.vrpath` even with `VR_OVERRIDE`. If none exists, the explicit command below creates a minimal one pointing at the owned xrizer tree, without requiring SteamVR. **It refuses to overwrite an existing file.** This is the sole opt-in global OpenVR initialization action; normal installation does not perform it.

```sh
OpenUUYC pico-vr components-init-openvr
```

## Validation and remaining work

At the initial library-only checkpoint the real loader created an instance (`instance_result=0`) but found no system (`system_result=-6`). The follow-up below adds a calibrated PICO device builder; without live headset data it still deliberately reports no HMD.

The preceding SBS transport/decoder/OpenXR projection work is a receiver foundation, not a complete host runtime. Still required: live PICO validation, input/clock/floor alignment, a Vulkan compositor target feeding the authenticated OpenUUYC encoder/media path, controller/haptics support, and end-to-end game/headset validation. Stock Monado plus xrizer alone does not complete these pieces. The SteamVR fallback bridge also remains tracking-only.

The manual `Owned OpenXR components` GitHub Actions workflow builds a separate Linux component artifact from the lock file and checks actual loader initialization. It is not run/downloaded by ordinary application startup. The artifact is a tar archive so executable modes survive upload/download. This workflow has been added but has not been executed remotely during this change.

Local checks for this integration: 218 Android JVM tests plus APK/lint passed; 14 PCVR Rust tests and 3 quick-app tests passed; the host CLI was built and used to install the real bundle. `runtime-status` confirmed instance creation without a system. A negative launch test in `openxr-only` mode confirmed a supplied command was not executed without an HMD. Before/after hashes confirmed the existing global OpenXR/OpenVR configuration files were unchanged.

## Device bridge follow-up (after commit 82d34dc)

The source build now overlays the owned `pico_builder.c` onto the pinned Monado tree and packages the overlay source, without editing the upstream checkout. Bundles advertise `pico_device_v1`, **not** `pico_stereo_v1`. The graphical launcher still refuses to start an OpenXR game without the stereo/compositor capability.

PICO measures eye poses/FOV in OpenXR VIEW space, uses runtime-recommended image dimensions (uniformly capped to a 2048-pixel long edge) and the reported display period, and includes calibration in the explicit VR start request. The authenticated Linux session publishes accepted pose packets to a private 4096-byte seqlock snapshot at `$XDG_RUNTIME_DIR/openuuyc-pcvr/openxr/device.shm`. The Monado builder only creates an HMD from valid calibration and a fresh, valid head pose. No fixed IPD, floor height or fake tracking is supplied. STAGE/local-floor, controllers and clock-correct prediction are not implemented yet.

The source is owner-only (0700 directory/0600 file), globally publisher-locked, stale after 250 ms, invalidated/unlinked on stop, and generation-bound. Reconnection currently requires restarting the OpenXR game/runtime instance, rather than reusing an old instance's eye calibration. Steam launch environments include a read-only mapping of the private IPC directory into Pressure Vessel, preserving preexisting mappings.

Monado's pinned in-process path previously always initialized a compositor even with `XR_MND_headless`; the explicit source overlay lets a headless device diagnostic avoid GPU/display initialization. Normal graphical applications are unchanged. The custom system provides Monado's required I/O cache, so a real OpenXR headless session can be created, not merely an instance.

The synthetic fixture test in `tests/device_fixture.py` compiles against the real OpenXR loader and verifies that an actual Monado HMD session returns injected head position (1, 2, 3) through `xrLocateSpace`; after publication stops, a new session cannot acquire a stale HMD. The fixture uses a temporary XDG runtime/config directory, is never enabled in production, and is not headset or game-rendering acceptance evidence. Headless mode intentionally exposes no eye-image views; graphical view/compositor testing is still separate.

The updated native Android library/APK and local Rust device tests build successfully. The device-overlay component bundle has been built and installed locally; real PICO-to-Monado verification is pending the user enabling tracking on the headset. The GPU compositor → encoder → stereo media path remains unconnected, so no full PCVR completion is claimed.

### 双眼生产链路开发状态

`stereo_overlay.py` 将同帧左右眼 scratch readback 接入 Monado，并附带该帧实际
`frame_params.poses/fovs`；`stereo_capture.[ch]` 定义私有、临时的 OUST v2 导出。
仅显式设置 `OPENUUYC_STEREO_CAPTURE` 且宿主预建了正确大小、当前 uid 独占的
0600 文件时才导出；生产端使用 inode 级排他锁，帧未完整配对时不提交序列锁。
这不是录屏文件，也不是桌面捕获。当前正确性基线每眼高 512 像素，CPU 回读。

Rust `pcvr/capture.rs` 验证头部、时间、尺寸、姿态/FOV 和序列锁，将两眼拼成 SBS BGRA；
`pcvr/encode.rs` 通过已有真实 H.264 编码器保留原帧姿态、时间和会话代号。
现已接到已授权会话的文件生命周期、独立编码线程和头显 video-config/ACK 握手；
停止/撤销会话后旧 generation 的帧不能继续发送。ACK 只在硬件解码器配置就绪后发送。
目前尚无真实游戏双眼出图验收，不能标记 PCVR 完成。

### VRChat 单游戏入口绑定

沉浸应用菜单现在先请求真实 PCVR 会话，主机确认后才请求启动 VR 应用。
普通窗口没有双眼 Surface/真实眼位校准时明确拒绝 PCVR 准备，不伪装成桌面成功。

Steam 已经运行时，给另一次 steam 进程设置 XR_RUNTIME_JSON 不能改变游戏的环境。
`devtools/pcvr-steam-options.py --appid 438100` 用当前选定组件的参数配置该游戏的
LaunchOptions，不修改全局 OpenXR/OpenVR 选择；必须先关闭 Steam。
只修改唯一匹配的 Steam 用户配置，已有自定义参数不覆盖；修改前建立 0700 目录内的
0600 完整备份并校验 SHA-256。恢复备份也必须先退出 Steam。
这些配置变更与入口连接尚不构成 VRChat 实机双眼出图证明。

### 附加已有应用（已实现范围与剩余缺口）

沉浸菜单提供「附加已有 VR 应用（内置运行时）」；该操作只建立会话和等待真实
双眼帧，不启动/重启游戏，停止连接也不关闭游戏。
新版 PICO Monado builder 在发布端断开后重新打开当前 uid 的私有设备映射，
只接受光学参数兼容的新发布端，保留应用原有 XrInstance/XrSession。
旧映射失效期间返回无效追踪，不保持伪造的有效姿态。

`device_reattach_fixture.py` 使用合成 IPC 姿态、真实 headless OpenXR 会话，
验证同一应用会话接上新 inode 和拒绝不兼容光学参数；它不是头显/游戏验收。
`stereo_reattach_test.c` 验证已有双眼生产端改用新输出映射，带 ASan/UBSan 运行。

该机制适用于加载新版内置运行时的既有应用，不会把已经绑定 SteamVR 或其他
运行时的进程强制切换为 Monado。SteamVR 既有进程的独立合成器取帧仍需实现；
未实现前不宣称任意现有 VR 应用都可附加，也不通过注入或桌面镜像冒充支持。

附加入口现在通过只读 vr-applications 扩展查询当前 uid 的 /proc 映射，只列出
实际加载已安装内置 OpenXR 库的进程，不按 VRChat 进程名猜测，也不传输命令行。
没有目标时明确提示，不一直等待或偷偷启动新实例。该清单证明库已加载，不证明
应用正在渲染、双眼帧已到达头显或控制器已经可用。

### Real graphical stereo fixture (not headset acceptance)

`tests/stereo_render_smoke.cpp` creates an actual OpenXR OpenGL session, clears
separate left/right swapchains red/green, and submits 90 projection frames.
`tests/stereo_render_fixture.py` supplies temporary synthetic tracking IPC and
asserts distinct colors in the compositor's actual OUST v2 readback. It never
writes the live headset publisher or starts a game.

```sh
c++ -std=c++17 -O1 -Wall tests/stereo_render_smoke.cpp \
  $(pkg-config --cflags --libs x11 gl openxr) -o /tmp/openuuyc-stereo-render-smoke
python3 tests/stereo_render_fixture.py --runtime /path/to/openxr_openuuyc-dev.json \
  --smoke /tmp/openuuyc-stereo-render-smoke
```

The fixture needs a reachable X display and the application/compositor on the
same GPU. On the tested Intel/NVIDIA machine, `DISPLAY=:0` with
`__NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia` passes; default Intel
GL against NVIDIA Vulkan previously crashed during opaque-FD import. The
`gl_gpu_identity_v1` overlay now checks GL device UUIDs before swapchain import
and returns `XR_ERROR_GRAPHICS_DEVICE_INVALID` on mismatch. The fixture's
`--expect-gpu-mismatch` option verifies rejection with no published stereo.
This is a local GPU/compositor regression test, not VRChat/Proton, transport,
decoder, headset projection, controller or latency acceptance.

### Controller input transport v2

The negotiated 400-byte OUVR v2 frame preserves the 224-byte v1 prefix, adds two
independent measured aim poses (64 bytes each), then two 24-byte controls:
`u32 validity, u32 buttons, f32 trigger, squeeze, stick_x, stick_y`.
Validity bits 0..6 denote trigger/squeeze/stick/stick-click/primary/secondary/menu;
button bits 0..3 denote stick-click/primary/secondary/menu. V2 pose flags add actual
orientation/position TRACKED bits 4/5. A v1 peer receives only the legacy prefix,
version 1 and masked pose flags; aim and analog values are not synthesized.
OUXD snapshot v2 is 536 bytes inside the unchanged private 4096-byte mapping.

`controller_input_v2` registers left/right Touch-compatible logical devices for
OpenVR/xrizer compatibility, with real grip/aim spaces, analog trigger/squeeze,
stick and digital X/Y/A/B/menu. No capacitive touch, skeleton or haptic feedback
is claimed. Inputs become inactive/neutral on stale tracking; reconnect keeps the
same devices and action bindings. A local toolbar owns axes/buttons while shown,
and release-to-neutral is required before forwarding game input again. During
PCVR, X+A together toggle the local toolbar so individual X/A remain game inputs;
this shortcut still requires headset usability validation.

`tests/controller_fixture.py --runtime MANIFEST --smoke BINARY` publishes synthetic
v2 data for `tests/controller_smoke.cpp`. It verifies the real OpenXR action
binding path for both hands, distinct grip/aim positions, tracked bits, trigger,
stick, primary button and input release after tracking loss. This does not prove
physical-controller capture, VRChat interoperability, or end-to-end control.

### Haptic feedback transport

`haptics_v1` adds `/output/haptic` to both logical controllers. The runtime sends
40-byte OUHP v1 datagrams to the current host's private `haptics.sock`:
magic/version, publisher generation, host monotonic timestamp, hand, amplitude,
frequency, duration-ms. The host accepts only its current generation, packets at
most 250 ms old, amplitude 0..1, frequency 0..1000 Hz and duration at most 1000 ms.
It coalesces bounded batches per hand and forwards them only over the authorized
peer's control channel. The headset additionally checks the peer generation and
current owner. No public socket or global runtime setting is created.

Native OpenXR application occurs only while the session is focused, actual PCVR
video is presenting and the local toolbar is hidden. A stop request, local menu,
loss of focus or revoked stream cancels vibration; pending pulses older than
250 ms are never replayed. XR_MIN_HAPTIC_DURATION maps to a 1 ms pulse; longer
requests are capped at 1 s, and explicit stops remain stops.

The real headless OpenXR controller fixture verifies both hands' apply/stop
requests reach the private socket, including duration capping and minimum-duration
handling. Rust socket/generation tests, Kotlin message validation and native
focus/expiry policy tests cover other boundaries. Physical PICO actuation and
VRChat's end-to-end feedback are still hardware acceptance items, not established
by those fixtures.

### Measured STAGE and Proton/OpenVR initialization

The PICO client optionally creates its runtime's STAGE space and reports its
actual pose in LOCAL alongside eye calibration. The host validates xyz/xyzw and
publishes it only when supplied; OUXD's reserved bit0 marks the measured pose at
offset 536 (the snapshot is now 568 bytes in the same 4096-byte map). Monado
exports STAGE using that transform, without inventing floor height or play-area
bounds. Changed stage transforms are incompatible with same-session reattachment.

A Proton diagnostic using VRChat's unmodified `openvr_api.dll` exposed a real
xrizer startup panic: its unconditional STAGE creation unwrap failed with
`ERROR_REFERENCE_SPACE_UNSUPPORTED`. The source-packaged `xrizer_overlay.py`
propagates that error normally instead of substituting LOCAL as a fake floor or
opening a panic dialog. If PICO cannot provide floor calibration, OpenVR remains
unavailable and the client asks for floor setup/reconnection. Native LOCAL-only
OpenXR clients can still use the tracking bridge.

`tests/openvr_windows_probe.c` is a standalone Windows diagnostic (build with
`zig cc -target x86_64-windows-gnu`). `tests/proton_openvr_fixture.py` supplies
synthetic poses/floor in private temporary IPC and uses a separately marked,
locked diagnostic Proton prefix. It never launches VRChat or modifies its DLL,
game prefix, global runtime, or existing game scene. The probe verifies background
initialization, the five interface versions referenced by the installed
XRSDKOpenVR DLL, and clean shutdown. `--without-stage --expect-init-error` verifies
normal rejection, and `--container /path/to/SteamLinuxRuntime_4/run` exercises the
actual Steam container. Only its own marked Wine prefix is stopped during test
cleanup. A synthetic 1.6 m floor in this fixture is not a runtime default and is
not headset or VRChat gameplay acceptance.

### Calibrated cadence and producer restart

The host uses the headset's validated 30..165 Hz calibration for the encoder's
rate hint and the capture worker's frame budget. Encoding/readback time counts
inside that budget rather than adding a fixed 33 ms sleep after each frame.
Over-budget work does not enqueue catch-up frames: the reader still takes only
the latest complete compositor export. This removes the artificial 30 fps cap;
it does not promise that CPU readback/software encoding sustain the target rate.
Status distinguishes `target_fps` from measured `send_fps` (host-sent complete
frames, not proof of headset display), resetting measurements across generations.

An application's compositor frame ID can reset on process restart. Capture-reader
sequence therefore uses the shared export's completed-publication seqlock serial,
which persists while ownership transfers between producers of the same inode.
Encoded transport sequence is separate: it increments only for produced access
units, so intentionally skipped capture frames do not look like network loss.
The actual source ID still pairs eyes inside the compositor. A replacement
producer no longer waits to exceed an old game's potentially huge frame ID.

CPU export consumption reads each tightly packed eye in one operation instead
of issuing a read for every scanline; a single temporary eye buffer is reused.
The final seqlock check still rejects a pair changed during either read. This is
an optimization of the CPU baseline, not a GPU zero-copy implementation.

### Windows D3D11 submissions and projection-only export

`tests/openvr_d3d11_probe.cpp` uses the IVRCompositor_028 ABI from the SHA-pinned
xrizer archive's `openvr/headers/openvr-2.5.1.h`. Build with `zig c++ -target
x86_64-windows-gnu -O1 -I/path/to/xrizer/openvr/headers ... -ld3d11 -ldxgi`, then
run `proton_openvr_fixture.py --render` with that executable and a dedicated
marked prefix. It uses the runtime-recommended DXGI adapter, clears separate
D3D11 textures red/green, submits 90 frames, and shuts OpenVR down before releasing
its texture/device resources. Success requires actual matching pixels in a
complete compositor export, not merely successful Submit return codes.

This exposed a real startup bug: an empty/background initialization frame could
claim the exclusive export and leave the consumer seeing only black, even when
D3D11 submissions succeeded. `projection_only_export_v1` gates capture on an
actual projection/projection-depth layer, never empty or quad-only output. The
controlled old/new test uses the same installed xrizer and D3D11 probe: the old
runtime exports a single black pair, the new runtime exports distinct red/green
eyes. Native OpenXR graphical regression remains a separate check.

An earlier test destroyed D3D resources before OpenVR shutdown and hung in
cleanup. With corrected test resource ordering, the existing xrizer shuts down
normally; an evaluated cleanup modification was not included in the installed
bundle. D3D11 diagnostics are not VRChat gameplay or PICO decode/display evidence.
