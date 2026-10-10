package uk.xa0.dsh

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.model.ROUTER_READOUT_FRAME_FAILURE
import uk.xa0.dsh.model.ROUTER_READOUT_MODELS_UNREADABLE
import uk.xa0.dsh.model.ROUTER_READOUT_TERMINAL_ID
import uk.xa0.dsh.model.RouterModelStatus
import uk.xa0.dsh.model.RouterProbeResult
import uk.xa0.dsh.model.RouterReadoutChunk
import uk.xa0.dsh.model.RouterReadoutMarkers
import uk.xa0.dsh.model.RouterSlotsReadout
import uk.xa0.dsh.model.classifyReadout
import uk.xa0.dsh.model.isRouterReadoutTerminal
import uk.xa0.dsh.model.modelStatusesOf
import uk.xa0.dsh.model.parseReadoutChunk
import uk.xa0.dsh.model.parseSlotsChunk
import uk.xa0.dsh.model.residentModelForSlots
import uk.xa0.dsh.model.routerReadoutReadLine
import uk.xa0.dsh.model.routerReadoutSlotsLine
import uk.xa0.dsh.model.routerReadoutSyncLine
import uk.xa0.dsh.model.routerReadoutSyncMarker
import uk.xa0.dsh.model.routerRootOf
import uk.xa0.dsh.model.slotsContextSizeOf
import uk.xa0.dsh.model.slotsForReadout
import uk.xa0.dsh.model.slotsOf

/**
 * The router readout's wire protocol and the honesty rules it exists to keep.
 *
 * The fixtures are the shapes two live routers answered on 2026-10-10, trimmed to the
 * fields under test: the `/props` document, the `data` array `/v1/models` answered,
 * and the first slot `/slots?model=Qwen3-Embedding-0.6B-Q8_0` answered for the one
 * resident model on `:55556`.
 *
 * The decision logic is checked here because none of it is about the terminal: which
 * model may be asked about, what a withheld slot read must say, and how the tab's
 * three readings — not offered, attempted and failed, reported as nothing — are kept
 * apart. What this file **cannot** check is that a real PTY stops echoing after
 * `stty -echo`, or that the host delivers a write's output intact: those are facts
 * about the host, measured on the host and recorded in `docs/HANDOFF.md`. What is
 * pinned here is that a duplicated marker is a *failed* read rather than an ambiguous
 * one, which is the failure that would otherwise be invisible.
 */
class RouterReadoutTest {

    // ------------------------------------------------------------ the command lines

    @Test
    fun `sync line silences echo and the prompt, and its sentinel is not in the typed text`() {
        val nonce = "ab12cd34"
        val line = routerReadoutSyncLine(nonce)
        assertTrue("echo must be turned off", line.contains("stty -echo"))
        assertTrue("the interactive prompt must be silenced", line.contains("PROMPT_COMMAND=()"))
        assertTrue(line.contains("PS1=''"))
        // The whole point of the printf indirection: the marker the reader waits for
        // must not be a literal in the line, because this line runs while the PTY still
        // echoes — the last moment at which an echoed marker could be mistaken for the
        // answer.
        assertFalse(
            "the resolved sentinel must not appear in the command that prints it",
            line.contains(routerReadoutSyncMarker(nonce)),
        )
        assertTrue(line.contains("%s"))
        assertTrue("the sentinel must be unique per attachment", line.contains(nonce))
    }

    @Test
    fun `read line asks for props and the model listing in one write`() {
        val line = routerReadoutReadLine("http://127.0.0.1:55555")
        // One write, because a write is a PTY round trip and the two requests share it.
        assertEquals("one newline-terminated command", 1, line.count { it == '\n' })
        assertTrue(line.contains("'http://127.0.0.1:55555/props'"))
        assertTrue(line.contains("'http://127.0.0.1:55555/v1/models'"))
        assertTrue(line.contains(RouterReadoutMarkers.PROPS_BEGIN))
        assertTrue(line.contains(RouterReadoutMarkers.MODELS_HTTP))
        assertTrue(line.contains(RouterReadoutMarkers.READ_END))
        // Each request is bounded and reports curl's own status, so a dead port is HTTP
        // 000 rather than an empty body that would read as "no models".
        assertEquals(2, Regex("-m 5").findAll(line).count())
        assertEquals(2, Regex("%\\{http_code\\}").findAll(line).count())
        // Read-only by construction: no other verb is in the line.
        assertFalse(line.contains("POST"))
        assertFalse(line.contains(" -X "))
    }

