package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The host's Schedule catalogue, read for *management* rather than for the clock mark.
 *
 * [Automations.kt] deliberately keeps one field pair per row — the owning session and
 * whether the task is active — because that is all a mark needs. This file is the other
 * reader: it keeps the whole row, because a list that is meant to be actionable has to
 * say what the task does, when it will run, and where it will deliver.
 *
 * Everything here comes from `schedule/catalog`, which the host answers with a bare
 * JSON array (hence `DshClient.rpcRaw`, not `rpc`). One row is exactly a
 * `ScheduleCatalogEntry`: the stored record's own members (`id`, `kind`, `title`,
 * `prompt`, the rule's fields, `scheduledAt`) plus the three the catalogue adds —
 * `sessionId`, `status`, and `lastDelivery` when the task has ever delivered.
 *
 * ## What this file refuses to do
 *
 * It does not invent a rule. A `kind` this build does not know becomes
 * [ScheduleSpec.Unknown] and is shown as an unrecognised rule rather than as a guess at
 * what the host meant. There is no enable/disable operation on the wire: `status` is
 * read-only here, and [ScheduleStatus] is a description of the host's state, never
 * something this client can write. `schedule/update` carries no `status` member at all
 * and refuses an inactive row with `schedule_ended`, so a toggle would have to lie.
 *
 * ## The rule grammar, as the host's own types state it
 *
 *  - `after` — a one-shot delay, stored as `afterSeconds` and the absolute
 *    `scheduledAt` it computed at creation.
 *  - `at` — a one-shot absolute target, stored only as `scheduledAt`.
 *  - `every` — a fixed rate, `everySeconds` (never below 60), `scheduledAt` is the next
 *    anchor-aligned occurrence.
 *  - `daily` — `time` in `HH:mm:ss.SSS`, plus an explicit IANA `timeZone`.
 *  - `weekly` — the same time and zone, plus `weekdays`: unique ascending ISO weekdays,
 *    Monday 1 through Sunday 7.
 *  - `cron` — a canonical five-field Vixie expression
 *    (`minute hour day-of-month month day-of-week`) and an explicit IANA `timeZone`.
 *
 * `scheduledAt` is always a UTC instant (four-digit year, RFC 3339). For a recurring
 * rule it is the next committed occurrence while the task is active, and the final one
 * once it has ended; for a one-shot it is the only occurrence.
 */

/** The `status` a task that will fire again carries. */
const val SCHEDULE_STATUS_ACTIVE: String = "active"

/** The only other `status` the host's schema defines: the task will never fire again. */
const val SCHEDULE_STATUS_INACTIVE: String = "inactive"

/** How this build reads one row's `status`. */
enum class ScheduleStatus {
    /** The host will let it fire again. */
    ACTIVE,

    /** It has ended — a delivered one-shot, or a recurring rule with no future target. */
    INACTIVE,

    /** A value this build does not know; it is never treated as active. */
    UNKNOWN,
}

/**
 * One stored rule, reduced to the shape a person can be told.
 *
 * The variants are the host's own `kind` discriminators, and each keeps exactly the
 * members that make its timing sayable. Nothing here is normalised into a different
 * model: a cron rule stays a cron expression, because re-describing it is where a
 * client starts lying about when something will run.
 */
sealed interface ScheduleSpec {
    /** A one-shot reminder created from a positive delay. */
    data class After(val afterSeconds: Long?) : ScheduleSpec

    /** A one-shot reminder created from an absolute instant. */
    data object At : ScheduleSpec

    /** A fixed-rate reminder, in seconds, aligned to creation or the last timing edit. */
    data class Every(val everySeconds: Long) : ScheduleSpec

    /** A daily wall-clock rule in an explicit IANA zone. */
    data class Daily(val time: String, val timeZone: String) : ScheduleSpec

    /** A weekly wall-clock rule in an explicit IANA zone, on ISO weekdays 1–7. */
    data class Weekly(
        val time: String,
        val timeZone: String,
        val weekdays: List<Int>,
    ) : ScheduleSpec

