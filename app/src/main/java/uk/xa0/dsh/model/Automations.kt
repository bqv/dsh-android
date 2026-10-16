package uk.xa0.dsh.model

import org.json.JSONArray

/**
 * A session a scheduled automation delivers into, as the host now reports it.
 *
 * **The prefix this file used to key on is gone, and so is the plugin that wrote it.**
 * Automations were once a third-party plugin (`@michengai/dsh-automation`, last seen at
 * 0.1.53) that started one throwaway session per run, named `dsh-automation-session-…`.
 * That plugin is not compatible with the host this app talks to and is no longer in the
 * profile; the host's own Schedule subsystem replaced it
 * (`@deepseek-ai/dsh-experimental-schedule-bundle`, pulling `@deepseek-ai/dsh-schedule`,
 * `dsh-client-ui-schedule` and `dsh-time-context`, all 0.2.0-rc.2).
 *
 * The replacement is not a rename. A task is stored against the **original session it
 * was created in** and each due occurrence is delivered into that same session as a
 * follow-up turn, so a run is no longer a session at all: the session is reused, and its
 * id is an ordinary `session-…`. Measured on this host, the live `session/list` held 485
 * sessions and **not one** began `dsh-automation`, while the task store
 * (`~/.dsh/storages/schedule.json`, unit `schedule`) bound all 12 tasks to ordinary
 * session ids. So there is nothing left in an id to recognise, and the old predicate
 * matched nothing.
 *
 * What the host does publish is a task catalog. The Schedule service registers a typert
 * remote, so the running host answers `schedule/catalog` with **no arguments**: every
 * active and ended task, each carrying the `sessionId` it is bound to, its `status`
 * (`"active"` or `"inactive"`) and, when it has fired, a `lastDelivery`. This client
 * cannot read the host's files and must not guess, so that read is the authority here,
 * exactly as it is for the web client: `dsh-client-ui-schedule`'s
 * `selectSessionScheduleFacts` filters the same one catalog snapshot by
 * `record.sessionId === sessionId && record.status === "active"` and marks the row.
 *
 * A session with an active task is still a perfectly ordinary session — it is the one
 * the reader was in when they asked for the schedule, and it keeps its Workspace and its
 * place in the drawer. The task is why its `updatedAt` keeps moving while nothing is
 * being read, which is the fact the landing rule below is built on.
 *
 * Nothing here is hidden or deleted: a session with a task is marked, preferred against
 * when a cold start picks where to land, and otherwise left exactly as it was.
 */

/** The `status` a task that will fire again carries; an ended one is no reason to mark. */
const val AUTOMATION_TASK_ACTIVE: String = "active"

/**
 * The only other `status` the host's schema defines. A row carrying neither this nor
 * [AUTOMATION_TASK_ACTIVE] is one this build cannot read a mark from at all, which is
 * what a renamed `status` field or a new status value looks like from here.
 */
const val AUTOMATION_TASK_INACTIVE: String = "inactive"

/**
 * One task from the `schedule/catalog` answer, reduced to what a client needs.
 *
 * [id] is the host's task identity (`schedule-…`), [sessionId] the session it delivers
 * into, and [active] whether it will fire again. The prompt, the timing rule and the
 * title are deliberately not carried: nothing on this client draws them yet, and copying
 * a field the wire owns invites it to be treated as client state.
 */
data class AutomationTask(
    val id: String,
    val sessionId: String,
    val active: Boolean,
)

/**
 * What one `schedule/catalog` answer held, and what could not be read from it.
 *
 * [tasks] is exactly the list [automationTasksFrom] returns, so a caller that only wants
 * the marks still reads that and gets them unchanged. The counts exist for the one caller
 * that has to *say* why a mark is missing: [rows] is the answer's length, [droppedRows]
 * counts rows that named no task id or no owning session and were skipped, and
 * [unknownStatusRows] counts tasks that were kept but carried no `status` this build knows
 * ([AUTOMATION_TASK_ACTIVE] or [AUTOMATION_TASK_INACTIVE]). A task in that last state is
 * never `active`, so it can never mark its session — a renamed `status` field would put
 * every row there. Neither count repairs anything: which marks appear is unchanged.
 */
data class AutomationCatalog(
    val tasks: List<AutomationTask>,
    val rows: Int,
    val droppedRows: Int,
    val unknownStatusRows: Int,
)

/**
 * Reads one `schedule/catalog` answer, reporting the tasks and what reading them lost.
 *
 * The parse is the one the marks have always used, and the counts are diagnostics only: a
 * row with no task id or no owning session is skipped rather than repaired, because a mark
 * on the wrong row is worse than a missing one and the host's schema requires both fields
 * anyway. A null answer is the "no catalog" case and loses nothing — it is a whole answer
 * with no rows, not a fault.
 */
