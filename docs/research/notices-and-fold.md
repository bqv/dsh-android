# DSH web client — non-conversational rows and turn-process folding

Provenance: every path/snippet below was read from the read-only checkout
`/home/user/bin/deepseek-harness`; paths are relative to that root. No secrets/tokens found.
Companion to `transcript-ui.md`, which already documents the process disclosure chrome and store.

## Q1 — Turn-process folding: members vs independent nodes

Not a container: every visible node is a sibling `div.flowItem`. Membership is per node,
`ui-chat/src/client/chat/ChatNodeSeat.tsx:69-73`:

```ts
const processMember = routedNode !== undefined && processWindowReady
  && !TURN_PROCESS_INDEPENDENT_KINDS.has(routedNode.kind)
  && routedNode.anchorSeq >= processSpec.processStartSeq
  && routedNode.anchorSeq < processSpec.answerAnchorSeq
```

`processWindowReady` (`ChatNodeSeat.tsx:62-68`) requires a process spec + presentation,
`compactTranscript`, `answerAnchorSeq !== null`, `turnClosed` and `!historyIncomplete`.
`processHidden = controllerInactive || (foldable && processMember && !processOpen)`
(`ChatNodeSeat.tsx:98`). Hidden members stay mounted; `useSearchableHidden` sets
`hidden="until-found"` and opens the group on `beforematch`.

Independent kinds — never members, always rendered even when folded
(`ui-chat/src/client/contract/turn-process.ts:20-33`): **`system-prompt`, `user`, `steering`,
`turn-process`, `turn-error`, `turn-max-tokens`, `turn-tail`**.

Everything else strictly inside `[processStartSeq, answerAnchorSeq)` is a member: `assistant-step`
(every step except the answer step), `tool-call`, `context`, `model-retry`, `command`,
`manual-compaction`, `compaction`, `unknown`. The answer `assistant-step` is at `answerAnchorSeq`,
so it is not a member; only its inline reasoning hides (`chat/AssistantNodeView.tsx:23-28`).

- The control state folds `turn/start`, `assistant/live-chunk`, `assistant/message`, `tool/call`,
  `tool/result`, `llm/retry`, `step/start`, `step/end`, `turn/end` (`turn-process.ts:212-227`).
- `processStartSeq`/`answerAnchorSeq`: `conversation-nodes/turn-process.ts:124-168`; the answer is
  the latest step's `assistant-step` with reply content and no tool call.
- `compactTranscript` = the `Compact` setting (`chat/ChatView.tsx:244`); `Normal` mode never folds.
  `foldable`/`hasExternalProcess`/`compactAnswer` come from
  `conversation-nodes/turn-process-presentation.ts:47-70`, which skips
  `TURN_PROCESS_INDEPENDENT_KINDS` when flagging external process. The controller node itself is
  independent but renders `null` while not foldable (`ChatNodeSeat.tsx:92-93`,
  `chat/TurnProcessNodeView.tsx:11`).
- Ordering: `orderedVisibleChatNodes` filters `visibility === 'visible'` and sorts by
  `(anchor, rank, originalAnchor, key)` (`chat-snapshot-builder.ts:400-413`).

## Q2 — `user/message` whose `source.kind !== 'user'`

There is no `conversation-nodes/context.ts`; the classifier is
`ui-chat/src/client/conversation-nodes/message.ts:47-64`. It drops compaction checkpoints first
(`!isCompactionCheckpoint(event)`, `isAppendSurfaceEvent` required) and maps every other non-`user`
source to a Chat node of kind `context`:

```ts
if (event.data.source.kind !== 'user') {
  return { kind: 'context', seq: event.seq, time: event.time, content: event.data.content,
    source: event.data.source, producer: contextProducer(event.data.source),
    form: contextForm(event.data.source) }
}
```

