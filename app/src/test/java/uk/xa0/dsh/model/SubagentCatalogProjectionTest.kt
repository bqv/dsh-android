package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `subagentCatalog` projection, which is where a parent's children come from on
 * 0.2.0 — `subagents/list` is not a host method any more.
 *
 * Captured from the live host: rows of `id`, `createdAt`, `mode` and `label`.
 */
class SubagentCatalogProjectionTest {

    private fun rows(vararg ids: String) = JSONArray().also { array ->
        ids.forEachIndexed { index, id ->
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("createdAt", 1790097547288L + index)
                    .put("mode", "continuable")
                    .put("label", "Research $id"),
            )
        }
    }

    @Test
    fun `the rows become children with their mode and label`() {
        val catalog = parseSubagentCatalogProjection(rows("a", "b"))
        assertEquals(2, catalog.children.size)
        assertEquals("a", catalog.children[0].id)
        assertEquals("continuable", catalog.children[0].mode)
        assertEquals("Research a", catalog.children[0].label)
    }

    @Test
    fun `hasChildren comes from the roster, not the projection`() {
        val catalog = parseSubagentCatalogProjection(rows("a")) { it == "a" }
        assertEquals(true, catalog.children.single().hasChildren)
    }

    @Test
    fun `parentAvailable stays unknown, which the gate treats as available`() {
        assertNull(parseSubagentCatalogProjection(rows("a")).parentAvailable)
    }

    @Test
    fun `an absent projection is an empty catalog rather than a crash`() {
        assertEquals(0, parseSubagentCatalogProjection(null).children.size)
    }

    @Test
    fun `a row without an id is dropped rather than addressed by an empty string`() {
        val catalog = parseSubagentCatalogProjection(
            JSONArray().put(JSONObject().put("mode", "one-shot")).put(rows("b").get(0)),
        )
        assertEquals(listOf("b"), catalog.children.map { it.id })
    }
}
