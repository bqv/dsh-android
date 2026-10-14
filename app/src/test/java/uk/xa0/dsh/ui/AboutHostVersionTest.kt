package uk.xa0.dsh.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.HostVersion
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The About page's host-version row, drawn from the three states the view model can
 * publish.
 *
 * What this pins is the *wording*, which is the half a pure test cannot see: the row
 * is never blank, a host whose opening frame named no version says exactly that, and a
 * host that did name one is printed as sent. That no branch can reach an app-owned
 * version is `HostVersionTest`'s subject, not this one's. Which state the view model
 * publishes, and that it drops a previous host's version when the socket moves, is not
 * observable here — `DshViewModel` needs an `Application` and a live host
 * (`UiStateModelChoiceTest` records the same limit), so that half rests on inspection
 * of `ensureMuxWiring`/`rewireMux` and the mux-state loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class AboutHostVersionTest {

    @get:Rule
    val rule = createComposeRule()

    private fun about(version: HostVersion, connected: Boolean = true) {
        rule.setContent {
            DshTheme {
                AboutContent(
                    baseUrl = "https://host.example/",
                    username = "tony",
                    socketConnected = connected,
                    onSignOut = {},
                    onDismiss = {},
                    hostVersion = version,
                )
            }
        }
        rule.waitForIdle()
    }

    /** The host-version row's value, addressed by its seat, not by the word it draws. */
    private fun value() = rule.onNodeWithTag(HOST_VERSION_VALUE_TAG)

    @Test
    fun `a host whose opening frame named no version says so`() {
        // The shipped state: 0.2.0-rc.2's `ready` item carries `host: { home }` only.
        about(HostVersion.NotReported)
        value().assertTextEquals("Not reported")
    }

    @Test
    fun `a named version is printed exactly as the host sent it`() {
        about(HostVersion.Reported("9.9.9-hostver"))
        value().assertTextEquals("9.9.9-hostver")
    }

    @Test
    fun `nothing heard yet reads as the screen's Unknown, not as a claim`() {
        // `Unknown` is the value the view model publishes before the first frame and
        // while disconnected, so this is also the disconnected wording.
        about(HostVersion.Unknown)
        value().assertTextEquals("Unknown")
    }

    @Test
    fun `the row is drawn, not omitted, with the socket down`() {
        // An omitted row cannot be told from an app that never had the fact, which
        // is the shape this page's own doc comment refuses.
        about(HostVersion.Unknown, connected = false)
        rule.onNodeWithText("DSH version").assertExists()
        value().assertTextEquals("Unknown")
    }
}
