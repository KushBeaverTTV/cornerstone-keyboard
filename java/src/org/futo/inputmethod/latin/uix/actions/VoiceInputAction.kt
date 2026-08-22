package org.futo.inputmethod.latin.uix.actions

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.POLISH_DICTATION_ONLINE
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.BUILTIN_PARAKEET_MODEL
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.ggml.ParakeetModelDownloader
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.supportsVoiceCommands
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.ProcessedVoiceResult
import org.futo.voiceinput.shared.types.VoiceCommandProcessor
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                context.openURI("https://keyboard.futo.tech/voice-input-models", true)
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    // The exact string the last voice-input session committed into the field.
    // Powers the "delete that" voice command: it survives across windows because
    // this state outlives any single dictation session.
    var lastCommittedVoiceText: String? = null

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }

    override fun close() {
        runBlocking { modelManager.cleanUp() }
        userDictionaryObserver.unregister()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val usePersonalDict = context.getSetting(USE_PERSONAL_DICT)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()
        val glossary = if(usePersonalDict) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = glossary,
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD
            )
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    // Voice commands ("new line", "delete that", "send it") apply to the Parakeet
    // path only; whisper transcripts are committed verbatim.
    private val voiceCommandsEnabled = model.supportsVoiceCommands()

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch(e: ModelDoesNotExistException) {
            modelException.value = e
            return@launch
        }

        this@VoiceInputActionWindow.recognizerView.value = recognizerView

        //yield()
        recognizerView.reset()

        //yield()
        recognizerView.start()
    }

    private var inputTransaction = manager.createInputTransaction()

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    override fun close(): CloseResult {
        inputTransaction.cancel()
        runBlocking { initJob.cancelAndJoin() }
        recognizerView.value?.cancel()
        state.modelManager.cancelAll()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            inputTransaction.cancel()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            state.soundPlayer.playStartSound()
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true

        manager.getLifecycleScope().launch(Dispatchers.Main) {
            // Voice commands are post-processed on the Parakeet path only; any
            // other engine's transcript is committed verbatim.
            val afterVoiceCommands = when {
                voiceCommandsEnabled -> when (val processed = VoiceCommandProcessor.process(result)) {
                    is ProcessedVoiceResult.InsertText -> processed.text
                    ProcessedVoiceResult.DeleteLastSentence -> {
                        handleDeleteLastSentence()
                        return@launch
                    }
                    ProcessedVoiceResult.PerformImeAction -> {
                        handlePerformImeAction()
                        return@launch
                    }
                }
                else -> result
            }

            // Always run the conservative offline rules on the final transcript.
            // Partials are never touched; this path is only for finished results.
            val offlineText = offlineProcessTranscript(afterVoiceCommands)

            // Optional online polish: opt-in, default OFF, never blocks insertion.
            val finalText = if (context.getSetting(POLISH_DICTATION_ONLINE)) {
                polishDictationTranscript(context, offlineText)
            } else {
                offlineText
            }

            commitTranscript(finalText, result)
        }
    }

    private fun commitTranscript(commandProcessed: String, announceText: String) {
        val sanitized = ModelOutputSanitizer.sanitize(commandProcessed, inputTransaction.textContext)
        inputTransaction.commit(sanitized)
        if (sanitized.isNotBlank()) {
            state.lastCommittedVoiceText = sanitized
        }
        manager.announce(announceText)
        manager.closeActionWindow()
    }

    /**
     * Discards the composing partial (the command phrase itself — "delete that"
     * must not become text) and ends the input transaction, so subsequent
     * backspace/editor-action calls route through the normal IME again.
     * cancel() commits the current partial, so the partial is cleared FIRST.
     */
    private fun discardCommandPartialAndEndTransaction() {
        inputTransaction.updatePartial("")
        inputTransaction.cancel()
    }

    private fun handleDeleteLastSentence() {
        val beforeCursor = inputTransaction.textContext.beforeCursor?.toString().orEmpty()
        val charsToDelete = deletionLength(beforeCursor, state.lastCommittedVoiceText)

        discardCommandPartialAndEndTransaction()

        if (charsToDelete > 0) {
            manager.backspace(charsToDelete)
            manager.announce(context.getString(R.string.action_voice_deleted_last_sentence))
        } else {
            manager.announce(context.getString(R.string.action_voice_nothing_to_delete))
        }
        state.lastCommittedVoiceText = null
        manager.closeActionWindow()
    }

    private fun handlePerformImeAction() {
        discardCommandPartialAndEndTransaction()

        if (manager.performEditorAction()) {
            manager.announce(context.getString(R.string.action_voice_sent))
        }
        // A sent field usually dismisses itself; still close our window cleanly.
        manager.closeActionWindow()
    }

    companion object {
        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\n')

        /**
         * How many characters "delete that" removes.
         *
         * Primary path: the exact string this session previously committed is still
         * at the cursor — delete precisely it. Fallback: the user typed on since
         * then, so delete the trailing sentence instead (bounded to the last word
         * when no sentence boundary exists, so a runaway delete is impossible).
         */
        fun deletionLength(beforeCursor: String, lastCommitted: String?): Int {
            if (!lastCommitted.isNullOrEmpty() && beforeCursor.endsWith(lastCommitted)) {
                return lastCommitted.length
            }
            return trailingSentenceLength(beforeCursor)
        }

        fun trailingSentenceLength(text: String): Int {
            if (text.isEmpty()) return 0

            // Trailing whitespace belongs to the sentence before it:
            // "Hello. Next. " deletes "Next. " (with its trailing space).
            var i = text.length - 1
            while (i >= 0 && text[i].isWhitespace()) i--
            if (i < 0) return text.length // field is whitespace-only before cursor

            // Consume the trailing sentence's OWN terminator(s) first, otherwise
            // the boundary scan below would stop at it and delete only that.
            while (i >= 0 && text[i] in SENTENCE_TERMINATORS) i--
            if (i < 0) return text.length // text is punctuation-only (e.g. "...")

            while (i >= 0 && text[i] !in SENTENCE_TERMINATORS) i--
            if (i >= 0) return text.length - (i + 1)

            // No sentence boundary anywhere: bound the damage to the last word.
            val lastSpace = text.indexOfLast { it.isWhitespace() }
            return if (lastSpace >= 0) text.length - (lastSpace + 1) else 0
        }
    }

    override fun partialResult(result: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "languages")
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

