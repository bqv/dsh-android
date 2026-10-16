# Files panel, text preview, and the deliverables row

Companion to `panels-settings.md` §1.2/§1.4/§4, verified against the read-only web
checkout at `/home/user/bin/deepseek-harness` (commit
`0d1f50007f9bca3f52b06e1c3074fa14d5fb0720`, root `0.1.6-alpha.1`). That report
already summarises the two surfaces; this one records the exact rules the app
ports, the host RPCs a live preview needs, and the call sites as wired in
`ui/ChatScreen.kt`.

All citations are relative to the web checkout root.

---

## 1. The web's Files panel

The Files panel is a **page tab**, not a viewer: `FILES_KIND = 'files'`
(`packages/client/ui-sidebar-files/src/client/definition.tsx:14`), title
`type.label`, guide entry order 10. It claims no address.

- **One RPC, ever**: `workspaceFiles/list(sessionId, path, signal)`
  (`.../face.ts:53-59`). The root is the session `cwd` (`FilesBody.tsx:206-210`
  shows `no-workspace` when the session has no cwd) and every level is keyed by
  absolute path; a child is the parent plus the entry name (`face.ts:70`).
  Levels are listed lazily the first time a directory opens, and **Reload** is
  the only invalidation — the tree never subscribes to `workspaceFiles/changes`
  (`panels-settings.md` §1.2, "Refresh / invalidation").
- **Ordering** is client-side and always directories first, then by
  `Intl.Collator(undefined,{numeric:true,sensitivity:'base'})` — `file2` before
  `file10` (`FilesBody.tsx:37-51`).
- **Entry kinds**: `file` opens, `directory` toggles, anything else is an
  `aria-disabled` span with no click (`FilesBody.tsx:130-177`).
- **A file click** calls `tabActions.openResource(fileAddressFor(sessionId,
  root, path))` (`FilesBody.tsx:218`) — the address
  `dsh-resource://file/session/<id>/<path>`, which the `text` preview tab owns.
- **Copy** (`.../locales.ts:46-50`): `loading` = `Reading…`, `empty` =
  `Empty directory`, `truncated` = `Too many entries, showing only some of
  them.`, `noWorkspace` = `This session has no workspace directory.`, `reload` =
  `Reload`. Failure lines are mapped from `RemoteFailure.code` at
  `FilesBody.tsx:59-68`.

## 2. The web's text preview

`packages/client/ui-sidebar-documentpreview`: kind exactly `text`
(`definition.ts:15`), `patterns: ['dsh-resource://file/**']`
(`definition.ts:49`), `canOpen` only for a session-scoped address
(`definition.ts:51`).

- A page comes from `workspaceFiles/read(sessionId, path, {offset}, signal)`
  (`src/client/rpc.ts:81`); complete bytes from `readAll`
  (`src/client/index.ts:95`); HTML sub-resources from `readRelated`
  (`panels-settings.md` §1.4).
- Pages append by 1-based line offset, wrap is the default for the code and
  plain-text renderers, and a host version change is announced, not applied
  (`panels-settings.md` §1.4 "Paging / refresh model").
- A `{line}` address param deep-links into the page walk
  (`panels-settings.md` §1.4 "Line navigation").

## 3. The web's deliverables row

### 3.1 Where it attaches

`conversation.chat.turnTail` is a turn-tail **chain**, rendered at the end of a
finished turn by `TurnTailNodeView` (`packages/client/ui-chat/src/client/conversation-nodes/turn-tail.ts:167-208`
defines the node). `ui-deliverables` registers into that chain; its
`selectDeliverables` returns null when produced + presented is zero
(`Deliverables.tsx:34-38`), so a turn with nothing delivered draws **no row at
all**. There is no deliverables tab and no `sidebar.right.*` registration
(`panels-settings.md` §4 "Absent").

### 3.2 Which calls count as producing a file — the exact rule

