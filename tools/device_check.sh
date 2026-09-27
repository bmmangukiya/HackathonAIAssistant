#!/bin/sh
# Read-only health check of the phone for the Jarvis pipeline. Changes nothing on the device.
#   tools/device_check.sh            # prints a report; also saved to traces/device_check-<time>.txt
# Checks: adb setup, device/SoC/OS, memory/storage, NPU + GPU vendor libs, speech/TTS services,
# app install + permissions + accessibility, models on disk, llama.cpp runtime, battery/thermal.
cd "$(dirname "$0")/.."
PKG=com.hackathon.assistant
mkdir -p traces
OUT="traces/device_check-$(date +%H%M%S).txt"

sec() { printf '\n== %s ==\n' "$1"; }
ok()  { printf '  [ok]   %s\n' "$1"; }
bad() { printf '  [FAIL] %s\n' "$1"; }
inf() { printf '  %s\n' "$1"; }
sh_() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
has() { [ -n "$(sh_ "ls $1 2>/dev/null")" ]; }

{
sec "adb (Mac)"
inf "adb in PATH: $(command -v adb) ($(adb version | sed -n 2p))"
for a in "$HOME/Library/Android/sdk/platform-tools/adb" /opt/homebrew/bin/adb /Applications/pcsuite.app/Contents/Resources/adb/adb; do
  [ -x "$a" ] && inf "found: $a ($("$a" version 2>/dev/null | sed -n 2p))"
done
inf "Several adb versions share port 5037 and restart each other's server. Use one (PATH) and keep Office Kit's USB mode off while using adb, or connect Office Kit over Wi-Fi."
if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
  bad "no phone over adb. USB: set 'File transfer' mode, enable USB debugging, accept the RSA prompt. Wi-Fi: Developer options > Wireless debugging > adb pair <ip:port>"
  exit 1
fi
ok "phone connected: $(adb get-serialno)"

sec "Device"
inf "model:    $(sh_ getprop ro.product.marketname) / $(sh_ getprop ro.product.model)"
inf "soc:      $(sh_ getprop ro.soc.model) ($(sh_ getprop ro.board.platform)), abi $(sh_ getprop ro.product.cpu.abi)"
inf "android:  $(sh_ getprop ro.build.version.release) (SDK $(sh_ getprop ro.build.version.sdk)), OS $(sh_ getprop ro.vivo.os.build.display.id)"
inf "ram:      $(sh_ cat /proc/meminfo | awk '/MemTotal|MemAvailable/{printf "%s %.1f GB  ", $1, $2/1048576}')"
inf "storage:  $(sh_ df -h /sdcard | tail -1 | awk '{print $4 " free of " $2}')"

sec "NPU / GPU vendor libraries (needed by QNN, llama.cpp Hexagon, LiteRT)"
for l in /vendor/lib64/libcdsprpc.so /vendor/lib64/libOpenCL.so /system/vendor/lib64/libOpenCL.so; do
  has "$l" && ok "$l" || inf "missing $l"
done
inf "public native libs: $(sh_ cat /vendor/etc/public.libraries.txt | tr '\n' ' ')"
inf "Hexagon DSP images: $(sh_ 'ls /vendor/dsp/cdsp 2>/dev/null | head -5' | tr '\n' ' ')"
has "/vendor/lib64/libQnnHtp.so" && ok "vendor ships libQnnHtp.so" || inf "no vendor QNN libs: bundle QNN runtime libs in the APK (normal)"

sec "Speech services (fallback STT/TTS)"
for p in com.google.android.as com.google.android.tts com.google.android.googlequicksearchbox com.vivo.agent; do
  v=$(sh_ dumpsys package $p | awk -F= '/versionName/{print $2; exit}')
  [ -n "$v" ] && ok "$p $v" || inf "not installed: $p"
done
inf "default recognizer: $(sh_ settings get secure voice_recognition_service)"
inf "default assistant:  $(sh_ settings get secure assistant)"

sec "vivo Office Kit (phone side)"
sh_ pm list packages | grep -i -E 'pcsuite|pcconnect|vivo.share|vivo.office|easyshare|vivo.vcast|multidevice' | sed 's/^/  /'

sec "App"
if sh_ pm list packages | grep -q "package:$PKG\$"; then
  ok "$PKG installed ($(sh_ dumpsys package $PKG | awk -F= '/versionName/{print $2; exit}'))"
  for p in RECORD_AUDIO CALL_PHONE SEND_SMS READ_SMS READ_CONTACTS POST_NOTIFICATIONS; do
    sh_ dumpsys package $PKG | grep -q "android.permission.$p: granted=true" && ok "$p" || inf "not granted: $p"
  done
  [ "$(sh_ appops get --uid $PKG MANAGE_EXTERNAL_STORAGE | grep -c allow)" -gt 0 ] && ok "All files access" || bad "All files access not granted (tools/install.sh)"
  sh_ settings get secure enabled_accessibility_services | grep -q "$PKG" && ok "accessibility service enabled" || bad "accessibility service off (tools/enable_a11y.sh)"
  sh_ dumpsys deviceidle whitelist | grep -q "$PKG" && ok "battery optimisation: allowlisted" || inf "not on battery allowlist (foreground mic service may be killed)"
else
  bad "$PKG not installed (tools/install.sh)"
fi

sec "Models on the phone"
sh_ ls -la /sdcard/Download/models/ | awk 'NR>1{printf "  %8.2f GB  %s\n", $5/1073741824, $NF}'
sh_ ls -la /sdcard/Download/models/jarvis/ 2>/dev/null | awk 'NR>1{printf "  jarvis/ %8.2f MB  %s\n", $5/1048576, $NF}'

sec "llama.cpp runtime (scripts/02_setup_runtime.sh)"
for d in /data/local/tmp/llama.cpp /data/local/tmp/pkg-snapdragon/llama.cpp; do
  has "$d/bin/llama-server" && ok "llama-server in $d" && inf "devices: $(sh_ "cd $d && LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./lib ./bin/llama-cli --list-devices" | tr '\n' ' ')"
done
inf "listening on 8080: $(sh_ 'netstat -tln 2>/dev/null | grep :8080' || echo no)"

sec "Thermal / battery"
inf "thermal status: $(sh_ dumpsys thermalservice | awk -F: '/Thermal Status/{print $2; exit}')"
inf "battery: $(sh_ dumpsys battery | awk -F': ' '/level|temperature/{printf "%s=%s ", $1, $2}')"
} 2>&1 | tee "$OUT"
echo "\nsaved: $OUT"
