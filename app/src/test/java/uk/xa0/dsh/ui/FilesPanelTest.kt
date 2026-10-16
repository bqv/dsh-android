package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.SessionFile
import uk.xa0.dsh.model.SessionFiles
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.FilesPanel
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * A deliverable card's tap lands on the presented file.
 *
 * The card's `onOpen` hands its own path to the Files surface, which passes it to
 * this panel as `initialPath` (`ui/ChatScreen.kt`). The panel selects a path only
 * when the list holds that exact spelling, so a presented file with no row was a
 * tap that did nothing: the panel opened on the list, or on `No files yet`, and
 * the file was never previewed. These tests are the panel half of the pair; the
 * other half is `model/SessionFilesTest`, which proves the declaration is a row.
 *
 * ## What these can and cannot show
 *
 * They can show which node was composed: with a path the list holds, the *preview*
 * pane is up (its back control, and its own sentence for a file with nothing read
 * into it); with a path it does not hold, the list is still up and no preview was
 * composed at all. Against the code before the fix, `derive` returned nothing for a
 * present-only transcript, so the first test met the `No files yet` empty state and
 * failed. The second test pins the panel's side of the contract — it selects from
 * the list and will not invent a row — which is why the fix is in the derivation
 * rather than in the panel.
 *
 * They cannot show that the host can read the path back (`workspaceFiles/read` is
 * an RPC, and this harness has no host), nor that `ChatScreen` passes the card's
 * path rather than some other string: that wiring is read from source, not
 * exercised here. As everywhere in this suite, text metrics are stubs, so nothing
 * here says the row *reads* well — only which nodes exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class FilesPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val presented = "/w/out/REPORT.md"

    /** One successful `present`, and nothing else: the file was never read or written. */
    private val entries = listOf(
        ChatEntry.ToolCall(
            seq = 1,
            callId = "call-1",
            name = "present",
            arguments = """{"files":[{"path":"$presented","description":"The report"}]}""",
            result = "Presented $presented",
            isError = false,
            time = 0L,
            turn = 1,
        ),
    )

    @Composable
    private fun panel(files: List<SessionFile>, focus: String?) {
        // Single-pane: 400dp is below the panel's 720dp two-pane cut, so the list
        // and the preview swap rather than sitting side by side.
        Box(Modifier.size(width = 400.dp, height = 800.dp)) {
            FilesPanel(
                files = files,
                root = "/w",
                initialPath = focus,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** The reported symptom: the card's path opens the file it names. */
    @Test
    fun `a presented file's own path opens its preview`() {
        rule.setContent { DshTheme { panel(SessionFiles.derive(entries), presented) } }

        // The preview pane, not the list: the back control is drawn only there,
        // and with no cached read and no loader the pane says so in its own words.
        // The unmerged tree, because the control is an `Icon` inside the row's
        // own clickable node and the merged node is not what is being asserted.
        rule.onNodeWithContentDescription("Back to files", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Nothing read from this file in this session.").assertExists()
    }

    /** The other reported symptom: the file is a row, under the card's spelling. */
    @Test
    fun `the list holds the presented file under the card's name`() {
        rule.setContent { DshTheme { panel(SessionFiles.derive(entries), null) } }

        rule.onNodeWithText("REPORT.md").assertExists()
        rule.onNodeWithText("Presented").assertExists()
    }

    /**
     * The panel's own contract, which is why the fix had to be in the derivation:
     * a focus path with no row leaves the list up and composes no preview. Handing
     * the card's path somewhere else would not have opened it.
     */
    @Test
    fun `a focus path the list does not hold leaves the list up`() {
        rule.setContent { DshTheme { panel(SessionFiles.derive(entries), "/w/out/elsewhere.md") } }

        rule.onNodeWithText("REPORT.md").assertExists()
        rule.onNodeWithContentDescription("Back to files", useUnmergedTree = true).assertDoesNotExist()
    }
}
