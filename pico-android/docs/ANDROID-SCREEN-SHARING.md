# Android screen sharing protocol

This viewer implements the same-account Android approval flow found in the official UU Remote Android 4.46.0 client. It receives screen video and optional playback audio. Remote input, microphone return, files, clipboard, desktop display changes and XR additional-device entry are disabled for Android targets. Annotation is not implemented.

## Public references and inspected version

- [Official usage guide](https://uuyc.163.com/help/android-screen-sharing.html): same account, consent within 60 seconds, a fresh Android screen-capture grant for each connection, Android/iOS controllers and Android targets.
- [Official download page](https://uuyc.163.com/download/) links through the [Android release endpoint](https://api.nrd.nie.163.com/api/v1/release/dl/2?channel=gwqd). The inspected APK identifies as com.netease.uuremote, version 4.46.0, version code 446000.
- APK SHA-256: 68f41574b0074734647f4f314b47c157609ff6a1d528423871f1a0d6d9e79828. Its ARM64 libnrdsign.so SHA-256: 2cd214374c4ba236b93fd004af73d65d89d0bf690cffc767fb64bb091a162af7. No official binaries are bundled.

## Registration and signing

POST /api/v1/device/android/init uses Android DeviceInitData: name, client_id, system_id, system_version, gaid, install_id, build_fingerprint, brand, manufacturer, model, product, rom, abi, resolution, screen_size, dpi, controllable. There is no body platform field. The viewer sets controllable=false. Observed headers include X-Param-PLAT=2, X-Param-VC=446000 and channel gwqd.

The Java canonicalizer filters empty header values, lowercases and sorts X-Param names, then signs METHOD + path/query + canonical headers + exact request body. Static inspection of native functions 0x4f64 (key decoder), 0x4e30 (ipad/opad) and 0x4944 (SHA-256 compression) establishes the existing HMAC-SHA256 protocol key and algorithm. This is static binary evidence plus independent reference fixtures, not successful execution of the native library or live service authentication.

Android device identity and encrypted credentials use separate preference namespaces from the previous Windows-compatible registration. Upgrading therefore requires one new login. Old credentials are not imported into the new identity; explicit logout clears both credential generations. Device identities remain stable.

## Approval before joining

1. Connect the account online room, acknowledge room_info, and subscribe to bmsg_push transport_rpc before sending a request. This connection does not publish media or grant capture access.
2. POST /api/v1/transport/{targetDeviceId}/rpc/request with p=android_control_request, need_response=false and data containing action=request_control and local device_info (device_id, platform=2, alias, version_name).
3. HTTP success only starts the 60-second approval wait. The reply arrives asynchronously with p=android_control_result; its nested device_info.device_id must match the target.
4. Only accept_control grants a short-lived, single-use join permit. reject_request, switch_is_off, not_foreground, during_control and during_request stop the attempt. Unknown or unrelated messages do not grant access.
5. Join using POST /api/v1/room/join/by_device/{targetDeviceId}, force_join=false. ConnectOptions client type is Android (2); same-account connection type remains Normal (1), not Assistance (2). The target must also complete the Android system screen-capture dialog.

Cancellation sends android_control_request with action=cancel_request. Results carry no request nonce. An unresolved same-device request is therefore quarantined in private preferences for the original 60-second window plus a small delivery grace period before retrying. An uncertain HTTP delivery retains a fresh quarantine. This avoids treating a normal late approval from a cancelled attempt as approval for an immediate retry; it cannot authenticate arbitrarily delayed responses beyond the bounded protocol window. Explicit terminal replies release the quarantine.

## Verification boundary

JVM tests cover Android registration and signing fixtures, platform parsing and consent permission, immediate replies arriving during HTTP submission, rejection and cancellation, push subscription/ACK/heartbeat, unresolved-request retry isolation and single-use join permits. Terminal regressions also cover official server-assigned session identifiers and detach behavior.

A successful real-account Android-to-Android screen-sharing session has not been captured. The system capture dialog, device-specific media negotiation, audio and on-device UI still require a real target. No test result is presented as end-to-end interoperability.
