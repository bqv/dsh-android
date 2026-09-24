package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The host's settings document, as far as the General panel reads it.
 *
 * `settings/describe` answers one view per registered namespace
 * (`api/settings-controller/src/index.ts:namespaceView`), and the web decides
 * whether a row may be edited from three facts that only ride that reply:
 *
 *  - `writable`, the provider's own "I accept writes" flag;
 *  - each namespace's `applies` — `restart` means the value is stored but only
 *    applied on a host restart, so offering a control would promise an effect
 *    the user will not see;
 *  - each namespace's `secrets` — a schema-declared `role('secret')` field is
 *    stripped out of `value` before the reply leaves the host, so a panel can
 *    neither show nor write one.
 *
 * The option sets and bounds come off the serialized schema rather than a
 * client-side table: the host owns the vocabulary, and a union the app
 * hardcoded would silently disagree with the host the day it grows a member
 * (`ui-permission-presets/src/client/settings-store.ts` reads the preset enum
 * the same way for the same reason).
 */

/**
 * One selectable value a schema advertises: the machine value written back and
 * the label the row shows.
 */
data class SettingsChoice(val value: String, val label: String)

/**
 * One scalar field of one settings namespace.
 *
 * [revision] is the namespace's CAS token as of the describe read, and it is
 * what `settings/update` takes as `expectedRevision` — a stale one is refused
 * with `settings/conflict`, never applied.
 */
data class SettingsField(
    val ns: String,
    /**
     * The field's own key inside the namespace section. Deliberately not called
     * `field`: inside a property accessor that name is Kotlin's backing-field
     * identifier, so a getter mentioning it silently requires an initializer for
     * the property it lives in — which is how [key] first failed to compile.
     */
    val name: String,
    val revision: Int,
    /** The host's accepted value as text; null when the redacted view omitted it. */
    val value: String?,
    /** Schema-advertised consts, in schema order; empty when the field is not a choice. */
    val choices: List<SettingsChoice> = emptyList(),
    /** Inclusive bounds of a numeric field (the font-size stepper). */
    val min: Int? = null,
    val max: Int? = null,
    /**
     * `applies == "live"`. Strict on the documented vocabulary on purpose: a
     * value this client does not recognise reads as "not now", which degrades
     * to a read-only row rather than a write whose effect is unknown.
     */
    val live: Boolean = false,
    /** A schema-declared `role('secret')` slot: the host withholds its value. */
    val secret: Boolean = false,
) {
    /** `<ns>.<name>`: the key the sheet marks an in-flight row with. */
    val key: String
        get() = "$ns.$name"

    /**
     * Whether this field can carry a control at all. The deployment's own
     * `writable` flag is checked by the caller ([HostSettings.writable]), since
     * it is a fact about the provider rather than the namespace.
     */
    val editable: Boolean
        get() = live && !secret && value != null &&
            (choices.isNotEmpty() || (min != null && max != null))
}

/** The `settings/describe` facts the General panel renders. */
data class HostSettings(
    /** The provider accepts writes; false disables every write control. */
    val writable: Boolean = false,
    /** A local settings document exists for the host to open (unused by the panel). */
    val hasDocument: Boolean = false,
    /** `<ns>.<field>` → field, for the rows this panel knows. */
    val fields: Map<String, SettingsField> = emptyMap(),
) {
    /** The named field, or null when the host did not advertise that namespace. */
    fun field(ns: String, name: String): SettingsField? = fields["$ns.$name"]

    /** Fold one accepted write's namespace view back in, keeping every other row. */
    fun with(field: SettingsField?): HostSettings =
        if (field == null) this else copy(fields = fields + (field.key to field))
}

/** Namespace and field identities of the web's General panel rows. */
object GeneralSettings {
    const val PERMISSION_NS = "permission"
    const val PERMISSION_FIELD = "defaultPreset"
    const val THEME_NS = "ui-theme"
    const val THEME_PREFERENCE_FIELD = "preference"
    const val THEME_FONT_SIZE_FIELD = "fontSize"
    const val CHAT_NS = "ui-chat"
    const val TRANSCRIPT_FIELD = "transcriptView"
    const val CONVERSATION_NS = "ui-conversation"
    const val BUSY_ENTER_FIELD = "busyEnter"
}

/**
 * The web General panel's host-backed rows, in registration order (permission
 * -20, appearance 10, font-size 11, transcript-view 12, composer-enter 20).
 * The Language row (0) is absent: this client has no locale setting.
 */
private val GENERAL_ROWS = listOf(
    GeneralSettings.PERMISSION_NS to GeneralSettings.PERMISSION_FIELD,
    GeneralSettings.THEME_NS to GeneralSettings.THEME_PREFERENCE_FIELD,
    GeneralSettings.THEME_NS to GeneralSettings.THEME_FONT_SIZE_FIELD,
    GeneralSettings.CHAT_NS to GeneralSettings.TRANSCRIPT_FIELD,
    GeneralSettings.CONVERSATION_NS to GeneralSettings.BUSY_ENTER_FIELD,
)

