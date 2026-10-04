package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * Holding a list's order still while somebody is reading it — the rule behind the
 * drawer not re-sorting under the reader.
 */
class SessionOrderTest {

    private fun session(id: String, updatedAt: Long) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = updatedAt,
        running = false,
        isSubagent = false,
    )

    private val a = session("a", 1)
    private val b = session("b", 2)
    private val c = session("c", 3)

    @Test
    fun `with nothing held the list is left alone`() {
        val live = listOf(c, b, a)
        assertEquals(live, holdOrder(live, emptyList()))
    }

    @Test
    fun `a held order is restored over the live one`() {
        // The roster now says c, b, a — the reader was looking at a, b, c.
        val held = holdOrder(listOf(c, b, a), held = listOf("a", "b", "c"))
        assertEquals(listOf(a, b, c), held)
    }

    @Test
    fun `a session that arrives while the order is held goes to the front`() {
        // Membership is never held: a stale row is worse than a moved one.
        val fresh = session("fresh", 9)
        val held = holdOrder(listOf(fresh, c, b, a), held = listOf("a", "b", "c"))
        assertEquals(listOf(fresh, a, b, c), held)
    }

    @Test
    fun `a session that leaves while the order is held is gone`() {
        val held = holdOrder(listOf(c, a), held = listOf("a", "b", "c"))
        assertEquals(listOf(a, c), held)
    }

    @Test
    fun `two sessions that both arrived keep the order they arrived in`() {
        val one = session("one", 9)
        val two = session("two", 8)
        val held = holdOrder(listOf(one, two, b, a), held = listOf("a", "b"))
        assertEquals(listOf(one, two, a, b), held)
    }
}
