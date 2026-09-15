package org.futo.inputmethod.latin.xlm.correction

data class CorrectionJson(
    val prediction: String,
    val uncertain: List<String>,
) {
    companion object {
        private val OBJECT = Regex("""\{[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)

        fun parse(text: String): CorrectionJson? {
            var last: CorrectionJson? = null
            for (match in OBJECT.findAll(text)) {
                val parsed = parseObject(match.value) ?: continue
                if (parsed.prediction.isBlank() || parsed.prediction == "corrected text") continue
                last = parsed
            }
            return last
        }

        private fun parseObject(raw: String): CorrectionJson? {
            val prediction = stringField(raw, "prediction") ?: return null
            val uncertain = stringListField(raw, "uncertain") ?: emptyList()
            return CorrectionJson(prediction, uncertain)
        }

        private fun stringField(raw: String, name: String): String? {
            val m = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(raw) ?: return null
            return unescape(m.groupValues[1])
        }

        private fun stringListField(raw: String, name: String): List<String>? {
            val m = Regex(""""$name"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return null
            return Regex(""""((?:\\.|[^"\\])*)"""").findAll(m.groupValues[1]).map {
                unescape(it.groupValues[1])
            }.toList()
        }

        private fun unescape(s: String): String = s
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }
}
