package org.futo.voiceinput.shared.whisper

import android.content.Context

/**
 * Process-wide keep-warm ModelManager. Loading Parakeet takes seconds; the
 * floating Cornerstone Voice bubble keeps one engine alive. The IME must too.
 * Window close must not destroy this — only cancel in-flight infer.
 */
object RecognizerHolder {
    @Volatile
    private var instance: ModelManager? = null

    fun get(context: Context): ModelManager {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = ModelManager(context.applicationContext)
            instance = created
            return created
        }
    }
}
