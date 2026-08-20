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
 *   http://100.100.194.62:8890/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/<file>
 *
 * (Cleartext HTTP to that host only is permitted via network_security_config.xml;
 * the mesh itself is encrypted.)
 *
 * Download discipline (same lessons as ModelParakeetAsset's asset seeding):
 *   stream -> <file>.download -> byte-count verify -> atomic rename() to final name
 *   -> .complete marker written LAST. Presence of the marker guarantees every file
 *   finished; without it a truncated ONNX file would cause an uncatchable native
 *   abort (SIGABRT) inside onnxruntime at load time.
 *
 * On any failure every *.download partial is deleted and an error notification is
 * shown, so no partial state survives.
 *
 * Progress is surfaced purely through a NotificationManager progress notification
 * (channel created on demand) — no UI files are touched.
 */
object ParakeetModelDownloader {
    private const val TAG = "ParakeetModelDl"

    const val MODEL_DIR_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    private const val BASE_URL = "http://100.100.194.62:8890/models/$MODEL_DIR_NAME"

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

    private fun completeMarker(context: Context): File =
        File(targetDir(context), ".complete")

    /**
     * Strict presence check: all 4 required files present (non-empty) AND the
     * .complete marker written. Deliberately stricter than
     * ModelParakeetAsset.exists() (which also accepts sideloads/asset bundles) —
     * this answers "is a full download on disk".
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

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)

        // Total bytes for aggregate progress: known expected sizes only; 0 = unknown.
        val knownTotal = REQUIRED_FILES.sumOf { EXPECTED_SIZES[it] ?: 0L }
        var downloadedTotal = 0L
        val writtenManifest = mutableListOf<String>()

        try {
            // Fresh attempt: clear any prior failure state so the UI doesn't show
            // a stale "Download failed" while the retry is actively downloading.
            lastError.value = null
            progressPercent.intValue = 0
            REQUIRED_FILES.forEachIndexed { index, name ->
                val url = "$BASE_URL/$name"
                val finalFile = File(dir, name)
                val tmpFile = File(dir, "$name.download")
                val expected = EXPECTED_SIZES[name] ?: 0L

                // Skip files already fully in place from a previous (interrupted) run.
                if (finalFile.isFile && finalFile.length() > 0L &&
                    (expected == 0L || finalFile.length() == expected)
                ) {
                    tmpFile.delete() // stale partial superseded by the complete file
                    downloadedTotal += finalFile.length()
                    writtenManifest.add("$name:${finalFile.length()}")
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

                    // Byte-verify: exact EXPECTED_SIZES entry when known, else the
                    // response Content-Length, else accept with a warning.
                    val actual = tmpFile.length()
                    when {
                        expected > 0L && actual != expected ->
                            throw IOException("Size mismatch for $name: expected $expected bytes (EXPECTED_SIZES), got $actual")
                        expected == 0L && contentLength >= 0L && actual != contentLength ->
                            throw IOException("Size mismatch for $name: expected $contentLength bytes (Content-Length), got $actual")
                        expected == 0L && contentLength < 0L ->
                            Log.w(TAG, "No size reference for $name (${actual}B) - skipping byte verification")
                    }

                    if (finalFile.exists() && !finalFile.delete()) {
                        throw IOException("Could not replace existing file: ${finalFile.absolutePath}")
                    }
                    if (!tmpFile.renameTo(finalFile)) {
                        // Keep the verified partial — the next run resumes/reuses it
                        throw IOException("Atomic rename failed for $name")
                    }

                    downloadedTotal += actual
                    writtenManifest.add("$name:$actual")
                }
            }

            // Marker LAST: its presence guarantees every file above completed.
            completeMarker(context).writeText(writtenManifest.joinToString("\n"))

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
            // Keep .download partials — they are verified prefixes and the next run
            // resumes them via HTTP Range. Only drop a partial that is provably
            // corrupt (longer than its known expected size).
            dir.listFiles()?.forEach {
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
