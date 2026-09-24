# Journal compaction & context-injection shapes (ground truth)
Read-only extraction from live DSH session journals under `~/.dsh/sessions/`:
decompressed every `session*.jsonl.zstd` (`zstd -dc`), parsed JSONL, saved scratch
scans under `.probe/`. All 165 journals were searched for `"type":"compaction/…`;
the 23 containing any were re-read for the structural tables. Long prose is
truncated to <=200 chars with its real length; no journal was modified.

## (a) Inventory and one complete example per compaction type
Distinct types (whole corpus):

| type | records | sessions | notes |
| --- | --- | --- | --- |
| `compaction/start` | 59 | 23 | opens a compaction region |
| `compaction/end` | 59 | 23 | closes it; may carry `error` |
| `compaction/summary` | 39 | 23 | the LLM summary; expensive payload |
| `compaction/prune` | 4 | 2 | intra-step token pruning, unrelated |

`compactionId` is present and **stable across start/summary/end**: all 59 IDs
group into exactly one ordered `start … [summary] … end` sequence (seq strictly
increasing, gap 1–6, median 4). `compaction/prune` has **no** `compactionId`,
no `turn`, no `sourceCommandId`.

`compaction/start` — `data` = 3 keys, all present on every record; `sourceCommandId`
is optional in the type but present on 43/59:
```json
{"type":"compaction/start","seq":355,"time":1790087484886,
 "data":{"compactionId":"4ffe9aef-0df6-47c3-8b57-e2680fd541ec",
         "sourceCommandId":"cmd-a1fedad3-11","turn":null}}
```

`compaction/end` — `data` = `compactionId:str`, `turn:int|null`, `sourceCommandId?:str`,
`error?:str`. `error` present on 20/59 (all of which lack a summary):
```json
{"type":"compaction/end","seq":684,"time":1790002702227,
 "data":{"compactionId":"9c556fce-dacd-4d84-a3f6-aeacd4e14929",
         "sourceCommandId":"cmd-ed687d75-1","turn":null,
         "error":"messages.1.3: `tool_use` ids were found without `tool_result` blocks immediately after: call_1717d8c213a142359de1, call_4d4bf083d7694915bbac. Each `tool_use` block must have a corresponding `tool_resu...<len=230>"}}
```

`compaction/prune` — `data` = 3 keys only; `shadowedSeqs` may be one seq:
```json
{"type":"compaction/prune","seq":6233,"time":1790084180346,
 "data":{"shadowedRange":{"start":5540,"end":5540},"shadowedSeqs":[5540],"shadowedTokenCount":12512}}
```

**Is `turn` null?** Sometimes. `start` and `end` always carry `turn`, and
`start.turn === end.turn` on all 59 IDs. 43/59 are `null` (manual `/compact`,
runs between turns); 16/59 are the integer of the open turn (automatic, runs
inside a turn). `compaction/summary` and `compaction/prune` have **no** `turn` key.

Group attribute cross-tab (59 groups) — perfect correlations:
`sourceCommandId` present <=> `turn === null`; summary present <=> no `end.error`:

| sourceCommandId | turn | summary | error | count |
| --- | --- | --- | --- | --- |
| yes | null | yes | no | 35 |
| yes | null | no | yes | 8 |
| no | integer | no | yes | 12 |
| no | integer | yes | no | 4 |

## (b) `compaction/summary` — full `data` key list
Exactly 12 keys (union); order as emitted, value type in brackets:
```
compactionId        [str]     always (39/39)
sourceCommandId     [str]     OPTIONAL (35/39)
summary             [list]    always — exactly one {"type":"text","text":str} block
rawOutput           [list]    always — 38x [{"type":"reasoning","text":""},{"type":"text",...}]
                              1x [{"type":"text",...}]
llmStreamCall       [bool]    always true
shadowedRange       [dict]    always {"start":int,"end":int} (seq range)
shadowedSeqs        [list]    always, int[] of shadowed seqs (length 1..1488)
shadowedTokenCount  [int]     always — tokens removed by this compaction
provider            [str]     always ("deepseek-official" 33, "dsh-local" 6)
model               [str]     always ("deepseek-flash" 33, local 1B models 6)
maxTokens           [int]     always 8192
usage               [dict]    always — inputTokens, outputTokens, cacheReadTokens,
                              cacheWriteTokens (33/39 — absent on 6), totalTokens
```

**There is no "after" token count.** The only pre/post numbers are
`shadowedTokenCount` (what the compaction *removes*: min 1432, median 499 844,
max 663 706) and the summarizer call's `usage` (`cacheReadTokens` dominated,
e.g. 757 120). Real summary text is `data.summary[0].text`
(min 817 / median 11 428 / max 15 262 chars). Full example (elided list):
```json
{"type":"compaction/summary","seq":4233,"time":1790105829973,
 "data":{
  "compactionId":"b509a9b9-c58c-4622-bc9e-61b71383bccd",
  "sourceCommandId":"cmd-a1fedad3-28",
  "summary":[{"type":"text","text":"## Primary Request and Intent\n- Original: \"create a fully fledged android app ...<len=15262>"}],
  "rawOutput":[{"type":"reasoning","text":""},{"type":"text","text":"## Primary Request ...<len=15262>"}],
  "llmStreamCall":true,
  "shadowedRange":{"start":8,"end":4223},
  "shadowedSeqs":[8,9,10,... <1488 ints> ...,4223],
  "shadowedTokenCount":663706,
  "provider":"deepseek-official","model":"deepseek-flash","maxTokens":8192,
  "usage":{"inputTokens":474,"outputTokens":4354,"cacheReadTokens":757120,
           "cacheWriteTokens":0,"totalTokens":761948}}}
