package org.futo.inputmethod.latin.uix.actions

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.IncognitoState
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.UixActionKeyboardManager
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.latin.xlm.correction.CorrectionOutcome
import org.futo.inputmethod.latin.xlm.correction.ON_DEVICE_LLM_CORRECTION
import org.futo.inputmethod.latin.xlm.correction.OnDeviceCorrection
import java.util.concurrent.TimeUnit

// Master toggle for the AI "Fix" chip. Cornerstone ships with the local route
// enabled, so this is safe to expose by default; the network fallback remains
// separately opt-in.
val AI_AUTOCORRECT_ENABLED = SettingsKey(
    key = booleanPreferencesKey("ai_autocorrect_enabled"),
    default = true
)

private const val AI_AUTOCORRECT_AGENT_FALLBACK = DEFAULT_AGENT_ID

private const val AI_FIX_PROMPT_PREFIX =
    "Fix spelling and grammar mistakes. Keep the same language, tone, and meaning. " +
            "Output ONLY the corrected sentence, nothing else:"

// Sentence capture is bounded: getTextBeforeCursor is capped and an over-long
// sentence simply gets no chip rather than sending a truncated fragment.
internal const val MAX_SENTENCE_CHARS = 300

// A lone "Ok." or "Hi!" is not worth a network round-trip; the chip only
// appears when there is enough text for a fix to plausibly change something.
internal const val MIN_SENTENCE_CHARS = 8

// The chip is transient: it expires on its own after a few seconds, or sooner
// on the next input event that is not the sentence's own trailing whitespace.
internal const val OFFER_TTL_MS = 6_000L

// How long capture stays suppressed after a fix is applied (the applied fix
// fires input events whose tail is a sentence end again).
private const val APPLY_SUPPRESS_MS = 1_500L

// Short-timeout client mirroring DictationPolish: a fix suggestion must never
// block typing, so the whole attempt is hard-capped and fails silent.
private const val AI_FIX_TIMEOUT_MS = 12_000L

private val aiFixHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(AI_FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '…')

// Words whose trailing period does NOT end a sentence (titles and common
// abbreviations). Matched lowercase against the word immediately before a
// candidate ". " boundary. Multi-part abbreviations like "e.g." / "i.e." need
// no entry: the letter before their final dot is a single letter, which the
// initial rule below already treats as abbreviation-like.
private val ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "st", "vs", "etc", "jr", "sr", "prof", "ltd",
    "inc", "corp", "no", "fig", "al", "gen", "col", "lt", "sgt", "capt",
    "rev", "hon", "pres", "gov", "sen", "rep", "vol", "pp", "approx", "dept",
    "est", "misc", "ave", "blvd", "rd", "mt", "ft"
)

/**
 * What the chip captured when the sentence ended. [sentence] includes its
 * ending punctuation but no surrounding whitespace; it is the text sent to
 * the agent. [trailingWhitespace] is the whitespace run typed AFTER the
 * terminator (usually the autospace) — it is deleted together with the
 * sentence and re-appended after the fixed text, so the user's separating
 * space survives. The suffix the field must still end with at apply time is
 * exactly `sentence + trailingWhitespace`; [deleteCodePoints] counts the code
 * points of that SAME concatenation (not UTF-16 units — the backspace path
 * deletes one code point per event), so the plan check and the delete count
 * can never disagree. [terminator] is the trailing run of terminator
 * characters ("." / "!!" / "..."), re-appended by [normalizeFixedSentence]
 * when the agent drops it.
 */
internal data class SentenceCapture(
    val sentence: String,
    val deleteCodePoints: Int,
    val terminator: String,
    val trailingWhitespace: String
)

/** True when the word before a candidate ". " boundary is abbreviation-like. */
private fun isAbbreviationBeforeDot(text: String, dotIndex: Int): Boolean {
    var i = dotIndex - 1
    while (i >= 0 && text[i].isLetter()) i--
    val word = text.substring(i + 1, dotIndex)
    if (word.isEmpty()) return false // "). " style — a real boundary
    // A single letter is treated as an initial ("J. R. R. Tolkien") — never a
    // boundary. Lowercase single letters ("...a. b") are rare and harmless.
    if (word.length == 1) return true
    return word.lowercase() in ABBREVIATIONS
}

