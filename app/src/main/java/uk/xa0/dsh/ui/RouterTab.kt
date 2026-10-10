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
import uk.xa0.dsh.model.RouterLoad
import uk.xa0.dsh.model.RouterProbeResult
import uk.xa0.dsh.model.RouterSlot
import uk.xa0.dsh.model.RouterTarget
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The llama.cpp **router** behind the session's local model, and what can honestly
 * be said about it.
 *
 * This is the fourth tab, and it exists only while the session's *selected* route
 * is local — see `LlamaRouter.kt` for the gate and for the probe evidence behind
 * every fact below.
 *
 * The tab is deliberately split into three, and the split is the whole point:
 *
 *  * **What the host declares** — endpoint, protocol, context window, output cap,
 *    modalities, whether a credential is sent. These are the host's own statements
 *    about the endpoint it will talk to, and they are always available.
 *  * **What has been measured** — a check the host runs on this client's behalf
 *    that answers "can the host reach that endpoint, and what does it advertise
 *    right now". Shown with the time it was taken, because a reachability answer
 *    without a timestamp is a claim about the past.
 *  * **What the router itself reports** — which model is resident, each slot's
 *    prefill progress, and how many tokens have been decoded. Read by running `curl`
 *    on the host through a terminal this app opens in the session and never presents
 *    (`model/RouterReadout.kt`, `net/RouterReadoutTransport.kt`), so it needs no
 *    plugin and no forwarded port. When that read cannot be made, the section says
 *    why — rather than by rows of dashes, which read as measured zeroes.
 *
 * The distinctions the tab is most careful about:
 *
 *  * **not reported** — [RouterProbeResult.Reachable.load] is null and
 *    [RouterProbeResult.Reachable.readoutIssue] is null: nothing here offers the
 *    readout, and its absence is stated as an absence;
 *  * **attempted and not obtained** — `load` is null and `readoutIssue` is set: the
 *    readout was tried and failed, and the reason is printed rather than a generic
 *    "not available", so a fixable failure cannot look like a host that cannot offer
 *    it at all;
 *  * **reported as nothing** — a [RouterLoad] with no models is the router listing
 *    none, and a [RouterLoad] whose `slotsUnread` is null with no slots is the router
 *    saying it holds no slots. A [RouterLoad] whose `slotsUnread` is set is the
 *    opposite: the slots were never read, and the PREFILL section says so instead of
 *    borrowing the sentence for an empty report.
 *
 * Memory, VRAM and throughput are *not* here even in the reported case. The router
 * reports load and prefill state only — the two things the user asked for — and this
 * tab does not draw a number no call returned.
 */
