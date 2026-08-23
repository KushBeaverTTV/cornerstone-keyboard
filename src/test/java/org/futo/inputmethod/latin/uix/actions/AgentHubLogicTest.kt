package org.futo.inputmethod.latin.uix.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Cornerstone Keyboard fork logic (Agent Hub v2,
 * memory, conversation threads, custom agents, security guards).
 * No Android framework dependencies: everything here runs on the plain JVM.
 */
class AgentHubLogicTest {

    // ---------- AgentMemory ----------

    @Test
    fun `memory starts empty for untouched agents`() {
        assertNull(AgentMemory.lastAnswer("mem-fresh"))
        assertEquals(0, AgentMemory.count("mem-fresh"))
        assertNull(AgentMemory.contextBlock("mem-fresh"))
    }

    @Test
    fun `memory records exchange and exposes last answer in full`() {
        val longAnswer = "A".repeat(1000)
        AgentMemory.record("mem-record", label = "Improve my draft", userText = "hello draft", answer = longAnswer)
        assertEquals(1, AgentMemory.count("mem-record"))
        assertEquals(longAnswer, AgentMemory.lastAnswer("mem-record"))
    }

    @Test
    fun `memory evicts oldest past six exchanges`() {
        repeat(8) { i -> AgentMemory.record("mem-evict", label = "L$i", userText = "u$i", answer = "a$i") }
        assertEquals(6, AgentMemory.count("mem-evict"))
        val block = AgentMemory.contextBlock("mem-evict")!!
        assertFalse("oldest evicted", block.contains("[L0]"))
        assertTrue("newest kept", block.contains("[L7]"))
        assertTrue("numbered oldest-first", block.indexOf("1. [") < block.indexOf("6. ["))
    }

    @Test
    fun `memory truncates summaries to documented bounds`() {
        AgentMemory.record("mem-trunc", label = "Ask", userText = "U".repeat(500), answer = "R".repeat(900))
        val block = AgentMemory.contextBlock("mem-trunc")!!
        assertTrue(block.contains("\"" + "U".repeat(80)))
        assertFalse(block.contains("U".repeat(81)))
        assertTrue(block.contains("\"" + "R".repeat(300)))
        assertFalse(block.contains("R".repeat(301)))
    }

    @Test
    fun `memory isolates agents`() {
        AgentMemory.record("iso-a", label = "L", userText = "uk", answer = "ak")
        AgentMemory.record("iso-b", label = "L", userText = "up", answer = "ap")
        assertEquals(1, AgentMemory.count("iso-a"))
        assertEquals(1, AgentMemory.count("iso-b"))
        assertTrue(AgentMemory.contextBlock("iso-a")!!.contains("uk"))
        assertFalse(AgentMemory.contextBlock("iso-a")!!.contains("ap"))
    }

    // ---------- ConversationThreads ----------

    private fun turn(user: Boolean, text: String) = ThreadTurn(fromUser = user, text = text, label = "")

    @Test
    fun `thread preserves order and interleaving`() {
        ConversationThreads.add("kimi", turn(true, "q1"))
        ConversationThreads.add("kimi", turn(false, "a1"))
        ConversationThreads.add("kimi", turn(true, "q2"))
        val t = ConversationThreads.turns("kimi")
        assertEquals(listOf("q1", "a1", "q2"), t.map { it.text })
        assertEquals(listOf(true, false, true), t.map { it.fromUser })
    }

    @Test
    fun `thread caps at forty turns dropping oldest`() {
        repeat(45) { i -> ConversationThreads.add("cap", turn(true, "m$i")) }
        val t = ConversationThreads.turns("cap")
        assertEquals(40, t.size)
        assertEquals("m5", t.first().text)
        assertEquals("m44", t.last().text)
    }

    @Test
    fun `replaceLastAgentTurn rewrites newest agent message only`() {
        ConversationThreads.add("r1", turn(true, "q"))
        ConversationThreads.add("r1", turn(false, "stale"))
        ConversationThreads.add("r1", turn(true, "follow-up"))
        assertTrue(ConversationThreads.replaceLastAgentTurn("r1", "fresh"))
        assertEquals(listOf("q", "fresh", "follow-up"), ConversationThreads.turns("r1").map { it.text })
    }

    @Test
    fun `replaceLastAgentTurn returns false with no agent turn or no thread`() {
        ConversationThreads.add("r2", turn(true, "user only"))
        assertFalse(ConversationThreads.replaceLastAgentTurn("r2", "x"))
        assertFalse(ConversationThreads.replaceLastAgentTurn("never-started", "x"))
    }

