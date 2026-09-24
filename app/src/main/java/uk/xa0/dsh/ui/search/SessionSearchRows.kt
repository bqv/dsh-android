package uk.xa0.dsh.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.SessionSearch
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The web's search copy, verbatim from
 * `packages/client/ui-workspace/src/client/locales.ts` (en block, lines 95-102).
 *
 * Exposed rather than private so the caller that owns the drawer's search field
 * can use the same strings for its own statuses; [TITLE] and [CLOSE] have no web
 * counterpart (the web's search is a header state the sidebar can collapse
 * again, not a surface with its own chrome).
 */
object SessionSearchCopy {
    const val TITLE = "Search sessions"
    const val CLOSE = "Close search"
    const val PLACEHOLDER = "Search sessions..."
    const val CLEAR = "Clear search"
    const val PENDING = "Searching session history…"
    const val UNAVAILABLE = "Content search is temporarily unavailable. Showing name matches."
    const val NO_MATCHES = "No matching sessions"
    const val UNGROUPED = "Ungrouped"

    /** `search.hasMore`, with `{n}` filled from the host's own result bound. */
    fun hasMore(limit: Int = SessionSearch.RESULT_LIMIT): String =
        "Showing the first $limit results. Narrow your search."
}

/**
 * One resolved search row: a host [uk.xa0.dsh.model.SessionSearchHit] joined with
 * the metadata the host did not send.
 *
 * `session/search` answers with `{sessionId, snippet}` only, so the caller
 * resolves [title] and [workspace] from its own roster — the same join the web
 * performs in `deriveSearchResults`. [workspace] blank renders
 * [SessionSearchCopy.UNGROUPED], which is the web's own fallback; [snippet] blank
 * means a name-only match and draws no excerpt line, again as the web does.
 */
data class SessionSearchRow(
    val sessionId: String,
    val title: String,
    val workspace: String = "",
    val snippet: String = "",
)

/**
 * The web's `SearchResults` body — the hit rows followed by its status lines, in
 * the web's order: pending, then the unavailable warning, then the empty line,
 * then the hasMore hint. The web renders the warning and the empty line together
 * once the content call failed and nothing local matched; so does this.
 *
 * Deliberately a plain [Column], not a `LazyColumn`: the caller may be a drawer
 * that already scrolls, and nesting two vertical scrollables is a crash. Wrap it
 * in whatever scroll the host surface owns.
 *
 * One intentional divergence: the snippet wraps to two lines instead of the
 * web's single `white-space: nowrap` line. The host centres the match in up to
 * 240 code points, so on a 360dp screen one line would ellipsize the match away
 * more often than not.
 */
@Composable
fun SessionSearchBody(
    rows: List<SessionSearchRow>,
    query: String,
    loading: Boolean,
    error: String?,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    hasMore: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        SessionSearchRows(rows = rows, query = query, onResultClick = onResultClick)
        SessionSearchStatus(
            loading = loading,
            error = error,
            empty = rows.isEmpty(),
            hasMore = hasMore,
        )
    }
}

/**
 * The content-hit rows only — no input, no statuses, no scrolling.
 *
 * Safe against a caller that merges its local name matches with the host page
 * and repeats a session id: the first occurrence keeps its position and a later
 * duplicate donates its snippet, which is the web's rule for a session that is
 * both a name match and a content match.
 */
@Composable
fun SessionSearchRows(
    rows: List<SessionSearchRow>,
    query: String,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        uniqueRows(rows).forEach { row ->
            SessionSearchRowItem(row = row, query = query, onResultClick = onResultClick)
        }
    }
}

