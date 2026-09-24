package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The session's files, derived from the durable transcript.
 *
 * The web has two unrelated surfaces here and this file serves both from one
 * pass over [ChatEntry] rows:
 *
 *  - the **Files sidebar** is a live directory tree, not a transcript view — the
 *    web reads it with `workspaceFiles/list` rooted at the session cwd. The app
 *    has no directory tree yet, so the panel lists what the session actually
 *    *touched*: every read, write, and edit the transcript recorded. That is a
 *    deliberate divergence, and it is why [derive] exists at all.
 *  - the **deliverables turn-tail row** (`turn-deliverables.ts`) is transcript
 *    derived, and its rule is exact: a turn's produced files are the successful
 *    first-party mutation calls — `write`, `edit`, and mutating
 *    `str_replace_editor` — never reads and never the closing prose. [mutationOf]
 *    ports that rule field for field.
 *
 * Only successful calls count, on both surfaces: a failed write produced
 * nothing, and neither did a call still in flight. Reads are an app-side
 * addition for the Files panel; the web's produced-files rule has no read case
 * at all.
 *
 * Paths keep their exact spelling from the tool arguments (no normalization), so
 * two spellings of one file stay two rows — the web's produced paths are deduped
 * by exact string too.
 */
enum class FileTouch { READ, WRITTEN, EDITED }

/** One path the session touched, in first-seen order. */
data class SessionFile(
    val path: String,
    /**
     * Distinct kinds of access in the order they first happened, so a file
     * written then read shows `[WRITTEN, READ]` and a file read twice shows
     * `[READ]`.
     */
    val touches: List<FileTouch>,
    val reads: Int,
    val writes: Int,
    val edits: Int,
    /** Seq of the first tool call that touched it; the list's sort key. */
    val firstSeq: Int,
    /** Seq of the most recent tool call that touched it. */
    val lastSeq: Int,
) {
    /** What the latest call did; the row's badge. */
    val lastTouch: FileTouch get() = touches.lastOrNull() ?: FileTouch.READ

    /** True when the session changed the file at least once. */
    val modified: Boolean get() = writes > 0 || edits > 0

    val basename: String get() = fileNameOf(path)
}

/** One numbered line of a preview, keeping the file's own 1-based numbering. */
data class PreviewLine(val number: Int, val text: String)

// Text content harvested here is delivered as `FilePreview.Ready`, whose
// [offset]/[totalLines] come from the `read` result's `meta` window
// (`{lines:[{number,text}],offset,path,totalLines,lang}`), so a preview can say
// "Showing N of M lines" exactly as the chat read card does. The transcript
// source and the `workspaceFiles/read` loader share that one type so the panel
// renders a single vocabulary.

/** A file the turn wrote or edited, in first-seen order. */
data class ProducedFile(val path: String, val touch: FileTouch, val seq: Int) {
    val basename: String get() = fileNameOf(path)
}

/** A file the turn declared through the `present` tool. */
data class PresentedFile(
    val path: String,
    val description: String?,
    /** Seq of the `present` call; with [index] it is the card's stable key. */
    val seq: Int,
    /** Original index in the call's `files` array. */
    val index: Int,
) {
    val basename: String get() = fileNameOf(path)
}

/**
 * The files one turn produced and declared.
 *
 * [isEmpty] is the row's whole visibility rule, mirroring the web's
 * `selectDeliverables` returning null: a turn that wrote nothing and declared
 * nothing renders no row at all.
 */
data class TurnDeliverables(
    val produced: List<ProducedFile> = emptyList(),
    val presented: List<PresentedFile> = emptyList(),
) {
    val isEmpty: Boolean get() = produced.isEmpty() && presented.isEmpty()
}

/**
 * Pure derivation of the session's touched files, their previews, and one turn's
 * deliverables. No Android types: the UI layer consumes the results directly.
 */
object SessionFiles {

    /** Wire names whose successful result means the call *read* a path. */
    private val READ_TOOLS = setOf("read", "read_file", "read_image")

    // ------------------------------------------------------------ files panel

    /**
     * Every path the transcript's successful tool calls touched, in first-seen
     * order. A path written twice is one row; the counts say what happened.
     */
    fun derive(entries: List<ChatEntry>): List<SessionFile> {
        val order = ArrayList<String>()
        val files = HashMap<String, MutableFile>()
        for (entry in entries) {
            val hit = touchOf(entry) ?: continue
            val file = files.getOrPut(hit.path) {
                order += hit.path
                MutableFile()
            }
            file.record(hit.touch, entry.seq)
        }
        return order.map { path -> files.getValue(path).freeze(path) }
    }

