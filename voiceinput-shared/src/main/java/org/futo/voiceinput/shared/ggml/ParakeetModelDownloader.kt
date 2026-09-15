package org.futo.voiceinput.shared.ggml

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.futo.voiceinput.shared.R
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device downloader for the Parakeet TDT 0.6B v2 (int8) sherpa-onnx model.
 *
 * The model (~630MB total) is NOT bundled in the phone APK and app-private storage
 * (filesDir/models/...) means there is no sideload path for a normal user install —
 * so the app downloads the model itself, over the Tailscale mesh, from the VPS:
 *
 *   $MODEL_MIRROR_URL/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/<file>  (build-time injected)
 *
 * (Cleartext HTTP to that host only is permitted via network_security_config.xml;
 * the mesh itself is encrypted.)
 *
 * Download discipline (same lessons as ModelParakeetAsset's asset seeding):
 *   stream -> <staging>/<file>.download -> byte-count + SHA-256 verify -> atomic
 *   rename() to <staging>/<file> -> only after ALL files verify, atomic rename()
 *   each into the live model dir -> .complete marker written LAST. The engine can
 *   never mmap a file the downloader is mid-write on: ModelParakeetAsset.exists()
 *   requires the marker, and the marker only appears after every file finished.
 *   Without the marker a truncated ONNX would cause an uncatchable native abort
 *   (SIGABRT) inside onnxruntime at load time.
 *
 * On any failure the staging .download partials are KEPT (they are verified
 * prefixes and the next run resumes them via HTTP Range); only a provably
 * corrupt partial is deleted, and an error notification is shown.
 *
 * Progress is surfaced purely through a NotificationManager progress notification
 * (channel created on demand) — no UI files are touched.
 */
object ParakeetModelDownloader {
    private const val TAG = "ParakeetModelDl"

    const val MODEL_DIR_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    // Prefer the private mirror when one is injected. Personal builds must remain
    // usable without that build-machine secret, so fall back to the hash-pinned
    // upstream repository over HTTPS rather than constructing an invalid /models URL.
    private val configuredMirror =
        org.futo.voiceinput.shared.BuildConfig.MODEL_MIRROR_URL.trim().trimEnd('/')
    private val BASE_URL = if (
        configuredMirror.startsWith("https://") || configuredMirror.startsWith("http://")
    ) {
        "$configuredMirror/models/$MODEL_DIR_NAME"
    } else {
        "https://huggingface.co/csukuangfj/$MODEL_DIR_NAME/resolve/main"
    }

    val sourceLabel: String
        get() = if (configuredMirror.isNotBlank()) "Cornerstone mirror" else "Hugging Face"

    private const val CHANNEL_ID = "parakeet_model_download"
    private const val PROGRESS_NOTIFICATION_ID = 7101
    private const val ERROR_NOTIFICATION_ID = 7102

    /** Files ModelParakeetAsset / ParakeetSherpa expect inside the model dir. */
    val REQUIRED_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    )

    /** Exact byte sizes of the files served by the VPS (verified 2026-08-20). */
    val EXPECTED_SIZES: Map<String, Long> = mapOf(
        "encoder.int8.onnx" to 652184296L,
        "decoder.int8.onnx" to 7257753L,
        "joiner.int8.onnx"  to 1739080L,
        "tokens.txt"        to 9384L
    )

    /**
     * Pinned SHA-256 hashes, verified after download (including after any Range
     * resume completes) BEFORE the file leaves staging and before the .complete
     * marker can be written.
     *
     * Trust-on-first-use pins captured 2026-08-26 from the upstream k2-fsa model
     * (huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8);
     * each file's byte size also matches EXPECTED_SIZES. A file without a
     * pinned hash logs a warning and falls back to the byte-size check.
     */
    val EXPECTED_SHA256: Map<String, String> = mapOf(
        "encoder.int8.onnx" to "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab",
        "decoder.int8.onnx" to "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e",
        "joiner.int8.onnx"  to "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2",
        "tokens.txt"        to "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadInProgress = AtomicBoolean(false)

    /** Observable state for the keyboard's download window (Compose snapshot state).
     *  percent: -1 = idle/not started, 0-100 = downloading, 100 = done. */
    val progressPercent = androidx.compose.runtime.mutableIntStateOf(-1)
    val progressText = androidx.compose.runtime.mutableStateOf("")
    val lastError = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The directory ModelParakeetAsset loads from: filesDir/models/<assetDir>. */
    fun targetDir(context: Context): File =
        File(context.filesDir, "models/$MODEL_DIR_NAME")

    /**
     * Sibling staging directory: all downloads land here first and are only
     * swapped into [targetDir] after full verification, so the engine never
     * mmaps a file the downloader is mid-write on.
     */
    private fun stagingDir(context: Context): File =
        File(context.filesDir, "models/$MODEL_DIR_NAME.staging")

    private fun completeMarker(context: Context): File =
        File(targetDir(context), ".complete")

    @Throws(IOException::class)
    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Full-content verification of a downloaded file in staging: exact byte size
     * against EXPECTED_SIZES, then SHA-256 against EXPECTED_SHA256 when a hash is
     * pinned (otherwise a warning and the size check stands alone).
     */
    @Throws(IOException::class)
    private fun verifyStagedFile(name: String, file: File) {
        val expectedSize = EXPECTED_SIZES[name] ?: 0L
        if (expectedSize > 0L && file.length() != expectedSize) {
            throw IOException("Size mismatch for $name: expected $expectedSize bytes (EXPECTED_SIZES), got ${file.length()}")
        }

        val pinnedHash = EXPECTED_SHA256[name]
        if (pinnedHash != null) {
            val actualHash = sha256Hex(file)
            if (!actualHash.equals(pinnedHash, ignoreCase = true)) {
                throw IOException("SHA-256 mismatch for $name: expected $pinnedHash, got $actualHash")
            }
        } else {
            Log.w(TAG, "No pinned SHA-256 for $name — verified by byte size only")
        }
    }

    /**
     * Strict presence check: all 4 required files present (non-empty) AND the
     * .complete marker written — the same marker gate ModelParakeetAsset.exists()
     * applies, plus a file-level sanity check. This answers "is a full download
     * on disk".
     */
    fun isModelFullyDownloaded(context: Context): Boolean {
        if (!completeMarker(context).exists()) return false
        val dir = targetDir(context)
        return REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0L } }
    }

    /**
     * Kicks off a download on a background daemon thread if the model is missing.
     * Safe to call repeatedly and from any thread — concurrent/duplicate calls
     * are coalesced via an AtomicBoolean. Returns true if a download was started.
     */
    fun downloadIfNeededAsync(context: Context): Boolean {
        val appContext = context.applicationContext
        if (isModelFullyDownloaded(appContext)) return false
        if (!downloadInProgress.compareAndSet(false, true)) return false

        Thread({
            try {
                downloadBlocking(appContext)
            } finally {
                downloadInProgress.set(false)
            }
        }, "ParakeetModelDownloader").apply { isDaemon = true }.start()

        return true
    }

    /**
     * Synchronous download. Call from a background thread only.
     * Returns true if the model is fully present afterwards (either it already was,
     * or the download completed). On failure all partial state is cleaned up and an
     * error notification is posted.
     */
    fun downloadBlocking(context: Context): Boolean {
        if (isModelFullyDownloaded(context)) {
            progressPercent.intValue = 100
            return true
        }

        val dir = targetDir(context)
        if (!dir.mkdirs() && !dir.isDirectory) {
            notifyError(context, "Could not create model directory")
            return false
        }
        val staging = stagingDir(context)
        if (!staging.mkdirs() && !staging.isDirectory) {
            notifyError(context, "Could not create model staging directory")
            return false
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)

        // Total bytes for aggregate progress: known expected sizes only; 0 = unknown.
        val knownTotal = REQUIRED_FILES.sumOf { EXPECTED_SIZES[it] ?: 0L }
        var downloadedTotal = 0L

        fun isCompleteFile(f: File, expected: Long) =
            f.isFile && f.length() > 0L && (expected == 0L || f.length() == expected)

        try {
            // Fresh attempt: clear any prior failure state so the UI doesn't show
            // a stale "Download failed" while the retry is actively downloading.
            lastError.value = null
            progressPercent.intValue = 0
            REQUIRED_FILES.forEachIndexed { index, name ->
                val url = "$BASE_URL/$name"
                val finalFile = File(dir, name)
                val stagedFile = File(staging, name)
                val tmpFile = File(staging, "$name.download")
                val expected = EXPECTED_SIZES[name] ?: 0L

                // Skip files already fully in place from a previous (interrupted)
                // run — either swapped into the live dir or verified in staging.
                if (isCompleteFile(finalFile, expected) || isCompleteFile(stagedFile, expected)) {
                    val inPlace = if (isCompleteFile(finalFile, expected)) finalFile else stagedFile
                    tmpFile.delete() // stale partial superseded by the complete file
                    downloadedTotal += inPlace.length()
                    return@forEachIndexed
                }

                // Resume: keep any .download partial from a dropped connection and
                // fetch only the rest via HTTP Range. A 650MB file on a flaky link
                // never survives a from-scratch retry policy.
                val resumeFrom = if (tmpFile.isFile) tmpFile.length() else 0L

                val reqBuilder = Request.Builder().url(url).get()
                if (resumeFrom > 0L) reqBuilder.header("Range", "bytes=$resumeFrom-")
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val alreadyComplete = response.code == 416 && resumeFrom > 0L
                    if (!alreadyComplete && !response.isSuccessful) {
                        throw IOException("HTTP ${response.code} downloading $name")
                    }

                    var contentLength = -1L
                    if (!alreadyComplete) {
                        if (resumeFrom > 0L && response.code != 206) {
                            throw IOException("Server ignored Range for $name (HTTP ${response.code}) — refusing to clobber partial")
                        }
                        val body = response.body
                            ?: throw IOException("Empty response body for $name")
                        // 206 Content-Length is the REMAINING bytes; full size adds the resume offset
                        contentLength = body.contentLength().let {
                            if (response.code == 206 && it >= 0) it + resumeFrom else it
                        }

                        body.byteStream().use { input ->
                            java.io.FileOutputStream(tmpFile, resumeFrom > 0L).buffered().use { output ->
                                val buffer = ByteArray(1 shl 16)
                                var fileBytes = resumeFrom
                                var lastNotify = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    fileBytes += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastNotify > 500) {
                                        lastNotify = now
                                        val pct = if (knownTotal > 0)
                                            (((downloadedTotal + fileBytes) * 100) / knownTotal).toInt().coerceIn(0, 100)
                                        else -1
                                        progressPercent.intValue = pct
                                        progressText.value = "$name — ${(downloadedTotal + fileBytes) / (1024 * 1024)}MB / ${knownTotal / (1024 * 1024)}MB"
                                        notifyProgress(
                                            notificationManager, context,
                                            fileIndex = index, fileName = name,
                                            fileBytes = fileBytes,
                                            fileTotal = contentLength,
                                            totalBytes = downloadedTotal + fileBytes,
                                            totalTotal = knownTotal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Byte-verify against the response when no size is pinned.
                    val actual = tmpFile.length()
                    if (expected == 0L && contentLength >= 0L && actual != contentLength) {
                        throw IOException("Size mismatch for $name: expected $contentLength bytes (Content-Length), got $actual")
                    }
                    // Full verification (EXPECTED_SIZES + pinned SHA-256) BEFORE the
                    // file leaves staging — a corrupt file must never go live.
                    verifyStagedFile(name, tmpFile)

                    if (stagedFile.exists() && !stagedFile.delete()) {
                        throw IOException("Could not replace staged file: ${stagedFile.absolutePath}")
                    }
                    if (!tmpFile.renameTo(stagedFile)) {
                        // Keep the verified partial — the next run resumes/reuses it
                        throw IOException("Atomic rename failed for $name")
                    }

                    downloadedTotal += actual
                }
            }

            // All four files verified in staging: swap them into the live
            // directory only now, so the engine can never mmap a mid-write file.
            val writtenManifest = mutableListOf<String>()
            REQUIRED_FILES.forEach { name ->
                val stagedFile = File(staging, name)
                val finalFile = File(dir, name)
                if (stagedFile.isFile) {
                    if (finalFile.exists() && !finalFile.delete()) {
                        throw IOException("Could not replace existing file: ${finalFile.absolutePath}")
                    }
                    if (!stagedFile.renameTo(finalFile)) {
                        // Staging survives — the next run reuses the verified files
                        throw IOException("Atomic rename failed for $name")
                    }
                }
                writtenManifest.add("$name:${finalFile.length()}")
            }

            // Marker LAST: its presence guarantees every file above completed.
            completeMarker(context).writeText(writtenManifest.joinToString("\n"))
            staging.deleteRecursively() // stale .download partials are moot now

            progressPercent.intValue = 100
            progressText.value = "Voice model ready — close and reopen voice input"
            lastError.value = null
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
            notifyComplete(context, notificationManager)
            Log.i(TAG, "Parakeet model download complete: ${writtenManifest.joinToString()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Parakeet model download failed", e)
            lastError.value = e.message ?: e.javaClass.simpleName
            // Keep staging .download partials — they are verified prefixes and the
            // next run resumes them via HTTP Range. Only drop a partial that is
            // provably corrupt (longer than its known expected size).
            staging.listFiles()?.forEach {
                if (it.isFile && it.name.endsWith(".download")) {
                    val base = it.name.removeSuffix(".download")
                    val expected = EXPECTED_SIZES[base] ?: 0L
                    if (expected > 0L && it.length() > expected) it.delete()
                }
            }
            completeMarker(context).delete()
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
            notifyError(context, e.message ?: e.javaClass.simpleName)
            return false
        }
    }

    // --- Notifications (self-contained; no existing UI files touched) ---

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice model downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress of on-device voice model downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun notifyProgress(
        notificationManager: NotificationManager,
        context: Context,
        fileIndex: Int,
        fileName: String,
        fileBytes: Long,
        fileTotal: Long,
        totalBytes: Long,
        totalTotal: Long
    ) {
        val mb = fileBytes / (1024 * 1024)
        val text = if (fileTotal > 0) {
            "$fileName — ${mb}MB / ${fileTotal / (1024 * 1024)}MB (file ${fileIndex + 1}/${REQUIRED_FILES.size})"
        } else {
            "$fileName — ${mb}MB (file ${fileIndex + 1}/${REQUIRED_FILES.size})"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic_2_)
            .setContentTitle("Downloading Parakeet voice model")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (totalTotal > 0) {
            val percent = ((totalBytes * 100) / totalTotal).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else if (fileTotal > 0) {
            val percent = ((fileBytes * 100) / fileTotal).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        try {
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+): download continues silently.
            Log.w(TAG, "Cannot post progress notification", e)
        }
    }

    private fun notifyComplete(context: Context, notificationManager: NotificationManager) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic_2_)
            .setContentTitle("Parakeet voice model ready")
            .setContentText("On-device English voice typing is now available.")
            .setAutoCancel(true)
        try {
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post completion notification", e)
        }
    }

    private fun notifyError(context: Context, detail: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic_2_)
            .setContentTitle("Parakeet voice model download failed")
            .setContentText(detail)
            .setAutoCancel(true)
        try {
            notificationManager.notify(ERROR_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post error notification", e)
        }
    }
}
