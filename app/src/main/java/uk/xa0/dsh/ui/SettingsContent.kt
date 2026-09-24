package uk.xa0.dsh.ui

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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.AgentPresetOption
import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.PermissionOption
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.data.BusyEnter
import uk.xa0.dsh.data.ThemeMode
import uk.xa0.dsh.model.GeneralSettings
import uk.xa0.dsh.model.HostSettings
import uk.xa0.dsh.model.PERMISSION_PRESET_LABELS
import uk.xa0.dsh.model.SettingsChoice
import uk.xa0.dsh.model.SettingsField
import uk.xa0.dsh.model.displayPresetName
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The port of the web Settings modal.
 *
 * The web shell is a left nav rail over five sections, in registration order:
 * **General** (0) / **Models** (10) / **Plugins** (15) / **Agent presets** (20) /
 * **Archived sessions** (25). A phone sheet cannot afford a 188px rail, so the
 * sections stack vertically in that same order, each with its nav label as the
 * heading; the grouping inside a section follows the web's own row order.
 *
 * **General is the one section the shell owns, and the one this client can
 * actually edit.** Its five host-backed rows are the web's own feature-owned
 * settings — `permission.defaultPreset`, `ui-theme.preference`,
 * `ui-theme.fontSize`, `ui-chat.transcriptView`, `ui-conversation.busyEnter` —
 * and each renders as the app's existing control (a selector pill where the web
 * has a Menu, chips where it has cubes, the stepper where it has one). The sheet
 * never writes: a change is reported upward with its namespace and field, so all
 * protocol knowledge stays in the view model. Whether a row *may* be edited is
 * the host's answer, not the app's guess: `HostSettings` carries the provider's
 * `writable` flag, each namespace's `applies`, and its `secrets`, and a row the
 * host would refuse stays read-only with the host's own wording in the section
 * note. That is also what makes this surface different from the web's on a remote
 * page: the browser client is handed the `memory` settings scope, whose `enqueue`
 * is a no-op (`settings-scope.ts:165-166`), while this app asks the host directly
 * and obeys the provider's real `writable` answer. A caller that hands the panel
 * nothing host-side at all leaves these five rows on the client's own stored
 * preferences — the rendering this sheet had before the write path existed —
 * rather than showing five dead controls.
 *
 * Everything the web can edit but this client cannot is still a value with a
 * disabled control, or omitted when the client has no such value at all (the
 * Language row). Each deliberate omission is called out in
 * `docs/research/files-and-deliverables.md`'s sibling report and in the parent's
 * handoff.
 *
 * Settings carries only what the user can change. The meta that used to be
 * bolted on below it — the **Connection** block (server, account, sign-in,
 * cookie, status), the **Session** block, the app version and the sign-out
 * action — is not settable, so it moved to the About sheet (`AboutScreen.kt`),
 * which the drawer's footer opens beside Settings. Its credential rows still
 * read `ui/settings/SettingsLocalFacts.kt`.
 */
