package org.futo.inputmethod.latin.uix.actions

import android.text.InputType
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.futo.inputmethod.latin.BuildConfig
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
import java.util.concurrent.TimeUnit

// Prototype endpoint, reached over plain HTTP inside the encrypted Tailscale
// mesh (cleartext allowed for this host only, see res/xml/network_security_config.xml).
// Change here if the endpoint moves.
private const val AGENT_ENDPOINT = "http://100.100.194.62:8877/agentkey"

// Bearer token lives in DataStore only — NEVER hardcode a token in this repo.
// Empty default = feature explains itself and refuses to fire.
val AGENT_KEY_TOKEN = SettingsKey(
    key = stringPreferencesKey("agent_key_token"),
    default = ""
)

private val agentHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private fun isPasswordField(inputType: Int): Boolean {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}

private fun postToAgent(draft: String, token: String): String? {
    val body = JSONObject().put("text", draft).toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url(AGENT_ENDPOINT)
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
private fun simpleOkDialog(manager: KeyboardManagerForAction, text: String) {
    manager.requestDialog(text, listOf(DialogRequestItem("OK") {}), {})
}

val AgentAction = Action(
    icon = R.drawable.agent,
    name = R.string.action_agent_title,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val token = context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }

        when {
            token.isBlank() -> simpleOkDialog(
                manager,
                "Agent Key token is not set. Configure the 'agent_key_token' setting to use the agent."
            )

            // Never read or send password field contents
            manager.getLatinIMEForDebug().currentInputEditorInfo?.let {
                isPasswordField(it.inputType)
            } == true -> simpleOkDialog(manager, "Agent Key is disabled in password fields.")

            else -> {
                // Explicit tap only: capture the draft once, right here.
                val transaction = manager.createInputTransaction()
                val draft = transaction.textContext.beforeCursor?.toString().orEmpty()
                transaction.cancel()

                if (draft.isBlank()) {
                    simpleOkDialog(manager, "Type something first, then tap Agent.")
                } else {
                    // Immediate feedback — the call takes seconds; silence reads as broken
                    showAnswerOnStrip(manager, "⏳ Asking the agent…")
                    manager.getLifecycleScope().launch(Dispatchers.IO) {
                        val answer = postToAgent(draft, token)

                        withContext(Dispatchers.Main) {
                            if (answer == null) {
                                simpleOkDialog(manager, "Agent request failed. Check connection and token.")
                            } else {
                                // The answer card is the primary UX — the strip is too
                                // easy to miss (an answer there looks like a word
                                // suggestion). Strip is a bonus, not the delivery.
                                showAnswerOnStrip(manager, answer)
                                manager.requestDialog(
                                    answer,
                                    listOf(
                                        DialogRequestItem("Dismiss") {},
                                        DialogRequestItem("Insert") { manager.typeText(answer) }
                                    ),
                                    {}
                                )
                            }
                        }
                    }
                }
            }
        }
    },
    windowImpl = null,
)
