package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * A session's whole subagent family — itself, its subagents, and theirs, however deep —
 * folded into one token and cost reading.
 *
 * ## Where the numbers come from
 *
 * `tokenUsage` is a **per-session journal fold** (`dsh-token-meter`'s
 * `usage-projection.ts`): it totals the `assistant/message` and `assistant/attempt`
 * settlements in *that session's own log*. A subagent is a first-class session on the
 * host, so its usage is reported in its own right, and a session's projections ride its
 * `session/list` row. Measured on the running host: of 2,514 list rows, **1,411 carry
 * the full projection block** (`tokenUsage`, `sessionStats`, `subagent`,
 * `subagentTiming`, `subagentCatalog`, `modelSelection`, `sessionListMetadata`) and
 * 1,103 carry `title` alone — the legacy `session.v3` records of HANDOFF note 17. Among
 * the **1,287 subagent rows, 618 carry `tokenUsage` and 669 do not**. Nothing here is
 * derived: each member's figure is the host's own count, and the family total is the sum
 * of those.
 *
 * ## Why membership comes from the roster
 *
 * The host's lineage is the roster's `parentSessionId` chain, which is what the drawer's
 * descendant count and the header's lineage chip already walk. [familyUsage] therefore
 * reuses [subagentTreeIds] — the same transitive, cycle-safe walk — rather than
 * inventing a second one, so no two surfaces can disagree about who is in the family.
 *
 * ## What the sum is, and the two ways it is not the family's own work
 *
 * The figure is the sum of what the host reported, and it deviates from the family's own
 * token use in **both** directions, which is why no label here claims to be a bound.
 *
 * **It omits a member whose usage was never reported.** A member whose row carried no
 * `tokenUsage` contributes nothing. That is not a rare case: 669 of the 1,287 live
 * subagent rows are in it. The `Subagents` row shows `counted of members` and the panel
 * says how many were not reported, so an omission is visible where it happens.
 *
 * **It counts an inherited prefix twice.** Established from the host, not assumed:
 *
 *  * A **spawned** child's `tokenUsage` is its own. The fold handles `assistant/message`
 *    and `assistant/attempt` and nothing else, and a child's settlements are written to
 *    the child's log. Measured on this machine: `session-c9b44b3a`'s log held 5,710 usage
 *    samples, all on its own assistant messages and none in any `tool/result`, and its
 *    only `subagent/<method>` events were `subagent/catalog` (identity).
 *  * A **forked** child's `tokenUsage` includes its parent's history. The `fork` provider
 *    seeds the child's log with a copy of the parent's prefix (`dsh-session`'s
 *    `buildForkSeed`), and `tokenUsage` has **no reset at that cut** — its `init` ignores
 *    the `inheritedEventCount` every unit is handed, unlike `subagentCatalog` (which
 *    guards on it) and `subagentTiming` (which resets on the child's own descriptor,
 *    precisely because "a fork seed may contain an ancestor descriptor and completed
 *    turns"). Measured twice over: by folding fork subagent `630d0054`'s own log, its
 *    inherited prefix is 40,847,940 tokens over 126 settled steps and its own work
 *    7,373,046 over 13; and the host's live `session/list` row for it reports
 *    808,431 + 280,139 + 47,132,416 = **48,220,986** tokens, exactly that whole-log fold.
 *
 * **No `session/list` field identifies such a child, and two candidate detectors were
 * built, tested against all 618 live subagent rows, and rejected.**
 *
 *  * `subagent.seq > 0` — the descriptor's position in the child's log. All three seeded
 *    children put theirs after an inherited cut (`1047`, `3122`, `3214`). But **116 of the
 *    615 unseeded ones also do** (two at 5, 114 at 6): they are `one-shot` children whose
 *    delegation setup — `sandbox/mode`, `approval/policy`, `permission/preset`,
 *    `agent/inbox/spliced`, `turn/start` — lands before the descriptor. The rule would
 *    have discarded 19% of all subagents' usage.
 *  * `sessionStats.llmMs > subagentTiming.settledMs` — whole-log model time against
 *    post-descriptor turn time, which separates the three forks by 12x to 44x. It also
 *    fires for **4 of 615 unseeded children**, including children still inside their first
 *    turn, where `settledMs` is 0 and any model time exceeds it. It would have dropped
 *    exactly the running children a live panel is most likely to be showing.
 *
 * `SessionSummary` carries no `isSeeded`, the `subagent` identity projection publishes
 * `{mode, label, seq}` and drops the descriptor's `provider` (`identityValueSchema` is
 * `.strict()` on those keys), and there is no cheap header read (`session` exposes no
 * `get`/`header`, and `session/projections` answers empty where `session/list` does not —
 * HANDOFF note 22). So this is disclosed rather than decided: [METHOD_NOTE] is shown with
 * every family reading, because the double count is a property of the arithmetic and not
 * a fault in one reading.
 *
 * The size of it, as a live worked example: `session-3832a7b1` reports 486,055,001 tokens
 * and its seven subagents report 392,350,287 between its three forked children
 * (172,634,765, 171,494,536, 48,220,986) and 9,585,293 between its four spawned ones.
 * Summed, the family reads 887,990,581, against 495,640,294 if every member's own work
 * were separable — the inherited prefixes are 79% of the naive total.
 *
 * ## Cost
 *
 * A member is priced at the route its *last turn actually ran*
 * (`modelSelection.lastUsed`), which is the host's only statement about the tokens it
 * counted, and which every live child row carries. Where the app has no published price
 * for a member, **no family figure is shown at all** — the same absolute rule the
 * session's own cost row already follows (`tokenRatesFor` answers null, and null draws
 * nothing) — and the panel says how many of how many. A subtotal that quietly drops a
 * model is money-shaped, so there is none. The inherited-prefix double count applies to
 * the cost exactly as it does to the tokens, and [METHOD_NOTE] is stated once for both.
 */

