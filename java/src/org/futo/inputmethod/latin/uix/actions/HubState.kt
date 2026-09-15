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
import okhttp3.Request
import org.json.JSONObject
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


// One-time "what is this" card on first hub open.
val HUB_ONBOARDED = SettingsKey(
    key = booleanPreferencesKey("hub_onboarded"),
    default = false
)

/** Live status of one agent as reported by the hub status endpoint. */
internal data class AgentStatus(
    val name: String,
    val role: String,
    val online: Boolean
)

// ---- Short agent memory ----

/**
 * One completed exchange with an agent, kept so follow-ups ("make it shorter")
 * have context. The label records which operation produced the answer.
 */
internal data class AgentExchange(
    val label: String,
    val draftSummary: String,
    val answerSummary: String
)

/**
 * Short per-agent memory for the prototype: the last few exchanges, held in
 * process memory keyed by agent id. This memory itself is never persisted —
 * but note ConversationThreads DOES write full turn text to app-private
 * storage (see its comment), so a restarted keyboard restores the threads
 * while these summaries start blank.
 *
 * Behavior needed: FIFO with a hard size bound → one ArrayDeque per agent,
 * evicting the oldest entry in O(1) when full. All methods are @Synchronized
 * because prompt composition reads on Dispatchers.IO while record() writes on
 * the main thread after a request completes.
 */
internal object AgentMemory {
    private const val MAX_EXCHANGES = 6

    // Truncations keep the composed prompt compact: summaries orient the agent,
    // they don't need to carry the full text again.
    private const val DRAFT_SUMMARY_CHARS = 80
    private const val ANSWER_SUMMARY_CHARS = 300

    private val exchanges = mutableMapOf<String, ArrayDeque<AgentExchange>>()

    // Full text of each agent's most recent answer — the truncated summaries in
    // `exchanges` orient the model, this is what "Copy last answer" hands out.
    private val lastAnswers = mutableMapOf<String, String>()

    /** Store a successful exchange, evicting the oldest once past the bound. */
    @Synchronized
    fun record(agentId: String, label: String, userText: String, answer: String) {
        val queue = exchanges.getOrPut(agentId) { ArrayDeque() }
        queue.addLast(
            AgentExchange(
                label = label,
                draftSummary = userText.take(DRAFT_SUMMARY_CHARS),
                answerSummary = answer.take(ANSWER_SUMMARY_CHARS)
            )
        )
        while (queue.size > MAX_EXCHANGES) queue.removeFirst()
        lastAnswers[agentId] = answer
    }

    /** The agent's most recent full answer, or null when it hasn't answered yet. */
    @Synchronized
    fun lastAnswer(agentId: String): String? = lastAnswers[agentId]

    /** How many exchanges are remembered for this agent (0 when none). */
    @Synchronized
    fun count(agentId: String): Int = exchanges[agentId]?.size ?: 0

    /**
     * Compact "Recent conversation:" block to prepend to a prompt, oldest
     * first, or null when this agent has no history (nothing to prepend).
     */
    @Synchronized
    fun contextBlock(agentId: String): String? {
        val queue = exchanges[agentId]?.takeIf { it.isNotEmpty() } ?: return null
        val lines = queue.mapIndexed { index, exchange ->
            "${index + 1}. [${exchange.label}] draft: \"${exchange.draftSummary}\" -> you: \"${exchange.answerSummary}\""
        }
        return "Recent conversation:\n" + lines.joinToString("\n")
    }
}

/** In-window navigation: tiles overview <-> one agent's detail view <-> add-agent form. */
internal sealed class HubScreen {
    object Tiles : HubScreen()
    data class Detail(val agentId: String) : HubScreen()
    object AddAgent : HubScreen()
}

/** State machine for the detail view's request lifecycle. */
internal sealed class DetailState {
    /** The action rows / whole-field toggle (also the post-back-to state). */
    object Options : DetailState()

    /**
     * Conversation mode home: the persistent bubble thread plus the pinned
     * follow-up row (text + send + voice). Every successful request lands here.
     */
    object Thread : DetailState()

    /** Device-controls page (volume/brightness/torch/…), reached from Options. */
    object Device : DetailState()
    object Dictating : DetailState()
    object Thinking : DetailState()
    data class Failed(val message: String) : DetailState()
    data class Hint(val message: String) : DetailState()
}

