#!/bin/sh
# Build, install (keeping app data and models), re-enable accessibility.
set -e
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug -q
# Some OEM builds set log.tag to a non-standard level and use a tiny log buffer, which
# silently drops our logs; make sure both are sane.
adb shell setprop log.tag V >/dev/null 2>&1
adb logcat -G 16M >/dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
sleep 2  # let the install settle before granting permissions
for p in RECORD_AUDIO CALL_PHONE SEND_SMS READ_CONTACTS READ_SMS BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_MEDIA_IMAGES; do adb shell pm grant com.hackathon.assistant android.permission.$p; done
adb shell appops set --uid com.hackathon.assistant MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.hackathon.assistant SYSTEM_ALERT_WINDOW allow
# Jarvis: notification for the always-on mic service, skills' runtime permissions, and the
# accessibility self-heal (vivo strips the service on app switch). Some phones refuse a grant
# ("security settings" off); that shouldn't stop the install.
for p in POST_NOTIFICATIONS READ_CALENDAR READ_CALL_LOG WRITE_SECURE_SETTINGS; do
  adb shell pm grant com.hackathon.assistant android.permission.$p || echo "could not grant $p"
done
# "Put the phone on silent" needs Do Not Disturb access.
adb shell cmd notification allow_dnd com.hackathon.assistant || echo "could not allow DND access"
sh tools/enable_a11y.sh