    @Test
    fun `a base URL and a model id are quoted so nothing in them can run`() {
        val line = routerReadoutReadLine("http://127.0.0.1:55555")
        assertTrue(line.contains("'/props'"))
        val hostile = routerReadoutSlotsLine("http://127.0.0.1:55555", "x'y")
        assertTrue("a quote in a value must be closed and re-opened", hostile.contains("x'\\''y"))
    }

    @Test
    fun `the slots line encodes the model id as a query value`() {
        val line = routerReadoutSlotsLine("http://127.0.0.1:55556", "Qwen3-Embedding-0.6B-Q8_0")
        assertTrue(line.contains("'http://127.0.0.1:55556/slots?model=Qwen3-Embedding-0.6B-Q8_0'"))
        assertTrue(line.contains(RouterReadoutMarkers.SLOTS_END))
        // A plus in a query value means a literal plus and a space means %20 — the
        // reason this app percent-encodes rather than using form encoding.
        val spaced = routerReadoutSlotsLine("http://127.0.0.1:55555", "a model+v1")
        assertTrue(spaced.contains("slots?model=a%20model%2Bv1"))
    }

    // ------------------------------------------------------------------- the root

    @Test
    fun `the router root is the scheme and authority, with or without a v1 suffix`() {
        assertEquals("http://127.0.0.1:55555", routerRootOf("http://127.0.0.1:55555/v1"))
        assertEquals("http://127.0.0.1:55555", routerRootOf("http://127.0.0.1:55555/v1/"))
        assertEquals("http://127.0.0.1:55556", routerRootOf("http://127.0.0.1:55556"))
        assertEquals("http://127.0.0.1:55555", routerRootOf("  http://127.0.0.1:55555/v1  "))
        // No authority means no URL to build, and the readout says so rather than
        // pasting half a string into a shell.
        assertNull(routerRootOf(""))
        assertNull(routerRootOf("/v1"))
    }

    // ---------------------------------------------------------------- the decision

    @Test
    fun `slots are asked for only when exactly one model is resident`() {
        assertEquals("only", residentModelForSlots(listOf(unloaded("a"), loaded("only"))))
        assertEquals("only", residentModelForSlots(listOf(unloaded("a"), loading("only"))))
        assertNull(
            "nothing resident is the autoload trap",
            residentModelForSlots(listOf(unloaded("a"), unloaded("b"))),
        )
        assertNull(
            "two residents is not a state to guess about",
            residentModelForSlots(listOf(loaded("a"), loaded("b"))),
        )
        assertNull("an empty listing names nothing", residentModelForSlots(emptyList()))
    }

    @Test
    fun `not asking is never drawn as the router reporting nothing`() {
        val withheld = slotsForReadout(listOf(unloaded("a"), unloaded("b")), null)
        assertTrue(withheld.entries.isEmpty())
        assertNotNull("the reason must survive to the screen", withheld.reason)
        assertTrue(withheld.reason!!.contains("no model is loaded"))

        // The other reading: the router really did answer, and it held no slots.
        val answered = slotsForReadout(listOf(loaded("only")), RouterSlotsReadout(entries = emptyList()))
        assertNull("an answered read is not a withheld one", answered.reason)
        assertEquals("only", answered.model)

        // And a read that was attempted and failed is also a reason, not an emptiness.
        val failed = slotsForReadout(listOf(loaded("only")), RouterSlotsReadout(reason = "it timed out"))
        assertEquals("it timed out", failed.reason)
    }

    @Test
    fun `each refusal to ask has its own sentence`() {
        val nothingListed = slotsForReadout(emptyList(), null).reason
        val nothingLoaded = slotsForReadout(listOf(unloaded("a")), null).reason
        val twoLoaded = slotsForReadout(listOf(loaded("a"), loading("b")), null).reason
        assertNotNull(nothingListed)
        assertNotNull(nothingLoaded)
        assertNotNull(twoLoaded)
        assertFalse(nothingListed == nothingLoaded)
        assertFalse(nothingLoaded == twoLoaded)
        assertTrue(twoLoaded!!.contains("2 models are loaded at once"))
    }

    // ------------------------------------------------------------------- framing

