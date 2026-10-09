package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.Composer
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The composer row cannot hide its own primary action.
 *
 * The row is one line of controls: attach, the access mode, the model trigger, the
 * context ring, and send. Every one of them used to be unweighted, so they simply
 * overflowed a narrow card — a long model name was enough, and once the trigger also
 * carried a state caption beside the name ("when created") the pair pushed the send
 * button off the right edge on a 411dp phone. Send is the screen's primary action and
 * must survive whatever the chip says.
 *
 * What is pinned here:
 *  * the send control is inside the card with every caption the trigger can show, and
 *    with the longest name the shim can be made to lay out;
 *  * the caption sits *under* the name rather than beside it — the layout that makes
 *    the width pressure disappear — and shares its left edge;
 *  * a name with no caption still draws one line, so the ordinary chip stays compact.
 *
 * **On the text**: Robolectric lays text out with stub metrics, so a name has almost
 * no width however long it is (`docs/HANDOFF.md`, traps §1 — 24,892 characters measure
 * as one 260px line). The pressure here therefore comes from the trigger's own resting
 * cap, [132dp], which any sufficiently long name reaches at every density. That is the
 * hazard this file can reproduce honestly; a caption's *own* width cannot be reproduced
 * on the JVM, which is exactly why the caption had to stop sharing the line.
 *
 * The row is composed at 320dp — the narrowest screen Android phones actually ship —
 * inside the same 16dp inset the chat screen gives its composer. The composer is
 * therefore given 320 − 32 = 288dp, and its action row has 288 − 16 (its own md
 * padding) = 272dp to lay five controls out in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w320dp-h640dp")
class ComposerOverflowTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** The composer's slot inside the chat screen's 16dp inset. */
        const val SLOT = "composer-slot"

        /** The trigger's accessibility sentence, which is also its merged node's id. */
        const val TRIGGER = "trigger under test"

        const val SEND = "Send and queue"

        /**
         * Long enough to reach the trigger's 132dp cap at any density the shim can be
         * configured with: the stub metric is ~0.6px per glyph, so 1200 glyphs is
         * 720px, which is ≥132dp even at a density of 4.
         */
        val LONG_NAME = "Qwen3.5-35B-A3B-UD-IQ4_XS-128k-" + "x".repeat(1200)

        const val SHORT_NAME = "DeepSeek-Flash"
    }

    private val label = "$LONG_NAME · High"

    private fun composeRow(trigger: ModelTriggerState?) {
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xl)) {
                    // Tagged on its own node, with no padding of its own, so the bounds
                    // the assertions compare against are the width the composer is
                    // actually given (320 − 32) rather than the screen's.
                    Box(Modifier.fillMaxWidth().testTag(SLOT)) {
                        Composer(
                            value = TextFieldValue("hello"),
                            onValueChange = {},
                            onSend = {},
                            onStop = {},
                            running = false,
                            modelTrigger = trigger,
                            onModelClick = {},
                            permissionLabel = "Full access",
                            onPermissionClick = {},
                            planActive = false,
                            onExitPlan = {},
                            contextPercent = 12,
                            contextTokens = 12_000,
                            contextWindow = 100_000,
                            contextBreakdown = null,
                            attachments = emptyList(),
                            onToggleCommands = {},
                            onRemoveAttachment = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    /**
     * The send control is present, enabled, and wholly inside the card's width.
     *
     * `assertIsDisplayed` alone would not catch this: a button half off the right edge
     * is still "displayed". The bounds comparison is the assertion that matters.
     */
    private fun assertSendIsWhole(state: String) {
        val slot = rule.onNodeWithTag(SLOT).fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithContentDescription(SEND)
        send.assertIsDisplayed()
        val bounds = send.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "send is pushed past the card's right edge with $state: send=$bounds slot=$slot",
            bounds.right <= slot.right + 0.5f,
        )
        assertTrue(
            "send starts left of the card with $state: send=$bounds slot=$slot",
            bounds.left >= slot.left - 0.5f,
        )
    }

    @Test
    fun `a long model name alone does not push the send control out`() {
        composeRow(ModelTriggerState(label = label, description = TRIGGER))

        assertSendIsWhole("a long name and no caption")
    }

    @Test
    fun `the next-turn caption does not push the send control out`() {
        composeRow(ModelTriggerState(label = label, marker = "next turn", description = TRIGGER))

        assertSendIsWhole("the next-turn caption")
    }

    @Test
    fun `the in-flight caption does not push the send control out`() {
        composeRow(ModelTriggerState(label = label, marker = "applying\u2026", description = TRIGGER))

        assertSendIsWhole("the in-flight caption")
    }

    @Test
    fun `the new-session caption does not push the send control out`() {
        composeRow(ModelTriggerState(label = label, marker = "when created", description = TRIGGER))

        assertSendIsWhole("the new-session caption")
    }

    @Test
    fun `the unavailable caption does not push the send control out`() {
        composeRow(
            ModelTriggerState(label = label, marker = "unavailable", description = TRIGGER, alert = true),
        )

        assertSendIsWhole("the unavailable caption")
    }

    @Test
    fun `the caption sits under the name, sharing its left edge`() {
        // The four send assertions above each compose one caption; this pins the part
        // of the layout that makes them fit — the caption is a second line of the same
        // chip, not a peer of the name. `useUnmergedTree` because the trigger merges
        // its children into one clickable node, and the two texts are what is being
        // compared.
        composeRow(ModelTriggerState(label = label, marker = "when created", description = TRIGGER))

        val name = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val caption = rule.onNodeWithText("when created", useUnmergedTree = true).fetchSemanticsNode()

        assertTrue(
            "the caption belongs under the name, not beside it: " +
                "name=${name.boundsInRoot} caption=${caption.boundsInRoot}",
            caption.boundsInRoot.top >= name.boundsInRoot.bottom - 1f,
        )
        assertEquals(
            "the caption shares the name's left edge",
            name.boundsInRoot.left.toDouble(),
            caption.boundsInRoot.left.toDouble(),
            1.0,
        )
    }

    @Test
    fun `a name with no caption still draws a single line`() {
        // bodyMedium is 20sp/20dp and the chip adds xs (4dp) above and below, so a
        // one-line trigger is 28dp — the same height as the mode chip beside it. The
        // caption is what makes it taller, and only in the states that need saying.
        composeRow(ModelTriggerState(label = "$SHORT_NAME · High", description = TRIGGER))

        val oneLine = with(rule.density) { 28.dp.roundToPx() }
        val height = rule.onNodeWithContentDescription(TRIGGER).fetchSemanticsNode().size.height

        assertTrue("a bare name must stay one line, measured ${height}px", height <= oneLine + 2)
    }
}