`mutationPath` (`turn-deliverables.ts:50-68`) accepts only three wire names and
validates their arguments; a malformed call returns null. Ported field for
field:

| tool name | condition | path key |
|---|---|---|
| `write` | `typeof args.content === 'string'` | `file_path` |
| `edit` | `old_string` non-empty string, `new_string` string, `old_string !== new_string`, `replace_all` absent-or-boolean (`validEditArgs`, `:71-78`) | `file_path` |
| `str_replace_editor` `create` | `file_text` is a string | `path` |
| `str_replace_editor` `str_replace` | `old_str` non-empty string, `new_str` absent-or-string; **a pure deletion counts** (`:80-103`) | `path` |
| `str_replace_editor` `insert` | `insert_line` integer ≥ 0, `new_str` string | `path` |

`pathValue` keeps the exact spelling and requires a non-blank string
(`:105-107`). A produced path is recorded only against a **non-error**
`tool/result` (`:194`), deduped by exact path in first-seen order, and a
`producedForClosing(data, seq)` call drops any settlement whose seq is past the
closing assistant (`:131-143`). Reads, deletes on other tools, unsupported
tools, malformed calls, and failed results contribute nothing.

The `present` tool (`packages/fs/tool-present/src/index.ts`) is the explicit
class: parameters `{files:[{path, description?}]}`, output `{turn, files}`,
`maxFiles` default 8, and on a non-error result it appends the durable
`deliverables/presented` event `{turn, callId, files}` (`:99-107`).

### 3.3 What it draws

```
Deliverables (root)
├─ ProducedFiles                         only when produced > 0
│  ├─ label “Files changed”              produced.label
│  └─ chip per path                      LinkIcon + basename, title = full path
└─ div.presented                         only when presented > 0
   ├─ PresentedFileCard × N              grid, 2 cols until 620px, 1 when single
   └─ collapse toggle                    only when presented > 4
```

- Produced chips: `SHOWN_LIMIT = 6` (`ProducedFiles.tsx:9`); CSS container bands
  hide chips at 687/583/479/375/271px, and the visible band's remainder renders
  `produced.moreOne` = `+ 1 file` / `produced.more` = `+ {count} files`
  (`locales.ts:82-84`, `ProducedFiles.module.css` container queries).
- Presented cards collapse to `COLLAPSED_PRESENTED_COUNT = 4`
  (`Deliverables.tsx:17,52-54`); the toggle copy is `presented.all` =
  `All {count} files` / `presented.collapse` = `Collapse` (`locales.ts:68-71`).
- Card status: the description with a trailing parenthesised suffix stripped,
  else the upper-cased extension, else `presented.file` = `File`
  (`PresentedFileCard.tsx:15-18,45-47`). The visible action is
  `presented.action` = `Open` (`locales.ts:64`).
- **Drop on a phone**: `presented.defaultApp` / Finder / Explorer reveal act on
  the *serving host's* desktop; the app keeps only the preview action
  (`ui/components/DeliverableRow.kt:67-70`).

---

## 4. Host RPCs for reading a file

Namespace `workspaceFiles`, host owner
`packages/api/workspace-files/src/index.ts`. Every method's first parameter is
the **`workspaceFileScope` lookup**, whose wire name is `workspaceFileScopeId`
and whose value is the **session id** (`lib/typert.remote-client.d.ts:12-18`;
the lookup itself resolves the session header's `cwd`, falling back to the
sandbox root, at `src/index.ts:203-216`). The gateway serialises the call as one
named-args object, and the live args were confirmed against a throwaway session
(created with `session/create`, archived afterwards).

### `workspaceFiles/list` — a directory's direct children

```jsonc
// args
{ "workspaceFileScopeId": "<sessionId>", "path": "<abs or workspace-relative dir>" }
// -> 
{ "path": "<workspace-relative dir, '' for the root>",
  "entries": [ { "name": "app", "type": "directory" },
               { "name": "README.md", "type": "file", "size": 4385 } ],
  "truncated": false }
```