    /**
     * Content already present in tool results, keyed by exact path.
     *
     * The latest successful read of a path wins. This is the FilesPanel's first
     * choice — the web resolves the session resource before the workspace file —
     * and the durable transcript already holds the window the host sent, so a
     * file the session read previews with no extra RPC.
     */
    fun previews(entries: List<ChatEntry>): Map<String, FilePreview.Ready> {
        val result = LinkedHashMap<String, FilePreview.Ready>()
        for (entry in entries) {
            val call = entry as? ChatEntry.ToolCall ?: continue
            if (call.result == null || call.isError) continue
            if (call.name.lowercase() !in READ_TOOLS) continue
            // Keyed by the argument spelling, which is what `derive` recorded:
            // the result's echoed path can be shortened or relative.
            val path = argPathOf(call) ?: continue
            val preview = previewOf(call) ?: continue
            result[path] = preview
        }
        return result
    }

    // ------------------------------------------------------------ deliverables

    /** Both lists for one turn; the parent renders nothing when [TurnDeliverables.isEmpty]. */
    fun deliverablesFor(
        entries: List<ChatEntry>,
        turn: Int,
        throughSeq: Int = Int.MAX_VALUE,
    ): TurnDeliverables = TurnDeliverables(
        produced = producedForTurn(entries, turn, throughSeq),
        presented = presentedForTurn(entries, turn, throughSeq),
    )

    /**
     * Successful mutations of one turn, deduped by exact path in first-seen
     * order.
     *
     * [throughSeq] is the closing assistant message's seq. The web excludes a
     * tool settlement that lands *after* the closing prose
     * (`producedForClosing(data, owner.seq)`), so this does too — with one
     * approximation: the entry keeps the `tool/call` seq, because
     * `ChatEntry.ToolCall` does not retain its result's seq, so a mutation that
     * settles after the closing prose is included here where the web would drop
     * it.
     */
    fun producedForTurn(
        entries: List<ChatEntry>,
        turn: Int,
        throughSeq: Int = Int.MAX_VALUE,
    ): List<ProducedFile> {
        val seen = HashSet<String>()
        val produced = ArrayList<ProducedFile>()
        for (entry in entries) {
            val call = entry as? ChatEntry.ToolCall ?: continue
            if (call.turn != turn || call.seq > throughSeq) continue
            if (call.result == null || call.isError) continue
            val hit = mutationOf(call) ?: continue
            if (!seen.add(hit.path)) continue
            produced += ProducedFile(hit.path, hit.touch, call.seq)
        }
        return produced.sortedBy { it.seq }
    }

    /**
     * The latest `present` declaration of each path before [throughSeq], in
     * first-seen path order.
     *
     * The web publishes a `deliverables/presented` durable event when the tool
     * result settles; the transcript does not fold that event, but a successful
     * `present` call carries the same `{files:[{path,description}]}` arguments,
     * which is the same declaration one step earlier. A path declared twice
     * keeps the later description in the earlier position, exactly as the web's
     * `presentedForClosing` Map does.
     */
    fun presentedForTurn(
        entries: List<ChatEntry>,
        turn: Int,
        throughSeq: Int = Int.MAX_VALUE,
    ): List<PresentedFile> {
        val byPath = LinkedHashMap<String, PresentedFile>()
        for (entry in entries) {
            val call = entry as? ChatEntry.ToolCall ?: continue
            if (call.name.lowercase() != "present" || call.turn != turn) continue
            // The web hangs the declaration on a non-error result; a running call
            // has declared nothing yet.
            if (call.result == null || call.isError || call.seq >= throughSeq) continue
            val files = jsonObject(call.arguments)?.optJSONArray("files") ?: continue
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val path = (file.opt("path") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val description = (file.opt("description") as? String)?.takeIf { it.isNotBlank() }
                byPath[path] = PresentedFile(path, description, call.seq, index)
            }
        }
        return byPath.values.toList()
    }

    // --------------------------------------------------------------- previews

