package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.ScheduleHistoryState
import uk.xa0.dsh.SchedulePageState
import uk.xa0.dsh.model.ScheduleDelivery
import uk.xa0.dsh.model.ScheduleEntry
import uk.xa0.dsh.model.ScheduleHistoryRead
import uk.xa0.dsh.model.ScheduleSpec
import uk.xa0.dsh.model.ScheduleStatus
import uk.xa0.dsh.model.parseScheduleInstant
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme
import java.time.Instant

/**
 * The Automation tasks sheet's own screen: what a row says, what the detail says, and
 * that the delete verb is behind a confirmation that names the task.
 *
 * ## What these can and cannot show
 *
 * They can show which nodes exist and which callback a tap reaches. They can show that
 * an inactive task is drawn with its own status and its final target rather than a next
 * run, that the status filter actually removes rows, and that "Delete task" opens a
 * dialog whose confirm — and only whose confirm — calls back.
 *
 * They cannot show that the host accepts a delete (`schedule/delete` is a mutation, and
 * nothing here may issue one), that a rule renders *legibly* (this harness cannot measure
 * text or colour), or that the sheet's read is wired to these states — `ChatScreen` does
 * that, and it is read from source rather than exercised here. The display is pinned to
 * a phone-sized 411×891 dp window because the default Robolectric display is
 * `w320dp-h470dp` and anything below that fold is a tap the test would throw away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
class AutomationTasksTest {

    @get:Rule
    val rule = createComposeRule()

    /** The sheet's "now", fixed so every relative label is assertable. */
    private val now = Instant.parse("2026-10-18T06:00:00.000Z").toEpochMilli()

    private fun entry(
        id: String = "schedule-1",
        title: String = "Weekly tmp cleanup",
        prompt: String = "Delete files older than two days.",
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        spec: ScheduleSpec = ScheduleSpec.Weekly("09:00:00.000", "Europe/Helsinki", listOf(1, 3)),
        scheduledAt: String = "2026-10-19T06:00:00.000Z",
        lastDelivery: ScheduleDelivery? = null,
    ) = ScheduleEntry(
        id = id,
        sessionId = "session-a",
        title = title,
        prompt = prompt,
        status = status,
        statusRaw = if (status == ScheduleStatus.ACTIVE) "active" else "inactive",
        spec = spec,
        scheduledAt = scheduledAt,
        scheduledAtMillis = parseScheduleInstant(scheduledAt),
        lastDelivery = lastDelivery,
    )

    /** The content under the app's theme, in a phone-sized box, as `FilesPanelTest` does. */
    private fun show(
        page: SchedulePageState,
        historyTaskId: String? = null,
        history: ScheduleHistoryState = ScheduleHistoryState.Idle,
        actionError: String? = null,
        notice: String? = null,
        deletingId: String? = null,
        sessionTitle: (String) -> String? = { "Weekly bump session" },
        onDelete: (ScheduleEntry) -> Unit = {},
        onOpenSession: (String) -> Unit = {},
    ) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 411.dp, height = 860.dp)) {
                    AutomationContent(
                        page = page,
                        historyTaskId = historyTaskId,
                        history = history,
                        deletingId = deletingId,
                        actionError = actionError,
                        notice = notice,
                        sessionTitle = sessionTitle,
                        onRetry = {},
                        onHistory = {},
                        onLoadMoreHistory = {},
                        onDelete = onDelete,
                        onDismissAction = {},
                        onOpenSession = onOpenSession,
                        now = now,
                        systemTimeZone = "Europe/Helsinki",
                    )
                }
            }
        }
    }

    @Test
    fun `a row says what it does, when it runs, where it delivers and when it last did`() {
        show(SchedulePageState.Ready(listOf(entry())))
        rule.waitForIdle()

        // The device's zone is the rule's own here, so the row omits the suffix — the
        // web's decision, and the reason this line once failed while the detail passed.
        rule.onNodeWithText("Weekly on Mon, Wed at 09:00").assertExists()
        rule.onNodeWithText("Next run 19 Oct 09:00 (in 1 day)").assertExists()
        rule.onNodeWithText("Into Weekly bump session").assertExists()
        rule.onNodeWithText("Never delivered").assertExists()
        rule.onNodeWithText("1 task · 1 enabled").assertExists()
    }

    @Test
    fun `a rule names its zone only when it is not the device's`() {
        show(
            SchedulePageState.Ready(
                listOf(
                    entry(id = "s-local"),
                    entry(
                        id = "s-foreign",
                        title = "Peak reminder",
                        spec = ScheduleSpec.Daily("00:45:00.000", "UTC"),
                        scheduledAt = "2026-10-19T00:45:00.000Z",
                    ),
                ),
            ),
        )
        rule.waitForIdle()

        // Same zone as the device's (Europe/Helsinki): no suffix, no noise.
        rule.onNodeWithText("Weekly on Mon, Wed at 09:00").assertExists()
        rule.onNodeWithText("Weekly on Mon, Wed at 09:00 (Europe/Helsinki)").assertDoesNotExist()
        // A different zone is named, so "00:45" can never be read as the reader's clock.
        rule.onNodeWithText("Daily at 00:45 (UTC)").assertExists()
    }

    @Test
    fun `an ended task is drawn as ended and is not counted as enabled`() {
        show(
            SchedulePageState.Ready(
                listOf(
                    entry(id = "s-active", title = "Still running"),
                    entry(
                        id = "s-ended",
                        title = "Finished task",
                        status = ScheduleStatus.INACTIVE,
                        scheduledAt = "2026-10-01T06:00:00.000Z",
                    ),
                ),
            ),
        )
        rule.waitForIdle()

        // The ended row has no next run and no relative countdown: it has a final target.
        rule.onNodeWithText("Final target 1 Oct 09:00").assertExists()
        rule.onNodeWithText("2 tasks · 1 enabled").assertExists()
    }

    @Test
    fun `the status tabs remove rows rather than only labelling them`() {
        show(
            SchedulePageState.Ready(
                listOf(
                    entry(id = "s-active", title = "Still running"),
                    entry(id = "s-ended", title = "Finished task", status = ScheduleStatus.INACTIVE),
                ),
            ),
        )
        rule.waitForIdle()
        rule.onNodeWithText("Finished task").assertExists()

        rule.onNodeWithTag("schedule-filter-enabled").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Still running").assertExists()
        rule.onNodeWithText("Finished task").assertDoesNotExist()

        rule.onNodeWithTag("schedule-filter-inactive").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Finished task").assertExists()
        rule.onNodeWithText("Still running").assertDoesNotExist()
    }

    @Test
    fun `the detail states status, rule, session, instruction and the one verb`() {
        show(SchedulePageState.Ready(listOf(entry())))
        rule.waitForIdle()

        rule.onNodeWithTag("schedule-row-schedule-1").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Status: Enabled").assertExists()
        rule.onNodeWithText("Weekly on Mon, Wed at 09:00 (Europe/Helsinki)").assertExists()
        rule.onNodeWithText("Weekly bump session").assertExists()
        rule.onNodeWithText("Delete files older than two days.").assertExists()
        rule.onNodeWithText(
            "Every time this runs, its instruction is delivered into that session as a new " +
                "turn, whether or not you are looking. It does not create a session of its own.",
        ).assertExists()
        rule.onNodeWithText("Delete task").performScrollTo().assertExists()
    }

    @Test
    fun `delete asks first, names the task, and only then calls back`() {
        val deleted = ArrayList<String>()
        show(
            SchedulePageState.Ready(listOf(entry())),
            onDelete = { deleted += it.id },
        )
        rule.waitForIdle()

        rule.onNodeWithTag("schedule-row-schedule-1").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete task").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Delete this task?").assertExists()
        rule.onNodeWithText(
            "\"Weekly tmp cleanup\" will stop triggering, and this task's saved delivery " +
                "records will be deleted with it. Its session and every message in it remain, " +
                "and queued messages are not withdrawn.",
        ).assertExists()
        // Nothing has been deleted merely by opening the dialog.
        assertEquals(emptyList<String>(), deleted)

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this task?").assertDoesNotExist()
        assertEquals(emptyList<String>(), deleted)

        rule.onNodeWithText("Delete task").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Confirm deletion").performClick()
        rule.waitForIdle()
        assertEquals(listOf("schedule-1"), deleted)
    }

    @Test
    fun `a refusal is said on the sheet instead of looking like a tap that did nothing`() {
        show(
            SchedulePageState.Ready(listOf(entry())),
            actionError = "Could not delete the task: schedule_not_found: Host rejected the call",
        )
        rule.waitForIdle()

        rule.onNodeWithText(
            "Could not delete the task: schedule_not_found: Host rejected the call",
        ).assertExists()
    }

    @Test
    fun `a loaded history is shown with its records and the retention it was cut by`() {
        val history = ScheduleHistoryState.Ready(
            ScheduleHistoryRead.Loaded(
                records = listOf(
                    ScheduleDelivery(
                        scheduledAt = "2026-10-17T06:00:00.000Z",
                        deliveredAt = "2026-10-17T06:03:00.000Z",
                        messageId = "m-1",
                        prompt = "Delete files older than two days.",
                    ),
                ),
                earlierRecordsUnavailable = false,
                earlierRecordsPruned = true,
                retentionDays = 30,
                retentionRecords = 200,
                nextBefore = null,
            ),
        )
        show(
            SchedulePageState.Ready(listOf(entry())),
            historyTaskId = "schedule-1",
            history = history,
        )
        rule.waitForIdle()
        rule.onNodeWithTag("schedule-row-schedule-1").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Due 17 Oct 09:00").performScrollTo().assertExists()
        rule.onNodeWithText("Delivered 17 Oct 09:03").assertExists()
        rule.onNodeWithText("Earlier delivery records have been cleared.").assertExists()
        rule.onNodeWithText(
            "Each task keeps up to 200 delivery records from the last 30 days. " +
                "Clearing records does not stop the task.",
        ).assertExists()
    }

    @Test
    fun `an incomplete catalogue says what it could not read`() {
        show(
            SchedulePageState.Ready(
                entries = listOf(entry()),
                droppedRows = 1,
                unknownStatusRows = 0,
                unknownKindRows = 2,
            ),
        )
        rule.waitForIdle()

        rule.onNodeWithText(
            "This list is incomplete: 1 row named no task id or no session and cannot be " +
                "listed; 2 tasks carry a rule kind this build does not know, so their timing " +
                "is not rendered.",
        ).assertExists()
    }
}
