package uk.xa0.dsh

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the connection to DSH alive with no Activity in existence.
 *
 * This is the answer to "does it push even when the app is closed?". Nothing in
 * Android can hold a WebSocket open for an app that the system has reaped, and
 * there is no push channel here — FCM would need a server, which would mean host
 * changes. A foreground service is the one mechanism the platform provides: while
 * it runs, the process is not a background candidate for the reaper, so the event
 * stream stays connected and an agent's approval or question reaches the phone.
 *
 * The ongoing notification is not decoration; it is the price of the mechanism,
 * and it doubles as the honest state of the connection.
 *
 * What this cannot fix: if the process is *killed outright* — which on this
 * phone's OEM (OPPO/ColorOS) the battery manager will do unless the app is
 * exempted — the socket dies with it, the host fails the pending waterfall with
 * `NO_PROVIDER`, and no notification can be raised for something that never
 * arrived. Hence [BatteryExemption], which asks for the one exemption that makes
 * this reliable on ColorOS.
 */
class DshConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAlive: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as DshApplication
        Attention.ensureChannel(this)
        startForeground(ONGOING_ID, ongoingNotification())
        app.attention.start(app.client)
        // The centre's stream loop reconnects on its own; this only makes sure the
        // socket is actually open even if no other component ever asks for it.
        keepAlive = scope.launch {
            while (true) {
                runCatching { app.client.mux().ensureConnected() }
                delay(CONNECT_INTERVAL_MS)
            }
        }
    }

    /**
     * `START_STICKY` because a service the system killed for memory should come
     * back: the whole point is to be reachable when the user is not looking.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        keepAlive?.cancel()
        super.onDestroy()
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Attention.CHANNEL_ID_CONNECTION)
            // LOW: it is a standing fact, not something to be alerted about.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("DSH connected")
            .setContentText("Ready for approvals and questions")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 1000
        private const val CONNECT_INTERVAL_MS = 15_000L

        /** Starts (or re-prompts) the standing connection. */
        fun start(context: Context) {
            val intent = Intent(context, DshConnectionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DshConnectionService::class.java)) }
        }
    }
}

/**
 * Android's battery optimisation is what actually reaps this app, and on ColorOS
 * it is aggressive enough that a foreground service alone is not enough. Asking
 * for the exemption is the difference between "usually delivers" and "delivers".
 */
object BatteryExemption {

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system's exemption prompt. This app is sideloaded and personal, so
     * the Play policy that reserves this intent for a narrow set of app types does
     * not apply.
     */
    fun request(context: Context) {
        if (isExempt(context)) return
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
