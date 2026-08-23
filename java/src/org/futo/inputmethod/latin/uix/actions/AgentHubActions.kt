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
    val transaction = manager.createInputTransaction()
    val beforeCursor = transaction.textContext.beforeCursor?.toString().orEmpty()
    val draft = if (wholeField) {
        beforeCursor + transaction.textContext.afterCursor?.toString().orEmpty()
    } else {
        beforeCursor
    }
    transaction.cancel()

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

internal fun AgentHubWindow.runOption(agentId: String, option: HubOption) {
    // Belt-and-braces: buttons are disabled in these states, but never rely on UI state alone.
    if (isPasswordContext) return
    if (isIncognitoContext) {
        detailState = DetailState.Hint("Agent disabled in private fields")
        return
    }

    // Read-only transaction: capture the draft, then cancel so nothing changes.
    val transaction = manager.createInputTransaction()
    val beforeCursor = transaction.textContext.beforeCursor?.toString().orEmpty()
    val draft = if (wholeField) {
        beforeCursor + transaction.textContext.afterCursor?.toString().orEmpty()
    } else {
        beforeCursor
    }
    transaction.cancel()

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
 * with a Retry chip instead of a generic error screen. AgentMemory records
 * successful answers exactly as before. Regenerate passes recordUser=false:
 * its user turn is already in the thread, and the new answer REPLACES the
 * old agent/error bubble instead of duplicating the pair.
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

    if (recordUser) {
        ConversationThreads.add(
            request.agentId,
            ThreadTurn(fromUser = true, text = request.userText, label = request.label)
        )
        threadVersion++
    }

    detailState = DetailState.Thinking
    promptJob?.cancel()
    promptJob = manager.getLifecycleScope().launch(Dispatchers.IO) {
        // The composed prompt IS the text — postToAgent takes the full string.
        // A custom agent's system prompt goes first (it's the standing
        // instruction), then the live thread so follow-ups continue the real
        // conversation, then AgentMemory for older summary context.
        val custom = customAgent(request.agentId)
        val threadContext = ConversationThreads.contextBlock(request.agentId)
        val prompt = buildString {
            if (custom != null && custom.systemPrompt.isNotBlank()) {
                append(custom.systemPrompt)
                append("\n\n")
            }
            if (threadContext != null) {
                append(threadContext)
                append("\n\nRespond to the latest request.\n\n")
            }
            append(request.promptPrefix)
            // Whole-field captures are labeled so the agent doesn't treat
            // text after the cursor as part of an unfinished draft.
            if (request.wholeField) append("[Full field contents, before and after the cursor]\n")
            append(request.userText)
        }
        val endpoint = custom?.endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_ENDPOINT
        val result = postToAgent(prompt, token, request.agentId, endpoint)
        val answerText = (result as? AgentResult.Success)?.text
        Log.i("AgentHub", "answer from ${request.agentId}: success=${result is AgentResult.Success} head=${answerText?.take(60)}")
        withContext(Dispatchers.Main) {
            when (result) {
                is AgentResult.Success -> {
                    lastAnswerRequest = request
                    ConversationThreads.persist(context)
                    if (recordUser.not()) {
                        // Regenerate: swap the previous agent/error bubble for the new answer.
                        ConversationThreads.replaceLastAgentTurn(request.agentId, result.text)
                    } else {
                        ConversationThreads.add(
                            request.agentId,
                            ThreadTurn(fromUser = false, text = result.text, label = request.label)
                        )
                    }
                    threadVersion++
                    detailState = DetailState.Thread
                }
                else -> {
                    val message = agentResultMessage(result, name)
                    if (recordUser.not()) {
                        // Regenerate/Retry: replace the previous agent/error bubble.
                        ConversationThreads.replaceLastAgentTurn(
                            request.agentId,
                            message,
                            errorResult = result,
                            request = request
                        )
                    } else {
                        ConversationThreads.add(
                            request.agentId,
                            ThreadTurn(
                                fromUser = false,
                                text = message,
                                label = request.label,
                                errorResult = result,
                                request = request
                            )
                        )
                    }
                    threadVersion++
                    ConversationThreads.persist(context)
                    detailState = DetailState.Thread
                }
            }
        }
    }
}

internal fun AgentHubWindow.cancelInFlightPrompt() {
    promptJob?.cancel()
    promptJob = null
    detailState = DetailState.Thread
}

internal fun AgentHubWindow.insertAnswer(answer: String) {
    manager.typeText(answer)
    manager.closeActionWindow()
}

/**
 * Replace draft: delete exactly what the request captured before the
 * cursor, then type the answer in its place. ActionInputTransaction has no
 * replace/setText capability, and KeyboardManagerForAction's only deletion
 * API is backspace(amount) — so delete-then-type is the clean
 * implementation with the APIs that exist.
 *
 * Known limitation (prototype): if the field changed after the request
 * started, backspace removes whatever is before the cursor now. The hub
 * window keeps the keyboard open, so mid-request edits are unlikely.
 */
internal fun AgentHubWindow.replaceDraftWith(answer: String, draftDeleteCount: Int) {
    val transaction = manager.createInputTransaction()
    val before = transaction.textContext.beforeCursor?.toString().orEmpty()
    transaction.cancel()
    val actual = before.codePointCount(0, before.length)
    if (draftDeleteCount <= 0 || actual < draftDeleteCount) {
        manager.announce("Field changed — inserting instead")
        manager.typeText(answer)
        manager.closeActionWindow()
        return
    }
    manager.backspace(draftDeleteCount)
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