`path` is required and non-empty (an empty string is rejected
`gateway/bad-request`). `type` ∈ `file|directory|other`; listings are capped at
`maxEntries` default 2000 (`src/index.ts:189`). Directory paths are
workspace-confined (`workspace-file/outside-workspace`); **file** reads are not
(scoped only by filesystem access).

### `workspaceFiles/read` — one page of text

```jsonc
// args
{ "workspaceFileScopeId": "<sessionId>", "path": "README.md",
  "range": { "offset": 1, "limit": 5000 } }   // both optional; 1-based lines
// ->
{ "absolutePath": "/home/user/var/work/dsh-android/README.md",
  "version": "40:10365806:4385:…",
  "bytes": 4385,
  "offset": 1,
  "text": "# DSH — native Android client\n\nA native…",
  "lines": 3,          // a COUNT, not an array
  "eof": false }
```

`offset` defaults to 1 and `limit` to `maxLines` default 5000 (`:188`);
a page above `maxBytes` default 2 MiB is refused, not shortened
(`workspace-file/too-large`), as is non-UTF-8 (`workspace-file/not-text`).
Errors: `workspace-file/not-found`, `not-regular-file`, `outside-workspace`
(list only), `too-large`, `not-text` (`src/types.ts` error map).

### The rest, for completeness

| method | args (after the scope) | response |
|---|---|---|
| `workspaceFiles/readAll` | `path` | `{absolutePath, version, bytes?, offset:0, data:<base64>, eof:true}` |
| `workspaceFiles/readBytes` | `path, range:{offset,length}` | same shape, one byte window |
| `workspaceFiles/readRelated` | `path, relativePath` | `readAll` of a sibling file |
| `workspaceFiles/stat` | `path` | `{absolutePath, version, bytes?}` |
| `workspaceFiles/changes` | — (stream) | `{kind:'ready'} | {kind:'change', change:{absolutePath, version}}` |

`readAll` caps at `maxFileBytes` default 32 MiB (`:187`). `changes` is a mux
stream, observed only through instrumented fs operations — not an OS watch.

App-side call shape: `DshViewModel.workspaceFileLoader()`
(`DshViewModel.kt:3504-3541`) captures the open session id when it is built, so
the pane cannot re-scope mid-read, and sends `workspaceFiles/read` with
`range {offset: 1, limit: FILE_PREVIEW_LINES}`. The reply maps to
`FilePreview.Ready` field for field (`truncated = !eof`; `bytes` and
`absolutePath` stay null when the host omits them). A host refusal becomes
`FilePreview.Unavailable(describe(error))` — the host's own wording, with a 401
routed to the auth funnel — so a failed read renders as the failure, not as the
transcript's older content. `SessionFiles.previewOfText`
(`SessionFiles.kt:333-349`) is the equivalent pure builder; the loader fills the
type directly.

---

## 5. What the app implements

### `model/SessionFiles.kt` (pure, no Android types)

- `SessionFile(path, touches, reads, writes, edits, presents, firstSeq, lastSeq)` and
  `enum FileTouch { READ, WRITTEN, EDITED, PRESENTED }`. `derive(entries)` walks the
  `ChatEntry.ToolCall` rows in seq order, keeps the first-seen order, and takes
  *many* hits from one row when that row is a `present`.
- `mutationOf` is the §3.2 table, verbatim, **successful results only**
  (`result != null && !isError`). Wire names are exact and lowercase-compared;
  the app's UI aliases (`write_file`, `str_replace`, `apply_patch`) are
  deliberately **not** counted, matching the web's "a new mutation tool needs an
  explicit contribution".
