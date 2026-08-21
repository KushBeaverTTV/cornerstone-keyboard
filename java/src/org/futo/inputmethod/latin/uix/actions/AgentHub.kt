package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
private const val AGENT_STATUS_ENDPOINT = "http://100.100.194.62:8877/agents"

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
    object Options : DetailState()
    object Dictating : DetailState()
    object Thinking : DetailState()

    /**
     * An answer is showing. Keeps the originating request so the action row
     * can Replace (delete-then-type needs the draft's delete count) and
     * Regenerate (re-run needs the prompt parts) without re-deriving them.
     */
    data class Answered(val answer: String, val request: AgentRequest) : DetailState()
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
    val draftDeleteCount: Int  // code points to delete for Replace; 0 = no draft captured
)

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

    // Wave 5 (Glamour): custom agents, one-time onboarding card, delete
    // confirmation, and the flashlight toggle's on/off state.
    private var customAgents by mutableStateOf(loadCustomAgents(context))
    private var hubOnboarded by mutableStateOf(context.getSetting(HUB_ONBOARDED))
    private var pendingDeleteAgent by mutableStateOf<CustomAgent?>(null)
    private var torchOn by mutableStateOf(false)

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
                HubScreen.AddAgent -> AddAgentForm()
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
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Welcome to the Agent Hub", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Each tile is an AI agent you can talk to from any text field. " +
                        "Open one, then ask it about your draft, have it rewrite or " +
                        "summarize, or dictate a question by voice. Add your own agent " +
                        "with the + tile below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Got it") }
                }
            }
        }
    }

    @Composable
    private fun AddAgentTile(onClick: () -> Unit) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Text("＋", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Add agent", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your own agent: name, role, and system prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    /** Confirm card for a long-pressed custom tile. Built-in agents can't be deleted. */
    @Composable
    private fun DeleteConfirmCard(agent: CustomAgent) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Delete ${agent.name}?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Its conversation memory goes with it. This can't be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { pendingDeleteAgent = null }) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { deleteCustomAgent(agent) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
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
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .agentTileClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                // Custom agents aren't on the hub roster, so their reachability is
                // genuinely unknown — the neutral dot says so instead of guessing.
                StatusDot(online = null, unknown = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            agent.role.ifBlank { null },
                            "custom"
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (agent.id == selectedAgent) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Long-press-to-delete on tiles. Kept as a Modifier factory so the @OptIn
    // for combinedClickable lives in exactly one place.
    @OptIn(ExperimentalFoundationApi::class)
    private fun Modifier.agentTileClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
        combinedClickable(onClick = onClick, onLongClick = onLongClick)

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

    // ColumnScope extension so the Answered branch can weight() its scroll box.
    @Composable
    private fun ColumnScope.AgentDetail(agentId: String) {
        val name = agentName(agentId)
        val status = statuses?.get(agentId)
        val custom = customAgent(agentId)

        TextButton(onClick = {
            teardownDictation()
            detailState = DetailState.Options
            screen = HubScreen.Tiles
        }) {
            Text("← Back")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Custom agents aren't on the hub roster — neutral dot, not a guess.
            StatusDot(
                online = status?.online,
                unknown = statusFetchFailed || custom != null && status == null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            detailSubtitle(agentId, status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        // AgentMemory is not Compose state, but every record() is immediately
        // followed by a detailState change, which recomposes this header — so
        // the count read here is always fresh by the time it could go stale.
        val memoryCount = AgentMemory.count(agentId)
        if (memoryCount > 0) {
            Text(
                "context: $memoryCount exchange" + if (memoryCount == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        when (val state = detailState) {
            DetailState.Options -> DetailOptions(agentId)
            DetailState.Dictating -> DictationPanel(agentId)
            DetailState.Thinking -> Text(
                "⏳ $name is thinking…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp)
            )

            is DetailState.Answered -> AnswerView(state)
            is DetailState.Failed -> MessageWithBack(state.message)
            is DetailState.Hint -> MessageWithBack(state.message)
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

            DeviceActionsSection(agentId)
        }
    }

    /**
     * On-device helpers for the hub. Only what's cleanly possible from an IME
     * without new permissions is wired; anything not wired says so instead of
     * pretending (see HubDeviceActions.kt for the per-action honesty rules).
     */
    @Composable
    private fun DeviceActionsSection(agentId: String) {
        val scope = rememberCoroutineScope()
        val lastAnswer = AgentMemory.lastAnswer(agentId)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Device actions", style = MaterialTheme.typography.titleSmall)
        Text(
            "Helpers that run on this phone. No new permissions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )

        OutlinedButton(
            onClick = {
                if (!openAlarmSetter(context)) manager.announce("No clock app on this device")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("⏰ Set alarm")
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                if (setTorch(context, !torchOn)) {
                    torchOn = !torchOn
                    manager.announce(if (torchOn) "Flashlight on" else "Flashlight off")
                } else {
                    manager.announce("Flashlight unavailable on this device")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (torchOn) "🔦 Flashlight (on)" else "🔦 Flashlight")
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { lastAnswer?.let { copyAnswer(it) } },
            enabled = lastAnswer != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📋 Copy last answer")
        }
        if (lastAnswer == null) {
            Text(
                "Nothing to copy yet — ask ${agentName(agentId)} something first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Stub: the hub has no find-my-phone endpoint today, so a failure is
        // reported as "not wired yet", never as success.
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val wired = requestFindMyPhone(agentToken())
                    withContext(Dispatchers.Main) {
                        if (wired) {
                            manager.announce("Find-my-phone request sent")
                        } else {
                            detailState = DetailState.Hint(
                                "Find my phone isn't wired yet — the hub has no endpoint for it."
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📱 Find my phone (not wired yet)")
        }
    }

    @Composable
    private fun ColumnScope.AnswerView(state: DetailState.Answered) {
        Text(
            "${agentName(state.request.agentId)} says:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // Bounded height, NOT weight(1f): the IME action window can measure this
        // Column as wrap-content, in which case a weighted box collapses to zero
        // height and the answer renders invisibly (confirmed on emulator 2026-08-21).
        // 320dp fits ~8 lines and scrolls for longer answers.
        Box(
            modifier = Modifier
                .heightIn(min = 120.dp, max = 320.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Explicit onSurface: the IME window's default content color is not
            // guaranteed light on every theme path, and an invisible answer
            // renders as a dead black gap (seen on emulator 2026-08-21).
            Text(
                state.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // Primary action pinned OUTSIDE the scroll row: Insert must never be
        // hidden off-screen. Secondary actions scroll horizontally.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TextButton(onClick = { detailState = DetailState.Options }) {
                Text("Back")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { insertAnswer(state.answer) }) {
                Text("Insert")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp)
        ) {
            // Replace only exists when a draft was captured — dictation answers
            // have nothing before the cursor to swap out.
            if (state.request.draftDeleteCount > 0) {
                OutlinedButton(onClick = { replaceDraftWith(state) }) {
                    Text("Replace draft")
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            OutlinedButton(onClick = { copyAnswer(state.answer) }) {
                Text("Copy")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = { postPrompt(state.request) }) {
                Text("Regenerate")
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

        // Delete count for a later Replace: CODE_DELETE removes one code point
        // per event, so count code points, not UTF-16 units (an emoji is two
        // units). Grapheme clusters (emoji + modifiers) can still drift by one
        // — acceptable approximation for the prototype.
        postPrompt(
            AgentRequest(
                agentId = agentId,
                label = option.label,
                promptPrefix = option.promptPrefix,
                userText = draft,
                draftDeleteCount = draft.codePointCount(0, draft.length)
            )
        )
    }

    /**
     * Shared tail of every prompt path (button options, voice dictation, and
     * Regenerate): Thinking on the Main dispatcher, POST off it, result back
     * on Main. Recent exchanges are prepended here — once per send — so a
     * re-run sees the conversation it is continuing, and only successful
     * answers become memory (failures carry nothing a follow-up could use).
     */
    private fun postPrompt(request: AgentRequest) {
        val name = agentName(request.agentId)

        val token = agentToken()
        if (token.isBlank()) {
            detailState = DetailState.Hint("Agent token is not set. Configure the 'agent_key_token' setting.")
            return
        }

        detailState = DetailState.Thinking
        manager.getLifecycleScope().launch(Dispatchers.IO) {
            // The composed prompt IS the text — postToAgent takes the full string.
            // A custom agent's system prompt goes first (it's the standing
            // instruction), then memory so the agent sees its own prior answers
            // before the new request.
            val custom = customAgent(request.agentId)
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
                append(request.promptPrefix)
                append(request.userText)
            }
            val endpoint = custom?.endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_ENDPOINT
            val answer = postToAgent(prompt, token, request.agentId, endpoint)
            Log.i("AgentHub", "answer from ${request.agentId}: len=${answer?.length} head=${answer?.take(60)}")
            withContext(Dispatchers.Main) {
                detailState = if (answer != null) {
                    AgentMemory.record(request.agentId, request.label, request.userText, answer)
                    DetailState.Answered(answer, request)
                } else {
                    DetailState.Failed("$name request failed. Check connection and token.")
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
    private fun replaceDraftWith(state: DetailState.Answered) {
        manager.backspace(state.request.draftDeleteCount)
        manager.typeText(state.answer)
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
