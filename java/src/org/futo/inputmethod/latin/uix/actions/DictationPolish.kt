package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.POLISH_DICTATION_ONLINE
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.voiceinput.shared.text.OfflineTextProcessor
import java.util.concurrent.TimeUnit

private const val POLISH_DICTATION_AGENT_FALLBACK = "kimi"
private const val POLISH_PROMPT_PREFIX =
    "Restore punctuation and capitalization. Output ONLY the corrected text, nothing else:"

// Short-timeout client for dictation polish: dictation must never block insertion,
// so both connect and read timeouts are capped tightly.
private val dictationPolishHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(POLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

// Hard ceiling on the whole polish attempt. Slightly longer than the OkHttp
// timeouts so SocketTimeoutException is handled naturally by postToAgent, but
// low enough that insertion always happens promptly.
private const val POLISH_TIMEOUT_MS = 10_000L

/**
 * Attempts an online punctuation/capitalization polish on the offline-processed
 * [offlineText]. Returns the agent's answer on success, or [offlineText] on any
 * failure (network, timeout, parse, blank answer, or answer longer than 2x input).
 *
 * This function is safe to call from the main thread: the network call runs on
 * [Dispatchers.IO] and the overall attempt is hard-capped at
 * [POLISH_TIMEOUT_MS].
 */
suspend fun polishDictationTranscript(context: Context, offlineText: String): String {
    if (!context.getSetting(POLISH_DICTATION_ONLINE)) {
        return offlineText
    }

    val token = context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }
    if (token.isBlank() || BuildConfig.AGENT_KEY_ENDPOINT.isBlank() || offlineText.isBlank()) {
        return offlineText
    }

    val draft = "$POLISH_PROMPT_PREFIX\n$offlineText"

    return withContext(Dispatchers.IO) {
        val result = try {
            withTimeoutOrNull(POLISH_TIMEOUT_MS) {
                postToAgent(
                    draft = draft,
                    token = token,
                    agent = context.getSetting(AGENT_KEY_AGENT).ifBlank { POLISH_DICTATION_AGENT_FALLBACK },
                    client = dictationPolishHttpClient
                )
            } ?: AgentResult.Timeout
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("DictationPolish", "unexpected polish exception", e)
            AgentResult.Network
        }

        when (result) {
            is AgentResult.Success -> {
                val answer = result.text.trim()
                if (answer.isBlank() || answer.length > offlineText.length * 2) {
                    Log.w(
                        "DictationPolish",
                        "polish rejected: blank=${answer.isBlank()} len=${answer.length} inputLen=${offlineText.length}"
                    )
                    offlineText
                } else {
                    answer
                }
            }
            else -> {
                Log.w("DictationPolish", "polish failed: $result")
                offlineText
            }
        }
    }
}

/**
 * Applies the always-on offline rules to a final transcript. Kept as a small
 * wrapper so callers don't need to know which module owns the processor.
 */
fun offlineProcessTranscript(text: String): String = OfflineTextProcessor.process(text)
