# DSH web client — right panel (dockkit), Trajectory view, Settings modal, Deliverables

Deep-dive companion to `chrome-ui.md` §5–§7. That report already carries the *summary*
facts (tab-kind table, `workspaceFiles` list, terminal RPC list, settings section order,
modal geometry, "what does not exist"). This file deliberately **does not restate those**;
it fills the gaps, corrects two details, and documents the **Trajectory view**, which the
other reports do not cover at all.

Source checkout (read-only): `/home/user/bin/deepseek-harness`, commit
`0d1f50007f9bca3f52b06e1c3074fa14d5fb0720` (2026-09-15), root version `0.1.6-alpha.1`.
All paths below are relative to that root. English strings are quoted from the real
`en` dictionaries.

Two corrections to `chrome-ui.md` found while verifying:

1. `chrome-ui.md` §5.4 writes produced-files copy as `produced.more` = `'+ {count} files'`
   and omits the singular. The real dictionary has **both**:
   `'produced.moreOne'`: `'+ 1 file'` and `'produced.more'`: `'+ {count} files'`
   (`packages/client/ui-deliverables/src/client/locales.ts:83-84`), selected at
   `ProducedFiles.tsx:16-18`.
2. `chrome-ui.md` §5.1 says the `+` ("New tab") control "opens the guide page and only
   appears while the pane holds no guide". That is right, and the exact predicate is
   `canAddTab={paneId => guideIn(surface.layout, paneId) === undefined}`
   (`ui-sidebar-right/src/client/shell/SidebarRight.tsx:315`).

---

## 1. The right-hand panel (dockkit)

### 1.1 Tab strip — kinds, names, icons, order, default, multiplicity, overflow

The strip renders **only the tabs currently open in a pane**; there is no static tab
order (`ui-dockkit/src/components/TabPanel.tsx:257-360`). "Order" therefore means the
order in which kinds are *registered* and the order of the guide entries on the Start page.

| kind | definition id | chip title (EN) | chip glyph | `multiple` | band | registered by |
|---|---|---|---|---|---|---|
| `guide` | `@deepseek-ai/dsh-client-ui-sidebar-right/guide` | `tab.guide.title` = **`Start`** | `CompassGlyph` 56px hero; `CubeGlyph` fallback for a capsule with no icon | — | `builtin` | `ui-sidebar-right/.../tabs/guide/definition.ts` |
| `files` | `@deepseek-ai/dsh-client-ui-sidebar-files` | `type.label` = **`Files`** | `FileTypeIcon kind="folder" size={16}` (coloured folder sheet) | — | `builtin` | `ui-sidebar-files/.../definition.tsx:29-42` |
| `terminal` | `@deepseek-ai/dsh-client-ui-sidebar-terminal` | `title` = **`Terminal`**, then the live terminal name | `TerminalIcon` | **`true`** | `builtin` | `ui-sidebar-terminal/.../index.ts:47-50` |
| `text` | `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` | decoded **basename** of the file | `FileTypeIcon kind={classifyFileType(title)}` | — | `fallback` | `ui-sidebar-documentpreview/.../definition.ts:45-54` |

- **Registered by default in this deployment**: all four. Guide ships inside
  `ui-sidebar-right`; `files`, `terminal`, `text` each register from their own package
  through `ctx.sidebarRightTabs.register(...)` guarded by `ctx.effect`, so all are live in
  the shipped web bundle.
- **Registration order / guide order**: guide entries carry an explicit `order`:
  files `order: 10` (`definition.tsx:37`), terminal `order: 20`
  (`ui-sidebar-terminal/.../index.ts:49`). `SidebarRightTabRegistry.refresh()` sorts the
  guide capsules by that `order` and renders each provider's `sidebar.right.tab.guide.entry`
  slot if it has one, else the generic capsule (`tab-registry.ts:~430`;
  `tabs/guide/GuideBody.tsx:70-118`). Terminal overrides the capsule with `TerminalGuide`
  (a launch button + shell-picker chevron); files uses the generic capsule.
- **Default / active tab**: `defaultSeed(tabs)` (`contract/seed.ts:16-24`) returns the
  **sole guide entry** when exactly one is registered, otherwise the **guide** kind. Two
  ship, so the seeded default is **`Start` (kind `guide`)**. A fresh surface is seeded with
  one empty pane, no tab, and the settle rule inserts the default page the first time the
  column expands. The active tab is then whatever the user last clicked; opening a guide
  capsule **replaces the guide in place** (`replaceTab: true`, `GuideBody.tsx:110-111` and
  `TerminalGuide.tsx:50,66`).
- **Multiple open**: yes, many. Terminal is `multiple: true`, so `openTab('terminal')`
  mints the address `sidebar://terminal/<uuid>` (`service.ts:348-370`) and each open is an
  independent PTY. Resource tabs (`text`) are keyed by **contentId = the full address**, so
  two opens of the same file are one tab and two different files are two tabs
  (`tab-registry.ts` `claim()`). `files` is a page kind (no `patterns`), so it is one tab
  per pane per kind. The pane cap is **two docked panes side by side** —
  `dockPaneIds(state).length >= 2` refuses a further split
  (`ui-sidebar-right/src/client/stores.ts:288`), and `canSplit` in the shell is
  `canSplit(layout) && dockPaneIds(layout).length < 2` (`SidebarRight.tsx:311`). The kit's
  own `MAX_DOCK_PANES = 4` (`ui-dockkit/src/engine/constraints.ts:10`) is not the product
  limit. There is **no per-pane tab-count cap**; the chip box scrolls.
- **Overflow**: horizontal scroll of the chip box with **24px fade masks** on each clipped
  side, driven by `data-dockkit-strip-scroll` ∈ `start` / `end` / `start end`
  (`TabPanel.tsx:126-199`; `STRIP_FADE = 24` at `:199`; CSS masks
  `dockkit.module.css:190-199`). The active chip is scrolled back into view after a close
  or reorder (`useActiveChipInView`, `TabPanel.tsx:176-197`). **No dropdown overflow menu.**
- **Chip geometry**: strip height **28px**, chip height **28px**, chip title span clips and
  fades at its inset instead of ellipsizing (`TabTitle.tsx`; `dockkit.module.css:156-296`).
  The close control (×) draws over the chip's right end and is shown while the chip is
  active, hovered or focused (`TabPanel.tsx:329-330`, `data-dockkit-tab-close`). Keyboard on
  the strip is a roving tablist: Left/Right step and wrap, Home/End jump (`TabPanel.tsx:104`).
- **Context menu**: secondary press on a chip opens `TabMenu`
  (`data-dockkit-tab-menu`) in a portal; it carries `dock.closeTab` = `'Close'` plus the
  embedder's `sidebar.right.tab.menu.item` extras. **No production registrant for that
  extras seat**, so today the menu contains only Close. The comment is explicit that copy
  and float have no menu item — float is drag-release, copy is an embedder API
  (`ui-dockkit/src/components/TabMenu.tsx:1-8`).
- **End-of-strip controls**, in order: the chip box (`flex:1`, the one shrinking part), then
  `+` (`dock.addTab` = `'New tab'`, only while the pane holds no guide), then the pane's
  `dock.splitPane` = `'Split'` control (`hideSplitWhenBlocked`), then `PanelChrome`
  (fullscreen toggle, collapse) at the far end (`TabPanel.tsx:20-21`; `SidebarRight.tsx:315-323`).
  Split-control disabled copy: `dock.splitPaneDisabled` = `'Two panes is the limit'`,
  `dock.splitPaneNarrow` = `'Not enough width to split, widen the sidebar'`.
- **Unregistered-kind fallback**: `tab.unavailable` =
  `'Nothing here can view this kind of content yet.'`
  (`SidebarRight.tsx:218`, `data-sidebar-right-unavailable`).

### 1.2 Files panel

Files: `ui-sidebar-files/src/client/{definition.tsx,FilesBody.tsx,store.ts,face.ts,locales.ts}`.

- **What it is**: a page type (no `patterns`, no address) that browses one absolute
  directory level at a time, rooted at the session `cwd`.
- **State model** (`store.ts:43-52`): one bucket per tab id —
  `{ root, levels: Record<absPath, LevelState>, expanded: string[], scrollTop }`;
  `LevelState = {kind:'loading'} | {kind:'ready',level:{entries,truncated}} |
  {kind:'failed',failure}`. `start()` seeds `{root, levels:{}, expanded:[root], scrollTop:0}`
  (`store.ts:101-103`). The store is an **exclusive per-session store bucketed by tab id**,
  so two Files tabs in one session expand independently and **loaded levels survive the
  body unmounting**; a collapsed level keeps its contents, so reopening draws instantly
  (`store.ts:1-12,133-146`).
- **Tree / disclosure**: directory rows are `<button aria-expanded>` with
  `IconFolderOpen16` / `IconFolderClose16`; nested `<ul class="level">` gets
  `padding-left: 18px` (`.level .level`, `FilesBody.module.css:97`). File rows render
  `FileTypeIcon kind={classifyFileType(name)} size={16}`. A non-file/non-dir entry renders
  as an `aria-disabled` span with `title` = `entry.other` and **no click handler**.
