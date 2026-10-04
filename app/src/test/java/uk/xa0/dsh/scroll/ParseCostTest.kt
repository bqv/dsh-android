package uk.xa0.dsh.scroll

import org.junit.Test
import uk.xa0.dsh.ui.markdown.Markdown

/**
 * What one streamed token costs on the main thread.
 *
 * The transcript republishes every 60ms while a turn runs (`transcriptTick.sample(60)`),
 * and the live row's markdown is parsed from scratch on each one, because
 * `remember(text) { Markdown.parse(text) }` is keyed on the text and the text has
 * changed. This measures that parse so the claim can be argued with rather than
 * assumed.
 *
 * A diagnostic, not a rule: the numbers are machine-dependent and are printed.
 */
class ParseCostTest {

    private fun answer(paragraphs: Int): String = buildString {
        appendLine("## Findings")
        appendLine()
        repeat(paragraphs) { i ->
            appendLine("Paragraph $i with **bold**, _italic_, `inline code` and a [link](https://example.com/$i) in it.")
            appendLine()
            if (i % 5 == 0) {
                appendLine("```kotlin")
                appendLine("fun example$i(state: LazyListState) = state.firstVisibleItemIndex * $i")
                appendLine("```")
                appendLine()
            }
            if (i % 7 == 0) {
                appendLine("- first bullet with some length to it")
                appendLine("- second bullet")
                appendLine()
            }
        }
    }

    @Test
    fun `diagnostic - the cost of parsing a growing answer`() {
        for (paragraphs in listOf(20, 60, 150)) {
            val text = answer(paragraphs)
            repeat(5) { Markdown.parse(text) }   // warm up
            val runs = 30
            val started = System.nanoTime()
            repeat(runs) { Markdown.parse(text) }
            val perParse = (System.nanoTime() - started) / runs / 1_000_000.0
            println(
                "[parse] ${text.length} chars -> ${"%.2f".format(perParse)}ms per parse, " +
                    "so ${"%.0f".format(perParse * 16.7)}ms/s of main thread at the 60ms publish rate",
            )
        }
    }
}
