# Composer command menu, tool-call variants, sidebar row actions — exact spec

Source: read-only checkout `/home/user/bin/deepseek-harness`, client packages under `packages/client/`.
Companion reports already exist and are **not repeated** here:

* `design-system.md` — colour/font/spacing tokens.
* `chrome-ui.md` §4.9 — slash/`@` trigger detection, `commands/list`, `commands/execute`, the
  section row *names*, the popupSelect shell, `@` mention grammar, attachment-admission policy.
* `transcript-ui.md` §1.5 and §3 — tool-call tree and every tool card variant in prose.
* `chrome-ui.md` §2 — sidebar session-row verbs, icons, labels, RPCs, hover-only visibility.

**This file fills the gaps.** Each section states explicitly what is new vs. what the companion
report already covers. Nothing below is invented; every string is an EN-locale literal copied from
source. Where a behaviour is hover-gated in the web UI the touch equivalent is named.

---

## 1. The composer command menu

### 1.0 Files

| Concern | File |
| --- | --- |
| The round `+` button (composer toolbar) | `ui-conversation/src/client/skeleton/InputBar.tsx:398-421` |
| Hidden file input | `ui-conversation/src/client/skeleton/InputBar.tsx:414-421` |
| `+` click → synthetic hit | `ui-conversation/src/client/apply.ts:376-388` |
| Menu overlay component & CSS | `ui-input-trigger/src/client/MenuView.tsx`, `MenuView.module.css` |
| Menu reducer (open/close/highlight) | `ui-input-trigger/src/core/menu.ts` |
| Trigger detection core | `ui-input-trigger/src/core/detect.ts` |
| Per-session controller (track/pick/arbitrate) | `ui-input-trigger/src/client/controller.ts` |
| `/` source registry + contributions | `ui-commands/src/client/service.ts` |
| Empty-query sectioning / host faces | `ui-commands/src/client/presentation.ts`, `locales.ts` |
| Skill `/` source | `ui-skill/src/client/index.ts` |
| `@` source | `ui-reference/src/client/index.ts` |
| Overlay slot registrations | `ui-input-trigger/src/client/index.ts` (`slash-menu`, order 0); `ui-commands/src/client/index.ts` (`command-popup`, order 1) |
| popupSelect shell (different overlay) | `ui-commands/src/client/PopupSelectView.tsx` |

### 1.1 The `+` button (exact)

`InputBar.tsx:400-413`:

```tsx
<Tooltip label={t('input.commands')} side="top" delayMs={500}>
  <button type="button" className={css.add}
    aria-label={t('input.commands')}          // EN "Add files or run commands"
    aria-haspopup="listbox"
    aria-expanded={commandMenuOpen}
    disabled={locked || toggleCommandMenu === undefined}
    onMouseDown={keepFocus} onClick={onToggleCommandMenu}>
    <IconPlusOutline16 size={14} />
  </button>
</Tooltip>
```

* `input.commands` = **"Add files or run commands"** (`ui-conversation/src/client/locales.ts:182`).
* CSS `.add` (`InputBar.module.css:297-318`): 28×28 px, `border-radius:999px`, grid-centred,
  background `--dsw-specific-selector`, icon 14 px in a 28 px circle; disabled → `opacity:.5`.
* `keepFocus` calls `preventDefault` at `mousedown` so the editor keeps focus.
* `commandMenuOpen` is `useMenuLauncher(source => source === 'command')` — i.e. only true when the
  menu was opened by this programmatic launcher, not by typing `/`.

`onToggleCommandMenu` (`InputBar.tsx:260-262`) → `toggleCommandMenu(keyboard.caretSpan())` →
`apply.ts:378-387`:

```ts
shell.dismissPopup()                       // closes an open popupSelect overlay first
const snapshot = shell.snapshot
inputTriggers.toggleSource('command', {
  trigger: '/', query: '', quoted: false,
  position: snapshot.draft.slice(0, selection.start).trim() === '' ? 'leading' : 'inline',
  span: { ...selection, draftRev: snapshot.draftRev },
})
```

`InputTriggerController.toggleSource` (`controller.ts:145-163`) **seeds the menu with exactly one
source** (the `command` source) and fetches it. Toggling again while that source's launcher is open
dismisses.

### 1.2 The menu component, anchoring and layout

`MenuView` renders into the slot `conversation.input.overlay`, which `InputBar` places inside
`div.overlayAnchor` (`InputBar.tsx:362-364`; CSS `InputBar.module.css:116-120`:
`position:absolute; inset:0 0 auto; height:0`). So the anchor is a zero-height box pinned to the
**top edge of the composer card**, top-left aligned, exactly card-width.

`MenuView.module.css:1-31` (`.menu`):

* `position:absolute; bottom: calc(100% + 4px); left:0; right:0` → the menu sits **4 px above the
  card's top edge**, full card width, `z-index:100`.
* `border-radius:20px`, `padding:4px`, `background: var(--dsw-specific-menu)`, shadow
  `--dsw-elevation-prominent`, no border.
* `max-height:400px` in CSS, but the inline style overrides it with
  `useAnchoredMaxHeight(listRef, 400, state)` = `min(400, elementBottom − 12)` (`MARGIN = 12`,
  `ui-primitives/src/useAnchoredMaxHeight.ts`). It re-measures on every store update, `resize` and
  capture-phase `scroll`, so the menu shrinks as the composer grows.
