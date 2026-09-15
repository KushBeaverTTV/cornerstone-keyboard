package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                onValueChange = { endpoint = it; error = null },
                label = { Text("Endpoint URL (optional)") },
                placeholder = { Text("Default: the shared hub") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Custom endpoints get the same request as the built-in agents, " +
                    "but WITHOUT the hub Bearer token — no Authorization header " +
                    "is sent. Leave blank for the shared hub. https recommended — " +
                    "cleartext http only reaches hosts the keyboard's network " +
                    "config allows.",
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
                    // Save-time endpoint check: a URL Android would refuse to
                    // call (malformed, or cleartext to a non-allowlisted host)
                    // must fail HERE with a specific message, not at send time.
                    val endpointError = validateAgentEndpoint(endpoint, CLEARTEXT_ALLOWLIST)
                    when {
                        id == null -> error = validation.error
                        endpointError != null -> error = endpointError
                        else -> {
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
                    }
                }) {
                    Text("Save")
                }
            }
        }
    }
