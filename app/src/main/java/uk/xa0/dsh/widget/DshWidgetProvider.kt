package uk.xa0.dsh.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import uk.xa0.dsh.DshApplication

/**
 * The widget's only entry point.
 *
 * This is a `BroadcastReceiver`, and the platform calls it on its own schedule: when
 * a widget is added, when the app is updated, and after a reboot. It does not run on
 * a timer — `updatePeriodMillis` is declared as zero in `xml/dsh_widget_info.xml` —
 * because nothing a periodic wake-up could learn has changed while the app is not
 * running. Every number this widget can show arrives over the app's own socket, and
 * when that socket is gone the honest answer is the age of the last reading, which
 * the launcher's Chronometer is already keeping.
 *
 * Being `exported="true"` is the platform's own requirement for a provider that
 * declares an intent-filter, and is what every shipped widget on the test handset
 * does. Nothing here is reachable by it: [onUpdate] re-reads a file this app wrote and
 * re-draws, so an outside caller can only ask the widget to say the same thing again.
 */
class DshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        publisher(context)?.onWidgetsChanged()
    }

    /** First widget placed; `onUpdate` follows immediately for it. */
    override fun onEnabled(context: Context) {
        publisher(context)?.onWidgetsChanged()
    }

    /**
     * The last widget removed. Nothing is cancelled because nothing was ever
     * scheduled: the widget's only cost while it exists is the file write the app was
     * already doing, and the app stops doing that by itself as soon as
     * [WidgetPublisher.onWidgetsChanged] finds no ids.
     */
    override fun onDisabled(context: Context) = Unit

    /**
     * Null when the running application is not this app's — which in production cannot
     * happen and in a test harness is exactly the case, so the receiver degrades to
     * "draw nothing" rather than crashing a launcher's broadcast.
     */
    private fun publisher(context: Context): WidgetPublisher? =
        (context.applicationContext as? DshApplication)?.widgets
}
