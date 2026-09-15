package org.futo.inputmethod.latin.xlm.correction

import java.text.Normalizer

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
    private val digitWords = mapOf(
        "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )
    private val numberValues = mapOf(
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L,
        "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )
    private val numberScales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L)
    private val numberLinkers = setOf("and", "point")
    private val glossaryTokens = setOf(
        "qwen", "parakeet", "razr", "nvidia", "gguf", "lora", "sherpa", "onnx", "rocm", "cornerstone",
    )
    private val digitsOnly = Regex("\\d+")

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
        val rawLower = raw.lowercase()
        val predictionLower = parsed.prediction.lowercase()
        val rawProfanity = profanity.findAll(raw).map { it.value.lowercase() }.toSet()
        val predictionProfanity = profanity.findAll(parsed.prediction).map { it.value.lowercase() }.toSet()
        if (rawProfanity.any { it !in predictionLower }) {
            return CorrectionOutcome.Unchanged("profanity")
        }
        if (predictionProfanity.any { it !in rawLower }) {
            return CorrectionOutcome.Unchanged("introduced_profanity")
        }
        val rawDigits = Regex("\\d+").findAll(raw).map { it.value }.toList()
        if (rawDigits.isNotEmpty() && rawDigits != Regex("\\d+").findAll(parsed.prediction).map { it.value }.toList()) {
            return CorrectionOutcome.Unchanged("numbers")
        }
        val rewriteViolation = unsanctionedRewrite(raw, parsed.prediction)
        if (rewriteViolation != null) return CorrectionOutcome.Unchanged(rewriteViolation)

        val uncertainty = linkedSetOf<String>()
        parsed.uncertain.mapTo(uncertainty) { it.trim() }
        if (explicitAmbiguity.containsMatchIn(raw)) uncertainty += "ambiguous wording"
        if (numberWords.containsMatchIn(raw)) uncertainty += "number wording"
        uncertainty += suspiciousLexicalChanges(raw, parsed.prediction)
        uncertainty += diacriticAdditions(raw, parsed.prediction)

        return CorrectionOutcome.Applied(
            parsed.prediction,
            uncertainty.filter { it.isNotBlank() }.distinct().take(5),
        )
    }

    private fun unsanctionedRewrite(raw: String, prediction: String): String? {
        val after = token.findAll(prediction.lowercase()).map { it.value }.toMutableList()
        val removed = mutableListOf<String>()
        for (word in token.findAll(raw.lowercase()).map { it.value }) {
            val exact = after.indexOf(word)
            if (exact >= 0) after.removeAt(exact) else removed += word
        }
        val added = after.toList()

        val spokenNumbers = removed.filter(::isNumberWord)
        val numbersConsumed = spokenNumbers.any { it !in numberLinkers }
        if (numbersConsumed) {
            val spoken = encodeNumberWords(spokenNumbers) ?: return "numbers"
            val digitTokens = added.filter(::isDigitCarrier)
            val written = digitTokens.joinToString("") { t -> t.filter(Char::isDigit) }
            if (spoken != written) return "numbers"
        } else if (added.any { digitsOnly.matches(it) }) {
            return "numbers"
        }

        val addedPool = added.toMutableList()
        if (numbersConsumed) added.filter(::isDigitCarrier).forEach(addedPool::remove)
        val removedContent = removed.filterNot(::isNumberWord)
        val swapGlossary = addedPool
            .filter { it in glossaryTokens && removedContent.none { old -> editDistance(it, old) <= 2 } }
            .toMutableList()
        for (word in removedContent) {
            if (word.length < 4 || word in lexicalStopWords) continue
            if (addedPool.any { editDistance(word, it) <= 2 }) continue
            val swap = swapGlossary.firstOrNull() ?: return "paraphrase"
            swapGlossary.remove(swap)
            addedPool.remove(swap)
        }
        for (word in addedPool) {
            if (word.length < 4 || word in lexicalStopWords) continue
            if (removed.none { editDistance(word, it) <= 2 }) return "inserted"
        }
        return null
    }

    private fun isNumberWord(word: String): Boolean =
        word in digitWords || word in numberValues || word in numberScales || word in numberLinkers

    private fun isDigitCarrier(word: String): Boolean =
        digitsOnly.matches(word) || (word in glossaryTokens && word.any(Char::isDigit))

    private fun encodeNumberWords(words: List<String>): String? {
        if (words.all { it in digitWords }) {
            return words.joinToString("") { digitWords.getValue(it) }
        }
        var total = 0L
        var current = 0L
        val fraction = StringBuilder()
        var decimal = false
        for (word in words) {
            when {
                word in digitWords -> {
                    if (decimal) fraction.append(digitWords.getValue(word)) else current += digitWords.getValue(word).toLong()
                }
                word == "point" -> if (decimal) return null else decimal = true
                word == "and" -> Unit
                word in numberValues -> {
                    if (decimal) return null
                    current += numberValues.getValue(word)
                }
                word in numberScales -> {
                    if (decimal) return null
                    val scale = numberScales.getValue(word)
                    if (scale == 100L) current = maxOf(1L, current) * scale
                    else { total += maxOf(1L, current) * scale; current = 0L }
                }
                else -> return null
            }
        }
        return (total + current).toString() + fraction.toString()
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

    private fun diacriticAdditions(raw: String, prediction: String): List<String> {
        val rawTokens = token.findAll(raw.lowercase()).map { it.value }.toSet()
        val rawFolded = rawTokens.map(::stripDiacritics).toSet()
        return token.findAll(prediction.lowercase())
            .map { it.value }
            .distinct()
            .filter { stripDiacritics(it) != it }
            .filter { it !in rawTokens && stripDiacritics(it) in rawFolded }
            .take(2)
            .map { "added diacritic '$it'" }
            .toList()
    }

    private fun stripDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

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
