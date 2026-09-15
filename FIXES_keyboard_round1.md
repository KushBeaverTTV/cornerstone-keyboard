# FIXES - 2026-09-13 - keyboard-v2
[FIXED] 1: build.gradle:16-88 - new newestReleaseApk() scans releases/keyboard for the newest cornerstone-keyboard-*-<code>.apk, so git-less local builds get versionCode=newest+1 and versionName="<name>-local"; VERSION_CODE/VERSION_NAME env vars and git (when alive) still take precedence.
[FIXED] 2: Build-KeyboardRelease.ps1:64-75 - unstable VERSION_CODE default is now today's date plus (max same-day staged seq +1); dry-run produced 2026091301, so same-day rebuilds cannot collide.
[FIXED] 3: Build-KeyboardRelease.ps1:50-59,88-95 - VERSION_NAME now derives from the newest staged APK and bumps its trailing counter (2.0.3-fast-4 -> 2.0.3-fast-5); stable lane also falls back to the staged scan instead of hard-coded '2.0.0' when git describe fails.
[FIXED] 4: Build-KeyboardRelease.ps1:104-131 - stableRelease bootstraps keystore.properties by parsing C:\Cornerstone\Cornerstone_Secrets\signing.env (KEY=VALUE), with explicit errors for missing file/keys/keystore; secret values are written but never printed.
[FIXED] 5: README.md:11,15 - chose to fix the README (code default=true and shipped 2.0.1 notes both document ON, so the doc was wrong): "Off by default"->"On by default", and the current line now reads 2.0.3-fast-4 / releases/keyboard/20260907-072915.
[FIXED] 6: docs/VCS_REPAIR_PLAN.md (new) - snapshot-first plan covering init-in-place vs remote-overlay options, the six dangling .git pointer files plus 7 .gitmodules entries, verification steps, and a recommendation (Option A); nothing executed.
[FIXED] 7: local.properties:3-8 - AGENT_KEY_TOKEN value removed and replaced with a documented pointer to HUB_TOKEN in C:\Cornerstone\Cornerstone_Secrets\hub-bridge.env; local.properties was already covered by .gitignore:7.
[FIXED] 8: docs/NATIVE_BUILD.md (new) - exact recipe for libcornerstone_correction.so: llama.cpp tag b10687 (c841aeeb8bb2), NDK 27.1.12297006 toolchain, android-28, Ninja/Release, cmake invocations for arm64-v8a and x86_64, and the copy step into java/jniLibs/.
[FIXED] 9: Build-KeyboardRelease.ps1:173-192 + CHANGELOG.md (new) - script now writes a README.txt (APK name, package, version/code, lane, SHA-256, size) into every staged dir; CHANGELOG.md summarises all 9 existing dirs using only artifact names and the 2 README.txt files present.

## Files changed
- build.gradle
- Build-KeyboardRelease.ps1
- README.md
- local.properties
- docs/VCS_REPAIR_PLAN.md (new)
- docs/NATIVE_BUILD.md (new)
- CHANGELOG.md (new)
- FIXES_keyboard_round1.md (new)

## Notes
- No build was run per scope. Verification was source-level: every changed file was re-read after editing; Build-KeyboardRelease.ps1 passes a parse-only PSParser check; the version-scan logic was dry-run against releases/keyboard/ (newest 2.0.3-fast-4/2026090705 -> next code 2026091301, next name 2.0.3-fast-5, stable fallback 2026090706). The new Groovy closures in build.gradle still need one real gradle evaluation to be fully validated.
- ROTATION REQUIRED: the old AGENT_KEY_TOKEN leaked into an agent transcript and must be rotated by the owner; after rotation, copy the new token into local.properties (gitignored) so it matches HUB_TOKEN in Cornerstone_Secrets\hub-bridge.env.
- VCS recommendation: docs/VCS_REPAIR_PLAN.md Option A - robocopy snapshot, rename the six dangling .git pointer files, git init in place and commit the whole tree (submodules vendored), keep github.com/KushBeaverTTV/cornerstone-keyboard as a fetch-only remote.
- Pre-edit backups of every modified file are in D:\Cornerstone-Labs\keyboard-v2-round1-backups\ (outside the repo).
- keystore.properties bootstrap only runs on the stableRelease lane when the file is absent; the generated file stays gitignored.
- stableRelease still prefers git rev-list for VERSION_CODE; the staged-release scan is only the dead-git fallback.
