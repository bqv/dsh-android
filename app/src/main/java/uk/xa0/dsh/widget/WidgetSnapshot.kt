package uk.xa0.dsh.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing the app last established about the world, and the instant it established it.
 *
 * ## Why `known` is a field and not an absence
 *
 * Every fact in this app arrives over a live socket, and a widget is read by a
 * launcher process that cannot ask for anything. So a reading is either something
 * the app **confirmed** — "at [asOf] I was connected and this is what I saw" — or
 * it is nothing at all. Those are two different statements and this type keeps
 * them apart, because collapsing them is the failure this whole feature exists to
 * avoid: `known = false, value = 0` is *"nobody has looked"*, while
 * `known = true, value = 0` is *"the app looked and nothing is waiting"*. The
 * Router tab enforces the same rule for "not asked" versus "reported as empty";
 * a widget that paints them the same way is lying about one of them.
 *
 * ## Why `asOf` is the app's own clock and not the file's
 *
 * [asOf] advances only when the reading's owner re-publishes it while it can still
 * see the truth. It is the instant the footer prints, so a reading that stops being
 * renewed goes on to say *when* it was last true — which is the only honest thing a
 * stamp can do once nothing is maintaining it, and unlike an age it needs no
 * maintenance to stay honest. See
 * [WidgetPublisher] for who renews what, and `docs/WIDGET.md` for the whole trade.
 */
data class WidgetReading(
    val known: Boolean = false,
    val value: Int = 0,
    val asOf: Long = 0L,
) {

    /**
     * Whether the value is worth drawing: `known` says we can assert it, `asOf > 0`
     * says we once saw something even if we can assert nothing now. Both matter to
     * the footer, which is the only place a withdrawn reading is still worth a word.
     */
    val everSeen: Boolean get() = known || asOf > 0L

    /**
     * Whether two readings say the same thing **about the world**, ignoring when they
     * were taken. The publisher writes the file only when this changes or when the
     * re-stamp is due, which is what keeps a widget from costing a file write per
     * roster frame.
     */
    fun sameValueAs(other: WidgetReading?): Boolean =
        other != null && other.known == known && other.value == value

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_KNOWN, known)
        .put(KEY_VALUE, value)
        .put(KEY_AS_OF, asOf)

    companion object {
        private const val KEY_KNOWN = "known"
        private const val KEY_VALUE = "value"
        private const val KEY_AS_OF = "asOf"

        /** Nothing has ever been established. */
        val NEVER = WidgetReading()

        /** Null for anything that is not exactly the object [toJson] writes. */
        fun fromJson(json: JSONObject?): WidgetReading? {
            json ?: return null
            if (!json.has(KEY_KNOWN) || !json.has(KEY_VALUE) || !json.has(KEY_AS_OF)) return null
            return WidgetReading(
                known = json.optBoolean(KEY_KNOWN),
                value = json.optInt(KEY_VALUE),
                asOf = json.optLong(KEY_AS_OF),
            )
        }
    }
}

/**
 * One session the card may name: an id for the tap and the title to draw.
 *
 * A row whose session cannot be named is a row the reader cannot act on, so the id is
 * not optional here — a document whose row has none is refused rather than drawn as a
 * title with nowhere to go.
 *
 * The title is the roster's own (`SessionItem.title`, which already carries a
 * subagent's label when the catalog has landed). It is *not* trimmed here: this is the
 * persisted shape, and trimming for a tile is a copy decision ([WidgetCopy.tileTitle]).
 */
data class WidgetSession(
    val id: String,
    val title: String,
) {

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_TITLE, title)

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"

        /** Null for anything that is not exactly the object [toJson] writes. */
        fun fromJson(json: JSONObject?): WidgetSession? {
            json ?: return null
            val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
            return WidgetSession(id = id, title = json.optString(KEY_TITLE))
        }
    }
}

/**
 * Everything the homescreen widget can say, as one snapshot written at one instant.
 *
 * A handful of facts and no more, deliberately: what is waiting on the user
 * ([needsYou], ranked by the website's own cross-domain precedence), what is working
 * ([working], of which [subagents], and *which* of it by name in [running]), whether the
 * app is still hearing the host at all ([active]), and — for the tap — which session to
 * open ([sessionId]). What is *not* here is as much the design as what is: no model
 * chip, no jobs and no balance, each for a reason recorded in `docs/WIDGET.md`.
 *
 * ## Why the names are here and the jobs are not
 *
 * `job/list` is per-session, so a job figure would present one session's jobs as the
 * world's — a count that is right for one session and wrong for every other. The
 * session roster is app-wide: `session/list` is a whole-world pull, this app already
 * fetches it, and each row already carries `running`. A list of running sessions is
 * therefore a real fact about the whole system, which is exactly what a count of jobs
 * could never be.
 *
 * The same bar was applied to "is anything happening": [active] is built on the one mux
 * socket every stream shares, not on the followed session's turn or the open session's
 * job roster, both of which speak for one session and would be wrong for every other.
 */