/**
 * Parse one `settings/describe` reply.
 *
 * The reply's failure text is the caller's to hold; a reply that is not the
 * describe envelope at all parses to null so the panel degrades to read-only
 * instead of rendering a guess from a shape it does not know.
 */
fun parseHostSettings(reply: JSONObject?): HostSettings? {
    val namespaces = reply?.arr("namespaces") ?: return null
    val views = (0 until namespaces.length()).mapNotNull { namespaces.optJSONObject(it) }
    val fields = GENERAL_ROWS.mapNotNull { (ns, name) ->
        views.firstOrNull { it.str("ns") == ns }
            ?.let { parseSettingsField(it, name) }
    }
    return HostSettings(
        writable = reply.bool("writable"),
        hasDocument = reply.bool("hasDocument"),
        fields = fields.associateBy { it.key },
    )
}

/**
 * Parse one namespace view's field, resolving its option set and bounds off the
 * serialized schema.
 *
 * The schema is schemastery's `toJSON()` envelope: `{uid, refs}` where every
 * child is a uid into `refs` (`vendor/schemastery/src/index.ts:244-255`), so a
 * union's members and an object's properties are ids, never inline nodes. An
 * envelope this client cannot walk yields a field with no options — the row
 * then has nothing to offer and stays read-only, which is the honest answer for
 * a schema shape the host may have changed.
 */
fun parseSettingsField(view: JSONObject, field: String): SettingsField? {
    val ns = view.str("ns").takeIf { it.isNotBlank() } ?: return null
    val schema = view.obj("schema")
    val node = schemaFieldNode(schema, field)
    val raw = view.obj("value")?.opt(field)
    return SettingsField(
        ns = ns,
        name = field,
        revision = view.int("revision"),
        value = if (raw == null || raw == JSONObject.NULL) null else raw.toString(),
        choices = node?.let { choiceNodes(schema, it) }
            .orEmpty()
            .mapNotNull { member ->
                val value = member.opt("value")
                if (member.str("type") != "const" || value !is String || value.isEmpty()) {
                    null
                } else {
                    SettingsChoice(value, choiceLabel("$ns.$field", value, member.obj("meta").str("description")))
                }
            },
        min = node?.let { numberMeta(it, "min") },
        max = node?.let { numberMeta(it, "max") },
        live = view.str("applies") == "live",
        secret = secretPaths(view).any { it.size == 1 && it[0] == field },
    )
}

/**
 * `settings/update` args for one field change.
 *
 * The patch merges into the namespace's *user* section, so only the changed
 * field rides and the rest of the resolved value stays as stored.
 * `expectedRevision` is the CAS token from the describe read [field] came from:
 * the host answers a stale one with `settings/conflict` rather than silently
 * overwriting an edit made in another client.
 */
fun settingsUpdateArgs(field: SettingsField, value: String): JSONObject {
    val numeric = field.min != null || field.max != null
    val coerced: Any = if (numeric) value.toIntOrNull() ?: value else value
    return JSONObject()
        .put("ns", field.ns)
        .put("patch", JSONObject().put(field.name, coerced))
        .put("expectedRevision", field.revision)
}

/**
 * What a caller should do once one `settings/update` answered. The caller, not
 * this model, performs the wire calls — a conflict can therefore be described
 * without dragging the network into a pure parser.
 */
sealed interface SettingsApply {
    /** The host accepted the value; [field] is the namespace view it answered with. */
    data class Accepted(val field: SettingsField?) : SettingsApply

    /** `settings/conflict`: re-describe the host, then send [request] once more. */
    data class Retry(val request: JSONObject) : SettingsApply

    /** Stop, and show the host's own wording. */
    data class Failed(val message: String) : SettingsApply
}

/**
 * One field change, driven against the host's compare-and-set revision.
 *
 * The host's own contract makes a stale revision "its own outcome rather than
 * an invalid request: the caller must re-read and re-apply"
 * (`api/settings-controller/src/index.ts:rejected`), so a plan earns exactly one
 * re-describe and one resend; a second refusal, a `settings/rejected`, or a
 * re-describe that no longer advertises the field editable is the failure. The
 * failure text is always the host's own message — a client paraphrase over a
 * schema validation error would hide which value the host refused.
 */
class SettingsWritePlan(val ns: String, val name: String, val value: String) {

    /** `<ns>.<name>`: the key the sheet marks the in-flight row with. */
    val key: String
        get() = "$ns.$name"

    /** `settings/update` calls this plan has asked for; one retry is allowed. */
    var attempts: Int = 0
        private set

    /** The `settings/update` args for the next attempt, against [target]'s revision. */
    fun request(target: SettingsField): JSONObject {
        attempts += 1
        return settingsUpdateArgs(target, value)
    }

    /** The write was accepted: fold the answered namespace view back into the panel. */
    fun accepted(view: JSONObject): SettingsApply =
        SettingsApply.Accepted(parseSettingsField(view, name))

    /** `settings/rejected`: unknown namespace, malformed patch, schema, storage. */
    fun rejected(message: String): SettingsApply = SettingsApply.Failed(message)