- `READ` is an app-side addition (the web's produced rule has no read case):
  successful `read` / `read_file` / `read_image` calls, path from `file_path`
  then `path`. `PRESENTED` is the second app-side addition, and it is
  load-bearing rather than decorative: a successful `present` declares each path
  in its `files` array as a row, under the spelling the card carries. Without it
  the deliverable card draws while the Files list has no row for the card's path
  — `present` may name the file relatively where every `write` named it
  absolutely, or the file may have been produced by a shell redirect, a
  subagent's turn, or already existed — and the card's tap, which reaches the
  panel as `initialPath` and is selected only on an exact path match, does
  nothing at all. `presentedForTurn` and `derive` share one argument parser
  (`declaredFiles`), so the card's list and the Files list cannot drift apart.
- `previews(entries)` harvests the **last successful read of each path** from
  `meta` (`parseReadMeta`) or, for records that predate `meta`, from the
  `<path>…<content>` envelope (`parseReadEnvelope`, ported from the read card).
  The key is the tool-argument path, so it matches `derive` exactly.
- `parseReadMeta`, `parseReadEnvelope`, and `previewOfText` are public so a
  loader can reuse the same numbering.
- Deliverables: `deliverablesFor(entries, turn, throughSeq)`,
  `producedForTurn` (dedup first-seen, `seq <= throughSeq`),
  `presentedForTurn` (latest declaration per path before `throughSeq`,
  first-seen order). **Known approximation**: the produced entry keeps the
  `tool/call` seq, because `ChatEntry.ToolCall` does not retain its result's seq.
  The web keys on the result seq, so a mutation that settles *after* the closing
  prose would be excluded there and is included here — a rare case (the agent
  would have to emit the final answer while a mutation is still in flight).
  `present` is read from the **call arguments** rather than the durable
  `deliverables/presented` event, because `Transcript.kt` does not fold that
  event and this model may not edit it; the call's `{files:[{path,
  description}]}` is the same declaration, gated on a non-error result.

### `ui/components/FilesPanel.kt`

`FilesPanel(files, modifier, root, cached, loader, downloader, initialPath, onSelect, explorer)`
(`FilesPanel.kt`). Two modes when the parent supplies an `ExplorerWiring` and a
`root`: **This session**, the touched-file list below, and **Browse**, the
workspace tree (see `WorkspaceExplorer.kt` above). A plain open lands on Browse,
because a panel derived only from the session's activity is what read as broken; a
caller that named a file (`initialPath` — a deliverable card's tap) lands on This
session, which is the only list that can select an arbitrary transcript spelling.
Switching modes clears the selection: the two lists address one file under
different spellings. Switching the *hidden* toggle lives in the explorer pane and
resets with the panel.

The session list: a list of the touched files (icon, basename, directory
tail, touch badges) with a wrapped, line-numbered monospace preview — or, since
§8, a Markdown rendering, with the source one tap away. From 720dp
the list and preview sit side by side; below that they swap, with a back control
(`FilesPanel.kt:148-195`). `loader == null` uses `cached` only, and the loader is
consulted **only for a path `cached` does not hold** (`FilesPanel.kt:122`); a
failure becomes `FilePreview.Unavailable` and renders the host's own wording with
a `Retry` (`FilesPanel.kt:372-376`), never a fallback to older content. Empty
states: no files at all (`No files yet`, `FilesPanel.kt:213-219`), `Select a
file to preview.` (`:368`), `Reading…` (`:369`, `:381`), `Nothing read from this
file in this session.` when no loader is wired and nothing is cached (`:383`),
and `This file is empty.` for a zero-line page (`:388`). The explorer's own
states are `Loading…`, `Empty directory`, the host's refusal with `Retry`, the
web's `Too many entries, showing only some of them.` for a truncated level, and
the standing line `A file with no mark has not been touched by this session.`

### `model/WorkspaceExplorer.kt` (pure, no Android types)

Added when the panel became an explorer as well as a session record. The host's
`list` surface is a **one-shot call**: `workspaceFiles/list(workspaceFileScopeId,
path)`, scoped by session id, confined to the session's workspace root, with no
cursor and no paging — only `maxEntries` (default 2000) and a `truncated` flag.
`path` must be non-empty, so the root is asked for as `.` (the host's `contains`
is documented as true for the parent itself, so `.` names the workspace root
without this client having to agree about its spelling); every level below is the
`path` the reply itself returned, joined with the child's name by `/`, which the
wire type explicitly sanctions. There is a `workspaceFiles/changes` watch, but the
web's tree does not subscribe to it either, and the app does not.

- `WorkspaceEntry(name, kind, size)`, `WorkspaceEntryKind{FILE,DIRECTORY,OTHER}`,
  `WorkspaceListing(path, entries, truncated)`, `ExplorerState{Idle,Loading,Ready,Failed}`.
- `relativeToWorkspace(path, cwd)` resolves a transcript spelling onto a
  workspace-relative one: absolute under the cwd is stripped, relative passes
  through, `.` and `..` are collapsed, and an escape above the root, the root
  itself, or an absolute path outside it answers **null** rather than a guess.
  That null is the whole reason a badge cannot be wrong: the host would refuse to
  list a directory outside the workspace, so no row could carry such a path.
- `badges(files, cwd)` keys `SessionFiles.derive`'s rows by that resolved path and
  **merges** two spellings of one file into one mark (counts add, first/last seqs
  are the extremes, touch kinds are the union). An absent key means the session
  has not touched that file — a fact, not a gap.
- `visibleEntries` filters dot-prefixed rows behind the toggle *unless* the
  session touched them (a mark is the session's own record, and hiding a file it
  edited would fail the panel's one job to honour a filter about exploring), and
  orders directories first, then by a `Intl.Collator`-shaped natural name order
  (`file2` before `file10`).
- `crumbs(relative, rootName)` roots at the workspace and offers no "up": the host
  confines listings, so there is nothing above to list.

The ViewModel half is `listWorkspaceDirectory` / `reloadWorkspaceExplorer` /
`resetWorkspaceExplorer` (`DshViewModel.kt`), with a per-level cache
(`explorerLevels`) held for one panel open and a generation counter so a
superseded level cannot land after a newer one. Reload is the only invalidation,
as in the web.

### `ui/components/DeliverableRow.kt`

`DeliverableRow(delivered: TurnDeliverables, onOpen, modifier)`. Renders nothing
for an empty model. Produced: `Files changed` label + link chips with the web's
container bands reproduced against the lane width — six chips down to one at
687/583/479/375/271px (`DeliverableRow.kt:331-338`) — plus the `+ N file(s)`
counter. Presented: cards collapsed to four with an `All N files` / `Collapse`
toggle, each card tap-to-open with an `Open` control, status = stripped
description → upper-cased extension → `File`. The host-desktop menu is omitted
(`DeliverableRow.kt:67-70`).

## 6. Call sites

### a. The deliverables row, in `ui/ChatScreen.kt`

Wired. The web attaches the row to a `turn-tail` node at the end of a finished
turn; the app's equivalent is the closing assistant of an ended turn, the row
whose `entry.seq` is in `closingSeqs` (`DshViewModel.kt:503-504`, set from the
reducer's `closingAssistantSeqs()` at `:3474`, collected at `ChatScreen.kt:161`).
The answer is computed once per row key into `deliverableCache`
(`ChatScreen.kt:297`) and drawn only when non-empty (`:718-726`):

- a folding turn's process summary (`DisplayRow.TurnProcess`) uses
  `SessionFiles.deliverablesFor(entries, row.turn)` (`ChatScreen.kt:703-707`) —
  the turn has ended, so no through-seq is needed;
- a closing assistant uses
  `deliverablesFor(entries, entry.turn, entry.seq)` (`ChatScreen.kt:709-714`),
  the web's `producedForClosing(data, owner.seq)`.

`onOpen` points `filesFocus` at the path and opens the Files surface
(`ChatScreen.kt:721-724`), so a produced chip lands on that file's preview. An
empty `TurnDeliverables` draws nothing, so non-producing turns are untouched.

### b. The Files panel

Hosted as the full-body `FilesSurface` (`ChatScreen.kt:1449-1519`), not a column
beside the transcript: a phone has no room for both. It opens from the header's
Files control (`ChatScreen.kt:482-486`, which clears any stale `filesFocus`) or
from a deliverable chip's `onOpen`, and carries its own back bar. Its data is
derived on demand, only while the surface is open (`ChatScreen.kt:502-519`):

```kotlin
val files = remember(entries) { SessionFiles.derive(entries) }
val previews = remember(entries) { SessionFiles.previews(entries) }
val fileLoader = remember(current?.id) { vm.workspaceFileLoader() }
val fileDownloader = remember(current?.id) { vm.workspaceFileDownloader() }
FilesSurface(
    files = files,
    previews = previews,
    root = current?.cwd ?: header?.cwd,
    focus = filesFocus,
    loader = fileLoader,
    downloader = fileDownloader,
    explorer = ExplorerWiring(
        state = workspaceExplorer,
        onList = vm::listWorkspaceDirectory,
        onReload = vm::reloadWorkspaceExplorer,
    ),
    onClose = { vm.resetWorkspaceExplorer(); showFiles = false },
    modifier = Modifier.weight(1f),
)
```

`derive`/`previews` are cheap enough to recompute from the row list on change;
the parent remembers them on the list identity, and the loader is rebuilt per
session id (`DshViewModel.kt:3504`) so a stale reader cannot resolve paths
against the previous workspace. The explorer's state is a flow of its own
(`vm.workspaceExplorer`) rather than part of `UiState`, like the directory
browser's: a listing failure belongs to the panel, not to the session error
banner. Both close paths — the surface's back bar and the header's Files toggle —
call `resetWorkspaceExplorer()`, so a reopened panel lists the directory again
instead of drawing a snapshot from whenever it was last looked at.

The downloader is session-scoped for the same reason the loader is. Its *control's*
state is not session-scoped at all: `FileDownloadControl` holds its fetch, its job and
its bytes in `remember(path)`, so closing the panel (or selecting another file) disposes
the composition and cancels a fetch in flight. That is why the panel close needs no
download reset alongside `resetWorkspaceExplorer()` — there is no state outside the
composition to clear.

### c. The Files tree, and what it does not do

The web behaviour in §1 is the spec and the app now ports its shape: one
`workspaceFiles/list` call per opened level, directories first with natural
collation, a `Reload` that re-lists the current level, and the `text` route via
`workspaceFiles/read`. What is deliberately *not* ported: the web's tree is the
only Files list, while the app keeps the transcript-derived list as a second mode
(the divergence is recorded in `docs/PARITY.md`); and the tree subscribes to no
watch, exactly as the web's does not.

## 7. What is left

- **The explorer lists one level at a time and does not page.** `workspaceFiles/list`
  has no cursor, so the host's `maxEntries` (2000 by default) is the only bound and
  the panel reports `truncated` rather than offering `Load more`. Hidden rows are
  client-filtered behind a toggle; `node_modules`-style noise is *not* on any
  deny-list — that would be an invented policy on top of the one rule the host and
  the web already share (a dot prefix), and directories-first ordering plus a lazy
  list is what keeps a large directory navigable.
- **A mark is the session's record, and the panel says so.** Absence of a mark
  means "not touched by this session", not "unknown"; the explorer states that in
  words under the list. The listener should not have to infer it.
- **No pagination / `Load more`** in the preview: the loader reads one page
  (`offset: 1`, `FILE_PREVIEW_LINES`) and the panel says `Showing the first N
  lines` when the host cut it short (`FilesPanel.kt:395`); nothing pages past it
  and `stat.version` is never read, so the web's "the file has changed" bar has
  no counterpart.
- **No file-watch refresh** (the web tree has none either; the preview does).
- **No PDF or HTML renderer.** A PDF is read as text like anything else, so the
  host answers `workspace-file/not-text` and the pane shows its wording; the
  download control is the way such a file leaves the app (§8).
- **No inline-code file mentions** from the closing prose
  (`chatFileMentions`), and **no `present` tool card**: a `present` call renders
  as the generic transcript row with a `Visibility` icon
  (`ui/components/ChatRows.kt:1553`), not the web's `PresentRow`.
- **`deliverables/presented` is not folded into `ChatEntry`**, so the
  declaration is read from the `present` call arguments. If `Transcript.kt`
  later folds the event, `presentedForTurn` could switch to it (the event's seq
  is the result's, which is marginally more faithful).

## 8. Rendering, colouring, and taking the file

Added after the port above, and drawing on what was already in the app rather
than on a new dependency.

### 8.1 The renderers were already here

`ui/markdown/Markdown.kt` is a hand-rolled Markdown parser covering headings,
paragraphs, bullet/numbered/task lists, tables with a `|---|` separator, fenced
code (``` and `~~~`, language-labelled), block quotes, rules and inline
emphasis/code/links/images/strike plus GFM autolinks. It is streaming-tolerant,
and it is what the chat transcript already draws (`MarkdownText`). The same file
holds `SyntaxHighlighter`: a line-based, language-agnostic highlighter producing
KEYWORD/STRING/COMMENT/NUMBER/FUNCTION/PUNCTUATION spans, painted from the
theme's `synKeyword`/`synString`/`synComment`/… colours by
`MarkdownView.highlightCodeLine` (added so the Files preview can colour one
numbered row at a time, with the same mapping `CodeBlock` uses).

