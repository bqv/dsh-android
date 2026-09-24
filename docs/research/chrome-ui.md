# DSH web client — chrome structure, interaction model, copy, and wire calls

Companion to `docs/research/design-system.md` (colors / type / raw CSS) and `docs/research/protocol.md`
(transport / envelopes). This report deliberately does **not** repeat the palette or geometry tables. It
documents **component structure, behaviour, exact English copy, and the RPC each interaction fires**, so a
native Android Jetpack Compose client can reproduce the surrounding chrome faithfully.

Source checkout (read only): `/home/user/bin/deepseek-harness`, client packages under `packages/client/`.
All paths below are relative to `packages/client/` unless stated otherwise.

## 0. How copy is authored (read this first)

Every client package registers a locale namespace with two dictionaries in `src/client/locales.ts`
(sometimes `locale.ts`): `zh` is the key-set source of truth and `en` is key-identical. Components call
`t('key')`. All copy quoted below is the **`en`** value; the key is given where useful.

Shared keys live in the locale package `locale/src/locales/{en,zh}.ts` — e.g. `number.thousand` = `'{value}K'`,
`number.million` = `'{value}M'`. The conversation namespace is `conversation` and owns many of the composer
strings (`ui-conversation/src/client/locales.ts`).

Two structural facts that drive everything:

* The whole chat column is assembled from **slots** (`ctx.slots.register/inject`) declared in
  `ui-conversation/src/client/apply.ts` and typed in `ui-conversation/src/client/contract/slots.ts`.
  A native port must implement the slot *seats* and their contributors as equivalent Compose composables.
* Views and header actions are contributed by independent plugins (`ui-chat`, `ui-trajectory`,
  `ui-jobs`, `ui-schedule`, `ui-agent-preset`, `ui-subagent`, `ui-sidebar-right`, `ui-sidebar-terminal`,
  `ui-open-in-app`, …). Several render nothing unless the host serves the capability.

---

## 1. Conversation header

### 1.1 Where it lives

* Component: `ConversationSessionHeader` in `ui-conversation/src/client/skeleton/ConversationSession.tsx`.
* Registered as slot `conversation.session.header` (single, session scope) in
  `ui-conversation/src/client/apply.ts:300-307`. It is rendered by
  `ui-conversation/src/client/skeleton/ConversationMainPanel.tsx:133`
  (`renderSlot('conversation.session.header', {})`) above the transcript content.
* Root element: `<header className={css.header}>` (`ConversationRoot.module.css`): `min-height: 76px`,
  `padding: 10px 28px 0 20px`, `border-bottom: 0.5px solid var(--dsw-alias-border-l3)`.
* **Hidden for a blank session**: when `session.blank && conversationPhase(session, conversation) === 'blank'`
  the header renders with `.headerHidden { display: none }` and `aria-hidden` (it stays mounted so the
  layout does not jump). The whole body is inside `{!hideChrome && (…)}`.

The header has two rows inside `{!hideChrome}`:

1. `.titleRow` — crumbs + `.headerActions` on the left (`.titleCluster`), `.headerUtilities`, then
   `.headerCorner` (`data-conversation-header-corner`).
2. `.tabs` — the view tab strip, **rendered only when more than one view is registered**
   (`{tabs.length > 1 && (…)}`). With only the Chat view composed there is no tab strip at all.

### 1.2 The crumb / breadcrumb chain

Built by `deriveAncestry(list, id)` (`ConversationSession.tsx`):

* Starting at the current `sessionId`, it walks **up through subagent parents only**:
  it unshifts `{ id, displayTitle, subagent: summary.origin === 'subagent' }` and continues to
  `summary.parentId` **while `summary.origin === 'subagent'`**, stopping at the first non-subagent
  (top-level) session. So the chain is `top-level session` → … → `current subagent`, root first.
* Duplicate ids are guarded by a `seen` set.

Render (`<nav className={css.crumbs} aria-label={t('session.hierarchy')}>`, English
`'Session hierarchy'`):

* Each segment is `<span className={css.crumbSeg}>`; a `/` separator (`.crumbSep`) is rendered before every
  segment after the first. The literal separator text is `/`.
* Each segment itself is:
  `<button type="button" className={clsx(css.crumb, summary.subagent && css.crumbSubagent, last && css.crumbCurrent)} disabled={last} onClick={() => open(summary.id)}>{summary.displayTitle}</button>`
  * `open(id)` is injected as `workspaceNavigation.openSession(id)` (`apply.ts:307-310`) — clicking a crumb
    navigates to that session.
  * The **last** (current) crumb is `disabled` and styled `.crumbCurrent` (medium weight); it is not
    clickable.
  * Subagent crumbs get `.crumbSubagent` (12px) instead of 14px.
  * CSS: `.crumb` max-width 220px, ellipsis, r12, hover `--dsw-alias-interactive-bg-hover`; `.crumbSep`
    uses `--dsw-alias-label-caption`.
* If `ancestry.length === 0`, the header renders `<span className={css.crumbCurrent}>{sessionId}</span>`
  (raw id).
* **Lineage slot**: each segment may render `conversation.session.header.lineage`:
  * subagent segment → `renderSlot('…lineage', lineageOwner, { fallback: title })` (the subagent lineage
    replaces/falls back to the plain title);
  * last non-subagent segment → plain `title` **plus** `renderSlot('…lineage', lineageOwner, { fallback: null })`;
  * `lineageOwner = { lineageSessionId, displayTitle, …(last ? {} : { openTitle: () => open(id) }) }`.
  The default contributor is `SubagentHeaderLineage` (`ui-subagent/src/client/SubagentHeaderLineage.tsx`):
  a chevron + a subagent count and a portaled tree of child subagents. Copy (namespace `subagent`):
  `'count.total.one'` = `'{count} subagent'`, `'count.total.other'` = `'{count} subagents'`,
  `'count.running.one'`/`'.other'` = `'{count} subagent(s), running'`, `'tree.aria'` = `'Subagent sessions'`,
  `'switcher.aria'` = `'Switch subagent: {title}'`, `'branch.expand'` = `'Expand subagents under {label}'`,
  `'branch.collapse'` = `'Collapse subagents under {label}'`, `'loading.label'` = `'Loading subagents…'`,
  `'retry'` = `'Retry'`, `'mode.oneShot'` = `'One-shot'`, `'mode.continuable'` = `'Continuable'`.

### 1.3 The VIEW TABS strip

* Source: slot list `conversation.view`. The shell projects each registered entry to
  `ViewTab { id, label }` (`ui-conversation/src/client/contract/views.ts`).
* Exactly **two** views are registered in this checkout:

  | order | id | label key | English label | package |
  |---|---|---|---|---|
  | `0` | `chat` | `view.chat` | **Chat** | `ui-chat/src/client/apply.ts:99-101` |
  | `10` | `trajectory` | `view.trajectory` | **Trajectory** | `ui-trajectory/src/client/index.ts:80-84` |

* Rendering (`ConversationSession.tsx`):
  ```
  <div className={css.tabs} role="tablist">
    {tabs.map(viewTab => (
      <button role="tab" aria-selected={viewTab.id === active?.id}
              className={clsx(css.tab, active && css.tabActive)}
              onClick={() => selectView(viewTab.id)}>{viewTab.label}</button>
    ))}
  </div>
  ```
* **Default / active resolution**: `resolveActiveView` (`ui-conversation/src/client/view-selection.ts`)
  returns the stored preference if it is still registered, otherwise `tabs.find(v => v.id === 'chat')` —
  **Chat is the hard-coded default** (`DEFAULT_VIEW_ID = 'chat'`). `selectView` sets the per-session
  store value and calls `activateView(sessionId, view)`.
* **Active state = 2px underline + blue text**, not a pill: `.tabActive { color:
  var(--dsw-alias-state-business-primary) }` and `.tabActive::after { height:2px; border-radius:2px;
  background: var(--dsw-alias-state-business-primary) }`. Inactive tabs are `--dsw-alias-label-tertiary`,
  13px/16px weight 500. The strip: `gap:36px; margin-top:10px; padding-left:8px`.
* What each tab shows: **Chat** = the message transcript (`ChatView`); **Trajectory** = the request/response
  inspection table (`TrajectoryView`). `DefaultConversationViews` renders only the active view inside
  `.viewArea`, passing a `viewRequest`/`openView`/`completeViewRequest` focus protocol.

### 1.4 Header actions, utilities, corner

Three separate slots, rendered left→right in `.titleRow`:

* `conversation.session.header.actions` (list) inside `.headerActions` (gap 8). Contributors, ordered by
  `order`, then registration:
  1. **agent preset label** — id `agent-preset`, `order: -10` (`ui-agent-preset/src/client/index.ts:164-167`,
     `AgentPresetLabel.tsx`). Read-only: `IconAgentPresetOutline16 size={14}` + the preset name; `title` =
     the preset description, else `headerHint` =
     `'The agent preset this session runs, fixed when it started'`. Renders `null` when the session records
     no preset. It is **not** a control (the comment says the composition is fixed once the conversation
     starts; the choice lives on the new-session screen).
  2. **terminal recovery** — id = the terminal id, no `order` (0)
     (`ui-sidebar-terminal/src/client/index.ts:77-90`, `TerminalRecovery.tsx`). Normally renders `null`;
     on a failed restore it renders a plain `<button>` with text `retryRecovery` =
     `'Retry terminal recovery'` and `title={t('recoveryFailed', {message})}` =
     `'Terminal recovery failed: {message}'`. Side-effect only: it re-opens retained terminals as right-panel
     tabs once per page.
  3. **schedule catalog** — id `schedule-catalog`, `order: 10`
     (`ui-schedule/src/client/index.ts:26-31`, `ScheduleCatalogAction.tsx`). `IconAlarmClockOutline16` +
     `IconChevronDownOutline14`; count `t('trigger.one'|'trigger.other')` =
     `'{count} reminder'` / `'{count} reminders'`; popover `list.aria` = `'Active reminders'`, rows carry
     status `'Scheduled'` / `'Overdue'`, frequency `'Once'` / `'Every {value} {unit}'`, and relative time
     `'Due now'` / `'in {value} {unit}'` / `'{value} {unit} overdue'`.
  4. **background jobs** — id `job-list`, `order: 20` (`ui-jobs/src/client/index.ts:33-38`,
     `JobListAction.tsx`). Chevron + `StateDot` per job; counts
     `'{count} background job running'` / `'{count} background jobs running'` (live) and
     `'{count} background job'` / `'{count} background jobs'` (idle); list `aria-label`
     `'Background jobs'`; status words `'running'`, `'stopping'`, `'completed'`, `'cancelled'`, `'failed'`;
     durations `'{seconds}s'`, `'{minutes}m {seconds}s'`, `'{hours}h {minutes}m'` with titles
     `'Running for {duration}'` / `'Took {duration}'`. A `<StateDot>` maps status→state
     (`running`→`ongoing`, `stopping`/`killed`→`warning`, `completed`→`done`, `failed`→`error`).

* `conversation.session.header.utilities` (list) inside `.headerUtilities` (gap 8, `margin-left:20px`;
  `:empty { display:none }`). Sole contributor: **Open-in-app split button**
  (`ui-open-in-app/src/client/index.ts:38-40`, `OpenInAppAction.tsx`). It renders `null` unless the host
  probed ≥1 nameable application **and** the session has a known non-empty `cwd`. Structure:
  * main button: `AppIcon` of the remembered app (host-served PNG, generic rounded-rect fallback),
    `aria-label` = `t('open.title', {app})` = `'Open workspace in {app}'`, tooltip `t('open.tooltip')` =
    `'Open locally'`; error state shows `'Failed to open'` for 2s and the busy dress only appears after
    `BUSY_DRESS_DELAY_MS = 250`.
  * chevron button: `aria-haspopup="menu"`, `aria-label`/`title` = `t('menu.toggle')` =
    `'Choose an app to open in'`.
  * menu: one row per probed app, `dense`, `selection:"fill"`, `align:"end"`, current app checked. Product
    names include `'Finder'`, `'File Explorer'`, `'Files'`, `'Terminal'`, plus IDE names from
    `PRODUCT_NAMES` (VS Code, Cursor, Windsurf, Zed, Xcode, Android Studio, IntelliJ, …). The full id→name
    map is `APP_LABEL_KEY` in `OpenInAppAction.tsx` (`app.finder`, `app.explorer`, `app.filemanager`,
    `app.cursor`, `app.vscode`, … `app.terminal`). Launches via the injected `launch(appId, cwd)`.
    This control is meaningless on a phone (no local desktop apps) and should be omitted.

* `conversation.session.header.corner` (single) inside `.headerCorner`
  (`margin-left:8px; margin-right:-16px`). Sole contributor: **ExpandButton**
  (`ui-sidebar-right/src/client/index.ts:178-181`, `shell/ExpandButton.tsx`). Renders only while the
  right panel is collapsed for this session (`state.bySession[sessionId]?.layout.expanded ?? false`):
  `IconPanelLeftOutline16` (the left sidebar collapse glyph mirrored), tooltip `chrome.expand` =
  `'Open sidebar'`, `aria-label` `chrome.expandAria` = `'Open right sidebar'`,
  `data-sidebar-right-expand`, click → `actions.setExpanded(sessionId, true)`.

### 1.5 Per-session overflow menu

**There is no per-session overflow (⋮) menu in the conversation header in this version.** The header
contributes exactly the seats above; none of them is rename/fork/archive/export/delete.

Those verbs live elsewhere:

* **Rename / Fork / Archive** — the sidebar session row's own trailing ⋮ menu (see §2).
* **Export** — a slash command, not header chrome: `/export`, English label `'Export'`, description
  `'Download this Session log as a ZIP archive'` (`ui-commands/src/client/locales.ts`).
* **Delete** — there is no session-delete verb in the header or the row menu. Workspaces have
  `workspace/delete` (`'Delete workspace'`), sessions only have archive (`workspace/archiveSession`)
  and restore (`workspace/unarchiveSession`). There is no session duplicate.

---

## 2. Session row actions

### 2.1 Where the rows live

* `ui-workspace/src/client/rows/Rows.tsx` — presentational rows. Exports `ProjectRowItem` (workspace group
  header), `SessionNodeItem` (one top-level session row) and `SearchResultItem` (flat search result, no
  actions). `Rows.module.css` holds all row/action CSS.
