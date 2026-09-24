package uk.xa0.dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** Clickable without the Material ripple — DSH's chrome uses flat hover fills, not ripples. */
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}