No Markdown or highlighting library is in the offline Gradle cache, and none is
added: the honest subset was already implemented, so this is a wiring change.

**What the parser does with what it does not understand: nothing is dropped.**
Footnoted `[^1]`, raw HTML tags, reference links, setext headings, HTML entities
and nesting deeper than the two-space list heuristic all survive as literal
characters; only *interpretation* is declined. `MarkdownHighlightTest` pins the
same property for colouring — the spans concatenate to the line exactly as it
arrived.

### 8.2 Deciding what a file gets, from the name alone

Three claims, all from the last path segment, all client-side (the host sends no
content type, and the read/readBytes choice has to be made before the first
byte):

| function | decides | vocabulary |
|---|---|---|
| `previewFormatOf` | how it is **read** | TEXT + PNG/JPEG/WEBP/GIF/BMP/ICO/HEIC/AVIF/SVG |
| `previewLanguageOf` | how its source is **coloured** | the `LANG_BY_EXTENSION` table, moved here from the read card so both surfaces answer the same, plus `kts` |
| `isMarkdownPath` | whether it is **rendered** | `md`, `markdown`, `mdown` only |

A dotfile and an unlisted extension answer `null`/false, which is what keeps a
`.log` or an extensionless file from being coloured as code. `.mdx` is
deliberately not rendered: it is Markdown *plus* JSX, and drawing the JSX as
prose would be a claim the file does not support.

