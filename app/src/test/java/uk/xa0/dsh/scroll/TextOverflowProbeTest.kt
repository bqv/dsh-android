package uk.xa0.dsh.scroll

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What this harness cannot measure: text.
 *
 * Written to check a disclosure that appears only when a paragraph is clipped, and it
 * answered a different question. Robolectric lays text out with stub metrics — 24,892
 * characters measure as a single 260px line, about 0.6px per glyph — so nothing wraps,
 * nothing overflows and `hasVisualOverflow` is false however long the string is.
 *
 * The consequence is a rule about this test suite rather than about the app: **a test
 * whose subject is where text breaks cannot be written here.** Layout of boxes, the
 * position of rows, the effect of a gesture and the shape of a list are all real;
 * typography is not. Anything that depends on it has to be seen on a device.
 *
 * Kept because the next person to try will otherwise spend the same hour on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class TextOverflowProbeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `diagnostic - what the layout reports for a clipped paragraph`() {
        var overflows: Boolean? = null
        var lines = -1
        var measured = "?"
        var density = -1f
        val long = (1..2000).joinToString(" ") { "sentence$it" }

        rule.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            Text(
                text = long,
                fontSize = 12.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(260.dp),
                onTextLayout = { result ->
                    overflows = result.hasVisualOverflow
                    lines = result.lineCount
                    measured = "${result.size.width}x${result.size.height}"
                },
            )
        }
        rule.waitForIdle()
        println("[probe] chars=${long.length} density=$density measured=$measured lines=$lines overflow=$overflows")
    }
}