    /**
     * The structured read window, ported from the chat read card's `readMeta`
     * validation: 1-based `offset`, strictly increasing line numbers that stay
     * inside the file. Anything else declines rather than misnumbering a page.
     */
    fun parseReadMeta(meta: JSONObject?): FilePreview.Ready? {
        if (meta == null) return null
        val path = meta.opt("path") as? String ?: return null
        if (path.isBlank()) return null
        val offset = positiveInt(meta.opt("offset")) ?: 1
        val total = nonNegativeInt(meta.opt("totalLines"))
        val array = meta.optJSONArray("lines") ?: return null
        val lines = ArrayList<PreviewLine>(array.length())
        var previous = offset - 1
        for (i in 0 until array.length()) {
            val line = array.optJSONObject(i) ?: return null
            val number = positiveInt(line.opt("number")) ?: return null
            val text = line.opt("text") as? String ?: return null
            if (number <= previous || (total != null && number > total)) return null
            previous = number
            lines += PreviewLine(number, text)
        }
        val lang = when (val value = meta.opt("lang")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return null
        }
        return FilePreview.Ready(
            text = lines.joinToString("\n") { it.text },
            lines = lines.size,
            truncated = total != null && (lines.lastOrNull()?.number ?: 0) < total,
            absolutePath = path,
            offset = offset,
            totalLines = total,
            lang = lang,
        )
    }

    /**
     * The model-facing read envelope, for records that predate `meta`.
     *
     * Mirrors the read card's fallback (`<path>…</path>…<content>` with a
     * possibly `(`-prefixed footer), so an old record still previews instead of
     * falling to the generic row.
     */
    fun parseReadEnvelope(result: String, path: String? = null): FilePreview.Ready? {
        val matched = READ_ENVELOPE.matchEntire(result) ?: return null
        val body = matched.groupValues[2]
        val split = body.lastIndexOf("\n\n")
        val numbered: String
        val footer: String
        when {
            split >= 0 && body.startsWith("(", split + 2) -> {
                numbered = body.substring(0, split)
                footer = body.substring(split + 2)
            }
            // An empty file's body IS the footer.
            body.startsWith("(") -> {
                numbered = ""
                footer = body
            }
            else -> {
                numbered = body
                footer = ""
            }
        }

        val lines = ArrayList<PreviewLine>()
        if (numbered.isNotBlank()) {
            var previous = 0
            for (raw in numbered.split('\n')) {
                val line = READ_NUMBERED_LINE.matchEntire(raw) ?: return null
                val number = line.groupValues[1].toIntOrNull() ?: return null
                // Strictly increasing 1-based numbers, as the read card requires.
                if (number <= previous) return null
                previous = number
                lines += PreviewLine(number, line.groupValues[2])
            }
        }
        val displayed = matched.groupValues[1].ifBlank { path ?: return null }
        val total = READ_SHOWN.find(footer)?.groupValues?.get(3)?.toIntOrNull()
            ?: READ_TOTAL.find(footer)?.groupValues?.get(1)?.toIntOrNull()
            ?: lines.size
        val last = lines.lastOrNull()?.number ?: 0
        return FilePreview.Ready(
            text = lines.joinToString("\n") { it.text },
            lines = lines.size,
            truncated = last < total,
            absolutePath = displayed,
            offset = lines.firstOrNull()?.number ?: 1,
            totalLines = total,
        )
    }

    /**
     * A preview built from one `workspaceFiles/read` page, for the loader that
     * feeds [FilePreview].
     *
     * That RPC answers `{text, lines, offset, eof, bytes, absolutePath}`, where
     * `lines` is a *count* (unlike the transcript `meta.lines` array) — `0` for a
     * page past the file's last line and `1` for a page holding one empty line,
     * which is why it is a parameter rather than derived from [text].
     */
    fun previewOfText(
        path: String,
        text: String,
        lines: Int = if (text.isEmpty()) 0 else text.split('\n').size,
        offset: Int = 1,
        eof: Boolean = true,
        bytes: Int? = null,
        lang: String? = null,
    ): FilePreview.Ready = FilePreview.Ready(
        text = text,
        lines = lines,
        truncated = !eof,
        bytes = bytes,
        absolutePath = path,
        offset = offset,
        lang = lang,
    )

    // ---------------------------------------------------------------- internals

    private data class TouchHit(val path: String, val touch: FileTouch)

    /** The tool's own spelling of the path, which is what `derive` records. */
    private fun argPathOf(call: ChatEntry.ToolCall): String? =
        jsonObject(call.arguments)?.let { pathOf(it, "file_path", "path") }

    private fun previewOf(call: ChatEntry.ToolCall): FilePreview.Ready? =
        parseReadMeta(call.meta) ?: call.result?.let { parseReadEnvelope(it, argPathOf(call)) }

    /** What one successful call touched, or null when the call is not a file tool. */
    private fun touchOf(entry: ChatEntry): TouchHit? {
        val call = entry as? ChatEntry.ToolCall ?: return null
        if (call.result == null || call.isError) return null
        if (call.name.lowercase() in READ_TOOLS) {
            val args = jsonObject(call.arguments) ?: return null
            val path = pathOf(args, "file_path", "path") ?: return null
            return TouchHit(path, FileTouch.READ)
        }
        return mutationOf(call)
    }

