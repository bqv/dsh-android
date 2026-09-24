package uk.xa0.dsh.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshColors
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * Renders the parsed markdown subset as real Compose text.
 *
 * No WebView, no TextView-with-HTML: every block becomes Compose layout, so
 * selection, theming and scrolling behave natively.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DshTheme.colors.labelPrimary,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val colors = DshTheme.colors

    Column(modifier, verticalArrangement = Arrangement.spacedBy(DshSpacing.xl)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.tokens, DshType.messageBody, color)

                is MdBlock.Heading -> InlineText(
                    tokens = block.tokens,
                    style = when (block.level) {
                        1 -> DshType.heading1
                        2 -> DshType.heading2
                        else -> DshType.heading3
                    },
                    color = color,
                )

                is MdBlock.Code -> CodeBlock(block.language, block.code)

                is MdBlock.BulletList -> ListBlock(block.items, ordered = false)

                is MdBlock.NumberedList -> ListBlock(block.items, ordered = true)

                is MdBlock.Table -> TableBlock(block.header, block.rows)

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .background(colors.borderL4, RoundedCornerShape(1.dp)),
                    )
                    Spacer(Modifier.width(DshSpacing.lg))
                    InlineText(
                        block.tokens,
                        DshType.messageBody.copy(color = colors.labelSecondary),
                        colors.labelSecondary,
                        Modifier.weight(1f),
                    )
                }

                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.borderL3),
                )
            }
        }
    }
}

/** Bullet / numbered / task list, with indentation for nested entries. */
@Composable
private fun ListBlock(items: List<ListItem>, ordered: Boolean) {
    val colors = DshTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        items.forEachIndexed { index, item ->
            Row(Modifier.padding(start = (item.depth * 16).dp)) {
                val marker = when {
                    item.checked == true -> "✓"
                    item.checked == false -> "○"
                    ordered -> "${index + 1}."
                    else -> "•"
                }
                Text(
                    text = marker,
                    style = DshType.messageBody,
                    color = when {
                        item.checked == true -> colors.success
                        item.checked == false -> colors.labelTertiary
                        else -> colors.labelTertiary
                    },
                )
                Spacer(Modifier.width(DshSpacing.md))
                InlineText(
                    tokens = item.tokens,
                    style = DshType.messageBody,
                    color = if (item.checked == true) colors.labelTertiary else colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Tables scroll horizontally with fixed-width columns rather than squeezing into
 * the phone's width — the web UI sizes columns to content, which a 360dp viewport
 * cannot emulate readably.
 */
@Composable
private fun TableBlock(
    header: List<List<InlineToken>>,
    rows: List<List<List<InlineToken>>>,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(
            Modifier
                .clip(shape)
                .border(0.5.dp, colors.borderL1, shape),
        ) {
            TableRow(header, header = true)
            rows.forEach { row ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.borderL1),
                )
                TableRow(row, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<InlineToken>>, header: Boolean) {
    val colors = DshTheme.colors
    Row(Modifier.background(if (header) colors.codeBanner else Color.Transparent)) {
        cells.forEach { cell ->
            Box(
                Modifier
                    .width(160.dp)
                    .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.md),
            ) {
                InlineText(
                    tokens = cell,
                    style = DshType.bodyMedium.copy(lineHeight = 22.sp),
                    color = colors.labelPrimary,
                )
            }
        }
    }
}

@Composable
private fun InlineText(
    tokens: List<InlineToken>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val uriHandler = LocalUriHandler.current

    val annotated: AnnotatedString = remember(tokens, color, colors) {
        buildAnnotatedString {
            tokens.forEach { token ->
                val span = when (token.style) {
                    InlineStyle.NORMAL -> SpanStyle(color = color)
                    InlineStyle.BOLD -> SpanStyle(color = color, fontWeight = FontWeight.Bold)
                    InlineStyle.ITALIC -> SpanStyle(color = color, fontStyle = FontStyle.Italic)
                    InlineStyle.BOLD_ITALIC -> SpanStyle(
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                    )

                    InlineStyle.CODE -> SpanStyle(
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        background = colors.inlineCode,
                    )

                    InlineStyle.LINK -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                    InlineStyle.STRIKE -> SpanStyle(color = color, textDecoration = TextDecoration.LineThrough)
                }
                if (token.url != null) {
                    pushStringAnnotation(tag = "url", annotation = token.url)
                    withStyle(span) { append(token.text) }
                    pop()
                } else {
                    withStyle(span) { append(token.text) }
                }
            }
        }
    }

    ClickableText(
        text = annotated,
        style = style,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset)
                .firstOrNull()
                ?.let { runCatching { uriHandler.openUri(it.item) } }
        },
    )
}

/** A fenced code block: banner with language + copy, then a scrolling mono body. */
@Composable
fun CodeBlock(language: String?, code: String) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.codeBanner)
                .padding(start = DshSpacing.lg, end = DshSpacing.sm, top = DshSpacing.sm, bottom = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.lowercase() ?: "code",
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(DshSpacing.sm))
                    .clickableNoRipple { clipboard.setText(AnnotatedString(code)); copied = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) colors.success else colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        val highlighted = remember(code, colors) { highlightCode(code, colors) }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = highlighted,
                style = DshType.code,
                color = colors.labelPrimary,
                softWrap = false,
                modifier = Modifier.padding(DshSpacing.lg),
            )
        }
    }
}

private fun highlightCode(code: String, colors: DshColors): AnnotatedString {
    return buildAnnotatedString {
        code.split('\n').forEachIndexed { index, line ->
            if (index > 0) append("\n")
            SyntaxHighlighter.highlightLine(line).forEach { span ->
                val color = when (span.kind) {
                    SyntaxHighlighter.Kind.PLAIN -> colors.labelPrimary
                    SyntaxHighlighter.Kind.KEYWORD -> colors.synKeyword
                    SyntaxHighlighter.Kind.STRING -> colors.synString
                    SyntaxHighlighter.Kind.COMMENT -> colors.synComment
                    SyntaxHighlighter.Kind.NUMBER -> colors.synConstant
                    SyntaxHighlighter.Kind.FUNCTION -> colors.synFunction
                    SyntaxHighlighter.Kind.PUNCTUATION -> colors.synPunctuation
                }
                withStyle(SpanStyle(color = color)) { append(span.text) }
            }
        }
    }
}
