package org.futo.voiceinput.shared

import org.futo.voiceinput.shared.types.ModelBuiltInAsset
import org.futo.voiceinput.shared.types.ModelDownloadable
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.ModelParakeetAsset

val BUILTIN_ENGLISH_MODEL: ModelLoader = ModelBuiltInAsset(
    name = R.string.tiny_en_name,
    ggmlFile = "tiny_en_acft_q8_0.bin.not.tflite"
)

// Parakeet TDT 0.6B v2 (int8) via sherpa-onnx. English built-in voice model.
// Model files are NOT bundled in the phone APK - they are sideloaded/adb-pushed into
// filesDir/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/ (or bundled in assets
// under the same directory name for the emulator-only test variant).
val BUILTIN_PARAKEET_MODEL: ModelLoader = ModelParakeetAsset(
    name = R.string.parakeet_name,
    assetDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
)

// Parakeet TDT 0.6B v3 (int8, multilingual) via sherpa-onnx. Same tmp->verify->
// rename->.complete seed discipline as v2 (ModelParakeetAsset is shared); only the
// model directory — and with it the ModelManager cache key ("Parakeet$assetDir") —
// differs, so v2 and v3 coexist on disk and in memory.
// NOTE: v3 keeps the v2 engine parameters (16kHz, 80-dim feats, nemo_transducer);
// if a future model changes them, pass a ParakeetEngineConfig here and the engine
// follows the loader. Delivery UI (downloader/fallback routing) is NOT wired to v3
// yet — selection happens wherever this loader is explicitly chosen.
val BUILTIN_PARAKEET_V3_MODEL: ModelLoader = ModelParakeetAsset(
    name = R.string.parakeet_v3_name,
    assetDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
)

/**
 * Whether final transcripts from this model should go through voice-command
 * post-processing ("new line", "delete that", "send it"). Parakeet path only —
 * whisper models keep raw transcript behavior.
 */
fun ModelLoader.supportsVoiceCommands(): Boolean =
    this === BUILTIN_PARAKEET_MODEL || this === BUILTIN_PARAKEET_V3_MODEL

val ENGLISH_MODELS: List<ModelLoader> = listOf(
    ModelBuiltInAsset(
        name = R.string.tiny_en_name,
        ggmlFile = "tiny_en_acft_q8_0.bin.not.tflite"
    ),

    ModelDownloadable(
        name = R.string.base_en_name,
        ggmlFile = "base_en_acft_q8_0.bin",
        checksum = "e9b4b7b81b8a28769e8aa9962aa39bb9f21b622cf6a63982e93f065ed5caf1c8"
    ),
    ModelDownloadable(
        name = R.string.small_en_name,
        ggmlFile = "small_en_acft_q8_0.bin",
        checksum = "58fbe949992dafed917590d58bc12ca577b08b9957f0b3e0d7ee71b64bed3aa8"
    ),
)

val MULTILINGUAL_MODELS: List<ModelLoader> = listOf(
    ModelDownloadable(
        name = R.string.tiny_name,
        ggmlFile = "tiny_acft_q8_0.bin",
        checksum = "07aa4d514144deacf5ffec5cacb36c93dee272fda9e64ac33a801f8cd5cbd953"
    ),
    ModelDownloadable(
        name = R.string.base_name,
        ggmlFile = "base_acft_q8_0.bin",
        checksum = "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4"
    ),
    ModelDownloadable(
        name = R.string.small_name,
        ggmlFile = "small_acft_q8_0.bin",
        checksum = "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4"
    ),
)