package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.search.SessionSearchCopy
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The drawer's own search box, end to end: the clear affordance is wired to the
 * query the list is filtered by, so clearing it restores the unfiltered list
 * rather than only emptying the text.
 *
 * That is the half `SearchClearTest` cannot see. There the field's value is the
 * test's own state, so "the results come back" is an assumption; here the count
 * row above the list is the app's own derivation from `query`, and it has to move
 * from 1 back to 3.
 *
 * The drawer is driven flat (`grouping = DrawerGrouping.FLAT`) with no Workspaces, which
 * is the shortest path from the box to the count: the grouped list would interpose
 * section headers and the host registry's own two empty states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionsDrawerSearchTest {

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

    private val sessions = listOf(
        session("s1", "Clear the search box"),
        session("s2", "Widget age cost"),
        session("s3", "Sidebar workspaces"),
    )

    @Composable
    private fun drawer() {
        SessionsDrawer(
            sessions = sessions,
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
    fun `clearing the drawer's search restores the unfiltered list`() {
        rule.setContent { DshTheme { drawer() } }
        rule.waitForIdle()

        rule.onNodeWithText("3 sessions").assertExists()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()

        rule.onNode(hasSetTextAction()).performTextInput("widget")
        rule.waitForIdle()

        // One title carries the query; the other two are filtered out of the count.
        rule.onNodeWithText("1 sessions").assertExists()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertExists()

        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("3 sessions").assertExists()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()
    }
}
