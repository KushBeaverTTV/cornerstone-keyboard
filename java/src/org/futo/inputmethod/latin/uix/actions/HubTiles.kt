package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.uix.setSetting

// ---- Tiles view ----

    @Composable
    internal fun AgentHubWindow.HubTiles(onSelect: (String) -> Unit) {
        val scope = rememberCoroutineScope()
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
                    scope.launch { context.setSetting(HUB_ONBOARDED, true) }
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
                HubStatusDot(online = null, unknown = true)
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
                HubStatusDot(online = status?.online, unknown = statusFetchFailed)
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
