package org.futo.voiceinput.shared.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.futo.voiceinput.shared.ggml.BailLanguageException
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.ParakeetSherpa
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.types.toWhisperString


data class MultiModelRunConfiguration(
    val primaryModel: ModelLoader,
    val languageSpecificModels: Map<Language, ModelLoader>
)

data class DecodingConfiguration(
    val glossary: List<String>,
    val languages: Set<Language>,
    val suppressSymbols: Boolean
)

class MultiModelRunner(
    private val modelManager: ModelManager
) {
    suspend fun preload(runConfiguration: MultiModelRunConfiguration) = coroutineScope {
        val jobs = mutableListOf<Job>()

        jobs.add(launch(Dispatchers.Default) {
            modelManager.obtainModel(runConfiguration.primaryModel)
        })

        if (runConfiguration.languageSpecificModels.count() < 2) {
            runConfiguration.languageSpecificModels.forEach {
                jobs.add(launch(Dispatchers.Default) {
                    modelManager.obtainModel(it.value)
                })
            }
        }

        jobs.forEach { it.join() }
    }

    @Throws(InferenceCancelledException::class)
    suspend fun run(
        samples: FloatArray,
        runConfiguration: MultiModelRunConfiguration,
        decodingConfiguration: DecodingConfiguration,
        callback: ModelInferenceCallback
    ): String = coroutineScope {
        callback.updateStatus(InferenceState.LoadingModel)
        val primaryModel = modelManager.obtainModel(runConfiguration.primaryModel)

        // Parakeet (sherpa-onnx offline transducer) is English-only with no language
        // detection, no prompt/glossary and no bail-language model switching.
        // Run a single decode and skip the whisper-specific machinery below.
        if (primaryModel is ParakeetSherpa) {
            callback.updateStatus(InferenceState.Encoding)
            return@coroutineScope primaryModel.infer(
                samples = samples,
                prompt = "",
                languages = arrayOf(),
                bailLanguages = arrayOf(),
                decodingMode = DecodingMode.Greedy,
                suppressNonSpeechTokens = false,
                partialResultCallback = {
                    callback.partialResult(it)
                }
            )
        }

        val allowedLanguages = decodingConfiguration.languages.map { it.toWhisperString() }.toTypedArray()
        val bailLanguages = runConfiguration.languageSpecificModels.filter { it.value != runConfiguration.primaryModel }.keys.map { it.toWhisperString() }.toTypedArray()

        val glossary = if(decodingConfiguration.glossary.isNotEmpty()) {
            "(Glossary: " + decodingConfiguration.glossary.joinToString(separator = ", ") + ")"
        } else {
            ""
        }

        val result = try {
            callback.updateStatus(InferenceState.Encoding)
            primaryModel.infer(
                samples = samples,
                prompt = glossary,
                languages = allowedLanguages,
                bailLanguages = bailLanguages,
                decodingMode = DecodingMode.BeamSearch5,
                suppressNonSpeechTokens = decodingConfiguration.suppressSymbols,
                partialResultCallback = {
                    callback.partialResult(it)
                }
            )
        } catch(e: BailLanguageException) {
            callback.updateStatus(InferenceState.SwitchingModel)
            val language = getLanguageFromWhisperString(e.language)

            val specificModelLoader = runConfiguration.languageSpecificModels[language]!!
            val specificModel = modelManager.obtainModel(specificModelLoader)

            specificModel.infer(
                samples = samples,
                prompt = glossary,
                languages = arrayOf(e.language),
                bailLanguages = arrayOf(),
                decodingMode = DecodingMode.BeamSearch5,
                suppressNonSpeechTokens = decodingConfiguration.suppressSymbols,
                partialResultCallback = {
                    callback.partialResult(it)
                }
            )
        }

        return@coroutineScope result
    }

    fun cancelAll() {
        modelManager.cancelAll()
    }
}