@Composable
fun SettingsContent(
    themeMode: String,
    busyEnter: String,
    onTheme: (String) -> Unit,
    onBusyEnter: (String) -> Unit,
    defaultPermission: String,
    permissionOptions: List<PermissionOption>,
    transcriptView: String,
    models: List<ModelOption>,
    providerOrder: List<String>,
    agentPreset: String,
    agentPresetOptions: List<AgentPresetOption>,
    agentModePickerEnabled: Boolean,
    archivedSessions: List<SessionItem>,
    workspaces: List<WorkspaceItem>,
    sessionsLoading: Boolean,
    /**
     * The host settings panel's state, all of it defaulted so a caller that has
     * not adopted the settings API keeps rendering exactly what it did before.
     * A non-null value in any of the first three means the caller *did* wire the
     * panel, and the General rows then answer to the host rather than to the
     * client-local preferences they carried before — including when the answer
     * is "no".
     */
    hostSettings: HostSettings? = null,
    hostSettingsLoading: Boolean = false,
    /** The read's failure text, or the reason there was no read ("Not signed in"). */
    hostSettingsError: String? = null,
    /** `<ns>.<field>` of the row with a `settings/update` in flight. */
    settingsSaving: String? = null,
    /** The host's own wording for the last refused write. */
    settingsWriteError: String? = null,
    /** One changed field, as (namespace, field, value). Null leaves the rows unwired. */
    onSettingWrite: ((String, String, String) -> Unit)? = null,
    onPreset: (String) -> Unit,
    onUnarchive: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = DshSpacing.xxxl),
    ) {
        // The web header: title + Close (`settings.title` / `settings.close`).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Settings",
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

        SectionHeading("General")
        // Web General registration order: permission (-20), language (0),
        // appearance (10), font-size (11), transcript-view (12), composer-enter
        // (20). The Language row is omitted: this client has no locale setting.
        GeneralSection(
            themeMode = themeMode,
            onTheme = onTheme,
            busyEnter = busyEnter,
            onBusyEnter = onBusyEnter,
            defaultPermission = defaultPermission,
            permissionOptions = permissionOptions,
            transcriptView = transcriptView,
            hostSettings = hostSettings,
            loading = hostSettingsLoading,
            readError = hostSettingsError,
            saving = settingsSaving,
            writeError = settingsWriteError,
            onWrite = onSettingWrite,
        )

        SectionHeading("Models")
        ModelsSection(models, providerOrder)

        SectionHeading("Plugins")
        PluginsSection()

        SectionHeading("Agent presets")
        AgentPresetsSection(
            agentPreset = agentPreset,
            options = agentPresetOptions,
            pickerEnabled = agentModePickerEnabled,
            onPreset = onPreset,
        )

        SectionHeading("Archived sessions")
        ArchivedSessionsSection(
            sessions = archivedSessions,
            workspaces = workspaces,
            loading = sessionsLoading,
            onUnarchive = onUnarchive,
        )
    }
}

// ------------------------------------------------------------------- General

