package uk.xa0.dsh.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uk.xa0.dsh.model.AccountBalance
import uk.xa0.dsh.model.DEEPSEEK_API_BASE
import uk.xa0.dsh.model.parseBalance
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The account's balance, read from DeepSeek with the user's own key.
 *
 * The only outbound call this app makes that does **not** go to the DSH host, and it
 * goes nowhere else either: the balance is DeepSeek's own `GET /user/balance`, and it
 * is the only account endpoint DeepSeek publishes — there is no spend or history API,
 * which is why the app prices tokens itself rather than asking.
 *
 * No cookie jar and no host cookie: this is a different service, and the key is the
 * whole credential. A wrong key answers 401/403 and is reported as such, because
 * "your key was refused" and "DeepSeek is unreachable" are different things to a
 * reader staring at an empty balance card.
 */
class DeepSeekAccount(
    private val baseUrl: String = DEEPSEEK_API_BASE,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Throws [BalanceFailure] with wording fit to show: the key's refusal, DeepSeek's
     * own error message, or the transport's.
     */
    suspend fun balance(apiKey: String): AccountBalance {
        if (apiKey.isBlank()) throw BalanceFailure("No DeepSeek API key saved.")
        val request = Request.Builder()
            .url("$baseUrl/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            val body = try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw BalanceFailure(refusal(response.code, text))
                    text
                }
            } catch (error: IOException) {
                throw BalanceFailure(error.message ?: "Could not reach api.deepseek.com")
            }
            parseBalance(runCatching { JSONObject(body) }.getOrNull())
                ?: throw BalanceFailure("DeepSeek's answer was not a balance.")
        }
    }

    /** DeepSeek's own wording when it sends one, and the status when it does not. */
    private fun refusal(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return when {
            message != null -> message
            code == 401 || code == 403 -> "DeepSeek refused that API key."
            else -> "DeepSeek answered HTTP $code."
        }
    }
}

/** A balance read that could not be answered, with wording to show a reader. */
class BalanceFailure(message: String) : Exception(message)