    /** A five-field cron rule in an explicit IANA zone. */
    data class Cron(val expression: String, val timeZone: String) : ScheduleSpec

    /** A `kind` this build does not know; [kind] is the raw value, shown verbatim. */
    data class Unknown(val kind: String) : ScheduleSpec
}

/** One durably acknowledged delivery into the task's session. */
data class ScheduleDelivery(
    /** Canonical UTC target of the delivered occurrence. */
    val scheduledAt: String,
    /** Canonical UTC time sampled after the host's Session persistence acknowledged it. */
    val deliveredAt: String,
    /** Identity of the delivered message, shared by tasks in one recurring batch. */
    val messageId: String,
    /** The prompt sent for this occurrence, when the host recorded it. */
    val prompt: String?,
)

/**
 * One host-wide schedule, as `schedule/catalog` reports it.
 *
 * [spec] is the rule, [scheduledAt] the committed target, [status] whether it will fire
 * again, and [lastDelivery] the most recent acknowledged inbox delivery (absent until
 * the task has delivered once). [sessionId] is the session every occurrence is delivered
 * into — not a session a run creates.
 */
data class ScheduleEntry(
    val id: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
    val status: ScheduleStatus,
    /** The raw `status` value, kept so an unknown one can be reported verbatim. */
    val statusRaw: String,
    val spec: ScheduleSpec,
    /** Durable UTC target, verbatim from the catalogue. */
    val scheduledAt: String,
    /** [scheduledAt] in epoch milliseconds, or null when it is not a readable instant. */
    val scheduledAtMillis: Long?,
    val lastDelivery: ScheduleDelivery?,
)

/**
 * What one `schedule/catalog` answer held, and what reading it could not use.
 *
 * The counts are the honest half: a row that names no task or no owning session is
 * skipped rather than repaired (a row with an invented session is worse than a missing
 * one), an unknown `status` is kept but never active, and an unknown `kind` is kept so
 * the task can still be found, opened and deleted while its rule is reported as
 * unrecognised.
 */
data class ScheduleCatalogRead(
    val entries: List<ScheduleEntry>,
    val rows: Int,
    val droppedRows: Int,
    val unknownStatusRows: Int,
    val unknownKindRows: Int,
)

/**
 * Reads one `schedule/catalog` answer in full.
 *
 * A null answer is the "no catalogue" case and yields no rows: the call failed and the
 * caller has already reported why. Rows keep the host's order, which is `scheduledAt`
 * ascending and then id; [sortScheduleEntries] applies the list's own order.
 */
fun readScheduleEntries(catalog: JSONArray?): ScheduleCatalogRead {
    if (catalog == null) return ScheduleCatalogRead(emptyList(), 0, 0, 0, 0)
    val entries = ArrayList<ScheduleEntry>(catalog.length())
    var dropped = 0
    var unknownStatus = 0
    var unknownKind = 0
    for (i in 0 until catalog.length()) {
        val row = catalog.optJSONObject(i)
        if (row == null) {
            dropped++
            continue
        }
        val id = row.optString("id")
        val sessionId = row.optString("sessionId")
        if (id.isEmpty() || sessionId.isEmpty()) {
            dropped++
            continue
        }
        val statusRaw = row.optString("status")
        val status = when (statusRaw) {
            SCHEDULE_STATUS_ACTIVE -> ScheduleStatus.ACTIVE
            SCHEDULE_STATUS_INACTIVE -> ScheduleStatus.INACTIVE
            else -> {
                unknownStatus++
                ScheduleStatus.UNKNOWN
            }
        }
        val kind = row.optString("kind")
        val spec = readSpec(kind, row)
        if (spec is ScheduleSpec.Unknown) unknownKind++
        val scheduledAt = row.optString("scheduledAt")
        entries += ScheduleEntry(
            id = id,
            sessionId = sessionId,
            title = row.optString("title"),
            prompt = row.optString("prompt"),
            status = status,
            statusRaw = statusRaw,
            spec = spec,
            scheduledAt = scheduledAt,
            scheduledAtMillis = parseScheduleInstant(scheduledAt),
            lastDelivery = readDelivery(row.optJSONObject("lastDelivery")),
        )
    }
    return ScheduleCatalogRead(
        entries = entries,
        rows = catalog.length(),
        droppedRows = dropped,
        unknownStatusRows = unknownStatus,
        unknownKindRows = unknownKind,
    )
}

