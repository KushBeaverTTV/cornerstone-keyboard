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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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


    // ---- Tiles view ----

    @Composable
    internal fun AgentHubWindow.HubTiles(onSelect: (String) -> Unit) {
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
    internal fun AgentHubWindow.OnboardingCard(onDismiss: () -> Unit) {
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
    internal fun AgentHubWindow.AddAgentTile(onClick: () -> Unit) {
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
    internal fun AgentHubWindow.DeleteConfirmCard(agent: CustomAgent) {
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
    @Composable
    internal fun AgentHubWindow.CustomAgentTile(agent: CustomAgent, onClick: () -> Unit, onLongClick: () -> Unit) {
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
    internal fun AgentHubWindow.AgentTile(agentId: String, fallbackName: String, onClick: () -> Unit) {
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
    internal fun AgentHubWindow.tileSubtitle(status: AgentStatus?): String = when {
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
    internal fun AgentHubWindow.AddAgentForm() {
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
    @Composable
    internal fun AgentHubWindow.DetailHeader(
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
    internal fun AgentHubWindow.ThinkingView(name: String) {
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
    internal fun AgentHubWindow.detailSubtitle(agentId: String, status: AgentStatus?): String {
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
    internal fun AgentHubWindow.DetailOptions(agentId: String) {
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
    @Composable
    internal fun AgentHubWindow.ThreadView(agentId: String) {
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
                            lastRequest?.let { replaceDraftWith(lastAgentText, it.draftDeleteCount) }
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
    internal fun AgentHubWindow.UserBubble(turn: ThreadTurn) {
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
    internal fun AgentHubWindow.AgentBubble(agentId: String, turn: ThreadTurn) {
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
    internal fun AgentHubWindow.TranslateCard(agentId: String, canAct: Boolean) {
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
    @Composable
    internal fun AgentHubWindow.MessageWithBack(message: String, isError: Boolean) {
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
    // ---- Shared bits ----

    @Composable
    internal fun AgentHubWindow.StatusDot(online: Boolean?, unknown: Boolean) {
        // Semantics unchanged: primary = online, error = offline, outline = unknown.
        HubStatusDot(online = online, unknown = unknown)
    }

