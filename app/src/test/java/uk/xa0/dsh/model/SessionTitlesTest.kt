package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer's one label rule.
 *
 * The regression this pins: a session the app named itself — the host shell's
 * bootstrap renames the session it creates — is still `blank` (no user message was
 * ever sent to it), so testing `blank` before the stored title threw the name away
 * and the archived shell read as "New Session" while the host reported
 * `Host shell · /…` as its title.
 */
class SessionTitlesTest {

    private fun title(
        subagent: String? = null,
        stored: String? = null,
        blank: Boolean = false,
        dir: String? = null,
        id: String = "session-1",
    ) = sessionRowTitle(subagentLabel = subagent, stored = stored, blank = blank, directoryName = dir, id = id)

    @Test
    fun `a blank row with no name is the placeholder`() {
        assertEquals("New Session", title(blank = true, dir = "dsh-android"))
    }

    /** The regression: a name set on purpose survives the blank flag. */
    @Test
    fun `a named blank row keeps its name`() {
        val stored = "Host shell · /home/user/var/work/dsh-android"
        assertEquals(stored, title(stored = stored, blank = true, dir = "dsh-android"))
        assertEquals(stored, title(stored = stored, blank = false))
    }

    @Test
    fun `a subagent label outranks everything`() {
        assertEquals(
            "Fix the thing",
            title(subagent = "Fix the thing", stored = "Host shell · /tmp", blank = true, dir = "tmp"),
        )
    }

    @Test
    fun `an untitled row falls back to its directory, then its id`() {
        assertEquals("dsh-android", title(dir = "dsh-android"))
        assertEquals("session-1", title(dir = ""))
        assertEquals("session-1", title(dir = null))
    }

    @Test
    fun `blank text is not a name`() {
        assertEquals("New Session", title(stored = "", blank = true, dir = "dsh-android"))
        assertEquals("dsh-android", title(subagent = "", stored = "   ", dir = "dsh-android"))
    }
}
