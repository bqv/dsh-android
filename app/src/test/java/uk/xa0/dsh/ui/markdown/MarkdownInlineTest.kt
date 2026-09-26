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
}