fun readAutomationCatalog(catalog: JSONArray?): AutomationCatalog {
    if (catalog == null) return AutomationCatalog(emptyList(), 0, 0, 0)
    val tasks = ArrayList<AutomationTask>(catalog.length())
    var dropped = 0
    var unknownStatus = 0
    for (i in 0 until catalog.length()) {
        val row = catalog.optJSONObject(i)
        if (row == null) {
            dropped++
            continue
        }
        val id = row.optString("id")
        val sessionId = row.optString("sessionId")
        if (id.isEmpty() || sessionId.isEmpty()) {
            dropped++
            continue
        }
        val status = row.optString("status")
        if (status != AUTOMATION_TASK_ACTIVE && status != AUTOMATION_TASK_INACTIVE) unknownStatus++
        tasks += AutomationTask(
            id = id,
            sessionId = sessionId,
            active = status == AUTOMATION_TASK_ACTIVE,
        )
    }
    return AutomationCatalog(tasks, catalog.length(), dropped, unknownStatus)
}

/**
 * Reads the `schedule/catalog` answer.
 *
 * The answer is a bare JSON *array* — hence `DshClient.rpcRaw` rather than `rpc`, which
 * reads `value` as an object and would quietly hand back an empty one. A row with no
 * task id or no owning session is skipped rather than repaired: a mark on the wrong row
 * is worse than a missing one, and the host's schema requires both fields anyway.
 *
 * This is [readAutomationCatalog]'s tasks and nothing else, kept as the name the marks
 * have always been read through.
 */
fun automationTasksFrom(catalog: JSONArray?): List<AutomationTask> =
    readAutomationCatalog(catalog).tasks

/**
 * Decides which `schedule/catalog` read losses earn a log line, so logcat is not flooded.
 *
 * The read is issued from every roster pull, so a host that has renamed the method, the
 * answer or a field would otherwise draw a line per pull, for as long as the app runs.
 * [accept] reports the first loss of an episode and then stays quiet. A whole read resets
 * it — the task array, every row carrying the fields a mark needs, and **including one
 * that legitimately holds no tasks at all** — so a loss after a healthy read is reported
 * again. That is one boolean and no timer, which is the whole mechanism; a fresh instance
 * has reported nothing, which is the correct state at startup.
 */
class ScheduleSignalLatch {
    private var reported = false

    /**
     * Returns [loss] the first time it is offered in this episode, else null. A null
     * [loss] means the read was whole: it clears the latch, so the next loss reports.
     */
    fun accept(loss: String?): String? {
        if (loss == null) {
            reported = false
            return null
        }
        if (reported) return null
        reported = true
        return loss
    }
}

/**
 * How many *active* tasks each session hosts, keyed by session id.
 *
 * Only `active` counts, matching the web: an ended one-shot task leaves the session
 * alone, which is what stops a session being marked forever after a single reminder.
 */
fun activeTaskCounts(tasks: List<AutomationTask>): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (task in tasks) {
        if (!task.active) continue
        out[task.sessionId] = (out[task.sessionId] ?: 0) + 1
    }
    return out
}

/**
 * The clock mark's spoken label.
 *
 * The web puts `"{count} scheduled tasks"` in an aria label on its mark
 * (`dsh-client-ui-schedule`, `mark.aria`). A phone has no hover card to reveal a
 * count, so this is the whole of what the mark says to a screen reader, and it is
 * singular for one. Empty for zero, which is never drawn.
 */
fun scheduledTaskMarkLabel(count: Int): String = when (count) {
    0 -> ""
    1 -> "1 scheduled task"
    else -> "$count scheduled tasks"
}

/**
 * Whether a session that has just stopped is worth an idle notification.
 *
 * Two kinds of session are not, and for the same reason: nobody is waiting on them.
 * A subagent's turn is part of its parent's, whose result arrives in the parent's
 * transcript anyway; a session hosting an active schedule is driven by deliveries that
 * happen unattended, and the hourly kind would otherwise post twenty-four "Done"
 * notifications a day for work the reader set going and then walked away from.
 *
 * The rule is now coarser than it was, and deliberately so. Under the prefix plugin an
 * entire session was a run; under the Schedule subsystem a host session may also hold
 * the reader's own turns, and nothing on the wire yet says whether the turn that just
 * finished was a delivery or the reader's. Muting the whole session is the only rule the
 * evidence supports — the alternative would buzz on every delivery — and the row still
 * takes its green "done" dot, which is the quiet half of the same signal.
 *
 * Both kinds still speak up when something is *stuck* — a question or an approval stalls
 * them where nothing else can show it — and an error or a terminal bell is an event
 * rather than a status. This rules out the status, not the session.
 */
fun warrantsIdleAlert(subagent: Boolean, scheduledTaskCount: Int): Boolean =
    !subagent && scheduledTaskCount == 0
