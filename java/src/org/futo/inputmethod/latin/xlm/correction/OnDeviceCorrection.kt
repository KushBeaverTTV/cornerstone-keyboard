package org.futo.inputmethod.latin.xlm.correction

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.xlm.ModelPaths
import java.io.File
import java.security.MessageDigest

val ON_DEVICE_LLM_CORRECTION = SettingsKey(
    key = booleanPreferencesKey("on_device_llm_correction"),
    default = true,
)

private const val TAG = "OnDeviceCorrection"
private const val TIMEOUT_MS = 12_000L
private const val N_PREDICT = 120
const val ON_DEVICE_MODEL_FILENAME = "cornerstone-v6-Q4_K_M.gguf"
private const val MODEL_ASSET_PATH = "models/$ON_DEVICE_MODEL_FILENAME"
private const val MODEL_BYTES = 529_297_184L
private const val MODEL_SHA256 = "85873ebdde3d8ef34cf75642aa7f1464104aff1bbe8c2e7bd5754ca760f8c802"
private const val TIMEOUT_SENTINEL = "__CORNERSTONE_TIMEOUT__"
private const val STATUS_PREFS = "cornerstone_correction_status"

enum class CorrectionModelReadiness {
    Bundled,
    Installed,
    Verified,
}

data class CorrectionModelStatus(
    val readiness: CorrectionModelReadiness,
    val bytes: Long,
    val displayName: String = "Cornerstone v6",
    val modelFamily: String = "Qwen3.5-0.8B · Q4_K_M",
)

data class CorrectionLastRun(
    val timestampMillis: Long,
    val result: String,
)

private const val INSTRUCTION =
    "Correct this keyboard/dictation text. Preserve names, profanity, intent, and voice. " +
        "Return only compact JSON with exactly this schema: " +
        "{\"prediction\":\"corrected text\",\"uncertain\":[\"term needing review\"]}. " +
        "Use an empty uncertain list when no review is needed."

object OnDeviceCorrection {
    private val owner = CorrectionGenerationOwner()
    private val modelInstallMutex = Mutex()
    @Volatile private var verifiedModelPath: String? = null

    fun modelFile(context: Context): File =
        File(ModelPaths.getModelDirectory(context), ON_DEVICE_MODEL_FILENAME)

    private fun verificationMarker(context: Context): File =
        File(modelFile(context).parentFile, "$ON_DEVICE_MODEL_FILENAME.sha256")

    fun modelStatus(context: Context): CorrectionModelStatus {
        val destination = modelFile(context)
        val installed = destination.isFile && destination.length() == MODEL_BYTES
        val verified = installed && verificationMarker(context).run {
            isFile && runCatching { readText().trim().equals(MODEL_SHA256, ignoreCase = true) }
                .getOrDefault(false)
        }
        return CorrectionModelStatus(
            readiness = when {
                verified -> CorrectionModelReadiness.Verified
                installed -> CorrectionModelReadiness.Installed
                else -> CorrectionModelReadiness.Bundled
            },
            bytes = MODEL_BYTES,
        )
    }

    fun lastRun(context: Context): CorrectionLastRun? {
        val prefs = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong("timestamp", 0L)
        if (timestamp <= 0L) return null
        return CorrectionLastRun(timestamp, prefs.getString("result", "unknown") ?: "unknown")
    }

    private fun record(context: Context, outcome: CorrectionOutcome): CorrectionOutcome {
        val result = when (outcome) {
            is CorrectionOutcome.Applied -> "applied"
            is CorrectionOutcome.Unchanged -> outcome.reason
        }
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("timestamp", System.currentTimeMillis())
            .putString("result", result)
            .apply()
        return outcome
    }

