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
 * How a door failed in real traffic, which decides how it comes back.
 *
 * The split is not a severity ordering: it is the difference between a fact about
 * the wire and a fact about the door, and only the first can be hoped away by
 * asking again.
 */
enum class DoorFault {
    /**
     * The connection, not the answer: a timeout, a dropped socket, no route. The
     * door may well be the right host reached over a wire that has since healed,
     * so this is forgiven by time and by a resume.
     */
    Transport,

    /**
     * It answered, and what it answered with was not a DSH envelope: a captive
     * portal serving its own page for every address, or another machine that has
     * taken the host's address. A probe is fooled by both in exactly the same way,
     * so no amount of asking again is evidence — only a different network is.
     */
    WrongAnswer,
}

/**
 * How a door's answer that was not a DSH envelope should be recorded, from the
 * status that carried it.
 *
 * A door that answers **2xx** with something that is not DSH is the shape
 * [DoorFault.WrongAnswer] is for: it answered as though the request were fine, and
 * what came back was another service's page — a captive portal, or a machine that
 * has taken the host's address. No door that speaks DSH answers a gateway call
 * that way, so the answer is a verdict on the door.
 *
 * A door that answers **4xx/5xx** with something that is not DSH is the host's own
 * surface failing the call: the webserver's `404 not found` for an endpoint it does
 * not serve, the auth gate's `502 bad gateway` while the carrier restarts, a 500
 * page. Those say nothing about *which* door this is, and treating them as a
 * verdict is what would set the LAN aside until the network changed every time the
 * host hiccupped — a permanent trap built out of a transient fact. They are
 * recorded as [DoorFault.Transport], whose hold is bounded, lengthens with each
 * repeat, and is forgiven by the clock and by a resume.
 *
 * A top-level function rather than a private branch so the rule itself is
 * addressable from the unit-test source set, which is not a friend of this one.
 */
