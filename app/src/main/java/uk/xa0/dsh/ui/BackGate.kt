package uk.xa0.dsh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The last line of defence against a Back that was not meant to leave.
 *
 * Android's Back is a *stack* gesture and this app's screens are states rather than
 * activities, so a press with nothing open used to close the app outright —
 * including from a chat whose Files panel was open, which is the press a reader
 * most often means as "close this". Those surfaces consume Back themselves (see
 * `ChatScreen`'s handlers, and the drawer's and sheets' own); what reaches here is
 * only the press with nothing left to dismiss.
 *
 * That press asks, in a dialog. Leaving is not undoable, so a second stray press is
 * not allowed to do it by accident: Back while the dialog is up dismisses it —
 * pressing Back twice in a row therefore cancels — and only the dialog's own
 * **Exit** leaves.
 *
 * Composed before the content on purpose: `BackHandler`s run most-recently-registered
 * first, so anything the screen adds is asked before this.
 */
@Composable
fun BackGate(onExit: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = true) { confirming = true }

    if (!confirming) return
    val colors = DshTheme.colors
    AlertDialog(
        // Back, a tap outside, or Stay: all three mean "keep the app open".
        onDismissRequest = { confirming = false },
        containerColor = colors.bgBase,
        title = { Text("Exit DSH?", color = colors.labelPrimary, style = DshType.titleMedium) },
        text = {
            Text(
                text = "The agent keeps working while the app is closed — reopen it to see " +
                    "where things got to. A running turn, a background job, or a question " +
                    "waiting for you is not cancelled.",
                style = DshType.bodyMedium,
                color = colors.labelSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirming = false
                onExit()
            }) {
                Text("Exit", color = colors.warn)
            }
        },
        dismissButton = {
            TextButton(onClick = { confirming = false }) {
                // Named rather than "Cancel": the button that keeps the app open is
                // worth spelling out when Back is how you got here.
                Text("Stay", color = colors.labelSecondary)
            }
        },
    )
}