@Composable
private fun GeneralSection(
    themeMode: String,
    onTheme: (String) -> Unit,
    busyEnter: String,
    onBusyEnter: (String) -> Unit,
    defaultPermission: String,
    permissionOptions: List<PermissionOption>,
    transcriptView: String,
    hostSettings: HostSettings?,
    loading: Boolean,
    readError: String?,
    saving: String?,
    writeError: String?,
    onWrite: ((String, String, String) -> Unit)?,
) {
    // "Wired" is decided by the caller handing the panel anything at all: with
    // every host fact absent the sheet renders the client-local preferences it
    // always did, so a caller that has not adopted `settings/describe` is not
    // left with five dead rows.
    val wired = hostSettings != null || loading || readError != null

    /**
     * The change callback for one row, or null when the host would refuse the
     * write: no field, no value, a `restart`-scoped namespace, a secret slot, or
     * a provider that does not accept writes. Every one of those is a fact the
     * describe reply carried, so the row can be honest about which it is by
     * simply not offering a control.
     */
    fun writerFor(field: SettingsField?): ((String) -> Unit)? {
        if (field == null || !field.editable || hostSettings?.writable != true) return null
        val write = onWrite ?: return null
        return { value -> write(field.ns, field.name, value) }
    }

    /** The host's value under the web's own label, or [fallback] before a read. */
    fun labelFor(field: SettingsField?, fallback: String): String {
        val value = field?.value ?: return fallback
        return field.choices.firstOrNull { it.value == value }?.label ?: value
    }

    if (wired) {
        when {
            readError != null -> SectionErrorNote(readError)
            hostSettings == null -> SectionNote("Reading settings…")
            // The web's own read-only copy (`settings.models.readOnly`); the fact
            // is the same one on this panel.
            !hostSettings.writable -> SectionNote("The settings document is read-only in this deployment.")
        }
    }
    // The refusal is about an action the user just took, so it keeps the host's
    // own wording — a schema error paraphrased by the client would hide which
    // value the host rejected.
    writeError?.let { SectionErrorNote(it) }

    // Permission: a write to `permission.defaultPreset` is what *new* sessions
    // get, not the open session's own mode (that fact now sits on the About
    // sheet, and the web keeps the two apart the same way).
    val permission = hostSettings?.field(GeneralSettings.PERMISSION_NS, GeneralSettings.PERMISSION_FIELD)
    val permissionWrite = writerFor(permission)
    SettingsRow(
        title = "Permission",
        description = "Choose the default permission mode for new sessions",
    ) {
        SettingsSelector(
            label = labelFor(permission, permissionDisplay(defaultPermission, permissionOptions)),
            choices = permission?.choices.orEmpty(),
            selected = permission?.value,
            onSelect = permissionWrite,
            busy = saving != null && saving == permission?.key,
        )
    }

    // Appearance: the persisted preference, never the resolved theme — and the
    // web's cube order is Light, Dark, System.
    val appearance = hostSettings?.field(GeneralSettings.THEME_NS, GeneralSettings.THEME_PREFERENCE_FIELD)
    val appearanceWrite = writerFor(appearance)
    SettingsGroup(title = "Appearance") {
        SettingsChoiceRow(
            choices = appearance?.choices.orEmpty().ifEmpty { APPEARANCE_CHOICES },
            // Before a successful read the app's own stored mode is the only
            // value that exists, and the app really does apply it.
            selected = appearance?.value ?: themeMode,
            enabled = !wired || (appearanceWrite != null && saving != appearance?.key),
            onSelect = appearanceWrite ?: if (wired) NO_WRITE else onTheme,
        )
    }

    // Font size: the conversation body's px size, live only while the host
    // advertised whole bounds for it — the stepper has nothing to clamp to
    // otherwise, and sending a value the schema will refuse is not a control.
    val fontSize = hostSettings?.field(GeneralSettings.THEME_NS, GeneralSettings.THEME_FONT_SIZE_FIELD)
    val fontSizeWrite = writerFor(fontSize)
    SettingsRow(title = "Font size", description = "Only affects conversation content") {
        val size = fontSize
        val current = size?.value?.toIntOrNull()
        val min = size?.min
        val max = size?.max
        if (fontSizeWrite != null && size != null && current != null && min != null && max != null) {
            SettingsStepper(
                value = current,
                min = min,
                max = max,
                enabled = saving != size.key,
                onChange = { fontSizeWrite(it.toString()) },
            )
        } else {
            // 14 is the app's own fixed conversation size while no host value
            // exists; it is what `DshType` renders and what the schema defaults to.
            ReadOnlyStepper(size?.value ?: "14", "px")
        }
    }

    // Conversation display: the web's `transcriptView`. The app still folds a
    // finished turn's process, so a host value of `normal` is stored intent this
    // client does not render yet — the row states it truthfully rather than
    // showing the app's effective mode as if it were the setting.
    val transcript = hostSettings?.field(GeneralSettings.CHAT_NS, GeneralSettings.TRANSCRIPT_FIELD)
    val transcriptWrite = writerFor(transcript)
    SettingsRow(
        title = "Conversation display",
        description = "Controls process content in completed turns",
    ) {
        SettingsSelector(
            label = labelFor(transcript, if (transcriptView == "normal") "Normal" else "Compact"),
            choices = transcript?.choices.orEmpty(),
            selected = transcript?.value,
            onSelect = transcriptWrite,
            busy = saving != null && saving == transcript?.key,
        )
    }

    val busy = hostSettings?.field(GeneralSettings.CONVERSATION_NS, GeneralSettings.BUSY_ENTER_FIELD)
    val busyWrite = writerFor(busy)
    SettingsGroup(
        title = "Send behavior while busy",
        description = "What Enter and the Send button do while the agent is running; " +
            "Cmd/Ctrl+Enter uses the other behavior",
    ) {
        SettingsChoiceRow(
            choices = busy?.choices.orEmpty().ifEmpty { BUSY_ENTER_CHOICES },
            selected = busy?.value ?: busyEnter,
            enabled = !wired || (busyWrite != null && saving != busy?.key),
            onSelect = busyWrite ?: if (wired) NO_WRITE else onBusyEnter,
        )
    }
}

/** The web's cube order (figma 501:30015-30017), for a schema that advertised none. */
private val APPEARANCE_CHOICES = listOf(
    SettingsChoice(ThemeMode.LIGHT, "Light"),
    SettingsChoice(ThemeMode.DARK, "Dark"),
    SettingsChoice(ThemeMode.SYSTEM, "System"),
)

