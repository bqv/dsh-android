package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.StartupCopy
import uk.xa0.dsh.model.StartupStep
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.components.DotState
import uk.xa0.dsh.ui.components.StateDot
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** The start-up throbber, so a test can find it without measuring text. */
const val STARTUP_THROBBER_TAG = "startup-throbber"

/**
 * What the reader sees between launching the app and the transcript.
 *
 * ## The throbber
 *
 * It is `StateDot(ONGOING)` — the chase already used for every session that is
 * working — rather than a new animation. Material's `CircularProgressIndicator` is
 * on the classpath and is not used anywhere in this app; this is the app's own
 * in-progress mark, ported from the web's `StateDot`, and reusing it costs no new
 * animation and keeps one meaning for one shape. It is also *indeterminate* on
 * purpose: none of these waits has a denominator, so there is nothing honest to
 * fill a bar with, and the semantics below say so rather than leaving TalkBack to
 * read a decorative square.
 *
 * ## The words
 *
 * [StartupCopy] owns them and [StartupStep] is set where the work happens. Before
 * that, this screen drew whatever string the event multiplexer had last written —
 * its own socket trace, verbatim — which is how "item s2 baseline (#1)" reached a
 * reader: a stream id and a frame type are facts about the protocol, and no fact
 * at all about what the app is doing for them.
 */
@Composable
fun StartupScreen(step: StartupStep, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DshMark(size = 40.dp)
            Spacer(Modifier.height(DshSpacing.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(
                    state = DotState.ONGOING,
                    size = 16.dp,
                    modifier = Modifier
                        .testTag(STARTUP_THROBBER_TAG)
                        .semantics {
                            // Indeterminate, and declared as such: a screen reader
                            // should hear "in progress", never a percentage this
                            // screen has no way to know.
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = StartupCopy.title(step),
                    style = DshType.bodyMedium,
                    color = DshTheme.colors.labelSecondary,
                )
            }
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                text = StartupCopy.detail(step),
                style = DshType.micro,
                color = DshTheme.colors.labelTertiary,
            )
        }
    }
}
