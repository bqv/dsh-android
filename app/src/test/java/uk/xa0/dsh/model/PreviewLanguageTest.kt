package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the panel claims a file *is*, from its name alone.
 *
 * Two claims are made from the same last segment and they are not the same claim:
 * [previewFormatOf] decides how the file is *read* (text or bytes), while
 * [previewLanguageOf] decides what grammar the source is coloured with, and
 * [isMarkdownPath] decides whether it is rendered as a document at all.
 *
 * The table is shared with the chat read card rather than copied, because two copies of
 * one table is how a `.kt` file ends up labelled `kotlin` in one pane and blank in
 * another. The tests here hold the shared answer, including the two cases where "no
 * claim" is the only honest one: a dotfile, and an extension nobody listed.
 */
class PreviewLanguageTest {

    @Test
    fun `the languages this work actually uses are named`() {
        assertEquals("kotlin", previewLanguageOf("/w/src/App.kt"))
        assertEquals("kotlin", previewLanguageOf("/w/build.gradle.kts"))
        assertEquals("sh", previewLanguageOf("/w/tools/build.sh"))
        assertEquals("sh", previewLanguageOf("run.bash"))
        assertEquals("py", previewLanguageOf("/w/tools/x.py"))
        assertEquals("json", previewLanguageOf("/w/package.json"))
        assertEquals("toml", previewLanguageOf("/w/Cargo.toml"))
        assertEquals("yaml", previewLanguageOf("/w/ci.yml"))
        assertEquals("md", previewLanguageOf("/w/README.md"))
    }

    /** Case and a query or fragment tail are the same file, exactly as the format table has it. */
    @Test
    fun `case and a tail do not change the language`() {
        assertEquals("kotlin", previewLanguageOf("/w/App.KT"))
        assertEquals("json", previewLanguageOf("/w/data.json?raw=1"))
        assertEquals("json", previewLanguageOf("/w/data.json#L10"))
    }

    /**
     * The honest answer when nothing is known: a dotfile is not a `.gitignore`-flavoured
     * language, an unlisted suffix is not a language, and a name with no dot is not one
     * either. `null` here is what keeps a log file from being coloured as code.
     */
    @Test
    fun `an unknown extension and a dotfile make no claim at all`() {
        assertNull(previewLanguageOf("/w/.gitignore"))
        assertNull(previewLanguageOf("/w/notes.txt"))
        assertNull(previewLanguageOf("/w/run.log"))
        assertNull(previewLanguageOf("/w/Makefile"))
        assertNull(previewLanguageOf("/w/archive.tar.gz"))
        assertNull(previewLanguageOf(""))
    }

    /** A dot in a directory name is not an extension, for this table as for the other. */
    @Test
    fun `only the file name's own extension counts`() {
        assertNull(previewLanguageOf("/home/user/v1.2/README"))
        assertEquals("json", previewLanguageOf("/home/user/v1.2/data.json"))
    }

    /** Markdown is rendered only for the extensions whose whole content is Markdown. */
    @Test
    fun `markdown is the three extensions that are wholly markdown`() {
        assertTrue(isMarkdownPath("/w/README.md"))
        assertTrue(isMarkdownPath("/w/notes.MARKDOWN"))
        assertTrue(isMarkdownPath("/w/notes.mdown"))
        assertTrue(isMarkdownPath("/w/notes.md?raw=1"))
    }

    /**
     * `.mdx` is deliberately not rendered. Its body is Markdown *plus* JSX, and drawing
     * the JSX as prose would be a claim the file does not support — so it keeps the
     * `mdx` chip and its source, and nothing is dropped.
     */
    @Test
    fun `mdx, text and an unknown suffix are not rendered`() {
        assertFalse(isMarkdownPath("/w/page.mdx"))
        assertFalse(isMarkdownPath("/w/notes.txt"))
        assertFalse(isMarkdownPath("/w/README.md.bak"))
        assertFalse(isMarkdownPath("/w/Makefile"))
        assertFalse(isMarkdownPath(""))
    }
}
