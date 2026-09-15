package org.futo.inputmethod.latin.uix.actions

/**
 * Pure "delete that" span logic, extracted from VoiceInputActionWindow for JVM
 * unit tests.
 *
 * ALL returned lengths are CODE POINT counts, not UTF-16 units:
 * manager.backspace() emits one delete key event per unit and each event
 * removes one code point (same contract as AiAutocorrect's deleteCodePoints) —
 * UTF-16 lengths would over-delete text containing emoji or other astral
 * characters (one emoji = two UTF-16 units, one backspace).
 */
internal object VoiceDeletion {
    private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\n')

    /**
     * How many code points "delete that" removes.
     *
     * Primary path: the exact string this session previously committed is still
     * at the cursor — delete precisely it. Fallback: the user typed on since
     * then, so delete the trailing sentence instead (bounded to the last word
     * when no sentence boundary exists, so a runaway delete is impossible).
     */
    fun deletionLength(beforeCursor: String, lastCommitted: String?): Int {
        if (!lastCommitted.isNullOrEmpty() && beforeCursor.endsWith(lastCommitted)) {
            return lastCommitted.codePointCount(0, lastCommitted.length)
        }
        return trailingSentenceLength(beforeCursor)
    }

    fun trailingSentenceLength(text: String): Int {
        if (text.isEmpty()) return 0

        // Trailing whitespace belongs to the sentence before it:
        // "Hello. Next. " deletes "Next. " (with its trailing space).
        var i = text.length - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return text.codePointCount(0, text.length) // field is whitespace-only before cursor

        // Consume the trailing sentence's OWN terminator(s) first, otherwise
        // the boundary scan below would stop at it and delete only that.
        while (i >= 0 && text[i] in SENTENCE_TERMINATORS) i--
        if (i < 0) return text.codePointCount(0, text.length) // text is punctuation-only (e.g. "...")

        while (i >= 0 && text[i] !in SENTENCE_TERMINATORS) i--
        if (i >= 0) return text.codePointCount(i + 1, text.length)

        // No sentence boundary anywhere: bound the damage to the last word.
        val lastSpace = text.indexOfLast { it.isWhitespace() }
        return if (lastSpace >= 0) text.codePointCount(lastSpace + 1, text.length) else 0
    }
}
