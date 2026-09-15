package org.futo.inputmethod.latin.uix.actions

import kotlinx.coroutines.withContext

import org.futo.inputmethod.latin.BuildConfig

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
import org.futo.inputmethod.latin.uix.IncognitoState
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.POLISH_DICTATION_ONLINE
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VOICE_KEEP_WARM
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneBase
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneCard

import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneDanger
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneElevated
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneMuted
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneSuccess
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneWarning
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneYellow
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
import org.futo.voiceinput.shared.whisper.RecognizerHolder
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.FinishPolicy
import org.futo.voiceinput.shared.EditorPublicationPolicy
import org.futo.voiceinput.shared.CommitFirstPolicy
import org.futo.voiceinput.shared.DictationPolishPolicy
import org.futo.voiceinput.shared.PolishPolicy
import org.futo.voiceinput.shared.VoiceToAgent
import org.futo.inputmethod.latin.xlm.correction.ON_DEVICE_LLM_CORRECTION
import java.util.Locale

private val CornerstoneVoiceBlack = Color(0xFF050505)
private val CornerstoneVoiceSurface = Color(0xFF111111)
private val CornerstoneVoiceRaised = Color(0xFF1A1A1A)
private val CornerstoneVoiceMuted = Color(0xFFB8B8B8)

private val CornerstoneVoiceColorScheme = darkColorScheme(
    primary = CornerstoneYellow,
    onPrimary = CornerstoneVoiceBlack,
    secondary = Color.White,
    onSecondary = CornerstoneVoiceBlack,
    background = CornerstoneVoiceBlack,
    onBackground = Color.White,
    surface = CornerstoneVoiceSurface,
    onSurface = Color.White,
    surfaceVariant = CornerstoneVoiceRaised,
    onSurfaceVariant = CornerstoneVoiceMuted,
    primaryContainer = CornerstoneVoiceRaised,
    onPrimaryContainer = Color.White,
    secondaryContainer = CornerstoneVoiceSurface,
    onSecondaryContainer = CornerstoneVoiceMuted,
    error = CornerstoneDanger,
)

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
                context.openURI("https://github.com/KushBeaverTTV/cornerstone-keyboard", true)
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

/**
 * Pre-loads the Parakeet engine in the background when the keyboard comes up,
 * so tapping the mic never waits on the ~600MB model load. Idempotent and
 * cheap when the engine is already resident (one map lookup). Respects the
 * user's keep-warm switch and does nothing when system voice input is chosen
 * or the model has not been downloaded yet.
 */
