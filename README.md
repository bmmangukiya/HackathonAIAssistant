# HackathonAIAssistant

On-device, voice-first AI assistant for Android (iQOO Hackathon 2026).

- Voice in, voice out. Clarifying questions and confirmations are spoken.
- Local LLM (LiteRT-LM) on the Snapdragon NPU/GPU. No cloud.
- Drives any app through the accessibility tree, via a translation layer and a
  registry of deterministic skills (deep links and intents).

See [docs/PLAN.md](docs/PLAN.md) for architecture and team ownership.

## Attribution
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Apache 2.0)
- AndroidX / Jetpack Compose (Apache 2.0)
