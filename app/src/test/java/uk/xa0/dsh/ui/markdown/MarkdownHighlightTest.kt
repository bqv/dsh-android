package uk.xa0.dsh.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the syntax highlighter guarantees, and what it does not.
 *
 * The one property worth testing without a screen is the one a reader would never
 * forgive: **the spans are the line**. Colouring is a claim about a line's parts, and a
 * claim is only survivable if every character survives it — a highlighter that ate a
 * character, or swallowed the tail of a line, would show a file that is not the file.
 * `MarkdownView.highlightCodeLine` paints these spans, so this is the content half of
 * that rendering, testable on the JVM.
 *
 * What it cannot show is that the colours are *good*, or even that they are the right
 * ones: this is one grammar with one keyword set, not a per-language parser. It has no
 * state between lines, so a block comment spanning two lines is coloured on neither —
 * a limitation it shares with the chat's code blocks, and one that costs colour rather
 * than content.
 */
class MarkdownHighlightTest {

    private fun spans(line: String) = SyntaxHighlighter.highlightLine(line)

    private fun text(line: String) = spans(line).joinToString("") { it.text }

    @Test
    fun `every line survives the highlighter exactly, character for character`() {
        val lines = listOf(
            "fun main() {",
            "    val answer = 42 // the answer, apparently",
            "val quoted = \"# this is not a comment\"",
            "#!/bin/sh",
            "curl -fsSL https://example.dev/install | sh   # fetch",
            "{\"key\": \"value\", \"n\": 1.5e-3}",
            "key: value  # yaml comment",
            "name = \"dsh\"  # toml",
            "## A markdown heading with `code`",
            "print('it\\'s here')",
            "",
            "    ",
            "héllo wörld — ünïcode",
            "x = a--b",
        )
        for (line in lines) assertEquals(line, text(line))
    }

    @Test
    fun `an empty line yields no spans at all`() {
        assertEquals(emptyList<SyntaxHighlighter.Span>(), spans(""))
    }

    /** The languages this work is in: each one's own shape is recognised as something. */
    @Test
    fun `kotlin, shell, python and the config formats are each read as code`() {
        assertTrue(spans("fun main() {").any { it.kind == SyntaxHighlighter.Kind.KEYWORD && it.text == "fun" })
        assertTrue(spans("class Box").any { it.kind == SyntaxHighlighter.Kind.KEYWORD })
        assertTrue(spans("val n = 42").any { it.kind == SyntaxHighlighter.Kind.NUMBER && it.text == "42" })
        assertTrue(spans("fun main() {").any { it.kind == SyntaxHighlighter.Kind.FUNCTION && it.text == "main" })
        assertTrue(spans("#!/bin/sh").isNotEmpty())
        assertTrue(spans("echo \"\$HOME\"").any { it.kind == SyntaxHighlighter.Kind.STRING })
        assertTrue(spans("def run():").any { it.kind == SyntaxHighlighter.Kind.KEYWORD && it.text == "def" })
        assertTrue(spans("{\"a\": 1}").any { it.kind == SyntaxHighlighter.Kind.STRING && it.text == "\"a\"" })
        assertTrue(spans("key: value").any { it.kind == SyntaxHighlighter.Kind.PLAIN })
    }

    @Test
    fun `a line comment runs to the end of the line, in each of its spellings`() {
        for (line in listOf("// rest of the line", "# rest of the line", "-- rest of the line")) {
            val comment = spans(line).single()
            assertEquals(SyntaxHighlighter.Kind.COMMENT, comment.kind)
            assertEquals(line, comment.text)
        }
    }

    /**
     * The one place a comment marker could do real damage: a `#` inside a string is part
     * of the string. The highlighter takes the whole quoted span in one go, so it is.
     */
    @Test
    fun `a comment marker inside a string stays string`() {
        val spans = spans("val quoted = \"# not a comment\"")
        assertTrue(spans.any { it.kind == SyntaxHighlighter.Kind.STRING && it.text == "\"# not a comment\"" })
        assertTrue(spans.none { it.kind == SyntaxHighlighter.Kind.COMMENT })
    }

    /**
     * The renderer's own view of one line, and the claim that a whole-code-block
     * rendering is the same text line by line — the pair `CodeBlock` and the Files
     * preview both rest on.
     */
    @Test
    fun `a multi-line block keeps every newline and every character`() {
        val code = "fun a() {\n    // one\n    val x = 1\n}\n"
        val lines = code.split('\n').map { text(it) }
        assertEquals(code, lines.joinToString("\n"))
    }
}
