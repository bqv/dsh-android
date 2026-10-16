package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.DownloadResult
import uk.xa0.dsh.model.FileDownloader
import uk.xa0.dsh.model.FilePreview
import uk.xa0.dsh.model.FileTouch
import uk.xa0.dsh.model.SessionFile
import uk.xa0.dsh.model.SessionFiles
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.FilesPanel
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * What the Files preview draws for a document, and the way back to its source.
 *
 * The panel used to draw one thing — numbered source — so every file was the same
 * reading. It now draws a Markdown document as a *document*, and that is a claim about
 * the content, so the properties here are about the claim being visible and reversible:
 * a rendered file says so by having a Source control, the source is one tap away, a code
 * file's lines are drawn exactly as they arrived however they are coloured, and a window
 * the host cut short is announced *in the rendering* rather than only in the source.
 *
 * ## What these can and cannot show
 *
 * They can show which nodes were composed and what they say: whether the heading exists
 * as its own text or the raw `# ` line does, whether the language chip is drawn, and
 * whether the truncation sentence is present in the rendered view. That is exactly the
 * pair of properties this change turns on.
 *
 * They cannot show that the colouring is *right*, or that it is applied at all: a span
 * colour is not a semantics property, and Robolectric lays text out with stub metrics.
 * The content half — that the highlighter never changes a character — is pinned by
 * `markdown/MarkdownHighlightTest` on the JVM, and the colours come from the one theme
 * mapping in `MarkdownView`. Nothing here says the result *reads* well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class FilesPanelPreviewTest {

    @get:Rule
    val rule = createComposeRule()

    private fun touched(path: String) = SessionFile(
        path = path,
        touches = listOf(FileTouch.PRESENTED),
        reads = 0,
        writes = 0,
        edits = 0,
        presents = 1,
        firstSeq = 1,
        lastSeq = 1,
    )

    private fun show(
        path: String,
        text: String,
        preview: FilePreview? = null,
        downloader: FileDownloader? = null,
    ) {
        val held = preview ?: SessionFiles.previewOfText(path, text)
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                    FilesPanel(
                        files = listOf(touched(path)),
                        root = "/w",
                        cached = mapOf(path to held),
                        downloader = downloader,
                        initialPath = path,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a markdown file opens as its rendering, with the source one tap away`() {
        show("/w/out/REPORT.md", "# Findings\n\n- one\n- two\n")

        // The document: the heading's text, and the chip saying what it was taken for.
        rule.onNodeWithText("Findings").assertExists()
        rule.onNodeWithText("md").assertExists()
        // The source is not what is on screen, which is the whole point of a rendering.
        rule.onNodeWithText("# Findings").assertDoesNotExist()
        rule.onNodeWithText("Source").assertExists()

        rule.onNodeWithText("Source").performClick()
        rule.waitForIdle()

        // And the raw text is reachable, unchanged, one tap later.
        rule.onNodeWithText("# Findings").assertExists()
        rule.onNodeWithText("Findings").assertDoesNotExist()
        rule.onNodeWithText("Rendered").assertExists()
    }

    /**
     * A code file is not rendered and has no Source toggle: there is nothing to render.
     * Its line is drawn exactly as it arrived even though it is coloured, which is the
     * one property of highlighting a reader would notice being wrong.
     */
    @Test
    fun `a code file's lines are drawn exactly as they arrived`() {
        show("/w/src/App.kt", "fun main() {\n    val answer = 42 // the answer\n}\n")

        rule.onNodeWithText("fun main() {").assertExists()
        rule.onNodeWithText("    val answer = 42 // the answer").assertExists()
        rule.onNodeWithText("kotlin").assertExists()
        rule.onNodeWithText("Source").assertDoesNotExist()
    }

    /** A file this app cannot name as a language makes no claim: no chip at all. */
    @Test
    fun `an unknown kind is drawn without a language claim`() {
        show("/w/Makefile", "all:\n\tcc -o out main.c\n")

        rule.onNodeWithText("all:").assertExists()
        rule.onNodeWithText("Source").assertDoesNotExist()
    }

    /**
     * A rendered window is a rendering *of the window*. Without this line the reader
     * would take the first two thousand lines for the whole document.
     */
    @Test
    fun `a rendered window says the rest of the document is not shown`() {
        val path = "/w/README.md"
        val text = "# Title\n\nBody\n"
        show(
            path,
            text,
            preview = FilePreview.Ready(text = text, lines = 3, truncated = true, totalLines = 5000),
        )

        rule.onNodeWithText("Title").assertExists()
        rule.onNodeWithText("Showing 3 of 5000 lines · the rest of this document is not shown").assertExists()
    }

    /**
     * The Download control is drawn when, and only when, something can answer for it. A
     * control that cannot work is the dead-affordance defect this app keeps meeting, so
     * its absence is the assertion that matters most here.
     *
     * The two halves are two tests rather than two `setContent` calls: a compose rule
     * composes its content once per test, which is also why every other test here sets up
     * exactly one panel.
     */
    @Test
    fun `download is not offered without a downloader`() {
        show("/w/out/REPORT.md", "# Findings\n", downloader = null)
        rule.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun `download is offered when a downloader is wired`() {
        show(
            "/w/out/REPORT.md",
            "# Findings\n",
            downloader = FileDownloader { _, _ -> DownloadResult.Refused("not used here") },
        )
        rule.onNodeWithText("Download").assertExists()
    }
}
