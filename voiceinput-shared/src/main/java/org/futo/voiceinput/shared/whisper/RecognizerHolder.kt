package org.futo.voiceinput.shared.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.futo.voiceinput.shared.types.ModelLoader

/**
 * Process-wide keep-warm ModelManager. Loading Parakeet takes seconds; the
 * floating Cornerstone Voice bubble keeps one engine alive. The IME must too.
 * Window close must not destroy this — only cancel in-flight infer.
 */
object RecognizerHolder {
    @Volatile
    private var instance: ModelManager? = null

    // Count of live recognizer sessions (AudioRecognizer start..reset). While
    // any session is active, releaseAll() must not close engines out from
    // under it — a trim during dictation would force a ~600MB reload under
    // memory pressure or fail the session's final decode.
    private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)

    val isSessionActive: Boolean get() = activeSessions.get() > 0

    fun sessionStarted() {
        activeSessions.incrementAndGet()
    }

    fun sessionEnded() {
        activeSessions.decrementAndGet()
    }

    fun get(context: Context): ModelManager {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = ModelManager(context.applicationContext)
            instance = created
            return created
        }
    }

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True when [model]'s engine is resident right now (no load triggered). */
    fun isWarm(context: Context, model: ModelLoader): Boolean =
        instance?.peekLoadedEngine(model) != null

    /**
     * Loads [model]'s engine in the background so the first mic tap after a
     * process start (or after a memory-pressure release) does not pay the
     * multi-second Parakeet load on the user's clock. This is what the
     * floating Cornerstone Voice bubble does by being a resident service.
     *
     * Cheap and idempotent: returns immediately when the model is not on disk,
     * the engine is already resident, or a warm-up is already in flight.
     * Never throws into the IME.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun warmUp(context: Context, model: ModelLoader, reason: String) {
        val appContext = context.applicationContext
        if (!model.exists(appContext)) return
        val manager = get(appContext)
        if (manager.peekLoadedEngine(model) != null) return
        if (!warming.compareAndSet(false, true)) return

        GlobalScope.launch(Dispatchers.Default) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                manager.obtainModel(model)
                Log.i(
                    "RecognizerHolder",
                    "warm-up ($reason): engine loaded in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: Exception) {
                Log.w("RecognizerHolder", "warm-up ($reason) failed; will load on demand", e)
            } finally {
                warming.set(false)
            }
        }
    }

    /**
     * Drops every keep-warm engine (~600MB for Parakeet) under memory pressure.
     * No-op if no engine was ever loaded OR a dictation session is live; the
     * next dictation session simply reloads the model. Safe to call from any thread.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun releaseAll() {
        if (isSessionActive) {
            Log.i("RecognizerHolder", "Memory trim during active session — keeping engines warm")
            return
        }
        val manager = instance ?: return
        GlobalScope.launch(Dispatchers.Default) {
            try {
                manager.cleanUp()
            } catch (e: Exception) {
                Log.w("RecognizerHolder", "Keep-warm cleanup under memory pressure failed", e)
            }
        }
    }
}
