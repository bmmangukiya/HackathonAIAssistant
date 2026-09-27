#!/bin/sh
# Runs a command as if spoken:  tools/say.sh open youtube and search lofi
# Then watch:  adb logcat -s Assistant LlmPlanner Voice
TEXT="$*"
adb shell "am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es text '$TEXT'" >/dev/null