* `ui-workspace/src/client/rows/WorkspaceBrowser.tsx` — the tree (`SessionTree`, `FlatList`), the dialogs,
  and the wiring of injected callbacks to rows.
* `ui-workspace/src/client/tree.ts` — `deriveGroups`, `SessionNode`, `sessionVisible()`.
* `ui-sidebar/src/client/SidebarRoot.tsx` — sidebar shell only (logo, New Session, panels, the
  `sidebar.workspaces` hole, footer). **It owns no row actions.** Keys: `session.new` = `'New Session'`,
  `session.new.label` = `'New session'`, `toggle.open` = `'Open sidebar'`,
  `toggle.collapse` = `'Collapse sidebar'`, `panels.label` = `'Global panels'`.
* `ui-session` is only the Session Controller React adapter (hooks, pending-interaction publisher,
  slot-scope adapter) — no rows, no row actions.

### 2.2 Trailing actions per row

**Session row** (`SessionNodeItem`, `Rows.tsx:417-501`): a single trailing `IconEllipsisOutline16` button
opening a `Menu` with exactly three items (`sessionMenuItems`, `Rows.tsx:417-422`):

| menu id | icon | label key | English |
|---|---|---|---|
| `rename` | `IconEditOutline16` | `rename` | **Rename** |
| `fork` | `IconBranchOutline16` | `menu.fork` | **Fork session** |
| `archive` | `IconArchiveOutline20 size={16}` | `menu.archiveSession` | **Archive session** |

