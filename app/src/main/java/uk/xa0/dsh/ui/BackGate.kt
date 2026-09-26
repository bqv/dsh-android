package uk.xa0.dsh.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** How long a first Back stays armed. */
const val BACK_GATE_WINDOW_MS = 2_000L

/**
 * Whether a Back press leaves the app, given when the last one was armed.
 *
 * Pure so it can be pinned by a test: `null` means nothing is armed (this press
 * only arms), and a press inside the window leaves. A press *after* it is a fresh
 * first press — the caller has already cleared the arming on a timer, and this
 * double-checks it rather than trusting that.
 */
fun backGateLeaves(armedAtMs: Long?, nowMs: Long, windowMs: Long = BACK_GATE_WINDOW_MS): Boolean =
    armedAtMs != null && nowMs - armedAtMs in 0..windowMs

/**
 * The last line of defence against a Back that was not meant to leave.
 *
 * Android's Back is a *stack* gesture and this app's screens are states rather
 * than activities, so a press with nothing open used to close the app outright —
 * including from a chat whose Files panel was open, which is the press a reader
 * most often means as "close this". Those surfaces consume Back themselves (see
 * `ChatScreen`'s handlers and the drawer's and sheets' own); what reaches here is
 * only the press with nothing left to dismiss, and that one asks for a second
 * press within [BACK_GATE_WINDOW_MS].
 *
 * It is composed before the content on purpose: `BackHandler`s run
 * most-recently-registered first, so anything the screen adds is asked before this.
 */
@Composable
fun BackGate(onExit: () -> Unit) {
    val context = LocalContext.current
    var armedAt by remember { mutableStateOf<Long?>(null) }

    // The arming expires on its own, so a single press a minute ago is not half of
    // a deliberate double press.
    LaunchedEffect(armedAt) {
        if (armedAt != null) {
            delay(BACK_GATE_WINDOW_MS)
            armedAt = null
        }
    }

    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (backGateLeaves(armedAt, now)) {
            armedAt = null
            onExit()
        } else {
            armedAt = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
}
