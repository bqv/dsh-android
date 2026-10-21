package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shape of a `session/list` answer, and what the drawer says when one fails.
 *
 * The bug this closes is "the drawer is empty and nothing says why": an answer
 * with no `items` array was read as an empty roster, so the app replaced a good
 * list with nothing and reported success. The protocol's own empty roster is
 * `"items": []`, which stays valid — a host with no sessions is allowed to say
 * so — while every unreadable answer becomes a failure the caller can report and
 * that leaves the previous list (labelled) on screen.
 *
 * It cannot prove the ViewModel keeps the list on a failure; that is
 * [uk.xa0.dsh.DshViewModel]'s `applyRoster`, which the JVM harness cannot reach.
 */
class SessionListAnswerTest {

    @Test
    fun `an answer without an items array is a failure, not an empty roster`() {
        for (raw in listOf("{}", """{"value":{}}""", """{"items":"none"}""")) {
            try {
                sessionListItems(JSONObject(raw))
                fail("accepted an unreadable roster: $raw")
            } catch (expected: SessionListShapeException) {
                assertTrue(expected.message.orEmpty().contains("items"))
            }
        }
    }

    @Test
    fun `the protocol's empty roster is still an empty roster`() {
        val items: JSONArray = sessionListItems(JSONObject("""{"items":[]}"""))

        assertEquals(0, items.length())
    }

    @Test
    fun `a real answer is handed through untouched`() {
        val items = sessionListItems(
            JSONObject("""{"items":[{"sessionId":"a"},{"sessionId":"b"}]}"""),
        )

        assertEquals(2, items.length())
        assertEquals("a", items.getJSONObject(0).getString("sessionId"))
    }

    @Test
    fun `the stale note carries the reason and says the list is old`() {
        val note = sessionListErrorNote("HTTP 504: the host did not answer")

        assertTrue(note.contains("Could not refresh the session list"))
        assertTrue(note.contains("HTTP 504: the host did not answer"))
        assertTrue("must say the rows are old, not gone", note.contains("last one that loaded"))
        assertFalse("must not claim the list is empty", note.contains("no sessions"))
    }
}
