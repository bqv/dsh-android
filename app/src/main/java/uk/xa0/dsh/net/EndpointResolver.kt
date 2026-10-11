package uk.xa0.dsh.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The host of a configured address, with the scheme optional.
 *
 * The config stores what the user typed — `192.168.1.110`, `http://…`,
 * `https://…` — and only [DshClient]'s own normaliser knows the default scheme,
 * so the private-address check cannot assume a URL.
 */
internal fun hostOf(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
    return withScheme.toHttpUrlOrNull()?.host
}

/**
 * True for the addresses a phone only reaches on its own network: the RFC1918
 * IPv4 blocks and mDNS `.local` names.
 *
 * This is what lets an install that predates [uk.xa0.dsh.data.DshConfig.lanUrl]
 * keep working with nothing re-entered: its `baseUrl` *is* the LAN door, and
 * treating it as the LAN candidate is the whole migration.
 */
internal fun isPrivateLanHost(host: String?): Boolean {
    val lower = host?.lowercase() ?: return false
    if (lower.endsWith(".local")) return true
    val octets = parseIpv4(lower) ?: return false
    return when {
        octets[0] == 10 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}

/**
 * The configured LAN address re-addressed onto the phone's own /24, or null when
 * there is nothing to move.
 *
 * The host is configured once (`192.168.1.110`) and the phone then joins a
 * different network (`192.168.0.x`) — a router swap, a hotspot, a second home.
 * The DSH machine keeps its last octet, so the same octet on the phone's subnet
 * is the candidate worth trying; only a literal IPv4 can be re-addressed, since
 * a `.local` name already follows the network.
 */
internal fun subnetRewritten(lan: HttpUrl, localIpv4: String?): HttpUrl? {
    val local = localIpv4?.let { parseIpv4(it.trim()) } ?: return null
    val target = parseIpv4(lan.host) ?: return null
    if (local[0] == target[0] && local[1] == target[1] && local[2] == target[2]) return null
    val moved = "${local[0]}.${local[1]}.${local[2]}.${target[3]}"
    if (moved == lan.host) return null
    return lan.newBuilder().host(moved).build()
}

/** Dotted-quad only: four 0..255 octets, or null. No IPv6, no names. */
private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val out = IntArray(4)
    for (i in 0..3) {
        val part = parts[i]
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val value = part.toInt()
        if (value !in 0..255) return null
        out[i] = value
    }
    return out
}

/**
 * One resolution's answer: the address to use, and whether that moved the route
 * since the previous probe. [changed] is false for a cached answer.
 */
data class RouteChoice(val url: HttpUrl?, val changed: Boolean)

/**
 * Decides which door traffic goes through: the LAN address when the phone can
 * reach it, the public URL otherwise, and again whenever the network moves.
 *
 * A phone is on the LAN, then on mobile data, then on the LAN of a different
 * house; the host's public URL is the same in all three. The LAN is preferred
 * because it is the only route that needs no login and no tunnel, but it is
 * preferred *only while it answers* — so the choice is a probe, not a preference
 * the user has to re-make.
 *
 * Deliberately free of Android APIs: the "is a DSH host here" answer, the
 * phone's own address and the clock all arrive as injected lambdas, so the whole
 * decision is JVM-testable and the same code serves the client and its tests.
 *
 * A probe's "yes" is a *probation*, not a verdict. A door can answer a cheap
 * `GET /` and still be the wrong door for real traffic — a captive portal that
 * answers every address with its own sign-in page, or a different machine that
 * took the host's address on another network — so a door that fails in use is
 * [noteUnreachable] and stops being preferred until [networkChanged] says the
 * network is not the one that produced the failure. That is what keeps the
 * recovery from being worse than the fault: a phone that switched onto a broken
 * LAN must not be switched back onto it on every retry.
 *
 * [peek] is the half that request construction calls, synchronously, from a
 * thread that must not block; [resolve] is the half that probes. They are split
 * because OkHttp builds a request from a URL it is handed, and a resolver that
 * could only answer asynchronously would have nowhere to put the wait.
 */