data class WidgetSnapshot(
    val needsYou: WidgetReading = WidgetReading.NEVER,
    val working: WidgetReading = WidgetReading.NEVER,
    val subagents: WidgetReading = WidgetReading.NEVER,
    /**
     * Whether the host is *talking to this app*: 1 while a frame has arrived on the
     * mux within [WidgetPublisher.ACTIVE_WINDOW_MS], 0 once that window has lapsed,
     * `known = false` when the socket is not open and the app therefore cannot tell.
     *
     * This is the reading that separates "still listed" from "actually working". It is
     * deliberately **not** a second count of sessions: [working] is the host's own
     * whole-world liveness answer and is already on the card. What [working] cannot say
     * is whether the app is still hearing the host at all, and that is what this is —
     * a fact about the evidence rather than about any one session. Every candidate that
     * could speak only for one session (`session/follow` for the open session, the
     * per-session `job/list` roster) was rejected for exactly that reason; see
     * `docs/WIDGET.md`.
     *
     * The copy layer draws the stronger word for a running list only while this
     * reading is confirmed ([WidgetCopy.WORKING]); when it is 0 or unknown the card
     * falls back to the roster's own word ([WidgetCopy.RUNNING]), which claims no
     * freshness. An unknown activity reading therefore never renders as "nothing is
     * happening": it withholds a word rather than asserting an idle world.
     */
    val active: WidgetReading = WidgetReading.NEVER,
    /**
     * The running sessions the card may name, in the app's own roster order, at most
     * [MAX_NAMED_SESSIONS] of them. See [WidgetPublisher.namedSessions] for which rows
     * survive and what happens to the rest: the count in [working] is the total, so a
     * card can always say how many it is not naming.
     */
    val running: List<WidgetSession> = emptyList(),
    /** The `PendingKind` name behind [needsYou], when more than nothing is waiting. */
    val kind: String? = null,
    /** The session that most needs the user, for the tap target. */
    val sessionId: String? = null,
) {

    /**
     * The instant the widget's footer is based on: the **oldest** instant at which any
     * reading that has a line on the card was confirmed.
     *
     * It is a bound over the whole card, not a stamp on one line, and that is what the
     * footer says in words (`WidgetCopy.STAMP_OLDEST`): the worst line was confirmed then,
     * so everything above it is at least that fresh. Taking the newest instead would put a
     * fresh-looking instant under a figure that may be hours old — which is exactly the
     * shape of lie a stale model chip is.
     *
     * One instant rather than one per line because the footer is a single line — the
     * layout has one stamp and no way to write two differently — so the card takes the
     * worst of them and says what it is. The filter is
     * [WidgetReading.everSeen] rather than `known` for the same reason that field exists:
     * a withdrawn reading is still *drawn*, as "Needs you: unknown", so the instant it was
     * withdrawn is genuinely a fact about a line on the card and must be allowed to bound
     * the others.
     *
     * `null` means nothing has ever been established on this phone, and the footer then
     * carries the way in rather than an instant.
     *
     * [running] adds no instant of its own: the named rows are the detail *of* [working]
     * — the same roster pull, the same fold, the same instant — so the stamp that bounds
     * the running line already bounds every name under it. A separate stamp for the list
     * would only be a second thing to keep honest.
     *
     * [active] does bound the card, because it is drawn: it decides whether the headline
     * says the sessions are working or merely running. A word whose reading was left out
     * of this bound would be exactly the claim the footer could no longer cover.
     */
    val confirmedAt: Long?
        get() = listOf(needsYou, working, subagents, active)
            .filter { it.everSeen }
            .minOfOrNull { it.asOf }

    /** Whether two snapshots say the same thing about the world; see [WidgetReading.sameValueAs]. */
    fun sameValuesAs(other: WidgetSnapshot?): Boolean =
        other != null &&
            needsYou.sameValueAs(other.needsYou) &&
            working.sameValueAs(other.working) &&
            subagents.sameValueAs(other.subagents) &&
            // An activity change is a change to what the card draws — it decides which
            // word the headline uses — so it is a moved value like any other, and the
            // publisher's gate lets it through at once.
            active.sameValueAs(other.active) &&
            // A title is a fact the card draws, so a rename is a change worth a write —
            // and the order is part of the statement, because the reader reads top-down.
            running == other.running &&
            kind == other.kind &&
            sessionId == other.sessionId

    fun encode(): String = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_NEEDS_YOU, needsYou.toJson())
        .put(KEY_WORKING, working.toJson())
        .put(KEY_SUBAGENTS, subagents.toJson())
        .put(KEY_ACTIVE, active.toJson())
        .put(KEY_RUNNING, JSONArray().apply { running.forEach { put(it.toJson()) } })
        .put(KEY_KIND, kind ?: JSONObject.NULL)
        .put(KEY_SESSION, sessionId ?: JSONObject.NULL)
        .toString()

    companion object {
        /**
         * The version of the document's *shape*, and it is deliberately still 1.
         *
         * `running` was added without a bump because a bump would blank **every widget
         * already on a homescreen** until the app next ran and rewrote the file, and this
         * change does not need that. The key is optional in both directions: this build
         * reads a document without it and draws the honest thing it can (the count, with
         * the names not known), and the previous build — whose decoder never inspects a
         * key it does not know — reads a document with it and draws exactly what it drew
         * before. The version exists for documents this build **cannot read**; an absent
         * optional list is not one of those.
         *
         * `active` is optional on exactly the same terms, and its absent state is a real
         * one rather than a placeholder: a document with no activity reading is one whose
         * author could not say whether the host was talking, so it reads as
         * [WidgetReading.NEVER] and the copy layer withholds the word that would have
         * claimed otherwise. A *present* key this build cannot parse is a different thing
         * — a document contradicting itself — and is refused by [decode].
         */
        const val VERSION = 1

        /**
         * How many running sessions the document may carry. Past this the card says
         * "+N more" rather than dropping a row in silence; see [WidgetCopy.of].
         *
         * Three, from a measurement rather than a guess: a placed 3x2 widget on the test
         * handset's Kvaesitso grid occupies 1040x510 px at an override density of 408
         * (2.55x) — 408x200 dp — and the whole card, headline, three names, the "+N more"
         * line and the footer, comes to roughly 130 dp by arithmetic over Roboto's font
         * metrics.
         */
        const val MAX_NAMED_SESSIONS = 3

        private const val KEY_VERSION = "v"
        private const val KEY_NEEDS_YOU = "needsYou"
        private const val KEY_WORKING = "working"
        private const val KEY_SUBAGENTS = "subagents"
        private const val KEY_ACTIVE = "active"
        private const val KEY_RUNNING = "running"
        private const val KEY_KIND = "kind"
        private const val KEY_SESSION = "sessionId"

        /** Nothing has ever been established. */
        val NEVER = WidgetSnapshot()

        /**
         * Reads one snapshot, or null for anything unexpected — a missing file, a
         * half-written one, a version this build does not know. Never throws: a widget
         * is drawn by a receiver that must not crash on a corrupt byte.
         *
         * An absent or unreadable `running` list is not an error: it reads as "no names
         * were written down", which the copy layer draws as the count alone. That is what
         * makes a document from the build before this change readable rather than blank.
         * The list is read up to [MAX_NAMED_SESSIONS]: a longer one can only come from a
         * file this app did not write, and the count in `working` still tells the reader
         * how many sessions exist.
         *
         * An absent `active` key is likewise not an error — it is "this document does not
         * say" — but a present one that is not a reading is refused, because that is a
         * document contradicting itself rather than one from an older build.
         */
        fun decode(text: String?): WidgetSnapshot? {
            if (text.isNullOrBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (json.optInt(KEY_VERSION, 0) != VERSION) return null
            val needsYou = WidgetReading.fromJson(json.optJSONObject(KEY_NEEDS_YOU)) ?: return null
            val working = WidgetReading.fromJson(json.optJSONObject(KEY_WORKING)) ?: return null
            val subagents = WidgetReading.fromJson(json.optJSONObject(KEY_SUBAGENTS)) ?: return null
            // Absent (an older document) and JSON null both mean "nobody said", which the
            // copy layer draws by withholding the word. Anything else present under this
            // key has to be a reading: a value that is not one is a document contradicting
            // itself, and is refused with the rest of the unreadable shapes.
            val active = if (!json.has(KEY_ACTIVE) || json.isNull(KEY_ACTIVE)) {
                WidgetReading.NEVER
            } else {
                WidgetReading.fromJson(json.optJSONObject(KEY_ACTIVE)) ?: return null
            }
            val running = json.optJSONArray(KEY_RUNNING)?.let { array ->
                (0 until minOf(array.length(), MAX_NAMED_SESSIONS))
                    .mapNotNull { WidgetSession.fromJson(array.optJSONObject(it)) }
            }.orEmpty()
            return WidgetSnapshot(
                needsYou = needsYou,
                working = working,
                subagents = subagents,
                active = active,
                running = running,
                // A JSON null reads back through `optString` as the text "null".
                kind = json.optString(KEY_KIND).takeIf { it.isNotEmpty() && it != "null" },
                sessionId = json.optString(KEY_SESSION).takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }
}
