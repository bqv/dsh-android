package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.ExplorerState
import uk.xa0.dsh.model.SessionFiles
import uk.xa0.dsh.model.WorkspaceEntry
import uk.xa0.dsh.model.WorkspaceEntryKind
import uk.xa0.dsh.model.WorkspaceListing
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.ExplorerWiring
import uk.xa0.dsh.ui.components.FilesPanel
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The Files panel's explorer half: the workspace tree, with the session's own
 * touches drawn on it, and the derived list still one tap away.
 *
 * The user's complaint is the subject: a panel derived only from the session's
 * activity held a handful of rows on a real session and read as broken rather
 * than scoped. So these tests open the panel on the listing — a directory, a file
 * the session read, a file it never touched, and a hidden file — and check which
 * rows exist, which of them carry a mark, and that the session list still has its
 * own mode.
 *
 * ## What these can and cannot show
 *
 * They show which nodes the panel composed and what a tap asked for. They cannot
 * show that the host lists anything: `workspaceFiles/list` is an RPC and this
 * harness has no host, so the listing here is a literal. They cannot show that the
 * merge of two spellings onto one row *reads* well either — as everywhere in this
 * suite the text metrics are stubs, so nothing here says a row wraps or fits.
 * That a mark is absent from an untouched row is asserted as a node count, which
 * is the part that can be measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class FilesPanelExplorerTest {

    @get:Rule
    val rule = createComposeRule()

    private val cwd = "/w"

    private val listing = WorkspaceListing(
        path = "",
        entries = listOf(
            WorkspaceEntry("out", WorkspaceEntryKind.DIRECTORY),
            WorkspaceEntry("REPORT.md", WorkspaceEntryKind.FILE, size = 2048L),
            WorkspaceEntry("notes.txt", WorkspaceEntryKind.FILE, size = 10L),
            WorkspaceEntry(".secret", WorkspaceEntryKind.FILE),
        ),
        truncated = false,
    )

    /** One successful read of `/w/REPORT.md`, and nothing else. */
    private val entries = listOf(
        ChatEntry.ToolCall(
            seq = 1,
            callId = "call-1",
            name = "read",
            arguments = """{"file_path":"$cwd/REPORT.md"}""",
            result = "ok",
            isError = false,
            time = 0L,
            turn = 1,
        ),
    )

    @Composable
    private fun panel(onList: (String) -> Unit = {}) {
        // Single-pane: 400dp is below the 720dp two-pane cut.
        Box(Modifier.size(width = 400.dp, height = 800.dp)) {
            FilesPanel(
                files = SessionFiles.derive(entries),
                root = cwd,
                explorer = ExplorerWiring(
                    state = ExplorerState.Ready(listing),
                    onList = onList,
                    onReload = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** The reported symptom: the panel is the workspace, not the session's dozen rows. */
    @Test
    fun `the panel opens on the listing and keeps the session list one tap away`() {
        rule.setContent { DshTheme { panel() } }

        rule.onNodeWithText("Browse").assertExists()
        rule.onNodeWithText("This session").assertExists()
        // A directory, and a file the session never touched: both are rows.
        rule.onNodeWithText("out").assertExists()
        rule.onNodeWithText("notes.txt").assertExists()
        // And the convention is stated rather than left to be inferred.
        rule.onNodeWithText("A file with no mark has not been touched by this session.")
            .assertExists()

        rule.onNodeWithText("This session").performClick()
        rule.onNodeWithText("REPORT.md").assertExists()
        // The session's own list is *not* the workspace's: the untouched file has
        // no row there, which is what makes the two lists different things.
        rule.onNodeWithText("notes.txt").assertDoesNotExist()
    }

    /**
     * A mark means the session read, wrote, edited or declared that exact path;
     * a row without one is a file it has not touched. The count is the assertion:
     * two files are on screen and exactly one carries the mark.
     */
    @Test
    fun `a mark is drawn only on the file the session touched`() {
        rule.setContent { DshTheme { panel() } }

        rule.onNodeWithText("REPORT.md").assertExists()
        rule.onNodeWithText("notes.txt").assertExists()
        rule.onAllNodesWithText("Read").assertCountEquals(1)
    }

    @Test
    fun `a hidden file is behind the toggle`() {
        rule.setContent { DshTheme { panel() } }

        rule.onNodeWithText(".secret").assertDoesNotExist()

        rule.onNodeWithText("Show hidden files").performClick()

        rule.onNodeWithText(".secret").assertExists()
    }

    /** A directory row descends by asking for the child's workspace path. */
    @Test
    fun `descending asks for the child's workspace path`() {
        val asked = mutableListOf<String>()
        rule.setContent { DshTheme { panel(onList = { asked += it }) } }

        rule.onNodeWithText("out").performClick()

        assertEquals(listOf("out"), asked)
    }
}
