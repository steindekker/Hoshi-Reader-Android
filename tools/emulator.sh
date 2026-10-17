#!/usr/bin/env bash
# Headless emulator helper for local agent testing.
#
#   tools/emulator.sh create [avd]      create the AVD if missing (hoshi-api36 or hoshi-api28)
#   tools/emulator.sh start [avd]       boot headless, wait for boot, disable animations
#   tools/emulator.sh stop              kill the running emulator
#   tools/emulator.sh install           assembleDebug + adb install -r (preserves app data)
#   tools/emulator.sh launch            start the debug app
#   tools/emulator.sh ui                list on-screen native elements: text | desc | id | x,y
#   tools/emulator.sh webview [pkg]     forward the app's WebView DevTools to :9222 for tools/awv
#   tools/emulator.sh screenshot FILE   save a PNG of the current screen (visual checks only)
#
# ANDROID_SERIAL selects the device when more than one is connected.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.14206865}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_AVD=hoshi-api36
DEBUG_PACKAGE=nl.steindekker.hoshi.debug

image_for() {
  case "$1" in
    hoshi-api36) echo "system-images;android-36;google_apis;x86_64" ;;
    hoshi-api28) echo "system-images;android-28;google_apis;x86_64" ;;
    *) echo "unknown AVD: $1" >&2; exit 1 ;;
  esac
}

create() {
  local avd="${1:-$DEFAULT_AVD}"
  "$EMULATOR" -list-avds | grep -qx "$avd" && return
  echo no | "$AVDMANAGER" create avd -n "$avd" -k "$(image_for "$avd")" -d pixel_7
  printf 'hw.keyboard=yes\nhw.ramSize=4096\ndisk.dataPartition.size=8G\n' \
    >> "$HOME/.android/avd/$avd.avd/config.ini"
}

start() {
  local avd="${1:-$DEFAULT_AVD}"
  if "$ADB" devices | grep -q '^emulator-.*device$'; then
    echo "emulator already running"
  else
    create "$avd"
    nohup "$EMULATOR" -avd "$avd" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect > "${TMPDIR:-/tmp}/hoshi-emulator.log" 2>&1 &
  fi
  timeout 300 "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$setting" 0
  done
  # The host resolver can return IPv6-only answers the emulator NAT can't use.
  "$ADB" shell settings put global private_dns_mode hostname
  "$ADB" shell settings put global private_dns_specifier one.one.one.one
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell wm dismiss-keyguard
  echo "booted: $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
}

ui() {
  local xml
  xml=$("$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null || true)
  if [[ "$xml" != *"<hierarchy"* ]]; then
    # mobile-mcp's on-device server holds UiAutomation and makes uiautomator dump fail.
    "$ADB" shell pkill -f com.mobilenext.mobilecli.DeviceServer 2>/dev/null || true
    xml=$("$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null || true)
  fi
  [[ "$xml" == *"<hierarchy"* ]] || { echo "uiautomator dump failed" >&2; exit 1; }
  python3 -I -c '
import re, sys
for n in re.findall(r"<node [^>]*>", sys.stdin.read().split("</hierarchy>")[0]):
    a = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", n))
    t, d, i = a.get("text", ""), a.get("content-desc", ""), a.get("resource-id", "")
    if "systemui" in i or not (t or d or i or a.get("clickable") == "true"): continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"]))
    flag = " *" if a.get("clickable") == "true" else ""
    rid = i.split("/")[-1]
    print(f"{t} | {d} | {rid} | {(x1+x2)//2},{(y1+y2)//2}{flag}")
' <<<"$xml"
}

webview() {
  local pkg="$1" pid sock
  pid=$("$ADB" shell pidof -s "$pkg" | tr -d '\r') || { echo "$pkg is not running" >&2; exit 1; }
  # Background apps get frozen and stop answering DevTools.
  "$ADB" shell am unfreeze --sticky "$pkg" >/dev/null 2>&1 || true
  sock=$("$ADB" shell cat /proc/net/unix | tr -d '\r' | grep -oE "webview_devtools_remote_${pid}\$" | head -1 || true)
  [[ -n "$sock" ]] || { echo "no WebView DevTools socket for $pkg: open a WebView screen in a debuggable build" >&2; exit 1; }
  "$ADB" forward "tcp:${AWV_PORT:-9222}" "localabstract:$sock" >/dev/null
  echo "$pkg WebView -> localhost:${AWV_PORT:-9222} (use tools/awv)"
}

case "${1:-}" in
  create) create "${2:-}" ;;
  start) start "${2:-}" ;;
  stop)
    "$ADB" emu kill || true
    while pgrep -f 'qemu-system.*-avd' >/dev/null; do sleep 1; done ;;
  install)
    (cd "$ROOT" && ./gradlew assembleDebug)
    "$ADB" install -r "$ROOT"/app/build/outputs/apk/debug/app-debug.apk ;;
  launch) "$ADB" shell am start -W -n "$DEBUG_PACKAGE/moe.antimony.hoshi.MainActivity" ;;
  ui) ui ;;
  webview) webview "${2:-$DEBUG_PACKAGE}" ;;
  screenshot) "$ADB" exec-out screencap -p > "${2:?output file}" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
