package uk.xa0.dsh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.net.AcceptedCert
import uk.xa0.dsh.net.CertPins
import uk.xa0.dsh.net.CertReadout
import uk.xa0.dsh.net.hostOf
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Date

/**
 * The certificate half of a screen that names a host.
 *
 * Shared between the setup form and the connection settings because both are places
 * a user reasonably looks for it, and the one that matters is whichever they can
 * reach: a logged-in session has no route back to setup, and a certificate that can
 * only be pinned before signing in is a certificate nobody pins.
 *
 * Only for an https address: a cleartext LAN door has no certificate to discuss, and
 * a block saying so on every http install would be noise. Three states, in the order
 * the user meets them — not trusted yet, trusted (with the fingerprint that was
 * accepted), and a readout of what the host presents right now, where *any* link of
 * the chain can be trusted instead. Trusting the CA rather than the leaf is the
 * choice that outlives a leaf renewal, which is why the whole chain is offered and
 * not only the certificate the host happened to send first.
 *
 * [heading] is null where the caller already draws its own title, so the same block
 * sits inside a settings group without a second heading above it.
 */
@Composable
internal fun CertificateSection(
    url: String,
    acceptedCerts: List<AcceptedCert>,
    readout: CertReadout?,
    checking: Boolean,
    certError: String?,
    onCheckCertificate: (String) -> Unit,
    onTrustCertificate: (String, X509Certificate) -> Unit,
    onForgetCertificate: (String) -> Unit,
    heading: String? = "Certificate",
) {
    val colors = DshTheme.colors
    val host = remember(url) { hostOf(url) }
    if (host == null || !url.trim().startsWith("https://", ignoreCase = true)) return
    val accepted = acceptedCerts.firstOrNull { it.host == host }
    val shown = readout?.takeIf { it.host == host }

    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
        heading?.let { Text(it, style = DshType.micro, color = colors.labelCaption) }

        Text(host, style = DshType.bodySmall, color = colors.labelPrimary)

        if (accepted != null) {
            Text(
                text = "Trusted since ${trustedSince(accepted.acceptedAt)}",
                style = DshType.micro,
                color = colors.labelTertiary,
            )
            Text(
                text = CertPins.display(accepted.pin),
                style = DshType.codeSmall,
                color = colors.labelCaption,
            )
            Text(
                text = "Forget this certificate",
                style = DshType.micro,
                color = colors.link,
                modifier = Modifier.clickableNoRipple { onForgetCertificate(accepted.host) },
            )
        } else {
            Text(
                text = "Not trusted yet. An unknown certificate is recorded on first " +
                    "connection — check it first if you would rather see it than accept it.",
                style = DshType.micro,
                color = colors.labelCaption,
            )
        }

        Text(
            text = if (checking) "Checking…" else "Check certificate",
            style = DshType.micro,
            color = colors.link,
            modifier = Modifier.clickableNoRipple { if (!checking) onCheckCertificate(url) },
        )

        certError?.let { Text(it, style = DshType.micro, color = colors.error) }

        if (shown != null) {
            Text(
                text = if (shown.systemTrusted) {
                    "The system already trusts this chain."
                } else {
                    "The system does not vouch for this chain."
                },
                style = DshType.micro,
                color = colors.labelTertiary,
            )
            shown.chain.forEachIndexed { index, certificate ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(start = DshSpacing.sm),
                ) {
                    Text(
                        text = (if (index == 0) "Leaf — " else "Chain — ") +
                            certificate.subjectX500Principal.name,
                        style = DshType.micro,
                        color = colors.labelTertiary,
                    )
                    Text(
                        text = CertPins.display(shown.pinOf(certificate)),
                        style = DshType.codeSmall,
                        color = colors.labelCaption,
                    )
                    Text(
                        text = "Trust this certificate",
                        style = DshType.micro,
                        color = colors.link,
                        modifier = Modifier.clickableNoRipple {
                            onTrustCertificate(shown.host, certificate)
                        },
                    )
                }
            }
        }
    }
}

/**
 * When a certificate was accepted, in the reader's own locale and to the minute.
 *
 * "Trusted since" is a fact about a decision the user made, and the date is how they
 * recognise which decision it was.
 */
private fun trustedSince(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
