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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The transcript row for a settled `ask_user_question` call.
 *
 * The live card (`QuestionCard`) only exists while the host is waiting for an
 * answer, so without this the answered/cancelled question set left no trace in
 * the transcript beyond a generic tool line. Ports `ask-question-row.tsx`: the
 * collapsed summary is the web's own count/verdict copy and the expanded body
 * is the paired question list, or the IN/OUT card when the result cannot be
 * paired into a transcript.
 */
internal enum class AskRowState { RUNNING, OK, STOPPED, ERROR }

/** Validated question from the call arguments. */
internal data class AskCardQuestion(val id: String, val question: String)

/** One paired question with its visible answer lines. */
internal data class AskAnsweredQuestion(val id: String, val question: String, val answers: List<String>)

/** The card model shared with `AskQuestionCard.tsx`: answered or unanswered. */
internal sealed interface AskTranscript {
    data class Answered(
        val questions: List<AskAnsweredQuestion>,
        val skippedLabel: String,
    ) : AskTranscript

    data class Unanswered(
        val questions: List<AskCardQuestion>,
        val verdict: String,
    ) : AskTranscript
}

/** Row summary, state semantics, and the optional transcript card. */
internal data class AskQuestionRowModel(
    val summary: String,
    val state: AskRowState,
    val transcript: AskTranscript?,
)

/** One result entry after validating the fields the transcript uses. */
private data class AskAnswerEntry(
    val id: String,
    val selected: List<String>,
    val custom: String?,
)

/**
 * Questions from the call JSON; null when pairing with answers would be
 * ambiguous. Mirrors the web's `questionEntries`: a non-empty array whose
 * entries each carry a unique string id and a string question.
 */
private fun questionEntries(argsRaw: String): List<AskCardQuestion>? {
    val parsed = runCatching { JSONObject(argsRaw) }.getOrNull() ?: return null
    val questions = parsed.optJSONArray("questions") ?: return null
    if (questions.length() == 0) return null
    val result = ArrayList<AskCardQuestion>(questions.length())
    val ids = HashSet<String>()
    for (i in 0 until questions.length()) {
        val question = questions.optJSONObject(i) ?: return null
        val id = question.opt("id") as? String ?: return null
        val text = question.opt("question") as? String ?: return null
        if (!ids.add(id)) return null
        result.add(AskCardQuestion(id, text))
    }
    return result
}

/** Answer records from the result JSON; null when the result is malformed. */
private fun answerEntries(text: String): List<AskAnswerEntry>? {
    val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val answers = parsed.optJSONArray("answers") ?: return null
    val result = ArrayList<AskAnswerEntry>(answers.length())
    for (i in 0 until answers.length()) {
        val answer = answers.optJSONObject(i) ?: return null
        val id = answer.opt("id") as? String ?: return null
        val selected = answer.optJSONArray("selected") ?: return null
        val lines = ArrayList<String>(selected.length())
        for (j in 0 until selected.length()) {
            lines.add(selected.opt(j) as? String ?: return null)
        }
        // A JSON null custom is "absent", not a validation failure; org.json
        // hands it back as JSONObject.NULL rather than a String.
        val custom = when (val value = answer.opt("custom")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return null
        }
        result.add(AskAnswerEntry(id, lines, custom))
    }
    return result
}

/**
 * Pair questions with result entries by their echoed stable ids. Null when the
 * two sides disagree on any id or count, which is what keeps a mismatched pair
 * out of the transcript.
 */
private fun pairAnswers(
    questions: List<AskCardQuestion>,
    answers: List<AskAnswerEntry>,
): List<AskAnsweredQuestion>? {
    if (questions.size != answers.size) return null
    val byId = HashMap<String, AskAnswerEntry>(answers.size)
    for (answer in answers) {
        if (byId.put(answer.id, answer) != null) return null
    }
    val paired = ArrayList<AskAnsweredQuestion>(questions.size)
    for (question in questions) {
        val answer = byId[question.id] ?: return null
        paired.add(
            AskAnsweredQuestion(
                id = question.id,
                question = question.question,
                // The web appends `custom` after the chosen labels and treats an
                // empty string as absent, so a free-text answer reads as one more line.
                answers = answer.selected + listOfNotNull(answer.custom?.takeIf { it.isNotEmpty() }),
            ),
        )
    }
    return paired
}

/** Best-effort answered-count summary when strict transcript pairing fails. */
private fun answeredSummary(text: String): String? {
    val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val answers = parsed.optJSONArray("answers") ?: return null
    var answered = 0
    for (i in 0 until answers.length()) {
        val answer = answers.optJSONObject(i) ?: return null
        val selected = answer.optJSONArray("selected")
        val custom = answer.optString("custom")
        if ((selected != null && selected.length() > 0) || custom.isNotEmpty()) answered++
    }
    return "$answered/${answers.length()} answered"
}

/**
 * The verdict code carried by a settled call.
 *
 * The host's structured `errorCode` is authoritative. Older records predate that
 * field, so the flattened result prose — the error message with no code in it —
 * is matched as a fallback; a tool result's text is not a contract.
 */
private fun askErrorCode(result: String): String? = when {
    result.contains("ASK_CANCELLED") || result.contains("the user cancelled ask_user_question") ->
        "ASK_CANCELLED"
    result.contains("ASK_ABORTED") || result.contains("ask_user_question was aborted") ->
        "ASK_ABORTED"
    else -> null
}

