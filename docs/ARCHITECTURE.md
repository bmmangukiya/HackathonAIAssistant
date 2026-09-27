# Architecture

## Approach in one paragraph

A small on-device model is good at *choosing from a short menu* and bad at *driving a raw UI
tree*. So every request goes through two tiers:

1. **Skills first.** The model maps the request to a registered skill plus its slot values
   (`set_alarm(time=06:30)`). Skills are plain Kotlin that fire Android intents or deep links:
   fast, deterministic, no screen reading.
2. **Screen navigation as fallback.** For anything else, the model drives the UI one step at
   a time. It never sees the raw accessibility tree, only a **translated screen**: a numbered
   list of meaningful elements (`[5] button "lofi music"`). It answers with one action.

A skill can also do **both**: deep-link to the right screen, then hand a short follow-up goal
("play the first result") to navigation. The user only talks and listens. Missing details are
asked for by voice, and risky actions (call, SMS) need a spoken "yes".

## Request flow

```mermaid
sequenceDiagram
    actor User
    participant V as AndroidVoiceIO
    participant A as Assistant
    participant P as LlmPlanner
    participant L as LiteRtLlm (Gemma 4 E4B, GPU)
    participant S as Skill
    participant R as AccessibilityScreenReader
    participant C as AccessibilityUiController

    User->>V: presses a11y button, speaks
    V->>A: "play lofi on youtube"
    A->>P: route(utterance, skills)
    P->>L: routing prompt + JSON schema
    L-->>P: {"type":"skill","skill":"play_youtube","args":{...}}
    P-->>A: UseSkill(play_youtube, {query})
    A->>V: ask() for any missing slot / confirm() if risky
    A->>S: execute(ctx, args)
    S-->>A: Success(followUpGoal = "play first result", doneWhen = musicPlaying)
    loop until done / doneWhen / 15 steps
        A->>R: capture() → ScreenState, toPrompt()
        A->>P: nextStep(goal, screen text, history)
        P->>L: step prompt + JSON schema
        L-->>P: {"action":"tap","id":5}
        A->>C: perform(Tap(5))
        C-->>A: ok / failed / no effect → history
    end
    A->>V: speak("Done.")
    V->>User: 🔊
```

## Class diagram

```mermaid
classDiagram
    direction LR

    %% ---------- :core (shared contracts, no Android UI) ----------
    namespace core {
        class VoiceIO {
            <<interface>>
            +state: StateFlow~VoiceState~
            +listen(timeoutMs) String?
            +speak(text)
            +ask(question) String?
            +confirm(question) Boolean
            +stop()
        }
        class Planner {
            <<interface>>
            +route(utterance, skills) RouteDecision
            +nextStep(goal, screen, history) StepDecision
        }
        class LocalLlm {
            <<interface>>
            +load()
            +generate(prompt, maxTokens, jsonSchema) String
        }
        class ScreenReader {
            <<interface>>
            +capture() ScreenState?
            +awaitIdle(timeoutMs)
            +toPrompt(state) String
        }
        class UiController {
            <<interface>>
            +perform(action, on: ScreenState) ActionResult
        }
        class Skill {
            <<interface>>
            +id / description / slots / examples
            +risk: Risk
            +execute(ctx, args) ActionResult
        }
        class SkillRegistry {
            <<interface>>
            +all() List~Skill~
            +get(id) Skill?
        }
        class SkillContext {
            +android: Context
            +screen: ScreenReader
            +ui: UiController
            +voice: VoiceIO
        }
        class ScreenState {
            +packageName, appLabel
            +elements: List~UiElement~
        }
        class UiElement {
            +id, role, label, value
            +clickable, editable, scrollable, checked
            +bounds
        }
        class UiAction {
            <<sealed>>
            Tap / LongPress / TypeText / PressEnter
            Scroll / Back / Home / OpenNotifications
        }
        class RouteDecision {
            <<sealed>>
            UseSkill / Navigate / Clarify / Answer
        }
        class StepDecision {
            <<sealed>>
            Act / Ask / Done / Fail
        }
        class ActionResult {
            <<sealed>>
            Success(message, followUpGoal, doneWhen)
            Failure(reason)
        }
    }

    %% ---------- :app ----------
    namespace app {
        class AssistantApp {
            manual DI, warm-up, onTrigger()
            +converse(heard)
        }
        class Assistant {
            +handle(utterance)
            -runSkill()
            -navigate(goal, doneWhen)
        }
        class MainActivity
        class DebugCommandReceiver {
            adb: text / talk / bench / dump
        }
    }

    %% ---------- :llm ----------
    namespace llm {
        class LiteRtLlm {
            Engine(GPU), greedy, thinking off
            constrained JSON decoding
        }
        class LlmPlanner {
            extractJson(), retry once
        }
        class Prompts {
            route() / step()
            ROUTE_SCHEMA / STEP_SCHEMA
        }
        class LlmBenchmark
    }

    %% ---------- :perception ----------
    namespace perception {
        class AssistantAccessibilityService {
            instance, lastChangeAt
            a11y button → onTrigger
        }
        class AccessibilityScreenReader
        class ScreenTranslator {
            tree → UiElements
        }
        class AccessibilityUiController {
            node action → gesture fallback
        }
        class Snapshots {
            id → AccessibilityNodeInfo
        }
    }

    %% ---------- :voice ----------
    namespace voice {
        class AndroidVoiceIO {
            on-device SpeechRecognizer en-IN→en-US
            offline TextToSpeech
        }
    }

    %% ---------- :actions ----------
    namespace actions {
        class DefaultSkillRegistry
        class OpenAppSkill
        class SimpleSkill {
            id, slots, run lambda
        }
        class Skills {
            15 SimpleSkills
        }
        class Contacts {
            fuzzy name → number
        }
    }

    AssistantApp --> Assistant
    AssistantApp --> AndroidVoiceIO
    AssistantApp --> LiteRtLlm
    MainActivity --> AssistantApp
    DebugCommandReceiver --> AssistantApp
    AssistantAccessibilityService ..> AssistantApp : onTrigger

    Assistant --> Planner
    Assistant --> SkillRegistry
    Assistant --> SkillContext
    Assistant --> VoiceIO

    LiteRtLlm ..|> LocalLlm
    LlmPlanner ..|> Planner
    LlmPlanner --> LocalLlm
    LlmPlanner --> Prompts

    AccessibilityScreenReader ..|> ScreenReader
    AccessibilityUiController ..|> UiController
    AccessibilityScreenReader --> ScreenTranslator
    AccessibilityScreenReader --> Snapshots
    AccessibilityUiController --> Snapshots
    AccessibilityScreenReader --> AssistantAccessibilityService
    AccessibilityUiController --> AssistantAccessibilityService

    AndroidVoiceIO ..|> VoiceIO

    DefaultSkillRegistry ..|> SkillRegistry
    OpenAppSkill ..|> Skill
    SimpleSkill ..|> Skill
    Skills --> SimpleSkill
    Skills --> Contacts
    DefaultSkillRegistry --> OpenAppSkill
    DefaultSkillRegistry --> Skills
```

