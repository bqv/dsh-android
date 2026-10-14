package uk.xa0.dsh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.net.DoorFault
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The About page's LAN-door note, drawn from the two faults the resolver can
 * publish.
 *
 * What this pins is that the reason is *visible at all*, and that the two faults
 * do not read as the same thing: a reader on the public route with the LAN door
 * answering is looking at a decision the app made, and the question worth
 * answering is whether that door comes back by itself. Which fault the resolver
 * records, and when a resume lifts the hold, is `EndpointResolverTest`'s subject;
 * that `DshClient` maps a transport failure and a wrong answer onto the two kinds
 * is not observable here — it needs a live host — so that half rests on
 * inspection of `onRouteFailure` and its two call sites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class AboutLanDoorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun about(fault: DoorFault?) {
        rule.setContent {
            DshTheme {
                AboutContent(
                    baseUrl = "https://host.example/",
                    username = "tony",
                    socketConnected = true,
                    onSignOut = {},
                    onDismiss = {},
                    // The public door in use, with a LAN door set aside: the shape
                    // the reader is meant to find an explanation for.
                    activeUrl = "https://host.example/",
                    activeRouteLan = false,
                    lanDoorFault = fault,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a transport failure says the LAN door comes back after a pause`() {
        about(DoorFault.Transport)
        rule.onNodeWithText(ABOUT_LAN_DOOR_TRANSPORT).assertExists()
    }

    @Test
    fun `a wrong answer says only the network brings it back`() {
        about(DoorFault.WrongAnswer)
        rule.onNodeWithText(ABOUT_LAN_DOOR_WRONG_ANSWER).assertExists()
    }

    @Test
    fun `nothing is said when no LAN door has been set aside`() {
        // Omitted rather than drawn as a reassuring sentence: an app that has
        // nothing to explain saying so would be noise on the row a reader checks
        // when something *is* wrong.
        about(null)
        rule.onNodeWithText(ABOUT_LAN_DOOR_TRANSPORT).assertDoesNotExist()
        rule.onNodeWithText(ABOUT_LAN_DOOR_WRONG_ANSWER).assertDoesNotExist()
    }
}
