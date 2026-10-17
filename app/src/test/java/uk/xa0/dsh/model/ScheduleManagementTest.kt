package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * The host's Schedule catalogue as a management surface reads it.
 *
 * `AutomationsTest` already pins the small half — the field pair a clock mark needs. This
 * file pins the whole row: every rule kind the host defines, the status it reports, the
 * delivery it records, and the exact sentences a person is shown when a task is opened.
 *
 * Two things these cannot prove, and do not pretend to: that the host still answers these
 * shapes (that is read from `dsh-schedule/lib/types/types.d.ts`, not exercised here), and
 * that a delete is accepted (it is a mutation, and no test may issue one against the live
 * store). The mutation's *answer* shapes are pinned below, which is the half that can be
 * checked without a host.
 */
class ScheduleManagementTest {

    /**
     * One catalogue row in the host's own shape.
     *
     * [overrides] are applied last, so a test can replace a member (its kind, its target)
     * without the helper and the test both writing the same key.
     */
    private fun row(
        id: String = "schedule-1",
        sessionId: String = "session-a",
        status: String = "active",
        overrides: JSONObject = JSONObject(),
    ): JSONObject {
        val obj = JSONObject()
            .put("id", id)
            .put("kind", "weekly")
            .put("title", "Weekly tmp cleanup")
            .put("prompt", "Delete stale files.")
            .put("time", "09:00:00.000")
            .put("timeZone", "Europe/Helsinki")
            .put("weekdays", JSONArray(listOf(1, 3)))
            .put("scheduledAt", "2026-10-19T06:00:00.000Z")
            .put("sessionId", sessionId)
            .put("status", status)
        for (key in overrides.keys()) obj.put(key, overrides.get(key))
        return obj
    }

    private fun catalog(vararg rows: JSONObject) = JSONArray(rows.toList())

    // ---------------------------------------------------------------- the row

    @Test
    fun `a weekly row keeps its rule, its binding and its status`() {
        val read = readScheduleEntries(catalog(row()))
        assertEquals(1, read.entries.size)
        assertEquals(0, read.droppedRows)
        val entry = read.entries.single()
        assertEquals("schedule-1", entry.id)
        assertEquals("session-a", entry.sessionId)
        assertEquals("Weekly tmp cleanup", entry.title)
        assertEquals(ScheduleStatus.ACTIVE, entry.status)
        val spec = entry.spec as ScheduleSpec.Weekly
        assertEquals("09:00:00.000", spec.time)
        assertEquals("Europe/Helsinki", spec.timeZone)
        assertEquals(listOf(1, 3), spec.weekdays)
        assertEquals(Instant.parse("2026-10-19T06:00:00.000Z").toEpochMilli(), entry.scheduledAtMillis)
    }

    @Test
    fun `every kind the host defines is read as its own rule, not as a guess`() {
        val read = readScheduleEntries(
            catalog(
                row(id = "s-after", overrides = JSONObject().put("kind", "after").put("afterSeconds", 600)),
                row(id = "s-at", overrides = JSONObject().put("kind", "at")),
                row(id = "s-every", overrides = JSONObject().put("kind", "every").put("everySeconds", 1800)),
                row(id = "s-daily", overrides = JSONObject().put("kind", "daily").put("time", "00:45:00.000")),
                row(id = "s-cron", overrides = JSONObject().put("kind", "cron").put("expression", "0 9 * * 1-5")),
                row(id = "s-weekly"),
            ),
        )
        assertEquals(0, read.unknownKindRows)
        assertEquals(ScheduleSpec.After(600L), read.entries.first { it.id == "s-after" }.spec)
        assertEquals(ScheduleSpec.At, read.entries.first { it.id == "s-at" }.spec)
        assertEquals(ScheduleSpec.Every(1800L), read.entries.first { it.id == "s-every" }.spec)
        assertEquals(
            ScheduleSpec.Daily("00:45:00.000", "Europe/Helsinki"),
            read.entries.first { it.id == "s-daily" }.spec,
        )
        assertEquals(
            ScheduleSpec.Cron("0 9 * * 1-5", "Europe/Helsinki"),
            read.entries.first { it.id == "s-cron" }.spec,
        )
        assertTrue(read.entries.first { it.id == "s-weekly" }.spec is ScheduleSpec.Weekly)
    }