@Composable
fun RouterTab(
    target: RouterTarget,
    probe: RouterProbeResult,
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
        Text("llama.cpp router", style = DshType.titleMedium, color = colors.labelPrimary)
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
        RouterRow("Model", target.modelId)
        val declaredModel = target.model
        // The display name earns its row only when it says something the model row
        // above does not: the host's document usually gives `name` as the same
        // string as the id (`Spark-X2.5-4B-Q4_K_M-768k`), and drawing it twice is
        // noise. `LocalModelEntry` names its own id `id`, which is the very string
        // the entry was looked up by — so this compares against `target.modelId`.
        if (declaredModel?.name != null && declaredModel.name != target.modelId) {
            RouterRow("Display name", declaredModel.name)
        }
        if (!effort.isNullOrEmpty()) RouterRow("Reasoning effort", effort)

        Spacer(Modifier.height(DshSpacing.md))
        Text(
            text = "DECLARED BY THE HOST",
            style = DshType.micro.copy(fontWeight = FontWeight.Medium),
            color = colors.labelCaption,
        )
        RouterRow("Provider", "${target.provider.id} (${target.provider.displayName})")
        RouterRow("Endpoint", target.endpoint)
        target.provider.api?.let { RouterRow("Protocol", it) }
        RouterRow("Context window", target.contextWindow?.let(::tokenCount) ?: "Not declared")
        RouterRow("Max output", target.maxTokens?.let(::tokenCount) ?: "Not declared")
        RouterRow("Input", modalityText(target.input))
        RouterRow("Credential", if (target.authenticated) "Sent with each request" else "None")

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
            enabled = probe !is RouterProbeResult.Checking,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
        ) {
            Text(
                text = if (probe is RouterProbeResult.Idle) "Check router" else "Check again",
                color = if (probe is RouterProbeResult.Checking) colors.labelCaption else colors.link,
                style = DshType.bodyMedium,
            )
        }

        // The router's own report, when there is one to draw.
        val load = (probe as? RouterProbeResult.Reachable)?.load
        if (load != null) {
            Spacer(Modifier.height(DshSpacing.md))
            LoadSection(load = load, target = target)
            Spacer(Modifier.height(DshSpacing.md))
            PrefillSection(load = load)
        }

        Spacer(Modifier.height(DshSpacing.md))
        RouterNote(
            text = "The routers answer on the host's own loopback, which this phone cannot " +
                "reach: the host's API forward no router endpoint, and nothing here opens " +
                "a port to the network. The readout above is made by a shell command the " +
                "host runs at this app's request — a terminal this app opens in the " +
                "session and does not show — so it travels the same connection as " +
                "everything else and needs no plugin, no tunnel and no adb.",
        )
        if (load != null) {
            RouterNote(
                text = "Load and prefill above are the router's own report, read by the " +
                    "host. Memory and throughput are not part of that report and are " +
                    "therefore not shown.",
            )
        } else if ((probe as? RouterProbeResult.Reachable)?.readoutIssue != null) {
            RouterNote(
                text = "The endpoint answered, so the router is up; the readout itself " +
                    "did not arrive. The reason is above, and it is a fact about this " +
                    "app's access to the host rather than about the router.",
            )
        } else {
            RouterNote(
                text = "The router's own readouts — which model is loaded, each slot's " +
                    "prefill progress, and how many tokens it has decoded — were not " +
                    "obtained for this check. Saying so is better than drawing a number " +
                    "nobody measured.",
            )
        }
    }
}

/**
 * The check's own paragraph.
 *
 * Each state says exactly one thing, and the two that carry an answer say when it
 * was taken.
 */
