package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.StartupCopy
import uk.xa0.dsh.model.StartupStep
import uk.xa0.dsh.net.AcceptedCert
import uk.xa0.dsh.net.CertReadout
import uk.xa0.dsh.ui.components.CertificateSection
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.components.DshPrimaryButton
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.security.cert.X509Certificate

/**
 * First-run / reconnect screen.
 *
 * It mirrors the DSH login page: the mark centred above a short form. The
 * credential model is the host's own — the app signs in through the same
 * `/auth/login` the web UI posts to, and never needs anything installed on the host.
 */
@Composable
fun SetupScreen(
    initialUrl: String,
    initialUsername: String,
    error: String?,
    busy: Boolean,
    /**
     * What the connect attempt is waiting on, or null when none is in flight.
     *
     * A step rather than a sentence for the same reason the loading screen takes
     * one: this line used to be whatever the event multiplexer last wrote, so a
     * reader connecting from here could be told `item s2 baseline (#1)`.
     */
    startupStep: StartupStep?,
    onConnect: (url: String, lanUrl: String, username: String, password: String, cookie: String) -> Unit,
    initialLanUrl: String = "",
    /**
     * The hosts this app already trusts, one certificate each.
     *
     * Passed in rather than fetched so the screen can say "pinned, since when, with
     * which fingerprint" before anything is dialled — which is the moment at which
     * the answer still matters.
     */
    acceptedCerts: List<AcceptedCert> = emptyList(),
    /** What the last certificate read saw, from a check or from a refusal. */
    readout: CertReadout? = null,
    checking: Boolean = false,
    certError: String? = null,
    onCheckCertificate: (String) -> Unit = {},
    onTrustCertificate: (String, X509Certificate) -> Unit = { _, _ -> },
    onForgetCertificate: (String) -> Unit = {},
) {
    val colors = DshTheme.colors
    var url by rememberSaveable { mutableStateOf(initialUrl.ifBlank { "http://192.168.1.110/" }) }
    var lanUrl by rememberSaveable { mutableStateOf(initialLanUrl) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var cookie by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        DshMark(size = 44.dp)
        Spacer(Modifier.height(DshSpacing.xl))
        Text("DSH", style = DshType.heading1, color = colors.ink)
        Spacer(Modifier.height(DshSpacing.md))
        Text(
            text = "Connect to your DeepSeek Harness host",
            style = DshType.bodyMedium,
            color = colors.labelTertiary,
        )
        Spacer(Modifier.height(DshSpacing.xxxl))

        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(DshSpacing.xl)) {
            DshTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "http://192.168.1.110/",
                label = "Server",
                keyboardType = KeyboardType.Uri,
            )
            CertificateSection(
                url = url,
                acceptedCerts = acceptedCerts,
                readout = readout,
                checking = checking,
                certError = certError,
                onCheckCertificate = onCheckCertificate,
                onTrustCertificate = onTrustCertificate,
                onForgetCertificate = onForgetCertificate,
            )
            DshTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "username",
                label = "Account",
            )
            DshTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "password",
                label = "Password",
                isPassword = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (advanced) "Hide advanced" else "Advanced",
                    style = DshType.bodySmall,
                    color = colors.link,
                    modifier = Modifier.clickableNoRipple { advanced = !advanced },
                )
            }

            if (advanced) {
                Column {
                    DshTextField(
                        value = lanUrl,
                        onValueChange = { lanUrl = it },
                        placeholder = "http://192.168.1.110/",
                        label = "LAN address (optional)",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(DshSpacing.md))
                    Text(
                        text = "A second address for the same host, reachable only on your " +
                            "own network. Tried first when it answers, so the app uses the " +
                            "LAN at home and the server address everywhere else.",
                        style = DshType.micro,
                        color = colors.labelCaption,
                    )
                    Spacer(Modifier.height(DshSpacing.xl))
                    DshTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        placeholder = "dsh-auth-…=v1.…  or  dsh_auth_session=…",
                        label = "Cookie (optional — bypasses login)",
                        singleLine = false,
                        minHeight = 96.dp,
                    )
                    Spacer(Modifier.height(DshSpacing.md))
                    Text(
                        text = "For a host reached directly on its carrier port, paste the " +
                            "browser cookie instead of a password. Leave the account blank.",
                        style = DshType.micro,
                        color = colors.labelCaption,
                    )
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    style = DshType.bodySmall,
                    color = colors.error,
                )
            }

            DshPrimaryButton(
                text = when {
                    busy -> startupStep?.let { StartupCopy.title(it) } ?: "Connecting…"
                    else -> "Connect"
                },
                enabled = !busy && url.isNotBlank(),
                onClick = { onConnect(url, lanUrl, username, password, cookie) },
            )

            if (busy) {
                Text(
                    text = startupStep?.let { StartupCopy.detail(it) }.orEmpty(),
                    style = DshType.micro,
                    color = colors.labelCaption,
                )
            }

            Spacer(Modifier.height(DshSpacing.lg))
            Text(
                text = "The app speaks the same HTTP and WebSocket API as the DSH web " +
                    "client. Nothing needs to be installed on the host.",
                style = DshType.micro,
                color = colors.labelCaption,
            )
        }
        Spacer(Modifier.height(48.dp))
    }
}

