# Status

_Last updated: Sun 27 Sep, ~01:45 IST. Kept current with every push._

## Done
- **ReAct agent loop** (`app/.../Assistant.kt`). Each step: capture screen → transformer →
  model returns `{thought, tool, args, final, screen}` → run tool → **wait until the UI has
  reacted and settled** (`ScreenReader.awaitSettled`) → observation → next step.
- **Reliability layer** (this branch; see `docs/WORKFLOW.md`, `docs/RELIABILITY.md`):
  JARVIS master prompt with a 5-phase workflow and few-shot examples; host-side cycle detection,
  stall recovery, 90 s request deadline, per-call 30 s timeout, arg validation with repair retry,
  exceptions → observations, TERMINAL failures, spoken stop reasons, `open <app>` fast path,
  installed-app names as STT hints, graceful fallback when the model fails to load.
  Measured on device: the "order on Zepto (not installed)" request went from **15 steps / 2.5 min /
  "too many steps"** to **2 steps / 23 s / "Zepto isn't installed on this phone…"**.
- **Tools**: 16 skills (intents/deep links), screen actions (tap/type/enter/scroll/back/home),
  `ask_user`, `ask_choice`, `fill_field`, `finish`. The tool name is a schema enum and `id` is an
  integer, so the model cannot invent tools or ids.
- **Transformer** (`perception/.../ScreenTranslator.kt`): the active window plus dialogs, one
  element per actionable node, labels from children or overlay text. The service is declared an
  accessibility tool, so it can see "data-sensitive" views such as sign-in and payment buttons.
- Voice: Google on-device speech recognition (en-IN with en-US fallback), Google TTS, "Jarvis"
  hotword, bottom-sheet card with live steps.
- Model: **Gemma 4 E4B** on GPU via LiteRT-LM, 8k context, system prompt kept in the KV cache per run
  (mainline prompt caching). Switch with `--es model <file>` (remembered). See below and `docs/MODELS.md`.
- SMS reader skill (Bansi).

## Debug tools
- `tools/install.sh`: build, install, grant permissions, re-enable accessibility (keeps HackTracker on).
- `tools/say.sh <command>`: run a command as if spoken.
- `tools/trace.sh <command>`: full trace (every prompt, model output, action, observation) → `traces/`.
- `tools/dump_screen.sh`: the translated screen as the model sees it. `--ez raw true`: the raw tree.
- `--es bench <model>.litertlm`: scored 9-case eval, `adb logcat -s LlmBenchmark`.

## Known issues / next
- Latency is ~6 s per step on Qwen3-1.7B (prompt ~6.5k chars, ~70% identical across steps).
  Next: reuse the cached prefix (LiteRT-LM conversation reuse with a per-step suffix).
- The "list_apps again" eval case still fails on the model; the host repairs it deterministically
  (`appNamedInGoal`). A prefix cache would let a longer example list fix it in-model too.
- The test phone's Play Store is **not signed in**; app installs need a Google account.
- Not yet re-tested on device after this branch: call/SMS confirm flow end to end, Maps, camera,
  settings, volume, Spotify, `fill_field` on a real sign-up form.

## Model decision
Default since the jarvis-voice-card merge: **Gemma 4 E4B** (`gemma-4-E4B-it-gpu.litertlm`), for its per-step
judgement on long multi-screen tasks; prompt caching removes most of the per-step prompt cost. The Qwen3-1.7B
notes below are kept for comparison; it is still one `--es model Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm` away.

### Qwen3-1.7B int4 (GPU), the previous default
`Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`, 0.98 GB, Apache-2.0. Load 1–14 s; **~6 s per step**;
**8/9** on the scored eval with the JARVIS v3 prompt (up from 2/9 with the first workflow prompt:
the launcher description line and the lack of examples were the whole difference).
Gemma 4 E4B is 8/9 (9/9 effective) but ~17 s mean / 21 s p50 per step once warm and non-OSI.
Gemma 4 12B (6 GB) did not finish a single benchmark in 10 minutes. Full table in `docs/MODELS.md`.

## Open questions
- Red Light schedule and which Office Kit actions HackTracker counts (ask at the teach-in).
- Is there a working NPU model for SM8850? (Teammate B, model research)

## Models on the test phone
`/sdcard/Download/models/` (outside the app, so uninstalling does not delete them). Push with
`adb push model.litertlm /sdcard/Download/models/`. The app needs All files access: `tools/install.sh` grants it.
Present now: `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`, `qwen3_4b_instruct_2507_mixed_int4.litertlm`,
`gemma-4-E4B-it-gpu.litertlm`.
