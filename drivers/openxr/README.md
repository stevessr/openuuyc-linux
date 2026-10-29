# OpenUUYC-owned OpenXR components

## Backend policy

Default: **OpenXR first, SteamVR fallback**. `openxr-only` forbids fallback; `steamvr` explicitly selects the backup. A corrupt owned bundle is an error, not permission to use another runtime. An initialized OpenXR runtime without a headset can fall back to the installed SteamVR runtime; the selected runtime must actually expose a system before the launcher starts a game.

The policy is available in Linux host settings and through each authorized connection's app menu (including the XR controller menu). A setting applies to the next connection/launch, not an active VR session. These extension controls require the host's remote-terminal permission.

## Actual components

- Monado `59be345db769a89c93d1bdf6ed8316fa6209d8ae`, built as `libopenxr_openuuyc.so`, **in-process**, no service/dashboard executable, no simulated/remote/physical-hardware driver enabled.
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

The component bundle was built from the pinned sources and installed locally. The real OpenXR loader successfully created an instance (`instance_result=0`); `xrGetSystem` failed (`system_result=-6`) because there is **no PICO xrt_device driver/compositor target yet**. No fake HMD is registered to disguise this.

The preceding SBS transport/decoder/OpenXR projection work is a receiver foundation, not a complete host runtime. Still required: a Monado PICO device builder with real headset calibration/input/clock alignment, a Vulkan compositor target feeding the authenticated OpenUUYC encoder/media path, controller/haptics support, and end-to-end game/headset validation. Stock Monado plus xrizer alone does not complete these pieces. The SteamVR fallback bridge also remains tracking-only.

The manual `Owned OpenXR components` GitHub Actions workflow builds a separate Linux component artifact from the lock file and checks actual loader initialization. It is not run/downloaded by ordinary application startup. The artifact is a tar archive so executable modes survive upload/download. This workflow has been added but has not been executed remotely during this change.

Local checks for this integration: 218 Android JVM tests plus APK/lint passed; 14 PCVR Rust tests and 3 quick-app tests passed; the host CLI was built and used to install the real bundle. `runtime-status` confirmed instance creation without a system. A negative launch test in `openxr-only` mode confirmed a supplied command was not executed without an HMD. Before/after hashes confirmed the existing global OpenXR/OpenVR configuration files were unchanged.
