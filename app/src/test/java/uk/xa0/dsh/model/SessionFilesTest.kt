package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `present` declaration is a row in the Files list, under the very spelling the
 * deliverable card carries.
 *
 * That is the whole point of the rule: the card's tap hands its path to the Files
 * panel as an `initialPath`, and the panel selects nothing unless the path is an
 * exact match for a row (`ui/components/FilesPanel.kt`, `files.any { it.path ==
 * path }`). A presented file the transcript never read or wrote had no row at all,
 * and a `present` naming the file relatively while every `write` named it
 * absolutely was a different row — so in both cases the card drew, the tap set the
 * focus, and the panel had nothing to select. The user saw a card that did not
 * open and a file missing from the list.
 *
 * ## What these can and cannot show
 *
 * They show the derivation's own answers: which paths are rows, with which touch
 * kinds and counts, for a declaration that succeeded, failed, never answered, or
 * repeated a path. They cannot show that the panel *renders* the selection — that
 * is `ui/FilesPanelTest` — and they cannot show that the host can read the path
 * back, which is `workspaceFiles/read` and a live host.
 */
class SessionFilesTest {

    private fun call(
        seq: Int,
        name: String,
        arguments: String,
        result: String? = "ok",
        isError: Boolean = false,
        turn: Int = 1,
    ): ChatEntry.ToolCall = ChatEntry.ToolCall(
        seq = seq,
        callId = "call-$seq",
        name = name,
        arguments = arguments,
        result = result,
        isError = isError,
        time = 0L,
        turn = turn,
    )

    private fun present(vararg paths: String, seq: Int = 1, result: String? = "ok", isError: Boolean = false) =
        call(
            seq = seq,
            name = "present",
            arguments = """{"files":[${paths.joinToString(",") { """{"path":"$it"}""" }}]}""",
            result = result,
            isError = isError,
        )

    /** The reported case: a file produced by something that is not a mutation. */
    @Test
    fun `a presented file the session never read or wrote is a row`() {
        val files = SessionFiles.derive(listOf(present("/w/out/REPORT.md")))

        assertEquals(1, files.size)
        val file = files.single()
        assertEquals("/w/out/REPORT.md", file.path)
        assertEquals(listOf(FileTouch.PRESENTED), file.touches)
        assertEquals(1, file.presents)
        assertEquals(0, file.reads)
        assertEquals(0, file.writes)
        assertEquals(0, file.edits)
        // A declaration is not a change to the file.
        assertFalse(file.modified)
    }

    /**
     * The commonest shape in the host's own journals: `present` names the file
     * relative to the session cwd while `write`/`edit` named it absolutely. Both
     * spellings are rows, and the card's spelling is one of them.
     */
    @Test
    fun `a relative declaration is a row beside the absolute write`() {
        val entries = listOf(
            call(1, "write", """{"file_path":"/w/out/report.md","content":"x"}"""),
            present("out/report.md", seq = 2),
        )

        val paths = SessionFiles.derive(entries).map { it.path }
        assertEquals(listOf("/w/out/report.md", "out/report.md"), paths)
        assertTrue(paths.contains("out/report.md"))
    }

    /** A file both written and declared keeps one row per spelling, with both kinds. */
    @Test
    fun `a declaration of a written path adds the kind to that row`() {
        val entries = listOf(
            call(1, "write", """{"file_path":"/w/out/report.md","content":"x"}"""),
            present("/w/out/report.md", seq = 2),
        )

        val file = SessionFiles.derive(entries).single()
        assertEquals(listOf(FileTouch.WRITTEN, FileTouch.PRESENTED), file.touches)
        assertEquals(1, file.writes)
        assertEquals(1, file.presents)
        assertTrue(file.modified)
    }

    /** The web hangs the declaration on a non-error result; a failed present declared nothing. */
    @Test
    fun `a failed declaration is not a row`() {
        assertTrue(SessionFiles.derive(listOf(present("/w/out/x.md", isError = true))).isEmpty())
    }

    /** A call still in flight has declared nothing yet. */
    @Test
    fun `a declaration that has not answered is not a row`() {
        assertTrue(SessionFiles.derive(listOf(present("/w/out/x.md", result = null))).isEmpty())
    }

    /** A blank or missing path is not a file, whichever key it hides behind. */
    @Test
    fun `blank, malformed and absent paths declare nothing`() {
        val entries = listOf(
            call(1, "present", """{"files":[{"path":"   "},{"path":""},{"description":"no path"}]}"""),
            call(2, "present", """{"files":"not an array"}"""),
            call(3, "present", """{"files":[{"path":7}]}"""),
        )
        assertTrue(SessionFiles.derive(entries).isEmpty())
    }

    /** One path declared twice in one call is one row, not two counts. */
    @Test
    fun `a path repeated in one call is one row with one declaration`() {
        val file = SessionFiles.derive(
            listOf(call(1, "present", """{"files":[{"path":"a.md"},{"path":"a.md"}]}""")),
        ).single()
        assertEquals(1, file.presents)
        assertEquals(listOf(FileTouch.PRESENTED), file.touches)
    }

    /** First-seen order is the list's order, whatever kind of touch arrived first. */
    @Test
    fun `the declaration joins the list in first-seen order`() {
        val entries = listOf(
            call(1, "write", """{"file_path":"first.md","content":"x"}"""),
            present("second.md", seq = 2),
            call(3, "read", """{"file_path":"third.md"}"""),
        )
        assertEquals(
            listOf("first.md", "second.md", "third.md"),
            SessionFiles.derive(entries).map { it.path },
        )
    }

    /**
     * The produced-files rule is the web's, field for field, and it has no
     * `present` case: a declaration must not appear as a file the turn *changed*.
     */
    @Test
    fun `a declaration is not a produced file`() {
        val entries = listOf(present("/w/out/report.md", seq = 5))

        assertTrue(SessionFiles.producedForTurn(entries, turn = 1).isEmpty())
        assertEquals(
            listOf("/w/out/report.md"),
            SessionFiles.presentedForTurn(entries, turn = 1).map { it.path },
        )
    }

    /** The card's parser and the list's parser are one parser, so they agree on spelling. */
    @Test
    fun `the card and the list agree on the declared spelling`() {
        val entries = listOf(present("out/report.md", seq = 4))

        val card = SessionFiles.presentedForTurn(entries, turn = 1).single()
        val row = SessionFiles.derive(entries).single()
        assertEquals(card.path, row.path)
    }
}