    fun wrapPrompt(raw: String): String {
        val body = "$INSTRUCTION\nRaw: $raw\nResult:"
        return "<|im_start|>user\n$body<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    /**
     * Run on-device correction. Never throws into the IME. Disabled, missing
     * model, timeout, parse failure, or stale generation all return Unchanged
     * so the caller keeps the original text.
     */
    suspend fun correct(context: Context, raw: String, enabled: Boolean): CorrectionOutcome {
        if (!enabled) return record(context, CorrectionOutcome.Unchanged("disabled"))
        if (raw.isBlank()) return record(context, CorrectionOutcome.Unchanged("blank"))
        val file = ensureModelFile(context)
            ?: return record(context, CorrectionOutcome.Unchanged("missing_model"))
        if (!CorrectionNative.ensureLoaded()) {
            return record(context, CorrectionOutcome.Unchanged("no_native"))
        }

        val generation = owner.tryBegin()
        if (generation < 0) return record(context, CorrectionOutcome.Unchanged("busy"))

        val outcome = try {
            withContext(Dispatchers.IO) {
                val loaded = try {
                    CorrectionNative.nativeLoad(file.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "nativeLoad failed", e)
                    false
                }
                if (!loaded) {
                    return@withContext CorrectionPolicy.decide(
                        true, raw, null, timedOut = false, generation, owner.current(),
                    ).let { CorrectionOutcome.Unchanged("load") }
                }

                val output = CorrectionNative.nativeGenerate(wrapPrompt(raw), N_PREDICT, TIMEOUT_MS)
                val timedOut = output == TIMEOUT_SENTINEL
                CorrectionPolicy.decide(
                    enabled = true,
                    raw = raw,
                    modelOutput = output.takeUnless { timedOut },
                    timedOut = timedOut,
                    generation = generation,
                    currentGeneration = owner.current(),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                owner.cancel()
                throw e
            }
            Log.w(TAG, "correct failed", e)
            CorrectionOutcome.Unchanged("error")
        } finally {
            owner.end(generation)
        }
        return record(context, outcome)
    }

    fun cancel() {
        owner.cancel()
        if (CorrectionNative.available) {
            try {
                CorrectionNative.nativeCancel()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Materializes the hash-pinned bundled model into app-private storage.
     * The copy is atomic and serialized so an interrupted first use can never
     * leave a file that llama.cpp later mistakes for a valid model.
     */
    private suspend fun ensureModelFile(context: Context): File? = withContext(Dispatchers.IO) {
        modelInstallMutex.withLock {
            val destination = modelFile(context)
            val marker = verificationMarker(context)
            if (destination.isFile && destination.length() == MODEL_BYTES) {
                val markerMatches = marker.isFile && runCatching {
                    marker.readText().trim().equals(MODEL_SHA256, ignoreCase = true)
                }.getOrDefault(false)
                if (verifiedModelPath == destination.absolutePath || markerMatches || sha256(destination) == MODEL_SHA256) {
                    verifiedModelPath = destination.absolutePath
                    if (!markerMatches) runCatching { marker.writeText(MODEL_SHA256) }
                    return@withLock destination
                }
                Log.w(TAG, "existing correction model failed integrity verification; replacing it")
                marker.delete()
                if (!destination.delete()) return@withLock null
            }

            val directory = destination.parentFile ?: return@withLock null
            if (!directory.exists() && !directory.mkdirs()) return@withLock null
            if (directory.usableSpace < MODEL_BYTES + 64L * 1024L * 1024L) {
                Log.w(TAG, "not enough app storage to prepare bundled correction model")
                return@withLock null
            }

            val temporary = File(directory, "$ON_DEVICE_MODEL_FILENAME.part")
            try {
                temporary.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open(MODEL_ASSET_PATH).use { input ->
                    temporary.outputStream().buffered(1024 * 1024).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (temporary.length() != MODEL_BYTES || hash != MODEL_SHA256) {
                    Log.e(TAG, "bundled correction model failed integrity verification")
                    temporary.delete()
                    return@withLock null
                }

                if (destination.exists() && !destination.delete()) {
                    temporary.delete()
                    return@withLock null
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                destination.takeIf { it.isFile && it.length() == MODEL_BYTES }?.also {
                    verifiedModelPath = it.absolutePath
                    marker.writeText(MODEL_SHA256)
                }
            } catch (e: Exception) {
                temporary.delete()
                marker.delete()
                Log.e(TAG, "failed to prepare bundled correction model", e)
                null
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