    @Test
    fun `an unknown kind is listed but never turns into a rule this build invents`() {
        val read = readScheduleEntries(catalog(row(overrides = JSONObject().put("kind", "fortnightly"))))
        assertEquals(1, read.unknownKindRows)
        assertEquals(ScheduleSpec.Unknown("fortnightly"), read.entries.single().spec)
        assertEquals(
            "Unrecognised rule (kind \"fortnightly\")",
            scheduleFrequencyLabel(read.entries.single().spec, "UTC"),
        )
    }

    @Test
    fun `both statuses are read, and a status this build does not know is not active`() {
        val read = readScheduleEntries(
            catalog(
                row(id = "s1", status = "active"),
                row(id = "s2", status = "inactive"),
                row(id = "s3", status = "paused"),
            ),
        )
        assertEquals(ScheduleStatus.ACTIVE, read.entries.first { it.id == "s1" }.status)
        assertEquals(ScheduleStatus.INACTIVE, read.entries.first { it.id == "s2" }.status)
        assertEquals(ScheduleStatus.UNKNOWN, read.entries.first { it.id == "s3" }.status)
        assertEquals("paused", read.entries.first { it.id == "s3" }.statusRaw)
        assertEquals(1, read.unknownStatusRows)
        // The count that feeds the badge and the mark is active only, so a status this
        // build cannot read can never look like a running task.
        assertEquals(1, activeScheduleCount(read.entries))
    }

    @Test
    fun `a row with no task id or no owning session is dropped, not repaired`() {
        val read = readScheduleEntries(
            catalog(
                row(id = "s1"),
                JSONObject()
                    .put("kind", "weekly")
                    .put("sessionId", "session-a")
                    .put("status", "active"),
                JSONObject().put("kind", "weekly").put("id", "s2").put("status", "active"),
            ),
        )
        assertEquals(1, read.entries.size)
        assertEquals(2, read.droppedRows)
        assertEquals(3, read.rows)
    }

    @Test
    fun `a null catalogue is no rows rather than a fault`() {
        val read = readScheduleEntries(null)
        assertEquals(0, read.rows)
        assertEquals(0, read.droppedRows)
        assertTrue(read.entries.isEmpty())
    }

    @Test
    fun `lastDelivery is read when it is there and absent when it is not`() {
        val delivered = readScheduleEntries(
            catalog(
                row(
                    overrides = JSONObject().put(
                        "lastDelivery",
                        JSONObject()
                            .put("scheduledAt", "2026-10-17T06:00:00.000Z")
                            .put("deliveredAt", "2026-10-17T06:00:00.139Z")
                            .put("messageId", "m-1"),
                    ),
                ),
            ),
        ).entries.single()
        assertNotNull(delivered.lastDelivery)
        assertEquals("m-1", delivered.lastDelivery?.messageId)

        assertNull(readScheduleEntries(catalog(row())).entries.single().lastDelivery)
    }

    // -------------------------------------------------------------- the words

    @Test
    fun `a weekly rule names its weekdays, its clock and its zone`() {
        val spec = ScheduleSpec.Weekly("09:00:00.000", "Europe/Helsinki", listOf(1, 3))
        assertEquals("Weekly on Mon, Wed at 09:00 (Europe/Helsinki)", scheduleFrequencyLabel(spec, "UTC"))
        // The web's own rule: the zone is omitted only when it is the reader's zone.
        assertEquals("Weekly on Mon, Wed at 09:00", scheduleFrequencyLabel(spec, "Europe/Helsinki"))
    }

    @Test
    fun `a daily rule and a cron rule keep the host's own spelling`() {
        assertEquals(
            "Daily at 00:45 (UTC)",
            scheduleFrequencyLabel(ScheduleSpec.Daily("00:45:00.000", "UTC"), "Europe/Helsinki"),
        )
        assertEquals(
            "Cron 0 9 * * 1-5 (Europe/Helsinki)",
            scheduleFrequencyLabel(ScheduleSpec.Cron("0 9 * * 1-5", "Europe/Helsinki"), "UTC"),
        )
    }

