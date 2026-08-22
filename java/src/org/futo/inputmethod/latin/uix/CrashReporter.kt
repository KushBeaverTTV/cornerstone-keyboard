package org.futo.inputmethod.latin.uix

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.actions.AGENT_KEY_TOKEN
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * KBP crash reporter. The keyboard is prototype-grade and the test device is not
 * adb-reachable — so crashes report themselves. On any uncaught exception:
 * write the trace to filesDir/crash-last.log, and POST it to the VPS over the
 * Tailscale mesh (best-effort, never blocks, never throws).
 *
 * Install once from LatinIME.onCreate. Chains to any previous handler.
 */
object CrashReporter : Thread.UncaughtExceptionHandler {
    private const val TAG = "CrashReporter"
    private val CRASH_URL = "${BuildConfig.AGENT_KEY_ENDPOINT.removeSuffix("/agentkey")}/crash"

    @Volatile private var appContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun install(context: Context) {
        appContext = context.applicationContext
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        if (existing !== this) previousHandler = existing
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "crash reporter installed")
        flushStoredCrash()
    }

    /** Token resolution mirrors AgentAction: DataStore setting first, BuildConfig fallback. */
    private fun getToken(ctx: Context): String =
        runCatching { ctx.getSettingBlocking(AGENT_KEY_TOKEN) }.getOrDefault("")
            .ifBlank { BuildConfig.AGENT_KEY_TOKEN }

    /** If the last run died without network (or pre-token), upload the stored trace now. */
    private val flushGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun flushStoredCrash() {
        val ctx = appContext ?: return
        val token = getToken(ctx)
        if (token.isBlank()) return
        val file = File(ctx.filesDir, "crash-last.log")
        if (!file.isFile || file.length() == 0L) return
        if (!flushGuard.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().put("trace", "REPLAYED_ON_LAUNCH\n" + file.readText()).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                client.newCall(
                    Request.Builder()
                        .url(CRASH_URL)
                        .header("Authorization", "Bearer $token")
                        .post(body)
                        .build()
                ).execute().use { if (it.isSuccessful || it.code in 400..499) file.delete() }
            } catch (_: Exception) { }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = "thread=${thread.name}\n$sw"

            val ctx = appContext
            if (ctx != null) {
                try {
                    File(ctx.filesDir, "crash-last.log").writeText(trace.take(32000))
                } catch (_: Exception) { }

                val token = getToken(ctx)
                if (token.isNotBlank()) {
                    // The process is dying. A direct POST from the crashing thread
                    // would throw NetworkOnMainThreadException for main-thread crashes
                    // (exactly the ones we care about), so run it on a worker thread
                    // and give it a hard 2s window before the process dies.
                    try {
                        val payload = JSONObject().put("trace", trace.take(32000)).toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                        val t = Thread {
                            try {
                                client.newCall(
                                    Request.Builder()
                                        .url(CRASH_URL)
                                        .header("Authorization", "Bearer $token")
                                        .post(payload)
                                        .build()
                                ).execute().close()
                            } catch (_: Exception) { }
                        }
                        t.isDaemon = true
                        t.start()
                        t.join(2000)
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            // never crash the crash handler
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
