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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.xa0.dsh.ui.ChatScreen
import uk.xa0.dsh.ui.SetupScreen
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

class MainActivity : ComponentActivity() {

    /**
     * Session a notification deep-links to, or null. Held as activity state rather
     * than read off `intent` inside composition so a tap on a *second*
     * notification while the app is already open still lands.
     */
    private val deepLinkSession = mutableStateOf<String?>(null)

    /** One provisioning intent, consumed once by composition. */
    private val provisioning = mutableStateOf<Provisioning?>(null)

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

            LaunchedEffect(deepLinkSession.value) {
                deepLinkSession.value?.let { sessionId ->
                    deepLinkSession.value = null
                    // Explicit: this must outrank connect's "most recent session
                    // with content" auto-open, which otherwise races it and can
                    // leave the app on an unrelated (often empty) session.
                    vm.openSessionExplicit(sessionId)
                }
            }

            DshTheme(themeMode = ui.themeMode) {
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
        val url = intent.getStringExtra("url")?.takeIf { it.isNotBlank() } ?: return
        provisioning.value = Provisioning(
            url = url,
            username = intent.getStringExtra("username").orEmpty(),
            password = intent.getStringExtra("password").orEmpty(),
            cookie = intent.getStringExtra("cookie").orEmpty(),
        )
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
