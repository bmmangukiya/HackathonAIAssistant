# JARVIS workflow: the flow every request follows

This is the contract between the **model** (which picks one action per step) and the **host**
(`Assistant.kt`, which runs it and enforces the rules). The host guarantees are what make the
system safe with a small on-device model: the model can be wrong, but it cannot loop forever,
hang, burn minutes, or fail silently.

## The two halves

```
                     MODEL (Qwen3-1.7B, on-device)          HOST (Assistant.kt, deterministic)
                     ───────────────────────────────         ──────────────────────────────────
  Persona            "You are JARVIS…"                        —
  Workflow           1 QUESTION? 2 SKILL? 3 APP? 4 SCREEN 5 CHECK
  Stop conditions    finish on done / TERMINAL                deadline · cycle · stall · step cap
  Rules              never repeat, ask intent only, confirm   validate tool+args · drop unknown args
  Examples           8 goal → JSON one-liners                 —
  Recovery           reads observation, changes approach      exception → observation · repair list_apps
  Voice              finish(answer) is spoken                 every stop path speaks the reason
```

## One request, end to end

```
 1  hotword / mic     "Jarvis, order the grocery list on Zepto please"
 2  STT               en-IN with app + contact names as biasing hints
 3  INTAKE            normalize(): drop "jarvis", "please", "can you"; → goal = "order the grocery list on Zepto"
 4  FAST PATH         "open <installed app>" → open_app directly, no model (saves ~6 s)
 5  PLAN              prompt = persona + tools + WORKFLOW + examples + goal + history + screen
                      model → {thought, tool, args, final, screen}   (schema-constrained, ≤30 s)
 6  VALIDATE          tool ∈ registry · unknown args dropped · id is an int · else REPAIR retry
 7  GUARDS            same tool+args 3× → stop   ·   list_apps again + app in goal → open_app
 8  ACT               skill / screen action / talk tool; exceptions → "failed: …" observation
 9  OBSERVE           ok / failed / NO EFFECT / TERMINAL; permanent failures marked TERMINAL
10  SETTLE            wait for UI to react and go quiet (awaitSettled), re-capture screen
11  LOOP              stall 3× → hint; 5× → stop   ·   deadline 90 s   ·   15 steps max
12  SPEAK             finish(answer), or a one-sentence reason for stopping
```

## The model's workflow (verbatim from `Prompts.kt`)

Small models follow numbered phases; they do not infer from prose. Every step the model reads:

```
WORKFLOW (every step, in order):
1. QUESTION? If the goal is a general question, answer it now with finish.
2. SKILL? If one skill does the goal directly, call it. Skills work from ANY screen, including the home screen.
3. APP? Otherwise open the right app: open_app(name). Unsure which app: list_apps once, then open_app with a name from the list.
4. SCREEN. Inside the app, use screen actions on the CURRENT screen. "id" is a NUMBER from the screen list.
5. CHECK. If the observation says failed or NO EFFECT, do something DIFFERENT next.

STOP with finish(answer) when: the goal is done; or an observation says TERMINAL: tell the user what is needed, do not retry.
```

Followed by RULES (confirm before send/pay, fill_field for empty inputs, ask_choice for preferences,
close overlays, `final` only when the step completes the goal) and 8 EXAMPLES.

## Host guarantees (what "never fails" actually means)

"Never fails" is not achievable with a 1.7B model. What *is* achievable, and what the host enforces:

