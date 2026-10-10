package uk.xa0.dsh.widget

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
 * see the truth. It is the base of the ticking age the widget draws, so a reading
 * that stops being renewed goes on to say how long ago it was true — which is the
 * only honest thing a number can do once nothing is maintaining it. See
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
 * Everything the homescreen widget can say, as one snapshot written at one instant.
 *
 * A handful of facts and no more, deliberately: what is waiting on the user
 * ([needsYou], ranked by the website's own cross-domain precedence), how much is
 * working ([working], of which [subagents]), and — for the tap — which session to
 * open ([sessionId]). What is *not* here is as much the design as what is: no
 * session list, no model chip, no jobs and no balance, each for a reason recorded in
 * `docs/WIDGET.md`.
 */
data class WidgetSnapshot(
    val needsYou: WidgetReading = WidgetReading.NEVER,
    val working: WidgetReading = WidgetReading.NEVER,
    val subagents: WidgetReading = WidgetReading.NEVER,
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
     * footer says in words (`WidgetCopy.STAMP_OLDEST`): the worst line was confirmed that
     * long ago, so everything above it is at least that fresh. Taking the newest instead
     * would put a fresh-looking age under a figure that may be hours old — which is
     * exactly the shape of lie a stale model chip is.
     *
     * One instant rather than one per line because `RemoteViews` gives a widget a single
     * Chronometer and no way to stamp two lines differently. The filter is
     * [WidgetReading.everSeen] rather than `known` for the same reason that field exists:
     * a withdrawn reading is still *drawn*, as "Needs you: unknown", so the instant it was
     * withdrawn is genuinely the age of a line on the card and must be allowed to bound
     * the others.
     *
     * `null` means nothing has ever been established on this phone, and the footer then
     * carries the way in rather than an age.
     */
    val confirmedAt: Long?
        get() = listOf(needsYou, working, subagents)
            .filter { it.everSeen }
            .minOfOrNull { it.asOf }

    /** Whether two snapshots say the same thing about the world; see [WidgetReading.sameValueAs]. */
    fun sameValuesAs(other: WidgetSnapshot?): Boolean =
        other != null &&
            needsYou.sameValueAs(other.needsYou) &&
            working.sameValueAs(other.working) &&
            subagents.sameValueAs(other.subagents) &&
            kind == other.kind &&
            sessionId == other.sessionId

    fun encode(): String = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_NEEDS_YOU, needsYou.toJson())
        .put(KEY_WORKING, working.toJson())
        .put(KEY_SUBAGENTS, subagents.toJson())
        .put(KEY_KIND, kind ?: JSONObject.NULL)
        .put(KEY_SESSION, sessionId ?: JSONObject.NULL)
        .toString()

    companion object {
        /**
         * Bumped whenever the shape changes. A file this build cannot read is treated
         * as no file at all, which is the safe direction: an unrecognised snapshot is
         * one this build cannot honestly draw.
         */
        const val VERSION = 1

        private const val KEY_VERSION = "v"
        private const val KEY_NEEDS_YOU = "needsYou"
        private const val KEY_WORKING = "working"
        private const val KEY_SUBAGENTS = "subagents"
        private const val KEY_KIND = "kind"
        private const val KEY_SESSION = "sessionId"

        /** Nothing has ever been established. */
        val NEVER = WidgetSnapshot()

        /**
         * Reads one snapshot, or null for anything unexpected — a missing file, a
         * half-written one, a version this build does not know. Never throws: a widget
         * is drawn by a receiver that must not crash on a corrupt byte.
         */
        fun decode(text: String?): WidgetSnapshot? {
            if (text.isNullOrBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (json.optInt(KEY_VERSION, 0) != VERSION) return null
            val needsYou = WidgetReading.fromJson(json.optJSONObject(KEY_NEEDS_YOU)) ?: return null
            val working = WidgetReading.fromJson(json.optJSONObject(KEY_WORKING)) ?: return null
            val subagents = WidgetReading.fromJson(json.optJSONObject(KEY_SUBAGENTS)) ?: return null
            return WidgetSnapshot(
                needsYou = needsYou,
                working = working,
                subagents = subagents,
                // A JSON null reads back through `optString` as the text "null".
                kind = json.optString(KEY_KIND).takeIf { it.isNotEmpty() && it != "null" },
                sessionId = json.optString(KEY_SESSION).takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }
}