- **Sorting**: endpoint order is discarded; `orderEntries()` sorts **directories first,
  then everything else, each group by `Intl.Collator(undefined,{numeric:true,
  sensitivity:'base'})`** — so `file2` precedes `file10` and case is ignored
  (`FilesBody.tsx:36-51`).
- **Hidden files**: no filtering client-side. The listing endpoint/renderer decides;
  the tree renders every entry the endpoint returns, including dotfiles. (Unlike the
  directory-picker browser, there is no "Show hidden files" toggle here.)
- **RPC and when**: only `workspaceFiles/list(sessionId, path, signal)`
  (`face.ts:53-59`, wire `'workspaceFiles/list'`, generated at
  `packages/api/workspace-files/lib/typert.remote-client.d.ts:21`). Calls:
  `start` → one `list(root)`; `toggle(dir)` → `list(dir)` **only the first time** that
  level is opened (`loaded` flag, `face.ts:143-146`); the header **Reload** button →
  `actions.reset(tabId)` then one `list(path)` for **every** currently expanded path
  (`FilesBody.tsx:223-226`). A per-tab/per-path generation counter retires an in-flight
  listing when a newer request for the same level arrives (`face.ts:112-131`). All listing
  is aborted by the tab's `signal` on close; `forget(tabId)` drops the bucket.
- **File preview behaviour**: clicking a file calls
  `tabActions.openResource(fileAddressFor(sessionId, root, path))`
  (`FilesBody.tsx:218`) — address
  `dsh-resource://file/session/<sessionId>/<path>` — which the tab registry routes to the
  **`text` viewer** (`ui-sidebar-documentpreview`) as a new preview tab in the right panel
  (see §1.4). No external editor, no inline preview.
- **Header**: 38px row showing the root path (directory greyed, last segment full ink,
  faded when clipped via `data-files-path-clipped`) and the sole control `Reload`
  (`t('reload')`, `data-files-reload`, `IconRefreshOutline16`) (`FilesBody.module.css:31-67`).
- **Empty / error / status copy** (`locales.ts:42-56`, en):
  `loading` = `'Reading…'`; `empty` = `'Empty directory'`;
  `truncated` = `'Too many entries, showing only some of them.'`;
  `noWorkspace` = `'This session has no workspace directory.'` (rendered as
  `data-files-state="no-workspace"`); `reload` = `'Reload'`;
  `entry.other` = `'Not a file or a directory, so it cannot be opened.'`.
  Failure lines are mapped by `RemoteFailure.code` (`FilesBody.tsx:59-68`):
  `workspace-file/not-found` → `'That directory is gone. It may have been moved or deleted.'`;
  `workspace-file/outside-workspace` → `'That directory is outside the workspace, so the
  sidebar will not read it.'`; `workspace-file/not-directory` → `'That is not a directory.'`;
  everything else → `'Read failed: {message}'`. A level's failure renders as a note row
  with the code in `data-files-code`; there is no per-level retry — Reload or collapse/
  re-expand is the recovery.
- **Refresh / invalidation**: **manual only**. There is no file-watch subscription in the
  Files tree (the `workspaceFiles/changes` feed is consumed by the `file` resource provider,
  §1.4, not by the tree). Directory contents change under the UI until Reload.
- **Scroll restoration**: body scroll offset is written to the store **once, on unmount**
  and re-applied on remount (`FilesBody.tsx:186-198`).

### 1.3 Terminal panel

Files: `ui-sidebar-terminal/src/client/*`; the React-free model is
`packages/api/terminal-controller/src/client/*` (`ClientTerminals` service `ctx.webTerminals`).

- **Built on**: a real PTY on the Host, rendered by **xterm.js** (`@xterm/xterm` +
  `@xterm/addon-fit`). `TerminalScreen` builds `new Terminal({ minimumContrastRatio: 4.5,
  cursorBlink: true, fontSize: 13, fontFamily: 'ui-monospace, SFMono-Regular, Menlo,
  Consolas, monospace', scrollback: environment.scrollback ?? 0 })` and loads `FitAddon`
  (`TerminalBody.tsx:66-78`). The `TerminalView` model **survives DOM unmount**; the PTY
  process ends only on explicit close. Host default `maxTerminals` is 8 (documented in the
  package README / config; the over-limit copy is quoted below).
- **RPCs** (namespace `terminal`; generated client
  `packages/api/terminal-controller/lib/typert.remote-client.d.ts:11-30`):
  `terminal/environment` (unary), `terminal/list` (unary, per session),
  `terminal/shells` (unary), `terminal/create` (unary, `TerminalCreateRequest`),
  `terminal/follow` (**stream**, `(sessionId, id, attachmentId, signal)`),
  `terminal/write` (unary, `(sessionId, id, attachmentId, data)`),
  `terminal/resize` (unary, `(sessionId, id, attachmentId, cols, rows)`),
  `terminal/rename` (unary, `(sessionId, id, title)`),
  `terminal/close` (unary, `(sessionId, id)`).
- **How output streams in**: `terminal/follow` is an **async iterable** carried by the
  gateway's WebSocket mux (`/api/remote.mux`, `REMOTE_STREAM_MUX_PATH`), one physical
  socket with logical streams opened by name. Frames are typed: a `snapshot` frame carries
  the whole screen; `data` frames carry output deltas; a `revision` accompanies each and
  the client acknowledges the last applied revision (`model.acknowledge(revision)`), so a
  reconnect can resume. `TerminalBody.tsx:113-123` applies frames to xterm and resizes the
  emulator to `frame.info.cols/rows`. **Input**: `xterm.onData(data => model.write(data))`
  (`:83`) — raw key bytes over `terminal/write`, serialized through the model. Resize comes
  from `FitAddon` + a `ResizeObserver` and goes through `terminal/resize`.
- **Tab create / rename / close**:
  * *Create*: the guide's terminal capsule is a launch button
    (`tab.actions.openTab('terminal', { replaceTab: true })`,
    `TerminalGuide.tsx:50`) plus a chevron menu listing `terminal/shells` choices
    (`shellLoading` = `'Loading shells…'`, `shellEmpty` = `'No shells available'`,
    failure label `'Terminal error: {message}'` + `'Retry'`); picking one stores the
    preference and opens with `params:{shellPath}` (`:62-67`). The chosen shell persists
    under `localStorage['dsh.terminal.shell']`
    (`packages/api/terminal-controller/src/client/shell-preference.ts:2`).
  * *Rename*: **double-click the tab chip's title** (the listener is attached to the
    enclosing `[data-dockkit-tab]` / `[data-dockkit-float-grip]` because dockkit captures
    the pointer on its drag handle). An `<input maxLength={120} aria-label="Terminal name">`
    focuses/selects; Enter or blur commits a non-empty changed value via
    `view.rename(title)` → `terminal/rename`; Escape cancels; IME composition is guarded
    (`TerminalTitle.tsx:14-59`). `rename` = `'Terminal name'`.
  * *Close*: the chip × or the context-menu Close triggers the registered close handler
    `('terminal', (sessionId, tab) => ctx.webTerminals.close(sessionId, tab.id, terminalId))`
    (`index.ts:51-53`) → `terminal/close`. A failed close is persisted under
    `localStorage['dsh.terminal.close.v1.<id>']`
    (`close-requests.ts:12-41`) and surfaced by `TerminalCleanup` in the global
    `shell.overlay` stack: `cleanupFailed` = `'Terminal “{title}” could not be ended:
    {message}'` with `Retry`.
