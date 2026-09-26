package uk.xa0.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.AgentPresetOption
import uk.xa0.dsh.ContextBreakdown
import uk.xa0.dsh.FULL_ACCESS_PRESET
import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.PermissionOption
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.HostSettings
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    themeMode: String,
    agentPreset: String,
    onTheme: (String) -> Unit,
    onPreset: (String) -> Unit,
    busyEnter: String,
    onBusyEnter: (String) -> Unit,
    onDismiss: () -> Unit,
    // Everything below is the read-only parity data. Each default keeps the
    // existing ChatScreen call compiling, and the parent wires the real UiState
    // values into the same names.
    models: List<ModelOption> = emptyList(),
    providerOrder: List<String> = emptyList(),
    permissionOptions: List<PermissionOption> = emptyList(),
    defaultPermission: String = "",
    transcriptView: String = "compact",
    agentPresetOptions: List<AgentPresetOption> = emptyList(),
    agentModePickerEnabled: Boolean = true,
    archivedSessions: List<SessionItem> = emptyList(),
    workspaces: List<WorkspaceItem> = emptyList(),
    sessionsLoading: Boolean = false,
    onUnarchive: ((String) -> Unit)? = null,
    /**
     * The host's own settings document, read when the sheet opens. All defaulted,
     * so a caller that passes none of it gets the client-local rows as before.
     */
    hostSettings: HostSettings? = null,
    hostSettingsLoading: Boolean = false,
    hostSettingsError: String? = null,
    settingsSaving: String? = null,
    settingsWriteError: String? = null,
    onSettingWrite: ((String, String, String) -> Unit)? = null,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        // The web sections and their order live in `SettingsContent`; this
        // entry point stays in `uk.xa0.dsh.ui` because ChatScreen opens it by
        // that name. The non-settable facts that used to be bolted on the end
        // now have their own surface — `AboutSheet` below.
        SettingsContent(
            themeMode = themeMode,
            busyEnter = busyEnter,
            onTheme = onTheme,
            onBusyEnter = onBusyEnter,
            defaultPermission = defaultPermission,
            permissionOptions = permissionOptions,
            transcriptView = transcriptView,
            models = models,
            providerOrder = providerOrder,
            agentPreset = agentPreset,
            agentPresetOptions = agentPresetOptions,
            agentModePickerEnabled = agentModePickerEnabled,
            archivedSessions = archivedSessions,
            workspaces = workspaces,
            sessionsLoading = sessionsLoading,
            hostSettings = hostSettings,
            hostSettingsLoading = hostSettingsLoading,
            hostSettingsError = hostSettingsError,
            settingsSaving = settingsSaving,
            settingsWriteError = settingsWriteError,
            onSettingWrite = onSettingWrite,
            onPreset = onPreset,
            onUnarchive = onUnarchive,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The About sheet: the app's own facts — what it is, the host it is pointed at,
 * how it is signed in, what it is running on, and the open session. Everything
 * here is local state, so nothing is read from the host on open and the page
 * stays truthful with the socket down.
 *
 * A sheet rather than a screen because it is reached from the drawer's footer
 * beside Settings and is dismissed the same way; ChatScreen owns the flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    baseUrl: String,
    username: String,
    socketConnected: Boolean,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    sessionTitle: String = "",
    sessionCwd: String? = null,
    sessionPermission: String = "",
    sessionAgentPreset: String = "",
    permissionOptions: List<PermissionOption> = emptyList(),
    selectedModel: ModelOption? = null,
    selectedEffort: String? = null,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        AboutContent(
            baseUrl = baseUrl,
            username = username,
            socketConnected = socketConnected,
            onSignOut = onSignOut,
            onDismiss = onDismiss,
            sessionTitle = sessionTitle,
            sessionCwd = sessionCwd,
            sessionPermission = sessionPermission,
            sessionAgentPreset = sessionAgentPreset,
            permissionOptions = permissionOptions,
            selectedModel = selectedModel,
            selectedEffort = selectedEffort,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(
    models: List<ModelOption>,
    providerOrder: List<String>,
    selected: ModelOption?,
    selectedEffort: String?,
    onSelect: (ModelOption, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val grouped = remember(models, providerOrder) {
        models.groupBy { it.providerName }
            .toList()
            .sortedBy { (name, _) -> providerOrder.indexOf(name).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = DshSpacing.xxl)) {
            Text(
                text = "Model",
                style = DshType.heading2,
                color = colors.labelPrimary,
                modifier = Modifier.padding(horizontal = DshSpacing.xxl),
            )
            Spacer(Modifier.height(DshSpacing.lg))

            if (models.isEmpty()) {
                Text(
                    text = "No models reported by the host.",
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(horizontal = DshSpacing.xxl),
                )
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                grouped.forEach { (provider, options) ->
                    item(key = "h-$provider") {
                        Text(
                            text = provider.uppercase(),
                            style = DshType.micro,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(
                                start = DshSpacing.xxl,
                                end = DshSpacing.xxl,
                                top = DshSpacing.lg,
                                bottom = DshSpacing.sm,
                            ),
                        )
                    }
                    items(options, key = { it.provider + "/" + it.model }) { option ->
                        val isSelected = selected?.provider == option.provider && selected.model == option.model
                        // The selected route's card owns both its row and its
                        // effort chips: the hover surface wraps the chips too, so
                        // they read as that model's own settings rather than as a
                        // detached strip under it. Only the Row stays clickable —
                        // the card's slack must not fall through to
                        // `onSelect(..., defaultEffort)` and silently reset an
                        // effort the tap was aiming at.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DshSpacing.lg)
                                .clip(RoundedCornerShape(DshRadius.md))
                                .background(if (isSelected) colors.hover else colors.bgBase),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickableNoRipple { onSelect(option, option.defaultEffort) }
                                    .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = option.name,
                                        style = DshType.messageBody.copy(
                                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                        ),
                                        color = colors.labelPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = option.model,
                                        style = DshType.micro,
                                        color = colors.labelTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            // Reasoning effort belongs to the route, so the chips
                            // appear under the selected model only. Both the model
                            // and the effort ride `session/selectModel`.
                            if (isSelected && option.efforts.isNotEmpty()) {
                                // Insets match the model row's own content box: the
                                // 24dp lead lines the chips up under the model's
                                // name (12dp card + 12dp inner padding) and the
                                // trailing side matches it instead of stopping at
                                // the card's edge — 24dp against 12dp is what read
                                // as a lopsided row. Vertically the 12dp above the
                                // chips is the row's own bottom inner padding and
                                // the 12dp below them is this `bottom`, both inside
                                // the one card, so the gap that used to fall between
                                // two separate surfaces now sits inside the surface
                                // the chips belong to and they hug their own model.
                                // A route can carry four levels (Off/Low/High/Max is
                                // ~262dp of chips), so this wraps rather than
                                // running the last one off a phone's edge; the
                                // wrapped lines keep the tighter 6dp
                                // `verticalArrangement` — intra-group spacing —
                                // while 12dp stays the group margin above and below.
                                FlowRow(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = DshSpacing.lg,
                                            end = DshSpacing.lg,
                                            bottom = DshSpacing.lg,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
                                    verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                                ) {
                                    option.efforts.forEach { effort ->
                                        val active = effort.id == selectedEffort ||
                                            (selectedEffort == null && effort.id == option.defaultEffort)
                                        ChoiceChip(
                                            label = effort.name,
                                            selected = active,
                                            onClick = { onSelect(option, effort.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Workspace picker for a new session.
 *
 * The host has no "change cwd" RPC, so a pick resolves the target through
 * [uk.xa0.dsh.model.SessionTargets]: a blank the picked Workspace already holds
 * is reused, a blank already rooted at that directory (including the one you are
 * looking at) is adopted by both ids, and only otherwise is a session created.
 * The limit is the host's, not the UI's — its identity check compares the stored
 * `cwd` to the Workspace path, so a blank sitting in another directory cannot be
 * carried across; the Workspace's own blank is the one that gets reused. The web
 * shows the same control as a chip on the hero, above the composer.
 *
 * A registered row reports its **id** and a typed path reports its text: only the
 * id form attaches the session to the Workspace, and it still does so when the
 * registry has not reached this client yet — which is exactly when a path lookup
 * would miss and the session would land in Ungrouped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    workspaces: List<WorkspaceItem>,
    currentCwd: String?,
    onPickWorkspace: (String) -> Unit,
    onPickPath: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var custom by remember { mutableStateOf(false) }
    var customPath by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.lg)
                .padding(bottom = DshSpacing.xxl),
        ) {
            Text(
                text = "Where should this session run?",
                style = DshType.heading2,
                color = colors.labelPrimary,
                modifier = Modifier.padding(horizontal = DshSpacing.md),
            )
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                text = "A session's directory is fixed when it is created.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.md),
            )
            Spacer(Modifier.height(DshSpacing.lg))

            workspaces.forEach { workspace ->
                val selected = workspace.path == currentCwd
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(if (selected) colors.hover else colors.bgBase)
                        .clickableNoRipple { onPickWorkspace(workspace.id) }
                        .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(DshSpacing.lg))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = workspace.title,
                            style = DshType.messageBody.copy(
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = workspace.path,
                            style = DshType.micro,
                            color = colors.labelTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.borderL1),
            )

            if (custom) {
                Spacer(Modifier.height(DshSpacing.lg))
                DshTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    placeholder = "/home/user/some/project",
                    label = "Directory",
                )
                Spacer(Modifier.height(DshSpacing.lg))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(DshRadius.card))
                        .background(if (customPath.isBlank()) colors.labelDimmed else colors.ink)
                        .clickableNoRipple(enabled = customPath.isNotBlank()) { onPickPath(customPath) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Start session here",
                        style = DshType.labelLarge,
                        color = if (customPath.isBlank()) colors.labelTertiary else colors.bgBase,
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickableNoRipple { custom = true }
                        .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CreateNewFolder,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(DshSpacing.lg))
                    Text("Another directory…", style = DshType.messageBody, color = colors.accent)
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) colors.accentTertiary else colors.tip)
            .border(0.5.dp, if (selected) colors.accent else colors.borderL1, shape)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
    ) {
        Text(
            text = label,
            style = DshType.bodyMedium,
            color = if (selected) colors.accent else colors.labelSecondary,
        )
    }
}

/**
 * Access-mode picker. Values and copy come from the host
 * (`permissionPresets/catalog`); Full access keeps the host's own
 * acknowledgement gate, so choosing it opens a confirmation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    options: List<PermissionOption>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by remember { mutableStateOf<PermissionOption?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = DshSpacing.xxl),
        ) {
            Text(
                text = "Access mode",
                style = DshType.heading2,
                color = colors.labelPrimary,
                modifier = Modifier.padding(horizontal = DshSpacing.xxl),
            )
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                text = "Applies to this session's tool calls.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.xxl),
            )
            Spacer(Modifier.height(DshSpacing.lg))

            if (options.isEmpty()) {
                Text(
                    text = "The host did not report any presets.",
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(horizontal = DshSpacing.xxl),
                )
            }

            options.forEach { option ->
                val selected = option.value == current
                val dangerous = option.value == FULL_ACCESS_PRESET
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpacing.lg)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(if (selected) colors.hover else colors.bgBase)
                        .clickableNoRipple {
                            if (dangerous && !selected) confirming = option else onSelect(option.value)
                        }
                        .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = option.name,
                            style = DshType.messageBody.copy(
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = if (dangerous) colors.warn else colors.labelPrimary,
                        )
                        option.description?.let {
                            Text(
                                text = it,
                                style = DshType.micro,
                                color = colors.labelTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    confirming?.let { option ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = colors.bgBase,
            title = { Text("Enable Full access?", color = colors.labelPrimary, style = DshType.titleMedium) },
            text = {
                Text(
                    text = "Full access lets new sessions reduce confirmation steps and perform " +
                        "more actions directly, including sensitive operations, file changes, or " +
                        "external commands. Only use it when you trust subsequent tasks.",
                    style = DshType.bodyMedium,
                    color = colors.labelSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onSelect(option.value)
                }) {
                    Text("Enable Full access", color = colors.warn)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text("Cancel", color = colors.labelSecondary)
                }
            },
        )
    }
}

/**
 * Agent-preset picker for the blank session the hero is about to start.
 *
 * Per-session by construction: the host composes a session's agent once, so a
 * preset can only change before the first turn (`agentPresets/select` refuses a
 * started session with `agent-preset/locked`). The web puts the same control on
 * the new-session screen, not in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPresetSheet(
    options: List<AgentPresetOption>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = DshSpacing.xxl),
        ) {
            Text(
                text = "Agent preset",
                style = DshType.heading2,
                color = colors.labelPrimary,
                modifier = Modifier.padding(horizontal = DshSpacing.xxl),
            )
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                // Web copy, `seatHint`: the chip's own tooltip.
                text = "Agent preset for the session you are about to start",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.xxl),
            )
            Spacer(Modifier.height(DshSpacing.lg))

            if (options.isEmpty()) {
                Text(
                    text = "Could not load agent presets.",
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(horizontal = DshSpacing.xxl),
                )
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(options, key = { it.id }) { option ->
                    val selected = option.id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DshSpacing.lg)
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(if (selected) colors.hover else colors.bgBase)
                            .clickableNoRipple { onSelect(option.id) }
                            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = agentPresetLabel(option.id, option.name),
                                style = DshType.messageBody.copy(
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                ),
                                color = if (option.broken) colors.warn else colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // Web copy: `noDescription`.
                                // Web copy: `noDescription`, and the shipped
                                // presets' own descriptions for the built-ins.
                                text = agentPresetDescription(option.id, option.description)
                                    ?: "No description.",
                                style = DshType.micro,
                                color = colors.labelTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Context-window dialog: the percentage the composer ring shows, plus the token
 * split the host reports in `contextBreakdown`.
 */
@Composable
fun ContextDialog(
    percent: Int,
    usedTokens: Int,
    windowTokens: Int,
    breakdown: ContextBreakdown?,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgBase,
        title = { Text("Context window", color = colors.labelPrimary, style = DshType.titleMedium) },
        text = {
            Column {
                Text(
                    text = if (windowTokens > 0) {
                        "$percent% · ${formatTokens(usedTokens)} of ${formatTokens(windowTokens)} tokens"
                    } else {
                        "The host has not reported a context window for this session yet."
                    },
                    style = DshType.bodyMedium,
                    color = colors.labelSecondary,
                )
                if (breakdown != null) {
                    Spacer(Modifier.height(DshSpacing.lg))
                    BreakdownRow("System", breakdown.system, windowTokens, colors.accent)
                    BreakdownRow("Tools", breakdown.tools, windowTokens, colors.synFunction)
                    BreakdownRow("Messages", breakdown.messages, windowTokens, colors.success)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = colors.accent) }
        },
    )
}

@Composable
private fun BreakdownRow(label: String, tokens: Int, window: Int, tint: Color) {
    val colors = DshTheme.colors
    val fraction = if (window > 0) (tokens.toFloat() / window).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(vertical = DshSpacing.xs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = DshType.bodySmall, color = colors.labelSecondary, modifier = Modifier.weight(1f))
            Text(formatTokens(tokens), style = DshType.bodySmall, color = colors.labelTertiary)
        }
        Spacer(Modifier.height(DshSpacing.xs))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.borderL1),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.0fk", tokens / 1_000.0)
    else -> tokens.toString()
}

/**
 * The host serves preset names localized to its own deployment (this host returns
 * Chinese), so the four shipped presets are labelled client-side from their ids,
 * exactly as `dsh-agent-presets/display.ts` maps them (`presetStandardName`, …).
 * A user-authored preset keeps whatever its own metadata says.
 */
fun agentPresetLabel(id: String, hostName: String): String = when (id.lowercase()) {
    "standard" -> "Standard mode"
    "ptc" -> "PTC mode"
    "minimal" -> "Minimal mode"
    "cordis" -> "Creator mode"
    else -> hostName.ifEmpty { id }
}

/**
 * The shipped description for a built-in preset, from the web's own
 * `settings.agentPreset` dictionary; a custom preset keeps its own description.
 */
fun agentPresetDescription(id: String, hostDescription: String?): String? = when (id.lowercase()) {
    "standard" -> "Full coding agent with file editing, shell, file and web search, " +
        "skills, planning, goals, subagents, and workflows."

    "ptc" -> "Full coding agent without the workflow tool; other tools are exposed " +
        "through the PTC mode SDK so the model can combine multi-step operations in " +
        "one TypeScript program."

    "minimal" -> "Single-tool coding agent with a persistent shell."

    "cordis" -> "Built for creating custom agent presets, with all Standard mode " +
        "capabilities plus runtime inspection, plugin experiments, and " +
        "preset-authoring guidance."

    else -> hostDescription
}

/** True for the four presets the deployment ships (`shipped-root.spec.ts`). */
fun isBuiltInPreset(id: String): Boolean = id.lowercase() in BUILT_IN_PRESET_IDS

private val BUILT_IN_PRESET_IDS = setOf("standard", "ptc", "minimal", "cordis")