/** KBP: shown when the built-in Parakeet model is selected but not on disk yet —
 *  the downloader is running; show OUR progress instead of FUTO's model store. */
private class CornerstoneModelDownloadWindow : ActionWindow() {
    @Composable
    override fun windowName(): String = "Cornerstone Voice"

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val percent = ParakeetModelDownloader.progressPercent.intValue
        val status = ParakeetModelDownloader.progressText.value
        val error = ParakeetModelDownloader.lastError.value

        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cornerstone Voice", textAlign = TextAlign.Center)
                Text(
                    when {
                        error != null -> "Download failed: $error\nClose and reopen voice input to retry."
                        percent >= 100 -> "Voice model ready!\nClose and reopen voice input to start dictating."
                        percent >= 0 -> "Downloading voice model… $percent%"
                        else -> "Preparing voice model download…"
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (error == null && percent in 0..99) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (status.isNotBlank()) {
                        Text(status, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Text(
                    "One-time download (~650MB). It resumes automatically if the connection drops — you can close this and keep typing.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), locales.firstOrNull() ?: Locale.ROOT, triggerDownload = true)

        if(model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else if (model === BUILTIN_PARAKEET_MODEL && !model.exists(manager.getContext())) {
            // Our model isn't on disk yet — the downloader was already kicked off
            // inside tryFindingVoiceInputModelForLocale; show OUR progress screen
            CornerstoneModelDownloadWindow()
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)