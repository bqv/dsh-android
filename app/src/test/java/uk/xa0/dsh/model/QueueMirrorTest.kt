package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's pending queue, as the host actually publishes it.
 *
 * 0.2.0 sends no `queue` frame and its `session/control` baseline carries
 * `projections` alone; the queue is the `inbox` session projection, whose value is
 * `next-turn` plus `next-step` arrays of messages. This is the shape the host's
 * own projection definition describes (`@deepseek-ai/dsh-agent-loop`,
 * `inboxProjectionSchema`) and the one the host web client reads
 * (`inbox?.["next-turn"]`).
 *
 * What is pinned here is the part that decides whether the dock can edit at all: a
 * row from this projection must come out *host-backed* — `pending` false, a real
 * item id, and the full text wherever every block is text — because the dock's
 * Edit action is enabled only for such a row. A client that does not read this
 * projection (which is what happened: the read was the control stream's, and the
 * host stopped sending it) can only ever draw local echoes, whose actions are
 * disabled by construction.
 */
class QueueMirrorTest {

    private val source = "\"source\":{\"kind\":\"user\",\"rpcId\":\"req-7\"}"

    /** One `inbox` projection value, as the host's schema writes it. */
    private fun inbox(vararg messages: String): JSONObject = JSONObject(
        "{\"next-turn\":[" + messages.joinToString(",") + "],\"next-step\":[]}",
    )

    private fun textMessage(id: String, vararg blocks: String): String =
        "{\"id\":\"$id\",\"content\":[" + blocks.joinToString(",") + "]," + source + "}"

    private fun text(text: String): String = "{\"type\":\"text\",\"text\":\"$text\"}"

    @Test
    fun `a next-turn message becomes a host-backed row with its full text`() {
        val rows = parseQueueItems(
            inboxQueueItems(inbox(textMessage("m-1", text("run the tests")))),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("m-1", row.id)
        assertEquals("queued", row.placement)
        assertEquals("run the tests", row.preview)
        assertEquals("run the tests", row.text)
        assertEquals("req-7", row.rpcId)
        // The whole point: a host-backed row is what the dock lets you edit, and
        // the request id is what retires the local echo standing in for it.
        assertEquals(false, row.pending)
    }

    @Test
    fun `the preview collapses whitespace and the text does not`() {
        val rows = parseQueueItems(
            inboxQueueItems(
                inbox(
                    textMessage(
                        "m-1",
                        text("  one\\n\\n two "),
                        text("three"),
                    ),
                ),
            ),
        )

        assertEquals("one two three", rows.single().preview)
        // `textOf` joins with no separator, unlike the preview — so the trailing
        // space of the first block stays, and there is no space before "three".
        assertEquals("  one\n\n two three", rows.single().text)
    }

    @Test
    fun `a non-text block keeps the text null, which is what disables Edit`() {
        val image = "{\"type\":\"image\",\"attachment\":{\"attachmentId\":\"a-1\"," +
            "\"name\":\"shot.png\",\"bytes\":2048}}"
        val rows = parseQueueItems(
            inboxQueueItems(inbox(textMessage("m-2", text("see this"), image))),
        )

        val row = rows.single()
        // `previewOf` filters image and file blocks out: an attachment draws a chip
        // of its own, and the preview is the *non-attachment* content only. `[type]`
        // is for an unknown block, not for an attachment — see the next test.
        assertEquals("see this", row.preview)
        // The rule that gates Edit: one non-text block and `textOf` answers null, so
        // the dock draws the "editing is not supported" label rather than an editor
        // whose save the host would refuse.
        assertNull(row.text)
        assertEquals(1, row.attachments.size)
        assertEquals("image", row.attachments.single().kind)
        assertEquals("shot.png", row.attachments.single().name)
        assertEquals(2048, row.attachments.single().bytes)
    }

    @Test
    fun `an unknown block names itself in the preview and also forces text null`() {
        val rows = parseQueueItems(
            inboxQueueItems(
                inbox(textMessage("m-5", text("listen"), "{\"type\":\"audio\"}")),
            ),
        )

        val row = rows.single()
        assertEquals("listen [audio]", row.preview)
        assertNull(row.text)
    }

    @Test
    fun `a message with no source has no request id to retire an echo with`() {
        val rows = parseQueueItems(
            inboxQueueItems(inbox("{\"id\":\"m-3\",\"content\":[${text("hi")}]}")),
        )

        assertNull(rows.single().rpcId)
    }

    @Test
    fun `next-step is not a dock row, matching the web dock`() {
        // The web client reads `next-turn` only; an item the host injected into the
        // running step is not something it offers to edit, and drawing one here
        // would offer an edit the host has no pending occurrence for.
        val inbox = JSONObject(
            "{\"next-turn\":[],\"next-step\":[" +
                textMessage("s-1", text("injected")) + "]}",
        )

        assertTrue(parseQueueItems(inboxQueueItems(inbox)).isEmpty())
    }

    @Test
    fun `an absent inbox is an empty queue, not a crash`() {
        assertTrue(parseQueueItems(inboxQueueItems(null)).isEmpty())
        assertTrue(parseQueueItems(inboxQueueItems(JSONObject())).isEmpty())
    }

    @Test
    fun `the preview is capped where the web caps it`() {
        val long = "x".repeat(QUEUE_PREVIEW_CHARS + 40)
        val rows = parseQueueItems(
            inboxQueueItems(inbox(textMessage("m-4", text(long)))),
        )

        assertEquals(QUEUE_PREVIEW_CHARS + 1, rows.single().preview.length)
        assertTrue(rows.single().preview.endsWith("…"))
        // The *editor* is seeded from `text`, so the cap must not reach it.
        assertEquals(long, rows.single().text)
    }

    @Test
    fun `the old control-stream item shape still parses`() {
        // A host that still sends a `queue` frame is read rather than dropped; the
        // two shapes differ in where the content and the request id sit.
        val items = JSONArray(
            "[{\"id\":\"m-9\",\"placement\":\"queued\",\"rpcId\":\"req-9\"," +
                "\"message\":{\"content\":[${text("older host")} ]}}]",
        )

        val row = parseQueueItems(items).single()
        assertEquals("m-9", row.id)
        assertEquals("older host", row.text)
        assertEquals("req-9", row.rpcId)
    }
}
