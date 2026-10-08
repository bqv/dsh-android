# Background jobs, as 0.2.0-rc.2 actually serves them

The jobs seat went blank on every session. The cause was not the UI: the roster
it read had stopped being sent. This file records the probes that establish what
the host serves instead, because **the shipped `typert.*.js` descriptors disagree
with the running host**, and the first two things anyone will try — reading the
descriptors, or generalising from a neighbouring method — are both wrong here.

Everything below was run against the live harness on `127.0.0.1:8081`.

## Probing

Unary methods are a plain envelope; stream methods must ride the Remote mux
socket (`/api/remote.mux`), which is what `RemoteMux.kt` implements.

```sh
COOKIE=$(curl -sI http://127.0.0.1/ | sed -n 's/^[Ss]et-[Cc]ookie: //p' | cut -d';' -f1)

# unary
curl -s -X POST http://127.0.0.1:8081/api/<method> \
  -H "Cookie: $COOKIE" -H 'Host: 127.0.0.1:8081' -H 'Content-Type: application/json' \
  -d '{"type":"client-request","rpcId":"x","method":"<method>","payload":{"args":{…}}}'
```

```
# mux stream: {"type":"open","streamId":…,"endpoint":…,"payload":{"args":…}}
{"type":"open","streamId":"s1","endpoint":"job/list",
 "payload":{"args":{"request":{"sessionId":"1d76d2d5-…"}}}}
```

## The namespace

| method | carrier | args | answer |
| --- | --- | --- | --- |
| `job/list` | mux stream | `{"request":{"sessionId"}}` | `{"type":"rows","jobs":[JobView…]}`, re-sent whole after every lifecycle change |
| `job/follow` | mux stream | `{"request":{"sessionId?","jobId","from?"}}` | `opened`, then `output` batches, then `status`, then `end` |
| `job/kill` | unary | `{"request":{"sessionId","jobId"}}` | `{"outcome":"requested"｜"already-finished"}` |

### Two traps, both silent

**The parameter's wire name is `request`, but its neighbours differ.** Guessing
one name for the whole gateway fails:

```
$ … method:"job/list", payload:{"args":{"_request":{…}}}
{"ok":false,"error":{"code":"gateway/arguments-invalid",
 "message":"typert gateway: job/list: args fields do not match the descriptor:
            missing \"request\"; unexpected \"_request\""}}

$ … method:"session/list", payload:{"args":{"request":{}}}
{"ok":false,"error":{"code":"gateway/arguments-invalid",
 "message":"typert gateway: session/list: args fields do not match the descriptor:
            missing \"_request\"; unexpected \"request\""}}
```

`session/list` really does want `_request`; `job/*` really does want `request`.

**`list` and `follow` are stream methods.** A plain POST is refused outright:

```
gateway/signature-invalid: typert gateway: job/list:
  stream Remote methods must be opened through the stream carrier
```

## The roster has left `session/control`

The app used to read jobs from the control stream's baseline. The running host's
baseline carries `projections` and **nothing else** — no `jobs`, and no `queues`
either:

```
$ endpoint session/control, args {}
{"type":"item","streamId":"s1","value":{"type":"baseline","value":{"projections":{…}}}}
$ grep -o '"jobs"' control.raw | wc -l
0
$ grep -o '"queues"' control.raw | wc -l
0
$ node -e '…Object.keys(baseline.value)…'
baseline value keys: [ 'projections' ]
```

The `jobs` block is still *read* by the app, so an older host that pushes one is
not ignored — but it is never what makes jobs appear.

## There is no forwarded job event

The allowlist the host forwards (`dsh-api-remotes/lib/types/remote-events.js`)
contains 27 events and not one of them is job-related. Confirmed live by holding
an `$events` subscription across a background job's entire life:

```
$ node ev.mjs 18000      # $events open, then a 10-second background job started
"event":"api-session/status"   1
"event":"api-session/activity" 0
```

`api-session/activity` never fired at all, and the doc's `job` activity kind
(`dsh-jobs/lib/types/view.d.ts`) is not something this deployment publishes.

**Consequence, stated rather than hidden:** a roster stream per session is the
only channel, so the seat's finished-unseen dot can only be earned by the session
whose roster is open. The web watches exactly one session too — its job control
mounts with the open conversation — so this is parity, not a regression, but it
*is* less than the old global baseline gave, and `JobsMarker`'s per-session
comparison is kept for that reason.