fun warmCornerstoneVoiceIfEnabled(context: Context, reason: String) {
    try {
        if (!context.getSetting(VOICE_KEEP_WARM)) return
        if (context.getSetting(USE_SYSTEM_VOICE_INPUT)) return
        RecognizerHolder.warmUp(context, BUILTIN_PARAKEET_MODEL, reason)
    } catch (e: Exception) {
        Log.w("VoiceInputAction", "voice warm-up skipped", e)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = RecognizerHolder.get(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    // The exact string the last voice-input session committed into the field.
    // Powers the "delete that" voice command: it survives across windows because
    // this state outlives any single dictation session.
    var lastCommittedVoiceText: String? = null

    override suspend fun cleanUp() {
        // Keep the engine warm across sessions. Cancel in-flight work only.
        modelManager.cancelAll()
    }

    override fun close() {
        modelManager.cancelAll()
        userDictionaryObserver.unregister()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow(), RecognizerViewListener {
    override val windowBackgroundColor: Color = CornerstoneVoiceBlack
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
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
            shouldShowInlinePartialResult = FinishPolicy.SHOW_INLINE_PARTIALS,
            shouldShowVerboseFeedback = false,
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
    override fun windowName(): String = "Cornerstone Voice"

    @Composable
    override fun WindowTitleBar(rowScope: RowScope) {
        with(rowScope) {
            Text(
                "Cornerstone Voice",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        MaterialTheme(colorScheme = CornerstoneVoiceColorScheme) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    when {
                        modelException.value != null -> ModelDownloader(modelException.value!!)
                        recognizerView.value != null -> recognizerView.value!!.Content()
                    }
                }
            }
        }
    }

    override fun close(): CloseResult {
        // Once finished() owns the one final commit, window teardown must not
        // race it by finalizing the input transaction with a stale partial.
        if (!wasFinished) inputTransaction.cancel()
        initJob.cancel()
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
        // Single-writer guarantee: only the FIRST finished() may run the commit
        // pipeline. A second delivery (stale decode racing window teardown)
        // would commit the same text again — that is how duplicated text lands
        // in the field, so it is a logged no-op here.
        if (wasFinished) {
            Log.w("VoiceInputAction", "Duplicate finished() delivery ignored")
            return
        }
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

            val routed = VoiceToAgent.parse(offlineText)
            if (routed != null) {
                handleVoiceToAgent(routed)
                return@launch
            }

            // Local correction is controlled solely by its own switch. Network
            // polish remains separately opt-in. Both paths fail closed to the
            // offline transcript and stay disabled in sensitive fields.
            val polishAllowed = PolishPolicy.allowedInField(
                isPassword = isPasswordField(manager.getCurrentEditorInfo()?.inputType ?: 0),
                isIncognito = IncognitoState.active
            )
            val onDeviceCorrection = context.getSetting(ON_DEVICE_LLM_CORRECTION)
            val onlinePolish = context.getSetting(POLISH_DICTATION_ONLINE)
            val wantsPolish = polishAllowed && DictationPolishPolicy.shouldAttempt(
                onDeviceEnabled = onDeviceCorrection,
                onlineEnabled = onlinePolish,
                offlineText = offlineText,
            )

            if (!wantsPolish || !CommitFirstPolicy.COMMIT_BEFORE_POLISH) {
                // Legacy path (polish disabled, or contract flipped off): the
                // text that lands is final.
                val finalText = if (wantsPolish) polishDictationTranscript(context, offlineText) else offlineText
                commitTranscript(finalText)
                return@launch
            }

            // COMMIT FIRST. The user sees their words the instant recognition
            // ends. The correction pass (on-device Qwen and/or online polish)
            // runs afterwards, off the user's clock, and swaps the text in
            // place only if the field still ends with exactly what we put there.
            val textContextAtCommit = inputTransaction.textContext
            val committed = commitTranscript(offlineText)
            if (committed.isBlank()) return@launch

            manager.getLifecycleScope().launch(Dispatchers.IO) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val polished = try {
                    polishDictationTranscript(context, offlineText)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("VoiceInputAction", "background polish failed; keeping committed text", e)
                    return@launch
                }
                Log.i(
                    "VoiceInputAction",
                    "background polish finished in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(changed=${polished.trim() != offlineText.trim()})"
                )
                if (polished.trim() == offlineText.trim()) return@launch

                withContext(Dispatchers.Main) {
                    applyCorrectionInPlace(committed, polished, textContextAtCommit)
                }
            }
        }
    }

    /**
     * Swaps the just-committed dictation for its corrected form without ever
     * touching text the user typed afterwards. Runs on Main. Silent no-op when
     * the field moved on (cursor moved, more typing, app changed the text).
     */
    private fun applyCorrectionInPlace(
        committed: String,
        corrected: String,
        textContextAtCommit: org.futo.inputmethod.latin.uix.utils.TextContext?,
    ) {
        val latinIME = manager.getLatinIMEForDebug()
        val ic = latinIME.currentInputConnection ?: return
        val sanitizedCorrected = ModelOutputSanitizer.sanitize(corrected, textContextAtCommit)
        val beforeCursor = ic.getTextBeforeCursor(committed.length + 64, 0)?.toString() ?: return
        val deleteCodePoints = CommitFirstPolicy.replacementDeleteCount(
            beforeCursor = beforeCursor,
            committed = committed,
            corrected = sanitizedCorrected,
        ) ?: run {
            Log.i("VoiceInputAction", "correction skipped: field no longer ends with the committed dictation")
            return
        }
        if (latinIME.isInputConnectionOverridden) {
            // Another action window owns the field now — do not fight it.
            return
        }
        manager.backspace(deleteCodePoints)
        manager.typeText(sanitizedCorrected)
        if (state.lastCommittedVoiceText == committed) {
            state.lastCommittedVoiceText = sanitizedCorrected
        }
        manager.announce(sanitizedCorrected)
    }

    private suspend fun handleVoiceToAgent(cmd: VoiceToAgent.Command) {
        context.setSetting(AGENT_KEY_AGENT, cmd.agentId)
        if (cmd.rest.isBlank()) {
            inputTransaction.updatePartial("")
            inputTransaction.cancel()
            manager.announce("Agent: ${agentDisplayName(cmd.agentId)}")
            manager.closeActionWindow()
            return
        }
        val token = context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }
        if (token.isBlank()) {
            commitTranscript(cmd.rest)
            return
        }
        val result = withContext(Dispatchers.IO) {
            postToAgent(cmd.rest, token, cmd.agentId)
        }
        when (result) {
            is AgentResult.Success -> commitTranscript(result.text)
            else -> {
                manager.announce(agentResultMessage(result, agentDisplayName(cmd.agentId)))
                commitTranscript(cmd.rest)
            }
        }
    }

    /** Commits and closes the window. Returns the exact string that landed in the field. */
    private fun commitTranscript(commandProcessed: String): String {
        val sanitized = ModelOutputSanitizer.sanitize(commandProcessed, inputTransaction.textContext)
        inputTransaction.commit(sanitized)
        if (sanitized.isNotBlank()) {
            state.lastCommittedVoiceText = sanitized
        }
        // TalkBack announces what actually landed in the field, not the raw
        // pre-processing transcript.
        manager.announce(sanitized)
        manager.closeActionWindow()
        return sanitized
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
        fun deletionLength(beforeCursor: String, lastCommitted: String?): Int =
            VoiceDeletion.deletionLength(beforeCursor, lastCommitted)

        fun trailingSentenceLength(text: String): Int =
            VoiceDeletion.trailingSentenceLength(text)
    }

    override fun partialResult(result: String) {
        // RecognizerView already renders the live transcript in Cornerstone
        // Voice. Do not stream it into the target editor: Telegram and other
        // apps can lose the composing span and append the final transcript,
        // producing "partial + final" duplication. The editor receives one
        // final commit through finished() only.
        EditorPublicationPolicy.partialTextForEditor(result, finalizing = wasFinished)?.let { partial ->
            manager.getLifecycleScope().launch(Dispatchers.Main) {
                val sanitized = ModelOutputSanitizer.sanitize(partial, inputTransaction.textContext)
                inputTransaction.updatePartial(sanitized)
            }
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
    override val windowBackgroundColor: Color = CornerstoneBase

    @Composable
    override fun windowName(): String = "Cornerstone Voice"

    @Composable
    override fun WindowTitleBar(rowScope: RowScope) {
        with(rowScope) {
            Text(
                "Cornerstone Voice",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val context = LocalContext.current
        val percent = ParakeetModelDownloader.progressPercent.intValue
        val status = ParakeetModelDownloader.progressText.value
        val error = ParakeetModelDownloader.lastError.value

        MaterialTheme(colorScheme = CornerstoneVoiceColorScheme) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    when {
                        error != null && percent in 0..99 -> "DOWNLOAD PAUSED · $percent%"
                        error != null -> "DOWNLOAD PAUSED"
                        percent >= 100 -> "Voice model ready"
                        percent >= 0 -> "Downloading local voice model · $percent%"
                        else -> "Preparing local voice model"
                    },
                    color = when {
                        error != null -> CornerstoneWarning
                        percent >= 100 -> CornerstoneSuccess
                        else -> CornerstoneYellow
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                if (percent in 0..99) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(6.dp)
                            .background(CornerstoneElevated, RoundedCornerShape(3.dp)),
                    ) {
                        if (percent > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(percent / 100f)
                                    .height(6.dp)
                                    .background(CornerstoneYellow, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                    if (error == null && status.isNotBlank()) {
                        Text(
                            status,
                            color = CornerstoneMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Text(
                    "661 MB total · Source: ${ParakeetModelDownloader.sourceLabel}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    if (error == null) {
                        "Keep typing while this downloads. Recognition runs on your phone; audio is not uploaded. Online polish, if enabled separately, may send the transcript."
                    } else {
                        "Partial download saved. Resume when the connection is ready."
                    },
                    color = CornerstoneMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (error != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { ParakeetModelDownloader.downloadIfNeededAsync(context) },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .fillMaxWidth(0.68f)
                            .padding(top = 10.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CornerstoneYellow,
                            contentColor = CornerstoneBase,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Resume download", fontWeight = FontWeight.Bold)
                    }
                }
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