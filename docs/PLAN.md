# Team plan

## What we're building

A voice-only assistant for Android. The user speaks, and the assistant replies and asks
follow-up questions out loud. It completes tasks inside *any* app by reading the
accessibility tree and acting on it. All inference runs on-device (Snapdragon 8 Elite
Gen 5 / SM8850, 12 GB). No cloud LLM.

## Architecture

```
 mic ──► :voice (STT) ──► Assistant (:app) ──► :llm Planner.route()
                               │                     │
                               │        ┌────────────┴─────────────┐
                               │   UseSkill(id,args)          Navigate(goal)
                               │        │                          │
                               │   :actions Skill.execute()   loop: :perception capture()
                               │   (deep links / intents)          → toPrompt() → Planner.nextStep()
                               │        │ fails? ──────────────►   → UiController.perform()
                               ▼
 speaker ◄── :voice (TTS) ◄── result / clarifying question / confirmation
```

**Why skills come first.** A small on-device model is bad at driving raw UI trees and
good at choosing from a short menu. So most requests become typed skill calls with
slots (for example `call_contact(name)` or `set_alarm(time)`), run through
intents and deep links. UI navigation is the fallback for everything else. It sees a
**translated** screen: a numbered list of meaningful elements, not the raw tree.

**Contracts** live in `:core` (`Screen.kt`, `UiAction.kt`, `Skill.kt`, `Voice.kt`, `Planner.kt`).
They are frozen. To change one, post in the team chat first, keep the change small, and
push it on its own.

## Ownership

| Who | Area | Delivers |
|---|---|---|
| **Vishnu (+Claude)** | All code modules: `:core`, `:app`, `:llm`, `:perception`, `:actions`, `:voice` | The working app, end to end. Commits land on `main` continuously. |
| **Teammate B** | Model research | Which local model and backend wins on this phone (see "Model research" below) |
| **Teammate C** | UI / UX design | Assistant overlay, dev console, onboarding and the demo look (see "UI design" below) |

Current progress is tracked in [`docs/STATUS.md`](STATUS.md). Read it before starting work.

### Teammate B: model research (hand this section to your agent)
Goal: pick the best on-device model and backend for (a) routing a request to a skill and
(b) choosing one UI action per step, within ~2 s per call on this SM8850 phone.
- Candidates from `litert-community` on Hugging Face (all ungated `.litertlm`): Gemma 4 E2B/E4B/12B
  (GPU builds), Qwen3.5-4B (mixed int4), Agents-A1-4B, Ministral-3-3B, LFM2.5-2.6B. An NPU build for
  SM8850 would be the prize. Only `gemma-4-E2B-it_qualcomm_sm8750` exists right now; check whether
  it runs on SM8850 or whether AI Hub / QNN can compile one.
- Benchmark with our real prompts on the phone. Push a model to
  `/sdcard/Download/models/`, then run
  `adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es bench <file>.litertlm`
  and read `adb logcat -s LlmBenchmark LiteRtLlm LlmPlanner`.
- Deliver `docs/MODELS.md`: a table of model, backend, load time, route ms, step ms, and
  correctness on the benchmark cases, plus a recommendation.
- Stretch goals: on-device STT options better than Android's recognizer for Indian English
  (Whisper / Moonshine / sherpa-onnx / VibeVoice-ASR on litert-community), and TTS voices (Kokoro).

### Teammate C: UI design (hand this section to your agent)
Voice-only for the user, but judges watch the screen. Design:
- **Assistant overlay**: a floating orb or edge glow over other apps showing
  listening/thinking/speaking, the live transcript, and the current step ("Tapping *Send*").
- **Dev console** (in-app): transcript, translated screen (`toPrompt()` output), last prompt and
  response, latency. Must be readable when mirrored through Office Kit.
- **Onboarding**: mic permission, accessibility service, default-assistant role, model download status.
- Deliver Figma or Compose mockups plus a colour/typography spec in `docs/UI.md`. Compose code
  goes in `app/src/main/java/com/hackathon/assistant/ui/` (that package only, to avoid conflicts).
  Read `VoiceIO.state` (a `StateFlow<VoiceState>`) for the animation state.

### Vishnu + Claude: `:llm` + `:app`
- `LiteRtLlm`: load `.litertlm` from `getExternalFilesDir("models")` (push it with adb). Try
  the NPU, then the GPU, then the CPU. Candidate models: Gemma 3n E2B/E4B and Qwen3 1.7B/4B.
- `LlmPlanner`: routing prompt = skill menu (id, description, slots, 1 example) + utterance
  → strict JSON. The step prompt uses the `toPrompt()` output. Use a tolerant JSON extractor
  and retry once on a parse failure.
- Fast path: a rule-based matcher for exact phrasings ("open X", "call X") skips the LLM.
- Dev console screen: live transcript, current `ScreenState`, the last prompt and response,
  and latency. Useful during Red Light and a strong visual for judges.

## Scoring: where the points are
- **End product 30%**: 8–10 flows that work reliably beat 50 flaky ones. Build an eval list early.
- **Novelty 20%**: skills-first plus a translated screen, and voice-only clarification
  and confirmation. Stretch goal: "teach mode" (the user demonstrates a task once and we
  record the accessibility events as a new skill).
- **Creative phone use 15%**: voice, on-device NPU, camera skill.
- **Technical depth 15%**: the clean module boundaries and the NPU are the talking points.
- **Office Kit 10%**: see Red Light below.
- **Demo 10%**: scripted 3–5 minute run on the phone, mirrored through Office Kit.

## Git rules
- Pull often: `git pull --rebase`. Commit small and push often. Only touch your own module.
- Commit message format: `<module>: <what>` (for example `perception: collapse clickable parents`).
- Don't commit model files (`*.litertlm`, `*.task`), APKs, or `local.properties`.
- Before you push: `./gradlew :app:assembleDebug` passes.

## Red Light (phone-only, via Office Kit)
Phase work so Red Light hours are for **testing and tuning on the phone**, not writing code:
- Before Red Light starts: push, build, and install the latest APK. Push the model file.
- During Red Light: run the eval list by voice across real apps. Log failures on the phone,
  in a shared note or the dev console. Use Office Kit screen mirror, remote control and
  file transfer to move logs, screen dumps and APKs. Record demo takes.
- Keep prompts and skill examples editable from the dev console so tuning can happen on
  the phone.
- Ask at the Saturday teach-in which Office Kit actions HackTracker counts, and plan
  around the answer.

## Testing on device
```
./gradlew :app:installDebug
adb shell settings put secure enabled_accessibility_services com.hackathon.assistant/com.hackathon.assistant.perception.AssistantAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es text "open youtube"
adb logcat -s Assistant
```
Note: `adb install` clears the accessibility setting. Re-run the two `settings` lines after every install.
