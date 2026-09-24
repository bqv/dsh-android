package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.markdown.MarkdownText
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

internal data class WebSource(
    val url: String,
    val title: String?,
    val snippet: String?,
    val publishedAt: String?,
)

/** The web card's material: a provider answer over sources, or a fetch summary. */
internal sealed interface WebCardModel {
    data class Search(
        val answer: String?,
        val sources: List<WebSource>,
        val truncated: Boolean,
    ) : WebCardModel

    data class Fetch(
        val url: String,
        val statusCode: Int,
        val truncated: Boolean,
    ) : WebCardModel
}

/**
 * Derive the `web_search`/`web_fetch` card from the result's `meta`, narrowing
 * every field the way `web-card-model.ts` does. Null (the generic row) when the
 * payload does not match its declared shape.
 */
internal fun webCardModel(name: String, isError: Boolean, meta: JSONObject?): WebCardModel? {
    if (isError || meta == null) return null
    val truncated = meta.opt("truncated") as? Boolean ?: return null
    if (name == "web_search") {
        val answer = when (val value = meta.opt("answer")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return null
        }
        val array = meta.optJSONArray("sources") ?: return null
        val sources = ArrayList<WebSource>(array.length())
        for (i in 0 until array.length()) {
            val source = array.optJSONObject(i) ?: return null
            val url = source.opt("url") as? String ?: return null
            // A present-but-mistyped optional field declines the whole card, the
            // way the web's `webSources` narrows each one.
            for (key in listOf("title", "snippet", "publishedAt")) {
                val value = source.opt(key)
                if (value != null && value !== JSONObject.NULL && value !is String) return null
            }
            sources.add(
                WebSource(
                    url = url,
                    title = source.opt("title") as? String,
                    snippet = source.opt("snippet") as? String,
                    publishedAt = source.opt("publishedAt") as? String,
                ),
            )
        }
        return WebCardModel.Search(answer, sources, truncated)
    }
    if (name != "web_fetch") return null
    val url = meta.opt("url") as? String ?: return null
    val statusCode = (meta.opt("statusCode") as? Number)?.toInt() ?: return null
    return WebCardModel.Fetch(url, statusCode, truncated)
}

/** The link label: the title, else the URL's hostname, else the raw URL. */
private fun linkLabel(url: String, title: String?): String {
    if (!title.isNullOrEmpty()) return title
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
    return host.ifEmpty { url }
}

@Composable
internal fun WebCallRow(entry: ChatEntry.ToolCall, model: WebCardModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val title = if (entry.name == "web_fetch") "Fetch" else "Search"
    val summary = remember(entry.arguments, entry.name) {
        runCatching { JSONObject(entry.arguments) }.getOrNull()?.let { args ->
            when (entry.name) {
                "web_search" -> args.optJSONArray("queries")?.let { queries ->
                    (0 until queries.length()).mapNotNull { queries.opt(it) as? String }.joinToString(", ")
                }
                else -> args.optString("url")
            }
        }.orEmpty()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    expanded -> Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    entry.name == "web_fetch" -> Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    else -> Icon(
                        Icons.Rounded.Language,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(title, style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(DshSpacing.md))
                Box(
                    Modifier
                        .size(2.dp)
                        .clip(CircleShape)
                        .background(colors.labelCaption),
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = summary,
                    style = DshType.rowSummary,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            WebBlockCard(model)
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

@Composable
private fun WebBlockCard(model: WebCardModel) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock)
            .padding(horizontal = 14.dp, vertical = DshSpacing.lg),
    ) {
        when (model) {
            is WebCardModel.Fetch -> {
                Text(
                    text = model.url,
                    style = DshType.codeSmall,
                    color = colors.link,
                )
                Spacer(Modifier.height(DshSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HTTP ${model.statusCode}", style = DshType.bodyMedium, color = colors.labelSecondary)
                    if (model.truncated) {
                        Spacer(Modifier.width(DshSpacing.lg))
                        Text("Content truncated", style = DshType.bodyMedium, color = colors.labelTertiary)
                    }
                }
            }

            is WebCardModel.Search -> {
                val answer = model.answer
                val empty = answer.isNullOrEmpty() && model.sources.isEmpty()
                if (!answer.isNullOrEmpty()) {
                    MarkdownText(answer)
                    if (!empty) Spacer(Modifier.height(DshSpacing.md))
                }
                if (empty) {
                    Text("No results found", style = DshType.bodyMedium, color = colors.labelSecondary)
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        model.sources.forEachIndexed { index, source ->
                            Row {
                                Text(
                                    text = "${index + 1}.",
                                    style = DshType.bodyMedium,
                                    color = colors.labelTertiary,
                                )
                                Spacer(Modifier.width(DshSpacing.md))
                                Column {
                                    Text(
                                        text = linkLabel(source.url, source.title),
                                        style = DshType.bodyMedium,
                                        color = colors.link,
                                    )
                                    source.snippet?.takeIf { it.isNotEmpty() }?.let {
                                        Text(it, style = DshType.bodyMedium, color = colors.labelSecondary)
                                    }
                                    source.publishedAt?.takeIf { it.isNotEmpty() }?.let {
                                        Text(it, style = DshType.bodyMedium, color = colors.labelTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
                if (model.truncated) {
                    Spacer(Modifier.height(DshSpacing.md))
                    Text("Source list truncated", style = DshType.bodyMedium, color = colors.labelTertiary)
                }
            }
        }
    }
}