Surface-**replace** `user/message` events are excluded by `isAppendSurfaceEvent`, so they never
reach this node. Renderer: key `context` → `ContextMessageNodeView`
(`chat/register-node-renderers.ts:22-23`) → `ContextInjectionRow` (`chat/ContextInjectionRow.tsx`).

Title / icon (`ContextInjectionRow.tsx:38-44`):

```tsx
icon={producer.role === 'recall'
  ? <span data-context-recall-icon><ReferenceIcon kind="session" /></span>
  : <IconContextInjectionOutline16 size={14} />}
title={t(producer.role === 'recall' ? 'message.contextRecall' : 'message.contextInjection')}
```

- EN `message.contextRecall` = **"Session recall"**, `message.contextInjection` = **"Context
  injection"** (`ui-chat/src/client/locale.ts:150-151`); ZH `跨会话召回` / `上下文注入`
  (`locale.ts:39-40`).
- Collapsed chrome (`ContextInjectionRow.tsx:45-59`): when `producer.label !== null`, an
  aria-hidden dot + `<span data-context-source>{producer.label}</span>`, plus — only for
  `form:'notice'` — `<span data-context-summary>{summary}</span>`. `summary` = non-empty
  `source.summary`, else null (`ContextBody.tsx:533-537, 569-573`).

Label logic — `conversation-nodes/event-projection.ts:60-78`: `session-reference` → role `recall`,
label = joined `references[].label`; `agent-instructions` → label = joined `changes[].path`;
`plugin` → label = `source.plugin`; `skill-invocation` → label = `source.name`; every other
readable kind (the merge-extensible default arm) → `{ role: 'inject', label: kind }`; a source with
no readable `kind` → `{ role: 'inject', label: null }`.

With blank prose and no `source.summary` the row is **not** empty and does **not** fall back to a
generic "Context" word: it shows the title plus the raw `source.kind` as the label (default arm).
Only when the source has no readable `kind` at all is `label` null, the dot+label is dropped and
the bare title remains. The expanded body for an unknown/absent form is `OpaqueBody` = model-facing
text (`<pre>`, none when blank) + remaining `source` fields as a key/value list with `kind` (and
`form` when a form rendered) hidden (`ContextBody.tsx:88-108, 168-179`). Tests pin both accessible
names: `'上下文注入'` for `label: null`, `^上下文注入\s*agent-message$` for `agent-message`
(`ui-chat/tests/chat-branch-tails.client.spec.tsx:430, 770`).

## Q3 — Compaction

- `conversation-nodes/compaction.ts` handles **automatic** compaction only
  (`sourceCommandId === undefined`): `compaction/start` (role `start`),
  `compaction/summary`/`compaction/end` (role `update`), and the checkpoint `user/message` whose
  source is `{kind:'plugin', plugin:'compact'}` (`compaction.ts:34-48`; recognizer
  `command.ts:78-94`). `conversation-nodes/command.ts` owns `command/run`/`command/done`; a
  `compact` command plus its correlated checkpoint becomes `manual-compaction`
  (`command.ts:205-216`).

`buildViewNode` (`compaction.ts:51-56`) returns `null` unless `state.checkpoint` exists; otherwise
`chatNode(context, 'compaction', marker.seq, marker)` with `marker = compactSummary(state.summary,
state.checkpoint)`. So `compaction/start` alone, `compaction/summary` alone and `compaction/end`
alone render **nothing** (no automatic "compacting…" row). The marker appears only when the
replacement `user/message` checkpoint lands, using the checkpoint's `seq`/`time`.
`compaction/end.error` is never surfaced by Chat (trajectory shows running/complete/error
separately).

