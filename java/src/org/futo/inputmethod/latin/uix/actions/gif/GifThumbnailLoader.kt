package org.futo.inputmethod.latin.uix.actions.gif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Minimal GIF thumbnail fetcher. The project has no image-loading library, so
 * this is a deliberately small OkHttp + BitmapFactory implementation:
 * - downloads bounded by a semaphore (no unbounded parallelism when flinging)
 * - in-memory LruCache of downsampled bitmaps (memory-bounded)
 * - suspending loads that cancel the underlying OkHttp call when the caller's
 *   coroutine is cancelled (e.g. panel close or cell scrolled away)
 */
object GifThumbnailLoader {
    // ~24 MB of decoded thumbnails is plenty for a keyboard panel
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_PARALLEL = 6

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val semaphore = Semaphore(MAX_PARALLEL)

    fun cached(url: String): ImageBitmap? = cache.get(url)?.asImageBitmap()

    /**
     * Loads and downsamples the image at [url] to roughly [targetWidthPx] wide.
     * Returns null on any failure. Safe to call from the main thread.
     */
    suspend fun load(url: String, targetWidthPx: Int): ImageBitmap? {
        cache.get(url)?.let { return it.asImageBitmap() }

        return semaphore.withPermit {
            // Re-check after waiting for a permit: another cell may have loaded it
            cache.get(url)?.let { return@withPermit it.asImageBitmap() }

            val bytes = fetch(url, MAX_FULL_GIF_BYTES) ?: return@withPermit null
            val bitmap = withContext(Dispatchers.Default) {
                decodeDownsampled(bytes, targetWidthPx.coerceAtLeast(1))
            } ?: return@withPermit null

            cache.put(url, bitmap)
            bitmap.asImageBitmap()
        }
    }

    /**
     * Downloads the full (unmodified) GIF bytes for insertion, capped at
     * [maxBytes]. Returns null on failure or oversize.
     */
    suspend fun downloadFullGif(url: String, maxBytes: Long = MAX_FULL_GIF_BYTES): ByteArray? =
        fetch(url, maxBytes)

    fun clear() {
        cache.evictAll()
    }

    private suspend fun fetch(url: String, maxBytes: Long): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body
                        cont.resume(
                            if (it.isSuccessful && body != null &&
                                (body.contentLength() < 0 || body.contentLength() <= maxBytes)
                            ) {
                                try {
                                    body.byteStream().readBounded(maxBytes)
                                } catch (e: IOException) {
                                    null
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            })
        }

    // Reads up to maxBytes; returns null if the stream holds more than that.
    private fun java.io.InputStream.readBounded(maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeDownsampled(bytes: ByteArray, targetWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
