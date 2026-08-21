package org.futo.inputmethod.latin.uix.actions

import android.text.InputType
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import java.util.concurrent.TimeUnit

// Prototype endpoint, reached over plain HTTP inside the encrypted Tailscale
// mesh (cleartext allowed for this host only, see res/xml/network_security_config.xml).
// Change here if the endpoint moves. Custom agents default to this; a custom
// agent may carry its own endpoint URL override (same Bearer contract).
internal const val DEFAULT_AGENT_ENDPOINT = "http://100.100.194.62:8877/agentkey"

// Bearer token lives in DataStore only — NEVER hardcode a token in this repo.
// Empty default = feature explains itself and refuses to fire.
val AGENT_KEY_TOKEN = SettingsKey(
    key = stringPreferencesKey("agent_key_token"),
    default = ""
)

// Which agent answers. Long-press the Agent key to switch.
val AGENT_KEY_AGENT = SettingsKey(
    key = stringPreferencesKey("agent_key_agent"),
    default = "kimi"
)

val AGENT_CHOICES = listOf("kimi" to "Kimi", "aurora" to "Aurora", "prima" to "Prima")

// Shared with AgentHub.kt (same package) — the hub reuses the display name,
// HTTP client, password guard, and POST helper rather than duplicating them.
internal fun agentDisplayName(id: String): String =
    AGENT_CHOICES.firstOrNull { it.first == id }?.second ?: "Kimi"

internal val agentHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

internal fun isPasswordField(inputType: Int): Boolean {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}

// `draft` is the full text to send — callers compose any prompt into it first.
// `endpoint` defaults to the shared hub; custom agents pass their override URL.
internal fun postToAgent(
    draft: String,
    token: String,
    agent: String,
    endpoint: String = DEFAULT_AGENT_ENDPOINT
): String? {
    val body = JSONObject().put("text", draft).put("agent", agent).toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer $token")
        .post(body)
        .build()

    return try {
        agentHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            JSONObject(responseBody).optString("answer").ifBlank { null }
        }
    } catch (e: Exception) {
        Log.w("AgentAction", "agent request failed", e)
        null
    }
}

private fun showAnswerOnStrip(manager: KeyboardManagerForAction, answer: String): Boolean {
    // Strip can't show the answer when the IME doesn't implement the accessor
    // (e.g. JapaneseIME) or when the user has suggestions disabled — caller must
    // fall back to a dialog so the answer is never silently dropped.
    val suggestionsOn = Settings.getInstance().current.isSuggestionsEnabledPerUserSettings
    val ime = manager.getCurrentIME() as? SuggestionStripViewAccessor
    if (!suggestionsOn || ime == null) return false
    val words = SuggestedWords(
        arrayListOf(
            SuggestedWordInfo(
                answer,
                "",
                SuggestedWordInfo.MAX_SCORE,
                SuggestedWordInfo.KIND_PREDICTION,
                null,
                0,
                0
            )
        ),
        null,
        null,
        false,
        false,
        false,
        SuggestedWords.INPUT_STYLE_PREDICTION,
        SuggestedWords.NOT_A_SEQUENCE_NUMBER
    )

    ime.showSuggestionStrip(words)
    return true
}

val AgentAction = Action(
    icon = R.drawable.agent,
    name = R.string.action_agent_title,
    // Tap opens the Agent Hub window; the network call now happens from there.
    simplePressImpl = null,
    // Long-press: pick which agent answers (built-ins + any custom agents)
    altPressImpl = { manager, _ ->
        val context = manager.getContext()
        val current = context.getSetting(AGENT_KEY_AGENT)
        val choices = AGENT_CHOICES + loadCustomAgents(context).map { it.id to it.name }
        manager.requestDialog(
            "Agent answering: ${agentDisplayName(context, current)}\n\nTap to switch:",
            choices.map { (id, name) ->
                DialogRequestItem(if (id == current) "✓ $name" else name) {
                    context.setSettingBlocking(AGENT_KEY_AGENT.key, id)
                    showAnswerOnStrip(manager, "Agent: $name")
                }
            },
            {}
        )
    },
    windowImpl = { manager, _ -> AgentHubWindow(manager) },
)
