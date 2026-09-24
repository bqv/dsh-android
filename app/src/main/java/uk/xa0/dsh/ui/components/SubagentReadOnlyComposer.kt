package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.SubagentReadOnlyReason
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The composer's replacement for an addressed child that cannot take input
 * (`ui-subagent/src/client/SubagentReadOnlyComposer.tsx`).
 *
 * The web frame is one centred flex row — title then body, 8px apart. At phone
 * width that puts two sentences on one line each of them too narrow to read, so
 * the same two strings stack; the copy is verbatim and the tokens are the
 * frame's own (`.module.css`).
 */
@Composable
fun SubagentReadOnlyComposer(
    reason: SubagentReadOnlyReason,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    // The CSS 0.5px border and 14px radius, in this app's radius inventory: 14px
    // has no token, and `card` is the nearest one above the composer's own scale.
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            // The frame's `min-height: 54px`: it sits where the composer card
            // was, so it must not read as a strip the layout has lost.
            .defaultMinSize(minHeight = 54.dp)
            .clip(shape)
            .background(colors.bgLayer1)
            .border(0.5.dp, colors.borderL4, shape)
            .padding(horizontal = DshSpacing.xl, vertical = 10.dp)
            // `role="status"`: the composer is gone, so the takeover has to be
            // announced without stealing focus.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = reason.title,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DshSpacing.md))
        Text(
            text = reason.body,
            style = DshType.bodyMedium,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
