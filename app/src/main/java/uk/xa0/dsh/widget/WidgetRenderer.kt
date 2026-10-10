package uk.xa0.dsh.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import uk.xa0.dsh.Attention
import uk.xa0.dsh.MainActivity
import uk.xa0.dsh.R

/**
 * Turns [WidgetText] into the `RemoteViews` a launcher draws.
 *
 * Nothing here decides anything: every string and every level already exists in the
 * text, so this file can be read as a layout description and nothing else. Colour is
 * resolved from resources rather than hardcoded so a test can compare what was drawn
 * against the palette the layout uses.
 *
 * ## The tap
 *
 * The whole card is one tap target, because a widget whose parts do different things
 * is a widget you have to learn. It opens the app on the session that most needs the
 * user when there is one, through exactly the path a notification already uses
 * ([Attention.EXTRA_SESSION], read by `MainActivity.consume`), and opens the app
 * plainly when there is not. No new launch path and no new extra: a widget that
 * invented its own would be a second way in, and the second way in is the one that
 * rots.
 */
object WidgetRenderer {

    /**
     * Two request codes, not one. A `PendingIntent` is only distinct by
     * (requestCode, Intent filterEquals), and with a single code a session-bearing
     * intent and a plain one would collapse into whichever was built last.
     */
    private const val REQUEST_OPEN = 8_100
    private const val REQUEST_OPEN_SESSION = 8_101

    fun render(context: Context, text: WidgetText, sessionId: String?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_dsh)

        views.setTextViewText(R.id.widget_headline, text.headline)
        views.setTextColor(R.id.widget_headline, context.getColor(headlineColour(text.level)))

        val detail = text.detail
        if (detail.isNullOrEmpty()) {
            views.setViewVisibility(R.id.widget_detail, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_detail, View.VISIBLE)
            views.setTextViewText(R.id.widget_detail, detail)
            views.setTextColor(R.id.widget_detail, context.getColor(R.color.widget_text_detail))
        }

        views.setTextViewText(R.id.widget_stamp, text.stampLabel)
        views.setTextColor(R.id.widget_stamp, context.getColor(R.color.widget_text_stamp))

        val base = text.ageBase
        if (base == null) {
            // "Open DSH to connect" is a sentence, not a moment: there is no age to
            // tick because there is nothing that was ever true.
            views.setViewVisibility(R.id.widget_age, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_age, View.VISIBLE)
            // started = true is the whole honesty mechanism. The launcher's own
            // Chronometer keeps counting with no process of ours alive, so the age
            // cannot be left behind by a force-stop, an OEM kill or a reboot — the
            // three cases where a scheduled "mark it stale" wake-up never arrives.
            views.setChronometer(R.id.widget_age, base, "%s ago", true)
        }

        views.setOnClickPendingIntent(R.id.widget_root, contentIntent(context, sessionId))
        return views
    }

    /**
     * The tap's intent. Modeled on `Attention.notify`'s, deliberately — including
     * `FLAG_ACTIVITY_CLEAR_TOP`, because a second tap while the app is open must land
     * on the session rather than stack another task.
     */
    fun contentIntent(context: Context, sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!sessionId.isNullOrEmpty()) putExtra(Attention.EXTRA_SESSION, sessionId)
        }
        val request = if (sessionId.isNullOrEmpty()) REQUEST_OPEN else REQUEST_OPEN_SESSION
        return PendingIntent.getActivity(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun headlineColour(level: WidgetLevel): Int = when (level) {
        WidgetLevel.ATTENTION -> R.color.widget_text_attention
        WidgetLevel.QUIET -> R.color.widget_text_quiet
        WidgetLevel.UNKNOWN -> R.color.widget_text_unknown
    }
}