- **Extra keys / paste affordances**: **none exist.** There is no on-screen modifier row,
  no `Ctrl`/`Esc`/`Tab`/arrow key bar, no paste button, no `attachCustomKeyEventHandler`,
  and no `navigator.clipboard` use anywhere in `ui-sidebar-terminal` or
  `terminal-controller/client` (grep for `paste|clipboard|KeyEventHandler` → only the
  rename input's `onKeyDown`). Paste works only through the browser's native paste into
  xterm's hidden textarea. This is the single biggest touch gap for a phone port.
- **States / copy** (`locales.ts` en): `loading` = `'Reading terminal environment…'`,
  `creating` = `'Starting…'`, `connecting` = `'Connecting…'`,
  `disconnected` = `'Disconnected.'` + `'Reconnect'`, `closed` = `'Terminal closed.'`,
  `exited` = `'Process exited ({code})'`, `unavailable` = `'Unavailable'` + `'Retry'`,
  `readonly` = `'This view is read-only.'` + `'Take control'`,
  `failed` = `'Terminal error: {message}'`, plus `missingTerminal`,
  `inputFull`, `attachmentEnded`, `invalidOutput`, `terminalLimit` (see `chrome-ui.md` §5.3
  for the full set — unchanged). Recovery ran from the conversation header
  (`ctx.webTerminals.recover(sessionId)` → `terminal/list`, then
  `openTabIn(sessionId,'terminal',{params:{terminalId}})`).

### 1.4 Preview / text panel (`ui-sidebar-documentpreview`)

- **What opens into it**: any address matching `dsh-resource://file/**`
  (`definition.ts:49`), i.e. everything the Files tree and the Deliverables cards address.
  The kind is literally **`text`** (`TEXTPREVIEW_KIND = 'text'`), band `fallback`,
  `canOpen: address => parseFileAddress(address)?.scope === 'session'`
  (`definition.ts:15,45-53`). It is the plain-viewer catch-all; a more specific type
  sharing the address would outrank it.
- **Addressing**: `dsh-resource://file/session/<sessionId>/<path>` — the session id and
  path travel in the address, so a tab addressed into another session reads from *that*
  session (`rpc.ts:67-72`). Tab chip title = `basenameOf(address)` decoded per segment
  (`definition.ts:30-39`); the address itself is the content identity (idempotent opens).
- **Routing decision** (`TextPreview.tsx:106-114`): the registry
  (`document/registry.ts`) ranks live `DocumentPreviewDefinition`s —
  extension/`builtin` band, then **longest matched suffix**, then registration order. If a
  matched definition declares the suffix binary (`binaryDocumentPath`) the text fallback is
  dropped; if nothing matches and the suffix is on the unviewable list
  (`document/unviewable.ts`) it shows the unsupported empty state; otherwise the plain-text
  fallback (`PLAIN_BODY_ID`) is always appended as a choice.
- **Which types preview, and how**:

  | renderer id | extensions | load mode | wrap | notes |
  |---|---|---|---|---|
  | `.../code` | `CODE_EXTENSIONS` = every key of `code/languages.ts` (Shiki grammars) | `text-pages` | yes | syntax-highlighted code |
  | `.../markdown` | `md`, `markdown` | `text-pages` | no | rendered Markdown |
  | `.../html` | `html`, `htm` | `bytes-complete` | no | sandboxed HTML bootstrap; sub-resources via `workspaceFiles/readRelated` |
  | `.../image` | `png jpg jpeg gif webp bmp ico svg`; binary = `png jpg jpeg gif webp bmp ico` (svg stays text-readable) | `bytes-complete` | no | image viewer |
  | `.../pdf` | `pdf` (binary) | `bytes-complete` | no | pdf.js runtime |
  | `.../text` | `[]` fallback | `text-pages` | yes | plain text |
  | *unviewable* | video/audio/archives/office/executables/fonts/disk images/psd/ai/tiff/heic/avif | — | — | `unsupportedFile` = `'Preview is not available for this file type yet.'` |

  If more than one renderer matches (e.g. `.md` also has the text fallback) a **`Open with`**
  menu appears with each candidate's `title()` (`TextPreview.tsx:273-290`, `openWith` =
  `'Open with'`, `data-document-viewer-menu`).
- **RPCs**: text pages via `workspaceFiles/read(sessionId, path, {offset}, signal)`
  (`rpc.ts:80-82`) — the page length is the Host's configured cap; complete files via
  `workspaceFiles/readAll` (`index.ts:95`); HTML sub-resources via
  `workspaceFiles/readRelated` (`html/index.ts`). File **metadata** comes from the standard
  resource hook `useResource<'file'>(tab.contentId)`, served by the `file` provider
  (`packages/api/workspace-files/src/client/provider.ts`): first frame is
  `workspaceFiles/stat`, then live frames from the `workspaceFiles/changes` stream
  (`ChangeFeed`), keyed by `stat.absolutePath`. So the viewer **is** refresh-aware even
  though the Files tree is not.
- **Paging / refresh model** (`face.ts:86-177`, `store.ts`): pages are appended by 1-based
  line offset; scrolling to the bottom auto-loads the next page; a `Load more` button
  (`loadMore` = `'Load more'`) is the explicit affordance. A Host-reported version change
  is announced, **not applied**: a bar shows `changed` = `'The file has changed, showing
  the previous content.'` with `reloadNow` = `'Reload'`. If metadata itself fails while
  content is shown, the failure line replaces the change bar over the pages already read
  (with the same Reload). A page of a newer version arriving past line 1 restarts the walk
  from line 1. Reload drops every page and re-reads page 1 (`reloadPages`), or re-reads the
  whole document (`reloadAll`).
- **Header controls**: path row (same grey-dir/full-ink-name treatment, faded when clipped)
  plus, left→right: `Open with` viewer menu (only when >1 candidate), wrap toggle
  (`wrap.enable` = `'Turn on line wrap'`, `wrap.disable` = `'Turn off line wrap'`,
  `wrap.aria` = `'Line wrap'`, `data-textpreview-tool="wrap"`, only for `wrap:true`
  renderers), Reload (`reload` = `'Read the file again'`, `data-textpreview-tool="reload"`).
- **Empty / error copy** (`locales.ts` en): `loading` = `'Reading…'`,
  `resourceUnavailable` = `'The file resource service is unavailable.'`,
  `rendererUnavailable` = `'The {name} preview is unavailable.'`,
  `unsupportedFile` = `'Preview is not available for this file type yet.'`,
  `error.notFound` = `'File not found. It may have been moved or deleted.'`,
  `error.tooLarge` = `'This page exceeds the {limit} limit and cannot be read.'`,
  `error.notText` = `'Preview is not available for this file type yet.'`,
  `error.notRegularFile` = `'Not a regular file, nothing to display.'`,
  `error.unavailable` = `'Read failed: {message}'`, `retry` = `'Retry'`.
- **Scroll position** per tab persists in the store (like the Files tree), so switching
  view tabs and back restores the reader's place.
- **Line navigation**: the `text` address params support `{line}`; a navigation request
  loads pages until the line is covered, scrolls to it and marks it handled
  (`TextPreview.tsx:165-187`), so deliverables/transcript links can deep-link to a line.

### 1.5 Geometry, open/close, persistence

These are covered in `chrome-ui.md` §5.5 and `design-system.md` §4.11; the exact constants
verified here are:

- `RIGHTBAR_MIN = 300`, `RIGHTBAR_MAX_RATIO = 0.7`, `RIGHTBAR_DEFAULT_RATIO = 0.45`;
  first open = `max(300, round(viewport*0.45))`, clamped to `[300, max(300, viewport*0.7)]`;
  split divider `minPaneFraction = 0.2` (`ui-layout/src/client/columns.ts`).
- `autoFullscreen = viewportWidth < 768`; left sidebar auto-collapses below 1024; centre
  protected at `CENTER_MIN = 400`.
- Panel markers: `data-sidebar-right-panel="push"|"fullscreen"`, `data-sidebar-right-open`,
  `data-sidebar-right-mode`, `data-sidebar-right-toggle`. Collapse copy
  `chrome.collapse` = `'Collapse sidebar'` / `chrome.collapseAria` =
  `'Collapse right sidebar'`; fullscreen `chrome.toFullscreen` / `chrome.exitFullscreen`;
  expand-from-collapsed button in the header corner: `chrome.expand` = `'Open sidebar'` /
  `chrome.expandAria` = `'Open right sidebar'` (`data-sidebar-right-expand`).
- Floating panes: first float `{width:380, height:300}`, min `{width:220, height:140}`,
  one tab each, dock-back/close in the float chrome.
- **Persistence: none for the panel.** The `ui-layout` store and the
  `createSidebarRightStore(seed)` are both transient (no persist key), so width, expanded
  state, tabs, active tab and split ratios all reset on reload. The **only** `localStorage`
  keys in this whole area are the terminal's: `dsh.terminal.shell` and
  `dsh.terminal.close.v1.<id>`.

---

## 2. The Trajectory view

The conversation header has exactly two views. `Chat` (`ui-chat/src/client/apply.ts:99-101`,
`order: 0`, hard-coded default `DEFAULT_VIEW_ID = 'chat'`) and **`Trajectory`**
(`ui-trajectory/src/client/index.ts:79-84`, `order: 10`, label `view.trajectory` =
`'Trajectory'`). Both register into the `conversation.view` slot; the shell renders only the
active one (`DefaultConversationViews.tsx:37`). This section documents the whole alternate
view so a port can decide to build or consciously omit it.

### 2.1 What it is

A dense, turn-aware **request/response ledger** with a Network-style overview timeline and a
right-hand event inspector. Source package `packages/client/ui-trajectory/src/client/`.
Locale namespace `trajectory` (all keys at `locales.ts`). It is entirely client-side
projection — **there is no `trajectory/*` RPC**. The view is a `ConversationViewDefinition`
with `target: 'trajectory'` (`trajectory-snapshot-builder.ts:303-319`), exposed to the view
component through the slot-injected `useTrajectory` selector hook
(`index.ts:75-78`; contract at `trajectory-contract.ts:75-83`).

Note: `TrajectoryTurn.tsx`, `TrajectoryTurnHeader.tsx`, `TrajectoryGroupHeader.tsx` exist
but are **only referenced by tests** (`ui-trajectory/tests/layout.client.spec.tsx`); the
live view is `TrajectoryView` → `TrajectoryToolbar` + `TrajectoryTimeline` +
`TrajectoryTable`. A port can ignore those three files. `column.input/output/think/time`
are likewise only used by the dead `TrajectoryTurnHeader`; the live timeline uses
`column.input`, `column.model`, `column.tools`.

### 2.2 What feeds it (stream + RPC)

The snapshot is assembled from **durable session events** by five registered node
definitions (`index.ts:69-74`). Matched event types, exactly:

