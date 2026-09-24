# Trajectory view — web reference, app port, and call site

Status: **implemented**, build-verified. Files:

- `app/src/main/java/uk/xa0/dsh/model/Trajectory.kt` — pure fold from `List<ChatEntry>` to the ledger + timeline.
- `app/src/main/java/uk/xa0/dsh/ui/TrajectoryScreen.kt` — the standalone Compose screen.
- This document.

Web source read for this port (read-only, under `/home/user/bin/deepseek-harness`):
`packages/client/ui-trajectory/src/client/*` and the Chat target's neighbouring
definitions in `packages/client/ui-chat/src/client/conversation-nodes/*`.

## 1. How the web hosts the view

The trajectory is the conversation's **second view tab**, not a separate panel:

- It registers into the `conversation.view` list slot with `id: 'trajectory'`,
  `order: 10`, label `Trajectory` — `ui-trajectory/src/client/index.ts:79-112`.
  Chat registers the same slot with `id: 'chat'`, `order: 0` —
  `ui-chat/src/client/apply.ts:97-101`.
- `DefaultConversationViews` resolves the preferred view id and renders **only**
  the active one inside the resident scrollport —
  `ui-conversation/src/client/skeleton/DefaultConversationViews.tsx:34-43`.
  `resolveActiveView` falls back to Chat when nothing is stored —
  `ui-conversation/src/client/view-selection.ts:1-18`.
- The view root is `display:flex; height:100%` and reserves bottom clearance for
  the floating composer — `ui-trajectory/src/client/views.module.css:1-28`.
  So the composer stays; only the conversation column swaps.

The app has no view-slot registry. The equivalent is a local `view` state in the
conversation screen plus a content-area swap (§6).

## 2. What the web draws

### 2.1 The record kinds and their labels

`TrajectoryCellKind` is exactly seven values —
`ui-trajectory/src/client/trajectory-record.ts:9-16`:

| kind | label (`en`, `locales.ts`) | produced by |
| --- | --- | --- |
| `system` | `SYSTEM` (`:222`) | `system/message` / `request/header` prompt changes — `trajectory-request-header-definition.ts:32-149` |
| `user` | `USER` (`:223`) | `user/message` with `source.kind === 'user'` — `trajectory-message-definitions.ts:136-181` |
| `context` | `CONTEXT` (`:224`) | any other `user/message` source (plugin, instructions, skill, recall, relay, goal) — same file, `contextProducer` at `trajectory-event-projection.ts:60-78` |
| `compacted` | `COMPACTED` (`:225`) | the compaction request — `trajectory-compaction-definition.ts:80-111` |
| `message` | `ASSISTANT` (`:227`) | `assistant/message` — `trajectory-assistant-definition.ts:334-414` |
| `tool` | `TOOL` (`:228`) | `tool/call` + `tool/result` — `trajectory-tool-definition.ts:216-260` |
| `subtool` | `SUBTOOL` (`:229`) | `tool/ptc-dispatch*` children of a `run_code` parent — same file, `expandSubCalls` at `layout.ts:1009-1060` |

The table keys off `KIND_LABEL_KEY`, which maps `message → kind.assistant` (the
legacy `TrajectoryCell.tsx:19-27` maps it to `kind.message` = `Message`); the
shipped table therefore prints `ASSISTANT` — `TrajectoryTable.tsx:49-57`.

Two other node data kinds exist but are **not** cells: `session-end`
(`trajectory-compaction-definition.ts:118-133`) only feeds the timeline domain,
and `turn-end` (`trajectory-assistant-definition.ts:424-456`) only sets request
error state.

### 2.2 Layout: turn → group → cell

`deriveTrajectoryLayout` (`ui-trajectory/src/client/layout.ts:155-552`) folds the
snapshot into `TrajectoryTurnModel { turn, groups }` and
`TrajectoryGroupModel { title, description, cells }` (`layout.ts:25-35`):

- **Turn ownership.** An assistant/tool node carries `turn` directly. A
  `user/message` has none on the wire, so `enclosingUserTurn`
  (`layout.ts:876-885`) puts it in the *next* assistant turn, else the in-flight
  partial, else `lastAssistantTurn + 1`, else 1. Orphan turn-0 cells fold into
  turn 1 (`layout.ts:532-540`).