### 8.3 Rendering replaces the text, so the source stays one tap away

The preview header carries a `Source`/`Rendered` toggle for a Markdown file
(the existing SVG `Image`/`Source` control generalised into `ViewToggle`), the
label naming where the tap *goes*. Markdown opens rendered; the language chip
always says what the panel took the file for, so a misnamed file's claim is
visible and checkable; and the window banner is hoisted above **both** views, so
a rendering of the first 2000 lines reads `Showing 3 of 5000 lines · the rest of
this document is not shown` rather than looking like the whole document.

### 8.4 Download: paged bytes, then the system's save dialog

There is one file read per window in this API, so a download is a walk:

- **Window**: `workspaceFiles/readBytes` with `options.range {offset, length}`,
  2 MiB at a time — the host's own ceiling on a *requested* length (it refuses a
  larger request outright rather than shortening it), and the same call the image
  preview already uses. Every `bytes-`named part is reassembled in order
  (`multipartByteParts`), not just the first: truncation that looks whole is the
  failure mode this must not have. The captured live reply (now asserted against
  its bytes in `net/MultipartTest.kt`) is **two** parts — `bytes-0` with the
  payload, and `metadata` holding the RPC envelope, which names the payload
  (`"attachments":[{"…","part":"bytes-0"}]`) and states `"eof":true`. The filter is
  therefore load-bearing rather than precautionary: without it the envelope's JSON
  would be appended to the reader's file.
