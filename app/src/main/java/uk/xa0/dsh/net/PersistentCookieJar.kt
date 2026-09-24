package uk.xa0.dsh.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cookie store that survives process death.
 *
 * This is what makes the LAN door work without any credential handling: nginx in
 * front of a DSH host injects `Set-Cookie: dsh-auth-*`, and `/auth/login` returns
 * `dsh_auth_session`. Both are HttpOnly, so the app must behave like a browser and
 * replay them on every request.
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("dsh_cookies", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cache = mutableMapOf<String, MutableList<Cookie>>()

    init {
        prefs.getString(KEY, null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val cookie = Cookie.Builder()
                        .name(obj.getString("name"))
                        .value(obj.getString("value"))
                        .domain(obj.getString("domain"))
                        .path(obj.getString("path"))
                        .apply {
                            if (obj.optBoolean("secure")) secure()
                            if (obj.optBoolean("httpOnly")) httpOnly()
                            val expires = obj.optLong("expires", 0L)
                            if (expires > 0L) expiresAt(expires)
                        }
                        .build()
                    cache.getOrPut(cookie.domain) { mutableListOf() }.add(cookie)
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (cookie in cookies) {
                val list = cache.getOrPut(cookie.domain) { mutableListOf() }
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list.add(cookie)
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val result = mutableListOf<Cookie>()
            for ((_, list) in cache) {
                list.removeAll { it.expiresAt < now }
                for (cookie in list) {
                    if (cookie.matches(url)) result.add(cookie)
                }
            }
            return result
        }
    }

    /** Replaces every stored cookie with one raw `name=value` pair for [url]. */
    fun setRawCookie(url: HttpUrl, header: String) {
        synchronized(lock) {
            val at = header.indexOf('=')
            if (at <= 0) return
            val name = header.substring(0, at).trim()
            val value = header.substring(at + 1).trim()
            val cookie = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(url.host)
                .path("/")
                .build()
            cache[url.host] = mutableListOf(cookie)
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            prefs.edit().remove(KEY).apply()
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (list in cache.values) {
            for (cookie in list) {
                array.put(
                    JSONObject()
                        .put("name", cookie.name)
                        .put("value", cookie.value)
                        .put("domain", cookie.domain)
                        .put("path", cookie.path)
                        .put("secure", cookie.secure)
                        .put("httpOnly", cookie.httpOnly)
                        .put("hostOnly", cookie.hostOnly)
                        .put("expires", cookie.expiresAt),
                )
            }
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "jar"
    }
}
