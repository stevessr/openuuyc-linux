#!/usr/bin/env bash
# Manual PICO hardware smoke test; no real UU login or host access is automated.
set -euo pipefail

APP_ID="org.openuuyc.pico"
APK="${1:-}"

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: Android SDK platform-tools (adb) not installed" >&2
  exit 2
fi
if [[ -n "$APK" && ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 2
fi
if [[ "$(adb get-state 2>/dev/null | tr -d '\r')" != "device" ]]; then
  echo "ERROR: connect and authorize exactly one PICO headset using USB debugging" >&2
  exit 2
fi

echo "PICO model: $(adb shell getprop ro.product.model | tr -d '\r')"
echo "Android API: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ -n "$APK" ]]; then
  adb install -r "$APK"
fi

if ! adb shell pm path "$APP_ID" | grep -q 'package:'; then
  echo "ERROR: $APP_ID is not installed (pass APK path as first argument)" >&2
  exit 1
fi

echo "Launching native OpenUUYC PICO Activity..."
adb shell am start -W -n "$APP_ID/.PicoActivity"
sleep "${SMOKE_SECONDS:-5}"
pid="$(adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
  echo "FAIL: native Activity process exited or crashed" >&2
  exit 1
fi

# Query only the newly launched process, not unrelated Android/system logs.
if crash_lines="$(adb logcat --pid="$pid" -d -t 300 2>/dev/null)"; then
  if grep -Ei '(FATAL EXCEPTION|Fatal signal|SIGSEGV|JNI DETECTED ERROR)' <<<"$crash_lines" >&2; then
    echo "FAIL: Android/NDK crash detected" >&2
    exit 1
  fi
else
  echo "WARN: process-scoped logcat unavailable; only liveness was checked" >&2
fi

echo "PASS: app launched and process $pid is alive."
echo "NEXT: verify OpenXR stereo surface, UU QR login, device selection, video, and trigger input manually."
