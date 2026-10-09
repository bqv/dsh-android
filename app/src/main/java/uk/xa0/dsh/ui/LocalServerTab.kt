package uk.xa0.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.LocalServerProbeResult
import uk.xa0.dsh.model.LocalServerTarget
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The llama.cpp server behind the session's local model, and what can honestly be
 * said about it.
 *
 * This is the fourth tab, and it exists only while the session's *selected* route
 * is local — see `LocalModelServer.kt` for the gate and for the probe evidence
 * behind every fact below.
 *
 * The tab is deliberately split in two, and the split is the whole point:
 *
 *  * **What the host declares** — endpoint, protocol, context window, output cap,
 *    modalities, whether a credential is sent. These are the host's own statements
 *    about the server it will talk to, and they are always available.
 *  * **What has been measured** — one check, run by the host on this client's
 *    behalf, that answers "can the host reach that endpoint, and what does it
 *    advertise right now". It is shown with the time it was taken, because a
 *    reachability answer without a timestamp is a claim about the past.
 *
 * What it never shows is the server's *internals*: which model is loaded, slot
 * occupancy, memory, or throughput. Those live on the router's own endpoints, which
 * are bound to the host's loopback and which the host does not forward, so this
 * client cannot see them — and a number nobody measured is worse than no number.
 * The closing paragraph says so in as many words, rather than leaving a reader to
 * wonder why a "server status" tab has no health on it.
 */
@Composable
fun LocalServerTab(
    target: LocalServerTarget,
    probe: LocalServerProbeResult,
    /** The active reasoning effort's own label, resolved at the call site. */
    effort: String?,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bgBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.xl)
            .padding(top = DshSpacing.xl, bottom = DshSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Text("Local model server", style = DshType.titleMedium, color = colors.labelPrimary)
        Text(
            text = target.displayName,
            style = DshType.bodyMedium,
            color = colors.labelSecondary,
        )

        Spacer(Modifier.height(DshSpacing.xs))
        Text(
            text = "THIS SESSION WILL RUN",
            style = DshType.micro.copy(fontWeight = FontWeight.Medium),
            color = colors.labelCaption,
        )
        ServerRow("Model", target.modelId)
        val declaredModel = target.model
        if (declaredModel?.name != null && declaredModel.name != declaredModel.modelId) {
            ServerRow("Display name", declaredModel.name)
        }
        if (!effort.isNullOrEmpty()) ServerRow("Reasoning effort", effort)

        Spacer(Modifier.height(DshSpacing.md))
        Text(
            text = "DECLARED BY THE HOST",
            style = DshType.micro.copy(fontWeight = FontWeight.Medium),
            color = colors.labelCaption,
        )
        ServerRow("Provider", "${target.provider.id} (${target.provider.displayName})")
        ServerRow("Endpoint", target.endpoint)
        target.provider.api?.let { ServerRow("Protocol", it) }
        ServerRow("Context window", target.contextWindow?.let(::tokenCount) ?: "Not declared")
        ServerRow("Max output", target.maxTokens?.let(::tokenCount) ?: "Not declared")
        ServerRow("Input", modalityText(target.input))
        ServerRow("Credential", if (target.authenticated) "Sent with each request" else "None")

        if (target.model == null) {
            // The routers advertise every GGUF in their directory; the settings
            // document lists the rungs a session may pick. An unlisted model is a
            // real state, and the rows above are then the provider's defaults —
            // which is worth saying rather than leaving the reader to guess why
            // the window looks wrong.
            Text(
                text = "The host's settings document does not list this model, so the " +
                    "window, output cap and modalities above are the provider's defaults.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )
        }

        Spacer(Modifier.height(DshSpacing.md))
        Text(
            text = "REACHABILITY",
            style = DshType.micro.copy(fontWeight = FontWeight.Medium),
            color = colors.labelCaption,
        )
        ProbeBlock(probe = probe, target = target)
        TextButton(
            onClick = onCheck,
            enabled = probe !is LocalServerProbeResult.Checking,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
        ) {
            Text(
                text = if (probe is LocalServerProbeResult.Idle) "Check server" else "Check again",
                color = if (probe is LocalServerProbeResult.Checking) colors.labelCaption else colors.link,
                style = DshType.bodyMedium,
            )
        }

        Spacer(Modifier.height(DshSpacing.md))
        ServerNote(
            text = "The host makes this check; this app cannot reach the endpoint itself. " +
                "The phone talks to the host over one forwarded port, and the host's API " +
                "does not forward the server's own endpoints.",
        )
        ServerNote(
            text = "So live internals — which model is loaded, slot occupancy, memory and " +
                "throughput — are not available here. Everything above is what the host " +
                "declares, plus the result of the last check.",
        )
    }
}

/**
 * The check's own paragraph.
 *
 * Each state says exactly one thing, and the two that carry an answer say when it
 * was taken.
 */
@Composable
private fun ProbeBlock(probe: LocalServerProbeResult, target: LocalServerTarget) {
    val colors = DshTheme.colors
    when (probe) {
        LocalServerProbeResult.Idle -> Text(
            text = "Not checked yet.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )

        LocalServerProbeResult.Checking -> Text(
            text = "Asking the host to reach ${target.endpoint}…",
            style = DshType.bodySmall,
            color = colors.labelSecondary,
        )

        is LocalServerProbeResult.Reachable -> Column(
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        ) {
            Text(
                text = "Reachable. At ${clock(probe.atMillis)} the host read " +
                    "${probe.modelIds.size} ${plural(probe.modelIds.size, "model", "models")} " +
                    "from the endpoint.",
                style = DshType.bodySmall,
                color = colors.success,
            )
            if (probe.advertises(target.modelId)) {
                Text(
                    text = "It advertises this session's model, ${target.modelId}.",
                    style = DshType.bodySmall,
                    color = colors.labelSecondary,
                )
            } else {
                // Worth its own sentence: a router answers `/v1/models` with every
                // model it *could* load, so this is a mismatch between the profile
                // and the running server — not proof that a turn would fail.
                Text(
                    text = "It does not advertise ${target.modelId}. The endpoint lists " +
                        "different models from the host's profile; a turn may still " +
                        "load it, or may fail.",
                    style = DshType.bodySmall,
                    color = colors.warn,
                )
            }
        }

        is LocalServerProbeResult.Unreachable -> Column(
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        ) {
            Text(
                text = "Not reachable at ${clock(probe.atMillis)}.",
                style = DshType.bodySmall,
                color = colors.error,
            )
            Text(probe.message, style = DshType.bodySmall, color = colors.labelSecondary)
        }
    }
}

@Composable
private fun ServerRow(label: String, value: String) {
    val colors = DshTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = DshType.bodySmall,
            color = colors.labelPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ServerNote(text: String) {
    val colors = DshTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.bgLayer1)
            .padding(DshSpacing.lg),
    ) {
        Text(text, style = DshType.bodySmall, color = colors.labelTertiary)
    }
}

/** `262144` → `262,144 tokens`; the groups a reader can actually count. */
private fun tokenCount(tokens: Int): String =
    java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(tokens) + " tokens"

/** The declared modalities in the host's own vocabulary. */
private fun modalityText(input: List<String>): String {
    if (input.isEmpty()) return "Text"
    return input.joinToString(", ") { modality ->
        when (modality) {
            "text" -> "Text"
            "image" -> "Images"
            else -> modality
        }
    }
}

private fun plural(count: Int, one: String, many: String): String =
    if (count == 1) one else many

private val serverClock = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun clock(atMillis: Long): String = serverClock.format(java.util.Date(atMillis))
