package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.QueuedMessage
import uk.xa0.dsh.model.QUEUE_PREVIEW_CHARS
import uk.xa0.dsh.model.inboxQueueItems
import uk.xa0.dsh.model.parseQueueItems
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.QueueDock
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The one message-editing surface in the app, driven rather than read.
 *
 * Editing a queued message lives on the queue dock and nowhere else: the
 * transcript's footer carries clock, copy and branch, and the web client has no
 * retry or edit on a sent message either. The dock's Edit action is enabled for a
 * **host-backed** row — a real item id, `pending` false — and disabled for the
 * local submission echo that stands in for one until the host's row arrives.
 *
 * That distinction is the whole failure mode this pins. The host stopped
 * publishing the queue on `session/control` (0.2.0's baseline carries
 * `projections` alone; the queue is the `inbox` projection), the client kept
 * reading the frame, and so the dock could only ever draw echoes: the Edit glyph
 * was on screen the whole time, greyed out, and the row was retired after fifteen
 * seconds. These tests take the row the host's `inbox` projection produces and
 * prove the action works on it, and take the echo to show the state that is
 * indistinguishable from "the feature is gone".
 *
 * Bounds are not asserted: this harness stubs text metrics (see HANDOFF), so what
 * is checked is the semantics tree — displayed, clickable, enabled — plus the
 * callbacks the dock hands back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = HarnessApplication::class,
    // Robolectric's default display is w320dp-h470dp. The dock is short, but the
    // editor is an animated swap and this class drives it with real touch input;
    // a phone-sized window keeps the whole exchange inside the composed surface,
    // the same way `PasswordFieldTest` does for the sign-in form.
    qualifiers = "w411dp-h891dp",
)
class QueueDockEditTest {

    @get:Rule
    val rule = createComposeRule()

    /** Long enough that the preview is truncated and the text is not. */
    private val full = "x".repeat(QUEUE_PREVIEW_CHARS + 40)

    /** One session's `inbox` projection value carrying a single queued prompt. */
    private fun inbox(text: String): JSONObject = JSONObject(
        "{\"next-turn\":[{\"id\":\"m-1\",\"content\":[" +
            "{\"type\":\"text\",\"text\":\"$text\"}]," +
            "\"source\":{\"kind\":\"user\",\"rpcId\":\"req-1\"}}],\"next-step\":[]}",
    )

    /** The row the host's `inbox` projection yields for that prompt. */
    private fun hostRow(text: String): QueuedMessage =
        parseQueueItems(inboxQueueItems(inbox(text))).single()

