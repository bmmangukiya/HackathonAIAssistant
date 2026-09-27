#!/bin/sh
# Runs a command and records the full agent trace (prompts, model output, actions, voice).
#   tools/trace.sh open playstore and download whatsapp
# Output: stdout and traces/<timestamp>.log
mkdir -p traces
OUT="traces/$(date +%H%M%S).log"
# Some OEM builds set log.tag to a non-standard level and use a tiny log buffer, which
# silently drops our logs; make sure both are sane.
adb shell setprop log.tag V >/dev/null 2>&1
adb logcat -G 16M >/dev/null 2>&1
adb logcat -c
sh "$(dirname "$0")/say.sh" "$*"
( adb logcat -v time -s Assistant LlmPlanner Prompt LiteRtLlm Voice AppCatalog AndroidRuntime > "$OUT" ) &
PID=$!
# Stop when the agent has spoken its last line (or after 4 minutes).
for i in $(seq 1 120); do
  sleep 2
  grep -qE "speak: (Done\.|Sorry, I got confused|Sorry, something went wrong|That took|I.m stuck|Okay, stopping|Okay, cancelled|Okay, I won.t|Please turn)|-> finish\{" "$OUT" && sleep 3 && break
  grep -q "(final)" "$OUT" && grep -q "speak:" "$OUT" && sleep 2 && break
done
kill $PID
echo "$OUT"
