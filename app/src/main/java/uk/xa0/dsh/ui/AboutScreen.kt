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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.BuildConfig
import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.PermissionOption
import uk.xa0.dsh.model.CONNECTION_RESTART_ACTION
import uk.xa0.dsh.model.CONNECTION_RESTART_NOTE
import uk.xa0.dsh.model.CONNECTION_RESTART_RUNNING
import uk.xa0.dsh.model.ConnectionRestart
import uk.xa0.dsh.model.HostVersion
import uk.xa0.dsh.model.connectionRestartNote
import uk.xa0.dsh.net.AcceptedCert
import uk.xa0.dsh.net.CertReadout
import uk.xa0.dsh.net.DoorFault
import uk.xa0.dsh.ui.components.CertificateSection
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.components.DshWordmark
import uk.xa0.dsh.ui.settings.rememberSettingsLocalFacts
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.security.cert.X509Certificate

/**
 * The app's own facts, split off the Settings sheet because not one of them is a
 * setting: what this client is, which host it is pointed at, how it is signed in,
 * what it is running on, and which session is open.
 *
 * It is also where the host's *changeable* connection rows live — the optional LAN
 * address, and the certificate this host is pinned to. Changeable is not the test
 * here; reachable is. A signed-in session has no route back to the setup screen, so
 * a certificate that could only be pinned before signing in is one nobody pins, and
 * the drawer's footer opens this page beside Settings.
 *
 * The credential rows read `ui/settings/SettingsLocalFacts.kt`, the same source
 * the Settings sheet's old Connection block used. The sign-in mode and whether
 * the cookie jar holds an auth cookie are the app's own state and are not part of
 * the `UiState` slice the sheets are handed, so the view model has no field for
 * either.
 *
 * Every row here is local, and every one stays truthful with the socket down. Two
 * actions reach the network: **Check certificate**, which reads what the host
 * presents and is explicit about doing so, and **Restart connection**, which
 * rebuilds the transport in-process and states both its progress and its outcome.
 * The one row that is neither local nor a call of its own is the host's DSH
 * version: it is folded from the `$events`
 * opening frame the app already receives, and a host that names no version is said
 * to have named none. Facts that would need a host call to *print* — the host's own
 * build, the machine it runs on — are deliberately absent rather than guessed.
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
    /**
     * The resolved endpoint and which door it is. All defaulted: a caller that
     * passes neither sees the rows omitted rather than a guessed route.
     */
    activeUrl: String = "",
    activeRouteLan: Boolean = false,
    /**
     * Why the LAN door is not in use, when it is not: the fault the app recorded
     * against it. Defaulted to null — no LAN door has failed, or the one that did
     * is the route in use anyway — so a caller that passes nothing sees the note
     * omitted rather than a sentence about a fault that never happened.
     */
    lanDoorFault: DoorFault? = null,
    /**
     * Whether a LAN address exists to try at all. Defaulted to true so a caller
     * that passes nothing says nothing: this note is for the reader who is looking
     * at a public route and needs to know that no LAN door was ever in the running,
     * which is a missing setting rather than a failed door.
     */
    lanRouteConfigured: Boolean = true,
    /**
     * The reader's own "try the LAN door again", if this caller can offer one.
     *
     * The resolver holds a door that answered with something which was not DSH
     * until the network changes, and nothing automatic may lift that — a captive
     * portal answers a probe exactly as it answered the first. Without an action
     * here the verdict would be one no reader could ever revisit, which is
     * indistinguishable from the app having stopped trying. Null hides the action.
     */
    onRetryLanRoute: (() -> Unit)? = null,
    /**
     * The DSH version of the host this app is actually connected to, as that host
     * named it in its `$events` opening frame. Never this app's own version: the
     * host is a remote machine and the two releases move independently. Defaulted
     * to [HostVersion.Unknown], so a caller that never had a connection prints the
     * usual `Unknown` rather than omitting the row.
     */
    hostVersion: HostVersion = HostVersion.Unknown,
    /**
     * The reader's "Restart connection": tear the transport down and build it
     * again without killing the process. Null hides the action.
     *
     * It belongs in this section because this is where a wedged route is *read*:
     * the Route, Active URL, LAN-fault and DSH-version rows above are the evidence
     * a reader has for wanting it, and one surface that holds both the diagnosis
     * and the repair is worth more than a second, faster entry point. A drawer
     * item or a long-press would put a transport-level action one stray tap from
     * navigation, and a gesture nobody can see is no faster for the reader who
     * does not already know it exists. See [connectionRestartNote].
     */
    onRestartConnection: (() -> Unit)? = null,
    /** What the restart is doing, or what it last did; see [ConnectionRestart]. */
    connectionRestart: ConnectionRestart = ConnectionRestart.Idle,
    /**
     * The LAN door's own address, and where an edit goes. Committed by the Save
     * action rather than per keystroke: a new LAN address re-resolves the endpoint
     * and redials, so committing on every character would reconnect the app once
     * per letter.
     */
    lanUrl: String = "",
    onLanUrl: ((String) -> Unit)? = null,
    /**
     * The certificates the app has been told to trust, one per host, and the last
     * thing a host presented. See [uk.xa0.dsh.ui.components.CertificateSection].
     *
     * These live here, with the rest of the host's facts, rather than in Settings: a
     * certificate that can only be pinned before signing in is one nobody pins, and
     * this page is the surface a signed-in session can actually reach.
     */
    acceptedCerts: List<AcceptedCert> = emptyList(),
    certReadout: CertReadout? = null,
    certChecking: Boolean = false,
    certError: String? = null,
    onCheckCertificate: ((String) -> Unit)? = null,
    onTrustCertificate: ((String, X509Certificate) -> Unit)? = null,
    onForgetCertificate: ((String) -> Unit)? = null,
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
        // Which door is actually in use. Not the same as Host: the resolver
        // prefers the LAN address whenever the phone can reach it, so Host can be
        // the public URL while traffic goes over the LAN. When the two agree,
        // "Active URL" would only repeat the row above, so it is omitted.
        InfoRow(
            "Route",
            when {
                activeUrl.isBlank() -> "Not connected"
                activeRouteLan -> "LAN"
                else -> "Public"
            },
        )
        // Compared with the trailing slash folded away: the configured string is
        // stored trimmed while the resolved one is a normalised URL.
        if (activeUrl.isNotBlank() && activeUrl.trimEnd('/') != baseUrl.trimEnd('/')) {
            InfoRow("Active URL", activeUrl)
        }
        // Why the LAN door is not the route in use, when it is not. The resolver
        // prefers the LAN whenever it answers, so "Public" with a LAN address
        // configured has a reason — and a reason that can only be inferred is
        // exactly the "works now, I do not know why" this app's convention says to
        // name instead. Omitted when there is nothing to explain: no fault, or a
        // fault on a door that is nevertheless the one in use.
        //
        // A missing LAN address is the other reason a public route needs a
        // sentence, and a different fact: nothing is probed, so no fault can ever be
        // recorded and no reconnect can change the answer. Without this the two read
        // identically from here.
        val fault = lanDoorFault
        when {
            fault != null -> SectionNote(lanDoorNote(fault))
            !lanRouteConfigured -> SectionNote(ABOUT_LAN_DOOR_UNCONFIGURED)
        }
        // And the one action that can revisit a verdict the clock may not: a person
        // asking again. Offered only when there is a fault to revisit, because the
        // other case needs a setting rather than a retry — the field is right below.
        val retryLan = onRetryLanRoute
        if (retryLan != null && fault != null) {
            Column(Modifier.padding(horizontal = DshSpacing.xxl)) {
                Spacer(Modifier.height(DshSpacing.sm))
                Text(
                    text = ABOUT_LAN_DOOR_RETRY,
                    style = DshType.bodyMedium,
                    color = colors.link,
                    modifier = Modifier.clickableNoRipple { retryLan() },
                )
            }
        }

        // The second door, edited where the other connection facts are read. Bound to
        // a stable local so the save lambda captures a non-null function rather than
        // relying on a smart cast across a closure.
        val saveLan = onLanUrl
        var lanDraft by rememberSaveable(lanUrl) { mutableStateOf(lanUrl) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.sm),
        ) {
            Text("LAN address", style = DshType.bodyMedium, color = colors.labelTertiary)
            Spacer(Modifier.height(DshSpacing.sm))
            DshTextField(
                value = lanDraft,
                onValueChange = { lanDraft = it },
                placeholder = "http://192.168.1.110/",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                text = "Optional, for the same host: reachable only on your own network, " +
                    "and tried first when it answers.",
                style = DshType.micro,
                color = colors.labelCaption,
            )
            // Compared in the form the store keeps, so saving `http://host/` does not
            // leave the action showing for a value that is already stored.
            if (saveLan != null && lanDraft.trim().trimEnd('/') != lanUrl.trimEnd('/')) {
                Spacer(Modifier.height(DshSpacing.sm))
                Text(
                    text = "Save LAN address",
                    style = DshType.bodyMedium,
                    color = colors.link,
                    modifier = Modifier.clickableNoRipple { saveLan(lanDraft) },
                )
            }
        }

        // A blank account is what the Setup screen asks for on the carrier-port
        // cookie path ("Leave the account blank"), so it is named, not dashed.
        InfoRow("Account", username.ifBlank { "None" })
        InfoRow("Sign-in", local.signIn)
        InfoRow("Session cookie", local.sessionCookie?.let { if (it) "Present" else "None" })
        // Web shell connection copy: connected / disconnected.
        InfoRow("Status", if (socketConnected) "Connected" else "Disconnected")
        // The host's own DSH version, folded from its `$events` opening frame — the
        // only identity frame the wire carries. An `Unknown` here is "not connected,
        // or no opening frame yet"; a connected host whose frame named none says so
        // in words rather than being left blank or filled in with this app's version.
        InfoRow("DSH version", hostVersionLabel(hostVersion), HOST_VERSION_VALUE_TAG)

        // The transport-level action, beside the facts that explain it. One action,
        // one effect: it rebuilds the socket and re-asks both doors, and leaves the
        // process — and therefore the connection service, the cookie jar and every
        // waiting approval — alone. The busy and outcome lines are not decoration:
        // a restart takes a moment and this app's recurring defect is doing
        // something deliberate and saying nothing.
        val restart = onRestartConnection
        if (restart != null) {
            val running = connectionRestart == ConnectionRestart.Running
            Column(Modifier.padding(horizontal = DshSpacing.xxl)) {
                Spacer(Modifier.height(DshSpacing.sm))
                Text(
                    text = if (running) CONNECTION_RESTART_RUNNING else CONNECTION_RESTART_ACTION,
                    style = DshType.bodyMedium,
                    color = if (running) colors.labelCaption else colors.link,
                    modifier = Modifier
                        .testTag(RESTART_CONNECTION_TAG)
                        // Disabled while it runs: a second tap would ask for a
                        // second teardown before the first has finished probing.
                        .clickableNoRipple(enabled = !running) { restart() },
                )
            }
            val note = connectionRestartNote(connectionRestart)
            if (note == null) {
                SectionNote(CONNECTION_RESTART_NOTE)
            } else {
                Text(
                    text = note,
                    style = DshType.bodySmall,
                    // Failure is the one outcome drawn in the error tint; success
                    // and progress stay in the ordinary note colour, so the page
                    // does not shout about a rebuild that worked.
                    color = if (connectionRestart is ConnectionRestart.Failed) {
                        colors.error
                    } else {
                        colors.labelTertiary
                    },
                    modifier = Modifier.padding(
                        horizontal = DshSpacing.xxl,
                        vertical = DshSpacing.xs,
                    ),
                )
            }
        }

        // What this host is pinned to, or what it presented when it was refused. Bound
        // to locals because a nullable parameter does not smart-cast inside a lambda.
        val check = onCheckCertificate
        val trust = onTrustCertificate
        val forget = onForgetCertificate
        if (check != null && trust != null && forget != null &&
            baseUrl.trim().startsWith("https://", ignoreCase = true)
        ) {
            SectionHeading("Certificate")
            Column(Modifier.padding(horizontal = DshSpacing.xxl)) {
                CertificateSection(
                    url = baseUrl,
                    acceptedCerts = acceptedCerts,
                    readout = certReadout,
                    checking = certChecking,
                    certError = certError,
                    onCheckCertificate = check,
                    onTrustCertificate = trust,
                    onForgetCertificate = forget,
                    heading = null,
                )
            }
        }

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
 *
 * [valueTag] names the value's seat for a test that has to read this row
 * specifically; `Unknown` is drawn by more than one row, so counting the word
 * cannot tell them apart.
 */
