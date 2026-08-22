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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

// Hub status endpoint: same prototype host as DEFAULT_AGENT_ENDPOINT (Tailscale mesh,
// cleartext allowed for this host only), but needs no auth token.
private val AGENT_STATUS_ENDPOINT = "${BuildConfig.AGENT_KEY_ENDPOINT.removeSuffix("/agentkey")}/agents"

// One-time "what is this" card on first hub open.
val HUB_ONBOARDED = SettingsKey(
    key = booleanPreferencesKey("hub_onboarded"),
    default = false
)

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

// ---- Short agent memory ----

/**
 * One completed exchange with an agent, kept so follow-ups ("make it shorter")
 * have context. The label records which operation produced the answer.
 */
internal data class AgentExchange(
    val label: String,
    val draftSummary: String,
    val answerSummary: String
)

/**
 * Short per-agent memory for the prototype: the last few exchanges, held in
 * process memory keyed by agent id. No persistence — user text never hits
 * disk, and a restarted keyboard process simply starts blank.
 *
 * Behavior needed: FIFO with a hard size bound → one ArrayDeque per agent,
 * evicting the oldest entry in O(1) when full. All methods are @Synchronized
 * because prompt composition reads on Dispatchers.IO while record() writes on
 * the main thread after a request completes.
 */
internal object AgentMemory {
    private const val MAX_EXCHANGES = 6

    // Truncations keep the composed prompt compact: summaries orient the agent,
    // they don't need to carry the full text again.
    private const val DRAFT_SUMMARY_CHARS = 80
    private const val ANSWER_SUMMARY_CHARS = 300

    private val exchanges = mutableMapOf<String, ArrayDeque<AgentExchange>>()

    // Full text of each agent's most recent answer — the truncated summaries in
    // `exchanges` orient the model, this is what "Copy last answer" hands out.
    private val lastAnswers = mutableMapOf<String, String>()

    /** Store a successful exchange, evicting the oldest once past the bound. */
    @Synchronized
    fun record(agentId: String, label: String, userText: String, answer: String) {
        val queue = exchanges.getOrPut(agentId) { ArrayDeque() }
        queue.addLast(
            AgentExchange(
                label = label,
                draftSummary = userText.take(DRAFT_SUMMARY_CHARS),
                answerSummary = answer.take(ANSWER_SUMMARY_CHARS)
            )
        )
        while (queue.size > MAX_EXCHANGES) queue.removeFirst()
        lastAnswers[agentId] = answer
    }

    /** The agent's most recent full answer, or null when it hasn't answered yet. */
    @Synchronized
    fun lastAnswer(agentId: String): String? = lastAnswers[agentId]

    /** How many exchanges are remembered for this agent (0 when none). */
    @Synchronized
    fun count(agentId: String): Int = exchanges[agentId]?.size ?: 0

    /**
     * Compact "Recent conversation:" block to prepend to a prompt, oldest
     * first, or null when this agent has no history (nothing to prepend).
     */
    @Synchronized
    fun contextBlock(agentId: String): String? {
        val queue = exchanges[agentId]?.takeIf { it.isNotEmpty() } ?: return null
        val lines = queue.mapIndexed { index, exchange ->
            "${index + 1}. [${exchange.label}] draft: \"${exchange.draftSummary}\" -> you: \"${exchange.answerSummary}\""
        }
        return "Recent conversation:\n" + lines.joinToString("\n")
    }
}

/** In-window navigation: tiles overview <-> one agent's detail view <-> add-agent form. */
private sealed class HubScreen {
    object Tiles : HubScreen()
    data class Detail(val agentId: String) : HubScreen()
    object AddAgent : HubScreen()
}

/** State machine for the detail view's request lifecycle. */
private sealed class DetailState {
    /** The action rows / whole-field toggle (also the post-back-to state). */
    object Options : DetailState()

    /**
     * Conversation mode home: the persistent bubble thread plus the pinned
     * follow-up row (text + send + voice). Every successful request lands here.
     */
    object Thread : DetailState()
    object Dictating : DetailState()
    object Thinking : DetailState()
    data class Failed(val message: String) : DetailState()
    data class Hint(val message: String) : DetailState()
}

/**
 * Everything postPrompt needs to run one agent request and remember it
 * afterwards. Grouped into an object because runOption, dictation, and
 * Regenerate all build the same bundle — five loose parameters would drift.
 */
