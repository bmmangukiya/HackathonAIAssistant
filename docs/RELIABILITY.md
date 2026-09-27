# Reliability: failure points in the voice → task pipeline

_Baseline captured Sat 27 Sep 2026, ~01:05 IST from the test phone (`adb logcat -s Assistant LlmPlanner LiteRtLlm Voice`), model `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`, before the changes in this branch._

This file records what actually went wrong on the device, why, and which fix addresses it. Each
row links to a code change so future regressions can be traced.

## Pipeline stages and where they break

```
 hotword → STT → intake → plan (LLM) → validate → act → observe → settle → loop → speak
```

| # | Stage | Observed failure (device evidence) | Root cause | Fix |
|---|---|---|---|---|
| F1 | plan/loop | **`list_apps` ping-pong: 12 wasted steps, 2.5 min, then "too many steps".** Request "order the grocery list on zepto" → `open_app(Zepto)` ×2 failed (not installed) → `list_apps` → `not run: just done` → `list_apps` → … until step 15. | Two bugs. (a) The loop guard compared `substringAfter("Action: ")` of the last 3 entries, which **includes the Observation text**; the observation alternated `ok` / `not run: just done`, so three entries were never "identical" even though the action was. (b) The app-list collapse rewrote whatever the *last* entry was to `Observation: (app list was shown)`, so the `not run` observation was overwritten and the model never saw that its repeat had been blocked. Net effect: no hard stop, no recovery, and the model kept re-deriving the same plan. | `Assistant.kt`: cycle detector over the whole scratchpad (same tool+args seen ≥2× *anywhere* → inject a recovery hint; ≥3× → stop with a spoken reason). Repeat-skill block escalates to a stop after 2 blocks. Missing-app is now a **terminal** observation the prompt tells the model to `finish` on. |
| F2 | plan | **Model hallucinates arg names** (`list_apps{filter=installed}`; `filter` is not a slot). Harmless here but shows the schema allows any known arg name on any tool. | `reactSchema` puts the union of all param names on every tool's `args`. | `LlmPlanner.validate()`: drop unknown args, reject/repair required-missing; prompt lists exact args per tool. |
| F3 | plan | **Model echoes screen text into `screen` field with junk prefix** (`"path:Home screen (launcher)…"`). Cosmetic, but wastes tokens and shows the 1.7B copies rather than summarises. | Prompt asks for a free-form summary; small model copies. | Prompt: "screen ≤ 6 words, your own words"; schema `maxLength` on `screen`/`thought`. |
| F4 | act | **`open_app("Remote PC (com.vivo.remotecontrol)")` fails twice.** Model passed `label (package)` (copied from `list_apps` output) as the app name; `OpenAppSkill` does substring match on label only → no match. | `list_apps` prints `Name (pkg)` and the model pastes the whole line. | `OpenAppSkill`: accept `Name (pkg)`, bare `pkg`, or name; strip the parenthetical; also match on package. |
| F5 | act | **App not installed → dead end.** Zepto isn't installed; the model has no way to know except by trying. Each try costs ~9 s. | No installed-app knowledge at plan time; failure observation doesn't say "stop trying". | Failure observation now says `NOT INSTALLED. Do not retry. Use finish to tell the user, or open Play Store`; prompt has an explicit rule for it. Installed-app *names* are added to STT hints so "Zepto" is at least heard right. |
| F6 | latency | **~9–11 s per step, prompt 7.3–9.4k chars.** 15 steps ≈ 2.5 min. Users abandon well before that. | Full tool list (≈2.5k chars) + rules + scratchpad every step; no prefix cache. | Prompt shrunk (persona+workflow ~40% shorter; tools listed compactly). `Assistant`: **request deadline** (default 90 s) so a run can't silently burn minutes; speaks "this is taking too long" and stops. |
| F7 | loop | **No progress detection.** After several no-op steps the loop keeps calling the model with the same context. | Only the identical-action-×3 guard exists. | Stall counter: N consecutive `ok=false` observations → inject `"You are stuck. Change approach or finish."`; N+2 → stop. |
| F8 | plan | **Planner returns `null` → "Sorry, I got confused."** Retry uses the identical prompt, so the same bad output comes back. | `repeat(2)` with the same prompt. | Second attempt appends a *repair* note with the exact validation error. Per-call timeout so a hung GPU call doesn't freeze the loop. |
| F9 | act | **Skill exceptions abort the whole run.** Any `Throwable` from `skill.execute` propagates to `converse()` → "Sorry, something went wrong." | No try/catch around skill/UI execution. | Exceptions become `failed: <reason>` observations; the model gets one chance to recover. |
| F10 | STT | **Wake word fragments** (`"Hey jarv"`, `"Yahi jarv"`, `"Jarv"`) accepted; request words in the same breath sometimes clipped. Request STT mis-hears app names ("zepto" vs "septo"). | Hotword uses prefix match (intended); request recognizer has contact hints but **no app-name hints**. | Add installed app labels to `hints` (biasing strings) alongside contacts. |
| F11 | intake | **Long natural requests go to the model verbatim** ("There is a not called grocery list, can you check the items and order those items on zepto app?"). STT typo "not" for "note". No normalisation. | No intake step. | Intake normaliser: trim filler ("can you", "please", "jarvis"), collapse whitespace, keep original for display. Cheap and deterministic; the goal line in the prompt gets shorter and cleaner. |
| F12 | fast path | **Trivial requests pay the full LLM cost** ("open youtube" = 8 s). | Every request goes to the planner. | Deterministic fast path for `open <app>` / `launch <app>` when the app is installed; skipped otherwise so the model can plan (e.g. suggest Play Store). |
| F13 | speak | **Failure messages are generic** ("Sorry, I got confused", "too many steps"). The user learns nothing. | Fixed strings. | Each stop path speaks *why* (what was tried, what blocked it) in one sentence, and the card shows it. |

