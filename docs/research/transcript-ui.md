# DSH web transcript — component structure, behaviour and render rules

Provenance: every file path, component name, prop name and string literal below was read from the
read-only checkout `/home/user/bin/deepseek-harness` at `packages/client/*` and
`packages/extensions/ui-cordis` / `packages/jobs/tool-jobs` / `packages/core/*`. Palette, fonts and
raw CSS values are **not** repeated here — see `design-system.md`. Where something does not exist it
is called out explicitly.

Scope: this documents the **Chat transcript** target (`target: 'chat'`), which is the conversation
column the Android app reproduces (`ui/ChatScreen.kt`, `ui/components/ChatRows.kt`). It is not the
trajectory/devtools view (`ui-trajectory`).

---

## 0. Source map (read these files to verify any claim)

| Concern | File(s) |
| --- | --- |
| Chat view shell, scrolling, turn-rail | `ui-chat/src/client/chat/ChatView.tsx`, `ChatView.module.css` |
| Per-node seat + process visibility | `ui-chat/src/client/chat/ChatNodeSeat.tsx` |
| Node ordering (the (anchor, rank) sort) | `ui-chat/src/client/conversation-nodes/chat-snapshot-builder.ts` (`orderedVisibleChatNodes`, ~L393-413) |
| Turn-process projection | `ui-chat/src/client/conversation-nodes/turn-process.ts`, `turn-process-presentation.ts` |
| Process disclosure row | `ui-chat/src/client/chat/TurnProcessNodeView.tsx` (+ `.module.css`) |
| Reasoning row | `ui-chat/src/client/chat/ReasoningRow.tsx` (+ `.module.css`) |
| Assistant row / streaming | `ui-chat/src/client/chat/AssistantNodeView.tsx`, `AssistantMarkdown.tsx`, `conversation-nodes/assistant.ts`, `conversation-nodes/partial.ts` |
| Turn footer (assistant actions) | `ui-chat/src/client/chat/TurnTailNodeView.tsx`, `MessageIconActions.tsx`, `TurnUsagePanel.tsx`, `conversation-nodes/turn-tail.ts` |
| User row + actions | `ui-chat/src/client/chat/MessageItem.tsx`, `conversation-nodes/message.ts` |
| Tool dispatch / nesting | `ui-tool/src/client/tool/ToolCallTree.tsx`, `conversation-nodes/tool.ts` (in ui-chat) |
| Tool row chrome + variants | `ui-tool/src/client/tool/components/ToolRow.tsx`, `models/tool-call-model.ts`, `toolviews/*` |
| Card primitives | `ui-primitives/src/{TerminalBlock,DiffBlock,ReadBlock,SearchBlock,WebBlock,DisclosureRow,StateDot}.tsx` |
| Markdown | `ui-primitives/src/markdown/{MarkdownText,parse,render,incremental,mathCompatibility,katex,CodeBlock}.tsx` |
| Copy strings | `ui-chat/src/client/locale.ts` (`chat` NS), `ui-conversation/src/client/locales.ts` (`conversation` NS), `locale/src/locales/en.ts` (common NS), `ui-message-feedback/src/client/locales.ts` |

The render pipeline is: durable `SessionEvent` stream → per-node "Conversation Node Definitions"
(`conversation-nodes/*.ts`) → `Chat`-target `ChatNode[]` → `orderedVisibleChatNodes()` → flat list of
`ChatNodeSeat` (each a `div.flowItem`) → keyed renderer slot `conversation.chat.node`.

---

## 1. Turn process grouping

### 1.1 It is a flat node list, not a container

There is **no `<Turn>` component and no wrapper element** around a turn's rows. Every row is a
sibling `div` rendered by `ChatNodeSeat`:

```
<div className={css.flowItem}
     data-chat-anchor-key={node.key} data-chat-flow-key={node.key}
     data-chat-flow-kind={node.kind} data-chat-turn={turn}
     data-turn-process-member / data-turn-process-hidden / data-turn-process-answer>
```

Rows are sorted globally by `orderedVisibleChatNodes()` with the tuple
`(anchor, rank, originalAnchor, key)`. The process *disclosure* is itself a synthetic node
(`kind: 'turn-process'`, built by `turn-process.ts#buildViewNode` at
`controlAnchorSeq + CHAT_SYNTHETIC_SEQ_OFFSETS.processControl` where `processControl = -0.1`) and is
placed by the rank rule in `presentationPosition()`: **anchor = the opening human message's seq,
rank = 1**, so the button sits immediately after the user/steering message that opened the turn.
Process-member rows keep rank 0, so inside an expanded group they remain in natural event order
(reasoning/step text, then tool calls, then context notices, …). `openingHumanAnchor` and rank 2 put
any stray pre-user process node after the human message.

### 1.2 Collapsed summary label — exact composition

`TurnProcessNodeView.tsx` builds `labels: string[]` in this fixed order:

1. `toolCallCount > 0` → `message.turnProcess.toolCalls.one` / `.other` → **"1 tool call" / "3 tool calls"**
2. `messageCount > 0` → `message.turnProcess.messages.one` / `.other` → **"1 message" / "2 messages"**
3. `subagentCount > 0` → `message.turnProcess.subagents.one` / `.other` → **"1 subagent" / "2 subagents"**

joined with `message.turnProcess.separator` = `' · '` (space-middle-dot-space). If all three are 0
the label is `message.turnProcess.thoughtForAWhile` = **"Thought for a while"**.

There is **no "Worked for 12s" and no step count** in this row. Examples of real labels:
`"3 tool calls"`, `"2 tool calls · 1 message"`, `"1 subagent · 4 tool calls"`,
`"Thought for a while"`. The row is a `<button>` with a trailing `IconChevronDownOutline14`
(rotated -90° closed, 0° open) and `aria-expanded`.

For EN an implementation is:
`[toolCalls && `${n} tool call${n===1?'':'s'}`] [messages && `${n} message${n===1?'':'s'}`] [subagents && `${n} subagent${n===1?'':'s'}`].join(' · ') || 'Thought for a while'`.

Count semantics (`turn-process.ts`):
- `toolCallCount`: durable root `tool/call` events in the turn, **excluding** subagent delegations.
- `subagentCount`: root `tool/call` whose name is `subagent` or starts with `subagent_`
  (`isSubagentDelegationTool`, `contract/turn-process.ts`).
- `messageCount`: `assistant/message` appends carrying reply content (text/reasoning/image/other —
  a tool-call-only message does not count). Once a final answer exists, only messages from **steps
  before the answer step** count.

The Android port counts every folded root tool call in `toolCalls`, delegations included, so a
`subagent`/`task`/`delegate` call is counted once as a tool call *and* once as a subagent
(`model/TurnProcess.kt:237-246`).

### 1.3 Open/closed default — decided by turn completion, not by "running"

The disclosure only exists once `processWindowReady` is true in `ChatNodeSeat.tsx`:

```ts
const processWindowReady = processSpec !== undefined
  && processPresentation !== undefined
  && compactTranscript                       // settings mode === 'compact' (the default)
  && processSpec.answerAnchorSeq !== null    // a finalized answer exists
  && processPresentation.turn === processSpec.turn
  && processPresentation.turnClosed          // turn/end has landed
  && !historyIncomplete                      // no "load earlier" page outstanding
```

