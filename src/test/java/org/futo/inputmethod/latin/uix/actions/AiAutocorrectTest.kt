package org.futo.inputmethod.latin.uix.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the AI autocorrect layer (sentence capture, trigger
 * detection, replace planning, answer validation, guard matrix).
 * No Android framework dependencies: everything here runs on the plain JVM.
 */
class AiAutocorrectTest {

    // ---------- Trigger detection (cheap last-chars check) ----------

    @Test
    fun `trigger fires on terminator with or without trailing whitespace`() {
        assertTrue(isSentenceEndTriggerSuffix("Hello world."))
        assertTrue(isSentenceEndTriggerSuffix("Hello world. "))
        assertTrue(isSentenceEndTriggerSuffix("Really? "))
        assertTrue(isSentenceEndTriggerSuffix("Wow!"))
        assertTrue(isSentenceEndTriggerSuffix("Wow!  "))
        assertTrue(isSentenceEndTriggerSuffix("Wait…"))
        assertTrue(isSentenceEndTriggerSuffix(".\n")) // Enter after punctuation
    }

    @Test
    fun `trigger stays quiet for ordinary typing`() {
        assertFalse(isSentenceEndTriggerSuffix(""))
        assertFalse(isSentenceEndTriggerSuffix(" "))
        assertFalse(isSentenceEndTriggerSuffix("Hello world"))
        assertFalse(isSentenceEndTriggerSuffix("Hello world, "))
        assertFalse(isSentenceEndTriggerSuffix("3.14")) // decimal, no terminator at end
        assertFalse(isSentenceEndTriggerSuffix("word. x"))
    }

    // ---------- Sentence capture ----------

    @Test
    fun `captures the only sentence in the field`() {
        val c = extractSentenceCapture("This is a test.")!!
        assertEquals("This is a test.", c.sentence)
        assertEquals(".", c.terminator)
        assertEquals("", c.trailingWhitespace)
        assertEquals(c.sentence.length, c.deleteCodePoints)
    }

    @Test
    fun `captures only the last sentence after a boundary`() {
        val c = extractSentenceCapture("First one done. Second one here!")!!
        assertEquals("Second one here!", c.sentence)
        assertEquals("!", c.terminator)
        assertEquals("", c.trailingWhitespace)
    }

    @Test
    fun `trailing whitespace is captured for delete-and-restore`() {
        val c = extractSentenceCapture("First one done. Second one here? \n")!!
        assertEquals("Second one here?", c.sentence)
        assertEquals("?", c.terminator)
        assertEquals(" \n", c.trailingWhitespace)
        // Delete count covers sentence + trailing whitespace — the exact
        // region the apply path removes and then restores.
        assertEquals(
            "Second one here? \n".codePointCount(0, "Second one here? \n".length),
            c.deleteCodePoints
        )
    }

    @Test
    fun `abbreviations do not split the sentence`() {
        // Documented heuristic: "Mr." / "Dr." style titles keep the sentence
        // together instead of capturing just "Smith went home.".
        val c = extractSentenceCapture("Mr. Smith went home.")!!
        assertEquals("Mr. Smith went home.", c.sentence)
        assertEquals("Dr. Jones agreed, and Ms. Lee left.", extractSentenceCapture("Dr. Jones agreed, and Ms. Lee left.")!!.sentence)
    }

    @Test
    fun `single-letter initials do not split the sentence`() {
        val c = extractSentenceCapture("J. R. R. Tolkien wrote this.")!!
        assertEquals("J. R. R. Tolkien wrote this.", c.sentence)
    }

    @Test
    fun `multi-line text uses the newline as boundary`() {
        val c = extractSentenceCapture("First line here\nSecond line here.")!!
        assertEquals("Second line here.", c.sentence)
    }

    @Test
    fun `empty and unfinished fields yield no capture`() {
        assertNull(extractSentenceCapture(""))
        assertNull(extractSentenceCapture("   \n "))
        assertNull(extractSentenceCapture("still typing"))
        assertNull(extractSentenceCapture("trailing comma, "))
    }

    @Test
    fun `too-short sentences yield no capture`() {
        assertNull(extractSentenceCapture("Ok."))
        assertNull(extractSentenceCapture("Hi! "))
        assertNull(extractSentenceCapture("Longer sentence here. Ok."))
    }