* `.viewport` is the only scroller: `display:flex; flex-direction:column; overflow-y:auto;
  min-height:0`. It carries `role="listbox"`, `aria-label={t('suggestions.aria')}` = **"Trigger
  suggestions"**, and `aria-activedescendant` = the highlighted row's DOM id
  `dsh-slash-option-<source>-<index>`.
* When the viewport is not scrolled to the bottom, `.menu[data-overflow-below]::after` paints a
  16 px gradient fade at the bottom (`right:14px; bottom:4px; left:4px`).

**There is no header and no search field in the trigger menu.** `ui-commands/src/client/locales.ts`
does contain `search.placeholder` = "Search…" / `search.aria` = "Filter options", but those belong
to `PopupSelectView` (the `/model`, `/permission` overlay), which is a **different** component and
slot ordering. The trigger menu's query is the text typed in the composer draft after the trigger.

Row layout (`MenuView.module.css:53-129`), pill button, `width:100%`:

```
[ 16px icon box, colour label-tertiary ] gap 8
[ label ?? name  (max-width:40%, ellipsis) ]
[ alias = raw name, only when label.toLowerCase() !== name.toLowerCase() (max-width:20%, tertiary) ]
[ description, flex:1, min-width:0, text-align:right, tertiary ]
[ optional .trailing: drill hint + Tab keycap + chevron, only on the active row ]
```

* `.item` `min-height:40px; padding:8px 10px; border-radius:10px; font-size:14px; line-height:22px;
  color: var(--dsw-alias-label-primary)`. The highlight is a single shared style `.item.active`
  (`background: var(--dsw-alias-interactive-bg-hover)`); there is **no separate `:hover` tint** —
  `mousemove` writes the same highlight store.
* `.sectionTitle` (per-row section heading): `min-height:26px; padding:6px 10px 2px;
  font-size:12px; font-weight:500; line-height:18px; colour label-tertiary`; a non-first section
  gets `margin-top:4px`.
* `.groupTitle` (source heading, when the group has no sections): `padding:8px 10px; font-size:12px;
  line-height:16px; colour label-tertiary`.
* Pending group with no items yet: two `.skeletonRow`s (40 px cells) with a 20 px `bg-skeleton` bar
  (`width:32%` / `48%`) pulsing `dsh-menu-skeleton 2s`; wrapper `role="status"
  aria-label={t('loading')}` = **"Loading…"**.
* Drill affordance (directories in the `@` menu only): `.trailing` with hidden-until-active
  `drill.hint` = **"Browse folder"**, `kbd` `drill.key` = **"Tab"**, and a 20×20 chevron whose
  `aria-label` is `drill.aria` = **"Browse folder"**.
* Crumbs (only for a drilled `@` listing): a pinned `.crumbs` `<nav aria-label={t('crumbs.aria')}>`
  = **"Folder navigation"**, chips separated by `IconChevronRightOutline14`; current crumb is a
  disabled label.
* Dismiss: a capture-phase `pointerdown` anywhere outside the menu **and** outside
  `[data-composer-card]` calls `onDismiss()`; clicks on the textarea or the composer's bottom bar do
  not close it. Rows use `mousedown` with `preventDefault` so focus never leaves the textarea.

### 1.3 The exact entry list — `+` shows MORE than `commands/list`

`commands/list` for a live session returns 6 host descriptors (`compact`, `export`, `feedback`,
`goal`, `permission`, `plan`; see `ui-commands/tests/service.client.spec.ts`). The `+` menu shows
**8 rows**, because `CommandUiRuntime.candidates()` merges the host catalog with **client
contributions** (`ui-commands/src/client/service.ts:208-234`).

Contributions registered in `packages/client`:

| name | label (EN) | description | icon | `ui` | source |
| --- | --- | --- | --- | --- | --- |
| `file` | **"File"** (`input.file`) | — | `IconPaperclipOutline16` | `action` → `inputHub.pickFiles(sessionId)` | `ui-conversation/src/client/apply.ts:212-221` |
| `model` | **"Model"** | **"Select the model for this conversation"** | `IconDataOutline16` | `popupSelect` | `ui-model-selection/src/client/index.ts:144-170` |