/**
 * Cheap pre-check on the last few characters before the cursor: did the user
 * just end a sentence? True when the text ends with a sentence terminator,
 * optionally followed by trailing whitespace (the space/newline typed right
 * after the punctuation). Called on every input event with a 2-char read, so
 * it must stay allocation-light.
 */
internal fun isSentenceEndTriggerSuffix(text: String): Boolean {
    if (text.isEmpty()) return false
    val trimmed = text.trimEnd { it.isWhitespace() }
    return trimmed.isNotEmpty() && trimmed.last() in SENTENCE_TERMINATORS
}

/**
 * Extract the last sentence from [beforeCursor], or null when no fixable
 * sentence ends at the cursor. The field may end with whitespace after the
 * terminator (the user already typed the separating space/newline); that
 * whitespace is recorded as [SentenceCapture.trailingWhitespace] and becomes
 * part of the deleted-and-restored region — see [SentenceCapture].
 *
 * Sentence-start heuristic: scan backwards from the final terminator for the
 * previous newline or "[terminator][whitespace]" pair. A ". " pair is skipped
 * when the word before the dot looks like an abbreviation or initial (see
 * [ABBREVIATIONS]), so "Mr. Smith left." captures the whole sentence instead
 * of "Smith left.". This is a heuristic by design — "I met him at St. Louis."
 * style false positives still mis-split, but a wrong capture is harmless: the
 * chip is opt-in per tap and the suffix check at apply time protects the text.
 *
 * Returns null when the sentence is shorter than [MIN_SENTENCE_CHARS] or
 * longer than [maxChars] (no truncated fragments are ever sent).
 */
internal fun extractSentenceCapture(
    beforeCursor: String,
    maxChars: Int = MAX_SENTENCE_CHARS
): SentenceCapture? {
    val core = beforeCursor.trimEnd { it.isWhitespace() }
    if (core.isEmpty()) return null
    if (core.last() !in SENTENCE_TERMINATORS) return null
    // trimEnd only removes from the end, so everything past core is exactly
    // the trailing whitespace run (empty when the cursor sits right after the
    // terminator).
    val trailingWhitespace = beforeCursor.substring(core.length)

    var terminatorStart = core.length - 1
    while (terminatorStart > 0 && core[terminatorStart - 1] in SENTENCE_TERMINATORS) {
        terminatorStart--
    }
    val terminator = core.substring(terminatorStart)

    var start = 0
    var i = terminatorStart - 1
    while (i > 0) {
        val c = core[i]
        if (c == '\n') {
            start = i + 1
            break
        }
        if (c in SENTENCE_TERMINATORS && core[i + 1].isWhitespace()) {
            if (c == '.' && isAbbreviationBeforeDot(core, i)) {
                i--
                continue
            }
            start = i + 1
            break
        }
        i--
    }

    val sentence = core.substring(start).trimStart()
    if (sentence.length < MIN_SENTENCE_CHARS) return null
    if (sentence.length > maxChars) return null
    // deleteCodePoints covers sentence + trailingWhitespace — the exact suffix
    // planAiFix() verifies — so the apply path deletes precisely what it checked.
    val deleted = sentence + trailingWhitespace
    return SentenceCapture(
        sentence = sentence,
        deleteCodePoints = deleted.codePointCount(0, deleted.length),
        terminator = terminator,
        trailingWhitespace = trailingWhitespace
    )
}

/** How a tapped fix may proceed, decided by [planAiFix]. */
internal enum class AiFixPlan { DELETE_THEN_INSERT, ABORT }

/**
 * The fix deletes backward only, so it is safe solely when the field STILL
 * ends with exactly `capture.sentence + capture.trailingWhitespace` — the
 * same region [SentenceCapture.deleteCodePoints] counts. If anything about
 * that suffix changed while the request was in flight (more typing, an added
 * or removed space, a moved cursor), abort — never backspace over the user's
 * text. Mirrors planReplace()'s contract in AgentHubActions, but aborts
 * instead of falling back to a plain insert: dumping a corrected sentence at
 * a cursor that has moved on is worse than doing nothing. Pure + top-level so
 * the rule is unit-testable.
 */
internal fun planAiFix(beforeCursor: String, capture: SentenceCapture): AiFixPlan {
    return if (beforeCursor.endsWith(capture.sentence + capture.trailingWhitespace)) {
        AiFixPlan.DELETE_THEN_INSERT
    } else {
        AiFixPlan.ABORT
    }
}