Consequences to port carefully:

- **While the turn is running there is no group and no summary row.** Every reasoning/step/tool row
  is visible and rendered standalone. `foldable` is false, so `TurnProcessNodeView` returns `null`
  (the synthetic node exists but paints nothing).
- **After `turn/end`, in Compact mode** the group becomes foldable and defaults **closed**. `open`
  is not React state: it is read from a per-session store `ChatStoreState.turnProcesses`
  (`ui-chat/src/client/stores.ts`), which starts empty. `setTurnProcessOpen(turn, answerStep, true)`
  pushes `{turn, answerStep}`; `false` removes it. Crucially the stored entry only counts when
  `storedEntry.answerStep === processSpec.answerStep` (`ChatNodeSeat` L51-56), so **a retry/new answer
  step resets the group to closed**.
- **Normal mode** (`settings.transcript.normal`, `TranscriptViewMode = 'normal' | 'compact'`,
  `DEFAULT_TRANSCRIPT_VIEW_MODE = 'compact'`) disables `processWindowReady` entirely: process rows
  are always visible, no summary row. Setting row: `settings/TranscriptViewRow.tsx`
  (`settings.transcript.title` = "Conversation display", `.normal`/`.compact` = "Normal"/"Compact").
- An **interrupted turn with no final answer** has `answerAnchorSeq === null` → never foldable → its
  partial rows are always visible.

Hidden members are **not unmounted**: `useSearchableHidden` (`searchable-hidden.ts`) sets
`hidden="until-found"` on the row and listens for `beforematch` to open the group (browser find can
reveal a hidden process row and the group opens). An empty hidden box gets `margin-bottom:-16px`
(`AssistantMarkdown.module.css`) and `.flowItem:empty { display:none }`.

When closed and the answer is the immediately following row with no intervening human message
(`processPresentation.compactAnswer`), `data-turn-process-answer` is set on the answer's flow item,
which narrows the column gap from 16px to 8px so the answer visually attaches to the summary row
(`ChatView.module.css`: `.flowItem[data-turn-process-answer] { --dsh-chat-flow-gap: 8px }`).
Expanded groups return to the standard 16px rhythm.

The disclosure chrome: 33px-high full-width button, `padding: 0 0 8px`, bottom `0.5px` l2 hairline;
label 14/24 `label-secondary`; when closed, extra `margin-bottom: 8px`.

### 1.4 Row order inside an expanded group

Natural `anchorSeq` (event seq) order; there is **no type reordering**. A typical multi-step turn:

```
steering?                       (independent kind, never a member)
user                            (independent kind)
[turn-process disclosure row]   (anchor = user seq, rank 1)
  assistant-step (step 0)       → Think row + interim text + images
  tool-call (root, call order)  → one row per root tool call
  context (form: notice/…)      → plugin/job notices (NOT independent; they hide too)
  assistant-step (step 1)
  tool-call ...
  assistant-step (answer step)  → final answer; its inline reasoning hides into the group
turn-error | turn-max-tokens    (independent kinds, after the answer)
turn-tail                       (independent kind, last)
context / compaction / model-retry (by seq)
```

`TURN_PROCESS_INDEPENDENT_KINDS` (`contract/turn-process.ts`) is exactly:
`system-prompt`, `user`, `steering`, `turn-process`, `turn-error`, `turn-max-tokens`, `turn-tail`.
Everything else inside `[processStartSeq, answerAnchorSeq)` is a process member — including
`context` notices, `compaction`, `model-retry`, `command`, and `unknown`.

The Android port narrows the member set to the assistant's own process: tool calls, interim assistant
messages and to-do rows fold, while every notice (`context`, `compaction`, `model-retry`, turn error)
and every human message stays visible outside the group (`model/TurnProcess.kt:212-219`).

`processStartSeq` = the turn's start seq, or the earliest external/assistant process evidence.
`answerAnchorSeq` = the final assistant message's seq. The **answer step's own inline reasoning** is
part of the group: `AssistantNodeView` sets `reasoningHidden` when the presentation is foldable, the
step is the answer step, `spec.inlineReasoning` and the group is closed; the reasoning is then
wrapped in `data-turn-process-inline [hidden]`.

### 1.5 Tool-call nesting (tree)

Yes, tool calls are a tree. `tool.ts` (ui-chat) folds `tool/ptc-dispatch-start` and `tool/ptc-dispatch`
events into `subCalls` on the root `ToolCallBlock`, keyed by `parentCallId`/`subCallId`/`rootCallId`,
with a cycle + `MAX_DEPTH = 256` guard. `ToolCallTree.tsx` renders it recursively:

- Each call is `div.callRow[data-chat-anchor-key="call:<callId>"][data-chat-call-id]`.
- Children render inside `div.subCalls[data-subcalls]` with
  `.subCalls { margin: 4px 0 2px 22px; padding-left: 8px; border-left: .5px solid border-l2; gap: 4px }`.
- The keyed slot `tool.call.toolview` (keyed by tool name) dispatches **every level** to the same
  atomic row renderer; an unregistered name falls back to `GenericToolCard`. So a `cordis_run` card
  that dispatches sub-calls shows those sub-calls inside it exactly like a `subagent` root does.
- An interrupted tree closes each unfinished root as a synthetic error result
  (`error: {name:'Interrupted', code:'interrupted'}`) at
  `interruptedAt.seq + CHAT_SYNTHETIC_SEQ_OFFSETS.interruptedFollowup` (-0.8).

### 1.6 Exact copy per state

The disclosure row itself does **not** carry state copy: it is the same count label in every state,
and it only appears after the turn is closed. Its only state-dependent hooks are
`data-open` (chevron direction). Per-row state lives on the rows:

| Row | running | finished | interrupted |
| --- | --- | --- | --- |
| Tool row hidden status (`ToolRow`) | `row.running` "Running" | — | `row.stopped` "Stopped" |
| Tool row failure | — | `row.failed` "Failed" (state dot red) | amber state dot (warning) |
| Bash row hidden status (`bash-sample.tsx`) | `bash.running` "Running" | — | `bash.stopped` "Stopped" |
| Reasoning row (`ReasoningRow.tsx`) | `row.running` "Running" (visually hidden) + running sweep | — | — |
| Assistant row tail (`AssistantMarkdown.tsx`) | column shimmer (`TurnStatus`, below) | — | inline chip `message.stopped` **"Stopped"** |
| Turn-end notice | — | `message.turnError` "This turn failed" / `message.maxTokens` "Output token limit reached" | — |

The running-turn indicator is a separate, turn-scoped row appended at the *bottom of the column*
while the session is running: `ChatView.tsx#TurnStatus`, `role="status" aria-live="polite"`, label
`chat.deepDiving` = **"Deep diving..."**, plus a live elapsed clock (`formatRunDuration`) that only
appears after `elapsedMs >= 15_000`. It is anchored to `turn/start` and rides the whole turn (first-token
wait, tool execution, streaming) so it never flickers per step. It is a brand-blue text-shimmer
(`dsh-turn-status-shimmer 1.8s linear infinite`), static under `prefers-reduced-motion`.

---

## 2. Message footer / actions

