package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.json.JSONObject
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import java.util.Locale

// User-defined agents, stored as a JSON array in one DataStore string. Small
// and bounded (a handful of entries), so a whole-list rewrite on every edit
// is the simple correct choice over per-agent keys.
val CUSTOM_AGENTS = SettingsKey(
    key = stringPreferencesKey("custom_agents"),
    default = ""
)

/**
 * A user-defined agent. `endpoint` blank means the shared hub endpoint
 * (DEFAULT_AGENT_ENDPOINT); a non-blank value is a full URL override that
 * receives the same request contract as the hub but WITHOUT the hub Bearer
 * token — no Authorization header is sent to custom hosts.
 */
internal data class CustomAgent(
    val id: String,
    val name: String,
    val role: String,
    val systemPrompt: String,
    val endpoint: String
)

// ---- JSON (de)serialization ----

private const val JSON_ID = "id"
private const val JSON_NAME = "name"
private const val JSON_ROLE = "role"
private const val JSON_PROMPT = "prompt"
private const val JSON_ENDPOINT = "endpoint"

/**
 * Parse the stored list. Malformed JSON or malformed entries are dropped
 * (with a log) rather than failing the hub — a corrupt list must never make
 * the built-in agents unusable.
 */
internal fun parseCustomAgents(json: String): List<CustomAgent> {
    if (json.isBlank()) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (e: Exception) {
        Log.w("CustomAgents", "custom_agents setting is not valid JSON, ignoring", e)
        return emptyList()
    }
    return buildList {
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString(JSON_ID)
            val name = entry.optString(JSON_NAME)
            if (id.isBlank() || name.isBlank()) continue
            add(
                CustomAgent(
                    id = id,
                    name = name,
                    role = entry.optString(JSON_ROLE),
                    systemPrompt = entry.optString(JSON_PROMPT),
                    endpoint = entry.optString(JSON_ENDPOINT)
                )
            )
        }
    }
}

internal fun serializeCustomAgents(agents: List<CustomAgent>): String {
    val array = JSONArray()
    agents.forEach { agent ->
        array.put(
            JSONObject()
                .put(JSON_ID, agent.id)
                .put(JSON_NAME, agent.name)
                .put(JSON_ROLE, agent.role)
                .put(JSON_PROMPT, agent.systemPrompt)
                .put(JSON_ENDPOINT, agent.endpoint)
        )
    }
    return array.toString()
}

internal fun loadCustomAgents(context: Context): List<CustomAgent> =
    parseCustomAgents(context.getSetting(CUSTOM_AGENTS))

internal fun saveCustomAgents(context: Context, agents: List<CustomAgent>) {
    context.setSettingBlocking(CUSTOM_AGENTS.key, serializeCustomAgents(agents))
}

internal fun customAgentById(context: Context, id: String): CustomAgent? =
    loadCustomAgents(context).firstOrNull { it.id == id }

/**
 * Context-aware display name: built-ins first, then custom agents, then the
 * no-context fallback. Prefer this over agentDisplayName(id) wherever a
 * Context is available, so custom tiles never render as "Kimi".
 */
internal fun agentDisplayName(context: Context, id: String): String =
    customAgentById(context, id)?.name ?: agentDisplayName(id)

// ---- Validation ----

/**
 * Derive the agent id from its name: lowercase, digits/letters kept,
 * everything else collapsed to single dashes, dashes trimmed from the ends.
 * "My Helper!" -> "my-helper". Returns "" when nothing usable remains.
 */
internal fun slugifyAgentId(name: String): String =
    name.trim().lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

/**
 * Validate a would-be custom agent against the built-ins and the existing
 * custom list. Returns the slugified id on success, or null with [error]
 * naming the exact problem (shown verbatim in the form).
 */
internal data class AgentValidation(val id: String?, val error: String?)

internal fun validateNewAgent(name: String, existingCustom: List<CustomAgent>): AgentValidation {
    if (name.isBlank()) return AgentValidation(null, "Name can't be blank")
    val id = slugifyAgentId(name)
    if (id.isBlank()) return AgentValidation(null, "Name needs at least one letter or digit")
    val taken = AGENT_CHOICES.map { it.first } + existingCustom.map { it.id }
    if (id in taken) return AgentValidation(null, "An agent called \"$id\" already exists")
    return AgentValidation(id, null)
}
