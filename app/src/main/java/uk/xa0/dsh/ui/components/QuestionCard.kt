package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.PendingQuestionSet
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** One answer as the host wants it: `(question id, selected labels, custom text)`. */
typealias QuestionAnswer = Triple<String, List<String>, String?>

/**
 * The question card — the web's `QuestionComposer`, docked above the composer.
 *
 * This is the one place the client *must* answer: the host's `ask_user_question`
 * tool blocks until the waterfall resolves, so doing nothing is not a neutral
 * outcome — the host fails the call with `NO_PROVIDER` and the agent loses the
 * interaction. "Skip" therefore delegates deliberately rather than silently.
 *
 * A plan review (`intent.kind === 'plan-review'`) is the same card with the plan
 * shown as the detail and the approve label marked, exactly as the web presents
 * it; the answer encoding is identical either way.
 *
 * Divergence from the web: a multi-question request renders every question in one
 * scrollable card instead of one-at-a-time with prev/next. On a phone the whole
 * set fits, and answering all of it in one pass is fewer taps.
 */
@Composable
fun QuestionCard(
    set: PendingQuestionSet,
    onSubmit: (List<QuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(20.dp)
    // questionId -> chosen labels; a single-select question keeps at most one.
    val selected = remember(set.eventId) {
        mutableStateOf<Map<String, List<String>>>(emptyMap())
    }
    val custom = remember(set.eventId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    val planReview = set.questions.any { it.isPlanReview }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.inputMajor)
            .border(1.dp, colors.accent, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.accentTertiary)
                .padding(horizontal = DshSpacing.xl, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (planReview) Icons.Rounded.PushPin else Icons.Rounded.HelpOutline,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = when {
                    planReview -> "Plan review"
                    // The host stamps the asking session's own nature, so a subagent's
                    // question says so instead of crediting the parent.
                    set.subjectIsSubagent && set.questions.size == 1 -> "Your subagent is asking"
                    set.subjectIsSubagent -> "Your subagent is asking · ${set.questions.size} questions"
                    set.questions.size == 1 -> "Your agent is asking"
                    else -> "Your agent is asking · ${set.questions.size} questions"
                },
                style = DshType.meta,
                color = colors.accent,
            )
        }

        Column(
            Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(DshSpacing.xl),
        ) {
            set.questions.forEachIndexed { index, question ->
                if (index > 0) Spacer(Modifier.height(DshSpacing.xl))
                if (question.header != null) {
                    Text(
                        text = question.header,
                        style = DshType.micro,
                        color = colors.labelTertiary,
                    )
                    Spacer(Modifier.height(DshSpacing.xxs))
                }
                Text(
                    text = question.question,
                    style = DshType.titleSmall,
                    color = colors.labelPrimary,
                )
                if (question.detail != null) {
                    Spacer(Modifier.height(DshSpacing.md))
                    Text(
                        // A plan can be long; the card clips rather than pushing
                        // the composer off the screen, and the scroll above
                        // still reaches all of it.
                        text = question.detail,
                        style = DshType.bodySmall,
                        color = colors.labelSecondary,
                        maxLines = 24,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val chosen = selected.value[question.id].orEmpty()
                if (question.options.isNotEmpty()) {
                    Spacer(Modifier.height(DshSpacing.lg))
                    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.md)) {
                        question.options.forEach { option ->
                            val isChosen = option.label in chosen
                            ChoiceRow(
                                label = option.label,
                                description = option.description,
                                chosen = isChosen,
                                recommended = option.label == question.approveLabel,
                                onClick = {
                                    selected.value = selected.value + (question.id to
                                        if (question.multiSelect) {
                                            if (isChosen) chosen - option.label else chosen + option.label
                                        } else {
                                            listOf(option.label)
                                        })
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(DshSpacing.lg))
                DshTextField(
                    value = custom.value[question.id].orEmpty(),
                    onValueChange = { text -> custom.value = custom.value + (question.id to text) },
                    placeholder = if (question.options.isEmpty()) "Type your answer" else "Or type your own answer",
                    label = null,
                    singleLine = false,
                    minHeight = 44.dp,
                )
            }

            Spacer(Modifier.height(DshSpacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
                QuestionButton("Skip", primary = false, onClick = onSkip)
                QuestionButton("Send answers", primary = true) {
                    onSubmit(
                        set.questions.map { question ->
                            // Both halves ride along whenever both are present. The host's
                            // answer shape documents `custom` beside `selected` only for a
                            // multi-select question, and the web clears one as soon as you
                            // touch the other — but the wire takes the pair, and a typed note
                            // next to a pick is more useful than making the user choose.
                            QuestionAnswer(
                                question.id,
                                selected.value[question.id].orEmpty(),
                                custom.value[question.id]?.takeIf { it.isNotBlank() },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** One option row: a radio/checkbox affordance with the label and its context. */
@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    chosen: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (chosen) colors.accentTertiary else colors.tip)
            .border(0.5.dp, if (chosen) colors.accent else colors.borderL2, shape)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(DshRadius.pill))
                .background(if (chosen) colors.accent else colors.bgBase)
                .border(1.dp, if (chosen) colors.accent else colors.borderL4, RoundedCornerShape(DshRadius.pill)),
        )
        Spacer(Modifier.width(DshSpacing.lg))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
            )
            if (!description.isNullOrBlank()) {
                Text(description, style = DshType.micro, color = colors.labelTertiary)
            }
        }
        if (recommended) {
            Spacer(Modifier.width(DshSpacing.sm))
            Text("Recommended", style = DshType.micro, color = colors.accent)
        }
    }
}

@Composable
private fun QuestionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Box(
        Modifier
            .clip(shape)
            .background(if (primary) colors.sendFill else colors.tip)
            .border(0.5.dp, if (primary) colors.sendFill else colors.borderL3, shape)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
    ) {
        Text(
            text = label,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (primary) colors.sendGlyph else colors.labelPrimary,
        )
    }
}