Both roles share one component: `MessageIconActions.tsx`. Its render order is fixed:

```
[clock (if clock='start')] [copy] [extraActions] [branch (if onBranch)] [usageAction] [clock (if clock='end')]
```

- **copy**: always present, `IconCopyOutline16` → `IconCheckOutline16` for 1000 ms after a successful
  `writeClipboard`. Tooltip label `t('copy')` = "Copy" → `t('copied')` = "Copied".
- **extraActions**: the `conversation.chat.assistant-actions` list slot (see below).
- **branch**: present only when `onBranch` is given. `IconBranchOutline16`. Tooltip
  `message.branch` = "Branch into a new conversation"; when unavailable,
  `message.branchUnavailable` = "Available only on the last message of a completed turn", plus
  `aria-disabled`, `data-unavailable`, `opacity:.4`, and a visually-hidden reason span.
- **usageAction**: assistant only — the turn usage/time pills (below).

### 2.1 User / steering message

`MessageItem.tsx#UserMessageNodeView` (keys `user` and `steering`) renders `UserStyleBubble`: a
right-aligned stack (attachments row, bubble, optional `message.referenceSummary` =
"Referenced session · {labels}"), then:

```tsx
<MessageIconActions text={text} time={data.time} clock="start" className={css.actions} t={t} />
```

So the user row has exactly **[clock] [copy]** — no branch, no extra actions, no usage pill.

- **Icons in order:** clock, then copy.
- **Timestamp:** at the **start** of the row (left of the copy icon), `formatMessageClock`.
- **No retry / regenerate / edit / quote action exists for user messages** anywhere in this client.
  (The only "retry" UI is the automatic `model-retry` row described in §5.)

### 2.2 Assistant message

Assistant content rows have **no actions**. Actions live on the separate `turn-tail` node
(`TurnTailNodeView.tsx`), which is the last row of a completed turn and is anchored to the closing
assistant message:

```tsx
<div data-turn-tail={turn} data-actions-reveal={isLatestTurn ? 'always' : 'hover'}>
  {tail}   // conversation.chat.turnTail chain (deliverables etc.)
  <MessageIconActions
    text={assistantText(closing.blocks)}
    time={closing.time}
    clock="end"
    onBranch={() => forkAt(closing.finalNode.seq)}
    branchUnavailable={data.branchUnavailable || hasLaterChatNode}
    extraActions={assistantActions}          // conversation.chat.assistant-actions list
    usageAction={tokenUsage && <TurnUsagePanel/> + runMs && <TurnTimePanel/>}
  />
</div>
```

Rendered order in the assistant footer is therefore:

1. `conversation.chat.turnTail` chain contributions (e.g. `ui-deliverables` produced-files strip) —
   these are **above** the icon row, with 16px gap.
2. clock (`data-actions-reveal` gate)
3. copy
4. `assistant-actions` entries in slot `order` — today only `ui-message-feedback` (order 10) with
   **Like / Dislike** buttons (outline glyphs, filled when active, `aria-pressed`; clicking an active
   rating retracts it, an inactive one opens the per-message feedback dialog).
5. branch
6. `TurnUsagePanel` pill (`IconDatabaseOutline16` + `message.turnUsage.consumed` = **"Usage {total}"**)
   when `tokenUsage` exists, then `TurnTimePanel` pill (`IconClockOutline16` + `message.ranFor` =
   **"Ran for {duration}"**) when the turn has start+end times.
7. clock (`clock="end"`, after everything)

If there is no closing text assistant (`closing === null`, e.g. a tool-only or interrupted turn),
only the tail chain renders; the icon row is omitted entirely.

- **Timestamp:** at the **end** of the row, after the pills.
- **No retry/regenerate/edit/quote** for assistant messages either. `forkAt(seq)` is the only
  durable message action, and it is labelled "Branch into a new conversation".

### 2.3 Visibility rule

`MessageIconActions.module.css` owns it via `data-actions-reveal` and flow-kind selectors, gated by
`@media (hover: hover)`:

- Assistant tail: `data-actions-reveal="always"` for the **latest turn**; `"hover"` for every earlier
  turn. Hover/focus-within reveals; opacity transition 80 ms; layout is stable (opacity, not display).
- User/steering: there is no `data-actions-reveal`, but the selector
  `:is([data-chat-flow-kind='user'],[data-chat-flow-kind='steering']):has(~ :is([data-chat-flow-kind='user'],[data-chat-flow-kind='steering'])) .actions`
  hides the row for any user message that has a **later user/steering message**, and shows it on
  hover/focus-within. So the **latest user message's actions (and timestamp) are always visible**;
  earlier ones are hover-only.
- On devices without `hover` (`@media (hover: hover)` false) **every** action row stays visible;
  the Android app does the same — `MessageActions` has no hover gate
  (`ui/components/ChatRows.kt:419-486`).
- The clock is inside `.actions`, so it obeys the same reveal rule (earlier user messages: timestamp
  is hover-only; latest user and latest assistant turn: always visible).

### 2.4 Timestamp format

`message-chrome.ts#formatMessageClock(time, t, now)`:

- same local calendar day → `HH:mm` (24-hour, zero-padded)
- same year, earlier day → `clock.md` + time; EN `'{m}/{d}'` → `M/D HH:mm`
- other year → `clock.ymd` + time; EN `'{y}-{m}-{d}'` → `Y-M-D HH:mm`

`useCalendarDay` re-renders at local midnight so the day bracket stays correct.

### 2.5 Copy behaviour

There is **no toast** for message copy. The button swaps to a check glyph and the tooltip label
becomes "Copied" for 1000 ms (`copyTimer`, `copyEpoch` guards re-clicks). Repeated clicks during the
window are ignored. Failure is silent (`if (!ok) return`). The card primitives
(`TerminalBlock`/`DiffBlock`/`ReadBlock`/`SearchBlock`) instead swap the copy **button text** to
`t('copied')` = "Copied" for 1 s. The icon-grid copy button carries `aria-label` Copy/Copied.

---

## 3. Tool call card variants

### 3.1 Dispatch and shared chrome

`ToolCallTree.tsx` → keyed slot `tool.call.toolview` keyed by **wire tool name**, fallback
`GenericToolCard`. Keys registered by the shipped build:

| key(s) | component | file |
| --- | --- | --- |
| `bash` | `BashRow` | `toolviews/bash-sample.tsx` |
| `read` | `ReadRow` | `toolviews/read-row.tsx` |
| `read_image` | `ReadImageRow` | `toolviews/read-image-row.tsx` |
| `edit`, `write` | `FileMutationRow` | `toolviews/file-mutation-row.tsx` |
| `grep`, `glob` | `SearchRow` | `toolviews/search-row.tsx` |
| `web_search`, `web_fetch` | `WebRow` | `toolviews/web-row.tsx` |
| `todo_write` | `TodoRow` | `toolviews/todo-row.tsx` |
| `ask_user_question` | `AskQuestionRow` | `toolviews/ask-question-row.tsx` |
| `cordis_define` | `CordisDefineRow` | `extensions/ui-cordis` |
| `cordis_run` | `CordisRunRow` | `extensions/ui-cordis` |
| `cordis_stop`, `cordis_undefine` | `CordisActionRow` | `extensions/ui-cordis` |
| `present` | `PresentRow` | `ui-deliverables` |