    @Test
    fun `dropLast removes newest turn and tolerates empty thread`() {
        ConversationThreads.add("d1", turn(true, "q"))
        ConversationThreads.add("d1", turn(false, "a"))
        ConversationThreads.dropLast("d1")
        assertEquals(listOf("q"), ConversationThreads.turns("d1").map { it.text })
        ConversationThreads.dropLast("d1")
        ConversationThreads.dropLast("d1") // idempotent on empty/absent
        assertEquals(emptyList<ThreadTurn>(), ConversationThreads.turns("d1"))
    }

    @Test
    fun `thread contextBlock carries recent turns with speaker labels`() {
        ConversationThreads.add("cb", turn(true, "u".repeat(400)))   // over the 300-char cut
        ConversationThreads.add("cb", turn(false, "hello"))
        val block = ConversationThreads.contextBlock("cb")!!
        assertTrue(block.startsWith("Conversation so far:"))
        assertTrue(block.contains("User: "))
        assertTrue(block.contains("You: "))
        assertFalse("turn text clipped to 300", block.contains("u".repeat(301)))
        assertNull("no block for unknown thread", ConversationThreads.contextBlock("nobody"))
    }

    // ---------- CustomAgents ----------

    @Test
    fun `parse handles blank garbage and malformed entries`() {
        assertEquals(emptyList<CustomAgent>(), parseCustomAgents(""))
        assertEquals(emptyList<CustomAgent>(), parseCustomAgents("   "))
        assertEquals(emptyList<CustomAgent>(), parseCustomAgents("{not json at all"))
        val mixed = parseCustomAgents(
            """[{"id":"","name":"x"},{"name":"missing-id"},{"id":"ok","name":"Fine","role":"r"}]"""
        )
        assertEquals("only the valid entry survives", 1, mixed.size)
        assertEquals("ok", mixed[0].id)
        assertEquals("Fine", mixed[0].name)
        assertEquals(emptyList<CustomAgent>(), parseCustomAgents("["))
        assertEquals(emptyList<CustomAgent>(), parseCustomAgents("null"))
    }

    @Test
    fun `parse keeps valid entries with defaults for missing optional fields`() {
        val agents = parseCustomAgents("""[{"id":"helper","name":"Helper"}]""")
        assertEquals(1, agents.size)
        assertEquals("helper", agents[0].id)
        assertEquals("Helper", agents[0].name)
        assertEquals("", agents[0].role)
        assertEquals("", agents[0].systemPrompt)
        assertEquals("", agents[0].endpoint)
    }