    /** The wire text a read produces, from the same markers the command prints. */
    private fun readText(
        propsBody: String,
        propsHttp: Int,
        modelsBody: String,
        modelsHttp: Int,
    ): String = buildString {
        append("\u001b[?2004l\r\n")
        append(RouterReadoutMarkers.PROPS_BEGIN).append("\r\n")
        append(propsBody)
        append("\r\n").append(RouterReadoutMarkers.PROPS_HTTP).append(propsHttp).append(">>>\r\n")
        append(RouterReadoutMarkers.MODELS_BEGIN).append("\r\n")
        append(modelsBody)
        append("\r\n").append(RouterReadoutMarkers.MODELS_HTTP).append(modelsHttp).append(">>>\r\n")
        append(RouterReadoutMarkers.READ_END).append("\r\n\u001b[?2004h")
    }

    @Test
    fun `a framed read parses out of a stream carrying ANSI and CR`() {
        val chunk = parseReadoutChunk(readText(PROPS, 200, MODELS, 200))
        assertNotNull(chunk)
        // The bodies must be exactly the JSON the router sent, with the PTY's own noise
        // removed and nothing else touched.
        assertEquals(PROPS, chunk!!.propsBody)
        assertEquals(MODELS, chunk.modelsBody)
        assertEquals(200, chunk.propsHttp)
        assertEquals(200, chunk.modelsHttp)
    }

    @Test
    fun `a duplicated marker is a failed read, not an ambiguous one`() {
        // This is what an echoed line looks like: every marker twice. Picking either
        // occurrence would be a guess, and a guess here is a wrong number drawn as a
        // measured one — so the read fails instead.
        val twice = readText(PROPS, 200, MODELS, 200) + readText(PROPS, 200, MODELS, 200)
        assertNull(parseReadoutChunk(twice))

        val endTwice = readText(PROPS, 200, MODELS, 200) + RouterReadoutMarkers.READ_END
        assertNull("a marker appearing twice is a failed frame", parseReadoutChunk(endTwice))

        val beginTwice = RouterReadoutMarkers.PROPS_BEGIN +
            readText(PROPS, 200, MODELS, 200)
        assertNull(parseReadoutChunk(beginTwice))
    }

    @Test
    fun `a missing or reordered marker is a failed read`() {
        val full = readText(PROPS, 200, MODELS, 200)
        assertNull(parseReadoutChunk(full.substringBefore(RouterReadoutMarkers.READ_END)))

        val swapped = full
            .replace(RouterReadoutMarkers.PROPS_BEGIN, "@@TMP@@")
            .replace(RouterReadoutMarkers.MODELS_BEGIN, RouterReadoutMarkers.PROPS_BEGIN)
            .replace("@@TMP@@", RouterReadoutMarkers.MODELS_BEGIN)
        assertNull(parseReadoutChunk(swapped))
    }

    @Test
    fun `slots chunk parses, and a missing status fails it`() {
        val text = slotsText(SLOTS, 200)
        val parsed = parseSlotsChunk(text)
        assertNotNull(parsed)
        assertEquals(SLOTS, parsed!!.first)
        assertEquals(200, parsed.second)
        assertNull(parseSlotsChunk(text.substringBefore(RouterReadoutMarkers.SLOTS_HTTP)))
    }

    private fun slotsText(body: String, http: Int): String =
        RouterReadoutMarkers.SLOTS_BEGIN + "\r\n" + body + "\r\n" +
            RouterReadoutMarkers.SLOTS_HTTP + http + ">>>\r\n" + RouterReadoutMarkers.SLOTS_END

    // -------------------------------------------------------------- classification