private data class AgentRequest(
    val agentId: String,
    val label: String,         // option label, or "Ask by voice" for dictation
    val promptPrefix: String,  // "" for dictation: the spoken text is the whole prompt
    val userText: String,      // captured draft, or the spoken question
    val draftDeleteCount: Int, // code points to delete for Replace; 0 = no draft captured
    val wholeField: Boolean = false // userText is the entire field, not just beforeCursor
)

/** One interaction option: label shown on the button, prompt prefix sent to the agent. */
private data class HubOption(val label: String, val promptPrefix: String)

private val HUB_OPTIONS = listOf(
    HubOption("Ask about my draft", "Here is my draft. Answer any question in it, or respond to it:\n\n"),
    HubOption("Improve my draft", "Improve this draft, keeping my voice and intent:\n\n"),
    HubOption("Rewrite — professional", "Rewrite this in a professional tone:\n\n"),
    HubOption("Rewrite — casual", "Rewrite this in a casual, friendly tone:\n\n"),
    HubOption("Summarize this", "Summarize this briefly:\n\n"),
    HubOption("Draft a reply", "Draft a reply to this message in my voice, returning only the reply:\n\n"),
    HubOption("Continue writing", "Continue this text seamlessly for 1-3 sentences, returning only the continuation:\n\n")
)

// "Translate…" opens an inline language input instead of firing immediately;
// the composed prefix is built once the language is entered.
internal const val TRANSLATE_LABEL = "Translate…"
private fun translatePrefix(language: String) =
    "Translate this to $language, returning only the translation:\n\n"

/**
 * One visible turn in an agent's conversation thread. Kept full-text so the
 * follow-up prompt can carry real context (the summaries in AgentMemory are
 * orientation only). role: true = user, false = agent.
 */
internal data class ThreadTurn(val fromUser: Boolean, val text: String, val label: String)

/**
 * Window-lifetime per-agent conversation threads. Back-navigation keeps the
 * thread; process death clears it (acceptable for the prototype — user text
 * never hits disk). Hard cap bounds prompt size.
 */
internal object ConversationThreads {
    private const val MAX_TURNS = 40 // 20 exchanges
    private val threads = mutableMapOf<String, ArrayDeque<ThreadTurn>>()

    @Synchronized
    fun add(agentId: String, turn: ThreadTurn) {
        val queue = threads.getOrPut(agentId) { ArrayDeque() }
        queue.addLast(turn)
        while (queue.size > MAX_TURNS) queue.removeFirst()
    }

    @Synchronized
    fun replaceLastAgentTurn(agentId: String, text: String): Boolean {
        val queue = threads[agentId] ?: return false
        for (index in queue.indices.reversed()) {
            if (!queue[index].fromUser) {
                queue[index] = queue[index].copy(text = text)
                return true
            }
        }
        return false
    }

    @Synchronized
    fun turns(agentId: String): List<ThreadTurn> =
        threads[agentId]?.toList() ?: emptyList()

    /** Remove the newest turn (used when a request fails before answering). */
    @Synchronized
    fun dropLast(agentId: String) {
        threads[agentId]?.removeLast()
    }

