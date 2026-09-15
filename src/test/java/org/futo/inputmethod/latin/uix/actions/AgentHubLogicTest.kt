package org.futo.inputmethod.latin.uix.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun `contextBlock can exclude the trailing current turn`() {
        // postPrompt records the user turn BEFORE composing; the block must not
        // carry it too, or the current question is sent twice.
        ConversationThreads.add("cx-excl", turn(true, "older question"))
        ConversationThreads.add("cx-excl", turn(false, "older answer"))
        ConversationThreads.add("cx-excl", turn(true, "current question"))
        val block = ConversationThreads.contextBlock("cx-excl", excludeTrailingTurns = 1)!!
        assertTrue(block.contains("older question"))
        assertTrue(block.contains("older answer"))
        assertFalse(block.contains("current question"))
        // Excluding everything yields null, not an empty header.
        assertNull(ConversationThreads.contextBlock("cx-excl", excludeTrailingTurns = 3))
    }

    // ---------- Thread outcome ordering (persist AFTER the mutation) ----------

    @Test
    fun `applyResult persists only after the answer turn is in the thread`() {
        // Regression: the success path used to persist BEFORE adding the
        // answer turn, so an IME restart lost every answer.
        val req = AgentRequest("ord-add", "Ask", "", "q", 0)
        var observedAtPersist: List<String>? = null
        ConversationThreads.applyResult("ord-add", req, replace = false, text = "the answer") {
            observedAtPersist = ConversationThreads.turns("ord-add").map { it.text }
        }
        assertEquals(listOf("the answer"), observedAtPersist)
        assertEquals(listOf("the answer"), ConversationThreads.turns("ord-add").map { it.text })
    }

    @Test
    fun `applyResult persists only after the replaced bubble is rewritten`() {
        val req = AgentRequest("ord-rep", "Ask", "", "q", 0)
        ConversationThreads.applyResult("ord-rep", req, replace = false, text = "stale") {}
        var observedAtPersist: List<String>? = null
        ConversationThreads.applyResult("ord-rep", req, replace = true, text = "fresh") {
            observedAtPersist = ConversationThreads.turns("ord-rep").map { it.text }
        }
        assertEquals(listOf("fresh"), observedAtPersist)
    }

    @Test
    fun `regenerate replaces the bubble for its own request not the newest agent bubble`() {
        // Request A succeeds, follow-up B fails: regenerating A must rewrite A's
        // bubble and leave B's error bubble (and its Retry chip) intact.
        val reqA = AgentRequest("reg-match", "Ask about my draft", "", "question A", 0)
        val reqB = AgentRequest("reg-match", "Follow-up", "", "question B", 0)
        ConversationThreads.add("reg-match", ThreadTurn(true, "question A", "Ask about my draft"))
        ConversationThreads.applyResult("reg-match", reqA, replace = false, text = "answer A") {}
        ConversationThreads.add("reg-match", ThreadTurn(true, "question B", "Follow-up"))
        ConversationThreads.applyResult(
            "reg-match", reqB, replace = false, text = "B failed", errorResult = AgentResult.Network
        ) {}
        ConversationThreads.applyResult("reg-match", reqA, replace = true, text = "answer A v2") {}

        val turns = ConversationThreads.turns("reg-match")
        assertEquals(
            listOf("question A", "answer A v2", "question B", "B failed"),
            turns.map { it.text }
        )
        assertEquals("B's error bubble keeps its retry request", reqB, turns.last().request)
        assertEquals(AgentResult.Network, turns.last().errorResult)
    }

    @Test
    fun `regenerate falls back to the newest agent bubble for untagged requests`() {
        // Turns loaded from disk carry no request tag; replacing must still land.
        val req = AgentRequest("reg-fallback", "Ask", "", "q", 0)
        ConversationThreads.add("reg-fallback", turn(true, "q"))
        ConversationThreads.add("reg-fallback", turn(false, "stale"))
        ConversationThreads.applyResult("reg-fallback", req, replace = true, text = "fresh") {}
        assertEquals(listOf("q", "fresh"), ConversationThreads.turns("reg-fallback").map { it.text })
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
        assertNull("built-in collision", validateNewAgent("Kaori", emptyList()).id)
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
    fun `built-in roster is Kaori and unknown ids display as Kaori`() {
        // Kaori (Hermes "kaori" profile via hub-bridge) is the one active
        // agent. Stale settings from the old hub ("kimi") must still render a
        // real name, and the bridge routes them to Kaori.
        assertEquals(listOf(DEFAULT_AGENT_ID), AGENT_CHOICES.map { it.first })
        assertEquals("kaori", DEFAULT_AGENT_ID)
        assertEquals("Kaori", agentDisplayName("kaori"))
        assertEquals("Kaori", agentDisplayName("kimi"))
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
    fun `runtime cleartext allowlist mirrors network_security_config xml`() {
        // The Gradle F-5 gate reads the XML; the runtime pre-flight reads
        // CLEARTEXT_ALLOWLIST. If they drift, a host passes one check and is
        // killed by the other at runtime behind a generic "unreachable".
        val candidates = listOf(
            java.io.File("java/res/xml/network_security_config.xml"),
            java.io.File("../java/res/xml/network_security_config.xml"),
        )
        val xml = candidates.firstOrNull { it.isFile }
            ?: error("network_security_config.xml not found from ${java.io.File(".").absolutePath}")
        val declared = Regex("<domain[^>]*>([^<]+)</domain>")
            .findAll(xml.readText())
            .map { it.groupValues[1].trim().lowercase() }
            .toSet()
        assertEquals(declared, CLEARTEXT_ALLOWLIST.map { it.lowercase() }.toSet())
        assertTrue("hub bridge hosts must be IP literals or hostnames, never blank", declared.none { it.isBlank() })
    }

    @Test
    fun `hub bridge hosts pass pairing over plain http`() {
        for (host in CLEARTEXT_ALLOWLIST) {
            assertTrue(host, isCleartextPairingValid("http://$host:8899/agentkey", CLEARTEXT_ALLOWLIST))
            assertNull(validateAgentEndpoint("http://$host:8899/agentkey", CLEARTEXT_ALLOWLIST))
        }
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

    // ---------- Custom-endpoint request path (no Bearer leaks off-hub) ----------

    @Test
    fun `custom endpoints receive the same request without an Authorization header`() {
        val foreign = "https://foreign.example.com/hook"
        assertTrue("precondition: the URL is foreign to the hub", isForeignAgentEndpoint(foreign))
        val request = buildAgentRequest("hello", "secret-token", "kimi", foreign)
        assertNull("the hub Bearer token must never reach a custom host", request.header("Authorization"))
        assertEquals("https://foreign.example.com/hook", request.url.toString())
    }

    @Test
    fun `hub endpoint still receives the Bearer token`() {
        assumeTrue("public build: no hub endpoint injected", DEFAULT_AGENT_ENDPOINT.isNotBlank())
        val request = buildAgentRequest("hello", "secret-token", "kimi", DEFAULT_AGENT_ENDPOINT)
        assertEquals("Bearer secret-token", request.header("Authorization"))
    }

    @Test
    fun `blank hub configuration makes every real endpoint foreign`() {
        // Documents the no-leak rule for public builds (empty BuildConfig endpoint).
        assumeTrue("only meaningful with a blank hub endpoint", DEFAULT_AGENT_ENDPOINT.isBlank())
        assertTrue(isForeignAgentEndpoint("https://anything.example.com/x"))
        assertFalse("an empty endpoint is not 'foreign', just invalid", isForeignAgentEndpoint(""))
    }

    // ---------- Save-time endpoint validation ----------

    @Test
    fun `endpoint validation accepts blank https and allowlisted http`() {
        assertNull(validateAgentEndpoint("", ALLOW))
        assertNull(validateAgentEndpoint("   ", ALLOW))
        assertNull(validateAgentEndpoint("https://api.example.com/v1", ALLOW))
        assertNull(validateAgentEndpoint("http://tailscale-mesh-host.local:8069/hook", ALLOW))
    }

    @Test
    fun `endpoint validation rejects malformed URLs with a specific error`() {
        val error = validateAgentEndpoint("not a url", ALLOW)
        assertNotNull(error)
        assertTrue(error!!.contains("valid URL"))
    }

    @Test
    fun `endpoint validation rejects non-http schemes`() {
        val error = validateAgentEndpoint("ftp://x.example.com/f", ALLOW)
        assertNotNull(error)
        assertTrue(error!!.contains("http"))
    }

    @Test
    fun `endpoint validation rejects cleartext http to foreign hosts`() {
        val error = validateAgentEndpoint("http://random-host.local/hook", ALLOW)
        assertNotNull(error)
        assertTrue(error!!.contains("https"))
    }

    // ---------- Cleartext / bad-URL failure messages ----------

    @Test
    fun `cleartext and bad-url failures name the actual problem`() {
        val cleartext = agentResultMessage(AgentResult.CleartextBlocked, "Kimi")
        assertTrue(cleartext.contains("cleartext"))
        assertTrue(cleartext.contains("https"))
        val badUrl = agentResultMessage(AgentResult.BadUrl, "Kimi")
        assertTrue(badUrl.contains("URL"))
        assertTrue(badUrl.startsWith("Kimi"))
    }

    // ---------- Replace-draft planning (never backspace over new edits) ----------

    @Test
    fun `replace plan deletes only when the field still ends with the captured draft`() {
        assertEquals(ReplacePlan.DELETE_THEN_INSERT, planReplace("hello draft", 5, "draft"))
        assertEquals("field edited mid-request", ReplacePlan.PLAIN_INSERT, planReplace("hello world", 5, "draft"))
        assertEquals("field shorter than the delete count", ReplacePlan.PLAIN_INSERT, planReplace("hi", 5, "draft"))
        assertEquals("no draft captured", ReplacePlan.PLAIN_INSERT, planReplace("hello draft", 0, "draft"))
    }

    @Test
    fun `replace plan counts code points not UTF-16 units`() {
        // "😀" is ONE code point but TWO UTF-16 units — miscounting would delete
        // half the emoji or miscompare the suffix.
        assertEquals(ReplacePlan.DELETE_THEN_INSERT, planReplace("x😀", 1, "😀"))
        assertEquals(ReplacePlan.PLAIN_INSERT, planReplace("x😀", 1, "x"))
    }
}
