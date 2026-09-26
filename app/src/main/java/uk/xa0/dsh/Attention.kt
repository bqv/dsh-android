package uk.xa0.dsh

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * "An agent needs you" notifications.
 *
 * A DSH agent stops and waits when it wants a human: an approval it cannot take
 * on its own, or a question it has to ask. This client may well be in a pocket
 * at that moment, and a stalled agent looks exactly like a working one from the
 * outside — so each of those, plus a session that finished, a session that
 * errored and a goal that went blocked, raises a real Android notification.
 *
 * Every notification is a deep link: tapping it opens the app on the session the
 * notification is about ([EXTRA_SESSION]), because "something needs you" without
 * "here it is" is only half an answer.
 */
object Attention {

    const val CHANNEL_ID = "dsh_attention"

    /**
     * The standing foreground-service notification gets its own channel.
     *
     * On API 26+ a notification's importance comes from its channel, not from
     * `setPriority`, so posting the permanent "connected" strip on [CHANNEL_ID]
     * silently promoted it to HIGH and made it sound-capable.
     */
    const val CHANNEL_ID_CONNECTION = "dsh_connection"

    /** Session id the notification is about; MainActivity opens it on launch. */
    const val EXTRA_SESSION = "uk.xa0.dsh.session"

    // Stable ids: a newer alert of the same kind replaces the older one rather
    // than stacking, which is what a phone-sized notification shade wants.
    const val ID_APPROVAL = 1001
    const val ID_QUESTION = 1002
    const val ID_ERROR = 1003
    const val ID_GOAL = 1004

    /** One notification per finished session, so several can coexist. */
    fun idleId(sessionId: String): Int = 2000 + (sessionId.hashCode() and 0xFF)

    /**
     * Per-session ids for the blocking waterfalls, so two sessions (an agent and
     * its subagent, typically) each keep their own notification instead of one
     * replacing the other. A waterfall with no session keeps the legacy stable
     * id, since there is nothing to distinguish it by.
     */
    fun approvalId(sessionId: String?): Int =
        if (sessionId.isNullOrEmpty()) ID_APPROVAL else 3_000_000 + (sessionId.hashCode() and 0xFFFFF)

    fun questionId(sessionId: String?): Int =
        if (sessionId.isNullOrEmpty()) ID_QUESTION else 5_000_000 + (sessionId.hashCode() and 0xFFFFF)

    /**
     * A terminal's BEL, per session. It gets its own range rather than sharing the
     * question one: a bell and a question can be waiting at the same time, and the
     * one that arrived last must not erase the other from the shade.
     */
    fun bellId(sessionId: String?): Int =
        if (sessionId.isNullOrEmpty()) 1005 else 6_000_000 + (sessionId.hashCode() and 0xFFFFF)

    /**
     * Both channels must exist before the first post. Creating them on every app
     * start is idempotent, so there is nothing to guard for — and the check has
     * to be per channel anyway, because an upgrade adds the connection channel to
     * an install that already has the attention one.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Needs attention",
                // HIGH so an approval or question can interrupt: the whole point
                // is that the agent is blocked until it is answered.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approvals, agent questions and finished sessions"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_CONNECTION,
                "Connection status",
                // LOW because the notification is a standing fact; without its own
                // channel that intent was defeated by the attention channel's HIGH.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The ongoing connection to the host"
            },
        )
    }

    /** Withdraws an alert that no longer applies, e.g. a host-cancelled waterfall. */
    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /**
     * Posts one attention notification. Missing permission is not an error here:
     * on Android 13+ the user may simply have declined, and the in-app card is
     * still there for them.
     */
    fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        sessionId: String? = null,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            sessionId?.let { putExtra(EXTRA_SESSION, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            // IMMUTABLE is required from API 31; the request code keeps the
            // per-notification extras distinct.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}

/**
 * Tracks whether any activity is resumed, so an alert the user is already
 * looking at does not also buzz their pocket. Registered from
 * [DshApplication.onCreate]; the cost is two booleans per lifecycle edge.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    var resumed: Boolean = false
        private set

    /**
     * Invoked on the edge from "no activity resumed" to "an activity is
     * resumed", i.e. when the app comes back after being backgrounded or frozen.
     *
     * The process freeze is why this exists: a frozen app runs no coroutines, so
     * its socket and stream collectors can be dead while every guard still says
     * they are alive, and nothing else re-checks them. Set by the view model.
     */
    @Volatile
    var onForeground: (() -> Unit)? = null

    override fun onActivityResumed(activity: Activity) {
        val wasResumed = resumed
        resumed = true
        if (!wasResumed) onForeground?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
