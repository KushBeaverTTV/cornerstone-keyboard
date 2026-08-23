# Cornerstone Keyboard

AI-agent keyboard forked from [FUTO Keyboard](https://keyboard.futo.tech/). In-keyboard Agent Hub (Kimi / Aurora / Prima), Cornerstone Voice dictation, and device actions.

This is **not** upstream FUTO. Issues and product work belong in [KushBeaverTTV/cornerstone-keyboard](https://github.com/KushBeaverTTV/cornerstone-keyboard).

## Product surface

- **Agent Hub** — tap the Agent key. Conversation threads persist across IME restarts. Custom agent URLs never receive the hub Bearer token.
- **Cornerstone Voice** — live partials, greedy decode, keep-warm Parakeet. Say `hey kimi …` / `hey aurora …` / `hey prima …` to route a spoken request.
- **Device** — volume / flashlight live on their own action, not inside the hub.

Current line: `cornerstone/prototype` (v1.4.0+).

## Building

Recursive clone, then:

```
./gradlew assembleUnstableDebug
```

Agent endpoint and debug token are injected from `local.properties` (`AGENT_KEY_ENDPOINT`, `AGENT_KEY_TOKEN`). Never commit those values.

## Upstream

FUTO Keyboard source and CLA still apply to inherited LatinIME/FUTO files. See `LICENSE.md`.