/**
 * What a family figure is a sum of, always stated with it.
 *
 * Not a warning about a broken reading but the arithmetic's own terms: a reader comparing
 * a family total against a bill has to know that a forked member's number can contain
 * another member's history, because that is the one thing no row on the wire says. Short
 * on purpose: it sits under two figures in a panel that has a balance link and a
 * price-list date to fit as well.
 */
const val METHOD_NOTE: String =
    "Summed from each session's reported count, so a forked subagent's inherited history is counted twice."

/**
 * One family's token and cost reading.
 *
 * Every count is carried separately rather than collapsed into one "complete" flag, so the
 * panel can name what is missing instead of only that something is. `members` against
 * `counted` is the coverage disclosure and needs no note; the notes carry what the numbers
 * cannot say about themselves.
 */
data class FamilyUsage(
    /** Subagents the roster places under the open session, however deep. */
    val members: Int,
    /** Those whose reported figure is additive and is inside [tokens]. */
    val counted: Int,
    /** Total tokens over the open session and the [counted] subagents. */
    val tokens: Long,
    /** Sessions [tokens] is summed over: the open session when it reported, plus [counted]. */
    val inFigure: Int,
    /** Subagents the host reported no usage for, so [tokens] omits them. */
    val unreported: Int,
    /** Cost over the [inFigure] sessions, or null when any of them has no known price. */
    val cost: CostRange?,
    /** Sessions in the figure this app has no published price for. */
    val unpriced: Int,
) {
    /**
     * The tokens row's label.
     *
     * Deliberately says what the figure is rather than how big the family is. "Whole
     * family" would claim the family's own token use, which this is not whenever a member
     * was forked — and `at least` would claim a floor, which a double count breaks in the
     * other direction.
     */
    val label: String get() = "Family, as reported"

    /** The subagents row's value: how many of the family's members are inside [tokens]. */
    val memberCountText: String
        get() = if (counted == members) members.toString() else "$counted of $members"

    /**
     * What the reader has to know for the figures above not to mislead, in the order they
     * qualify the total, then the cost, then its provenance.
     *
     * The omission and cost notes are conditional, because a note that fires on a healthy
     * reading is one nobody reads when it matters. [METHOD_NOTE] is not: it describes what
     * the sum is, and there is no reading at which that stops being true.
     */
    val notes: List<String>
        get() {
            val out = ArrayList<String>(3)
            if (unreported > 0) {
                out.add(
                    "The host has not reported usage for ${subagentCount(unreported)}, " +
                        "so their tokens are not in this figure.",
                )
            }
            if (cost == null && unpriced > 0) {
                out.add(
                    "Family cost is not shown: no published price for $unpriced of " +
                        "the $inFigure sessions counted.",
                )
            }
            out.add(METHOD_NOTE)
            return out
        }
}

/** `1 subagent` / `3 subagents`, because the notes are prose and `1 subagents` is not. */
private fun subagentCount(count: Int): String =
    if (count == 1) "1 subagent" else "$count subagents"