Decorations (popup/action hung on an existing host command's bare invocation):

| name | UI | effect of bare pick | source |
| --- | --- | --- | --- |
| `feedback` | `action` | opens the session feedback dialog (`/feedback <text>` still goes to the host) | `ui-message-feedback/src/client/index.ts:113-119` |
| `permission` | `popupSelect` | permission-preset picker (with full-access risk gate) | `ui-permission-presets/src/client/index.ts:175-197` |

Exact `+` menu order for an empty query (`sectionRows`, `presentation.ts:19-22,74-86`):

```
Add
  File        (contribution, paperclip)         -> native file dialog
  Goal        (host, goal       icon)           -> claim, accepts attachments
  Plan        (host, plan       icon)           -> claim, accepts attachments
  Feedback    (host, pencil-plane icon)         -> feedback dialog
Commands
  Compact     (host, compact    icon)           -> commands/execute "/compact"
  Permission  (host, shield     icon)           -> permission popup
  Model       (contribution, data icon)         -> model popup
  Export      (host, download   icon)           -> commands/execute "/export"
```

Host rows carry the localized face from `HOST_FACES` (`presentation.ts:41-48`):

| name | label | description | icon |
| --- | --- | --- | --- |
| `goal` | **Goal** | **Set or view the goal for a long-running task** | `IconGoalOutline16` |
| `plan` | **Plan** | **Enter or leave plan mode** | `IconPlanOutline14` |
| `feedback` | **Feedback** | **Record feedback about this session** | `IconPaperPlaneOutline14` |
| `compact` | **Compact** | **Compact older conversation history** | `IconCompactOutline16` |
| `permission` | **Permission** | **Switch the permission preset (sandbox mode + approval policy)** | `IconShieldOutline16` |
| `export` | **Export** | **Download this Session log as a ZIP archive** | `IconDownloadOutline16` |

None of these labels differ from their names only by case, so **no alias column is drawn** for the
shipped rows (alias only appears for a localized label whose lowercase differs from the name).

**What does NOT exist** (verified by grep over `packages/client`): no "Attach image", no "Choose
workspace", no subagent picker, no model picker row beyond `/model`, no "New session" row. A
subagent session's host catalog is empty and `file` is unavailable (`canPickFiles` false); `model`
is unavailable for addressed subagent sessions.

### 1.4 Filtering, keyboard, empty state

* Filtering is `rankByName(visible, query)` (`ui-primitives/src/rank-by-name.ts`): case-insensitive
  ordered subsequence over `name` and optional `label`; prefix hits first, then alignment score,
  then source order. `req.query` is the text between the trigger and the caret.
* **Argument-taking rows vanish from an inline menu.** `candidates()` ends with
  `rows.filter(c => req.position === 'leading' || c.hint === undefined)`. `/goal` and `/plan` carry
  an `input.hint`, so they are hidden when the trigger is not at the start of the draft (e.g.
  `"hi /go"`), but present for the `+` button and for a leading `/`.
* Keyboard (registered on the Lexical editor at `COMMAND_PRIORITY_CRITICAL`,
  `ui-conversation/src/client/input/editor/keymap.ts`): `ArrowUp`/`ArrowDown` move the shared
  highlight with wrap-around over *ready* items only; `Enter` picks the highlight when its group is
  ready (a pending refinement consumes the key as a no-op, it does not fall through to submit);
  `Tab` picks/drills the highlight, else falls through to native focus traversal; `Escape` first
  dismisses an open popupSelect, then closes the trigger menu; `Space` runs `matchSpace`.
* **No-match / empty state: the menu simply closes.** `menuReduce` auto-closes when every group is
  `ready` with 0 items (`core/menu.ts:78-79,118`), and `MenuView` renders `null` for an empty ready
  group. There is **no "No results" string** in the trigger menu. (The popupSelect overlay does have
  one: `status.empty` = "No options".)
* Stale-while-revalidate: refining the query keeps the old rows on screen (skeleton only if the
  group has none) until the new fetch settles; picks are fenced off while `pending`.

### 1.5 Typing `/` vs. the `+` button vs. `@`

All three render the **same `MenuView`**; the difference is the roster and the trigger source.

* **`+` button** — synthetic hit, roster = `[command]` only. Query `''`. No Skills group.
* **Typing `/`** — `detectTrigger` runs on every editor commit (`facade.ts:186-191`,
  `guardOf(phase)`): roster = **all sources registered for `'/'` in `order` sequence** =
  `command` (default order 0) **plus** `skill` (`ui-skill/src/client/index.ts:142`, `order: 2`).
  So typing `/` shows the 8 command rows *and* a **"Skills"** group (`slash.menu:skill` =
  "Skills") listing that session's `/skills/list` catalog (icon-less rows: name + description;
  model-non-invocable skills prefix the description with `menu.userOnly` = **"user-only"**). Skills
  picks insert the literal text `` `/<name> ` `` and do not claim a host command.
  For the empty query the command group shows the Add/Commands section headings *instead of* its
  group title; once a query is typed the sections drop and the group title **"Commands"** shows.
* **Typing `@`** — a **different source** (`reference`, `showGroupTitle:false`) and a different
  grammar. `activeAtToken` matches `@path` or `@"quoted path"` (the quote may span whitespace), so
  `@` is tried *before* `/` at the caret. Only the reference group renders, with per-row sections
  `section.files` = **"Files & folders"** and `section.sessions` = **"Sessions"**. Directory rows
  are `drill:true` (Tab/chevron/crumb descends); file/session rows insert a chip. A pick does not
  run an RPC — it inserts a `ReferenceInsert` (serialized to the literal mention on submit). The
  `position` filter does not hide any `@` row (they carry no `hint`).
* **Guards** (`TriggerGuard`, from `guardOf(phase)` in `facade.ts:73-79`): `plain` = `/` and `@`
  live; `claimed` (a command claim is active) = `/` fully suppressed, `@` live; `frozen`
  (`adjudicating`/`submitting`) = both suppressed and the menu force-closes. Word-boundary rule:
  a trigger opens at start-of-draft, after whitespace, or after punctuation; not after a word
  char; `/` is additionally dead in URLs (`//` and `:/` after a non-space). Inline `/` is allowed
  for typing but submit requires a **leading** `/` (`service.ts:294`).

