package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balance reply, against the shape DeepSeek actually documents.
 *
 * Two things matter beyond the happy path. Every figure arrives as a **string**, so the
 * screen prints what DeepSeek said rather than a Double this app rounded for it. And a
 * row without a currency is dropped: the panel labels every figure with its currency,
 * and there is nothing to print for a row that did not say.
 */
class BalanceTest {

    private fun reply(json: String): AccountBalance? = parseBalance(JSONObject(json))

    @Test
    fun `a documented reply parses`() {
        val account = reply(
            """
            { "is_available": true,
              "balance_infos": [ { "currency": "CNY", "total_balance": "12.34",
                                   "granted_balance": "2.00", "topped_up_balance": "10.34" } ] }
            """.trimIndent(),
        )!!
        assertTrue(account.isAvailable)
        val cny = account.primary!!
        assertEquals("CNY", cny.currency)
        // Strings end to end: "12.34", not 12.34 — the display is DeepSeek's own text.
        assertEquals("12.34", cny.totalBalance)
        assertEquals("2.00", cny.grantedBalance)
        assertEquals("10.34", cny.toppedUpBalance)
    }

    /** The prices this app shows are in yuan, so a CNY row leads when there is one. */
    @Test
    fun `cny is preferred, and another currency is still carried`() {
        val account = reply(
            """
            { "is_available": true,
              "balance_infos": [ { "currency": "USD", "total_balance": "1.00",
                                   "granted_balance": "0", "topped_up_balance": "1.00" },
                                 { "currency": "cny", "total_balance": "7.10",
                                   "granted_balance": "0", "topped_up_balance": "7.10" } ] }
            """.trimIndent(),
        )!!
        assertEquals("CNY", account.primary!!.currency)
        assertEquals(2, account.infos.size)
        assertTrue("the other one is kept, not dropped", account.infos.any { it.currency == "USD" })
    }

    @Test
    fun `an unavailable account is reported as such`() {
        val account = reply(
            """{ "is_available": false, "balance_infos": [ { "currency": "CNY",
                "total_balance": "0.00", "granted_balance": "0.00", "topped_up_balance": "0.00" } ] }""",
        )!!
        assertEquals(false, account.isAvailable)
        assertEquals("0.00", account.primary!!.totalBalance)
    }

    /**
     * A missing `is_available` is not "unavailable": the flag describes whether the
     * account can serve, and inventing a warning from its absence would paint a red
     * card over a perfectly healthy account.
     */
    @Test
    fun `an absent availability flag does not mean unavailable`() {
        val account = reply("""{ "balance_infos": [ { "currency": "CNY", "total_balance": "5.00" } ] }""")!!
        assertTrue(account.isAvailable)
        assertEquals("5.00", account.primary!!.totalBalance)
        // Absent sub-figures come back empty rather than as "null".
        assertEquals("", account.primary!!.grantedBalance)
    }

    @Test
    fun `a row without a currency is dropped`() {
        val account = reply(
            """{ "balance_infos": [ { "total_balance": "9.99" },
                                   { "currency": "CNY", "total_balance": "1.00" } ] }""",
        )!!
        assertEquals(1, account.infos.size)
        assertEquals("1.00", account.primary!!.totalBalance)
    }

    @Test
    fun `a payload that is not a balance is not read as one`() {
        assertNull(parseBalance(null))
        assertNull(parseBalance(JSONObject("""{"error":{"message":"nope"}}""")))
        // An empty list is a balance of nothing, not a parse failure: the screen says
        // DeepSeek reported no balances.
        assertEquals(0, reply("""{"is_available":true,"balance_infos":[]}""")!!.infos.size)
        assertNull(reply("""{"is_available":true,"balance_infos":[]}""")!!.primary)
    }

    /** The top-up link is the platform page, which is the only place a top-up happens. */
    @Test
    fun `the top-up url is deepseek's own page`() {
        assertEquals("https://platform.deepseek.com/top_up", DEEPSEEK_TOP_UP_URL)
        assertEquals("https://api.deepseek.com", DEEPSEEK_API_BASE)
    }
}
