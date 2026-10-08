package uk.xa0.dsh

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.xa0.dsh.data.BusyEnter
import uk.xa0.dsh.data.DshConfig
import uk.xa0.dsh.data.DraftStore
import uk.xa0.dsh.data.ThemeMode
import uk.xa0.dsh.model.AccountBalance
import uk.xa0.dsh.diag.ScrollDiag
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.JobsMarker
import uk.xa0.dsh.model.JobObservation
import uk.xa0.dsh.model.JobOutput
import uk.xa0.dsh.model.JobsWire
import uk.xa0.dsh.model.JobTail
import uk.xa0.dsh.model.LiveAttempt
import uk.xa0.dsh.model.MessageAttachment
import uk.xa0.dsh.model.ModelChoice
import uk.xa0.dsh.model.ModelRef
import uk.xa0.dsh.model.ModelSelectionState
import uk.xa0.dsh.model.PendingSessionTarget
import uk.xa0.dsh.model.parseHostModelSelection
import uk.xa0.dsh.model.parseModelRef
import uk.xa0.dsh.model.SessionIntentPlan
import uk.xa0.dsh.model.subagentTreeIds
import uk.xa0.dsh.model.RunningBook
import uk.xa0.dsh.model.durableRunningOf
import uk.xa0.dsh.model.providerRank
import uk.xa0.dsh.model.withRunning
import uk.xa0.dsh.model.warrantsIdleAlert
import uk.xa0.dsh.model.FilePreview
import uk.xa0.dsh.model.SessionHeader
import uk.xa0.dsh.model.SessionSearch
import uk.xa0.dsh.model.sessionRowTitle
import uk.xa0.dsh.model.SettingsApply
import uk.xa0.dsh.model.SettingsWritePlan
import uk.xa0.dsh.model.SessionSearchHit
import uk.xa0.dsh.model.HostSettings
import uk.xa0.dsh.model.SessionStats
import uk.xa0.dsh.model.SessionTarget
import uk.xa0.dsh.model.SessionTargetCandidate
import uk.xa0.dsh.model.SessionTargetPlan
import uk.xa0.dsh.model.SessionTargets
import uk.xa0.dsh.model.SUBAGENT_ATTACHMENT_INVALID
import uk.xa0.dsh.model.SUBAGENT_ATTACHMENT_REFUSAL
import uk.xa0.dsh.model.SUBAGENT_FILE_UNSUPPORTED
import uk.xa0.dsh.model.SubagentCatalog
import uk.xa0.dsh.model.SubagentCatalogEntry
import uk.xa0.dsh.model.SubagentTarget
import uk.xa0.dsh.model.TodoItem
import uk.xa0.dsh.model.PreviewFormat
import uk.xa0.dsh.model.WorkspaceFileLoader
import uk.xa0.dsh.model.previewFormatOf
import uk.xa0.dsh.model.TranscriptReducer
import uk.xa0.dsh.model.arr
import uk.xa0.dsh.model.bool
import uk.xa0.dsh.model.hasFileContentPart
import uk.xa0.dsh.model.int
import uk.xa0.dsh.model.long
import uk.xa0.dsh.model.obj
import uk.xa0.dsh.model.parseSessionSearchResults
import uk.xa0.dsh.model.parseSessionStats
import uk.xa0.dsh.model.parseHostSettings
import uk.xa0.dsh.model.permissionCommandMode
import uk.xa0.dsh.model.parseSubagentCatalogProjection
import uk.xa0.dsh.model.str
import uk.xa0.dsh.model.subagentInterruptArgs
import uk.xa0.dsh.model.subagentPromptRequest
import uk.xa0.dsh.model.subagentTargetOf
import uk.xa0.dsh.net.BalanceFailure
import uk.xa0.dsh.net.DeepSeekAccount
import uk.xa0.dsh.net.DshAuthException
import uk.xa0.dsh.net.DshRpcException
import uk.xa0.dsh.net.multipartBytes
import uk.xa0.dsh.net.boundaryOf
import uk.xa0.dsh.net.DshUnreachableException
import uk.xa0.dsh.net.MuxState
import uk.xa0.dsh.net.JobClient
import uk.xa0.dsh.net.StreamEvent
import uk.xa0.dsh.net.TerminalClient
import uk.xa0.dsh.term.HostShellIssue
import uk.xa0.dsh.term.HostShellProgress
import uk.xa0.dsh.term.HostShellRoot
import uk.xa0.dsh.term.HostShellStep
import uk.xa0.dsh.term.HOST_SHELL_PRESET
import uk.xa0.dsh.term.SeatOption
import uk.xa0.dsh.term.TerminalAttachmentSession
import uk.xa0.dsh.term.TerminalEmulator
import uk.xa0.dsh.term.TerminalEnvironmentInfo
import uk.xa0.dsh.term.TerminalFrameAction
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.TerminalInputBudget
import uk.xa0.dsh.term.TerminalIssue
import uk.xa0.dsh.term.TerminalProtocolException
import uk.xa0.dsh.term.TerminalSeat
import uk.xa0.dsh.term.TerminalState
import uk.xa0.dsh.term.hostShellCreateRequest
import uk.xa0.dsh.term.hostShellIssueFact
import uk.xa0.dsh.term.hostShellRoot
import uk.xa0.dsh.ui.hostShellOwnerOf
import uk.xa0.dsh.term.hostShellSessionName
import uk.xa0.dsh.term.terminalIssueFact
import uk.xa0.dsh.term.terminalIssueOf
import uk.xa0.dsh.term.terminalReapList
import uk.xa0.dsh.term.terminalSeats
import uk.xa0.dsh.term.terminalToAdopt
import uk.xa0.dsh.term.withHostShellSession
import java.util.UUID
import kotlin.math.roundToInt

enum class AppPhase { LOADING, SETUP, READY }

/** One row of the `@` menu: a file, a directory, or another session. */
data class ReferenceCandidate(
    val kind: String,
    val name: String,
    val description: String?,
    val mention: String,
)

/**
 * One row of the host's `commands/list` catalogue.
 *
 * The descriptor carries no display label or section — those stay client-side —
 * so this keeps only what the wire owns: the name, the description, and the
 * optional `input` (its hint here) that marks a command as taking input.
 */
data class HostCommand(
    val name: String,
    val description: String,
    val takesInput: Boolean,
    val inputHint: String?,
)

data class SessionItem(
    val id: String,
    val title: String,
    val cwd: String?,
    val updatedAt: Long,
    /**
     * Whether this session's agent is working now.
     *
     * Derived, never sampled: it is `RunningBook.running(id)` and nothing else, folded
     * in by `withRunning` at the two places a row is built or repainted. Nothing may
     * OR a live frame into it at a display site — that is what let the drawer, the
     * header chip and the lineage sheet each answer the same question differently.
     */
    val running: Boolean,
    val isSubagent: Boolean,
    /** A provisional session with nothing in it yet; the host sends `title: null`. */
    val blank: Boolean = false,
    /** Set on subagent sessions, which nest under their parent in the tree. */
    val parentSessionId: String? = null,
    /** The real subagent title, from the parent's `subagentCatalog` projection. */
    val subagentLabel: String? = null,
    /** `one-shot` | `continuable`; required by the subagent address form. */
    val subagentMode: String? = null,
)

/**
 * One registered Workspace (a directory the host groups sessions under), with its
 * manual session order. Sourced from the `workspace/follow` stream rather than
 * derived from `cwd`, because a session's membership and order are host state.
 */
data class WorkspaceItem(
    val id: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

/**
 * One directory row of a browsed level.
 *
 * `path` is always the host's absolute path: clients never join segments
 * themselves, which is what keeps a directory whose name contains the platform
 * separator addressable.
 */
data class DirectoryEntry(
    val name: String,
    val path: String,
    val hidden: Boolean,
)

/**
 * One directory level as `directoryPicker/list` reports it.
 *
 * [home] is what roots the breadcrumb: the chain above it is dropped for display
 * so a deep path does not push the current directory off the row.
 */
data class DirectoryLevel(
    val path: String,
    val home: String,
    val crumbs: List<DirectoryEntry>,
    val entries: List<DirectoryEntry>,
    val truncated: Boolean,
)

/** One adapter-owned reasoning effort for a model route. */
data class EffortOption(val id: String, val name: String)

/**
 * What the usage panel knows about the account balance.
 *
 * [NoKey] is a state rather than an absent value because it has its own wording on
 * screen — "add a key to see this" is advice, and an empty card is not.
 */
sealed interface BalanceState {
    data object Idle : BalanceState
    data object Loading : BalanceState
    data object NoKey : BalanceState
    data class Ready(val account: AccountBalance) : BalanceState
    data class Failed(val message: String) : BalanceState
}

data class ModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    /** Reasoning efforts this exact route accepts; empty when it has none. */
    val efforts: List<EffortOption> = emptyList(),
    val defaultEffort: String? = null,
)

/**
 * Whether [models] offers [ref]'s route **and** the effort it names.
 *
 * The host refuses an effort a route does not advertise with the same
 * `session/model-unavailable` it uses for a missing model (probed), so an effort
 * that is not in the list is not runnable either. A route with no efforts at all
 * accepts a null effort only.
 */
fun modelRouteIsRunnable(models: List<ModelOption>, ref: ModelRef): Boolean {
    val option = models.firstOrNull { it.provider == ref.provider && it.model == ref.model }
        ?: return false
    val effort = ref.reasoningEffort ?: return true
    return option.efforts.any { it.id == effort }
}

/** One host-advertised permission preset (`permissionPresets/catalog`). */
data class PermissionOption(
    val value: String,
    val name: String,
    val description: String?,
)

/**
 * One host-advertised agent preset (`agentPresets/list`). A preset is the plugin
 * composition a session's agent runs — its tools, prompt, and capabilities; see
 * the web `settings.agentPreset` copy.
 */
data class AgentPresetOption(
    val id: String,
    val name: String,
    val description: String?,
    /** The preset a session with no explicit pick is composed from. */
    val isDefault: Boolean,
    /** Discovery reported the preset unusable; selecting it is refused by the host. */
    val broken: Boolean,
)

/** Full access is the one preset the host gates behind an explicit acknowledgement. */
const val FULL_ACCESS_PRESET = "danger-full-access"

/** Token split shown in the context dialog (`contextBreakdown` projection). */
data class ContextBreakdown(val system: Int, val tools: Int, val messages: Int)

/**
 * The current goal, from the `goal` projection. `phase` is the host's own
 * lifecycle value; `complete` renders nothing at all.
 *
 * `revision` is the host's CAS revision and advances on every mutation
 * (create/edit/pause/resume), so it is *not* a round counter. The round budget
 * is `roundsStarted`, which the projection carries beside the goal snapshot
 * rather than inside it.
 */
data class GoalState(
    val id: String,
    val revision: Long,
    val objective: String,
    val phase: String,
    val maxRounds: Int?,
    val roundsStarted: Int = 0,
) {
    val visible: Boolean get() = phase != "complete" && objective.isNotBlank()
}

/**
 * One background job the host is running (or ran) for a session — a `JobView` off
 * the `job/list` stream.
 *
 * The roster used to arrive on the same control stream as the queue, as a `jobs`
 * block per session. The running 0.2.0-rc.2 host publishes no such block (probed:
 * its control baseline carries `projections` and nothing else), which is why jobs
 * had vanished from every session. They now come from `job/list` — one stream per
 * session — and a job that starts, stops or fails is still a push, not a poll.
 * See `model/Jobs.kt` for the wire rules and `docs/JOBS.md` for the probes.
 */
data class JobItem(
    val id: String,
    val kind: String,
    val label: String,
    /** Owning session; absent for an unowned job, which every caller can see. */
    val owner: String? = null,
    val status: String,
    /** The producer's live progress line, cleared at settlement. */
    val progress: String? = null,
    val detail: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** The output ring's coordinates, which decide whether a row can be expanded. */
    val output: JobOutput = JobOutput(),
) {
    val running: Boolean get() = status == "running" || status == "stopping"
}

/** One file being uploaded for the next prompt. */
data class PendingAttachment(
    val id: String,
    val name: String,
    val bytes: Int,
    val receiptId: String? = null,
    val error: String? = null,
    val mediaType: String? = null,
    /**
     * Base64 payload, kept only for images: the host takes image bytes inline in
     * the prompt, so there is nothing to upload and nothing to re-read later.
     */
    val data: String? = null,
) {
    val isImage: Boolean get() = mediaType.orEmpty() in IMAGE_MEDIA_TYPES
}

/**
 * The host promotes exactly these image types to durable references when they
 * arrive inline in a prompt; anything else has to travel as an uploaded file.
 */
private val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * How long the sidebar's Refresh spin stays up at minimum. A pull against a
 * local host returns in a few milliseconds, and a flash that fast reads as the
 * button doing nothing — which is exactly what it was reported as.
 */
private const val REFRESH_MIN_SPIN_MS = 700L

/**
 * How many lines one workspace-file page asks for. The host caps a page at its
 * own `maxLines` (5000) and reports `eof`, so this is a comfortable first screen
 * for a source file while keeping a huge log from crossing the wire whole.
 */
private const val FILE_PREVIEW_LINES = 2000

/**
 * The ceiling on an image preview, in bytes — **the host's**, not a preference.
 *
 * `workspaceFiles/readBytes` refuses a request for more than 2 MiB outright
 * (`workspace-file/too-large`), and it measures the *requested* length rather than
 * the file: asking for 8 MiB for a 300 KB PNG is refused just the same. Measured
 * against the running host, so the number here is the cap it enforces, and the
 * window it is asked for is exactly this. A file longer than the window comes back
 * with `eof` false, which is what "too large to preview" means here.
 */
private const val MAX_PREVIEW_IMAGE_BYTES = 2 * 1024 * 1024

/**
 * The draft key for the composer that has no session yet.
 *
 * The new-session hero takes typing like any other composer and what is typed there is
 * as easy to lose, but there is no session id to file it under. It cannot collide with
 * one: session ids are `session-…` or `dsh-automation-session-…`.
 */
const val PENDING_DRAFT_KEY = "pending"

/**
 * The web's pause between the last keystroke and a `session/search` request
 * (`WorkspaceBrowser.tsx`). The host scans every visible session's messages for
 * one query, so firing per keystroke would be a scan per character.
 */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * The pause before a `subagentCatalog` projection rebuild that a frame asked for.
 *
 * Deliberately the same trailing-edge shape and 500ms as `refreshSessionsSoon`:
 * membership frames arrive in bursts (a parent spawning its children one after
 * another), and one read of the catalog answers all of them. The web's own
 * debounce is 50ms (`manager.ts:802-815`) and sits under a *push* stream that
 * names each addition; here the trigger is a whole-roster pull, so the local
 * idiom is the honest one.
 */
private const val CATALOG_DEBOUNCE_MS = 500L

/**
 * How long after the last live frame the host's own bookkeeping is trusted over
 * a fold, for the *composer* only.
 *
 * A roster read sampled a beat before the host flushed the open session's own stop
 * frame must not blink its Stop button back to Send mid-turn. Rows do not need this
 * grace any more — [RunningBook] orders a frame against the pull's cut instead of
 * guessing from a clock — so this guards `UiState.running` and nothing else.
 */
private const val LIVE_QUIET_MS = 1500L

/**
 * The host's refusal when an idempotent adopt names a directory its stored header
 * does not carry (`ApiSessionCwdConflict` in the host's `session-controller`).
 * The only expected outcome of adopting from a roster that has gone stale.
 */
private const val SESSION_CONFLICT = "session/conflict"

/** The one intermediate step of staging an attachment, before it is on the UI. */
private data class StagedAttachment(val data: String?, val receiptId: String?, val bytes: Int)

/** One attachment block carried by a queued message, for its chip. */
data class QueueAttachment(
    val kind: String,
    val name: String,
    val bytes: Int,
    /**
     * Base64 bytes of a locally staged picture. A host row never has them (it
     * carries a durable `attachmentId` instead); a local submission echo does,
     * because there is nothing to read back yet.
     */
    val data: String? = null,
)

/**
 * One still-pending inbox occurrence, as the host projects it (`inbox` /
 * `session/control` queue frames).
 *
 * [preview] is the host client's own flattening: every non-attachment block
 * becomes its text (or `[type]`), whitespace collapsed, capped at 200 chars —
 * that is what the web row shows. [text] is non-null only when *every* block is
 * text, which is precisely when the web allows editing it inline.
 */
data class QueuedMessage(
    val id: String,
    val placement: String,
    val preview: String,
    val text: String?,
    val attachments: List<QueueAttachment>,
    /**
     * The `session/prompt` request id the host echoed onto this row. It is the
     * only identity shared with a local submission echo, so it is what retires
     * one: the web's `admitted` set is built exactly this way.
     */
    val rpcId: String? = null,
    /**
     * A client-side submission echo, not yet a host row. It has no host item id
     * ([id] is the prompt request id), so its actions are disabled.
     */
    val pending: Boolean = false,
)

data class PendingApproval(
    val eventId: String,
    val toolName: String,
    val reason: String?,
    /** Which session raised it; null when the host sent no session id. */
    val sessionId: String? = null,
)

/** One selectable answer the host offered. */
data class QuestionOption(val label: String, val description: String?)

/**
 * One question from a `user-questions/request` waterfall.
 *
 * The host's `ask_user_question` tool blocks the agent until this is answered,
 * so the card is not a nicety: with nobody answering, the host rejects the call
 * with `NO_PROVIDER` and the agent loses the interaction entirely.
 */
data class PendingQuestion(
    val id: String,
    val header: String?,
    val question: String,
    val detail: String?,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
    /** `plan-review` when the question is a plan awaiting approval. */
    val intent: String?,
    /** The label that approves, for a `plan-review` intent. */
    val approveLabel: String?,
) {
    val isPlanReview: Boolean get() = intent == "plan-review"
}

/** A whole request: one or more questions that are answered and submitted together. */
data class PendingQuestionSet(
    val eventId: String,
    val sessionId: String?,
    val questions: List<PendingQuestion>,
    /** Whether the asking session is a subagent, so the card can say "subagent". */
    val subjectIsSubagent: Boolean = false,
)

/**
 * A submit's text, handed back to the composer because it was never delivered.
 *
 * The composer owns the draft and clears it on the tap, so a failed
 * materialisation (or a failed prompt) would otherwise lose what the user typed.
 * [nonce] distinguishes two failures carrying the same sentence, so the composer's
 * `LaunchedEffect` re-runs for the second one instead of treating it as unchanged.
 */
data class DraftRestore(val text: String, val nonce: Long)

data class UiState(
    val phase: AppPhase = AppPhase.LOADING,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    /**
     * The banner's failure is an expired/refused session rather than a failed
     * action, so it offers a way out ("Sign in again") instead of only Dismiss.
     * A dismiss-only auth banner over a screen where every action fails is
     * exactly the dead end the user reported.
     */
    val errorNeedsSignIn: Boolean = false,
    val sessions: List<SessionItem> = emptyList(),
    val sessionsLoading: Boolean = false,
    val workspaces: List<WorkspaceItem> = emptyList(),
    val archivedSessionIds: Set<String> = emptySet(),
    /**
     * Host content-search hits for the drawer's query (`session/search`), already
     * in the host's own order — the drawer joins them with its local name matches.
     * Empty on a deployment whose search index is off, which is a permanent fact
     * there rather than a failure the reader needs told.
     */
    val sessionSearchHits: List<SessionSearchHit> = emptyList(),
    val sessionSearchLoading: Boolean = false,
    /** Set only for a *transient* content-search failure; see [searchSessionContent]. */
    val sessionSearchError: String? = null,
    val sessionSearchHasMore: Boolean = false,
    val currentSessionId: String? = null,
    /**
     * The new-session seat with **no session behind it**: a recorded target.
     *
     * Distinct from `currentSessionId == null`, which also covers a cold start with
     * nothing to reopen and the instant while sessions swap. When this is set the
     * hero is showing a session that has deliberately not been created yet —
     * `session/create` is deferred to the seat's first real use (see
     * [uk.xa0.dsh.model.SessionTargets.intent] / [materializePending]). Null means
     * the hero is not asking for a new session at all.
     */
    val pendingSession: PendingSessionTarget? = null,
    /**
     * Text handed back to the composer after a submit that never reached a session,
     * so a send made from the pending seat is not swallowed by a failed
     * `session/create`. [DraftRestore.nonce] makes two identical failures both
     * land, which a bare String could not.
     */
    val draftRestore: DraftRestore? = null,
    /**
     * Whether the session on screen is working — what the composer's Stop button reads.
     *
     * A different fact from [SessionItem.running]'s row dot: this one also carries a
     * send's optimistic echo, which no roster read can know about yet. Its roster half
     * is still taken from the open row, so the two cannot contradict each other.
     */
    val running: Boolean = false,
    /**
     * The journal has closed this session's own turn, whatever the agent registry
     * still reports — see [uk.xa0.dsh.model.TranscriptReducer.ownTurnClosed]. Set
     * from [DshViewModel.publishTranscript]; read through [ownTurnInFlight].
     */
    val ownTurnClosed: Boolean = false,
    /**
     * `parentAvailable` for the open addressed child, from its parent's catalog.
     * Null for every ordinary session and until that read lands — the composer
     * gate treats unknown as available on purpose, so the composer can never
     * flicker into a read-only frame it would have to take back.
     */
    val subagentParentAvailable: Boolean? = null,
    /**
     * The subagent catalogs this client holds, keyed by the parent they describe.
     * Each is the host's own direct-child projection for that parent: identity only —
     * the durable rows, their modes and labels. It carries no activity, and no reader
     * may infer any; a child's running state lives in `RunningBook` alone.
     */
    val subagentCatalogs: Map<String, SubagentCatalog> = emptyMap(),
    val models: List<ModelOption> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    /**
     * The model trigger's inputs: the host's `modelSelection` projection, the
     * catalog's global default, the pick recorded on the new-session screen, and the
     * pick still out with the host.
     *
     * These are the *only* writable model state there is. The three properties the
     * UI reads — [modelChoice], [selectedModel], [selectedEffort] — are derived from
     * them below, so there is no field for a second writer to set and no way for the
     * chip to disagree with the state it is supposed to describe. That is deliberate:
     * this area has already had four separate bugs of exactly that shape.
     */
    val modelSelection: ModelSelectionState = ModelSelectionState(),
    val permissionOptions: List<PermissionOption> = emptyList(),
    /** Current session's access mode, from the `permissions` projection. */
    val currentPermission: String = "",
    /** Plan mode is host-folded: `pending ? !active : active`. */
    val planActive: Boolean = false,
    /** Context-window pressure for the current session. */
    val contextPercent: Int = 0,
    val contextTokens: Int = 0,
    val contextWindow: Int = 0,
    val contextBreakdown: ContextBreakdown? = null,
    val goal: GoalState? = null,
    /**
     * The open session's folded `sessionStats` / `tokenUsage` projections, which
     * the composer dock renders as the web's two statistic pills.
     */
    val sessionStats: SessionStats? = null,
    /**
     * The account's balance, as last read. Null until asked for: this is an outbound
     * call to DeepSeek with the user's own key, so it happens when the panel is opened
     * rather than on every launch.
     */
    val balance: BalanceState = BalanceState.Idle,
    /**
     * The current session's dock rows, from the host control stream plus any
     * local submission echo the host has not admitted yet. Host `queued` and
     * `steering` placements both render (`steering` is already on its way into
     * the turn, and the dock gives it the send glyph); a `pending` row is a
     * local echo still in flight.
     */
    val queue: List<QueuedMessage> = emptyList(),
    /** A queue mutation is in flight, so per-row actions are disabled. */
    val queueBusy: String? = null,
    /** Background jobs the host reports for the open session. */
    val jobs: List<JobItem> = emptyList(),
    /**
     * Live output for whichever job is expanded — in the jobs sheet or in a
     * chat-log entry. Null when nothing is expanded, so a panel never paints
     * another row's bytes.
     */
    val jobOutput: JobObservation? = null,
    /**
     * A job finished in the **open** session since its list was last opened — the
     * jobs seat's green dot. Purely client-side, like the session rows'
     * finished-unseen marker, and per session: the unseen sessions are held in a
     * set and only the open one's membership is published here.
     */
    val jobsFinishedUnseen: Boolean = false,
    val attachments: List<PendingAttachment> = emptyList(),
    /**
     * Sessions that finished running while not selected — the web client's green
     * "done" reminder dot. Purely client-side state, cleared by opening the row.
     */
    val completedSessionIds: Set<String> = emptySet(),
    val themeMode: String = ThemeMode.SYSTEM,
    /** What send does while running: `queue` (web default) or `steer`. */
    val busyEnter: String = BusyEnter.QUEUE,
    /** Sidebar Group mode: Workspaces (`true`) or one flat list; persisted. */
    val drawerGroupByWorkspace: Boolean = true,
    /** Sidebar Order mode: last updated (`true`) or manual order; persisted. */
    val drawerOrderByUpdated: Boolean = false,
    /** Sidebar Archived filter; persisted so it survives a restart like the other two. */
    val drawerShowArchived: Boolean = false,
    /**
     * Sidebar Workspace sections the user collapsed, keyed by section key (a
     * Workspace's host id, or the `__flat__`/`__ungrouped__` sentinels); persisted
     * so a cold start restores the drawer's shape instead of expanding everything.
     */
    val drawerCollapsedSections: Set<String> = emptySet(),
    /**
     * Root key (a Workspace id, or a bare cwd) → the archived Session that root's
     * unconfined "Host shell" terminal lives in; persisted, because an archived session
     * cannot be browsed to and the app would otherwise build a fresh one on every visit.
     * See [uk.xa0.dsh.term.HostShellRoot].
     */
    val hostShellSessions: Map<String, String> = emptyMap(),
    /** The new-session default the app passes to `session/create` (a stored setting). */
    val agentPreset: String = "",
    /** Roster the host offers for a blank session (`agentPresets/list`). */
    val agentPresetOptions: List<AgentPresetOption> = emptyList(),
    /**
     * The preset the current session actually runs, from the `agentPreset`
     * projection. Distinct from [agentPreset]: a per-session pick must not
     * silently rewrite the stored new-session default.
     */
    val currentAgentPreset: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val socketConnected: Boolean = false,
    /**
     * The mux's real four-state value, carried beside [socketConnected] so every
     * existing caller that only wants OPEN/not-OPEN keeps working while the
     * conversation can still tell CONNECTING from CLOSED — collapsing the two
     * into one Boolean is what left a re-dialling socket with no on-screen
     * wording at all.
     */
    val muxState: MuxState = MuxState.IDLE,
    val approval: PendingApproval? = null,
    /** An agent is waiting on an answer; the app must answer or the call fails. */
    val questions: PendingQuestionSet? = null,
    /**
     * Which blocking interaction each session holds, keyed by session id
     * (`AttentionCenter.pendingBySession`). Per session rather than the single
     * card above because the drawer paints every row at once.
     */
    val pendingInteractions: Map<String, PendingKind> = emptyMap(),

    /** The host's `settings/describe` answer, folded into the General panel's five rows. */
    val hostSettings: HostSettings? = null,
    val hostSettingsLoading: Boolean = false,
    /** The read's own failure wording, or the reason there was no read. */
    val hostSettingsError: String? = null,
    /** `<ns>.<field>` of the row with a `settings/update` in flight. */
    val settingsSaving: String? = null,
    /** The host's own wording for the last refused write. */
    val settingsWriteError: String? = null,
) {
    /**
     * The route the model trigger advertises, and how the host came to it.
     *
     * Derived, never stored: whatever [modelSelection] says is what the chip says.
     * That is what makes a second writer impossible rather than merely absent — there
     * is no field to assign. An empty catalog means "not loaded", so nothing is
     * condemned as unavailable on the strength of a failed `session/modelCatalog`.
     */
    val modelChoice: ModelChoice
        get() = modelSelection.choice(
            sessionOpen = currentSessionId != null,
            isKnown = if (models.isEmpty()) null else { ref -> modelRouteIsRunnable(models, ref) },
        )

    /**
     * The catalog entry for [modelChoice], when the host still lists the route.
     * Null while the route is unknown or has gone away — the chip then falls back
     * to naming the route from `modelChoice.ref` rather than to a stale entry.
     */
    val selectedModel: ModelOption?
        get() = modelChoice.ref?.let { ref ->
            models.firstOrNull { it.provider == ref.provider && it.model == ref.model }
        }

    /**
     * The active reasoning effort: the route's own id, which is what the sheet's
     * effort chips compare against, falling back to the catalog's default for it.
     */
    val selectedEffort: String?
        get() = modelChoice.ref?.reasoningEffort?.takeIf { it.isNotEmpty() }
            ?: selectedModel?.defaultEffort

    /**
     * Whether this session has its **own** work in flight. The messaging gate for
     * the transcript's turn-status row.
     *
     * Inputs are [running] — the host's own liveness for this session as this
     * client last heard it, quiet-window hold included — and [ownTurnClosed]. The
     * journal wins in the one direction that matters for messaging: a session the
     * host still reports running while only a descendant works must not impersonate
     * an active turn, because the header's `+N running` chip is where descendant
     * activity is reported. Journal *silence* wins nothing, so a live turn whose
     * boundary has scrolled out of the loaded window keeps the host's answer
     * instead of losing its row.
     *
     * Deliberately NOT the gate for Stop: interrupt authority is the host's, so the
     * composer reads [running] directly. A Stop that cannot interrupt is worse than
     * a row that over-reports.
     */
    val ownTurnInFlight: Boolean get() = running && !ownTurnClosed

    /**
     * What the conversation's connection pill says, or null when there is
     * nothing honest to say.
     *
     * The shell's model is three states (`ConnectionState` in
     * `packages/client/connection/src/client/connection.ts`): `connecting` while
     * its recovery loop runs, `connected`, and `disconnected` — which is emitted
     * only when the *browser* reports no network. The retry backoff happens after
     * the state has already become `connecting`, so a socket that dropped is
     * `connection.connecting` = 'Reconnecting' for the whole episode, and
     * 'Disconnected' (`connection.error`) is the no-network state this client has
     * no signal for. That makes CLOSED — the backoff wait before the supervisor
     * redials — a 'Reconnecting' too, not the web's offline word.
     *
     * IDLE claims nothing: it is the mux before an attempt has an outcome, which
     * the web also renders as no indicator at all.
     */
    val connectionLabel: String?
        get() = when (muxState) {
            MuxState.CONNECTING, MuxState.CLOSED -> "Reconnecting"
            MuxState.OPEN, MuxState.IDLE -> null
        }
}

