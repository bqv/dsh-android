# Parity roadmap — native client vs vanilla DSH web UI

Target: **effectively identical** to the vanilla web UI, on a phone, with no WebView.

Legend: `[x]` done · `[~]` partial · `[ ]` not started

Code references are relative to `app/src/main/java/uk/xa0/dsh/` unless a fuller path
is given. The web's own rules live in `docs/research/*.md`; this file is the ledger
of where the app stands against them.

## Foundation

- [x] Cookie auth via `/auth/login`, silent re-auth, manual-cookie escape hatch (`net/DshClient.kt`)
- [x] Encrypted credential storage (Android Keystore) (`data/ConfigStore.kt`)
- [x] RPC envelope + typed-gateway arg names (`_request` vs `request`) (`net/DshClient.kt`)
- [x] WebSocket mux (`/api/remote.mux`) with reconnect replay (`net/RemoteMux.kt`)
- [x] Waterfall answering (`$events/result`): approvals with allow/reject and
      `ask_user_question` with `{answers:[{id,selected,custom?}]}`. One `$events`
      registration process-wide, owned by `AttentionCenter.kt`; it relays
      `api-session/status` to the ViewModel
- [x] Dark + light themes from DSH tokens (`ui/theme/DshTheme.kt`)
- [x] `workspace/follow` registry stream (`DshViewModel.ensureMuxWiring`)
- [x] `commands/*`, `goal/*`, `permissionPresets/*`, `subagents/*`, `session/page`,
      `session/search`, `directoryPicker/*`, `workspace/*`, `workspaceFiles/read`
- [ ] `settings/*` and `skills/*`. A remote browser lands in the host's `memory`
      settings scope, where every `settings/mutate` is a no-op, so the settings sheet
      holds no host settings write; the one host write it carries is
      `workspace/unarchiveSession`. There is no skills surface

## Session sidebar

