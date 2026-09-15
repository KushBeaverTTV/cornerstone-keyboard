# NATIVE_BUILD - libcornerstone_correction.so

Recipe for the on-device correction JNI library that ships prebuilt under
`java/jniLibs/<abi>/libcornerstone_correction.so`. Gradle does **not** build it -
`android.externalNativeBuild` in `build.gradle` points at `native/jni/CMakeLists.txt`
(the LatinIME/whisper natives) only. The correction `.so` is produced by a manual
cmake run and copied into `jniLibs`, so this file is the only record of how.

## Ingredients (as built 2026-08-30)

- llama.cpp checkout: `C:/Cornerstone/tools/llama.cpp` at tag `b10687`
  (commit `c841aeeb8bb2fe417038dadfa9b007cf1a9ef950`, 2026-08-29).
- Android NDK `27.1.12297006` under `C:/Cornerstone/tools/android-sdk/ndk/`
  (note: the Gradle-driven `native/jni` build uses `ndkVersion 28.2.13676358`;
  the correction library was built with 27.1 - pin whichever was actually used).
- SDK-bundled cmake `3.22.1` + `ninja.exe`
  (`C:/Cornerstone/tools/android-sdk/cmake/3.22.1/bin/`).
- `ANDROID_PLATFORM=android-28`, `CMAKE_BUILD_TYPE=Release`, generator `Ninja`,
  default `GGML_CPU` backend (all GPU/BLAS backends OFF, `GGML_CPU_REPACK=ON`).

## Step 1 - build llama.cpp static libs per ABI

The correction `CMakeLists.txt` imports these prebuilt archives:
`src/libllama.a`, `ggml/src/libggml.a`, `libggml-cpu.a`, `libggml-base.a`.

```powershell
$NDK = 'C:\Cornerstone\tools\android-sdk\ndk\27.1.12297006'
$TC  = "$NDK\build\cmake\android.toolchain.cmake"
$LL  = 'C:\Cornerstone\tools\llama.cpp'

# arm64-v8a (phone target)
cmake -S $LL -B "$LL\build-android-arm64" -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build "$LL\build-android-arm64"

# x86_64 (emulator target)
cmake -S $LL -B "$LL\build-android-x64" -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI=x86_64 `
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build "$LL\build-android-x64"
```

## Step 2 - build the JNI shim per ABI

`native/correction/CMakeLists.txt` hard-codes `LLAMA_ROOT=C:/Cornerstone/tools/llama.cpp`
and picks `build-android-arm64` vs `build-android-x64` from `ANDROID_ABI`.

```powershell
$KB = 'D:\Cornerstone-Labs\keyboard-v2'   # real path, not the C:\Cornerstone symlink

cmake -S "$KB\native\correction" -B "$KB\native\correction\build-arm64" -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release
cmake --build "$KB\native\correction\build-arm64"

cmake -S "$KB\native\correction" -B "$KB\native\correction\build-x64" -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI=x86_64 `
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release
cmake --build "$KB\native\correction\build-x64"
```

## Step 3 - stage the .so into the app

```powershell
Copy-Item "$KB\native\correction\build-arm64\libcornerstone_correction.so" "$KB\java\jniLibs\arm64-v8a\"
Copy-Item "$KB\native\correction\build-x64\libcornerstone_correction.so"   "$KB\java\jniLibs\x86_64\"
```

`jniLibs.srcDirs = ['java/jniLibs']` in `build.gradle` packages them verbatim.

## Caveats

- `LLAMA_ROOT` and the `build-android-*` dir names are hard-coded in
  `native/correction/CMakeLists.txt`; a llama.cpp checkout elsewhere needs an edit.
- If llama.cpp is updated, rebuild **both** llama.cpp ABI trees and **both**
  correction `.so`s together - the ggml static-link ABI is not stable across bumps.
- The committed `.so`s are ~55 MB each; verify they load on-device
  (`CorrectionNative.ensureLoaded()`) after any rebuild.
