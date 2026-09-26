package uk.xa0.dsh.ui.markdown

/**
 * A deliberately small, streaming-tolerant Markdown parser.
 *
 * It is not a spec-complete CommonMark implementation; it covers the subset DSH
 * assistant messages actually use (headings, lists, task lists, tables, fenced
 * code, block quotes, rules, inline emphasis/code/links) and — importantly — it
 * stays sane on a *partial* document, which is what arrives while tokens stream
 * in. An unclosed fence simply swallows the tail as code rather than flickering
 * the layout, and a table is only recognised once its `|---|` separator lands.
 */

enum class InlineStyle { NORMAL, BOLD, ITALIC, BOLD_ITALIC, CODE, LINK, STRIKE }

data class InlineToken(
    val text: String,
    val style: InlineStyle = InlineStyle.NORMAL,
    val url: String? = null,
)

/** One list entry; [checked] is non-null for a `- [x]` task item. */
data class ListItem(
    val tokens: List<InlineToken>,
    val depth: Int = 0,
    val checked: Boolean? = null,
)

sealed interface MdBlock {
    data class Paragraph(val tokens: List<InlineToken>) : MdBlock
    data class Heading(val level: Int, val tokens: List<InlineToken>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class BulletList(val items: List<ListItem>) : MdBlock
    data class NumberedList(val items: List<ListItem>) : MdBlock
    data class Table(val header: List<List<InlineToken>>, val rows: List<List<List<InlineToken>>>) : MdBlock
    data class Quote(val tokens: List<InlineToken>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        val bullets = mutableListOf<ListItem>()
        val numbered = mutableListOf<ListItem>()
        val quote = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                blocks += MdBlock.BulletList(bullets.toList())
                bullets.clear()
            }
        }

        fun flushNumbered() {
            if (numbered.isNotEmpty()) {
                blocks += MdBlock.NumberedList(numbered.toList())
                numbered.clear()
            }
        }

        fun flushQuote() {
            if (quote.isNotEmpty()) {
                blocks += MdBlock.Quote(parseInline(quote.joinToString("\n")))
                quote.clear()
            }
        }