### 1.6 Selection behaviour

Decision table in `CommandUiRuntime.dispatch()` (`service.ts:236-260`):

* **Contribution with `kind:'action'`** (`file`) → consume the span, `run(session)`; no RPC for
  `file` itself (it opens the OS picker). `feedback` → opens dialog.
* **Contribution / decoration with `kind:'popupSelect'`** (`model`, `permission`) → open that
  session's `PopupSelectView` overlay; **no claim inserted**, the trigger token is left in the
  draft until an option is chosen, then consumed.
* **Host command with `desc.input !== undefined`** (`goal`, `plan`) → returns a `CommandClaim`.
  `leadingClaim()` (`service.ts:363-373`) sets `token = "/" + claimToken(desc, t) + " "` (EN
  `"/goal "` / `"/plan "`), `hint = desc.input.hint`, and `attachments: true` when
  `desc.input.attachments === true`. The pipeline dispatches the scoped event
  `slash/input-begin-command`, which **inserts the token + trailing space into the draft** and puts
  the machine in `claimed` phase. The cursor lands after the space, so the user types the argument
  as ordinary draft text.
* **Host bare command** (`compact`, `export`) → consume the span, `runDetached()` →
  `commands/execute(sessionId, "/compact")`. Outcome is not echoed (the host logs
  `command/run`/`command/done` as a flow node); only an admission failure posts a composer notice.

**Ghost hint for claims** (`InputBar.tsx:307-321`): while phase is `claimed`/`submitting`, the
claim token matches the draft start, and everything after the token is blank, the composer renders
a CSS ghost hint after the token (custom prop `--dsh-composer-hint`). The translated per-command key
is `` `hint.${claim.name}` ``, with the goal special case
`hint.${goal && hasGoal ? 'goal.active' : 'goal'}`. EN:
`hint.goal` = **"describe the objective for a long-running task"**,
`hint.goal.active` = **"goal active — edit / pause / resume / clear"**,
`hint.plan` = **"describe your task to generate plan"**.
If no `hint.<name>` key exists the machine's own `desc.input.hint` string is shown verbatim
(e.g. the host's `[<objective>|clear|edit <objective>|pause|resume]` from
`packages/goal/command-goal/src/index.ts:195`).

**On Enter** (`machine.onEnter` → `commands.execute`, `chrome-ui.md §4.9` already has the wire
details). Exact wire signature:
`commands/execute(agentId: SessionId, line: string, submittedAttachments: readonly
CommandSubmitAttachment[], signal?)` (`packages/interaction/commands/lib/typert.remote-client.d.ts`).
A leading claim always sends the **catalog name** and the args after the token:
`` line = `/${desc.name} ` + args `` (`service.ts:363-372,386-401`). A handler `error` result keeps
the draft and attachments for correction; admission success consumes attachments.

**`input.attachments:true` (`/goal`, `/plan`)**: a claim that declares `attachments:true` is the
*only* way an argued command may carry attachments; otherwise the composer refuses the whole
submission with `notice.attachmentsUnsupported` = **"/{command} does not accept attachments;
remove them first"** (`service.ts:325-330`, `machine.ts` pre-gate). The attachments ride the same
`commands/execute` call.

### 1.7 Attachment picking

* **Only trigger found**: the `+` menu's first row, `File` (contribution label `input.file` =
  "File"), runs `inputHub.pickFiles(sessionId)` → `shell.pickFiles()` →
  `fileInputRef.current.click()`. There is **no keyboard shortcut and no dedicated paperclip
  button** — grep for `pickFiles` returns only this path (`ui-conversation/src/client/apply.ts:219`
  → `input/hub.ts:175` → `input/facade.ts:522`). Other intake paths:
  * **Paste** — `PASTE_COMMAND` collects clipboard `items` of `kind === 'file'` and calls
    `intakeFiles` (`keymap.ts:146-165`).
  * **Drag & drop** — the `conversation.input.attachments` slot (`ui-attachment`) handles the drop
    with `canAcceptDrop = subagent === null && !locked && !machineBusy && addFiles !== undefined`.
* **The hidden input** (`InputBar.tsx:414-421`):
  ```tsx
  <input ref={fileInputRef} type="file" multiple disabled={subagent !== null} hidden
         onChange={onPickFiles} />
  ```
  No `accept` attribute (any file type), `multiple`, `hidden`, disabled inside a subagent session.
  `onPickFiles` copies `e.target.files`, resets `e.target.value = ''` (so re-picking the same file
  re-fires), then `intakeFiles(picked)`.
* **Client-side intake pre-check** (`InputBar.tsx:197-219`) — only the **image** subset is checked,
  and an over-limit batch is refused **as a whole** with a `Toast` (icon `IconWarningOutline16`):
  * `maxImagesPerMessage` (projected; host default **20**) → `image.tooMany` =
    **"A message can include up to {count} images"**
  * `maxImageBytes` (default **20 MiB**) → `image.fileTooLarge` =
    **"Each image must be smaller than {size}"**
  * `maxMessageImageBytes` (default **200 MiB**) → `image.totalTooLarge` =
    **"Images exceed {size} in total; remove some and try again"**
  * `mediaTypes` = `['image/png','image/jpeg','image/webp','image/gif']`.
  The `imageLimits` projection is absent when no attachment service is composed, and then the
  client defers entirely to the host. **Generic files have no client-side size or count limit.**
  `imageSizeText()` formats bytes as `10MB` / `2.5MB` (integer → no decimal).
