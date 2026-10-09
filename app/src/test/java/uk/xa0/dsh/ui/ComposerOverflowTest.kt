package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
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
 * One line of controls: attach, the access mode, the model chip, the context ring and
 * send — five things that used to be unweighted, so their widths simply added up and
 * overflowed a narrow card. A long model name was enough by itself; once the chip also
 * carried its state caption *beside* the name ("when created") the pair pushed send off
 * the card's right edge on a 411dp phone. Send is the screen's primary action and must
 * survive whatever the chip says.
 *
 * What is pinned here:
 *  * send stays wholly inside the card for every state the trigger can draw;
 *  * the model chip is what gives way when the row is tighter than its contents — the
 *    structural half, and the event a long name causes on a device;
 *  * the caption is a second line *under* the name, and a bare name is a single line.
 *
 * **On the text, and why the width pressure is applied by hand.** This harness cannot
 * measure text: stub metrics leave a paragraph unwrapped and a name effectively
 * zero-width (HANDOFF, traps — 24,892 characters lay out as one line). A long name
 * therefore creates *no* width pressure here, and the containment assertions below would
 * pass just as well against the old unweighted row. The squeeze test is the one with
 * teeth: it tightens the row until the chip has to give way, which is exactly what a long
 * name does on a device, and unlike a name it depends on no metric the shim stubs out.
 *
 * The row is composed at 320dp — the narrowest width Android phones ship — inside the
 * same 16dp inset the chat screen gives its composer, so the composer is handed
 * 320 − 32 = 288dp and its action row has 288 − 16 of its own padding left for five
 * controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w320dp-h640dp")
class ComposerOverflowTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** The composer's slot inside the chat screen's 16dp inset. */
        const val SLOT = "composer-slot"

        /** The trigger's accessibility sentence, which is how its node is addressed. */
        const val TRIGGER = "trigger under test"

        const val SEND = "Send and queue"

        /** How far each turn of the squeeze test tightens the row. */
        val STEP = 6.dp

        /**
         * A real name from the host's catalogue, longer than the trigger's resting 132dp
         * cap. It is what would create the pressure on a device; here the shim gives it
         * no width at all, which is why the squeeze test applies the pressure instead.
         */
        const val LONG_NAME = "Qwen3.5-35B-A3B-UD-IQ4_XS-128k-single-slot"

        const val SHORT_NAME = "DeepSeek-Flash"
    }

    private val label = "$LONG_NAME · High"

    /**
     * Composes the real composer at the width of a 320dp phone, or at [width] when a
     * test needs to change the room the row has while it is on screen.
     */
    private fun composeRow(trigger: ModelTriggerState?, width: () -> Dp? = { null }) {
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xl)) {
                    val fixed = width()
                    Box(
                        // Tagged on its own node, with no padding of its own, so the
                        // bounds the assertions compare against are the width the
                        // composer is actually given rather than the screen's.
                        (if (fixed != null) Modifier.width(fixed) else Modifier.fillMaxWidth())
                            .testTag(SLOT),
                    ) {
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
     * `assertIsDisplayed` alone would not catch this: a button half off the right edge is
     * still "displayed". The bounds comparison is the assertion that matters.
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

    /** The text nodes under [node]: one per drawn line of text. */
    private fun textLines(node: SemanticsNode): List<SemanticsNode> =
        node.children.flatMap { child ->
            val here =
                if (child.config.getOrNull(SemanticsProperties.Text) != null) listOf(child) else emptyList()
            here + textLines(child)
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
    fun `the caption is a second line under the name, sharing its left edge`() {
        // The part of the layout that makes the row fit: the caption is another line of
        // the same chip, not a peer of the name. `useUnmergedTree` because the trigger
        // merges its children into one clickable node and the two texts are what is
        // being compared.
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

        val chip = rule.onNodeWithContentDescription(TRIGGER, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("a captioned chip is two lines of text", 2, textLines(chip).size)
    }

    @Test
    fun `a name with no caption is a single line of text`() {
        // The height in px is *not* asserted, and cannot be on this harness: stub metrics
        // do not honour `lineHeight`, so a one-line box measured ~43dp where the tokens
        // add up to 28dp — an earlier version of this test asserted 28dp and failed on a
        // chip that was drawing exactly one line. The line *count* is the fact the height
        // stood for, it is immune to the metrics, and it is the regression that matters:
        // no caption may be drawn when there is no state to state.
        composeRow(ModelTriggerState(label = "$SHORT_NAME · High", description = TRIGGER))

        val chip = rule.onNodeWithContentDescription(TRIGGER, useUnmergedTree = true).fetchSemanticsNode()
        val lines = textLines(chip).map { it.config.getOrNull(SemanticsProperties.Text) }

        assertEquals("a bare name must be one line, found $lines", 1, lines.size)
    }

    @Test
    fun `the chip gives way, not send, when the row is tighter than its contents`() {
        // The harness cannot make a name wide, so the pressure a long name produces on a
        // device is produced here by taking width away from the row until the chip has to
        // shrink. The loop stops the moment it does, so nothing rests on guessing the
        // row's natural width. A row that never forces the chip to give way *is* the
        // failure: that is the unweighted layout the user hit, where every control kept
        // its full width and send paid for it.
        val width = mutableStateOf<Dp?>(null)
        composeRow(ModelTriggerState(label = label, marker = "when created", description = TRIGGER)) {
            width.value
        }

        val slot = rule.onNodeWithTag(SLOT).fetchSemanticsNode().boundsInRoot
        val chipNatural = rule.onNodeWithContentDescription(TRIGGER).fetchSemanticsNode().size.width
        val sendNatural = rule.onNodeWithContentDescription(SEND).fetchSemanticsNode().size.width

        var turns = 0
        var gaveWay = false
        while (turns < 24) {
            turns += 1
            width.value = with(rule.density) { (slot.width - (STEP * turns).toPx()).toDp() }
            rule.waitForIdle()
            if (rule.onNodeWithContentDescription(TRIGGER).fetchSemanticsNode().size.width < chipNatural) {
                gaveWay = true
                break
            }
        }

        assertTrue(
            "the chip held its ${chipNatural}px width through ${turns * STEP.value}dp of tightening: " +
                "nothing in the row yields, so the width comes out of send",
            gaveWay,
        )
        assertEquals(
            "the send control must keep its full width while the chip gives way",
            sendNatural,
            rule.onNodeWithContentDescription(SEND).fetchSemanticsNode().size.width,
        )
        assertSendIsWhole("a row tightened until the model chip had to give way")
    }
}
