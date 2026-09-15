package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Conversation mode: persistent bubble thread + follow-up row ----





// ---- Thread actions ----

internal fun AgentHubWindow.sendFollowUp(agentId: String) {
    val text = followUpText.trim()
    if (text.isEmpty()) return
    if (isIncognitoContext) {
        detailState = DetailState.Hint("Agent disabled in private fields")
        return
    }
    followUpText = ""
    // The spoken/typed question IS the prompt — same contract as Ask by voice.
    postPrompt(
        AgentRequest(
            agentId = agentId,
            label = "Follow-up",
            promptPrefix = "",
            userText = text,
            draftDeleteCount = 0
        )
    )
}

internal fun AgentHubWindow.regenerateLast(lastRequest: AgentRequest?) {
    if (lastRequest != null) postPrompt(lastRequest, recordUser = false)
}

internal fun AgentHubWindow.runTranslate(agentId: String, language: String) {
    // Belt-and-braces guard mirroring runOption.
    if (isPasswordContext) return
    if (isIncognitoContext) {
        detailState = DetailState.Hint("Agent disabled in private fields")
        return
    }
    translateText = ""
    val draft = captureDraft(wholeField)

    if (draft.isBlank()) {
        detailState = DetailState.Hint("Type something in the field first")
        return
    }

    postPrompt(
        AgentRequest(
            agentId = agentId,
            label = "$TRANSLATE_LABEL ($language)",
            promptPrefix = translatePrefix(language),
            userText = draft,
            draftDeleteCount = if (wholeField) 0 else draft.codePointCount(0, draft.length),
            wholeField = wholeField
        )
    )
}

// ---- Actions ----

/**
 * Upper bound on the draft read from the field, per side of the cursor. Large
 * enough for any message or email body; bounded so a pasted novel can't be
 * shipped wholesale to the agent.
 */
internal const val HUB_DRAFT_CAPTURE_CHARS = 8000

/**
 * Read the user's draft straight from the live InputConnection. Regression
 * (2026-09-07): this used to read `createInputTransaction().textContext`,
 * whose before/after windows are Constants.VOICE_INPUT_CONTEXT_SIZE = 3
 * characters — the dictation sanitizer's spacing context, never meant as a
 * draft. Every hub option was therefore sending the last three characters of
 * the field ("se.") and agents replied "nothing to summarize".
 */
internal fun AgentHubWindow.captureDraft(wholeField: Boolean): String {
    val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return ""
    val beforeCursor = ic.getTextBeforeCursor(HUB_DRAFT_CAPTURE_CHARS, 0)?.toString().orEmpty()
    if (!wholeField) return beforeCursor
    val afterCursor = ic.getTextAfterCursor(HUB_DRAFT_CAPTURE_CHARS, 0)?.toString().orEmpty()
    return beforeCursor + afterCursor
}

internal fun AgentHubWindow.runOption(agentId: String, option: HubOption) {
    // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
    if (isPasswordContext) {
        // Same feedback the incognito path gives — never a silent no-op.
        detailState = DetailState.Hint("Agent disabled in password fields")
        return
    }
    if (isIncognitoContext) {
        detailState = DetailState.Hint("Agent disabled in private fields")
        return
    }

    val draft = captureDraft(wholeField)

    if (draft.isBlank()) {
        detailState = DetailState.Hint("Type something in the field first")
        return
    }

    // Delete count for a later Replace: CODE_DELETE removes one code point
    // per event, so count code points, not UTF-16 units (an emoji is two
    // units). Grapheme clusters (emoji + modifiers) can still drift by one
    // — acceptable approximation for the prototype.
    // Whole-field captures get 0: Replace deletes backward only, so it
    // can't undo text captured after the cursor — hiding the button (same
    // mechanism as dictation) beats a half-correct replace.
    postPrompt(
        AgentRequest(
            agentId = agentId,
            label = if (wholeField) "${option.label} (whole field)" else option.label,
            promptPrefix = option.promptPrefix,
            userText = draft,
            draftDeleteCount = if (wholeField) 0 else draft.codePointCount(0, draft.length),
            wholeField = wholeField
        )
    )
}