| contribution | matched durable event types | file |
|---|---|---|
| input messages | `user/message`, `agent/inbox/spliced` (target `next-step`) | `trajectory-message-definitions.ts:118,139` |
| prompt/header | `system/message`, `request/header` | `trajectory-request-header-definition.ts:36,102` |
| assistant | `step/start`, `assistant/live-chunk`, `assistant/attempt`, `assistant/message`, `llm/retry`, `step/end`, `turn/end` | `trajectory-assistant-definition.ts:335-368,427` |
| tools | `tool/call`, `tool/result`, `tool/ptc-dispatch-start`, `tool/ptc-dispatch` | `trajectory-tool-definition.ts:220-224` |
| compaction | `compaction/start`, `compaction/summary`, `compaction/end`, `session/end-seed` | `trajectory-compaction-definition.ts:86,98-99,121` |

Those events arrive over the ordinary **`session/follow`** durable stream
(`protocol.md` §4.1/§4.3) via the conversation binding; no extra subscription. **Older
history** is paged with the ordinary `session.loadOlder()`: the view keeps a window of
`HISTORY_PAGE_NODES = 50` event nodes (`TrajectoryView.tsx:35,504-508`) and shows a
`Load earlier history` row/`…` timeline stub when `session.hasMore`. Images render through
the `conversation.trajectory.images` slot, registered by `ui-attachment`.

### 2.3 Toolbar

`TrajectoryToolbar.tsx`, sticky 32px tall (`.root { height: var(--dsh-trajectory-toolbar-height) = 32px }`),
`role="toolbar"`, `aria-label` = `'Trajectory toolbar'`. Left→right:

1. **Duration** toggle — `aria-label` = `'Use actual duration'`, `aria-pressed` bound to the
   persisted preference; tooltip swaps to `'Use equal-width operations'` when on; glyph is a
   clock. This is the only timeline-mode control a user can reach.
2. **Actual time** switch — **rendered with the HTML `hidden` attribute** in this checkout
   (`TrajectoryToolbar.tsx:74-86`). It is dead code; a port can omit it.
3. **Turns** collapse-all — label toggles between `'Expand turns'` / `'Collapse turns'`,
   glyph `⊞`/`⊟`, `aria-pressed`.
4. **Calls** collapse-all — `'Expand calls'` / `'Collapse calls'`.
5. **Search** input at the right end, `type="search"`, `aria-label` = `'Search trajectory'`,
   placeholder `'Search'` (`toolbar.search` / `toolbar.searchPlaceholder`); flex-basis
   164px, min 84px, 22px tall.

### 2.4 Timeline (top overview)

`TrajectoryTimeline.tsx`; 50px tall, grid `44px minmax(0,1fr)`
(`TrajectoryTimeline.module.css:15-17`). Left gutter has three lane labels top-to-bottom:
**`Input`**, **`Model`**, **`Tools`** (`column.input/model/tools`). Records are projected into
three lanes by kind (`timeline.ts:60-64`): `system|user|context` → lane 0 (Input),
`message|compacted` → lane 1 (Model), `tool|subtool` → lane 2 (Tools).

- Four projection modes (`TrajectoryTimelineMode`): `sequence` (equal-width operations by
  default), `duration` (recorded durations), `time` (equal width, actual wall clock),
  `actual` (recorded durations + wall clock). Reachable UI only toggles `sequence ↔ duration`
  (the Duration button) because the Actual-time switch is hidden; `time`/`actual` exist in
  code for the persisted-preference path.
- Interactions: **left drag** selects a time/operation range (`MINIMUM_DRAG_PX = 3`) and
  filters the ledger to the records inside it (`timelineFocusIndexes`, rows get
  `data-timeline-focus="inside"|"outside"`); **right-button drag** pans when zoomed;
  **wheel** zooms about the cursor (`exp(deltaY*0.0015)`, min 4 operations in sequence
  mode); **click a span** selects that record in the ledger; **click whitespace** focuses the
  nearest record; **Escape** clears the range; a `…` control at the left edge is the
  earlier-history load boundary (tooltip `'Click to load earlier history'` /
  `'Loading earlier history…'`). `aria-label` = `'Timeline overview; drag horizontally to
  focus events'`; empty = `'No timing data'`.
- Tooltips (500ms delay) compose: kind label, `startedAt → startedAt+duration` (local
  `HH:MM:SS.mmm`), `'Total {duration}'`, and `'TTFT {ttft} · Decoding {decoding}'`.

### 2.5 Ledger table — row structure and columns

`TrajectoryTable.tsx`. An HTML `<table>` with `<colgroup>` of exactly **two columns**
(`TrajectoryTable.tsx:2566-2569`; `TrajectoryTable.module.css:137-145`):

| column | class | width | content |
|---|---|---|---|
| 1 | `.eventColumn` | **122px** (`84px` under `:lang(zh)`) | kind badge + turn label + request-boundary marker |
| 2 | `.contentColumn` | `auto` | one-line summary, or `summary → result` |

Every `<tr>` is one **record** (a `TrajectoryCellProps`). Rows are 30px; collapsed-summary
rows are 20px; the table is `table-layout: fixed`, `font: var(--dsw-font-xxs-12)`, with a
`aria-rowcount`. Rows carry rich data attributes for styling/testing:
`data-kind`, `data-record-index`, `data-request-only`, `data-error`, `data-running`,
`data-turn-start`, `data-turn-end`, `data-group-start`, `data-collapsed-summary`,
`data-selected`, `data-timeline-focus`, `data-trajectory-row-key`.

What each row represents and the exact kind labels (`TrajectoryTable.tsx:49-57`, locale en):

| `data-kind` | badge label | badge icon | source of the row |
|---|---|---|---|
| `system` | `SYSTEM` | `IconSettingsOutline16` | `system/message` / `request/header`: the initial system prompt, or a "System Prompt Updated" / "Tools Updated" marker |
| `user` | `USER` | `IconUserOutline16` | `user/message` with `source.kind === 'user'`, and steering input |
| `context` | `CONTEXT` | custom info circle | `user/message` whose source is not `user` (plugin / goal / session-reference / agent-instructions / skill-invocation) |
| `compacted` | `COMPACTED` | custom four-arrows glyph | a compaction request (summary of the compacted context) |
| `message` | `ASSISTANT` | `IconSparkle16` | one assistant message/step; usage and timing attach here |
| `tool` | `TOOL` | custom wrench | a top-level `tool/call`+`tool/result` pair |
| `subtool` | `SUBTOOL` | custom wrench | a child call under a `run_code`/PTC dispatch |

`kind.message` (`'Message'`) and `kind.sub` (`'Sub'`) exist in the dictionary but are **not
used by any badge** in this checkout.

**Source classification labels** — the details inspector's *Source* field uses
`messageSourceLabel()` (`TrajectoryTable.tsx:880-901`) over the durable `user/message`
source object, with exact strings:

| source shape | label |
|---|---|
| `{kind:'user'}` | `source.user` = `'User'` |
| `{kind:'plugin', plugin:'<name>'}` | `source.pluginNamed` = `'Plugin · {plugin}'` |
| `{kind:'plugin'}` (no name) | `source.plugin` = `'Plugin'` |
| `{kind:'goal', round:n}` (`n > 0`) | `source.goalRound` = **`'Goal · Round {round}'`** |
| `{kind:'goal'}` (no/invalid round) | `source.goal` = `'Goal'` |
| not an object / no `kind` | `source.unknown` = `'Unknown'` |
| any other non-empty `kind` string | the kind with its first letter upper-cased (e.g. `session-reference` → `Session-reference`) |
| no source at all | `source.notRecorded` = `'Source not recorded'` |
| tab *Source* JSON tree label | `source.messageJson` = `'Message source JSON'` |

(The Chat view has a separate `contextProducer()` projector
`trajectory-event-projection.ts:60-78` that maps `session-reference`→recall,
`agent-instructions`→inject, `plugin`→inject, `skill-invocation`→inject; the Trajectory
inspector does **not** use it — it shows the raw source JSON.)

**Turn / group structure** (`layout.ts`): the ledger is grouped into **turns** (`turn.label`
= `'Turn {turn}'`, compact `#{turn}` under narrow width; standalone compactions land in a
`null` turn shown as `section.betweenTurns` = `'Between turns'`). Inside a turn, groups are
titled `group.message` = `'Message'`, `group.step` = `'Step {step}'`, or
`group.compaction` = `'Compaction {seq}'`. Orphan turn-0 rows fold into Turn 1
(`layout.ts:532-540`). Row order within an expanded group is source order; a tool's nested
sub-calls are interleaved immediately after the parent.

**Row content composition** (`RecordPresentation`, `TrajectoryTable.tsx:1012-1127`):
`displayText` = the kind's summary — for tools `${name} · ${args}` (args from the raw call,
previewed to a single line), for messages the first text/reasoning preview, for
user/context the message text; `resultText` = the tool's result preview. The cell renders
`displayText` and, when a result exists, `→ resultText` (the arrow is a separate span). A
tool preview is built by `trajectoryPreviewText()`: source capped at **2048 chars**, plain
text at **512 chars**, whitespace collapsed, trailing `…` when truncated
(`trajectory-preview.ts:3-16`). An assistant row with no visible text is
`(tool call only)` (`record.toolCallOnly`) or an image count `layout.imageOnly` =
`'Images ×{count}'`; no content at all is `record.noContent` = `'No content'`.

**Request boundary markers**: for a `(turn, group)` that has an assistant request, the first
non-user/context record of the group renders a small `requestBoundaryControl` button in the
event column, labelled `request.label` = `'Request #{request}'` (or
`request.labelCompaction` = `'Request #{request} · Compaction'`), with a per-run horizontal
offset (`8px` per stacked request). Clicking it opens the **request inspector** instead of a
record inspector.

