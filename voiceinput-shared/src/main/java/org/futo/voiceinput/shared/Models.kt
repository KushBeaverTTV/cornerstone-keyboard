package org.futo.voiceinput.shared

import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.ModelParakeetAsset

// Parakeet TDT 0.6B v2 (int8) via sherpa-onnx. The fork's only built-in voice
// model. Model files are NOT bundled in the phone APK — ParakeetModelDownloader
// fetches them into filesDir/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/
// (or they are bundled in assets under the same directory name for the
// emulator-only test variant).
val BUILTIN_PARAKEET_MODEL: ModelLoader = ModelParakeetAsset(
    name = R.string.parakeet_name,
    assetDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
)

/**
 * Whether final transcripts from this model should go through voice-command
 * post-processing ("new line", "delete that", "send it"). Parakeet path only —
 * whisper models keep raw transcript behavior.
 */
fun ModelLoader.supportsVoiceCommands(): Boolean =
    this === BUILTIN_PARAKEET_MODEL