    /** Compact last-N transcript for the follow-up prompt. Null when empty. */
    @Synchronized
    fun contextBlock(agentId: String, maxTurns: Int = 8): String? {
        val queue = threads[agentId]?.takeIf { it.isNotEmpty() } ?: return null
        val recent = queue.takeLast(maxTurns)
        val lines = recent.map { turn ->
            val speaker = if (turn.fromUser) "User" else "You"
            "$speaker: \"${turn.text.take(300)}\""
        }
        return "Conversation so far:\n" + lines.joinToString("\n")
    }
}

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

    // Whole-field mode: hub options send the entire field, not just the draft
    // before the cursor (e.g. "summarize this email I'm reading").
    private var wholeField by mutableStateOf(context.getSetting(AGENT_WHOLE_FIELD))

    // Wave 5 (Glamour): custom agents, one-time onboarding card, delete
    // confirmation, and the flashlight toggle's on/off state.
    private var customAgents by mutableStateOf(loadCustomAgents(context))
    private var hubOnboarded by mutableStateOf(context.getSetting(HUB_ONBOARDED))
    private var pendingDeleteAgent by mutableStateOf<CustomAgent?>(null)
    private var torchOn by mutableStateOf(false)

    // v2 conversation mode: bump to recompose the thread; lastAnswerRequest keeps
    // the most recent request for Replace/Regenerate in the Thread view.
    private var threadVersion by mutableStateOf(0)
    private var lastAnswerRequest by mutableStateOf<AgentRequest?>(null)

    // Translate… inline language input state (detail-view local).
    private var translateLanguage by mutableStateOf("")

    // Follow-up input text + focus owner flag ("text" | "translate" | null).
    private var followUpText by mutableStateOf("")
    private var translateText by mutableStateOf("")

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

    private fun agentName(id: String): String =
        statuses?.get(id)?.name
            ?: customAgents.firstOrNull { it.id == id }?.name
            ?: agentDisplayName(id)

    private fun customAgent(id: String): CustomAgent? = customAgents.firstOrNull { it.id == id }

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

    // ---- Tiles view ----

    @Composable
    private fun HubTiles(onSelect: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "Agent Hub",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Tap an agent to open it. Long-press the sparkle key to quick-switch.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            if (!hubOnboarded) {
                OnboardingCard(onDismiss = {
                    context.setSettingBlocking(HUB_ONBOARDED.key, true)
                    hubOnboarded = true
                })
                Spacer(modifier = Modifier.height(12.dp))
            }

            AGENT_CHOICES.forEach { (id, fallbackName) ->
                AgentTile(
                    agentId = id,
                    fallbackName = fallbackName,
                    onClick = { onSelect(id) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            customAgents.forEach { agent ->
                CustomAgentTile(
                    agent = agent,
                    onClick = { onSelect(agent.id) },
                    onLongClick = { pendingDeleteAgent = agent }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            AddAgentTile(onClick = { screen = HubScreen.AddAgent })

            pendingDeleteAgent?.let { agent ->
                Spacer(modifier = Modifier.height(12.dp))
                DeleteConfirmCard(agent)
            }
        }
    }

    /** One-time explainer. "Got it" persists HUB_ONBOARDED so it never returns. */
    @Composable
    private fun OnboardingCard(onDismiss: () -> Unit) {
        HubCard(accentColor = MaterialTheme.colorScheme.primary) {
            Text(
                "Welcome to the Agent Hub",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Each tile is an AI agent you can talk to from any text field. " +
                    "Open one, then ask it about your draft, have it rewrite or " +
                    "summarize, or dictate a question by voice. Add your own agent " +
                    "with the + tile below.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                HubChip(text = "Got it", onClick = onDismiss)
            }
        }
    }

    @Composable
    private fun AddAgentTile(onClick: () -> Unit) {
        // outlineVariant border approximates the dashed "empty slot" look.
        HubCard(onClick = onClick) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                Text(
                    "Add agent",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Your own agent: name, role, and system prompt.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    /** Confirm card for a long-pressed custom tile. Built-in agents can't be deleted. */
    @Composable
    private fun DeleteConfirmCard(agent: CustomAgent) {
        HubCard {
            Text(
                "Delete ${agent.name}?",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Its conversation memory goes with it. This can't be undone.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                HubChip(text = "Cancel", onClick = { pendingDeleteAgent = null })
                Spacer(modifier = Modifier.width(8.dp))
                HubChip(
                    text = "Delete",
                    onClick = { deleteCustomAgent(agent) },
                    textColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    private fun deleteCustomAgent(agent: CustomAgent) {
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

    @Composable
    private fun CustomAgentTile(agent: CustomAgent, onClick: () -> Unit, onLongClick: () -> Unit) {
        val selected = agent.id == selectedAgent
        HubCard(
            onClick = onClick,
            onLongClick = onLongClick,
            borderColor = if (selected) MaterialTheme.colorScheme.primary else null,
            borderWidth = if (selected) 1.5.dp else 1.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HubMonogram(name = agent.name, active = selected)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        agent.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        listOfNotNull(
                            agent.role.ifBlank { null },
                            "custom"
                        ).joinToString(" • "),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Custom agents aren't on the hub roster, so their reachability is
                // genuinely unknown — the neutral dot says so instead of guessing.
                StatusDot(online = null, unknown = true)
                Spacer(modifier = Modifier.width(8.dp))
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
            }
        }
    }

    @Composable
    private fun AgentTile(agentId: String, fallbackName: String, onClick: () -> Unit) {
        val status = statuses?.get(agentId)
        val selected = agentId == selectedAgent
        // The selected tile's primary ring is the selection signal — no checkmark.
        HubCard(
            onClick = onClick,
            borderColor = if (selected) MaterialTheme.colorScheme.primary else null,
            borderWidth = if (selected) 1.5.dp else 1.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HubMonogram(
                    name = status?.name ?: fallbackName,
                    active = selected || status?.online == true
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        status?.name ?: fallbackName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        tileSubtitle(status),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusDot(online = status?.online, unknown = statusFetchFailed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
            }
        }
    }

    private fun tileSubtitle(status: AgentStatus?): String = when {
        statusFetchFailed -> "status unknown"
        status == null -> "Checking status…"
        status.role.isNotBlank() -> status.role
        else -> if (status.online) "Online" else "Offline"
    }

    // ---- Add-agent form ----

    /**
     * In-hub form for defining a custom agent. Field state is remember{}-local
     * (nothing outside the form needs it); validation errors render inline
     * under the Save button.
     */
    @Composable
    private fun AddAgentForm() {
        var name by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("") }
        var systemPrompt by remember { mutableStateOf("") }
        var endpoint by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TextButton(onClick = { screen = HubScreen.Tiles }) {
                Text("← Back")
            }
            Text("Add agent", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = role,
                onValueChange = { role = it },
                label = { Text("Role (e.g. \"Coding helper\")") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                placeholder = { Text("Instructions sent before every request") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Endpoint URL (optional)") },
                placeholder = { Text("Default: the shared hub") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Uses the same Bearer token as the built-in agents. Leave blank for " +
                    "the shared hub. https recommended — cleartext http only reaches " +
                    "hosts the keyboard's network config allows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { screen = HubScreen.Tiles }) { Text("Cancel") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    val validation = validateNewAgent(name, customAgents)
                    val id = validation.id
                    if (id == null) {
                        error = validation.error
                    } else {
                        saveCustomAgents(
                            context,
                            customAgents + CustomAgent(
                                id = id,
                                name = name.trim(),
                                role = role.trim(),
                                systemPrompt = systemPrompt.trim(),
                                endpoint = endpoint.trim()
                            )
                        )
                        customAgents = loadCustomAgents(context)
                        screen = HubScreen.Tiles
                    }
                }) {
                    Text("Save")
                }
            }
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

    @Composable
    private fun DetailHeader(
        name: String,
        status: AgentStatus?,
        agentId: String,
        isCustom: Boolean
    ) {
        // App-bar row: back chevron (48.dp touch target) + monogram + name with
        // an inline status dot and subtitle. Back behavior is identical to the
        // old "← Back" button: teardown dictation, reset state, go to Tiles.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        teardownDictation()
                        detailState = DetailState.Options
                        screen = HubScreen.Tiles
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
            }
            HubMonogram(
                name = name,
                // "active" means reachable, not merely open — every Detail view
                // is the selected agent, so selectedAgent can't be the signal.
                active = status?.online == true,
                size = 32.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Custom agents aren't on the hub roster — neutral dot, not a guess.
                    StatusDot(
                        online = status?.online,
                        unknown = statusFetchFailed || isCustom && status == null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // AgentMemory is not Compose state, but every record() is immediately
                    // followed by a detailState change, which recomposes this header — so
                    // the count read here is always fresh by the time it could go stale.
                    val memoryCount = AgentMemory.count(agentId)
                    Text(
                        detailSubtitle(agentId, status) +
                            if (memoryCount > 0) {
                                " · $memoryCount exchange" + if (memoryCount == 1) "" else "s"
                            } else {
                                ""
                            },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    /** Pulsing monogram + typing dots + amber progress while a request is in flight. */
    @Composable
    private fun ThinkingView(name: String) {
        val transition = rememberInfiniteTransition(label = "hubThinking")
        val pulse by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hubThinkingAlpha"
        )
        // Three-dot typing indicator, staggered by phase offsets.
        val dotsTransition = rememberInfiniteTransition(label = "hubDots")
        val dotPhase = listOf(0, 200, 400)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
        ) {
            HubMonogram(name = name, active = true, modifier = Modifier.alpha(pulse))
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$name is thinking",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                dotPhase.forEach { delayMs ->
                    val dotAlpha by dotsTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, delayMillis = delayMs, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "hubDot$delayMs"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(5.dp)
                            .alpha(dotAlpha)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.width(120.dp)
            )
        }
    }

    private fun detailSubtitle(agentId: String, status: AgentStatus?): String {
        val custom = customAgent(agentId)
        if (custom != null) {
            // No roster status for customs — describe the agent itself instead.
            return listOfNotNull(
                custom.role.ifBlank { null },
                "custom agent",
                if (custom.endpoint.isBlank()) null else "own endpoint"
            ).joinToString(" • ")
        }
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
            HubCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Use whole field",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Send the entire field, not just text before the cursor",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wholeField,
                        onCheckedChange = {
                            wholeField = it
                            context.setSettingBlocking(AGENT_WHOLE_FIELD.key, it)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Voice first: the spoken text is sent as-is (no prefix, no draft needed).
            HubPrimaryPill(
                text = "Ask by voice",
                onClick = { startDictation(agentId) },
                enabled = !passwordGuard && !tokenMissing
            )
            Spacer(modifier = Modifier.height(12.dp))

            HubRowCard(
                rows = HUB_OPTIONS.map { option ->
                    HubRow(
                        label = option.label,
                        enabled = !passwordGuard && !tokenMissing,
                        onClick = { runOption(agentId, option) }
                    )
                }
            )

            if (passwordGuard) {
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(accentColor = MaterialTheme.colorScheme.error) {
                    Text(
                        "${agentName(agentId)} is disabled in password fields.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (tokenMissing) {
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(accentColor = MaterialTheme.colorScheme.error) {
                    Text(
                        "Agent token is not set. Configure the 'agent_key_token' setting to use the agent.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            DeviceActionsSection(agentId)
        }
    }

    /**
     * Real device control (v2): sound/media via AudioManager, brightness via
     * Settings.System (one-time WRITE_SETTINGS grant walked through on first
     * use), vibrate, flashlight with true state tracking, alarm. No Wi-Fi row:
     * silent third-party toggles have been impossible since Android 10.
     */
    @Composable
    private fun DeviceActionsSection(agentId: String) {
        val lastAnswer = AgentMemory.lastAnswer(agentId)

        Spacer(modifier = Modifier.height(20.dp))
        HubSectionLabel("DEVICE CONTROL")
        Text(
            "Real controls for this phone. Brightness asks for a one-time settings grant.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        HubSectionLabel("SOUND & MEDIA")
        Spacer(modifier = Modifier.height(6.dp))
        HubRowCard(
            rows = listOf(
                HubRow("Volume up") { deviceVolume(context, raise = true) },
                HubRow("Volume down") { deviceVolume(context, raise = false) },
                HubRow("Mute / unmute media") { deviceMuteToggle(context) },
                HubRow("Play / pause") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                HubRow("Next track") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) },
                HubRow("Previous track") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))
        HubSectionLabel("DEVICE")
        Spacer(modifier = Modifier.height(6.dp))
        HubRowCard(
            rows = listOf(
                HubRow("Brightness up") {
                    val result = adjustBrightness(context, +30)
                    manager.announce(
                        when (result) {
                            is BrightnessResult.Set -> "Brightness ${result.level}"
                            BrightnessResult.NeedsGrant -> "Grant modify-settings once, then it works"
                            BrightnessResult.Unavailable -> "Brightness unavailable on this device"
                        }
                    )
                },
                HubRow("Brightness down") {
                    val result = adjustBrightness(context, -30)
                    manager.announce(
                        when (result) {
                            is BrightnessResult.Set -> "Brightness ${result.level}"
                            BrightnessResult.NeedsGrant -> "Opening modify-settings grant"
                            BrightnessResult.Unavailable -> "Brightness unavailable on this device"
                        }
                    )
                },
                HubRow("Vibrate") { deviceVibrate(context) },
                HubRow(if (torchOn) "Flashlight (on)" else "Flashlight") {
                    if (setTorch(context, !torchOn)) {
                        torchOn = !torchOn
                        manager.announce(if (torchOn) "Flashlight on" else "Flashlight off")
                    } else {
                        manager.announce("Flashlight unavailable or camera busy")
                    }
                },
                HubRow("Set alarm") {
                    if (!openAlarmSetter(context)) manager.announce("No clock app on this device")
                }
            )
        )

        // Copy-last-answer stays under the agent, not the device.
        val copyEnabled = lastAnswer != null
        if (copyEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HubChip(text = "Copy last answer") { lastAnswer?.let { copyAnswer(it) } }
            }
        }
    }

    // ---- Conversation mode: persistent bubble thread + follow-up row ----

    @Composable
    private fun ThreadView(agentId: String) {
        val passwordGuard = isPasswordContext
        val tokenMissing = agentToken().isBlank()
        val canAct = !passwordGuard && !tokenMissing

        // Reading ConversationThreads through threadVersion makes every add/replace
        // in postPrompt recompose this view.
        val turns = remember(threadVersion, agentId) { ConversationThreads.turns(agentId) }
        val lastRequest = lastAnswerRequest

        Column(modifier = Modifier.fillMaxWidth()) {
            HubRowCard(
                rows = listOf(
                    HubRow("Actions & drafts", enabled = true) { detailState = DetailState.Options }
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (turns.isEmpty()) {
                HubCard(accentColor = MaterialTheme.colorScheme.primary) {
                    Text(
                        "Start the conversation — run an action, ask by voice, or type a follow-up below. " +
                            "The thread stays with ${agentName(agentId)} while the keyboard is open.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // Bounded scroll box — weight() collapses under wrap measurement.
                Box(
                    modifier = Modifier
                        .heightIn(min = 160.dp, max = 300.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        turns.forEach { turn ->
                            if (turn.fromUser) {
                                UserBubble(turn)
                            } else {
                                AgentBubble(agentId, turn)
                            }
                        }
                    }
                }
            }

            // Pinned follow-up row: never scrolls away.
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = followUpText,
                    onValueChange = { followUpText = it },
                    placeholder = { Text("Ask a follow-up…") },
                    singleLine = true,
                    enabled = canAct,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (canAct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = canAct) { sendFollowUp(agentId) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("↑", color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                HubChip(text = "Voice", onClick = { startDictation(agentId) }, enabled = canAct)
            }

            // Last-answer actions operate on the most recent agent turn only.
            val lastAgentText = turns.lastOrNull { !it.fromUser }?.text
            if (lastAgentText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    if ((lastRequest?.draftDeleteCount ?: 0) > 0) {
                        HubChip(text = "Replace draft") {
                            replaceDraftWith(lastAgentText, lastRequest!!.draftDeleteCount)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    HubChip(text = "Insert") { insertAnswer(lastAgentText) }
                    Spacer(modifier = Modifier.width(8.dp))
                    HubChip(text = "Copy") { copyAnswer(lastAgentText) }
                    Spacer(modifier = Modifier.width(8.dp))
                    HubChip(text = "Regenerate", enabled = canAct) { regenerateLast(lastRequest) }
                }
            }

            if (passwordGuard) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${agentName(agentId)} is disabled in password fields.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (tokenMissing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Agent token is not set. Configure the 'agent_key_token' setting to use the agent.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            TranslateCard(agentId, canAct)
        }
    }

    @Composable
    private fun UserBubble(turn: ThreadTurn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    turn.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (turn.label.isNotBlank()) {
            Text(
                turn.label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }
    }

    @Composable
    private fun AgentBubble(agentId: String, turn: ThreadTurn) {
        Row(verticalAlignment = Alignment.Top) {
            HubMonogram(name = agentName(agentId), active = true, size = 28.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    turn.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    // Explicit onSurface: default content color is not guaranteed
                    // light on every theme path (invisible-text bug).
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }

    /** Inline language input for Translate…; Go fires as soon as a language is set. */
    @Composable
    private fun TranslateCard(agentId: String, canAct: Boolean) {
        Spacer(modifier = Modifier.height(16.dp))
        HubSectionLabel("TRANSLATE")
        Spacer(modifier = Modifier.height(6.dp))
        HubCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = translateText,
                    onValueChange = { translateText = it },
                    placeholder = { Text("e.g. Spanish") },
                    singleLine = true,
                    enabled = canAct,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                HubChip(
                    text = "Go",
                    enabled = canAct && translateText.isNotBlank(),
                    onClick = {
                        val language = translateText.trim()
                        if (language.isNotEmpty()) runTranslate(agentId, language)
                    }
                )
            }
            if (!canAct) {
                Text(
                    "Translation is disabled here (password field or missing token).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    // ---- Thread actions ----

    private fun sendFollowUp(agentId: String) {
        val text = followUpText.trim()
        if (text.isEmpty()) return
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

    private fun regenerateLast(lastRequest: AgentRequest?) {
        if (lastRequest != null) postPrompt(lastRequest, recordUser = false)
    }

    private fun runTranslate(agentId: String, language: String) {
        translateText = ""
        // Belt-and-braces guard mirroring runOption.
        if (isPasswordContext) return
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
                    dictationView != null -> dictationView!!.Content()
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

    @Composable
    private fun MessageWithBack(message: String, isError: Boolean) {
        HubCard(
            accentColor = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        ) {
            Text(
                message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        HubChip(text = "Back", onClick = { detailState = DetailState.Options })
    }

    // ---- Actions ----

    private fun runOption(agentId: String, option: HubOption) {
        // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
        if (isPasswordContext) return

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
     * turn only on success (a failed request leaves nothing behind). AgentMemory
     * records successful answers exactly as before. Regenerate passes
     * recordUser=false: its user turn is already in the thread, and the new
     * answer REPLACES the old agent bubble instead of duplicating the pair.
     */
    private fun postPrompt(request: AgentRequest, recordUser: Boolean = true) {
        val name = agentName(request.agentId)

        val token = agentToken()
        if (token.isBlank()) {
            detailState = DetailState.Hint("Agent token is not set. Configure the 'agent_key_token' setting.")
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
        manager.getLifecycleScope().launch(Dispatchers.IO) {
            // The composed prompt IS the text — postToAgent takes the full string.
            // A custom agent's system prompt goes first (it's the standing
            // instruction), then the live thread so follow-ups continue the real
            // conversation, then AgentMemory for older summary context.
            val custom = customAgent(request.agentId)
            val threadContext = ConversationThreads.contextBlock(request.agentId)
            val memoryContext = AgentMemory.contextBlock(request.agentId)
            val prompt = buildString {
                if (custom != null && custom.systemPrompt.isNotBlank()) {
                    append(custom.systemPrompt)
                    append("\n\n")
                }
                if (memoryContext != null) {
                    append(memoryContext)
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
            val answer = postToAgent(prompt, token, request.agentId, endpoint)
            Log.i("AgentHub", "answer from ${request.agentId}: len=${answer?.length} head=${answer?.take(60)}")
            withContext(Dispatchers.Main) {
                if (answer != null) {
                    AgentMemory.record(request.agentId, request.label, request.userText, answer)
                    if (recordUser.not()) {
                        // Regenerate: swap the previous agent bubble for the new answer.
                        ConversationThreads.replaceLastAgentTurn(request.agentId, answer)
                    } else {
                        ConversationThreads.add(
                            request.agentId,
                            ThreadTurn(fromUser = false, text = answer, label = request.label)
                        )
                    }
                    threadVersion++
                    lastAnswerRequest = request
                    detailState = DetailState.Thread
                } else {
                    // The pending user turn would dangle with no reply — pull it back out.
                    if (recordUser) ConversationThreads.dropLast(request.agentId)
                    threadVersion++
                    detailState = DetailState.Failed("$name request failed. Check connection and token.")
                }
            }
        }
    }

    private fun insertAnswer(answer: String) {
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
    private fun replaceDraftWith(answer: String, draftDeleteCount: Int) {
        manager.backspace(draftDeleteCount)
        manager.typeText(answer)
        manager.closeActionWindow()
    }

    private fun copyAnswer(answer: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent answer", answer))
        // Stay in the window — the user may want to Copy and then Insert too.
        // announce() gives confirmation without new UI state.
        manager.announce("Answer copied")
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
        // Never leave the flashlight burning after the keyboard goes away —
        // the hub owns the toggle, so it owns the cleanup.
        if (torchOn) {
            setTorch(context, false)
            torchOn = false
        }
        runBlocking { dictationModelManager.cleanUp() }
        return CloseResult.Default
    }

    // ---- Shared bits ----

    @Composable
    private fun StatusDot(online: Boolean?, unknown: Boolean) {
        // Semantics unchanged: primary = online, error = offline, outline = unknown.
        HubStatusDot(online = online, unknown = unknown)
    }
}