/**
 * Everything postPrompt needs to run one agent request and remember it
 * afterwards. Grouped into an object because runOption, dictation, and
 * Regenerate all build the same bundle — five loose parameters would drift.
 */
internal data class AgentRequest(
    val agentId: String,
    val label: String,         // option label, or "Ask by voice" for dictation
    val promptPrefix: String,  // "" for dictation: the spoken text is the whole prompt
    val userText: String,      // captured draft, or the spoken question
    val draftDeleteCount: Int, // code points to delete for Replace; 0 = no draft captured
    val wholeField: Boolean = false // userText is the entire field, not just beforeCursor
)

/** One interaction option: label shown on the button, prompt prefix sent to the agent. */
internal data class HubOption(val label: String, val promptPrefix: String)

internal val HUB_OPTIONS = listOf(
    HubOption("Ask about my draft", "Here is my draft. Answer any question in it, or respond to it:\n\n"),
    HubOption("Improve my draft", "Improve this draft, keeping my voice and intent:\n\n"),
    HubOption("Rewrite — professional", "Rewrite this in a professional tone:\n\n"),
    HubOption("Rewrite — casual", "Rewrite this in a casual, friendly tone:\n\n"),
    HubOption("Summarize this", "Summarize this briefly:\n\n"),
    HubOption("Draft a reply", "Draft a reply to this message in my voice, returning only the reply:\n\n"),
    HubOption("Continue writing", "Continue this text seamlessly for 1-3 sentences, returning only the continuation:\n\n")
)

// "Translate…" opens an inline language input instead of firing immediately;
// the composed prefix is built once the language is entered.
internal const val TRANSLATE_LABEL = "Translate…"
internal fun translatePrefix(language: String) =
    "Translate this to $language, returning only the translation:\n\n"

/**
 * One visible turn in an agent's conversation thread. Kept full-text so the
 * follow-up prompt can carry real context (the summaries in AgentMemory are
 * orientation only). role: true = user, false = agent.
 */
internal data class ThreadTurn(
    val fromUser: Boolean,
    val text: String,
    val label: String,
    val errorResult: AgentResult? = null,
    val request: AgentRequest? = null
)

/**
 * Per-agent conversation threads, kept in process memory and persisted to
 * filesDir/hub-threads.json so they survive an IME restart. Hard cap bounds
 * prompt size.
 *
 * Privacy: turn text is written UNENCRYPTED to app-private storage — other
 * apps can't read it, but root and device backups can. If that ever becomes
 * unacceptable, persist()/load() below are the single choke point to encrypt
 * or stop writing.
 */
internal object ConversationThreads {
    private const val MAX_TURNS = 40 // 20 exchanges
    private val threads = mutableMapOf<String, ArrayDeque<ThreadTurn>>()

    @Synchronized
    fun add(agentId: String, turn: ThreadTurn) {
        val queue = threads.getOrPut(agentId) { ArrayDeque() }
        queue.addLast(turn)
        while (queue.size > MAX_TURNS) queue.removeFirst()
    }

    @Synchronized
    fun replaceLastAgentTurn(
        agentId: String,
        text: String,
        errorResult: AgentResult? = null,
        request: AgentRequest? = null
    ): Boolean {
        val queue = threads[agentId] ?: return false
        for (index in queue.indices.reversed()) {
            if (!queue[index].fromUser) {
                queue[index] = queue[index].copy(
                    text = text,
                    errorResult = errorResult,
                    request = request
                )
                return true
            }
        }
        return false
    }

    /**
     * Replace the agent turn that answered [request] — Regenerate must rewrite
     * the bubble for ITS request, not whatever agent bubble happens to be
     * newest (after request A succeeds and follow-up B fails, rewriting the
     * newest bubble would overwrite B's error and leave B's user turn
     * permanently unanswered). Falls back to the newest agent turn when no
     * turn is tagged with the request.
     */
    @Synchronized
    fun replaceAgentTurnForRequest(
        agentId: String,
        request: AgentRequest,
        text: String,
        errorResult: AgentResult? = null
    ): Boolean {
        val queue = threads[agentId] ?: return false
        for (index in queue.indices.reversed()) {
            val turn = queue[index]
            if (!turn.fromUser && turn.request == request) {
                queue[index] = turn.copy(text = text, errorResult = errorResult, request = request)
                return true
            }
        }
        return replaceLastAgentTurn(agentId, text, errorResult, request)
    }