| Guarantee | Mechanism | Where |
|---|---|---|
| **Terminates** in ≤ 90 s or ≤ 15 steps | `REQUEST_DEADLINE_MS`, `MAX_STEPS` | `Assistant.runSteps` |
| **Never loops on one action** | `seen[tool+args]` ≥ 3 → stop | `Assistant.runSteps` |
| **Never ping-pongs a skill** | same-skill-twice guard; blocked ≥ 2 → stop | `Assistant.run` / `repeatBlocks` |
| **Notices when stuck** | 3 consecutive failed observations → hint injected; 5 → stop | `stalled` |
| **A single model call can't hang the loop** | 30 s `withTimeoutOrNull` per call | `LlmPlanner.next` |
| **Bad model output gets a corrected retry**, not the same prompt | `Prompts.repairNote(problem)` | `LlmPlanner` |
| **Model cannot invent tools or ids** | JSON schema: `tool` enum, `id` integer, string `maxLength` | `Prompts.reactSchema` |
| **Hallucinated args are dropped**, not executed | `validate()` filters to the tool's declared params | `LlmPlanner.validate` |
| **A skill exception is an observation**, not a crash | `runGuarded` catch-all → `failed: …` | `Assistant.runGuarded` |
| **Permanent failures are terminal** | `terminalize()` appends `TERMINAL: do not retry` | `Assistant.runSkill` |
| **Every stop speaks the reason** | `lastBlocker(scratchpad)` appended to the spoken message | all stop paths |
| **Opening an app is never "final"** | `openedApp` guard forces one more look at the screen | `Assistant.runSteps` |
| **Model missing/broken → graceful fallback** | `llmReady` + `tryLoadLlm()` else `JarvisPipeline` | `AssistantApp.converse` |

## When a step fails: RESET → RETHINK → ASK

1. **RESET** what the failure left behind before retrying. `fill_field` clears an input whose value is
   invalid for it, or that the app rejected ("Incorrect OTP. Please try again"), and asks again. An
   expired/rejected OTP makes it tap Resend / "Send SMS" first, then read the new code from SMS or ask.
   Saying "it expired" / "I didn't get it" also triggers Resend. After filling, it waits and checks
   whether the app complained; if so the step is a failure, not a success.
2. **RETHINK** (up to 3 times) instead of stopping when stuck: the same action 3×, the same skill
   again, 5 steps with no effect, or no usable model output. The failed action is **banned** (never
   executed again) and the model gets a `RETHINK n of 3` note listing everything already tried.
3. **ASK THE USER** after 3 rethinks: it says what it has done so far and what blocked it, and asks
   what to do. The answer is added to the goal as `USER GUIDANCE` and the task continues. No answer or
   "stop" ends the request.

## Observation vocabulary

The host writes observations in a fixed vocabulary so the prompt can name them:

| Observation starts with / contains | Meaning for the model |
|---|---|
| `ok.` | Worked. Continue. |
| `… is open.` / `Switched to X` | You are now in X. Look at the screen. |
| `failed: …` | This action did not work. Do something different. |
| `… NO EFFECT, screen unchanged` | The tap/type did nothing. Try another element or a real touch happened already. |
| `… TERMINAL: …` | Nothing to retry. `finish` and tell the user. |
| `not run: X was ALREADY done …` | You repeated a skill. Use its earlier result. |
| `You are stuck: …` | Host-injected after 3 useless steps. Change approach or finish. |
| `User said: "…"` | Answer to your `ask_user`. |

## Adding a skill without breaking the flow

1. Return `ActionResult.Failure(reason)` for anything that can't work; if the reason is permanent on
   this device, include the word `TERMINAL` or one of the phrases in `Assistant.PERMANENT_FAILURES`.
2. Set `openedPackage` when you launch an app so the host waits for it to be in front.
3. Slots with a `question` are asked by voice when missing; don't fail on a missing optional slot.
4. Add an eval case to `LlmBenchmark.cases` if the skill has a routing shape the model hasn't seen.

## Tuning knobs

All in `Assistant.companion`:

| Constant | Default | Raise when… | Lower when… |
|---|---|---|---|
| `REQUEST_DEADLINE_MS` | 90 000 | using a slower model (Gemma E4B ≈ 20 s/step) | demoing; users won't wait |
| `MAX_STEPS` | 15 | long multi-screen flows | — |
| `MAX_SAME_ACTION` | 3 | — | model is very loop-prone |
| `STALL_HINT_AT` / `STALL_STOP_AT` | 3 / 5 | flaky app UIs (many NO EFFECT) | — |
| `LlmPlanner.callTimeoutMs` | 30 000 | slower model | — |