    /**
     * The web's mutation vocabulary, ported from `turn-deliverables.ts`
     * `mutationPath`: wire names only (an alias or a new mutation tool needs an
     * explicit contribution there, and the app matches that conservatism), with
     * the argument validation each tool's execution requires. A malformed call
     * contributes nothing.
     */
    private fun mutationOf(call: ChatEntry.ToolCall): TouchHit? {
        if (call.result == null || call.isError) return null
        val args = jsonObject(call.arguments) ?: return null
        return when (call.name.lowercase()) {
            "write" ->
                // The web requires a string `content`; an empty string still counts.
                if (args.opt("content") is String) {
                    pathOf(args, "file_path")?.let { TouchHit(it, FileTouch.WRITTEN) }
                } else {
                    null
                }

            "edit" ->
                if (validEdit(args)) {
                    pathOf(args, "file_path")?.let { TouchHit(it, FileTouch.EDITED) }
                } else {
                    null
                }

            "str_replace_editor" -> editorMutation(args)
            else -> null
        }
    }

    /** `edit` execution requirements: a real, changing replacement. */
    private fun validEdit(args: JSONObject): Boolean {
        val old = args.opt("old_string")
        val new = args.opt("new_string")
        if (old !is String || old.isEmpty()) return false
        if (new !is String || old == new) return false
        val replaceAll = args.opt("replace_all")
        // Absent is fine; an explicit null or a non-boolean is not.
        return replaceAll == null || replaceAll is Boolean
    }

    /** `str_replace_editor`: only a complete mutating command names a path. */
    private fun editorMutation(args: JSONObject): TouchHit? {
        val path = pathOf(args, "path") ?: return null
        return when (args.optString("command")) {
            "create" ->
                if (args.opt("file_text") is String) TouchHit(path, FileTouch.WRITTEN) else null

            "str_replace" -> {
                val old = args.opt("old_str")
                val new = args.opt("new_str")
                // A pure deletion (`new_str` absent) still counts.
                if (old is String && old.isNotEmpty() && (new == null || new is String)) {
                    TouchHit(path, FileTouch.EDITED)
                } else {
                    null
                }
            }

            "insert" ->
                if (isNonNegativeInt(args.opt("insert_line")) && args.opt("new_str") is String) {
                    TouchHit(path, FileTouch.EDITED)
                } else {
                    null
                }

            else -> null
        }
    }

    private class MutableFile {
        private val touches = ArrayList<FileTouch>()
        private var reads = 0
        private var writes = 0
        private var edits = 0
        private var firstSeq = Int.MAX_VALUE
        private var lastSeq = Int.MIN_VALUE

        fun record(touch: FileTouch, seq: Int) {
            // Distinct kinds only: a file read ten times keeps one READ badge.
            if (touch !in touches) touches += touch
            when (touch) {
                FileTouch.READ -> reads += 1
                FileTouch.WRITTEN -> writes += 1
                FileTouch.EDITED -> edits += 1
            }
            if (seq < firstSeq) firstSeq = seq
            if (seq > lastSeq) lastSeq = seq
        }

        fun freeze(path: String) =
            SessionFile(path, touches.toList(), reads, writes, edits, firstSeq, lastSeq)
    }

    private fun jsonObject(raw: String): JSONObject? =
        if (raw.isBlank()) null else runCatching { JSONObject(raw) }.getOrNull()

    /** The first non-blank string among [keys], keeping the tool's exact spelling. */
    private fun pathOf(args: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = args.opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

    private fun positiveInt(value: Any?): Int? =
        (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf { it >= 1 }

    private fun nonNegativeInt(value: Any?): Int? =
        (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf { it >= 0 }

    private fun isNonNegativeInt(value: Any?): Boolean = nonNegativeInt(value) != null

    private val READ_ENVELOPE =
        Regex("^<path>([^\\n]*)</path>\\n<type>file</type>\\n<content>\\n([\\s\\S]*)\\n</content>$")
    private val READ_NUMBERED_LINE = Regex("^(\\d+): (.*)$")
    private val READ_SHOWN = Regex("Showing lines (\\d+)-(\\d+) of (\\d+)\\.")
    private val READ_TOTAL = Regex("total (\\d+) lines")
}

/** Final path segment, on either separator; the whole string when separator-free. */
internal fun fileNameOf(path: String): String {
    val at = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (at == -1) path else path.substring(at + 1)
}