* **How files become prompt content** (`service.createDrafts`, `service.ts:308-327`): image MIME
  (exactly the four above) → an image draft (object-URL preview, dimension probe, bytes encoded at
  submit); every other file → a file draft whose background upload starts immediately
  (`beginFileUpload`, tracked in `fileUploads`). `InputBar` holds the Send button while any file
  attachment is not `ready` (`uploadsPending`).
  On submit, `sendSession` serializes to `{type:'image', mimeType, data…}` or
  `{type:'file', receiptId}`, then `session.prompt(content, mode, signal, requestId)` →
  wire **`session/prompt`**. Attachments sent with a command go through `commands/execute`'s third
  argument instead.
* **No client-side upload size limit for non-images** and no per-file count cap beyond the host's
  own enforcement at submit.

### 1.8 Touch recommendations (hover-only web affordances)

These were the port brief; §1.9 records what shipped against it.

* The menu itself is touch-friendly: rows are 40 px high, `mousedown` picks, scroll viewport.
  Keep the 400 px cap and the 12 px viewport margin; on Android clamp to the IME-visible area.
* No hover is required for the menu; but the composer `+` tooltip (500 ms delay) is web-only —
  expose the same `aria-label` as the content description.
* File picking: on Android use `ActivityResultContracts.OpenMultipleDocuments` and pass real MIME
  types; the web `accept` is absent, so mirror "any file".
* `@` directory drill shows its "Tab" keycap hint — on touch, render the chevron always on
  drillable rows (the trailing affordance is already visible without hover only for the active
  row; make it permanent on touch).

### 1.9 Shipped client (app side)

The port exists; this is what the shipped client does and where it departs from
the spec above, so the touch notes are not read as pending work. Code:
`app/src/main/java/uk/xa0/dsh/ui/composer/ComposerTriggers.kt`,
`ui/components/CommandMenu.kt`, `ui/ChatScreen.kt` (`onPickCommand`,
`rememberComposerTriggers`, `ComposerPopups`), `DshViewModel.kt`
(`refreshCommands`, `executeCommand`, `searchReferences`).

* **Caret-accurate triggers, no guard tiers.** `detectTriggerToken` ports
  `detect.ts` + `file-reference/grammar.ts` over `(draft, caret)`, so the menu
  follows the caret rather than the draft's end; every hit is the web's `plain`
  tier (there is no claim/frozen state), and a pick replaces the recorded span,
  never the draft end. One client-only guard: an `@` token longer than 96 chars
  is dropped.
* **`+` and typed `/` share one roster.** `composerCommandEntries(host)` is
  `commands/list` plus the client's `file` and `model` rows, sectioned `Add`
  (`file, goal, plan, feedback`) then `Commands` (`compact, permission, model,
  export`), with unknown host rows appended in catalog order. The `+` keeps the
  web's accessible name. No Skills group (`skills/list` is never called), no
  alias column, no skeleton rows, no "No results" string, no keyboard highlight:
  a 300dp scroll column with tap picks, in the same IME-padded content column as
  the composer (`ui/ChatScreen.kt:464`). `+` and `/` are therefore the *same*
  menu here: §4's Skills group and its hiding of the argument-taking rows in a
  non-leading menu do not exist in this client, because `CommandEntry` carries no
  `hint` and no skills source is composed.
* **Picks.** `file` → `ActivityResultContracts.GetContent("*/*")` (a single pick,
  not `OpenMultipleDocuments`); `model`/`permission` → the client's sheets; a row
  with `input` → the literal `/<name> ` inserted at the token; any other host row
  → `commands/execute(agentId, "/<name>", [])`. There is no claim phase and no
  ghost hint.
* **`@` menu.** `ReferenceMenu` is a flat two-section list (Files & folders,
  Sessions) that inserts the candidate's own mention — `@path`, `@"path with
  spaces"`, or the host's `@[label](dsh-session:…)` — plus a space; directory
  rows are not drillable (no Tab keycap, chevron or crumbs). `fileReferences/list`
  publishes the moment it answers; `sessionReferenceResolver/candidates` runs
  concurrently and merges on arrival, generation-guarded
  (`DshViewModel.kt:1471-1486`).
* **Both seats.** The hero and a live session run the same derivation and render
  both popups (`ui/ChatScreen.kt:1866-1917,2019-2030`).

---

## 2. Tool-call card variants — corrections and additions to `transcript-ui.md §3`

`transcript-ui.md §3` (esp. §3.1–3.9) already documents dispatch, collapsed summaries, expanded
bodies, caps and copy for bash/read/write/edit/diff/grep/glob/web/todo/generic, the leading-icon
*semantics*, the Inspect pill and the running sweep. **Read it first.** Below are the facts it does
not state, plus corrections. All line references are to that report.

### 2.1 At-a-glance variant index (tool name → component → collapsed title)