`compaction/summary` `data` (`packages/compaction/compaction/src/types.ts:34-67`): `compactionId`,
optional `sourceCommandId`, `summary: ContentBlock[]`, `shadowedRange`, `shadowedSeqs`,
`shadowedTokenCount`, `provider`, `model`, optional `maxTokens`, optional `usage`, optional
`rawOutput`, `llmStreamCall`. The Chat marker keeps only (`command.ts:102-132`; type
`ui-conversation/src/client/contract/records.ts:182-197`): `kind:'compaction'`, `seq`, `time`,
`summary` (concatenated `type:'text'` text, `null` when blank), `summaryEventSeq`,
`shadowedItemCount` (`shadowedSeqs.length`, all non-negative safe ints), `shadowedTokenCount`
(same validation). Provider/model/usage/maxTokens/rawOutput are dropped from Chat (trajectory's
`requestFromState` uses them: `ui-trajectory/src/client/trajectory-compaction-definition.ts:40-78`).

Renderer `chat/CompactionItem.tsx:31-76`:
- `<button>`, `disabled={!expandable}`, `expandable = node.summary !== null`.
- `IconApiOutline14` (`data-compaction-icon="context"`) + chevron
  (`data-compaction-disclosure="expanded"|"collapsed"`).
- Title `title ?? t('message.compaction')` = "Context compacted"; manual passes
  `t('message.compaction.commandTitle')` = "compact".
- Summary text: both counts non-null → `t('message.compaction.completed', { items, tokens })` =
  **"Compacted {items} history items (~{tokens} tokens)"**; else `fallbackSummary ??
  (expandable ? t('message.compaction.expand') : t('message.compaction.unavailable'))` =
  "View compaction summary" / "Compaction summary unavailable" (`locale.ts:164-169`).
- Click toggles `expanded`; when open and `summary !== null`, body =
  `<MarkdownText text={node.summary} labels={markdownLabels(t)} />`.
- Manual without a landed checkpoint: `CompactionCommandCard` renders `GenericCommandCard` with
  `runningSummary` = "Compacting context…" while running, else the command settlement text
  (`chat/CompactionCommandCard.tsx:13-26`; `CommandNodeView.tsx:27-39`).

Membership: `compaction` is **not** in `TURN_PROCESS_INDEPENDENT_KINDS`, so automatic compaction
enclosed in an open turn (owner `current-turn`,
`packages/compaction/compaction-basic/src/region.ts:200-211`) is turn-located and its checkpoint
seq falls inside the process window → a process member that hides when folded. Manual `/compact`
runs with `turn: null`, requires no open turn, and its `manual-compaction` node is session-located
→ always top-level.

## Q4 — "Context injection"

"Context injection" is a UI label, not a record kind: the title `message.contextInjection` of every
non-`user` `user/message` row whose producer role is `inject` (node kind `context`); role `recall`
producers use `message.contextRecall` (`ContextInjectionRow.tsx:44`; roles in
`ui-conversation/src/client/contract/context-producer.ts:11-26`). `contextForm` accepts only
`instructions, catalog, snapshot, notice, relay, recall` (`event-projection.ts:37-53`); anything
else, or a declared form with unreadable required fields, → `form: null` → opaque, with the
`data-context-form` marker omitted (`ContextBody.tsx:551-592`).

Recognized `source.kind` values (format list `packages/session/session-format-v2-to-v3/src/payload.ts:10`; each registered via merge-extensible `MessageSourceMap`):