- **Groups.** A `Message` group collects non-step records — `pushMessage`
  (`layout.ts:191-199`): append to the trailing group when its title is already
  `Message`, else open one. Each assistant step opens/extends `Step N` —
  `pushStep` (`layout.ts:200-210`), and `expandAssistant` emits the step's
  assistant cell *and* its tool cells into that one group (`layout.ts:704-796`).
- **Compaction.** A compaction request becomes a single group titled
  `Compaction {startSeq}` (`group.compaction`, `locales.ts:240`,
  `layout.ts:366-378`); when the request has no turn it becomes a standalone
  section between turns (`layout.ts:375-376`).
- **Ordering.** Turns are sorted by their first cell index (`layout.ts:548-552`).
- **Group description.** Wall-span duration plus a tool histogram, e.g.
  `2227 ms bash×6` — `groupDescription` (`layout.ts:651-683`). Note the label is
  always **milliseconds**: `formatElapsedSeconds` multiplies back to ms
  (`trajectory-record.ts:140-145`). A one-cell group uses that cell's own
  `timeSeconds` (`layout.ts:669-673`), and a lone user/context cell has
  `timeSeconds: 0`, so the web prints `0 ms` there.

### 2.3 The ledger table (the actual drawn surface)

`TrajectoryTable.tsx` is a two-column `<table>` (`event` / `content`,
`TrajectoryTable.tsx:2566-2569`). Each record is one row
(`TrajectoryTable.tsx:2613-2861`) whose event column holds:

- a **request boundary** — a 5px dot, `data-label="Request #N"` /
  `"Request #N · Compaction"`, revealed on hover/focus; error requests paint it
  red (`TrajectoryTable.tsx:2732-2753`, CSS `:237-323`);
- a **turn chip** — `Turn N` (or `Between turns` for `turn === null`,
  `sectionLabel` at `TrajectoryTable.tsx:561-563`) on the turn's first row,
  tinted while that turn is active (`:396-436`; `sectionLabel` uses
  `turn.label` / `section.betweenTurns`, `locales.ts:237-238`);
- a **kind tag** — 10px/16px, weight 650, radius 4 (`:471-505`).

The content column shows `displayText` and, when a result exists, `→ result` in
a result column (`:2834-2860`). Display/result text rules live in
`recordDisplayText` / `recordResultText` (`:1012-1034`): `text` and
`previewMarkdown` join with ` · `, a result with no text becomes `No output`
(`record.noOutput`), a failure's verdict is its error code, and a message with
neither prose nor reasoning is `Tool call only` (`:1051-1056`). Preview budgets
are 2 048 source chars → 512 output chars (`trajectory-preview.ts:1-20`).

**Fold.** The toolbar's `Turns` toggle collapses turns
(`TrajectoryTable.tsx:619-652`): the first content record stays, the rest is
replaced by `… {n} steps · {n} tool calls` (`summarizeTurn`, `:598-617`;
`summary.steps.*` / `summary.toolCalls.*`, `locales.ts:348-351`). The `Calls`
toggle collapses the tool calls after an assistant cell into
`{n} tool calls · names` (`:654-721`). Rows are virtualized in 30px content /
20px summary / 9px terminal-boundary units (`trajectory-virtual-rows.ts:6-8`).

**Inspector.** Selecting a row opens a resizable details `<aside>` with tabs
(`Summary / Output / Payload / Result / Schema / Timing / Source / …`,
`TrajectoryTable.tsx:2881+`), which is where schema, usage, prompt-diff and
timing facts live.

### 2.4 The timeline overview

`TrajectoryView.tsx:510-576` stacks toolbar → timeline → ledger. The timeline
(`TrajectoryTimeline.tsx`) projects every visible record through
`deriveTrajectoryTimeline` (`timeline.ts:75-205`) into **three lanes** by kind
(`timeline.ts:51-55`):

| lane | captions (`column.*`, `locales.ts:231-236`) | kinds |
| --- | --- | --- |
| 0 | `Input` | system, user, context |
| 1 | `Model` | message, compacted |
| 2 | `Tools` | tool, subtool |

