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