| kind | producer projection | form → Chat body |
| --- | --- | --- |
| `user` (incl. `user-rpc` key) | user/steering bubble, never a context row | — |
| `plugin` | label = `source.plugin` | optional `form`; `plugin:'compact'` replace intercepted by command/compaction |
| `agent-instructions` | label = joined `changes[].path` (`context/agent-instructions/src/state.ts:37-46`) | `instructions` → file list + prose |
| `session-reference` | role **recall**, label = joined `references[].label` (`context/session-reference/src/types.ts:13-32`) | `recall` → kept/omitted/truncated counts + prose |
| `skill-invocation` | label = `source.name` (`skill/skill/src/index.ts:145-152`) | `instructions` → instruction body |
| `skill-catalog` | default → label `"skill-catalog"` (`skill/tool-skill/src/index.ts:33-44`) | `catalog` → entry list, "Replacement catalog" notice |
| `goal` | default → label `"goal"` (`goal/goal/src/domain.ts:47-53`) | no `form` → opaque: prose + `goalId`/`revision`/`round` fields |
| `webhook` | default → label `"webhook"` (`webhook/webhook/src/types.ts:73-82`) | `notice` → `summary` on the collapsed row |
| `agent-message` | default → label `"agent-message"` (`subagent/subagent/src/continuation-messages.ts:15-21`) | `relay` → "From session {senderSessionId}" + prose |
| `subagent-settled` | default → label `"subagent-settled"` (`continuation-messages.ts:30-38`) | `notice` → `summary` on the collapsed row |
| `team-message` | default → label `"team-message"` (`experimental/agent-team/src/types.ts:114-121`) | no `form` → opaque |
| `coordinator`, `subagent-report` | historical kinds in the format list; no current producer found | opaque |

Observed: `plugin` = "Context injection · {plugin id}"; `agent-message` = "Context injection · agent-message"; `subagent-settled` = "Context injection · subagent-settled · {summary}"; `goal` = "Context injection · goal"; `session-reference` = "Session recall · {labels}" (session icon).

## What the Android client does

1. Fold after `turn/end` with a finalized answer, default closed
   (`model/TurnProcess.kt:203-204`). The web also gates on `Compact` mode, a `turn-process` control
   and complete history; the port has no mode switch and always folds
   (`ui/SettingsContent.kt`, the "Conversation display" row), and does not gate on a pending history page.
2. Fold the assistant's own process — tool calls, interim messages and to-do rows — plus nothing
   else. Human messages and every notice (`context`, `compaction`, `model-retry`, turn error) stay
   visible outside the group; the web hides them, and folding them here hid compaction entirely
   (`model/TurnProcess.kt:212-219`).
3. Folded members are dropped from the rendered row list, not hidden at zero height; the reducer
   keeps the entries, so expanding the group restores them (`model/TurnProcess.kt:251-266`).
4. Route appended `user/message` with `source.kind !== 'user'` to a notice row; a `plugin: compact`
   checkpoint becomes the compaction marker, and any other `surfaceOp: replace` copy is model-only
   (`model/Transcript.kt:306-363`).
5. One label line, no title and no role distinction: the row's text is a `tool-jobs` job label,
   else the non-blank `source.summary`, else the projected label — instruction paths, skill name,
   catalog count, goal round, relay sender — else the raw `kind`
   (`model/Transcript.kt:336-351`, `:817-856`). `source.plugin` is carried on the row's `kind` and
   only shows when the text is blank. The web's "Session recall"/"Context injection" titles are not
   ported.
6. `notice` rows show `source.summary` collapsed and the model-facing prose as the expandable
   detail (`model/Transcript.kt:346-349`).
7. `compaction/start`, `compaction/summary` and `compaction/end` are never rows: the marker is
   anchored on the checkpoint and enriched from the summary, and `compaction/end`'s error is never
   surfaced (`model/Transcript.kt:536-579`).
8. Marker label `"{title} · {N} history items (~{tokens})"` when both counts are valid, else "View
   compaction summary" / "Compaction summary unavailable"; expanding shows the summary as plain
   text (`model/Transcript.kt:905-918`, `ui/components/ChatRows.kt:1244-1254`). The web keeps the
   title and the "Compacted …" copy in separate elements and renders the body as Markdown; the port
   joins them with ` · ` and abbreviates the token count (`k`/`M`).
9. Manual `/compact`: "Compacting context…" from `compaction/start` until the checkpoint lands, then
   the marker titled "compact" (`model/Transcript.kt:545-554`, `:872`).
10. Compaction markers never fold; they are notices (see 2). The web folds an automatic compaction
    into its turn's group.
