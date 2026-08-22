package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import android.view.KeyEvent
import org.futo.voiceinput.shared.BUILTIN_PARAKEET_MODEL
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.ggml.ParakeetModelDownloader
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale


    // Same text state CornerstoneModelDownloadWindow shows, inline in the panel.
    @Composable
    internal fun AgentHubWindow.DictationModelDownloading(onReady: () -> Unit) {
        val percent = ParakeetModelDownloader.progressPercent.intValue
        val status = ParakeetModelDownloader.progressText.value
        val error = ParakeetModelDownloader.lastError.value

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    error != null -> "Voice model download failed: $error\nCancel and try again to retry."
                    percent >= 100 -> "Voice model ready!"
                    percent >= 0 -> "Downloading voice model… $percent%"
                    else -> "Preparing voice model download…"
                },
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (error == null && percent in 0..99) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (status.isNotBlank()) {
                    Text(
                        status,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Once the download lands, swap straight into the recognizer — no
        // close/reopen like the standalone voice window needs. Keyed on the
        // states so a landed-but-not-on-disk-yet model doesn't loop retries.
        LaunchedEffect(percent, error) {
            if (error == null && percent >= 100) onReady()
        }
    }
    // ---- Dictation (Ask by voice) ----

    internal fun AgentHubWindow.startDictation(agentId: String) {
        teardownDictation()
        dictationAgentId = agentId

        // Same model lookup as VoiceInputAction: this is what routes dictation to
        // the built-in Parakeet model, and kicks off its one-time download.
        val locale = manager.getActiveLocales().firstOrNull() ?: Locale.ROOT
        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(context, locale, triggerDownload = true)
        when {
            model == null -> {
                detailState = DetailState.Hint(
                    "No voice input model is available for ${locale.getDisplayName(locale)}."
                )
                return
            }
            model === BUILTIN_PARAKEET_MODEL && !model.exists(context) -> {
                // The download was started by the lookup above; show the same
                // "downloading" text state as CornerstoneModelDownloadWindow.
                dictationModelMissing = true
                detailState = DetailState.Dictating
                return
            }
        }

        detailState = DetailState.Dictating
        // Construction touches model metadata — off the UI thread, exactly like
        // VoiceInputActionWindow's initJob.
        dictationInitJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
            yield()
            val settings = dictationSettings(model)
            yield()
            val view = try {
                RecognizerView(
                    context = context,
                    listener = dictationListener,
                    settings = settings,
                    lifecycleScope = manager.getLifecycleScope(),
                    modelManager = dictationModelManager
                )
            } catch (e: ModelDoesNotExistException) {
                dictationModelMissing = true
                return@launch
            }

            // teardownDictation() may have cancelled us while the recognizer was
            // being built; don't hand the stale view to the panel.
            if (!isActive) {
                view.cancel()
                return@launch
            }

            dictationView = view
            view.reset()
            view.start()
        }
    }
    internal fun AgentHubWindow.cancelDictation() {
        teardownDictation()
        detailState = DetailState.Options
    }

    /** Stop any in-flight session and drop the view. Safe to call when idle. */
    internal fun AgentHubWindow.teardownDictation() {
        dictationInitJob?.cancel()
        dictationInitJob = null
        dictationView?.cancel()
        dictationView = null
        dictationModelMissing = false
    }
    // Mirrors VoiceInputActionWindow.loadSettings(), minus sound feedback and the
    // personal-dictionary glossary — that path needs a UserDictionaryObserver that
    // the voice-typing action already owns, and a second one would double-register.
    internal fun AgentHubWindow.dictationSettings(model: ModelLoader): RecognizerViewSettings {
        val allowedLanguages = manager.getActiveLocales()
            .mapNotNull { getLanguageFromWhisperString(it.language) }
            .toSet()

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = false,
            shouldAnimateBubble = context.getSetting(ANIMATE_BUBBLE),
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = model,
                languageSpecificModels = emptyMap()
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = emptyList(),
                languages = allowedLanguages,
                suppressSymbols = context.getSetting(DISALLOW_SYMBOLS)
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = context.getSetting(PREFER_BLUETOOTH),
                requestAudioFocus = context.getSetting(AUDIO_FOCUS),
                canExpandSpace = context.getSetting(CAN_EXPAND_SPACE),
                useVADAutoStop = context.getSetting(USE_VAD_AUTOSTOP)
            )
        )
    }

