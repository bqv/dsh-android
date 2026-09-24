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
import kotlinx.coroutines.delay
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
import uk.xa0.dsh.data.ThemeMode
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.LiveAttempt
import uk.xa0.dsh.model.MessageAttachment
import uk.xa0.dsh.model.FilePreview
import uk.xa0.dsh.model.SessionHeader
import uk.xa0.dsh.model.SessionSearch
import uk.xa0.dsh.model.SettingsApply
import uk.xa0.dsh.model.SettingsWritePlan
import uk.xa0.dsh.model.SessionSearchHit
import uk.xa0.dsh.model.HostSettings
import uk.xa0.dsh.model.SessionStats
import uk.xa0.dsh.model.SUBAGENT_ACTIVITY_INACTIVE
import uk.xa0.dsh.model.SUBAGENT_ACTIVITY_RUNNING
import uk.xa0.dsh.model.SUBAGENT_ATTACHMENT_INVALID
import uk.xa0.dsh.model.SUBAGENT_ATTACHMENT_REFUSAL
import uk.xa0.dsh.model.SUBAGENT_FILE_UNSUPPORTED
import uk.xa0.dsh.model.SubagentCatalog
import uk.xa0.dsh.model.SubagentCatalogEntry
import uk.xa0.dsh.model.SubagentTarget
import uk.xa0.dsh.model.TodoItem
import uk.xa0.dsh.model.WorkspaceFileLoader
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
import uk.xa0.dsh.model.parseSubagentCatalog
import uk.xa0.dsh.model.str
import uk.xa0.dsh.model.subagentInterruptArgs
import uk.xa0.dsh.model.subagentPromptRequest
import uk.xa0.dsh.model.subagentTargetOf
import uk.xa0.dsh.net.DshAuthException
import uk.xa0.dsh.net.DshRpcException
import uk.xa0.dsh.net.DshUnreachableException
import uk.xa0.dsh.net.MuxState
import uk.xa0.dsh.net.StreamEvent
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

data class ModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    /** Reasoning efforts this exact route accepts; empty when it has none. */
    val efforts: List<EffortOption> = emptyList(),
    val defaultEffort: String? = null,
)

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
 * One background job the host is running (or ran) for a session.
 *
 * These arrive on the same control stream as the queue — the host publishes a
 * `jobs` frame per session on every change — so there is no RPC to call and no
 * polling: a job that starts, stops or fails is a push.
 */
data class JobItem(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String?,
    val startedAt: Long,
    val finishedAt: Long?,
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
 * The web's pause between the last keystroke and a `session/search` request
 * (`WorkspaceBrowser.tsx`). The host scans every visible session's messages for
 * one query, so firing per keystroke would be a scan per character.
 */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * The pause before a `subagents/list` pull that a frame asked for.
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
 * a fold. Shared by [DshViewModel.reconcileLiveRunning] and the catalog fold: a
 * read sampled a beat before the host flushed a child's stop frame must not
 * blink the open session's Stop button back to Send mid-turn.
 */
private const val LIVE_QUIET_MS = 1500L

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
    val running: Boolean = false,
    /**
     * The journal has closed this session's own turn, whatever the agent registry
     * still reports — see [uk.xa0.dsh.model.TranscriptReducer.ownTurnClosed]. Set
     * from [DshViewModel.publishTranscript]; read through [ownTurnInFlight].
     */
    val ownTurnClosed: Boolean = false,
    /**
     * `parentAvailable` for the open addressed child, from `subagents/list`.
     * Null for every ordinary session and until that read lands — the composer
     * gate treats unknown as available on purpose, so the composer can never
     * flicker into a read-only frame it would have to take back.
     */
    val subagentParentAvailable: Boolean? = null,
    /**
     * The `subagents/list` catalogs this client holds, keyed by the parent they
     * describe. Each is the host's own direct-child answer for that parent: the
     * durable rows, their modes and labels, and the live Agent status the list
     * summary is not a dependable source for — it serves any session the
     * controller has not attached as cold, and that reads `running: false`.
     */
    val subagentCatalogs: Map<String, SubagentCatalog> = emptyMap(),
    val models: List<ModelOption> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    val selectedModel: ModelOption? = null,
    /** Active reasoning effort for the selected route, when it has any. */
    val selectedEffort: String? = null,
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
     * A job finished since the list was last opened — the jobs seat's green dot.
     * Purely client-side, like the session rows' finished-unseen marker.
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
    val approval: PendingApproval? = null,
    /** An agent is waiting on an answer; the app must answer or the call fails. */
    val questions: PendingQuestionSet? = null,

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
}

