package uk.xa0.dsh.scroll

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The harness itself: proof that a real Compose layout and real touch input can be
 * driven on the JVM, deterministically.
 *
 * Everything the scroll work needs to be answered honestly depends on this
 * working — a list that is laid out for real, a finger that moves for real, and a
 * scroll position that can be read back without a phone, a person, or a guess.
 */
/**
 * A bare application for the harness.
 *
 * The real one builds a `ConfigStore` in `onCreate`, and that reaches for
 * `AndroidKeyStore` through `androidx.security.crypto` — a keystore Robolectric has
 * no shadow for, so the JVM throws "AndroidKeyStore not found" before a single
 * test runs. Nothing here needs an application at all; it needs a layout.
 */
class HarnessApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ScrollHarnessTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a drag moves the list`() {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
                items(200) { index ->
                    Text("row $index", Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }

        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -240f))
            up()
        }

        rule.runOnIdle {
            assertTrue(
                "a 240px drag should have scrolled the list, firstVisibleItemIndex=${state.firstVisibleItemIndex}",
                state.firstVisibleItemIndex > 0,
            )
        }
    }
}