        fun flushAll() {
            flushParagraph(); flushBullets(); flushNumbered(); flushQuote()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code ---------------------------------------------------
            val fence = line.trimStart()
            if (fence.startsWith("```") || fence.startsWith("~~~")) {
                flushAll()
                val marker = fence.take(3)
                val language = fence.removePrefix(marker).trim().ifEmpty { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    body.append(lines[i])
                    if (i < lines.size - 1) body.append('\n')
                    i++
                }
                // Skip the closing fence when present (absent mid-stream).
                if (i < lines.size) i++
                blocks += MdBlock.Code(language, body.toString().trimEnd('\n'))
                continue
            }

            // Table ---------------------------------------------------------
            if (i + 1 < lines.size && isTableRow(line) && isTableSeparator(lines[i + 1])) {
                flushAll()
                val header = splitRow(line)
                val rows = mutableListOf<List<List<InlineToken>>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i])) {
                    rows += splitRow(lines[i])
                    i++
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }

            val trimmed = line.trim()
            val indent = (line.length - line.trimStart().length) / 2

            when {
                trimmed.isEmpty() -> {
                    flushAll()
                    i++
                }

                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    flushAll()
                    blocks += MdBlock.Rule
                    i++
                }

                trimmed.startsWith("#") -> {
                    flushAll()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                    blocks += MdBlock.Heading(level, parseInline(trimmed.drop(level).trim()))
                    i++
                }

                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushParagraph(); flushBullets(); flushNumbered()
                    quote += trimmed.removePrefix(">").removePrefix(" ").trimEnd()
                    i++
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    flushParagraph(); flushNumbered(); flushQuote()
                    bullets += listItem(trimmed.drop(2).trim(), indent)
                    i++
                }

                NUMBERED.containsMatchIn(trimmed) -> {
                    flushParagraph(); flushBullets(); flushQuote()
                    numbered += listItem(trimmed.replaceFirst(NUMBERED, ""), indent)
                    i++
                }

                else -> {
                    flushBullets(); flushNumbered(); flushQuote()
                    paragraph += line
                    i++
                }
            }
        }

        flushAll()
        return blocks
    }

    /** Recognises `- [x] done` / `- [ ] todo` alongside a plain entry. */
    private fun listItem(text: String, depth: Int): ListItem {
        val task = TASK.find(text)
        return if (task != null) {
            ListItem(
                tokens = parseInline(text.substring(task.range.last + 1).trim()),
                depth = depth,
                checked = task.groupValues[1].equals("x", ignoreCase = true),
            )
        } else {
            ListItem(tokens = parseInline(text), depth = depth)
        }
    }

    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= 2 && trimmed.contains('|')
    }

    /** The `|---|:--:|` row that turns the preceding line into a table header. */
    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim().trim('|', ' ')
        if (trimmed.isEmpty()) return false
        val cells = trimmed.split('|')
        return cells.isNotEmpty() && cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' }
        }
    }

    private fun splitRow(line: String): List<List<InlineToken>> =
        line.trim().trim('|').split('|').map { parseInline(it.trim()) }

    /** Inline emphasis / code / links, in the order that matters for nesting. */
    fun parseInline(text: String): List<InlineToken> {
        val tokens = mutableListOf<InlineToken>()
        val buffer = StringBuilder()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += InlineToken(buffer.toString())
                buffer.clear()
            }
        }

        while (i < text.length) {
            val rest = text.substring(i)

            // Inline code wins over everything else.
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    tokens += InlineToken(text.substring(i + 1, end), InlineStyle.CODE)
                    i = end + 1
                    continue
                }
            }

            if (rest.startsWith("**") || rest.startsWith("__")) {
                val marker = rest.take(2)
                val end = rest.indexOf(marker, 2)
                if (end > 0) {
                    flush()
                    tokens += InlineToken(rest.substring(2, end), InlineStyle.BOLD)
                    i += end + 2
                    continue
                }
            }

            if (rest.startsWith("~~")) {
                val end = rest.indexOf("~~", 2)
                if (end > 0) {
                    flush()
                    tokens += InlineToken(rest.substring(2, end), InlineStyle.STRIKE)
                    i += end + 2
                    continue
                }
            }

            if (rest.startsWith("*") || rest.startsWith("_")) {
                val marker = rest[0]
                val end = rest.indexOf(marker, 1)
                if (end > 0 && !rest.substring(1, end).contains(' ')) {
                    flush()
                    tokens += InlineToken(rest.substring(1, end), InlineStyle.ITALIC)
                    i += end + 1
                    continue
                }
            }

            if (text[i] == '[') {
                val close = text.indexOf(']', i)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        flush()
                        tokens += InlineToken(
                            text = text.substring(i + 1, close),
                            style = InlineStyle.LINK,
                            url = text.substring(close + 2, urlEnd),
                        )
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            // A bare URL is a link whether or not it was written as one.
            //
            // Assistant messages are full of them — a release page, a file on a host,
            // an issue — and `[text](url)` is the form people write when they are
            // being *careful*; the common case on screen is the URL by itself. GFM's
            // rule, so the two clients agree on what counts: `http://`, `https://`
            // and a `www.` host, and nothing else.
            //
            // Guarded on the first character because the parser runs per character
            // over text that is still streaming in, and a regex scan at every
            // position would be quadratic for no reason.
            val auto = if (text[i] == 'h' || text[i] == 'w') AUTOLINK.find(text, i) else null
            // A link starts at a word boundary: `xhttps://example.dev` is a word that
            // happens to contain one, not a URL, and linking it would also make the
            // link's text disagree with what is on screen.
            val atBoundary = i == 0 || !text[i - 1].isLetterOrDigit()
            if (auto != null && auto.range.first == i && atBoundary) {
                val (link, end) = trimUrlEnd(auto.value)
                if (link.isNotEmpty()) {
                    flush()
                    tokens += InlineToken(
                        text = link,
                        style = InlineStyle.LINK,
                        // `www.example.com` has no scheme; both a browser intent and
                        // the web client read it as HTTPS.
                        url = if (link.startsWith("www.")) "https://$link" else link,
                    )
                    i += end
                    continue
                }
            }

            buffer.append(text[i])
            i++
        }
        flush()
        return tokens
    }

    /**
     * `https://…`, `http://…` or a `www.` host, as GFM autolinks them.
     *
     * Brackets are allowed *inside* the match on purpose: `…/wiki/Foo_(bar)` is a
     * real URL and cutting it at the `(` mangles it. What is not part of the address
     * is a trailing closer — that is [trimUrlEnd]'s job, and it balances them.
     */
    private val AUTOLINK = Regex("(?:https?://|www\\.)[^\\s<>\"'`\\[\\]]+")

    /**
     * A URL as it should be linked, and how much of the match that consumed.
     *
     * Sentence punctuation is not part of the address: "see https://x.dev/a." ends
     * the sentence, and "(see https://x.dev/a)" closes a bracket. A closing paren
     * that *is* part of the path is kept — `…/wiki/Foo_(bar)` — which is why the
     * counts are compared rather than the character stripped blindly.
     */
    private fun trimUrlEnd(raw: String): Pair<String, Int> {
        var end = raw.length
        while (end > 0) {
            val c = raw[end - 1]
            val prefix = raw.take(end)
            val unbalanced = c == ')' && prefix.count { it == ')' } > prefix.count { it == '(' }
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || unbalanced) {
                end--
            } else {
                break
            }
        }
        return raw.take(end) to end
    }

    private val TASK = Regex("^\\[([ xX])\\]\\s+")
    // Marker only — deliberately NOT `.*` to end of line. An earlier version
    // included the rest of the line, so `replaceFirst` deleted the item's text and
    // every numbered list rendered as bare "1." "2." "3." markers.
    private val NUMBERED = Regex("^\\d+[.)]\\s+")
}

