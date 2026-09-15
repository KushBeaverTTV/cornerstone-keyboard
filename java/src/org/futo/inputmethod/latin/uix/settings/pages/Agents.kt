package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.AGENT_KEY_TOKEN
import org.futo.inputmethod.latin.uix.actions.statusEndpointFromAgentKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu

val AgentsMenu = UserSettingsMenu(
    title = R.string.settings_agents_title,
    navPath = "agents",
    registerNavPath = true,
    settings = listOf(
        UserSetting(
            name = R.string.settings_agents_token_title,
            subtitle = R.string.settings_agents_token_subtitle,
            component = { AgentTokenField() }
        )
    )
)

@Composable
private fun AgentTokenField() {
    val context = LocalContext.current
    var value by remember {
        mutableStateOf(context.getSetting(AGENT_KEY_TOKEN))
    }
    val endpoint = BuildConfig.AGENT_KEY_ENDPOINT
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_agents_token_subtitle))
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                context.setSettingBlocking(AGENT_KEY_TOKEN.key, it)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.settings_agents_token_title)) },
            singleLine = true
        )
        if (endpoint.isNotBlank()) {
            Text(
                "Hub: ${statusEndpointFromAgentKey(endpoint)}",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