## `job/list` frames

A whole-set frame, and a push on lifecycle change rather than a poll — captured
by opening the stream and asking the host to kill the running job six seconds in:

```
t+0.1s rows(8) bash-7829 -> running detail= total=15
t+6.3s job/kill -> {"ok":true,"value":{"outcome":"requested"}}
t+6.3s rows(8) bash-7829 -> killed detail=signal: SIGTERM; cancelled by the user total=15
t+6.4s rows(8) bash-7829 -> killed detail=signal: SIGTERM; cancelled by the user total=15
```

Two frames followed the kill because the roster converged through `stopping`
before it settled. Nothing above was polled.

## `JobView`

```json
{"id":"bash-7695","kind":"bash","label":"timeout 30 node scan.mjs",
 "owner":"1d76d2d5-7b8d-4651-b2e5-a07d01fa30bc","status":"running",
 "startedAt":1791476057168,
 "output":{"total":249,"earliest":16,"spillPaths":["/tmp/dsh-subprocess-x/stdout.log"]}}
```

- `status` ∈ `running` | `stopping` | `completed` | `killed` | `failed`.
- `progress` is the live line and is cleared at settlement; `detail` is the
  terminal reason (`exit code: 3`, `signal: SIGTERM; cancelled by the user`).
- `output.total` is the offset the *next* chunk starts at and `output.earliest`
  is the oldest retained byte, so `total > 0` is exactly "there are bytes to
  read" — which is what makes a settled row expandable or not.
- `finishedAt`, `owner`, `progress`, `detail` and `spillPaths` are **absent**
  rather than null while they do not apply.

## `job/follow` frames

Opened on a live job, then the same job after it settled:

```
opened  {"job":{…"status":"running","output":{"total":64,"earliest":0}},"from":0}
output  {"chunks":[{"at":0,"text":"tick 1 …\n","channel":"stdout"},…],"next":64}
output  {"chunks":[{"at":64,"text":"tick 5 …\n"}],"next":80}
… one batch per arrival, until the work stops …
status  {"job":{…"status":"completed","detail":"exit code: 0",…}}
end
```

`status` rides the same stream as the output, so settlement can never race a
still-open output channel. `channel` is `stdout` | `stderr` | `log`, and `log`
reaches observers only — it is what the agent never reads.

Two host behaviours worth knowing before "fixing" them:

- **A resume offset returns the batch that *contains* it, not one starting at
  it.** `from: 20` answered with the chunk at `at: 16`. The web's model simply
  appends, so a reconnect can repeat up to one chunk; this client mirrors that
  rather than de-duplicating a frame the host deliberately coalesces.
- **A lost head is reported, not repaired.** `output.earliest > 0` means
  retention dropped bytes, and `lossy: true` on a frame or `gapBefore: true` on a
  chunk means the same for that batch. The web renders these as a notice above
  the panel, so a broken stream cannot read as a job that printed
  `… earlier output dropped …`.

## What the web does with it

`dsh-client-ui-jobs` is the only consumer: one slot entry in the conversation
header. Mounting it keeps that session's `job/list` open; expanding an observable
row opens its `job/follow` and collapsing closes it, so **output only flows while
someone is watching**. The panel is a `TerminalBlock` configured with
`command = job.label`, `copyText = job.label`, `runStateDot = false`.

`dsh-api-job-controller` supplies the model: a bounded per-job render tail of
128 KiB of UTF-16 code units (`RENDER_TAIL_LIMIT`), cut with a surrogate-pair
guard, plus the `gapBefore` / `streaming` / `error` flags. `JobsWire` and
`JobTail` in `app/src/main/java/uk/xa0/dsh/model/Jobs.kt` are that model, ported.

In the chat log the web has no live job output at all. It has two job-shaped
entries, and this client now matches both:

- a `tool-jobs` notice, drawn as a turn trigger titled "Background task updated";
- the tool cards for `job_output` / `job_list` / `job_kill`.

The live panel in the chat log is therefore this client's addition, attached to
the two entries that name a job id: the `tool-jobs` notice (which the parse in
`jobNoticeLabel` already reads the id out of) and the background shell call that
started the job — the latter being the only chat-log entry that exists *while*
the job is still running.