/** Reads one row's rule by its `kind`, keeping only members the host defines. */
private fun readSpec(kind: String, row: JSONObject): ScheduleSpec = when (kind) {
    "after" -> ScheduleSpec.After(
        afterSeconds = row.optLong("afterSeconds", -1L).takeIf { it >= 0L },
    )

    "at" -> ScheduleSpec.At
    "every" -> ScheduleSpec.Every(everySeconds = row.optLong("everySeconds", 0L))
    "daily" -> ScheduleSpec.Daily(
        time = row.optString("time"),
        timeZone = row.optString("timeZone"),
    )

    "weekly" -> ScheduleSpec.Weekly(
        time = row.optString("time"),
        timeZone = row.optString("timeZone"),
        weekdays = readWeekdays(row.optJSONArray("weekdays")),
    )

    "cron" -> ScheduleSpec.Cron(
        expression = row.optString("expression"),
        timeZone = row.optString("timeZone"),
    )

    else -> ScheduleSpec.Unknown(kind)
}

/** The stored ISO weekday set, keeping only 1–7 and preserving the host's order. */
private fun readWeekdays(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    val out = ArrayList<Int>(array.length())
    for (i in 0 until array.length()) {
        val day = array.optInt(i, 0)
        if (day in 1..7 && day !in out) out += day
    }
    return out
}

/** One delivery receipt, when the host gave one. */
private fun readDelivery(row: JSONObject?): ScheduleDelivery? {
    if (row == null) return null
    val scheduledAt = row.optString("scheduledAt")
    val deliveredAt = row.optString("deliveredAt")
    if (scheduledAt.isEmpty() || deliveredAt.isEmpty()) return null
    return ScheduleDelivery(
        scheduledAt = scheduledAt,
        deliveredAt = deliveredAt,
        messageId = row.optString("messageId"),
        prompt = null,
    )
}

/** Parses the host's durable UTC instant, or null when the text is not one. */
fun parseScheduleInstant(text: String?): Long? {
    if (text.isNullOrEmpty()) return null
    return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
}

/**
 * The order the management list draws: what will fire, soonest first, then what has
 * ended.
 *
 * The status split is the one deliberate departure from the web page's single
 * `scheduledAt` sort. An ended task's target is in the past, so a plain ascending sort
 * floats every ended row above the task that runs next — and this app's list has no
 * second pane to push them into. The web does have status tabs, and this list keeps
 * them; grouping by status is what those tabs already mean. Rows keep the host's
 * tie-break (ascending target, then id) inside each group, and an unreadable target
 * sorts last within its group rather than at the top.
 */
fun sortScheduleEntries(entries: List<ScheduleEntry>): List<ScheduleEntry> =
    entries.sortedWith(
        compareBy<ScheduleEntry>(
            { if (it.status == ScheduleStatus.ACTIVE) 0 else 1 },
            { it.scheduledAtMillis ?: Long.MAX_VALUE },
            { it.id },
        ),
    )

/** How many tasks the host says will fire again. */
fun activeScheduleCount(entries: List<ScheduleEntry>): Int =
    entries.count { it.status == ScheduleStatus.ACTIVE }

/**
 * The task's name for display.
 *
 * Every stored record and every catalogue entry carries a title the host has already
 * trimmed and required to be non-empty, so this normally returns it unchanged. If a
 * host ever answers without one, the fallback is still host data — the instruction's
 * first line, then the task id — rather than a name this client made up.
 */