@Composable
private fun ProbeBlock(probe: RouterProbeResult, target: RouterTarget) {
    val colors = DshTheme.colors
    when (probe) {
        RouterProbeResult.Idle -> Text(
            text = "Not checked yet.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )

        RouterProbeResult.Checking -> Text(
            text = "Asking the host to reach ${target.endpoint}…",
            style = DshType.bodySmall,
            color = colors.labelSecondary,
        )

        is RouterProbeResult.Reachable -> Column(
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
                // and the running router — not proof that a turn would fail.
                Text(
                    text = "It does not advertise ${target.modelId}. The endpoint lists " +
                        "different models from the host's profile; a turn may still " +
                        "load it, or may fail.",
                    style = DshType.bodySmall,
                    color = colors.warn,
                )
            }
            // Three states, and the third is the one that is easy to lose: the readout
            // was *attempted* and did not arrive. Printing the generic "not available"
            // sentence here would let a fixable failure look like a host that cannot
            // offer the readout at all.
            probe.readoutIssue?.let { issue ->
                Text(
                    text = "The router's own load and prefill readout could not be read: " +
                        "$issue The reachability above was measured separately, so it " +
                        "stands on its own.",
                    style = DshType.bodySmall,
                    color = colors.warn,
                )
            }
        }

        is RouterProbeResult.Unreachable -> Column(
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

/**
 * What the router says it is holding.
 *
 * The resident-model sentence comes first because it is the answer to the question
 * the section's heading asks; the full advertised list follows, because a router
 * that holds twelve GGUFs while loading one is the normal state of this box and the
 * count is how a reader knows the listing was read rather than defaulted.
 */
@Composable
private fun LoadSection(load: RouterLoad, target: RouterTarget) {
    val colors = DshTheme.colors
    Text(
        text = "LOAD",
        style = DshType.micro.copy(fontWeight = FontWeight.Medium),
        color = colors.labelCaption,
    )
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        when {
            load.loaded.size == 1 -> {
                val resident = load.loaded.first()
                Text(
                    text = "Loaded: ${resident.id}",
                    style = DshType.bodySmall,
                    color = colors.success,
                )
            }

            load.loaded.size > 1 -> Text(
                text = "Loaded: ${load.loaded.joinToString(", ") { it.id }}",
                style = DshType.bodySmall,
                color = colors.success,
            )

            load.loading.isNotEmpty() -> Text(
                text = "Loading: ${load.loading.joinToString(", ") { it.id }}",
                style = DshType.bodySmall,
                color = colors.warn,
            )

            load.models.isEmpty() -> Text(
                text = "The router listed no models.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )

            else -> Text(
                text = "Nothing is loaded. The route's turn would load " +
                    "${target.modelId} on demand.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )
        }

        // The resident model's own launch facts, when the router reported them.
        // Both halves are the router's numbers, not the profile's declarations —
        // worth separating, because the two can disagree (the profile's
        // `contextWindow` is a rung a session may pick; `--ctx-size` is what is
        // actually serving).
        load.loaded.firstOrNull()?.let { resident ->
            resident.contextSize?.let { RouterRow("Serving context", tokenCount(it)) }
            resident.childPort?.let { RouterRow("Instance port", it.toString()) }
        }
        load.maxInstances?.let { RouterRow("Holds at most", "$it ${plural(it, "model", "models")}") }
        load.modelsAutoload?.let { RouterRow("Loads on demand", if (it) "Yes" else "No") }
        RouterRow(
            label = "Advertised",
            value = if (load.models.isEmpty()) {
                "None listed"
            } else {
                "${load.models.size} ${plural(load.models.size, "model", "models")}"
            },
        )
    }
}

/**
 * What each slot is doing, from the router's `/slots`.
 *
 * `is_processing` is shown as its own word rather than inferred from the token
 * counts: a slot can be resident and idle, and drawing "processing" for it because
 * its counters are non-zero would be an invention. The bars are deliberately absent
 * — a progress bar needs a denominator, and `n_prompt_tokens` is the router's
 * *current task's* prompt, not a fixed budget.
 */
@Composable
private fun PrefillSection(load: RouterLoad) {
    val colors = DshTheme.colors
    Text(
        text = "PREFILL",
        style = DshType.micro.copy(fontWeight = FontWeight.Medium),
        color = colors.labelCaption,
    )
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        // "Not read" first, and as its own sentence: the router never being asked is a
        // different fact from the router answering that it holds no slots, and the two
        // must not share copy. `slotsUnread` is set by the reader whenever the request
        // was withheld or did not answer, and it carries the reason.
        load.slotsUnread?.let { reason ->
            Text(
                text = "Not read: $reason.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )
            return@Column
        }
        if (load.slots.isEmpty()) {
            Text(
                text = "The router reported no slots.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )
            return@Column
        }
        load.slotsModel?.let { RouterRow("Slots for", it) }
        load.slotsContextSize?.let { RouterRow("Slot context", tokenCount(it)) }
        val busy = load.processing.size
        Text(
            text = when {
                busy == 0 -> "Idle: no slot is processing a prompt."
                busy == 1 -> "1 of ${load.slots.size} slots is processing."
                else -> "$busy of ${load.slots.size} slots are processing."
            },
            style = DshType.bodySmall,
            color = if (busy == 0) colors.labelSecondary else colors.accent,
        )
        for (slot in load.slots) {
            SlotBlock(slot)
        }
    }
}

@Composable
private fun SlotBlock(slot: RouterSlot) {
    val colors = DshTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        Text(
            text = "Slot ${slot.id} — " + if (slot.isProcessing) "processing" else "idle",
            style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (slot.isProcessing) colors.accent else colors.labelSecondary,
        )
        slot.promptTokens?.let { RouterRow("Prompt", tokenCount(it)) }
        slot.promptTokensProcessed?.let { RouterRow("Processed", tokenCount(it)) }
        slot.promptTokensRemaining?.let { RouterRow("Still to do", tokenCount(it)) }
        slot.promptTokensCache?.let { RouterRow("From cache", tokenCount(it)) }
        slot.decoded?.let { RouterRow("Decoded", tokenCount(it)) }
        slot.remaining?.let { RouterRow("Output left", tokenCount(it)) }
    }
}

@Composable
private fun RouterRow(label: String, value: String) {
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
private fun RouterNote(text: String) {
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

private val routerClock = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun clock(atMillis: Long): String = routerClock.format(java.util.Date(atMillis))