| Wire tool name(s) | Registered component | Collapsed title (EN) | Source file |
| --- | --- | --- | --- |
| `bash` | `BashRow` | **"Bash"** | `ui-tool/src/client/tool/toolviews/bash-sample.tsx` |
| `pwsh` | `GenericToolCard`, variant `bash` | **"Pwsh"** | `models/tool-call-model.ts` (`TOOL_TITLE_KEYS`) |
| `read` | `ReadRow` | **"Read"** | `toolviews/read-row.tsx` |
| `read_image` | `ReadImageRow` | **"Read image"** | `toolviews/read-image-row.tsx` |
| `edit`, `write` | `FileMutationRow` | **"Edit"** / **"Write"** | `toolviews/file-mutation-row.tsx` |
| `grep`, `glob` | `SearchRow` | **"Grep"** / **"Glob"** | `toolviews/search-row.tsx` |
| `web_search`, `web_fetch` | `WebRow` | **"Search"** / **"Fetch"** | `toolviews/web-row.tsx` |
| `todo_write` | `TodoRow` | **"Update to-do list"** | `toolviews/todo-row.tsx` |
| `ask_user_question` | `AskQuestionRow` | question row (own card) | `toolviews/ask-question-row.tsx` |
| `skill` | `SkillRow` | accent row | `ui-skill/src/client/index.ts` |
| `present` | `PresentRow` | deliverables strip | `ui-deliverables/src/client/index.ts` |
| `cordis_define` | `CordisDefineRow` | **"Run Cordis Plugin"** | `extensions/ui-cordis` |
| `cordis_run` | `CordisRunRow` | **"Run Cordis Plugin"** | `extensions/ui-cordis` |
| `cordis_stop`/`cordis_undefine` | `CordisActionRow` | **"Stop Cordis Plugin"** / **"Remove Cordis Plugin"** | `extensions/ui-cordis` |
| `subagent`, `subagent_*` | **no keyed card** → `GenericToolCard` | `subagent · <first arg>` | fallback |
| anything else | `GenericToolCard` | **"Tool call"** | `toolviews/GenericToolCard.tsx` |

There is **no `task` tool** in `packages` (grep for `name: 'task'` returns nothing) and **no `plan`
tool** — plan mode is a composer mode, not a tool call. `todo_write` is the only to-do tool view.

### 2.2 Corrections

1. **The bash prompt is NOT `$ cwd command`.** `TerminalBlock` renders, on the first prompt row:
   `[StateDot] [cwd label] [command]`. The cwd label is `promptLabel(cwd, home)` = `~` when `cwd`
   equals home, else the **last path segment** (never the full path); the literal `$` appears only
   (a) on continuation rows (`index > 0`) of a multi-line command or (b) when `cwd` is undefined
   (`TerminalBlock.tsx:76-81,203-221`). CSS: `.cwd` is `label-tertiary`, `.command` is
   `label-primary`, `white-space:pre`, ellipsis (`TerminalBlock.module.css:78-122`).
2. **Leading-icon components** (`GenericToolCard.tsx:16-24`): `search` → `IconSearchOutline16`,
   `read` → `IconBrowseOutline16`, `bash` → `IconApiOutline14`, `write`/`edit` →
   `IconEditOutline16`, `code` → `IconCodeOutline16`, `others` → `IconSparkle16`; every glyph is
   rendered `size={14}` inside a 16 px box. `transcript-ui.md` names only the semantic families.
3. **`read` collapsed summary** keeps the file path as an openable dotted-underline link and never
   expands an args body; `read_image` is classified `read` and shares the browse icon but its own
   title, and its card is `ReadImageRow` (image element + `loadImage`), not `ReadBlock`.
4. **`code` variant** = `run_code` → title **"Code"**, `bodyRaw` is `args.code` rendered through
   `CodeBlock lang="typescript"` in a 260 px scroller (not the IN/OUT card). `transcript-ui.md §3.1`
   covers it, but it is absent from the task's variant list — include it.
5. **Bash background calls** (`run_in_background:true`) return a null terminal card and stay
   permanently collapsed (no chevron), even for an error/`stopped` state — the summary line is the
   whole row.
6. **Subagent/task nesting.** `subagent`/`subagent_*` calls get the generic "Tool call" card and
   are counted in `subagentCount` (not `toolCallCount`). The recursive indent tree
   (`ToolCallBranch` → `div.subCalls[data-subcalls]`) is **not** a subagent tree: it renders
   `tool/ptc-dispatch*` sub-calls of any root (e.g. `cordis_run`), recursively, at every level,
   through the same keyed slot. Indent is `margin:4px 0 2px 22px; padding-left:8px;
   border-left:.5px solid var(--dsw-alias-border-l2); gap:4px` (`ToolCallTree.module.css`).
   Cycle/`MAX_DEPTH = 256` guard lives in `ui-chat/src/client/conversation-nodes/tool.ts:18`; a
   child deeper than the cap is truncated to `subCalls: []`, and a repeated `callId` is dropped.
   There is no dedicated subagent card and no link out to the child session from the transcript row.
7. **`todo_write` is the only structured to-do card.** There is no `plan` tool view; the plan panel
   (`TodoPanel.tsx`) is a composer-side dock, not a transcript row (`transcript-ui.md §3.7` says
   this; restated because the task groups "todo_write / plan").