fun scheduleDisplayTitle(entry: ScheduleEntry): String {
    val title = entry.title.trim()
    if (title.isNotEmpty()) return title
    val firstLine = entry.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    return firstLine?.take(80)?.takeIf { it.isNotEmpty() } ?: entry.id
}

/** Status copy, in the web's own words: `Enabled` or `Inactive`. */
fun scheduleStatusLabel(status: ScheduleStatus): String = when (status) {
    ScheduleStatus.ACTIVE -> "Enabled"
    ScheduleStatus.INACTIVE -> "Inactive"
    ScheduleStatus.UNKNOWN -> "Unknown status"
}

/**
 * The stored wall-clock time, written the way the web writes it: fractional seconds
 * dropped, and a `:00` seconds field dropped with them, so `09:00:00.000` is `09:00`
 * and `00:45:30.000` stays `00:45:30`. Unrecognised text is returned unchanged.
 */
fun scheduleClockLabel(time: String): String {
    if (time.isEmpty()) return time
    var value = time
    val dot = value.indexOf('.')
    if (dot >= 0) value = value.substring(0, dot)
    // Only a three-field clock drops its seconds: bare `09:00` is already the shape
    // this wants, and trimming it would leave `09`.
    if (value.count { it == ':' } >= 2 && value.endsWith(":00")) value = value.dropLast(3)
    return value
}

/** ISO weekday names, the web's own abbreviations, indexed Monday = 1. */
private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** `Mon, Wed` — the web's join, and its abbreviations. */
fun scheduleWeekdayLabel(weekdays: List<Int>): String =
    weekdays.mapNotNull { WEEKDAY_LABELS.getOrNull(it - 1) }.joinToString(", ")

/**
 * Seconds per unit, largest first, matching the web's interval units. A fixed rate is
 * described in the largest unit that divides it exactly, so 1800 seconds reads as
 * "Every 30 minutes" rather than "Every 1800 seconds".
 */
private val INTERVAL_UNITS = listOf(
    "day" to 86_400L,
    "hour" to 3_600L,
    "minute" to 60L,
    "second" to 1L,
)

/** `Every 30 minutes`, the web's own `frequency.every` shape. */
fun scheduleIntervalLabel(everySeconds: Long): String {
    if (everySeconds <= 0L) return "Repeats at an unreadable interval"
    val (unit, seconds) = INTERVAL_UNITS.firstOrNull { everySeconds % it.second == 0L }
        ?: ("second" to 1L)
    val value = everySeconds / seconds
    return "Every $value $unit"
}

/**
 * The rule as a sentence, in the web page's vocabulary.
 *
 * [systemTimeZone] is the device's own zone. A daily, weekly or cron rule names its
 * zone whenever it differs from the device's, exactly as the web does — and always
 * names it in the detail, where the rule is what the reader came to check. A cron
 * expression is printed verbatim: this client does not re-describe a cron rule, because
 * any prose it wrote would be a second implementation of the host's parser.
 */
fun scheduleFrequencyLabel(spec: ScheduleSpec, systemTimeZone: String): String {
    val zoneSuffix = { zone: String ->
        if (zone.isBlank()) " (no zone stored)"
        else if (zone == systemTimeZone) ""
        else " ($zone)"
    }
    return when (spec) {
        is ScheduleSpec.After, ScheduleSpec.At -> "Once"
        is ScheduleSpec.Every -> scheduleIntervalLabel(spec.everySeconds)
        is ScheduleSpec.Daily ->
            "Daily at ${scheduleClockLabel(spec.time)}${zoneSuffix(spec.timeZone)}"

        is ScheduleSpec.Weekly -> {
            val days = scheduleWeekdayLabel(spec.weekdays)
            val on = if (days.isEmpty()) "Weekly" else "Weekly on $days"
            "$on at ${scheduleClockLabel(spec.time)}${zoneSuffix(spec.timeZone)}"
        }

        is ScheduleSpec.Cron -> {
            val expression = spec.expression.ifBlank { "(no expression stored)" }
            "Cron $expression${zoneSuffix(spec.timeZone)}"
        }

        is ScheduleSpec.Unknown -> "Unrecognised rule (kind \"${spec.kind}\")"
    }
}