fun doorFaultForAnswer(status: Int): DoorFault =
    if (status in 200..299) DoorFault.WrongAnswer else DoorFault.Transport

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
 * [noteFailure] and stops being preferred. What brings it back is decided by
 * *how* it failed, which is the one distinction that keeps both halves honest:
 *
 * - A [DoorFault.Transport] failure is plausibly a blip — a stale pooled socket,
 *   a host restarting, a roam inside one SSID that no callback ever reports — so
 *   the door is only held for a while, and the hold lengthens each time a
 *   re-admitted door fails again. [grantRetry], which the foreground resume path
 *   calls, may lift a hold early, a bounded number of times.
 * - A [DoorFault.WrongAnswer] — it answered, and the answer was not DSH — is a
 *   verdict on the door itself. A fresh probe of it is fooled in exactly the same
 *   way, so the clock does not bring that door back and [grantRetry] refuses to.
 *   Two things do: [networkChanged], and [forgiveByUser], which is a person
 *   asking again. The second exists because a verdict nothing the user can do
 *   will revisit is indistinguishable, from the outside, from an app that has
 *   quietly stopped trying.
 *
 * Holding every failure until the network changed, which is what this used to do,
 * meant one transient failure left the LAN door avoided for the life of that
 * network, foreground resumes included. Holding none of them would be worse: a
 * phone that switched onto a broken LAN must not be switched back onto it on
 * every retry. Nothing here is scheduled — every hold is compared against the
 * injected clock when an event (a resolution, a resume, a failure) arrives, so
 * the design stays event-driven.
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
     * One door's record of failing in real traffic, while it is being avoided.
     *
     * Concurrent because the failures arrive on OkHttp's threads while a
     * resolution may be reading the map on another; the lock above is held for a
     * whole probe round and must not be the thing a request thread waits on.
     */
    private data class Fault(
        val kind: DoorFault,
        /** True when the failed door is one of this resolver's LAN candidates. */
        val isLan: Boolean,
        /** Consecutive failures; a quiet spell starts the count over. */
        val strikes: Int,
        /** Resume-granted retries this run of failures has already had. */
        val grants: Int,
        /** Avoided while the clock reads before this. */
        val avoidedUntil: Long,
        /** When the failure happened, which is what the strike count decays from. */
        val failedAt: Long,
    )

    private val faults: ConcurrentHashMap<HttpUrl, Fault> = ConcurrentHashMap()

    /**
     * Re-seed the candidates from a new configuration and drop the cached
     * winner: a changed address is not a reason to trust the old answer, and the
     * old winner may not even be among the new candidates.
     *
     * What the old addresses failed at goes with it: those are records attached to
     * addresses, and these may be different ones, which start with a clean one.
     */
    fun update(primary: HttpUrl?, lan: HttpUrl?) {
        this.primary = primary
        this.lan = lan
        faults.clear()
        winner = null
        winnerIsLan = false
        resolved = false
        resolvedAt = 0L
    }

    /**
     * A door did not survive contact with real traffic, in the way [kind] names.
     *
     * Recorded rather than acted on — the caller invalidates and re-resolves, and
     * this is what makes that re-resolution land somewhere else. The record is a
     * [Fault] rather than a flag because *how* a door comes back is the whole
     * question: a transport failure is avoided for a hold that lengthens with each
     * repeat, and a wrong answer until the network changes.
     *
     * Reporting the same ongoing failure again does not escalate it — a door that
     * is already being avoided has nothing new to say — so one outage cannot
     * inflate its own reluctance into the ceiling on the first second.
     */
    fun noteFailure(url: HttpUrl?, kind: DoorFault) {
        val target = url ?: return
        val now = clock()
        val configuredLan = lan
        val onLan = configuredLan != null &&
            (target == configuredLan || target == subnetRewritten(configuredLan, localIpv4()))
        faults.compute(target) { _, previous ->
            when {
                // A wrong answer is a verdict on the door, not on the wire, and no
                // later spin of the same door changes it.
                kind == DoorFault.WrongAnswer -> Fault(
                    kind = DoorFault.WrongAnswer,
                    isLan = onLan,
                    strikes = 1,
                    grants = 0,
                    avoidedUntil = Long.MAX_VALUE,
                    failedAt = now,
                )

                previous == null -> Fault(
                    kind = kind,
                    isLan = onLan,
                    strikes = 1,
                    grants = 0,
                    avoidedUntil = now + TRANSIENT_HOLD_MS,
                    failedAt = now,
                )

                previous.kind == DoorFault.WrongAnswer -> previous

                // Long enough since the last one to count as a new fault rather
                // than another strike in the same run.
                now - previous.failedAt > STRIKE_DECAY_MS -> Fault(
                    kind = kind,
                    isLan = onLan,
                    strikes = 1,
                    grants = 0,
                    avoidedUntil = now + TRANSIENT_HOLD_MS,
                    failedAt = now,
                )

                // Still in the hold this failure began: the same fault reported
                // again, so it keeps the hold and the strikes it already had.
                now < previous.avoidedUntil -> previous

                else -> {
                    val strikes = previous.strikes + 1
                    Fault(
                        kind = kind,
                        isLan = onLan,
                        strikes = strikes,
                        grants = previous.grants,
                        avoidedUntil = now + transportHold(strikes),
                        failedAt = now,
                    )
                }
            }
        }
    }

    /**
     * A foreground resume is a deliberate "ask again after an absence", so it may
     * lift a transport hold early rather than wait the hold out.
     *
     * Bounded deliberately: at most [RESUME_GRANTS] liftings per run of failures,
     * and never for a wrong answer. Without the bound an app resumed repeatedly
     * would be put back onto a door that fails on every attempt, one failed action
     * at a time; with it, the retries the clock already allows are the only ones
     * left, and each of those is longer than the last. A resume against a hold that
     * has already run out spends nothing — there is nothing to lift, and charging
     * it would let ordinary use use up the budget.
     */
    fun grantRetry() {
        for (url in faults.keys) {
            faults.computeIfPresent(url) { _, fault ->
                val liftable = fault.kind == DoorFault.Transport &&
                    fault.grants < RESUME_GRANTS &&
                    clock() < fault.avoidedUntil
                if (liftable) fault.copy(grants = fault.grants + 1, avoidedUntil = 0L) else fault
            }
        }
    }

    /**
     * The network itself changed, which excuses every door at once: each failure
     * above happened on the wire that is now gone.
     */
    fun networkChanged() {
        faults.clear()
        invalidate()
    }

    /**
     * A person asked again, which excuses every door at once for a different
     * reason: they are holding the phone.
     *
     * This is the only path that lifts a [DoorFault.WrongAnswer] verdict, and it is
     * deliberately not wired to anything automatic. The clock is not evidence about
     * a door that answered with something which was not DSH — a captive portal
     * answers a probe exactly as it answered the first one — and neither is a
     * resolve, a retry or a resume, which the app makes on its own. A person is a
     * different kind of evidence: they may have just walked into the house, swapped
     * the router, or plugged the host back in, and none of that reaches a
     * connectivity callback the app is guaranteed to see.
     *
     * Forgiving everything is the honest reading of one deliberate action. What it
     * must not become is a cadence, and it cannot: nothing calls this except a
     * caller acting on a tap, so a door that answers wrongly again is avoided again
     * by the call that followed, and the cost of the re-ask is one failed attempt
     * per request a human made.
     */
    fun forgiveByUser() {
        faults.clear()
    }

    /**
     * Whether any LAN candidate is configured at all.
     *
     * A different fact from "the LAN door failed": with no candidate there is
     * nothing to probe, nothing to record a fault against, and no probe that could
     * ever change the answer — so a reader who is told only "Public" would be
     * looking at a missing setting and reading it as a broken door.
     */
    val hasLanCandidate: Boolean get() = lan != null

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
     * Why the LAN door is being avoided right now, or null when it is not.
     *
     * This is the half of the decision a reader cannot otherwise see. The app
     * prefers the LAN whenever it answers, so a public route with a LAN address
     * configured has a reason, and "it works now, I do not know why" is exactly
     * what an invisible reason sounds like. A door that is avoided but is still
     * the one traffic is going through is not reported: it is not being avoided.
     *
     * Cheap and synchronous, like [peek]: it reads the failure records the
     * failures already wrote, and probes nothing.
     */
    val lanDoorFault: DoorFault?
        get() {
            val current = winner
            fun avoided(kind: DoorFault): Boolean = faults.entries.any { (url, fault) ->
                fault.isLan && url != current && fault.kind == kind && clock() < fault.avoidedUntil
            }
            // Two LAN candidates can carry different records — the configured
            // address and its subnet rewrite — so the answer is ordered rather
            // than whichever the map happens to hand back first. A wrong answer is
            // the one whose wording matters; it is the one that does not come back
            // until the network changes.
            return when {
                avoided(DoorFault.WrongAnswer) -> DoorFault.WrongAnswer
                avoided(DoorFault.Transport) -> DoorFault.Transport
                else -> null
            }
        }

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
        // An answering door that is not being avoided wins; failing that, an
        // answering door that is — a door that answers is still better than one
        // that does not, so a hold changes the order and never removes a candidate
        // outright. With no answer at all the primary is the honest fallback, and
        // the reported door is whichever candidate that fallback actually is (a
        // private `baseUrl` is a LAN address even when only the LAN field named
        // it).
        val pick = answered.firstOrNull { !isAvoided(it) } ?: answered.firstOrNull()
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

    /**
     * Whether a door is being passed over because of a failure it has not yet
     * lived down. There is no timer behind this: the clock is consulted here, when
     * a resolution is happening anyway, and a hold that has run out simply stops
     * counting.
     */
    private fun isAvoided(url: HttpUrl): Boolean {
        val fault = faults[url] ?: return false
        return clock() < fault.avoidedUntil
    }

    /**
     * How long a transport failure keeps a door out of the running.
     *
     * The first hold is short but not zero: the failure itself triggers a
     * re-resolution, and that one has to land on the other door or the retry
     * inside the failing call goes straight back to the door that just broke. Each
     * later failure in the same run holds the door longer, so an app whose user
     * keeps coming back meets a door that is still being avoided instead of being
     * put back on it — and the ceiling means a door that genuinely recovers is
     * readmitted, with no timer and no network change, within half an hour at the
     * very worst.
     */
    private fun transportHold(strikes: Int): Long = when {
        strikes <= 1 -> TRANSIENT_HOLD_MS
        strikes == 2 -> 60_000L
        strikes == 3 -> 300_000L
        else -> HOLD_CEILING_MS
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

        /**
         * The hold the first transport failure earns: long enough to keep the
         * re-resolution that failure triggers off the door, short enough that a
         * later probe — a resume's, or the next one the TTL allows — takes it.
         */
        private const val TRANSIENT_HOLD_MS = 5_000L

        /** The longest a run of transport failures can hold a door. */
        private const val HOLD_CEILING_MS = 1_800_000L

        /**
         * How quiet a door has to be for its next failure to count as a fresh one
         * rather than another strike in the same run. Without this, a door that
         * blipped once a day would climb to the ceiling and stay there.
         */
        private const val STRIKE_DECAY_MS = 600_000L

        /**
         * How many holds a foreground resume may lift per run of failures —
         * enough for "ask again after an absence" to work, few enough that a
         * resume loop cannot keep putting the app back on a door that keeps
         * failing it.
         */
        private const val RESUME_GRANTS = 2
    }
}