/** One row on its own, for a caller that renders inside its own `LazyColumn`. */
@Composable
fun SessionSearchRowItem(
    row: SessionSearchRow,
    query: String,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val workspace = row.workspace.ifBlank { SessionSearchCopy.UNGROUPED }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple { onResultClick(row.sessionId) }
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
    ) {
        Text(
            text = row.title,
            style = DshType.titleSmall,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = workspace,
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The web caps the workspace at 40% of the row so a long
                // directory name can never crowd the excerpt out.
                modifier = Modifier.widthIn(max = 140.dp),
            )
            if (row.snippet.isNotBlank()) {
                Spacer(Modifier.width(DshSpacing.sm))
                Text(
                    text = snippetText(row.snippet, query, colors.accent),
                    style = DshType.bodySmall,
                    color = colors.labelSecondary,
                    maxLines = SNIPPET_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The web's status lines. [error] is rendered as given, or as the web's fixed
 * [SessionSearchCopy.UNAVAILABLE] copy when the caller has no message of its own
 * — the host's own text ("session search failed: …") is an internal detail.
 */
@Composable
fun SessionSearchStatus(
    loading: Boolean,
    error: String?,
    empty: Boolean,
    hasMore: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (loading) StatusLine(SessionSearchCopy.PENDING, colors.labelTertiary)
        if (error != null) {
            StatusLine(error.ifBlank { SessionSearchCopy.UNAVAILABLE }, colors.labelSecondary)
        }
        if (!loading && empty) StatusLine(SessionSearchCopy.NO_MATCHES, colors.labelTertiary)
        if (hasMore) StatusLine(SessionSearchCopy.hasMore(), colors.labelTertiary)
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text = text,
        style = DshType.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
    )
}

/**
 * The host snippet with the query emphasised.
 *
 * There is no emphasis on the wire: the provider's `highlight()` markers are
 * stripped by `makeSnippet` before the value leaves the host, so the only way to
 * point at the match is to find the query in the text again. The host collapses
 * whitespace and matches the query as one quoted phrase, so the needle is
 * collapsed the same way; when the plain substring is absent — FTS tokenizes on
 * punctuation the snippet still displays, so "a-b" can match "a b" — the longest
 * single token is tried once. Failure renders the excerpt unemphasised, which is
 * what the web does for every hit.
 */
@Composable
private fun snippetText(snippet: String, query: String, emphasis: Color): AnnotatedString =
    remember(snippet, query, emphasis) {
        buildAnnotatedString {
            append(snippet)
            val range = matchRange(snippet, query) ?: return@buildAnnotatedString
            addStyle(
                SpanStyle(color = emphasis, fontWeight = FontWeight.SemiBold),
                range.first,
                range.last + 1,
            )
        }
    }

private fun matchRange(text: String, query: String): IntRange? {
    val needle = query.trim().replace(WHITESPACE, " ")
    if (needle.isEmpty()) return null
    indexOfIgnoreCase(text, needle)?.let { return it until it + needle.length }
    val token = needle.split(' ').filter { it.isNotEmpty() }.maxByOrNull { it.length } ?: return null
    val at = indexOfIgnoreCase(text, token) ?: return null
    return at until at + token.length
}

private fun indexOfIgnoreCase(text: String, needle: String): Int? =
    text.indexOf(needle, ignoreCase = true).takeIf { it >= 0 }

private fun dedupe(rows: List<SessionSearchRow>): List<SessionSearchRow> {
    if (rows.size < 2) return rows
    val byId = LinkedHashMap<String, SessionSearchRow>(rows.size)
    for (row in rows) {
        val kept = byId[row.sessionId]
        byId[row.sessionId] = when {
            kept == null -> row
            kept.snippet.isBlank() && row.snippet.isNotBlank() -> kept.copy(snippet = row.snippet)
            else -> kept
        }
    }
    return byId.values.toList()
}

@Composable
private fun uniqueRows(rows: List<SessionSearchRow>): List<SessionSearchRow> =
    remember(rows) { dedupe(rows) }

private val WHITESPACE = Regex("\\s+")

/** Snippet lines on a phone; see the body's divergence note. */
private const val SNIPPET_LINES = 2
