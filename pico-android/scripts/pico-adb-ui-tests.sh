#!/usr/bin/env bash
# Synthetic UI and real Android Keystore tests; never request/live-log UU credentials.
set -euo pipefail
if [[ "$(adb get-state 2>/dev/null | tr -d '\r')" != device ]]; then
  echo 'Connect exactly one authorized PICO device.' >&2; exit 2
fi
if [[ $# != 0 && $# != 2 ]]; then echo "Usage: $0 [app.apk test.apk]" >&2; exit 2; fi
if [[ $# == 2 ]]; then adb install -r "$1"; adb install -r "$2"; fi
runner=org.openuuyc.pico.test/org.openuuyc.pico.PicoPanelInstrumentation
for phase in save restore network ui; do
  echo "PICO fixture phase: $phase"
  if [[ "$phase" == ui ]]; then result=$(adb shell am instrument -w "$runner")
  else result=$(adb shell am instrument -w -e vault-phase "$phase" "$runner"); fi
  printf '%s\n' "$result"
  grep -q 'PICO on-device fixture: 1 test passed' <<<"$result" || exit 1
done
echo 'PASS: Android UI fixture and cross-process Keystore and native WebRTC startup fixtures. Not live UU stream proof.'