/**
 * Owns the session the user is looking at and translates DSH's journal into UI
 * state. Everything the app can do to the host happens through here, so the
 * Compose layer stays free of protocol knowledge.
 */
@OptIn(FlowPreview::class)
class DshViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DshApplication
    private val client = app.client
    private val configStore = app.configStore

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // The transcript is published on its own flow and throttled, because a busy
    // turn can emit hundreds of token deltas per second.
    private val reducer = TranscriptReducer()
    private val transcriptTick = MutableStateFlow(0L)
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()
    private val _live = MutableStateFlow<LiveAttempt?>(null)
    val live: StateFlow<LiveAttempt?> = _live.asStateFlow()
    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos.asStateFlow()
    private val _header = MutableStateFlow<SessionHeader?>(null)
    val header: StateFlow<SessionHeader?> = _header.asStateFlow()
    /** Turns the host has closed; only these fold behind a process summary. */
    private val _endedTurns = MutableStateFlow<Set<Int>>(emptySet())
    val endedTurns: StateFlow<Set<Int>> = _endedTurns.asStateFlow()

    /**
     * PTC dispatch links (sub-call → the call that dispatched it), read at
     * composition time rather than published: the links only ever change together
     * with [entries], which is what the transcript recomposes on, so a flow of its
     * own would be a second, redundant invalidation for every event.
     */
    fun toolParents(): Map<String, String> = reducer.toolParents()
    /** Seq of each completed turn's closing answer, which carries the action row. */
    private val _closingSeqs = MutableStateFlow<Set<Int>>(emptySet())
    val closingSeqs: StateFlow<Set<Int>> = _closingSeqs.asStateFlow()
    /** Elapsed ms per turn, for the "Ran for …" pill. */
    private val _turnDurations = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val turnDurations: StateFlow<Map<Int, Long>> = _turnDurations.asStateFlow()
    /** Start of the newest turn, for the live "Deep diving…" elapsed clock. */
    private val _turnStartedAt = MutableStateFlow<Long?>(null)
    val turnStartedAt: StateFlow<Long?> = _turnStartedAt.asStateFlow()

    private var followJob: Job? = null

    /** The one in-flight `settings/describe`; the Settings sheet is its only reader. */
    private var settingsJob: Job? = null
    private var controlJob: Job? = null
    private var workspacesJob: Job? = null
    private var muxWired = false
    private var previousRunning: Set<String> = emptySet()

    /** Failed connect attempts since the last success, for [scheduleReconnect]. */
    private var connectRetries = 0

    /**
     * One silent re-login per auth episode.
     *
     * Every auth failure — unary RPC, stream error, mux handshake, connect —
     * lands in [onAuthFailure]. The first one tries the stored credentials
     * silently; if that refusal came back again, the next one must not try
     * again, or a wrong password would become a login loop. Cleared whenever
     * the user connects explicitly ([connect]) or a heal succeeds.
     */
    private var authHealSpent = false

    /** True while the silent re-login is in flight, so failures do not pile up. */
    private var authHealInFlight = false

    /** When the last silent re-login succeeded, for the re-heal cooldown. */
    private var lastAuthHealAt = 0L

    /**
     * A session the shell explicitly asked for (a notification deep link).
     *
     * It outranks `attemptConnect`'s "most recent session with content"
     * auto-open: the two race, and whichever landed last used to win — so an
     * explicit open could be replaced by the auto-open and the app came up on
     * an unrelated (often empty) session.
     */
    private var explicitSession: String? = null

    /**
     * Monotone tag for [UiState.draftRestore]. The composer restores on change, and
     * two identical failed sends must both land — a bare String would compare equal
     * and the second restore would be ignored.
     */
    private var draftRestoreNonce = 0L

    /** True while a resume re-sync is running, so two resumes cannot stack. */
    private var resumeResyncInFlight = false

    /**
     * Held so [onCleared] can unregister exactly this hook: the tracker is
     * process-scoped and would otherwise keep a destroyed view model alive.
     */
    /** The one client that talks to DeepSeek itself rather than to the host. */
    private val deepseekAccount = DeepSeekAccount()

    private val foregroundHook: () -> Unit = { onAppResumed() }

    /** Same idea for the mux handshake hook on [DshClient]. */
    private val streamAuthHook: () -> Unit = { onAuthFailure("event socket", null) }

    private var historyJob: Job? = null

    /**
     * The parent-catalog rebuilds behind both the composer's read-only gate and the
     * labels a lineage row is named with. Kept apart from [historyJob]
     * because opening a child must not cancel its own history pager (or vice
     * versa).
     *
     * One request per parent, like the web's `catalogInflight`
     * (`manager.ts:355-366`): a second ask while one is in flight is not a second
     * call but a mark, and the settle path answers it with one trailing pull.
     */
    private val subagentCatalogs = java.util.concurrent.ConcurrentHashMap<String, SubagentCatalog>()
    private val subagentCatalogLock = Any()
    /** Parents with a read in flight, so single-flight survives the dispatch gap. */
    /** Parents a fetch was asked for while it was already in flight (`:418`). */
    /** Keys of the debounced pulls, so closing a disclosure can drop one. */
    private val subagentCatalogDebounce = HashMap<String, Job>()
    /**
     * The single authority on which sessions are running.
     *
     * Every row's `running` flag is this object's answer and nothing else — see
     * [RunningBook] for why a roster cut and a live frame can be ordered against one
     * another rather than guessed at, and for what each of them actually proves.
     */
    private val runningBook = RunningBook()
    /**
     * Parents whose catalog a disclosure is currently showing, the app's
     * `openCatalogs` (`manager.ts:438`). Membership under one of these is the
     * change a reader would actually see, so only these are re-read on a frame.
     */
    private val subagentCatalogOpen = HashSet<String>()
    /**
     * child id → durable parent id, from the last roster pull.
     *
     * The app has no `session/added` push: its roster arrives whole, so a child
     * that is new *since the last pull* is the only membership signal it gets —
     * the stand-in for the web's `handleSessionAdded` (`:708-725`).
     */
    private var rosterSubagents: Map<String, String?> = emptyMap()

    /** True while an older history page is in flight, so the list can show it. */
    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    /**
     * The directory browser's current level, or null before its first listing.
     *
     * Kept as a flow of its own rather than folded into [UiState] because only the
     * browser reads it, and a listing failure must not take over the session
     * error banner.
     */
    private val _directoryLevel = MutableStateFlow<DirectoryLevel?>(null)
    val directoryLevel: StateFlow<DirectoryLevel?> = _directoryLevel.asStateFlow()
    private val _directoryLoading = MutableStateFlow(false)
    val directoryLoading: StateFlow<Boolean> = _directoryLoading.asStateFlow()
    private val _directoryError = MutableStateFlow<String?>(null)
    val directoryError: StateFlow<String?> = _directoryError.asStateFlow()
    /** A create or adopt is in flight, so the browser's own verbs are disabled. */
    private val _directoryBusy = MutableStateFlow(false)
    val directoryBusy: StateFlow<Boolean> = _directoryBusy.asStateFlow()
    /** Bumped on every listing so a superseded scan cannot land after a newer one. */
    private var directoryGeneration = 0

    // --------------------------------------------------------------- terminal

    /**
     * The session terminal panel.
     *
     * One at a time on purpose: a phone draws one grid, and the host is what owns
     * the terminals — a second one is one tap away through the picker above the
     * grid, not a second pane beside it.
     */
    private val _terminal = MutableStateFlow(TerminalUiState())
    val terminal: StateFlow<TerminalUiState> = _terminal.asStateFlow()

    private val terminalClient by lazy { TerminalClient(client) }

    /**
     * The host's `job` namespace.
     *
     * The roster is a stream per session rather than the `session/control` block it
     * used to be, so this is a client like [terminalClient] and not a call site in
     * the control handler.
     */
    private val jobClient by lazy { JobClient(client) }

    /**
     * The live screen, mutated in place rather than published as state.
     *
     * Both the mutation (the `terminal/follow` collector, on the main dispatcher)
     * and the read (the panel's Canvas, on the main thread) happen on the same
     * thread, which is what makes reading it without a lock correct. The
     * unsynchronised emulator says the same thing from its side.
     */
    private var terminalEmulator: TerminalEmulator? = null
    /**
     * This attachment's screen state: the ordering gate, the resize gate, the
     * attachment that owns input and the retry budget. A fresh one per
     * user-initiated attach; [TerminalAttachmentSession.restart] rotates the
     * attachment id underneath it without losing the panel's measured grid.
     */
    private var terminalSession: TerminalAttachmentSession? = null
    private var terminalId: String? = null
    /**
     * The Session id terminal RPCs address for the seat currently shown.
     *
     * Not the same thing as the *displayed* session: the host shell seat lives in the
     * workspace's own archived Session, so `terminal/follow`, `write`, `resize`,
     * `close` and `list` must all address that one while the panel still displays the
     * session the user has open. Set by [attachTerminal] from the resolved seat.
     */
    private var terminalAgentId: String? = null
    private var terminalInput = TerminalInputBudget(64 * 1024)
    private var terminalJob: Job? = null
    /** Tail of the input chain; see [enqueueTerminalWrite]. */
    private var terminalWriteChain: Job? = null

    /** The screen for the panel's Canvas, or null before the first snapshot. */
    fun terminalScreen(): TerminalEmulator? = terminalEmulator

    /**
     * Entered when the Terminal tab is shown.
     *
     * Idempotent for a tab that is already open, and a *different* session starts
     * over: the terminal identity and the attachment are both scoped to the session
     * whose agent id they were created with, so reusing either across a switch would
     * address another session's shell.
     */
    fun enterTerminal(sessionId: String?) {
        if (sessionId == null) {
            leaveTerminal()
            _terminal.value = TerminalUiState()
            return
        }
        val current = _terminal.value
        val live = current.sessionId == sessionId &&
            current.phase != TerminalPhase.IDLE &&
            current.phase != TerminalPhase.CLOSED &&
            current.phase != TerminalPhase.FAILED
        if (live) return
        startTerminal(sessionId, current.seat, reuse = true)
    }

    /**
     * Left the tab.
     *
     * Detaches only. The host's process is independent of the attachment by design
     * (`BrowserTerminal.follow` never terminates it), so dropping the stream here is
     * what "leaving must not close the terminal" means on this side.
     */
    fun leaveTerminal() {
        terminalJob?.cancel()
        terminalJob = null
        terminalSession = null
        terminalId = null
        terminalAgentId = null
        publishTerminal(phase = TerminalPhase.IDLE, writable = false, error = null, issue = null, limit = null)
    }

    /** Re-attaches for a fresh screen after a failure or a stream that ended. */
    fun retryTerminal() {
        val sessionId = _terminal.value.sessionId ?: return
        startTerminal(sessionId, _terminal.value.seat, reuse = true)
    }

    /** Explicitly allocates a second terminal in the seat the panel is showing. */
    fun newTerminal() {
        val sessionId = _terminal.value.sessionId ?: return
        startTerminal(sessionId, _terminal.value.seat, reuse = false)
    }

    /**
     * Switches the panel between its two seats.
     *
     * Selecting the seat that is already showing is a no-op only while it is live:
     * after a failure the same tap is the retry, which is how a reader gets out of a
     * refused host shell without leaving the tab. The other seat's shell is *not*
     * closed — it stays on the host, and coming back adopts it again through
     * `terminal/list`.
     */
    fun selectSeat(seat: TerminalSeat) {
        val sessionId = _terminal.value.sessionId ?: return
        val current = _terminal.value
        val live = current.seat == seat &&
            current.phase != TerminalPhase.IDLE &&
            current.phase != TerminalPhase.CLOSED &&
            current.phase != TerminalPhase.FAILED
        if (live) return
        startTerminal(sessionId, seat, reuse = true)
    }

    /** Switches the panel to another terminal the seat already retains. */
    fun selectTerminal(id: String) {
        val agentId = terminalAgentId ?: return
        if (id == terminalId) return
        val info = _terminal.value.terminals.firstOrNull { it.id == id } ?: return
        terminalJob?.cancel()
        terminalSession = null
        terminalId = null
        publishTerminal(
            phase = TerminalPhase.CONNECTING, active = info, writable = false,
            error = null, issue = null, limit = null,
        )
        terminalJob = viewModelScope.launch {
            try {
                val environment = _terminal.value.environment ?: terminalClient.environment(agentId)
                attachTerminal(agentId, info, environment)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportTerminalFailure(error)
            }
        }
    }

    /**
     * Kills the active terminal's process.
     *
     * The only thing in this feature that ends a shell. A detach — closing the tab,
     * losing the socket, backgrounding the app — deliberately does not.
     */
    fun closeActiveTerminal() {
        val agentId = terminalAgentId ?: return
        val id = terminalId ?: return
        terminalJob?.cancel()
        terminalJob = null
        terminalSession = null
        terminalId = null
        val remaining = _terminal.value.terminals.filterNot { it.id == id }
        publishTerminal(phase = TerminalPhase.CLOSING, writable = false, error = null, issue = null)
        viewModelScope.launch {
            try {
                terminalClient.close(agentId, id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportTerminalFailure(error)
                return@launch
            }
            terminalEmulator = null
            publishTerminal(
                phase = TerminalPhase.CLOSED, active = null, writable = false,
                terminals = remaining, error = null, issue = null, limit = null,
            )
        }
    }

    /**
     * Types into the terminal.
     *
     * Every keystroke and every key-row button arrives here. A write that the host
     * refuses as read-only is not an error the reader needs a banner for: it means
     * another attachment owns input, and the panel says exactly that.
     */
    fun terminalWrite(data: String) {
        if (data.isEmpty()) return
        val agentId = terminalAgentId ?: return
        val id = terminalId ?: return
        val session = terminalSession ?: return
        // Never drop input silently.
        //
        // Every one of these guards used to `return` and say nothing, so a panel
        // that could not write looked exactly like a shell that was ignoring the
        // keyboard - which is exactly how "Ctrl-C and Ctrl-D do nothing, but
        // everything else works" presents. A refusal is a fact the user has to be
        // able to see, and the log line is how a device can be diagnosed without
        // guessing which link broke.
        if (!_terminal.value.writable) {
            Log.w(
                TAG,
                "terminalWrite dropped: writable=false phase=${_terminal.value.phase} " +
                    "attachment=${session.attachmentId} controller=" +
                    "${_terminal.value.active?.controllerId} state=${_terminal.value.active?.state}",
            )
            publishTerminal(
                issue = TerminalIssue.READ_ONLY,
                error = terminalIssueFact(TerminalIssue.READ_ONLY),
            )
            return
        }
        val attachment = session.attachmentId
        val bytes = data.toByteArray(Charsets.UTF_8).size
        // The host's own budget, counted the way the web client counts it: queued
        // bytes, not just this request's, because a write is asynchronous. The
        // instance is captured alongside the offer so the release cannot land on
        // the replacement a re-attach installs mid-flight, which would under-count
        // the new budget and let the panel queue past the host's limit.
        val budget = terminalInput
        if (!budget.offer(bytes)) {
            publishTerminal(
                issue = TerminalIssue.INPUT_FULL,
                error = terminalIssueFact(TerminalIssue.INPUT_FULL),
            )
            return
        }
        enqueueTerminalWrite {
            try {
                if (terminalSession?.attachmentId == attachment) {
                    Log.d(TAG, "terminalWrite ${bytes}B to $id via $attachment")
                    terminalClient.write(agentId, id, attachment, data)
                } else {
                    Log.w(
                        TAG,
                        "terminalWrite dropped late: attachment moved from $attachment to " +
                            "${terminalSession?.attachmentId}",
                    )
                }
            } finally {
                budget.release(bytes)
            }
        }
    }

    /**
     * Reports the panel's measured grid.
     *
     * The measurement is clamped to the host's `maxCols`/`maxRows` — the host
     * rejects an oversized grid rather than clipping it — and it sizes the local
     * model as well as the PTY (W1): the model is the reader's window, so a model
     * still at the 80×24 the terminal was created with hides every row below the
     * panel's last one and never wraps a line at the panel edge. A repeated grid is
     * dropped, since the panel is re-measured on every layout pass and the soft
     * keyboard changes the height on every open and close.
     */
    fun terminalResize(columns: Int, rows: Int) {
        val agentId = terminalAgentId ?: return
        val id = terminalId ?: return
        val session = terminalSession ?: return
        if (!_terminal.value.writable) return
        val grid = session.measure(columns, rows) ?: return
        val attachment = session.attachmentId
        enqueueTerminalWrite {
            if (terminalSession?.attachmentId == attachment) {
                terminalClient.resize(agentId, id, attachment, grid.first, grid.second)
            }
        }
    }

    /**
     * Chains a terminal mutation onto the previous one.
     *
     * A mutex would only guarantee mutual exclusion; its waiters may be scheduled in
     * any order, and two keystrokes that overtake each other arrive transposed. A
     * chain on the previous job preserves submission order exactly.
     */
    private fun enqueueTerminalWrite(block: suspend () -> Unit) {
        val previous = terminalWriteChain
        terminalWriteChain = viewModelScope.launch {
            previous?.join()
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onTerminalControlFailure(error)
            }
        }
    }

    /**
     * Opens (or adopts) a terminal in [seat] of [sessionId] and attaches to it.
     *
     * `terminal/list` comes first and is not agent-scoped, so a session that already
     * retains a running terminal adopts it instead of leaving an orphan behind on
     * every visit — and it is also how switching seats back adopts the shell the other
     * seat left alive on the host.
     *
     * The seat decides which Session every terminal call addresses: the session the
     * user has open for `THIS_SESSION`, or the workspace's dedicated archived Session
     * for `HOST_SHELL`, which [materializeHostShell] builds (create → set mode →
     * archive) before any terminal exists in it.
     */
    private fun startTerminal(sessionId: String, seat: TerminalSeat, reuse: Boolean) {
        terminalJob?.cancel()
        terminalSession = null
        // Nothing this panel can address until the attach below succeeds: a
        // `terminal/limit-reached` refusal must not leave a terminal id behind that
        // a later Close would act on (W8).
        terminalId = null
        terminalAgentId = null
        // The session id and the seat move first: every publish below spreads the
        // current state, and a stale id here would attach the new session's screen to
        // the old session's identity for a frame.
        _terminal.value = _terminal.value.copy(
            sessionId = sessionId,
            seat = seat,
            seats = terminalSeatsFor(sessionId),
            hostShellIssue = null,
        )
        publishTerminal(
            phase = TerminalPhase.LOADING, active = null, writable = false,
            error = null, issue = null, limit = null, hostShellIssue = null,
        )
        terminalJob = viewModelScope.launch {
            try {
                val agentId = resolveTerminalAgent(sessionId, seat) ?: return@launch
                // The bootstrap may have just written the mapping, and that is what
                // makes the host-shell seat addressable, so the chips are refreshed
                // before the terminal call that can fail.
                _terminal.value = _terminal.value.copy(seats = terminalSeatsFor(sessionId))
                val environment = terminalClient.environment(agentId)
                val existing = terminalClient.list(agentId)
                // Only a running terminal may be adopted: the fallback to
                // `existing.firstOrNull()` adopted an *exited* one, which the host
                // still answers with a snapshot, so the panel painted it as connected
                // and typed into a shell that was gone (see [terminalToAdopt]).
                val adopted = if (reuse) terminalToAdopt(existing) else null
                // The terminals that can never be adopted again are retired here. Left
                // behind, each `exit`-and-reopen would allocate one more shell until the
                // host's limit replaced the dead panel with a dead end.
                val reaped = terminalReapList(existing)
                for (stale in reaped) {
                    try {
                        terminalClient.close(agentId, stale.id)
                    } catch (error: Throwable) {
                        // Not fatal: the stale terminal simply stays in the list and the
                        // next visit tries again. Named so the log is not silent.
                        Log.w(TAG, "terminal reap failed: id=${stale.id} state=${stale.state}", error)
                    }
                }
                val survived = existing.filterNot { stale -> reaped.any { it.id == stale.id } }
                val info = adopted ?: createTerminal(agentId, environment)
                publishTerminal(
                    environment = environment,
                    terminals = (survived.filterNot { it.id == info.id } + info),
                )
                attachTerminal(agentId, info, environment)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportTerminalFailure(error)
            }
        }
    }

    /** The Session terminal RPCs address for one seat; null when the seat failed. */
    private suspend fun resolveTerminalAgent(sessionId: String, seat: TerminalSeat): String? =
        when (seat) {
            TerminalSeat.THIS_SESSION -> sessionId
            TerminalSeat.HOST_SHELL -> materializeHostShell(sessionId)
        }

    /** The Workspace a session is accounted to, if any; a non-member is hosted at its cwd. */
    private fun workspaceOf(sessionId: String): WorkspaceItem? =
        _ui.value.workspaces.firstOrNull { sessionId in it.sessionIds }

    /**
     * The directory the app knows [sessionId] was created at.
     *
     * The roster is the normal source, and it carries archived sessions too, which is
     * why it is enough for the archived throwaway a reader can open from the drawer.
     * The header is the fallback for the window before `session/list` has landed, and
     * it is evidence about the *current* session only: another session's header must
     * never root this one's shell.
     */
    private fun cwdOf(sessionId: String): String? =
        _ui.value.sessions.firstOrNull { it.id == sessionId }?.cwd
            ?: header.value?.cwd?.takeIf { _ui.value.currentSessionId == sessionId }

    /**
     * The root this session's host shell belongs at, or null when the app knows no
     * directory to open one in.
     *
     * A Workspace first, because its host shell is shared by its members; otherwise the
     * session's own cwd, which `session/create` accepts exactly as it accepts a
     * workspace id. The choice itself is [hostShellRoot], where the JVM tests reach it.
     *
     * A **subagent** asks for its ancestor's root, not its own: the host never makes a
     * subagent a Workspace member, so resolving its own cwd built a second host shell
     * for a directory that already had one, and a shell the parent was not in. See
     * [hostShellOwnerOf] — one shell per project, which is what the reader means by
     * "the host shell" whatever session they are looking at.
     */
    private fun hostShellRootFor(sessionId: String): HostShellRoot? {
        val owner = hostShellOwnerOf(_ui.value.sessions, sessionId) ?: sessionId
        val workspace = workspaceOf(owner)
        return hostShellRoot(
            workspaceId = workspace?.id,
            workspaceTitle = workspace?.title,
            cwd = cwdOf(owner),
        )
    }

    /**
     * The seats on offer for [sessionId], each labelled with the policy in force.
     *
     * Recomputed rather than remembered because the session seat's label is the open
     * session's *current* preset: a chip that still said "Workspace Write" after the
     * reader moved the access chip to Full access would be the panel telling them
     * which shell they are in and getting it wrong.
     */
    private fun terminalSeatsFor(sessionId: String): List<SeatOption> = terminalSeats(
        sessionId = sessionId,
        sessionPolicyLabel = permissionLabel(_ui.value.currentPermission),
        hostShellSessionId = hostShellRootFor(sessionId)?.let { _ui.value.hostShellSessions[it.key] },
    )

    /**
     * Re-labels the chips after the open session's access mode moved.
     *
     * Only the labels: the shells themselves are unaffected by a preset change, and
     * re-attaching here would tear a live screen down to redraw two words.
     */
    private fun refreshTerminalSeats() {
        val sessionId = _terminal.value.sessionId ?: return
        _terminal.value = _terminal.value.copy(seats = terminalSeatsFor(sessionId))
    }

    /**
     * Builds (or adopts) the session's unconfined host shell and returns the Session
     * a terminal may be created in, or null after publishing the fact that stopped it.
     *
     * **The order is the feature.** `session/create` → `/permission danger-full-access`
     * → `workspace/archiveSession`, and only then a terminal:
     *
     *  - `terminal/create` derives the shell's confinement from the *Session's* policy
     *    (`terminal-controller`: `if (policy.mode !== 'danger-full-access') argv =
     *    sandbox.confine(argv, policy)`), so a terminal created before the mode step
     *    would be a bwrap-confined shell sitting behind a seat labelled "Host shell ·
     *    Full access". That is the one lie this panel must not tell.
     *  - The host refuses a mode change while any terminal is open in the session
     *    ("Close browser terminals before changing the Session sandbox mode"), so the
     *    mode cannot be corrected afterwards — only before.
     *  - The dedicated session must not sit in the drawer, so it is archived before
     *    anything is opened in it. Creating a terminal first would still work, but it
     *    leaves the session visible in the sidebar for as long as the terminal lives.
     *
     * [HostShellProgress.next] is the only thing that decides which step runs, and it
     * cannot hand back a terminal step: [HostShellProgress.terminalAllowed] is the gate
     * asserted below before this returns.
     */
    private suspend fun materializeHostShell(sessionId: String): String? {
        // A Workspace's directory when the session is a member of one, otherwise the
        // cwd the host reports: an unconfined shell needs a directory, not a Workspace.
        // Null here means the app knows no directory at all, which is the only case
        // left that the refusal sentence describes.
        val root = hostShellRootFor(sessionId)
        if (root == null) {
            failHostShell(HostShellIssue.NO_WORKSPACE)
            return null
        }
        val name = hostShellSessionName(root.label)
        val remembered = _ui.value.hostShellSessions[root.key]
        var progress = if (remembered == null) HostShellProgress() else HostShellProgress().withSession(remembered)
        // One rebuild at most: a remembered id the host no longer has is a stale
        // preference, and a second one would mean the host is refusing `session/create`
        // outright, which is the failure to report rather than to retry forever.
        var rebuilds = 0
        while (true) {
            val step = progress.next(name, root.key) ?: break
            publishTerminal(phase = TerminalPhase.CREATING)
            val failure = try {
                when (step) {
                    is HostShellStep.CreateSession ->
                        progress = progress.withSession(createHostShellSession(step.name, root))
                    is HostShellStep.SetAccessMode -> {
                        grantHostShellAccess(step.sessionId)
                        progress = progress.withModeSet()
                    }
                    is HostShellStep.Archive -> {
                        archiveHostShellSession(step.sessionId)
                        progress = progress.withArchived()
                    }
                }
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // The tab was left mid-bootstrap. Not a failure to report: there is no
                // reader, and a half-built session is exactly what the next visit's
                // walk resumes from.
                throw cancelled
            } catch (error: Throwable) {
                error
            }
            if (failure == null) continue
            if (isSessionGone(failure) && remembered != null && rebuilds == 0) {
                rebuilds++
                setHostShellSession(root.key, null)
                progress = HostShellProgress()
                continue
            }
            failHostShell(hostShellIssueFor(step, failure), failure)
            return null
        }
        // The ordering rule, enforced: `next()` returns null only once the session
        // exists, its mode is `danger-full-access` and it is archived, so no caller can
        // reach a `terminal/create` while that is not true.
        check(progress.terminalAllowed) {
            "the host shell must be unconfined and archived before any terminal is created in it"
        }
        val shellSessionId = progress.sessionId
        if (shellSessionId != remembered) setHostShellSession(root.key, shellSessionId)
        return shellSessionId
    }

    /**
     * `session/create` for one root, titled with [name].
     *
     * The request body is [hostShellCreateRequest], so a session in a Workspace is
     * created by `workspaceId` and one in no Workspace by `cwd` — the host accepts one
     * or the other and roots the session at `workspace?.path ?? cwd` either way
     * (`commands.ts:88-101`).
     *
     * The title is a second call because `session/create` has no title field
     * (`SessionCreateRequest`, `types.ts`): it is `session/rename`, the same RPC the
     * drawer's own rename path ([renameSession]) sends. **Best-effort, deliberately.**
     * `session/rename` is refused outright on a deployment that mounts no session-title
     * service (`commands.ts:179`), and a name in the Archived list is not worth denying
     * the reader the shell over — the bootstrap's failure facts stay reserved for the
     * steps that decide whether a terminal may exist. Renaming happens here, before the
     * mode step and the archive, while the session's agent is certainly live, because
     * `rename` resolves it.
     */
    private suspend fun createHostShellSession(name: String, root: HostShellRoot): String {
        val request = JSONObject()
        for ((field, value) in hostShellCreateRequest(root)) request.put(field, value)
        val value = client.rpc("session/create", JSONObject().put("request", request))
        val id = value.optString("sessionId")
        check(id.isNotEmpty()) { "session/create answered without a sessionId" }
        try {
            client.rpc(
                "session/rename",
                JSONObject().put(
                    "request",
                    JSONObject().put("sessionId", id).put("title", name),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "host shell session $id was created but not named: ${error.message}")
        }
        return id
    }

    /**
     * `/permission danger-full-access` — the write that makes the shell unconfined.
     *
     * The same call the access chip makes, and the same one the host's own web client
     * uses; it is deliberately *not* routed through [setPermission], which would also
     * move the access chip of the session the user is working in.
     */
    private suspend fun grantHostShellAccess(sessionId: String) {
        runCommandFor(sessionId, "/permission $HOST_SHELL_PRESET").getOrThrow()
    }

    /** `workspace/archiveSession`: keeps the dedicated session out of the drawer. */
    private suspend fun archiveHostShellSession(sessionId: String) {
        client.rpc(
            "workspace/archiveSession",
            JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
        )
    }

    /** True when the host no longer has the session a remembered id names. */
    private fun isSessionGone(error: Throwable): Boolean =
        (error as? DshRpcException)?.code == "session/not-found"

    /** Which fact a refused bootstrap step is. */
    private fun hostShellIssueFor(step: HostShellStep, error: Throwable): HostShellIssue = when {
        (error as? DshRpcException)?.code == "gateway/lookup-not-found" -> HostShellIssue.NO_LIVE_AGENT
        step is HostShellStep.CreateSession -> HostShellIssue.CREATE_REFUSED
        step is HostShellStep.SetAccessMode -> HostShellIssue.MODE_REFUSED
        else -> HostShellIssue.ARCHIVE_REFUSED
    }

    /**
     * States why the host shell is not there.
     *
     * A fact, not a spinner: nothing is retried on its own, and the sentence says what
     * did *not* happen. The panel keeps both seat chips on screen, so "This session"
     * is the way out and the same tap on "Host shell" is the retry.
     */
    private fun failHostShell(issue: HostShellIssue, cause: Throwable? = null) {
        publishTerminal(
            phase = TerminalPhase.FAILED,
            writable = false,
            issue = null,
            error = null,
            limit = null,
            hostShellIssue = hostShellIssueFact(issue, cause?.message),
        )
    }

    /**
     * Remembers (or, for null, forgets) one root's host-shell session, keyed by
     * [HostShellRoot.key].
     *
     * Written only once the bootstrap has completed, so a remembered id is evidence
     * that the mode step ran — see [materializeHostShell]. Forgetting is what a stale
     * id becomes, and it is also the manual recovery path: the drawer's Archived list
     * can restore or remove the session, and the next visit then builds a fresh one.
     */
    private fun setHostShellSession(rootKey: String, sessionId: String?) {
        val sessions = _ui.value.hostShellSessions.withHostShellSession(rootKey, sessionId)
        if (sessions == _ui.value.hostShellSessions) return
        configStore.save(configStore.load().copy(hostShellSessions = sessions))
        _ui.value = _ui.value.copy(hostShellSessions = sessions)
    }

    private suspend fun createTerminal(
        agentId: String,
        environment: TerminalEnvironmentInfo,
    ): TerminalInfo {
        publishTerminal(phase = TerminalPhase.CREATING)
        // The shell list is a convenience; a host that refuses it still has a
        // default shell, which `create` selects when `shellPath` is absent.
        val shells = runCatching { terminalClient.shells(agentId) }.getOrDefault(emptyList())
        val id = UUID.randomUUID().toString()
        // `id` is *not* published as the panel's terminal id here: on
        // `terminal/limit-reached` nothing was created, and a published id would let
        // a later Close report "The shell was closed." for a shell that never
        // existed (W8). `attachTerminal` sets it once `create` returns.
        return terminalClient.create(
            agentId = agentId,
            id = id,
            // The web client opens a new tab at 80×24 and lets the first resize fix
            // it; the host's own floors are 2 columns and 1 row.
            columns = minOf(80, environment.maxCols),
            rows = minOf(24, environment.maxRows),
            shellPath = shells.firstOrNull()?.path,
        )
    }

    /**
     * Attaches to [info] and keeps a live stream on it.
     *
     * A stream the host breaks (a [TerminalProtocolException] from the sequence
     * gate) and a stream the host simply ends are recovered the same way: cancel
     * this attachment and open a *fresh* one, which the host answers with a fresh
     * snapshot. That is the only honest repair for a screen with a hole in it (W2),
     * and because the host hands input to the newest attachment, the fresh id is
     * also what genuinely takes control back. [TerminalAttachmentSession] bounds and
     * backs off the retries; once they are spent the panel states the fact and waits
     * for the reader's Reconnect.
     */
    private suspend fun attachTerminal(
        agentId: String,
        info: TerminalInfo,
        environment: TerminalEnvironmentInfo,
    ) {
        terminalId = info.id
        terminalAgentId = agentId
        terminalInput = TerminalInputBudget(environment.maxInputBytes)
        val emulator = terminalEmulator ?: TerminalEmulator(info.cols, info.rows, environment.scrollback)
            .also { terminalEmulator = it }
        emulator.resize(info.cols, info.rows)
        val session = TerminalAttachmentSession(emulator, environment, info)
        terminalSession = session

        while (currentCoroutineContext().isActive) {
            publishTerminal(
                phase = TerminalPhase.CONNECTING, environment = environment, active = info,
                writable = false, error = null, issue = null, limit = null,
            )
            var restartIssue: TerminalIssue? = null
            var hostError = false
            try {
                terminalClient.follow(agentId, info.id, session.attachmentId).collect { event ->
                    when (event) {
                        is StreamEvent.Item -> {
                            val issue = onTerminalFrame(session, event.value)
                            if (issue != null) {
                                restartIssue = issue
                                // Unwind the stream so the loop opens a fresh attachment.
                                throw TerminalReattach()
                            }
                        }
                        is StreamEvent.Failure -> {
                            // A host error frame is authoritative: there is nothing
                            // to retry, and it has already been published.
                            hostError = true
                            onTerminalStreamFailure(event)
                        }
                        StreamEvent.End -> Unit
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (reattach: TerminalReattach) {
                // Expected: the loop below decides whether a restart is allowed.
            } catch (error: Throwable) {
                reportTerminalFailure(error)
                return
            }

            if (hostError || !currentCoroutineContext().isActive) return
            val phase = _terminal.value.phase
            if (phase == TerminalPhase.CLOSED || phase == TerminalPhase.FAILED) return
            // An exited or failed shell has no stream left to hold open.
            //
            // And an exited shell is a *closed* one: it leaves the seat. It did not
            // before, so `terminals` kept holding a shell that was gone, the seat was
            // never empty, and "closing the last shell returns to the conversation"
            // never fired for the way a shell usually ends — Ctrl-D, or `exit`. Only
            // the Close button emptied the list, which is why the rule looked like it
            // had stopped working.
            if (session.stopped) {
                publishTerminal(
                    phase = TerminalPhase.CLOSED, active = null, writable = false,
                    terminals = _terminal.value.terminals.filterNot { it.id == info.id },
                    error = null, issue = null, limit = null,
                )
                return
            }

            val restart = session.restart()
            if (restart == null) {
                // The stream is not coming back, but whether the *shell* is gone is a
                // different question: a dropped attachment is worth keeping the seat
                // for, and an exited shell is not. The host's own list is the
                // authority, and the answer is the difference between offering
                // "Reconnect" and returning to the conversation.
                //
                // A failed list is not an answer: on a blip the seat stays, because
                // guessing "gone" would throw the reader out of a shell that is still
                // running.
                val listed = runCatching { terminalClient.list(agentId) }.getOrNull()
                val gone = listed != null && listed.none { it.id == info.id }
                publishTerminal(
                    phase = TerminalPhase.DISCONNECTED, writable = false,
                    terminals = if (gone) {
                        _terminal.value.terminals.filterNot { it.id == info.id }
                    } else {
                        _terminal.value.terminals
                    },
                    issue = restartIssue ?: _terminal.value.issue ?: TerminalIssue.ATTACHMENT_ENDED,
                )
                return
            }
            // The notice's line for this phase is "Reconnecting to the host…".
            publishTerminal(
                phase = TerminalPhase.DISCONNECTED, writable = false,
                issue = null, error = null,
            )
            delay(restart.delayMs)
        }
    }

    /**
     * One `terminal/follow` frame.
     *
     * A snapshot is a *repaint*, not text: the host serialises xterm's whole buffer
     * (scrollback included, `\r\n` per row, with `?1049h` and the modes appended) for
     * a fresh terminal, so it is reset before it is fed. A frame that breaks the
     * ordering contract returns [TerminalIssue.OUTPUT_INVALID], which is the attach
     * loop's signal to re-attach rather than keep rendering a grid with a hole in it.
     */
    private fun onTerminalFrame(session: TerminalAttachmentSession, value: JSONObject): TerminalIssue? {
        val frame = TerminalClient.frameOf(value) ?: return null
        val action = try {
            session.accept(frame)
        } catch (error: TerminalProtocolException) {
            return TerminalIssue.OUTPUT_INVALID
        }
        when (action) {
            is TerminalFrameAction.Repaint -> {
                // Input is set up *before* the screen is fed: a repaint can ask the
                // host for its cursor position (DSR), and that answer must be writable.
                //
                // The snapshot is the first frame of every attachment, so this is where
                // a terminal that had *already* stopped when the panel adopted it first
                // becomes visible — and it used to publish CONNECTED unconditionally.
                // A shell that had exited then painted its last screen under a bar
                // saying "running" with a keyboard that could not type, and the first
                // keystroke blamed "another attachment" for it. The state a snapshot
                // carries is exactly as authoritative as a `state` frame's, so the same
                // fact is stated for both.
                publishTerminal(
                    phase = terminalPhaseFor(action.info) ?: TerminalPhase.CONNECTED,
                    active = action.info,
                    writable = session.writable,
                    issue = terminalStopIssue(action.info),
                    error = terminalStopDetail(action.info),
                )
                session.emulator.append(action.screen)
                flushTerminalReplies(session.emulator)
            }

            TerminalFrameAction.Output -> {
                flushTerminalReplies(session.emulator)
                bumpTerminalRevision()
            }

            is TerminalFrameAction.Metadata -> {
                // W6: this is where an exited or failed shell becomes visible. The
                // branch used to keep CONNECTED ("running") and only clear
                // `writable`, with no reason ever shown.
                val running = action.info.state == TerminalState.RUNNING
                publishTerminal(
                    phase = terminalPhaseFor(action.info) ?: _terminal.value.phase,
                    active = action.info,
                    writable = session.writable,
                    issue = terminalStopIssue(action.info)
                        ?: _terminal.value.issue.takeUnless {
                            running && (it == TerminalIssue.READ_ONLY || it == TerminalIssue.NOT_RUNNING)
                        },
                    error = terminalStopDetail(action.info) ?: _terminal.value.error,
                )
            }
        }

        // A bell is a program asking for the reader, and what is in front of them is
        // not necessarily this panel — a long build rings when it is done, with the
        // phone in a pocket. So it gets exactly what a question gets: the attention
        // notification, deep-linking to the session, suppressed while that session is
        // the one on screen (`alert` owns that test). The count is compared, not the
        // fact, so two bells are two alerts and a re-attach is not one.
        val bells = session.emulator.bellCount
        if (bells > session.bellsSeen) {
            session.bellsSeen = bells
            val sessionId = _terminal.value.sessionId
            // Logged because every way this can fail is silent: a stream that is not
            // attached, a notification the OS refuses, and the "already looking at it"
            // rule all look like a bell that never rang.
            Log.d(
                TAG,
                "terminal bell: count=$bells session=$sessionId " +
                    "foreground=${app.foreground.resumed} current=${_ui.value.currentSessionId}",
            )
            alert(
                id = Attention.bellId(sessionId),
                sessionId = sessionId,
                title = "Terminal bell",
                text = "${session.info?.title ?: "A terminal"} rang",
            )
        }
        return null
    }

    /**
     * Unwinds one `follow` collection so the attach loop can open a fresh
     * attachment. Not a failure: the session has already decided — and budgeted —
     * the restart.
     */
    private class TerminalReattach : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Answers the emulator's own questions (cursor position, DA) on the PTY. */
    private fun flushTerminalReplies(emulator: TerminalEmulator) {
        val replies = emulator.takeReplies()
        if (replies.isNotEmpty()) terminalWrite(replies)
    }

    private fun onTerminalStreamFailure(event: StreamEvent.Failure) {
        if (isAuthStreamFailure(event.code, event.message)) {
            onAuthFailure("terminal/follow", null)
            return
        }
        val issue = terminalIssueOf(event.code, null).takeIf { it != TerminalIssue.UNKNOWN }
            ?: if (event.code == "terminal/view") TerminalIssue.OUTPUT_INVALID else null
        publishTerminal(
            phase = TerminalPhase.DISCONNECTED, writable = false, issue = issue,
            error = event.message.ifBlank { null },
        )
    }

    /**
     * A refused write or resize.
     *
     * `terminal/control-unavailable` is the interesting one and it is not a banner:
     * it means another attachment took input, or the shell stopped. Both are facts
     * about control, so the panel drops to read-only and says which one it is.
     */
    private fun onTerminalControlFailure(error: Throwable) {
        if (error is DshAuthException) {
            onAuthFailure("terminal", error)
            return
        }
        val rpc = error as? DshRpcException
        val issue = terminalIssueOf(rpc?.code.orEmpty(), rpc?.details?.optString("reason"))
        publishTerminal(writable = false, issue = issue.takeIf { it != TerminalIssue.UNKNOWN })
    }

    private fun reportTerminalFailure(error: Throwable) {
        if (error is DshAuthException) {
            onAuthFailure("terminal", error)
            return
        }
        val rpc = error as? DshRpcException
        val issue = terminalIssueOf(rpc?.code.orEmpty(), rpc?.details?.optString("reason"))
        val limit = rpc?.details?.optInt("limit")?.takeIf { it > 0 }
        val message = if (error is DshUnreachableException) {
            "Could not reach the host."
        } else {
            error.message?.takeIf { it.isNotBlank() } ?: terminalIssueFact(issue, limit)
        }
        publishTerminal(
            phase = TerminalPhase.FAILED, writable = false,
            issue = issue.takeIf { it != TerminalIssue.UNKNOWN }, error = message, limit = limit,
        )
    }

    private fun bumpTerminalRevision() {
        _terminal.value = _terminal.value.copy(revision = _terminal.value.revision + 1)
    }

    private fun publishTerminal(
        phase: TerminalPhase = _terminal.value.phase,
        environment: TerminalEnvironmentInfo? = _terminal.value.environment,
        active: TerminalInfo? = _terminal.value.active,
        writable: Boolean = _terminal.value.writable,
        issue: TerminalIssue? = _terminal.value.issue,
        error: String? = _terminal.value.error,
        limit: Int? = _terminal.value.limit,
        terminals: List<TerminalInfo>? = null,
        hostShellIssue: String? = _terminal.value.hostShellIssue,
    ) {
        // A revision bump keeps the Canvas honest when only the metadata changed:
        // an exit or a mode change redraws the same grid with a different cursor.
        _terminal.value = _terminal.value.copy(
            phase = phase,
            environment = environment,
            active = active,
            writable = writable,
            issue = issue,
            error = error,
            limit = limit,
            terminals = terminals ?: _terminal.value.terminals,
            hostShellIssue = hostShellIssue,
            revision = _terminal.value.revision + 1,
        )
    }
    /**
     * Folds a fresh roster sample into the rows the UI holds.
     *
     * A one-line fold into [RunningBook]'s answer, which is the point: there is no
     * previous row to consult and no second opinion to reconcile, because the book is
     * the only thing that decides.
     */
    private fun mergeRoster(list: List<SessionItem>): List<SessionItem> =
        list.map { it.withRunning(runningBook) }

    /** The last running summary written to the diag file, so an unchanged pull is silent. */
    private var lastRunningSummary: String? = null

    /**
     * Records what one pull did to running state, for a device to read back.
     *
     * The bug this replaces was only ever diagnosed from a diag file, and what it looked
     * like was a *sequence*: a count that appeared and then vanished. So the counts, the
     * ids behind them and every flag that moved are written here, once per change rather
     * than once per pull.
     *
     * `wire`, `active` and `orphan` are the pull's raw material, so a reader can check
     * the arithmetic rather than trust the summary: `wire` is the host's own agent-status
     * count, `active` is how many rows carry an open turn in their journal, and `orphan`
     * is the subset of those with **no agent bound** — the crash signature
     * ([durableRunningOf] measured two of them after the box restarted mid-build, which
     * is why `active` is not read as liveness). `frameHeld` counts rows the book answers
     * `true` for while the pull's `running` said `false`, which after that decision only
     * a live `api-session/status` frame can do.
     */
    private fun noteRunningPull(
        wire: Map<String, Boolean>,
        active: Set<String>,
        noAgent: Set<String>,
    ) {
        val before = HashMap<String, Boolean>(_ui.value.sessions.size + 8)
        for (row in _ui.value.sessions) before[row.id] = row.running
        val ids = HashSet<String>(wire.size + 8)
        ids.addAll(wire.keys)
        ids.addAll(before.keys)
        var running = 0
        var frameHeld = 0
        val heldIds = ArrayList<String>(4)
        val changed = ArrayList<String>(4)
        for (id in ids) {
            val was = before[id]
            val answer = runningBook.running(id)
            if (was != null && was != answer) changed += id
            if (!answer) continue
            running++
            if (wire[id] == true) continue
            frameHeld++
            heldIds += id
        }
        val orphan = active.count { it in noAgent }
        val summary = "${wire.size}:$running:$frameHeld:$orphan:${changed.size}"
        if (summary == lastRunningSummary) return
        lastRunningSummary = summary
        ScrollDiag.note(
            "roster",
            "rows" to wire.size,
            "running" to running,
            "wire" to wire.count { it.value },
            "active" to active.size,
            "orphan" to orphan,
            "frameHeld" to frameHeld,
            "changed" to changed.size,
            "ids" to heldIds.take(6).joinToString(","),
            "changedIds" to changed.take(6).joinToString(","),
        )
    }

    /** Client-side fallback for the running clock when `turn/start` is off-window. */
    private var runningSince: Long? = null
    /** Latest pending queue per session, from the host-wide control stream. */
    private val queues = java.util.concurrent.ConcurrentHashMap<String, List<QueuedMessage>>()
    /**
     * Local submission echoes per session, in submission order (the host's own
     * client keeps the same list). A row sits here — and renders in the dock as
     * "Sending…" — until an authoritative queue row carrying its `rpcId` lands,
     * its prompt fails, or it ages out.
     */
    private val pendingSubmissions = java.util.concurrent.ConcurrentHashMap<String, List<QueuedMessage>>()
    /** Latest background jobs per session, from that session's `job/list` stream. */
    private val jobsBySession = java.util.concurrent.ConcurrentHashMap<String, List<JobItem>>()
    /** The session whose `job/list` roster stream is open, and its collector. */
    private var jobsWatchedSession: String? = null
    private var jobsWatchJob: Job? = null
    /** Accumulated live output per observed job, keyed by job id. */
    private val jobObservations = java.util.concurrent.ConcurrentHashMap<String, JobObservation>()
    /** The one job whose `job/follow` stream is open — the expanded row. */
    private var observedJobId: String? = null
    private var jobFollowJob: Job? = null
    /**
     * Sessions whose jobs settled since their list was last opened.
     *
     * Per session, not one app-wide flag: a job finishing in another session must
     * not light the seat of the session on screen. The open session's membership
     * is what [UiState.jobsFinishedUnseen] publishes. Written only from a roster
     * frame and the UI, both on the main dispatcher; the update rules themselves
     * live in [JobsMarker].
     */
    private var finishedUnseenSessions: Set<String> = emptySet()
    /** Folded projection values for the open session, merged from control deltas. */
    private var liveProjections: JSONObject? = null
    private var lastEventAt: Long = 0L
    private var tick: Long = 0L
    private val refreshLock = Any()
    private var pendingRefresh = false
    /** The content-search call in flight (or waiting out its debounce). */
    private var sessionSearchJob: Job? = null
    /** The query [sessionSearchJob] is answering, so a stale page can be dropped. */
    private var sessionSearchQuery: String? = null
    /**
     * Latched false the first time the host says it has no content index at all.
     *
     * That answer is a deployment fact ("the session-query index with openAt
     * \"never\""), identical on every keystroke, so continuing to ask paints the
     * same permanent warning over a search box the user is only using to find a
     * session by name. The name filter below is the fallback the web shows anyway;
     * this just stops saying so.
     */
    private var contentSearchAvailable = true

    init {
        val config = configStore.load()
        client.applyConfig(config)
        _ui.value = _ui.value.copy(
            themeMode = config.themeMode,
            agentPreset = config.agentPreset,
            busyEnter = config.busyEnter,
            drawerGroupByWorkspace = config.drawerGroupByWorkspace,
            drawerOrderByUpdated = config.drawerOrderByUpdated,
            drawerShowArchived = config.drawerShowArchived,
            drawerCollapsedSections = config.drawerCollapsedSections,
            hostShellSessions = config.hostShellSessions,
            baseUrl = config.baseUrl,
            username = config.username,
        )

        viewModelScope.launch {
            transcriptTick.sample(60).collect { publishTranscript() }
        }

        if (config.isConfigured) {
            ensureMuxWiring()
            connect()
        } else {
            _ui.value = _ui.value.copy(phase = AppPhase.SETUP)
        }
    }

    /**
     * Creates the event socket and wires its waterfall responder.
     *
     * Guarded because [DshClient.mux] needs a configured base URL: calling it
     * before setup would throw inside a coroutine and take the app down.
     */
    private fun ensureMuxWiring() {
        if (muxWired) return
        // DshClient.mux() only constructs the multiplexer; it opens no socket.
        val mux = runCatching { client.mux() }.getOrNull() ?: return
        muxWired = true

        mux.postEventResult = { args -> client.rpc("\$events/result", args) }
        mux.onLog = { message -> _ui.value = _ui.value.copy(status = message) }
        // The socket's own handshake can be refused (401/403, or a redirect to
        // the sign-in page). Nothing else observes that: the supervisor just
        // re-dials on a backoff while every screen still looks READY. Route it
        // through the same funnel as any other auth failure.
        client.onStreamAuthFailure = streamAuthHook
        // Coming back from the background is the one moment nobody else re-checks
        // whether the socket and the streams are actually alive: a frozen process
        // runs no coroutines, so a collector stays "active" while being dead.
        app.foreground.onForeground = foregroundHook
        // Approvals and questions are handled process-wide rather than here, so a
        // reaped Activity cannot turn an agent's question into a failed tool call.
        app.attention.foreground = { app.foreground.resumed }
        app.attention.start(client)
        // This client deliberately does *not* subscribe to `$events` itself. The
        // host fans a waterfall out to every registered event client and only
        // settles it once each of them has answered, so a second registration
        // meant "Skip" — and both attention timeouts — could never resolve, and
        // the agent hung waiting on a client that would never reply. The centre
        // owns the single subscription and relays the one signal the UI needs.
        app.attention.onLiveStatus = { sessionId, running -> applyLiveStatus(sessionId, running) }
        // A provider registering or disappearing changes the catalog, and the app has no
        // other way to hear about it: the list is fetched per connect. The host forwards
        // this event for exactly that purpose and the web refreshes on it, so a local
        // router that comes up is not invisible until the next reconnect.
        app.attention.onAdaptersUpdated = {
            viewModelScope.launch {
                // `loadModels` re-derives the chip from the host's catalog default and
                // the session's own projection, so re-reading the list is enough: a
                // provider that has just appeared or gone is reflected without the
                // chip having to be poked separately.
                loadModels()
            }
        }
        // The notification copy names the subject; only the client's session list
        // knows whether a waterfall came from a subagent.
        app.attention.isSubagent = { id ->
            _ui.value.sessions.firstOrNull { it.id == id }?.isSubagent == true
        }
        viewModelScope.launch {
            // The UI is a view of the centre's pending state, not its owner.
            app.attention.approval.collect { pending ->
                if (_ui.value.approval != pending) _ui.value = _ui.value.copy(approval = pending)
            }
        }
        viewModelScope.launch {
            app.attention.questions.collect { pending ->
                if (_ui.value.questions != pending) _ui.value = _ui.value.copy(questions = pending)
            }
        }
        viewModelScope.launch {
            // The drawer's per-session status source; a session parked on an
            // approval or a question is not idle and must not be painted as running.
            app.attention.pendingBySession.collect { pending ->
                if (_ui.value.pendingInteractions != pending) {
                    _ui.value = _ui.value.copy(pendingInteractions = pending)
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                val state = runCatching { client.mux().state.value }.getOrDefault(MuxState.IDLE)
                val open = state == MuxState.OPEN
                // Both fields move together: a CONNECTING -> CLOSED step leaves
                // `socketConnected` false either way but changes the word the
                // pill shows, so the guard is on the four-state value.
                if (_ui.value.muxState != state) {
                    _ui.value = _ui.value.copy(muxState = state, socketConnected = open)
                }
                delay(1000)
            }
        }
    }

    /** DshClient drops its multiplexer whenever the config changes, so rewire. */
    private fun rewireMux() {
        teardownStreams()
        muxWired = false
        ensureMuxWiring()
    }

    /**
     * Stops every long-lived stream. They are bound to one multiplexer instance,
     * so a config change (which builds a new one) would otherwise leave them
     * collecting from a dead socket — silently, and forever.
     */
    private fun teardownStreams() {
        workspacesJob?.cancel()
        controlJob?.cancel()
        workspacesJob = null
        controlJob = null
    }

    // ------------------------------------------------------------------ setup

    fun saveSetup(baseUrl: String, username: String, password: String, manualCookie: String) {
        val config = configStore.load().copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            username = username.trim(),
            password = password,
            manualCookie = manualCookie.trim(),
        )
        configStore.save(config)
        client.applyConfig(config)
        rewireMux()
        _ui.value = _ui.value.copy(
            baseUrl = config.baseUrl,
            username = config.username,
            drawerGroupByWorkspace = config.drawerGroupByWorkspace,
            drawerOrderByUpdated = config.drawerOrderByUpdated,
            drawerCollapsedSections = config.drawerCollapsedSections,
            hostShellSessions = config.hostShellSessions,
        )
        connect()
    }

    fun updateTheme(mode: String) {
        val config = configStore.load().copy(themeMode = mode)
        configStore.save(config)
        _ui.value = _ui.value.copy(themeMode = mode)
    }

    /**
     * Read the host's settings document for the Settings sheet.
     *
     * On open, not on a timer: the sheet is the only reader, and a write folds its
     * own answer back in, so there is nothing to poll for. A failed refresh keeps
     * the document already held, because the write path is CAS-guarded — a stale
     * revision is refused by the host and re-read here rather than applied blind.
     */
    fun loadHostSettings() {
        if (settingsJob?.isActive == true) return
        settingsJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(hostSettingsLoading = true, hostSettingsError = null)
            val (parsed, failure) = describeHostSettings()
            _ui.value = _ui.value.copy(
                hostSettings = parsed ?: _ui.value.hostSettings,
                hostSettingsLoading = false,
                hostSettingsError = failure,
            )
        }
    }

    /** One `settings/describe`, as the answer plus the wording of its failure. */
    private suspend fun describeHostSettings(): Pair<HostSettings?, String?> =
        runCatching { client.rpc("settings/describe", JSONObject()) }.fold(
            onSuccess = { reply ->
                val parsed = parseHostSettings(reply)
                parsed to if (parsed == null) "The host's settings reply was not a settings document" else null
            },
            onFailure = { error ->
                null to when (error) {
                    is DshAuthException -> "Not signed in"
                    else -> error.message ?: "Could not read the host's settings"
                }
            },
        )

    /**
     * Write one General row through `settings/update`.
     *
     * A `settings/conflict` is the host's own "re-read and re-apply" outcome rather
     * than a bad request, so the plan earns exactly one re-describe and one resend;
     * every other refusal reaches the reader in the host's own words. The local
     * preference is adopted only after the host has accepted, so a row can never
     * show a value the host did not take.
     */
    fun writeHostSetting(ns: String, name: String, value: String) {
        val held = _ui.value.hostSettings ?: return
        val target = held.field(ns, name) ?: return
        if (!held.writable || !target.editable || _ui.value.settingsSaving != null) return
        viewModelScope.launch {
            val plan = SettingsWritePlan(ns, name, value)
            _ui.value = _ui.value.copy(settingsSaving = plan.key, settingsWriteError = null)
            var request: JSONObject? = plan.request(target)
            var message = "The host refused the change"
            while (request != null) {
                val body = request
                request = null
                val outcome = runCatching { client.rpc("settings/update", body) }
                val accepted = outcome.getOrNull()?.let { plan.accepted(it) as? SettingsApply.Accepted }
                if (accepted != null) {
                    _ui.value = _ui.value.copy(
                        hostSettings = (_ui.value.hostSettings ?: HostSettings()).with(accepted.field),
                        settingsSaving = null,
                    )
                    adoptHostSetting(ns, name, value)
                    return@launch
                }
                val error = outcome.exceptionOrNull()
                message = error?.message ?: message
                if (error is DshRpcException && error.code == "settings/conflict") {
                    val (fresh, _) = describeHostSettings()
                    when (val step = plan.conflict(fresh ?: HostSettings(), message)) {
                        is SettingsApply.Retry -> request = step.request
                        is SettingsApply.Failed -> message = step.message
                        is SettingsApply.Accepted -> Unit
                    }
                }
            }
            _ui.value = _ui.value.copy(settingsSaving = null, settingsWriteError = message)
        }
    }

    /**
     * Keep the app's own theme and busy-Enter preference in step with an accepted
     * host write: this client renders from those local copies, and the host's
     * document is where the next session — and the web UI — will read them from.
     */
    private fun adoptHostSetting(ns: String, name: String, value: String) {
        when (ns to name) {
            "ui-theme" to "preference" -> updateTheme(value)
            "ui-conversation" to "busyEnter" -> updateBusyEnter(value)
            else -> Unit
        }
    }

    fun updatePreset(preset: String) {
        val config = configStore.load().copy(agentPreset = preset)
        configStore.save(config)
        _ui.value = _ui.value.copy(agentPreset = preset)
    }

    /**
     * The sidebar's two view modes are client-local preferences, so they follow
     * the same load/copy/save path as the theme rather than living in the
     * composable's saved state — which reset them on every cold start.
     */
    fun setDrawerGroupByWorkspace(groupByWorkspace: Boolean) {
        configStore.save(configStore.load().copy(drawerGroupByWorkspace = groupByWorkspace))
        _ui.value = _ui.value.copy(drawerGroupByWorkspace = groupByWorkspace)
    }

    fun setDrawerOrderByUpdated(orderByUpdated: Boolean) {
        configStore.save(configStore.load().copy(drawerOrderByUpdated = orderByUpdated))
        _ui.value = _ui.value.copy(drawerOrderByUpdated = orderByUpdated)
    }

    fun setDrawerShowArchived(showArchived: Boolean) {
        configStore.save(configStore.load().copy(drawerShowArchived = showArchived))
        _ui.value = _ui.value.copy(drawerShowArchived = showArchived)
    }

    /**
     * Collapse/expand a Workspace section. Takes the whole set rather than a single
     * key so the drawer's toggle stays a pure `set +/- key` at the call site, exactly
     * as it was when the set lived in the composable.
     */
    fun setDrawerCollapsedSections(sections: Set<String>) {
        configStore.save(configStore.load().copy(drawerCollapsedSections = sections))
        _ui.value = _ui.value.copy(drawerCollapsedSections = sections)
    }

    fun signOut() {
        viewModelScope.launch {
            // There is no logout endpoint — browser auth is a signed cookie, so
            // dropping it locally is the whole of signing out.
            client.cookieJar.clear()
            configStore.clearCredentials()
            followJob?.cancel()
            teardownStreams()
            client.mux().shutdown()
            // Drop the pending cards and their notifications with the session
            // list; the reset below has no current session for them to belong to.
            app.attention.stop()
            muxWired = false
            queues.clear()
            pendingSubmissions.clear()
            liveProjections = null
            _ui.value = UiState(phase = AppPhase.SETUP, themeMode = _ui.value.themeMode)
        }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null, errorNeedsSignIn = false)
    }

    /**
     * Hands [text] back to the composer after a send that never landed on a host
     * session. Blank text is nothing to give back — a failed attachment-only send
     * still has its receipt on the rail.
     */
    private fun restoreDraft(text: String) {
        if (text.isBlank()) return
        _ui.value = _ui.value.copy(draftRestore = DraftRestore(text, ++draftRestoreNonce))
    }

    /** The composer has taken the restored draft; clear it so it cannot re-apply. */
    fun consumeDraftRestore() {
        _ui.value = _ui.value.copy(draftRestore = null)
    }

    /**
     * The banner's action for an auth failure: go to sign-in with the stored
     * host and username already in the fields.
     *
     * The transcript stays in memory, so signing back in returns to the same
     * session rather than to a lost screen.
     */
    fun signInAgain() {
        val config = configStore.load()
        _ui.value = _ui.value.copy(
            phase = AppPhase.SETUP,
            busy = false,
            status = null,
            error = AUTH_ERROR,
            errorNeedsSignIn = false,
            baseUrl = config.baseUrl,
            username = config.username,
        )
    }

    // -------------------------------------------------------------- approvals

    /** Submits answers: `{answers:[{id, selected:[label], custom?}]}`. */
    fun answerQuestions(answers: List<Triple<String, List<String>, String?>>) =
        app.attention.answerQuestions(answers)

    /** Delegates the request untouched, which surfaces the host's own refusal. */
    fun skipQuestions() = app.attention.skipQuestions()

    fun resolveApproval(allow: Boolean) = app.attention.resolveApproval(allow)

    // ----------------------------------------------------------- attention

    /**
     * Posts an attention notification, unless the user is already looking at the
     * very session it is about — buzzing a phone for a card that is on screen is
     * noise, and the in-app card is always there.
     */
    private fun alert(id: Int, sessionId: String?, title: String, text: String) {
        val visible = app.foreground.resumed &&
            (sessionId == null || sessionId == _ui.value.currentSessionId)
        if (visible) {
            Log.d(TAG, "alert suppressed (already on screen): $title")
            return
        }
        Log.d(TAG, "alert posted: id=$id session=$sessionId title=$title")
        Attention.notify(app, id, title, text, sessionId)
    }

    // --------------------------------------------------------------- connect

    /**
     * Decides what a failed connect actually means.
     *
     * Only a host that *refused* the credentials may throw the user back to the
     * setup screen. A timeout, a dropped socket or a host that is still starting is
     * transient — and on a slow host those arrive constantly, so losing the session
     * being read to a login screen is far worse than a banner over the transcript.
     * Those cases keep the app where it is and retry on a short backoff.
     */
    private fun onConnectFailure(error: Throwable?, wasReady: Boolean) {
        if (error is DshAuthException) {
            // A refusal seen while connecting is an auth failure like any other,
            // so it goes through the one funnel. `ensureAuthenticated` already
            // tried the stored credentials, so no second login is attempted
            // here — a repeat within a second only risks the host's lockout.
            onAuthFailure("connect", error, allowHeal = false)
            return
        }
        val message = when {
            error == null -> "Could not reach the host"
            else -> error.message ?: error.javaClass.simpleName
        }
        _ui.value = if (wasReady) {
            _ui.value.copy(phase = AppPhase.READY, busy = false, status = null, error = message, errorNeedsSignIn = false)
        } else {
            _ui.value.copy(phase = AppPhase.SETUP, busy = false, status = null, error = message, errorNeedsSignIn = false)
        }
        scheduleReconnect()
    }

    // ------------------------------------------------------------ auth funnel

    /**
     * The one place an auth refusal lands, whichever path produced it.
     *
     * Unary calls used to paint `error = "Session expired — sign in again."` over
     * a READY screen whose every action then failed, and a stream that died with
     * an auth error was logged and otherwise ignored — the transcript simply
     * froze. Both are the same event, so both call this.
     *
     * The order is deliberate:
     *  1. heal silently with the stored credentials (a merely-expired cookie
     *     costs the user nothing: same screen, same transcript);
     *  2. only if that refusal is a *credential* refusal, fall through to Setup
     *     with the fields prefilled;
     *  3. a host that merely stopped answering keeps the session on screen with
     *     an actionable banner and the ordinary reconnect backoff.
     *
     * Bounded: at most one silent attempt per episode ([authHealSpent]) plus a
     * short cooldown, so a host that keeps refusing the socket cannot turn this
     * into a login loop.
     */
    private fun onAuthFailure(source: String, cause: Throwable?, allowHeal: Boolean = true) {
        viewModelScope.launch { handleAuthFailure(source, cause, allowHeal) }
    }

    private suspend fun handleAuthFailure(source: String, cause: Throwable?, allowHeal: Boolean) {
        Log.w(TAG, "auth failure from $source: ${cause?.message ?: "host refused the session"}")
        // A heal already running owns the outcome: a second one would race it.
        if (authHealInFlight) return
        if (_ui.value.phase == AppPhase.SETUP) {
            // Nothing left to heal — the user is already being asked to sign in.
            if (_ui.value.error == null) offerSignIn(AUTH_ERROR)
            return
        }
        val cooling = System.currentTimeMillis() - lastAuthHealAt < AUTH_HEAL_COOLDOWN_MS
        val alreadySpent = authHealSpent
        authHealSpent = true
        if (!allowHeal || alreadySpent || cooling || !configStore.load().isConfigured) {
            askToSignIn(cause)
            return
        }

        authHealInFlight = true
        // `ensureAuthenticated` re-installs a configured manual cookie before it
        // probes, which is itself a heal for a jar that was cleared or corrupted.
        val healed = runCatching { client.ensureAuthenticated() }
            .getOrElse { Result.failure(it) }
        authHealInFlight = false
        val failure = healed.exceptionOrNull()

        when {
            healed.isSuccess -> {
                authHealSpent = false
                lastAuthHealAt = System.currentTimeMillis()
                onAuthHealed(source)
            }

            // The host stopped answering while we were healing: that is the
            // transient case, which must not throw the session away.
            failure is DshUnreachableException -> {
                authHealSpent = false
                offerSignIn(AUTH_ERROR)
                scheduleReconnect()
            }

            else -> askToSignIn(failure)
        }
    }

    /**
     * The silent re-login worked, so nothing on screen changes: the transcript is
     * untouched (the reducer is deliberately not reset here) and only the streams
     * are put back on their feet.
     */
    private fun onAuthHealed(source: String) {
        Log.d(TAG, "silent re-login healed an auth failure from $source")
        ensureMuxWiring()
        _ui.value = _ui.value.copy(error = null, errorNeedsSignIn = false, status = null, busy = false)
        if (_ui.value.phase != AppPhase.READY) {
            // The refusal landed while connecting; run the ordinary connect path.
            connectRetries = 0
            attemptConnect()
            return
        }
        viewModelScope.launch {
            // The socket may have died at its handshake; ask for it now rather
            // than waiting out the supervisor's backoff.
            runCatching { client.mux().ensureConnected() }
            ensureWorkspaceStream()
            ensureControlStream()
            reopenFollowIfStopped()
            runCatching { fetchSessions() }.onSuccess { list ->
                _ui.value = _ui.value.copy(sessions = mergeRoster(list))
            }
        }
    }

    /**
     * The last resort: the credentials were refused (or are gone), so the user
     * has to act. Setup is prefilled from the stored config and says what to do.
     */
    private fun askToSignIn(cause: Throwable?) {
        val config = configStore.load()
        val detail = cause?.message?.takeIf { it.isNotBlank() } ?: AUTH_REFUSED
        val message = detail.trimEnd('.') + ". Sign in again to continue."
        _ui.value = _ui.value.copy(
            phase = AppPhase.SETUP,
            busy = false,
            status = null,
            error = message,
            errorNeedsSignIn = false,
            baseUrl = config.baseUrl,
            username = config.username,
        )
        authHealSpent = true
    }

    /**
     * Keep the user where they are, but make the banner actionable. Used when the
     * refusal is real but the host is/was unreachable — the screen is still
     * usable the moment the host answers, so it must not be thrown away.
     */
    private fun offerSignIn(message: String) {
        _ui.value = if (_ui.value.phase == AppPhase.READY) {
            _ui.value.copy(error = message, errorNeedsSignIn = true, busy = false, status = null)
        } else {
            _ui.value.copy(
                phase = AppPhase.SETUP,
                error = message,
                errorNeedsSignIn = false,
                busy = false,
                status = null,
            )
        }
    }

    /**
     * Paints a failed call — and the one place an auth refusal is diverted to
     * [onAuthFailure] before it can become a dead-end banner over a READY screen.
     */
    private fun showFailure(error: Throwable, source: String, prefix: String = "") {
        if (error is DshAuthException) {
            onAuthFailure(source, error)
            return
        }
        _ui.value = _ui.value.copy(error = prefix + describe(error), errorNeedsSignIn = false)
    }

    /**
     * Whether a stream failure code is an auth refusal. The host has no shared
     * vocabulary for "your cookie is dead", so this matches the words the host
     * and its proxy actually use; everything else stays a transient stream error.
     */
    private fun isAuthStreamFailure(code: String, message: String): Boolean {
        val text = "$code $message".lowercase()
        return AUTH_MARKERS.any { text.contains(it) }
    }

    // ------------------------------------------------------------ resume sync

    /**
     * The app came back to the foreground after being backgrounded (or frozen).
     *
     * A frozen process runs no coroutines: the socket can be dead, the
     * `workspace/follow` and `session/control` collectors can be "active" while
     * receiving nothing, and the transcript can be stale. Nothing else re-checks
     * any of that — `RemoteMux` only replays on a reconnect it detected itself,
     * and the stream guards trust `isActive` — so the app used to look frozen
     * until it was restarted. This re-checks, re-subscribes and re-reads the
     * state that only ever arrives on a stream.
     *
     * Idempotent and cheap on a healthy app: the socket is not touched when it
     * is already open, the transcript is never reset, and the re-subscriptions
     * are baseline snapshots. It also never schedules anything — a socket that
     * is still down stays with the existing backoff and supervisor.
     */
    private fun onAppResumed() {
        if (_ui.value.phase != AppPhase.READY) return
        if (resumeResyncInFlight) return
        resumeResyncInFlight = true
        viewModelScope.launch {
            try {
                Log.d(TAG, "resume: re-checking the connection")
                // A no-op while the socket is OPEN; the supervisor owns the rest.
                runCatching { client.mux().ensureConnected() }
                val open = runCatching { client.mux().state.value == MuxState.OPEN }.getOrDefault(false)
                if (!open) {
                    Log.d(TAG, "resume: socket is down; leaving it to the backoff")
                    return@launch
                }
                // Resuming *onto* a session is opening it: a notification that landed
                // while the app was in a pocket is about the screen the reader is now
                // looking at. (Opening a different one clears that one instead, in
                // `AttentionCenter.visibleSessionId`.)
                _ui.value.currentSessionId?.let { app.attention.markSessionSeen(it) }
                // Cancel and re-open rather than trusting `isActive`: a frozen
                // collector is active and dead at the same time, and only a fresh
                // opening frame carries the baseline (archived ids included).
                reopenStreamsForResume()
                runCatching { fetchSessions() }
                    .onFailure { if (it is DshAuthException) onAuthFailure("session/list", it) }
                    .onSuccess { list ->
                        _ui.value = _ui.value.copy(sessions = mergeRoster(list))
                        warnOnMissingMembers(list)
                        publishRosterRunning()
                    }
                // Identity, not activity: a resume repaints the labels and modes the
                // lineage rows are named with, which the follow snapshot re-delivers
                // (`manager.ts:796-798`). Running state came from the pull above.
                refreshWatchedCatalogs()
                Log.d(TAG, "resume: streams re-subscribed")
            } finally {
                resumeResyncInFlight = false
            }
        }
    }

    /**
     * Re-subscribes every stream the UI depends on. The transcript is *not*
     * reset: the follow stream's new snapshot re-applies records idempotently
     * (they are keyed by seq), so the reader keeps whatever they were looking at.
     */
    private fun reopenStreamsForResume() {
        workspacesJob?.cancel()
        workspacesJob = null
        controlJob?.cancel()
        controlJob = null
        ensureWorkspaceStream()
        ensureControlStream()
        val sessionId = _ui.value.currentSessionId
        if (sessionId != null) startFollow(sessionId)
        // The terminal stream is a reconnecting attachment like the others, but a
        // frozen process leaves its collector "active" and dead. Re-attaching with a
        // *fresh* attachment is also the only way to take input control back from
        // another client that took it while this one was asleep.
        if (sessionId != null && terminalJob != null && _terminal.value.sessionId == sessionId) {
            startTerminal(sessionId, _terminal.value.seat, reuse = true)
        }
    }

    /** A few spaced retries, so a host that is merely slow heals without a tap. */
    private fun scheduleReconnect() {
        if (connectRetries >= MAX_CONNECT_RETRIES) return
        connectRetries += 1
        viewModelScope.launch {
            delay(RECONNECT_BASE_DELAY_MS * connectRetries)
            if (configStore.load().isConfigured) attemptConnect()
        }
    }

    fun connect() {
        // An explicit connect starts the retry budget over, and it is also the
        // one action that clears the "already tried silently" latch: the user has
        // just supplied (possibly corrected) credentials.
        connectRetries = 0
        authHealSpent = false
        attemptConnect()
    }

    private fun attemptConnect() {
        ensureMuxWiring()
        viewModelScope.launch {
            val wasReady = _ui.value.phase == AppPhase.READY
            _ui.value = _ui.value.copy(
                // A reconnect must not blank the transcript behind the loading state.
                phase = if (wasReady) AppPhase.READY else AppPhase.LOADING,
                busy = !wasReady,
                error = null,
                errorNeedsSignIn = false,
                status = if (wasReady) "Reconnecting…" else "Connecting…",
            )
            client.installManualCookie()

            val auth = client.ensureAuthenticated()
            if (auth.isFailure) {
                onConnectFailure(auth.exceptionOrNull(), wasReady)
                return@launch
            }

            _ui.value = _ui.value.copy(status = "Loading sessions…")
            val sessions = runCatching { fetchSessions() }
            if (sessions.isFailure) {
                onConnectFailure(sessions.exceptionOrNull(), wasReady)
                return@launch
            }

            connectRetries = 0
            _ui.value = _ui.value.copy(
                phase = AppPhase.READY,
                busy = false,
                status = null,
                sessions = sessions.getOrThrow(),
            )

            ensureWorkspaceStream()
            ensureControlStream()
            runCatching { loadModels() }
            runCatching { loadPermissionCatalog() }
            runCatching { loadAgentPresets() }

            // Reopen the session the user explicitly asked for, else the most
            // recent one that actually has content; a blank (provisional) session
            // is not worth landing in, and the host reports several of them near
            // the top of the list.
            //
            // The explicit pick is read (and consumed) here, in the same
            // uninterrupted main-thread block as the open below, so it cannot be
            // overwritten by this auto-open whichever order the two arrive in.
            val current = _ui.value.currentSessionId
            val candidates = sessions.getOrThrow().filter { !it.isSubagent }
            val explicit = explicitSession
            explicitSession = null
            val target = explicit
                ?: current
                ?: candidates.firstOrNull { !it.blank }?.id
                ?: candidates.firstOrNull()?.id
            if (target != null) {
                if (explicit != null) Log.d(TAG, "connect honoured explicit session $explicit")
                openSession(target)
            } else {
                // The host holds no session at all: show the pending seat for the
                // default Workspace rather than an empty hero. Nothing is created —
                // the seat's target is only recorded, and the first send (or the
                // first command, or the first staged file) is what creates.
                preferredWorkspaceId()?.let { workspaceId ->
                    val path = _ui.value.workspaces.firstOrNull { it.id == workspaceId }?.path
                    enterPendingSession(
                        PendingSessionTarget(SessionTarget.Workspace(workspaceId, path)),
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- sessions

    /**
     * The host-wide live control stream (`session/control`).
     *
     * This is the only place the pending queue is published. It opens with one
     * `baseline` covering *every* session — queues, background jobs and folded
     * projections — then pushes `queue` / `projection` / `jobs` replacements.
     * The queue is transient by nature (it is inbox state, not journal state),
     * so nothing about it appears in `session/follow`: without this stream the
     * composer has no idea a message is waiting.
     */
    private fun ensureControlStream() {
        if (controlJob?.isActive == true) return
        controlJob = viewModelScope.launch {
            client.mux().openStream("session/control", JSONObject()).collect { event ->
                when (event) {
                    is StreamEvent.Item -> runCatching { handleControlFrame(event.value) }
                        .onFailure { Log.w(TAG, "control frame failed: ${it.message}") }

                    is StreamEvent.Failure -> {
                        Log.w(TAG, "control stream failed: ${event.code} ${event.message}")
                        if (isAuthStreamFailure(event.code, event.message)) {
                            onAuthFailure("session/control", null)
                        }
                    }

                    StreamEvent.End -> Log.d(TAG, "control stream ended")
                }
            }
        }
    }

    private fun handleControlFrame(value: JSONObject) {
        when (value.str("type")) {
            "baseline" -> {
                val payload = value.obj("value") ?: return
                queues.clear()
                payload.obj("queues")?.let { all ->
                    all.keys().forEach { sessionId ->
                        queues[sessionId] = parseQueue(all.optJSONArray(sessionId))
                    }
                }
                // The running host's baseline carries `projections` and nothing
                // else: no `queues`, and — the reason jobs had vanished from every
                // session — no `jobs`. The queue block above is kept because an
                // older host does send it; the job block is deliberately *not*
                // replaced with a fallback, because a client that keeps waiting for
                // a frame the host no longer sends waits forever. Jobs come from
                // `job/list` now; see [watchJobs].
                publishJobsForCurrent()
                // Seed the open session's folded projections so a control delta
                // that only carries one key still has its siblings in hand.
                val current = _ui.value.currentSessionId
                if (current != null) {
                    val block = payload.obj("projections")?.obj(current)?.obj("values")
                    if (block != null) {
                        liveProjections = block
                        applySessionProjections(block)
                        applyModelSelection(block)
                        // The subagent catalog lives in these projections, and this is
                        // where they arrive. The rebuild used to happen only at
                        // `openSession`, which runs *before* the baseline — so it read an
                        // empty map every time and the lineage showed nothing, however
                        // many children the session had.
                        refreshSubagentCatalog(current)
                    }
                }
                publishQueue()
            }

            "queue" -> {
                val sessionId = value.str("sessionId")
                if (sessionId.isEmpty()) return
                queues[sessionId] = parseQueue(value.arr("items"))
                publishQueue()
            }

            // A 0.2.0-rc.2 host never sends this. It was the job roster's old home,
            // and the block is kept only so a host that still pushes one is read
            // rather than dropped; [watchJobs] is what makes jobs appear.
            "jobs" -> {
                val sessionId = value.str("sessionId")
                if (sessionId.isEmpty()) return
                recordJobsFrame(sessionId, JobsWire.jobsOf(value.arr("jobs")))
            }

            "projection" -> {
                // Only the open session's values are worth folding; keeping the
                // other sessions' would grow without bound for no reader.
                val sessionId = value.str("sessionId")
                if (sessionId.isEmpty() || sessionId != _ui.value.currentSessionId) return
                // A catalog projection arriving for the session on screen is the
                // host *pushing* membership — the app's one push-shaped
                // roster change, and the closest thing here to the `session/added`
                // that re-reads a catalog in the web (`manager.ts:718-725`).
                val merged = liveProjections ?: JSONObject()
                merged.put(value.str("key"), value.opt("value"))
                liveProjections = merged
                applySessionProjections(merged)
                // `modelSelection` is a projection like any other, so a delta that
                // carries it — the host rolling `lastUsed` forward after a turn, or
                // another client selecting — has to move the chip. It used to be read
                // only from a full snapshot, which left the chip describing whatever
                // the last snapshot said until the stream was reopened.
                if (value.str("key") == "modelSelection") applyModelSelection(merged)
                // After the merge, not before: the rebuild reads `liveProjections`,
                // and running it first would read the value this frame replaces.
                if (value.str("key") == "subagentCatalog") refreshSubagentCatalogSoon(sessionId)
            }
        }
    }

    /** Publishes the open session's slice of the queue map to the UI. */
    private fun publishQueue() {
        val current = _ui.value.currentSessionId ?: return
        val rows = dockRows(current)
        // Unchanged frames must not churn the UI state: the dock's expand/enter
        // animations are driven off this list.
        if (rows == _ui.value.queue) return
        _ui.value = _ui.value.copy(queue = rows)
    }

    /**
     * The dock's rows for one session: the host's inbox rows, then the local
     * submission echoes the host has not admitted yet.
     *
     * `steering` rows are kept, not filtered: the host is authoritative about
     * placement, and a force-steered item comes back as `placement: "steering"`
     * (probed on this host) and stays there until the running turn claims it —
     * which is exactly the "on its way" state the dock's send glyph draws. The
     * web drops them because its dock is fed by a mirror that never carries
     * them; this app's mirror does, and hiding them made a force-steer vanish
     * with no transition at all.
     *
     * A local echo is retired by the `rpcId` the host copies onto the admitted
     * row (`session/prompt.requestId`), which is the web's own `admitted` rule.
     */
    private fun dockRows(sessionId: String): List<QueuedMessage> {
        val host = queues[sessionId].orEmpty()
            .filter { it.placement == "queued" || it.placement == "steering" }
        val admitted = host.mapNotNull { it.rpcId }.toSet()
        val echoes = pendingSubmissions[sessionId].orEmpty().filterNot { it.id in admitted }
        if (echoes.size != pendingSubmissions[sessionId].orEmpty().size) {
            if (echoes.isEmpty()) {
                pendingSubmissions.remove(sessionId)
            } else {
                pendingSubmissions[sessionId] = echoes
            }
        }
        return host + echoes
    }

    /**
     * Flattens one wire queue item.
     *
     * Both client-side derivations are ported verbatim from the host's client
     * store (`queue-mirror.ts`) because the dock's behaviour depends on them:
     * the preview is the *non-attachment* content and `text` is null the moment
     * any block is not text, which is exactly when the web disables Edit.
     */
    private fun parseQueue(array: JSONArray?): List<QueuedMessage> {
        if (array == null) return emptyList()
        val result = ArrayList<QueuedMessage>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val message = item.obj("message")
            val content = message.arr("content") ?: JSONArray()
            val attachments = ArrayList<QueueAttachment>()
            val flat = StringBuilder()
            var allText = true
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                when (val type = block.str("type")) {
                    "text" -> {
                        if (flat.isNotEmpty()) flat.append(' ')
                        flat.append(block.str("text"))
                    }

                    "image", "file" -> {
                        allText = false
                        val ref = block.obj("attachment")
                        attachments += QueueAttachment(
                            kind = type,
                            name = ref.str("name").ifEmpty { if (type == "image") "Image" else "File" },
                            bytes = ref.int("bytes"),
                        )
                    }

                    else -> {
                        allText = false
                        if (flat.isNotEmpty()) flat.append(' ')
                        flat.append("[$type]")
                    }
                }
            }
            val collapsed = flat.toString().replace(Regex("\\s+"), " ").trim()
            val preview = if (collapsed.length > QUEUE_PREVIEW_CHARS) {
                collapsed.take(QUEUE_PREVIEW_CHARS) + "…"
            } else {
                collapsed
            }
            result += QueuedMessage(
                id = item.str("id"),
                placement = item.str("placement").ifEmpty { "queued" },
                preview = preview,
                // `textOf` joins with NO separator, unlike the preview.
                text = if (allText) content.let { blocks ->
                    (0 until blocks.length()).joinToString("") { blocks.optJSONObject(it)?.str("text").orEmpty() }
                } else {
                    null
                },
                attachments = attachments,
                // Probed on this host: the row carries `rpcId`, copied from the
                // `session/prompt.requestId` that admitted it. That is the only
                // identity a local echo can be matched against.
                rpcId = item.str("rpcId").takeIf { it.isNotEmpty() },
            )
        }
        return result
    }

    // ------------------------------------------------------------------- jobs

    /**
     * Keeps the open session's `job/list` roster stream current.
     *
     * One stream, for the session on screen — which is exactly what the web does:
     * it mounts its job control with the open conversation and watches that
     * session's roster. This is a change in reach from the old global `jobs`
     * baseline, and it is not a choice: 0.2.0-rc.2 has no host-wide job push and no
     * forwarded job event (the allowlist in `dsh-api-remotes` was checked, and a
     * live `$events` capture across a job's whole life carried none), so a roster
     * per session is the only channel there is.
     *
     * The consequence is worth stating plainly: the seat's finished-unseen dot is
     * now only ever earned by the session you are looking at, because no other
     * session's roster is open. The web has no cross-session job dot either, so
     * this is parity rather than a regression — but it *is* less than the old
     * global baseline gave, and it is why [JobsMarker]'s per-session comparison is
     * kept rather than simplified away.
     */
    private fun watchJobs(sessionId: String?) {
        if (jobsWatchedSession == sessionId && jobsWatchJob?.isActive == true) return
        jobsWatchJob?.cancel()
        jobsWatchJob = null
        jobsWatchedSession = sessionId
        if (sessionId == null) {
            recordJobsFrame(sessionId = null, items = emptyList())
            return
        }
        jobsWatchJob = viewModelScope.launch {
            jobClient.list(sessionId).collect { event ->
                when (event) {
                    is StreamEvent.Item -> {
                        // Whole-set replacement frames, so a reconnect's first frame
                        // is already the truth and there is nothing to merge.
                        if (event.value.str("type") != "rows") return@collect
                        recordJobsFrame(sessionId, JobsWire.jobsOf(event.value.arr("jobs")))
                    }

                    is StreamEvent.Failure -> Log.w(TAG, "job/list failed: ${event.code} ${event.message}")
                    StreamEvent.End -> Log.d(TAG, "job/list ended for $sessionId")
                }
            }
        }
    }

    /**
     * Records one observation of a session's job list.
     *
     * The finished transition is judged here, against **this session's own**
     * previous list — [jobsBySession] still holds it when the new one is written
     * — never against the list currently published. Comparing against the
     * published list is what made the first roster frame after a switch read as a
     * finish: it was the *other* session's list on the left of the comparison.
     *
     * A session's first observation has no previous list, so it never settles
     * ([JobsMarker.settled]); a session you have never viewed is marked only when
     * a later observation shows it losing its running job.
     */
    private fun observeJobs(sessionId: String, items: List<JobItem>) {
        val previous = jobsBySession.put(sessionId, items).orEmpty()
        finishedUnseenSessions = JobsMarker.observed(finishedUnseenSessions, sessionId, previous, items)
    }

    /**
     * Records one roster frame and republishes when it belongs to the open
     * session. A null session is the pending hero: there is no roster to hold, so
     * its entry is dropped rather than left behind to strand rows.
     */
    private fun recordJobsFrame(sessionId: String?, items: List<JobItem>) {
        if (sessionId == null) {
            val current = _ui.value.currentSessionId
            if (current != null) jobsBySession.remove(current)
        } else {
            observeJobs(sessionId, items)
            // A job that leaves the roster cannot be observed any more; keeping its
            // panel state would let a reopened sheet paint output for a row that is
            // no longer there.
            val live = items.mapTo(HashSet()) { it.id }
            jobObservations.keys.retainAll(live)
        }
        publishJobsForCurrent()
    }

    /**
     * Publishes the open session's list, the observation for whichever of its jobs
     * is expanded, and whether *its* seat carries the finished marker.
     *
     * No open session (the pending hero from deferred creation) publishes an
     * empty list and no dot, so a session switched away from cannot leave its
     * jobs — or its marker — on screen.
     */
    private fun publishJobsForCurrent() {
        val current = _ui.value.currentSessionId
        val items = current?.let { jobsBySession[it] }.orEmpty()
        val unseen = JobsMarker.unseenFor(finishedUnseenSessions, current)
        val observed = observedJobId?.let { jobObservations[it] }
        if (items == _ui.value.jobs &&
            unseen == _ui.value.jobsFinishedUnseen &&
            observed == _ui.value.jobOutput
        ) {
            return
        }
        _ui.value = _ui.value.copy(
            jobs = items,
            jobsFinishedUnseen = unseen,
            jobOutput = observed,
        )
    }

    /**
     * Opening one session's jobs list clears that session's finished marker.
     *
     * Only the viewed session's membership leaves [finishedUnseenSessions];
     * another session's dot survives being passed over.
     */
    fun markJobsSeen() {
        val current = _ui.value.currentSessionId ?: return
        finishedUnseenSessions = JobsMarker.seen(finishedUnseenSessions, current)
        if (_ui.value.jobsFinishedUnseen) publishJobsForCurrent()
    }

    // -------------------------------------------------- job output observation

    /**
     * Starts (or stops) the `job/follow` observation behind the expanded row.
     *
     * Observation follows visibility, as it does in the web: output only flows
     * while someone is watching, and collapsing closes the stream. [jobId] is the
     * single job currently expanded anywhere in the UI — the sheet and a chat-log
     * entry are never open at once, because the sheet is modal, so one stream is
     * enough and a second would only duplicate output.
     *
     * Re-expanding a job resumes from the previous generation's `next` offset, so
     * a collapse-and-reopen does not re-read output already on screen.
     */
    fun observeJob(jobId: String?) {
        if (jobId == observedJobId) return
        observedJobId = jobId
        jobFollowJob?.cancel()
        jobFollowJob = null
        if (jobId == null) {
            publishJobsForCurrent()
            return
        }
        // A fresh expansion with no prior state starts streaming rather than
        // inheriting the failed/settled flags of some earlier generation.
        val previous = jobObservations[jobId]
        jobObservations[jobId] = previous?.copy(streaming = true, error = null)
            ?: JobObservation(jobId = jobId)
        publishJobsForCurrent()
        val sessionId = _ui.value.currentSessionId
        jobFollowJob = viewModelScope.launch {
            jobClient.follow(sessionId, jobId, previous?.cursor).collect { event ->
                when (event) {
                    is StreamEvent.Item -> applyFollowFrame(jobId, event.value)
                    is StreamEvent.Failure -> failJobObservation(jobId, "${event.code} ${event.message}".trim())
                    StreamEvent.End -> endJobObservation(jobId)
                }
            }
        }
    }

    /**
     * One `job/follow` frame.
     *
     * `opened` is the generation anchor; `output` is a coalesced batch; `status` is
     * the terminal projection, which rides the same stream as the output so
     * settlement can never race a still-open channel.
     */
    private fun applyFollowFrame(jobId: String, value: JSONObject) {
        val current = jobObservations[jobId] ?: JobObservation(jobId = jobId)
        when (value.str("type")) {
            "opened" -> {
                val from = value.int("from")
                val earliest = value.obj("job")?.obj("output")?.int("earliest") ?: 0
                jobObservations[jobId] = JobTail.opened(current, jobId, from, earliest)
                    .copy(cursor = from)
            }

            "output" -> {
                val chunks = value.arr("chunks")
                val texts = if (chunks == null) {
                    emptyList()
                } else {
                    (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.str("text") }
                }
                val chunkGap = chunks != null && (0 until chunks.length())
                    .any { chunks.optJSONObject(it)?.bool("gapBefore") == true }
                jobObservations[jobId] = JobTail
                    .append(current, texts, value.optBoolean("lossy", false), chunkGap)
                    .copy(cursor = value.int("next"))
            }

            "status" -> jobObservations[jobId] = JobTail.settled(current)
        }
        if (observedJobId == jobId) publishJobsForCurrent()
    }

    /**
     * The stream ended without a `status` frame.
     *
     * A clean end after `status` is the normal path — the host closes the stream
     * once the ring is drained — so this must not paint an error over a job that
     * simply finished. [JobTail.settled] already cleared `streaming`, and a stream
     * that ends there is expected.
     */
    private fun endJobObservation(jobId: String) {
        val current = jobObservations[jobId] ?: return
        if (current.streaming) jobObservations[jobId] = JobTail.settled(current)
        if (observedJobId == jobId) publishJobsForCurrent()
    }

    private fun failJobObservation(jobId: String, error: String) {
        jobObservations[jobId] = JobTail.failed(jobObservations[jobId], jobId, error)
        if (observedJobId == jobId) publishJobsForCurrent()
    }

    /**
     * The human kill: `job/kill`, then let the roster stream report the outcome.
     *
     * Nothing is painted from this answer. The host admits the request
     * (`requested`) or reports the row already gone (`already-finished`) and the
     * `job/list` frame does the rest, which is what keeps a row from claiming to be
     * cancelled while its process is still running.
     */
    fun killJob(jobId: String) {
        val sessionId = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            runCatching { jobClient.kill(sessionId, jobId) }
                .onFailure { Log.w(TAG, "job/kill $jobId failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------- references

    /** Candidates for the `@` menu, already flattened into display rows. */
    private val _references = MutableStateFlow<List<ReferenceCandidate>>(emptyList())
    val references: StateFlow<List<ReferenceCandidate>> = _references.asStateFlow()
    private var referenceJob: Job? = null

    /** Guards publication; see [searchReferences]. */
    private var referenceGeneration = 0

    /**
     * Searches files and sessions for the `@` menu.
     *
     * The two host lookups run concurrently **and publish separately**. File
     * matching answers in well under a second, but the session resolver takes
     * 25-30s on this machine, and waiting for both before showing anything left
     * the menu blank for as long as a query took to type — which reads as a picker
     * that simply does not work.
     *
     * A generation counter guards publication: whatever order the two calls finish
     * in, a superseded query can never overwrite a newer one.
     */
    fun searchReferences(query: String) {
        val sessionId = _ui.value.currentSessionId ?: return
        referenceJob?.cancel()
        val generation = ++referenceGeneration
        referenceJob = viewModelScope.launch {
            delay(REFERENCE_DEBOUNCE_MS)
            val filesJob = async { fileReferences(sessionId, query) }
            val sessionsJob = async { sessionReferences(sessionId, query) }

            val files = filesJob.await()
            if (generation == referenceGeneration) _references.value = files
            val sessions = sessionsJob.await()
            if (generation == referenceGeneration) _references.value = files + sessions
            Log.d("DshRefs", "query='$query' files=${files.size} sessions=${sessions.size} gen=$generation")
        }
    }

    /**
     * Files and folders matching a reference query.
     *
     * A failure here is logged, not thrown: one unreachable source must not take
     * the other half of the picker down with it.
     */
    private suspend fun fileReferences(sessionId: String, query: String): List<ReferenceCandidate> {
        val value = runCatching {
            client.rpcRaw(
                "fileReferences/list",
                JSONObject().put("agentId", sessionId).put("query", query),
            )
        }.onFailure {
            Log.d("DshRefs", "files failed for '$query': ${it.message}")
            if (it is DshAuthException) onAuthFailure("fileReferences/list", it)
        }.getOrNull()
        val list = value as? JSONArray ?: return emptyList()
        val rows = ArrayList<ReferenceCandidate>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val path = item.str("path")
            if (path.isEmpty()) continue
            val directory = item.str("kind") == "directory"
            rows += ReferenceCandidate(
                kind = if (directory) "directory" else "file",
                // The row shows the name; the parent is context the header does
                // not repeat.
                name = path.substringAfterLast('/') + if (directory) "/" else "",
                description = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() },
                mention = fileMention(path),
            )
        }
        return rows
    }

    /** Sessions whose label matches a reference query. */
    private suspend fun sessionReferences(sessionId: String, query: String): List<ReferenceCandidate> {
        val value = runCatching {
            client.rpcRaw(
                "sessionReferenceResolver/candidates",
                JSONObject().put("agentId", sessionId).put("query", query),
            )
        }.onFailure {
            Log.d("DshRefs", "sessions failed for '$query': ${it.message}")
            if (it is DshAuthException) onAuthFailure("sessionReferenceResolver/candidates", it)
        }.getOrNull()
        val list = value as? JSONArray ?: return emptyList()
        val rows = ArrayList<ReferenceCandidate>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val mention = item.str("mention")
            if (mention.isEmpty()) continue
            rows += ReferenceCandidate(
                kind = "session",
                name = item.str("label").ifEmpty { "Session" },
                description = item.str("cwd").takeIf { it.isNotEmpty() },
                mention = mention,
            )
        }
        return rows
    }

    fun clearReferences() {
        referenceJob?.cancel()
        // A cancelled job can still be between its last suspension and its write,
        // so the generation moves on as well.
        referenceGeneration++
        if (_references.value.isNotEmpty()) _references.value = emptyList()
    }

    /** `@path`, quoted only when the path contains a space. */
    private fun fileMention(path: String): String =
        if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    // ----------------------------------------------------------------- images

    /** Decoded attachments, keyed by `attachmentId`. Small and session-lifetime. */
    private val images = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /**
     * Reads one durable image and decodes it.
     *
     * The host only hands back bytes for an image the session log actually
     * references (`session/attachment` authorises against the journal), which is
     * why this takes the id straight off the message block.
     */
    suspend fun imageBitmap(attachmentId: String): androidx.compose.ui.graphics.ImageBitmap? {
        val sessionId = _ui.value.currentSessionId ?: return null
        images[attachmentId]?.let { return it.asImageBitmap() }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val value = client.rpc(
                    "session/attachment",
                    JSONObject().put(
                        "request",
                        JSONObject().put("sessionId", sessionId).put("attachmentId", attachmentId),
                    ),
                )
                val bytes = Base64.decode(value.str("data"), Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.onFailure { if (it is DshAuthException) onAuthFailure("session/attachment", it) }
                .getOrNull()
        } ?: return null
        images[attachmentId] = decoded
        return decoded.asImageBitmap()
    }

    // --------------------------------------------------------------- sessions

    fun archiveSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    "workspace/archiveSession",
                    JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
                )
            }.onSuccess {
                refreshSessions()
            }.onFailure { error ->
                showFailure(error, "workspace/archiveSession", "Could not archive: ")
            }
        }
    }

    fun unarchiveSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    "workspace/unarchiveSession",
                    JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
                )
            }.onSuccess {
                refreshSessions()
            }.onFailure { error ->
                showFailure(error, "workspace/unarchiveSession", "Could not restore: ")
            }
        }
    }

    /**
     * Forks a session at its last completed turn, the one verb the row menu was
     * missing.
     *
     * The host answers with the new session's id, which is opened here: a fork the
     * user cannot see is indistinguishable from the button doing nothing, and the
     * new session is the only thing they wanted from the tap.
     */
    fun forkSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    "session/fork",
                    JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
                ).str("sessionId")
            }.onSuccess { forked ->
                refreshSessions()
                if (forked.isNotEmpty()) openSession(forked)
            }.onFailure { error ->
                showFailure(error, "session/fork", "Could not fork: ")
            }
        }
    }

    /**
     * Starts (or reuses, or adopts) a blank session inside a registered workspace.
     *
     * The choice itself lives in [SessionTargets.intent] so that this path, the
     * drawer's New Session, the per-workspace `+` and a typed directory all take
     * the same decision. A blank the Workspace already holds is opened, a blank
     * rooted at its path is adopted, and **nothing is created**: the workspace
     * becomes the pending hero's recorded target, and `session/create` waits for
     * first use. Reuse demands *membership*, not just a matching directory: the
     * host only puts a session in a workspace when it was created through
     * `workspaceId`, so a blank sitting at the right cwd but attached to nothing
     * is not the session the user means — the web draws the same distinction. An
     * archived blank is skipped too, or "New Session" would reopen a session the
     * user has just filed away.
     */
    fun startSessionInWorkspace(workspaceId: String) {
        if (workspaceId.isBlank()) return
        val path = _ui.value.workspaces.firstOrNull { it.id == workspaceId }?.path
        startSessionAt(SessionTarget.Workspace(workspaceId, path))
    }

    /**
     * Resolves a [SessionTarget] and performs the one action the intent names.
     *
     * `Adopt` is the idempotent `session/create` with both ids; `Open` opens a
     * session that is already the one meant and sends nothing; `Record` is the
     * branch that used to create — it now **shows the pending hero** and issues no
     * request at all, which is what stops a workspace pick from littering a blank.
     */
    private fun startSessionAt(target: SessionTarget) {
        val plan = SessionTargets.intent(
            target = target,
            preset = newSessionPreset(),
            currentSessionId = _ui.value.currentSessionId,
            candidates = sessionTargetCandidates(),
        )
        when (plan) {
            is SessionIntentPlan.Adopt -> adoptSession(plan.sessionId, plan.workspaceId)
            is SessionIntentPlan.Open -> openSessionCarryingModelPick(plan.sessionId)
            is SessionIntentPlan.Record -> enterPendingSession(plan.pending)
        }
    }

    /**
     * Opens the session a "start a session here" path resolved to, carrying the
     * new-session seat's model pick onto it.
     *
     * `Open` means the host already holds the blank this intent wants — so no create
     * happens, and a pick recorded on the seat would otherwise be discarded in
     * silence: the reader chose a model, then chose where to start, and got neither.
     * Only the paths that come *from* the seat use this; opening a row from the
     * drawer is navigation and drops the pick, which is why [openSession] itself
     * still clears it.
     */
    private fun openSessionCarryingModelPick(sessionId: String) {
        val pick = pendingModelPick()
        openSession(sessionId)
        if (pick != null) viewModelScope.launch { applyPendingModelPick(sessionId, pick) }
    }

    /**
     * Shows the new-session hero over [pending] and creates nothing.
     *
     * Leaving a session this way is deliberately quiet: the session that *was* open
     * is not touched (it exists, and the host has no delete RPC), but its follow
     * stream is cancelled and the reducer reset so the pending hero is not painted
     * with the previous session's transcript. This is the only path that sets
     * [UiState.pendingSession], and it is the whole of "the drawer's `+` no longer
     * leaves an empty session behind".
     */
    private fun enterPendingSession(pending: PendingSessionTarget) {
        if (_ui.value.currentSessionId == null && _ui.value.pendingSession == pending) return
        followJob?.cancel()
        historyJob?.cancel()
        reducer.reset()
        publishTranscript()
        _ui.value = _ui.value.copy(
            currentSessionId = null,
            pendingSession = pending,
            running = false,
            error = null,
            errorNeedsSignIn = false,
            queue = emptyList(),
            queueBusy = null,
            // Uploads are bound to the session they were staged for, so a receipt
            // from the session being left would be refused by the host.
            attachments = emptyList(),
        )
        // The hero's chip is the host's global default plus whatever pick the reader
        // records here; the session being left has no say in it. The pick itself is
        // deliberately kept — this seat exists to hold choices for a session that has
        // not been created, and re-recording its target is not a reason to drop one.
        updateModelSelection { it.onSessionLeft() }
        // The host's global default is what this seat's session runs until a pick is
        // recorded, and it moves when *any* session or client selects — asynchronously,
        // measured at 0.4-2 s on 0.2.0-rc.2. The one catalog read from connect is
        // therefore not enough on this seat: re-read it on the way in.
        viewModelScope.launch { runCatching { loadModels() } }
        // No session is open, so the seat publishes an empty list and no dot. The
        // jobs the session being left had, and its marker, stay keyed to it.
        // The roster stream goes with it: nothing is on screen to keep current, and
        // an expanded panel's output must not outlive the row it belonged to.
        watchJobs(null)
        observeJob(null)
        publishJobsForCurrent()
        app.attention.visibleSessionId = null
        app.attention.visibleWith = emptySet()
    }

    /**
     * Creates (or reuses) the session the pending seat recorded, and opens it.
     *
     * This is **first use**: the one place a deferred [SessionIntentPlan.Record]
     * can turn into a session. Order is load-bearing — the plan is re-resolved
     * against the live roster, the one call it names runs, the seat is cleared, and
     * only then is the session opened, so no caller can observe a pending seat on
     * top of an open session. Returns the session id, or null after surfacing the
     * failure through the banner (the caller must not deliver into a null id).
     */
    private suspend fun materializePending(pending: PendingSessionTarget): String? {
        _ui.value = _ui.value.copy(busy = true, error = null, errorNeedsSignIn = false)
        // Read before anything is created: the pick belongs to the session this call
        // is about to make, and `openSession` below consumes whatever is on the seat.
        val modelPick = pendingModelPick()
        val plan = SessionTargets.materialize(pending, sessionTargetCandidates())
        val id = try {
            when (plan) {
                is SessionTargetPlan.Reuse -> plan.sessionId
                is SessionTargetPlan.Adopt -> adoptSessionNow(plan.sessionId, plan.workspaceId)
                is SessionTargetPlan.Create -> createSessionRequest(plan.cwd, pending.preset, plan.workspaceId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _ui.value = _ui.value.copy(busy = false)
            showFailure(error, "session/create")
            return null
        }
        if (id == null) return null
        // The recorded access mode is applied before anything is sent, because a
        // mode change is refused while a turn is in flight; a refusal is reported
        // but does not lose the send.
        pending.permission?.let { mode ->
            runCatching { runCommandFor(id, "/permission $mode").getOrThrow() }
                .onSuccess { _ui.value = _ui.value.copy(currentPermission = mode) }
                .onFailure { showFailure(it, "commands/execute", "Could not switch access mode: ") }
        }
        // Whatever was typed on the hero belongs to the session it just created: the
        // composer's key changes with the session id, so without this the reader's own
        // words would be filed under the hero's key and never read again — a draft lost
        // by the act of using it.
        draftStore.rename(PENDING_DRAFT_KEY, id)
        _ui.value = _ui.value.copy(busy = false, pendingSession = null)
        openSession(id)
        // After the open, so the session's own projections and the pick do not fight
        // over the chip; the caller sends its prompt only once this returns, so the
        // first turn cannot run before the selection is in force.
        modelPick?.let { applyPendingModelPick(id, it) }
        return id
    }

    /**
     * The roster the reuse decision reads, with Workspace membership joined in.
     *
     * Built fresh at each intent rather than cached: the archived set, membership
     * and the `blank` flag all move under the user, and a stale copy is what makes
     * a decision to reuse (or not) wrong at exactly the moment it matters.
     */
    private fun sessionTargetCandidates(): List<SessionTargetCandidate> {
        val ui = _ui.value
        return ui.sessions.map { session ->
            SessionTargetCandidate(
                id = session.id,
                cwd = session.cwd,
                blank = session.blank,
                isSubagent = session.isSubagent,
                archived = session.id in ui.archivedSessionIds,
                workspaceIds = ui.workspaces.filter { session.id in it.sessionIds }
                    .mapTo(mutableSetOf()) { it.id },
            )
        }
    }

    /** The stored new-session preset, or null when the host should pick its default. */
    private fun newSessionPreset(): String? = _ui.value.agentPreset.takeIf { it.isNotBlank() }

    /**
     * `session/create` with both ids: the host's idempotent adopt.
     *
     * No `agentPreset` travels with it. The session already exists, so its preset
     * is settled and a differing request would come back `agent-preset/conflict`
     * — the preset is a choice about a *new* session, and this call is not making one.
     *
     * A `session/conflict` means the roster lied about the directory (a stale cwd
     * string), not that the user's pick was wrong, so it falls back to the create
     * the plan would have chosen; the pick still lands, it just costs the blank
     * the roster promised could be adopted.
     */
    private fun adoptSession(sessionId: String, workspaceId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, errorNeedsSignIn = false)
            val modelPick = pendingModelPick()
            runCatching { adoptSessionNow(sessionId, workspaceId) }
                .onSuccess { id ->
                    _ui.value = _ui.value.copy(busy = false)
                    if (id != null) {
                        openSession(id)
                        // The seat's pick belongs to the session the reader ends up
                        // in, whether the host created one or adopted the blank the
                        // Workspace already held.
                        modelPick?.let { applyPendingModelPick(id, it) }
                    }
                }
                .onFailure { error ->
                    // A `session/conflict` never reaches here: [adoptSessionNow]
                    // turns it into the create the plan would have chosen.
                    _ui.value = _ui.value.copy(busy = false)
                    showFailure(error, "session/create")
                }
        }
    }

    /**
     * The adopt call itself, suspending so [materializePending] can await it.
     *
     * A `session/conflict` means the roster lied about the directory — the adopt
     * was refused before the workspace attach — so it falls back to the create the
     * plan would have chosen; the pick still lands, it just costs the blank the
     * roster promised could be adopted.
     */
    private suspend fun adoptSessionNow(sessionId: String, workspaceId: String): String? {
        val request = JSONObject().put("sessionId", sessionId).put("workspaceId", workspaceId)
        return try {
            val value = client.rpc("session/create", JSONObject().put("request", request))
            val id = value.optString("sessionId").ifEmpty { sessionId }
            refreshSessions()
            id
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: DshRpcException) {
            if (error.code != SESSION_CONFLICT) throw error
            refreshSessions()
            createSessionRequest(null, newSessionPreset(), workspaceId)
        }
    }

    /**
     * The Workspace registry, which is what the session list groups by.
     *
     * Streamed rather than fetched: membership and order change when a session is
     * adopted into a directory or archived, and the host pushes a fresh baseline
     * plus ordered increments. Grouping from this (instead of deriving buckets
     * from `cwd`) keeps the app consistent with what the web UI shows.
     */
    private fun ensureWorkspaceStream() {
        if (workspacesJob?.isActive == true) return
        workspacesJob = viewModelScope.launch {
            client.mux().openStream("workspace/follow", JSONObject()).collect { event ->
                if (event is StreamEvent.Failure) {
                    // The registry stream used to swallow its errors entirely: a
                    // dead one left the sidebar short of workspaces with no hint
                    // that anything was wrong.
                    Log.w(TAG, "workspace stream failed: ${event.code} ${event.message}")
                    if (isAuthStreamFailure(event.code, event.message)) {
                        onAuthFailure("workspace/follow", null)
                    }
                    return@collect
                }
                if (event !is StreamEvent.Item) return@collect
                val value = event.value
                when (value.str("type")) {
                    "baseline" -> {
                        val payload = value.obj("value") ?: JSONObject()
                        val items = payload.arr("items") ?: JSONArray()
                        val archived = toStringSet(payload.arr("archivedSessionIds"))
                        // The line a resume re-sync is judged by: the sidebar's
                        // archived filter is only ever refreshed by a baseline, so
                        // seeing a new one after a resume proves the stream really
                        // re-subscribed (and not just that a coroutine is "active").
                        Log.d(SIDEBAR_TAG, "workspace baseline: ${items.length()} workspaces, ${archived.size} archived")
                        _ui.value = _ui.value.copy(
                            workspaces = (0 until items.length())
                                .mapNotNull { items.optJSONObject(it)?.let(::toWorkspace) },
                            archivedSessionIds = archived,
                        )
                    }

                    "upsert" -> {
                        val workspace = value.obj("workspace")?.let(::toWorkspace) ?: return@collect
                        val current = _ui.value.workspaces.toMutableList()
                        val at = current.indexOfFirst { it.id == workspace.id }
                        if (at >= 0) current[at] = workspace else current += workspace
                        _ui.value = _ui.value.copy(workspaces = current)
                    }

                    "remove" -> {
                        val id = value.str("workspaceId")
                        _ui.value = _ui.value.copy(workspaces = _ui.value.workspaces.filterNot { it.id == id })
                    }

                    "order" -> {
                        val order = value.arr("workspaceIds") ?: return@collect
                        val rank = (0 until order.length()).associate { order.optString(it) to it }
                        _ui.value = _ui.value.copy(
                            workspaces = _ui.value.workspaces.sortedBy { rank[it.id] ?: Int.MAX_VALUE },
                        )
                    }

                    "archived" -> _ui.value = _ui.value.copy(
                        archivedSessionIds = toStringSet(value.arr("archivedSessionIds")),
                    )
                }
            }
        }
    }

    private fun toWorkspace(json: JSONObject) = WorkspaceItem(
        id = json.str("workspaceId"),
        path = json.str("path"),
        title = json.str("title").ifEmpty { json.str("path").trimEnd('/').substringAfterLast('/') },
        sessionIds = toStringSet(json.arr("sessionIds")).toList(),
    )

    /** Order-preserving: a Workspace's `sessionIds` is its manual display order. */
    private fun toStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val result = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotEmpty() }?.let { result += it }
        }
        return result
    }

    // -------------------------------------------------------- directory picker

    /**
     * Lists one directory level for the browser (`directoryPicker/list`).
     *
     * The verb takes its `path` at the *top level* of the args object, and an
     * absent path means the host account's home — so the empty object is a real
     * request, not a malformed one. A superseded scan is dropped by generation
     * rather than cancelled on the wire, because the host scan is cheap and the
     * browser only ever needs the newest answer.
     */
    fun listDirectory(path: String? = null) {
        val generation = ++directoryGeneration
        _directoryLoading.value = true
        _directoryError.value = null
        viewModelScope.launch {
            runCatching {
                val args = JSONObject()
                if (path != null) args.put("path", path)
                toDirectoryLevel(client.rpc("directoryPicker/list", args))
            }.onSuccess { level ->
                if (generation != directoryGeneration) return@onSuccess
                _directoryLevel.value = level
                _directoryLoading.value = false
            }.onFailure { error ->
                if (generation != directoryGeneration) return@onFailure
                _directoryLoading.value = false
                if (error is DshAuthException) {
                    onAuthFailure("directoryPicker/list", error)
                } else {
                    _directoryError.value = describe(error)
                }
            }
        }
    }

    /**
     * Creates one child directory and reports the created absolute path.
     *
     * `createDirectory` answers with a bare path string, which [rpc] cannot carry
     * (it reads `value` as an object), so this goes through `rpcRaw`.
     */
    fun createDirectory(path: String, name: String, onCreated: (String) -> Unit = {}) {
        _directoryBusy.value = true
        viewModelScope.launch {
            runCatching {
                val result = client.rpcRaw(
                    "directoryPicker/createDirectory",
                    JSONObject().put("path", path).put("name", name),
                )
                result as? String ?: error("The host did not return the created path.")
            }.onSuccess { created ->
                _directoryBusy.value = false
                onCreated(created)
            }.onFailure { error ->
                _directoryBusy.value = false
                if (error is DshAuthException) {
                    onAuthFailure("directoryPicker/createDirectory", error)
                } else {
                    _directoryError.value = describe(error)
                }
            }
        }
    }

    /**
     * Registers one directory as a Workspace (`workspace/create`), then refreshes
     * the sidebar.
     *
     * Unlike the directory-picker verbs, `workspace/create` takes a single
     * `request` object on the wire, and it is idempotent: an existing Workspace
     * over the same path answers with `created = false` instead of failing, so a
     * re-pick is safe.
     */
    fun addWorkspace(path: String, onAdded: (String) -> Unit = {}) {
        _directoryBusy.value = true
        viewModelScope.launch {
            runCatching {
                client.rpc("workspace/create", JSONObject().put("request", JSONObject().put("path", path)))
            }.onSuccess { value ->
                _directoryBusy.value = false
                // The sidebar's list is owned by the `workspace/follow` stream, but
                // the upsert can land a frame later; refresh so the new group is
                // there before the drawer is reopened.
                refreshSessions()
                onAdded(value.obj("workspace")?.str("workspaceId").orEmpty())
            }.onFailure { error ->
                _directoryBusy.value = false
                if (error is DshAuthException) {
                    onAuthFailure("workspace/create", error)
                } else {
                    _directoryError.value = describe(error)
                }
            }
        }
    }

    /** Clears the browser's own error text, e.g. when the dialog is reopened. */
    fun clearDirectoryError() {
        _directoryError.value = null
    }

    /** Forgets the listed level so a reopened browser does not show a stale one. */
    fun resetDirectoryBrowser() {
        directoryGeneration++
        _directoryLevel.value = null
        _directoryLoading.value = false
        _directoryError.value = null
        _directoryBusy.value = false
    }

    private fun toDirectoryLevel(json: JSONObject) = DirectoryLevel(
        path = json.str("path"),
        home = json.str("home"),
        crumbs = toDirectoryEntries(json.arr("crumbs")),
        entries = toDirectoryEntries(json.arr("entries")),
        truncated = json.optBoolean("truncated", false),
    )

    private fun toDirectoryEntries(array: JSONArray?): List<DirectoryEntry> {
        if (array == null) return emptyList()
        val result = ArrayList<DirectoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.str("path")
            if (path.isEmpty()) continue
            result += DirectoryEntry(
                name = item.str("name").ifEmpty { path.trimEnd('/').substringAfterLast('/') },
                path = path,
                hidden = item.optBoolean("hidden", false),
            )
        }
        return result
    }

    private suspend fun fetchSessions(): List<SessionItem> {
        // Taken *before* the request goes out, so a live frame that lands while this
        // read is in flight is stamped as possibly newer than the cut it answers — the
        // roster read carries `running` and `subagentTiming` as of the host's own
        // moment, which the answer may predate. See [RunningBook].
        val pull = runningBook.beginPull()
        val value = client.rpc("session/list", JSONObject().put("_request", JSONObject()))
        val items = value.optJSONArray("items") ?: JSONArray()
        // A subagent's own stored title is its opening prompt ("You are ..."), which
        // the web UI shows only as a subtitle. The real title — and the `mode` its
        // address needs — live in the *parent's* `subagentCatalog` projection.
        val catalog = HashMap<String, Pair<String, String>>()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val list = entry.obj("projections")?.obj("values").arr("subagentCatalog") ?: continue
            for (k in 0 until list.length()) {
                val child = list.optJSONObject(k) ?: continue
                // The *projection* entry keys the child as `id`; only the `subagent/catalog`
                // event uses `childId`, and assuming that name here silently matched nothing.
                val childId = child.str("id")
                if (childId.isNotEmpty()) catalog[childId] = child.str("label") to child.str("mode")
            }
        }

        val result = ArrayList<SessionItem>(items.length())
        // The durable answer this pull proves, per id: the host's own agent-status
        // sample — see [durableRunningOf] for why `subagentTiming.active` is *not* part
        // of it. `wire`, `active` and `noAgent` are kept for the diag record only
        // (`noteRunningPull`), so its arithmetic can be checked rather than trusted.
        val durable = HashMap<String, Boolean>(items.length())
        val wire = HashMap<String, Boolean>(items.length())
        val active = HashSet<String>(items.length() / 8)
        val noAgent = HashSet<String>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.str("sessionId")
            if (id.isEmpty()) continue
            durable[id] = durableRunningOf(item)
            wire[id] = item.optBoolean("running")
            // An explicit `false` only: a row that omits the field is not evidence of a
            // missing agent, and the orphan count is a diagnosis, not a decision.
            if (item.has("agentAvailable") && !item.optBoolean("agentAvailable")) noAgent += id
            if (item.obj("projections")?.obj("values")?.obj("subagentTiming")?.obj("active") != null) {
                active += id
            }
        }
        // Applied before the rows are built, so `withRunning` below reads this cut and
        // not the previous one.
        runningBook.applyPull(pull, durable)
        noteRunningPull(wire, active, noAgent)

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val projections = item.obj("projections")?.obj("values")
            // Always read through the Json extensions: org.json's raw optString
            // turns an explicit JSON null into the string "null".
            val blank = item.optBoolean("blank")
            val stored = projections.str("title")
            val id = item.str("sessionId")
            val cwd = item.str("cwd").takeIf { it.isNotEmpty() }
            val subagent = catalog[id]
            val parentSessionId = item.str("parentSessionId").takeIf { it.isNotEmpty() }
            // Where a catalog this client already holds names this child, its row
            // wins the label and the mode: see [applyCatalogLabels] for why the
            // read-time sample outranks the projection.
            val held = parentSessionId?.let { subagentCatalogs[it]?.child(id) }
            result += SessionItem(
                id = id,
                // Blank rows carry `title: null`; the web substitutes its
                // localized "New Session" label for them. For the rest this is the
                // web's own `displayTitleOf` (session-controller's
                // `client/sessions/service.ts`): the durable title, else the project
                // directory's basename, else the session id. Without the middle step
                // every session the host never titled — anything created by a script,
                // an automation or a fork — read as "Untitled session" in the drawer
                // while the web named it after its directory.
                //
                // The rule itself lives in [sessionRowTitle] so it can be tested
                // without a ViewModel; the order there is the point (a stored name
                // outranks the blank placeholder).
                title = sessionRowTitle(
                    subagentLabel = subagent?.first,
                    stored = stored,
                    blank = blank,
                    directoryName = basename(cwd),
                    id = id,
                ),
                cwd = cwd,
                updatedAt = item.optLong("updatedAt"),
                // The raw wire sample is kept only as `fetchSessions`'s own record of
                // what the host said; every row the UI is handed has already been
                // folded through the book by [mergeRoster].
                running = item.optBoolean("running"),
                isSubagent = item.str("origin") == "subagent",
                blank = blank,
                parentSessionId = parentSessionId,
                subagentLabel = subagent?.first?.takeIf { it.isNotBlank() },
                subagentMode = subagent?.second?.takeIf { it.isNotBlank() },
            ).withCatalog(held)
        }
        // Keep the open session's folded projections fresh: goal, access mode,
        // plan and context pressure all ride them.
        val currentId = _ui.value.currentSessionId
        if (currentId != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("sessionId") == currentId) {
                    applySessionProjections(item.obj("projections")?.obj("values"))
                    break
                }
            }
        }
        refreshCatalogsForRoster(result)
        return result.sortedByDescending { it.updatedAt }
    }

    /**
     * Re-reads the catalogs a roster pull just showed to have changed.
     *
     * The host pushes `session/added` / `session/removed` to the web client, which
     * is what re-reads the affected parent's catalog there (`manager.ts:708-760`).
     * This client has no such push: its roster arrives whole, so a child that is
     * new, gone, or re-parented *since the previous pull* is the same signal —
     * otherwise a session spawned between two pulls stayed absent from the host's
     * catalog view of its parent until something unrelated happened to read it.
     *
     * Only parents something is actually watching are re-read — see
     * [watchedCatalogParents]: a parent nobody is looking at has no reader to be
     * wrong for. The diff is against the last pull rather than against the
     * catalog, so a child that is missing from both is not a change and cannot
     * drive a read loop.
     */
    private fun refreshCatalogsForRoster(list: List<SessionItem>) {
        val previous = rosterSubagents
        val next = list.filter { it.isSubagent }.associate { it.id to it.parentSessionId }
        rosterSubagents = next
        if (previous == next) return
        val watched = watchedCatalogParents()
        val touched = HashSet<String>()
        for ((id, parent) in next) {
            if (parent != null && previous[id] != parent) touched.add(parent)
        }
        for ((id, parent) in previous) {
            if (parent != null && id !in next) touched.add(parent)
        }
        for (parent in touched) if (parent in watched) refreshSubagentCatalogSoon(parent)
    }

    /**
     * The parents whose catalog is worth re-reading: the one on screen, the parent
     * of the child on screen, and every open disclosure — the web's exact watch set
     * (`manager.ts:723`: `selected === summary.parentSessionId ||
     * openCatalogs.has(summary.parentSessionId)`).
     */
    private fun watchedCatalogParents(): Set<String> {
        val watched = synchronized(subagentCatalogLock) { HashSet(subagentCatalogOpen) }
        val open = _ui.value.currentSessionId ?: return watched
        watched.add(open)
        addressedChild(open)?.parentSessionId?.let { watched.add(it) }
        return watched
    }

    /**
     * Re-reads every watched catalog, the app's half of `handleConnected`
     * (`manager.ts:789-799`).
     *
     * This is about *identity*, not activity: the roster pull that precedes it is what
     * carries running state now (see [RunningBook]), but the label and mode a disclosure
     * names its rows with come from the projection the follow snapshot re-delivers, so a
     * resume repaints them from here rather than waiting for a frame that may never come.
     */
    private fun refreshWatchedCatalogs() {
        for (parent in watchedCatalogParents()) refreshSubagentCatalog(parent)
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(sessionsLoading = true)
            val sessions = runCatching { fetchSessions() }
            sessions.onSuccess { list ->
                _ui.value = _ui.value.copy(
                    sessions = mergeRoster(list),
                    sessionsLoading = false,
                )
                warnOnMissingMembers(list)
                publishRosterRunning()
            }.onFailure { error ->
                _ui.value = _ui.value.copy(sessionsLoading = false)
                if (error is DshAuthException) onAuthFailure("session/list", error)
            }
        }
    }

    /**
     * The sidebar's Refresh control, which has to be worth a tap.
     *
     * Re-pulling `session/list` alone is invisible here: the sidebar is normally
     * kept current by the streams, so the list comes back identical, the screen
     * does not change and the button reads as broken. What a user actually wants
     * from it is "start following again" — the failure mode it exists for is a
     * collector that is still *active* on a socket that has gone quiet, which no
     * amount of re-fetching detects. So this re-dials the multiplexer, re-subscribes
     * every stream (the same reopening the app does when it comes back to the
     * foreground) and pulls the list, holding the spin for a beat so the work is
     * visible either way.
     */
    fun refreshSidebar() {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _ui.value = _ui.value.copy(sessionsLoading = true)
            runCatching { client.mux().ensureConnected() }
                .onFailure { Log.w(TAG, "refresh could not re-dial: ${it.message}") }
            reopenStreamsForResume()
            val sessions = runCatching { fetchSessions() }
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < REFRESH_MIN_SPIN_MS) delay(REFRESH_MIN_SPIN_MS - elapsed)
            sessions.onSuccess { list ->
                _ui.value = _ui.value.copy(
                    sessions = mergeRoster(list),
                    sessionsLoading = false,
                )
                warnOnMissingMembers(list)
                publishRosterRunning()
            }.onFailure { error ->
                _ui.value = _ui.value.copy(sessionsLoading = false)
                // The pull is a direct user action, so a genuine failure says so
                // rather than leaving the tap with no consequence at all.
                if (error is DshAuthException) onAuthFailure("session/list", error)
                else showFailure(error, "session/list", "Could not refresh: ")
            }
        }
    }

    /**
     * Content search behind the drawer's query box (`session/search`).
     *
     * Debounced like the web's, because the host runs one indexed scan across
     * every visible session and the box would otherwise fire per keystroke. Each
     * answer is checked against the query it was asked for: a superseded page must
     * never overwrite a newer one, which is what "the drawer shows hits for a
     * query I already deleted" looks like from outside.
     *
     * A host without a content index fails every call the same way. That is not a
     * transient error to paint over the search box on each keystroke — it is a
     * deployment fact — so it is latched and the RPC stops being sent, leaving the
     * local name filter in charge. Every other failure is transient and does reach
     * the reader, with the web's own "showing name matches" copy.
     */
    fun searchSessionContent(query: String) {
        val normalized = SessionSearch.normalizeQuery(query)
        if (!SessionSearch.isSearchable(normalized) || !contentSearchAvailable) {
            sessionSearchJob?.cancel()
            sessionSearchQuery = null
            _ui.value = _ui.value.copy(
                sessionSearchHits = emptyList(),
                sessionSearchLoading = false,
                sessionSearchError = null,
                sessionSearchHasMore = false,
            )
            return
        }
        sessionSearchQuery = normalized
        _ui.value = _ui.value.copy(
            sessionSearchHits = emptyList(),
            sessionSearchLoading = true,
            sessionSearchError = null,
            sessionSearchHasMore = false,
        )
        sessionSearchJob?.cancel()
        sessionSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val answer = runCatching { client.rpc("session/search", SessionSearch.args(normalized)) }
            if (sessionSearchQuery != normalized) return@launch
            answer.onSuccess { value ->
                val results = parseSessionSearchResults(value)
                _ui.value = _ui.value.copy(
                    sessionSearchHits = results.items,
                    sessionSearchLoading = false,
                    sessionSearchHasMore = results.hasMore,
                )
            }.onFailure { error ->
                if (error is DshAuthException) onAuthFailure("session/search", error)
                if (isContentSearchDisabled(error)) {
                    contentSearchAvailable = false
                    Log.i(SIDEBAR_TAG, "content search is off on this deployment: ${describe(error)}")
                }
                _ui.value = _ui.value.copy(
                    sessionSearchHits = emptyList(),
                    sessionSearchLoading = false,
                    sessionSearchError = if (contentSearchAvailable) "" else null,
                )
            }
        }
    }

    /**
     * Whether the host is telling us it has no content index at all, rather than
     * that this one search failed.
     *
     * Both arrive as `gateway/internal`, so the wording is the only signal the
     * protocol offers: `list.ts` throws "session search is unavailable: this
     * deployment does not mount …" when the provider is missing and the provider
     * itself throws "session search is disabled: this deployment configures the
     * session-query index with openAt \"never\"" — both of which are permanent for
     * the deployment the app is pointed at.
     */
    private fun isContentSearchDisabled(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("session search is unavailable") ||
            message.contains("session search is disabled")
    }

    /** Trailing-edge debounce so a step-heavy turn does not hammer `session/list`. */
    private fun refreshSessionsSoon() {
        synchronized(refreshLock) {
            if (pendingRefresh) return
            pendingRefresh = true
        }
        viewModelScope.launch {
            delay(500)
            synchronized(refreshLock) { pendingRefresh = false }
            val sessions = runCatching { fetchSessions() }
            sessions.onFailure { error ->
                if (error is DshAuthException) onAuthFailure("session/list", error)
            }.onSuccess { list ->
                _ui.value = _ui.value.copy(sessions = mergeRoster(list))
                warnOnMissingMembers(list)
                publishRosterRunning()
            }
        }
    }

    /**
     * An accounted member missing from a whole-world list pull is the shape of the
     * sidebar's vanishing group: the drawer groups by Workspace membership, so a
     * member that never arrives leaves its group with nothing to draw. The pull is
     * the authority and normally has it, so this only ever fires on a real
     * divergence — where knowing which id vanished is the whole diagnosis.
     */
    private fun warnOnMissingMembers(list: List<SessionItem>) {
        val known = list.mapTo(HashSet()) { it.id }
        val missing = _ui.value.workspaces.flatMap { it.sessionIds }.filterNot { it in known }
        if (missing.isNotEmpty()) Log.w(SIDEBAR_TAG, "session/list omitted workspace member(s): $missing")
    }

    /**
     * Applies one live `api-session/status` frame.
     *
     * The frame is handed to [runningBook], which owns the ordering rule that decides
     * whether it outranks the roster cut already held — the frame itself is never
     * applied to a row directly, because a second path that patches `running` is a
     * second answer to the same question. This host emits the frame only on an
     * `agent/status` *transition*, so it can never be the whole truth: a client that
     * connects while a child is already running sees no "started" frame at all, and
     * the child's own journal (via the roster's `subagentTiming`) is what covers that.
     */
    private fun applyLiveStatus(sessionId: String, running: Boolean) {
        // In the diag file rather than logcat: the test phone's ROM hides third-party
        // app logs, and "did the frame arrive at all" is the first question worth
        // answering about a signal that is transition-only.
        ScrollDiag.note("livestatus", "id" to sessionId, "running" to running)
        runningBook.frame(sessionId, running)
        publishRunning(sessionId)
    }

    /**
     * Republishes one row from the book, and nothing else.
     *
     * The single write path for `SessionItem.running` outside [mergeRoster]: the new
     * value is read back from [runningBook] rather than taken from the event that
     * prompted the repaint, so the row can only ever carry the authority's answer.
     */
    private fun publishRunning(sessionId: String) {
        val sessions = _ui.value.sessions
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val running = runningBook.running(sessionId)
        if (sessions[index].running == running) return
        // A row's flag changing is the whole subject of this fix, so it is recorded
        // with its cause: which id, which answer, and whether it is a subagent.
        ScrollDiag.note(
            "running",
            "id" to sessionId,
            "running" to running,
            "subagent" to sessions[index].isSubagent,
            "via" to "frame",
        )
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(running = running)
        _ui.value = _ui.value.copy(sessions = updated)
    }

    /**
     * Publishes everything that follows from a roster pull: the "finished while you
     * were not looking" dots, and the composer's own running flag.
     *
     * The rows have already been folded through the book by [mergeRoster], so this
     * reads its answer off them rather than asking a second time — one authority, one
     * question. [list] is not consulted for running at all; it is the raw sample, kept
     * only so a caller can name what vanished.
     *
     * The composer's `running` is a *different* fact from a row's dot: it is this
     * client's own turn on the session on screen, which a send sets optimistically and
     * which the follow stream settles. Its value is still taken from the row here, so
     * the two cannot contradict each other, and the quiet guard only delays believing a
     * "no" that races the host's own bookkeeping.
     */
    private fun publishRosterRunning() {
        val sessions = _ui.value.sessions
        val nowRunning = sessions.filter { it.running }.map { it.id }.toSet()

        // A green "done" dot means "finished running while you were not looking":
        // a session that stopped since the last refresh, which is not the open one.
        // Opening a row clears it, exactly like the web client.
        val justFinished = previousRunning - nowRunning
        if (justFinished.isNotEmpty()) {
            val current = _ui.value.currentSessionId
            _ui.value = _ui.value.copy(
                completedSessionIds = (_ui.value.completedSessionIds + justFinished) - setOfNotNull(current),
            )
            // A session that stopped is the one thing this client can tell you
            // about that the web UI cannot: you may have walked away mid-turn.
            justFinished.forEach { sessionId ->
                // Neither a subagent nor an automation run is worth a "Done" buzz:
                // nobody is waiting on either. Its row still takes the green "done"
                // dot, which is the quiet half of the same signal, and both still
                // speak up when something is stuck or has failed.
                if (!warrantsIdleAlert(sessionId, app.attention.isSubagent(sessionId))) {
                    return@forEach
                }
                val title = sessions.firstOrNull { it.id == sessionId }?.title
                alert(
                    id = Attention.idleId(sessionId),
                    sessionId = sessionId,
                    title = if (title.isNullOrBlank()) "Session is idle" else "Done: $title",
                    text = "The agent finished its turn. Tap to open the session.",
                )
            }
        }
        previousRunning = nowRunning

        val current = _ui.value.currentSessionId ?: return
        val item = sessions.firstOrNull { it.id == current } ?: return
        // Only believe "not running" once the stream has been quiet briefly,
        // otherwise a list fetch that races the host's own bookkeeping would
        // flicker the stop button back to send mid-turn.
        val quiet = System.currentTimeMillis() - lastEventAt > LIVE_QUIET_MS
        _ui.value = _ui.value.copy(running = item.running || (!quiet && _ui.value.running))
    }

    /**
     * The addressed child one session is, or null for a plain session.
     *
     * The test is the session list's own — a durable parent id plus the catalog
     * mode — so a row that the app can address at all is addressed the same way
     * by the follow stream, the pager, a continuation prompt and an interrupt.
     * Without the mode the child is not addressable on the wire, so it keeps the
     * plain-session path it has always had.
     */
    private fun addressedChild(sessionId: String): SubagentTarget? {
        val selected = _ui.value.sessions.firstOrNull { it.id == sessionId } ?: return null
        return subagentTargetOf(sessionId, selected.parentSessionId, selected.subagentMode)
    }

    /**
     * One session's address.
     *
     * `SessionAddress` is a union: a subagent must be addressed with its parent and
     * mode, not as a plain session, or the stream opens nothing — which is why
     * tapping a subagent row appeared to do nothing. Both the follow stream and the
     * history pager need this, so it lives in one place.
     */
    private fun sessionAddress(sessionId: String): JSONObject {
        val child = addressedChild(sessionId)
        return if (child != null) {
            JSONObject()
                .put("kind", "subagent")
                .put("parentSessionId", child.parentSessionId)
                .put("childSessionId", child.childSessionId)
                .put("mode", child.mode)
        } else {
            JSONObject().put("kind", "session").put("sessionId", sessionId)
        }
    }

    /**
     * Marks whether one parent's catalog is being read by a disclosure that is
     * actually open — the app's `setSubagentCatalogOpen` (`manager.ts:433-446`).
     *
     * Opening is an immediate read rather than a debounced one: the reader is
     * about to look at the rows, and a beat of staleness there is the one place it
     * is visible. Closing drops any pull the opening still owed, so a sheet
     * dismissed inside the debounce window does not fire a read nobody wants.
     */
    fun setSubagentCatalogOpen(parentSessionId: String, open: Boolean) {
        if (open) {
            synchronized(subagentCatalogLock) { subagentCatalogOpen.add(parentSessionId) }
            refreshSubagentCatalog(parentSessionId)
            return
        }
        synchronized(subagentCatalogLock) { subagentCatalogOpen.remove(parentSessionId) }
        synchronized(subagentCatalogLock) { subagentCatalogDebounce.remove(parentSessionId) }?.cancel()
    }

    /**
     * One parent-catalog rebuild, single-flight per parent.
     *
     * A second ask while one is in flight is not a second call: the response the
     * caller is waiting on predates whatever asked again, so the ask is recorded
     * and answered by exactly one trailing pull once that response lands — the
     * web's `catalogStale` re-arm (`manager.ts:412-419`). Without it the only
     * carrier of a membership change observed mid-flight would be the next
     * unrelated frame.
     *
     * A catalog is identity only — the label and the mode — so it is folded into two
     * places: the roster's label/mode for the rows it names, and, when the parent still
     * belongs to the session on screen, `parentAvailable`, the read-only gate's only
     * input. It is deliberately **not** folded into running state: the projection has
     * no activity field, and the `subagents/list` reply that once carried one is not a
     * host method on 0.2.0. See [RunningBook] for where a child's activity comes from.
     * A failure is deliberately left as unknown (it is not the reader's problem, and
     * unknown keeps the composer), except for a dead cookie, which the banner has to say.
     */
    /**
     * Rebuilds one parent's child catalog from the `subagentCatalog` projection.
     *
     * This used to call `subagents/list`, which 0.2.0 does not serve — every session
     * open paid for a round trip that came back `http/404`, so the catalog was empty
     * and the lineage sheet had nothing to name its rows with. The host pushes the
     * same facts as a projection on the parent now, and the app already receives it.
     *
     * Only the open session's projections are kept (`liveProjections`), so a catalog
     * for any other parent is not known here. That is not a loss: the roster still
     * names those children, and a catalog is only ever read through the tree of the
     * session on screen.
     */
    private fun refreshSubagentCatalog(parentSessionId: String) {
        if (parentSessionId.isEmpty()) return
        if (parentSessionId != _ui.value.currentSessionId) return
        val catalog = parseSubagentCatalogProjection(
            liveProjections?.optJSONArray("subagentCatalog"),
        ) { id ->
            _ui.value.sessions.any { it.parentSessionId == id }
        }
        subagentCatalogs[parentSessionId] = catalog
        _ui.value = _ui.value.copy(subagentCatalogs = HashMap(subagentCatalogs))
        // Recorded because this rebuild is what used to erase running state: it folded
        // the projection's absent `activity` in as "not running" and wiped every child's
        // row. A device reading the diag can now see a rebuild land between two roster
        // summaries and check that no child moved.
        ScrollDiag.note(
            "catalog",
            "parent" to parentSessionId,
            "children" to catalog.children.size,
        )
        applyCatalogLabels(parentSessionId, catalog)
        publishParentAvailable(parentSessionId, catalog)
    }

    /**
     * A trailing-edge debounce in front of [refreshSubagentCatalog], for triggers
     * that arrive in bursts rather than from one deliberate disclosure.
     */
    private fun refreshSubagentCatalogSoon(parentSessionId: String) {
        if (parentSessionId.isEmpty()) return
        synchronized(subagentCatalogLock) {
            if (subagentCatalogDebounce.containsKey(parentSessionId)) return
            // The job is registered before the body can remove it: a `delay` is
            // this coroutine's first suspension, so the body cannot reach its own
            // cleanup between `launch` and the put below.
            subagentCatalogDebounce[parentSessionId] = viewModelScope.launch {
                delay(CATALOG_DEBOUNCE_MS)
                synchronized(subagentCatalogLock) { subagentCatalogDebounce.remove(parentSessionId) }
                refreshSubagentCatalog(parentSessionId)
            }
        }
    }

    /**
     * Overlays one catalog's label and mode onto the roster rows it names.
     *
     * Both ends carry the same durable descriptor, so they can only differ when
     * one of them is behind. Where they disagree the catalog wins: it is the host
     * re-sampling the child's descriptor at read time, while the projection
     * arrives inside a `session/list` snapshot whose cursor can be older — and the
     * web's row reads its mode from exactly this reply (`SubagentHeaderLineage.tsx`
     * `entry.mode`, which `selectSubagent` then validates the address against,
     * `manager.ts:196-203`). The projection stays the baseline because it covers
     * every child in one pull, including the many whose parent catalog this client
     * has never read.
     */
    private fun applyCatalogLabels(parentSessionId: String, catalog: SubagentCatalog) {
        val sessions = _ui.value.sessions
        val patched = sessions.toMutableList()
        var changed = false
        for (child in catalog.children) {
            val index = patched.indexOfFirst { it.id == child.id && it.parentSessionId == parentSessionId }
            if (index < 0) continue
            val row = patched[index].withCatalog(child)
            if (row == patched[index]) continue
            patched[index] = row
            changed = true
        }
        if (changed) _ui.value = _ui.value.copy(sessions = patched)
    }

    /**
     * One catalog's `parentAvailable` for the session on screen.
     *
     * The read is per parent, so the answer belongs to whichever child of that
     * parent is open *now* — not to the child whose open started it, which the user
     * may have left for a sibling under the same parent. A response whose parent is
     * not the open session's is dropped rather than claimed: that is how the
     * previous session's answer used to be able to decide this session's composer.
     *
     * Read off the roster rather than through `addressedChild`: whose answer this is
     * depends on the parent id alone, and requiring the catalog mode first would
     * throw away an answer the gate can already use once that mode lands.
     */
    private fun publishParentAvailable(parentSessionId: String, catalog: SubagentCatalog) {
        val open = _ui.value.currentSessionId ?: return
        if (_ui.value.sessions.firstOrNull { it.id == open }?.parentSessionId != parentSessionId) return
        _ui.value = _ui.value.copy(subagentParentAvailable = catalog.parentAvailable)
    }

    /**
     * Pulls one older page of the open session's history.
     *
     * `session/follow` opens a **bounded** window, so without this the transcript
     * simply stops when you scroll to the top — on a long session most of its history
     * is unreachable, which is the whole point of reading it here. `session/page`
     * wants the inclusive cut the window was opened at (`throughSeq`) plus the oldest
     * seq held (`beforeSeq`), and answers with `hasMore` so the caller knows when to
     * stop asking.
     */
    fun loadOlderHistory() {
        val sessionId = _ui.value.currentSessionId ?: return
        if (historyJob?.isActive == true) return
        if (!reducer.hasMore()) return
        val before = reducer.oldestSeq() ?: return
        val through = reducer.throughSeq()
        if (through <= 0) return

        historyJob = viewModelScope.launch {
            _loadingHistory.value = true
            runCatching {
                client.rpc(
                    "session/page",
                    JSONObject().put(
                        "request",
                        JSONObject()
                            .put("address", sessionAddress(sessionId))
                            .put("throughSeq", through)
                            .put("beforeSeq", before)
                            .put("maxMessages", HISTORY_PAGE_MESSAGES),
                    ),
                )
            }.onSuccess { page ->
                val added = reducer.applyOlderPage(page)
                Log.d("$TAG/History", "page before=$before added=$added hasMore=${reducer.hasMore()}")
                // Older rows land above the reader; the recorder should be able to
                // tell that from the app moving the list itself.
                uk.xa0.dsh.diag.ScrollDiag.note(
                    "page",
                    "s" to "chat",
                    "added" to added,
                    "hasMore" to reducer.hasMore(),
                )
                bumpTranscript()
            }.onFailure { error ->
                // A failed page is not worth the banner: the transcript is still
                // readable, and the next scroll to the top asks again.
                Log.w("$TAG/History", "page before=$before failed: ${describe(error)}")
                if (error is DshAuthException) onAuthFailure("session/page", error)
            }
            _loadingHistory.value = false
        }
    }

    /**
     * Opens a session the shell explicitly asked for (a notification deep link).
     *
     * Recorded as well as opened: [attemptConnect]'s auto-open and this call race,
     * and the auto-open used to be able to land last — putting an unrelated
     * session (often an empty one) on screen instead of the one the user tapped.
     */
    fun openSessionExplicit(sessionId: String) {
        explicitSession = sessionId
        openSession(sessionId)
    }

    fun openSession(sessionId: String) {
        // Any other open supersedes a pending explicit one, so a later reconnect
        // cannot drag the user back to a session they have since left.
        if (explicitSession != null && explicitSession != sessionId) explicitSession = null
        if (_ui.value.currentSessionId == sessionId && followJob?.isActive == true) return
        followJob?.cancel()
        historyJob?.cancel()
        reducer.reset()
        publishTranscript()
        Log.d(TAG, "openSession $sessionId")
        _ui.value = _ui.value.copy(
            currentSessionId = sessionId,
            // The seat is spent the moment a real session is on screen; leaving it
            // set would let a later send try to materialise over an open session.
            pendingSession = null,
            running = _ui.value.sessions.firstOrNull { it.id == sessionId }?.running ?: false,
            error = null,
            errorNeedsSignIn = false,
            // Opening the row retires its finished-unseen reminder dot.
            completedSessionIds = _ui.value.completedSessionIds - sessionId,
            // Swap in whatever the control stream already knows about this
            // session's queue rather than carrying the previous one's over.
            queue = dockRows(sessionId),
            queueBusy = null,
            // Uploads are bound to the session they were staged for, so carrying
            // them across a switch would leave a receipt the host rejects as
            // ATTACHMENT_NOT_FOUND — a send with a permanently broken chip.
            attachments = emptyList(),
            // Availability belongs to one child's parent: the previous session's
            // answer must not decide this session's composer, so it drops back to
            // unknown until the read below lands.
            subagentParentAvailable = null,
        )
        // The chip belongs to the session on screen. A selection is not carried
        // across a switch: what this session will run arrives with its own
        // projections, and until they land the host's global default is the honest
        // answer — the previous session's model never is. A pick recorded on the
        // new-session screen is dropped too: it was about a session that is not
        // this one. (The create/adopt paths take it before calling this.)
        updateModelSelection { it.onSessionOpened() }
        // Point the roster stream at this session and re-publish what is already
        // known for it. The stream is what makes jobs appear at all — nothing else
        // pushes them — and re-publishing keeps the seat from wearing the outgoing
        // session's list (and from judging the new session's first frame against it)
        // until the host's first `rows` frame lands.
        observeJob(null)
        watchJobs(sessionId)
        publishJobsForCurrent()
        _todos.value = emptyList()
        app.attention.visibleSessionId = sessionId
        // A parent and the subagents it dispatched are one thing the reader attends
        // to: opening the parent is how you look at their work, so it settles their
        // alerts and stops them buzzing while it is in front.
        app.attention.visibleWith = subagentTreeIds(_ui.value.sessions, sessionId)
        // The follow snapshot carries this session's projections; drop the
        // previous session's folded values so a delta cannot merge across them.
        liveProjections = null

        startFollow(sessionId)
        refreshSubagentCatalogsFor(sessionId)
    }

    /**
     * The two catalog reads one selection owes, mirroring the web's own
     * selection-change pulls (`manager.ts:183`, `:201`, `:796-797`).
     *
     * The selected session is read as a catalog *owner*: its own direct children
     * are what the lineage chip counts and the sheet lists, so the answer is
     * wanted before the reader asks for it. And when the selection is itself an
     * addressed child, its parent's catalog is read too — that is the one carrying
     * `parentAvailable` for the read-only gate, this child's own `activity`, and
     * its siblings', which is why the composer and the drawer's caret can both be
     * right after a single read.
     */
    private fun refreshSubagentCatalogsFor(sessionId: String) {
        refreshSubagentCatalog(sessionId)
        addressedChild(sessionId)?.let { refreshSubagentCatalog(it.parentSessionId) }
    }

    /**
     * Re-opens the follow stream for a session whose stream stopped, *without*
     * resetting the reducer: a silent re-login must not cost the user the
     * transcript already on screen, and the new snapshot re-applies the same
     * records idempotently (they are keyed by seq).
     */
    private fun reopenFollowIfStopped() {
        val sessionId = _ui.value.currentSessionId ?: return
        if (followJob?.isActive == true) return
        Log.d(TAG, "reopening follow for $sessionId after re-auth")
        startFollow(sessionId)
    }

    /** Opens one `session/follow` stream. Assigns [followJob]. */
    private fun startFollow(sessionId: String) {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            val args = JSONObject().put(
                "request",
                JSONObject()
                    .put("address", sessionAddress(sessionId))
                    .put("maxMessages", FOLLOW_WINDOW_MESSAGES)
                    .put("assistantStream", true),
            )
            client.mux().openStream("session/follow", args).collect { event ->
                lastEventAt = System.currentTimeMillis()
                when (event) {
                    is StreamEvent.Item -> {
                        val value = event.value
                        when (value.optString("type")) {
                            "snapshot" -> {
                                reducer.applySnapshot(value)
                                // The snapshot is the *only* place a session switch
                                // delivers projections: the control baseline is once per
                                // connection, so anything read only from there keeps the
                                // previous session's values forever. That was the model
                                // chip carrying across switches, and the same shape would
                                // leave the subagent catalog stale on a switch too.
                                value.obj("projections")?.obj("values")?.let { block ->
                                    liveProjections = block
                                    applySessionProjections(block)
                                    applyModelSelection(block)
                                    refreshSubagentCatalog(sessionId)
                                }
                                bumpTranscript()
                                Log.d(TAG, "follow snapshot: ${value.optJSONArray("records")?.length() ?: 0} records, cursor=${value.optInt("cursor")}")
                            }

                            "event" -> {
                                value.optJSONObject("event")?.let { reducer.applyEvent(it) }
                                bumpTranscript()
                                val type = value.optJSONObject("event")?.optString("type")
                                if (type == "step/end" || type == "assistant/message") refreshSessionsSoon()
                            }

                            "assistant-stream" -> {
                                value.optJSONObject("frame")?.let { reducer.applyAssistantFrame(it) }
                                bumpTranscript()
                            }
                        }
                    }

                    is StreamEvent.Failure -> {
                        Log.w(TAG, "follow failed: ${event.code} ${event.message}")
                        if (isAuthStreamFailure(event.code, event.message)) {
                            // The transcript stream is the one that makes the app
                            // look alive; losing it silently is the reported "the
                            // app does nothing" hanging off a dead session.
                            onAuthFailure("session/follow", null)
                        } else if (event.code.contains("not-found") || event.code.contains("unavailable")) {
                            _ui.value = _ui.value.copy(error = "Session is no longer available.", errorNeedsSignIn = false)
                        } else if (_header.value == null) {
                            // A stream that failed before ever delivering this
                            // session's opening snapshot leaves the transcript body on
                            // "Loading…" with nothing to explain it, forever. That is
                            // exactly what a host which refuses to read a session looks
                            // like from here — 0.2.0 refusing to migrate a v3 log, say —
                            // and the host's own words are the only useful thing on
                            // offer, so they go in the banner.
                            //
                            // Guarded on the header so a transient failure *during* a
                            // live stream still retries quietly, as it always has: the
                            // difference is whether there is anything on screen to
                            // explain.
                            _ui.value = _ui.value.copy(
                                error = "This session could not be loaded: ${event.message}",
                                errorNeedsSignIn = false,
                            )
                        }
                    }

                    StreamEvent.End -> {
                        Log.d(TAG, "follow ended")
                        refreshSessionsSoon()
                    }
                }
            }
        }
    }

    /**
     * Starts a session at [cwd] or in [workspaceId], reusing one when the intent
     * already names a session.
     *
     * The host takes a `cwd` or a `workspaceId` and rejects both
     * (`gateway/bad-request`), and only the workspace form **attaches** the
     * session to that workspace's membership — which is exactly what puts it in
     * that group in the sidebar. Creating by `cwd` alone leaves it Ungrouped,
     * which is why adopting a workspace has to go through the id.
     *
     * This is the raw entry point as much as the UI one, so it runs the same
     * [SessionTargets.plan] decision every other path does: "New Session" must
     * never mean "and also one more blank" when the host already holds one that
     * is what the user asked for. [createSessionNow] is the branch that actually
     * creates; it is private so no path can reach `session/create` without the
     * plan above it.
     */
    fun newSession(cwd: String?, preset: String?, workspaceId: String? = null) {
        val target = when {
            !workspaceId.isNullOrBlank() -> SessionTarget.Workspace(
                id = workspaceId,
                path = _ui.value.workspaces.firstOrNull { it.id == workspaceId }?.path,
            )
            !cwd.isNullOrBlank() -> SessionTarget.Directory(cwd)
            // No target at all: the host's own default directory. There is nothing
            // to record and nothing to match against, so the plan has no opinion and
            // a create is honest. No UI path reaches this — every entry point in the
            // app names a Workspace or a directory — so it stays the one eager form.
            else -> null
        }
        if (target == null) {
            createSessionNow(cwd, preset, workspaceId)
            return
        }
        when (
            val plan = SessionTargets.intent(
                target = target,
                preset = preset,
                currentSessionId = _ui.value.currentSessionId,
                candidates = sessionTargetCandidates(),
            )
        ) {
            is SessionIntentPlan.Adopt -> adoptSession(plan.sessionId, plan.workspaceId)
            is SessionIntentPlan.Open -> openSessionCarryingModelPick(plan.sessionId)
            is SessionIntentPlan.Record -> enterPendingSession(plan.pending)
        }
    }

    /**
     * The unconditional `session/create`, suspending. **The only code that can add
     * a session row.**
     *
     * Split from the firing [createSessionNow] so [materializePending] can await the
     * one call that must happen before a send, and surface its failure on the send's
     * own terms.
     */
    private suspend fun createSessionRequest(cwd: String?, preset: String?, workspaceId: String?): String {
        val request = JSONObject()
        if (!workspaceId.isNullOrBlank()) {
            request.put("workspaceId", workspaceId)
        } else if (!cwd.isNullOrBlank()) {
            request.put("cwd", cwd)
        }
        if (!preset.isNullOrBlank()) request.put("agentPreset", preset)
        val value = client.rpc("session/create", JSONObject().put("request", request))
        val id = value.optString("sessionId")
        check(id.isNotEmpty()) { "session/create answered without a sessionId" }
        refreshSessions()
        return id
    }

    /** The unconditional `session/create`, launched. The only code that can add a row. */
    private fun createSessionNow(cwd: String?, preset: String?, workspaceId: String?) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, errorNeedsSignIn = false)
            val modelPick = pendingModelPick()
            runCatching { createSessionRequest(cwd, preset, workspaceId) }
                .onSuccess { id ->
                    _ui.value = _ui.value.copy(busy = false)
                    openSession(id)
                    modelPick?.let { applyPendingModelPick(id, it) }
                }
                .onFailure { error ->
                    _ui.value = _ui.value.copy(busy = false)
                    showFailure(error, "session/create")
                }
        }
    }

    /**
     * The drawer's bare "New Session", which inherits a Workspace rather than
     * asking for one.
     *
     * The web resolves the target the same way (`ui-workspace`'s `startSession`):
     * the current session's Workspace when that session is one of its members,
     * otherwise the most recently used Workspace. This matters because a session
     * created at a plain `cwd` is attached to *nothing* and therefore lands in
     * Ungrouped — the host only accounts a session to a Workspace when it was
     * created through `workspaceId`. [cwd] is only the fallback for a host with no
     * registered Workspace at all, where Ungrouped is the correct home.
     *
     * Like every other entry point this **records** the resolved target: a tap on
     * New Session from the hero cannot leave a blank in the Workspace being left.
     */
    fun startSession(cwd: String? = null) {
        preferredWorkspaceId()?.let { workspaceId ->
            startSessionInWorkspace(workspaceId)
            return
        }
        val fallback = cwd?.takeIf { it.isNotBlank() }
            ?: currentCwd()
            ?: return
        startSessionIn(fallback)
    }

    /**
     * The Workspace a bare "New Session" belongs to: the current session's, else
     * the most recently used one.
     *
     * "Most recently used" is the newest `updatedAt` among a Workspace's known
     * members — the same measure the web takes, minus its ISO `createdAt` tiebreak
     * for a Workspace that has no sessions yet; those compare as unused here and
     * fall back to the host's manual order, which is the order the sidebar draws in.
     */
    private fun preferredWorkspaceId(): String? {
        val ui = _ui.value
        val current = ui.currentSessionId
        if (current != null) {
            ui.workspaces.firstOrNull { current in it.sessionIds }?.let { return it.id }
        }
        val updatedAt = ui.sessions.associate { it.id to it.updatedAt }
        val ranked = ui.workspaces.map { workspace ->
            workspace to workspace.sessionIds.mapNotNull { updatedAt[it] }.maxOrNull()
        }
        return (ranked.filter { it.second != null }.maxByOrNull { it.second!! } ?: ranked.firstOrNull())
            ?.first?.id
    }

    /** The open session's directory, or its header's when the row has not landed. */
    private fun currentCwd(): String? =
        _ui.value.sessions.firstOrNull { it.id == _ui.value.currentSessionId }?.cwd
            ?: header.value?.cwd

    /**
     * Starts (or reuses) a blank session in [cwd].
     *
     * The host has no "change cwd" RPC — a session's working directory is fixed at
     * `session/create` and later differing requests raise `session/conflict` — so
     * choosing a different directory means reusing a blank already rooted there,
     * adopting one by both ids when it is, or creating one. Which of the three is
     * [SessionTargets.plan]'s call; this only hands it the target.
     *
     * This is the path for a directory the user *typed*, which is not necessarily
     * registered; a picked Workspace goes through [startSessionInWorkspace] with its
     * id instead, so it attaches even before the registry has loaded.
     */
    fun startSessionIn(cwd: String) {
        val trimmed = cwd.trim()
        if (trimmed.isEmpty()) return
        // A registered workspace is addressed by id, because only that form attaches
        // the session to it.
        val workspace = _ui.value.workspaces.firstOrNull { it.path == trimmed }
        startSessionAt(
            if (workspace != null) SessionTarget.Workspace(workspace.id, workspace.path)
            else SessionTarget.Directory(trimmed),
        )
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    "session/rename",
                    JSONObject().put(
                        "request",
                        JSONObject().put("sessionId", sessionId).put("title", title),
                    ),
                )
            }.onSuccess { refreshSessions() }
                .onFailure { error -> showFailure(error, "session/rename", "Could not rename: ") }
        }
    }

    // ------------------------------------------------------------------ turns

    /**
     * The delivery mode for a plain submit (Send button / Enter), resolved the
     * way the web's `resolveSubmitMode` does: an idle agent always takes the
     * message as the next turn, and a running one follows the busy-Enter
     * preference.
     */
    fun submitMode(): String =
        if (_ui.value.running) _ui.value.busyEnter else BusyEnter.QUEUE

    /** The mode the *other* gesture uses — the web's Cmd/Ctrl+Enter behavior. */
    fun alternateMode(): String = BusyEnter.flip(submitMode())

    fun updateBusyEnter(mode: String) {
        val config = configStore.load().copy(busyEnter = mode)
        configStore.save(config)
        _ui.value = _ui.value.copy(busyEnter = mode)
    }

    private val draftStore by lazy { DraftStore(app) }

    /**
     * The draft for [key], from disk.
     *
     * Read synchronously on purpose: the composer seeds itself from this on its first
     * frame, and an asynchronous read would render an empty box and then fill it — the
     * one place where "loading" is worse than a plain read of a few hundred bytes.
     *
     * @param key the session id, or [PENDING_DRAFT_KEY] for the new-session composer.
     */
    fun draftFor(key: String?): String = key?.let { draftStore.load(it) }.orEmpty()

    /**
     * Stores [key]'s draft. Called on every change rather than on a timer: preferences
     * writes are asynchronous and cheap, and a debounce would lose the last keystrokes
     * whenever the process is killed — which is one of the two cases this exists for.
     */
    fun saveDraft(key: String?, text: String) {
        key ?: return
        draftStore.save(key, text)
    }

    /** Saves (or clears) the DeepSeek key used to read the account balance. */
    fun updateDeepSeekKey(key: String) {
        val config = configStore.load().copy(deepseekApiKey = key.trim())
        configStore.save(config)
        // The old balance belonged to the old key; a stale figure next to a new key is
        // worse than none.
        _ui.value = _ui.value.copy(balance = BalanceState.Idle)
    }

    /**
     * Reads the account balance, if there is a key to read it with.
     *
     * The only call this app makes that is not to the DSH host: DeepSeek's own
     * `GET /user/balance`, which is the whole of what that API will say about an
     * account — it publishes no spend and no history.
     */
    fun refreshBalance() {
        val key = configStore.load().deepseekApiKey
        if (key.isBlank()) {
            _ui.value = _ui.value.copy(balance = BalanceState.NoKey)
            return
        }
        if (_ui.value.balance is BalanceState.Loading) return
        _ui.value = _ui.value.copy(balance = BalanceState.Loading)
        viewModelScope.launch {
            val next = try {
                BalanceState.Ready(deepseekAccount.balance(key))
            } catch (error: BalanceFailure) {
                BalanceState.Failed(error.message ?: "Could not read the balance.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                BalanceState.Failed(error.message ?: error.javaClass.simpleName)
            }
            _ui.value = _ui.value.copy(balance = next)
        }
    }

    /**
     * Admits one prompt.
     *
     * [mode] is `queue` (deliver once the current turn ends) or `steer`
     * (interrupt the running turn). Steering is best-effort by design: the host
     * turns a submission that arrives after the turn closed into the next
     * queued item rather than failing it.
     *
     * On an addressed child the same call goes out as `subagents/prompt` with
     * [mode] travelling as `delivery`: that is what makes a child's composer
     * reach the child, instead of a `session/prompt` the host has no route for.
     *
     * With no open session this is the pending seat's **first use**: the session
     * is created, opened, and only then is this very prompt delivered — see
     * [sendFromPending]. Text and attachments are captured here, before the
     * session switch clears them, so neither can be dropped.
     */
    fun send(text: String, mode: String = BusyEnter.QUEUE) {
        val riding = _ui.value.attachments
        // An attachment on its own is a complete message: a screenshot with no
        // caption is an ordinary thing to send.
        if (text.isBlank() && riding.isEmpty()) return
        val sessionId = _ui.value.currentSessionId
        if (sessionId != null) {
            dispatchSend(sessionId, text, riding, mode, restoreOnFailure = false)
            return
        }
        val pending = _ui.value.pendingSession ?: return
        sendFromPending(pending, text, riding, mode)
    }

    /**
     * The **first use** of a pending seat: create (or reuse) the session in the
     * recorded target, open it, and only then deliver the very same text and
     * attachments.
     *
     * The order is the guarantee. `session/create` → `openSession` →
     * `session/prompt`, with `text` and `riding` captured by [send] *before*
     * materialisation so a session switch (which clears `attachments`) cannot drop
     * them. A failure at the create/adopt step is surfaced through the banner and
     * the text is handed back to the composer, so the send is neither lost nor
     * half-applied: nothing was echoed and nothing was sent.
     */
    private fun sendFromPending(
        pending: PendingSessionTarget,
        text: String,
        riding: List<PendingAttachment>,
        mode: String,
    ) {
        viewModelScope.launch {
            val id = materializePending(pending)
            if (id == null) {
                // `materializePending` has already published the banner; the one
                // thing left is not to swallow what the user typed.
                restoreDraft(text)
                return@launch
            }
            dispatchSend(id, text, riding, mode, restoreOnFailure = true)
        }
    }

    /**
     * Echos one submission and sends it. [restoreOnFailure] hands the text back to
     * the composer when the prompt itself is refused — set for a send that came off
     * the pending seat, where the reader has already had one failure mode reported
     * and the draft is the only copy of what they wrote.
     */
    private fun dispatchSend(
        sessionId: String,
        text: String,
        riding: List<PendingAttachment>,
        mode: String,
        restoreOnFailure: Boolean,
    ) {
        val requestId = UUID.randomUUID().toString()
        // The content is built before anything is echoed, because an addressed
        // child refuses a staged file *client-side* and must not pretend to have
        // sent it: the receipt stays on the rail so the reader can take it off
        // and retry, which a silently attachment-less message would hide.
        val child = addressedChild(sessionId)
        val content = promptContent(text, riding)
        if (child != null && hasFileContentPart(content)) {
            // The refusal the web raises before ever reaching the subagent route,
            // surfaced through the same banner a host error of that code would
            // use; the staged receipt is left on the rail to be taken off.
            showFailure(
                DshRpcException(
                    code = SUBAGENT_ATTACHMENT_INVALID,
                    message = SUBAGENT_ATTACHMENT_REFUSAL,
                    details = JSONObject().put("reason", SUBAGENT_FILE_UNSUPPORTED),
                ),
                "subagents/prompt",
            )
            return
        }
        // The web derives the echo's placement the same way (`beginSubmission`):
        // a steer while running is already on its way into the turn (the
        // transcript shows it), a queue while running waits in the dock, and an
        // idle submit becomes the next turn immediately — nothing to echo.
        val running = _ui.value.running
        if (mode == BusyEnter.STEER) {
            reducer.addPending(
                rpcId = requestId,
                text = text,
                attachments = riding.map {
                    MessageAttachment(
                        kind = if (it.isImage) "image" else "file",
                        attachmentId = "",
                        name = it.name,
                        bytes = it.bytes,
                        mediaType = it.mediaType,
                        // The bytes are already in hand, so the optimistic row draws
                        // the picture instead of waiting on a journal round trip it
                        // cannot make — there is no attachmentId until the echo lands.
                        localData = it.data,
                    )
                },
            )
            bumpTranscript()
        } else if (running) {
            // The host's own inbox row is at least one control frame away, and on
            // a busy host that is seconds: without this the dock stayed empty
            // between the tap and the frame, so a queued send looked like it went
            // nowhere. The echo is retired by the row's `rpcId` (see dockRows).
            addPendingSubmission(sessionId, requestId, text, riding)
        }
        viewModelScope.launch {
            // Do NOT reset the reducer here. The session/follow stream stays open
            // and is not re-snapshotted, so clearing it would blank the transcript
            // and leave only the events that arrive afterwards. The host echoes the
            // user message as a durable event, and the next attempt's `start` frame
            // clears the previous live buffer on its own.
            _ui.value = _ui.value.copy(running = true, error = null, errorNeedsSignIn = false)
            runCatching {
                if (child != null) {
                    client.rpc(
                        "subagents/prompt",
                        JSONObject().put(
                            "request",
                            subagentPromptRequest(
                                requestId = requestId,
                                target = child,
                                delivery = mode,
                                content = content,
                                clientTimeZone = resolvedTimeZone(),
                            ),
                        ),
                    )
                } else {
                    val request = JSONObject()
                        .put("requestId", requestId)
                        .put("sessionId", sessionId)
                        .put("mode", mode)
                        .put("content", content)
                    // The host rejects anything but `UTC` or an Area/Location name, and
                    // a device pinned to a fixed offset reports "GMT+02:00" — omitting
                    // the hint is better than failing the whole prompt.
                    resolvedTimeZone()?.let { request.put("clientTimeZone", it) }
                    client.rpc("session/prompt", JSONObject().put("request", request))
                }
            }.onSuccess {
                _ui.value = _ui.value.copy(attachments = emptyList())
            }.onFailure { error ->
                reducer.failPending(requestId)
                // A failed prompt must not leave a phantom row in the dock.
                retirePendingSubmission(sessionId, requestId)
                publishQueue()
                bumpTranscript()
                _ui.value = _ui.value.copy(running = false)
                showFailure(error, if (child != null) "subagents/prompt" else "session/prompt")
                if (restoreOnFailure) restoreDraft(text)
            }
            refreshSessionsSoon()
        }
    }

    /**
     * One prompt's wire content: the text first, then every staged attachment.
     *
     * A picture travels as its own bytes — that is what puts it in front of a
     * vision model and what makes the host keep it readable back out of the
     * journal. Everything else rides as a file receipt.
     */
    private fun promptContent(text: String, attachments: List<PendingAttachment>): JSONArray {
        val content = JSONArray()
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        attachments.forEach { attachment ->
            val data = attachment.data
            when {
                data != null && attachment.isImage -> content.put(
                    JSONObject()
                        .put("type", "image")
                        .put("mediaType", attachment.mediaType)
                        .put("data", data)
                        .put("name", attachment.name),
                )

                attachment.receiptId != null -> content.put(
                    JSONObject().put("type", "file").put("receiptId", attachment.receiptId),
                )
            }
        }
        return content
    }

    // ------------------------------------------------------------------ queue

    /**
     * One queue mutation (`session/updateQueue`).
     *
     * The dock's optimistic bookkeeping is deliberately thin: the host answers
     * every mutation with a replacement `queue` frame on the control stream, so
     * the local list is only touched to keep the row from flashing back before
     * that frame lands.
     */
    private fun queueAction(itemId: String, action: JSONObject, failure: String, optimistic: () -> Unit) {
        val sessionId = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(queueBusy = itemId)
            runCatching {
                client.rpc(
                    "session/updateQueue",
                    JSONObject().put(
                        "request",
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put("itemId", itemId)
                            .put("action", action),
                    ),
                )
            }.onSuccess {
                optimistic()
                _ui.value = _ui.value.copy(queueBusy = null)
            }.onFailure { error ->
                _ui.value = _ui.value.copy(queueBusy = null)
                showFailure(error, "session/updateQueue", "$failure ")
            }
        }
    }

    fun editQueueItem(itemId: String, text: String) {
        if (text.isBlank()) return
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        queueAction(
            itemId = itemId,
            action = JSONObject().put("kind", "edit").put("content", content),
            failure = "Edit failed: this message may have already started sending.",
        ) {
            mutateQueueRow(itemId) { it.copy(text = text, preview = text) }
        }
    }

    fun removeQueueItem(itemId: String) {
        queueAction(
            itemId = itemId,
            action = JSONObject().put("kind", "remove"),
            failure = "Removal failed: this message may have already started sending.",
        ) {
            mutateQueueRow(itemId) { null }
        }
    }

    /**
     * Promotes one queued row into the running turn.
     *
     * The row is not deleted: the host answers a steer by re-listing the very
     * same item with `placement: "steering"` and holds it there until the turn
     * claims it, so the dock's send glyph is the truthful state. Flipping it
     * locally just makes that transition start on the tap instead of on the next
     * control frame; the frame then agrees, or drops the row if the turn got
     * there first and there is nothing left to settle.
     */
    fun steerQueueItem(itemId: String) {
        queueAction(
            itemId = itemId,
            action = JSONObject().put("kind", "steer"),
            failure = "Steering failed. Try again.",
        ) {
            mutateQueueRow(itemId) { it.copy(placement = "steering") }
        }
    }

    // ------------------------------------------------------- submission echoes

    /**
     * Records one local submission echo for a queue-mode send.
     *
     * It is keyed by the prompt's `requestId` — the host copies that id onto the
     * admitted row as `rpcId`, which is the only identity the two share (probed:
     * the row is `{id, placement, rpcId, message}`, and a `session/prompt`
     * answer carries no id at all).
     */
    private fun addPendingSubmission(
        sessionId: String,
        requestId: String,
        text: String,
        attachments: List<PendingAttachment>,
    ) {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        val echo = QueuedMessage(
            id = requestId,
            placement = "queued",
            preview = if (collapsed.length > QUEUE_PREVIEW_CHARS) {
                collapsed.take(QUEUE_PREVIEW_CHARS) + "…"
            } else {
                collapsed
            },
            text = text,
            attachments = attachments.map {
                QueueAttachment(
                    kind = if (it.isImage) "image" else "file",
                    name = it.name,
                    bytes = it.bytes,
                    // The bytes are in hand, so a staged picture draws itself.
                    data = it.data,
                )
            },
            pending = true,
        )
        pendingSubmissions[sessionId] = pendingSubmissions[sessionId].orEmpty() + echo
        if (sessionId == _ui.value.currentSessionId) publishQueue()
        // Safety net for the one case `rpcId` cannot cover: the turn closed
        // between the tap and the host's admission, so the prompt became a turn
        // of its own and no queue row will ever carry this id. Long enough that a
        // merely slow control frame still wins the race.
        viewModelScope.launch {
            delay(PENDING_ECHO_TIMEOUT_MS)
            if (retirePendingSubmission(sessionId, requestId)) publishQueue()
        }
    }

    /** Drops one local echo. Returns whether it was still there. */
    private fun retirePendingSubmission(sessionId: String, requestId: String): Boolean {
        val current = pendingSubmissions[sessionId].orEmpty()
        val remaining = current.filterNot { it.id == requestId }
        if (remaining.size == current.size) return false
        if (remaining.isEmpty()) {
            pendingSubmissions.remove(sessionId)
        } else {
            pendingSubmissions[sessionId] = remaining
        }
        return true
    }

    /**
     * Rewrites the row with [itemId] wherever it lives — the host list or the
     * local echo list — and republishes the dock.
     *
     * The two lists must stay apart: an echo is retired by matching `rpcId`, so a
     * copy leaked into the host map would come back as a second, authoritative
     * row. A null transform removes the row.
     */
    private fun mutateQueueRow(itemId: String, transform: (QueuedMessage) -> QueuedMessage?) {
        val sessionId = sessionIdOrNull()
        fun apply(rows: List<QueuedMessage>?): List<QueuedMessage>? =
            rows?.mapNotNull { row -> if (row.id == itemId) transform(row) else row }

        apply(queues[sessionId])?.let { queues[sessionId] = it }
        val echoes = apply(pendingSubmissions[sessionId])
        if (echoes != null) {
            if (echoes.isEmpty()) {
                pendingSubmissions.remove(sessionId)
            } else {
                pendingSubmissions[sessionId] = echoes
            }
        }
        publishQueue()
    }

    private fun sessionIdOrNull(): String = _ui.value.currentSessionId.orEmpty()

    fun stop() {
        val sessionId = _ui.value.currentSessionId ?: return
        val child = addressedChild(sessionId)
        viewModelScope.launch {
            runCatching {
                // A child's stop is parent-authorized, and that authority is what
                // still reaches a live child whose parent Agent is offline — the
                // one state where the composer keeps its Stop instead of the
                // read-only frame, so this route is the only way out of it.
                if (child != null) {
                    client.rpc("subagents/interruptByParent", subagentInterruptArgs(child))
                } else {
                    client.rpc(
                        "session/cancel",
                        JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
                    )
                }
            }.onFailure { error ->
                showFailure(
                    error,
                    if (child != null) "subagents/interruptByParent" else "session/cancel",
                )
            }
        }
    }

    // ----------------------------------------------------------------- models

    private suspend fun loadModels() {
        val catalog = client.rpc("session/modelCatalog", JSONObject())
        val options = mutableListOf<ModelOption>()
        val order = mutableListOf<String>()
        catalog.optJSONArray("groups")?.let { groups ->
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val providerId = group.str("id")
                val providerName = group.str("name").ifEmpty { providerId }
                order += providerName
                group.arr("models")?.let { models ->
                    for (j in 0 until models.length()) {
                        val model = models.optJSONObject(j) ?: continue
                        // Reasoning efforts live on the route, not globally.
                        val reasoning = model.obj("reasoning")
                        val efforts = reasoning.arr("efforts")?.let { list ->
                            (0 until list.length()).mapNotNull { k ->
                                list.optJSONObject(k)?.let {
                                    EffortOption(it.str("id"), it.str("name").ifEmpty { it.str("id") })
                                }
                            }
                        }.orEmpty()
                        options += ModelOption(
                            provider = providerId,
                            providerName = providerName,
                            model = model.str("id"),
                            name = model.str("name").ifEmpty { model.str("id") },
                            efforts = efforts,
                            defaultEffort = reasoning.str("defaultEffort").takeIf { it.isNotEmpty() },
                        )
                    }
                }
            }
        }
        // Logged while "only DeepSeek is listed" is being chased: the host answers this
        // call with five provider groups, so if fewer arrive here the loss is between
        // the socket and this loop rather than anywhere the sheet can see.
        Log.d(
            TAG,
            "model catalog: groups=${catalog.optJSONArray("groups")?.length() ?: -1} " +
                "models=${options.size} providers=${order.joinToString()}",
        )
        // Same order as the desktop: stable, so everything outside the two DeepSeek
        // providers keeps the catalog's own sequence.
        val orderedOptions = options.sortedBy { providerRank(it.provider) }
        val orderedProviders = order.sortedBy { providerRank(it) }
        // `catalog.default` is the host's **global** default agent model. It is not
        // this session's selection and must never be drawn as one; it is only what a
        // session with no selection of its own will actually run, which is exactly
        // how `ModelSelectionState` uses it.
        updateModelSelection { it.withCatalogDefault(parseModelRef(catalog.obj("default"))) }
        _ui.value = _ui.value.copy(models = orderedOptions, providerOrder = orderedProviders)
    }

    /**
     * Adopts a `modelSelection` projection block from the host.
     *
     * The host's word is the only authority on which route the session will run. This
     * runs wherever the open session's projections land — the follow snapshot, the
     * control baseline, and a single `projection` delta too, which is what carries
     * `lastUsed` rolling forward after a turn without any refresh of this client's
     * own.
     */
    private fun applyModelSelection(values: JSONObject?) {
        updateModelSelection { it.withHostSelection(parseHostModelSelection(values.obj("modelSelection"))) }
    }

    /**
     * The one way any code may move the model selector's state.
     *
     * There is no `modelChoice` field to assign: the chip is derived from this
     * state, so a second writer cannot exist even by accident. The equality guard
     * keeps `StateFlow` from emitting on a no-op transform.
     */
    private fun updateModelSelection(transform: (ModelSelectionState) -> ModelSelectionState) {
        val current = _ui.value
        val next = transform(current.modelSelection)
        if (next == current.modelSelection) return
        _ui.value = current.copy(modelSelection = next)
    }

    /**
     * Model and reasoning effort go through the same call — `session/selectModel`
     * takes an optional `reasoningEffort`; there is no separate setter.
     *
     * A pick on the **new-session screen** has no session to select for, and used to
     * be dropped on the floor after optimistically repainting the chip: the session
     * that was then created ran the host default while the chip advertised the pick.
     * It is now *recorded* ([ModelSelectionState.pendingPick]) and applied to the
     * session when that session is created — see [applyPendingModelPick]. On a
     * session that exists the pick goes to the host immediately, and until its
     * answer arrives the chip says so rather than claiming the switch has happened.
     */
    fun selectModel(option: ModelOption, effort: String? = null) {
        val ref = ModelRef(option.provider, option.model, effort ?: option.defaultEffort)
        val sessionId = _ui.value.currentSessionId
        if (sessionId == null) {
            updateModelSelection { it.withPendingPick(ref) }
            return
        }
        updateModelSelection { it.withInFlight(ref) }
        viewModelScope.launch { selectModelNow(sessionId, ref) }
    }

    /**
     * The `session/selectModel` call itself, suspending so a caller that must not
     * deliver a prompt before the pick is in force can await it.
     *
     * Failures are the host *refusing* — probed on 0.2.0-rc.2: an unavailable model,
     * an unknown provider and an effort the route does not accept are all answered
     * with `session/model-unavailable` and mutate nothing. The chip therefore drops
     * the pick and falls back to what the host still holds; the old code left the
     * refused model on screen, which is the "advertised model is not the one
     * running" the user reported.
     */
    private suspend fun selectModelNow(sessionId: String, ref: ModelRef) {
        val request = JSONObject()
            .put("sessionId", sessionId)
            .put("provider", ref.provider)
            .put("model", ref.model)
        // The key is only sent when the route names an effort, so the host's own
        // default applies. (`put(key, null)` would strip it anyway; being explicit
        // keeps the request's shape visible.)
        ref.reasoningEffort?.let { request.put("reasoningEffort", it) }
        runCatching {
            client.rpc("session/selectModel", JSONObject().put("request", request))
        }
            .onSuccess { value ->
                // The answer belongs to the session it was asked for. Adopting it after
                // the reader has switched would paint the *new* session's chip with the
                // old session's selection — the same lie, arriving by a different road.
                if (sessionId != _ui.value.currentSessionId) return@onSuccess
                // And it belongs to the pick that is still the current one: two taps in
                // a row mean the newer answer decides, not whichever returns last.
                if (_ui.value.modelSelection.inFlight != ref) return@onSuccess
                // The host's answer is what it selected, which is not always the same
                // *shape* as the request: an omitted `reasoningEffort` comes back
                // filled in with the route's `defaultEffort` (probed). Taking the
                // answer rather than the request is what keeps the chip and the host
                // from drifting apart.
                updateModelSelection { it.withAnswer(parseModelRef(value.obj("selected")) ?: ref) }
            }
            .onFailure { error ->
                if (sessionId == _ui.value.currentSessionId &&
                    _ui.value.modelSelection.inFlight == ref
                ) {
                    updateModelSelection { it.withRefusal() }
                }
                showFailure(error, "session/selectModel", "Model not changed: ")
            }
    }

    /**
     * Applies a pick recorded on the new-session screen to the session that has just
     * been created, adopted or reused for it.
     *
     * [pick] is read by [pendingModelPick] *before* the open, because opening consumes
     * the seat's pick. The call suspends on purpose: on the send path the prompt is
     * delivered immediately after this, and a prompt that ran before the selection
     * landed would run the host default — the exact disagreement this area is about.
     */
    private suspend fun applyPendingModelPick(sessionId: String, pick: ModelRef) {
        updateModelSelection { it.withInFlight(pick) }
        selectModelNow(sessionId, pick)
    }

    /**
     * The new-session pick, if the reader has recorded one.
     *
     * Read — not taken — here: [openSession] is what consumes it, on the same path
     * that puts a real session on screen. A create that fails therefore leaves the
     * pick on the seat for the retry, and a pick can never be applied twice.
     */
    private fun pendingModelPick(): ModelRef? = _ui.value.modelSelection.pendingPick

    // ----------------------------------------------------------- projections

    /**
     * Session-scoped projections, carried both by the follow snapshot and by
     * `session/list`. These are the host's already-folded values, so they are the
     * truth for access mode, plan mode and context pressure — no client-side
     * guessing.
     */
    private fun applySessionProjections(values: JSONObject?) {
        if (values == null) return
        val previousGoalPhase = _ui.value.goal?.phase

        // The host also projects the standing plan (`todos`, a bare array). The
        // transcript's own `todo/write` row is the richer source, but on a cold
        // start that event can sit outside the follow snapshot's window, and the
        // dock then stayed empty until the agent happened to write todos again.
        if (values.has("todos")) {
            val list = values.optJSONArray("todos")
            projectedTodos = (0 until (list?.length() ?: 0)).mapNotNull { index ->
                list?.optJSONObject(index)?.let { TodoItem(it.str("content"), it.str("status")) }
            }
            // A projection arriving on its own must reach the dock without waiting
            // for the next transcript publish.
            if (reducer.latestTodos().isEmpty()) _todos.value = projectedTodos
        }

        val permission = values.obj("permissions")?.str("currentValue").orEmpty()
        // `pending` describes the next turn, so it outranks the settled `active`.
        val plan = values.obj("plan")?.let { if (it.bool("pending")) !it.bool("active") else it.bool("active") } ?: false
        // Initialized from the session header, so even a blank session carries the
        // preset it will run. Absent means "not carried by this payload", not "none".
        val agentPreset = if (values.has("agentPreset")) values.str("agentPreset") else _ui.value.currentAgentPreset

        val pressure = values.obj("contextPressure")
        val window = pressure.int("contextWindow")
        val projected = pressure.int("projectedTokens")
        val used = if (projected > 0) projected else pressure.int("pressureTokens")
        val percent = if (window > 0) minOf(100, ((used.toDouble() / window) * 100).roundToInt()) else 0

        val breakdown = values.obj("contextBreakdown")?.let {
            ContextBreakdown(
                system = it.int("systemTokens"),
                tools = it.int("toolsTokens"),
                messages = it.int("messageTokens"),
            )
        }

        // Only touch the goal when the projection actually carries the key, so a
        // session-list refresh without it cannot wipe a live goal.
        val goal = if (values.has("goal")) {
            // The projection value is `{goal: {...}, roundsStarted, createdAt, updatedAt}`
            // (or null), so the round counter is read from the wrapper, not the snapshot.
            val projection = values.obj("goal")
            projection?.obj("goal")?.let { snapshot ->
                GoalState(
                    id = snapshot.str("id"),
                    revision = snapshot.long("revision"),
                    objective = snapshot.str("objective"),
                    phase = snapshot.str("phase"),
                    maxRounds = if (snapshot.has("maxGoalRounds")) snapshot.int("maxGoalRounds") else null,
                    roundsStarted = projection.int("roundsStarted"),
                )
            }
        } else {
            _ui.value.goal
        }

        // The open session's preset, which is what the session seat's chip names.
        val nextPermission = permission.ifEmpty { _ui.value.currentPermission }
        val permissionChanged = nextPermission != _ui.value.currentPermission

        _ui.value = _ui.value.copy(
            currentPermission = nextPermission,
            planActive = plan,
            currentAgentPreset = agentPreset,
            contextPercent = percent,
            contextTokens = used,
            contextWindow = window,
            contextBreakdown = breakdown ?: _ui.value.contextBreakdown,
            goal = goal,
            // Held over when the payload omits both keys: a `session/list` refresh
            // without them is not evidence that the session has no statistics, and
            // clearing them would blink the pills out on every pull.
            sessionStats = parseSessionStats(values) ?: _ui.value.sessionStats,
        )
        // The session seat's chip names the policy this session runs. Guarded on the
        // change, not called unconditionally: these projections re-apply on every
        // control delta, and rebuilding two labels per event would be work for nothing.
        if (permissionChanged) refreshTerminalSeats()

        // A goal that has just gone blocked is an escalation: the agent has
        // stopped making progress and wants a decision. Only the *transition*
        // alerts, because these projections re-apply on every list refresh.
        if (goal?.phase == "blocked" && previousGoalPhase != "blocked") {
            alert(
                id = Attention.ID_GOAL,
                sessionId = _ui.value.currentSessionId,
                title = "Goal blocked",
                text = goal.objective.ifBlank { "A goal is blocked and needs a decision." },
            )
        }
    }

    // ------------------------------------------------------------------ goals

    /**
     * Goal mutations require the current `{id, revision}` as a compare-and-set
     * reference, so a stale client cannot clobber a newer goal.
     */
    private fun goalAction(method: String, extra: JSONObject? = null) {
        val sessionId = _ui.value.currentSessionId ?: return
        val goal = _ui.value.goal ?: return
        viewModelScope.launch {
            runCatching {
                val args = JSONObject()
                    .put("agentId", sessionId)
                    .put("ref", JSONObject().put("id", goal.id).put("revision", goal.revision))
                extra?.let { args.put("request", it) }
                client.rpc(method, args)
            }.onFailure {
                showFailure(it, "goals", "Goal update failed: ")
            }
            refreshSessionsSoon()
        }
    }

    fun pauseGoal() = goalAction("goals/pause")

    fun resumeGoal() = goalAction("goals/resume")

    fun clearGoal() = goalAction("goals/clear")

    fun editGoal(objective: String) {
        if (objective.isBlank()) return
        goalAction("goals/edit", JSONObject().put("objective", objective))
    }

    private suspend fun loadPermissionCatalog() {
        val value = client.rpc("permissionPresets/catalog", JSONObject())
        val options = value.arr("options") ?: JSONArray()
        val parsed = (0 until options.length()).mapNotNull { index ->
            val option = options.optJSONObject(index) ?: return@mapNotNull null
            val id = option.str("value").ifEmpty { option.str("id") }
            if (id.isEmpty()) {
                null
            } else {
                // The host echoes the raw preset key as `name` (e.g. "read-only"),
                // so it is only a real label when it differs from the value.
                val raw = option.str("name")
                PermissionOption(
                    value = id,
                    name = if (raw.isEmpty() || raw == id) permissionLabel(id) else raw,
                    description = option.str("description").takeIf { it.isNotEmpty() },
                )
            }
        }
        _ui.value = _ui.value.copy(permissionOptions = parsed)
    }

    // ---------------------------------------------------------- agent presets

    /**
     * The preset roster (`agentPresets/list`). The host marks the deployment
     * default, which is what the hero chip falls back to when the current
     * session carries no recorded preset.
     */
    private suspend fun loadAgentPresets() {
        val value = client.rpc("agentPresets/list", JSONObject())
        val presets = value.arr("presets") ?: JSONArray()
        val options = (0 until presets.length()).mapNotNull { index ->
            val preset = presets.optJSONObject(index) ?: return@mapNotNull null
            val id = preset.str("id")
            if (id.isEmpty()) {
                null
            } else {
                AgentPresetOption(
                    id = id,
                    // The roster carries display copy; the id alone never says what
                    // a preset does. A preset with no published name shows its id.
                    name = preset.str("name").ifEmpty { id },
                    description = preset.str("description").takeIf { it.isNotEmpty() },
                    isDefault = preset.bool("isDefault"),
                    broken = preset.str("broken").isNotEmpty(),
                )
            }
        }
        _ui.value = _ui.value.copy(agentPresetOptions = options)
    }

    /** Re-reads the roster: a preset authored while the app is open must show up. */
    fun refreshAgentPresets() {
        viewModelScope.launch {
            runCatching { loadAgentPresets() }
                .onFailure { if (it is DshAuthException) onAuthFailure("agentPresets/list", it) }
        }
    }

    /**
     * Applies one preset to the current blank session.
     *
     * The choice is per-session and only valid before the session has produced
     * anything: the host refuses to recompose an agent whose history exists
     * (`agent-preset/locked`), which is exactly why this control lives on the
     * hero rather than in Settings. On the wire the session is named `agentId`,
     * the name the host resolves its live agent from; the preset is `agentPreset`.
     */
    fun selectAgentPreset(id: String) {
        // On the pending seat there is no agent to select for: the choice is
        // recorded and travels as `agentPreset` on the `session/create` that first
        // use makes. Picking a preset by itself must not create the session.
        val pending = _ui.value.pendingSession
        if (_ui.value.currentSessionId == null && pending != null) {
            _ui.value = _ui.value.copy(
                pendingSession = pending.copy(preset = id),
                currentAgentPreset = id,
                error = null,
                errorNeedsSignIn = false,
            )
            return
        }
        val sessionId = _ui.value.currentSessionId ?: return
        val blank = _ui.value.sessions.firstOrNull { it.id == sessionId }?.blank == true
        if (!blank) {
            // The chip is only offered while blank, so this only fires if the first
            // turn landed between the tap and the call.
            _ui.value = _ui.value.copy(error = "This session has already started; its agent preset is fixed.")
            return
        }
        viewModelScope.launch {
            runCatching {
                client.rpc(
                    "agentPresets/select",
                    JSONObject().put("agentId", sessionId).put("agentPreset", id),
                )
            }.onSuccess {
                _ui.value = _ui.value.copy(currentAgentPreset = id, error = null, errorNeedsSignIn = false)
            }.onFailure { error ->
                if (error is DshAuthException) {
                    onAuthFailure("agentPresets/select", error)
                    return@onFailure
                }
                // A refusal carries its cause twice: the host's `message` wraps it
                // in the roster's own framing (which already names the preset), while
                // a `reason` detail holds the bare cause. Prefer the detail, as the
                // web chip does.
                val name = _ui.value.agentPresetOptions.firstOrNull { it.id == id }?.name ?: id
                val reason = (error as? DshRpcException)?.details?.str("reason").orEmpty()
                _ui.value = _ui.value.copy(
                    error = "Could not switch to $name: ${reason.ifEmpty { describe(error) }}",
                )
            }
        }
    }

    // --------------------------------------------------------------- commands

    /**
     * The host's command catalogue for the open session, behind the `/` menu.
     *
     * It is session-scoped: a preset switch recomposes the agent and a different
     * deployment registers a different set, so the roster is re-read on every
     * session change rather than cached at startup.
     */
    private val _commands = MutableStateFlow<List<HostCommand>>(emptyList())
    val commands: StateFlow<List<HostCommand>> = _commands.asStateFlow()

    /**
     * Re-reads `commands/list` for the open session.
     *
     * The endpoint answers with a bare JSON array, which [client.rpc] would read
     * as an empty object, so the raw value is used exactly as for the `@` sources.
     * A failure is swallowed: the menu falls back to the client-owned rows and
     * simply has no host commands, which beats an error over an auxiliary surface.
     * The old roster is dropped first so a menu opened mid-flight cannot offer
     * commands the recomposed agent may no longer have.
     */
    fun refreshCommands() {
        val sessionId = _ui.value.currentSessionId ?: return
        _commands.value = emptyList()
        viewModelScope.launch {
            val parsed = runCatching {
                val list = client.rpcRaw("commands/list", JSONObject().put("agentId", sessionId)) as? JSONArray
                    ?: JSONArray()
                (0 until list.length()).mapNotNull { index ->
                    val item = list.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.str("name")
                    if (name.isEmpty()) {
                        null
                    } else {
                        // Presence of `input` is the host's claim signal; its hint
                        // is kept as copy of last resort for an undocumented command.
                        val input = item.obj("input")
                        HostCommand(
                            name = name,
                            description = item.str("description"),
                            takesInput = input != null,
                            inputHint = input?.str("hint")?.takeIf { it.isNotEmpty() },
                        )
                    }
                }
            }.onFailure { if (it is DshAuthException) onAuthFailure("commands/list", it) }
                .getOrDefault(emptyList())
            // A slow answer for a session already left must not land in the new one.
            if (_ui.value.currentSessionId == sessionId) _commands.value = parsed
        }
    }

    /**
     * Mode changes are slash commands on the host, not dedicated RPCs: the web
     * client runs `/permission <preset>` and `/plan off` through `commands/execute`.
     */
    private suspend fun runCommand(line: String): Result<String> {
        val sessionId = _ui.value.currentSessionId
            ?: return Result.failure(IllegalStateException("No session selected"))
        return runCommandFor(sessionId, line)
    }

    /**
     * The same call against an explicit session.
     *
     * The host-shell bootstrap needs it: it sets the mode of the workspace's own
     * archived session, so it must not go through [runCommand], which reads the id of
     * the session the user has open — and it must not go through [setPermission]
     * either, which would move that session's access chip to a preset the user did not
     * pick for it.
     */
    private suspend fun runCommandFor(sessionId: String, line: String): Result<String> = runCatching {
        val value = client.rpc(
            "commands/execute",
            JSONObject()
                .put("agentId", sessionId)
                .put("line", line)
                .put("submittedAttachments", JSONArray()),
        )
        val result = value.obj("result")
        val kind = result.str("kind")
        val text = result.str("text")
        if (kind == "error") throw IllegalStateException(text.ifEmpty { "The host rejected that command" })
        text
    }

    fun setPermission(preset: String) {
        // On the pending seat there is no session to run `/permission` in. The mode
        // is *recorded* and applied by [materializePending] before anything is sent,
        // so choosing access by itself never creates a session.
        val pending = _ui.value.pendingSession
        if (_ui.value.currentSessionId == null && pending != null) {
            _ui.value = _ui.value.copy(
                pendingSession = pending.copy(permission = preset),
                currentPermission = preset,
                error = null,
                errorNeedsSignIn = false,
            )
            return
        }
        viewModelScope.launch {
            runCommand("/permission $preset")
                .onSuccess {
                    _ui.value = _ui.value.copy(currentPermission = preset)
                    refreshTerminalSeats()
                }
                .onFailure {
                    showFailure(it, "commands/execute", "Could not switch access mode: ")
                }
        }
    }

    /**
     * Runs a bare slash command from the composer's command menu.
     *
     * On the pending seat `/permission <mode>` is recorded instead (the chip's own
     * path, above); anything else is a command that needs a session to run in, so
     * it **materialises** the seat first. That is this path's answer to "what is
     * first use": a command the reader chose is work, not a configuration tap.
     */
    fun executeCommand(line: String) {
        val pending = _ui.value.pendingSession
        if (_ui.value.currentSessionId == null && pending != null) {
            val mode = permissionCommandMode(line)
            if (mode != null) {
                setPermission(mode)
                return
            }
            viewModelScope.launch {
                val id = materializePending(pending) ?: return@launch
                runCommandFor(id, line).onFailure {
                    showFailure(it, "commands/execute", "Command failed: ")
                }
            }
            return
        }
        viewModelScope.launch {
            runCommand(line).onFailure {
                showFailure(it, "commands/execute", "Command failed: ")
            }
        }
    }

    fun exitPlanMode() {
        viewModelScope.launch {
            runCommand("/plan off")
                .onSuccess { _ui.value = _ui.value.copy(planActive = false) }
                .onFailure {
                    showFailure(it, "commands/execute", "Could not leave plan mode: ")
                }
        }
    }

    // ---------------------------------------------------------- attachments

    /**
     * Uploads through the gateway RPC rather than the raw binary route: the
     * envelope and response shape are known, so failures surface as structured
     * host errors instead of an opaque HTTP code. The cost is base64 inflation,
     * which is why there is a size cap.
     *
     * An upload is scoped to a session (`agentId`), so staging a file from the
     * pending seat is **first use**: the seat is materialised, and the file is
     * staged in the session that resulted. Deferring the upload instead would mean
     * holding file bytes and a content URI across a create, which is more state
     * than the one guarantee (no orphan session) needs.
     */
    fun addAttachment(uri: Uri) {
        val sessionId = _ui.value.currentSessionId
        if (sessionId == null) {
            val pending = _ui.value.pendingSession ?: return
            viewModelScope.launch {
                val id = materializePending(pending) ?: return@launch
                stageAttachment(id, uri)
            }
            return
        }
        stageAttachment(sessionId, uri)
    }

    private fun stageAttachment(sessionId: String, uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val id = UUID.randomUUID().toString()
        val name = queryDisplayName(resolver, uri) ?: "file"
        val mediaType = resolver.getType(uri)
        _ui.value = _ui.value.copy(
            attachments = _ui.value.attachments + PendingAttachment(id, name, 0, mediaType = mediaType),
        )

        viewModelScope.launch {
            runCatching {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read that file")
                if (bytes.size > MAX_ATTACHMENT_BYTES) {
                    throw IllegalStateException("Files over ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB are not supported yet")
                }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                // An image the host recognises needs no upload at all: it is
                // promoted to a durable reference from the prompt itself. Only
                // everything else goes through the upload-receipt dance, and only
                // an uploaded file is ever read back as a chip.
                if (mediaType.orEmpty() in IMAGE_MEDIA_TYPES) {
                    StagedAttachment(data = encoded, receiptId = null, bytes = bytes.size)
                } else {
                    val value = client.rpc(
                        "fileUploads/upload",
                        JSONObject()
                            .put("agentId", sessionId)
                            .put(
                                "request",
                                JSONObject().put("data", encoded).put("name", name),
                            ),
                    )
                    StagedAttachment(data = null, receiptId = value.str("receiptId"), bytes = bytes.size)
                }
            }.onSuccess { staged ->
                updateAttachment(id) {
                    it.copy(bytes = staged.bytes, receiptId = staged.receiptId, data = staged.data)
                }
            }.onFailure { error ->
                updateAttachment(id) { it.copy(error = error.message ?: "Upload failed") }
                if (error is DshAuthException) onAuthFailure("fileUploads/upload", error)
            }
        }
    }

    fun removeAttachment(id: String) {
        _ui.value = _ui.value.copy(attachments = _ui.value.attachments.filterNot { it.id == id })
    }

    private fun updateAttachment(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        _ui.value = _ui.value.copy(
            attachments = _ui.value.attachments.map { if (it.id == id) transform(it) else it },
        )
    }

    /**
     * The device's zone, but only when the host would accept it: `UTC` or an
     * Area/Location name. A device pinned to a fixed offset reports "GMT+02:00",
     * which the host rejects outright — and it rejects the whole prompt with it.
     */
    private fun resolvedTimeZone(): String? {
        val id = java.util.TimeZone.getDefault().id
        return if (id == "UTC") id else id.takeIf { ZONE_ID.matches(it) }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------- transcript

    private fun bumpTranscript() {
        tick += 1
        transcriptTick.value = tick
    }

    /** Latest `todos` projection, used only to seed a transcript with no `todo/write` in view. */
    private var projectedTodos: List<TodoItem> = emptyList()

    private fun publishTranscript() {
        // The journal's own verdict on whether this session's turn is closed, ahead
        // of the roster sample that `running` carries: `turn/end` arrives on this
        // stream, and nothing re-reads `session/list` for it afterwards. Written
        // only on a change, so the 16/s publish while streaming is not also a
        // UiState publish.
        val ownTurnClosed = reducer.ownTurnClosed()
        if (_ui.value.ownTurnClosed != ownTurnClosed) {
            _ui.value = _ui.value.copy(ownTurnClosed = ownTurnClosed)
        }
        _entries.value = reducer.snapshot()
        _live.value = reducer.liveAttempt()
        _todos.value = reducer.latestTodos().ifEmpty { projectedTodos }
        _header.value = reducer.header()
        _endedTurns.value = reducer.endedTurns()
        _closingSeqs.value = reducer.closingAssistantSeqs()
        _turnDurations.value = reducer.turnDurations()
        // Prefer the host's `turn/start` time, but fall back to when this client
        // first saw the session running: the event scrolls out of the loaded
        // snapshot window in a long session, and the clock then silently vanished.
        val journalStart = reducer.latestTurnStart()
        if (_ui.value.running) {
            if (runningSince == null) runningSince = journalStart ?: System.currentTimeMillis()
            _turnStartedAt.value = journalStart ?: runningSince
        } else {
            runningSince = null
            _turnStartedAt.value = null
        }
    }

    // --------------------------------------------------------- workspace files

    /**
     * A reader for the Files pane, bound to the session that is open now.
     *
     * `workspaceFiles/read` is scoped by a **session id**, not by a directory: the
     * host resolves the path against that session's recorded `cwd`, and it accepts
     * an absolute path outside it too — which is why the pane can hand over the
     * paths the transcript already reports without knowing the workspace root. The
     * id is captured when the loader is built rather than read per call, so the
     * pane cannot silently re-scope to a session the reader has since switched to.
     *
     * The pane prefers whatever the transcript itself read or wrote and only falls
     * back to this, matching the web's resource resolution order.
     */
    fun workspaceFileLoader(): WorkspaceFileLoader {
        val sessionId = _ui.value.currentSessionId
        return object : WorkspaceFileLoader {
            override suspend fun load(path: String): FilePreview {
                if (sessionId.isNullOrBlank()) {
                    return FilePreview.Unavailable("No open session to read it from.")
                }
                // A picture is fetched as *bytes*, which is the whole reason the format
                // is decided from the path before anything is sent: `workspaceFiles/read`
                // refuses non-UTF-8 outright (`workspace-file/not-text`), so asking it
                // for a PNG would answer with the host's own "not text" wording where a
                // picture was wanted. `readBytes` takes one bounded window, so an
                // enormous file is refused by *us* rather than transferred in full.
                val format = previewFormatOf(path)
                if (format.isImage && format != PreviewFormat.SVG) {
                    return loadImageBytes(sessionId, path, format)
                }
                // `workspaceFileScopeId` — checked against the *running* host rather
                // than against the descriptor files it ships, which disagree with what
                // its gateway validates.
                val args = JSONObject()
                    .put("workspaceFileScopeId", sessionId)
                    .put("path", path)
                    .put(
                        "range",
                        JSONObject()
                            .put("offset", 1)
                            .put("limit", FILE_PREVIEW_LINES),
                    )
                return try {
                    val value = client.rpc("workspaceFiles/read", args)
                    FilePreview.Ready(
                        text = value.str("text"),
                        lines = value.int("lines"),
                        // A page that stops short of the last line is the host's
                        // own cap, not an empty file, and the pane says so.
                        truncated = !value.bool("eof", true),
                        bytes = value.int("bytes"),
                        absolutePath = value.str("absolutePath").takeIf { it.isNotEmpty() },
                    )
                } catch (error: Throwable) {
                    // A 401 here is the same dead cookie as anywhere else and has
                    // to reach the one funnel; the rest is the host's own wording
                    // ("no entry at …", "contains NUL bytes") which is already
                    // written for a reader.
                    if (error is DshAuthException) onAuthFailure("workspaceFiles/read", error)
                    FilePreview.Unavailable(describe(error))
                }
            }
        }
    }

    /**
     * One image's bytes, in a single window.
     *
     * The window is the format's own ceiling rather than the host's (`readAll` allows
     * 32 MiB): a phone decoding a 32 MiB bitmap is a crash waiting for a file that
     * large, and a preview that says "too large to preview" is more use than one that
     * dies. The refusal is phrased as a fact about the file, because that is what it
     * is — nothing about the read failed.
     */
    private suspend fun loadImageBytes(
        sessionId: String,
        path: String,
        format: PreviewFormat,
    ): FilePreview {
        // The byte window is nested: `read` takes a bare `range`, `readBytes` takes
        // `options` holding it. That part is real — with a bare `range` the host
        // answers `missing "options"; unexpected "range"`.
        val args = JSONObject()
            .put("workspaceFileScopeId", sessionId)
            .put("path", path)
            .put(
                "options",
                JSONObject().put(
                    "range",
                    JSONObject()
                        .put("offset", 0)
                        .put("length", MAX_PREVIEW_IMAGE_BYTES),
                ),
            )
        return try {
            // 0.2.0 answers this one with `multipart/form-data`, not JSON with base64.
            // Read as text it was an HTTP body shown to the reader — the panel printed
            // `Content-Disposition: form-data; name="bytes-0"` over PNG chunks — because
            // the JSON parse failed and the failure message *is* the body's first 400
            // characters.
            val body = client.rpcBytes("workspaceFiles/readBytes", args)
            val bytes = boundaryOf(body)?.let { multipartBytes(body, it) }
                ?: return FilePreview.Unavailable("The host answered in a form this app cannot read.")
            if (bytes.isEmpty()) {
                return FilePreview.Unavailable("This file is empty.")
            }
            FilePreview.Image(
                bytes = bytes,
                format = format,
                sizeBytes = bytes.size,
                absolutePath = null,
            )
        } catch (error: Throwable) {
            if (error is DshAuthException) onAuthFailure("workspaceFiles/readBytes", error)
            FilePreview.Unavailable(describe(error))
        }
    }

    /** Decoded bytes for a markdown picture, keyed by the address it was written as. */
    private val markdownImages = android.util.LruCache<String, ByteArray>(24)

    /**
     * The bytes behind one `![alt](url)`.
     *
     * Two kinds of address turn up in a transcript, and they are fetched differently:
     * an `https://…` picture is somewhere on the internet, and anything else is a path
     * in this session's workspace — which is the same `workspaceFiles/readBytes` the
     * Files panel uses, multipart and all.
     *
     * Nothing is fetched twice: the cache is keyed by the address as written, and it is
     * small because a picture is held as raw bytes until it is decoded.
     */
    suspend fun markdownImageBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        markdownImages.get(url)?.let { return it }
        val sessionId = _ui.value.currentSessionId
        // Logged because every way this can fail is silent: the card simply keeps the
        // alt text, which is indistinguishable from a picture that was never parsed.
        Log.d(TAG, "markdown image: url=$url session=$sessionId")
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    fetchRemoteImage(url)
                } else {
                    val scope = sessionId ?: return@runCatching null
                    val body = client.rpcBytes(
                        "workspaceFiles/readBytes",
                        JSONObject()
                            .put("workspaceFileScopeId", scope)
                            .put("path", url)
                            .put(
                                "options",
                                JSONObject().put(
                                    "range",
                                    JSONObject().put("offset", 0).put("length", MAX_PREVIEW_IMAGE_BYTES),
                                ),
                            ),
                    )
                    boundaryOf(body)?.let { multipartBytes(body, it) }
                }
            }.onFailure { Log.w(TAG, "markdown image failed: url=$url", it) }.getOrNull()
        } ?: return null
        if (bytes.isEmpty()) {
            Log.w(TAG, "markdown image empty: url=$url")
            return null
        }
        Log.d(TAG, "markdown image ok: url=$url bytes=${bytes.size}")
        markdownImages.put(url, bytes)
        return bytes
    }

    /**
     * One picture from the open internet.
     *
     * A markdown picture is a URL the *model* wrote, so it is fetched with no
     * credentials, no cookies and no redirects into the host's own API, and capped
     * before it is read into memory: an address in a transcript is not a reason to
     * download a gigabyte.
     */
    private fun fetchRemoteImage(url: String): ByteArray? {
        val request = okhttp3.Request.Builder().url(url).get().build()
        client.httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_PREVIEW_IMAGE_BYTES) return null
            return body.bytes().takeIf { it.size <= MAX_PREVIEW_IMAGE_BYTES }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is DshAuthException -> AUTH_ERROR
        is DshRpcException -> "${error.code}: ${error.message}"
        else -> error.message ?: error.javaClass.simpleName
    }

    override fun onCleared() {
        super.onCleared()
        followJob?.cancel()
        teardownStreams()
        // Both hooks live on process-scoped objects; only drop our own.
        if (app.foreground.onForeground === foregroundHook) app.foreground.onForeground = null
        if (client.onStreamAuthFailure === streamAuthHook) client.onStreamAuthFailure = null
    }

    private companion object {
        const val TAG = "DshVm"
        /** Sidebar diagnostics: Workspace membership against the session list. */
        const val SIDEBAR_TAG = "DshSidebar"
        const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Longer than an approval: a question is a real decision, and the cost of
         * being slow is only that the agent moves on without an answer.
         */
        const val QUESTION_TIMEOUT_MS = 15 * 60 * 1000L

        /** Base64 uploads inflate by ~33%, so cap before the host sees it. */
        const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024

        /** The web client's queue-preview cap (`QUEUE_PREVIEW_CHARS`). */
        const val QUEUE_PREVIEW_CHARS = 200

        /**
         * How long a queue-mode submission echo may wait for its host row before
         * it is dropped as a phantom. The host answers in a frame or two; this is
         * only the backstop for a prompt that raced the end of the turn.
         */
        const val PENDING_ECHO_TIMEOUT_MS = 15_000L

        /** Long enough to skip a keystroke's worth of queries, short enough to feel live. */
        const val REFERENCE_DEBOUNCE_MS = 120L

        /** The opening window `session/follow` is asked for, in messages. */
        const val FOLLOW_WINDOW_MESSAGES = 120

        /** One back-fill step when the reader scrolls to the top of the transcript. */
        const val HISTORY_PAGE_MESSAGES = 80

        /** Spaced retries for a transient connect failure; 3s, 6s, 9s, 12s. */
        const val MAX_CONNECT_RETRIES = 4
        const val RECONNECT_BASE_DELAY_MS = 3_000L

        /** The banner copy for a session the host refused. */
        const val AUTH_ERROR = "Session expired — sign in again."

        /** What a refused heal says when the host's own message is unavailable. */
        const val AUTH_REFUSED = "Your session expired and the saved credentials were refused."

        /**
         * A second auth failure inside this window is not healed again. The host
         * can refuse the socket every backoff while unary calls keep succeeding
         * (a login *would* work), and without a cooldown that is a probe loop.
         */
        const val AUTH_HEAL_COOLDOWN_MS = 10_000L

        /**
         * The words the host and its proxy use for "your session is gone". There
         * is no shared error-code vocabulary for it, so a stream failure is
         * classed as auth only when one of these appears.
         */
        val AUTH_MARKERS = listOf(
            "unauthor",
            "unauthenticated",
            "forbidden",
            "not authenticated",
            "not-authenticated",
            "authentication required",
            "auth-required",
            "auth/required",
            "session expired",
            "session/expired",
            "login required",
            "log in again",
        )

        /**
         * The host takes `UTC` or an Area/Location zone name and rejects everything
         * else, which is why the device's zone is only sent when it matches.
         */
        val ZONE_ID = Regex("[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)+")
    }
}

