package org.futo.voiceinput.shared.ggml

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Engine-agnostic speech inference interface. Both WhisperGGML (whisper.cpp via JNI)
 * and ParakeetSherpa (sherpa-onnx OfflineRecognizer) implement this so that
 * ModelManager / MultiModelRunner can hold and run either engine.
 *
 * The parameter set is whisper-shaped (prompt/languages/bailLanguages/decodingMode);
 * engines that do not support a parameter simply ignore it.
 */
interface SpeechEngine {
    @Throws(BailLanguageException::class, InferenceCancelledException::class)
    suspend fun infer(
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String

    fun cancel()

    suspend fun close()
}

class InvalidParakeetModelException(message: String) :
    Exception("The Parakeet model could not be loaded: $message")

/**
 * Parakeet TDT (NeMo transducer) inference via sherpa-onnx OfflineRecognizer.
 *
 * @param modelDir directory on the real filesystem containing the model files
 *                 (encoder*.onnx, decoder*.onnx, joiner*.onnx, tokens.txt).
 *                 CRITICAL: must be a real file path (filesDir), NOT an asset path -
 *                 OfflineRecognizer is constructed with assetManager = null because
 *                 passing a non-null AssetManager with absolute paths fails.
 *
 * Notes:
 *  - Offline engine: no true partial results. A single partialResult is emitted at
 *    the end of a successful decode; the UI tolerates this.
 *  - No native mid-decode cancel: cancel() sets a flag and the result is discarded
 *    after decode returns (InferenceCancelledException). Lag is bounded by decode
 *    time of the buffered audio.
 *  - Native failures (bad config, OOM) are SIGABRTs from onnxruntime, not catchable
 *    Kotlin exceptions - file-level validation happens before construction.
 */
class ParakeetSherpa(
    modelDir: String,
    private val numThreads: Int = 4
) : SpeechEngine {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private val recognizer: OfflineRecognizer

    @Volatile
    private var cancelled: Boolean = false

    @Volatile
    private var closed: Boolean = false

    init {
        val dir = File(modelDir)
        if (!dir.isDirectory) {
            throw InvalidParakeetModelException("model directory does not exist: ${dir.absolutePath}")
        }

        val encoder = dir.requireModelFile("encoder")
        val decoder = dir.requireModelFile("decoder")
        val joiner = dir.requireModelFile("joiner")
        val tokens = dir.listFiles()?.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: throw InvalidParakeetModelException("missing tokens.txt in ${dir.absolutePath}")

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "nemo_transducer"
            ),
            decodingMethod = "greedy_search"
        )

        try {
            recognizer = OfflineRecognizer(assetManager = null, config = config)
        } catch (e: Throwable) {
            throw InvalidParakeetModelException("OfflineRecognizer construction failed: ${e.message}")
        }
    }

    private fun File.requireModelFile(kind: String): File {
        // Exact canonical name first; fall back to substring match but NEVER bind a
        // partial seed (.tmp-*) or an in-flight download (*.download) — a truncated
        // onnx means an uncatchable native SIGABRT at load.
        val files = listFiles()?.filter {
            it.isFile && !it.name.startsWith(".tmp-") && !it.name.endsWith(".download")
        } ?: emptyList()
        val match = files.firstOrNull { it.name == "$kind.int8.onnx" }
            ?: files.firstOrNull { it.name.contains(kind, ignoreCase = true) && it.name.endsWith(".onnx") }
            ?: throw InvalidParakeetModelException("missing $kind onnx file in $absolutePath")

        if (match.length() <= 0L) {
            throw InvalidParakeetModelException("$kind onnx file is empty: ${match.absolutePath}")
        }

        return match
    }

    // prompt, languages, bailLanguages, decodingMode and suppressNonSpeechTokens are
    // whisper-specific and intentionally ignored. Parakeet TDT 0.6B v2 is English-only.
    @Throws(InferenceCancelledException::class)
    override suspend fun infer(
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String = withContext(inferenceContext) {
        if (closed) {
            throw IllegalStateException("ParakeetSherpa has already been closed, cannot infer")
        }

        cancelled = false

        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)

            if (cancelled) throw InferenceCancelledException()

            val result = recognizer.getResult(stream).text.trim()

            if (cancelled) throw InferenceCancelledException()

            if (result.isNotEmpty()) {
                partialResultCallback(result)
            }

            return@withContext result
        } finally {
            stream.release()
        }
    }

    override fun cancel() {
        // No native cancel for offline decode; the result is discarded on return.
        cancelled = true
    }

    override suspend fun close() = withContext(inferenceContext) {
        if (!closed) {
            try {
                recognizer.release()
            } catch (_: Throwable) { }
        }
        closed = true
    }
}
