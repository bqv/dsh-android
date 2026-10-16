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

    /**
     * A picture: the file's own bytes, undecoded.
     *
     * Bytes rather than a bitmap, because decoding belongs to the platform and this
     * model layer is pure Kotlin — the pane decodes off the main thread and holds the
     * result for as long as the path is selected. [sizeBytes] is the host's reported
     * file size, which is what the caption shows; it is not necessarily
     * `bytes.size`, since only a bounded window is ever fetched.
     */
    data class Image(
        val bytes: ByteArray,
        val format: PreviewFormat,
        val sizeBytes: Int? = null,
        val absolutePath: String? = null,
    ) : FilePreview {

        override fun equals(other: Any?): Boolean =
            other is Image && other.format == format && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * format.hashCode() + bytes.contentHashCode()
    }
}

/** What a path is, as far as previewing it goes. */
enum class PreviewFormat {
    TEXT,
    PNG,
    JPEG,
    WEBP,
    GIF,
    BMP,
    ICO,
    /**
     * HEIF/AVIF decode only from API 28 and 30 respectively, above this app's
     * `minSdk` of 26 — where the decoder returns nothing and the pane says the image
     * could not be decoded. That is the honest outcome: reading the *bytes* as text
     * would be worse than saying so.
     */
    HEIC,
    AVIF,
    SVG,
    ;

    val isImage: Boolean get() = this != TEXT
}

/**
 * The format a path names, by extension.
 *
 * Extension rather than sniffing the bytes, which is what the web's viewer does and
 * what makes the *read* choice possible at all: a picture has to be fetched as bytes
 * (`workspaceFiles/readBytes`) and a document as text, and that decision has to be
 * made before the first byte arrives. The cost is that a misnamed file is previewed
 * as what it claims to be — the panel says which it chose, and a decode that fails
 * says so rather than showing nothing.
 *
 * Case-insensitive and query-less, because both occur: a `.PNG` off a camera and a
 * `?raw=1` tail off a URL a session wrote.
 */
fun previewFormatOf(path: String): PreviewFormat {
    val extension = extensionOf(previewNameOf(path))
    return when (extension) {
        "png", "apng" -> PreviewFormat.PNG
        "jpg", "jpeg", "jpe", "jfif" -> PreviewFormat.JPEG
        "webp" -> PreviewFormat.WEBP
        "gif" -> PreviewFormat.GIF
        "bmp" -> PreviewFormat.BMP
        "ico" -> PreviewFormat.ICO
        "heic", "heif" -> PreviewFormat.HEIC
        "avif" -> PreviewFormat.AVIF
        "svg" -> PreviewFormat.SVG
        else -> PreviewFormat.TEXT
    }
}

/** The file name alone, query and fragment stripped; the one name both tables read. */
private fun previewNameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').substringBefore('?').substringBefore('#')

/** The lower-cased suffix after the last dot; empty for a name with no dot at all. */
private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/**
 * The language id a path names, by extension.
 *
 * This is the read card's table (a port of the web's `read-render.ts`), moved here so
 * the read card and the Files panel cannot answer differently about what a `.kt` file
 * is. It is a *claim*, not a fact: nothing here looks inside the file, so a `.py` that
 * holds a shell script is labelled `py`. That is why the claim is drawn as a chip
 * beside the content rather than used to decide what the content means, and why the
 * panel offers the source for any file it renders.
 *
 * A dotfile and an unknown extension both answer `null` — no claim at all, which is
 * what keeps a `.log` or an extensionless file from being coloured as code.
 */
fun previewLanguageOf(path: String): String? {
    val name = previewNameOf(path)
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return null
    return LANGUAGE_BY_EXTENSION[name.substring(dot + 1).lowercase()]
}

/**
 * True when the panel will *render* this path rather than only colour it.
 *
 * Deliberately three extensions and no sniffing of the content: a document whose first
 * line happens to be `#` is not a promise that the rest of it is Markdown, and a
 * renderer that guesses wrong reflows prose the author laid out on purpose. The reader
 * always has the source one tap away, and the toggle names it.
 *
 * `.mdx` is not here: its body is Markdown *plus* JSX, and rendering half of it as
 * prose would be a claim the file does not support.
 */
fun isMarkdownPath(path: String): Boolean =
    extensionOf(previewNameOf(path)) in MARKDOWN_EXTENSIONS

/** The extensions whose whole content is Markdown. */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown")

/**
 * Extension to language id, verbatim from the read card's `LANG_BY_EXTENSION`, which
 * is itself the web's `read-render.ts` table, with one addition: `kts`, the Kotlin
 * script suffix (`build.gradle.kts` is a Kotlin file by any reading, and it is this
 * project's own build file). The read card picks the addition up too, since it now asks
 * this table rather than keeping its own copy.
 */
private val LANGUAGE_BY_EXTENSION = mapOf(
    "ts" to "ts", "tsx" to "tsx", "mts" to "ts", "cts" to "ts",
    "js" to "js", "jsx" to "jsx", "mjs" to "js", "cjs" to "js",
    "json" to "json", "jsonc" to "json", "py" to "py", "rb" to "rb", "go" to "go", "rs" to "rs",
    "java" to "java", "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp",
    "cxx" to "cpp", "cs" to "cs", "kt" to "kotlin", "kts" to "kotlin", "swift" to "swift", "php" to "php",
    "sh" to "sh", "bash" to "sh", "zsh" to "sh", "yaml" to "yaml", "yml" to "yaml",
    "toml" to "toml", "ini" to "ini", "md" to "md", "markdown" to "md", "mdx" to "mdx",
    "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss", "less" to "less",
    "sql" to "sql", "xml" to "xml", "lua" to "lua",
)

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
