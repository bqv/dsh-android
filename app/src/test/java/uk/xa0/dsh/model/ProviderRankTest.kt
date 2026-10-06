package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's provider order must match the desktop's (`orderModelProviders`). */
class ProviderRankTest {

    @Test
    fun `the signed-in account leads, then the official provider`() {
        assertEquals(0, providerRank("deepseek-account"))
        assertEquals(1, providerRank("deepseek-official"))
    }

    @Test
    fun `every other provider shares the last rank, so the catalog order survives`() {
        val local = providerRank("dsh-local")
        val aux = providerRank("dsh-local-aux")
        val groq = providerRank("free-groq")
        assertEquals(local, aux)
        assertEquals(aux, groq)
        assertTrue(local > providerRank("deepseek-official"))
    }

    @Test
    fun `sorting is stable and puts the two deepseek providers first`() {
        val catalog = listOf("dsh-local", "free-groq", "deepseek-official", "free-mistral", "deepseek-account")
        val sorted = catalog.sortedBy { providerRank(it) }
        assertEquals(listOf("deepseek-account", "deepseek-official", "dsh-local", "free-groq", "free-mistral"), sorted)
    }
}