    @Test
    fun `a live read becomes a reachable result carrying the router's own numbers`() {
        val result = classifyReadout(
            RouterReadoutChunk(PROPS, 200, MODELS, 200),
            RouterSlotsReadout(
                entries = slotsOf(SLOTS)!!,
                model = RESIDENT,
                contextSize = slotsContextSizeOf(SLOTS),
            ),
            atMillis = 42L,
        )
        assertTrue(result is RouterProbeResult.Reachable)
        val reachable = result as RouterProbeResult.Reachable
        assertEquals(42L, reachable.atMillis)
        assertEquals(listOf(RESIDENT, "granite-docling-258M-Q8_0"), reachable.modelIds)

        val load = reachable.load!!
        assertEquals("router", load.role)
        assertEquals(2, load.maxInstances)
        assertEquals(true, load.modelsAutoload)
        assertEquals(listOf(RESIDENT), load.loaded.map { it.id })
        // The launch argv is where the serving context and the child's port live, and
        // they are the router's numbers — not the profile's declarations.
        assertEquals(131072, load.loaded.first().contextSize)
        assertEquals(43723, load.loaded.first().childPort)
        // The second model's status carries no args, so both are absent rather than 0.
        assertNull(load.models[1].contextSize)
        assertNull(load.models[1].childPort)

        // Prefill: read, so there is no withheld reason, and every number the router sent.
        assertNull("a read readout is not a withheld one", load.slotsUnread)
        assertTrue(load.slotsRead)
        assertEquals(1, load.slots.size)
        val slot = load.slots.first()
        assertEquals(0, slot.id)
        assertFalse(slot.isProcessing)
        assertEquals(36, slot.promptTokens)
        assertEquals(0, slot.promptTokensProcessed)
        assertEquals(0, slot.promptTokensCache)
        assertEquals(0, slot.decoded)
        assertEquals(4096, load.slotsContextSize)
        assertEquals(RESIDENT, load.slotsModel)
    }

    @Test
    fun `no resident model yields a report with the slots withheld and named`() {
        val models = "{\"data\":[{\"id\":\"a\",\"status\":{\"value\":\"unloaded\"}}]}"
        val result = classifyReadout(
            RouterReadoutChunk(PROPS, 200, models, 200),
            slots = null,
            atMillis = 1L,
        ) as RouterProbeResult.Reachable
        val load = result.load!!
        assertEquals(1, load.models.size)
        assertTrue("nothing is loaded", load.loaded.isEmpty())
        assertFalse("a withheld read is not a read", load.slotsRead)
        assertNotNull(load.slotsUnread)
        // The distinction that matters: `models` is present, so this is *not* the
        // "listing was unreadable" failure, and `slots` is empty because nobody asked.
        assertTrue(load.slots.isEmpty())
    }

    @Test
    fun `an unreadable frame is its own sentence and never a router verdict`() {
        val result = classifyReadout(null, null, atMillis = 7L) as RouterProbeResult.Unreachable
        assertEquals(ROUTER_READOUT_FRAME_FAILURE, result.message)
        assertEquals(7L, result.atMillis)
    }

    @Test
    fun `a non-200 status names the endpoint rather than reporting no models`() {
        val propsDown = classifyReadout(RouterReadoutChunk("", 0, MODELS, 200), null, 1L)
            as RouterProbeResult.Unreachable
        assertTrue(propsDown.message.contains("/props"))
        assertTrue(propsDown.message.contains("curl status 000"))

        val modelsDown = classifyReadout(RouterReadoutChunk(PROPS, 200, "", 500), null, 1L)
            as RouterProbeResult.Unreachable
        assertTrue(modelsDown.message.contains("/v1/models"))
        assertTrue(modelsDown.message.contains("HTTP 500"))
    }

    @Test
    fun `a body that is not JSON is unreadable, not empty`() {
        val result = classifyReadout(RouterReadoutChunk(PROPS, 200, "not json", 200), null, 1L)
            as RouterProbeResult.Unreachable
        assertEquals(ROUTER_READOUT_MODELS_UNREADABLE, result.message)

        val badProps = classifyReadout(RouterReadoutChunk("[]", 200, MODELS, 200), null, 1L)
            as RouterProbeResult.Unreachable
        assertTrue(badProps.message.contains("/props"))
    }

    @Test
    fun `a report with no load is never a verdict and never a zero`() {
        // The third reading, and the one that is easy to lose: the endpoint answered,
        // the readout did not, and the two facts must not merge. `readoutIssue` is what
        // carries the second one, and its absence is what means "not offered here".
        val notOffered = RouterProbeResult.Reachable(modelIds = listOf("a"), atMillis = 1L)
        assertNull(notOffered.load)
        assertNull("silence is not a failure to report", notOffered.readoutIssue)

        val attempted = notOffered.copy(readoutIssue = "the terminal attachment did not open")
        assertNull(attempted.load)
        assertNotNull(attempted.readoutIssue)
    }