```

`notices-and-fold.md:116-124` correctly lists these; ground truth adds that
`rawOutput`/`llmStreamCall`/`maxTokens`/`usage` were present on all 39, and that
`cacheWriteTokens` is the one optional `usage` sub-key.

## (c) Ordering relative to `turn/start` / `turn/end`
Session `session-2615b629-…` (manual, `turn:null`), ±3 records:
```
  4228 step/end                 4232 session-log-deepseek/delivery-accepted
  4229 turn/end   turn=16       4233 compaction/summary  cid=b509a9b9…
  4230 command/run              4234 user/message          (the checkpoint)
<== 4231 compaction/start        4235 compaction/end  cid=b509a9b9…
                                4236 command/done
                                4237 agent/inbox/spliced
                                4238 turn/start   turn=17
```

Parent `turn/end` (4229) → `compaction/*` (4231-4235) → next `turn/start` (4238):
**between turns**, no open turn. Automatic compaction is different — session
`86e8a4c3-…` seq 326 has `start.turn=9` and sits **inside** open turn 9
(`turn/start` seq 314 … `turn/end` seq 336), after `step/end` 325:
```
  324 tool/result   326 compaction/start  turn=9   cid=fd09ce5a…
  325 step/end       327 compaction/summary turn=None cid=fd09ce5a…
                     328 user/message (checkpoint, source {kind:plugin,plugin:compact,compactionId})
                     329 compaction/end    turn=9   cid=fd09ce5a…
                     330 step/start
```

Corpus-wide: of 161 compaction records, 121 fall in a closed-turn gap and 40
fall inside an open turn. Rule: `turn:null` groups (manual) are always between
turns; `turn:<n>` groups (automatic) are inside turn `n`. `compaction/prune`
appears inside a step between `tool/result` records (e.g. seq 6229/6231), never
near a turn boundary.

## (d) `user/message` with `data.source.kind !== 'user'`
All such rows share `data` keys `[content, id, role, source]` with `role:"user"`
and `content` a `ContentBlock[]` of `{"type":"text"}` blocks; they add a top-level
`surfaceOp` (`{op:"append"|"replace", startSeq?, endSeq?}`). Source kinds and
counts: plugin 692, agent-instructions 498, agent-message 142, subagent-settled
124, skill-catalog 53, goal 20 (plus user 1738). `source.summary` exists **only**
on `plugin` (the notice/snapshot variants) and `subagent-settled`.

`agent-instructions` — source `kind, form, changes` (350) or `kind, form,
baseline, baselineIdentity, changes` (148); content 1 text block:
```json
{"type":"user/message","seq":…,"data":{"role":"user","id":"…",
 "content":[{"type":"text","text":"<system-reminder>\nThe following workspace instructions may be relevant …<len=214>"}],
 "source":{"kind":"agent-instructions","form":"instructions","baseline":true,
   "baselineIdentity":"{\"projectRoot\":\"\",\"projectRootMarkers\":[\".git\"],…<len=215>",
   "changes":[{"action":"set","scope":"user-global\u0000AGENTS.md","path":"~/.dsh/AGENTS.md","digest":"6f6fc829…"}]}}}
```

`agent-message` — source `kind, form:"relay", senderSessionId`; no summary;
content = 2 blocks (a "sent a message:" header + the relayed prose):
```json
"source":{"kind":"agent-message","form":"relay","senderSessionId":"93deb568-…"}
"content":[{"type":"text","text":"Agent 93deb568-… sent a message: "},
           {"type":"text","text":"DSH remote-auth package audit — 8 packages, …<len=214>"}]
```

`subagent-settled` — source `kind, form:"notice", senderSessionId, summary` (the
collapsed-row string); content 2-3 blocks (109 have 3):
```json
"source":{"kind":"subagent-settled","form":"notice","summary":"Background subagent bae4b0fe-… failed before it finished.","senderSessionId":"bae4b0fe-…"}
"content":[{"type":"text","text":"Background subagent … failed before it finished."},{"type":"text","text":"It left no closing message."}]
```

Other kinds: `goal` = `{kind,goalId,revision,round}` + 1 text block
(`<goal_round>…`); `plugin` = `{kind,plugin,form,summary?|sections?}` (e.g.
`plugin:"@deepseek-ai/dsh-system-prompt"`, `form:"snapshot"`,
`sections:[{name,text}]`); `skill-catalog` =
`{kind,form:"catalog",entries:[{name,description}],update?}`.

**Special `plugin` variant — the compaction checkpoint** (39/39, keys
`kind,plugin,compactionId` plus `sourceCommandId` on the 35 manual ones;
`plugin:"compact"`, no `form`). Content is always 3 text blocks: a fixed header
(`"This is an automatically generated checkpoint condensing an earlier span…"`),
the summary prose, and `"</compacted-summary>"`. It carries
`surfaceOp:{op:"replace",startSeq,endSeq}` and `sourceEventSeqs:[…]`:
```json
{"type":"user/message","seq":…,"sourceEventSeqs":[326,…,257],
 "surfaceOp":{"op":"replace","startSeq":11,"endSeq":257},
 "data":{"role":"user","id":"213c218d-…","content":[
   {"type":"text","text":"This is an automatically generated checkpoint condensing an earlier span …<len=321>"},
   {"type":"text","text":"## Primary Request and Intent\n- User wants to add custom script meters …<len=1590>"},
   {"type":"text","text":"</compacted-summary>"}],
  "source":{"kind":"plugin","plugin":"compact","compactionId":"fd09ce5a-b3b6-49e0-b7e7-df8a85901ffc"}}}
```

## (e) Explicit "context injection" records
The only type whose name contains `context` is **`request/context`** (200 records;
name search also covered `inject`: no type matches). It is a per-request LLM
routing record, 78x `{provider,model,contextWindow}`, 122x plus
`systemPromptUpdate:"in-history"`:
```json
{"type":"request/context","seq":12,"time":1790009196698,
 "data":{"provider":"deepseek-official","model":"deepseek-flash",
         "contextWindow":1000000,"systemPromptUpdate":"in-history"}}
```

Context *injection* is structural, not a type name: appended `user/message` rows
with non-`user` `source.kind` (§d) plus `surfaceOp`, and the checkpoint's
`surfaceOp:"replace"` + `sourceEventSeqs`, which shadow `seq 11..257` and
substitute the summary. `notices-and-fold.md` §Q4 matches this.

## (f) Recency and coverage
23 of 165 journals (13.9%) contain compaction records — the same 23 for every
type. Newest evidence is current; most recent mtimes:
```
2026-09-23 00:27  compaction*  --home-user-var-work-dsh-android--/session-2615b629-… (current parent; 4 groups)
2026-09-23 00:27  -            --home-user-var-work-dsh-android--/3a936e15-… / 62ac33ba-… / c88df12a-… / f19fc633-…
2026-09-22 21:31  compaction*  --home-user-tmp--/session-fd58fea2-…
2026-09-22 19:37  compaction*  --root--/session-abe1a49b-… (the 3 prune records)
```

The parent session's 4 groups are 2 manual successes + 2 automatic (see §c), and
`session-86e8a4c3-…` is the automatic mid-turn example. One new empty journal
(the scanning subagent's own session) appeared during the run; it has none.

## Implications for the Android client
1. **`compaction/start|summary|end` are region markers, not rows.** Join by
   `compactionId`; render one marker anchored at the checkpoint `user/message`
   (`source.plugin == "compact"`), whose seq/time is the row position. Never
   render a marker when the checkpoint is absent (`compaction/end.error` groups).
   The port does draw one transient row from `compaction/start` when it carries a
   `sourceCommandId` ("Compacting context…"), retired by the checkpoint or by
   `compaction/end` (`model/Transcript.kt:545-554`, `:578`).
2. **Counts first, text second.** `shadowedSeqs.length` + `shadowedTokenCount`
   are the only pre/post numbers; there is no "after" size. `data.usage` is the
   summarizer call's cost. Text is `data.summary` (`ContentBlock[]`, concatenate
   `type:"text"`; always one block, 817-15 262 chars); do not fall back to
   `rawOutput`, which repeats it after an empty `reasoning` block.
3. **`turn` is not always null.** Manual groups (with `sourceCommandId`) are
   between turns; automatic groups (`turn:<n>`) sit inside open turn `n`.
   `summary` has no `turn`. The port never folds a compaction marker into the
   turn's process — markers are notices and stay visible
   (`model/TurnProcess.kt:212-219`).
4. **The checkpoint is a normal `user/message`** — `role:"user"`,
   `source:{kind:"plugin",plugin:"compact",compactionId}` (+`sourceCommandId` on
   manual), 3 text blocks (header/summary/`</compacted-summary>`), and
   `surfaceOp.replace {startSeq,endSeq}` + `sourceEventSeqs` naming the shadowed
   seqs. Exclude it from generic context rows and the user bubble path. The port
   skips every `surfaceOp: replace` copy outright and does not read the shadow
   range (`model/Transcript.kt:306-335`).
5. **Other non-`user` kinds** route to a notice row via `source.kind`,
   `source.summary` (only plugin notices and subagent-settled) and `content`
   prose; `source.form` is not read (`model/Transcript.kt:336-351`, `:836-856`).
   `content` is always a `ContentBlock[]`; some rows are structured (`changes[]`,
   `entries[]`, `sections[]`) — do not assume prose.
6. `compaction/prune` carries no ID/turn and is intra-step; it is internal
   bookkeeping, not a checkpoint.
