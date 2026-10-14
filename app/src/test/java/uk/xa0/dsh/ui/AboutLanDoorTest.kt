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

    private fun about(
        fault: DoorFault?,
        lanRouteConfigured: Boolean = true,
        onRetryLanRoute: (() -> Unit)? = null,
    ) {
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
                    lanRouteConfigured = lanRouteConfigured,
                    onRetryLanRoute = onRetryLanRoute,
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

    @Test
    fun `a missing LAN address is named as a setting, not a failure`() {
        // The two facts a public route can mean, and the page must not conflate
        // them: a door that answered wrongly, and no door to try at all. The second
        // has no fault to explain, so without this note it would read as an app that
        // simply prefers the tunnel.
        about(null, lanRouteConfigured = false)
        rule.onNodeWithText(ABOUT_LAN_DOOR_UNCONFIGURED).assertExists()
        rule.onNodeWithText(ABOUT_LAN_DOOR_TRANSPORT).assertDoesNotExist()
    }

    @Test
    fun `a wrong answer offers the one action that can revisit it`() {
        // The verdict no automatic path may lift, and the reader's own "try again":
        // a fault the reader can never revisit is indistinguishable from an app that
        // has stopped trying.
        about(DoorFault.WrongAnswer, onRetryLanRoute = {})
        rule.onNodeWithText(ABOUT_LAN_DOOR_RETRY).assertExists()
    }

    @Test
    fun `no retry is offered when there is no fault to revisit`() {
        // A missing address needs a setting rather than a retry, and the field that
        // takes one is on the same page.
        about(null, lanRouteConfigured = false, onRetryLanRoute = {})
        rule.onNodeWithText(ABOUT_LAN_DOOR_RETRY).assertDoesNotExist()
    }
}
