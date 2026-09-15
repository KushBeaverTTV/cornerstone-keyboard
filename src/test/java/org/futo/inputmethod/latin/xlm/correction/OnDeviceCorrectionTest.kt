package org.futo.inputmethod.latin.xlm.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceCorrectionTest {

    @Test
    fun `parser takes the last JSON object with a real prediction`() {
        val text = """
            <think>planning</think>
            {"prediction":"corrected text","uncertain":[]}
            {"prediction":"Save it as a JSONL file.","uncertain":[]}
        """.trimIndent()
        val parsed = CorrectionJson.parse(text)!!
        assertEquals("Save it as a JSONL file.", parsed.prediction)
        assertTrue(parsed.uncertain.isEmpty())
    }

    @Test
    fun `parser rejects placeholder prediction and empty output`() {
        assertNull(CorrectionJson.parse("""{"prediction":"corrected text","uncertain":[]}"""))
        assertNull(CorrectionJson.parse(""))
        assertNull(CorrectionJson.parse("no json here"))
    }

    @Test
    fun `parser keeps uncertain terms`() {
        val parsed = CorrectionJson.parse("""{"prediction":"I saw Cravenport.","uncertain":["Cravenport"]}""")!!
        assertEquals(listOf("Cravenport"), parsed.uncertain)
    }

    @Test
    fun `disabled flag never applies a model output`() {
        val result = CorrectionPolicy.decide(
            enabled = false,
            raw = "save it as a jsonl file",
            modelOutput = """{"prediction":"Save it as a JSONL file.","uncertain":[]}""",
            timedOut = false,
            generation = 1L,
            currentGeneration = 1L,
        )
        assertEquals(CorrectionOutcome.Unchanged("disabled"), result)
    }

    @Test
    fun `timeout and parse failure fall back to original text`() {
        val raw = "do not delete the folder"
        assertEquals(
            CorrectionOutcome.Unchanged("timeout"),
            CorrectionPolicy.decide(true, raw, null, timedOut = true, 1, 1),
        )
        assertEquals(
            CorrectionOutcome.Unchanged("parse"),
            CorrectionPolicy.decide(true, raw, "not json", timedOut = false, 1, 1),
        )
    }

    @Test
    fun `stale generation cannot publish`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "hello",
            modelOutput = """{"prediction":"Hello.","uncertain":[]}""",
            timedOut = false,
            generation = 1L,
            currentGeneration = 2L,
        )
        assertEquals(CorrectionOutcome.Unchanged("stale"), result)
    }

    @Test
    fun `current generation applies a valid prediction`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "the recomended dose is one caplet",
            modelOutput = """{"prediction":"The recommended dose is one caplet.","uncertain":[]}""",
            timedOut = false,
            generation = 4L,
            currentGeneration = 4L,
        )
        assertEquals(
            CorrectionOutcome.Applied("The recommended dose is one caplet.", emptyList()),
            result,
        )
    }

    @Test
    fun `explicit ambiguity is surfaced even when the model misses it`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "I think her name was Sarah or Sara",
            modelOutput = """{"prediction":"I think her name was Sarah or Sara.","uncertain":[]}""",
            timedOut = false,
            generation = 5L,
            currentGeneration = 5L,
        ) as CorrectionOutcome.Applied
        assertTrue("ambiguous wording" in result.uncertain)
    }

    @Test
    fun `semantic substitutions are surfaced for review`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "send it to the cravenport account",
            modelOutput = """{"prediction":"Send it to the Craven account.","uncertain":[]}""",
            timedOut = false,
            generation = 6L,
            currentGeneration = 6L,
        ) as CorrectionOutcome.Applied
        assertTrue(result.uncertain.any { "cravenport" in it })
    }

    @Test
    fun `negation removal fails closed`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "do not delete the folder",
            modelOutput = """{"prediction":"Delete the folder.","uncertain":[]}""",
            timedOut = false,
            generation = 7L,
            currentGeneration = 7L,
        )
        assertEquals(CorrectionOutcome.Unchanged("negation"), result)
    }

    @Test
    fun `explicit digit changes fail closed`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "the code is 4520",
            modelOutput = """{"prediction":"The code is 452.","uncertain":[]}""",
            timedOut = false,
            generation = 8L,
            currentGeneration = 8L,
        )
        assertEquals(CorrectionOutcome.Unchanged("numbers"), result)
    }

    @Test
    fun `existing profanity cannot be silently sanitized`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "that was fucking hard",
            modelOutput = """{"prediction":"That was very hard.","uncertain":[]}""",
            timedOut = false,
            generation = 9L,
            currentGeneration = 9L,
        )
        assertEquals(CorrectionOutcome.Unchanged("profanity"), result)
    }

    @Test
    fun `new profanity cannot be introduced from an ambiguous euphemism`() {
        val result = CorrectionPolicy.decide(
            enabled = true,
            raw = "tell him to shut the front door",
            modelOutput = """{"prediction":"Tell him to shut the fuck up.","uncertain":[]}""",
            timedOut = false,
            generation = 10L,
            currentGeneration = 10L,
        )
        assertEquals(CorrectionOutcome.Unchanged("introduced_profanity"), result)
    }

    @Test
    fun `prompt carries the product glossary and the manual chat template`() {
        val prompt = OnDeviceCorrection.wrapPrompt("the quinn v six checkpoint passed")
        assertTrue(prompt.startsWith("<|im_start|>user\n"))
        assertTrue(prompt.contains("Raw: the quinn v six checkpoint passed\nResult:"))
        for (term in listOf("Qwen", "V6", "Parakeet", "Razr", "NVIDIA", "GGUF", "LoRA", "sherpa-onnx")) {
            assertTrue("glossary term missing: $term", term in prompt)
        }
    }

    @Test
    fun `generation owner rejects a second in-flight launch and invalidates on cancel`() {
        val owner = CorrectionGenerationOwner()
        val first = owner.tryBegin()
        assertTrue(first > 0)
        assertEquals(-1L, owner.tryBegin())
        owner.cancel()
        val stalePublish = CorrectionPolicy.decide(
            enabled = true,
            raw = "x",
            modelOutput = """{"prediction":"X.","uncertain":[]}""",
            timedOut = false,
            generation = first,
            currentGeneration = owner.current(),
        )
        assertEquals(CorrectionOutcome.Unchanged("stale"), stalePublish)
        owner.end(first)
        val second = owner.tryBegin()
        assertTrue(second > first)
        owner.end(second)
    }
}