Two override rules matter:
- If `toolRowModel(...).autoReviewDenial !== null` (error `AutoReviewDeniedError` /
  `AUTO_REVIEW_DENIED`), `ToolCallTree` forces `GenericToolCard` even for a keyed name. Copy:
  `tool.autoReviewRejected` "Rejected by Auto review",
  `tool.autoReviewNotExecuted` "Tool was not executed. Reason: {reason}",
  `tool.autoReviewReasonFallback` "Auto review did not authorize this action".
- `cordis_*` rows are generic-row mechanics with a business accent (see `ToolRow.module.css`:
  `[data-tool^='cordis_']` leading/title get `state-business-primary`, title weight 500, separator dot
  business colour). `cordis_define` has its own card (source tabs Host/Client, `CodeBlock`, output,
  "panel.hint"), `cordis_run` has a live run card.

The **generic fallback** (`GenericToolCard`) is also the variant classifier source of truth:
`TOOL_VARIANTS` in `models/tool-call-model.ts` maps
`bash→bash`, `pwsh→bash`, `read→read`, `read_image→read`, `web_fetch→read`, `web_search→search`,
`grep→search`, `glob→search`, `write→write`, `edit→edit`, `run_code→code`,
`cordis_package_inspect→read`, `cordis_runtime_inspect→read`, `cordis_run|stop|undefine→others`;
everything else → `others`. Variants: `'search' | 'read' | 'bash' | 'write' | 'edit' | 'code' | 'others'`.

Generic chrome (`ToolRow.tsx`), one 24 px line:
`[16px leading icon] gap6 [title] gap8 [2×2 separator dot] gap8 [summary that ellipsizes] [optional non-shrinking suffix]`.
Leading icon per variant (`VARIANT_ICONS`): search=search, read=browse, bash=api, write/edit=edit,
code=code, others=sparkle — all 14 px in a 16 px box. On an **error** row the leading icon is
replaced by a red `StateDot`; on a **stopped/interrupted** row by an amber `StateDot`. Titles come
from `VARIANT_TITLE_KEYS` and per-tool overrides in `TOOL_TITLE_KEYS`
(`cordis_package_inspect|cordis_runtime_inspect → "Inspect"`, `cordis_run → "Run Cordis Plugin"`,
`cordis_stop → "Stop Cordis Plugin"`, `cordis_undefine → "Remove Cordis Plugin"`, `pwsh → "Pwsh"`,
`read_image → "Read image"`).

Generic summary derivation (`toolRowModel` + `SUMMARY_KEYS`):
- bash: `description` → `command` (first line only)
- read: `path` → `file_path` → `url`
- search: if `args.queries` is an array, join non-empty first lines with `", "`; else `query` →
  `pattern` → `url`
- write/edit: `path` → `file_path`
- code: `description`
- others: no keys → first non-empty string arg; then the summary becomes `` `${toolName} · ${base}` ``
- args empty → summary = the call id
- all summaries pass through `firstLine()` then `relativizeToCwd()` + `abbreviateHomePath()`

For `read`/`write`/`edit` the summary is a **file-path link** (`button.fileLink`, dotted underline,
clicking opens the host default app; `read` passes `{line: offset}`), so those rows never expose an
args body (`bodyRaw={null}` in `readFamilyRow`/`GenericToolCard` when `singleFile`).

State (`toolRowModel`): `running` (no result) → `stopped` (`error.code === 'interrupted'`) → `error`
(`isError`) → `ok`. For a settled bash whose exit status is non-zero, `terminalFailed()` flips the row
to `error` even though the tool call itself is not an error.

Expanded generic body:
- `run_code` (`code`) → `bodyText` is the program (`args.code`), rendered through `CodeBlock` with
  `lang="typescript"` inside a 260 px `max-height` scroller.
- all other variants → `ioCard` with `IN` / `OUT` sections (`row.input` = "IN", `row.output` = "OUT"),
  0.5 px l1 border, 12 px radius, `markdown-code-block` surface, each section `max-height:150px`
  scrolling independently, a 0.5 px `ioDivider`, `pre-wrap` payload text; an error OUT section is
  coloured `state-error-primary`.
- Every expanded body ends with the **Inspect pill** when `inspect` is defined (see §3.7).
- The collapsed summary is **replaced** by `errorSummary` (first line of the result/error text) on a
  failed row, and that line is coloured `state-error-primary`.

Running rows get a **sweep glare** overlay: `::after` 300 px gradient band, `dsh-tool-row-sweep 2.6s
ease-out infinite` (`left: -300px → 100%`, 90% hold). The same sweep is used on the standalone bash
row and the running Think row.

### 3.2 bash / terminal — `BashRow` + `TerminalBlock`

Collapsed row (custom markup, not `DisclosureRow`): `[leading] [hidden status] [title] [dot] [summary]`.
- title `tool.title.bash` = **"Bash"** / `tool.title.pwsh` = **"Pwsh"**.
- summary = `failureLine ?? terminal.description ?? model.summary`; for a normal bash call this is the
  call's **`description`**, not the command.
- Expandable (chevron appears, icon cross-fades on hover) only when a terminal card exists, or for an
  error / settled persistent-shell / spilled-shell call with input or output.
- **Background** calls (`run_in_background: true`) return `null` from `terminalCardModel` and are
  explicitly kept collapsed (`genericBody` false) — a background acknowledgement renders as the
  plain summary line, no body, no dot change beyond state.

Expanded body is `TerminalBlock` with `maxLines={Infinity}` — **no line cap in chat**; the card
scrolls its output via `--dsl-terminal-output-max-height: 224px` (banner pinned).

`TerminalBlock` prompt layout (`TerminalBlock.tsx#.promptLine`):
```
[gutter 30px] run-state dot (absolute, vertically centred, on the FIRST row only)
              '~' or last path segment   (cwd label, first row only)
              gap 8
              command line (white-space: pre, ellipsized)
```
- Multi-line command → **one prompt row per line**; later rows use a bare `$` (and no dot).
- `cwd` label: `promptLabel()` → `~` when equal to `home`, else the last path segment; a multi-line
  command's later rows render `$`.
- **State dot**: exactly one per card, in the left gutter of row 0. `runState()`:
  `running → StateDot state="ongoing"` (8-cell blue pixel chase, `dsh-state-dot-chase 1s infinite`);
  a non-zero exit or a signal → `state="error"` (red); clean → `state="done"` (green). It is
  `aria-hidden`; the state is carried by a visually hidden label (`Running`/`Failed`/`Done`).
- **Exit code / signal**: not drawn inline in the output. `terminalCardModel.parseExitStatus()`
  strips a trailing `\n[exit code: N]` or `\n[killed by signal: X]` from the result text, and a
  `Pill` in the header shows `terminal.exitCode` = **"exit code {code}"** or `terminal.signal` =
  **"signal {signal}"** (signal wins). A clean exit 0 shows no pill.
- **stdout vs stderr are NOT separated.** `TerminalBlock` receives a single `output` string built by
  `singleResultText(block)` (result content blocks joined in order) after the exit marker is
  stripped. Interleaving/ordering is whatever the tool streamed. There is no separate stderr region,
  no colouring, and no ANSI split beyond SGR colour parsing (`parseAnsiLines`, styles applied as
  inline spans).