/** The web's own two busy-Enter options (`EnterBehaviorRow.tsx`), same fallback. */
private val BUSY_ENTER_CHOICES = listOf(
    SettingsChoice(BusyEnter.QUEUE, "Queue"),
    SettingsChoice(BusyEnter.STEER, "Steer"),
)

/** What a wired-but-not-editable chip row carries; its chips are disabled anyway. */
private val NO_WRITE: (String) -> Unit = {}

// -------------------------------------------------------------------- Models

@Composable
private fun ModelsSection(models: List<ModelOption>, providerOrder: List<String>) {
    val providers = remember(models, providerOrder) {
        // `providerOrder` is built from each entry's *display* name, so keying the
        // sort on the machine id (as this did) matched nothing and the cards fell
        // back to catalog order — the composer's own sheet sorts by name.
        models.groupBy { it.provider }
            .toList()
            .sortedBy { (provider, options) ->
                val name = options.firstOrNull()?.providerName?.ifBlank { provider } ?: provider
                providerOrder.indexOf(name).let { if (it < 0) Int.MAX_VALUE else it }
            }
    }

    SectionNote("Enter your API keys to use models from the following providers.")
    // A remote phone is handed the `memory` settings scope, which the web marks
    // read-only; the notice is the web's own `settings.models.readOnly` copy.
    SectionNote("The settings document is read-only in this deployment.")

    if (providers.isEmpty()) {
        SectionNote("The host has not reported any model providers for this session.")
        return
    }

    providers.forEach { (provider, options) ->
        ProviderCard(
            // The provider's own display name is on every route it advertises.
            name = options.firstOrNull()?.providerName?.ifBlank { provider } ?: provider,
            models = options,
        )
    }
}

@Composable
private fun ProviderCard(name: String, models: List<ModelOption>) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.sm)
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(DshSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = DshType.labelMedium,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (models.size == 1) "1 model" else "${models.size} models",
                style = DshType.micro,
                color = colors.labelTertiary,
            )
        }
        models.forEach { model ->
            Column {
                Text(
                    text = model.name,
                    style = DshType.bodyMedium,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.model,
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------- Plugins

@Composable
private fun PluginsSection() {
    SectionNote("Configure and inspect the plugins installed in this deployment.")
    // The configurable tab's read-only state. No cards render: the client never
    // calls `pluginInventory/list`, so there is no inventory to list here.
    SectionNote("This deployment stores settings read-only.")
}

// ------------------------------------------------------------- Agent presets

@Composable
private fun AgentPresetsSection(
    agentPreset: String,
    options: List<AgentPresetOption>,
    pickerEnabled: Boolean,
    onPreset: (String) -> Unit,
) {
    val colors = DshTheme.colors
    SectionNote(
        "A preset is the plugin composition one session's agent runs — its tools, " +
            "prompt, and capabilities. Duplicate an existing one and make it yours, " +
            "or let the agent draft one for you in Creator mode.",
    )

    // The picker switch is a `settings/update`, so a remote client cannot flip
    // it; it renders the app's real state disabled.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow switching Agent modes", style = DshType.bodyMedium, color = colors.labelPrimary)
                Spacer(Modifier.width(DshSpacing.sm))
                BetaTag()
            }
            Text(
                text = "When enabled, new tasks can choose Standard, PTC, Creator, " +
                    "Minimal, and custom modes. When disabled, all new tasks use the " +
                    "default mode (Standard by default; configurable). Only affects new tasks.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
            )
        }
        Spacer(Modifier.width(DshSpacing.md))
        Switch(checked = pickerEnabled, onCheckedChange = null, enabled = false)
    }

    if (options.isEmpty()) {
        SectionNote("Could not load agent presets.")
        return
    }

    val builtIn = options.filter { isBuiltInPreset(it.id) }
    val custom = options.filterNot { isBuiltInPreset(it.id) }
    // The row the web tags `inUse` = 'New task default': the explicit new-session
    // pick, else the host's own default.
    val defaultId = agentPreset.ifBlank { options.firstOrNull { it.isDefault }?.id ?: "" }

    if (builtIn.isNotEmpty()) {
        GroupLabel("Built-in")
        builtIn.forEach { option -> PresetRow(option, option.id == defaultId, pickerEnabled, onPreset) }
    }
    if (custom.isNotEmpty()) {
        GroupLabel("Custom")
        custom.forEach { option -> PresetRow(option, option.id == defaultId, pickerEnabled, onPreset) }
    }
}

