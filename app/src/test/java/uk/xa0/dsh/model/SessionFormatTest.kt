package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tool result, in both session formats.
 *
 * Session format v4 — what a 0.2.0 host writes — puts a result's parts straight into
 * the message. Up to and including v3 they were wrapped in a single `tool-result` part
 * that also carried the error flag. The wrapper's disappearance was not noticed by
 * anything except the screen: **every** bash row in the app read "No output" and every
 * image result rendered as nothing, from one field that stopped being a wrapper.
 *
 * Both shapes are asserted against the same expectations here, because both are on disk
 * and a transcript from last week is still a transcript.
 */
class SessionFormatTest {

    private fun toolCall(callId: String) = JSONObject()
        .put("type", "tool/call")
        .put("seq", 1)
        .put("time", 1000L)
        .put(
            "data",
            JSONObject()
                .put("turn", 1)
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "tool-call")
                        .put("toolCallId", callId)
                        .put("toolName", "bash")
                        .put("arguments", """{"command":"ls","description":"list"}"""))))
        )

    /** v4: parts directly on the message, `isError` on the message. */
    private fun flatResult(callId: String, text: String, isError: Boolean = false) = JSONObject()
        .put("type", "tool/result")
        .put("seq", 2)
        .put("time", 1001L)
        .put(
            "data",
            JSONObject().put("turn", 1).put(
                "message",
                JSONObject()
                    .put("role", "tool")
                    .put("toolCallId", callId)
                    .put("isError", isError)
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            ),
        )

    /** v3: one `tool-result` wrapper carrying the parts and the error flag. */
    private fun wrappedResult(callId: String, text: String, isError: Boolean = false) = JSONObject()
        .put("type", "tool/result")
        .put("seq", 2)
        .put("time", 1001L)
        .put(
            "data",
            JSONObject().put("turn", 1).put(
                "message",
                JSONObject()
                    .put("role", "tool")
                    .put("toolCallId", callId)
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool-result")
                                .put("toolCallId", callId)
                                .put("isError", isError)
                                .put("content", JSONArray().put(
                                    JSONObject().put("type", "text").put("text", text),
                                )),
                        ),
                    ),
            ),
        )

    private fun resultOf(result: JSONObject): ChatEntry.ToolCall {
        val reducer = TranscriptReducer()
        reducer.applyEvent(toolCall("call_1"))
        reducer.applyEvent(result)
        return reducer.snapshot().filterIsInstance<ChatEntry.ToolCall>().single()
    }

    /** v4: an image result is a part on the message, with its ref nested. */
    private fun flatImageResult(callId: String) = JSONObject()
        .put("type", "tool/result")
        .put("seq", 2)
        .put("time", 1001L)
        .put(
            "data",
            JSONObject().put("turn", 1).put(
                "message",
                JSONObject()
                    .put("role", "tool")
                    .put("toolCallId", callId)
                    .put("isError", false)
                    .put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "<path>/tmp/a.png</path>"))
                        .put(JSONObject().put("type", "image").put(
                            "attachment",
                            JSONObject()
                                .put("attachmentId", "sha256:abc")
                                .put("mediaType", "image/png")
                                .put("bytes", 1234)
                                .put("name", "a.png"),
                        ))),
            ),
        )

    @Test
    fun `a flat image result carries its picture`() {
        // The other half of the wrapper's disappearance, and the half no test covered:
        // the app read "No output" *and* drew nothing, and only the text was pinned.
        val entry = resultOf(flatImageResult("call_1"))
        assertEquals(1, entry.images.size)
        assertEquals("sha256:abc", entry.images.first().attachmentId)
        assertEquals("image", entry.images.first().kind)
    }

    @Test
    fun `a flat result carries its text`() {
        assertEquals("total 0\n-rw-r--r-- notes.md", resultOf(flatResult("call_1", "total 0\n-rw-r--r-- notes.md")).result)
    }

    @Test
    fun `a wrapped result carries its text`() {
        assertEquals("total 0\n-rw-r--r-- notes.md", resultOf(wrappedResult("call_1", "total 0\n-rw-r--r-- notes.md")).result)
    }

    @Test
    fun `a flat result reports failure`() {
        assertTrue(resultOf(flatResult("call_1", "no such file", isError = true)).isError)
    }

    @Test
    fun `a wrapped result reports failure`() {
        assertTrue(resultOf(wrappedResult("call_1", "no such file", isError = true)).isError)
    }

    @Test
    fun `neither shape reads as a failure when it succeeded`() {
        assertFalse(resultOf(flatResult("call_1", "ok")).isError)
        assertFalse(resultOf(wrappedResult("call_1", "ok")).isError)
    }
}