The default mode is `sequence` — each record is one equal-width block — with
`duration` / `time` / `actual` modes available from the toolbar. Block geometry
and colours: lane pitch 14px, block 8px, radius 1, default opacity 0.78;
`user` = business blue, `context` = success mix, `message` = brand/error mix
(with a TTFT/decoding gradient when timing is recorded), `tool` = warn,
error = red — `TrajectoryTimeline.module.css:153-218`. Turn boundaries are
0.5px border-l2 rules at each turn's first block (`:144-151`). Drag paints a
range, wheel zooms, click selects (`TrajectoryTimeline.tsx:432-575`).

## 3. The `ChatEntry → TrajectorySpan` mapping

`buildTrajectory(entries, endedTurns, toolParents)` (`model/Trajectory.kt`) is
the whole port; the screen never sees a `ChatEntry`. `toolParents` is the wire's
PTC link map (`callId → parentCallId`), which decides `TOOL` vs `SUBTOOL`.

| `ChatEntry` | span kind | notes |
| --- | --- | --- |
| `UserMessage` | `USER` | `text` = attachment summary (`Images ×N` / `Files ×N`), `previewMarkdown` = prose; `timeSeconds = 0`. |
| `AssistantMessage` | `MESSAGE` | prose/reasoning both belong to the one record; neither present → `Tool call only`. |
| `ToolCall` | `TOOL`, or `SUBTOOL` when the wire's PTC parent link names it | `text` = name, preview = arguments, `result` = error code / `No output`, result preview = result prose. `status = RUNNING` until the result lands. `callId`/`toolName`/`parentCallId` ride the span for the row key and the details panel. |
| `Notice(kind == "compaction")` | `COMPACTED` | standalone `Between turns` section, group `Compaction {seq}`; when the summary body exists it is the display preview, else the app's marker copy stands in. |
| `Notice(model-retry)` | `RETRY` | app-only; see §4. |
| `Notice(turn-error / turn-max-tokens / agent/error / session/error)` | `ERROR` | app-only; see §4. |
| any other `Notice` | `CONTEXT` | plugin/context rows, label rules already in `Transcript.kt`. |
| `Todos` | `CONTEXT` | app-only; the web's Trajectory target has no `todo/write` node. |

