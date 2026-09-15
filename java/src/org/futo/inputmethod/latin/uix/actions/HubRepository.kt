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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
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


// Hub status endpoint: same prototype host as DEFAULT_AGENT_ENDPOINT (Tailscale mesh,
// cleartext allowed for this host only), but needs no auth token.
/**
 * Derive the roster endpoint from the configured Agent Key endpoint.
 * Pure so the URL contract is unit-testable; never mutate a live URL by
 * substring surgery at the use site. Tolerates trailing slashes and a
 * missing /agentkey suffix.
 */
internal fun statusEndpointFromAgentKey(agentKeyEndpoint: String): String {
    val base = agentKeyEndpoint.trim().trimEnd('/')
        .removeSuffix("/agentkey")
    return "$base/agents"
}

internal fun crashEndpointFromAgentKey(agentKeyEndpoint: String): String {
    val base = agentKeyEndpoint.trim().trimEnd('/')
        .removeSuffix("/agentkey")
    return "$base/crash"
}

private val AGENT_STATUS_ENDPOINT = statusEndpointFromAgentKey(BuildConfig.AGENT_KEY_ENDPOINT)

internal val statusHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/**
 * GET the hub's agent roster. Returns null on any failure — callers treat null
 * as "status unknown" rather than an error, because the hub is optional
 * context for the UI, not a hard dependency of the feature.
 */
internal fun fetchAgentStatuses(): Map<String, AgentStatus>? {
    val endpoint = AGENT_STATUS_ENDPOINT
    if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
        return null
    }
    return try {
        val request = Request.Builder().url(endpoint).get().build()
        statusHttpClient.newCall(request).execute().use { response ->
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


internal sealed class AgentResult {
    data class Success(val text: String) : AgentResult()
    object Timeout : AgentResult()
    data class Http(val code: Int) : AgentResult()
    object Parse : AgentResult()
    object Network : AgentResult()

    /** The endpoint URL couldn't be parsed — the request was never sent. */
    object BadUrl : AgentResult()

    /** Plain http to a host Android's network security config doesn't allow. */
    object CleartextBlocked : AgentResult()
}

// `draft` is the full text to send — callers compose any prompt into it first.
// `endpoint` defaults to the shared hub; custom agents pass their override URL.
// `client` may be supplied to override the default long-timeout client (e.g. for
// short-latency paths such as dictation polish).

/**
 * A custom (non-hub) endpoint. A blank hub configuration (public builds ship
 * without one) makes every real endpoint custom — the Bearer token must still
 * stay home in that case.
 */
internal fun isForeignAgentEndpoint(endpoint: String): Boolean {
    val custom = endpoint.trim().trimEnd('/')
    val hub = DEFAULT_AGENT_ENDPOINT.trim().trimEnd('/')
    if (custom.isEmpty()) return false
    return custom != hub
}

/**
 * Build the agent request: the same JSON contract ({text, agent}) for hub and
 * custom endpoints, but the hub Bearer token goes ONLY to the hub — custom
 * endpoints receive no Authorization header. The token is the hub's
 * credential, not the user's to hand out to arbitrary hosts.
 */
internal fun buildAgentRequest(draft: String, token: String, agent: String, endpoint: String): Request {
    val body = JSONObject().put("text", draft).put("agent", agent).toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val builder = Request.Builder().url(endpoint).post(body)
    if (!isForeignAgentEndpoint(endpoint)) {
        builder.header("Authorization", "Bearer $token")
    }
    return builder.build()
}

internal fun postToAgent(
    draft: String,
    token: String,
    agent: String,
    endpoint: String = DEFAULT_AGENT_ENDPOINT,
    client: OkHttpClient = agentHttpClient
): AgentResult {
    val request = try {
        buildAgentRequest(draft, token, agent, endpoint)
    } catch (e: IllegalArgumentException) {
        return AgentResult.BadUrl
    }
    // Pre-flight the cleartext rule: Android would kill the call anyway —
    // name the real problem instead of letting it surface as "unreachable".
    if (!isCleartextPairingValid(endpoint, CLEARTEXT_ALLOWLIST)) return AgentResult.CleartextBlocked

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return AgentResult.Http(response.code)
            val responseBody = response.body?.string()?.trim() ?: return AgentResult.Parse
            if (responseBody.isBlank()) return AgentResult.Parse
            val answer = try {
                JSONObject(responseBody).optString("answer").trim()
            } catch (_: Exception) {
                return AgentResult.Parse
            }
            if (answer.isBlank()) return AgentResult.Parse
            AgentResult.Success(answer)
        }
    } catch (e: SocketTimeoutException) {
        AgentResult.Timeout
    } catch (e: UnknownServiceException) {
        // "CLEARTEXT communication to <host> is not permitted by network
        // security policy" — the allowlist above should catch this first, but
        // keep the runtime mapping so it never reads as a generic network error.
        if (e.message?.contains("CLEARTEXT") == true) AgentResult.CleartextBlocked else AgentResult.Network
    } catch (e: IOException) {
        AgentResult.Network
    } catch (e: Exception) {
        Log.w("HubRepository", "agent request failed", e)
        AgentResult.Network
    }
}