- **Termination**: the loop advances by the bytes it was *given*, not by the length
  it asked for, and a short (or empty) window ends the read. That is the only signal
  that works when `stat` reported nothing, which is why the granted length is what
  the loop counts and what `FileDownloadTest` asserts — the same test fails if the
  advance is ever changed to the requested window. The envelope's `eof` is a second,
  stronger signal the host already sends and this client does not yet read; it is the
  thing to use if the paging ever needs to be independent of short reads.
- **Length**: `workspaceFiles/stat` for the progress total and the pre-flight
  refusal, treated as best-effort — an absent `bytes` is "unknown", never zero,
  or the loop would hand back an empty file without reading anything.
- **Ceiling**: 32 MiB, the host's own `readAll` limit. Above it the download is
  refused with the file's own size; it is never truncated. The loop's shape is
  such that a short window always ends the read, and a file that exactly fills
  the ceiling costs one single-byte read rather than a guess
  (`model/FileDownload.kt`, `model/FileDownloadTest.kt`).
- **Destination**: SAF `ACTION_CREATE_DOCUMENT` via
  `ActivityResultContracts.CreateDocument(mimeType)` — no storage permission, no
  `FileProvider`, no manifest entry, and the reader chooses the location. The
  name and type come from `downloadSaveOf(path)`: the file's own last segment
  unchanged, and the format table's answer as the type
  (`application/octet-stream` for bytes of unknown kind).
