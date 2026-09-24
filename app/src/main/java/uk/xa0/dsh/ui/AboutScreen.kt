package uk.xa0.dsh.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.BuildConfig
import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.PermissionOption
import uk.xa0.dsh.ui.components.DshWordmark
import uk.xa0.dsh.ui.settings.rememberSettingsLocalFacts
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The app's own facts, split off the Settings sheet because not one of them is a
 * setting: what this client is, which host it is pointed at, how it is signed in,
 * what it is running on, and which session is open.
 *
 * The credential rows read `ui/settings/SettingsLocalFacts.kt`, the same source
 * the Settings sheet's old Connection block used. The sign-in mode and whether
 * the cookie jar holds an auth cookie are the app's own state and are not part of
 * the `UiState` slice the sheets are handed, so the view model has no field for
 * either.
 *
 * Everything here is already local: no row on this page performs a host call,
 * which is also why it stays truthful with the socket down. Facts that would
 * need one — the host's own build, the machine it runs on — are deliberately
 * absent rather than guessed.
 *
 * No row prints a bare dash. A value the host has not reported reads as
 * `Unknown`, and a state that has a name of its own (`Not configured`, `None`,
 * `Untitled`) reads as that name — a dash is indistinguishable from a value.
 */
@Composable
fun AboutContent(
    baseUrl: String,
    username: String,
    socketConnected: Boolean,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * The open session's facts, moved here from the Settings sheet's own Session
     * block. All defaulted so the page still renders for a caller with no session
     * at all; the rows then read `Untitled` / `Unknown`, because "no session" is
     * itself a fact this page may state.
     */
    sessionTitle: String = "",
    sessionCwd: String? = null,
    sessionPermission: String = "",
    sessionAgentPreset: String = "",
    permissionOptions: List<PermissionOption> = emptyList(),
    selectedModel: ModelOption? = null,
    selectedEffort: String? = null,
) {
    val colors = DshTheme.colors
    // Read once per sheet: both sources are disk-backed, so re-reading them per
    // recomposition would put keystore crypto on the composition thread.
    val local = rememberSettingsLocalFacts()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = DshSpacing.xxxl),
    ) {
        // The Settings sheet's own header idiom: title left, Close right.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "About",
                style = DshType.heading2,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Close",
                style = DshType.bodyMedium,
                color = colors.link,
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickableNoRipple(onClick = onDismiss)
                    .padding(DshSpacing.sm),
            )
        }

        // The one-line answer to "what is this?", under the app's own lockup.
        DshWordmark(Modifier.padding(horizontal = DshSpacing.xxl))
        Spacer(Modifier.height(DshSpacing.md))
        SectionNote(
            "A native Android client for the DSH host. It speaks the host's own " +
                "HTTP + WebSocket protocol: there is no WebView, and nothing is " +
                "installed on the host to serve it.",
        )

        SectionHeading("App")
        InfoRow("Version", local.versionName)
        InfoRow("Android", androidVersion())
        // The build's own id, not a host fact and not a setting: it is the first
        // thing a bug report needs, and the one thing Version cannot show — the
        // debug variant carries an `.debug` suffix while both share VERSION_NAME.
        InfoRow("Package", BuildConfig.APPLICATION_ID)
        InfoRow("Device", deviceName())

        SectionHeading("Connection")
        InfoRow("Host", baseUrl.ifBlank { "Not configured" })
        // A blank account is what the Setup screen asks for on the carrier-port
        // cookie path ("Leave the account blank"), so it is named, not dashed.
        InfoRow("Account", username.ifBlank { "None" })
        InfoRow("Sign-in", local.signIn)
        InfoRow("Session cookie", local.sessionCookie?.let { if (it) "Present" else "None" })
        // Web shell connection copy: connected / disconnected.
        InfoRow("Status", if (socketConnected) "Connected" else "Disconnected")

        SectionHeading("Session")
        InfoRow("Title", sessionTitle.ifBlank { "Untitled" })
        // The host reports the cwd with the session header; a session created
        // without one has no directory to name.
        InfoRow("Directory", sessionCwd?.let { shortenPath(it) })
        // Only rendered once a caller passes the route: an "Unknown" row here
        // would hide that the app knows the model and was simply not asked.
        if (selectedModel != null) InfoRow("Model", modelDisplay(selectedModel, selectedEffort))
        InfoRow("Access mode", permissionDisplay(sessionPermission, permissionOptions))
        InfoRow(
            "Agent preset",
            sessionAgentPreset
                .takeIf { it.isNotBlank() }
                ?.let { agentPresetLabel(it, "") },
        )

        // Where to send money, if anyone ever wants to. Deliberately a link and
        // not an embedded widget: the Ko-fi button is a script/iframe, so showing
        // it in place would mean a WebView — the one thing this client exists not
        // to be. Handing the URL to the system also means a phone with the Ko-fi
        // app installed gets to open it there instead of in a browser tab.
        SectionHeading("Support")
        SectionNote(
            "This client is a personal project and stays free. The host it talks to " +
                "is your own machine, so there is nothing to pay for here — but if " +
                "it has been useful, coffee is welcome.",
        )
        SupportRow()

        // The web has no logout row (browser auth is the signed cookie); this is
        // the native client's own escape hatch. It lives on About rather than in
        // Settings because it acts on the credentials listed above, and because
        // it is not a value the user can set.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xl)
                .height(44.dp)
                .clip(RoundedCornerShape(DshRadius.card))
                .background(colors.warnTertiary)
                .clickableNoRipple(onClick = onSignOut),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sign out and forget credentials", style = DshType.labelLarge, color = colors.error)
        }
    }
}