**History paging**: when `hasOlderRecords`, a 30px `historyLoadRow` with a button
`'Load earlier history'` / `'Loading earlier history…'` sits at the top; the initial load
shows a full-width `'Loading trajectory…'` status bar. Auto-follow: the table follows the
tail while the user is within 2px of the bottom; scrolling up cancels follow. Virtualized
with `@tanstack/react-virtual` when `hasOlderRecords` or **> 100** records
(`VIRTUALIZATION_THRESHOLD = 100`, overscan 12).

### 2.6 Row expansion, folding, and the details inspector

There is **no inline row expansion**. "Expanding" a row means selecting it, which opens a
**right-hand `<aside>` inspector** (`aria-label` = `'Event details'`,
`TrajectoryTable.tsx:2884-3457`). Two other fold mechanisms exist.

**Folds** (pure row-visibility):
- *Turn fold*: a turn with more than one content row can be folded to its first row plus a
  20px summary row `…  {N} steps · {M} tool calls` (`summary.steps.*` / `summary.toolCalls.*`).
  Click the summary (or double-click a turn-start row / use the toolbar Turns button) to
  toggle. `collapsibleTurnIds` excludes `system` and request-only rows (`TrajectoryView.tsx:428-441`).
- *Assistant-calls fold*: when a `message` row is immediately followed by `tool`/`subtool`
  rows, double-clicking it (or the toolbar Calls button) folds those calls into
  `…  {N} tool calls · {names}` (`summarizeAssistantTools`, `TrajectoryTable.tsx:670-681`);
  `openRecordSummary` auto-unfolds both first.

**Inspector** (`TrajectoryTable.tsx:2884+`): opens when a record or a request is selected;
width is free between `DETAILS_MIN_WIDTH = 320` and `DETAILS_MAX_WIDTH = 720` with a
`TABLE_MIN_WIDTH = 280` floor, resizable by a vertical `role="separator"` drag (16px per
arrow key; double-click resets). Header: a kind badge + location `'Turn {n} · Step {m}'`
(or `'Turn {n}'` for compactions) and a `×` close. Body is a `role="tablist"` whose tabs
depend on the selected thing:

| selection | tabs (EN labels) |
|---|---|
| request (assistant) | `Summary` / `Options` (only if a `requestConfig` was recorded) / `Usage` / `Timing` |
| request (compaction) | same; Summary shows `Purpose: Compaction` |
| `system` | `System Prompt` / `Tools` (+ `Diff` when a previous prompt snapshot exists) |
| `compacted` | `Summary` / `Raw Output` |
| `user` / `context` / `message` | `Summary` / `Preview` / `Raw` (+ `Source` when a message source exists) |
| `tool` / `subtool` | `Summary` / `Payload` (or `Code` for a PTC program) / `Result` / `Schema` / `Timing` |

Notable panel content:
- *Summary* for a request: `Status` (`Completed`/`Failed`/`Pending`),
  `Provider`/`Model` when known, `Tool calls`, `Subtool calls`, `Error`,
  `Retry` (`'Scheduled {retry} of {maximum}'`), `Retry delay`, and a jump link to the
  `Assistant Message` / `Compacted` result record. Below it are collapsible preview sections
  (Options, Usage, Timing).
- *Summary* for a record: optional `Source` / `Hierarchy` jump links (Request #, Assistant
  Message, Tool Call), `Status`, `Tokens` + `Reasoning` + `Content` for messages, and a
  `Duration` for user/context.
- *Timing*: `Started` (local `YYYY-MM-DD HH:MM:SS.mmm`; **click to toggle to a Unix
  timestamp**), `Total duration`, `TTFT`, `Generation`, `Throughput` (`{x} tok/s`);
  fallbacks `'Not recorded'`, `'Step start unavailable'`, `'First token unavailable'`,
  `'Usage unavailable'`, `'Output tokens unavailable'`, `'Duration too short'`, `'Pending'`.
- *Usage*: two groups `'This request'` and `'Session cumulative'` with `Input` (input +
  cache read + cache write), `Cached`, `Cache created`, `Other`, `Output`, `Reasoning`,
  `Content`; `'Usage not reported'` when absent.
- *Options*: JSON tree of the request config, `'Options not recorded'` when absent.
- *System Prompt* / *Tools*: rendered Markdown and a collapsible tool catalog (each tool's
  description + parameters JSON tree); `'No system prompt in this request'`,
  `'No tools in this request'`. *Diff* renders a green/red line diff of the system prompt
  and of the tools JSON.
- *Payload / Result*: JSON trees or block lists; errors are tinted; `'(no payload captured)'`
  / `'No result captured'` / `'No output'`. The *Payload* tab shows `Code` instead when the
  call is a `run_code`/PTC program (`codeProgram`), with a line-numbered code block, a wrap
  toggle, an "original JSON" toggle and a copy button.
- *Schema*: the call-time model-visible tool schema (`name`, `description`, `parameters`
  JSON tree), `'Schema unavailable'` when not captured.

**Cross-view inspect**: `viewRequest {view:'trajectory', focus: callId}` — when another view
passes a call id, the ledger finds that call's record, unfolds it and scrolls it into view
(`TrajectoryTable.tsx:2379-2411`, `onInspectApplied`). This is how a Chat tool card's
"Inspect" button jumps into Trajectory.

### 2.7 Search

Client-side only (`trajectory-search-index.ts`). Space-separated terms, case-insensitive,
**AND** semantics. The index contains each record's turn/group/kind, `text`,
`previewMarkdown`, `inputDetail`, `outputDetail`, `thinkingDetail`, `schemaDetail`,
`result`, `resultPreviewMarkdown`, `callId`, every source/output block's
type/content/callId/toolName/attachment name, and the JSON of `messageSource`,
`promptDetail`, `previousPromptDetail`. While a query is active the ledger is **filtered**
to matching records (`filterRecords`) — turn/group headers are recomputed for the filtered
set. The index rebuild is throttled to `3000ms` (`SEARCH_INDEX_THROTTLE_MS`).

---

## 3. Settings modal

Shell/domain packages: `ui-settings` (base domain: slot contract, `ctx.settingsScope`,
one `settings/describe` mirror), `ui-settings-general` (the actual shell + General rows).
The other sections register `settings.section` from their own packages.

### 3.1 Shell, geometry, open/close

- **Open**: only from the **sidebar-foot gear button** (the `sidebar.settings` hole,
  `ui-sidebar/src/.../SidebarRoot.tsx:273`): `aria-label` = `settings.trigger` =
  `'Settings'`, `aria-haspopup="dialog"`, `aria-expanded={open}`. Trigger geometry:
  `triggerRow` width `calc(100% + 4px)`, margin `4px -2px`, 42px tall, radius 12px; the rail
  button is 36×36, radius 50%. **There is no keyboard shortcut** — `SettingsRoot`'s only
  keydown listener is Escape (`SettingsRoot.tsx:58-64`).
- **Close**: header Close button (`settings.close` = `'Close'`, `data-…`/visually-hidden
  label), a click on the mask, or document Escape.
- **Presentation**: `SettingsRoot` renders its **own** overlay/dialog markup inline — it is
  *not* the shared `ui-primitives` `Modal` and is **not body-portaled**:
  - `.overlay` fixed inset 0, z-index 1000, flex-centered;
  - `.mask` absolute inset 0, `background: var(--dsw-alias-bg-mask-1)`,
    `backdrop-filter: var(--dsw-mask-blur)`, `aria-hidden`, click = close;
  - `.panel` `role="dialog"` `aria-modal` `aria-labelledby` → the title node,
    **width 800px**, **height `min(800px, calc(100vh - 48px))`**, `max-width:
    calc(100vw - 48px)`, **border-radius 32px**, overflow hidden, `--dsw-alias-bg-layer-2`,
    prominent elevation.
- **Left nav rail**: **188px**, padding `22px 12px 0`, gap 18. Title 16px/500, line-height
  24. Cell height **40px**, radius 12, padding `9px 16px 9px 12px`, label 14px;
  the active cell carries `aria-current="true"`. Nav glyph by section id
  (`SettingsRoot.tsx:30-37`): `models` → `IconDataOutline16`, `agent-presets` →
  `IconAgentPresetOutline16`, `plugins` → `IconPersonalizationOutline16`,
  `archived-sessions` → `IconArchiveOutline20`, anything else → `IconSettingsOutline16`.
- **Content header**: 54px, padding `20px 14px 8px 10px`, actions right-aligned gap 8. The
  Close button is 28×28, radius 28, 14px glyph. Options area: padding `0 24px 24px`,
  vertical scroll.
- **Focus**: the Close button is focused on mount and focus returns to the trigger on
  close. **There is no focus trap and no `#root.inert`** (unlike the blocking onboarding
  modals).
- **"Open configuration file" header action**: registered as `settings.action`,
  id `open-document`, order 0, **only when the controller is constructed with loopback**
  (`ui-settings-general/src/client/index.ts:76-78,164-172`), and it *renders* only when the
  describe view reports `hasDocument`. Copy: `openDocument` =
  `'Open configuration file'`, error `'Could not open configuration file'` (alert, max-width
  180px), disabled while opening. RPC: pathless **`settings/openSettingsDocument()`**.