/**
 * Sanity gate on the agent's answer, mirroring DictationPolish: blank, more
 * than 2x the input, or identical-to-input answers are all rejected (the last
 * quietly — there was simply nothing to fix).
 */
internal fun validFixAnswer(answer: String, original: String): Boolean {
    val trimmed = answer.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.length > original.length * 2) return false
    if (trimmed == original.trim()) return false
    return true
}

/** Re-append the original terminator when the agent dropped it. */
internal fun normalizeFixedSentence(answer: String, capture: SentenceCapture): String {
    val trimmed = answer.trim()
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.last() in SENTENCE_TERMINATORS) return trimmed
    return trimmed + capture.terminator.ifEmpty { "." }
}

/**
 * The full guard chain for offering/tapping a fix. Password fields reuse the
 * shared [isPasswordField] guard (same one the Agent key uses); incognito
 * fields read the shared IncognitoState. Pure apart from those, so the guard
 * matrix is unit-testable.
 */
internal fun aiFixAllowed(enabled: Boolean, incognito: Boolean, inputType: Int): Boolean =
    enabled && !incognito && !isPasswordField(inputType)

/**
 * Owns the "✨ Fix with AI" chip lifecycle: watches input events (hooked from
 * UixManager.onInputEvent, which InputLogic calls after every processed
 * event), captures the just-finished sentence, offers the chip for
 * [OFFER_TTL_MS], and on tap runs the polish-style agent call and replaces
 * the sentence in place.
 *
 * Everything fails silent by design — this is a suggestion, never a blocking
 * path: no token, no endpoint, network error, timeout, or a rejected answer
 * all just drop the chip. The only user-facing feedback is the abort hint
 * when the field changed mid-request.
 */
class AiAutocorrectController(private val manager: UixActionKeyboardManager) {

    internal var offer = mutableStateOf<SentenceCapture?>(null)
        private set

    private var expiryJob: Job? = null
    private var fixJob: Job? = null

    // Applying a fix generates input events (backspaces + the typed answer)
    // whose tail still looks like a fresh sentence end — without suppression
    // the chip would immediately re-offer a fix of the just-fixed sentence.
    @Volatile
    private var suppressCaptureUntil = 0L

    fun clear() {
        offer.value = null
        expiryJob?.cancel()
        expiryJob = null
    }

    /**
     * Called on every processed input event. Two-tier read: a 2-char tail
     * check gates the bounded 300-char capture, so ordinary typing costs one
     * small getTextBeforeCursor and nothing else.
     */
    fun onInputEvent() {
        val context = manager.getContext()
        val editorInfo = manager.getCurrentEditorInfo()
        if (!aiFixAllowed(
                enabled = context.getSetting(AI_AUTOCORRECT_ENABLED),
                incognito = IncognitoState.active,
                inputType = editorInfo?.inputType ?: 0
            )
        ) {
            clear()
            return
        }

        val onDevice = context.getSetting(ON_DEVICE_LLM_CORRECTION)
        val token = context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }
        if (!onDevice && (token.isBlank() || BuildConfig.AGENT_KEY_ENDPOINT.isBlank())) {
            clear()
            return
        }

        if (System.currentTimeMillis() < suppressCaptureUntil) {
            clear()
            return
        }

        val latinIME = manager.latinIME
        // Voice dictation overrides the input connection and owns the field —
        // stay out of its way (its own polish path handles that text).
        if (latinIME.isInputConnectionOverridden) {
            clear()
            return
        }
        val ic = latinIME.currentInputConnection ?: run {
            clear()
            return
        }

        val tail = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (!isSentenceEndTriggerSuffix(tail)) {
            // Any input that is not the sentence's own trailing whitespace
            // expires the chip.
            clear()
            return
        }

        val beforeCursor = ic.getTextBeforeCursor(MAX_SENTENCE_CHARS + 16, 0)?.toString().orEmpty()
        val capture = extractSentenceCapture(beforeCursor) ?: run {
            clear()
            return
        }

