package uk.xa0.dsh.model

/**
 * The escape hatch that does not depend on the restart being right.
 *
 * A restart rebuilds everything the app *knows* about its connection. It cannot
 * reach state below the app: a pooled socket the process still believes in, a
 * resolver answer held by the platform, a JNI/OkHttp thread stuck in a connect,
 * or anything the OS is holding on the app's behalf. A process kill is the only
 * guaranteed clear, which is why it is offered beside the restart rather than
 * hidden as a developer trick.
 *
 * The three verbs on the About page must not read alike, and the difference is
 * in the confirmation's words rather than only in the code:
 *
 *  * **Close** finishes the screen and keeps the connection (the drawer's
 *    BackGate copy says so);
 *  * **Restart connection** rebuilds the transport in this process, keeping the
 *    process and everything waiting for the reader;
 *  * **Force quit** ends the process: nothing in flight survives.
 */

/** The action's own label. */
const val FORCE_QUIT_ACTION: String = "Force quit"

/**
 * The line under the action, so the reader knows when it is the right tool
 * before they tap it. Deliberately says *when* rather than repeating what the
 * confirmation will say.
 */
const val FORCE_QUIT_NOTE: String =
    "Ends this app's process. The repair of last resort, for when Restart " +
        "connection has not helped: nothing the app is holding on this phone " +
        "survives it."

/** The confirmation's title. */
const val FORCE_QUIT_TITLE: String = "Force quit DSH?"

/**
 * What the confirmation says, in the reader's terms.
 *
 * Three things have to be true of this copy: it says the process ends (not just
 * the screen), it says nothing in flight survives (rather than implying a
 * resume), and it names the other two verbs so the three controls cannot be
 * mistaken for each other.
 */
const val FORCE_QUIT_BODY: String =
    "This ends the app's process outright, not just the connection. The event " +
        "socket, every stream and the notification stop with it, and nothing in " +
        "flight survives: a turn being followed stops arriving, and an approval " +
        "or question waiting for you will not be answered from this phone.\n\n" +
        "Close only closes this screen and keeps the connection. Restart " +
        "connection rebuilds the connection inside this process. Force quit is " +
        "the one that ends everything. Open DSH again to reconnect."

/** The confirm button: a decision, not a repeat of the action's label. */
const val FORCE_QUIT_CONFIRM: String = "Force quit the app"

/**
 * The dismiss button, named rather than "Cancel": the thing it does is keep the
 * app running, and that is worth spelling out on a dialog about ending it.
 */
const val FORCE_QUIT_CANCEL: String = "Keep it running"

/**
 * The last three things a force quit does, in the order that matters.
 *
 * **The service is stopped before the process dies**, not after: it is a
 * `START_STICKY` foreground service, so a process killed from under it looks to
 * the system like one that should be brought back. Stopping it first is what
 * makes "force quit" mean quit instead of "restart in a moment, without a
 * screen".
 *
 * The notifications are withdrawn next, so "stops everything" is true of the
 * shade as well as of the process. Both steps are best-effort — the kill is the
 * point, and a failed `stopService` binder call must not turn a requested force
 * quit into a no-op — hence `runCatching` around them and an unconditional kill.
 *
 * Extracted from the Application so the one rule with an order in it can be
 * tested on the JVM, where the real process kill cannot be.
 */
fun runForceQuit(
    stopService: () -> Unit,
    withdrawNotifications: () -> Unit,
    killProcess: () -> Unit,
) {
    runCatching(stopService)
    runCatching(withdrawNotifications)
    killProcess()
}
