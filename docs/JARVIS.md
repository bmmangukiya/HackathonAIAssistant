# Jarvis voice front end

Say "Jarvis" from any screen: a card slides up, listens until you finish your sentence, the
request goes to the answer step, and the reply is spoken and shown word by word. All speech uses
Google's Android APIs (speech-to-text and TTS); no extra models.

## Flow

```
JarvisService (mic foreground service, started by the app)
 └─ AssistantApp.startJarvis() loop
     1. GoogleHotword.awaitWake()   our mic → Google on-device recognizer (fed audio, no restarts,
                                    no start chime); card opens on the first "Jarvis"/"jarv…"
     2. converse(heard)             words said in the same breath, or voice.listen() — the same
                                    path as tapping the mic (Google cloud or on-device)
     3. Assistant (skills/screen agent, when the Gemma model is on the phone)
        or JarvisPipeline → Responder.respond()  ← plug-in point, EchoResponder for now
     4. voice.speakStream()         Google TTS, sentence by sentence as the reply streams in
 UI: AssistantOverlay → AssistantCard (bottom sheet over any app), reads JarvisVoice state
```

The hotword pauses (and releases the mic) while a keyboard is open, during a call, or when
another app records, so Gboard voice typing is unaffected (`HotwordGate`).

## Code

| Module / file | What it is |
|---|---|
| `voice/GoogleSpeechRecognizer.kt` | Wrapper over `SpeechRecognizer`: `listen(ListenOptions): Flow<SpeechEvent>` (partial + tentative words, level, finals with confidence, typed errors), every intent option (language + fallback, silence timings, biasing words, formatting, segmented sessions, language detection, fed audio), offline language packs. One reused recognizer per mode. |
| `voice/GoogleTextToSpeech.kt` | Wrapper over `TextToSpeech`: `speak(): Flow<TtsEvent>` with word ranges, voice selection, rate/pitch, ducking, save to file. Media volume. |
| `voice/AndroidVoiceIO.kt` | `VoiceIO` on the two wrappers: `listen()` (multi-sentence, ends after 1.3 s of quiet, retries transient errors), `speak()` / `speakStream()`, `confirm()`, card state/level/captions. |
| `voice/GoogleHotword.kt` | "Jarvis" detection on our mic stream fed into Google on-device; returns the same-breath request. |
| `voice/JarvisVoice.kt`, `AssistantVoice.kt`, `Captions.kt` | What the card reads: state, mic level, live words, reply + spoken position. |
| `core/Responder.kt` | The answer-step contract: `respond(ResponderRequest): Flow<String>` (streamed text). |
| `app/pipeline/` | `JarvisPipeline` (one turn + history), `EchoResponder` (placeholder). |
| `app/ui/AssistantCard.kt`, `JarvisLogo.kt` | The card and logo (Compose). `AssistantOverlay` shows it over other apps. |
| `app/JarvisService.kt`, `HotwordGate.kt` | Always-on mic service; when the hotword may listen. |

## Plugging in the real answer step

Implement `Responder` and change one line in `AssistantApp`:

```kotlin
val pipeline by lazy { JarvisPipeline(voice, EchoResponder()) }   // ← your Responder here
```

## Debugging (vivo mutes app logcat)

```
adb shell run-as com.hackathon.assistant cat files/jarvis.log      # wakes, gate, requests
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es say "hello"
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --ez cloud_stt true
```

Known limits: the app must be opened once after a reboot (Android only lets a mic service start
from the foreground); Google's online recognizer can't take fed audio, so wake detection is on-device.
