package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * One currency's side of a DeepSeek account balance.
 *
 * The wire shape is `GET https://api.deepseek.com/user/balance`:
 *
 * ```json
 * { "is_available": true,
 *   "balance_infos": [ { "currency": "CNY", "total_balance": "12.34",
 *                        "granted_balance": "2.00", "topped_up_balance": "10.34" } ] }
 * ```
 *
 * Every figure is a **string** on the wire — DeepSeek sends them as decimal text, not
 * as numbers — so they are carried as strings here and formatted for display rather
 * than parsed into a Double that would then have to invent a rounding. `balance_infos`
 * can hold more than one currency, which is why this is a list and not a value.
 */
data class BalanceInfo(
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String,
)

/** A whole balance reply: whether the account can be used, and in which currencies. */
data class AccountBalance(
    val isAvailable: Boolean,
    val infos: List<BalanceInfo>,
) {
    /**
     * The row to lead with: CNY when the account has it, else the first.
     *
     * The prices this app shows are in yuan, so a CNY balance is the one that reads
     * against them; anything else is shown for what it is rather than converted.
     */
    val primary: BalanceInfo?
        get() = infos.firstOrNull { it.currency.equals("CNY", ignoreCase = true) } ?: infos.firstOrNull()
}

/**
 * Reads a balance reply, or null when the payload is not one.
 *
 * A row without a currency is dropped rather than shown as an unlabelled figure: the
 * screen prints the currency next to every number, and there is nothing sensible to
 * print for a row that did not say.
 */
fun parseBalance(root: JSONObject?): AccountBalance? {
    root ?: return null
    val infos = root.optJSONArray("balance_infos") ?: return null
    val rows = ArrayList<BalanceInfo>(infos.length())
    for (i in 0 until infos.length()) {
        val row = infos.optJSONObject(i) ?: continue
        val currency = row.optString("currency").trim()
        if (currency.isEmpty()) continue
        rows += BalanceInfo(
            currency = currency.uppercase(),
            totalBalance = row.optString("total_balance").trim(),
            grantedBalance = row.optString("granted_balance").trim(),
            toppedUpBalance = row.optString("topped_up_balance").trim(),
        )
    }
    // `is_available` absent is not "unavailable": the flag describes whether the
    // account can serve requests, and a missing one must not paint a warning.
    return AccountBalance(isAvailable = root.optBoolean("is_available", true), infos = rows)
}

/** Where a top-up happens: DeepSeek's own platform page, opened in the browser. */
const val DEEPSEEK_TOP_UP_URL: String = "https://platform.deepseek.com/top_up"

/** The host a balance is read from. Its own endpoint, with the user's own key. */
const val DEEPSEEK_API_BASE: String = "https://api.deepseek.com"