- Shell copy (NS `settings`): `trigger`/`title` = `'Settings'`, `close` = `'Close'`;
  connection status `connection.error` = `'Disconnected'`, `connection.connecting` =
  `'Reconnecting'`, `connection.connected` = `'Connected'`, `connection.reconnect` =
  `'Disconnected, reconnect now'`, `connection.restart` = `'Reconnecting, reconnect now'`.

### 3.2 Exact section list (in nav order)

Exactly **five** client packages register `settings.section`; the shell sorts by `order`
(`ui-settings-general/src/client/index.ts:114`).

| order | id | nav label (EN) | registering file | locale NS / key |
|---|---|---|---|---|
| 0 | `general` | **`General`** | `ui-settings-general/src/client/index.ts:175-182` | NS `settings`, literal key `general.nav` |
| 10 | `models` | **`Models`** | `ui-settings-models/src/client/index.ts:131-140` | NS `settings.models`, key `nav` |
| 15 | `plugins` | **`Plugins`** | `ui-settings-plugins/src/client/index.ts:145-153` | NS `settings.plugins`, key `nav` |
| 20 | `agent-presets` | **`Agent presets`** | `ui-agent-preset/src/client/index.ts:213-220` | NS `settings.agentPreset`, key `nav` |
| 25 | `archived-sessions` | **`Archived sessions`** | `ui-settings-unarchive-sessions/src/client/index.ts:41-48` | NS `settings.archivedSessions`, key `nav` |

The section bodies are contributed to `settings.section`; the nav row only needs the
`label()` each registration supplies. There is no tab strip — it is a nav rail.

### 3.3 Rows per section

#### General (`settings.general.item`) — six rows

All six read from the single `settings/describe` mirror and **write through
`settings/mutate`** with a per-row namespace. Rows are stacked by `GeneralSection.tsx`;
each is registered individually.

| order | id | exact label(s) | control | write (RPC + namespace) |
|---|---|---|---|---|
| -20 | `permission` | title `'Permission'`; desc `'Choose the default permission mode for new sessions'` | button + `Menu` (custom, `aria-haspopup=menu`), disabled when not writable/busy | **`settings/mutate`** ns `permission`, `{op:'set', path:['defaultPreset'], value}` |
| 0 | `language` | title `'Language'` | button + `Menu` over registered locales | **`settings/mutate`** ns `locale` (`setLocale`) |
| 10 | `appearance` | title `'Appearance'`; values `'Light'` / `'Dark'` / `'System'` | three `aria-pressed` swatch buttons | **`settings/mutate`** ns `ui-theme` (`setTheme`) |
| 11 | `font-size` | title `'Font size'`; desc `'Only affects conversation content'`; unit `'px'`; aria `'Increase font size'` / `'Decrease font size'` | stepper, clamped **12–17 px**, default 14 | **`settings/mutate`** ns `ui-theme`, field `fontSize` |
| 12 | `transcript-view` | title `'Conversation display'`; desc `'Controls process content in completed turns'`; options `'Normal'` / `'Compact'` | select | **`settings/mutate`** ns `ui-chat`, field `transcriptView` (default `compact`) |
| 20 | `composer-enter` | title `'Send behavior while busy'`; desc `'What Enter and the Send button do while the agent is running; Cmd/Ctrl+Enter uses the other behavior'`; options `'Queue'` / `'Steer'` | select | **`settings/mutate`** ns `ui-conversation`, field `busyEnter` |

Correction to `chrome-ui.md` §6.2: the **Permission** row's options come from the
`settings/describe` schema field `permission.defaultPreset` (a const union), **not** from
`permissionPresets/catalog` (that catalog drives the in-session `/permission` picker). The
risk gate fires only for `full-access`: `RiskConfirmation` title `'Enable Full access?'`,
description `'Full access lets new sessions reduce confirmation steps and perform more
actions directly, including sensitive operations, file changes, or external commands.
Only use it when you trust subsequent tasks.'`, acknowledge `'I understand the risks and
want to continue'`, cancel `'Cancel'`, confirm `'Enable Full access'`; `auto` has no local
confirm. The row renders `null` when the namespace is unavailable.

#### Models (NS `settings.models`)

- Reads: **`llm/listProviders`**, **`llm/listConfigurableProviders`**,
  **`credentials/describe`**, and the shared `settings/describe`.
- Writes: **`settings/mutate`**, **`credentials/set`**, **`credentials/unset`**,
  **`llm/discoverModels`**.
- **Correction**: Models does **not** call `session/modelCatalog`, and does **not** call
  `settings/update`. `session/modelCatalog` belongs to Plugins ▸ Subagent.
- Copy highlights: `intro` = `'Enter your API keys to use models from the following
  providers.'`, `add` = `'Add provider'`, `customAdd` = `'Add a custom provider'`,
  `customTitle` = `'Custom provider'`, `customTag` = `'Custom'`, `edit`/`editProvider` =
  `'Edit'` / `'Edit {provider}'`, `remove`/`removeProvider` = `'Delete'` / `'Delete
  {provider}'`, `apply`/`applying` = `'Apply'` / `'Applying…'`, `savedProvider` =
  `'Saved {provider}.'`, `credentialConfigured` = `'API key configured'`,
  `credentialMissing` = `'API key missing'`, `readOnly` = `'The settings document is
  read-only in this deployment.'`, `loadFailed` = `'Loading the provider directory
  failed'`, `conflict` = `'Someone else changed these settings while this card was open.
  Close it and reopen to edit the current values.'`.
- Fields: API key (`keyInput`/`keyPlaceholder`/`keyPlaceholderNative`/`keyStored`/
  `keyEnvLocked`/`keyBlank`/`keyIllegalCharacters`), custom-provider `'Provider ID'`,
  `'API protocol'`, `'Display name'`, `'Base URL'` (`'Provider default'`,
  `https://api.deepseek.com`, `https://api.deepseek.com/anthropic`); model rows `'Model
  ID'`, `'Display name'`, `'Context window'`, `'Max output tokens'`, `'Capacities'`,
  `'Add model'`/`'Delete model'`, `'Restore defaults'`, `'Using the adapter defaults'`,
  `'Customized model catalog'`, `modelsEmpty` = `'No models will be shown in the selector.
  Unlisted IDs can still be sent directly.'`. Fetch flow `'Fetch available models'` /
  `'Asking the provider…'` / `'Choose models to add'` / `'Search models'` /
  `'Add selected'` → `llm/discoverModels`.
- Declared child slots `settings.models.provider-card` (keyed) and `settings.models.footer`
  (list) have **no shipped registrant** — those areas render nothing in the web bundle.

#### Plugins (NS `settings.plugins`) — two tabs

Tab order: `configurable` order 0 = `'Plugin configuration'`; `all` order 10 =
`'Plugin list'` (from `ui-settings-plugin-inventory`). Section `intro` = `'Configure and
inspect the plugins installed in this deployment.'`

Configurable cards (keyed `settings.plugin.item`; edits are staged then Saved through
`settings/mutate`):

| key | card title / description | fields (EN) | extra RPC |
|---|---|---|---|
| `shell` | `'Shell'` / `'Limits every command the agent runs.'` | `'Command timeout (ms)'`, `'Output cap per stream (bytes)'` | — |
| `agent-loop` | `'Agent loop'` / `'How the agent dispatches tool calls.'` | `'Parallel tool calls'` | — |
| `web-search-deepseek` | `'Web search'` / `'The DeepSeek search provider.'` | `'API key'`, `'Endpoint'`, `'Max searches per request'` | `credentials/describe`, `credentials/set` (never `unset`); credential ref = settings `apiKeyEnv` else `DEEPSEEK_API_KEY` |
| `subagent-model-selection` | `'Subagent'` | toggle `'Allow agents to choose models for subagents'`, list `'Models agents may choose'` | `session/modelCatalog` |

Shared card chrome: `'Save'`/`'Saving…'`, `'Discard'`, `'Unsaved'`, `'Reset to default'`,
`'Overridden'`, `'Show settings'`/`'Hide settings'`, `'This deployment stores settings
read-only.'`, `'This deployment exposes no plugin settings.'`, `'Enter a number, or leave
blank to use the default.'`, save failure `'The deployment did not accept these values;
they were left for you to correct.'`

Plugin inventory tab (`ui-settings-plugin-inventory`): reads **`pluginInventory/list`**;
search/preset-switcher/expand-collapse are **client-side**. Extra labels not in
`chrome-ui.md`: `presetSubtitle` = `'Composed per session by agent presets'`,
`switcherLabel` = `'Choose the agent preset to inspect'`, `presetOptionDefault` =
`'{name} (default)'`, `presetOptionBroken` = `'{name} (failed to load)'`, `moduleLabel` =
`'Module'`, `fromPreset` = `'From'`, `condition` = `'Disabled when'`, `configuration` =
`'Configuration'`, `runtime` = `'Status'`, `matchesInOtherPresets` = `'{count} more matches
in other presets: '`.

#### Agent presets (NS `settings.agentPreset`)