@Composable
private fun InfoRow(label: String, value: String?, valueTag: String? = null) {
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
            modifier = if (valueTag == null) Modifier else Modifier.testTag(valueTag),
            style = DshType.bodyMedium,
            color = if (value == null) colors.labelCaption else colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Names the host-version row's value for `AboutHostVersionTest`, which lives in the
 * unit-test source set and has to address this one node: `Unknown` is drawn by more
 * than one row, so counting the word cannot tell them apart.
 */
const val HOST_VERSION_VALUE_TAG = "about-host-dsh-version"

/**
 * The restart action's seat, for `AboutRestartTest`: the same words also appear in
 * the busy and done notes, so matching by text alone could not tell the control
 * from the line describing it.
 */
const val RESTART_CONNECTION_TAG = "about-restart-connection"

/**
 * What the About page says when the LAN door is being avoided, one sentence per
 * fault. Named because the wording is the whole of what this page adds here, and a
 * test that retyped the sentences would be free to drift from the ones drawn.
 *
 * The two faults read differently on purpose: the reader's question is not "what
 * went wrong" but "does it come back", and the answer is not the same for a blip as
 * for a door that answered with something which was not DSH.
 */
const val ABOUT_LAN_DOOR_TRANSPORT: String =
    "The LAN door failed a real call, so it is set aside for now. It is tried again " +
        "after a pause that grows each time it fails."

const val ABOUT_LAN_DOOR_WRONG_ANSWER: String =
    "The LAN door answered a real call with something that was not DSH, so it is set " +
        "aside until the network changes."

/**
 * What the About page says when there is no LAN address to try at all.
 *
 * Not a fault and not a failure: the resolver has no candidate, so nothing is
 * probed and no reconnect can change the route. A reader who has set the LAN
 * address on another screen is owed the difference between that setting being
 * missing and the door being refused.
 */
const val ABOUT_LAN_DOOR_UNCONFIGURED: String =
    "No LAN address is set, so only the public door is tried. If this phone is " +
        "usually on the same network as the host, set one below."

/** The action that takes a LAN-door verdict back to the doors themselves. */
const val ABOUT_LAN_DOOR_RETRY: String = "Try the LAN door again"

/** The sentence for one fault; see [ABOUT_LAN_DOOR_TRANSPORT]. */
private fun lanDoorNote(fault: DoorFault): String = when (fault) {
    DoorFault.Transport -> ABOUT_LAN_DOOR_TRANSPORT
    DoorFault.WrongAnswer -> ABOUT_LAN_DOOR_WRONG_ANSWER
}

/**
 * What the About row prints for the host's own DSH version.
 *
 * `null` is this screen's existing "not known" (see [InfoRow]), and it is what
 * [HostVersion.Unknown] means: no opening frame has arrived on this connection, so
 * there is nothing to name. A connected host whose opening frame carried no version
 * is named as having reported none, in the same shape as `Not configured` / `None` /
 * `Untitled` — never blank, and never this app's own version.
 */
private fun hostVersionLabel(version: HostVersion): String? = when (version) {
    HostVersion.Unknown -> null
    HostVersion.NotReported -> "Not reported"
    is HostVersion.Reported -> version.version
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