/**
 * User-facing message for one AgentResult, keyed off the sealed result: each
 * failure class names the action that can actually fix it instead of leaving
 * the user guessing (extends the Sprint-3 mapping; 401/403 now point at
 * 'agent_key_token', network points at Wi-Fi/Tailscale). Pure + top-level so
 * the wording contract is unit-testable without constructing the hub window.
 */
internal fun agentResultMessage(result: AgentResult, agentName: String): String = when (result) {
    is AgentResult.Success -> result.text
    AgentResult.Timeout -> "$agentName timed out — the server is still working or unreachable; try again shortly"
    is AgentResult.Http ->
        if (result.code == 401 || result.code == 403)
            "$agentName rejected the auth token — update 'agent_key_token' in settings"
        else if (result.code in 500..599)
            "$agentName hit a server error (${result.code}) — retry in a moment"
        else
            "$agentName request rejected (HTTP ${result.code})"
    AgentResult.Parse -> "Couldn't read $agentName's reply — check the hub server logs"
    AgentResult.Network -> "$agentName's server unreachable — check Wi-Fi/Tailscale, then retry"
    AgentResult.BadUrl -> "$agentName's endpoint URL is invalid — fix it in the agent's settings"
    AgentResult.CleartextBlocked ->
        "$agentName's endpoint uses cleartext http, which Android only allows for the paired hub host — use https instead"
}

/**
 * Mirror of the build-time gate in build.gradle: a plain-http endpoint is only
 * usable when its host is explicitly allowlisted for cleartext in
 * network_security_config.xml; Android permits https to any host regardless.
 * The Gradle gate fails the build on violation; this predicate documents and
 * unit-tests the same contract. Keep the two in sync.
 */
internal fun isCleartextPairingValid(endpoint: String, allowlistedDomains: Set<String>): Boolean {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return true // public builds ship without an endpoint
    val scheme = trimmed.substringBefore("://", "").lowercase()
    return when (scheme) {
        "https" -> true
        "http" -> {
            val host = trimmed.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            host.isNotEmpty() && allowlistedDomains.any { it.lowercase() == host }
        }
        else -> false // missing/unknown scheme can't be paired safely
    }
}

// Cleartext allowlist mirrored from java/res/xml/network_security_config.xml —
// keep the two in sync (the Gradle F-5 gate reads the XML at build time; the
// runtime checks here and in postToAgent can't; AgentHubLogicTest verifies the
// mirror). These are the PC running hub-bridge/hub_bridge.py, which fronts
// the Hermes agent: its LAN address and its Tailscale address.
internal val CLEARTEXT_ALLOWLIST = setOf("192.168.0.223", "100.84.54.112")

/**
 * Save-time validation for a custom agent's endpoint. Blank = shared hub (no
 * error). Otherwise the value must parse as an http/https URL, and plain http
 * is accepted only for a cleartext-allowlisted host — the same contract the
 * Gradle gate enforces for the built-in endpoint, applied to user input.
 * Returns the user-facing error, or null when the endpoint is usable.
 */
internal fun validateAgentEndpoint(endpoint: String, allowlistedDomains: Set<String>): String? {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return null
    val parsed = try {
        java.net.URL(trimmed)
    } catch (e: java.net.MalformedURLException) {
        return "That endpoint isn't a valid URL"
    }
    return when (parsed.protocol.lowercase()) {
        "https" -> null
        "http" ->
            if (isCleartextPairingValid(trimmed, allowlistedDomains)) {
                null
            } else {
                "Cleartext http is only allowed for the paired hub host — use https for other hosts"
            }
        else -> "Endpoint must start with http:// or https://"
    }
}

