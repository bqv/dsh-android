package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The drawer's Automation tasks entry: it exists, it says how many tasks will fire again,
 * and a tap reaches the caller that opens the sheet.
 *
 * The count is the point of more than one of these assertions. An inactive task is still
 * listed on the sheet, but it is not running, and a drawer badge that counted it would
 * say otherwise — which is the same rule that keeps an ended task out of
 * `SessionItem.scheduledTaskCount`.
 *
 * These cannot show the sheet opening: `ChatScreen` owns that state and is not composed
 * here. They pin the entry and the number it is handed, which is the half that lives in
 * the drawer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
class SessionsDrawerAutomationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun session(id: String, title: String) = SessionItem(
        id = id,
        title = title,
        cwd = "/home/user/var/work/dsh-android",
        updatedAt = 1_000L,
        running = false,
        isSubagent = false,
    )

    @Composable
    private fun drawer(
        activeScheduleTotal: Int,
        onAutomations: (() -> Unit)?,
    ) {
        SessionsDrawer(
            sessions = listOf(session("s1", "Widget age cost")),
            workspaces = emptyList(),
            workspacesPhase = WorkspaceListPhase.Ready,
            archivedSessionIds = emptySet(),
            completedSessionIds = emptySet(),
            currentId = null,
            drawerOpen = true,
            loading = false,
            onSelect = {},
            onNew = {},
            onRefresh = {},
            onSettings = {},
            onAbout = {},
            onAutomations = onAutomations,
            activeScheduleTotal = activeScheduleTotal,
            grouping = DrawerGrouping.FLAT,
            orderByUpdated = true,
            showArchived = false,
            collapsedSections = emptySet(),
            onGrouping = {},
            onOrderByUpdated = {},
            onShowArchived = {},
            onCollapsedSections = {},
        )
    }

    @Test
    fun `the entry says how many tasks are enabled and a tap opens it`() {
        var opened = 0
        rule.setContent {
            DshTheme { drawer(activeScheduleTotal = 2, onAutomations = { opened++ }) }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Automation tasks").assertExists()
        rule.onNodeWithText("2 active").assertExists()

        rule.onNodeWithTag("drawer-automation-tasks").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `zero enabled tasks draws no count but keeps the entry`() {
        rule.setContent {
            DshTheme { drawer(activeScheduleTotal = 0, onAutomations = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("0 active").assertDoesNotExist()
        // The entry is still there with nothing enabled: the list is worth opening.
        rule.onNodeWithText("Automation tasks").assertExists()
    }

    @Test
    fun `no entry is drawn when the caller has nowhere to send it`() {
        rule.setContent {
            DshTheme { drawer(activeScheduleTotal = 3, onAutomations = null) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Automation tasks").assertDoesNotExist()
    }
}
