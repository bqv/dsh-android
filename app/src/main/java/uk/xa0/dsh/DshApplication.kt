package uk.xa0.dsh

import android.app.Application
import android.os.Process
import uk.xa0.dsh.data.ConfigStore
import uk.xa0.dsh.diag.ScrollDiag
import uk.xa0.dsh.model.runForceQuit
import uk.xa0.dsh.net.DshClient
import uk.xa0.dsh.widget.WidgetPublisher

/** Process-wide singletons; the DSH client owns the cookie jar and the event socket. */
class DshApplication : Application() {

    lateinit var configStore: ConfigStore
        private set

    lateinit var client: DshClient
        private set

    /** Whether an activity is on screen, so alerts can stay quiet when they are already visible. */
    val foreground = ForegroundTracker()

    /**
     * Approvals, questions and their notifications. Process-scoped on purpose: the
     * connection service keeps it alive with no Activity, so an agent that wants a
     * human still reaches the phone after the UI is gone.
     *
     * Held through the delegate so [forceQuit] can withdraw this app's
     * notifications *without constructing the centre* on a process that never
     * needed one — creating it only to stop it would be a strange last act.
     */
    private val attentionDelegate = lazy { AttentionCenter(this) }
    val attention: AttentionCenter by attentionDelegate

    /**
     * The homescreen widget's snapshot and its one writer.
     *
     * Process-scoped because the widget is not a screen: it has to be kept current
     * with no Activity in existence, which is exactly the lifetime this object has.
     * [AttentionCenter] and `DshViewModel` push their own reading into it and nothing
     * else does — see [uk.xa0.dsh.widget.WidgetPublisher] for who renews what, and
     * why a reading that stops being renewed is the honest outcome rather than a bug.
     */
    val widgets by lazy { WidgetPublisher(this) }

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        client = DshClient(this)
        val config = configStore.load()
        // The accepted certificates are the app's trust anchors, and the client is
        // what accepts one — so it is also what has to say when the set changed. The
        // store is the only thing that knows where that state lives, hence the hook.
        client.onTrustChanged = {
            configStore.save(configStore.load().copy(acceptedCerts = client.trust.entries()))
        }
        client.setAcceptedCerts(config.acceptedCerts)
        client.applyConfig(config)
        Attention.ensureChannel(this)
        registerActivityLifecycleCallbacks(foreground)
        // Latent scroll diagnostics: invisible, additive, and off the main
        // thread. See uk.xa0.dsh.diag.ScrollDiag for how to read them back.
        ScrollDiag.attach(filesDir)
    }

    /**
     * End this app's process, deliberately — the About page's **Force quit**.
     *
     * This is the honest answer to a wedge below the app's own state, which a
     * transport restart cannot reach by construction: a pooled or native socket
     * the process still believes in, a resolver answer held by the platform, a
     * thread stuck in a connect. Nothing here pretends to repair anything; it
     * stops the app and leaves the next launch to start from nothing.
     *
     * The order lives in [runForceQuit] and is tested there: stop the sticky
     * service (so the system does not immediately bring it back), withdraw this
     * app's notifications, then kill. The view model flushes the composer's
     * drafts before calling this, because `SharedPreferences.apply` is
     * asynchronous and a kill can otherwise outrun the last keystroke's write.
     */
    fun forceQuit() {
        runForceQuit(
            stopService = { DshConnectionService.stop(this) },
            withdrawNotifications = { if (attentionDelegate.isInitialized()) attention.stop() },
            killProcess = { Process.killProcess(Process.myPid()) },
        )
    }
}
