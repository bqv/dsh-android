package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which read a path gets, decided before either is sent.
 *
 * This is the one decision that has to be made from the name alone: a picture has to
 * be fetched as bytes (`workspaceFiles/readBytes`) and a document as text
 * (`workspaceFiles/read`), and the text endpoint *refuses* non-UTF-8 outright — so
 * getting this wrong is not a slower preview, it is the host's "not text" wording
 * where a picture was asked for.
 */
class PreviewFormatTest {

    @Test
    fun `the raster formats are images`() {
        for (name in listOf("a.png", "a.PNG", "a.apng", "a.jpg", "a.jpeg", "a.JPEG", "a.jfif")) {
            assertTrue(name, previewFormatOf("/tmp/$name").isImage)
        }
        assertEquals(PreviewFormat.PNG, previewFormatOf("shot.png"))
        assertEquals(PreviewFormat.JPEG, previewFormatOf("shot.JPG"))
        assertEquals(PreviewFormat.WEBP, previewFormatOf("shot.webp"))
        assertEquals(PreviewFormat.GIF, previewFormatOf("anim.gif"))
        assertEquals(PreviewFormat.BMP, previewFormatOf("old.bmp"))
        assertEquals(PreviewFormat.HEIC, previewFormatOf("phone.heic"))
        assertEquals(PreviewFormat.AVIF, previewFormatOf("phone.avif"))
    }

    /**
     * SVG is an image *and* a document. It is fetched as text and drawn by the pane,
     * which is why it is its own case rather than either of the two.
     */
    @Test
    fun `svg is its own format, not text and not a raster`() {
        val svg = previewFormatOf("diagram.svg")
        assertEquals(PreviewFormat.SVG, svg)
        assertTrue(svg.isImage)
        assertFalse(previewFormatOf("notes.txt").isImage)
    }

    /** Everything else is read as text, which is the safe default: it is what the host can send. */
    @Test
    fun `documents and unknowns are text`() {
        for (name in listOf("a.kt", "README.md", "Makefile", "a.tar.gz", "noext")) {
            assertEquals(name, PreviewFormat.TEXT, previewFormatOf("/home/user/$name"))
        }
    }

    /** A `.PNG` off a phone camera and a `?raw=1` tail off a written URL both occur. */
    @Test
    fun `case and a query tail do not change the answer`() {
        assertEquals(PreviewFormat.PNG, previewFormatOf("Icon.PNG"))
        assertEquals(PreviewFormat.PNG, previewFormatOf("icon.png?raw=1"))
        assertEquals(PreviewFormat.SVG, previewFormatOf("logo.svg#fragment"))
    }

    /** A dot in a directory name is not an extension. */
    @Test
    fun `only the file name's own extension counts`() {
        assertEquals(PreviewFormat.TEXT, previewFormatOf("/home/user/v1.2/README"))
        assertEquals(PreviewFormat.PNG, previewFormatOf("/home/user/v1.2/logo.png"))
    }
}
