package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import uk.xa0.dsh.BalanceState
import uk.xa0.dsh.model.DEEPSEEK_TOP_UP_URL
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The account balance, and the way to top it up.
 *
 * Reached from the token-usage panel, which is where a reader is already looking at
 * what a session has cost — the balance is the same question one level up.
 *
 * Two facts the copy has to carry, because both are surprising and neither is
 * obvious from a number:
 *
 *  - the balance is **account-wide**, not per session or per key, so it is the same
 *    figure every client on the account would see (the host included);
 *  - **DeepSeek publishes no spend history at all** — this endpoint is the only
 *    account call there is — so the screen shows a balance and never a chart
 *    pretending to be one.
 *
 * The key belongs here rather than buried in Settings: it is a credential for
 * exactly this screen, and one tap from the panel that shows the figure it unlocks.
 * It is stored in the app's encrypted preferences and sent only to `api.deepseek.com`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceSheet(
    state: BalanceState,
    onKey: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = DshTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        BalanceContent(state = state, onKey = onKey, onRefresh = onRefresh, onDismiss = onDismiss)
    }
}

/** The sheet's body, split out so the sheet itself stays a container. */
@Composable
fun BalanceContent(
    state: BalanceState,
    onKey: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val uriHandler = LocalUriHandler.current
    // The field starts empty even when a key is saved: showing a stored secret back
    // to the screen is a habit worth not forming, and "Replace key" says what it does.
    var draft by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.xxl)
            .padding(bottom = DshSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        Text("DeepSeek account", style = DshType.titleMedium, color = colors.labelPrimary)
        Text(
            text = "The balance belongs to the account, not to this app: every key on it " +
                "reads the same figure, including the host's. DeepSeek publishes no spend " +
                "or usage history, so this is a balance and not a chart.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )

        when (state) {
            BalanceState.Idle -> SectionAction("Read the balance") { onRefresh() }
            BalanceState.Loading -> Text(
                "Reading…",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )

            BalanceState.NoKey -> Text(
                "No API key saved yet. Paste one to read the balance — it is stored " +
                    "encrypted on this device and sent only to api.deepseek.com.",
                style = DshType.bodySmall,
                color = colors.labelSecondary,
            )

            is BalanceState.Failed -> {
                Text(state.message, style = DshType.bodyMedium, color = colors.warn)
                SectionAction("Try again") { onRefresh() }
            }

            is BalanceState.Ready -> {
                val account = state.account
                if (!account.isAvailable) {
                    // DeepSeek's own flag: the account cannot serve requests (no
                    // balance, or suspended). Worth saying before the figure, which
                    // may well be zero.
                    Text(
                        "This account cannot serve requests right now.",
                        style = DshType.bodyMedium,
                        color = colors.warn,
                    )
                }
                val primary = account.primary
                if (primary == null) {
                    Text(
                        "DeepSeek reported no balances for this account.",
                        style = DshType.bodyMedium,
                        color = colors.labelSecondary,
                    )
                } else {
                    BalanceRow("Available", "${primary.currency} ${primary.totalBalance}")
                    BalanceRow("Granted", "${primary.currency} ${primary.grantedBalance}")
                    BalanceRow("Topped up", "${primary.currency} ${primary.toppedUpBalance}")
                    // A second currency is rare but real (a USD balance beside a CNY one),
                    // and silently dropping it would be the screen hiding money.
                    account.infos.filterNot { it === primary }.forEach { other ->
                        BalanceRow(
                            "Also (${other.currency})",
                            "${other.currency} ${other.totalBalance}",
                        )
                    }
                }
                SectionAction("Refresh") { onRefresh() }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionAction("Top up on DeepSeek") {
                // The platform page, in the browser: DeepSeek has no top-up API, and a
                // payment form inside this app would be inventing one.
                runCatching { uriHandler.openUri(DEEPSEEK_TOP_UP_URL) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.labelSecondary)
            }
        }

        DshTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "DeepSeek API key (sk-…)",
            modifier = Modifier.fillMaxWidth(),
            isPassword = true,
        )
        SectionAction(if (state is BalanceState.NoKey) "Save key" else "Replace key") {
            if (draft.isNotBlank()) {
                onKey(draft)
                draft = ""
                onRefresh()
            }
        }
        Text(
            text = "A key is only ever read here; this app never sends one anywhere else.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )
    }
}

@Composable
private fun BalanceRow(label: String, value: String) {
    val colors = DshTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DshType.bodySmall, color = colors.labelTertiary, modifier = Modifier.weight(1f))
        Text(value, style = DshType.bodyMedium, color = colors.labelPrimary)
        Spacer(Modifier.width(DshSpacing.xs))
    }
}

/** A tappable action line, in the sheets' own accent. */
@Composable
private fun SectionAction(label: String, onClick: () -> Unit) {
    val colors = DshTheme.colors
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
    ) {
        Text(label, color = colors.link, style = DshType.bodyMedium)
    }
}