/**
 * Owns the session the user is looking at and translates DSH's journal into UI
 * state. Everything the app can do to the host happens through here, so the
 * Compose layer stays free of protocol knowledge.
 */
@OptIn(FlowPreview::class)
/** Live running sessions reported by the host, shared with [SessionItem.withLiveRunning]. */
private val liveRunningIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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

    /** True while a resume re-sync is running, so two resumes cannot stack. */
    private var resumeResyncInFlight = false

    /**
     * Held so [onCleared] can unregister exactly this hook: the tracker is
     * process-scoped and would otherwise keep a destroyed view model alive.
     */
    private val foregroundHook: () -> Unit = { onAppResumed() }

    /** Same idea for the mux handshake hook on [DshClient]. */
    private val streamAuthHook: () -> Unit = { onAuthFailure("event socket", null) }

    private var historyJob: Job? = null

    /**
     * The `subagents/list` reads behind both the composer's read-only gate and the
     * per-child activity the roster cannot report. Kept apart from [historyJob]
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
    private val subagentCatalogPending = HashSet<String>()
    /** Parents a fetch was asked for while it was already in flight (`:418`). */
    private val subagentCatalogStale = HashSet<String>()
    /** Keys of the debounced pulls, so closing a disclosure can drop one. */
    private val subagentCatalogDebounce = HashMap<String, Job>()
    /**
     * Parents whose catalog a disclosure is currently showing, the app's
     * `openCatalogs` (`manager.ts:438`). Membership under one of these is the
     * change a reader would actually see, so only these are re-read on a frame.
     */
    private val subagentCatalogOpen = HashSet<String>()
    /**
     * Child activity seen on a live frame while a catalog read was in flight.
     *
     * The sample a response carries predates that frame, so folding it raw would
     * let it un-run a child the user just watched start (or re-run one that
     * stopped) — the web's `activityRows` (`:367-372`, `withCatalogMutations`).
     */
    private val subagentCatalogActivity = HashMap<String, Boolean>()
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
    /** Sessions the host says are running right now, from `api-session/status`. */
    private val liveRunning: MutableSet<String> = liveRunningIds
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
    /** Latest background jobs per session, from the same stream. */
    private val jobsBySession = java.util.concurrent.ConcurrentHashMap<String, List<JobItem>>()
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
            while (true) {
                val open = runCatching { client.mux().state.value == MuxState.OPEN }.getOrDefault(false)
                if (_ui.value.socketConnected != open) {
                    _ui.value = _ui.value.copy(socketConnected = open)
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
        if (visible) return
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
                _ui.value = _ui.value.copy(sessions = list.map { it.withLiveRunning() })
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
                // Cancel and re-open rather than trusting `isActive`: a frozen
                // collector is active and dead at the same time, and only a fresh
                // opening frame carries the baseline (archived ids included).
                reopenStreamsForResume()
                runCatching { fetchSessions() }
                    .onFailure { if (it is DshAuthException) onAuthFailure("session/list", it) }
                    .onSuccess { list ->
                        _ui.value = _ui.value.copy(sessions = list.map { it.withLiveRunning() })
                        warnOnMissingMembers(list)
                        syncRunningFromList(list)
                    }
                // The roster cannot correct a subagent's activity, so the catalogs
                // are what a resume repaints them from (`manager.ts:796-798`).
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
                // A baseline is the whole world: any session missing from it has
                // no jobs, so keeping the old map would strand finished rows.
                jobsBySession.clear()
                payload.obj("queues")?.let { all ->
                    all.keys().forEach { sessionId ->
                        queues[sessionId] = parseQueue(all.optJSONArray(sessionId))
                    }
                }
                val openSession = _ui.value.currentSessionId
                if (openSession != null) {
                    payload.obj("jobs")?.let { all ->
                        all.keys().forEach { sessionId ->
                            jobsBySession[sessionId] = parseJobs(all.optJSONArray(sessionId))
                        }
                    }
                    publishJobsForCurrent()
                }
                // Seed the open session's folded projections so a control delta
                // that only carries one key still has its siblings in hand.
                val current = _ui.value.currentSessionId
                if (current != null) {
                    val block = payload.obj("projections")?.obj(current)?.obj("values")
                    if (block != null) {
                        liveProjections = block
                        applySessionProjections(block)
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

            "jobs" -> {
                val sessionId = value.str("sessionId")
                if (sessionId.isEmpty() || sessionId != _ui.value.currentSessionId) return
                publishJobs(parseJobs(value.arr("jobs")))
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
                if (value.str("key") == "subagentCatalog") refreshSubagentCatalogSoon(sessionId)
                val merged = liveProjections ?: JSONObject()
                merged.put(value.str("key"), value.opt("value"))
                liveProjections = merged
                applySessionProjections(merged)
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
     * Publishes one `jobs` frame for the open session.
     *
     * A job that finishes while the list is closed raises the seat's finished
     * marker, the same convention the session rows use for a turn that ended
     * while you were elsewhere.
     */
    private fun publishJobs(items: List<JobItem>) {
        val current = _ui.value.currentSessionId ?: return
        jobsBySession[current] = items
        publishJobsForCurrent()
    }

    /** Publishes whichever job list belongs to the session now on screen. */
    private fun publishJobsForCurrent() {
        val current = _ui.value.currentSessionId
        val items = current?.let { jobsBySession[it] }.orEmpty()
        val previous = _ui.value.jobs
        val finishedNow = previous.any { it.running } && items.none { it.running }
        _ui.value = _ui.value.copy(
            jobs = items,
            jobsFinishedUnseen = _ui.value.jobsFinishedUnseen || finishedNow,
        )
    }

    private fun parseJobs(array: JSONArray?): List<JobItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val job = array.optJSONObject(index) ?: return@mapNotNull null
            JobItem(
                id = job.str("id"),
                kind = job.str("kind"),
                label = job.str("label").ifEmpty { job.str("kind") },
                status = job.str("status"),
                detail = job.str("detail").takeIf { it.isNotEmpty() },
                startedAt = job.long("startedAt"),
                finishedAt = if (job.has("finishedAt")) job.long("finishedAt") else null,
            )
        }
    }

    /** Opening the jobs list clears its finished marker. */
    fun markJobsSeen() {
        if (_ui.value.jobsFinishedUnseen) {
            _ui.value = _ui.value.copy(jobsFinishedUnseen = false)
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
     * Starts (or reuses) a blank session inside a registered workspace.
     *
     * Reuse demands *membership*, not just a matching directory: the host only puts
     * a session in a workspace when it was created through `workspaceId`, so a blank
     * sitting at the right cwd but attached to nothing is not the session the user
     * means — the web draws the same distinction. An archived blank is skipped too,
     * or "New Session" would reopen a session the user has just filed away.
     */
    fun startSessionInWorkspace(workspaceId: String) {
        if (workspaceId.isBlank()) return
        val workspace = _ui.value.workspaces.firstOrNull { it.id == workspaceId }
        val archived = _ui.value.archivedSessionIds
        val existing = workspace?.let { known ->
            _ui.value.sessions.firstOrNull { session ->
                session.blank && !session.isSubagent && session.id !in archived &&
                    session.cwd == known.path && session.id in known.sessionIds
            }
        }
        if (existing != null) {
            openSession(existing.id)
            return
        }
        // No local copy of the workspace yet is fine: the host resolves the cwd
        // from the id, so the create is still correct.
        newSession(
            cwd = null,
            preset = _ui.value.agentPreset.takeIf { it.isNotBlank() },
            workspaceId = workspaceId,
        )
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
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val projections = item.obj("projections")?.obj("values")
            // Always read through the Json extensions: org.json's raw optString
            // turns an explicit JSON null into the string "null".
            val blank = item.optBoolean("blank")
            val stored = projections.str("title")
            val subagent = catalog[item.str("sessionId")]
            val parentSessionId = item.str("parentSessionId").takeIf { it.isNotEmpty() }
            // Where a catalog this client already holds names this child, its row
            // wins the label and the mode: see [applyCatalogLabels] for why the
            // read-time sample outranks the projection.
            val held = parentSessionId?.let { subagentCatalogs[it]?.child(item.str("sessionId")) }
            result += SessionItem(
                id = item.str("sessionId"),
                // Blank rows carry `title: null`; the web UI substitutes its
                // localized "New Session" label for them. A non-blank row whose
                // projection has not landed yet is just untitled.
                title = when {
                    subagent != null && subagent.first.isNotBlank() -> subagent.first
                    blank -> "New Session"
                    stored.isNotBlank() -> stored
                    else -> "Untitled session"
                },
                cwd = item.str("cwd").takeIf { it.isNotEmpty() },
                updatedAt = item.optLong("updatedAt"),
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
     * A frozen process collected no frames, so the sample it holds is the only
     * thing still claiming a child is running — and the roster pull that precedes
     * this cannot correct a subagent ([reconcileLiveRunning] exempts them). This is
     * the read that can, which is why a resume owes it rather than waiting for the
     * child's next frame.
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
                    sessions = list.map { it.withLiveRunning() },
                    sessionsLoading = false,
                )
                warnOnMissingMembers(list)
                syncRunningFromList(list)
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
                    sessions = list.map { it.withLiveRunning() },
                    sessionsLoading = false,
                )
                warnOnMissingMembers(list)
                syncRunningFromList(list)
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
                _ui.value = _ui.value.copy(sessions = list.map { it.withLiveRunning() })
                warnOnMissingMembers(list)
                syncRunningFromList(list)
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
     * Applies one live `api-session/status` frame to the row it names.
     *
     * `session/list` is the authority for ordinary sessions, and for a subagent it
     * is one only while the child is attached — an unattached child is served cold
     * and reads `running: false` (`api-session-controller/list.ts:152`). So a
     * child's activity arrives both from this event and from [applyCatalogActivity].
     * Rows are patched in place; the list refresh keeps ownership of ordering and
     * of the "finished while you were elsewhere" dots.
     */
    private fun applyLiveStatus(sessionId: String, running: Boolean) {
        if (running) liveRunning.add(sessionId) else liveRunning.remove(sessionId)
        // A frame that beats a catalog read's settle is newer than the sample that
        // read carries; recording it here is what stops the response from
        // reverting it (`manager.ts:367-372`).
        synchronized(subagentCatalogLock) {
            if (subagentCatalogPending.isNotEmpty()) subagentCatalogActivity[sessionId] = running
        }
        val sessions = _ui.value.sessions
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        if (sessions[index].running == running) return
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(running = running)
        _ui.value = _ui.value.copy(sessions = updated)
    }

    /**
     * Drops live "running" claims that a whole-world `session/list` pull denies.
     *
     * The live set is only as good as the frames that reached this process, and a
     * backgrounded app receives none: a session it saw start kept its ongoing dot
     * for as long as the id stayed in the set, because the display is
     * `list.running || live` and the list's "no" could not win. The pull is the
     * authority, so it wins — with two exemptions. A subagent is never reported as
     * running by `session/list` at all, and the open session is held back by the
     * same quiet window that keeps a racing pull from blinking the composer's Stop
     * button back to Send mid-turn.
     */
    private fun reconcileLiveRunning(list: List<SessionItem>) {
        val current = _ui.value.currentSessionId
        val quiet = System.currentTimeMillis() - lastEventAt > LIVE_QUIET_MS
        val stale = liveRunning.filter { id ->
            val item = list.firstOrNull { it.id == id } ?: return@filter true
            if (item.isSubagent || item.running) return@filter false
            if (id == current && !quiet) return@filter false
            true
        }
        if (stale.isEmpty()) return
        stale.forEach { liveRunning.remove(it) }
        Log.d(TAG, "cleared stale live-running: $stale")
    }

    private fun syncRunningFromList(list: List<SessionItem>) {
        // `session/list` outranks a live frame for an ordinary session: a `true`
        // the list contradicts is a "stopped" that arrived while the app was not
        // listening (a frozen process collects nothing), and trusting it forever
        // left the row's ongoing dot stuck on for a session that had long finished.
        reconcileLiveRunning(list)

        // The effective set, not the raw one: the list reports every subagent as
        // not running, so a child's own finish is only ever visible through the
        // live frames folded in here.
        val nowRunning = list.filter { it.withLiveRunning().running }.map { it.id }.toSet()

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
                val title = _ui.value.sessions.firstOrNull { it.id == sessionId }?.title
                // A subagent can outlive its parent's turn, so "the agent" was
                // simply the wrong noun for the child's own idle alert.
                val subject = if (app.attention.isSubagent(sessionId)) "subagent" else "agent"
                alert(
                    id = Attention.idleId(sessionId),
                    sessionId = sessionId,
                    title = if (title.isNullOrBlank()) "Session is idle" else "Done: $title",
                    text = "The $subject finished its turn. Tap to open the session.",
                )
            }
        }
        previousRunning = nowRunning

        // The caller mapped the fresh list through `withLiveRunning` *before* this
        // ran, so a stale claim cleared above is still painted on its row. Fold the
        // cleaned set in once more and republish only when it actually changes.
        val corrected = _ui.value.sessions.map { it.withLiveRunning() }
        if (corrected != _ui.value.sessions) {
            _ui.value = _ui.value.copy(sessions = corrected)
        }

        val current = _ui.value.currentSessionId ?: return
        val item = list.firstOrNull { it.id == current } ?: return
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
     * One `subagents/list` read, single-flight per parent.
     *
     * A second ask while one is in flight is not a second call: the response the
     * caller is waiting on predates whatever asked again, so the ask is recorded
     * and answered by exactly one trailing pull once that response lands — the
     * web's `catalogStale` re-arm (`manager.ts:412-419`). Without it the only
     * carrier of a membership change observed mid-flight would be the next
     * unrelated frame.
     *
     * A response is folded into three places, in this order: the live-running set
     * (per-child `activity`, the one source that covers a child `session/list`
     * serves cold), the roster's label/mode for the rows it names, and — when the
     * parent still belongs to the session on screen — `parentAvailable`, which is
     * the read-only gate's only input. A failure is deliberately left as unknown
     * (it is not the reader's problem, and unknown keeps the composer), except for
     * a dead cookie, which the banner has to say.
     */
    private fun refreshSubagentCatalog(parentSessionId: String) {
        if (parentSessionId.isEmpty()) return
        synchronized(subagentCatalogLock) {
            if (!subagentCatalogPending.add(parentSessionId)) {
                subagentCatalogStale.add(parentSessionId)
                return
            }
        }
        viewModelScope.launch {
            val answer = runCatching {
                client.rpc("subagents/list", JSONObject().put("parentSessionId", parentSessionId))
            }
            answer.onSuccess { value ->
                val catalog = withInFlightActivity(parseSubagentCatalog(value))
                subagentCatalogs[parentSessionId] = catalog
                _ui.value = _ui.value.copy(subagentCatalogs = HashMap(subagentCatalogs))
                applyCatalogActivity(catalog)
                applyCatalogLabels(parentSessionId, catalog)
                publishParentAvailable(parentSessionId, catalog)
            }.onFailure { error ->
                Log.w(TAG, "subagents/list $parentSessionId failed: ${describe(error)}")
                if (error is DshAuthException) onAuthFailure("subagents/list", error)
            }
            val rearm = synchronized(subagentCatalogLock) {
                subagentCatalogPending.remove(parentSessionId)
                val asked = subagentCatalogStale.remove(parentSessionId)
                // With no read left in flight nothing can fold these, and keeping
                // them would let a frame about one parent's child decide a later
                // read of a different parent's catalog.
                if (subagentCatalogPending.isEmpty()) subagentCatalogActivity.clear()
                asked
            }
            if (rearm) refreshSubagentCatalog(parentSessionId)
        }
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
     * Folds child activity frames observed while a read was in flight over its
     * response, and forgets the ones it consumed.
     *
     * Only the ids the response actually names are taken: an id that belongs to
     * some other parent's catalog is that catalog's problem, and this read is not
     * evidence either way about it.
     */
    private fun withInFlightActivity(catalog: SubagentCatalog): SubagentCatalog {
        val overrides = synchronized(subagentCatalogLock) {
            if (subagentCatalogActivity.isEmpty()) return catalog
            val consumed = HashMap<String, Boolean>()
            for (child in catalog.children) {
                subagentCatalogActivity.remove(child.id)?.let { consumed[child.id] = it }
            }
            consumed
        }
        if (overrides.isEmpty()) return catalog
        return catalog.copy(
            entries = catalog.entries.map { entry ->
                if (entry !is SubagentCatalogEntry.Child) return@map entry
                val running = overrides[entry.id] ?: return@map entry
                entry.copy(
                    activity = if (running) SUBAGENT_ACTIVITY_RUNNING else SUBAGENT_ACTIVITY_INACTIVE,
                )
            },
        )
    }

    /**
     * Applies one catalog to the app's live-running view.
     *
     * This is the app's stand-in for the web's per-row catalog dot, which reads
     * `entry.activity` verbatim (`SubagentHeaderLineage.tsx`) — so the catalog's
     * answer wins in both directions, including over a stale `running: true` that
     * no whole-roster pull can clear, because [reconcileLiveRunning] exempts
     * subagents from the list's authority. `activity` and the list's `running` are
     * the same Agent-registry sample (`subagent/control.ts:catalogView` and
     * `api-session-controller/list.ts:114-152`), so they can only differ by the
     * instant they were taken, and the catalog is the later read.
     *
     * The one exemption is the open session, which keeps the quiet-window guard
     * [reconcileLiveRunning] documents: a read sampled a beat before the host
     * flushed that session's own stop frame must not blink the composer's Stop
     * back to Send mid-turn.
     */
    private fun applyCatalogActivity(catalog: SubagentCatalog) {
        val current = _ui.value.currentSessionId
        val quiet = System.currentTimeMillis() - lastEventAt > LIVE_QUIET_MS
        val sessions = _ui.value.sessions
        val patched = sessions.toMutableList()
        var changed = false
        for (child in catalog.children) {
            if (!child.running && child.id == current && !quiet) continue
            if (child.running) liveRunning.add(child.id) else liveRunning.remove(child.id)
            val index = patched.indexOfFirst { it.id == child.id }
            if (index < 0 || patched[index].running == child.running) continue
            patched[index] = patched[index].copy(running = child.running)
            changed = true
        }
        if (changed) _ui.value = _ui.value.copy(sessions = patched)
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
        _todos.value = emptyList()
        app.attention.visibleSessionId = sessionId
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
                                applySessionProjections(value.obj("projections")?.obj("values"))
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
     * Creates a session, either inside a registered workspace or at a plain `cwd`.
     *
     * The host takes one or the other and rejects both (`gateway/bad-request`), and
     * only the workspace form **attaches** the session to that workspace's
     * membership — which is exactly what puts it in that group in the sidebar.
     * Creating by `cwd` alone leaves it Ungrouped, which is why adopting a workspace
     * has to go through the id.
     */
    fun newSession(cwd: String?, preset: String?, workspaceId: String? = null) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, errorNeedsSignIn = false)
            runCatching {
                val request = JSONObject()
                if (!workspaceId.isNullOrBlank()) {
                    request.put("workspaceId", workspaceId)
                } else if (!cwd.isNullOrBlank()) {
                    request.put("cwd", cwd)
                }
                if (!preset.isNullOrBlank()) request.put("agentPreset", preset)
                client.rpc("session/create", JSONObject().put("request", request))
            }.onSuccess { value ->
                val id = value.optString("sessionId")
                _ui.value = _ui.value.copy(busy = false)
                refreshSessions()
                if (id.isNotEmpty()) openSession(id)
            }.onFailure { error ->
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
     * choosing a different workspace means adopting an existing blank session
     * there or creating one. Reusing avoids piling up blanks, which is what the web
     * client does when a workspace is connected.
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
        _ui.value.workspaces.firstOrNull { it.path == trimmed }?.let { workspace ->
            startSessionInWorkspace(workspace.id)
            return
        }
        val archived = _ui.value.archivedSessionIds
        val existing = _ui.value.sessions.firstOrNull {
            it.blank && !it.isSubagent && it.id !in archived && it.cwd == trimmed
        }
        if (existing != null) {
            openSession(existing.id)
        } else {
            newSession(trimmed, _ui.value.agentPreset.takeIf { it.isNotBlank() })
        }
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
     */
    fun send(text: String, mode: String = BusyEnter.QUEUE) {
        val sessionId = _ui.value.currentSessionId ?: return
        val riding = _ui.value.attachments
        // An attachment on its own is a complete message: a screenshot with no
        // caption is an ordinary thing to send.
        if (text.isBlank() && riding.isEmpty()) return
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
        val default = catalog.optJSONObject("default")
        val selected = default?.let { selection ->
            options.firstOrNull {
                it.provider == selection.str("provider") && it.model == selection.str("model")
            }
        }
        _ui.value = _ui.value.copy(
            models = options,
            providerOrder = order,
            selectedModel = selected,
            selectedEffort = default?.str("reasoningEffort")?.takeIf { it.isNotEmpty() }
                ?: selected?.defaultEffort,
        )
    }

    /**
     * Model and reasoning effort go through the same call — `session/selectModel`
     * takes an optional `reasoningEffort`; there is no separate setter.
     */
    fun selectModel(option: ModelOption, effort: String? = null) {
        val sessionId = _ui.value.currentSessionId
        val chosenEffort = effort ?: option.defaultEffort
        _ui.value = _ui.value.copy(selectedModel = option, selectedEffort = chosenEffort)
        if (sessionId == null) return
        viewModelScope.launch {
            runCatching {
                val request = JSONObject()
                    .put("sessionId", sessionId)
                    .put("provider", option.provider)
                    .put("model", option.model)
                chosenEffort?.let { request.put("reasoningEffort", it) }
                client.rpc("session/selectModel", JSONObject().put("request", request))
            }.onFailure { error -> showFailure(error, "session/selectModel") }
        }
    }

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

        _ui.value = _ui.value.copy(
            currentPermission = permission.ifEmpty { _ui.value.currentPermission },
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
        return runCatching {
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
    }

    fun setPermission(preset: String) {
        viewModelScope.launch {
            runCommand("/permission $preset")
                .onSuccess { _ui.value = _ui.value.copy(currentPermission = preset) }
                .onFailure {
                    showFailure(it, "commands/execute", "Could not switch access mode: ")
                }
        }
    }

    /** Runs a bare slash command from the composer's command menu. */
    fun executeCommand(line: String) {
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
     */
    fun addAttachment(uri: Uri) {
        val sessionId = _ui.value.currentSessionId ?: return
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
 * Overlays the live `api-session/status` signal onto one host list row.
 *
 * A subagent is reported accurately only while its session is attached; one the
 * controller serves cold reads `running: false`, and the child runs inside its
 * parent's agent, so the list summary is not where a child's activity can be read
 * from. The event stream and the parent's `subagents/list` catalog are the two
 * sources that can, and this reads the fold they share.
 */
private fun SessionItem.withLiveRunning(): SessionItem =
    if (running) this else copy(running = id in liveRunningIds)

/**
 * Overlays one `subagents/list` child row's label and mode onto a roster row.
 *
 * A subagent's own stored title is its opening prompt ("You are …"), so both
 * display fields come from the durable descriptor instead. The title follows the
 * label only for a subagent: the label is never a plain session's name.
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
private fun permissionLabel(preset: String): String = when (preset) {
    "read-only" -> "Read Only"
    "workspace-write" -> "Workspace Write"
    FULL_ACCESS_PRESET -> "Full access"
    else -> preset
}