- [x] Brand lockup (mark + "DSH")
- [x] New Session (38dp, hairline border, r12)
- [x] Workspace grouping from the host registry
- [x] **Ungrouped** bucket for sessions outside every workspace
- [x] Group by: `WorkSpace` / `List`; Order by: `Manual` / `Updated` (persisted in `ConfigStore`)
- [x] Per-section collapse + "Show N more sessions" / "Show less"
- [x] Archived filter, and Restore on an archived row
- [x] Subagents: never top-level rows (the web's `sessionVisible()` excludes them).
      A parent with descendants carries a caret that discloses them indented under it,
      respecting Archived/Order (`ui/SessionsDrawer.kt`)
- [x] Local title/path search
- [~] Content search via `session/search`. The whole path exists — `SessionSearch`
      request/parse, a 250 ms debounced and generation-guarded
      `DshViewModel.searchSessionContent`, the deployment latch for a host with no index,
      the merged result list (local name/path matches first in roster order, then host hits,
      one row per session, a duplicate donating its snippet) and the web's pending /
      "temporarily unavailable" / cap copy (`ui/SessionsDrawer.kt`,
      `ui/search/SessionSearchRows.kt`) — but the drawer is **not fed at the call site**:
      `ChatScreen`'s only `SessionsDrawer(...)` call passes none of
      `searchHits`/`searchLoading`/`searchError`/`searchHasMore`/`onSearch`
      (`ui/ChatScreen.kt`), so the query box filters title/cwd only and the
      "Searching session history…" line never appears. The ViewModel half has no caller
- [x] Live session status dot. The `ongoing` chase plus the green done / amber warning
      solids (`ui/components/StateDot.kt`); live `api-session/status` frames are
      reconciled against each `session/list` pull, so a running claim the whole-world
      list denies is dropped rather than left stuck (`DshViewModel.reconcileLiveRunning`)
- [x] Row actions — Rename / Fork session / Copy session id / Archive, and Restore on
      the archived filter (`DshViewModel.renameSession`, `forkSession`, `archiveSession`,
      `unarchiveSession`)
- [x] Add workspace through the directory picker: breadcrumbs from Home, path editor,
      hidden-file toggle, new-folder flow, truncation notice. Opening registers it via
      `workspace/create` and adopts the created `workspaceId`
      (`DshViewModel.addWorkspace`, `ui/components/WorkspaceBrowser.kt`)
- [ ] Bottom fade + drag-to-reorder
- [ ] Row hover card — desktop-only in the web; on touch the row menu is the equivalent

## Conversation header

- [x] Session title + shortened cwd crumb
- [x] Hidden entirely for a blank/hero session
- [x] View tabs strip — three tabs, order `Chat` (0), `Trajectory` (10) then `Shell`;
      default `chat`; active = 2dp business-blue underline (not a pill); the strip
      renders only when the session has a transcript (`ui/ChatScreen.kt: ChatView`, `ViewTabs`).
      `Chat` and `Trajectory` are the host's registered `conversation.view` entries;
      `Shell` is this client's own view over the host's `terminal` Remote namespace,
      not a host-registered view
- [~] Header seats: agent-preset label, terminal recovery (error-only), schedule catalog,
      background jobs, open-in-app, right-panel expand, subagent lineage.
      Present: background jobs, right panel (Files), subagent lineage (while descendants
      run, absent otherwise by design), agent-preset chip (hero only, because the host
      refuses to recompose an agent after a turn). Missing: terminal recovery, schedule
      catalog, open-in-app.
- [x] There is **no per-session overflow menu** — rename/fork/archive live on the sidebar row
- [ ] **No way up from a subagent.** The lineage seat goes *down* (descendants, while any are
      running) and the drawer nests a subagent under its parent — but a subagent's own screen
      offers nothing that opens the session which spawned it, so a nested run is a one-way
      trip: back to the drawer and find the parent by hand. Wanted: tapping the title (or the
      lineage chip) walks up one level, repeatable to the top of the stack.
      The data is already in hand, which is what makes this small: `session/list` carries
      `parentSessionId` on subagent records, and `ui/SubagentRollup.kt` already walks that
      chain upward — with the web's own cycle guard — to count ancestors. The one thing to
      decide is what a *missing* parent does: the roster can hold a child whose parent is
      gone, and `subagents/list` reports exactly that as `parentAvailable`.
- [x] No running-turn dot in the header, deliberately: the web's header utilities have no
      such contributor, and `JobsSeat` already draws the same `StateDot` a turn dot would

## Transcript

- [x] User message: right-aligned r22 bubble, 82% cap, timestamp
- [x] Assistant message: plain full-width markdown, no bubble/avatar
- [x] Reasoning row: collapsible "Think", live "Thinking…", summary line
- [x] Tool rows: 24dp disclosure + IN/OUT mono card
- [x] Tool card variants, dispatched by call name in `ui/components/ChatRows.kt`:
      bash/pwsh terminal card with `$ cwd cmd`, state dot, exit-code pill and **panned**
      output (`ui/components/BashRow.kt`), diff from `meta.diffs` (`DiffRow.kt`), read
      with its line window (`ReadRow.kt`), search (`SearchRow.kt`), web (`WebRow.kt`),
      read-image (`ReadImageRow.kt`), ask-question transcript row (`AskQuestionRow.kt`),
      plan summary (`PlanRow.kt`)
- [x] PTC tool-call tree: `tool/ptc-dispatch-start` / `tool/ptc-dispatch` carry the parent
      link, folded by `model/ToolCallTree.kt` and rendered recursively by
      `ToolCallTreeRow` (folded behind a caret that carries the child count)
- [x] Plan/todo card
- [x] Plugin notices, error notices, retry rows (`model/Transcript.kt: llm/retry`)
- [x] Turn process grouping — a turn folds only once the host ended it (`turn/end`) and it
      produced a finalised answer; while a turn runs there is no group and no summary.
      Counts-only summary ("N tool calls · N messages · N subagents", else "Thought for a
      while") spliced after the opening user message (`model/TurnProcess.kt`,
      `ui/components/ChatRows.kt: TurnProcessRow`). **Divergence:** the web also requires
      transcript mode `compact` and fully-loaded history; the app folds either way, and a
      host `transcriptView: normal` is stored intent it does not render
      (`ui/SettingsContent.kt` "Conversation display")
- [x] Live token streaming with throttling + streaming dot; the live signal is the
      turn-scoped "Deep diving..." shimmer, not a caret — the web has no streaming caret
- [x] Message footers: `[clock] [copy]` with a 1s icon swap and no toast
      (`ui/components/ChatRows.kt: MessageActions`). No retry/edit/quote exists in the web
      UI either
- [x] Per-message timestamp on assistant rows
- [x] Back-to-bottom (34dp floating circle)
- [x] Turn navigator rail at the transcript's right edge — one tick per human message that
      opened a turn; press-to-grow scrubber with a prompt/answer preview, commit on release
      (`ui/ChatScreen.kt: TurnRail`, `TurnPreviewCard`)
- [x] Markdown: headings, paragraphs, bullet/numbered/nested lists, task lists, tables,
      quotes, rules, fenced code + banner + copy, inline code, bold/italic/strike, links
- [x] Links open in the browser for `[text](url)` **and** for a bare `https://`/`http://`/
      `www.` URL (`model/Markdown.parseInline`'s autolink, GFM's rule: word boundary,
      sentence punctuation left out, a bracket belonging to the path kept)
- [x] Syntax highlighting (heuristic tokenizer, DSH Shiki colours)
- [ ] Markdown gaps: inline images, `@file` mentions as chips, citations, tags, mermaid/math
- [ ] Interrupted assistant tail tag. A `turn/end` reason already draws the
      `turn-max-tokens` / `turn-error` notices, and a stopped tool row keeps its amber dot,
      but a frozen partial assistant block carries no "interrupted" tag

## Composer

- [x] 22dp capsule, draft surface, placeholder, send → stop
- [x] Model chip + picker sheet (grouped by provider), with the reasoning-effort chips on
      the selected route (`ui/Sheets.kt`)
- [x] `/` command picker and the `+` add menu, both from one `commands/list` roster:
      client-owned File/Model/Permission rows, an input-taking command claimed into the
      draft, a bare one run through `commands/execute` (`ui/components/CommandMenu.kt`,
      `ui/components/Composer.kt`)
- [x] `@` reference picker: Files & folders and Sessions, a directory row drills in, and a
      pick inserts the mention as a chip. Both RPCs run concurrently and publish per source,
      guarded by a query generation; the session resolver uses a patient client.
      `fileReferences/list {agentId, query}` → `[{path, kind:"file"|"directory"}]`;
      `sessionReferenceResolver/candidates {agentId, query}` →
      `[{sessionId, label, cwd?, sameWorkspace, createdAt, mention}]`. The session
      candidate's `mention` field is the whole literal `@[label](dsh-session:<b64>)` and is
      inserted verbatim; a file mention is `@path`, quoted only when the path contains a space
- [x] Caret-accurate `/` and `@` triggers — the token under the caret decides, so a tap back
      into an earlier line opens or retargets the menu instead of reading the draft's end
      (`ui/composer/ComposerTriggers.kt: detectTriggerToken`)
- [x] Approval card above the card
- [x] Todo card above the card
- [x] Queue dock (`session/updateQueue`: edit / remove / steer, long-press send mode,
      "Send behavior while busy") at dock order 20
- [x] Goal bar (`goal/*`) at dock order 10. `activation` is not modelled, so a disarmed
      goal still reads `Ongoing Goal` instead of `Inactive Goal`, and `blockedReason` is not
      parsed; both gaps are recorded in `docs/research/goal-chip.md`
- [x] Mode chips (Plan / Read-only): the access chip opens the permission sheet, the Plan
      chip runs `/plan off` and exists only while plan mode is on
- [x] Context ring + popover (`contextPressure`, `contextBreakdown`)
- [x] Statistic pills folded from the `sessionStats` and `tokenUsage` projections: a
      turns/steps chip with `tok/s` (panel: LLM time, tool time, average TTFT, tokens per
      second) and a total-tokens chip with cache hit (panel: uncached/cached input, cache
      write, output). Laid out as a wrapping row because a phone is not wide enough for the
      web's single line (`model/SessionStats.kt`, `ui/components/SessionStatsPills.kt`)
- [x] Attachments: files and pictures through `fileUploads/upload`; a picture the host
      recognises is sent inline as `{type:'image', mediaType, data, name}` with no upload
- [x] Slash commands (`commands/list` → `commands/execute`)
- [x] Subagent messaging: an addressed continuable child sends `subagents/prompt`, Stop
      sends `subagents/interruptByParent`, and the composer seat becomes a read-only status
      frame unless `subagents/list` reports the parent available
      (`model/SubagentContinuation.kt`, `ui/components/SubagentReadOnlyComposer.kt`)

## Right-hand panel (dockkit)

- [ ] 38dp tab strip, r12 chips. A phone has no room for a column beside the transcript, so
      each surface takes the whole body with its own back bar instead of a strip
- [x] Files panel. Deliberate divergence: it lists what the session *touched* — every read,
      write and edit in the transcript — rather than a live directory tree
      (`workspaceFiles/list` is never called), and reads content on demand through
      `workspaceFiles/read` (`model/SessionFiles.kt`, `model/WorkspaceFileLoader.kt`,
      `ui/components/FilesPanel.kt`)
- [x] Terminal panel — the **Shell** tab, a real PTY over the host's `terminal`
      Remote namespace (`environment`, `shells`, `list`, `create`, `follow`, `write`,
      `resize`, `rename`, `close` in `net/TerminalClient.kt`), `follow` on the same
      `/api/remote.mux`. Not xterm.js: the grid is one `Canvas` with a block cursor
      over a hand-written Android-free VT core (`ui/TerminalScreen.kt`, `term/`), and
      the panel offers two seats — the unconfined host shell and this session's own
      confined terminal (`term/HostShell.kt: TerminalSeat`, `DshViewModel`). Re-attach
      after a network drop is **unverified**: the emulator's `svc wifi/data` do not
      touch the app's path
- [x] Preview panel: the `text` preview that a deliverable or a file row opens into
- [ ] **Preview is text only.** A file the session touched can be read as text
      (`workspaceFiles/read`), but a PNG/JPEG/WebP/GIF opens as mojibake rather than as a
      picture, and an SVG is one or the other with no choice. Wanted: images render, and an
      SVG offers *both* views — the drawing and its source — since it is legitimately text.
      The transcript already fetches and draws images the agent read
      (`ui/components/ReadImageRow.kt`), so the data path exists; this is the Files panel and
      the preview tab not using it. An SVG drawing needs a renderer the platform does not
      ship (Coil-SVG or equivalent), which is the one new dependency to weigh
- **NOT a right-panel tab:** Deliverables registers nothing under `sidebar.right.*`. It is a
  conversation turn-tail row plus a `present` tool view, whose items open the text preview tab.
  The row is transcript-derived (successful `write` / `edit` / mutating `str_replace_editor`
  calls only) and cached per turn key so streaming does not re-scan

## Mobile musts (hover-only in the web UI, so unreachable on touch)

- [x] Session row ⮕ row menu (Rename / Fork session / Copy session id / Archive; Restore when
      archived). No delete, no duplicate, no pin — the host offers none
- [x] Workspace `+` (add workspace) through the directory picker
- [ ] Workspace rename/delete — the app only creates and adopts

## Settings

- [x] Read-only port of the modal's five sections in nav order — General / Models / Plugins /
      Agent presets / Archived sessions — plus two client-owned blocks (Connection, Session)
      for host/account/socket status, theme, agent preset and sign out
      (`ui/SettingsContent.kt`, `ui/settings/SettingsLocalFacts.kt`)
- [x] Archived-session management: unarchive, the one write that does not ride the settings
      scope (`DshViewModel.unarchiveSession`)
- [x] Permission presets: the General row reads `permissionPresets/catalog`, and the composer
      sheet switches with `/permission <preset>`
- [ ] Account & security (password, captcha mode, whitelist) — no `auth/*` RPC beyond
      login/required; browser auth is the signed cookie

### Deferred: settings writes

The modal is read-only by design. Per `docs/research/panels-settings.md` §3.2–3.6, the web
shell picks `persistence = ctx.remote.$host.isLoopback ? 'host' : 'memory'`, and a `memory`
scope's `enqueue` returns without calling the host, so `settings/mutate`, `settings/update`,
`credentials/set` and the like are silently dropped for a remote phone. Anything that needs a
host settings write stays a value with a disabled control.

## Deliberate divergences (kept on purpose)

- **Notices never fold.** The web hides notice rows inside the collapsed process summary; the
  app keeps them visible because it has no `hidden="until-found"` reveal to get them back.
- **A question answers pick and typed text together** for single-select questions too. The
  wire accepts the pair; a typed note next to a pick is more useful than forcing a choice.
- **Files panel lists touched files, not a directory tree** (see above).
- **Previews are text only** — an image opens as bytes, and an SVG cannot be seen as both a
  drawing and its source (the open item above).
- **Conversation view strip gap** is tightened below the web's 36px, or the second label falls
  off a narrow phone.
- **Search snippets wrap to two lines** instead of the web's single nowrap line, so the match
  the host centred is not ellipsized away.
- **The rail is press-to-grow**; the web's preview mark and tooltip are hover-only.
- **The goal chip carries `roundsStarted/maxGoalRounds`**; the web GoalBar has no counter
  (`docs/research/goal-chip.md`).

## Host traps that shape the client

- **Argument names are exact.** The gateway rejects both extra and missing keys with
  `gateway/arguments-invalid`, so `agent` where the host declares `agentId` kills the call
  loudly rather than silently.
- **A waterfall is fanned to every registered `$events` client and settles only when each has
  answered.** Registering once (which the app does) is necessary but not sufficient: a web tab
  or a second device keeps it open, so Skip is only decisive when this app is the sole client.
- **`clientTimeZone` is only sent when it is `UTC` or an Area/Location name.** A device pinned
  to a fixed offset made the host reject the whole prompt with `session/invalid-time-zone`.
- **`session/page` is the only back-pagination route**: `session/follow` opens with a bounded
  window and answers `cursor`/`hasMore`; older history comes from `session/page` with
  `beforeSeq` = the oldest seq held and `throughSeq` = the follow frame's cut.

## Verification loop

Every iteration: `./build.sh :app:assembleDebug` → `tools/install.sh <serial>` → screenshot and
compare against the web UI. Device policy, screen-capture hygiene and the emulator services are
in `docs/HANDOFF.md`; they are not repeated here.

**Order of verification, cheapest first:** (1) compile, (2) probe the live host with a
throwaway session (never mutate the session the running client itself is in), (3) only then the
screen.

## Open work

Ordered by value, not by size:

1. **Wire the drawer's content search.** The ViewModel, the merge and the status copy are
   built; the `SessionsDrawer(...)` call site passes no search state, so
   `session/search` is never sent from the UI today.
2. **Terminal panel** (`terminal/follow`) — ported as the **Shell** tab, so it is no
   longer an unported dockkit surface. What remains in it is narrower: scrollback
   *rendering* (the tail is retained but not drawn), mouse reporting, terminal
   search, more than one terminal on screen at a time, sixel/kitty graphics, OSC 8
   hyperlinks, bracketed paste, and IME composition regions.
3. **Markdown gaps** — inline images, `@file` mention chips, citations, tags, mermaid/math.
4. **Interrupted assistant tail tag** — a frozen partial block with `interrupted: true` has no
   tag; only the `turn/end` notices mark a cut-off turn.
5. **Sidebar bottom fade + drag-to-reorder.**
6. **Workspace rename/delete**, and the header seats the web has and the app does not:
   terminal recovery, schedule catalog, open-in-app.
7. **`settings/*` / skills** — blocked on the host's `memory` scope, not on the UI.
8. **Goal `activation` / `blockedReason`** — the chip always says `Ongoing Goal` for a
   disarmed goal (`docs/research/goal-chip.md`).