    private fun composeDock(
        rows: List<QueuedMessage>,
        onEdit: (String, String) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            DshTheme {
                QueueDock(
                    items = rows,
                    running = true,
                    mutable = true,
                    busy = null,
                    onEdit = onEdit,
                    onRemove = {},
                    onSteer = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // The dock's own arrival and the row's fade are finite animations; the
        // clock is advanced rather than left to settle so a node is measured.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /**
     * Diagnostic, not an assertion: prints what the dock is showing at each point around
     * the editor swap, and again if the display wait times out.
     *
     * The previous version fetched single nodes, so the first missing or duplicated node
     * threw and the post-swap chain was never printed. This one counts instead of fetching,
     * dumps before the click, straight after it, after the clock advance, after idle, and
     * once more on the timeout path, so the failing path cannot hide the evidence. It
     * asserts nothing on purpose; delete it once the answer is in.
     */
    @Test
    fun `diagnostic - where the editor's Save control sits`() {
        composeDock(listOf(hostRow("run the tests")))
        dump("before the click")

        rule.onNodeWithContentDescription("Edit queued message").performClick()
        dump("straight after the click, before the clock is advanced")

        rule.mainClock.advanceTimeBy(1_000)
        dump("after the clock advance, before waitForIdle")
        rule.waitForIdle()
        dump("after waitForIdle", withTree = true)

        val wait = runCatching {
            rule.waitUntil(5_000L) {
                rule.onNodeWithContentDescription("Save queued message").isDisplayed()
            }
        }
        if (wait.isFailure) {
            dump("after the waitUntil timed out", withTree = true)
            throw wait.exceptionOrNull()!!
        }
    }

    /**
     * Counts every control that says which state the dock is in, and prints each match's
     * bounds and ancestor chain. Nothing here fetches a single node, so a missing or
     * duplicated control is reported as a count rather than thrown.
     */
    private fun dump(where: String, withTree: Boolean = false) {
        println("[probe] ===== $where =====")
        val labels = listOf(
            "Edit queued message",
            "Save queued message",
            "Cancel editing",
            "Remove queued message",
            "Steer queued message",
            "Contains non-text content; editing is not supported yet",
        )
        for (label in labels) {
            for (unmerged in listOf(false, true)) {
                val nodes = runCatching {
                    rule.onAllNodesWithContentDescription(label, useUnmergedTree = unmerged)
                        .fetchSemanticsNodes()
                }.getOrElse {
                    println("[probe] '$label' unmerged=$unmerged unreadable: $it")
                    emptyList()
                }
                println("[probe] '$label' unmerged=$unmerged count=${nodes.size}")
                nodes.forEachIndexed { index, node ->
                    runCatching {
                        println(
                            "[probe]   [$index] size=${node.size} root=${node.boundsInRoot} " +
                                "window=${node.boundsInWindow}",
                        )
                        var info: LayoutInfo? = node.layoutInfo
                        var depth = 0
                        while (info != null) {
                            println(
                                "[probe]     [$depth] ${info.javaClass.simpleName} " +
                                    "placed=${info.isPlaced} attached=${info.isAttached} " +
                                    "${info.width}x${info.height}",
                            )
                            info = info.parentInfo
                            depth++
                        }
                    }.onFailure { println("[probe]   [$index] unreadable: $it") }
                }
            }
        }
        val fields = runCatching {
            rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        }
        println("[probe] text fields = ${fields.getOrElse { "unreadable: $it" }}")
        if (withTree) {
            val tree = runCatching { rule.onRoot(useUnmergedTree = true).printToString() }
            println("[probe] tree:\n" + tree.getOrElse { "unreadable: $it" })
        }
    }

    @Test
    fun `a host-backed row offers an Edit that opens the editor`() {
        composeDock(listOf(hostRow("run the tests")))

        rule.onNodeWithContentDescription("Edit queued message")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        // The actions are a crossfade, so the incoming Save/Cancel row can be
        // composed a frame before it is placed. Waiting for displayed-ness and then
        // asserting it keeps the assertion the point — nothing here is relaxed to
        // "exists"; it just stops reading the swap mid-frame.
        rule.waitUntil(5_000L) {
            rule.onNodeWithContentDescription("Save queued message").isDisplayed()
        }
        rule.onNodeWithContentDescription("Save queued message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cancel editing").assertIsDisplayed()
    }

    @Test
    fun `saving hands the host the item id and the full text`() {
        var saved: Pair<String, String>? = null
        composeDock(listOf(hostRow(full))) { id, text -> saved = id to text }

        rule.onNodeWithContentDescription("Edit queued message").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        // Same swap as above: the Save control has to be placed before a real touch
        // can be injected into it.
        rule.waitUntil(5_000L) {
            rule.onNodeWithContentDescription("Save queued message").isDisplayed()
        }
        rule.onNodeWithContentDescription("Save queued message").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        assertEquals("m-1" to full, saved)
    }

    @Test
    fun `a local echo draws the same Edit, disabled, and cannot be tapped`() {
        // The state the client was stuck in: the glyph is present, so the screen
        // looks like the feature is there, and nothing can be done with it.
        composeDock(
            listOf(
                QueuedMessage(
                    id = "req-1",
                    placement = "queued",
                    preview = "waiting",
                    text = "waiting",
                    attachments = emptyList(),
                    pending = true,
                ),
            ),
        )

        // A pending row draws the web's three dimmed verbs, all wearing the
        // "Sending…" label and all inert.
        rule.onAllNodesWithContentDescription("Sending…").assertCountEquals(3)
        rule.onAllNodesWithContentDescription("Sending…")[0].assertIsNotEnabled()
    }

    @Test
    fun `a non-text row draws no edit at all`() {
        val row = parseQueueItems(
            inboxQueueItems(
                JSONObject(
                    "{\"next-turn\":[{\"id\":\"m-2\",\"content\":[" +
                        "{\"type\":\"text\",\"text\":\"look\"}," +
                        "{\"type\":\"image\",\"attachment\":{\"attachmentId\":\"a\"," +
                        "\"name\":\"p.png\",\"bytes\":9}}]," +
                        "\"source\":{\"kind\":\"user\",\"rpcId\":\"req-2\"}}],\"next-step\":[]}",
                ),
            ),
        ).single()
        composeDock(listOf(row))

        // The web disables Edit when any block is not text; the label says why
        // rather than leaving a dead control.
        rule.onNodeWithContentDescription("Contains non-text content; editing is not supported yet")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }
}