8. **Running sweep / dots.** Running rows (and the standalone bash card, Think row) paint
   `.row::after` — a 300 px gradient band, `animation: dsh-tool-row-sweep 2.6s ease-out infinite`
   (`left:-300px → 100%`, 90% hold, `ToolRow.module.css:19-45`). The running `StateDot` uses
   `data-state="ongoing"` with `animation: dsh-state-dot-chase 1s infinite`, colour
   `--dsh-state-ongoing` (`var(--dsw-static-deepseek-450)`); `done` green / `error` red / `warning`
   amber / `idle` grey (`ui-primitives/src/StateDot.tsx`, `.module.css`). A bash card's dot is
   absolute in a 30 px left gutter on row 0 only; its text label ("Running"/"Failed"/"Done") is
   visually hidden.
9. **The Inspect pill is hover-gated** (`opacity:0` until row `:hover` or pill `:focus-visible`,
   100 ms). It is inside the expanded body only and opens the **trajectory** view
   (`openView('trajectory', callId)`), not an in-place expansion. Touch: make it permanently visible
   in the expanded body; do not hide it behind long-press. **App:** there is no per-row pill; the
   trajectory is the conversation header's second view tab, opened from `ui/ChatScreen.kt:520-534`,
   and the per-record details live in that screen's inline disclosure
   (`ui/TrajectoryScreen.kt:497-501`).

### 2.3 Real copy inventory used by the cards (EN)

Titles: `tool.title.search` "Search", `read` "Read", `bash` "Bash", `write` "Write", `edit` "Edit",
`code` "Code", `generic` "Tool call", `readImage` "Read image", `pwsh` "Pwsh", `grep` "Grep",
`glob` "Glob", `webSearch` "Search", `webFetch` "Fetch", `inspect` "Inspect",
`runCordis` "Run Cordis Plugin", `stopCordis` "Stop Cordis Plugin",
`removeCordis` "Remove Cordis Plugin" (`ui-conversation/src/client/locales.ts:258-274`).
IO card: `row.input` **"IN"**, `row.output` **"OUT"**, `row.inspect` **"Inspect"** (lines 255-257).
Terminal: `terminal.running` "Running", `failed` "Failed", `done` "Done",
`exitCode` "exit code {code}", `signal` "signal {signal}", `noOutput` "No output",
`expandRest` "… {n} more lines", `sendInput` "(send input)", `session` "Terminal {sessionId}".
Diff: `diff.files.one/other` "{count} file"/"{count} files", `diff.expandRest` "… {count} more
lines". Read: `read.window` "Showing {shown} of {total} lines", `read.expandRest` "… {count} more
lines". Search: `search.matches` "{shown} matches · {files} files", `search.paths` "{shown} paths",
`search.noResults` "No results", `search.expandRest` "… {count} more lines". Web: `web.noResults`
"No results found", `web.sourcesTruncated` "Source list truncated", `web.http` "HTTP",
`web.contentTruncated` "Content truncated". To-do: `todo.rowTitle` "Update to-do list",
`todo.completed` "{done}/{total} completed". Running: `row.running` "Running", `row.failed`
"Failed", `row.stopped` "Stopped", `details.running` "Running…". Auto-review denial:
`tool.autoReviewRejected` "Rejected by Auto review", `tool.autoReviewNotExecuted` "Tool was not
executed. Reason: {reason}", `tool.autoReviewReasonFallback` "Auto review did not authorize this
action". Caps: `CHAT_READ_MAX_LINES = 8`, `CHAT_SEARCH_MAX_LINES = 8`, `CHAT_DIFF_MAX_LINES = 9`
(chat row); the primitive defaults are 16/…/… and are kept by the details panel.

---

## 3. Sidebar session row actions — gaps beyond `chrome-ui.md §2`

`chrome-ui.md §2` already has verb list, icons, EN labels, the RPC table, the hover-only rule, the
rename dialog's existence and the "no undo" finding. Additions:

### 3.1 Overflow menu structure

`SessionNodeItem` (`ui-workspace/src/client/rows/Rows.tsx:417-501`) wraps an
`IconEllipsisOutline16` button in the shared `Menu` primitive (`ui-primitives/src/Menu.tsx`):

* Items: `rename` (label **"Rename"**, `IconEditOutline16`), `fork` (label **"Fork session"**,
  `IconBranchOutline16`), `archive` (label **"Archive session"**, `IconArchiveOutline20 size={16}`).
* `Menu` renders `<div role="menu">` → `.viewport` → per item `<button role="menuitem">` with a
  16 px icon slot (`.itemIcon`), the label, and a trailing check only if selected (not used here).
  Submenus exist in the primitive but none of these three uses one.
* The row passes `portal` and `closeOnPointerLeave`: the list is portaled to `document.body` and
  fixed-positioned from the anchor rect with a 12 px viewport margin
  (`Menu.tsx:118-160`); crossing the 4 px gap back to the trigger does not close it
  (`usePointerGrace`). Outside `pointerdown` closes it. Item click calls `onSelect`, which closes
  the menu first and then runs the verb (`Rows.tsx:484-491`).
* The anchor button's accessible name is `actions.session.aria` = **"Session actions for {name}"**
  (`ui-workspace/src/client/locales.ts:121`); it has no tooltip.
* The menu is opened by clicking that button. **The button lives inside `.rowActions`, which is
  `display:none` until the row is hovered** (`chrome-ui.md §2.3`), so on touch the overflow menu is
  unreachable. Touch equivalent: always render the ⋮ (or show on long-press), and keep the menu
  open state pinned via the row's `menuOpen` class (the web UI already pins the row fill with
  `.sessionRow.menuOpen`).

