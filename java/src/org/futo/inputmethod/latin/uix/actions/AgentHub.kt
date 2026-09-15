package org.futo.inputmethod.latin.uix.actions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.RecognizerHolder

/**
 * The Agent Hub window: a tile per agent, and a detail view per agent with
 * interaction options that act on the current field draft. Opened by tapping
 * the sparkle Agent key (long-press remains the quick-switcher, see AgentAction).
 *
 * File map — keep these boundaries. Do not dump screens back into one file.
 *   AgentHub.kt         window shell + detail router
 *   AgentHubActions.kt  post / run / insert / copy
 *   AgentHubUi.kt       shared cards / chips / dots
 *   HubTiles.kt         tile grid
 *   HubAddAgent.kt      add-agent form
 *   HubDetail.kt        header / options / thinking
 *   HubThread.kt        conversation bubbles
 *   Dictation.kt        ask-by-voice
 *   HubState.kt         types
 *   HubRepository.kt    network
 *   DeviceSection.kt    device controls
 */
internal class AgentHubWindow(
    internal val manager: KeyboardManagerForAction
) : ActionWindow() {
    internal val context = manager.getContext()

    // Compose-observable window state. Lives on the window (not in remember{})
    // so non-composable helpers like runOption() can mutate it too.
    internal var screen by mutableStateOf<HubScreen>(HubScreen.Tiles)
    internal var statuses by mutableStateOf<Map<String, AgentStatus>?>(null)
    internal var statusFetchFailed by mutableStateOf(false)
    internal var selectedAgent by mutableStateOf(context.getSetting(AGENT_KEY_AGENT))
    internal var detailState by mutableStateOf<DetailState>(DetailState.Options)

    // Whole-field mode: hub options send the entire field, not just the draft
    // before the cursor (e.g. "summarize this email I'm reading").
    internal var wholeField by mutableStateOf(context.getSetting(AGENT_WHOLE_FIELD))

    // Wave 5 (Glamour): custom agents, one-time onboarding card, delete
    // confirmation, and the flashlight toggle's on/off state.
    internal var customAgents by mutableStateOf(loadCustomAgents(context))
    internal var hubOnboarded by mutableStateOf(context.getSetting(HUB_ONBOARDED))
    internal var pendingDeleteAgent by mutableStateOf<CustomAgent?>(null)
    internal var torchOn by mutableStateOf(false)

    // v2 conversation mode: bump threadVersion to recompose the thread;
    // lastAnswerRequests keeps each agent's most recent request (keyed by
    // agent id) for Replace/Regenerate in the Thread view — per-agent so
    // switching agents never cross-fires another agent's request.
    internal var threadVersion by mutableStateOf(0)
    internal var lastAnswerRequests by mutableStateOf<Map<String, AgentRequest>>(emptyMap())

    // Translate… inline language input state (detail-view local).
    private var translateLanguage by mutableStateOf("")

    // Follow-up input text + focus owner flag ("text" | "translate" | null).
    internal var followUpText by mutableStateOf("")
    internal var translateText by mutableStateOf("")

    // ---- Dictation (Ask by voice) session state ----
    // Window-scoped, not remember{} state: the RecognizerViewListener callbacks
    // that drive it are not composables.
    internal var dictationView by mutableStateOf<RecognizerView?>(null)
    internal var dictationModelMissing by mutableStateOf(false)
    internal var dictationInitJob: Job? = null
    internal var promptJob: Job? = null
    internal var dictationAgentId: String? = null

    // Where cancelInFlightPrompt returns (requests fire from Options or
    // Thread), and which agent's recorded user turn to drop if Cancel lands
    // mid-flight — a cancelled request never answers, so its turn must not
    // dangle in the thread.
    internal var promptReturnState: DetailState = DetailState.Options
    internal var promptRecordedUserTurn: String? = null

    // One ModelManager per window open — the same role VoiceInputPersistentState's
    // modelManager plays for voice typing. Released in close().
    internal val dictationModelManager = RecognizerHolder.get(context)

    internal fun agentName(id: String): String =
        statuses?.get(id)?.name
            ?: customAgents.firstOrNull { it.id == id }?.name
            ?: agentDisplayName(id)

    internal fun customAgent(id: String): CustomAgent? = customAgents.firstOrNull { it.id == id }

    // Never read or send password field contents — same guard as the old tap behavior.
    internal val isPasswordContext: Boolean
        get() = manager.getCurrentEditorInfo()
            ?.let { isPasswordField(it.inputType) } == true

    // Private/incognito fields (browser private mode, lockdown apps) are treated
    // like password fields: the hub must not capture or send their contents.
    internal val isIncognitoContext: Boolean
        get() = manager.isIncognitoContext()

    internal fun agentToken(): String =
        context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }

    @Composable
    override fun windowName(): String = "Agent Hub"

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        // Refresh the roster each time the tiles view is (re)shown: liveness
        // changes while the hub is open, and a stale first fetch must not pin
        // a tile on "checking" forever. Thread load lives inside the IO block
        // so its file I/O stays off the main thread.
        LaunchedEffect(screen) {
            if (screen != HubScreen.Tiles) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                ConversationThreads.load(context)
                val result = fetchAgentStatuses()
                withContext(Dispatchers.Main) {
                    statuses = result
                    statusFetchFailed = result == null
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    // Subtle forward slide-up + fade; the reverse plays on back.
                    (slideInVertically(
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 8 }
                    ) + fadeIn(tween(250))) togetherWith
                        (slideOutVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            targetOffsetY = { -it / 10 }
                        ) + fadeOut(tween(200)))
                },
                label = "hubScreenTransition"
            ) { current ->
                when (current) {
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
                    HubScreen.AddAgent -> AddAgentForm()
                }
            }
        }
    }





    internal fun deleteCustomAgent(agent: CustomAgent) {
        saveCustomAgents(context, customAgents.filterNot { it.id == agent.id })
        customAgents = loadCustomAgents(context)
        pendingDeleteAgent = null
        // If the deleted agent was the selected one, fall back to the default so
        // the quick-switcher and strip flows never point at a ghost id.
        if (selectedAgent == agent.id) {
            context.setSettingBlocking(AGENT_KEY_AGENT.key, AGENT_KEY_AGENT.default)
            selectedAgent = AGENT_KEY_AGENT.default
        }
    }





    // ---- Detail view ----

    @Composable
    private fun ColumnScope.AgentDetail(agentId: String) {
        val name = agentName(agentId)
        val status = statuses?.get(agentId)
        val custom = customAgent(agentId)

        // Single-root wrapper: AnimatedContent places targets in a Box, and this
        // function emits TWO siblings (header + state content) — in a Box they
        // stack on the same origin, which buried the header under the options
        // list (seen on the emulator rig 2026-08-21). The Column restores
        // vertical order. Tiles/AddAgent emit one root each and are unaffected.
        Column(modifier = Modifier.fillMaxWidth()) {
            DetailHeader(name, status, agentId, custom != null)
            when (val state = detailState) {
                DetailState.Options -> DetailOptions(agentId)
                is DetailState.Thread -> ThreadView(agentId)
                DetailState.Device -> DeviceView(agentId)
                DetailState.Dictating -> DictationPanel(agentId)
                DetailState.Thinking -> ThinkingView(name)
                is DetailState.Failed -> MessageWithBack(state.message, isError = true)
                is DetailState.Hint -> MessageWithBack(state.message, isError = false)
            }
        }
    }

    // In-window dictation panel: embeds the shared RecognizerView. Cancel is an
    // UNWEIGHTED sibling below the weighted card: when the IME window
    // wrap-measures this Column, weighted children collapse to zero height — if
    // Cancel lived inside the weight, the whole dictation UI could vanish.
    @Composable
    private fun ColumnScope.DictationPanel(agentId: String) {
        HubCard(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    dictationModelMissing -> DictationModelDownloading(onReady = { startDictation(agentId) })
                    dictationView != null -> dictationView?.Content()
                    else -> Text(
                        "Starting microphone…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HubChip(
            text = "Cancel",
            onClick = { cancelDictation() },
            modifier = Modifier.fillMaxWidth()
        )
    }

    internal val dictationListener = object : RecognizerViewListener {
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
                if (isIncognitoContext) {
                    detailState = DetailState.Hint("Agent disabled in private fields")
                    return@launch
                }
                // The spoken text IS the question — no prefix, no draft, so no
                // Replace (draftDeleteCount = 0 hides the button).
                postPrompt(
                    AgentRequest(
                        agentId = agentId,
                        label = "Ask by voice",
                        promptPrefix = "",
                        userText = result,
                        draftDeleteCount = 0
                    )
                )
            }
        }

        override fun partialResult(result: String) {
            // No-op: dictationSettings() enables SHOW_INLINE_PARTIALS, so the
            // recognizer's own panel renders partials inline — the hub keeps no
            // partial state of its own and reacts only to the final transcript.
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
        promptJob?.cancel()
        promptJob = null
        // Never leave the flashlight burning after the keyboard goes away —
        // the hub owns the toggle, so it owns the cleanup.
        if (torchOn) {
            setTorch(context, false)
            torchOn = false
        }
        dictationModelManager.cancelAll()
        return CloseResult.Default
    }

}