    @Test
    fun `an unfamiliar status word is carried verbatim`() {
        val models = "{\"data\":[{\"id\":\"x\",\"status\":{\"value\":\"quantising\"}}]}"
        val load = (classifyReadout(RouterReadoutChunk(PROPS, 200, models, 200), null, 1L)
            as RouterProbeResult.Reachable).load!!
        assertEquals("quantising", load.models.first().status)
        // Not loaded, not loading: the client must not coerce it into one of ours.
        assertFalse(load.models.first().isLoaded)
        assertFalse(load.models.first().isLoading)
        assertNull(residentModelForSlots(load.models))
    }

    // --------------------------------------------------------------------- bits

    @Test
    fun `the reserved terminal id is recognised and nothing else is`() {
        assertTrue(isRouterReadoutTerminal(ROUTER_READOUT_TERMINAL_ID))
        assertFalse(isRouterReadoutTerminal("router-readout"))
        assertFalse(isRouterReadoutTerminal(""))
        assertFalse(isRouterReadoutTerminal("$ROUTER_READOUT_TERMINAL_ID-2"))
    }

    @Test
    fun `model statuses read the launch args by name and skip unreadable entries`() {
        val array = JSONArray(
            "[{\"id\":\"a\",\"status\":{\"value\":\"loaded\"," +
                "\"args\":[\"--port\",\"43723\",\"--ctx-size\",\"122880\"]}}," +
                "\"nonsense\"," +
                "{\"status\":{\"value\":\"loaded\"}}]",
        )
        val models = modelStatusesOf(array)
        assertEquals("the two entries without an id are skipped", 1, models.size)
        assertEquals(43723, models[0].childPort)
        assertEquals(122880, models[0].contextSize)

        val flagWithoutValue = modelStatusesOf(
            JSONArray("[{\"id\":\"b\",\"status\":{\"value\":\"unloaded\",\"args\":[\"--port\"]}}]"),
        )
        assertNull("a flag with no value has no value", flagWithoutValue[0].childPort)
        assertEquals(0, modelStatusesOf(JSONArray("[]")).size)
    }

    @Test
    fun `a slot report is source-faithful to the router's own field names`() {
        val slots = slotsOf(SLOTS)!!
        assertEquals(1, slots.size)
        val slot = slots.first()
        assertEquals(36, slot.promptTokens)
        // `n_remain` is absent from this fixture and must stay absent, not become 0.
        assertNull(slot.remaining)
        assertEquals(0, slot.decoded)
        assertNull("a body that is not an array is not slot state", slotsOf("{\"id\":0}"))
    }

    private companion object {
        const val RESIDENT = "Qwen3-Embedding-0.6B-Q8_0"

        /** The `/props` document `127.0.0.1:55556` answered, trimmed. */
        const val PROPS = "{\"role\":\"router\",\"max_instances\":2,\"models_autoload\":true," +
            "\"model_alias\":\"llama-server\",\"model_path\":\"none\",\"build_info\":\"b0-unknown\"}"

        /** The `data` array shape `/v1/models` answered, trimmed to two entries. */
        const val MODELS = "{\"data\":[" +
            "{\"id\":\"Qwen3-Embedding-0.6B-Q8_0\",\"object\":\"model\"," +
            "\"status\":{\"value\":\"loaded\",\"args\":[\"/usr/bin/llama-server\"," +
            "\"--host\",\"127.0.0.1\",\"--port\",\"43723\",\"--alias\"," +
            "\"Qwen3-Embedding-0.6B-Q8_0\",\"--ctx-size\",\"131072\"]}}," +
            "{\"id\":\"granite-docling-258M-Q8_0\",\"object\":\"model\"," +
            "\"status\":{\"value\":\"unloaded\"}}]}"

        /** The first entry of what `/slots?model=Qwen3-Embedding-0.6B-Q8_0` answered. */
        const val SLOTS = "[{\"id\":0,\"n_ctx\":4096,\"speculative\":false," +
            "\"is_processing\":false,\"id_task\":9722,\"n_prompt_tokens\":36," +
            "\"n_prompt_tokens_processed\":0,\"n_prompt_tokens_cache\":0," +
            "\"prompt\":\"\",\"next_token\":[{\"n_decoded\":0}]}]"

        fun loaded(id: String) = RouterModelStatus(id = id, status = "loaded")

        fun loading(id: String) = RouterModelStatus(id = id, status = "loading")

        fun unloaded(id: String) = RouterModelStatus(id = id, status = "unloaded")
    }
}