## Module rules

- `:core` holds only interfaces and data classes. Every other module depends on `:core`, never on
  each other. `:app` is the only module that knows all of them (manual DI in `AssistantApp`).
- Swapping a part means one new class. For example, a Qualcomm NPU runtime is another
  `LocalLlm`, and Whisper speech input is another `VoiceIO`. Nothing else changes.

## The translation layer (`ScreenTranslator`)

Raw accessibility trees are hundreds of nested nodes, mostly layout wrappers. The translator:

| Step | Why |
|---|---|
| Read the **active window** plus dialogs above it; skip the status/nav bar, OEM bubbles and the keyboard | The model should only see the app the user is in |
| Drop invisible, zero-size and off-screen nodes | Noise |
| **One element per actionable node** (click/edit/check/scroll) | These are the things you can *do* |
| Label = own text → content description → hint → **children's text joined** → readable resource id | A list row becomes `"Mom, Call me when free, 10:42"` |
| Drop layout-ish resource ids (`vcommonlayout_toptargetview`) | They tell the model nothing |
| Clean labels: strip stray commas, de-duplicate repeated segments, cap at 60 chars | Shorter prompt, faster model |
| Sort in reading order, number 1..N, cap at 80 | Stable ids for the model to answer with |
| Keep id → node map (`Snapshots`) | The controller acts on the node the model picked |

A typical YouTube screen comes out as ~13 lines and ~500 characters.

## Making the small model reliable

The full contract is in [`docs/WORKFLOW.md`](WORKFLOW.md); the failure catalogue it was built
from is in [`docs/RELIABILITY.md`](RELIABILITY.md). In short:

- **Master prompt as a checklist, not prose** (`llm/Prompts.kt`): a one-line JARVIS persona, a
  5-phase WORKFLOW (question → skill → app → screen → check), explicit STOP conditions, and 8
  one-line `goal → JSON` examples. On Qwen3-1.7B this moved the eval from 2/9 to 8/9 with no code
  change; examples mattered more than rules. Every line of context is read as an instruction by a
  small model, so descriptive text is written as imperative.
- **Constrained decoding:** LiteRT-LM's `ResponseFormat.json(schema)`. `tool` is an enum, `id` is
  an integer, free-text fields are length-capped, and key order is `thought, tool, args, final,
  screen` so the model commits to a decision before it can start copying the screen.
- **Host-side validation and repair** (`llm/LlmPlanner.kt`): unknown args are dropped, a missing
  numeric `id` is rejected, and the retry carries the exact rejection reason instead of re-sending
  the same prompt. Each call has a 30 s timeout.
- **Host-side loop control** (`app/Assistant.kt`): the same `tool+args` three times stops the
  request; three consecutive failed observations inject a "you are stuck" hint and five stop it;
  a 90 s wall-clock deadline caps the whole request. Permanent skill failures (app not installed,
  no such contact) are marked `TERMINAL` and the prompt has a rule for them.
- **Observations in a fixed vocabulary:** `ok.`, `failed:`, `NO EFFECT`, `TERMINAL`, `not run:`.
  History shows *what* was acted on, never a numeric id, since ids are only valid for one screen.
- **Slot descriptions do the normalising:** `time: 24-hour HH:MM` makes the model turn
  "6 30 in the morning" into `06:30`, so Kotlin never parses free text.
- **Tap fallback:** if an accessibility click doesn't change the UI within 600 ms, send a real
  touch gesture. YouTube's suggestions need this.
- **`doneWhen`:** skills can end navigation from device state (music started playing) that the
  model can't see on screen.
- **Fast path:** "open <installed app>" runs `open_app` directly, no model call.
- **Every stop speaks a reason** ("Zepto isn't installed…", "I kept trying the same thing…"), never
  a generic apology.