/**
 * Overlays one `subagentCatalog` child row's label and mode onto a roster row.
 *
 * A subagent's own stored title is its opening prompt ("You are …"), so both
 * display fields come from the durable descriptor instead. The title follows the
 * label only for a subagent: the label is never a plain session's name.
 *
 * Identity only, deliberately: the catalog says nothing about activity, so nothing
 * here may touch `running`.
 */
private fun SessionItem.withCatalog(child: SubagentCatalogEntry.Child?): SessionItem {
    if (child == null) return this
    val label = child.label?.takeIf { it.isNotBlank() } ?: subagentLabel
    val mode = child.mode.takeIf { it.isNotBlank() } ?: subagentMode
    if (label == subagentLabel && mode == subagentMode) return this
    return copy(
        subagentLabel = label,
        subagentMode = mode,
        title = if (isSubagent && label != null) label else title,
    )
}

/** Host-side preset keys mapped to their canonical copy (see `ui-permission-presets`). */
/**
 * A directory's display name, the way the web's `workspaceTitleOf` takes it: the
 * last path segment, tolerating a trailing separator and either platform's.
 *
 * Null when there is no directory to name, which is what lets the caller fall
 * through to the session id instead of printing an empty row.
 */
private fun basename(path: String?): String? =
    path?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() }

private fun permissionLabel(preset: String): String = when (preset) {
    "read-only" -> "Read Only"
    "workspace-write" -> "Workspace Write"
    FULL_ACCESS_PRESET -> "Full access"
    else -> preset
}
