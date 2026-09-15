package org.futo.inputmethod.latin.xlm.correction

sealed class CorrectionOutcome {
    data class Applied(val prediction: String, val uncertain: List<String>) : CorrectionOutcome()
    data class Unchanged(val reason: String) : CorrectionOutcome()
}

object CorrectionPolicy {
    private val negation = Regex(
        """\b(?:no|not|never|without|don['’]?t|doesn['’]?t|didn['’]?t|isn['’]?t|wasn['’]?t|won['’]?t|can['’]?t|cannot)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val profanity = Regex(
        """\b(?:fuck(?:ing|ed|er|ers)?|shit(?:ty)?|bitch(?:es)?|asshole(?:s)?|dick(?:s)?|cunt(?:s)?|bastard(?:s)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitAmbiguity = Regex(
        """\b(?:not sure|maybe|perhaps|i forget|i forgot|i think|you know|something|the thing|same thing|either|or maybe|no wait|the other|which one)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val numberWords = Regex(
        """\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|to|too|for)(?:[\s,-]+(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|to|too|for)){2,}\b""",
        RegexOption.IGNORE_CASE,
    )
    private val token = Regex("[\\p{L}\\p{N}+']+")
    private val lexicalStopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "for", "from", "i", "in", "is", "it", "of", "on",
        "or", "that", "the", "this", "to", "was", "with", "you",
    )

    fun decide(
        enabled: Boolean,
        raw: String,
        modelOutput: String?,
        timedOut: Boolean,
        generation: Long,
        currentGeneration: Long,
    ): CorrectionOutcome {
        if (!enabled) return CorrectionOutcome.Unchanged("disabled")
        if (generation != currentGeneration) return CorrectionOutcome.Unchanged("stale")
        if (timedOut) return CorrectionOutcome.Unchanged("timeout")
        val parsed = CorrectionJson.parse(modelOutput ?: "")
            ?: return CorrectionOutcome.Unchanged("parse")
        if (parsed.prediction.isBlank()) return CorrectionOutcome.Unchanged("blank")
        if (parsed.prediction.length > raw.length * 2 + 32) return CorrectionOutcome.Unchanged("length")
        if (negation.findAll(raw).count() != negation.findAll(parsed.prediction).count()) {
            return CorrectionOutcome.Unchanged("negation")
        }
        if (profanity.findAll(raw).map { it.value.lowercase() }.toSet().any { it !in parsed.prediction.lowercase() }) {
            return CorrectionOutcome.Unchanged("profanity")
        }
        val rawDigits = Regex("\\d+").findAll(raw).map { it.value }.toList()
        if (rawDigits.isNotEmpty() && rawDigits != Regex("\\d+").findAll(parsed.prediction).map { it.value }.toList()) {
            return CorrectionOutcome.Unchanged("numbers")
        }

        val uncertainty = linkedSetOf<String>()
        parsed.uncertain.mapTo(uncertainty) { it.trim() }
        if (explicitAmbiguity.containsMatchIn(raw)) uncertainty += "ambiguous wording"
        if (numberWords.containsMatchIn(raw)) uncertainty += "number wording"
        uncertainty += suspiciousLexicalChanges(raw, parsed.prediction)

        return CorrectionOutcome.Applied(
            parsed.prediction,
            uncertainty.filter { it.isNotBlank() }.distinct().take(5),
        )
    }

    private fun suspiciousLexicalChanges(raw: String, prediction: String): List<String> {
        val before = token.findAll(raw.lowercase()).map { it.value }.toList()
        val after = token.findAll(prediction.lowercase()).map { it.value }.toList()
        val afterRemaining = after.toMutableList()
        val removed = mutableListOf<String>()
        for (word in before) {
            val exact = afterRemaining.indexOf(word)
            if (exact >= 0) {
                afterRemaining.removeAt(exact)
            } else {
                removed += word
            }
        }
        return removed.asSequence()
            .filter { it.length >= 5 && it !in lexicalStopWords }
            .filter { old -> afterRemaining.none { replacement -> editDistance(old, replacement) <= 2 } }
            .distinct()
            .take(2)
            .map { "changed '$it'" }
            .toList()
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }
}
