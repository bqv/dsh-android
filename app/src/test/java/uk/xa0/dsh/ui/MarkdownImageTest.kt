package uk.xa0.dsh.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.xa0.dsh.ui.markdown.InlineStyle
import uk.xa0.dsh.ui.markdown.Markdown
import uk.xa0.dsh.ui.markdown.MdBlock

/**
 * `![alt](url)` is a picture, not a link with a stray `!`.
 *
 * Treated as a link it drew as blue underlined words — the alt text and the whole URL —
 * which is what every markdown picture in a transcript looked like.
 */
class MarkdownImageTest {

    private fun tokens(source: String): List<uk.xa0.dsh.ui.markdown.InlineToken> =
        (Markdown.parse(source).first() as MdBlock.Paragraph).tokens

    @Test
    fun `an image becomes an image token with its address and its alt text`() {
        val token = tokens("![a shot](https://example.test/a.png)").single()
        assertEquals(InlineStyle.IMAGE, token.style)
        assertEquals("https://example.test/a.png", token.url)
        assertEquals("a shot", token.text)
    }

    @Test
    fun `a link is still a link`() {
        val token = tokens("[the docs](https://example.test/docs)").single()
        assertEquals(InlineStyle.LINK, token.style)
    }

    @Test
    fun `a picture among words keeps the words`() {
        val parsed = tokens("before ![a shot](a.png) after")
        assertEquals("before ", parsed.first().text)
        assertEquals(InlineStyle.IMAGE, parsed[1].style)
        assertEquals(" after", parsed.last().text)
    }

    @Test
    fun `a bare exclamation is not a picture`() {
        val parsed = tokens("wait! [the docs](https://example.test/docs)")
        assertEquals(true, parsed.first().text.contains("wait!"))
    }

    @Test
    fun `an empty alt is allowed`() {
        val token = tokens("![](a.png)").single()
        assertEquals(InlineStyle.IMAGE, token.style)
        assertEquals("", token.text)
    }
}
