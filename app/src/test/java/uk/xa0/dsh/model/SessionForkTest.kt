package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `session/fork` body, pinned at the wire.
 *
 * The host reads `request.sessionId` and an optional `request.atSeq`
 * (`packages/api/session-controller/src/index.ts`, `fork()`), and the difference
 * between the two shapes is the whole feature: **omitting** `atSeq` forks the latest
 * completed turn, while a number forks exactly that event's prefix. A null or a
 * zero standing in for "latest" would not be the same request.
 */
class SessionForkTest {

    @Test
    fun `a fork with no boundary omits atSeq entirely`() {
        val body = sessionForkRequest("session-1")
        assertEquals("session-1", body.getString("sessionId"))
        assertFalse("atSeq must be absent, not null", body.has("atSeq"))
    }

    @Test
    fun `an explicit boundary is carried as a number`() {
        val body = sessionForkRequest("session-1", 42)
        assertEquals("session-1", body.getString("sessionId"))
        assertTrue(body.has("atSeq"))
        assertEquals(42, body.getInt("atSeq"))
    }
}
