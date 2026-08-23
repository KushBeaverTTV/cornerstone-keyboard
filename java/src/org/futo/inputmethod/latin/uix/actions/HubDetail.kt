package org.futo.inputmethod.latin.uix.actions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.futo.inputmethod.latin.uix.setSettingBlocking

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
                    HubStatusDot(
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
            Spacer(modifier = Modifier.height(16.dp))
            HubChip(text = "Cancel", onClick = { cancelInFlightPrompt() }, modifier = Modifier.fillMaxWidth())
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
        val incognitoGuard = isIncognitoContext
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
                enabled = !passwordGuard && !incognitoGuard && !tokenMissing
            )
            Spacer(modifier = Modifier.height(12.dp))

            HubRowCard(
                rows = HUB_OPTIONS.map { option ->
                    HubRow(
                        label = option.label,
                        enabled = !passwordGuard && !incognitoGuard && !tokenMissing,
                        onClick = { runOption(agentId, option) }
                    )
                }
            )

            if (incognitoGuard) {
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(accentColor = MaterialTheme.colorScheme.error) {
                    Text(
                        "${agentName(agentId)} is disabled in private fields.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (passwordGuard) {
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
                        "Agent token is not set. Open Settings → Agents to set the token.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
