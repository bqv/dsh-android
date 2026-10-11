package uk.xa0.dsh.model

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which held approvals a fresh forwarded-event generation still speaks for.
 *
 * A *generation* is one opening of the host's `$events` logical stream, named by the
 * `clientId` in its `ready` frame. The host re-announces **every waterfall it is
 * still holding** to a new generation, at the moment the stream opens:
 * `openRemoteEvents` iterates `pendingRemoteEvents` and delivers each one to the new
 * client before it yields anything else (`dsh-api-gateway/lib/index.js`, the
 * `openRemoteEvents` generator). A waterfall the host has already settled is not in
 * that map at all — `finishRemoteEvent` deletes it — so it is never re-announced.
 *
 * That asymmetry is the reconciliation, and it is the host's own word rather than an
 * inference: a card the host does not re-announce on a fresh generation has been
 * settled on the host, whether by another answerer, by the host's own timeout, or by
 * this client's own answer. It is the one signal that survives a socket death, a
 * route move and a process freeze, because a new generation is what all three
 * produce.
 *
 * ## The one timing assumption
 *
 * The wire carries no marker for the end of the re-announcement batch, so the caller
 * gives it a bounded window and asks [settled] once the window has run out. A
 * re-announcement that arrives *after* the window cleared a card is self-healing:
 * the very frame that is late raises the card again, as a fresh waterfall on the live
 * generation.
 *
 * ## Threading
 *
 * The three callers are on different threads — the socket's reader reports a
 * generation, the handler's own coroutines announce and settle, and `clear` runs on
 * whichever thread tears the centre down — so the pairing is held in a
 * [ConcurrentHashMap] and the counter is an [AtomicLong]. The race that remains is
 * benign: an announcement that lands while a generation is turning over is tagged
 * with whichever side of the increment it sees, and both readings leave a waterfall
 * the host is still holding un-settled.
 *
 * ## What this is not
 *
 * It holds no cards, sends nothing and knows nothing about Android.
 */
class ApprovalRoster {

    private val generation = AtomicLong(0)
    private val announced = ConcurrentHashMap<String, Long>()

    /**
     * A fresh `$events` generation has opened. Returns the number that
     * [settled]'s callers hold on to, so a decision made in an older window cannot
     * take a card the newer generation is still speaking for.
     */
    fun onGeneration(): Long = generation.incrementAndGet()

    /**
     * The host announced this waterfall in the current generation: it is still
     * pending, and its card is not to be reconciled away.
     */
    fun onAnnounced(eventId: String) {
        announced[eventId] = generation.get()
    }

    /** This waterfall is gone — answered, refused, withdrawn or reconciled away. */
    fun onSettled(eventId: String) {
        announced.remove(eventId)
    }

    /**
     * Whether the host settled [eventId] rather than naming it in [atGeneration].
     *
     * False once a later generation has begun: that generation re-announces what it
     * holds and decides in its own window, so an old window has nothing to say about
     * a card the newer one may be speaking for.
     */
    fun settled(eventId: String, atGeneration: Long): Boolean {
        val current = generation.get()
        return atGeneration == current && announced[eventId] != atGeneration
    }

    /**
     * Forgets every pairing. Called when the cards themselves are dropped — a
     * sign-out, or a mux replaced for a reason other than a reconnect — so the map
     * cannot outlive the cards it describes.
     */
    fun clear() {
        announced.clear()
    }
}
