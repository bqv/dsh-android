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
