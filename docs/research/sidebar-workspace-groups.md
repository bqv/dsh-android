# Sidebar: why the `dsh-android` workspace group "disappears"

Round 30–32, 2026-09-24. Read-only host probes + on-emulator diagnosis.

## Symptom

On a fresh app launch the sessions drawer shows the `dsh-android` group with its
active session. After opening any other session the group is gone, and it stays
gone until the app is force-stopped and relaunched. It happens in both Group
modes and both Order modes. The drawer's other groups (root, tmp, ref, chance,
user, Ungrouped) are unaffected.

## Root cause: the drawer revealed the open row, and Compose's focus system put it back

Two things scrolled the list to the open session, both of which push every
section above that row — including the whole first workspace — off-screen:

1. `SessionsDrawer` had
   `LaunchedEffect(drawerOpen) { … listState.animateScrollToItem(target, scrollOffset = -headerPx) }`,
   which pins the open row to the top on every open. Workspace order is
   `dsh-android, tmp, ref, chance, user, root`, so as soon as the open session
   lives outside `dsh-android` the first group is scrolled away.
2. Even after removing that, the drawer still opened on the section owning the
   open session — verified on the emulator. A bare
   `remember { LazyListState() }` is not enough: Compose's focus system scrolls
   the previously focused row (normally the open session, the last row the user
   tapped) back into view when the sheet reopens.

The web does neither: its sidebar keeps its scroll position, and its only reveal
is a one-shot `scrollIntoView({block: 'nearest'})` on the current row, armed when
a *search result* is opened and acknowledged straight after
(`ui-workspace/src/client/rows/Rows.tsx:409-413`, `WorkspaceBrowser.tsx:851-856`).

## Evidence

