package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import uk.xa0.dsh.model.PresentedFile
import uk.xa0.dsh.model.TurnDeliverables
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.DeliverableRow
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Downloading a presented file from its delivery card.
 *
 * The card is where a reader is handed a file, so it is where taking it should be
 * possible — and the property that matters is not that the control draws but that a
 * download that fails *says so*. This app's recurring defect is a control that is
 * answered by nothing: a tap that matches no row, a disabled edit behind a dead frame. A
 * refusal here is a sentence on the card and a line in the log, and the first of those is
 * what these can see.
 *
 * ## What these can and cannot show
 *
 * They can show that the control exists only when a downloader is wired (`null` is how a
 * surface without a session says "not here"), and that a refused download puts the reason
 * on the card and turns the control into `Retry`.
 *
 * They cannot show anything past the refusal. A download that *succeeds* opens the
 * system's save dialog, which is an Activity result this harness has no host for, and
 * nothing here writes a byte to a content URI. So the picker, the write, `Saved`, Cancel
 * during a real transfer, and the whole host-facing paging loop are unverified by these —
 * the paging rule is tested against a fake reader in `model/FileDownloadTest`, and the
 * rest is device work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class DeliverableDownloadTest {

    @get:Rule
    val rule = createComposeRule()

    private val path = "/w/out/REPORT.md"
    private val delivered = TurnDeliverables(
        presented = listOf(PresentedFile(path, "The report", seq = 1, index = 0)),
    )

    private fun show(downloader: FileDownloader?) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                    DeliverableRow(delivered = delivered, downloader = downloader)
                }
            }
        }
        rule.waitForIdle()
    }

    /** No downloader is a surface with no session behind it: nothing is drawn at all. */
    @Test
    fun `no control is drawn when there is nothing to read with`() {
        show(null)
        rule.onNodeWithText("Download").assertDoesNotExist()
        // The card itself is still there, opened the same way it always was.
        rule.onNodeWithText("Open").assertExists()
    }

    /**
     * The refusal is the whole point: one word on the control, and the reason on the
     * card's own status line, where a reader is already looking.
     */
    @Test
    fun `a refused download says why, on the card, and offers a retry`() {
        val reason = "REPORT.md is 40.0 MB, above the 32.0 MB download limit."
        show(FileDownloader { _, _ -> DownloadResult.Refused(reason) })

        rule.onNodeWithText("Download").assertExists()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(reason).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(reason).assertExists()
        rule.onNodeWithText("Retry").assertExists()
    }
}