    @Test
    fun `a fixed rate is told in the largest unit that divides it, pluralised`() {
        assertEquals("Every 30 minutes", scheduleFrequencyLabel(ScheduleSpec.Every(1_800), "UTC"))
        assertEquals("Every 90 minutes", scheduleFrequencyLabel(ScheduleSpec.Every(5_400), "UTC"))
        assertEquals("Every 2 hours", scheduleFrequencyLabel(ScheduleSpec.Every(7_200), "UTC"))
        assertEquals("Every 1 hour", scheduleFrequencyLabel(ScheduleSpec.Every(3_600), "UTC"))
        assertEquals("Every 1 day", scheduleFrequencyLabel(ScheduleSpec.Every(86_400), "UTC"))
        assertEquals("Every 1 minute", scheduleFrequencyLabel(ScheduleSpec.Every(60), "UTC"))
        assertEquals("Every 90 seconds", scheduleFrequencyLabel(ScheduleSpec.Every(90), "UTC"))
        assertEquals(
            "Repeats at an unreadable interval",
            scheduleFrequencyLabel(ScheduleSpec.Every(0), "UTC"),
        )
    }

    @Test
    fun `one-shot rules are Once whatever created them`() {
        assertEquals("Once", scheduleFrequencyLabel(ScheduleSpec.After(600), "UTC"))
        assertEquals("Once", scheduleFrequencyLabel(ScheduleSpec.At, "UTC"))
    }

    @Test
    fun `an unknown status says so rather than reading as enabled`() {
        assertEquals("Enabled", scheduleStatusLabel(ScheduleStatus.ACTIVE))
        assertEquals("Inactive", scheduleStatusLabel(ScheduleStatus.INACTIVE))
        assertEquals("Unknown status", scheduleStatusLabel(ScheduleStatus.UNKNOWN))
    }

    @Test
    fun `the stored clock drops fractional and zero seconds only when it has them`() {
        assertEquals("09:00", scheduleClockLabel("09:00:00.000"))
        assertEquals("00:45:30", scheduleClockLabel("00:45:30.000"))
        assertEquals("09:00", scheduleClockLabel("09:00:00"))
        // A two-field clock must survive: trimming `:00` would leave `09`.
        assertEquals("09:00", scheduleClockLabel("09:00"))
        assertEquals("23:59:59", scheduleClockLabel("23:59:59.999"))
    }

    @Test
    fun `a name falls back to host data, never to an invented one`() {
        val titled = readScheduleEntries(catalog(row())).entries.single()
        assertEquals("Weekly tmp cleanup", scheduleDisplayTitle(titled))

        val untitled = readScheduleEntries(
            catalog(row(overrides = JSONObject().put("title", "  "))),
        ).entries.single()
        assertEquals("Delete stale files.", scheduleDisplayTitle(untitled))
    }

    // ------------------------------------------------------------- the timing

    @Test
    fun `a distance is said in the largest natural unit, rounded the web's way`() {
        val now = 1_000_000_000_000L
        assertEquals("in 3 hours", scheduleRelativeLabel(now + 3 * 3_600_000L, now))
        assertEquals("1 minute overdue", scheduleRelativeLabel(now - 90_000L, now))
        assertEquals("30 seconds overdue", scheduleRelativeLabel(now - 30_000L, now))
        // The web's `UNIT_SECONDS` ends with `{ unit: "second", seconds: 1 }` and has
        // no one-minute floor, so a sub-minute distance is stated in seconds. Pinning
        // this stops a future "fix" from making a nearer target look further away.
        assertEquals("in 30 seconds", scheduleRelativeLabel(now + 30_000L, now))
        assertEquals("in 1 second", scheduleRelativeLabel(now + 1_000L, now))
        assertEquals("in 2 minutes", scheduleRelativeLabel(now + 90_000L, now))
        assertEquals("in 1 minute", scheduleRelativeLabel(now + 60_000L, now))
        assertEquals("1 minute overdue", scheduleRelativeLabel(now - 60_000L, now))
        assertEquals("Due now", scheduleRelativeLabel(now, now))
    }

    @Test
    fun `a timestamp names its day, and its year only when the year differs`() {
        val zone = TimeZone.getTimeZone("Europe/Helsinki")
        val locale = Locale.UK
        val now = Instant.parse("2026-10-18T00:00:00.000Z").toEpochMilli()
        val later = Instant.parse("2026-10-19T06:00:00.000Z").toEpochMilli()
        assertEquals("19 Oct 09:00", scheduleStamp(later, now, zone, locale))

        val nextYear = Instant.parse("2027-01-02T06:00:00.000Z").toEpochMilli()
        assertEquals("2 Jan 2027 08:00", scheduleStamp(nextYear, now, zone, locale))
    }

