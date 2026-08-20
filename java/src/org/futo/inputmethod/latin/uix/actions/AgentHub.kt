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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking

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
        val name = agentName(agentId)

        // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
        if (isPasswordContext) return
        val token = agentToken()
        if (token.isBlank()) {
            detailState = DetailState.Hint("Agent token is not set. Configure the 'agent_key_token' setting.")
            return
        }

        // Read-only transaction: capture the draft, then cancel so nothing changes.
        val transaction = manager.createInputTransaction()
        val draft = transaction.textContext.beforeCursor?.toString().orEmpty()
        transaction.cancel()

        if (draft.isBlank()) {
            detailState = DetailState.Hint("Type something in the field first")
            return
        }

        detailState = DetailState.Thinking
        manager.getLifecycleScope().launch(Dispatchers.IO) {
            // The composed prompt IS the text — postToAgent takes the full string.
            val answer = postToAgent(option.promptPrefix + draft, token, agentId)
            withContext(Dispatchers.Main) {
                detailState = if (answer != null) {
                    DetailState.Answered(answer)
                } else {
                    DetailState.Failed("$name request failed. Check connection and token.")
                }
            }
        }
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
