package uk.xa0.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * What the Back gate says, and what its one destructive button does.
 *
 * The label is the subject: this control only finishes the Activity, while the
 * process, the event socket and the connection service keep running, so a dialog
 * that offered "Exit" claimed a shutdown it never performed. The test pins the
 * three claims — the title, the body that says the connection stays open, and the
 * confirm button reading Close — and that pressing it calls the one callback.
 *
 * It cannot prove the connection survives, because the callback here is a flag
 * rather than `finish()` and the harness has no client or service; that half is a
 * property of `MainActivity` passing `finish()` and of nothing else in the close
 * path touching `DshApplication`, which is inspected rather than executed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class BackGateTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `back asks to close, and the body says the connection stays open`() {
        var closed = false
        rule.setContent {
            DshTheme {
                BackGate(onClose = { closed = true })
            }
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText(CLOSE_DSH_TITLE).assertExists()
        rule.onNodeWithText(CLOSE_DSH_BODY).assertExists()
        rule.onNodeWithText(CLOSE_DSH_CONFIRM).assertExists()
        rule.onNodeWithText(CLOSE_DSH_STAY).assertExists()
        assertFalse("the dialog must not close anything by itself", closed)

        rule.onNodeWithText(CLOSE_DSH_CONFIRM).performClick()
        assertTrue("the confirm button is the one path out", closed)
    }

    @Test
    fun `staying leaves the screen open`() {
        var closed = false
        rule.setContent {
            DshTheme {
                BackGate(onClose = { closed = true })
            }
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText(CLOSE_DSH_STAY).performClick()
        assertFalse(closed)
        rule.onNodeWithText(CLOSE_DSH_TITLE).assertDoesNotExist()
    }

    @Test
    fun `no user-facing word still says Exit or quit`() {
        // The rename is the whole of change one: a label that offered a shutdown
        // the control does not perform. Checked across the copy, not just the
        // button, because the title and the body are read together.
        val copy = listOf(CLOSE_DSH_TITLE, CLOSE_DSH_BODY, CLOSE_DSH_CONFIRM, CLOSE_DSH_STAY)
        copy.forEach { line ->
            assertFalse("copy claims an exit: $line", line.contains("Exit"))
            assertFalse("copy claims a quit: $line", line.contains("Quit", ignoreCase = true))
        }
    }
}