/** Where the coffee lives. One constant, so it is one edit to move it. */
private const val KOFI_URL = "https://ko-fi.com/bqvdev"

/**
 * The Ko-fi entry, as a plain `ACTION_VIEW`.
 *
 * Nothing about a donation needs to happen inside this app: there is no state to
 * read back, no host call, and no reason to keep the reader in a browser surface
 * the app would then have to own. An intent also degrades honestly — a phone with
 * no browser at all gets told so instead of a blank frame.
 */
@Composable
private fun SupportRow() {
    val colors = DshTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(DshRadius.card)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xs)
            .height(44.dp)
            .clip(shape)
            .background(colors.bgLayer1)
            .border(0.5.dp, colors.borderL3, shape)
            .clickableNoRipple {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
                }.onFailure {
                    Toast.makeText(context, "No app can open $KOFI_URL", Toast.LENGTH_SHORT).show()
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.LocalCafe,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text("Buy me a coffee on Ko-fi", style = DshType.labelLarge, color = colors.labelPrimary)
    }
}

/**
 * Label beside a value. A null [value] is something the host has not reported
 * (no cwd on the header yet, no `agentPreset` projection), and reads as
 * `Unknown` in the caption tint — a dash there is indistinguishable from a real
 * value, and the vendored web copy has no placeholder of its own to borrow.
 *
 * The label column is fixed so every section's values line up as one column.
 */
@Composable
private fun InfoRow(label: String, value: String?) {
    val colors = DshTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xs)) {
        Text(
            label,
            style = DshType.bodyMedium,
            color = colors.labelTertiary,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value ?: "Unknown",
            style = DshType.bodyMedium,
            color = if (value == null) colors.labelCaption else colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Android release and the API level this build is running on. */
private fun androidVersion(): String {
    // The platform allows RELEASE to be an empty string when the version is not
    // known; the API level is the half that is always there.
    val release = Build.VERSION.RELEASE.takeIf { !it.isNullOrBlank() }
    return listOfNotNull(release, "API ${Build.VERSION.SDK_INT}").joinToString(" · ")
}

/** `Manufacturer Model` — the two device strings a bug report needs. */
private fun deviceName(): String =
    listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Unknown" }

/**
 * The composer's own model + effort pair, under the full names (`Composer.kt`
 * abbreviates only because its trigger shares a row with the access chip).
 */
private fun modelDisplay(model: ModelOption, effort: String?): String {
    val effortName = model.efforts.firstOrNull { it.id == effort }?.name ?: effort
    return listOfNotNull(model.name, effortName?.takeIf { it.isNotBlank() }).joinToString(" · ")
}
