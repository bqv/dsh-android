package uk.xa0.dsh.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.CommandEntry

/**
 * The `/` and `@` grammar, asked about a caret rather than composed.
 *
 * The composer's text is now a `TextFieldState`, whose text and selection are
 * read separately: `state.text` for the content and `state.selection.end` for the
 * caret. This is the pure half of that migration — the same
 * [composerTriggers] the screen calls, fed the same two facts — so a caret
 * anywhere in a line can be asked about without a window and without a field.
 *
 * What is pinned: the token is the one **under the caret**, mid-text included;
 * the query and the roster filtering are unchanged; and a pick replaces only the
 * token up to the caret, leaving the rest of the line alone. That last one is the
 * behaviour the whole migration had to keep, because the old `TextFieldValue`
 * carried the selection and the new state carries it as a separate property.
 *
 * What this cannot show: that the screen passes the live caret (that wiring is
 * read from `ui/ChatScreen.kt`), and anything about how the menu looks — the
 * JVM harness cannot measure text or colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ComposerTriggersTest {

    private fun entry(name: String, label: String = name) = CommandEntry(
        name = name,
        label = label,
        description = null,
        icon = Icons.Rounded.Send,
        section = "Commands",
    )

    private val entries = listOf(
        entry("model", "Model"),
        entry("permission", "Permission"),
        entry("file", "File"),
        entry("compact", "Compact"),
    )

    @Test
    fun `a slash token is read at a caret in the middle of the draft`() {
        // The caret is after "mod", with " world" still to its right: the old
        // value-based derivation read exactly this and the state-based one must not
        // fall back to the end of the draft.
        val triggers = composerTriggers("hello /mod world", caret = 10, commandEntries = entries)

        assertEquals('/', triggers.slash?.trigger)
        assertEquals("mod", triggers.slash?.query)
        assertEquals(6, triggers.slash?.start)
        assertEquals(10, triggers.slash?.end)
        assertNull(triggers.reference)
        assertEquals(listOf("model"), triggers.shownEntries.map { it.name })
    }

    @Test
    fun `a caret after the token, past whitespace, opens nothing`() {
        val triggers = composerTriggers("hello /mod world", caret = 16, commandEntries = entries)

        assertNull(triggers.slash)
        assertNull(triggers.reference)
    }

    @Test
    fun `the roster filter is the same prefix match as before`() {
        val triggers = composerTriggers("run /per", caret = 8, commandEntries = entries)

        assertEquals(listOf("permission"), triggers.shownEntries.map { it.name })
    }

    @Test
    fun `an at token is read at the caret too`() {
        // "ask @wor and more" with the caret right after "wor": the token ends at the
        // caret, not at the end of the draft. `@` wins over any `/` scan, as it always
        // has.
        val triggers = composerTriggers("ask @wor and more", caret = 8, commandEntries = entries)

        assertEquals('@', triggers.reference?.trigger)
        assertEquals("wor", triggers.reference?.query)
        assertEquals(4, triggers.reference?.start)
        assertEquals(8, triggers.reference?.end)
        assertNull(triggers.slash)
    }

    /**
     * A pick replaces the token the caret is in, and only as far as the caret. The
     * trailing "al" is not part of the token and must survive; the caret lands after
     * the insertion, which is where the next character is typed.
     */
    @Test
    fun `a pick rewrites the token under the caret and nothing else`() {
        val state = TextFieldState("hello /modal")
        val triggers = composerTriggers(state.text, caret = 10, commandEntries = entries)

        state.edit { replaceTrigger(triggers.slash, "/model ") }

        assertEquals("hello /model al", state.text.toString())
        assertEquals(TextRange(13), state.selection)
    }

    /** The `+` launcher types no token, so a pick must not eat the selection. */
    @Test
    fun `a pick with no token replaces only the selection`() {
        val state = TextFieldState(
            "hello /modal",
            initialSelection = TextRange(6, 10),
        )

        state.edit { replaceTrigger(token = null, insertion = "/model ") }

        assertEquals("hello /model al", state.text.toString())
    }
}