    /** `settings/conflict`: re-read and resend once, or report the host's message. */
    fun conflict(fresh: HostSettings, message: String): SettingsApply {
        val target = fresh.field(ns, name)
        return if (attempts < ATTEMPTS && fresh.writable && target != null && target.editable) {
            SettingsApply.Retry(request(target))
        } else {
            SettingsApply.Failed(message)
        }
    }

    private companion object {
        /** One first attempt plus the single re-describe retry the contract allows. */
        const val ATTEMPTS = 2
    }
}

// ------------------------------------------------------------------- parsing

/**
 * The schema node for one object member: root → `dict[field]`, following uids
 * through `refs`. Null when the envelope is absent or the field is not
 * described (a namespace whose schema is an empty object — `local-llm` on the
 * live host is one — advertises no members at all).
 */
private fun schemaFieldNode(schema: JSONObject?, field: String): JSONObject? {
    val root = schemaNode(schema, schema?.opt("uid")) ?: return null
    if (root.str("type") != "object") return null
    return schemaNode(schema, root.obj("dict")?.opt(field))
}

/**
 * Resolve one uid, or an inline node this host never emits, into a schema node.
 * A uid whose `refs` entry is missing resolves to null rather than to a
 * half-empty node.
 */
private fun schemaNode(schema: JSONObject?, ref: Any?): JSONObject? {
    if (ref == null || ref == JSONObject.NULL) return null
    (ref as? JSONObject)?.let { return it }
    return schema?.obj("refs")?.optJSONObject(ref.toString())
}

/** A union's members (or a lone const), resolved, in the schema's own order. */
private fun choiceNodes(schema: JSONObject?, node: JSONObject): List<JSONObject> =
    when (node.str("type")) {
        "const" -> listOf(node)
        "union" -> node.arr("list")
            ?.let { list -> (0 until list.length()).mapNotNull { schemaNode(schema, list.opt(it)) } }
            .orEmpty()
        else -> emptyList()
    }

/** A numeric constraint from the node's meta, when the schema declared a whole one. */
private fun numberMeta(node: JSONObject, key: String): Int? =
    (node.obj("meta")?.opt(key) as? Number)
        ?.takeIf { it.toDouble() == it.toInt().toDouble() }
        ?.toInt()

/** Every `role('secret')` path the view reports, as string segments. */
private fun secretPaths(view: JSONObject): List<List<String>> {
    val secrets = view.arr("secrets") ?: return emptyList()
    return (0 until secrets.length()).mapNotNull { index ->
        val path = secrets.optJSONObject(index)?.arr("path") ?: return@mapNotNull null
        (0 until path.length()).map { path.optString(it) }
    }
}

// -------------------------------------------------------------------- labels

/**
 * The web's product labels for the three built-in permission presets
 * (`ui-permission-presets/src/client/presentation.ts`: `PRESET_LABEL_KEYS`).
 * "danger-full-access" is spelled out rather than imported from the view model
 * so this wire-facing table stays independent of UI state.
 */
internal val PERMISSION_PRESET_LABELS: Map<String, String> = mapOf(
    "read-only" to "Read Only",
    "workspace-write" to "Workspace Write",
    "danger-full-access" to "Full access",
)

/** The web's own row copy for the values the live schemas advertise. */
private val CHOICE_LABELS: Map<String, Map<String, String>> = mapOf(
    "${GeneralSettings.PERMISSION_NS}.${GeneralSettings.PERMISSION_FIELD}" to PERMISSION_PRESET_LABELS,
    "${GeneralSettings.THEME_NS}.${GeneralSettings.THEME_PREFERENCE_FIELD}" to mapOf(
        "light" to "Light",
        "dark" to "Dark",
        "system" to "System",
    ),
    "${GeneralSettings.CHAT_NS}.${GeneralSettings.TRANSCRIPT_FIELD}" to mapOf(
        "normal" to "Normal",
        "compact" to "Compact",
    ),
    "${GeneralSettings.CONVERSATION_NS}.${GeneralSettings.BUSY_ENTER_FIELD}" to mapOf(
        "queue" to "Queue",
        "steer" to "Steer",
    ),
)

/**
 * The row label for a schema-advertised value.
 *
 * The web splits this in two: the permission row reads the const's own
 * `description` and runs it through `displayPermissionPreset`, while the other
 * rows ignore the schema and use their locale dictionaries. The live host's
 * consts carry no description at all, so the dictionary is what actually
 * renders; a host that grows one wins, which is the wire-first rule the
 * permission row already follows.
 */
internal fun choiceLabel(key: String, value: String, description: String): String {
    val known = CHOICE_LABELS[key]?.get(value)
    if (description.isBlank()) return known ?: displayPresetName(value)
    // `displayPermissionPreset`'s rule: a host name equal to the machine value
    // (or to the product label) still renders under the product label; any
    // other host wording is kept, title-cased only when it is kebab-case.
    return if (known != null && (description == value || description == known)) {
        known
    } else {
        displayPresetName(description)
    }
}

/** Kebab-case keys become Title Case; any other label is left alone. */
internal fun displayPresetName(name: String): String {
    if (!KEBAB.matches(name)) return name
    return name.split('-').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}

private val KEBAB = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
