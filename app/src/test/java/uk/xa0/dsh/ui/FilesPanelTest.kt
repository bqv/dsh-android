package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
 * this panel as `initialPath` (`ui/ChatScreen.kt`). The panel used to keep that
 * path only when the list already held it under the same spelling, so a presented
 * file with no row was a tap that did nothing: the panel came up on its list, or
 * on `No files yet`, and the file was never previewed. Two changes close that: the
 * derivation now records a `present` declaration as a row
 * (`model/SessionFilesTest` holds that half), and the panel opens a focus the list
 * does not hold rather than discarding it.
 *
 * ## What these can and cannot show
 *
 * They can show which node was composed. A selected path composes the preview
 * pane — its back control, and, with nothing cached and no loader, its own
 * sentence for a file with nothing read into it; a path that selected nothing
 * leaves the list up. Against the code before either change the first test met
 * the `No files yet` empty state and the third met the list, so both can go red.
 *
 * They cannot show that the host can read the path back (`workspaceFiles/read` is
 * an RPC, and this harness has no host), nor that `ChatScreen` passes the card's
 * path rather than some other string: that wiring is read from source, not
 * exercised here. As everywhere in this suite, text metrics are stubs, so nothing
 * here says the row *reads* well — only which nodes exist. The panel's second
 * guard — a row the list held and then dropped is deselected — is not exercised
 * at all: it needs a transcript reload mid-selection, which this test cannot
 * stage.
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

    /**
     * The panel under the app's own theme, in a box the size of a phone. 400dp is
     * below the panel's 720dp two-pane cut, so the list and the preview swap
     * rather than sitting side by side — which is what makes "the preview is up"
     * and "the list is up" exclusive, and therefore assertable.
     *
     * A plain function around `setContent`, as `AboutHostVersionTest.about` and
     * `RealRowExpansionTest.transcript` are: the theme and the real composables
     * sit directly in the rule's own content lambda.
     */
    private fun show(files: List<SessionFile>, focus: String?) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                    FilesPanel(
                        files = files,
                        root = "/w",
                        initialPath = focus,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The reported symptom: the card's path opens the file it names. */
    @Test
    fun `a presented file's own path opens its preview`() {
        show(SessionFiles.derive(entries), presented)

        // The preview pane, not the list: the back control is drawn only there,
        // and with no cached read and no loader the pane says so in its own words.
        rule.onNodeWithContentDescription("Back to files", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Nothing read from this file in this session.").assertExists()
    }

    /** The other reported symptom: the file is a row, under the card's spelling. */
    @Test
    fun `the list holds the presented file under the card's name`() {
        show(SessionFiles.derive(entries), null)

        rule.onNodeWithText("REPORT.md").assertExists()
        rule.onNodeWithText("Presented").assertExists()
    }

    /**
     * The belt-and-braces path, which is what makes the tap unable to be silent
     * however the list was derived: a focus the derivation does not hold is opened
     * too — and here the derivation holds nothing at all, so this covers the
     * panel's empty-state short-circuit as well, which used to swallow the focus
     * before any preview could be composed.
     */
    @Test
    fun `a focus the list cannot hold still opens its preview`() {
        show(emptyList(), presented)

        rule.onNodeWithContentDescription("Back to files", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Nothing read from this file in this session.").assertExists()
        rule.onNodeWithText("No files yet").assertDoesNotExist()
    }
}