/**
 * Derives the row from the raw args and the settled result text.
 *
 * Copy is taken verbatim from the English `ui-conversation` locale:
 * `ask.answered` = "{answered}/{total} answered", `ask.waiting` = "waiting",
 * `ask.cancelled` = "cancelled", `ask.cancelledDetail` = "This question set was
 * cancelled before answers were submitted.", `ask.interrupted` = "interrupted",
 * `ask.interruptedDetail` = "This question set was interrupted before answers
 * were submitted.", `ask.skipped` = "Not answered".
 */
internal fun askQuestionRowModel(
    arguments: String,
    result: String?,
    isError: Boolean,
    errorCode: String?,
): AskQuestionRowModel {
    val questions = questionEntries(arguments)
    val code = errorCode?.takeIf { it.isNotEmpty() }
        ?: if (isError) result?.let(::askErrorCode) else null
    // The two composer verdicts settle the call as specific errors. Cancelled is
    // the user's own dismissal and keeps the ok (green) semantics; an abort is a
    // turn interrupt and keeps the shared stopped (amber) semantics.
    when (code) {
        "ASK_CANCELLED" -> return AskQuestionRowModel(
            summary = "cancelled",
            state = AskRowState.OK,
            transcript = questions?.let {
                AskTranscript.Unanswered(
                    questions = it,
                    verdict = "This question set was cancelled before answers were submitted.",
                )
            },
        )

        "ASK_ABORTED" -> return AskQuestionRowModel(
            summary = "interrupted",
            state = AskRowState.STOPPED,
            transcript = questions?.let {
                AskTranscript.Unanswered(
                    questions = it,
                    verdict = "This question set was interrupted before answers were submitted.",
                )
            },
        )
    }
    if (result == null) {
        return AskQuestionRowModel(summary = "waiting", state = AskRowState.RUNNING, transcript = null)
    }
    val answers = answerEntries(result)
    if (answers != null) {
        val answered = answers.count { it.selected.isNotEmpty() || !it.custom.isNullOrEmpty() }
        return AskQuestionRowModel(
            summary = "$answered/${answers.size} answered",
            state = if (isError) AskRowState.ERROR else AskRowState.OK,
            transcript = questions?.let { pairAnswers(it, answers) }
                ?.let { AskTranscript.Answered(it, "Not answered") },
        )
    }
    return AskQuestionRowModel(
        summary = answeredSummary(result).orEmpty(),
        state = if (isError) AskRowState.ERROR else AskRowState.OK,
        transcript = null,
    )
}

@Composable
internal fun AskQuestionCallRow(entry: ChatEntry.ToolCall, model: AskQuestionRowModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val expandable = model.transcript != null || entry.result != null || entry.arguments.isNotBlank()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple(enabled = expandable) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    expanded -> Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    // A failed call leads with the state dot the way every tool
                    // row does; a stopped (interrupted) call keeps its amber.
                    model.state == AskRowState.ERROR -> StateDot(DotState.ERROR)
                    model.state == AskRowState.STOPPED -> StateDot(DotState.WARNING)
                    else -> Icon(
                        Icons.Rounded.HelpOutline,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = "Ask question",
                style = DshType.rowTitle,
                color = colors.labelSecondary,
                maxLines = 1,
            )
            if (model.summary.isNotBlank()) {
                Spacer(Modifier.width(DshSpacing.md))
                Box(
                    Modifier
                        .size(2.dp)
                        .clip(CircleShape)
                        .background(colors.labelCaption),
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = model.summary,
                    style = DshType.rowSummary,
                    color = if (model.state == AskRowState.ERROR) colors.error else colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            val transcript = model.transcript
            if (transcript != null) {
                AskQuestionTranscriptCard(transcript)
            } else {
                ToolIoCard(
                    sections = buildList {
                        add("IN" to entry.arguments)
                        entry.result?.let { add("OUT" to it) }
                    },
                    isError = entry.isError,
                )
            }
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

/**
 * `AskQuestionCard.tsx`: a bordered, scroll-capped card. Answered questions
 * read question (tertiary) then one line per answer (primary); a question with
 * no answers falls back to `skippedLabel`. Unanswered ones lead with the
 * verdict and a bulleted list of the questions that were never put to a human.
 */
@Composable
private fun AskQuestionTranscriptCard(transcript: AskTranscript) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            // The web card's `margin: 4px 0 4px 4px`, matching the IN/OUT card's
            // left inset so both expanded bodies hang off the same edge.
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.bgBase)
            .border(0.5.dp, colors.borderL1, shape)
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = DshSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xl),
    ) {
        when (transcript) {
            is AskTranscript.Answered -> transcript.questions.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xxs)) {
                    Text(
                        text = item.question,
                        style = DshType.messageBody,
                        color = colors.labelTertiary,
                    )
                    if (item.answers.isEmpty()) {
                        Text(
                            text = transcript.skippedLabel,
                            style = DshType.messageBody,
                            color = colors.labelTertiary,
                        )
                    } else {
                        item.answers.forEach { answer ->
                            Text(
                                text = answer,
                                style = DshType.messageBody,
                                color = colors.labelPrimary,
                            )
                        }
                    }
                }
            }

            is AskTranscript.Unanswered -> {
                Text(
                    text = transcript.verdict,
                    style = DshType.messageBody,
                    color = colors.labelPrimary,
                )
                // `<ul>` keeps its default disc marker with a 20px indent, so the
                // bullet is drawn rather than relying on a list primitive.
                Column(
                    Modifier.padding(start = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(DshSpacing.md),
                ) {
                    transcript.questions.forEach { question ->
                        Row {
                            Text(
                                text = "\u2022",
                                style = DshType.messageBody,
                                color = colors.labelTertiary,
                            )
                            Spacer(Modifier.width(DshSpacing.sm))
                            Text(
                                text = question.question,
                                style = DshType.messageBody,
                                color = colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
