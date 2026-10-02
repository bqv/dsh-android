package uk.xa0.dsh.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Autolinking, verified against the strings that actually appear in a transcript.
 *
 * The parser had `[text](url)` from the start, which is the form someone writes
 * when they are being careful; a transcript is mostly the other form — a release
 * URL, a file path, an issue — pasted as plain text, and those rendered as
 * something that looked like a link and did nothing.
 */
class MarkdownInlineTest {

    private fun links(text: String) = Markdown.parseInline(text).filter { it.style == InlineStyle.LINK }

    /** The line as it renders, with the linked runs marked. */
    private fun shape(text: String): String = Markdown.parseInline(text).joinToString("") {
        if (it.style == InlineStyle.LINK) "[${it.text}]" else it.text
    }

    @Test
    fun `a bare https url becomes a link to itself`() {
        val token = links("see https://github.com/bqv/dsh-android for the app").single()
        assertEquals("https://github.com/bqv/dsh-android", token.text)
        assertEquals("https://github.com/bqv/dsh-android", token.url)
    }

    @Test
    fun `http is linked too, and only as a whole word`() {
        assertEquals("http://127.0.0.1:8081/api", links("http://127.0.0.1:8081/api").single().url)
        // `xhttps://…` is not a URL: nothing links from the middle of a word.
        assertEquals(emptyList<InlineToken>(), links("xhttps://example.dev/a"))
    }

    @Test
    fun `a www host is linked with https`() {
        val token = links("www.example.com/x").single()
        assertEquals("www.example.com/x", token.text)
        assertEquals("https://www.example.com/x", token.url)
    }

    /** The sentence's full stop is the sentence's, not the address's. */
    @Test
    fun `trailing punctuation stays out of the link`() {
        assertEquals("see [https://x.dev/a].", shape("see https://x.dev/a."))
        assertEquals("([https://x.dev/a])", shape("(https://x.dev/a)"))
        assertEquals("[https://x.dev/a],", shape("https://x.dev/a,"))
    }

    /** …but a bracket that belongs to the path survives, which is the hard case. */
    @Test
    fun `a balanced bracket inside the path is kept`() {
        val url = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(url, links(url).single().url)
        // And with the sentence's own closer after it.
        assertEquals("$url.", "${links("$url.").single().url}.")
    }

    /**
     * The form this model actually uses. Across a sample of real sessions: zero
     * CommonMark angle autolinks, 198 backticked URLs in one session alone — so a link
     * in backticks is the *common* case, and it used to render as unclickable code.
     */
    @Test
    fun `a code span that is only a url is a link`() {
        assertEquals("http://host:8080", links("`http://host:8080`").single().url)
        assertEquals("https://x.dev/docs", links("see `https://x.dev/docs` now").single().url)
        // `www.` without a scheme is the same address, and the display text is what the
        // model wrote rather than what a browser would need.
        val www = links("`www.example.com/a`").single()
        assertEquals("www.example.com/a", www.text)
        assertEquals("https://www.example.com/a", www.url)
    }

    /** Inside backticks the characters are deliberate, so a trailing stop is part of it. */
    @Test
    fun `a code span keeps its own punctuation`() {
        assertEquals("https://x.dev/a.", links("`https://x.dev/a.`").single().url)
    }

    /** …but a span that is a command, or a sentence, is still code. */
    @Test
    fun `a code span with anything else in it stays code`() {
        assertEquals(emptyList<InlineToken>(), links("`curl https://x.dev/a`"))
        assertEquals(emptyList<InlineToken>(), links("`https://x.dev/a --flag`"))
        assertEquals(emptyList<InlineToken>(), links("`see https://x.dev`"))
    }

    @Test
    fun `a url inside inline code is not a link`() {
        val tokens = Markdown.parseInline("run `curl https://x.dev/a` now")
        assertEquals(emptyList<InlineToken>(), tokens.filter { it.style == InlineStyle.LINK })
        assertEquals("curl https://x.dev/a", tokens.single { it.style == InlineStyle.CODE }.text)
    }

    /** The markdown form still wins, and its label is what the reader taps. */
    @Test
    fun `a markdown link keeps its label and its url`() {
        val token = links("see [the release](https://github.com/bqv/dsh-android/releases)").single()
        assertEquals("the release", token.text)
        assertEquals("https://github.com/bqv/dsh-android/releases", token.url)
        assertNull(links("no links here").firstOrNull())
    }

    /** Two links in one line are two links, and the text between them is text. */
    @Test
    fun `several urls on a line are each linked`() {
        val found = links("https://a.dev/1 and https://b.dev/2")
        assertEquals(listOf("https://a.dev/1", "https://b.dev/2"), found.map { it.url })
    }


    /**
     * **A URL in bold is the form that prompted all of this.** Emphasis runs are taken
     * whole by the parser, so the autolink never saw what was inside them: in the
     * session under test the model answered "All done" with
     * `**https://github.com/Julow/Unexpected-Keyboard/pull/1451**` — bold, and dead.
     */
    @Test
    fun `a url inside emphasis is a link`() {
        val bold = links("**https://github.com/Julow/Unexpected-Keyboard/pull/1451**")
        assertEquals(1, bold.size)
        assertEquals("https://github.com/Julow/Unexpected-Keyboard/pull/1451", bold.single().url)
        assertEquals(InlineStyle.LINK, bold.single().style)

        // The same for the other two emphasis forms, and with words around it.
        assertEquals("https://x.dev/a", links("*https://x.dev/a*").single().url)
        assertEquals("https://x.dev/a", links("~~https://x.dev/a~~").single().url)
        val surrounded = links("**see https://x.dev/a now**")
        assertEquals("https://x.dev/a", surrounded.single().url)
        // The emphasis's own words are still there, and still bold.
        val boldWords = Markdown.parseInline("**see https://x.dev/a now**")
            .filter { it.style == InlineStyle.BOLD }
            .map { it.text }
        assertEquals(listOf("see ", " now"), boldWords)
    }

    /** A span that is a command is still code, emphasis or not. */
    @Test
    fun `a url inside a code span is not linked by the emphasis rule`() {
        assertEquals(emptyList<InlineToken>(), links("**`curl https://x.dev/a`**"))
    }
}
