package uk.xa0.dsh.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.model.sessionListErrorNote
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The drawer's line about a roster it could not refresh.
 *
 * The report this answers is a drawer that showed old data and never said so. The
 * fix keeps the previous rows — a failed refresh is not evidence the sessions are
 * gone — and puts the failure where the rows are, so "yesterday's list" cannot be
 * read as "the host's list". What is pinned here is that the line is drawn with
 * the app's own reason, that the rows survive beside it, and that a healthy
 * drawer says nothing.
 *
 * It cannot prove a pull failed, or that the ViewModel kept the rows: the JVM
 * harness has no host. That half is [uk.xa0.dsh.DshViewModel]'s `applyRoster`,
 * reachable only by inspection on this box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionsDrawerStaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val session = SessionItem(
        id = "session-1",
        title = "session one",
        cwd = "/home/tony/work",
        updatedAt = 1_000L,
        running = false,
        isSubagent = false,
    )

    private fun drawer(sessionsError: String?) {
        rule.setContent {
            DshTheme {
                SessionsDrawer(
                    sessions = listOf(session),
                    workspaces = emptyList(),
                    workspacesPhase = WorkspaceListPhase.Ready,
                    archivedSessionIds = emptySet(),
                    completedSessionIds = emptySet(),
                    currentId = null,
                    drawerOpen = true,
                    loading = false,
                    sessionsError = sessionsError,
                    onSelect = {},
                    onNew = {},
                    onRefresh = {},
                    onSettings = {},
                    onAbout = {},
                    grouping = DrawerGrouping.FLAT,
                    orderByUpdated = false,
                    showArchived = true,
                    collapsedSections = emptySet(),
                    onGrouping = {},
                    onOrderByUpdated = {},
                    onShowArchived = {},
                    onCollapsedSections = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a failed refresh says why, and keeps the rows`() {
        val note = sessionListErrorNote("HTTP 504: the host did not answer")
        drawer(note)

        rule.onNodeWithTag(SESSIONS_STALE_TAG).assertExists()
        rule.onNodeWithTag(SESSIONS_STALE_TAG).assertTextEquals(note)
        // The list is deliberately not replaced by the error: an error beside the
        // sessions it is about is easier to act on than a blank panel.
        rule.onNodeWithText("session one").assertExists()
    }

    @Test
    fun `a current list says nothing about being stale`() {
        drawer(sessionsError = null)

        rule.onNodeWithTag(SESSIONS_STALE_TAG).assertDoesNotExist()
        rule.onNodeWithText("session one").assertExists()
    }
}