Turn ownership reimplements `TurnProcess.kt`'s two-pass rule locally
(`resolveTurnOwners`, `model/Trajectory.kt:481-521`): human messages look
*forward* to the next assistant turn (else `lastAssistantTurn + 1`), every other
untagged row looks *back* at the turn already running. Turn-0 is folded into
turn 1, as the web does. Step ownership of a tool cell is "the most recent `Step
N` group", which is where the web's `expandAssistant` puts it — this app's
`ChatEntry.ToolCall` has no `step` field, so the seq-adjacent assistant step is
the only honest owner.

Request numbers are assigned over the finished ledger: every `Step N` group and
every standalone compaction gets the next ordinal, in start-seq order, matching
`TrajectoryView.tsx:213-313`.

## 4. What is implemented in the app

`ui/TrajectoryScreen.kt`:

- header (title + `Trajectory`) with a back arrow, suppressible with
  `showHeader = false` when the conversation header is already on screen;
- the three-lane timeline strip, sequence mode, with the web's lane captions,
  block geometry/colours and turn boundaries — read-only;
- the ledger as a `LazyColumn`: a turn chip per section (active tint while the
  turn has no `turn/end`), a `Request #N` / `Request #N · Compaction` boundary
  row per request group (the web's hover label made permanent, because a touch
  screen has no hover), then one row per record — kind tag (right-aligned in a
  fixed slot, as the web's `.kindSlot` does) + display text + `→ result`;
- per-turn fold with the web's `… {n} steps · {n} tool calls` summary;
- an inline disclosure per record for `thinking` / `input` / `output`, capped at
  4 000 chars, standing in for the web's details panel;
- an explicit empty state.

`model/Trajectory.kt` carries `SYSTEM` in its kind enum even though this reducer
cannot produce it: the kind set is the web's, and the mapping is what is
missing. `SUBTOOL` is produced — a call named by a `tool/ptc-dispatch*` link is
tagged `SUBTOOL` and the link kept as `parentCallId` (`model/Trajectory.kt:662`,
`model/ToolCallTree.kt:110-127`). `RETRY`/`ERROR` are app-only extensions (the
web folds a retry into the assistant request —
`trajectory-assistant-definition.ts:297-331` — and a turn failure into that
request's status), added because this app's reducer *does* emit them as rows and
dropping a model-visible event would be worse.

## 5. Deliberately left out

1. **`system` records.** `Transcript.kt` keeps no
   `system/message`/`request/header` prompt changes, so there is no data to draw.
   A request boundary still appears where the assistant request begins.
   (`tool/ptc-dispatch*` links *are* kept, in `ToolCallTree.kt`'s
   `ToolCallParents`, and a linked call is drawn as `SUBTOOL`.)
2. **The tabbed inspector.** No schema, usage, timing, prompt-diff, source-block
   or image tabs; the inline disclosure shows the retained text only.
3. **Timeline interaction.** No drag-to-focus range, wheel/keyboard zoom,
   click-to-select, hover tooltip, or the earlier-history boundary control.
   Sequence mode only: a tool or assistant span carries `timeSeconds = null`
   (the reducer stores no result/step-start timestamp), so `duration`/`time`
   modes would have nothing to project; user/notice/compaction/to-do spans carry
   `0.0` (`model/Trajectory.kt:622,697,734`). A tool call whose result has not
   landed is present as a `RUNNING` span, but there is no streaming partial
   assistant row: the parent passes only the durable rows.
4. **Toolbar.** No search index (`trajectory-search-index.ts`), no
   `Duration` / `Actual time` toggles, no expand/collapse-all.
5. **Per-assistant tool-call fold.** The web's `Calls` toggle
   (`TrajectoryTable.tsx:654-721`) is not implemented; only whole turns fold.
6. **Request-only boundary runs, sticky turn headers, virtualization.** The
   ledger is a plain `LazyColumn`; the web virtualizes at 100+ rows with
   per-row heights of 30/20/9px.
7. **Markdown flattening.** The web runs `extractMarkdownPlainText` before making
   a preview; this app has no such helper outside the renderer, so markdown
   syntax survives in one-line previews.
8. **Error detail wording.** A failed tool shows its error code (the contract)
   where the web can print `{error.name}: {code}`; the reducer keeps no error
   name.
9. **Compaction `running` state.** The web marks a `compacted` cell with no
   duration as pending; this app only ever draws the marker from a landed
   checkpoint, so its status is always complete.
10. **Group header rows and their `description`.** The web's live table only
    uses a group as a request-number key; the `TrajectoryGroupHeader` /
    `TrajectoryTurnHeader` components that would draw `Message` / `Step N` and
    the `2227 ms bash×6` description are unimported. The model still computes the
    description for parity, and the screen draws the request boundary instead.

## 6. Call site

Wired: `ChatScreen.kt` holds `var view by rememberSaveable(ui.currentSessionId)
{ mutableStateOf(ChatView.CHAT) }` and swaps only `ChatView.TRAJECTORY` against
the transcript (`ui/ChatScreen.kt:287,520-534,1387`). The conversation header
(title, drawer control, tab strip, Chat/Trajectory underline) stays. The
signatures below are the stable contract:

```kotlin
// model/Trajectory.kt
fun buildTrajectory(
    entries: List<ChatEntry>,
    endedTurns: Set<Int> = emptySet(),
    toolParents: Map<String, String> = emptyMap(),
): TrajectoryModel

// ui/TrajectoryScreen.kt
@Composable
fun TrajectoryScreen(
    model: TrajectoryModel,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
)
```

The shipped call:

```kotlin
} else if (view == ChatView.TRAJECTORY) {
    TrajectoryScreen(
        model = remember(entries, endedTurns) {
            buildTrajectory(entries, endedTurns, vm.toolParents())
        },
        title = current?.title ?: "DSH",
        onClose = { view = ChatView.CHAT },
        modifier = Modifier.weight(1f),
        // The conversation header already shows the title and back affordance.
        showHeader = false,
    )
} else {
    // existing transcript + composer body
}
```

`modifier = Modifier.weight(1f)` is the shipped shape inside the screen's
`Column`; the screen fills whatever it is given and owns its own scroll. The
composer stays mounted below it, as in the web, because the ledger scrolls
independently.

## 7. Verification

- `cd /home/user/var/work/dsh-android && flock /tmp/dsh-build.lock ./build.sh :app:assembleDebug`
  — compile-verified with the model + screen in the tree (the ChatScreen wiring
  above landed afterwards).
- No device run was performed for this screen (the task forbids driving the
  phone, and the emulator check is the parent's). The visual claims above are
  source-derived from the CSS/TSX cited inline.
