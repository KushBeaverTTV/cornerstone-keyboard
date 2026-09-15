package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.ggml.SpeechEngine
import org.futo.voiceinput.shared.types.ModelLoader


class ModelManager(
    val context: Context
) {
    // Single-flight: concurrent callers racing the same model share one load.
    private val loadedModels = EngineCache<Any, SpeechEngine>()

    fun obtainModel(model: ModelLoader): SpeechEngine {
        val key = model.key(context)
        return loadedModels.getOrLoad(key) { model.loadEngine(context) }
    }

    /**
     * Returns the engine for [model] only if it is ALREADY loaded — never triggers
     * a load. The recording loop uses this for streaming partial decodes: a load
     * takes seconds and must never happen on the audio path.
     */
    fun peekLoadedEngine(model: ModelLoader): SpeechEngine? {
        return loadedModels.peek(model.key(context))
    }

    fun cancelAll() {
        loadedModels.entries.forEach {
            it.value.cancel()
        }
    }

    suspend fun cleanUp() {
        for (model in loadedModels.entries) {
            model.value.cancel()
            model.value.close()
        }

        loadedModels.clear()
    }
}
