package uk.xa0.dsh

import android.app.Application
import uk.xa0.dsh.data.ConfigStore
import uk.xa0.dsh.net.DshClient

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
     */
    val attention by lazy { AttentionCenter(this) }

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        client = DshClient(this)
        client.applyConfig(configStore.load())
        Attention.ensureChannel(this)
        registerActivityLifecycleCallbacks(foreground)
    }
}
