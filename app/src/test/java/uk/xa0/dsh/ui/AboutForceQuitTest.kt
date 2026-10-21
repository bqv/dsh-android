package uk.xa0.dsh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.CONNECTION_RESTART_ACTION
import uk.xa0.dsh.model.FORCE_QUIT_ACTION
import uk.xa0.dsh.model.FORCE_QUIT_BODY
import uk.xa0.dsh.model.FORCE_QUIT_CANCEL
import uk.xa0.dsh.model.FORCE_QUIT_CONFIRM
import uk.xa0.dsh.model.FORCE_QUIT_NOTE
import uk.xa0.dsh.model.FORCE_QUIT_TITLE
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Force quit's seat on About, and the confirmation's words.
 *
 * The action sits beside Restart connection, so the page must offer three verbs
 * that do not read alike — Close, Restart connection, Force quit — and the
 * confirmation is where the difference is stated. The page is a long scrolled
 * document and the control is below the harness's 470 dp display, where
 * `performClick` would inject a touch that lands on nothing, so the dialog is
 * driven directly; that is also the part a reader actually decides on.
 *
 * It cannot press the real kill: the callback here is a captured flag, and
 * `Process.killProcess` needs a device. Nor can it prove the action is reached
 * from About by a real tap through the scrolled sheet; only `assertExists` does
 * that.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized surface, not the harness's 320x470 default: the confirmation's
// body is long enough that on the small default its buttons can sit below the
// window, where `performClick` injects a touch that lands on nothing.
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
class AboutForceQuitTest {

    @get:Rule
    val rule = createComposeRule()

    private fun about(onForceQuit: (() -> Unit)?) {
        rule.setContent {
            DshTheme {
                AboutContent(
                    baseUrl = "https://host.example/",
                    username = "tony",
                    socketConnected = true,
                    onSignOut = {},
                    onDismiss = {},
                    activeUrl = "https://host.example/",
                    activeRouteLan = false,
                    onRestartConnection = {},
                    onForceQuit = onForceQuit,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the page offers all three verbs, each with its own label`() {
        about(onForceQuit = {})

        rule.onNodeWithText("Close").assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_ACTION).assertExists()
        rule.onNodeWithTag(FORCE_QUIT_TAG).assertExists()
        rule.onNodeWithText(FORCE_QUIT_ACTION).assertExists()
        rule.onNodeWithText(FORCE_QUIT_NOTE).assertExists()
    }

    @Test
    fun `a caller with no kill to offer draws neither action nor note`() {
        about(onForceQuit = null)

        rule.onNodeWithTag(FORCE_QUIT_TAG).assertDoesNotExist()
        rule.onNodeWithText(FORCE_QUIT_NOTE).assertDoesNotExist()
        // The restart is a different action and stays offered.
        rule.onNodeWithText(CONNECTION_RESTART_ACTION).assertExists()
    }

    @Test
    fun `the confirmation names the process and offers a way back`() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            DshTheme {
                ForceQuitDialog(
                    onDismiss = { dismissed = true },
                    onConfirm = { confirmed = true },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(FORCE_QUIT_TITLE).assertExists()
        rule.onNodeWithText(FORCE_QUIT_BODY).assertExists()
        rule.onNodeWithText(FORCE_QUIT_CONFIRM).assertExists()
        rule.onNodeWithText(FORCE_QUIT_CANCEL).assertExists()

        rule.onNodeWithText(FORCE_QUIT_CANCEL).performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }

    @Test
    fun `the second tap is the decision`() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            DshTheme {
                ForceQuitDialog(
                    onDismiss = { dismissed = true },
                    onConfirm = { confirmed = true },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(FORCE_QUIT_CONFIRM).performClick()

        assertTrue(confirmed)
        assertFalse(dismissed)
    }
}