### 3.2 What happens after each verb (refresh / navigation)

* **Rename** — `renameSession` (`ui-workspace/src/client/index.ts:109-116`) resolves
  `sessions.binding(sessionId)?.session` and calls `session.rename(title)` → wire
  **`session/rename`** (`{sessionId, title}`). The Session face applies the reply to its local
  projection (`sessions/session.ts:357-363`: `projections.apply('title', value.title, seq)`), so
  the sidebar row re-renders from the shared list projection — **no list refetch**. If the binding
  is missing it throws `unknown session "<id>"`, surfaced in the dialog's `role="alert"` error line.
* **Fork** — `forkSession` → `navigation.ts:153-157`:
  ```ts
  const childId = await this.sessions.fork({ sessionId, increaseTitle: true })
  if (!navigation.aborted) this.openSession(childId)
  ```
  `sessions.fork` (`api/session-controller/src/client/sessions/service.ts:428-455`) calls the host
  **`session/fork`**, then `projectList()` (the child appears in the sidebar), then renames the
  child with `increasedForkTitle(sourceTitle)` via **`session/rename`**, then returns `childId`.
  `openSession` selects the child (`this.sessions.open(childId)`) and closes the right panel
  (`layout.selectPanel(null)`). So **a fork creates a new child session, gives it the incremented
  title and navigates to it**. Errors are swallowed (`catch(() => {})`) and the current selection
  stays. `increasedForkTitle` (`service.ts:161-171`): `"X(2)" → "X(3)"`, full-width
  `"X（2）" → "X（3）"`, otherwise appends `" (1)"` (ASCII, with a leading space).
* **Archive** — `archiveSession` (`index.ts:128`) → `navigation.ts:180-182` →
  **`workspace/archiveSession`**. Dialog-free; the row disappears only when the archive-set echo
  arrives (RPC reply or `workspace/follow`); failure is a non-fatal
  `console.warn('session archive rejected:', reason)`. No refetch, no toast, no undo.
* The header's branch action (`ui-chat/src/client/apply.ts:159`) uses the same
  `sessions.fork({sessionId, atSeq, increaseTitle:true})` and navigates to the child.

### 3.3 Confirmation dialogs (exact)

* **Rename** is the only one of the three verbs with a dialog (`WorkspaceBrowser.tsx:1276-1307`):
  `Modal` title `rename.session.title` = **"Rename session"**; a prefilled `input`
  (`aria-label = field.sessionName` = **"Session name"**, `autoFocus`, selects all on focus) with
  `Enter` (outside IME composition) confirming; footer buttons `cancel` = **"Cancel"**
  (outline) and `rename` = **"Rename"** (primary). Confirm is disabled while empty, while pending,
  or when the target is null. **An unchanged title is allowed** (confirming the automatic title
  pins it) and there is **no duplicate-title check**; a host error shows a `role="alert"` line.
* **Fork**: no dialog, no confirmation.
* **Archive**: no dialog, no confirmation.

### 3.4 Touch remediation summary

The shipped row menu is §3.5.

| Web behaviour | Touch equivalent |
| --- | --- |
| ⋮ hidden until row hover (`display:none`) | always visible, or long-press |
| Menu `closeOnPointerLeave` grace | irrelevant on touch; close on outside tap/back |
| `HoverCard` preview + one-click title copy | optional long-press preview; a copy item in the overflow |
| Folder→chevron swap on hover | static folder glyph or disclosure chevron |
| Timestamp hidden on hover in favour of actions | stack the timestamp and ⋮ in the trailing cell |

### 3.5 Shipped row overflow (app side)

The phone has no hover, so the ⋮ is always drawn and carries four verbs —
Rename, Fork session, Copy session id, Archive / Restore
(`ui/SessionsDrawer.kt:877-943`). Fork is `session/fork` then open the child
(`DshViewModel.kt:1635-1649`). Folder rows swap `Folder`/`FolderOpen` on
collapse rather than on hover, and the timestamp and ⋮ share the trailing cell.
§3.4's "always visible" and "a copy item in the overflow" are therefore shipped;
the web-only hover rules above no longer describe this surface.

---

## 4. The single most surprising thing a naive port would get wrong

**The round `+` and a typed `/` do not open the same menu.** The `+` button calls
`toggleSource('command', …)`, which seeds a roster of **exactly one** source, so it shows 8 rows
(`File`, `Goal`, `Plan`, `Feedback` under "Add"; `Compact`, `Permission`, `Model`, `Export` under
"Commands") — including two client-side entries (`File`, `Model`) that `commands/list` never
returns. Typing `/` runs `detectTrigger` and seeds **every** `'/'` source in order: the same
`command` group **plus a "Skills" group** from `ui-skill`. On top of that, the argument-taking host
rows `Goal`/`Plan` are filtered out whenever the `/` is not at the start of the draft, and a query
with no matches makes the menu **vanish silently** (there is no "No results" row — that string
belongs to the separate popupSelect overlay). A port that models "the slash menu" as one static
list will get all three of those wrong.

Primary deliverable: `docs/research/composer-menu-and-tools.md`.