@Composable
private fun PresetRow(
    option: AgentPresetOption,
    isDefault: Boolean,
    pickerEnabled: Boolean,
    onPreset: (String) -> Unit,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentPresetLabel(option.id, option.name),
                    style = DshType.bodyMedium,
                    color = if (option.broken) colors.warn else colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (option.broken) {
                    Spacer(Modifier.width(DshSpacing.sm))
                    Tag("Failed to load", colors.warn)
                }
                if (isDefault && !option.broken) {
                    Spacer(Modifier.width(DshSpacing.sm))
                    // `inUse` / `selectionOffDefault` (`AgentPresetSection.tsx`):
                    // with the picker off there is no new-task pick to be the
                    // default of, so the tag drops the "New task".
                    Tag(if (pickerEnabled) "New task default" else "Default", colors.accent)
                }
            }
            Text(
                text = agentPresetDescription(option.id, option.description) ?: "No description.",
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // "Set as default" is client-local here (the stored new-session pick), so
        // it stays live; the web's own action is a settings write a phone cannot make.
        if (!isDefault && !option.broken) {
            Spacer(Modifier.width(DshSpacing.md))
            if (pickerEnabled) {
                Text(
                    text = "Set as default",
                    style = DshType.bodySmall,
                    color = colors.link,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(DshRadius.sm))
                        .clickableNoRipple { onPreset(option.id) }
                        .padding(DshSpacing.sm),
                )
            } else {
                // The web's `enablePickerToSetDefault`, which it puts on the
                // card's title attribute; a phone has no tooltip, and the pick
                // really is refused (`AgentPresetSection.tsx` disables the card).
                Text(
                    text = "Turn on Agent mode selection to choose a default",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    maxLines = 2,
                )
            }
        }
    }
}

// --------------------------------------------------------- Archived sessions

@Composable
private fun ArchivedSessionsSection(
    sessions: List<SessionItem>,
    workspaces: List<WorkspaceItem>,
    loading: Boolean,
    onUnarchive: ((String) -> Unit)?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val colors = DshTheme.colors

    DshTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Search archived sessions",
        modifier = Modifier.padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
    )

    if (loading) {
        SectionNote("Reading sessions…")
        return
    }
    if (sessions.isEmpty()) {
        SectionNote("No archived sessions.")
        return
    }

    // The web filters on title and owning-workspace title only, and groups the
    // survivors by that workspace, with a synthetic `Ungrouped` bucket.
    val normalized = query.trim().lowercase()
    val rows = remember(sessions, workspaces, normalized) {
        sessions.map { session ->
            val owner = workspaces.firstOrNull { session.id in it.sessionIds }?.title
            session to (owner ?: "Ungrouped")
        }.filter { (session, workspace) ->
            normalized.isEmpty() ||
                session.title.lowercase().contains(normalized) ||
                workspace.lowercase().contains(normalized)
        }
    }

    if (rows.isEmpty()) {
        SectionNote("No matching sessions.")
        return
    }

    val groups = rows.groupBy { it.second }
    groups.forEach { (workspace, group) ->
        GroupLabel(workspace)
        group.forEach { (session, _) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // The web sets no `title` attribute; the full text is
                        // only in the row's accessible name.
                        text = session.title.ifBlank { "Untitled" },
                        style = DshType.bodyMedium,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // `[workspace, relativeTime].join(' · ')`.
                        text = "$workspace · ${relativeTime(session.updatedAt)}",
                        style = DshType.micro,
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                }
                if (onUnarchive != null) {
                    Spacer(Modifier.width(DshSpacing.md))
                    Text(
                        text = "Unarchive",
                        style = DshType.bodySmall,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DshRadius.md))
                            .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.md))
                            .clickableNoRipple { onUnarchive(session.id) }
                            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.sm),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ building

/**
 * A section break and its heading, `internal` rather than private because the
 * About sheet draws the same breaks: the two surfaces sit side by side in the
 * drawer, so a second heading style would read as two different screens.
 */
