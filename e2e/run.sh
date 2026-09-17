#!/usr/bin/env bash
# 에뮬레이터에서 진짜 Termux 로 끝까지 확인한다:
#   앱 → 가짜 AI 서버가 bash 블록을 줌 → 앱이 Termux 에 실행을 맡김 → Termux 출력이 다시 서버로 가는가.
# CI(android-emulator-runner) 에서 돈다. Termux 깃헙 판은 디버그 빌드라 run-as 로 설정 파일을 쓸 수 있다.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=io.github.parkjaehyeon12354.zakocode
TERMUX_APK_URL="https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_x86_64.apk"

rm -f e2e/log.jsonl
python3 e2e/mock.py 8765 e2e/log.jsonl &
MOCK=$!
trap 'kill $MOCK 2>/dev/null || true' EXIT

curl -fsSL -o termux.apk "$TERMUX_APK_URL"
adb install -r termux.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Termux 를 한 번 열어야 bash 같은 기본 꾸러미(bootstrap)를 푼다
adb shell am start -n com.termux/.app.TermuxActivity
for i in $(seq 1 150); do
  if adb shell run-as com.termux test -x files/usr/bin/bash; then echo "termux ready after ${i}x2s"; break; fi
  sleep 2
done
adb shell run-as com.termux test -x files/usr/bin/bash

# 사용자가 README 대로 하는 설정과 같은 것: 다른 앱의 명령 허용 + 이 앱에 권한
adb shell "run-as com.termux sh -c 'mkdir -p files/home/.termux && echo allow-external-apps=true > files/home/.termux/termux.properties'"
adb shell am force-stop com.termux
adb shell pm grant "$PKG" com.termux.permission.RUN_COMMAND

adb logcat -c
adb shell am start -n "$PKG/.MainActivity" --es e2e_root http://10.0.2.2:8765/v1 --es e2e_prompt make-a-file
result=""
for i in $(seq 1 90); do
  result=$(adb logcat -d -s ZakoE2E:I | grep -E 'DONE|FAIL' || true)
  if [ -n "$result" ]; then break; fi
  sleep 2
done

echo "--- app: ${result:-no result}"

# 화면 사진 — 검사가 실패해도 무엇이 보였는지 남긴다 (CI 가 screens 로 올린다)
mkdir -p screens
shot() { sleep "$1"; adb exec-out screencap -p > "screens/$2.png"; }
shot 3 1-chat
adb shell am start -S -n "$PKG/.MainActivity"
shot 5 2-dashboard
adb shell am start -S -n "$PKG/.MainActivity" --es e2e_screen drawer
shot 5 3-drawer
adb shell am start -S -n "$PKG/.MainActivity" --es e2e_screen settings
shot 5 4-settings
adb shell am force-stop "$PKG"
echo "--- logcat (termux / zako) ---"
adb logcat -d | grep -i -E 'termux|zako|AndroidRuntime' | tail -60 || true
echo "--- mock log ---"
cat e2e/log.jsonl || true

[[ "$result" == *DONE* ]]
echo "--- file made inside Termux home ---"
made=$(adb shell run-as com.termux cat files/home/zako-e2e.txt)
echo "$made"
[[ "$made" == *zako-e2e-ok* ]]
python3 e2e/check.py e2e/log.jsonl
echo "E2E OK"
