# On-device model evaluation

_Measured Sat 27 Sep 2026, 01:25–01:40 IST on the test phone (SM8850, 12 GB, vivo/iQOO), GPU backend
via LiteRT-LM, greedy decoding, thinking off, JSON-schema-constrained output. Benchmark:
`LlmBenchmark` (9 scored cases, see below). Run it yourself:_

```
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es bench <file>.litertlm
adb logcat -s LlmBenchmark
```

## Results

| Model | Size | Load | Score | Step p50 | Step mean | Step max | Notes |
|---|---|---|---|---|---|---|---|
| **Qwen3-1.7B int4** (`Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`) | 0.98 GB | 1–14 s | **8/9** | **6.0 s** | **6.2 s** | 7.9 s | Default. Fast enough for a 5–8 step task. |
| Gemma 4 E4B (`gemma-4-E4B-it-gpu.litertlm`) | 3.0 GB | 18 s | 8/9 (9/9 effective¹) | 21.5 s | 17.3 s | 24.4 s | Best judgement, but 3× slower and degrades as the phone warms (9.6 s → 22 s across the run). Non-OSI licence. |
| Qwen3-4B int4 (`qwen3_4b_instruct_2507_mixed_int4.litertlm`) | 2.7 GB | — | not re-run | ~16–23 s² | — | — | Prior measurement in `LiteRtLlm` comment; same speed class as Gemma. |
| Gemma 4 12B | 6 GB | — | unusable | — | — | — | Did not finish a benchmark in 10 min; phone unresponsive over adb (STATUS.md). |

¹ The one "FAIL" for Gemma was `whatsapp_message` (the real registered skill) vs the benchmark's
stub name `send_whatsapp`; the choice was correct. The accept set was widened after the run.
² Not re-measured this session; from the previous model swap.

### Qwen3-1.7B: effect of the prompt rewrite (same model, same phone, same 9 cases)

| Prompt version | Score | Step mean | What changed |
|---|---|---|---|
| Original rules-prose prompt (before this branch) | not scored (loop bug, see RELIABILITY F1) | 9–11 s | 7.3–9.4k-char prompts |
| JARVIS v1: persona + 5-phase workflow, no examples, launcher line "open one first" | **2/9** | 7.6 s | Every skill request from the home screen became `open_app`/`open_settings`/`list_apps`. |
| JARVIS v2: + 6 few-shot examples, launcher line rewritten, schema key order `thought,tool,…,screen` | **7/9** | 6.75 s | Skills, answers and TERMINAL recovery now correct. |
| JARVIS v3: + 2 more examples (list already shown, vague request), deduped tool list | **8/9** | 6.15 s | Only the "list_apps again" case fails; the host repairs it at runtime. |

The lesson for a 1.7B model, in priority order:

1. **Every line of context is an instruction.** `"No app is open; open one first."` was meant as
   a description and was obeyed as a command. One sentence moved the score from 2/9 to 7/9.
2. **Examples beat rules.** Six one-line `goal -> JSON` examples did more than the whole RULES block.
3. **Decode order matters** under constrained decoding. Putting `thought` and `tool` before `screen`
   stops the model from spending its first tokens copying the screen text.
4. **Shorter is faster and better.** Deduping tools and trimming param descriptions cut prompt size
   ~30% and step time ~20%, with no loss in accuracy.

## The 9 cases

| Case | Goal | Correct if |
|---|---|---|
| route: open app | "open youtube" | `open_app` |
| route: skill w/ slot | "call mom" | `call_contact(contact≈mom)` |
| route: time normalisation | "set an alarm for 6 30 tomorrow" | `set_alarm(time=06:30)` |
| route: two slots | "send a whatsapp message to rahul saying I'm running late" | `send_whatsapp`/`whatsapp_message`/`open_app` |
| answer: general knowledge | "what's the capital of australia" | `finish(answer∋Canberra)` |
| clarify: vague | "do the thing" | `ask_user` or `finish` |
| screen: tap right chat | WhatsApp chat list, "Send Rahul…" | `tap(id=6)` |
| recover: app not installed | history has a TERMINAL not-installed observation | `finish`, or `open_app(Play Store)`; never `open_app(Zepto)` |
| recover: app list already shown | history has the list with "Notes (com.vivo.notes)" | `open_app(Notes)` |

The last two are the exact situations that failed on the device (see `docs/RELIABILITY.md` F1, F4,
F5). They test *recovery from history*, which is where small models loop.

## Recommendation

**Update (merge into mainline):** the default is now **Gemma 4 E4B**, with the system prompt kept in the KV
cache across a run so each step only sends what is new. The original recommendation follows.

**Keep Qwen3-1.7B int4 as the default.** With the v3 prompt it matches Gemma 4 E4B on this eval
while being 3× faster and fully open-source. The remaining gap (re-asking for the app list) is
handled deterministically by the host (`Assistant.appNamedInGoal` repair + cycle stop), which is
where that logic belongs regardless of model.

Use **Gemma 4 E4B** only if a demo needs a long multi-screen navigation where per-step judgement
matters more than latency, and accept ~20 s/step once the phone is warm.

## What would move the needle next

- **Prefix caching.** ~70% of every prompt (persona, tools, workflow, examples) is identical across
  steps. LiteRT-LM conversations are created fresh per call to avoid KV leakage between screens;
  a cached prefix + per-step suffix would cut step time roughly in half. Highest-value latency work.
- **NPU build.** No SM8850 `.litertlm` exists yet. An E2B NPU build for SM8750 exists; untested here.
- **STT accuracy** is now the weakest link for app names: adding installed app labels to the
  recognizer's biasing strings (done in this branch) is cheap; a Whisper/Moonshine on-device model
  would be the next step and is independent of the planner model.