- A trailing newline is a terminator, not an extra blank row; empty (or escapes-only) output shows
  `terminal.noOutput` = **"No output"** and hides the Copy control.
- Header also carries the Copy control (text "Copy"/"Copied").
- `terminal_send` calls render through the same card (`command` = the typed text or
  `terminal.sendInput` = "(send input)"; description `terminal.session` = "Terminal {sessionId}").

Expanded bash error fallback (no terminal card): the same `ioCard` IN/OUT layout as generic.

### 3.3 read — `ReadRow` → `ReadBlock`

Collapsed row: title **"Read"** (`tool.title.read`) / **"Read image"** (`tool.title.readImage`), lead
browse icon, summary = cwd-relative path rendered as an openable dotted-underline link; no args body.

Expanded `ReadBlock` (`readCardModel` + `ReadBlock.tsx`):
- Content comes from **result metadata**, not the raw text: `meta = {path, offset, lines[{number,text}], totalLines, lang}`
  validated strictly (`readMeta`). The raw envelope
  `<path>…</path>\n<type>file</type>\n<content>\n…\n</content>` must also match.
- Line numbers **always shown**: fixed 48 px right-aligned gutter per row, `user-select:none`;
  content is `white-space: pre` (no wrapping, horizontal scroll). Numbers are the file's own 1-based
  numbers — a window past `offset` keeps real numbering.
- Syntax highlighting: `lang` (a file-extension-derived id from meta) → `useViewportHighlighting`
  `highlightLines(raw, lang)` whole-window (preserves multi-line grammar context); plain monospace
  when the grammar is unknown or still loading (lazy shiki grammar, re-render on load).
- **Truncation ladder**: `CHAT_READ_MAX_LINES = 8` in the chat row (the primitive's own default is
  16, kept by the details panel). Head/tail split `head = ceil(max/2) = 4`, `tail = 4`, with a
  `FoldToggle` between: collapsed label `read.expandRest` = **"… {count} more lines"**, expanded
  label `read.collapseAria` text = "Collapse". Per-line text is already truncated by the **tool**
  (meta.lines); the UI adds no per-line cap.
- Banner: file path label (12/18 code font), and on the right `read.window` =
  **"Showing {shown} of {total} lines"** only when `lines.length < totalLines`, the `lang`, and a
  Copy button (absent for an empty file). Copy writes the raw line texts joined by `\n` (no gutter).

### 3.4 write / edit / diff — `FileMutationRow` → `DiffBlock`

Collapsed row: lead edit icon, title **"Write"** / **"Edit"**, path summary link, and a trailing
non-shrinking suffix `+{added} -{removed}` (`diffStat`, code font, caption colour) computed by
`diffTotals()`.