/** The rule as a sentence that always names its zone, for the detail's own line. */
fun scheduleFrequencyLabelWithZone(spec: ScheduleSpec): String = when (spec) {
    is ScheduleSpec.Daily ->
        "Daily at ${scheduleClockLabel(spec.time)} (${spec.timeZone.ifBlank { "no zone stored" }})"

    is ScheduleSpec.Weekly -> {
        val days = scheduleWeekdayLabel(spec.weekdays)
        val on = if (days.isEmpty()) "Weekly" else "Weekly on $days"
        "$on at ${scheduleClockLabel(spec.time)} (${spec.timeZone.ifBlank { "no zone stored" }})"
    }

    is ScheduleSpec.Cron ->
        "Cron ${spec.expression.ifBlank { "(no expression stored)" }}" +
            " (${spec.timeZone.ifBlank { "no zone stored" }})"

    else -> scheduleFrequencyLabel(spec, systemTimeZone = "")
}

/** The units the relative label steps through, largest first, as the web lists them. */
private val RELATIVE_UNITS = listOf(
    "day" to 86_400L,
    "hour" to 3_600L,
    "minute" to 60L,
    "second" to 1L,
)

private fun plural(value: Long, unit: String): String =
    if (value == 1L) "$value $unit" else "$value ${unit}s"

/**
 * How far away a committed target is, said in the largest natural unit: `in 3 hours`,
 * `2 minutes overdue`, or `Due now`. The rounding is the web's — a future distance
 * rounds up, a past one rounds down, and neither is ever `0` — so a target one second
 * ahead reads as `in 1 minute` rather than vanishing into "now".
 */
fun scheduleRelativeLabel(targetMillis: Long, now: Long): String {
    val difference = targetMillis - now
    if (difference == 0L) return "Due now"
    val absoluteSeconds = kotlin.math.abs(difference) / 1000.0
    val (unit, seconds) = RELATIVE_UNITS.firstOrNull { absoluteSeconds >= it.second }
        ?: ("second" to 1L)
    val value = if (difference > 0) {
        kotlin.math.ceil(absoluteSeconds / seconds).toLong()
    } else {
        kotlin.math.floor(absoluteSeconds / seconds).toLong()
    }.coerceAtLeast(1L)
    return if (difference > 0) "in ${plural(value, unit)}" else "${plural(value, unit)} overdue"
}

/** `d MMM HH:mm`, the shape the widget's footer already uses for an instant. */
private const val STAMP_PATTERN = "d MMM HH:mm"

/** The same, with the year, for an instant outside the current year. */
private const val STAMP_PATTERN_WITH_YEAR = "d MMM yyyy HH:mm"

/**
 * One committed target as a date and time in [zone].
 *
 * The year appears only when the target falls outside [now]'s year, so a same-year
 * target stays compact while an old delivery still dates itself. Both the zone and the
 * locale are parameters because a stamp whose answer moves with the machine is not a
 * fact a test can assert — and because the deliverable is a person reading a clock time
 * that must be unambiguous: the date is never omitted.
 */
fun scheduleStamp(
    millis: Long,
    now: Long,
    zone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val targetYear = SimpleDateFormat("yyyy", locale).apply { timeZone = zone }.format(Date(millis))
    val nowYear = SimpleDateFormat("yyyy", locale).apply { timeZone = zone }.format(Date(now))
    val pattern = if (targetYear == nowYear) STAMP_PATTERN else STAMP_PATTERN_WITH_YEAR
    return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(millis))
}

/** Why one history read could not be shown. */
sealed interface ScheduleHistoryRead {
    /** The host's newest-first page, with the retention bounds it applied. */
    data class Loaded(
        val records: List<ScheduleDelivery>,
        val earlierRecordsUnavailable: Boolean,
        val earlierRecordsPruned: Boolean,
        val retentionDays: Int,
        val retentionRecords: Int,
        val nextBefore: String?,
    ) : ScheduleHistoryRead

