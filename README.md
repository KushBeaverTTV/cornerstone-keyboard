# Cornerstone Keyboard

AI-agent keyboard forked from [FUTO Keyboard](https://keyboard.futo.tech/). In-keyboard Agent Hub (Kimi / Aurora / Prima), Cornerstone Voice dictation, and device actions.

This is **not** upstream FUTO. Issues and product work belong in [KushBeaverTTV/cornerstone-keyboard](https://github.com/KushBeaverTTV/cornerstone-keyboard).

## Product surface

- **Agent Hub** — tap the Agent key. Conversation threads persist across IME restarts. Custom agent URLs never receive the hub Bearer token.
- **Cornerstone Voice** — live partials, incremental decode, keep-warm Parakeet. Say `hey kimi …` / `hey aurora …` / `hey prima …` to route a spoken request.
- **Intent-aware on-device correction** — finish a sentence and tap the Fix chip, or polish a dictated transcript, with the bundled Qwen v6 Q4 model. It preserves explicit negation, digits, profanity, and flags ambiguous or semantically changed wording for review. On by default (Settings → Predictive text); never runs in password/incognito fields. The original online Agent fix remains available when the on-device toggle is off.
- **GIF picker** — search + trending grid in the action palette with a provider toggle (GIPHY by default, Klipy as fallback/alternative); inserts into any field that accepts GIFs. Needs `GIPHY_API_KEY` and/or `KLIPY_API_KEY` in `local.properties` (auto-falls-back to whichever key is present).
- **Device** — volume / flashlight / media controls, from their own action or the hub's device-controls section.

Current line: `2.0.3-fast-4` (newest staged release: `releases/keyboard/20260907-072915/`; see `CHANGELOG.md`).

## Building

Recursive clone, then:

```
./gradlew assembleUnstableDebug
```

`libs/` is the upstream FUTO `android-libs` submodule (comes with the recursive clone). The one fork-added binary, `libs-fork/sherpa-onnx-1.13.6.aar`, is downloaded automatically by Gradle (`fetchSherpaOnnxAar`, SHA-256 pinned) from the official [k2-fsa sherpa-onnx release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6) when missing.

The self-contained build also stages the hash-pinned `cornerstone-v6-Q4_K_M.gguf` from `C:/Cornerstone/model-lab/models/`. Set `CORNERSTONE_MODEL_PATH` when building elsewhere. The build fails closed if the file is missing or its size/SHA-256 does not match; it never emits a deceptively model-less APK.

Agent endpoint and debug token are injected from `local.properties` (`AGENT_KEY_ENDPOINT`, `AGENT_KEY_TOKEN`, optional `AGENT_KEY_AGENT`, optional `GIPHY_API_KEY` / `KLIPY_API_KEY` for the GIF picker). Never commit those values.

Signed release build (versions, builds, verifies signature, stages APK + SHA256, `-Install` pushes over adb):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-KeyboardRelease.ps1 [-Install]
```

Signing reads the gitignored `keystore.properties` (same release identity as the other Cornerstone phone apps).

## Upstream

FUTO Keyboard source and CLA still apply to inherited LatinIME/FUTO files. See `LICENSE.md`.
