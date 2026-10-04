package uk.xa0.dsh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.xa0.dsh.ui.BackGate
import uk.xa0.dsh.ui.ChatScreen
import uk.xa0.dsh.ui.SetupScreen
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.markdown.LocalImageBytes
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

import android.net.Uri

class MainActivity : ComponentActivity() {

    /**
     * Session a notification deep-links to, or null. Held as activity state rather
     * than read off `intent` inside composition so a tap on a *second*
     * notification while the app is already open still lands.
     */
    private val deepLinkSession = mutableStateOf<String?>(null)

    /** One provisioning intent, consumed once by composition. */
    private val provisioning = mutableStateOf<Provisioning?>(null)

    /**
     * Files another app shared into this one, waiting to be staged.
     *
     * Held rather than handed straight to the view model because a share can *start*
     * the app: on a cold share there is no session, no pending target and often no
     * connection yet, and staging an attachment needs a session to stage it into.
     * Composition stages them once there is somewhere for them to go.
     */
    private val sharedFiles = mutableStateOf<List<Uri>>(emptyList())

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consume(intent)
        maybeAskForNotifications()
        reserveLeftEdgeForDrawer()

        setContent {
            val vm: DshViewModel = viewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()

            LaunchedEffect(provisioning.value) {
                provisioning.value?.let { request ->
                    provisioning.value = null
                    vm.saveSetup(
                        baseUrl = request.url,
                        username = request.username,
                        password = request.password,
                        manualCookie = request.cookie,
                    )
                }
            }

            // Keep the connection standing whenever the app is configured, so an
            // agent that needs a human is not blocked on the UI being open.
            LaunchedEffect(ui.phase) {
                if (ui.phase == AppPhase.READY) {
                    DshConnectionService.start(this@MainActivity)
                    BatteryExemption.request(this@MainActivity)
                }
            }

            // A share can *start* the app: no session, no pending target, and the
            // connection still coming up. So the files wait here until there is
            // somewhere to stage them — then they go in one at a time, exactly as if
            // each had been picked with the + button.
            LaunchedEffect(sharedFiles.value, ui.phase, ui.currentSessionId, ui.pendingSession) {
                val files = sharedFiles.value
                if (files.isEmpty()) return@LaunchedEffect
                if (ui.phase != AppPhase.READY) return@LaunchedEffect
                val destination = ui.currentSessionId ?: ui.pendingSession?.let { "pending" }
                if (destination == null) return@LaunchedEffect
                sharedFiles.value = emptyList()
                files.forEach { vm.addAttachment(it) }
            }

            LaunchedEffect(deepLinkSession.value) {
                deepLinkSession.value?.let { sessionId ->
                    deepLinkSession.value = null
                    // Explicit: this must outrank connect's "most recent session
                    // with content" auto-open, which otherwise races it and can
                    // leave the app on an unrelated (often empty) session.
                    vm.openSessionExplicit(sessionId)
                }
            }

            // How a markdown picture gets its bytes, provided once for every surface
            // that can render one — the transcript, reasoning, tool output, the
            // trajectory. `vm::markdownImageBytes` is stable, so this is not a new
            // fetcher per recomposition.
            CompositionLocalProvider(LocalImageBytes provides vm::markdownImageBytes) {
            DshTheme(themeMode = ui.themeMode) {
                // Composed before the screen so the screen's own Back handlers — the
                // drawer, the sheets, the Files panel, a non-Chat view — are asked
                // first; what reaches the gate is the press with nothing to dismiss.
                BackGate(onExit = { finish() })
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(DshTheme.colors.bgBase),
                ) {
                    when (ui.phase) {
                        AppPhase.LOADING -> LoadingScreen(ui.status)

                        AppPhase.SETUP -> Box(Modifier.systemBarsPadding()) {
                            SetupScreen(
                                initialUrl = ui.baseUrl,
                                initialUsername = ui.username,
                                error = ui.error,
                                busy = ui.busy,
                                status = ui.status,
                                onConnect = vm::saveSetup,
                            )
                        }

                        AppPhase.READY -> ChatScreen(vm)
                    }
                }
            }
            }
        }
    }

    /**
     * Reserves a strip of the left edge for the sessions drawer.
     *
     * Under gesture navigation the system's back gesture owns the screen edge, so
     * a swipe that starts there never reached the app's own drawer gesture: the
     * app either left to the launcher or the predictive-back preview sprang back,
     * which is exactly what "I cannot swipe the drawer open" looked like. The
     * platform lets an app claim at most 200dp of a single edge, so the drawer
     * gets a thumb-sized band at the middle of the left edge. Back still works
     * from the right edge, from the navigation bar, and from the drawer's own
     * swipe-left.
     */
    private fun reserveLeftEdgeForDrawer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val density = resources.displayMetrics.density
        val bandPx = (200 * density).toInt()
        val widthPx = (48 * density).toInt()
        window.decorView.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val band = bandPx.coerceAtMost(height)
            val start = ((height - band) / 2).coerceAtLeast(0)
            view.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, start, widthPx, start + band),
            )
        }
    }

    /**
     * singleTop delivers a notification tap here instead of recreating the
     * activity, which is why the extras have to be re-read rather than trusted
     * from `onCreate` alone.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: android.content.Intent?) {
        intent ?: return
        intent.getStringExtra(Attention.EXTRA_SESSION)?.takeIf { it.isNotBlank() }?.let {
            deepLinkSession.value = it
        }
        sharedFiles.value = sharedFilesOf(intent).ifEmpty { sharedFiles.value }
        val url = intent.getStringExtra("url")?.takeIf { it.isNotBlank() } ?: return
        provisioning.value = Provisioning(
            url = url,
            username = intent.getStringExtra("username").orEmpty(),
            password = intent.getStringExtra("password").orEmpty(),
            cookie = intent.getStringExtra("cookie").orEmpty(),
        )
    }

    /**
     * The `content://` URIs a share intent carries, in the order it lists them.
     *
     * Both share actions, because they are different intents rather than one with a
     * flag: `SEND` puts a single Uri in `EXTRA_STREAM`, `SEND_MULTIPLE` puts a list in
     * the same extra. A sender that puts a bare string URI there (some do) is read
     * too, and an `EXTRA_TEXT` carrying a `content://` URI is taken as a file as well.
     *
     * Only `content://`. A `file://` extra cannot be read by this app on a modern
     * Android — the sender's own file path is not ours to open — and pretending
     * otherwise would turn a share into an unexplained empty attachment.
     */
    private fun sharedFilesOf(intent: android.content.Intent?): List<Uri> {
        intent ?: return emptyList()
        val action = intent.action
        if (action != android.content.Intent.ACTION_SEND &&
            action != android.content.Intent.ACTION_SEND_MULTIPLE
        ) {
            return emptyList()
        }
        val uris = mutableListOf<Uri>()
        // Read the raw extra rather than the typed getter: `EXTRA_STREAM` is a Uri for
        // SEND and an ArrayList for SEND_MULTIPLE, and `getParcelableExtra` is only
        // correct for the first of those — asking it for a list is a ClassCastException
        // on a modern platform, which would make every multi-file share crash the app
        // it was aimed at. The string form is here too because some senders use it.
        when (val raw = intent.extras?.get(android.content.Intent.EXTRA_STREAM)) {
            is Uri -> uris += raw
            is String -> runCatching { Uri.parse(raw) }.getOrNull()?.let { uris += it }
            is ArrayList<*> -> raw.forEach { entry ->
                when (entry) {
                    is Uri -> uris += entry
                    is String -> runCatching { Uri.parse(entry) }.getOrNull()?.let { uris += it }
                    else -> Unit
                }
            }

            is Array<*> -> raw.forEach { entry -> if (entry is Uri) uris += entry }
        }
        // A text share that is a URI is a file share with extra words; anything else
        // as text is a message, which is not an attachment and is not staged here.
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.startsWith("content://") }
            ?.let { runCatching { Uri.parse(it) }.getOrNull()?.let { uri -> uris += uri } }
        return uris.filter { it.scheme == "content" }
    }

    /**
     * Android 13+ needs an explicit grant. Asked once on first launch, which is
     * the earliest point the app can post anything at all.
     */
    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * A host configuration carried by the launch intent, for scripted provisioning:
 *
 * ```
 * adb shell am start -n uk.xa0.dsh.debug/uk.xa0.dsh.MainActivity \
 *   -e url http://10.0.2.2:8080 -e username me -e password hunter2
 * ```
 *
 * This exists because driving the Setup form through a slow emulator's IME is
 * unreliable — `input text` gets truncated or autocorrected mid-credential, and
 * a truncated cookie fails as an opaque login prompt. Nothing here is a bypass:
 * it writes exactly the same config the form writes (`saveSetup`), so the normal
 * auth path still runs.
 */
data class Provisioning(
    val url: String,
    val username: String,
    val password: String,
    val cookie: String,
)

@Composable
private fun LoadingScreen(status: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DshMark(size = 40.dp)
            Spacer(Modifier.height(DshSpacing.xl))
            Text(
                text = status ?: "Connecting…",
                style = DshType.bodyMedium,
                color = DshTheme.colors.labelTertiary,
            )
        }
    }
}
