package org.futo.inputmethod.latin.uix.actions

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Request
import org.json.JSONObject
import org.futo.inputmethod.latin.BuildConfig
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

// Hub status endpoint: same prototype host as AGENT_ENDPOINT (Tailscale mesh,
// cleartext allowed for this host only), but needs no auth token.
private const val AGENT_STATUS_ENDPOINT = "http://100.100.194.62:8877/agents"

/** Live status of one agent as reported by the hub status endpoint. */
internal data class AgentStatus(
    val name: String,
    val role: String,
    val online: Boolean
)

/**
 * GET the hub's agent roster. Returns null on any failure — callers treat null
 * as "status unknown" rather than an error, because the hub is optional
 * context for the UI, not a hard dependency of the feature.
 */
internal fun fetchAgentStatuses(): Map<String, AgentStatus>? {
    val request = Request.Builder().url(AGENT_STATUS_ENDPOINT).get().build()
    return try {
        agentHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val agents = JSONObject(body).optJSONObject("agents") ?: return null
            buildMap {
                for (id in agents.keys()) {
                    val entry = agents.optJSONObject(id) ?: continue
                    put(
                        id, AgentStatus(
                            name = entry.optString("name").ifBlank { agentDisplayName(id) },
                            role = entry.optString("role"),
                            online = entry.optBoolean("online", false)
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w("AgentHub", "agent status fetch failed", e)
        null
    }
}

/** In-window navigation: tiles overview <-> one agent's detail view. */
private sealed class HubScreen {
    object Tiles : HubScreen()
    data class Detail(val agentId: String) : HubScreen()
}

/** State machine for the detail view's request lifecycle. */
private sealed class DetailState {
    object Options : DetailState()
    object Dictating : DetailState()
    object Thinking : DetailState()
    data class Answered(val answer: String) : DetailState()
    data class Failed(val message: String) : DetailState()
    data class Hint(val message: String) : DetailState()
}

/** One interaction option: label shown on the button, prompt prefix sent to the agent. */
private data class HubOption(val label: String, val promptPrefix: String)

private val HUB_OPTIONS = listOf(
    HubOption("Ask about my draft", "Here is my draft. Answer any question in it, or respond to it:\n\n"),
    HubOption("Improve my draft", "Improve this draft, keeping my voice and intent:\n\n"),
    HubOption("Rewrite — professional", "Rewrite this in a professional tone:\n\n"),
    HubOption("Rewrite — casual", "Rewrite this in a casual, friendly tone:\n\n"),
    HubOption("Summarize this", "Summarize this briefly:\n\n")
)

/**
 * The Agent Hub window: a tile per agent, and a detail view per agent with
 * interaction options that act on the current field draft. Opened by tapping
 * the sparkle Agent key (long-press remains the quick-switcher, see AgentAction).
 */
internal class AgentHubWindow(
    private val manager: KeyboardManagerForAction
) : ActionWindow() {
    private val context = manager.getContext()

    // Compose-observable window state. Lives on the window (not in remember{})
    // so non-composable helpers like runOption() can mutate it too.
    private var screen by mutableStateOf<HubScreen>(HubScreen.Tiles)
    private var statuses by mutableStateOf<Map<String, AgentStatus>?>(null)
    private var statusFetchFailed by mutableStateOf(false)
    private var selectedAgent by mutableStateOf(context.getSetting(AGENT_KEY_AGENT))
    private var detailState by mutableStateOf<DetailState>(DetailState.Options)

    // ---- Dictation (Ask by voice) session state ----
    // Window-scoped, not remember{} state: the RecognizerViewListener callbacks
    // that drive it are not composables.
    private var dictationView by mutableStateOf<RecognizerView?>(null)
    private var dictationModelMissing by mutableStateOf(false)
    private var dictationInitJob: Job? = null
    private var dictationAgentId: String? = null

    // One ModelManager per window open — the same role VoiceInputPersistentState's
    // modelManager plays for voice typing. Released in close().
    private val dictationModelManager = ModelManager(context)

    private fun agentName(id: String): String = statuses?.get(id)?.name ?: agentDisplayName(id)

    // Never read or send password field contents — same guard as the old tap behavior.
    private val isPasswordContext: Boolean
        get() = manager.getLatinIMEForDebug().currentInputEditorInfo
            ?.let { isPasswordField(it.inputType) } == true

    private fun agentToken(): String =
        context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }

    @Composable
    override fun windowName(): String = "Agent Hub"

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val scope = rememberCoroutineScope()

        // Fetch agent statuses once per window open, off the UI thread.
        LaunchedEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                val result = fetchAgentStatuses()
                withContext(Dispatchers.Main) {
                    statuses = result
                    statusFetchFailed = result == null
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (val current = screen) {
                HubScreen.Tiles -> HubTiles(
                    onSelect = { id ->
                        // Tapping a tile both picks the agent (persisted, so the
                        // quick-switcher and old flows agree) and opens its detail view.
                        context.setSettingBlocking(AGENT_KEY_AGENT.key, id)
                        selectedAgent = id
                        detailState = DetailState.Options
                        screen = HubScreen.Detail(id)
                    }
                )

                is HubScreen.Detail -> AgentDetail(current.agentId)
            }
        }
    }

    // ---- Tiles view ----

    @Composable
    private fun HubTiles(onSelect: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Agent Hub", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tap an agent to open it. Long-press the sparkle key to quick-switch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            AGENT_CHOICES.forEach { (id, fallbackName) ->
                AgentTile(
                    agentId = id,
                    fallbackName = fallbackName,
                    onClick = { onSelect(id) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun AgentTile(agentId: String, fallbackName: String, onClick: () -> Unit) {
        val status = statuses?.get(agentId)
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                StatusDot(online = status?.online, unknown = statusFetchFailed)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        status?.name ?: fallbackName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        tileSubtitle(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (agentId == selectedAgent) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    private fun tileSubtitle(status: AgentStatus?): String = when {
        statusFetchFailed -> "status unknown"
        status == null -> "Checking status…"
        status.role.isNotBlank() -> status.role
        else -> if (status.online) "Online" else "Offline"
    }

    // ---- Detail view ----

    // ColumnScope extension so the Answered branch can weight() its scroll box.
    @Composable
    private fun ColumnScope.AgentDetail(agentId: String) {
        val name = agentName(agentId)
        val status = statuses?.get(agentId)

        TextButton(onClick = {
            teardownDictation()
            detailState = DetailState.Options
            screen = HubScreen.Tiles
        }) {
            Text("← Back")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(online = status?.online, unknown = statusFetchFailed)
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            detailSubtitle(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        when (val state = detailState) {
            DetailState.Options -> DetailOptions(agentId)
            DetailState.Dictating -> DictationPanel(agentId)
            DetailState.Thinking -> Text(
                "⏳ $name is thinking…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp)
            )

            is DetailState.Answered -> AnswerView(name, state.answer)
            is DetailState.Failed -> MessageWithBack(state.message)
            is DetailState.Hint -> MessageWithBack(state.message)
        }
    }

    private fun detailSubtitle(status: AgentStatus?): String {
        val statusText = when {
            statusFetchFailed -> "status unknown"
            status == null -> "checking status…"
            status.online -> "Online"
            else -> "Offline"
        }
        return listOfNotNull(
            statusText,
            status?.role?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
    }

    @Composable
    private fun DetailOptions(agentId: String) {
        val passwordGuard = isPasswordContext
        val tokenMissing = agentToken().isBlank()

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // Voice first: the spoken text is sent as-is (no prefix, no draft needed).
            OutlinedButton(
                onClick = { startDictation(agentId) },
                enabled = !passwordGuard && !tokenMissing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🎤 Ask by voice")
            }
            Spacer(modifier = Modifier.height(8.dp))

            HUB_OPTIONS.forEach { option ->
                OutlinedButton(
                    onClick = { runOption(agentId, option) },
                    enabled = !passwordGuard && !tokenMissing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(option.label)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (passwordGuard) {
                Text(
                    "${agentName(agentId)} is disabled in password fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (tokenMissing) {
                Text(
                    "Agent token is not set. Configure the 'agent_key_token' setting to use the agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.AnswerView(name: String, answer: String) {
        Text(
            "$name says:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // weight(1f) so a long answer scrolls without pushing the buttons off-screen.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(answer, style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TextButton(onClick = { detailState = DetailState.Options }) {
                Text("Back")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = {
                manager.typeText(answer)
                manager.closeActionWindow()
            }) {
                Text("Insert")
            }
        }
    }

    // In-window dictation panel: embeds the shared RecognizerView, with Cancel
    // pinned at the bottom like the Answered view's buttons.
    @Composable
    private fun ColumnScope.DictationPanel(agentId: String) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                dictationModelMissing -> DictationModelDownloading(onReady = { startDictation(agentId) })
                dictationView != null -> dictationView!!.Content()
                else -> Text(
                    "Starting microphone…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        OutlinedButton(
            onClick = { cancelDictation() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }

    // Same text state CornerstoneModelDownloadWindow shows, inline in the panel.
    @Composable
    private fun DictationModelDownloading(onReady: () -> Unit) {
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
                textAlign = TextAlign.Center
            )
            if (error == null && percent in 0..99) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (status.isNotBlank()) {
                    Text(status, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
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

    @Composable
    private fun MessageWithBack(message: String) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        TextButton(onClick = { detailState = DetailState.Options }) {
            Text("Back")
        }
    }

    // ---- Actions ----

    private fun runOption(agentId: String, option: HubOption) {
        // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
        if (isPasswordContext) return

        // Read-only transaction: capture the draft, then cancel so nothing changes.
        val transaction = manager.createInputTransaction()
        val draft = transaction.textContext.beforeCursor?.toString().orEmpty()
        transaction.cancel()

        if (draft.isBlank()) {
            detailState = DetailState.Hint("Type something in the field first")
            return
        }

        // The composed prompt IS the text — postToAgent takes the full string.
        postPrompt(agentId, option.promptPrefix + draft)
    }

    /**
     * Shared tail of every prompt path (button options and voice dictation):
     * Thinking on the Main dispatcher, POST off it, result back on Main.
     */
    private fun postPrompt(agentId: String, prompt: String) {
        val name = agentName(agentId)

        val token = agentToken()
        if (token.isBlank()) {
            detailState = DetailState.Hint("Agent token is not set. Configure the 'agent_key_token' setting.")
            return
        }

        detailState = DetailState.Thinking
        manager.getLifecycleScope().launch(Dispatchers.IO) {
            val answer = postToAgent(prompt, token, agentId)
            withContext(Dispatchers.Main) {
                detailState = if (answer != null) {
                    DetailState.Answered(answer)
                } else {
                    DetailState.Failed("$name request failed. Check connection and token.")
                }
            }
        }
    }

    // ---- Dictation (Ask by voice) ----

    private fun startDictation(agentId: String) {
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

    private fun cancelDictation() {
        teardownDictation()
        detailState = DetailState.Options
    }

    /** Stop any in-flight session and drop the view. Safe to call when idle. */
    private fun teardownDictation() {
        dictationInitJob?.cancel()
        dictationInitJob = null
        dictationView?.cancel()
        dictationView = null
        dictationModelMissing = false
    }

    // Mirrors VoiceInputActionWindow.loadSettings(), minus sound feedback and the
    // personal-dictionary glossary — that path needs a UserDictionaryObserver that
    // the voice-typing action already owns, and a second one would double-register.
    private fun dictationSettings(model: ModelLoader): RecognizerViewSettings {
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

    private val dictationListener = object : RecognizerViewListener {
        override fun cancelled() {
            // Covers both our Cancel button (its cancel() round-trips through here)
            // and recognizer-internal cancels: nothing was captured, so the only
            // sane place to be is back at the options.
            manager.getLifecycleScope().launch(Dispatchers.Main) {
                if (detailState == DetailState.Dictating) detailState = DetailState.Options
            }
        }

        override fun recordingStarted(device: MicrophoneDeviceState) {
            // No-op: sound and bluetooth-preference side effects stay with voice typing.
        }

        override fun finished(result: String) {
            manager.getLifecycleScope().launch(Dispatchers.Main) {
                val agentId = dictationAgentId ?: return@launch
                teardownDictation()
                if (result.isBlank()) {
                    detailState = DetailState.Hint("Didn't catch that — try again.")
                    return@launch
                }
                // The spoken text IS the question — no prefix, no draft.
                postPrompt(agentId, result)
            }
        }

        override fun partialResult(result: String) {
            // No-op: shouldShowInlinePartialResult is false; the panel shows
            // nothing until the final transcript arrives.
        }

        override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
            // Same as VoiceInputActionWindow: we show no modal of our own, so the
            // recognizer falls back to its PermissionError view inside the panel.
            return false
        }

        override fun openSettings() {
            SettingsActivity.openToNavDest(context, "languages")
        }
    }

    override fun close(): CloseResult {
        teardownDictation()
        runBlocking { dictationModelManager.cleanUp() }
        return CloseResult.Default
    }

    // ---- Shared bits ----

    @Composable
    private fun StatusDot(online: Boolean?, unknown: Boolean) {
        // Theme colors only: primary = online, error = offline, outline = unknown.
        val color = when {
            unknown -> MaterialTheme.colorScheme.outline
            online == true -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
