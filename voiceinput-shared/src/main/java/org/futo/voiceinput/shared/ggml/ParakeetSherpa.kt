package org.futo.voiceinput.shared.ggml

import org.futo.voiceinput.shared.FinishPolicy
import android.os.SystemClock
import android.util.Log
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
 * Engine-level configuration for a Parakeet model, supplied by the loader
 * (ModelParakeetAsset) rather than hardcoded here — v2 and v3 differ in token
 * sets and may differ in these parameters, and this class must not guess.
 *
 * The defaults are the Parakeet TDT 0.6B v2/v3 (sherpa-onnx, int8) values.
 */
data class ParakeetEngineConfig(
    val sampleRate: Int = 16000,
    val featureDim: Int = 80,
    val modelType: String = "nemo_transducer",
    val numThreads: Int = 4
)

/**
 * Parakeet TDT (NeMo transducer) inference via sherpa-onnx OfflineRecognizer.
 *
 * @param modelDir directory on the real filesystem containing the model files
 *                 (encoder*.onnx, decoder*.onnx, joiner*.onnx, tokens.txt).
 *                 CRITICAL: must be a real file path (filesDir), NOT an asset path -
 *                 OfflineRecognizer is constructed with assetManager = null because
 *                 passing a non-null AssetManager with absolute paths fails.
 * @param engineConfig sample rate / feature dim / model type / thread count,
 *                     provided by the loader that owns the model directory.
 *
 * Notes:
 *  - Offline engine: partial results are produced by re-decoding the accumulated
 *    audio buffer from scratch (inferPartial) — sherpa's OfflineRecognizer has no
 *    incremental decode. A single partialResult is also emitted at the end of a
 *    successful final decode.
 *  - No native mid-decode cancel: cancel() sets a flag and the result is discarded
 *    after decode returns (InferenceCancelledException). Lag is bounded by decode
 *    time of the buffered audio.
 *  - Native failures (bad config, OOM) are SIGABRTs from onnxruntime, not catchable
 *    Kotlin exceptions - file-level validation happens before construction.
 */
class ParakeetSherpa(
    modelDir: String,
    private val engineConfig: ParakeetEngineConfig = ParakeetEngineConfig()
) : SpeechEngine {

    companion object {
        private const val TAG = "ParakeetSherpa"
    }

    /**
     * Single OfflineRecognizer. Final and partial both use greedy_search
     * (FinishPolicy.FINAL_DECODING_METHOD). The second ~600MB beam instance was
     * the stop-button pinwheel; the bubble never paid that cost.
     */
    private val greedyRecognizer: OfflineRecognizer

    @Volatile
    private var cancelled: Boolean = false

    // Invalidated on every cancel(); a partial decode captures it at entry and
    // discards its result if it changed mid-flight. Unlike [cancelled] (which
    // infer() resets per final decode), this survives session boundaries, so a
    // stale partial from a cancelled recording can never deliver text into the
    // next session. Atomic increments avoid losing concurrent invalidations.
    private val cancellationEpoch = CancellationEpoch()

    @Volatile
    private var closed: Boolean = false

    // Try-gate for partial decodes. All decode work serializes on inferenceContext
    // (single thread), so this gate's job is NOT mutual exclusion — it is to skip
    // a scheduled partial when one is already running/queued, so a slow decode
    // never lets partial requests pile up behind it.
    private val partialDecodeBusy = java.util.concurrent.atomic.AtomicBoolean(false)

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

        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokens.absolutePath,
            numThreads = engineConfig.numThreads,
            debug = false,
            provider = "cpu",
            modelType = engineConfig.modelType
        )

        val featConfig = FeatureConfig(
            sampleRate = engineConfig.sampleRate,
            featureDim = engineConfig.featureDim
        )

        val greedyConfig = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decodingMethod = FinishPolicy.FINAL_DECODING_METHOD
        )

        try {
            greedyRecognizer = OfflineRecognizer(assetManager = null, config = greedyConfig)
        } catch (e: Throwable) {
            throw InvalidParakeetModelException("greedy OfflineRecognizer construction failed: ${e.message}")
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
    // whisper-specific and intentionally ignored. Parakeet TDT 0.6B v2 is English-only
    // (v3 multilingual is routed by the loader, not by these parameters).
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

        val stream = greedyRecognizer.createStream()
        try {
            stream.acceptWaveform(samples, engineConfig.sampleRate)

            val decodeStartMs = SystemClock.elapsedRealtime()
            greedyRecognizer.decode(stream)
            val decodeElapsedMs = SystemClock.elapsedRealtime() - decodeStartMs
            Log.i(TAG, "final decode ${FinishPolicy.FINAL_DECODING_METHOD} took ${decodeElapsedMs}ms")

            if (cancelled) throw InferenceCancelledException()

            val result = greedyRecognizer.getResult(stream).text.trim()

            if (cancelled) throw InferenceCancelledException()

            if (result.isNotEmpty()) {
                partialResultCallback(result)
            }

            return@withContext result
        } finally {
            try { stream.release() } catch (e: Throwable) { Log.e(TAG, "greedy final stream release failed", e) }
        }
    }

    /**
     * Streaming-partial decode over one bounded audio snapshot.
     *
     * @return null when skipped/cancelled, an empty string when a decode completed
     * to silence, or nonblank transcript text when speech was recognized. Keeping
     * completed silence distinct from skipped work lets the scheduler advance
     * audio coverage without inventing text.
     */
    suspend fun inferPartial(samples: FloatArray): String? = withContext(inferenceContext) {
        // All checks run ON the inference thread: close() also runs there, so a
        // closed recognizer can never be touched by a queued partial.
        if (closed || samples.isEmpty()) return@withContext null
        if (!partialDecodeBusy.compareAndSet(false, true)) return@withContext null

        val generationAtEntry = cancellationEpoch.current()
        try {
            val stream = greedyRecognizer.createStream()
            try {
                stream.acceptWaveform(samples, engineConfig.sampleRate)
                greedyRecognizer.decode(stream)

                if (!cancellationEpoch.isCurrent(generationAtEntry)) return@withContext null

                val result = greedyRecognizer.getResult(stream).text.trim()

                if (!cancellationEpoch.isCurrent(generationAtEntry)) return@withContext null
                return@withContext result
            } finally {
                try { stream.release() } catch (e: Throwable) { Log.e(TAG, "greedy stream release failed", e) }
            }
        } finally {
            // The gate is released even if createStream() throws — a stuck gate
            // would disable partials for the lifetime of this cached engine.
            partialDecodeBusy.set(false)
        }
    }

    override fun cancel() {
        // No native cancel for offline decode; results are discarded on return.
        // Bumping the generation also invalidates any in-flight partial decode.
        cancelled = true
        cancellationEpoch.invalidate()
    }

    override suspend fun close() = withContext(inferenceContext) {
        if (!closed) {
            try {
                greedyRecognizer.release()
            } catch (e: Throwable) { Log.e(TAG, "greedy release failed", e) }
        }
        closed = true
    }
}