- **Order**: bytes are fetched first, then the dialog opens. The dialog creates
  the destination the moment it is answered, so fetching afterwards would leave
  a half-written file under a name the reader chose. The cost — the whole file
  held in memory — is why the ceiling exists.
- **Saying so**: `Download` → `N%` (or bytes, when no total was reported) with
  `Cancel`, then `Saved`, or `Retry` with the reason printed on its own line —
  the preview header for the panel, the card's status line for a delivery — and
  a `Log.w` under the `DshFiles` tag. Cancellation takes effect between windows,
  because the call in flight is a blocking socket read.

Both surfaces carry it (`ui/components/FileDownloadControl.kt`): the presented
card, where a reader is handed a file, and the Files preview header, where the
same file would otherwise behave differently.

### 8.5 What is not verified

Everything below the surface is untested from this box, which has no device:

- the `readBytes` window loop against the real host (only against a fake reader),
- whether `workspaceFiles/stat` answers on the running host at all (a failure is
  handled as "unknown length", so it degrades rather than breaks),
- the save dialog, the write to the chosen `content://` URI, `Saved`, and
  Cancel during a real transfer,
- whether the host splits a 2 MiB window into several payload parts. The captured
  reply is one `bytes-0` plus a `metadata` envelope, so the *multi-payload* shape is
  exercised only against a hand-built body; the envelope-plus-payload shape is the
  captured one and is asserted from its bytes.
- how a 2000-line Markdown document composes: `MarkdownText` is a plain
  `Column`, not a lazy list, so a very large rendering is a cost the preview
  pays in frames rather than in correctness.