/**
 * Shared tail of every prompt path (button options, voice dictation,
 * follow-ups, and Regenerate): Thinking on the Main dispatcher, POST off
 * it, result back on Main. The conversation thread gets the user turn up
 * front — so a slow answer never loses what was asked — and the agent's
 * turn on success. Failed requests become an error turn inside the thread
 * with a Retry chip instead of a generic error screen. Successful answers
 * are recorded to AgentMemory (the detail header's exchange counter and
 * Copy-last-answer read it), and every outcome is scheduled to persist AFTER
 * the turn mutation (the file write itself runs on Dispatchers.IO) so an IME
 * restart loses nothing. Regenerate passes
 * recordUser=false: its user turn is already in the thread, and the new
 * answer REPLACES the bubble that answered its request instead of
 * duplicating the pair.
 */
internal fun AgentHubWindow.postPrompt(request: AgentRequest, recordUser: Boolean = true) {
    val name = agentName(request.agentId)

    if (isIncognitoContext) {
        detailState = DetailState.Hint("Agent disabled in private fields")
        return
    }

    val token = agentToken()
    if (token.isBlank()) {
        detailState = DetailState.Hint("Agent token is not set. Open Settings → Agents.")
        return
    }

    // A previous request may still be in flight (rapid re-prompt): cancel it
    // and drop ITS recorded user turn BEFORE recording this one — otherwise
    // the old turn dangles unanswered forever (and dropLast could eat the NEW
    // turn when both requests target the same agent).
    promptJob?.cancel()
    promptJob = null
    dropRecordedUserTurn()

    if (recordUser) {
        ConversationThreads.add(
            request.agentId,
            ThreadTurn(fromUser = true, text = request.userText, label = request.label)
        )
        threadVersion++
    }

    // Where a Cancel returns: requests fire from Options (action buttons) or
    // Thread (follow-ups/Regenerate); anything else (dictation) falls back to
    // Options rather than landing on a dead dictation panel.
    promptReturnState = if (detailState == DetailState.Thread) DetailState.Thread else DetailState.Options
    promptRecordedUserTurn = if (recordUser) request.agentId else null

    detailState = DetailState.Thinking
    promptJob = manager.getLifecycleScope().launch(Dispatchers.IO) {
        // The composed prompt IS the text — postToAgent takes the full string.
        // A custom agent's system prompt goes first (it's the standing
        // instruction), then the live thread so follow-ups continue the real
        // conversation, then AgentMemory for older summary context. The thread
        // block EXCLUDES the turn just recorded for this request — it is
        // appended verbatim below, so including it sent the question twice.
        val custom = customAgent(request.agentId)
        val threadContext = ConversationThreads.contextBlock(
            request.agentId,
            excludeTrailingTurns = if (recordUser) 1 else 0
        )
        val memoryContext = AgentMemory.contextBlock(request.agentId)
        val prompt = buildString {
            if (custom != null && custom.systemPrompt.isNotBlank()) {
                append(custom.systemPrompt)
                append("\n\n")
            }
            if (threadContext != null) {
                append(threadContext)
                append("\n\n")
            }
            if (memoryContext != null) {
                append(memoryContext)
                append("\n\n")
            }
            if (threadContext != null || memoryContext != null) {
                append("Respond to the latest request.\n\n")
            }
            append(request.promptPrefix)
            // Whole-field captures are labeled so the agent doesn't treat
            // text after the cursor as part of an unfinished draft.
            if (request.wholeField) append("[Full field contents, before and after the cursor]\n")
            append(request.userText)
        }
        val endpoint = custom?.endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_ENDPOINT
        val result = postToAgent(prompt, token, request.agentId, endpoint)
        // Log outcome + size only — answer CONTENT must never reach logcat.
        Log.i(
            "AgentHub",
            "answer from ${request.agentId}: success=${result is AgentResult.Success} " +
                "chars=${(result as? AgentResult.Success)?.text?.length ?: 0}"
        )
        withContext(Dispatchers.Main) {
            promptRecordedUserTurn = null
            when (result) {
                is AgentResult.Success -> {
                    lastAnswerRequests = lastAnswerRequests + (request.agentId to request)
                    AgentMemory.record(request.agentId, request.label, request.userText, result.text)
                    // applyResult mutates the thread, THEN persists — persisting
                    // before the mutation used to lose every answer on restart.
                    // The lambda runs on Main; persistThreadsAsync moves the
                    // actual file write to Dispatchers.IO.
                    ConversationThreads.applyResult(
                        request.agentId, request, replace = !recordUser, text = result.text
                    ) {
                        persistThreadsAsync()
                    }
                    threadVersion++
                    detailState = DetailState.Thread
                }
                else -> {
                    val message = agentResultMessage(result, name)
                    ConversationThreads.applyResult(
                        request.agentId, request, replace = !recordUser,
                        text = message, errorResult = result
                    ) {
                        persistThreadsAsync()
                    }
                    threadVersion++
                    detailState = DetailState.Thread
                }
            }
        }
    }
}