    @Test
    fun `an instant is parsed only when it is one`() {
        assertEquals(
            Instant.parse("2026-10-20T06:00:00.000Z").toEpochMilli(),
            parseScheduleInstant("2026-10-20T06:00:00.000Z"),
        )
        assertNull(parseScheduleInstant("tomorrow"))
        assertNull(parseScheduleInstant(""))
        assertNull(parseScheduleInstant(null))
    }

    @Test
    fun `the list puts what will fire first and what has ended after it`() {
        val entries = readScheduleEntries(
            catalog(
                row(
                    id = "s-late",
                    status = "active",
                    overrides = JSONObject().put("scheduledAt", "2026-10-21T06:00:00.000Z"),
                ),
                row(
                    id = "s-ended",
                    status = "inactive",
                    overrides = JSONObject().put("scheduledAt", "2026-10-01T06:00:00.000Z"),
                ),
                row(
                    id = "s-soon",
                    status = "active",
                    overrides = JSONObject().put("scheduledAt", "2026-10-19T06:00:00.000Z"),
                ),
            ),
        ).entries
        assertEquals(listOf("s-soon", "s-late", "s-ended"), sortScheduleEntries(entries).map { it.id })
    }

    @Test
    fun `an unreadable target sorts last inside its group rather than first`() {
        val entries = readScheduleEntries(
            catalog(
                row(id = "s-broken", status = "active", overrides = JSONObject().put("scheduledAt", "soon")),
                row(id = "s-ok", status = "active"),
            ),
        ).entries
        assertEquals(listOf("s-ok", "s-broken"), sortScheduleEntries(entries).map { it.id })
    }

    // ---------------------------------------------------- the other two reads

    @Test
    fun `a history page keeps its records, its retention and its cursor`() {
        val read = readScheduleHistory(
            JSONObject(
                """
                {"id":"schedule-1","records":[
                  {"scheduledAt":"2026-10-17T06:00:00.000Z","deliveredAt":"2026-10-17T06:00:00.139Z",
                   "messageId":"m-2","prompt":"Delete stale files."},
                  {"scheduledAt":"2026-10-16T06:00:00.000Z","deliveredAt":"2026-10-16T06:00:00.201Z",
                   "messageId":"m-1"}
                ],"earlierRecordsUnavailable":false,"earlierRecordsPruned":true,
                 "retention":{"days":30,"records":200},"nextBefore":"m-1"}
                """.trimIndent(),
            ),
        )
        val loaded = read as ScheduleHistoryRead.Loaded
        assertEquals(2, loaded.records.size)
        assertEquals("Delete stale files.", loaded.records[0].prompt)
        assertNull(loaded.records[1].prompt)
        assertFalse(loaded.earlierRecordsUnavailable)
        assertTrue(loaded.earlierRecordsPruned)
        assertEquals(30, loaded.retentionDays)
        assertEquals(200, loaded.retentionRecords)
        assertEquals("m-1", loaded.nextBefore)
    }

    @Test
    fun `a history read that names no task says so instead of reading as empty`() {
        val gone = readScheduleHistory(JSONObject().put("id", "schedule-1").put("code", "schedule_not_found"))
        assertEquals(ScheduleHistoryRead.Gone("schedule_not_found"), gone)

        val unusable = readScheduleHistory(JSONArray())
        assertTrue(unusable is ScheduleHistoryRead.Unusable)
    }

    @Test
    fun `a delete answer is read as removed, already gone, or unreadable`() {
        assertEquals(
            ScheduleDeleteOutcome.Deleted,
            readScheduleDeleteResult(JSONObject().put("id", "s").put("deleted", true)),
        )
        assertEquals(
            ScheduleDeleteOutcome.NotFound,
            readScheduleDeleteResult(
                JSONObject().put("id", "s").put("deleted", false).put("code", "schedule_not_found"),
            ),
        )
        // `deleted: false` with no code is not "not found": it is an answer this build
        // does not understand, and it must not be reported as a successful removal.
        assertTrue(
            readScheduleDeleteResult(JSONObject().put("id", "s").put("deleted", false))
                is ScheduleDeleteOutcome.Unusable,
        )
        assertTrue(readScheduleDeleteResult(JSONObject.NULL) is ScheduleDeleteOutcome.Unusable)
    }
}