    @Test
    fun `over-long sentences yield no capture rather than a truncated fragment`() {
        val longSentence = "word ".repeat(100).trim() + "." // 496 chars, no inner boundary
        assertNull(extractSentenceCapture(longSentence))
        // But with a boundary inside the cap, the tail sentence is captured.
        val withBoundary = "x".repeat(280) + ". " + "Short tail sentence."
        assertEquals("Short tail sentence.", extractSentenceCapture(withBoundary)!!.sentence)
    }

    @Test
    fun `terminator runs stay attached to the sentence`() {
        val c = extractSentenceCapture("Are you serious right now!!")!!
        assertEquals("!!", c.terminator)
        assertEquals("Are you serious right now!!", c.sentence)
    }

    @Test
    fun `ellipsis made of dots does not self-split`() {
        val c = extractSentenceCapture("I was thinking... maybe not today.")!!
        assertEquals("maybe not today.", c.sentence)
    }

    @Test
    fun `delete count uses code points not UTF-16 units`() {
        val c = extractSentenceCapture("This emoji belongs here 😀!")!!
        assertEquals("This emoji belongs here 😀!".codePointCount(0, "This emoji belongs here 😀!".length), c.deleteCodePoints)
        assertTrue(c.deleteCodePoints < c.sentence.length) // 😀 is one code point, two UTF-16 units
    }

    // ---------- Replace planning ----------

    /**
     * Simulates the apply path in AiAutocorrectController.onChipTapped:
     * planAiFix() gates; on DELETE_THEN_INSERT the apply does
     * backspace(deleteCodePoints) — one code point per event, from the
     * cursor — then types the fixed sentence followed by the captured
     * trailing whitespace. Returns the resulting field contents.
     */
    private fun simulateApply(beforeCursor: String, capture: SentenceCapture, answer: String): String {
        if (planAiFix(beforeCursor, capture) == AiFixPlan.ABORT) return beforeCursor
        val keepEnd = beforeCursor.offsetByCodePoints(beforeCursor.length, -capture.deleteCodePoints)
        return beforeCursor.substring(0, keepEnd) +
                normalizeFixedSentence(answer, capture) + capture.trailingWhitespace
    }

    @Test
    fun `fix deletes only when the field still ends with the captured suffix`() {
        val capture = extractSentenceCapture("Fix this sentence. ")!!
        assertEquals(AiFixPlan.DELETE_THEN_INSERT, planAiFix("Fix this sentence. ", capture))
        assertEquals("whitespace removed after capture — the suffix no longer matches",
            AiFixPlan.ABORT, planAiFix("Fix this sentence.", capture))
        assertEquals("extra space typed after capture",
            AiFixPlan.ABORT, planAiFix("Fix this sentence.  ", capture))
        assertEquals("field edited mid-request",
            AiFixPlan.ABORT, planAiFix("Fix this sentence. More typing", capture))
        assertEquals("sentence itself changed",
            AiFixPlan.ABORT, planAiFix("Fix this sentenc. ", capture))
        assertEquals("field cleared",
            AiFixPlan.ABORT, planAiFix("", capture))
    }

    @Test
    fun `fix plan matches emoji suffixes by content`() {
        val capture = extractSentenceCapture("Emoji sentence 😀.")!!
        assertEquals(AiFixPlan.DELETE_THEN_INSERT, planAiFix("Prefix here. Emoji sentence 😀.", capture))
        assertEquals(AiFixPlan.ABORT, planAiFix("Prefix here. Emoji sentence 😀", capture))
        assertEquals("whitespace added after capture changes the suffix",
            AiFixPlan.ABORT, planAiFix("Prefix here. Emoji sentence 😀. ", capture))
    }

    // ---------- Plan -> delete count -> applied result chain ----------

    @Test
    fun `bare sentence is replaced in place`() {
        val field = "Hello world."
        val capture = extractSentenceCapture(field)!!
        assertEquals("Hello world.".length, capture.deleteCodePoints)
        assertEquals("Hello, world.", simulateApply(field, capture, "Hello, world."))
    }

