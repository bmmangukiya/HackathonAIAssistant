#!/bin/sh
# (Re)enables our accessibility service WITHOUT touching others.
# HackTracker must stay enabled: it is how the organisers score phone usage.
# `adb install` unbinds the service, so we remove and re-add it to force a rebind.
SVC=com.hackathon.assistant/com.hackathon.assistant.perception.AssistantAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
OTHERS=$(echo "$CUR" | tr ':' '\n' | grep -v -x "$SVC" | grep -v -x null | grep -v '^$' | paste -sd: -)
adb shell settings put secure enabled_accessibility_services "${OTHERS:-null}"
sleep 1
adb shell settings put secure enabled_accessibility_services "${OTHERS:+$OTHERS:}$SVC"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services