Diff source (`models/diff-card-model.ts`):
- `block.meta.diffs` (the host tool's applied diff hunks) when present and well-formed. A valid empty
  array on a successful `write` falls back to the argument-derived whole-file diff; on `edit` an empty
  array falls through to generic.
- Otherwise an **intended diff** is derived from args: `write` → `{path, oldText: null, newText: args.content}`;
  `edit` → `{path, oldText: args.old_string || null, newText: args.new_string}`;
  `str_replace_editor` create/str_replace → same shape (running only; it settles through Generic).
- `str_replace_editor` settled and all error results → no diff card.

Rendering (`DiffBlock.tsx`, `maxLines = CHAT_DIFF_MAX_LINES = 9`):
- The hunk is computed client-side with jsdiff `structuredPatch('', '', old, new, {context: 3, maxEditLength: 256})`
  unless meta.diffs already gives a patch; exceeding the edit limit falls back to a whole-fragment
  replacement.
- Row kinds: `path`, `del`, `add`, `context`, `gap`. The **sign prefix is CSS `::before`** and is part
  of the DOM: `del::before { content:'- ' }` (error colour), `add::before { content:'+ ' }` (success
  colour), `context::before { content:'  ' }`. The whole line takes the del/add colour, so colour is
  never the only signal. No wrapping (`white-space: pre`).
- Per-file header: the raw model-facing `path`, verbatim, `label-primary`, weight 600, `padding-right:56px`
  to clear the floating Copy button. A second hunk of the same file is preceded by a `gap` row whose
  text is `⋯`; a new file gets another `path` row.
- Footer: `└ +{added} -{removed} · {N file(s)}` in `label-tertiary` code font (`diff.files.one/.other`
  = "{count} file"/"{count} files"; the `└` is a literal string).
- Height cap: head `ceil(9/2)=5`, tail `4`, `FoldToggle` label `diff.expandRest` =
  **"… {count} more lines"**.
- Copy button floats top-right, copies the whole diff including folded rows with
  `- `/`+ `/two-space prefixes.

### 3.5 search — grep / glob — `SearchRow` → `SearchBlock`

Collapsed row: lead search icon, title **"Grep"** / **"Glob"**, summary = the joined queries/pattern
(see §3.1).

Expanded card is discriminated by `kind`:
- `grep` → `kind:'matches'`, `files[]` grouped by file. A group header is a **button** (path in
  primary 600-weight + match count in tertiary) that collapses/expands that group. Each match row is
  `<span class="lineNumber">{n}: </span>{line}` (line number dimmed).
- `glob` → `kind:'paths'`, one row per path.
- Header summary: `search.matches` = **"{shown} matches · {files} files"** (grep) /
  `search.paths` = **"{shown} paths"** (glob); when `meta.truncated`, `*.truncated` variants =
  "Showing {shown} of {total} …". Empty → `search.noResults` = "No results".
- Cap: `CHAT_SEARCH_MAX_LINES = 8` rows — **file headers count as rows**. Head 4, tail 4; when the
  tail slice starts mid-file the owning file header is restored at the top of the tail and consumes a
  tail slot so visible rows stay at 8 and `hidden` stays exact.
- `FoldToggle` label `search.expandRest` = "… {count} more lines".
- A capped search additionally prints the raw result text (the "Full … stored at …" locator) as
  `searchRecovery` below the card.
- Copy copies the full structured result regardless of cap/collapse.

Card structure from metadata: `meta = {truncated:boolean, total:number, shape, files|paths}`; `grep`
requires `shape === 'matches'`, `glob` requires `shape === 'paths'`.

### 3.6 web_search / web_fetch — `WebRow` → `WebBlock`

Collapsed row: `web_search` lead globe + title `tool.title.webSearch` = **"Search"**; `web_fetch`
lead browse icon + title `tool.title.webFetch` = **"Fetch"**. Summary = `queries` joined with `", "`
for search, the `url` for fetch.

Expanded:
- search (`kind:'search'`): optional `answer` rendered as **markdown** (`MarkdownText` with the shared
  labels) above an `<ol>` of sources. Each `<li value={index+1}>` is a safe external link (http/https
  only; otherwise plain text) labelled by title else URL hostname, then optional `.snippet` and
  `.publishedAt`. Empty answer+sources → `web.noResults` = "No results found". `truncated` →
  `web.sourcesTruncated` = "Source list truncated".
- fetch (`kind:'fetch'`): the final redirected `url` as a link, then `web.http` = **"HTTP {statusCode}"**
  and, when truncated, `web.contentTruncated` = "Content truncated".
Metadata: `meta = {truncated, answer?, sources?, url?, statusCode?}`; missing/invalid → generic.

### 3.7 todo_write / plan — `TodoRow`

- Title `todo.rowTitle` = **"Update to-do list"**, lead checklist icon.
- Summary: `todo.completed` = **"{done}/{total} completed"**, plus `" · {activeContent}"` when the
  first `in_progress` item has usable content. The count is the number of `status === 'completed'`
  items; `total` is the list length.
- When more than one task is in progress, a **non-shrinking suffix** `+{activeExtra}` is rendered
  outside the ellipsized summary so parallel work survives a narrow row (`summarySuffix`).
- Body = the raw args JSON (IN/OUT generic card) or the result output. No structured plan card here
  (the plan panel `TodoPanel.tsx` is a separate composer-side surface, not a transcript row).
- Malformed JSON / non-array `todos` falls back to the generic summary.

### 3.8 Generic fallback (`others`, and any unregistered name)

- Title `tool.title.generic` = **"Tool call"**, lead sparkle icon.
- Summary `` `${toolName} · ${base}` `` when the name has no specific title; `base` is the first
  non-empty string arg (or the call id).
- **Subagent calls** (`subagent`, `subagent_*`) have no dedicated card: they land here, e.g.
  `subagent · Fix the parser`, and are counted in the group's `subagentCount` rather than
  `toolCallCount`.
- Expanded body = IN/OUT `ioCard`. A running subagent is just a running generic row.

### 3.9 The "Inspect" pill

Rendered by `ToolRow`/`BashRow` **only inside the expanded body** (`bodyWrap`), at the body's
bottom-left, and only when `inspect` is provided. It reserves its own line, so revealing never shifts
layout. Contents: `IconInspectOutline12` + `row.inspect` = **"Inspect"**. It is `opacity:0` until the
whole row is hovered (`:hover`) or the pill is `:focus-visible`; 100 ms opacity transition. Clicking
calls `inspectCall(callId)` → `openView('trajectory', callId)` — i.e. it opens the **trajectory dev
view**, it does not expand anything in place.

---

## 4. Reasoning row ("Think")

`ReasoningRow.tsx` (used by `AssistantMarkdown` for every `kind:'reasoning'` block):

- **Title is always `message.think` = "Think"** in every state. There is no separate
  running/finished title. The Android port diverges: a running row reads `Thinking…` and settles
  back to `Think` (`ui/components/ChatRows.kt:690`).
- Leading `IconThinkOutline14` (chevron on hover), 2×2 separator dot, then the summary, then the
  body when expanded. Collapsed unless the user opens it (`useState(false)`), per block.
- `data-variant="think"`, `data-state={running ? 'running' : 'ok'}`, `data-expanded`.
- Running adds a visually-hidden `row.running` = "Running" label and the same 300 px sweep glare
  (`dsh-reasoning-row-sweep 2.6s`) as a running tool row.
- **Collapsed summary sampling**:
  - running: `latestLine(text)` — the last non-empty line of the reasoning as it streams, and the
    summary is right-aligned and allowed to overflow visibly
    (`[data-follow-end]`, `width:max-content; min-width:100%; text-overflow:clip`).
  - not running: `firstLine(text)` — the first line.
  - Both `.replaceAll('**','')` (markdown bold markers are stripped only in the summary; the expanded
    body keeps the full text verbatim).
- Expanded body (`thinkBody`) is plain text, `white-space: pre-wrap`, 13 px, indented to align under
  the title; it flows uncapped with the page (no internal scroller) but the disclosure header becomes
  `position: sticky; top:0` while open so the collapse control stays reachable.
- **Reasoning is per-step, not merged per turn.** Each model step is its own `assistant-step` Chat
  node, and each reasoning block in it renders its own row. `running` is only true for
  `streaming && i === last` (the last block of the streaming message).
- In a completed compact turn, the **answer step's** inline reasoning is hidden into the process
  group (`reasoningHidden`, `data-turn-process-inline [hidden]`); earlier steps' reasoning is an
  ordinary process member and hides with the group too.
- `prefers-reduced-motion` disables the sweep.

---

## 5. Notices / errors / interrupted tails

### 5.1 Plugin / context notices — `context` node

Any durable `user/message` whose `source.kind !== 'user'` becomes a `context` node
(`conversation-nodes/message.ts`). It renders `ContextInjectionRow` → `DisclosureRow`:

- icon: `IconContextInjectionOutline16` or, for `role:'recall'`, a session `ReferenceIcon`.
- title: `message.contextInjection` = **"Context injection"**, or `message.contextRecall` =
  **"Session recall"**.
- collapsed: dot + producer label + optional notice summary. Producer label comes from
  `contextProducer()`: `plugin` source → the plugin name; `skill-invocation` → the skill name;
  `agent-instructions` → joined `changes[].path`; `session-reference` → joined reference labels.
- `form` decides the body (`ContextBody.tsx`): `instructions`, `catalog`, `snapshot`, `notice`,
  `relay`, `recall`; any absent/unknown/malformed form → `OpaqueBody` (model-facing text as `<pre>`
  + the remaining source fields as a key/value list). Text bodies are bounded at 20 000 chars
  (`json.truncated` footer); list bodies at 200 entries (`message.context.catalog.more`).
- **`notice`** is the important one: the collapsed row shows `source.summary` verbatim and the body is
  the model-facing text. Example producers and their summaries:
  - `model-selection`: `"plain/model → capable/model"`
  - `repeat-tool-reminder`: `` `${toolName} × ${count}` ``
  - `plan-mode`: the plan notice text
  - `tool-jobs`: see below

### 5.2 Background-job notices

`jobs/tool-jobs/src/index.ts` injects a `context` message with
`source = { kind:'plugin', plugin:'tool-jobs', form:'notice', summary: completionSummary(snapshot) }`
when a job settles and has not been reported. Exact copy:

- collapsed summary: `` `${snapshot.kind} ${snapshot.label} [status: <status>]` `` (or
  `[status: <status>, <detail>]`), e.g. **"bash pnpm test [status: completed]"**.
- expanded body text: `` `background job ${id} (<kind>: <label>) finished [status: …]. Read its output with job_output.` ``,
  bounded to the job's `outputLimitBytes` with `[notice truncated]` when needed.

So a background-job completion is **not** a bespoke card: it is a `Context injection` disclosure row
with a pink/neutral disclosure chrome, producer label `tool-jobs`, and the status line in the summary.
`ui-jobs` itself contributes only a **session-header** action (`JobListAction.tsx`) — it does not add
a transcript row. The `job_output` / `job_kill` **calls** are ordinary generic tool rows; a
background `bash` acknowledgement is an ordinary collapsed Bash row.

### 5.3 Errors

- **Turn error** (`turn-error` node, `MessageItem.tsx#TurnErrorItem`): `role="status"`, a red
  `StateDot`, `message.turnError` = **"This turn failed"** in `state-error-primary` weight 600, then
  the failure message in `label-secondary`, then the provider code in a `<code>` chip
  (`turnErrorCode`). `code === 'AUTH'` replaces the message with `message.failure.auth` =
  **"API key is invalid"** (the raw message is deliberately not retained client-side).
- **Max-tokens notice** (`turn-max-tokens`): same row shape with an amber `StateDot`,
  title `message.maxTokens` = **"Output token limit reached"**, hint `message.maxTokens.hint` =
  "The reply was cut off; earlier output is preserved in the conversation. Send \"continue\" to let
  the model resume." It is anchored at `seq + 0.05` so the `turn-tail` stays last and keeps Branch
  enabled.
- **Tool errors:** collapsed summary replaced by the first line of the error/output in
  `state-error-primary`; red `StateDot`; expanded OUT text red.
- **Model retry** (`model-retry` node, `MessageItem.tsx#ModelRetryItem`): a native `<details>`; the
  `<summary>` reads `message.retry.status` = **"{label} ({retry}/{maximum}) · {seconds}s"** where
  label ∈ `message.retry.active` "Retrying model request" / `.cancelled` "Model request retry
  cancelled" / `.started` "Retried model request" / `.scheduled` "Waiting to retry model request",
  and `maximum` is the number or `'∞'` for `mode !== 'normal'`. While scheduled it shows a live
  250 ms countdown and a text shimmer (`retry-shimmer 1.6s`). Expanded: `message.retry.delay` =
  "Retry delay: " + `duration.milliseconds`, and `message.retry.failure` = "Failure reason: " + the
  failure message (AUTH → "API key is invalid").
- **Compaction** (`compaction` / `manual-compaction`): one dim 24 px sticky row, title
  `message.compaction` = **"Context compacted"** (or `message.compaction.commandTitle` = "compact"),
  summary `message.compaction.completed` = "Compacted {items} history items (~{tokens} tokens)" or
  `message.compaction.expand` / `.unavailable`; expandable iff a summary exists, body is markdown.
- **Unknown surface** (`unknown` node, fallback in `conversation-nodes/fallback.ts`): a `JsonBlock`
  labelled `message.unknownSurface` = **"Unknown surface event: {type}"**. Unknown Assistant blocks →
  `message.unknownBlock` = "Unknown content block" in a `JsonBlock`.

### 5.4 Interrupted assistant tail

Two interruption shapes, both rendered as an ordinary `assistant-step` row:

1. **Durable interrupted message** (`assistant/message` with `interrupted: true`): `status:
   'interrupted'`, `finalNode.interrupted = true`.
2. **Frozen partial**: the step/turn closes while only `assistant/live-chunk` text exists. `assistant.ts`
   synthesizes a `finalNode` at `boundary.seq + CHAT_SYNTHETIC_SEQ_OFFSETS.interruptedAssistant`
   (-0.9) with `interrupted: true` and the accumulated partial blocks.

`AssistantMarkdown` renders the blocks normally and appends an inline **"Stopped"** chip
(`message.stopped`, `span.stopped`: 11 px, 6 px radius, `interactive-bg-hover` background,
`label-tertiary`) — no animation, no icon. An interrupted tool tree yields synthetic
`Interrupted`/`interrupted` error results and amber "Stopped" dots. Because such a turn usually has no
answer step, its process group is **not foldable** (see §1.3). If the turn instead settles
successfully, the `turn-tail` renders; interruption of an unfinished tool only changes those rows'
state.

---

## 6. Markdown support matrix

Renderer: `ui-primitives/src/markdown/`, a direct mdast→React pipeline (not react-markdown).
Grammars (`parse.ts`):
- **streaming**: `gfm()` + `cjkFriendlyStrong()` + `gfmFromMarkdown()` — **no math** (incomplete TeX
  never flashes a KaTeX error mid-stream).
- **settled**: the same **plus** `math()` + `mathCompatibility()` + `mathFromMarkdown()`, so the
  streaming and settled arms agree on block boundaries except where math starts.

| Feature | Supported | How / special component |
| --- | --- | --- |
| Paragraphs, headings h1–h6 | yes | `<p>`, `createElement('h'+depth)`; CSS type ladder |
| Bold / italic / strike | yes | `<strong>` (600), `<em>`, `<del>`; CJK-friendly `**` handling (`cjkFriendlyStrong.ts`) |
| Links | yes | allowlist http/https/mailto; other/relative/fragment become plain text (no `<a>`). External links get `target=_blank rel="noopener noreferrer"` and a leading `LinkIcon` glyph (skipped when the anchor wraps only images) |
| Images | yes | absolute http/https only, **or** a `MarkdownPathImages` vocabulary rewrite. Local `/abs/path` images are rewritten to `/api/file?path=…` by `AssistantMarkdown.localPathMediaUrl` on http(s) pages. Failed/unknown destination → italic alt text (`imageAlt`). `loading=lazy decoding=async referrerPolicy=no-referrer` |
| Inline code | yes | `<code>` pill; line endings → spaces; an inline code token that is exactly an http(s) URL gains a safe anchor |
| **File mentions** | yes (DSH extension) | `MarkdownFileMentions.resolve(token)` (`render.tsx`). The vocabulary is injected by `ui-deliverables` via `ctx.chatFileMentions` for the closing turn; a recognised token renders `<code><button class="fileMention" title=fullPath aria-label=…><LinkIcon/>token</button></code>`. **Only applied on the settled render** (streaming renders pass `fileMentions: undefined`). Not a `@`-prefix syntax — the resolver decides which tokens are files |
| Fenced code blocks | yes | `CodeBlock` (shiki), language from the info string, copy button; streaming fences incremental (`incremental.ts`, tail re-tokenises only appended text); empty fence keeps a stock `<pre><code class="language-x">`. A ` ```math ` fence becomes display TeX only when settled |
| Tables (GFM) | yes | `<div class="tableScroll [md-table-wide]"><table>…`; ≥4 columns (and not inside a blockquote) gets the `md-table-wide` hook which the transcript widens to the full column; cell `text-align` inline styles; `<4` columns fill the column |
| Task lists (GFM) | yes | `<ul class="contains-task-list">`, `<li class="task-list-item">`, disabled `<input type=checkbox>` |
| Nested lists / ordered lists | yes | tight/loose handling, `start` attribute, nested `<ol>` inside lists gets `list-style-position:inside` |
| Blockquotes, thematic breaks, hard breaks | yes | `<blockquote>`, `<hr>`, `<br>` |
| Reference links/images | yes | definitions collected document-wide; first definition wins. Unresolved reference renders its literal bracketed source text |
| Footnotes (GFM) | yes | reference renders a numbered `<sup>` only (the in-page anchor fails the protocol allowlist); a trailing `<section data-footnotes class="footnotes">` with `<h2 class="sr-only">Footnotes</h2>` and per-reference `↩` backrefs (with `<sup>` for 2nd+). Known streaming deviation: a reference whose definition is across the freeze boundary renders literally until the settled full parse |
| Raw HTML | **literal text** | no HTML parser in the pipeline; `node.value` is emitted as text. No HTML ever enters the DOM |
| Math | yes | settled only. `$…$`, `$$…$$` (micromark-extension-math) plus `\(…\)` and `\[…\]` (mathCompatibility). Rendered by KaTeX → React (`katex.tsx`), `strict` render → `strict:'ignore'` retry → error span. No trusted commands. Display math in `.katex-display` |
| Mermaid / diagrams | **NO** | no mermaid, flowchart, or diagram handling anywhere in `ui-primitives`/`ui-chat`. A ` ```mermaid ` fence renders as a plain highlighted (or unhighlighted) code block |
| Citations | **NO** dedicated citation syntax | Citations are ordinary links; `web_search`'s source list is a separate `WebBlock` `<ol>`, not markdown footnotes |
| Tags (`#tag`) | **NO** | no tag construct |
| Frontmatter | no mapping | renders nothing (documented default for unmapped mdast types) |
| Definition / footnote-definition blocks | render nothing in place | consumed as reference targets |

Security posture worth preserving: `sanitizeUrl` allowlists `http:`, `https:`, `mailto:`; images
additionally require absolute http(s) (or a vocabulary-vouched `http(s)/blob:/data:` URL); relative
and `javascript:`/`data:`/`file:` links render as plain text.

`MarkdownText` props: `{text, streaming?, labels, fileMentions?, pathImages?}`. `labels` must be
reference-stable per locale revision (`markdownLabels(t)` is memoized) or the streaming cache is
discarded. Wrapper is `div.markdown`; the settled render appends `'\n'` text nodes between blocks
(DOM parity fixtures pin this).

---

## 7. Streaming presentation

### 7.1 What is shown while tokens stream

- **There is no caret, no blinking cursor, and no shimmer on the streaming text itself.**
  `MarkdownText` renders the growing text like any other text; there is no cursor element anywhere in
  the markdown path. (Verified: no `caret`/`cursor-blink`/`data-streaming` CSS rule exists.)
- The turn-level live indicator is the separate `TurnStatus` row pinned to the bottom of the
  column while `running`: **"Deep diving..."** with a blue gradient text shimmer
  (`dsh-turn-status-shimmer`, `background-clip:text`) and an elapsed clock appearing at ≥15 s. This
  row is the only always-on "something is happening" affordance.
- Per-row activity: **running tool rows and the running Think row carry the 300 px sweep glare**
  (`dsh-tool-row-sweep` / `dsh-reasoning-row-sweep` / `dsh-bash-row-sweep`, 2.6 s ease-out infinite);
  a running terminal card's dot is the blue 8-cell chase; a scheduled model retry's text shimmers.
- A streaming fenced code block highlights incrementally as it grows; TeX stays literal until the
  settled swap.

### 7.2 Where the streaming text lives relative to the durable message

**Same DOM node, same React element, same component instance.** The `assistant-step` Chat node is
keyed `"<turn>:<step>"` and exists from `step/start`; `AssistantNodeView` renders it with
`streaming={data.status === 'running'}`. There is no separate "live bubble" and no optimistic
duplicate. The block array is the message's block array, mutated by `assistant/live-chunk`
(`updateChunk` in `assistant.ts`, mirrored by `PartialAccumulator` in `partial.ts`), and replaced
wholesale by the durable `assistant/message` (`settleMessage`). Because the node key does not change,
React reconciles the same `<AssistantMarkdown>`/`<ReasoningRow>` instances across the
stream→committed transition.

### 7.3 Transition from live stream to committed message (flicker avoidance)

- **Identity stability**: the node key is `turn:step`; the settled message's `anchorSeq` becomes the
  durable event's seq, but the key is unchanged, so nothing remounts.
- **Incremental markdown cache**: `StreamingRenderer` freezes all but the trailing 2 blocks
  (`UNSTABLE_TAIL_BLOCKS = 2`) and caches them as React elements keyed by **absolute source offset**
  (`blockKey`), so a block crossing the freeze boundary reconciles instead of remounting. On the
  settled render `MarkdownText` sets `streaming=false`, drops the streaming renderer, and re-parses
  the full document once; blocks keep their offset keys, so the visible text does not jump.
- **Self-healing deltas**: reference links / footnotes whose definitions cross the freeze boundary, plus
  `fileMentions` and `pathImages` (deliberately withheld while streaming), appear only on the settled
  render. This is the one intentional visible change at settle time.
- **Retry reset**: on `llm/retry`, `resetForRetry` clears the accumulated blocks and sets
  `hidden: true`; the assistant row stays mounted but renders nothing until new visible chunks arrive,
  so the old partial does not linger behind the new attempt.
- **Interruption**: if the step/turn closes without a durable message, the partial is frozen into a
  synthetic `finalNode` and the same row gains the "Stopped" chip — again no remount.
- **Scroll follow**: `ChatView` uses a `ResizeObserver` on the column plus an at-bottom flag to keep
  the viewport pinned while the row grows; it deliberately does not re-pin on every render (only when
  the flow tip signature moves or a new user/steering/echo row arrives).
- `AssistantMarkdown` renders the wrapper with `data-streaming={streaming || undefined}` but **no CSS
  rule consumes it today**; it is a hook, not a visual.

---

## 8. Porting gotchas (ranked)

1. **Flat node list, post-hoc grouping, per-step assistant rows.** A turn is not a container and the
   answer is not one message: a turn with 3 model steps produces 3 `assistant-step` rows plus separate
   `tool-call` rows, all seq-ordered in one global list. The process group is a *visibility projection*
   that only engages after `turn/end`; the web collapses it by setting `hidden="until-found"` on
   member rows, the port by dropping them from the row list (gotcha 2).
2. **Rows stay mounted while grouped (web).** Hiding is an attribute, not an unmount: keep them in
   the composition (find-in-page reveal and state preservation depend on it) and only hide. The
   Android port drops folded members from the rendered row list instead; the reducer still holds
   the entries, so expanding restores them, and there is no find-in-page reveal
   (`model/TurnProcess.kt:251-266`).
3. **`thinking`/reasoning is per block, per step**, collapsed by default, title "Think" (the port
   shows `Thinking…` while the block streams — `ui/components/ChatRows.kt:690`).
4. **The disclosure label is counts only** ("3 tool calls · 1 message · 1 subagent" or "Thought for a
   while") — no duration, no step count. Turn duration is the separate assistant-footer "Ran for
   12s" pill.
5. **No caret / no copy toast / no retry-edit-quote message actions.** Copy is a 1 s icon swap; the
   only durable message action is Branch; the only retry UI is the automatic model-retry row. The
   Android port keeps the 1 s copy swap and the model-retry notice but has no Branch and no usage
   pill — its footer is clock + copy + "Ran for …" (`ui/components/ChatRows.kt:419-486`).
6. **Bash stdout and stderr are interleaved into one string** and the exit status is stripped from the
   tail of the result and re-rendered as a header pill + gutter dot.
7. **Tool cards are keyed by wire tool name**, and two names sharing a variant (e.g. `bash`/`pwsh`)
   still get distinct titles/icons; unknown or auto-review-denied names fall back to generic.
8. **File mentions are resolver-driven**, not a markdown `@` syntax, and are settled-render-only.
9. **Diff hunks may come from `meta.diffs`** (host-applied) or be derived from args (intended); the
   sign prefix is CSS-generated (`- `, `+ `, two spaces), not part of the stored text.
10. **Compact mode is the default**; Normal mode always expands process rows. The Android port has
    no mode switch and always folds (`ui/SettingsContent.kt`, the "Conversation display" row).