/**
 * Persist threads without blocking the UI thread. applyResult's persist lambda
 * runs on Main right after the turn mutation (preserving the mutate-then-
 * persist order); this moves the actual file write to Dispatchers.IO. Safe
 * against interleaving: persist() is @Synchronized on ConversationThreads and
 * always snapshots the latest state, so concurrent writes serialize and an
 * older scheduled write can never clobber a newer state.
 */
internal fun AgentHubWindow.persistThreadsAsync() {
    manager.getLifecycleScope().launch(Dispatchers.IO) {
        ConversationThreads.persist(context)
    }
}

/**
 * Drop a cancelled/superseded request's recorded user turn, if any, and
 * persist — a request that will never answer must not leave its user bubble
 * dangling in the thread. Shared by cancelInFlightPrompt and the rapid
 * re-prompt path in postPrompt.
 */
internal fun AgentHubWindow.dropRecordedUserTurn() {
    promptRecordedUserTurn?.let { agentId ->
        ConversationThreads.dropLast(agentId)
        persistThreadsAsync()
        threadVersion++
        promptRecordedUserTurn = null
    }
}

internal fun AgentHubWindow.cancelInFlightPrompt() {
    promptJob?.cancel()
    promptJob = null
    // A cancelled request never answers: drop the user turn it recorded (no
    // dangling unanswered bubble) and return to where the request started —
    // Options for action buttons, Thread for follow-ups/Regenerate.
    dropRecordedUserTurn()
    detailState = promptReturnState
}

internal fun AgentHubWindow.insertAnswer(answer: String) {
    manager.typeText(answer)
    manager.closeActionWindow()
}

/** How a Replace may proceed, decided by planReplace(). */
internal enum class ReplacePlan { DELETE_THEN_INSERT, PLAIN_INSERT }

/**
 * Delete-then-insert is only safe when the field STILL ends with the exact
 * draft the request captured: if the user edited the field while the request
 * was in flight, backspacing would delete their new text — fall back to a
 * plain insert instead. Pure + top-level so the rule is unit-testable.
 */
internal fun planReplace(beforeCursor: String, draftDeleteCount: Int, capturedDraft: String): ReplacePlan {
    if (draftDeleteCount <= 0) return ReplacePlan.PLAIN_INSERT
    if (beforeCursor.codePointCount(0, beforeCursor.length) < draftDeleteCount) {
        return ReplacePlan.PLAIN_INSERT
    }
    val suffixStart = beforeCursor.offsetByCodePoints(beforeCursor.length, -draftDeleteCount)
    return if (beforeCursor.substring(suffixStart) == capturedDraft) {
        ReplacePlan.DELETE_THEN_INSERT
    } else {
        ReplacePlan.PLAIN_INSERT
    }
}

/**
 * Replace draft: delete exactly what the request captured before the
 * cursor, then type the answer in its place. ActionInputTransaction has no
 * replace/setText capability, and KeyboardManagerForAction's only deletion
 * API is backspace(amount) — so delete-then-type is the clean
 * implementation with the APIs that exist. planReplace() verifies the field
 * still ends with the captured draft first; any mismatch degrades to a
 * plain insert rather than deleting whatever happens to be there.
 */
internal fun AgentHubWindow.replaceDraftWith(answer: String, request: AgentRequest) {
    // Same live read as captureDraft(): the 3-char textContext window made
    // planReplace() see "se." instead of the draft, so Replace always degraded
    // to "Field changed — inserting instead" for any draft over 3 characters.
    val before = captureDraft(wholeField = false)
    if (planReplace(before, request.draftDeleteCount, request.userText) == ReplacePlan.PLAIN_INSERT) {
        manager.announce("Field changed — inserting instead")
        manager.typeText(answer)
        manager.closeActionWindow()
        return
    }
    manager.backspace(request.draftDeleteCount)
    manager.typeText(answer)
    manager.closeActionWindow()
}

internal fun AgentHubWindow.copyAnswer(answer: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Agent answer", answer))
    // Stay in the window — the user may want to Copy and then Insert too.
    // announce() gives confirmation without new UI state.
    manager.announce("Answer copied")
}
