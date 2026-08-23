package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
    internal fun AgentHubWindow.ThreadView(agentId: String) {
        val passwordGuard = isPasswordContext
        val incognitoGuard = isIncognitoContext
        val tokenMissing = agentToken().isBlank()
        val canAct = !passwordGuard && !incognitoGuard && !tokenMissing

        // Reading ConversationThreads through threadVersion makes every add/replace
        // in postPrompt recompose this view.
        val turns = remember(threadVersion, agentId) { ConversationThreads.turns(agentId) }
        val lastRequest = lastAnswerRequest
        val lastAgentTurn = turns.lastOrNull { !it.fromUser && it.errorResult == null }

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
                        val lastAgentTurnIndex = turns.indexOfLast { !it.fromUser }
                        turns.forEachIndexed { index, turn ->
                            when {
                                turn.fromUser -> UserBubble(turn)
                                turn.errorResult != null -> ErrorBubble(agentId, turn, canAct && index == lastAgentTurnIndex)
                                else -> AgentBubble(agentId, turn)
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

            // Last-answer actions operate on the most recent successful agent turn only.
            if (lastAgentTurn != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    if ((lastRequest?.draftDeleteCount ?: 0) > 0) {
                        HubChip(text = "Replace draft") {
                            lastRequest?.let { replaceDraftWith(lastAgentTurn.text, it.draftDeleteCount) }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    HubChip(text = "Insert") { insertAnswer(lastAgentTurn.text) }
                    Spacer(modifier = Modifier.width(8.dp))
                    HubChip(text = "Copy") { copyAnswer(lastAgentTurn.text) }
                    Spacer(modifier = Modifier.width(8.dp))
                    HubChip(text = "Regenerate", enabled = canAct) { regenerateLast(lastRequest) }
                }
            }

            if (incognitoGuard) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${agentName(agentId)} is disabled in private fields.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (passwordGuard) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${agentName(agentId)} is disabled in password fields.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (tokenMissing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Agent token is not set. Open Settings → Agents to set the token.",
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
    @Composable
    internal fun AgentHubWindow.ErrorBubble(agentId: String, turn: ThreadTurn, canAct: Boolean) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                HubMonogram(name = agentName(agentId), active = false, size = 28.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Text(
                        turn.text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 36.dp)
            ) {
                HubChip(
                    text = "Retry",
                    enabled = canAct,
                    onClick = { regenerateLast(turn.request ?: lastAnswerRequest) }
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
                    "Translation is disabled here (private field or missing token).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