    @Test
    fun `regression - sentence plus autospace does not corrupt the text`() {
        // The reported bug: backspace(sentence-only count) on "Hello world. "
        // ate the space plus 11 of the 12 sentence chars, producing "HFixed.".
        val field = "Hello world. "
        val capture = extractSentenceCapture(field)!!
        assertEquals(" ", capture.trailingWhitespace)
        assertEquals("Hello world. ".length, capture.deleteCodePoints)
        assertEquals("Fixed. ", simulateApply(field, capture, "Fixed"))
        assertEquals("Hello, world. ", simulateApply(field, capture, "Hello, world"))
    }

    @Test
    fun `sentence plus newline keeps the newline`() {
        val field = "Hello world.\n"
        val capture = extractSentenceCapture(field)!!
        assertEquals("\n", capture.trailingWhitespace)
        assertEquals("Hello, world.\n", simulateApply(field, capture, "Hello, world."))
    }

    @Test
    fun `sentence plus two spaces keeps both spaces`() {
        val field = "Hello world.  "
        val capture = extractSentenceCapture(field)!!
        assertEquals("  ", capture.trailingWhitespace)
        assertEquals("Hello world.  ".length, capture.deleteCodePoints)
        assertEquals("Hello, world.  ", simulateApply(field, capture, "Hello, world."))
    }

    @Test
    fun `replacement only touches the last sentence and keeps the prefix`() {
        val field = "Earlier sentence stays. Hello world. "
        val capture = extractSentenceCapture(field)!!
        assertEquals("Earlier sentence stays. Hello, world. ", simulateApply(field, capture, "Hello, world."))
    }

    @Test
    fun `emoji sentence with trailing space deletes by code points`() {
        val field = "Emoji sentence 😀! "
        val capture = extractSentenceCapture(field)!!
        assertEquals(
            field.codePointCount(0, field.length),
            capture.deleteCodePoints
        )
        assertTrue("😀 is one code point but two UTF-16 units",
            capture.deleteCodePoints < field.length)
        assertEquals("Fixed emoji 😀! ", simulateApply(field, capture, "Fixed emoji 😀"))
    }

    @Test
    fun `mismatch aborts leave the field untouched`() {
        val capture = extractSentenceCapture("Hello world. ")!!
        assertEquals("Hello world.", simulateApply("Hello world.", capture, "Fixed."))
        assertEquals("Hello world. x", simulateApply("Hello world. x", capture, "Fixed."))
        assertEquals("", simulateApply("", capture, "Fixed."))
    }

    // ---------- Answer validation / normalization ----------

    @Test
    fun `answers are rejected when blank oversized or identical`() {
        val original = "This is the original sentence."
        assertFalse(validFixAnswer("", original))
        assertFalse(validFixAnswer("   ", original))
        assertFalse(validFixAnswer("x".repeat(original.length * 2 + 1), original))
        assertFalse(validFixAnswer(original, original)) // nothing to fix
        assertTrue(validFixAnswer("This is the corrected sentence.", original))
    }

    @Test
    fun `normalization re-appends a dropped terminator`() {
        val capture = extractSentenceCapture("Are you serious!!")!!
        assertEquals("Are you serious!!", normalizeFixedSentence("Are you serious", capture))
        assertEquals("Are you serious?", normalizeFixedSentence("Are you serious?", capture))
        assertEquals("", normalizeFixedSentence("  ", capture))
    }

    // ---------- Guard matrix ----------

    @Test
    fun `guards block password and incognito fields even when enabled`() {
        val passwordInputType = 0x00000081 // text | TYPE_TEXT_VARIATION_PASSWORD
        val normalInputType = 0x00000001   // text | normal
        assertTrue(aiFixAllowed(enabled = true, incognito = false, inputType = normalInputType))
        assertFalse("master toggle off", aiFixAllowed(enabled = false, incognito = false, inputType = normalInputType))
        assertFalse("password field", aiFixAllowed(enabled = true, incognito = false, inputType = passwordInputType))
        assertFalse("incognito", aiFixAllowed(enabled = true, incognito = true, inputType = normalInputType))
        assertFalse("all guards stacked", aiFixAllowed(enabled = false, incognito = true, inputType = passwordInputType))
    }
}