- Controls: a `Switch` `showPicker` = `'Allow switching Agent modes'` (+ tag `'Beta'`),
  description `'When enabled, new tasks can choose Standard, PTC, Creator, Minimal, and
  custom modes…'`; roster groups `'Built-in'` / `'Custom'`.
- The card body itself sets the default: its `aria-label` is
  `'{selectionAction}: {name}'`, where `selectionAction` is `'Set as default'`,
  `'New task default'` (is default + picker on), `'Default'` (is default + picker off), or
  `'Turn on Agent mode selection to choose a default'`. (Corrects `chrome-ui.md`'s
  implication of a separate "Set as default" button.)
- Icon buttons: `'View'` (built-in), `'Open folder'` / `'Show location'` (custom),
  `'Duplicate'`, `'Delete'` (custom). Copy dialog: `'Identifier'` (placeholder
  `'my-agent'`) + `'Name'`, buttons `'Create'` / `'Creating…'`.
- RPCs: **`agentPresets/list`**, **`agentPresets/read`**, **`agentPresets/copy`**,
  **`agentPresets/deletePreset`**; **`settings/update`** ns `agent-preset` for the default
  and `showPicker` (the **only** `settings/update` caller among the settings packages);
  **`settings/canOpenAgentPresetDirectory`**, **`settings/openAgentPresetDirectory`**.
  `agentPresets/select` is the new-session chip, not this section.
- Built-in preset names/descriptions: `Standard mode`, `PTC mode`, `Minimal mode`,
  `Creator mode`.

### 3.4 Archived sessions — detail

Package `ui-settings-unarchive-sessions` (`ArchivedSessionsSection.tsx`, `locales.ts`).

- **Data source**: `useWorkspaces()` (owners map: each workspace's `title` +
  `sessionIds[]`) joined with `useSessions().byId` summaries. `archivedSessionIds` is in
  host order (oldest-archived first); the section does `[...archivedSessionIds].reverse()`
  so rows show **newest-archived first**. A session with no loaded summary is **dropped** —
  no row, no action.
- **Grouping**: by **owning workspace title**. A session that belongs to no workspace is
  grouped under `ungrouped` = **`'Ungrouped'`**. (So both labels the task asked about exist:
  a workspace title, and `'Ungrouped'`.)
