#!/bin/sh
# Prints the translated current screen exactly as the planner sees it.
adb logcat -c
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --ez dump true >/dev/null
sleep 1.5
adb logcat -d -s ScreenDump:I | sed 's/^.*ScreenDump: //' | grep -v '^-----'
