package org.futo.voiceinput.shared.text

/**
 * Conservative, always-on post-processor for final voice transcripts.
 *
 * Rules are intentionally minimal so they improve dictation output without
 * reformatting user intent:
 *  - Trim leading/trailing whitespace.
 *  - Collapse runs of two or more regular spaces into a single space.
 *  - Capitalize the first character of the output.
 *  - Capitalize the first character after sentence-ending punctuation (. ! ?)
 *    followed by whitespace.
 *  - Capitalize standalone lowercase "i" -> "I".
 */
object OfflineTextProcessor {
    // Match . ! or ? followed by one or more regular spaces and a lowercase letter.
    private val sentenceEndRegex =
        Regex("""([.!?])( +)(\p{javaLowerCase})""")

    // Tokens whose trailing period is NOT a sentence end: common abbreviations and
    // anything domain/file-like (contains an internal dot, e.g. "example.com",
    // "e.g", "file.png") are skipped so "e.g. note" and "dr. smith" stay lowercase.
    private val abbreviations = setOf(
        "e.g", "i.e", "etc", "vs", "dr", "mr", "mrs", "ms", "st", "jr", "sr",
        "no", "fig", "approx", "dept", "est", "inc", "ltd", "corp"
    )

    // Standalone lowercase "i": preceded by start-of-string or whitespace,
    // followed by end-of-string, whitespace, or punctuation. Apostrophes and
    // other word-adjacent characters are excluded so "i've" / "it's" are untouched.
    private val standaloneIRegex =
        Regex("""(?<=^|\s)i(?=\s|[.!?,:;]|$)""")

    private val multiSpaceRegex = Regex(""" {2,}""")

    /**
     * Apply the conservative offline rules to [text]. Returns the processed
     * string, which may be empty if the input is blank.
     */
    fun process(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        // Capitalize the first character of the whole output.
        var result = trimmed.replaceFirstChar { it.uppercase() }

        // Capitalize after sentence-ending punctuation.
        result = sentenceEndRegex.replace(result) { match ->
            val punctuation = match.groupValues[1]
            val spaces = match.groupValues[2]
            val letter = match.groupValues[3]
            val wordBefore = result.substring(0, match.range.first)
                .takeLastWhile { it.isLetterOrDigit() || it == '.' }
                .trimEnd('.')
                .lowercase()
            if (wordBefore.contains('.') || wordBefore in abbreviations) {
                match.value
            } else {
                "$punctuation$spaces${letter.uppercase()}"
            }
        }

        // Standalone i -> I.
        result = standaloneIRegex.replace(result, "I")

        // Collapse double (or more) spaces.
        result = multiSpaceRegex.replace(result, " ")

        return result.trim()
    }
}