- **Row layout**: title 13px, ellipsized, **no `title=` attribute** (the full text is only
  in the button's aria-label); a 12px tertiary meta line =
  `[workspace, relativeTime].join(' · ')`, i.e. **title, workspace, relative time**. Time
  is client-rendered relative (`now` = `'now'`, `'{n}min'`, `'{n}h'`, `'{n}d'`, `'{n}mo'`,
  `'{n}y'`).
- **Search**: client-only `useState('')`; `<input type="search">` with `placeholder` and
  `aria-label` both `search` = `'Search archived sessions'`. Filter is
  `query.trim().toLowerCase()` matched against **title and workspace only** (not time).
  Input 32px high, radius 8.
- **Unarchive action**: a `Button variant="outline" size="sm"` with visible text
  `unarchive` = **`'Unarchive'`** and `aria-label` `unarchiveNamed` =
  `'Unarchive {title}'`. `onClick` calls `unarchive(row.id).catch(...)`, wired
  `sessionId => ctx.uiWorkspace.unarchiveSession(sessionId)`. There is **no per-row busy/
  disabled state and no optimistic removal**.
- **RPC**: **`workspace/unarchiveSession`** (namespace `workspace`, host owner
  `@Remote('unarchiveSession')` in `packages/api/workspace-controller/src/index.ts:43`;
  client model `ui-workspace/model.ts:186-190`). This is the **only** restore path for an
  archived session — there is no per-row undo in the sidebar (`chrome-ui.md` §2.5).
- **Empty states**: no archived ids → `empty` = `'No archived sessions.'`; archived ids but
  zero loaded rows → `unavailable` = `'No archived session here can be restored.'`; filter
  matched nothing → `emptySearch` = `'No matching sessions.'`; while the sessions store is
  not ready → `loading` = `'Reading sessions…'`.
- Full EN dictionary (NS `settings.archivedSessions`): `nav` = `'Archived sessions'`,
  `search` = `'Search archived sessions'`, `loading` = `'Reading sessions…'`,
  `empty` = `'No archived sessions.'`, `unavailable` = `'No archived session here can be
  restored.'`, `emptySearch` = `'No matching sessions.'`, `unarchive` = `'Unarchive'`,
  `unarchiveNamed` = `'Unarchive {title}'`, `ungrouped` = `'Ungrouped'`.

### 3.5 What does NOT exist (confirmed here)

Confirmed by grepping every `settings.section` registrant — there are exactly five, so:

- **No About / version section.**
- **No auth / account / security / logout UI** (and no `auth/*`, `account/*`, `security/*`
  RPC prefixes; there is no logout operation — browser auth is the signed cookie in
  `protocol.md` §5).
- **No Skills settings section** (`ui-skill` never registers `settings.section`; skills are
  only a composer slash-command source reading `skills/list`).
- **No `settings/models` RPC** — model selection rides `session/selectModel` from the
  composer.
- **No focus trap** in the settings panel and **no keyboard open shortcut**.
- The Models child slots `settings.models.provider-card` / `settings.models.footer` have no
  shipped registrant.

### 3.6 Client-side vs host-side, and the loopback restriction

- **Client-only (no RPC)**: modal open state and the active section id
  (`SettingsRoot.tsx`); the onboarding-completed set; the Archived-sessions search box;
  plugin-inventory search / preset-switcher / expand-collapse; agent-preset copy/delete
  draft state; mask/Escape handling.
- **Host-side (RPC)**: every General row (namespaces `permission`, `locale`, `ui-theme`,
  `ui-chat`, `ui-conversation`), Models config + credentials, Plugins card saves, the
  agent-preset default/`showPicker`, and archived unarchive.
- **The loopback gate** (`ui-settings/src/client/index.ts:58`):
  `const persistence = ctx.remote.$host.isLoopback ? 'host' : 'memory'`, passed to both
  `new SettingsDescribeMirror(ctx, persistence)` and
  `new SettingsScopeBinder(ctx, {mirror, schema, persistence})`. Consequences off-loopback:
  the mirror status is `'unavailable'`; `load()/ensure()` resolve immediately; each scope
  starts `{status: persistence === 'host' ? 'loading' : 'unavailable', writable: false,
  mode: persistence}`; and `enqueue` is a no-op —
  `if (this.persistence === 'memory' || this.disposed) return Promise.resolve()`
  (`settings-scope.ts:165-166`). So **a remote browser never fires a settings RPC**: the
  Permission row hides itself, Models/Plugins cards see `writable: false`, and
  locale/theme still mutate local runtime state but silently drop the `host.set` write
  (lost on reload). The `settings/openSettingsDocument` action is not even registered.

  A phone client therefore cannot edit these settings unless it either holds a
  loopback/trusted connection or the host is changed to allow it. The Android client
  takes that route: `ui/SettingsContent.kt` renders the host-owned rows as read-only
  values or notes and keeps only the preferences the app itself owns (theme, busy-Enter,
  new-session default preset, archived unarchive), plus its own Connection/Session facts
  from `ui/settings/SettingsLocalFacts.kt`.

---

## 4. Deliverables

### 4.1 What a deliverable is, and how it is produced

There are **two distinct classes** on one surface, and a port must not conflate them:

1. **Produced files** (implicit, client-derived). The client watches the durable event
   stream for **successful first-party mutation tool calls** and derives the changed paths.
   Nothing is declared by the model. Rules (`turn-deliverables.ts:50-102`):
   - `write` — string `content` + non-blank `file_path`
   - `edit` — `file_path` + non-empty `old_string` + string `new_string` +
     `old_string !== new_string` (+ optional boolean `replace_all`)
   - `str_replace_editor` — `create` (path + string `file_text`);
     `str_replace` (path + non-empty `old_str` + `new_str` undefined-or-string; a pure
     deletion **does** count); `insert` (path + integer `insert_line >= 0` + string `new_str`)
   Failed results, reads, unknown tools and malformed JSON contribute nothing. Only the
   opening `turn/start`…closing events are considered, and paths are exact-string deduped in
   first-seen order.
2. **Presented / delivered files** (explicit). Produced by the **`present` tool**
   (`packages/fs/tool-present/src/index.ts`), wire name exactly `present`:
   - parameters: `{ files: Array<{ path: string (required), description?: string }> }`,
     `additionalProperties: false`
   - output: `{ turn: integer, files: Array<{path, description?}> }`, rendered to the model
     as text lines `Presented <path>`
   - `maxFiles` config default **8**; rejects non-regular files, missing files, empty paths,
     no open turn, no workspace.
   - on a non-error `tools/result` it appends the durable session event
     **`deliverables/presented`** with payload `{ turn, callId, files }`
     (`index.ts:99-107`; `types.ts:12-16`).
   - **Availability**: `@deepseek-ai/dsh-tool-present` is **not** in the base bundle; it is
     mounted per agent preset as a cordis row `present` in **`standard`**
     (`presets/standard/agent.cordis.yml:261-262`), `ptc` (`:282-283`) and `cordis`
     (`:272-273`); **`minimal` does not include it**. This deployment's default preset is
     `standard` and the web patch does not disable it, so **`present` is on by default
     here**. A port must tolerate sessions/presets where it is absent.

### 4.2 How it renders in the transcript

The slot is **`conversation.chat.turnTail`** (`kind: chain`, scope session; owner
`TurnTailOwnerProps`; `ui-chat/src/client/contract/slots.ts:211`), rendered by
`renderSlotChain('conversation.chat.turnTail', owner)` at the tail of the turn
(`TurnTailNodeView.tsx:26`). `ui-deliverables` registers `{ select: selectDeliverables,
locale: 'deliverables' }` (`client/index.ts:48-60`). `selectDeliverables` claims the turn
**only when produced + presented > 0** (`Deliverables.tsx:34-38`), so turns with nothing
delivered show no row at all.

Structure (`Deliverables.tsx:60-83`):

```
Deliverables (root, data-after-produced-files)
├─ ProducedFiles            (only if produced > 0)
└─ div.presented            (only if presented > 0)
   ├─ optional host-status line (hostError/unavailable)
   ├─ PresentedFileCard × N   (grid; key `${file.seq}:${file.index}`)
   └─ optional collapse toggle
```

- `ProducedFiles.tsx`: a single-line list of file chips. `SHOWN_LIMIT = 6` chips; CSS
  container bands progressively hide chips (6→5→4→3→2→1, at 687/583/479/375/271px) and
  the counter shows the remainder
  (`'+ {count} files'`, singular `'+ 1 file'`). Section label `produced.label` =
  `'Files changed'`. Each chip is a button with `title={path}`, `aria-label` =
  `'Open {name}'` (`produced.open`), basename text and a link icon
  (`ProducedFiles.tsx:32-46`).
- Presented cards: collapsed to `COLLAPSED_PRESENTED_COUNT = 4`; when more, a toggle shows
  `presented.all` = `'All {count} files'` / `presented.collapse` = `'Collapse'` with aria
  `'Show all {count} delivered files'` / `'Collapse delivered files'`
  (`Deliverables.tsx:17,77-83`). Card status line = the description (trailing parenthesised
  suffix stripped) else the upper-cased extension else `'File'`.
- Geometry: 20px gap from the prose to the first file section, 16px between the produced
  and delivered sections (`Deliverables.module.css:2-5`).

### 4.3 The `present` tool card (`PresentRow`)

Registered as the keyed `tool.call.toolview` entry `'present'` (`client/index.ts:61-63`).
Root `div[data-tool="present"][data-state=…]`. States → title/subtitle
(`PresentRow.tsx:30`; locale en): `running` → `'Present files'` + `'Delivering'`;
`ok` → `'Delivered'`; `error` → `'Delivery failed'`; `stopped`
(`error.code === 'interrupted'`) → `'Interrupted'`. The collapsed subtitle appends the
comma-joined argument paths. Settled details expand to a `<pre>` of the result text or
`{name}: {code}`. `row.inspect` = `'Inspect call'` renders only when the host supplies an
inspect callback (the Chat→Trajectory jump, §2.6). Summary text 12px; output pre 12px,
padded 12px, radius 8px.

### 4.4 How an item opens

- **Primary click** (whole-card overlay `.cardPreview`, an always-visible split `Open`
  button whose **visible label** is `presented.action` = `'Open'` (the
  `presented.previewButton` = `'Open {name} in sidebar'` string is only its aria-label), or
  the chevron's first item) →
  `openFile(path)` → `ctx.sidebarRight.openResource(fileAddressFor(sessionId, cwd, path))`
  (`ui-chat/src/client/apply.ts:133-139`) →
  `dsh-resource://file/session/<id>/<path>` → the right panel's **`text` preview tab**
  (§1.4). No RPC.
- **Inline-code mentions** in assistant Markdown also resolve to the same action: the
  `chatFileMentions` service (`ui-deliverables/src/client/index.ts:66-77`) matches an exact
  produced path, or a basename only when it is unique; ambiguous/unknown tokens stay inert.
  Its label is `presented.previewButton` = `'Open {name} in sidebar'`.
- **Secondary menu** (chevron; disabled while opening/revealing or when the host desktop is
  null/unavailable): `presented.defaultApp` = `'Open in default app'`; and the reveal item,
  whose label depends on the Host file manager — `presented.finder` = `'Show in Finder'`,
  `presented.explorer` = `'Show in File Explorer'`, `presented.directory` =
  `'Open containing folder'`. Menu aria `presented.more` = `'More file actions for {name}'`.
- **Wire**: plain fetch, **not** RPC — `POST /api/present.open?sessionId=<id>&seq=<n>&index=<i>`
  (add `&action=reveal` for reveal). `GET /api/present.host` returns
  `{ name, available, fileManager: 'finder'|'explorer'|'directory'|null }`
  (`cache-control: no-store`) and is read lazily per turn. Host handler returns 400 bad
  action/coords, 409 `Host desktop unavailable.`, 404 not found,
  422 `Presented file has no verified Host path.`, 500 otherwise, 204 on success.
- **Failure copy**: `presented.error` = `'Could not open. Click to retry.'`,
  `presented.nativeUnavailable` = `'This file has no available Host path. Preview it in the
  sidebar.'`, `presented.hostError` = `'Could not read the Host desktop information'` +
  `'Retry'`, `presented.unavailable` = `'This Host has no desktop available to open files
  or folders'`, `presented.revealError` = `'Could not show in file manager. Try again.'`,
  plus `presented.opening/opened/revealing/revealed` and directory variants.

### 4.5 Touch / desktop notes

- **Hover-gated, no touch fallback**: `.file:hover .secondaryText{display:none}` +
  `.file:hover .previewHint{display:inline}` — the "Preview in sidebar" hint is hover-only
  (`Deliverables.module.css:21-22`). It is cosmetic: the always-visible `Open` button does
  the same thing.
- **Touch accommodation already present**: `@media (pointer: coarse)` gives the card's
  open/chevron a 44px minimum and the card 44px high (`:35`).
- **Desktop-only by nature**: default-app open and reveal act on the **serving host's**
  desktop, not the phone. On a headless/containerised host the menu is disabled and the
  whole turn shows `presented.unavailable`. The app drops them and keeps only the preview
  action (`ui/components/DeliverableRow.kt:67-70`).
- **Absent**: no deliverables panel/tab (grep `sidebar.right|sidebarRight` in
  `ui-deliverables/src` → nothing); no per-file delete/share/rename; no download route
  (`/api/present.download` is not registered). The turn action footer itself is
  hover-revealed for non-latest turns (`data-actions-reveal="hover"`,
  `TurnTailNodeView.tsx:41`) — a touch concern independent of deliverables.

### 4.6 EN locale (namespace `deliverables`, `client/locales.ts:48-86`)

```
presented.nativeUnavailable  This file has no available Host path. Preview it in the sidebar.
presented.revealError        Could not show in file manager. Try again.
presented.directoryError     Could not open containing folder. Try again.
presented.directoryOpening   Opening containing folder…
presented.directoryOpened    Requested opening containing folder
presented.revealed           Requested display in file manager
presented.revealing          Showing in file manager…
presented.unavailable        This Host has no desktop available to open files or folders
presented.retry              Retry
presented.hostError          Could not read the Host desktop information
presented.directory          Open containing folder
presented.explorer           Show in File Explorer
presented.finder             Show in Finder
presented.defaultApp         Open in default app
presented.more               More file actions for {name}
presented.action             Open
presented.preview            Preview in sidebar
presented.previewButton      Open {name} in sidebar
presented.previewCard        Preview {name} in sidebar
presented.all                All {count} files
presented.expandAria         Show all {count} delivered files
presented.collapse           Collapse
presented.collapseAria       Collapse delivered files
presented.opening            Opening…
presented.opened             Opened in default app
presented.error              Could not open. Click to retry.
presented.file               File
row.title                    Present files
row.running                  Delivering
row.ok                       Delivered
row.error                    Delivery failed
row.stopped                  Interrupted
row.inspect                  Inspect call
produced.label               Files changed
produced.moreOne             + 1 file
produced.more                + {count} files
produced.open                Open {name}
```

---

## 5. Quick map for the Compose port

The client has since built the Files pane (transcript-derived, with a numbered text
preview), the Trajectory ledger with a read-only overview, the turn-tail deliverables
row, and a read-only Settings sheet; `files-and-deliverables.md` §5–§6 records those
call sites. The rows below remain the web-side assessment.

| item | worth porting to a phone | notes |
|---|---|---|
| Files tree | **Yes** (high) | 2 RPCs, simple tree, real value on a phone |
| Text/document preview | **Yes** (high) | route `file:` addresses to one Compose viewer; paging + reload |
| Trajectory view | **Partial** (medium) | easiest faithful port = the **ledger table only**; timeline + details inspector are a lot of work |
| Terminal | **Defer / partial** | xterm → needs a WebView or a native VT; no extra keys means it is barely usable on touch |
| dockkit multi-pane / floating / drag-split | **No** | two-pane split and drag-float do not translate; a single tab strip suffices |
| Settings modal | **Partial** | General/Models/Archived are useful; Models is host-loopback-only |
| Deliverables row + present card | **Yes** | keep the turn-tail row and "Preview in sidebar" |
| "Open in default app" / "Show in Finder" | **No** | host-desktop concepts |
| Timeline overview + range zoom/pan | **No** (optional) | rich but a lot of surface for little phone value |
