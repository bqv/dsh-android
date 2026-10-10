package uk.xa0.dsh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.search.SessionSearchCopy
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The clear affordance on a search box: drawn only while there is a query to
 * clear, gone again once there is not, and clearing leaves the caret in the box.
 *
 * The drawer's box and the archived-sessions box are both this field with
 * `onClear` set, so the visibility rule and the focus hand-back are proved once
 * here rather than twice through two much larger surfaces. The wiring of each
 * caller's own query state is proved in `SessionsDrawerSearchTest`.
 *
 * The JVM harness cannot measure text, so nothing here asserts anything about
 * layout — only about which nodes exist and which one holds focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SearchClearTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the clear affordance appears with a query and goes when the query is empty`() {
        var query by mutableStateOf("")
        rule.setContent {
            DshTheme {
                DshTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = SessionSearchCopy.PLACEHOLDER,
                    onClear = { query = "" },
                    clearContentDescription = SessionSearchCopy.CLEAR,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()

        rule.onNode(hasSetTextAction()).performTextInput("drawer")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertExists()

        // Emptying it by typing, rather than by the affordance, is the same rule
        // read the other way: there is no separate visibility flag to fall out of
        // step with the text.
        rule.runOnIdle { query = "" }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()
    }

    @Test
    fun `tapping clear empties the field, drops the affordance and keeps focus`() {
        var query by mutableStateOf("deepseek")
        rule.setContent {
            DshTheme {
                DshTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = SessionSearchCopy.PLACEHOLDER,
                    onClear = { query = "" },
                    clearContentDescription = SessionSearchCopy.CLEAR,
                )
            }
        }
        rule.waitForIdle()

        // The caret starts in the box, the way it does for someone who has just
        // typed the query being cleared.
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()

        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).performClick()
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("", query) }
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()
        // The drawer stays open and the keyboard stays up: nothing here closes a
        // surface, in keeping with "the text goes, the results come back, and the
        // box is ready for the next query".
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    /**
     * The affordance is opt-in. A password, a host URL or an API key keeps the
     * field it had, so a form cannot grow a control that offers to throw away what
     * the user cannot read back.
     */
    @Test
    fun `a field with no onClear draws no affordance`() {
        rule.setContent {
            DshTheme {
                DshTextField(
                    value = "sk-a-secret",
                    onValueChange = {},
                    placeholder = "DeepSeek API key (sk-…)",
                    isPassword = true,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Clear").assertDoesNotExist()
        rule.onNodeWithContentDescription(SessionSearchCopy.CLEAR).assertDoesNotExist()
    }
}
