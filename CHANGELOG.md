# Changelog - Cornerstone Keyboard staged releases

Reconstructed from `releases/keyboard/` artifact names and the two `README.txt`
files present on disk. Entries without a README have no recorded notes.
Newest first. `Build-KeyboardRelease.ps1` now writes a `README.txt` into every
new staged directory.

## 2.0.3-fast-4 (2026090705) - 2026-09-07, `releases/keyboard/20260907-072915/`
- APK: `cornerstone-keyboard-2.0.3-fast-4-2026090705.apk`. No release note staged.

## 2.0.3-fast-3 (2026090704) - 2026-09-07, `releases/keyboard/20260907-071728/`
- APK: `cornerstone-keyboard-2.0.3-fast-3-2026090704.apk`. No release note staged.

## 2.0.3-fast-2 (2026090703) - 2026-09-07, `releases/keyboard/20260907-071014/`
- APK: `cornerstone-keyboard-2.0.3-fast-2-2026090703.apk`. No release note staged.

## 2.0.3-fast-1 (2026090702) - 2026-09-07, `releases/keyboard/20260907-055351/`
- APK: `cornerstone-keyboard-2.0.3-fast-1-2026090702.apk`. No release note staged.

## 2.0.2-daily-fix-3 (2026083009) - 2026-08-30, `releases/keyboard/20260830-173318/`
- APK: `cornerstone-keyboard-2.0.2-daily-fix-3-2026083009.apk`. No release note staged.

## 2.0.2-daily-fix-2 (2026083008) - 2026-08-30, `releases/keyboard/20260830-140010/`
- APK: `cornerstone-keyboard-2.0.2-daily-fix-2-2026083008.apk`. No release note staged.

## 2.0.2-daily-fix-1 (2026083007) - 2026-08-30, `releases/keyboard/20260830-125751/`
- APK: `cornerstone-keyboard-2.0.2-daily-fix-1-2026083007.apk`. No release note staged.

## 2.0.1 (2026083002) - 2026-08-30, `releases/keyboard/20260830-085229/`
- APK: `cornerstone-keyboard-2.0.1-2026083002.apk`; SHA-256 `0F4283A4…1619` per its README.txt.
- Installs over 2.0.0; per-dictation notices report whether the on-device polish applied.
- Documented defaults: on-device LLM correction ON, AI Fix chip ON, online dictation polish OFF.

## 2.0.0 (2026083001) - 2026-08-30, `releases/keyboard/20260830-004040/`
- APK: `cornerstone-keyboard-2.0.0-2026083001.apk`, `2.0.0-unstable`, 701,172,620 bytes.
- First self-contained build: hash-pinned `cornerstone-v6-Q4_K_M.gguf` bundled in the APK,
  intent/preservation guardrails, native llama.cpp correction runtime for arm64-v8a and
  x86_64, dictation cancellation/lifecycle fixes. On-device correction was opt-in here.
- Verification recorded in its README.txt: 253 JVM tests, instrumentation passed on
  Android 14 x86_64, APK Signature Scheme v2 verified.