## Why the 1.7B loops

The trace above is a textbook small-model failure: **the model has no memory that its plan
already failed**, because the scratchpad tells it `list_apps -> ok` and the collapsed observation
`(app list was shown)` hides that Zepto wasn't there. It re-derives the same "let me list apps"
plan every step. Three things fix this class of bug regardless of model:

1. **Terminal observations.** When a fact is settled ("Zepto is not installed"), the observation must
   say so in imperative form and the prompt must have a rule for it. Small models follow rules far
   better than they infer from history.
2. **Host-side cycle detection.** Never rely on the model to notice it's looping. Count repeats of
   `tool+args` over the whole run, and escalate: hint → stop.
3. **Budget.** A wall-clock deadline and a step cap that *speaks the reason*.

## Model choice (see `docs/MODELS.md`)

On this device Qwen3-1.7B int4 is ~9 s/step with a 9k-char prompt. Gemma 4 E4B was ~3.8 s/step and
8/8 on the benchmark but is non-OSI licensed. Qwen3-4B is 16–23 s/step. The prompt changes here cut
prompt size, which helps all three; the workflow/cycle changes make the 1.7B *safe* even when it
picks wrong.

## Measured result (same phone, same model, after the fixes)

| Request | Before | After |
|---|---|---|
| "order the grocery list items on zepto app" (Zepto not installed) | 15 steps, ~2.5 min, "That took too many steps, so I stopped." | **2 steps, 23 s**, "Zepto isn't installed on this phone. Install it from the Play Store and ask me again." |
| "open youtube" | 1 model call, ~8 s | **fast path, <1 s**, no model call |
| "call mom please" | — | intake → `call mom`; `call_contact(mom)` in 1 step; confirm prompt spoken |
| STT garbage ("card Nahin") | would run 15 steps | `list_apps` ×3 → cycle stop at step 3 with a spoken reason |
| Scored eval (9 cases) | not scored; 2/9 with the first workflow prompt | **8/9**, mean step 6.15 s (was 7.6 s) |

## How to reproduce a failure

```
tools/trace.sh "order the grocery list on zepto"      # full prompt/response trace to traces/
adb logcat -d -s Assistant LlmPlanner LiteRtLlm Voice | grep -E "step [0-9]+ \||observation:|⏱|said:"
```

## Checklist for future changes

- A new failure mode gets a row here **before** the fix lands.
- Any stop path must speak a reason, not a generic apology.
- Any new skill failure that is permanent (not installed, no permission, no such contact) must return
  a terminal observation (`… Do not retry.`).
