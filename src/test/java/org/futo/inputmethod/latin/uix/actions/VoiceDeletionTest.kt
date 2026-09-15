package org.futo.inputmethod.latin.uix.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDeletionTest {

    @Test
    fun deletionLength_exactCommittedStillAtCursor_deletesPreciselyIt() {
        assertEquals(11, VoiceDeletion.deletionLength("Hello. hello world", "hello world"))
    }

    @Test
    fun deletionLength_committedWithEmoji_countsCodePointsNotUtf16() {
        // "nice 😀" is 7 UTF-16 units but 6 code points — backspace deletes one
        // code point per event, so 7 would eat one character too many.
        val committed = "nice 😀"
        assertEquals(6, VoiceDeletion.deletionLength("Some text. $committed", committed))
        assertEquals(6, committed.codePointCount(0, committed.length))
        assertEquals(7, committed.length) // sanity: the bug was returning this
    }

    @Test
    fun deletionLength_committedNotAtCursor_fallsBackToSentence() {
        // Falls back to deleting the trailing sentence (" typed on", with its
        // leading space — same semantics as before the code-point change).
        assertEquals(9, VoiceDeletion.deletionLength("Hello. typed on", "hello world"))
    }

    @Test
    fun trailingSentence_simpleSentence() {
        assertEquals(6, VoiceDeletion.trailingSentenceLength("Hello. Next."))
        // Trailing whitespace belongs to the deleted sentence.
        assertEquals(7, VoiceDeletion.trailingSentenceLength("Hello. Next. "))
    }

    @Test
    fun trailingSentence_sentenceWithEmoji_countsCodePoints() {
        // " ok 😀." is 7 UTF-16 units but 6 code points.
        assertEquals(6, VoiceDeletion.trailingSentenceLength("Hello. ok 😀."))
    }

    @Test
    fun trailingSentence_noBoundary_deletesLastWordOnly() {
        assertEquals(9, VoiceDeletion.trailingSentenceLength("just somewords"))
        assertEquals(4, VoiceDeletion.trailingSentenceLength("just some word"))
    }

    @Test
    fun trailingSentence_emptyAndEdgeCases() {
        assertEquals(0, VoiceDeletion.trailingSentenceLength(""))
        assertEquals(3, VoiceDeletion.trailingSentenceLength("   "))
        assertEquals(3, VoiceDeletion.trailingSentenceLength("..."))
    }
}