/** Tiny keyword/comment/string highlighter, enough to read code on a phone. */
object SyntaxHighlighter {

    private val KEYWORDS = setOf(
        "abstract", "as", "assert", "async", "await", "bool", "boolean", "break", "byte", "case",
        "catch", "char", "class", "const", "constructor", "continue", "data", "def", "default",
        "defer", "delete", "do", "double", "elif", "else", "enum", "except", "export", "extends",
        "false", "final", "finally", "float", "fn", "for", "from", "fun", "func", "function", "go",
        "if", "impl", "implements", "import", "in", "instanceof", "int", "interface", "is", "lambda",
        "let", "long", "match", "module", "new", "nil", "None", "not", "null", "object", "or",
        "package", "private", "protected", "public", "raise", "readonly", "return", "select",
        "self", "static", "struct", "super", "switch", "this", "throw", "trait", "true", "try",
        "type", "typeof", "val", "var", "void", "when", "where", "while", "with", "yield",
    )

    data class Span(val text: String, val kind: Kind)

    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, PUNCTUATION }

    fun highlightLine(line: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += Span(plain.toString(), Kind.PLAIN)
                plain.clear()
            }
        }

        while (i < line.length) {
            val rest = line.substring(i)

            // Line comments
            if (rest.startsWith("//") || rest.startsWith("# ") || rest.startsWith("-- ") ||
                (rest.startsWith("#") && line.trimStart().startsWith("#") && !rest.startsWith("#!"))
            ) {
                flushPlain()
                spans += Span(rest, Kind.COMMENT)
                return spans
            }

            // Strings
            if (line[i] == '"' || line[i] == '\'' || line[i] == '`') {
                val quote = line[i]
                val end = findStringEnd(line, i, quote)
                flushPlain()
                spans += Span(line.substring(i, end), Kind.STRING)
                i = end
                continue
            }

            // Numbers
            if (line[i].isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                flushPlain()
                spans += Span(line.substring(i, j), Kind.NUMBER)
                i = j
                continue
            }

            // Identifiers
            if (line[i].isLetter() || line[i] == '_') {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                flushPlain()
                val kind = when {
                    word in KEYWORDS -> Kind.KEYWORD
                    j < line.length && line[j] == '(' -> Kind.FUNCTION
                    else -> Kind.PLAIN
                }
                spans += Span(word, kind)
                i = j
                continue
            }

            if (!line[i].isLetterOrDigit() && !line[i].isWhitespace()) {
                flushPlain()
                spans += Span(line[i].toString(), Kind.PUNCTUATION)
                i++
                continue
            }

            plain.append(line[i])
            i++
        }
        flushPlain()
        return spans
    }

    private fun findStringEnd(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }
}
