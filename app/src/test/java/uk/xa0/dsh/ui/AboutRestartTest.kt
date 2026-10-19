package uk.xa0.dsh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.CONNECTION_RESTART_ACTION
import uk.xa0.dsh.model.CONNECTION_RESTART_BUSY_NOTE
import uk.xa0.dsh.model.CONNECTION_RESTART_DONE_LAN
import uk.xa0.dsh.model.CONNECTION_RESTART_DONE_PUBLIC
import uk.xa0.dsh.model.CONNECTION_RESTART_NOTE
import uk.xa0.dsh.model.CONNECTION_RESTART_RUNNING
import uk.xa0.dsh.model.ConnectionRestart
import uk.xa0.dsh.model.connectionRestartFailure
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The About page's Connection section and its "Restart connection" action.
 *
 * What this pins is the surface the reader actually gets: the action is offered
 * where the route facts are, it says it is busy while it runs, and a finished
 * restart says which door the fresh dial used or why it failed. The distinction
 * between success and failure is a different sentence in a different tint; the
 * JVM harness cannot measure colour, so the text is what is asserted here.
 *
 * It cannot prove the restart rebuilt anything: the callback is a captured flag
 * and there is no client, socket or host under Robolectric. Nor does it click the
 * action — the page is a long scrolled document and the action sits below the
 * harness's 470dp display, where `performClick` would inject a touch that misses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class AboutRestartTest {

    @get:Rule
    val rule = createComposeRule()

    private fun about(
        restart: ConnectionRestart = ConnectionRestart.Idle,
        onRestart: (() -> Unit)? = {},
    ) {
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
                    onRestartConnection = onRestart,
                    connectionRestart = restart,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the action is offered beside the connection facts, with what it does`() {
        about()
        rule.onNodeWithTag(RESTART_CONNECTION_TAG).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_ACTION).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_NOTE).assertExists()
    }

    @Test
    fun `a restart in progress says so and offers no second tap`() {
        about(ConnectionRestart.Running)
        rule.onNodeWithText(CONNECTION_RESTART_RUNNING).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_BUSY_NOTE).assertExists()
        // The control is the same node, relabelled rather than duplicated: a second
        // "Restart connection" next to a busy line would be a tap that does nothing.
        rule.onNodeWithText(CONNECTION_RESTART_ACTION).assertDoesNotExist()
    }

    @Test
    fun `success reports the door that was chosen`() {
        about(ConnectionRestart.Succeeded(lan = true))
        rule.onNodeWithText(CONNECTION_RESTART_DONE_LAN).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_DONE_PUBLIC).assertDoesNotExist()
    }

    @Test
    fun `success over the public door is not reported as the LAN door`() {
        about(ConnectionRestart.Succeeded(lan = false))
        rule.onNodeWithText(CONNECTION_RESTART_DONE_PUBLIC).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_DONE_LAN).assertDoesNotExist()
    }

    @Test
    fun `a failed restart carries the reason`() {
        about(ConnectionRestart.Failed("Could not reach the host"))
        rule.onNodeWithText(connectionRestartFailure("Could not reach the host")).assertExists()
        rule.onNodeWithText(CONNECTION_RESTART_DONE_LAN).assertDoesNotExist()
        rule.onNodeWithText(CONNECTION_RESTART_DONE_PUBLIC).assertDoesNotExist()
    }

    @Test
    fun `a caller with no restart to offer draws neither action nor note`() {
        // About is also rendered by callers that have no view model behind it; the
        // section must not promise an action it cannot perform.
        about(onRestart = null)
        rule.onNodeWithTag(RESTART_CONNECTION_TAG).assertDoesNotExist()
        rule.onNodeWithText(CONNECTION_RESTART_ACTION).assertDoesNotExist()
        rule.onNodeWithText(CONNECTION_RESTART_NOTE).assertDoesNotExist()
    }
}