class EndpointResolver(
    /** True when a DSH host answered at that address (200/401/403/302/303). */
    private val isDshHost: suspend (HttpUrl) -> Boolean,
    /**
     * How long one candidate gets. Short on purpose: the winner is cached and
     * the fallback is always the primary, so a candidate that is slow to
     * *time out* costs nothing but that timeout on a rare refresh.
     */
    private val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    /** How long a resolved winner stands before [refresh] probes again. */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** The phone's own IPv4, for the subnet rewrite; null when unknown. */
    private val localIpv4: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var primary: HttpUrl? = null

    @Volatile
    private var lan: HttpUrl? = null

    @Volatile
    private var winner: HttpUrl? = null

    @Volatile
    private var winnerIsLan = false

    @Volatile
    private var resolved = false

    @Volatile
    private var resolvedAt = 0L

    /**
     * The winner of the last *probe* (not the last cached answer). Kept apart
     * from [winner] so a resolution can say whether *it* moved the route — two
     * racing refreshes must not both conclude that the same move happened.
     */
    @Volatile
    private var lastProbed: HttpUrl? = null

    /** Serialises [refresh], so two triggers cannot race the cache. */
    private val refreshLock = Mutex()

    /**
     * Doors that answered a probe and then failed in use, until the network moves.
     *
     * Concurrent because the failures arrive on OkHttp's threads while a
     * resolution may be reading the set on another; the lock above is held for a
     * whole probe round and must not be the thing a request thread waits on.
     */
    private val suspect: MutableSet<HttpUrl> = ConcurrentHashMap.newKeySet<HttpUrl>()

    /**
     * Re-seed the candidates from a new configuration and drop the cached
     * winner: a changed address is not a reason to trust the old answer, and the
     * old winner may not even be among the new candidates.
     *
     * Suspicion goes with it: it is attached to addresses, and these may be
     * different ones.
     */
    fun update(primary: HttpUrl?, lan: HttpUrl?) {
        this.primary = primary
        this.lan = lan
        suspect.clear()
        winner = null
        winnerIsLan = false
        resolved = false
        resolvedAt = 0L
    }

    /**
     * A door did not survive contact with real traffic: a dropped socket, or an
     * answer that was not a DSH envelope at all.
     *
     * Recorded rather than acted on — the caller invalidates and re-resolves, and
     * this is what makes that re-resolution land somewhere else.
     */
    fun noteUnreachable(url: HttpUrl?) {
        if (url != null) suspect += url
    }

    /**
     * The network itself changed, which is the one thing that excuses a door:
     * the failure above happened on the wire that is now gone.
     */
    fun networkChanged() {
        suspect.clear()
        invalidate()
    }

    /**
     * Mark the cached answer stale, so the next [refresh] probes again.
     *
     * The winner itself is deliberately kept: it is still the best route known
     * until the re-probe finishes, [peek] must never block waiting for one, and a
     * caller watching for a route *change* needs to see what the route was before
     * the re-probe replaced it.
     */
    fun invalidate() {
        resolved = false
        resolvedAt = 0L
    }

    /**
     * The cached winner, or the primary until something is resolved. Never
     * blocks and never probes: this is the call an in-flight request makes.
     */
    fun peek(): HttpUrl? = winner ?: primary

    /** True when [peek] is a LAN door — the configured one or its subnet rewrite. */
    val isLan: Boolean get() = winnerIsLan

    /**
     * Probes the candidates concurrently and caches the preferred one that
     * answers. Within the TTL this is a no-op and the cached winner is returned;
     * [invalidate] is how a caller says "ask again anyway".
     */
    suspend fun refresh(): HttpUrl? = resolve().url

    /**
     * [refresh] plus the one fact a reacting caller needs: whether *this*
     * resolution moved the route.
     *
     * The answer travels with the call rather than living on the instance, so two
     * refreshes racing the same probe each get the truth about their own
     * resolution — the one that probed sees the move, the one served from cache
     * does not, and neither can steal the other's verdict.
     */
    suspend fun resolve(): RouteChoice = refreshLock.withLock {
        if (resolved && clock() - resolvedAt < ttlMs) return RouteChoice(winner, changed = false)
        val ordered = candidates()
        if (ordered.isEmpty()) {
            winner = null
            winnerIsLan = false
            invalidate()
            return RouteChoice(null, changed = false)
        }
        val answers = coroutineScope {
            ordered.map { (url, _) -> async { url to answersAsDshHost(url) } }.awaitAll()
        }
        val answered = answers.filter { it.second }.map { it.first }
        // An answering door that has not just failed in use wins; failing that, an
        // answering door that has — a door that answers is still better than one
        // that does not, so suspicion changes the order and never removes a
        // candidate outright. With no answer at all the primary is the honest
        // fallback, and the reported door is whichever candidate that fallback
        // actually is (a private `baseUrl` is a LAN address even when only the LAN
        // field named it).
        val pick = answered.firstOrNull { it !in suspect } ?: answered.firstOrNull()
        val chosen = pick ?: primary ?: ordered.last().first
        winnerIsLan = ordered.first { it.first == chosen }.second
        val changed = chosen != lastProbed
        lastProbed = chosen
        // Cached like any other winner, so a phone between networks does not
        // re-probe on every request; a network change and a door that failed in use
        // both invalidate, which is when re-probing actually matters.
        winner = chosen
        resolved = true
        resolvedAt = clock()
        RouteChoice(chosen, changed)
    }

    /**
     * Candidates in preference order: the configured LAN, the same host on the
     * phone's subnet when that differs, then the primary. Duplicates are
     * dropped — a private `baseUrl` is both the LAN candidate and the primary,
     * and probing it twice would be two answers to one question.
     */
    private fun candidates(): List<Pair<HttpUrl, Boolean>> {
        val out = ArrayList<Pair<HttpUrl, Boolean>>(3)
        val seen = HashSet<HttpUrl>()
        fun add(url: HttpUrl, isLan: Boolean) {
            if (seen.add(url)) out += url to isLan
        }
        lan?.let { configured ->
            add(configured, true)
            subnetRewritten(configured, localIpv4())?.let { add(it, true) }
        }
        primary?.let { add(it, false) }
        return out
    }

    private suspend fun answersAsDshHost(url: HttpUrl): Boolean = try {
        withTimeoutOrNull(probeTimeoutMs) { isDshHost(url) } == true
    } catch (e: CancellationException) {
        // Our own timeout is swallowed by `withTimeoutOrNull`; anything here is
        // the caller going away, which must not be turned into "no answer".
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS = 1_000L
        const val DEFAULT_TTL_MS = 30_000L
    }
}