@Composable
internal fun SectionHeading(title: String) {
    val colors = DshTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = DshSpacing.xl)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderL1))
        Text(
            text = title,
            style = DshType.labelLarge,
            color = colors.labelPrimary,
            modifier = Modifier.padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.lg),
        )
    }
}

/** A section's own prose (the web's `intro` / read-only notices). Shared with About. */
@Composable
internal fun SectionNote(text: String) {
    Text(
        text = text,
        style = DshType.bodySmall,
        color = DshTheme.colors.labelTertiary,
        modifier = Modifier.padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xs),
    )
}

/**
 * A failure note. The text is always somebody else's own wording — the host's
 * refusal message or the transport's failure — because a client paraphrase of a
 * schema or CAS error hides the one detail the reader needs.
 */
@Composable
private fun SectionErrorNote(text: String) {
    Text(
        text = text,
        style = DshType.bodySmall,
        color = DshTheme.colors.error,
        modifier = Modifier.padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.xs),
    )
}

/** A sub-group label inside a section (Built-in / Custom, a workspace title). */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = DshType.micro,
        color = DshTheme.colors.labelTertiary,
        modifier = Modifier.padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.sm),
    )
}

/** Title (and optional description) beside a trailing control. */
@Composable
private fun SettingsRow(
    title: String,
    description: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = DshTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DshType.bodyMedium, color = colors.labelPrimary)
            description?.let {
                Text(it, style = DshType.bodySmall, color = colors.labelTertiary)
            }
        }
        Spacer(Modifier.width(DshSpacing.md))
        trailing()
    }
}

/** Title and description stacked above a full-width control group. */
@Composable
private fun SettingsGroup(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = DshTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md)) {
        Text(title, style = DshType.bodyMedium, color = colors.labelPrimary)
        description?.let {
            Text(it, style = DshType.bodySmall, color = colors.labelTertiary)
        }
        Spacer(Modifier.height(DshSpacing.md))
        content()
    }
}

// ------------------------------------------------------------------ controls

/** The web's selector pill, read-only: the value plus a dimmed chevron. */
@Composable
private fun ReadOnlyPill(value: String) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Row(
        Modifier
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(start = DshSpacing.xl, end = DshSpacing.md, top = DshSpacing.sm, bottom = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = DshType.bodyMedium, color = colors.labelTertiary, maxLines = 1)
        Spacer(Modifier.width(DshSpacing.sm))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The web's `Font size` stepper, disabled: value, dead arrows, unit. */
@Composable
private fun ReadOnlyStepper(value: String, unit: String) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Row(
        Modifier
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = DshType.bodyMedium, color = colors.labelTertiary, maxLines = 1)
        Spacer(Modifier.width(DshSpacing.sm))
        Column {
            Icon(
                imageVector = Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = colors.labelDimmed,
                modifier = Modifier.size(11.dp),
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.labelDimmed,
                modifier = Modifier.size(11.dp),
            )
        }
        Spacer(Modifier.width(DshSpacing.sm))
        Text(unit, style = DshType.micro, color = colors.labelCaption, maxLines = 1)
    }
}

/**
 * The live selector: the value in a pill that opens the field's own option set.
 *
 * The app's picker idiom is `DropdownMenu` (`SessionsDrawer.kt`), and the web
 * uses a Menu here too, so this is the same control with the app's chrome. A
 * null [onSelect] (or [busy]) falls back to the read-only pill the sheet has
 * always drawn, which is what a row the host would refuse must look like.
 */
@Composable
private fun SettingsSelector(
    label: String,
    choices: List<SettingsChoice>,
    selected: String?,
    onSelect: ((String) -> Unit)?,
    busy: Boolean = false,
) {
    // An option set the host did not advertise leaves the Menu nothing to offer,
    // so the pill falls back to the read-only shape rather than opening empty.
    if (onSelect == null || busy || choices.isEmpty()) {
        ReadOnlyPill(if (busy) "Saving…" else label)
        return
    }
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(colors.tip)
                .border(0.5.dp, colors.borderL3, shape)
                .clickableNoRipple { open = true }
                .padding(start = DshSpacing.xl, end = DshSpacing.md, top = DshSpacing.sm, bottom = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = DshType.bodyMedium, color = colors.labelPrimary, maxLines = 1)
            Spacer(Modifier.width(DshSpacing.sm))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            choice.label,
                            style = DshType.bodyMedium,
                            // The web's Menu marks the current selection; the app's
                            // DropdownMenu has no such state, so the accent is the mark.
                            color = if (choice.value == selected) colors.accent else colors.labelPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        // Re-picking the current value would be a no-op write that
                        // still spends the namespace's revision.
                        if (choice.value != selected) onSelect(choice.value)
                    },
                )
            }
        }
    }
}

