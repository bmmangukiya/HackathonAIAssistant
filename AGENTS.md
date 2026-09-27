# Notes for AI coding agents

- Read `docs/PLAN.md` and `docs/STATUS.md` first. Find your owner's section and stay inside that area.
- Contracts in `core/` are shared and frozen. Don't edit them unless your human says so.
- Build: `./gradlew :app:assembleDebug`. It must pass before every commit.
- Kotlin 2.4, AGP 8.13, compileSdk 36, minSdk 29, JDK 17. Manual DI lives in `app/.../AssistantApp.kt`.
- No cloud LLM or cloud speech APIs. Everything runs on-device.
- Hackathon rule: all code must be written during the event. Don't paste in pre-built projects.
  Open-source libraries are fine; add them to the Attribution section of `README.md`.
- Small commits: `<module>: <what>`. Run `git pull --rebase` before pushing.
- Device testing: see "Testing on device" in `docs/PLAN.md`.
- Repos: `mainline` on https://code.amazon.com/packages/HackathonPayUIAIAssistant (internal, primary) and
  `main` on GitHub `vishnu0179/HackathonAIAssistant` (mirror). Clone the internal one:
  `git clone ssh://git.amazon.com/pkg/HackathonPayUIAIAssistant` (needs `mwinit -o`).
