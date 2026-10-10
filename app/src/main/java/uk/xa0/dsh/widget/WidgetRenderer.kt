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
 * ## The taps
 *
 * Each named row is its own tap target, because a list of sessions the reader cannot
 * open is a list they have to go and retype into the app. Every one of them goes through
 * exactly the path a notification already uses ([Attention.EXTRA_SESSION], read by
 * `MainActivity.consume` and handed to `DshViewModel.openSessionExplicit`): no new
 * launch path and no new extra, because a widget that invented its own would be a second
 * way in, and the second way in is the one that rots.
 *
 * The card behind the rows keeps the old target — the session that most needs the user,
 * or the app plainly when none does — so a tap that misses a row still lands somewhere
 * useful.
 *
 * A `PendingIntent` is only distinct by (requestCode, Intent `filterEquals`), and
 * `filterEquals` **ignores extras**. Two rows therefore cannot share a request code even
 * though they carry different session ids, or the second row drawn would overwrite the
 * first row's tap and both would open the same session. Hence one code per row
 * ([REQUEST_ROW]) over and above the card's two.
 */
object WidgetRenderer {

    /**
     * Two request codes for the card itself, not one. With a single code a session-bearing
     * intent and a plain one would collapse into whichever was built last.
     */
    private const val REQUEST_OPEN = 8_100
    private const val REQUEST_OPEN_SESSION = 8_101

    /**
     * The first of one request code per named row. Deliberately clear of the two above:
     * the card and a row are different targets and must never be able to overwrite each
     * other's intent.
     */
    private const val REQUEST_ROW = 8_200

    /**
     * The row views, in the order the text fills them. The count is fixed by the layout,
     * and equals `WidgetCopy.MAX_ROWS`; a `WidgetText` with more rows than this cannot be
     * produced by the copy layer, which is the only thing that builds one.
     */
    private val ROW_VIEWS = intArrayOf(R.id.widget_row_0, R.id.widget_row_1, R.id.widget_row_2)

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

        drawRows(context, views, text)

        views.setTextViewText(R.id.widget_stamp, text.stampLabel)
        views.setTextColor(R.id.widget_stamp, context.getColor(R.color.widget_text_stamp))

        val instant = text.ageInstant
        if (instant == null) {
            // "Open DSH to connect" is a sentence, not a moment: there is no instant to
            // print because there is nothing that was ever true.
            views.setViewVisibility(R.id.widget_age, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_age, View.VISIBLE)
            // The reading's own instant, as a date and a time. Nothing on this card ticks.
            // A `Chronometer` re-renders once a second inside the launcher's process for as
            // long as the card is on a homescreen, and no widget-side call can slow that
            // down or stop it: the launcher owns the view, and the only lever `RemoteViews`
            // has is the one-way `started` flag — whose "off" position freezes the number
            // at the instant it was applied and nothing of ours is alive to re-apply it,
            // which is the stale-and-quietly-wrong card this whole design refuses. An
            // instant needs none of that: it is already complete, so there is nothing to
            // keep true. See `WidgetCopy.stampTime` for why it carries the date.
            views.setTextViewText(R.id.widget_age, WidgetCopy.stampTime(instant))
        }

        views.setOnClickPendingIntent(R.id.widget_root, contentIntent(context, sessionId))
        return views
    }

    /**
     * Fills the fixed row views top-down and hides the ones the text does not use.
     *
     * The "+N more" line is drawn below them, as its own view rather than folded into a
     * count, because a bounded list that does not say what it left out is the silent drop
     * this design forbids. It has no tap target of its own, so a tap on it falls through
     * to the card's.
     */
    private fun drawRows(context: Context, views: RemoteViews, text: WidgetText) {
        ROW_VIEWS.forEachIndexed { index, viewId ->
            val row = text.rows.getOrNull(index)
            if (row == null) {
                views.setViewVisibility(viewId, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, row.title)
            views.setTextColor(viewId, context.getColor(R.color.widget_text_detail))
            views.setOnClickPendingIntent(viewId, sessionIntent(context, row.id, index))
        }

        val more = text.moreLabel
        if (more.isNullOrEmpty()) {
            views.setViewVisibility(R.id.widget_row_more, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_row_more, View.VISIBLE)
            views.setTextViewText(R.id.widget_row_more, more)
            views.setTextColor(R.id.widget_row_more, context.getColor(R.color.widget_text_stamp))
        }
    }

    /**
     * The card's tap. Modeled on `Attention.notify`'s, deliberately — including
     * `FLAG_ACTIVITY_CLEAR_TOP`, because a second tap while the app is open must land
     * on the session rather than stack another task.
     */
    fun contentIntent(context: Context, sessionId: String?): PendingIntent {
        val request = if (sessionId.isNullOrEmpty()) REQUEST_OPEN else REQUEST_OPEN_SESSION
        return openIntent(context, sessionId, request)
    }

    /**
     * One named row's tap, carrying that row's own session.
     *
     * [row] is the row's index in the list, and it is part of the request code because
     * `Intent.filterEquals` compares everything *except* extras: without it, three rows
     * would be three names for one `PendingIntent` and all three would open whichever
     * session was set last.
     */
    fun sessionIntent(context: Context, sessionId: String, row: Int): PendingIntent =
        openIntent(context, sessionId, REQUEST_ROW + row)

    private fun openIntent(context: Context, sessionId: String?, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!sessionId.isNullOrEmpty()) putExtra(Attention.EXTRA_SESSION, sessionId)
        }
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