    /** The host no longer holds that task, or the cursor no longer names a record. */
    data class Gone(val code: String) : ScheduleHistoryRead

    /** An answer this build cannot read; [reason] says what arrived instead. */
    data class Unusable(val reason: String) : ScheduleHistoryRead
}

/**
 * Reads one `schedule/history` answer.
 *
 * The host's request needs both the task id and the session binding it was stored
 * against, and its reply is either the page or a `schedule_not_found` /
 * `delivery_cursor_not_found` code. Records are newest first in append order, not
 * wall-clock order, and each carries the prompt that was sent when the host recorded
 * one.
 */
fun readScheduleHistory(value: Any?): ScheduleHistoryRead {
    val row = value as? JSONObject ?: return ScheduleHistoryRead.Unusable(describeScheduleAnswer(value))
    val code = row.optString("code")
    if (code.isNotEmpty()) return ScheduleHistoryRead.Gone(code)
    val records = row.optJSONArray("records")
        ?: return ScheduleHistoryRead.Unusable("no records array")
    val out = ArrayList<ScheduleDelivery>(records.length())
    for (i in 0 until records.length()) {
        val record = records.optJSONObject(i) ?: continue
        val scheduledAt = record.optString("scheduledAt")
        val deliveredAt = record.optString("deliveredAt")
        if (scheduledAt.isEmpty() || deliveredAt.isEmpty()) continue
        out += ScheduleDelivery(
            scheduledAt = scheduledAt,
            deliveredAt = deliveredAt,
            messageId = record.optString("messageId"),
            prompt = record.optString("prompt").takeIf { it.isNotEmpty() },
        )
    }
    val retention = row.optJSONObject("retention")
    return ScheduleHistoryRead.Loaded(
        records = out,
        earlierRecordsUnavailable = row.optBoolean("earlierRecordsUnavailable"),
        earlierRecordsPruned = row.optBoolean("earlierRecordsPruned"),
        retentionDays = retention?.optInt("days") ?: 0,
        retentionRecords = retention?.optInt("records") ?: 0,
        nextBefore = row.optString("nextBefore").takeIf { it.isNotEmpty() },
    )
}

/** What one `schedule/delete` call did. */
sealed interface ScheduleDeleteOutcome {
    /** The row was removed; the task no longer schedules. */
    data object Deleted : ScheduleDeleteOutcome

    /** The host no longer owns that task under that session — nothing was changed. */
    data object NotFound : ScheduleDeleteOutcome

    /** An answer this build cannot read; [reason] says what arrived instead. */
    data class Unusable(val reason: String) : ScheduleDeleteOutcome
}

/**
 * Reads one `schedule/delete` answer.
 *
 * The host's only two answers are `{id, deleted: true}` and
 * `{id, deleted: false, code: "schedule_not_found"}`; a structured refusal arrives as a
 * thrown `DshRpcException` instead, so it is never seen here. `deleted: false` is not a
 * failure of the app's making — somebody else's delete landed first, or the host was
 * restarted with a different store — and the list must repaint from the host rather
 * than claim the tap did nothing.
 */
fun readScheduleDeleteResult(value: Any?): ScheduleDeleteOutcome {
    val row = value as? JSONObject ?: return ScheduleDeleteOutcome.Unusable(describeScheduleAnswer(value))
    if (row.optBoolean("deleted")) return ScheduleDeleteOutcome.Deleted
    if (row.optString("code") == "schedule_not_found") return ScheduleDeleteOutcome.NotFound
    return ScheduleDeleteOutcome.Unusable("deleted was false with no schedule_not_found code")
}

/** Names a reply this build did not expect, so a log line or a screen can say what came. */
fun describeScheduleAnswer(value: Any?): String = when (value) {
    null -> "no value"
    is JSONObject -> "a JSON object"
    is JSONArray -> "a JSON array"
    is String -> "a string"
    is Number -> "a number"
    is Boolean -> "a boolean"
    else -> value.javaClass.simpleName
}
