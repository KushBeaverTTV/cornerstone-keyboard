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
import okhttp3.Request
import org.json.JSONObject
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
import org.futo.voiceinput.shared.whisper.RecognizerHolder
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale



/**
 * The Agent Hub window: a tile per agent, and a detail view per agent with
 * interaction options that act on the current field draft. Opened by tapping
 * the sparkle Agent key (long-press remains the quick-switcher, see AgentAction).
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

    // v2 conversation mode: bump to recompose the thread; lastAnswerRequest keeps
    // the most recent request for Replace/Regenerate in the Thread view.
    internal var threadVersion by mutableStateOf(0)
    internal var lastAnswerRequest by mutableStateOf<AgentRequest?>(null)

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
        val scope = rememberCoroutineScope()

        // Fetch agent statuses once per window open, off the UI thread.
        LaunchedEffect(Unit) {
            ConversationThreads.load(context)
            scope.launch(Dispatchers.IO) {
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
                DetailState.Dictating -> DictationPanel(agentId)
                DetailState.Thinking -> ThinkingView(name)
                is DetailState.Failed -> MessageWithBack(state.message, isError = true)
                is DetailState.Hint -> MessageWithBack(state.message, isError = false)
            }
        }
    }






    // ---- Conversation mode: persistent bubble thread + follow-up row ----





    // ---- Thread actions ----

    internal fun sendFollowUp(agentId: String) {
        val text = followUpText.trim()
        if (text.isEmpty()) return
        if (isIncognitoContext) {
            detailState = DetailState.Hint("Agent disabled in private fields")
            return
        }
        followUpText = ""
        // The spoken/typed question IS the prompt — same contract as Ask by voice.
        postPrompt(
            AgentRequest(
                agentId = agentId,
                label = "Follow-up",
                promptPrefix = "",
                userText = text,
                draftDeleteCount = 0
            )
        )
    }

    internal fun regenerateLast(lastRequest: AgentRequest?) {
        if (lastRequest != null) postPrompt(lastRequest, recordUser = false)
    }

    internal fun runTranslate(agentId: String, language: String) {
        // Belt-and-braces guard mirroring runOption.
        if (isPasswordContext) return
        if (isIncognitoContext) {
            detailState = DetailState.Hint("Agent disabled in private fields")
            return
        }
        translateText = ""
        val transaction = manager.createInputTransaction()
        val beforeCursor = transaction.textContext.beforeCursor?.toString().orEmpty()
        val draft = if (wholeField) {
            beforeCursor + transaction.textContext.afterCursor?.toString().orEmpty()
        } else {
            beforeCursor
        }
        transaction.cancel()

        if (draft.isBlank()) {
            detailState = DetailState.Hint("Type something in the field first")
            return
        }

        postPrompt(
            AgentRequest(
                agentId = agentId,
                label = "$TRANSLATE_LABEL ($language)",
                promptPrefix = translatePrefix(language),
                userText = draft,
                draftDeleteCount = if (wholeField) 0 else draft.codePointCount(0, draft.length),
                wholeField = wholeField
            )
        )
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

    // ---- Actions ----

    internal fun runOption(agentId: String, option: HubOption) {
        // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
        if (isPasswordContext) return
        if (isIncognitoContext) {
            detailState = DetailState.Hint("Agent disabled in private fields")
            return
        }

        // Read-only transaction: capture the draft, then cancel so nothing changes.
        val transaction = manager.createInputTransaction()
        val beforeCursor = transaction.textContext.beforeCursor?.toString().orEmpty()
        val draft = if (wholeField) {
            beforeCursor + transaction.textContext.afterCursor?.toString().orEmpty()
        } else {
            beforeCursor
        }
        transaction.cancel()

        if (draft.isBlank()) {
            detailState = DetailState.Hint("Type something in the field first")
            return
        }

        // Delete count for a later Replace: CODE_DELETE removes one code point
        // per event, so count code points, not UTF-16 units (an emoji is two
        // units). Grapheme clusters (emoji + modifiers) can still drift by one
        // — acceptable approximation for the prototype.
        // Whole-field captures get 0: Replace deletes backward only, so it
        // can't undo text captured after the cursor — hiding the button (same
        // mechanism as dictation) beats a half-correct replace.
        postPrompt(
            AgentRequest(
                agentId = agentId,
                label = if (wholeField) "${option.label} (whole field)" else option.label,
                promptPrefix = option.promptPrefix,
                userText = draft,
                draftDeleteCount = if (wholeField) 0 else draft.codePointCount(0, draft.length),
                wholeField = wholeField
            )
        )
    }

    /**
     * Shared tail of every prompt path (button options, voice dictation,
     * follow-ups, and Regenerate): Thinking on the Main dispatcher, POST off
     * it, result back on Main. The conversation thread gets the user turn up
     * front — so a slow answer never loses what was asked — and the agent's
     * turn on success. Failed requests become an error turn inside the thread
     * with a Retry chip instead of a generic error screen. AgentMemory records
     * successful answers exactly as before. Regenerate passes recordUser=false:
     * its user turn is already in the thread, and the new answer REPLACES the
     * old agent/error bubble instead of duplicating the pair.
     */
    internal fun postPrompt(request: AgentRequest, recordUser: Boolean = true) {
        val name = agentName(request.agentId)

        if (isIncognitoContext) {
            detailState = DetailState.Hint("Agent disabled in private fields")
            return
        }

        val token = agentToken()
        if (token.isBlank()) {
            detailState = DetailState.Hint("Agent token is not set. Open Settings → Agents.")
            return
        }

        if (recordUser) {
            ConversationThreads.add(
                request.agentId,
                ThreadTurn(fromUser = true, text = request.userText, label = request.label)
            )
            threadVersion++
        }

        detailState = DetailState.Thinking
        promptJob?.cancel()
        promptJob = manager.getLifecycleScope().launch(Dispatchers.IO) {
            // The composed prompt IS the text — postToAgent takes the full string.
            // A custom agent's system prompt goes first (it's the standing
            // instruction), then the live thread so follow-ups continue the real
            // conversation, then AgentMemory for older summary context.
            val custom = customAgent(request.agentId)
            val threadContext = ConversationThreads.contextBlock(request.agentId)
            val prompt = buildString {
                if (custom != null && custom.systemPrompt.isNotBlank()) {
                    append(custom.systemPrompt)
                    append("\n\n")
                }
                if (threadContext != null) {
                    append(threadContext)
                    append("\n\nRespond to the latest request.\n\n")
                }
                append(request.promptPrefix)
                // Whole-field captures are labeled so the agent doesn't treat
                // text after the cursor as part of an unfinished draft.
                if (request.wholeField) append("[Full field contents, before and after the cursor]\n")
                append(request.userText)
            }
            val endpoint = custom?.endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_ENDPOINT
            val result = postToAgent(prompt, token, request.agentId, endpoint)
            val answerText = (result as? AgentResult.Success)?.text
            Log.i("AgentHub", "answer from ${request.agentId}: success=${result is AgentResult.Success} head=${answerText?.take(60)}")
            withContext(Dispatchers.Main) {
                when (result) {
                    is AgentResult.Success -> {
                        lastAnswerRequest = request
                        ConversationThreads.persist(context)
                        if (recordUser.not()) {
                            // Regenerate: swap the previous agent/error bubble for the new answer.
                            ConversationThreads.replaceLastAgentTurn(request.agentId, result.text)
                        } else {
                            ConversationThreads.add(
                                request.agentId,
                                ThreadTurn(fromUser = false, text = result.text, label = request.label)
                            )
                        }
                        threadVersion++
                        detailState = DetailState.Thread
                    }
                    else -> {
                        val message = agentResultMessage(result, name)
                        if (recordUser.not()) {
                            // Regenerate/Retry: replace the previous agent/error bubble.
                            ConversationThreads.replaceLastAgentTurn(
                                request.agentId,
                                message,
                                errorResult = result,
                                request = request
                            )
                        } else {
                            ConversationThreads.add(
                                request.agentId,
                                ThreadTurn(
                                    fromUser = false,
                                    text = message,
                                    label = request.label,
                                    errorResult = result,
                                    request = request
                                )
                            )
                        }
                        threadVersion++
                        ConversationThreads.persist(context)
                        detailState = DetailState.Thread
                    }
                }
            }
        }
    }

    internal fun cancelInFlightPrompt() {
        promptJob?.cancel()
        promptJob = null
        detailState = DetailState.Thread
    }

    internal fun insertAnswer(answer: String) {
        manager.typeText(answer)
        manager.closeActionWindow()
    }

    /**
     * Replace draft: delete exactly what the request captured before the
     * cursor, then type the answer in its place. ActionInputTransaction has no
     * replace/setText capability, and KeyboardManagerForAction's only deletion
     * API is backspace(amount) — so delete-then-type is the clean
     * implementation with the APIs that exist.
     *
     * Known limitation (prototype): if the field changed after the request
     * started, backspace removes whatever is before the cursor now. The hub
     * window keeps the keyboard open, so mid-request edits are unlikely.
     */
    internal fun replaceDraftWith(answer: String, draftDeleteCount: Int) {
        val transaction = manager.createInputTransaction()
        val before = transaction.textContext.beforeCursor?.toString().orEmpty()
        transaction.cancel()
        val actual = before.codePointCount(0, before.length)
        if (draftDeleteCount <= 0 || actual < draftDeleteCount) {
            manager.announce("Field changed — inserting instead")
            manager.typeText(answer)
            manager.closeActionWindow()
            return
        }
        manager.backspace(draftDeleteCount)
        manager.typeText(answer)
        manager.closeActionWindow()
    }

    internal fun copyAnswer(answer: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent answer", answer))
        // Stay in the window — the user may want to Copy and then Insert too.
        // announce() gives confirmation without new UI state.
        manager.announce("Answer copied")
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
