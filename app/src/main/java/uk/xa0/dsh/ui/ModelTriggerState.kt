package uk.xa0.dsh.ui

import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.model.ModelChoice
import uk.xa0.dsh.model.ModelChoiceSource
import uk.xa0.dsh.model.ModelPendency

/**
 * What the composer's model trigger says, and what is not settled about it.
 *
 * The trigger is the app's one always-visible statement about the model, so it is
 * built in one place, from the host's own choice, and never from a request the host
 * has not answered.
 */
data class ModelTriggerState(
    /** `V41-Flash · High`, or `provider/model` when the catalog no longer lists it. */
    val label: String,
    /**
     * A short word beside the label for a route that is not simply in force:
     * `next turn`, `when created`, `applying…` or `unavailable`. Null when the
     * advertised route is exactly what the host will run.
     */
    val marker: String? = null,
    /** The full sentence, for the trigger's accessibility label. */
    val description: String? = null,
    /** The marker is a fault (the route cannot run) rather than a wait. */
    val alert: Boolean = false,
)

/**
 * Derives the trigger's copy.
 *
 * Returns null when there is nothing to advertise at all — no session selection and
 * no catalog default, which is a real state on a host whose model-selection plugin
 * has not answered. An empty label used to render an empty pill there.
 */
fun modelTriggerState(
    choice: ModelChoice,
    option: ModelOption?,
    effort: String?,
): ModelTriggerState? {
    val label = modelLabel(choice, option, effort) ?: return null
    val marker = when {
        choice.unavailable -> "unavailable"
        choice.pendency == ModelPendency.IN_FLIGHT -> "applying\u2026"
        choice.pendency == ModelPendency.NEXT_TURN -> "next turn"
        choice.pendency == ModelPendency.ON_CREATE -> "when created"
        else -> null
    }
    return ModelTriggerState(
        label = label,
        marker = marker,
        description = modelTriggerDescription(choice, label),
        alert = choice.unavailable,
    )
}

/**
 * The name the UI uses for a route, from the catalog when it is still listed and
 * from the host's own `provider/model` when it is not.
 *
 * The fallback matters: a route that has gone away used to leave the chip on the
 * last catalog entry it had matched, which is a name for a *different* model. The
 * host's identifier is ugly but it is the thing the session is actually holding.
 */
internal fun modelLabel(choice: ModelChoice, option: ModelOption?, effort: String?): String? {
    val ref = choice.ref ?: return null
    val label = if (option != null) {
        listOfNotNull(shortModelName(option.name), effortLabel(option, effort)).joinToString(" · ")
    } else {
        listOfNotNull(ref.id, effort).joinToString(" · ")
    }
    return label.ifEmpty { null }
}

/**
 * The one sentence that resolves "which model is actually running" for anyone who
 * cannot see the marker's colour or read three words in a 132dp pill.
 */
private fun modelTriggerDescription(choice: ModelChoice, label: String): String {
    if (choice.ref == null) return label
    if (choice.unavailable) {
        return "$label is not available from this host. Select another model to continue."
    }
    return when (choice.pendency) {
        ModelPendency.NEXT_TURN ->
            "Model change pending. The next turn will use $label; " +
                "the last turn ran ${choice.lastUsed?.id ?: "another model"}."
        ModelPendency.ON_CREATE ->
            "Model change pending. $label will be selected in the session this screen creates."
        ModelPendency.IN_FLIGHT -> "Selecting $label\u2026"
        ModelPendency.NONE -> when (choice.source) {
            // Said out loud, because "the model this session will run" and "the
            // model this session chose" are different facts and the chip shows both
            // the same way.
            ModelChoiceSource.HOST_DEFAULT -> "$label, this host's default model."
            else -> "Select model, current $label"
        }
    }
}

/**
 * One line above the model list saying what is in force and what is not.
 *
 * Null when the checked row already says everything — an ordinary, in-force session
 * selection. Every other state gets words, because the point of this screen is to
 * make "what will run" and "what is only planned" distinguishable.
 */
fun modelSheetStatus(
    choice: ModelChoice,
    option: ModelOption?,
    effort: String?,
    sessionOpen: Boolean,
): String? {
    val ref = choice.ref ?: return null
    val label = modelLabel(choice, option, effort) ?: ref.id
    if (choice.unavailable) {
        return "${ref.id} is not available from this host. Select another model to continue."
    }
    return when (choice.pendency) {
        ModelPendency.IN_FLIGHT -> "Selecting $label\u2026"
        ModelPendency.NEXT_TURN ->
            "Pending: the next turn will use $label. " +
                "The last turn ran ${choice.lastUsed?.id ?: "another model"}."
        ModelPendency.ON_CREATE ->
            "Pending: $label will be selected in the session this screen creates."
        ModelPendency.NONE -> when (choice.source) {
            ModelChoiceSource.HOST_DEFAULT -> if (sessionOpen) {
                "This session has no model of its own \u2014 the next turn will use this host's default."
            } else {
                "The session this screen creates will use this host's default."
            }
            else -> null
        }
    }
}

/**
 * Trims a leading family token so the model trigger fits beside the mode chip on a
 * phone: `DeepSeek-V41-Flash` renders as `DeepSeek-Flash`. The model sheet still
 * lists every model under its full name.
 */
internal fun shortModelName(name: String?): String? {
    if (name == null) return null
    // Elide the *version*, not the name: dropping the leading segment (as this
    // first did) kept the version and threw away the model's identity.
    val parts = name.split('-')
    return if (parts.size >= 3) "${parts.first()}-${parts.last()}" else name
}

/** The active reasoning effort, shown beside the model as the web trigger does. */
internal fun effortLabel(model: ModelOption?, effort: String?): String? {
    if (effort.isNullOrEmpty()) return null
    return model?.efforts?.firstOrNull { it.id == effort }?.name ?: effort
}