The ellipsis button has no Tooltip; its accessible name is
`aria-label={t('actions.session.aria', {name: title})}` = **`Session actions for {name}`**.
Blank/provisional New Session rows render **no** time and **no** actions (the verbs "would all act on
content that does not exist").

**Workspace / group header row** (`ProjectRowItem`, `Rows.tsx:129-195`) has two trailing controls:
* `IconEllipsisOutline16` menu (only for real workspaces, absent on the "Ungrouped" bucket),
  aria-label `actions.workspace.aria` = **`Workspace actions for {name}`**; items `rename` →
  **Rename** (`IconEditOutline16`) and `delete` → **Delete workspace** (`IconTrashOutline16`, `danger:true`).
* `IconPlusOutline16`, aria-label `actions.newSession.aria` = **`New session in {name}`**, starts a New
  Session in that workspace.

Leading glyphs: `IconFolderOpen16`/`IconFolderClose16` (swapped for `IconTriangleRightFill14` on hover), plus
`IconAlarmClockOutline16` when `schedule.active` = `'Has active scheduled task'`.

**Not present in this version:** no session delete, duplicate, or pin — no `session/delete`,
`session/duplicate`, `session/pin` RPC exists (grep returns nothing). Sessions can only be renamed, forked,
archived; workspaces can be renamed/deleted/reordered.

### 2.3 Hover-only visibility (critical for touch)

`Rows.module.css:233-250` (verbatim):
```css
.rowActions { flex: none; display: none; align-items: center; gap: 12px; }
.projectRow:hover .rowActions,
.sessionRow:hover .rowActions,
.projectRow.menuOpen .rowActions,
.sessionRow.menuOpen .rowActions { display: inline-flex; }
.sessionRow:hover .time,
.sessionRow.menuOpen .time { display: none; }
```
Plus the folder→chevron swap (`Rows.module.css:148-151`):
```css
.projectRow .chevron { display: none; }
.projectRow:hover .chevron { display: inline-flex; }
.projectRow:hover .folder { display: none; }
```
And an open menu pins the hover fill: `.sessionRow.menuOpen { background: var(--dsw-alias-interactive-bg-hover) }`.
There is **no `:focus`/`:focus-within` rule**, and because the buttons are `display:none` they are out of the
tab order — this UI is **pointer-hover-only and keyboard-inaccessible**. On hover the relative-time cell is
replaced by the actions cell. A phone port must surface the ⋮ action always (or via long-press).

There is also a **`HoverCard`** wrapper around each non-blank session row: `disabled={menuOpen || drag?.active}`,
`content={<SessionHoverContent …>}`, `copyText={row.title}`, `copyLabel={t('copy')}`,
`copiedLabel={t('hover.copied')}` = `'Copied'` — i.e. a hover preview plus one-click title copy, with no
touch equivalent.

### 2.4 RPC per action

| action | client callback (file) | final RPC |
|---|---|---|
| Session **Rename** | `renameSession` `ui-workspace/src/client/index.ts:109-116` → `sessions.binding(id)?.session.rename(title)` | **`session/rename`** |
| Session **Fork** | `forkSession` `index.ts:117-122` → `navigation.ts:153-157` (`sessions.fork({sessionId, increaseTitle:true})`) | **`session/fork`** (then optionally `session/rename` for the child) |
| Session **Archive** | `archiveSession` `index.ts:128` → `navigation.ts:180-182` | **`workspace/archiveSession`** |
| Session **Unarchive** | `uiWorkspace.unarchiveSession` `navigation.ts:184-186` (Settings page, §6) | **`workspace/unarchiveSession`** |
| Workspace **Rename** | `renameWorkspace` `index.ts:123` | **`workspace/rename`** |
| Workspace **Delete** | `deleteWorkspace` `index.ts:124` | **`workspace/delete`** |
| Workspace **drag-reorder** | `insertWorkspaceBefore` `index.ts:125-127` | **`workspace/insertBefore`** |
| Session **drag-reorder** | client-side `setSessionOrder` (persisted key `dsh.workspace.view.v5`); `insertSessionBefore` exists but has **no production caller** | **`workspace/insertSessionBefore`** (defined, not driven) |
| Row "+" New Session | `startSession` `index.ts:105` → `navigation.ts:159-178` | **`session/create`** |

Method strings are declared in the generated maps
`packages/api/workspace-controller/lib/typert.remote-client.d.ts:26-36` and
`packages/api/session-controller/lib/typert.remote-client.d.ts:41-49`; HTTP form is `POST /api/<method>`.

Dialogs (`WorkspaceBrowser.tsx`):
* workspace rename modal — title `rename.workspace.title` = `'Rename workspace'`, field aria
  `field.workspaceName` = `'Workspace name'`, buttons `cancel` / `rename`; client-side duplicate check shows
  `conflict.named` = `'A workspace named “{name}” already exists.'`
* session rename modal — title `rename.session.title` = `'Rename session'`, field aria
  `field.sessionName` = `'Session name'`; no duplicate rule (an unchanged title is allowed).
* workspace delete modal — title `delete.workspace` = `'Delete workspace'`, description `delete.desc` =
  `'This removes “{name}” from the workspace list. The folder and session logs will be kept. Its sessions will
  appear under Ungrouped.'`, pending `delete.pending` = `'Deleting workspace…'`, danger button.
* archive is **dialog-free**.

### 2.5 Expiry / undo — does not exist

There is **no Undo, no toast, no countdown, and no optimistic removal** for session archive:
* `ui-workspace/src/client/locales.ts` has no `undo` key and no toast copy; `ui-settings-unarchive-sessions`
  likewise.
* `Toast` (ui-primitives, `HOLD_MS = 3000`, `FADE_MS = 1000`) is used by `ui-agent-preset`,
  `InputBar` and `ui-message-feedback` only — never by archiving. The only `undo` implementations are the
  right-panel tab close `_undo()` and editor/dock history; there is no "Undo" UI label anywhere.
* Archive is not optimistic: the row disappears only when the archive-set echo arrives (RPC reply or
  `workspace/follow`), and failures are a `console.warn('session archive rejected:', reason)` — non-fatal.
* Restore is manual: the Settings "Archived sessions" page (§6). **No time-limited undo window.**

### 2.6 Tree structure

The sidebar session list is **flat, not a lineage tree**: `sessionVisible()` requires
`session.origin !== 'subagent'`, so subagent children never appear as rows. Descendants are only aggregated
into a caret-less count on the parent row (`subagent-lineage.ts` → `SessionNode.runningSubagentCount`) using
`status.subagentsRunning.one`/`.other` = `'1 subagent running'` / `'{n} subagents running'`.

The real parent/child tree with collapse carets and indentation is the **subagent lineage dropdown in the
session header** (`ui-subagent/src/client/SubagentHeaderLineage.tsx`, `role="tree"`/`role="treeitem"`,
`aria-level`, `aria-expanded`), opened on hover (150 ms open / 120 ms close) — see §1.2.

---

## 3. Add-workspace flow

### 3.1 Components and entry points

* `ui-workspace/src/client/WorkspacePicker.tsx` — `WorkspacePickFlow` (reusable core: `Menu` + adoption-error
  `Modal`) and `WorkspacePicker` (conversation-hero wrapper filling `conversation.hero.workspace` with
  `renderDirectoryFlow` = `conversation.hero.workspace.directoryFlow`).
* `ui-workspace/src/client/rows/WorkspaceBrowser.tsx` — composes `WorkspacePickFlow` directly with
  `addOnly` for the sidebar; `renderDirectoryFlow` = `sidebar.workspaces.directoryFlow`.
* `ui-directory-picker-browse` — the in-app "Select Workspace Directory" dialog occupant
  (`DirectoryBrowser.tsx`, `flow.ts`).
* `ui-directory-picker-native` — renderless occupant driving the OS chooser via `directoryPicker/pick`.

Three entry points:
1. **Sidebar header "+"** (`WorkspaceBrowser.tsx:1120-1134`): `IconProjectAddOutline16`, Tooltip + aria-label
   `workspace.add` = **`Add workspace`**; rendered only while the directory-flow hole is occupied.
2. **Collapsed rail "+"** — same button at 36×36.
3. **Conversation hero `WorkspaceChip`** (`ConversationContent.tsx:187-211`): aria-label
   `hero.chooseWorkspace` = **`Choose workspace`**; opens `WorkspacePicker`, whose menu lists existing
   workspaces plus a footer entry `menu.addWorkspace` = **`Add workspace…`**.
   With no workspace picked, the composer is inert with placeholder `placeholder.workspace` =
   `'Choose a workspace to start'`.

Behaviour detail: with no workspaces listed and add available, opening the anchor **immediately** raises the
directory flow (`addIsTheOnlyEntry`) instead of showing a one-row menu; while the list is still `pending`
the menu stays up with `picker.loading` = `'Loading workspaces…'`. The sidebar instance is `addOnly` and never
lists existing workspaces; only the hero instance does.

### 3.2 Directory-picker RPCs

Namespace `directoryPicker` (`packages/api/workspace-controller/src/directory-picker.ts:46`):

| method | client call | final RPC |
|---|---|---|
| `pick` | `ctx.uiWorkspace.pickDirectory()` (`ui-directory-picker-native/src/client/index.ts:29` → `navigation.ts:188-192`) | **`directoryPicker/pick`** |
| `list` | `ctx.uiWorkspace.listDirectory(path, signal)` (`ui-directory-picker-browse/src/client/index.ts:79` → `navigation.ts:194-198`) | **`directoryPicker/list`** |
| `createDirectory` | `ctx.uiWorkspace.createDirectory(path, name)` (`:80` → `navigation.ts:200-204`) | **`directoryPicker/createDirectory`** |

Wire names: `packages/api/workspace-controller/lib/typert.remote-client.d.ts:26-28`. Both occupants fill the
two flow holes (hero + sidebar).

### 3.3 Validation

Client side:
* Workspace **rename** duplicate is detected client-side (`renameDuplicate = workspaces.some(w => w.title ===
  renameTrimmed)`) and shown as `conflict.named`; blank/unchanged are blocked.
* New-folder name only rejects an all-whitespace draft (the host gets the original spelling). The path editor
  sends any typed text to `directoryPicker/list`; **no client-side absolute-path check**.
* Adding a workspace has exactly one route — pick an existing host directory. There is **no
  create-workspace-by-name** flow.

Host side (`packages/api/workspace-controller/src/commands.ts:40-59`, `packages/workspace/workspace/src/`):
* relative path → `TypeError: Workspace path is not fully qualified: '<path>'`
* nonexistent path → `fs.realpath` ENOENT
* non-directory → `cannot create a workspace at '<canonical>': path is not a directory`
* **duplicate detection is by canonical path**: `create` is idempotent and returns the existing workspace
  (`{workspace, created:false}`); different paths may share a display title.
* errors are wrapped as `RemoteError('workspace/invalid-path', 'cannot create a Workspace at "<path>": <message>')`;
  rename conflicts `workspace/name-conflict`; blank rename `gateway/bad-request`.
* `directoryPicker/createDirectory` validates with zod: non-blank, not `.`/`..`, no `/` or `\`.

### 3.4 The RPC that registers the workspace

**`workspace/create`** is the only registration path.
* Client: `createWorkspace: input => workspaces.create(input)` (`ui-workspace/src/client/index.ts:129`),
  invoked by `WorkspacePickFlow.adoptDirectory` (`WorkspacePicker.tsx:126-134`) as `createWorkspace({path})`.
* Controller: `packages/api/workspace-controller/src/client/service.ts:97-101` → `model.ts:88-92` (optimistic
  `upsert` on success).
* Host: `@Remote('create')` (`packages/api/workspace-controller/src/index.ts:58-61`) →
  `WorkspaceCommands.create` → `WorkspaceRegistry.resolveByPath/create`, persisted in the durable registry
  order (new workspaces prepend).
* No `workspaces/create`, `workspace/add`, or `workspace/open` method exists; `workspace/follow` is the state
  stream only.

### 3.5 Exact copy

| UI | key | English |
|---|---|---|
| Sidebar/rail add button | `workspace.add` | `Add workspace` |
| Picker menu add entry | `menu.addWorkspace` | `Add workspace…` |
| Picker loading | `picker.loading` | `Loading workspaces…` |
| Rename duplicate | `conflict.named` | `A workspace named “{name}” already exists.` |
| Adoption error title | `folderError.title` | `Couldn’t open folder` |
| Adoption error retry | `folderError.retry` | `Choose again` |
| Error dialog body | (raw host message, `role="alert"`) | — |
| Browser dialog title | `browser.title` | `Select Workspace Directory` |
| Home crumb | `browser.home` | `Home` |
| New folder button / input aria | `browser.newFolder` / `browser.folderName` | `New folder` / `Folder name` |
| Create-folder subtitle | `browser.createIn` | `New folder in "{name}"` |
| New-folder placeholder | `browser.untitledFolder` | `Untitled folder` |
| Buttons | `browser.create` / `browser.cancel` / `browser.open` | `Create` / `Cancel` / `Open` |
| Path editor aria/title | `browser.editPath` | `Edit path` |
| Loading pill | `browser.loading` | `Loading…` |
| Truncation notice | `browser.truncated` | `Too many folders to list; only the beginning is shown.` |
| Hidden-files toggle | `browser.showHidden` | `Show hidden files` |
| Hero chip aria/label | `hero.chooseWorkspace` | `Choose workspace` |
| Inert composer placeholder | `placeholder.workspace` | `Choose a workspace to start` |
| Sidebar empty / no-match | `empty.none` / `empty.noMatches` | `No sessions yet` / `No matches` |

Dialog structure: the browse dialog is a headless `Modal` with a footer bar (New folder / Show hidden files /
Cancel / Open) and a nested create `Modal`. The adoption-error dialog's `folderError.retry` is disabled when
no directory-flow occupant is present.

### 3.6 Host-side vs client-side

* Client-only: the trigger surfaces (sidebar "+", hero `WorkspaceChip`), the pick menu, the browse UI, and
  all of the copy above. No client persistence or path-existence check.
* Host-side: directory enumeration/chooser/creation (`directoryPicker/*`) and workspace
  registration/durability (`workspace/create` → `WorkspaceRegistry`). Workspace identity and the archive set
  persist host-side; the client projection is fed by unary replies plus the `workspace/follow` stream.

---

## 4. Composer stack

### 4.0 Assembly order (what is above and inside the card)

`ConversationContent.tsx` builds the composer column (inside the resident scrollport, `composerSeat`,
sticky to the bottom). Order, top→bottom:

```
composerStack
├─ HeroShell                          (hero variant only)
├─ heroWorkspaceRow                   (hero only: WorkspaceChip + conversation.hero.workspace + conversation.hero.agentPreset)
├─ renderSlot('conversation.input.dock', zone)   ← list: todo (order 0) → goal (order 10) → queue (order 20)
└─ renderSlot('conversation.composer.bar', …)    ← the composer card (InputBar)
     … which itself renders, after the card:
     └─ renderSlot('conversation.composer.dock', {})  ← list: stats pills, composer variant only
```

`conversation.composer` is itself a **chain** slot; `ui-subagent` contributes a `priority:-10` selector
that can replace the whole bar with a read-only explanation (`SubagentReadOnlyComposer.tsx`) for a one-shot
child or a child whose parent is offline. Copy (namespace `subagent`): title
`'One-shot subagent record'` / `'This subagent is read-only for now'`, body
`'One-shot tasks do not accept follow-ups; review the full execution record here.'` /
`'The parent session is offline; reopen it to continue sending messages.'` (`role="status"`).

Dock slot registration constants:

| dock entry | slot | id | order | file |
|---|---|---|---|---|
| To-dos | `conversation.input.dock` | `todo` | 0 | `ui-conversation/src/client/skeleton/TodoPanel.tsx:137-138` |
| Goal bar | `conversation.input.dock` | `goal` | 10 | `ui-goal/src/client/index.ts:83-84` |
| Queue | `conversation.input.dock` | `queue` | 20 | `ui-conversation/src/client/queue/QueueDock.tsx:375-395` |
| Stats pills | `conversation.composer.dock` | `stats` | 0 | `ui-chat/src/client/apply.ts:171-174` |

### 4.1 QUEUE DOCK

Component `QueueDock` (`ui-conversation/src/client/queue/QueueDock.tsx`).

* **When it appears**: it reads `useSession(s => s.queue)` filtered to `row.placement === 'queued'`, plus
  optimistic local `pendingSubmissions` that the host queue has not echoed yet. `if (rowCount === 0) return null`.
  One-shot subagents are not mutable (`queueMutable = subagent === null || mode === 'continuable'`).
* **What it lists**: one `<li>` per queued row — durable image thumbnails (`QueueThumb`, resolved through
  the session-scoped image URL) and file chips (`QueueFile`, `FileTypeIcon` + name + size), then the
  previewed text (`projectUserText(row.preview, [])`). Pending local rows render with
  `data-submission-echo` and a status `'Sending…'` plus disabled actions.
* **Collapsed vs expanded**: one row renders directly (with a leading `IconQueueOutline14`). Two or more
  render a header button (collapsed by default: `useState(true)`) with `IconQueueOutline14`, the count, and a
  chevron (`IconChevronUpOutline14` when collapsed, `IconChevronDownOutline14` when expanded). Header text
  `t('queue.count', {n})` = `'{n} queued messages'`. The list is `hidden` when collapsed unless a row is
  being edited or an action is busy (an interaction forces it open). When collapsed it shows a
  `role="status"` `'Sending…'` if pending submissions exist.
* **Per-row actions** (icon → aria/tooltip → RPC action):
  * Edit — `IconEditOutline16`, `t('queue.edit')` = `'Edit queued message'`; opens an inline `<input>`
    (`aria-label` `'Edit queued message'`, Enter saves, Escape cancels). Disabled when `row.text === null`,
    with the native `title` `t('queue.edit.unsupported')` =
    `'Contains non-text content; editing is not supported yet'`. Save = `IconCheckOutline16`
    (`'Save queued message'`), cancel = `IconCloseOutline16` (`'Cancel editing'`).
  * Remove — `IconTrashOutline16`, `t('queue.remove')` = `'Remove queued message'`.
  * Steer — `IconSendOutline14`, `t('queue.steer')` = `'Steer queued message'`; enabled only while
    `running`, otherwise disabled with `title` `t('queue.steer.unavailable')` =
    `'Steering is available only while the agent is running'`.
* **RPC**: `conversation.updateQueue(itemId, action)` → `ui-conversation/src/client/service.ts:494-502` →
  `SessionFace.updateQueue` → wire **`session/updateQueue`**
  (`packages/api/session-controller/lib/typert.remote-client.d.ts:44`). Actions:
  `{kind:'edit', content:[{type:'text', text}]}`, `{kind:'remove'}`, `{kind:'steer'}`.
  Failure toasts (via `notify('error', …)`): `'Edit failed: this message may have already started sending.'`,
  `'Removal failed: this message may have already started sending.'`, `'Steering failed. Try again.'`.
* **Bulk steer**: with the composer empty and the agent running, `Cmd/Ctrl+Enter` submits every queued row
  through the same `session.updateQueue(id, {kind:'steer'})`
  (`ui-conversation/src/client/input/hub.ts:223`). The composer placeholder then reads
  `'Cmd/Ctrl+Enter steers all queued messages'` (`placeholder.steerQueue`).

### 4.2 GOAL BAR

Component `GoalBar`/`GoalDock` (`ui-goal/src/client/GoalBar.tsx`, registered in `ui-goal/src/client/index.ts`).

* **When shown**: only while the host-projected `goal` is non-null and `goal.phase !== 'complete'`
  (and not the id the user just cleared). `undefined` = capability absent/loading → nothing. Creation is
  not here — it happens through the `/goal` slash command.
* **What it displays**: `data-goal-bar`, `IconGoalOutline16`, a phase label, the truncated `goal.objective`,
  and icon actions. Phase labels (namespace `goal`):
  * `'Ongoing Goal'` (`phase.active`), or `'Inactive Goal'` (`phase.active.disarmed`) when the
    process-local activation is `disarmed`;
  * `'Paused Goal'` (`phase.paused`);
  * `'Blocked Goal'` (`phase.blocked`) — and the strip's `title` becomes `goal.blockedReason?.message`.
* **Actions / RPC** (all mutate through the shared injected verbs in `ui-goal/src/client/index.ts:100-121`):
  * Pause — `IconPauseOutline16`, `'Pause goal'`; shown only while `phase === 'active' && activation === 'armed'`;
    `ctx.remote.goals.pause(sessionId, ref)`.
  * Resume — `IconPlayOutline16`, `'Resume goal'`; shown while paused, or active+disarmed;
    `ctx.remote.goals.resume(sessionId, ref)`.
  * Edit — `IconEditOutline16`, `'Edit goal'`; swaps the strip for an inline text input
    (`aria-label` `'Goal objective'`) with save `IconCheckOutline16` (`'Save goal'`) and cancel
    `IconCloseOutline16` (`'Cancel edit'`); Enter saves, Escape cancels; `ctx.remote.goals.edit(sessionId, ref, {objective})`.
  * Clear — `IconTrashOutline16`, `'Clear goal'`; `ctx.remote.goals.clear(sessionId, ref)`.
  * `ref` is `{id: goal.id, revision: goal.revision}` read from the projection immediately before the call
    (the RPC CAS is the guard). When no goal is projected the verbs return
    `{ok:false, error:{code:'no-current-goal', message:'no current goal to mutate'}}`.
  * Wire namespace `goals` (`edit`/`pause`/`resume`/`clear`); the client also subscribes to the forwarded
    host event **`goal/activation-changed`** to overlay process-local activation. Errors render inline as
    `'{message} ({code})'` in a `role="alert"` span.
* Composer placeholder/hint copy tied to goals (`ui-conversation/src/client/locales.ts`):
  `hint.goal` = `'describe the objective for a long-running task'`,
  `hint.goal.active` = `'goal active — edit / pause / resume / clear'`.

### 4.3 TODO PANEL

Components `TodoDock` → `TodoPanel` (`ui-conversation/src/client/skeleton/TodoPanel.tsx`).

* **When shown**: `useProjection('todos')`; `todos.length === 0` → `null`. The list is **read-only** —
  it is the host-written `todos` projection (the todo tool writes it); no RPC is called from this panel.
* **Collapsed vs expanded**: collapsed by default (`useState(true)`). The header is a button with
  `IconChecklistOutline14`, the title `t('todo.title')` = `'To-dos'`, a progress summary, and a chevron
  (`IconChevronUpOutline14` collapsed / `IconChevronDownOutline14` expanded); `aria-expanded`; root is
  `<section data-testid="todo-panel" aria-label={t('todo.title')}>`.
* **Exact progress copy** — the non-empty segments joined with a literal en-space + `·`
  (`'\u2002·\u2002'`), zero segments omitted:
  `'{done} completed'` (`todo.progress.done`), `'{active} in progress'` (`todo.progress.active`),
  `'{pending} pending'` (`todo.progress.pending`). (There is also `todo.completed` = `'{done}/{total} completed'`
  and `todo.rowTitle` = `'Update to-do list'` used by the todo tool row, not this panel.)
* **Expanded rows**: per `TodoItem`, a 14×14 status glyph (completed = check-circle, `in_progress` = spinning
  gradient ring, `pending` = dashed ring) and the item `content` text; `data-status` on each `<li>`.

### 4.4 MODE CHIPS

Rendered inside the card's `.row > .tools`, in a `.modes` div, in this order: `conversation.input.permission`
then `conversation.input.plan` (InputBar.tsx). Both receive `{locked}`.

**Permission chip** — `PermissionSelect` (`ui-permission-presets/src/client/PermissionSelect.tsx`), namespace
`permission.access` / `settings.permission`.

* Trigger: `.trigger` button, shield glyph for known values + label + optional superscript badge + chevron.
  `aria-label` = `t('mode', {name})` = `'Access mode, current: {name}'`; `title` = the option description.
* **Exact labels**: `'Read Only'` (`read-only`), `'Workspace Write'` (`workspace-write`),
  `'Full access'` (machine value `danger-full-access`), and the experimental `'Auto review'`
  (machine value `auto`, badge `'EXP'`, description `'Run without a sandbox after an experimental
  same-model review of every native tool call and PTC inner call.'`). Host-configured extra presets are
  title-cased from kebab-case by `displayPresetName`.
* Menu = `catalog.options` as `MenuEntry`s with the current value marked `selectedId`.
* **Risk gate**: picking `full-access` or `auto` opens `RiskConfirmation` before submitting. Full access:
  title `'Enable Full access?'`, description `'Full access reduces confirmation steps and lets the agent
  perform more actions directly, including sensitive operations, file changes, or external commands. Only
  use it when you trust the current task.'`, acknowledge `'I understand the risks and want to continue'`,
  confirm `'Enable Full access'`, cancel `'Cancel'`, close `'Close'`.
* **RPC**: `select(preset)` calls `live.command('/permission <preset>')`
  (`ui-permission-presets/src/client/index.ts:123-132`) — i.e. the session command channel, wire
  **`commands/execute`**. It throws if the host does not offer `/permission`.

**Plan chip** — `PlanChip` (`ui-plan/src/client/PlanModeControl.tsx`), namespace `plan`.

* Shown only while the effective target is plan mode: `plan.pending ? !plan.active : plan.active`
  (a folded host projection; no client optimism). Otherwise the seat is empty.
* Label exactly **`'Plan'`** (`chip.label`), with an `IconCloseFill14` affordance; `aria-label`
  `'Plan mode on, press to turn off'`; `title` `'Plan mode on — click to turn off (/plan off)'`; failure
  line `'Failed to exit plan mode'`.
* Entering plan mode is **not** a chip: it is the `/plan` slash command. Leaving it is this chip, which
  executes `/plan off` through `ctx.remote.commands.execute(sessionId, '/plan off', [])` — wire
  **`commands/execute`**; a missing command yields the literal `'unknown command: /plan off'`.

### 4.5 CONTEXT RING

`ContextMeter` (`ui-conversation/src/client/skeleton/ContextMeter.tsx`), rendered in the card's trailing
cluster just before the send/stop buttons.

* **What it measures / how the percentage is computed** (`ui-conversation/src/client/context-occupancy.ts`):
  ```
  usedTokens = pressure.projectedTokens ?? pressure.pressureTokens
  if (usedTokens === undefined || pressure.contextWindow === undefined) → render nothing
  percent = Math.min(100, Math.round(usedTokens / pressure.contextWindow * 100))
  ```
  Projections `contextPressure` and `contextBreakdown` come from the token-meter domain. `projectedTokens`
  (when present) prefers the projected price over the provider-anchored `pressureTokens`.
* **Ring**: 14×14 SVG, r=5.5, 2px stroke; fill uses
  `strokeDasharray = C*percent/100 + ' ' + C` (C = 2πr) with `rotate(-90 7 7)`. Tooltip and `aria-label`
  `t('context.aria', {percent})` = `'{percent} of context used'`.
* **Popover** (click toggles, `role="dialog"`, `aria-label` `t('context.used')` = `'of context used'`;
  closes on outside pointerdown or Escape):
  * headline split around the reading using a `\u0000` slot so each locale owns word order
    (English headline reads `%` then `of context used`), plus `~{used} / {window}` figures formatted with
    `number.thousand` = `'{value}K'` / `number.million` = `'{value}M'`.
  * a stacked bar: overall length = the provider-exact `percent`; the three coloured segments are
    proportioned by `contextBreakdown` (`systemTokens`, `toolsTokens`, `messageTokens`); zero-width
    segments are dropped.
  * legend rows, in bar order: `'System prompt'` (`context.system`), `'Tool definitions'` (`context.tools`),
    `'Messages'` (`context.messages`), each with `~{tokens}`.
  * When `contextPressure` has no capacity the component renders `null` (no ring at all) and closes an open
    panel if capacity disappears (model switch).

### 4.6 MODEL TRIGGER

`ModelSelect` (`ui-model-selection/src/client/ModelSelect.tsx`), seat `conversation.input.model`
(`useProjection` + injected `select`). Namespace `model`.

* **Trigger displays**: `IconDataOutline16` + the model name + the effort label + chevron. Label resolution:
  `waiting ? 'Loading models…' : currentChoice?.model.name ?? (state.current === null ? 'Select model' : '${provider}/${model}')`;
  then `triggerLabel = effortLabel === undefined ? modelLabel : '${modelLabel} · ${effortLabel}'`.
  Effort resolves to `state.current.reasoningEffort ?? reasoning.defaultEffort`; when neither exists the
  label is `'Default'` (`effort.providerDefault`).
  ARIA: `'Select model'` when unset, else `'Select model, current {model}'` or
  `'Select model, current {model}, reasoning effort {effort}'`.
* **Dropdown structure** (portaled to `body`, right-edge aligned, `role="menu"`,
  `aria-label` `'Model and reasoning effort'`). It is a **two-level drill-down**, not a flat tree:
  * **root pane** — two cells: `Model` (`menu.model`) showing the current label, and, only when the current
    model exposes reasoning, `Effort` (`menu.effort`); both with `IconChevronRightOutline14`.
  * **model pane** — a scrollable list of `role="group"` sections, one **per provider** keyed by
    `group.id`, heading = the provider `group.name`; each row is a `menuitemradio`
    (`aria-checked`, check mark + `model.name`). Loading `'Refreshing model list…'`; per-provider load
    failure `'{name} failed to load: {message}'` + `'Reload'`; empty `'No models available.'`.
  * **effort pane** — `menuitemradio` rows: an optional provider default (`'Default'`) plus every
    `reasoning.efforts[].name`; empty state `'This model provides no reasoning effort levels.'`
  * There is **no search/filter input** in the model dropdown (unlike the slash-command menu).
  * Keyboard: `Escape` backs out of a drilled pane first, then closes and restores focus;
    `ArrowDown`/`ArrowUp` cycle focus through the rows.
* **RPC**: the catalog is `this.ctx.remote.session.modelCatalog()` (`ui-model-selection/src/client/catalog.ts:45`)
  → wire **`session/modelCatalog`**; one shared catalog per Host generation, refreshed on
  `llm/adapters-updated`, `settings/document-updated` and `credentials/reference-updated`. `select(selection)`
  → `ModelDirectory` → `remote.session.selectModel(...)` — wire **`session/selectModel`**
  (`ui-model-selection/src/client/directory.ts:92-106`). `selection` is
  `{provider, model, reasoningEffort?}` — **model and effort ride the same call; there is no separate
  `setModel`/`setEffort` method and no `settings/models` call in this path.** Failures surface as a `Toast`
  anchored to `[data-composer-card]`: `'Model operation failed: {message}'`.
* The same `ModelDirectory` backs the **`/model` popupSelect contribution**
  (`ui-model-selection/src/client/index.ts:144-170`): rows `id = '<providerId>/<modelId>'`,
  `label = model.name`, `detail = '<provider name> · <description>'` when a description exists else the
  provider name; failure rows use `option.loadError`; the selected row is `active:true`. Built-in
  descriptions: `option.deepseekV4Flash.description` = `'Fast, efficient, and economical; suited to focused,
  routine, or parallel tasks.'`, `option.deepseekV4Pro.description` = `'Stronger agentic coding, knowledge,
  and difficult reasoning; suited to complex or quality-critical tasks at higher cost.'`; command face
  `command.label` = `'Model'`, `command.description` = `'Select the model for this conversation'`.
* A composer block is pushed while the current model is unavailable:
  `'This model is unavailable — select one to continue'` (`blocked.composer`). The model seat is the one
  control a composer block leaves live (`modelSeatLocked = removed || inert || !live`).

### 4.7 STATS PILLS

`StatsPills` (`ui-chat/src/client/chat/StatsPills.tsx`) on `conversation.composer.dock`, rendered by
InputBar only for `variant === 'composer'` (never on the hero). Root has `data-composer-stats`.
Renders `null` when `stats.steps === 0 && !hasTokens`. Two icon pills, each opening a portaled
`role="dialog"`; only one can be open at a time.

* **Gauge / time pill** — `IconGaugeOutline16`. Label `t('stats.counts', {turns, steps})` =
  `'{turns} turns {steps} steps'`; when `decodeMs > 0` appends `·` + `t('message.tokensPerSecond', {tps})`
  = `'{tps} tok/s'`. If no timing figure exists it degrades to a non-button `<span>` reading.
  Dialog `aria-label` `'Session statistics'` (`stats.dialog.title`), rows (only >0):
  `'LLM time'` (`stats.dialog.llmTime`), `'Tool time'` (`stats.dialog.toolTime`),
  `'Avg time to first token (TTFT)'` (`stats.dialog.ttft`, the mean over TTFT-carrying steps),
  `'Tokens per second (TPS)'` (`stats.dialog.speed`). Durations: `'{seconds}s'` /
  `'{minutes}m{seconds}s'` (`duration.compactSeconds` / `duration.compactMinutes`).
  Data source: the durable `sessionStats` projection, falling back to `deriveStats(nodes)`.
* **Database / usage pill** — `IconDatabaseOutline16`. Total = `uncachedInputTokens + cacheReadTokens +
  cacheWriteTokens + outputTokens`, shown as `t('message.turnUsage.count', {count})` = `'{count} tok'`;
  appends `·` + `t('stats.cacheHit', {percent})` = `'Cache hit {percent}%'`. Dialog `aria-label`
  `'Token usage'` (`stats.dialog.usageTitle`); title value = exact count (`'{count} tok'` with exact
  separators). Rows: `'Cache hit'` (percent, when a denominator exists), `'Uncached input'`,
  `'Cached input'`, `'Cache write'` (only when non-zero), `'Output'`. Data source: `tokenUsage` projection.

### 4.8 SEND / STOP button states

All in `InputBar.tsx`; the primary button is `.primary` with a 16×16 SVG (up-arrow = send, rounded square =
stop). Conditions verbatim from the source:

* `primaryStops = running && subagent === null && (empty || blocked !== undefined)` → the primary button
  renders the **stop square**, label/aria `t('input.stop')` = `'Stop generating'`, and `onPrimary` calls
  `stop?.()`.
* `interruptible = running && continuable` (a continuable subagent child) → an **extra independent stop
  button** is rendered next to the primary (same square glyph, label `'Stop generating'`,
  `disabled={stop === undefined}`); the primary stays Send so a child can still be steered/followed up.
* `primaryDisabled = primaryStops ? stop === undefined : (empty || disabled || machineBusy || uploadsPending)`.
  `machineBusy` = input phase `'adjudicating'` or `'submitting'`; `uploadsPending` = any attachment of kind
  `file` whose upload status is not `ready`.
* `primaryLabel`: `'Stop generating'` while `primaryStops`; else, when
  `running && steeringAvailable && !disabled && !uploadsPending && plainMessageDraft` (a non-empty plain
  message, phase `'plain'`, not starting with `/`), it becomes `'Steer message'` (`input.send.steer`) or
  `'Queue message'` (`input.send.queue`) according to `resolveSubmitMode(busyEnter, running, 'enter', steeringAvailable)`;
  every other case is `'Send message'` (`input.send`).
* The neighbouring `.add` button (`IconPlusOutline16`, aria/tooltip `'Add files or run commands'`,
  `input.commands`, `aria-haspopup="listbox"`, `aria-expanded={commandMenuOpen}`) toggles the slash/attach
  menu; it is disabled when `locked` or no `toggleCommandMenu`.
* `disabled` (input lock) = `removed || inert || !live || blocked !== undefined || parentOffline`, where
  `inert` = no session / no workspace picked and `parentOffline` = a continuable child whose parent is not
  available. When `blocked`, the composer renders read-only but the model seat stays live (see §4.6).
* `locked` and `disabled` are the same value; `editorDisabled = removed || (locked && !workspaceTrigger)`.
* Messages while unavailable (placeholder keys): `'Session unavailable'`, `'Parent session offline; sending
  is unavailable but you can still stop the run'`, `'Choose a workspace to start'`,
  `'Describe what you want to build, / commands, @ files or sessions'` (hero, `placeholder.hero`),
  `'Message or run a task, / commands, @ files or sessions'` (default),
  `'describe your task to generate plan'` (plan mode).
* Transient failures surface as a `<Toast icon={<IconWarningOutline16/>} anchor={cardRef.current}>`; prompt
  failures print `'{error.message} ({error.code})'`, attachment rejections use product copy keyed by the
  wire reason (`session/attachment-invalid`, `subagent/attachment-invalid`).

### 4.9 SLASH COMMANDS, `@` mentions, and submission

**Where it lives.** `ui-input-trigger` owns `ctx.inputTriggers` (trigger detection, candidate menu, pick
pipeline); `ui-commands` registers the `'/'` source named `'command'`; `ui-skill` registers a second `'/'`
source named `'skill'` (`order:2`); `ui-reference` registers the `'@'` source named `'reference'`
(`showGroupTitle:false`).

**Detection** — pure core `ui-input-trigger/src/core/detect.ts:48-76` (`detectTrigger(draft, caret, guard)`):
* `@` is tried first via the shared grammar `activeAtToken` (`packages/context/file-reference/src/grammar.ts:26-35`),
  matching `@"quoted path` or plain `@path` at a start/whitespace boundary.
* `/` scans backward from the caret to the first whitespace; the first `/` passing `boundaryOk` wins.
  `boundaryOk` allows start-of-draft, after whitespace, or after punctuation; rejects after a word char; and
  carves out URLs (`//`, and `:/` after a non-whitespace char).
* `position` is `'leading'` iff the draft's first non-whitespace char is the trigger, else `'inline'`. A `/`
  may open **inline** ("hi /go"), but submit requires a leading `/` (`ui-commands/service.ts:294`).
* **Guard tiers**: `plain` = `/` and `@` live; `claimed` = `/` suppressed, `@` live; `frozen`
  (adjudicating/submitting) = none. Derived from the input-machine phase by `guardOf`
  (`ui-conversation/src/client/input/facade.ts:73-79`). Every editor update calls
  `track(detectText, caret, {tier}, rev)`; detect coordinates count each chip as one U+FFFC.

**Listing RPC**: `ctx.remote.commands.list(sessionId)` (`ui-commands/src/client/service.ts:85`) → wire
**`commands/list`** (`packages/interaction/commands/lib/typert.remote-client.d.ts:16`), returning
`RemoteResult<readonly CommandDescriptor[]>` where
`CommandDescriptor = { definitionId?, name, description, input?: {hint, attachments?} }`
(`packages/interaction/commands/src/types.ts:20-31, 57-66`). A per-session `CommandDirectory` caches it with
single-flight/epoch guarding; invalidated on `commands/change` and `agent-preset/selected`.

**Menu structure**:
* With an empty query, `sectionRows` (`ui-commands/src/client/presentation.ts:74-86`) lists section
  `section.add` = **`Add`** with rows `['file','goal','plan','feedback']`, then section
  `section.commands` = **`Commands`** with rows `['compact','permission','model','export']`, followed by any
  unlisted catalog rows. Built-in host rows get localized label/description/glyph via `builtinRowFace` +
  `HOST_FACES`; contribution rows carry their own. Group titles are localized in
  `ui-input-trigger/src/client/locales.ts`: `command` = `'Commands'`, `skill` = `'Skills'`,
  `subagent` = `'Subagents'`, `loading` = `'Loading…'`. (No source named `subagent` is registered in
  `packages/client` — only `command`, `skill`, and the `@` `reference` source.)
* Row render (`ui-input-trigger/src/client/MenuView.tsx:146-208`): icon → `label ?? name` → the raw `name` as
  an alias when the localized label differs → right-aligned `description`. **Argument hints are not a row
  column**: `input.hint` becomes `candidate.hint` and is used only to hide argued commands from inline
  queries and to seed the claim ghost hint, rendered after the claimed token by InputBar via CSS
  `--dsh-composer-hint` (translated as `hint.<command>`).
* **No separate search input** in the composer: the query is the text typed after `/`, ranked by the shared
  `rankByName` (case-insensitive ordered subsequence over `name` and optional `label`, prefix hits first,
  then alignment, then source order).
* **No max-items cap** was found; the list is height-capped at `MAX_HEIGHT = 400` px and scrolls.
* **Empty state**: an all-ready-empty (or failed-to-empty) menu **auto-closes**; `MenuView` renders nothing
  for an empty ready group. There is **no "no results" string** in the slash menu. (The popupSelect shell
  instead shows `status.empty` = `'No options'`.)
* Pointer: `mousedown` picks (the combobox keeps textarea focus), `mousemove` highlights. Copy:
  `crumbs.aria` = `'Folder navigation'`, `drill.hint`/`drill.aria` = `'Browse folder'`, `drill.key` = `'Tab'`,
  `suggestions.aria` = `'Trigger suggestions'`.

**Built-in host commands' localized rows** (`ui-commands/src/client/locales.ts`) — label / description:
`/goal` `'Goal'` / `'Set or view the goal for a long-running task'`; `/plan` `'Plan'` / `'Enter or leave plan
mode'`; `/feedback` `'Feedback'` / `'Record feedback about this session'`; `/compact` `'Compact'` /
`'Compact older conversation history'`; `/permission` `'Permission'` / `'Switch the permission preset (sandbox
mode + approval policy)'`; `/export` `'Export'` / `'Download this Session log as a ZIP archive'`. Localized
claim tokens (`token.*`) are `'goal'`, `'plan'`, `'feedback'`, `'compact'`, `'permission'`, `'export'`.

**Keyboard** (`ui-input-trigger/src/client/controller.ts:223-267`, `ui-conversation/src/client/input/editor/keymap.ts:101-145`,
Lexical `COMMAND_PRIORITY_CRITICAL`): `ArrowUp`/`ArrowDown` move the shared highlight with wrap-around;
`Enter` picks the highlighted ready row, otherwise falls through to submit; `Tab` picks/drills the highlighted
row, otherwise native focus traversal; `Escape` dismisses an open popupSelect overlay first, then closes the
menu; `Space` polls sources' `matchSpace` over the completed leading token and prevents default only when a
claim/insert applied.

**How a command is submitted** — `ui-conversation/src/client/input/machine.ts:145-160` (`onEnter`):
* `phase === 'claimed'` → `begin-submit` with `args = argsAfter(draft, claim.token)` (the token and its
  separator are stripped; a bare name retains the claim) → `claim.submit(args, actx, attachments)`.
* else `trimmed.startsWith('/')` → `adjudicate` → `InputTriggerController.adjudicate(line, signal, {attachments})`
  → `ui-commands/matchEnter` (`service.ts:287-334`); the **first non-`undefined` source outcome wins**.
* else an ordinary message → the default sink → `conversation.sendSession(...)` → `session.prompt(...)`.
* **Exact wire calls**:
  * host command → `ctx.remote.commands.execute(session.sessionId, line, attachments)`
    (`ui-commands/src/client/service.ts:391`) → **`commands/execute`**
    (`packages/interaction/commands/lib/typert.remote-client.d.ts:15`). A leading claim always sends the
    catalog name: `` line = `/${desc.name} ` + args ``. An admission miss yields
    `unknown or malformed command: ${line}`.
  * ordinary prompt → `session.prompt(content, mode, signal, requestId)` (`ui-conversation/src/client/service.ts:267,292`)
    → **`session/prompt`** (`packages/api/session-controller/lib/typert.remote-client.d.ts:48`). **A `/` line
    that no source claims falls through to this same prompt path** (`facade.ts:717-722`).
  * after a successful popupSelect/action the trigger token is consumed through the scoped event
    `slash/input-consume-token` with a span guard (`draftRev` CAS) or bare-token guard; sibling events are
    `slash/input-begin-command`, `slash/input-insert-reference`, `slash/input-insert-text`.

**Client command vs host command** (`ui-commands/src/client/contract.ts`): a `CommandContribution`
(`ctx.commandUi.register`) is a client-owned row merged with the host catalog by name — a collision throws.
Its `ui` is either `ActionSpec {kind:'action', run(session)}` (consumes the token, submits nothing, never
refuses attachments) or `PopupSelectSpec {kind:'popupSelect', options(), onSelect()}`. A `CommandDecoration`
(`ctx.commandUi.decorate`) hangs a popup/action on an **existing host command's bare invocation only**; an
argued line always takes the host claim/detached path. Attachments are admitted only by
`input.attachments === true` or an action; otherwise the localized refusal
`'/{command} does not accept attachments; remove them first'` (`notice.attachmentsUnsupported`) throws.

**popupSelect presentation** (`ui-commands/src/client/popup.ts`, `PopupSelectView.tsx`): one controller per
session; options load once and are filtered locally by case-insensitive substring over `label` + `detail`.
The card renders a search `<input placeholder={t('search.placeholder')} aria-label={t('search.aria')}>`,
`role="option"` rows with an optional `badge` superscript, `detail`, a trailing check when `active`, and an
optional `RiskConfirmation` gate. Keyboard: `ArrowUp`/`ArrowDown` move, `Enter` selects, `Escape` dismisses
to the composer, `ArrowLeft`/`ArrowRight` keep the input caret; outside pointerdown dismisses. Copy:
`search.placeholder` = `'Search…'`, `search.aria` = `'Filter options'`, `status.loading` = `'Loading options…'`,
`status.applying` = `'Applying…'`, `status.empty` = `'No options'`, `overlay.aria` = `'/{command} options'`,
`listbox.aria` = `'/{command} matches'`; plus shared `retry` = `'Retry'`, `close` = `'Close'`.
Examples: `/model` is a popupSelect **contribution** (`ui-model-selection/src/client/index.ts:144-170`);
`/permission` is a **decoration** on the host command with the full-access risk gate.

**`@` file/session mentions** (`ui-reference`): trigger char `@`, grammar plain `@path` or
`@"path with spaces`; picks insert a chip with `appearance` file/folder/session and a codec that serializes
back to the literal mention. Menu sections: `section.files` = `'Files & folders'` (directory rows drill with
`Tab`; name = basename, `+ '/'` for folders; description = parent path) and `section.sessions` =
`'Sessions'` (description = `location · age`). Drill crumbs start at `crumb.root` = `'Workspace'`; missing
cwd `candidate.noCwd` = `'(no cwd)'`. Ages: `'now'`, `'{n}min'`, `'{n}h'`, `'{n}d'`, `'{n}mo'`, `'{n}y'`.
Submitting serializes every chip through its owner codec and **fails the send rather than downgrading**.
Candidate RPC: `fileReferences/list` (`(agentId, query, signal?)`).

---

## 5. Right-hand panel (dockkit)

### 5.0 Architecture

* The product panel is `ui-sidebar-right`; the layout engine is `ui-dockkit` (tab strip, split tree,
  floating panels, per-tab context menu); the 3-column frame and the panel's width track are `ui-layout`.
* `ui-sidebar-right/src/client/index.ts` provides `ctx.sidebarRight` (`SidebarRightController`) and
  `ctx.sidebarRightTabs` (`SidebarRightTabRegistry`), mounts the `rightbar.session` seat, and registers the
  shipped `guide` type. Other packages contribute tab bodies in two stages:
  `ctx.sidebarRightTabs.register(...)` + a keyed `sidebar.right.pane.tab` slot.

### 5.1 Tab strip

**Registered tab kinds** (production `ctx.sidebarRightTabs.register` call sites):

| kind | id | multiple | priority | package |
|---|---|---|---|---|
| `guide` | `@deepseek-ai/dsh-client-ui-sidebar-right/guide` | — | `builtin` | `ui-sidebar-right/src/client/tabs/guide/definition.ts` |
| `files` | `@deepseek-ai/dsh-client-ui-sidebar-files` | — | `builtin` | `ui-sidebar-files/src/client/definition.tsx` |
| `terminal` | `@deepseek-ai/dsh-client-ui-sidebar-terminal` | `true` | `builtin` | `ui-sidebar-terminal/src/client/index.ts:47-50` |
| `text` | `TEXTPREVIEW_ID` (`'text'`) | — | `fallback` | `ui-sidebar-documentpreview/src/client/definition.ts` |

`text` is the resource viewer: `patterns: ['dsh-resource://file/**']`. **The registered kind is literally
`text`, not `documentpreview`** (the package is `ui-sidebar-documentpreview`). A file click opens this tab.

**Chip labels / icons:**

| kind | chip title (English) | icon |
|---|---|---|
| `guide` | `tab.guide.title` = **`Start`** | `CompassGlyph` (falls back to `CubeGlyph`) |
| `files` | `type.label` = **`Files`** | `FileTypeIcon kind="folder" size={16}` |
| `terminal` | `title` = **`Terminal`**, then the live terminal name | `TerminalIcon` |
| `text` | basename of the file | `FileTypeIcon kind={classifyFileType(title)}` |

Guide entry cards: files `guide.title` = `'Workspace files'` / `guide.description` =
`'Browse files in this session's workspace'`; terminal `new` = `'New terminal'` / `description` =
`'Run commands in the Session workspace'`.

**There is no fixed tab list/order in the strip** — it renders only the tabs currently open in that pane
(`ui-dockkit/src/components/TabPanel.tsx`). The one ordered list is the guide page's capsules: files
`order: 10`, terminal `order: 20`.

**Default panel**: `defaultSeed` (`ui-sidebar-right/src/client/contract/seed.ts`): if exactly one guide entry
is registered that type is default; otherwise the guide. Two ship, so the seeded default is the **guide
("Start") page**. `pageAddress(kind)` = `sidebar://<kind>`. A fresh surface starts collapsed with one empty
pane and no tab; the settle rule seeds the default page when the column first expands empty.

**How many can be open**: the **product caps docked panes at two, side by side**
(`stores.ts:288`, `SidebarRight.tsx:311`; README: "two horizontal panes, initially equal, with divider ratios
limited to 20%–80% … at the two-pane limit, split controls are hidden"). The kit's own ceiling
`MAX_DOCK_PANES = 4` is **not** the product limit. The panel passes `dropZones="horizontal"`, so only
left/right splits are offered (the `dock.drop.top`/`bottom` labels exist but are unused here). There is **no
tab-count cap per pane**: the chip box scrolls horizontally (`.stripTabs { overflow-x:auto }`) with 24px fade
masks. Floating panels each hold exactly one tab and do not count against the pane cap; first float
`{width:380, height:300}`, min `{width:220, height:140}`.

**Close / reopen**: chip close button `data-dockkit-tab-close` (and the context-menu item); `canCloseTab`
refuses the guide when it is the sole docked tab. Closing the sole non-guide docked tab also exits fullscreen
and collapses the column. Reopen via the header `ExpandButton`, any `ctx.sidebarRight.openResource/openTab`
(always expands), or the strip `+` (`dock.addTab` = `'New tab'`, which opens the guide page and only appears
while the pane holds no guide). `_undo()`/`_redo()` exist internally but have no UI.

**Overflow**: none for tabs — horizontal scroll + fades. `ui-dockkit/src/components/TabMenu.tsx` is the
**right-click per-tab context menu** (`data-dockkit-tab-menu`); the extras seat
`sidebar.right.tab.menu.item` has no production registrant, so today the menu contains only `'Close'`.

Dock labels (`ui-sidebar-right/src/client/locales.ts`, projected by `labels.ts`): `dock.emptyPane` =
`'Empty pane'`; `dock.splitPane` = `'Split'`; `dock.splitPaneDisabled` = `'Two panes is the limit'`;
`dock.splitPaneNarrow` = `'Not enough width to split, widen the sidebar'`; `dock.closeTab` = `'Close'`;
`dock.addTab` = `'New tab'`; `dock.dockFloat` = `'Send back to the sidebar'`; `dock.closeFloat` = `'Close'`;
`dock.drop.center/left/right/top/bottom` = `'Move here'` / `'Add left split'` / `'Add right split'` /
`'Add top split'` / `'Add bottom split'`. Unregistered-kind fallback: `tab.unavailable` =
`'Nothing here can view this kind of content yet.'`

### 5.2 FILES panel

`ui-sidebar-files/src/client/{definition.tsx,FilesBody.tsx,FilesTitle.tsx,face.ts,store.ts,locales.ts}`.

* **Tree, not flat** — one absolute directory level at a time; the root is the session `cwd`
  (`FilesBody.tsx:175`). `start()` seeds `{root, levels:{}, expanded:[root], scrollTop:0}`.
* **Expand/collapse + lazy load**: directory rows `aria-expanded`; toggling adds/removes from `expanded`; a
  first open triggers a load. A collapsed level keeps its loaded contents. Indentation: nested levels get
  `padding-left: 18px` (`.level .level`).
* **Icons**: `IconFolderOpen16`/`IconFolderClose16`; files `FileTypeIcon kind={classifyFileType(name)}`;
  non-file/dir entries render disabled with the `entry.other` title.
* **Ordering**: directories first, then case-insensitive numeric-aware name order (`orderEntries`).
* **Header**: shows the root path and a `Reload` button (`reload` = `'Reload'`, `data-files-reload`); reload
  resets and re-requests every expanded path.
* **RPC**: only `remote.workspaceFiles.list(sessionId, path, signal)` (`face.ts:55`) → wire
  **`workspaceFiles/list`** (`packages/api/workspace-files/lib/typert.remote-client.d.ts:21`); result trimmed
  to `{entries, truncated}`.
* **File preview**: clicking a file row calls
  `tab.actions.openResource(fileAddressFor(sessionId, root, path))` — address
  `dsh-resource://file/session/<id>/<path>` (`packages/util/workspace-path/src/index.ts:92-99`). The registry
  routes it to the `text` viewer (`ui-sidebar-documentpreview`) as a **preview tab in the right panel**, not
  an external editor. The viewer reads via `workspaceFiles/read` (`documentpreview/src/client/rpc.ts:81`),
  `workspaceFiles/readAll` (`index.ts:95`), `workspaceFiles/readRelated`; metadata from the `file` resource
  provider via `workspaceFiles/stat` + `workspaceFiles/changes`.
* **Empty / error copy**: `loading` = `'Reading…'`; `empty` = `'Empty directory'`; `truncated` =
  `'Too many entries, showing only some of them.'`; `noWorkspace` =
  `'This session has no workspace directory.'`; `reload` = `'Reload'`; `entry.other` =
  `'Not a file or a directory, so it cannot be opened.'`; `error.notFound` =
  `'That directory is gone. It may have been moved or deleted.'`; `error.outsideWorkspace` =
  `'That directory is outside the workspace, so the sidebar will not read it.'`; `error.notDirectory` =
  `'That is not a directory.'`; `error.unavailable` = `'Read failed: {message}'`.

### 5.3 TERMINAL panel

`ui-sidebar-terminal/src/client/*`; the client model is in `packages/api/terminal-controller/src/client/*`.

* **What it is**: a real PTY rendered by **xterm.js** (`import { Terminal } from '@xterm/xterm'` +
  `FitAddon`, `TerminalBody.tsx`). A `TerminalView` survives DOM unmount; the process ends only on explicit
  close. Host default `maxTerminals: 8`.
* **RPCs** (namespace `terminal`, generated at `packages/api/terminal-controller/lib/typert.remote-client.d.ts:22-30`):
  `terminal/environment`, `terminal/list`, `terminal/shells`, `terminal/create`, `terminal/follow` (**stream**),
  `terminal/write`, `terminal/resize`, `terminal/rename`, `terminal/close`.
* **How output streams**: **not SSE** — a WebSocket mux at `/api/remote.mux`
  (`REMOTE_STREAM_MUX_PATH`, `packages/api/gateway/src/stream-protocol.ts:6`), one physical socket carrying
  logical streams whose open/cancel frames name `endpoint:'terminal/follow'`; alternatively
  `connection.rpc.open('/api', endpoint, payload, signal)` when a worker-local carrier exists. Input, resize,
  rename and close are ordinary unary RPCs; keystrokes are serialized through a promise chain.
* **Guide / status copy**: `new` = `'New terminal'`, `description` = `'Run commands in the Session workspace'`,
  `shell` = `'Choose shell'`, `shellLoading` = `'Loading shells…'`, `shellEmpty` = `'No shells available'`,
  `loading` = `'Reading terminal environment…'`, `creating` = `'Starting…'`, `connecting` = `'Connecting…'`,
  `disconnected` = `'Disconnected.'`, `reconnect` = `'Reconnect'`, `closed` = `'Terminal closed.'`,
  `exited` = `'Process exited ({code})'`, `failed` = `'Terminal error: {message}'`, `unavailable` =
  `'Unavailable'`, `retry` = `'Retry'`, `readonly` = `'This view is read-only.'`, `control` = `'Take control'`,
  `terminalLimit` = `'The terminal limit has been reached. Close unused terminals and try again. Exited
  terminals also count toward the limit.'`
* **Cleanup flow**: `registerCloseHandler('terminal', …)` closes the host terminal on tab close. Failed
  closes persist under `localStorage` key **`dsh.terminal.close.v1.<id>`** and surface in a global
  `shell.overlay` stack: `cleanupFailed` = `'Terminal “{title}” could not be ended: {message}'` with Retry.
* **Recovery flow**: the header action calls `ctx.webTerminals.recover(sessionId)` → `terminal/list`, then
  reopens each via `ctx.sidebarRight.openTabIn(sessionId,'terminal',{params:{terminalId}})`. On failure:
  `title` = `'Terminal recovery failed: {message}'`, label = `'Retry terminal recovery'`. Shell preference
  persists under `localStorage` key **`dsh.terminal.shell`**.
* **Issue copy**: `missingTerminal` = `'This terminal no longer exists. Open a new terminal.'`; `inputFull` =
  `'The input buffer is full. Reconnect and try again.'`; `attachmentEnded` =
  `'The terminal connection ended. Reconnect to continue.'`; `invalidOutput` =
  `'The terminal screen could not be received. Reconnect to recover it.'`

### 5.4 DELIVERABLES — not a right-panel tab

Verified: `grep -rn "sidebar.right\|sidebarRight" ui-deliverables/src` returns nothing. `ui-deliverables`
registers **no `sidebar.right.*` slot and no tab kind**. It is a **conversation** surface:

* `conversation.chat.turnTail` row: `Deliverables` + `ProducedFiles` (`client/index.ts:48-60`). It lists
  **produced files** (paths from successful `write`/`edit`/mutating `str_replace_editor` tool calls) and
  **presented files** (durable `deliverables/presented` session event) via `deliverablesDefinition`
  (`turn-deliverables.ts:157-215`; matches `turn/start`, `tool/call`, `deliverables/presented`, `tool/result`).
  Copy: `produced.label` = `'Files changed'`, `produced.open` = `'Open {name}'`, `produced.more` =
  `'+ {count} files'`.
* `tool.call.toolview` key `'present'` → `PresentRow`, title `row.title` = `'Present files'`; states
  `row.running/ok/error/stopped` = `'Delivering'` / `'Delivered'` / `'Delivery failed'` / `'Interrupted'`;
  `row.inspect` = `'Inspect call'`.
* **How items open**: the primary click calls the chat view's `openFile(path)`, which in
  `ui-chat/src/client/apply.ts:133-139` is `ctx.sidebarRight.openResource(fileAddressFor(sessionId, cwd, path))`
  → **opens the file in the right panel's `text` preview tab**. The card's secondary menu offers native
  actions: `presented.defaultApp` = `'Open in default app'`, `presented.finder` = `'Show in Finder'` /
  `presented.explorer` = `'Show in File Explorer'` / `presented.directory` = `'Open containing folder'`,
  issued as `POST /api/present.open?…(&action=reveal)` with desktop metadata from `GET /api/present.host`.
  Other copy: `presented.preview` = `'Preview in sidebar'`, `presented.previewCard` =
  `'Preview {name} in sidebar'`, `presented.previewButton` = `'Open {name} in sidebar'`, `presented.all` =
  `'All {count} files'`, `presented.collapse` = `'Collapse'`, `presented.hostError` =
  `'Could not read the Host desktop information'`, `presented.unavailable` =
  `'This Host has no desktop available to open files or folders'`.

### 5.5 Open/close, presentation, width

* **Open/close is a recorded, per-session fact.** The panel stays mounted and slides off the right edge;
  `aria-hidden` when collapsed (`shell/SidebarRight.tsx:296-307`). Markers
  `data-sidebar-right-panel="push"|"fullscreen"`, `data-sidebar-right-open`.
* **Presentations** (`ui-dockkit` `DockMode`): `push` (panel width; the conversation makes room) and
  `fullscreen` (viewport). The control is in the top-right pane's strip chrome (`PanelChrome`):
  `chrome.toFullscreen` = `'Fullscreen'` / `chrome.exitFullscreen` = `'Exit fullscreen'`
  (`data-sidebar-right-mode`); collapse button `data-sidebar-right-toggle`, tooltip `chrome.collapse` =
  `'Collapse sidebar'`, aria `chrome.collapseAria` = `'Collapse right sidebar'`, glyph
  `IconPanelLeftOutline16` mirrored.
* **Responsive/collapse**: `autoFullscreen = viewportWidth < 768` (the panel fills the viewport and gives up
  its track); the left sidebar auto-collapses below `SIDEBAR_AUTO_COLLAPSE = 1024`; opening the right panel
  clears the narrow override; `canShow = normal.rightbar > 0`, and a shown non-fullscreen panel with no room
  auto-collapses; `computeColumns` protects `CENTER_MIN = 400` and drops the right track to 0 when
  `viewport - sidebar - 400 < RIGHTBAR_MIN`.
* **Drag-to-resize**: the outer `DragHandle` exists only in expanded normal presentation; pointer capture +
  rAF-throttled dx → `actions.setRightbar(rightbarBase - dx)`.
* **Width** (`ui-layout/src/client/columns.ts`): `RIGHTBAR_MIN = 300`, `RIGHTBAR_MAX_RATIO = 0.7`,
  `RIGHTBAR_DEFAULT_RATIO = 0.45`. First open = `max(300, round(viewport * 0.45))`, clamped to
  `[300, max(300, viewport * 0.7)]`. Split divider drag is clamped to `minPaneFraction = 0.2`.
* **Floating panels** portal to `document.body`; move grip `data-dockkit-float-grip`, corner
  `data-dockkit-float-resize`, dock-back `labels.dockFloat` = `'Send back to the sidebar'`, close
  `labels.closeFloat` = `'Close'`.
* **Persistence: none for the right panel.** The `ui-layout` store is transient by design
  ("Transient layout preferences"; `createLayoutStore` passes no persist option) and
  `createSidebarRightStore(seed)` passes no persist key. Width, expanded state, open tabs and split ratios
  all reset on reload. The only `localStorage` keys in this area are the terminal's
  `dsh.terminal.shell` and `dsh.terminal.close.v1.<id>`.
* `ctx.sidebarRight` face (`service.ts`): `openResource(address, {kind?, params?, paneId?, replaceTab?,
  revealIfOpened?})`, `openTab`, `close`, `active`, `isExpanded`, `toggleExpanded`, `focus`, `split`, `float`,
  `dock`, plus `…In` session variants and `registerCloseHandler(kind, handler)`. Resource addresses must start
  with `dsh-resource://`. Page tabs are recorded at `sidebar://<kind>`; `multiple:true` kinds get
  `sidebar://<kind>/<uuid>`.
* `RightbarRoot` renders the session panel only while `usePanelInfo(info => info.activePanelId === null)`
  (hidden behind global main panels).

### 5.6 Right-panel RPC/`workspaceFiles` summary

`workspaceFiles/list` (browse), `workspaceFiles/read`, `workspaceFiles/readAll`, `workspaceFiles/readRelated`,
`workspaceFiles/stat`, `workspaceFiles/changes` (preview + change feed). Terminal: `terminal/*` as in §5.3.
There is no `workspaceFiles/delete`/`write` in the panel (the panel is a read-only browser + viewer).

## 6. Settings and modals

### 6.1 Shell and modal chrome

* `ui-settings` is the domain base: the slot contract, `ctx.settingsScope`, and one `settings.describe`
  mirror (`settings-contract.ts`, `settings-scope.ts`, `settings-mirror.ts`, `schema.ts`).
* `ui-settings-general` is the **shell**. It occupies the `sidebar.settings` slot and renders a **modal
  dialog** (`role="dialog"`, `aria-modal`, its own overlay/mask markup — **not** the shared `Modal`
  primitive). It opens only from the **sidebar-foot gear button** (`settings.trigger` = `'Settings'`);
  there is **no keyboard shortcut**. It closes via the header Close button (`close` = `'Close'`), a mask
  click, or document `Escape`.
* Layout: a 188px **left nav rail**, not tabs. Section labels come from each section's `label()`. Nav glyphs
  are chosen by id in `SettingsRoot.tsx`: `models` → `IconDataOutline16`, `agent-presets` →
  `IconAgentPresetOutline16`, `plugins` → `IconPersonalizationOutline16`, `archived-sessions` →
  `IconArchiveOutline20`, anything else → the settings gear `IconSettingsOutline16`.
* Panel geometry (`SettingsRoot.module.css`): `.panel { width: 800px; height: min(800px, calc(100vh - 48px));
  border-radius: 32px }`; the mask uses `--dsw-alias-bg-mask-1` + `--dsw-mask-blur`; `.nav` is 188px;
  `.navCell` is 40px tall. Focus moves to the Close button on open and returns to the trigger on close.
* The shell slots are `settings.trigger`, `settings.header` (`title` = `'Settings'`), `settings.action` (list),
  `settings.close`, `settings.section`, `settings.onboarding`. A loopback-only header action (id
  `open-document`, order 0, rendered only when `ctx.remote.$host.isLoopback`) calls
  `settings/openSettingsDocument`.
* Section order (`settings.section`):

  | order | section id | nav label | package |
  |---|---|---|---|
  | 0 | `general` | `general.nav` = `General` | `ui-settings-general` |
  | 10 | `models` | `nav` = `Models` | `ui-settings-models` |
  | 15 | `plugins` | `nav` = `Plugins` | `ui-settings-plugins` |
  | 20 | `agent-presets` | `nav` = `Agent presets` | `ui-agent-preset` |
  | 25 | `archived-sessions` | `nav` = `Archived sessions` | `ui-settings-unarchive-sessions` |

* The shell also renders an "Open configuration file" action: `openDocument` = `'Open configuration file'`,
  error `'Could not open configuration file'` → `settings/openSettingsDocument`.
* Connection status copy in the shell: `connection.error` = `'Disconnected'`, `connection.connecting` =
  `'Reconnecting'`, `connection.connected` = `'Connected'`, `connection.reconnect` =
  `'Disconnected, reconnect now'`, `connection.restart` = `'Reconnecting, reconnect now'`.

### 6.2 General section rows (`settings.general.item`, rendered by `ui-settings-general/GeneralSection.tsx`)

| order | row | copy (English) | control | RPC |
|---|---|---|---|---|
| -20 | Permission default | title `'Permission'` (`settings.permission`), description `'Choose the default permission mode for new sessions'` | select over `permissionPresets/catalog` options (`Read Only` / `Workspace Write` / `Full access` / `Auto review`) | **`settings/mutate`** (namespace `settings.permission`) + `permissionPresets/catalog` to read options |
| 0 | Language | `language.title` = `'Language'` | Menu select | **`settings/mutate`** (namespace `settings.locale`) |
| 10 | Appearance | `appearance.title` = `'Appearance'`; values `'Light'` / `'Dark'` / `'System'` | three swatch buttons | **`settings/mutate`** (theme namespace) |
| 11 | Font size | `fontSize.title` = `'Font size'`, description `'Only affects conversation content'`, unit `'px'`, buttons `'Increase font size'` / `'Decrease font size'` | stepper | **`settings/mutate`** |
| 12 | Conversation display | `settings.transcript.title` = `'Conversation display'`, description `'Controls process content in completed turns'`; options `'Normal'` / `'Compact'` | Menu select | **`settings/mutate`** (chat namespace) |
| 20 | Send behavior while busy | `settings.enter.title` = `'Send behavior while busy'`, description `'What Enter and the Send button do while the agent is running; Cmd/Ctrl+Enter uses the other behavior'`; options `'Queue'` / `'Steer'` | Menu select | **`settings/mutate`** |

All General rows write through `settingsScope` → `settings/mutate` on the loopback host; in memory mode
(no host settings service) the change is process-local. The permission row's risk gate reuses
`RiskConfirmation` with `confirm.title` = `'Enable Full access?'` and `confirm.acknowledge` =
`'I understand the risks and want to continue'`.

### 6.3 Models section (`ui-settings-models`, namespace `settings.models`)

Rows are assembled from `llm/listProviders` + `llm/listConfigurableProviders` + the shared
`settings/describe` + `credentials/describe`. Copy highlights:
`nav`/`title` = `'Models'`, `intro` = `'Enter your API keys to use models from the following providers.'`,
`add` = `'Add provider'`, `customAdd` = `'Add a custom provider'`, `customTitle` = `'Custom provider'`,
`customTag` = `'Custom'`, `edit`/`editProvider` = `'Edit'` / `'Edit {provider}'`, `remove`/`removeProvider` =
`'Delete'` / `'Delete {provider}'`, `deleteTitle` = `'Delete {provider}?'`, `deleteDescription` =
`'Deleting {provider} removes its configuration. Any credential it uses is managed elsewhere and will be
kept.'`, `deleteDescriptionWithCredential` = `'Deleting {provider} removes its configuration and stored API
key.'`, `deleteConfirm` = `'Delete {provider}'`, `deleting` = `'Deleting {provider}…'`, `apply`/`applying` =
`'Apply'` / `'Applying…'`, `savedProvider` = `'Saved {provider}.'`, `credentialConfigured` =
`'API key configured'`, `credentialMissing` = `'API key missing'`, `readOnly` =
`'The settings document is read-only in this deployment.'`, `loadFailed` =
`'Loading the provider directory failed'`, `conflict` = `'Someone else changed these settings while this card
was open. Close it and reopen to edit the current values.'`.

Credential/API-key fields: `keyInput` = `'API key'`, `keyPlaceholder` = `'Enter your API key'`,
`keyPlaceholderNative` = `'Enter an API key, or leave blank to use environment authentication'`,
`keyStored` = `'Configured — enter a new value to replace'`, `keyEnvLocked` =
`'Provided by the launch environment (read-only)'`, `keyBlank` =
`'Enter the API key, or leave the field empty to keep the stored one.'`, `keyIllegalCharacters` =
`'This API key is not in a valid format. Please check it.'`.
Advanced: `baseUrl` = `'Base URL'`, `baseUrlDefault` = `'Provider default'`,
`deepSeekChatBaseUrl` = `'https://api.deepseek.com'`,
`deepSeekMessagesBaseUrl` = `'https://api.deepseek.com/anthropic'`, model rows `modelId` = `'Model ID'`,
`modelName` = `'Display name'`, `contextWindow` = `'Context window'`, `maxTokens` = `'Max output tokens'`,
`addModel` = `'Add model'`, `removeModel` = `'Delete model'`, `modelsEmpty` =
`'No models will be shown in the selector. Unlisted IDs can still be sent directly.'`, `advancedHint` =
`'Other fields live in settings.yaml; edit that section directly.'`.
Fetch flow: `fetchModels` = `'Fetch available models'`, `fetching` = `'Asking the provider…'`,
`fetchTitle` = `'Choose models to add'`, `fetchSearch` = `'Search models'`, `fetchAdopt` =
`'Add selected'` → **`llm/discoverModels`**.
RPCs: **`settings/mutate`** (or `settings/update`) for config, **`credentials/set`** / **`credentials/unset`**
for keys, **`credentials/describe`** to read configured/missing state, `llm/listProviders`,
`llm/listConfigurableProviders`, `llm/discoverModels`, `session/modelCatalog`. Delete confirm uses the
shared `Modal`.

### 6.4 Plugins section (`ui-settings-plugins`, namespace `settings.plugins`)

Section `nav`/`title` = `'Plugins'`, `intro` = `'Configure and inspect the plugins installed in this
deployment.'`, `tabs` = `'Plugin views'`. Two tabs, order configurable `0` (`configurableTab` =
`'Plugin configuration'`) then all `10` (from `ui-settings-plugin-inventory`).

Configurable cards (each stages edits and Saves through `settings/mutate` via scope `set`/`unset`):
* `bashTitle` = `'Shell'`, `bashDescription` = `'Limits every command the agent runs.'`;
  `bashTimeoutMs` = `'Command timeout (ms)'`, `bashMaxOutputBytes` = `'Output cap per stream (bytes)'`.
* `agentLoopTitle` = `'Agent loop'`, `agentLoopDescription` = `'How the agent dispatches tool calls.'`;
  `agentLoopMaxParallel` = `'Parallel tool calls'`.
* `webSearchTitle` = `'Web search'`, `webSearchDescription` = `'The DeepSeek search provider.'`;
  `webSearchApiKey` = `'API key'` (stored outside the settings file; read via `credentials/describe`,
  written via `credentials/set`), `webSearchBaseUrl` = `'Endpoint'`, `webSearchMaxUses` =
  `'Max searches per request'`.
* `subagentModelSelectionTitle` = `'Subagent'`, toggle `subagentModelSelectionToggle` =
  `'Allow agents to choose models for subagents'`, list `subagentModelSelectionAllowed` =
  `'Models agents may choose'` (catalog from `session/modelCatalog`).

Shared card chrome: `overridden` = `'Overridden'`, `reset` = `'Reset to default'`, `readOnly` =
`'This deployment stores settings read-only.'`, `expand`/`collapse` = `'Show settings'` / `'Hide settings'`,
`save`/`saving` = `'Save'` / `'Saving…'`, `discard` = `'Discard'`, `unsaved` = `'Unsaved'`, `saveFailed` =
`'The deployment did not accept these values; they were left for you to correct.'`, `invalidNumber` =
`'Enter a number, or leave blank to use the default.'`, `empty` = `'This deployment exposes no plugin
settings.'`

Plugin inventory tab (`ui-settings-plugin-inventory`): reads **`pluginInventory/list`**; copy `tab` =
`'Plugin list'`, `loading` = `'Reading plugins…'`, `error` = `'Plugins are temporarily unavailable.'`,
`retry` = `'Retry'`, `search` = `'Search plugins'`, `empty` = `'No plugins are available.'`,
`emptySearch` = `'No matching plugins.'`, `presetTitle` = `'Session plugins'`, `globalTitle` =
`'Global plugins'`, tags `'Enabled'`/`'Disabled'`/`'Conditional'`/`'Enabled via presets'`/`'Failed'`,
statuses `'Not running'`/`'Waiting for dependencies'`/`'Loading'`/`'Running'`/`'Failed to start'`/
`'Unloading'`.

### 6.5 Agent presets section (`ui-agent-preset`, namespace `settings.agentPreset`)

`nav` = `'Agent presets'`; `sectionIntro` = `'A preset is the plugin composition one session's agent runs —
its tools, prompt, and capabilities. Duplicate an existing one and make it yours, or let the agent draft one
for you in Creator mode.'`
* Roster **`agentPresets/list`**; view **`agentPresets/read`**; duplicate **`agentPresets/copy`**; delete
  **`agentPresets/deletePreset`**; default/visibility via **`settings/update`**; native folder via
  **`settings/canOpenAgentPresetDirectory`** + **`settings/openAgentPresetDirectory`**.
* Copy: `duplicate` = `'Duplicate'`, `duplicateUnavailable` =
  `'This deployment has no writable preset directory'`, `delete` = `'Delete'`, `inUse` =
  `'New task default'`, `view` = `'View'`, `setDefault` = `'Set as default'`, `openLocation` =
  `'Open folder'`, `showLocation` = `'Show location'`, `copyTitle` = `'Duplicate preset'`, `create` =
  `'Create'`, `creating` = `'Creating…'`, `creatorDraft` = `'Draft a custom preset with Creator mode'`,
  built-in preset names/descriptions `Standard mode`, `PTC mode`, `Minimal mode`, `Creator mode`.

### 6.6 Archived sessions section (`ui-settings-unarchive-sessions`)

`nav` = `'Archived sessions'`; `search` = `'Search archived sessions'`; `loading` =
`'Reading sessions…'`; `empty` = `'No archived sessions.'`; `unavailable` =
`'No archived session here can be restored.'`; `emptySearch` = `'No matching sessions.'`;
`ungrouped` = `'Ungrouped'`; per-row button `unarchive` = `'Unarchive'`, aria-label
`unarchiveNamed` = `'Unarchive {title}'`. Rows list title + workspace + relative time (newest archived
first), `title` is ellipsized with no `title=` (full text only in the aria-label).
**RPC: `workspace/unarchiveSession`** via `ctx.uiWorkspace.unarchiveSession(sessionId)`. This is the **only**
restore path for archived sessions — there is no per-row undo (§2.5).

### 6.7 What does NOT exist

* **No `auth/`, `account/`, `security/`, `models/`, or `plugins/` RPC prefixes**, and **no sign-in /
  sign-out / logout UI or copy**. Connection README: "There is no logout operation". Browser auth is a signed
  HttpOnly cookie handled by the connection package (`browser-auth.ts`) — see `protocol.md` §5.
* **No "About" / version settings section.**
* **No Skills settings section.** Skills exist only as a composer slash-command source (`ui-skill`) reading
  **`skills/list`** (host namespace `skills`).
* No `settings/models` RPC — model selection rides `session/selectModel` (§4.6).

### 6.8 Other modals / dialogs in the client

* Shared `Modal` primitive (`ui-primitives/src/Modal.tsx`): centered, body-portaled, closes on mask click +
  `Escape`, `closeLabel` default `'Close'`. Used by: models delete/fetch, agent-preset copy/view/delete,
  onboarding (`OnboardingModal`), directory browser ("Select Workspace Directory"), message-feedback dialog,
  chat file-open error, and the workspace rename/delete dialogs.
* `RiskConfirmation` (`ui-primitives`): title/description/acknowledge/cancel/confirm gate used by Full access
  and by popupSelect options that carry a `confirmation`.
* `ui-approval` and `ui-user-questions` are **not modals** — they are `conversation.composer` chain
  takeovers (ui-approval also fills `conversation.approval.detail`). Approval copy (namespace `approval`):
  `waiting` = `'Waiting for approval'`, `reject` = `'Reject'`, `allowOnce` = `'Allow once'`, `escalation` =
  `'Tool {toolName} requests privileged execution'`, `detail.aria` = `'Approval details'`. Questions copy
  (namespace `question`): `nav.prev` = `'Previous question'`, `nav.next` = `'Next question'`, `nav.cancel` =
  `'Dismiss all questions'`, `action.skip` = `'Skip'`, `action.next` = `'Next'`, `plan.header` =
  `'Plan review'`, `plan.approve` = `'Approve'`, `plan.decline` = `'Refuse'`, `plan.discuss` =
  `'Chat about it'`, `custom.placeholder` = `'Type your answer'`, `option.recommended` = `'Recommended'`.
* Onboarding modals are contributed by `ui-settings-models` through `settings.onboarding` and are
  **blocking** (`OnboardingModal` sets `#root.inert`): `welcome-notice` (`welcomeTitle` =
  `'Internal Testing Notice'`, `welcomeContinue` = `'Continue'`, `welcomeError` =
  `'The acknowledgement could not be saved. Please try again.'`) and `deepseek-official` (`onboardingTitle` =
  `'Add an API key to get started'`, `onboardingLater` = `'Configure later'`, `onboardingSave` =
  `'Save and continue'`).
* Message feedback (`ui-message-feedback`) uses a `Modal` from `conversation.input.overlay` plus
  `conversation.chat.assistant-actions`: `dialog.title` = `'Submit feedback'`, `dialog.categories` =
  `'Feedback category'`, `dialog.detail` = `'Feedback details'`, `toast.recorded` =
  `'Thanks for your feedback'`.
* Chat file-open error (`ui-chat`, `FileOpenErrorDialog`): `fileOpen.title` = `"Couldn't open file"`, buttons
  `'Cancel'` / `'Retry'`.
* Directory-picker holes: `conversation.hero.workspace.directoryFlow` and `sidebar.workspaces.directoryFlow`;
  RPCs `directoryPicker/list` + `directoryPicker/createDirectory` (browse) or `directoryPicker/pick` (native).
* The slash `popupSelect` shell is an **overlay** (`conversation.input.overlay`), not a modal.

### 6.9 Settings-related RPC catalog (confirmed host methods)

`settings/describe`, `settings/update`, `settings/replace`, `settings/mutate`,
`settings/openSettingsDocument`, `settings/canOpenAgentPresetDirectory`,
`settings/openAgentPresetDirectory`; `credentials/describe`, `credentials/set`, `credentials/unset`;
`llm/listProviders`, `llm/listConfigurableProviders`, `llm/discoverModels`; `session/modelCatalog`,
`session/selectModel`; `workspace/unarchiveSession`, `workspace/rename`, `workspace/delete`;
`agentPresets/{list,read,copy,deletePreset,select}`; `permissionPresets/catalog`; `pluginInventory/list`;
`skills/list`; `directoryPicker/{list,createDirectory,pick}`.
Client-only (no RPC): the open/close state, active section id and nav rendering are component-local in
`SettingsRoot.tsx`; plugin-inventory search/preset-switcher/expand-collapse, the archived-sessions search box,
and the directory browser's "Show hidden files" toggle / path editing are all client-side. The General rows
are **not** client-only: theme, locale, transcript view and busy-Enter persist in the **host user-settings
document** when one is served.

**Important nuance** (`ui-settings/src/client/index.ts`): the settings scope is `'host'` only when
`ctx.remote.$host.isLoopback`. A browser connected over the network gets `'memory'` mode, where
`SettingsScopeController.enqueue` returns immediately and `writable` stays false — i.e. the General/Models/
Plugins rows are effectively read-only from a remote client. A phone client should assume settings must be
edited through the host, or reimplement this loopback restriction deliberately.

**Models section slots with no shipped registrant**: `settings.models.provider-card` (keyed) and
`settings.models.footer` (list) are declared and dispatched but only tests register them, so those areas
render nothing in the shipped web bundle.

## 7. Keyboard and touch affordances

### 7.0 Cross-cutting facts

* The whole client has exactly **three `onContextMenu` handlers**: `ui-dockkit/src/components/TabPanel.tsx:318`,
  `ui-primitives/src/JsonTree.tsx:144`, `ui-trajectory/src/client/TrajectoryTimeline.tsx:607`. No custom
  `contextmenu` listeners.
* **Zero `touchstart`/`touchmove`/`touchend`/`onTouch*` handlers exist.** Custom gestures use Pointer Events
  (which do cover touch) plus mouse events.
* Only **five `@media (pointer: coarse)` blocks** exist, all for attachment-remove affordances:
  `ui-attachment/src/client/{AttachmentRail,FileCard,ComposerAttachments}.module.css`,
  `ui-deliverables/src/client/Deliverables.module.css:35`. Only **two `@media (hover: …)` blocks**:
  `ui-chat/src/client/chat/MessageIconActions.module.css:36` and
  `ui-conversation/src/client/skeleton/HeroShell.module.css:115`. **Everything else `:hover` is
  unconditional** — on touch that yields only unreliable sticky-hover.
* **There is no global keyboard-shortcut system and no command palette.** `metaKey` appears only in the
  composer keymap. The only app-wide keyboard convention is Escape-to-close (Modal, Menu, ContextMeter,
  SettingsRoot, ImageLightbox, stat dialog), and every one of those also has a tap path.

### 7.1 Hover-only controls that have NO touch equivalent (must be fixed in a port)

Ordered by severity. For each: the selector/code, the action it hides, and the consequence.

1. **Session/workspace row `…` and `+` actions** — `ui-workspace/src/client/rows/Rows.module.css:233-245`:
   `.rowActions { display:none }`, shown only by `.projectRow:hover`, `.sessionRow:hover`, or `.menuOpen`.
   The ellipsis `Menu` anchor and the workspace `+` live inside `.rowActions`
   (`Rows.tsx:158-194` workspace, `:475-500` session). `display:none` removes them from layout **and tab
   order**; there is no `:focus-within` rule; `.menuOpen` can only be set by tapping the hidden button
   (chicken-and-egg). **Lost on touch: Rename session, Fork session, Archive session, Rename workspace,
   Delete workspace, New session in this workspace.** This is the single most important gap.
2. **Manual reordering is HTML5 drag-and-drop only** — `Rows.tsx:139-147` (workspace
   `draggable`, `onDragStart`) and `:435-458` (session), plus the group drop target
   `WorkspaceBrowser.tsx:390-402`. No up/down buttons, no move menu, no keyboard. ViewOptionsMenu exposes
   `orderBy.manual` but offers no way to perform an order. Touch generates no HTML5 drag events, so manual
   ordering is a **dead end**.
3. **dockkit tab strip cannot be panned by touch** — `ui-dockkit/src/components/dockkit.module.css:173-184`:
   `.stripTabs { overflow-x:auto; scrollbar-width:none; touch-action:none; }`; the comment states
   `touch-action:none` is deliberate so a press-and-move is a tab drag. There is no `onWheel` handler and no
   scroll buttons. **Tabs past the visible width are unreachable on a touch-only device.** (`.tabStrip` and
   `.divider` also set `touch-action:none`.)
4. **Trajectory timeline: pan is right-button-only, zoom is wheel-only** —
   `ui-trajectory/src/client/TrajectoryTimeline.tsx:432-447`: `if (event.button === 2) { panRef… }` and
   `if (event.button !== 0) return`; `.track { overflow:hidden; cursor:crosshair; touch-action:none }`
   (`.module.css:53-58`); zoom only via `onWheel` (`:358-386`). Touch pointer events report `button === 0`,
   so once zoomed a touch user cannot pan the window at all.
5. **JSON tree per-row copy is invisible and untappable** — `ui-primitives/src/JsonTree.module.css:233-262`:
   `.copySlot { opacity:0; pointer-events:none }`, revealed by `.row:hover…` / `[data-json-copy-active]` /
   `.copySlot:focus-within`; reveal state is set only from mouse events (`JsonTree.tsx:544-547`, `:738-743`).
   The full copy menu (copy value / copy JSON / copy path / pretty / compact) opens only via
   `onContextMenu` (`:144-148`). The locale even says `'{action}; right-click for copy options'`
   (`locale/src/locales/en.ts:16`). (Expanded strings force the copy slot visible.)
6. **Sidebar / right-panel resize is an invisible 8px drag-only strip** —
   `ui-layout/src/client/AppFrame.module.css:44-58`: `.handle { position:absolute; width:8px;
   cursor:col-resize; touch-action:none }`, with the comment "No handle draws a visible pill". No
   double-click reset, no settings alternative. 8px is far below Android's 48dp target.
7. **Transcript width handles: hover-only glow and no `touch-action`** —
   `ui-conversation/src/client/skeleton/ConversationRoot.module.css:224-245` (`.widthHandle`,
   `cursor:col-resize`, **no `touch-action`**) and `:255-283` (`.widthHandle::after { opacity:0 }` revealed
   by `:hover` / `[data-dragging]`). A finger pan can be claimed by the scroller and cancel the drag. Width
   can also collapse to 0 on narrow columns. No tap/keyboard alternative.
8. **ToolRow / SkillRow "Inspect" pill is `opacity:0` until hover** —
   `ui-tool/src/client/tool/components/ToolRow.module.css:185-191` (also
   `toolviews/bash-sample.module.css:228-235`, `ui-skill/src/client/SkillRow.module.css:179-186`). No
   `pointer-events:none`, so it hit-tests **invisibly** (accidental taps) yet is undiscoverable;
   `:focus-visible` requires a keyboard.
9. **Wide markdown tables clipped until hover / `:focus-visible`** —
   `ui-primitives/src/markdown/MarkdownText.module.css:206-215`: `.md-table-wide { overflow-x:hidden }`,
   switched to `overflow-x:scroll` only on `:hover` / `:focus-visible`. Tap-initiated focus does not match
   `:focus-visible`, so on touch a 4+ column table cannot be panned.
10. **Row HoverCards are the only way to read full titles/paths and Copy** —
    `Rows.tsx:200-214` (workspace; `copyText={row.cwd}`) and `:504-513` (session; `copyText={row.title}`);
    ellipsized `.title`/`.meta` have no `title=`. `HoverCard.tsx` opens only after an `onPointerEnter` dwell
    (default 500 ms) and `onPointerDownCapture` closes it immediately — **a touch tap actively prevents it**.
11. **Terminal tab rename is double-click only** — `ui-sidebar-terminal/src/client/TerminalTitle.tsx:21-32`
    binds `dblclick` on the closest `[data-dockkit-tab]`/`[data-dockkit-float-grip]`; commits on blur/Enter,
    cancels on Escape. No single-tap or menu alternative.
12. **ModelSelect: no on-screen "back" and a hidden model name at phone width** —
    `ui-model-selection/src/client/ModelSelect.tsx:182-189`: only `Escape` returns from a drilled pane to the
    root; `@container (max-width:360px) { .triggerLabel, .triggerEffort { display:none } }`
    (`ModelSelect.module.css:77-86`) leaves only `title=` to reveal the active model.
13. **Directory browser: committing a typed path is Enter-only** —
    `ui-directory-picker-browse/src/client/DirectoryBrowser.tsx:902-921` navigates only on Enter; tapping away
    cancels (`onBlur`). The footer Open button is `disabled` while the draft is pending.
14. **dockkit tab close / divider / float resize: hover-revealed or tiny** —
    `.tabClose { opacity:0; pointer-events:none }` shown by `.tab:hover`, `.tab:focus-within`,
    `.tabActive .tabClose` (so the active tab's close *is* visible on touch; a non-active tab must be tapped
    first), 20×20px; right-click opens `TabMenu` whose only built-in item is Close;
    `.floatResize` 20×20 `touch-action:none` with `::after { opacity:0 }` shown only on hover/`:active`;
    `.divider` hit target 8px with hover-only visual; trajectory `.detailsResizeHandle` 8px with keyboard
    ArrowLeft/Right + double-click reset.
15. **Hover-only truncation reveals via native `title`** (no touch equivalent): job rows
    (`ui-jobs/src/client/JobListAction.tsx:167-171`), trajectory table cells
    (`ui-trajectory/src/client/TrajectoryTable.tsx:2829,2835-2840`), chat attachment names
    (`ui-chat/src/client/chat/MessageItem.tsx:203`), JSON tree, Switch, user text, markdown, katex,
    slash-menu item names/aliases/descriptions (`ui-input-trigger/src/client/MenuView.module.css:100-129`).
    Notably the header breadcrumb `.crumb` is ellipsized at 220px with **no `title` at all**
    (`ConversationSession.tsx:87-104`) — a long session title is simply unreadable on touch.
16. **Hover-only informational popups** (action remains tappable): TurnNavigator tick preview
    (`ui-chat/src/client/chat/TurnNavigator.tsx:133-155`), agent-preset `data-tip` bubbles
    (`ui-agent-preset/.../AgentPresetSection.module.css:169-179`, `:331-338`), sidebar rail whale→panel icon
    swap (`ui-sidebar/src/client/SidebarRoot.module.css:205-218`; rail controls are icon-only with
    Tooltip-only names), the "Tab to drill" keycap hint (`MenuView.module.css:144-166`), deliverables
    hover swap of the status line for "Preview in sidebar" (`Deliverables.module.css:20-22`),
    DisclosureRow icon→chevron crossfade.
17. **HTML5 drag-and-drop file upload has no touch path** —
    `ui-attachment/src/client/drop-events.ts:27-57` document-level drag events + `DropOverlay`. Touch
    generates no drag events. A tap alternative exists outside `ui-attachment`: the hidden
    `<input type="file" multiple hidden onChange={onPickFiles}>` (`InputBar.tsx:414-421`) and the `commandUi`
    "File" action (`ui-conversation/src/client/apply.ts:212-221`) — a port must surface one of these.

### 7.2 Keyboard-only affordances (no pointer path, or pointer path only partly equivalent)

* Composer: `Enter` submits, `Shift+Enter` newlines, `Cmd/Ctrl+Enter` accelerated submit (queue vs steer);
  a Settings row chooses busy-state Enter behaviour. All have visible buttons, so touch is fine.
* Slash menu: `ArrowUp`/`ArrowDown` highlight, `Enter` pick, `Tab` pick/drill, `Escape` close — pointer
  equivalents exist (tap row). Menu is a pointer-capable listbox.
* ModelSelect: `ArrowDown`/`ArrowUp` focus cycling and `Escape` pane-back are keyboard-only (see §7.1 #12).
* Dockkit: `_undo`/`_redo` exist internally only. Splits/docking are pointer-drag only.
* Trajectory table/detail: Arrow keys resize the detail divider; JSON tree keyboard navigation exists.
* Escape-to-close is keyboard-only but each surface also closes on outside tap/visible button.
* No Cmd/Ctrl global shortcuts, no command palette, no focus-visible-revealed row actions.

### 7.3 Already touch-safe (do not "fix")

* Message copy/branch actions gate hover inside `@media (hover: hover)`
  (`MessageIconActions.module.css:36-59`), so touch sees the full action row always; aria-labels present.
* Attachment remove buttons each have an explicit `@media (pointer: coarse) { .remove { opacity:1 } }`
  override (`AttachmentRail.module.css:73-78`): "Touch surfaces have no hover to reveal the control."
* Composer send/stop are always visible with aria-labels; Enter-to-send etc. have button equivalents.
* Code-block / diff / read / search / terminal copy buttons are always visible (not hover-gated).
* `ui-deliverables`, `ui-jobs`, `ui-schedule`, `ui-message-feedback`, `ui-approval`, `ui-plan`, `ui-goal`,
  `ui-commands`, `ui-open-in-app`, `ui-sidebar-right`, `ui-settings*`: no hover-hidden **actionable** controls
  were found — only cosmetic hover tints and supplementary tooltips.

### 7.4 Caveats

* Whether Android Chrome's tap-to-sticky-hover emulation partially reveals `opacity:0` / `overflow:hidden`
  controls is browser- and version-dependent and cannot be determined from source (items 5, 9, 10 could
  partially self-heal). Verify on the real target.
* `@media (pointer: coarse)` is not identical to `hover: none`, so some mitigations may not apply to every
  Android form factor.
* All of the above is static source reading; the app was not run.

---

## 8. Highest-value missing features for a phone client (priority order)

1. **Touch session/workspace management.** The sidebar row `…` and `+` controls are `display:none` until
   `:hover`/`.menuOpen` with no `:focus-within`, so Rename/Fork/Archive session, Rename/Delete workspace and
   New-session-in-workspace are unreachable on touch **and** keyboard (`Rows.module.css:233-245`). The
   conversation header has **no** session overflow at all. A phone port must surface these as an always-visible
   (or long-press) overflow, plus a conversation-header overflow for rename/fork/archive/export.
2. **A touch-shaped right panel (Files, Terminal, file preview).** The dockkit tab strip is
   `overflow-x:auto` + `touch-action:none` with no wheel handler or scroll buttons, so tabs past the visible
   width are unreachable; tab close/divider/float-resize grips are hover-only and 8–20px; nothing is
   persisted. Replace with a full-screen sheet, a scrollable tab bar, explicit close/split actions, and
   host-side or local persistence of the open tab set.
3. **A Terminal that works with a soft keyboard.** Terminal is the headline feature for a coding client and
   the wire side is clean (`terminal/*`, `terminal/follow` over `/api/remote.mux`), but the web UI offers no
   touch affordances: tab rename is `dblclick`-only, the shell menu is hover-driven, and cleanup/limit flows
   assume a desktop. A phone port needs an on-screen extra-keys/Ctrl/Esc row, paste, explicit rename/close,
   reconnect handling, and a visible terminal limit.
4. **Legible, searchable model + effort selection.** The web trigger hides the model/effort label below a
   360px container (icon only, revealable only via `title`), there is no on-screen back from the two-level
   drill (Escape only), and the dropdown has **no search input**. On a phone the current model is invisible
   and the drill is fiddly. Ship a labeled selector with search and explicit back navigation.
5. **Tap-to-reveal for hover/`title`-only content.** Session/workspace full titles + working directory and the
   tap-to-Copy action live only in a HoverCard that a touch `pointerdown` actively cancels; breadcrumb `.crumb`
   is ellipsized with **no** `title`; trajectory cells, job labels and attachment names use native `title`;
   JSON-tree per-row copy is `opacity:0` + `pointer-events:none` with a right-click-only menu; 4+ column
   markdown tables stay `overflow-x:hidden` until `:focus-visible`. Add long-press context menus / info
   sheets, horizontally scrollable tables, and explicit copy buttons.

**Also worth adding (just below the top five):** manual reordering of workspaces/sessions (HTML5 drag-and-drop
only today, so `orderBy.manual` is a dead end) via drag handles or move-up/down; an **Undo** snackbar for
Archive (the web has no undo — restore only exists three levels deep in Settings → Archived sessions); and a
visible File-picker action for attachments (the only touch path is the hidden file input behind the composer
`+` command menu).