    /**
     * Apply a finished request's outcome to the thread, THEN persist — in that
     * order. (The success path used to persist before adding the answer turn,
     * so an IME restart lost every answer.) `replace` is the Regenerate/Retry
     * path: it rewrites the bubble that answered THIS request. The persist
     * lambda keeps ConversationThreads Context-free and the ordering unit-testable.
     */
    @Synchronized
    fun applyResult(
        agentId: String,
        request: AgentRequest,
        replace: Boolean,
        text: String,
        errorResult: AgentResult? = null,
        persist: () -> Unit
    ) {
        if (replace) {
            replaceAgentTurnForRequest(agentId, request, text, errorResult)
        } else {
            add(
                agentId,
                ThreadTurn(
                    fromUser = false,
                    text = text,
                    label = request.label,
                    errorResult = errorResult,
                    request = request
                )
            )
        }
        persist()
    }

    @Synchronized
    fun turns(agentId: String): List<ThreadTurn> =
        threads[agentId]?.toList() ?: emptyList()

    /** Remove the newest turn (used when a request fails before answering). */
    @Synchronized
    fun dropLast(agentId: String) {
        // removeLastOrNull: an empty thread must be tolerated - double-failure
        // paths can call this twice and removeLast would crash the hub window.
        threads[agentId]?.removeLastOrNull()
    }

    private const val STORE = "hub-threads.json"

    @Synchronized
    fun persist(context: android.content.Context) {
        try {
            val arr = org.json.JSONArray()
            threads.forEach { (id, queue) ->
                val obj = org.json.JSONObject().put("id", id)
                val turns = org.json.JSONArray()
                queue.forEach { turn ->
                    turns.put(
                        org.json.JSONObject()
                            .put("fromUser", turn.fromUser)
                            .put("text", turn.text)
                            .put("label", turn.label)
                    )
                }
                obj.put("turns", turns)
                arr.put(obj)
            }
            context.openFileOutput(STORE, android.content.Context.MODE_PRIVATE).use { out ->
                out.write(arr.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            android.util.Log.w("ConversationThreads", "persist failed", e)
        }
    }

    @Synchronized
    fun load(context: android.content.Context) {
        if (threads.isNotEmpty()) return
        try {
            val file = context.getFileStreamPath(STORE)
            if (!file.isFile || file.length() == 0L) return
            val raw = context.openFileInput(STORE).bufferedReader().readText()
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val queue = ArrayDeque<ThreadTurn>()
                val turns = obj.optJSONArray("turns") ?: continue
                for (j in 0 until turns.length()) {
                    val t = turns.optJSONObject(j) ?: continue
                    queue.addLast(
                        ThreadTurn(
                            fromUser = t.optBoolean("fromUser"),
                            text = t.optString("text"),
                            label = t.optString("label")
                        )
                    )
                }
                if (queue.isNotEmpty()) threads[id] = queue
            }
        } catch (e: Exception) {
            android.util.Log.w("ConversationThreads", "load failed", e)
        }
    }

    /**
     * Compact last-N transcript for the follow-up prompt. Null when empty.
     * [excludeTrailingTurns] drops the newest turns first — postPrompt uses it
     * to keep the just-recorded current question out of the block, because the
     * question is appended verbatim afterwards (including it sent the current
     * question twice).
     */
    @Synchronized
    fun contextBlock(agentId: String, maxTurns: Int = 8, excludeTrailingTurns: Int = 0): String? {
        val queue = threads[agentId]?.takeIf { it.isNotEmpty() } ?: return null
        val recent = queue.dropLast(excludeTrailingTurns).takeLast(maxTurns)
        if (recent.isEmpty()) return null
        val lines = recent.filter { it.errorResult == null }.map { turn ->
            val speaker = if (turn.fromUser) "User" else "You"
            "$speaker: \"${turn.text.take(300)}\""
        }
        return "Conversation so far:\n" + lines.joinToString("\n")
    }
}