        offer.value = capture
        expiryJob?.cancel()
        expiryJob = manager.getLifecycleScope().launch {
            delay(OFFER_TTL_MS)
            if (offer.value === capture) offer.value = null
        }
    }

    /** Chip tapped: run the fix. Fails silent on every network-side problem. */
    fun onChipTapped() {
        val capture = offer.value ?: return
        val context = manager.getContext()
        val editorInfo = manager.getCurrentEditorInfo()
        if (!aiFixAllowed(
                enabled = context.getSetting(AI_AUTOCORRECT_ENABLED),
                incognito = IncognitoState.active,
                inputType = editorInfo?.inputType ?: 0
            )
        ) {
            clear()
            return
        }
        clear()

        val onDevice = context.getSetting(ON_DEVICE_LLM_CORRECTION)
        val token = context.getSetting(AGENT_KEY_TOKEN).ifBlank { BuildConfig.AGENT_KEY_TOKEN }
        if (!onDevice && (token.isBlank() || BuildConfig.AGENT_KEY_ENDPOINT.isBlank())) return

        fixJob?.cancel()
        fixJob = manager.getLifecycleScope().launch(Dispatchers.IO) {
            var uncertainty = emptyList<String>()
            val answer = if (onDevice) {
                when (val outcome = OnDeviceCorrection.correct(context, capture.sentence, enabled = true)) {
                    is CorrectionOutcome.Applied -> {
                        uncertainty = outcome.uncertain
                        outcome.prediction.trim()
                    }
                    is CorrectionOutcome.Unchanged -> {
                        Log.w("AiAutocorrect", "on-device fix not applied: ${outcome.reason}")
                        null
                    }
                }
            } else {
                val result = try {
                    withTimeoutOrNull(AI_FIX_TIMEOUT_MS) {
                        postToAgent(
                            draft = "$AI_FIX_PROMPT_PREFIX\n${capture.sentence}",
                            token = token,
                            agent = context.getSetting(AGENT_KEY_AGENT)
                                .ifBlank { AI_AUTOCORRECT_AGENT_FALLBACK },
                            client = aiFixHttpClient
                        )
                    } ?: AgentResult.Timeout
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("AiAutocorrect", "unexpected fix exception", e)
                    AgentResult.Network
                }
                val text = (result as? AgentResult.Success)?.text?.trim()
                if (text == null) {
                    Log.w("AiAutocorrect", "fix not applied: $result")
                }
                text
            }
            if (answer == null || !validFixAnswer(answer, capture.sentence)) {
                return@launch
            }

            withContext(Dispatchers.Main) {
                val ic = manager.latinIME.currentInputConnection ?: return@withContext
                val beforeCursor =
                    ic.getTextBeforeCursor(MAX_SENTENCE_CHARS + 16, 0)?.toString().orEmpty()
                if (planAiFix(beforeCursor, capture) == AiFixPlan.ABORT) {
                    manager.announce(context.getString(R.string.ai_autocorrect_field_changed))
                    return@withContext
                }
                // Suppress recapture BEFORE mutating the field: the backspaces
                // and the typed answer below fire input events whose tail is a
                // sentence end again.
                suppressCaptureUntil = System.currentTimeMillis() + APPLY_SUPPRESS_MS
                // deleteCodePoints covers sentence + trailingWhitespace (the
                // exact suffix planAiFix verified); the whitespace is typed
                // back after the fixed sentence so the autospace survives.
                manager.backspace(capture.deleteCodePoints)
                manager.typeText(normalizeFixedSentence(answer, capture) + capture.trailingWhitespace)
                if (uncertainty.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.on_device_uncertainty_warning,
                            uncertainty.take(3).joinToString(", "),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}

/**
 * The strip chip, styled after QuickClipPill: a quiet rounded pill that takes
 * over the suggestion area while the offer is live (the QuickClip precedent
 * for transient strip content). Suggestions return when it expires.
 */
@Composable
fun RowScope.AiFixChip(onTap: () -> Unit) {
    val label = stringResource(R.string.ai_autocorrect_chip_label)
    Box(
        Modifier
            .weight(1.0f)
            .fillMaxHeight()
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .clickable { onTap() }
                .padding(4.dp)
                .clearAndSetSemantics {
                    this.role = Role.Button
                    this.contentDescription = label
                    this.onClick(action = { onTap(); true })
                }
        ) {
            Surface(
                color = LocalKeyboardScheme.current.keyboardContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalKeyboardScheme.current.onKeyboardContainer
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxHeight()
                    ) {
                        Text(label, style = Typography.Small)
                    }
                }
            }
        }
    }
}