    @Test
    fun `serialize then parse round-trips faithfully`() {
        val original = listOf(
            CustomAgent("a1", "Alpha", "research", "Be terse.", ""),
            CustomAgent("b-2", "Beta!", "", "", "https://example.invalid/hook")
        )
        val parsed = parseCustomAgents(serializeCustomAgents(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `slugify follows documented rules`() {
        assertEquals("my-helper", slugifyAgentId("My Helper!"))
        assertEquals("kbp-ops", slugifyAgentId("  KBP   Ops?? "))
        assertEquals("", slugifyAgentId("!!!"))
        assertEquals("", slugifyAgentId("   "))
        assertEquals("a-1", slugifyAgentId("A-1")) // interior dashes kept, edges trimmed
        assertEquals("a1", slugifyAgentId("-a1-")) // edge dashes trimmed away
    }

    @Test
    fun `validate rejects blank unslugifiable builtin-colliding and duplicate names`() {
        assertNotNull(validateNewAgent("", emptyList()).error)
        assertNotNull(validateNewAgent("///", emptyList()).error)
        assertNull("built-in collision", validateNewAgent("Kimi", emptyList()).id)
        val existing = listOf(CustomAgent("dupe", "Dupe", "", "", ""))
        assertNull("custom collision", validateNewAgent("DUPE", existing).id)
        val ok = validateNewAgent("My New Agent", existing)
        assertNull(ok.error)
        assertEquals("my-new-agent", ok.id)
    }

    // ---------- Password-field guard ----------

    @Test
    fun `password guard accepts every password variation`() {
        // InputType layout (verified vs android.jar): class bits 0-3,
        // variation bits 4-11 (MASK 0xFF0), flags bit 12+.
        assertTrue(isPasswordField(0x00000081)) // text | TYPE_TEXT_VARIATION_PASSWORD (0x80)
        assertTrue(isPasswordField(0x00000091)) // text | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (0x90)
        assertTrue(isPasswordField(0x000000E1)) // text | TYPE_TEXT_VARIATION_WEB_PASSWORD (0xE0)
        assertTrue(isPasswordField(0x00000012)) // number | TYPE_NUMBER_VARIATION_PASSWORD (0x10)
        assertTrue(isPasswordField(0x0000C081)) // text_password + CAP flag (bit 14) still detected
    }

    @Test
    fun `password guard rejects ordinary fields`() {
        assertFalse(isPasswordField(0x00000001)) // text | normal
        assertFalse(isPasswordField(0x00000011)) // text | auto_complete variation
        assertFalse(isPasswordField(0x00002001)) // text | URI variation
        assertFalse(isPasswordField(0x00000002)) // number | normal
        assertFalse(isPasswordField(0x00000003)) // phone class
        assertFalse(isPasswordField(0x00000004)) // datetime class
        assertFalse(isPasswordField(0))
    }

    // ---------- Prompt contracts ----------

    @Test
    fun `hub options are non-empty and prompts end with blank line separator`() {
        assertTrue(HUB_OPTIONS.isNotEmpty())
        HUB_OPTIONS.forEach { opt ->
            assertTrue(opt.label.isNotBlank())
            assertTrue("prefix must end with \\n\\n: ${opt.label}", opt.promptPrefix.endsWith("\n\n"))
        }
    }

    @Test
    fun `labels are unique`() {
        assertEquals(HUB_OPTIONS.size, HUB_OPTIONS.map { it.label }.toSet().size)
    }

    @Test
    fun `translate prefix embeds the target language`() {
        val p = translatePrefix("German")
        assertTrue(p.contains("German"))
        assertTrue(p.startsWith("Translate this to German"))
        assertTrue(p.endsWith("\n\n"))
        assertEquals("Translate…", TRANSLATE_LABEL)
    }

    // ---------- Status endpoint derivation (F-2) ----------

    @Test
    fun `status endpoint strips agentkey suffix`() {
        assertEquals("http://h.local:8069/agents", statusEndpointFromAgentKey("http://h.local:8069/agentkey"))
        assertEquals("http://h.local:8069/agents", statusEndpointFromAgentKey("http://h.local:8069/agentkey/"))
    }

    @Test
    fun `status endpoint survives missing suffix and stray whitespace`() {
        assertEquals("http://h.local:8069/agents", statusEndpointFromAgentKey("http://h.local:8069"))
        assertEquals("http://h.local:8069/agents", statusEndpointFromAgentKey(" http://h.local:8069/agentkey "))
        assertEquals("/agents", statusEndpointFromAgentKey(""))
    }

    // ---------- Agent result messages (sealed results -> actionable wording) ----------

    @Test
    fun `agent result messages tell the user how to fix each failure`() {
        assertTrue(agentResultMessage(AgentResult.Timeout, "Kimi").contains("timed out"))
        assertTrue(agentResultMessage(AgentResult.Http(401), "Aurora").contains("token"))
        assertTrue(agentResultMessage(AgentResult.Http(403), "Prima").contains("token"))
        assertTrue(agentResultMessage(AgentResult.Http(503), "Kimi").contains("server error"))
        val parseMsg = agentResultMessage(AgentResult.Parse, "Kimi")
        assertTrue(parseMsg.contains("read") && parseMsg.contains("logs"))
        // Every message names the agent so multi-agent users know WHO failed.
        assertTrue(agentResultMessage(AgentResult.Network, "Prima").startsWith("Prima"))
        assertTrue(agentResultMessage(AgentResult.Network, "Kimi").contains("reach"))
        // Success passes the answer text straight through.
        assertEquals("the answer", agentResultMessage(AgentResult.Success("the answer"), "Kimi"))
    }

    @Test
    fun `agent result messages fall back to generic wording for odd statuses`() {
        val h = agentResultMessage(AgentResult.Http(302), "Kimi")
        assertTrue(h.contains("302"))
        assertFalse(h.contains("token"))
    }

    // ---------- Cleartext pairing rule (F-5, mirrors the build.gradle gate) ----------

    private val ALLOW = setOf("tailscale-mesh-host.local")

    @Test
    fun `http endpoint on the allowlisted host passes pairing`() {
        assertTrue(isCleartextPairingValid("http://tailscale-mesh-host.local:8069/agentkey", ALLOW))
    }

    @Test
    fun `http endpoint on a foreign host fails pairing`() {
        assertFalse(isCleartextPairingValid("http://random-host.local:8069/agentkey", ALLOW))
    }

    @Test
    fun `https endpoints pass pairing regardless of host`() {
        assertTrue(isCleartextPairingValid("https://api.example.com/v1", ALLOW))
    }

    @Test
    fun `pairing treats hosts case-insensitively and strips ports`() {
        assertTrue(isCleartextPairingValid("http://Tailscale-Mesh-Host.Local:8069/x", ALLOW))
    }

    @Test
    fun `pairing tolerates public builds with no endpoint and rejects schemeless junk`() {
        assertTrue(isCleartextPairingValid("", ALLOW))
        assertTrue(isCleartextPairingValid("   ", ALLOW))
        assertFalse(isCleartextPairingValid("ftp://x", ALLOW))
    }
}
