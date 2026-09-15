package org.futo.inputmethod.latin.xlm.correction

/**
 * Native llama.cpp bridge. Never load the .so at class init — that killed
 * the process on launch. Load only on first correction attempt.
 */
internal object CorrectionNative {
    @Volatile
    var available: Boolean = false
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (available) return true
        return try {
            System.loadLibrary("cornerstone_correction")
            available = true
            true
        } catch (_: Throwable) {
            available = false
            false
        }
    }

    external fun nativeLoad(path: String): Boolean
    external fun nativeGenerate(prompt: String, nPredict: Int, timeoutMs: Long): String
    external fun nativeCancel()
    external fun nativeUnload()
}
