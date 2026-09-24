package uk.xa0.dsh.model

/**
 * One file's preview content, whatever answered for it.
 *
 * Two host surfaces can answer and they disagree on shape: a transcript read
 * carries numbered `meta.lines` objects, while `workspaceFiles/read` answers one
 * raw page — `{absolutePath, version, bytes, offset, text, lines, eof}`, where
 * `lines` is a *count*, not a list. Both land here so the Files panel renders one
 * vocabulary, and it keeps the web's resolution order: the session resource (the
 * durable transcript window, no RPC) is the first choice and the workspace read
 * is the fallback.
 */
sealed interface FilePreview {

    /** A workspace read is in flight and nothing is held for the path yet. */
    data object Loading : FilePreview

    /**
     * A text page.
     *
     * [text], [lines], [truncated], [bytes] and [absolutePath] are the
     * `workspaceFiles/read` reply, field for field: the page's lines joined by
     * `\n`, how many it holds (`0` when the offset lies past the file's last
     * line), whether the page stops short of `eof`, the reported size, and the
     * host's resolved path. [bytes] and [absolutePath] are null when the host
     * omitted them.
     *
     * [offset], [totalLines] and [lang] are app-side additions that only the
     * transcript source fills: a `read` result knows which line its window
     * started at and how long the whole file is, which the paged endpoint does
     * not report.
     */
    data class Ready(
        val text: String,
        val lines: Int,
        val truncated: Boolean,
        val bytes: Int? = null,
        val absolutePath: String? = null,
        val offset: Int = 1,
        val totalLines: Int? = null,
        val lang: String? = null,
    ) : FilePreview {

        /** [text] split back into the file's own numbered lines; computed once. */
        val numbered: List<PreviewLine> =
            if (lines <= 0) {
                emptyList()
            } else {
                text.split('\n').mapIndexed { index, line -> PreviewLine(offset + index, line) }
            }

        /** Fewer lines than the file's reported total: the banner states the window. */
        val windowed: Boolean get() = totalLines != null && offset + lines - 1 < totalLines
    }

    /** The host refused the read; [message] is its own user-readable wording. */
    data class Unavailable(val message: String) : FilePreview
}

/**
 * Reads one workspace file on demand, for a panel that has no transcript preview.
 *
 * Injected so the panel stays host-free: the parent binds the session scope id
 * and the `workspaceFiles/read` call. A host refusal travels as
 * [FilePreview.Unavailable] rather than an exception, because the body renders
 * the host's own wording; an implementation may still throw, which the panel
 * renders the same way.
 */
fun interface WorkspaceFileLoader {
    suspend fun load(path: String): FilePreview
}