/** A row of choice chips, in the schema's own order. */
@Composable
private fun SettingsChoiceRow(
    choices: List<SettingsChoice>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.md)) {
        choices.forEach { choice ->
            SettingsChoiceChip(choice.label, choice.value == selected, enabled) { onSelect(choice.value) }
        }
    }
}

/** The web's `Font size` stepper, live: the value between its two arrows. */
@Composable
private fun SettingsStepper(
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Row(
        Modifier
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, if (enabled) colors.borderL3 else colors.borderL1, shape)
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value.toString(),
            style = DshType.bodyMedium,
            color = if (enabled) colors.labelPrimary else colors.labelTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Column {
            // The arrows draw dead at the schema's own bounds, exactly like the
            // web's (`disabled={fontSize >= FONT_SIZE_MAX}`).
            StepperArrow(Icons.Rounded.ExpandLess, "Increase font size", enabled && value < max) { onChange(value + 1) }
            StepperArrow(Icons.Rounded.ExpandMore, "Decrease font size", enabled && value > min) { onChange(value - 1) }
        }
        Spacer(Modifier.width(DshSpacing.sm))
        Text("px", style = DshType.micro, color = colors.labelCaption, maxLines = 1)
    }
}

@Composable
private fun StepperArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (enabled) DshTheme.colors.labelSecondary else DshTheme.colors.labelDimmed,
        modifier = Modifier
            .size(13.dp)
            .clickableNoRipple(enabled = enabled, onClick = onClick),
    )
}

/** The live theme / busy-Enter choice, styled as the app's composer chips. */
@Composable
private fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    val tint = when {
        !enabled -> colors.labelTertiary
        selected -> colors.accent
        else -> colors.labelSecondary
    }
    Box(
        Modifier
            .clip(shape)
            .background(if (selected && enabled) colors.accentTertiary else colors.tip)
            .border(0.5.dp, if (selected && enabled) colors.accent else colors.borderL1, shape)
            .clickableNoRipple(enabled = enabled, onClick = onClick)
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
    ) {
        Text(label, style = DshType.bodyMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun BetaTag() {
    Tag("Beta", DshTheme.colors.accent)
}

@Composable
private fun Tag(text: String, tint: androidx.compose.ui.graphics.Color) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Text(
        text = text,
        style = DshType.micro,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(horizontal = DshSpacing.sm, vertical = 1.dp),
    )
}

// ------------------------------------------------------------------ helpers

/**
 * The web's `displayPermissionPreset` (`presentation.ts`): a known machine value
 * renders under its product label only while the host's own name is that value or
 * the product label — a host that names `read-only` itself keeps its wording.
 * `Unavailable` is the web's own empty-selection copy, for a client that has no
 * value at all (the host's own value now comes from `HostSettings` instead).
 *
 * `internal`, not private: the About sheet names the open session's access mode
 * with the same function, and a second copy would drift from the host's wording.
 */
internal fun permissionDisplay(value: String, options: List<PermissionOption>): String {
    if (value.isBlank()) return "Unavailable"
    val name = options.firstOrNull { it.value == value }?.name ?: value
    val product = PERMISSION_PRESET_LABELS[value]
    return if (product != null && (name == value || name == product)) product else displayPresetName(name)
}

/**
 * The web's `archivedSessions` row time: relative, coarse, and never localized
 * beyond the unit (`now`, `{n}min`, `{n}h`, `{n}d`, `{n}mo`, `{n}y`).
 */
private fun relativeTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - updatedAt).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}min"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)}mo"
        else -> "${minutes / (60 * 24 * 365)}y"
    }
}
