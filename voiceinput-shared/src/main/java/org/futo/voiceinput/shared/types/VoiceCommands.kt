package org.futo.voiceinput.shared.types

/**
 * What the caller should do with a final transcript after voice-command
 * post-processing (Parakeet path only — see ModelLoader.supportsVoiceCommands).
 */
sealed interface ProcessedVoiceResult {
    /** Plain text to commit. Inline commands ("new line") are already rewritten. */
    data class InsertText(val text: String) : ProcessedVoiceResult

    /** "delete that": remove the last sentence committed this session. */
    data object DeleteLastSentence : ProcessedVoiceResult

    /** "send it": trigger the field's IME action (send/go). */
    data object PerformImeAction : ProcessedVoiceResult
}

/**
 * Phrase-level voice commands applied to a FINAL transcript before commit.
 *
 * Two command shapes, deliberately conservative:
 *  - Standalone commands ("delete that", "send it") match only when they ARE the
 *    whole utterance. Interpreting them mid-sentence would misfire constantly
 *    ("send it to my lawyer" is not a send command).
 *  - Inline commands ("new line", "new paragraph", "period", "comma", etc.) rewrite
 *    in place wherever they appear, since they almost never occur literally in
 *    dictated prose.
 *
 * The guarantee that matters most: speech that matches no command comes back as
 * InsertText unchanged — nothing is ever swallowed.
 */
object VoiceCommandProcessor {

    private val DELETE_PHRASES = setOf(
        "delete that", "delete this", "delete the last sentence", "undo that"
    )

    private val SEND_PHRASES = setOf(
        "send it", "send this", "send that", "send message"
    )

    // "new paragraph" is replaced BEFORE "new line": their word patterns don't
    // overlap, but running the longer phrase first keeps the intent obvious.
    // Leading horizontal whitespace and a trailing punctuation mark are absorbed
    // so "Hello world. New line. Next" becomes "Hello world.\nNext", not
    // "Hello world. \n. Next".
    private val NEW_PARAGRAPH_REGEX =
        Regex("""[ \t]*\bnew paragraph\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)
    private val NEW_LINE_REGEX =
        Regex("""[ \t]*\bnew line\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)

    // Spoken punctuation is rewritten with a trailing space, absorbing surrounding
    // horizontal whitespace and any trailing punctuation the recognizer added — same
    // cleanup style as "new line". "hello period world" -> "hello. world".
    private val PERIOD_REGEX =
        Regex("""[ \t]*\b(?:period|full stop)\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)
    private val COMMA_REGEX =
        Regex("""[ \t]*\bcomma\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)
    private val QUESTION_MARK_REGEX =
        Regex("""[ \t]*\bquestion mark\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)
    private val EXCLAMATION_MARK_REGEX =
        Regex("""[ \t]*\b(?:exclamation mark|exclamation point)\b[ \t]*[.,!?;:]?[ \t]*""", RegexOption.IGNORE_CASE)

    private val SPACE_BEFORE_NEWLINE_REGEX = Regex("""[ \t]+\n""")
    private val EXCESS_NEWLINES_REGEX = Regex("""\n{3,}""")

    fun process(transcript: String): ProcessedVoiceResult {
        // Normalize for standalone-command matching: case + surrounding punctuation
        // the recognizer adds ("Send it." == "send it").
        val normalized = transcript.trim()
            .lowercase()
            .trimEnd('.', '!', '?', ',')
            .trim()

        if (normalized in DELETE_PHRASES) return ProcessedVoiceResult.DeleteLastSentence
        if (normalized in SEND_PHRASES) return ProcessedVoiceResult.PerformImeAction

        return ProcessedVoiceResult.InsertText(applyInlineCommands(transcript))
    }

    private fun applyInlineCommands(text: String): String {
        var out = NEW_PARAGRAPH_REGEX.replace(text, "\n\n")
        out = NEW_LINE_REGEX.replace(out, "\n")
        out = PERIOD_REGEX.replace(out, ". ")
        out = COMMA_REGEX.replace(out, ", ")
        out = QUESTION_MARK_REGEX.replace(out, "? ")
        out = EXCLAMATION_MARK_REGEX.replace(out, "! ")
        out = SPACE_BEFORE_NEWLINE_REGEX.replace(out, "\n")
        out = EXCESS_NEWLINES_REGEX.replace(out, "\n\n")
        return out.trim()
    }
}