Host (live RPC, read-only; the parent's own session was never probed):

* `workspace/follow` baseline: 6 workspaces in registry order
  `dsh-android, tmp, ref, chance, user, root`; `dsh-android.sessionIds` is
  `[session-f04ac8d7… (archived), session-2615b629… (active)]`.
* `session/list`: 197 items, `session-2615b629…` present (`blank=false`,
  `cwd=/home/user/var/work/dsh-android`). Twelve concurrent calls all returned
  the full list with 2615 — the host never drops it.

App (diagnostic build, logs around the drawer's section build + screenshots):

* `DshDrawer: sections n=6 ws=dsh-android,tmp,ref,chance,user,root`
* `DshDrawer: group dsh-android ids=2 items=1 everyKnown=true emit=true`

So the group **is** in the drawer's section list; the skip condition is not what
hides it. Visual proof: opened on a session in `root`, the first visible section
is `root`; after scrolling up, `dsh-android (1)` and "Native Android app mirroring
DSH web UI" sit at the top (`.probe/up3.png`, `.probe/persist-scrollup.png`).

## Refuted hypotheses

* **The group-skip condition silently drops the group.** Not the cause: `byId`
  resolves `dsh-android`'s active member, the group is emitted, and it renders
  once scrolled to.
* **`openSession` / `refreshSessionsSoon` replace `_ui.sessions` or
  `_ui.workspaces` with a shorter list.** `openSession` touches neither field,
  and no `workspace/follow` frame arrived during the reproduction (a 10-minute
  watcher saw only its opening baseline). `session/list` always contained 2615.
* **The archived set contains the active session.** The host archive set never
  contains `session-2615b629…`.

## Secondary finding: the session count 44 → 34 → 35

The header count is `matched.size` — the roster minus subagents and, once the
workspace baseline has landed, minus the archive set. On a cold start the
baseline (and its `archivedSessionIds`) has not arrived yet, so the count briefly
includes archived sessions, then drops as the frame lands and as sibling agents
archive their own sessions. It is churn, not a cap: `session/list` returns all
197 items every time.

## Fix

`SessionsDrawer.kt`:

* Replaced the "reveal the open row" effect with an explicit anchor to item 0 on
  open: the top is the one position that always shows every group. (The open row
  is still tinted, so it is findable.)
* The group-skip condition now also fires when a workspace owns a member the
  *unfiltered* roster does not know about, so a lagging list can only degrade a
  group to an empty header, never erase the whole Workspace. A workspace with no
  members still shows (the "Add workspace…" case).

`DshViewModel.kt`: one `Log.w` when a `session/list` response omits an id the
workspace registry accounts for — the exact shape of the suspected (and refuted)
failure, kept so a real occurrence is diagnosable instead of silent.

## Per-session subagent disclosure (new requirement)

* Global `showSubagents` state and the "Subagents" filter chip are gone;
  top-level rows are always non-subagents (the web's `sessionVisible()`).
* A row's activity dot goes `ongoing` when `rollup.running > 0`
  (`indexSubagentRollups`, i.e. the web's `sessionStatuses()` precedence), and
  the running-descendant count is drawn next to the dot — the deliberate
  phone-only divergence, since there is no aria layer here.
* Running is a three-source fold: the session's own `session/list` flag, the
  live `api-session/status` frames (`liveRunning`), and the subagent rollup. A
  whole-world `session/list` pull is the authority and clears a live claim it
  denies — except for a subagent (`session/list` never reports one running) and
  the open session inside a 1500 ms quiet window, so a racing pull cannot blink
  the composer's Stop back to Send (`DshViewModel.kt:2147-2237`).
* A caret appears only on a session with descendants and discloses that
  session's children inline, indented, via `subagentChildrenOf`.
* **Children obey the view settings** (user-reported defect): they are filtered
  by `archivedSessionIds` (unless `showArchived`), and ordered by the Order
  setting — Updated = newest-first, Manual = oldest-first (a child has no host
  manual order; the section's own Manual order comes from the Workspace's
  `sessionIds`).
* The workspace header count counts top-level sessions, not disclosed children.
* An addressed child's composer is not read-only by construction: a `one-shot`
  record always shows the frame, a `continuable` child whose parent is
  unavailable shows it only once the child stops running, and unknown
  availability keeps the normal composer (`model/SubagentContinuation.kt:176-188`,
  `ui/components/SubagentReadOnlyComposer.kt`).
* A send from that composer goes out as `subagents/prompt` carrying the durable
  parent address and the ordinary `queue`/`steer` mode as `delivery`; Stop uses
  `subagents/interruptByParent`, and `subagents/list` supplies `parentAvailable`,
  read once per open (`DshViewModel.kt:2284-2300,2636-2738,2940-2964`). A staged
  file is refused client-side as `subagent/attachment-invalid`, before the call
  (`DshViewModel.kt:2649-2662`).

## Group/Order persistence (new requirement)

* `DshConfig` gained `drawerGroupByWorkspace: Boolean = true` and
  `drawerOrderByUpdated: Boolean = false`; `ConfigStore.load()`/`save()` persist
  them under `drawer_group_by_workspace` / `drawer_order_by_updated`. The
  Archived switch persists the same way (`drawerShowArchived` /
  `drawer_show_archived`) — see below.
* `UiState` exposes them; `setDrawerGroupByWorkspace()` / `setDrawerOrderByUpdated()`
  follow the `updateTheme` load/copy/save pattern.
* `SessionsDrawer` takes them as parameters plus callbacks (pure function of its
  inputs); the `rememberSaveable` copies are gone.
* One call-site block in `ChatScreen.kt` passes them (the drawer cannot be wired
  without it).

## Archived as a switch (new requirement)

`Archived` was a text chip sitting beside the Group/Order `MiniChoice` chips, so a
binary filter read as a third peer choice. It is now a Material3 `Switch` with its
label in the filter row (count on the left, right-aligned label+switch). The whole
label+switch is one `Modifier.toggleable(role = Role.Checkbox)`, and the `Switch`
itself carries `clearAndSetSemantics {}` so a screen reader announces a single
checkbox labelled "Archived". Group/Order chips are untouched.

## Search, Refresh, Add workspace (new requirement)

* The query box filters the roster locally by title and cwd
  (`SessionsDrawer.kt:172-185`). The host content-search half exists in the
  ViewModel — one debounced request (250 ms), generation-guarded so a superseded
  query cannot land (`model/SessionSearch.kt`, `DshViewModel.kt:2042-2087`), and a
  deployment without a content index latches the failure and stops sending
  (`DshViewModel.kt:2100-2104`) — but it is not fed (see below).
* The two sets merge the way the web's `deriveSearchResults` does: local name/path
  matches first in roster order, then host hits the roster does not already show,
  each carrying its workspace label; a hit the roster does not list is dropped
  (`SessionsDrawer.kt:202-233`, `ui/search/SessionSearchRows.kt`). A query replaces
  the grouped tree with the flat result list; a tap opens the session only, since
  `session/search` carries no seq (`SessionsDrawer.kt:542-555,676-678`).
* **Not fed at the call site.** `ChatScreen`'s `SessionsDrawer(...)` passes none of
  `searchHits`/`searchLoading`/`searchError`/`searchHasMore`/`onSearch`, so
  today only the local name/cwd filter is live (`ui/ChatScreen.kt:411-453`); the
  host half of the merge is constructed but never reaches the drawer.
* Refresh is "start following again", not a re-fetch: it re-dials the mux,
  re-subscribes every stream and then pulls `session/list`, holding the icon's
  spin for at least 700 ms so the tap has a visible consequence
  (`DshViewModel.kt:2000-2025`, `REFRESH_MIN_SPIN_MS`).
* "Add workspace…" is a real row under the Group/Order chips
  (`SessionsDrawer.kt:498-524`); it closes the drawer and opens the directory
  browser, whose pick calls `workspace/create` and then refreshes the list
  (`ui/ChatScreen.kt:444-448`, `DshViewModel.kt:1844-1855`). That browser is the
  `directoryPicker/list` + `directoryPicker/createDirectory` pair
  (`DshViewModel.kt:1770-1833`).

## Verification (emulator `127.0.0.1:5555`, final build)

* Build: `./build.sh :app:assembleDebug` — green.
* Group loss: drawer opened on a fresh process with `dsh-android (1)` first, in
  WorkSpace/Manual and again in WorkSpace/Updated (`.probe/sw-open.png`,
  `.probe/final-relaunch.png`). No auto-scroll to the open session.
* Persistence: Group and Order changed, `am force-stop`, relaunch → the chips come
  back on the chosen modes (`WorkSpace` + `Updated` in
  `.probe/final-relaunch.png`; `List` persisted in an earlier run).
* Subagent activity: the active session row shows a chase dot, `1` beside it, and
  a caret; tapping the caret expands the children.
* Acceptance fixture (`session-2615b629…`): 23 direct children, 22 archived, one
  unarchived (`826e63fc-60aa-48fb-a466-3e38d8127844`, the only running one).
  * Switch **off**, caret expanded → **exactly 1 child row**
    (`.probe/sw-off-expand.png`, `.probe/acc-arch-off.png`).
  * Switch **on**, Order **Updated** → 23 child rows, newest-first; Order
    **Manual** → oldest-first (`.probe/sw-on-expand.png`,
    `.probe/acc-arch-on.png`, `.probe/acc-manual.png`).
* Switch: off then on moves the count 39 → 114 and reveals the archived rows
  (`.probe/sw-open.png`, `.probe/sw-on.png`).

## Observed but out of scope

The blank-"New Session" landing on `--es uk.xa0.dsh.session <id>` is fixed:
`MainActivity` hands the intent session to `openSessionExplicit`, which records
it in `explicitSession` (`MainActivity.kt:82-89`,
`DshViewModel.kt:2355-2358`), and `attemptConnect`'s auto-open reads and clears
that field in the same uninterrupted main-thread block as its own `openSession`,
so the explicit pick outranks "most recent session with content" whichever order
the two arrive in (`DshViewModel.kt:539-546,1181-1191`). The record is
per-process, so it closes the launch race only; a session the host itself
reports as blank is a separate matter.

The cold-start "Loading sessions…" wait is host latency: the live probe showed
`session/list` answering anywhere from ~300 ms to ~20 s for the same 197 rows
(12 concurrent calls all returned the full list, 5.7–9.6 s each). The client's
`fetchSessions` is two linear passes plus one subagent-catalog index, so it is not
the quadratic cost.