/** A member's figure counts only when the row actually carried `tokenUsage`. */
private fun reportedUsage(stats: SessionStats?): SessionStats? = stats?.takeIf { it.hasUsage }

/**
 * Folds the open session and every subagent under it into one reading.
 *
 * [sessions] is the flat roster, which is where lineage, usage and route all come from.
 * [own] is the open session's live `sessionStats` figure — fresher than its own roster row,
 * because it arrives on the control stream as well as in a pull — and is preferred to that
 * row when it carries usage, so the family figure and the session's own rows above it can
 * never be two different readings of the same session. [ownRates] is the price of the route
 * the panel's own cost row already uses, so the open session's share of the family cost is
 * priced identically in both rows.
 *
 * [catalog] is the open session's `subagentCatalog` projection, which may name a direct
 * child the roster pull has not delivered yet. The roster is a whole-world read and a child
 * can be spawned mid-turn, so a child the catalog knows and the roster does not is counted
 * — as a member with no reported usage, which is exactly what it is. The app's descendant
 * count floors on the same signal, and the two must not disagree.
 *
 * Costs are summed as two doubles rather than as integers, so a family of cheap members
 * cannot lose a fraction to per-member rounding before it is printed. Each member keeps its
 * own rates: pricing a child's tokens at its parent's route would be a number from another
 * model's price list.
 */
fun familyUsage(
    sessions: List<SessionItem>,
    rootId: String,
    own: SessionStats?,
    ownRates: TokenRates?,
    catalog: SubagentCatalog? = null,
): FamilyUsage {
    // Indexed by id, first row wins: a roster that names one session twice must not be able
    // to pay for it twice, and every count below is a count of *sessions*.
    val byId = LinkedHashMap<String, SessionItem>(sessions.size)
    for (session in sessions) byId.putIfAbsent(session.id, session)

    // The live figure first, the roster's own row only as a fallback. Both are the same
    // projection at different cuts; taking one of them cannot double-count.
    val ownStats = reportedUsage(own) ?: reportedUsage(byId[rootId]?.usage)

    var tokens = ownStats?.totalTokens ?: 0L
    var offPeak = 0.0
    var peak = 0.0
    var inFigure = 0
    var unpriced = 0

    if (ownStats != null) {
        inFigure += 1
        if (ownRates == null) {
            unpriced += 1
        } else {
            val cost = costRange(
                cacheHitTokens = ownStats.cacheReadTokens,
                // A cache write is billed as a miss, as it is in the panel's own row.
                cacheMissTokens = ownStats.uncachedInputTokens + ownStats.cacheWriteTokens,
                outputTokens = ownStats.outputTokens,
                rates = ownRates,
            )
            offPeak += cost.offPeak
            peak += cost.peak
        }
    }

    var members = 0
    var counted = 0
    var unreported = 0

    val ids = subagentTreeIds(sessions, rootId)
    for (id in ids) {
        if (id == rootId) continue
        members += 1
        val session = byId[id]
        val stats = session?.let { reportedUsage(it.usage) }
        // A member whose figure the host has not given is a member, and the one thing that
        // must not happen to it is being spent as a zero.
        if (session == null || stats == null) {
            unreported += 1
            continue
        }
        counted += 1
        tokens += stats.totalTokens
        inFigure += 1
        val rates = tokenRatesFor(session.lastUsedModel?.provider, session.lastUsedModel?.model)
        if (rates == null) {
            unpriced += 1
        } else {
            val cost = costRange(
                cacheHitTokens = stats.cacheReadTokens,
                cacheMissTokens = stats.uncachedInputTokens + stats.cacheWriteTokens,
                outputTokens = stats.outputTokens,
                rates = rates,
            )
            offPeak += cost.offPeak
            peak += cost.peak
        }
    }

    // A child the catalog names and the roster has not delivered. Only the open session's
    // own catalog is consulted: a catalog is a parent's direct children, and this client
    // holds one only for the sessions something is watching.
    for (entry in catalog?.children.orEmpty()) {
        if (entry.id == rootId || entry.id in ids) continue
        members += 1
        unreported += 1
    }

    return FamilyUsage(
        members = members,
        counted = counted,
        tokens = tokens,
        inFigure = inFigure,
        unreported = unreported,
        // Null rather than a subtotal: see the class doc. The invariant is stated here
        // rather than left to the reader — a figure exists exactly when nothing in it is
        // unpriced and there is something in it at all.
        cost = if (unpriced == 0 && inFigure > 0) CostRange(offPeak, peak) else null,
        unpriced = unpriced,
    )
}
