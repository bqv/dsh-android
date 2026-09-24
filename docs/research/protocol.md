# DSH client/server wire protocol — native-client reference

Provenance: every field name below was read from the DeepSeek Harness source checkout
`/home/user/bin/deepseek-harness` (version `0.1.6-alpha.1`) and cross-checked against the **live**
instance on this machine where noted as "live probe". Nothing is invented; where a value is
merge-extensible by plugins it is marked as such.

Primary sources:

| Area | Files |
|---|---|
| HTTP RPC + auth + trust fence | `packages/client/connection/src/{index,rpc,rpc-host,rpc-schema,browser-auth,api-request-trust,http-bridge}.ts` |
| WebSocket stream mux + forwarded events | `packages/api/gateway/src/{index,stream-protocol,stream-server,remote-error-codes}.ts`, `packages/api/gateway/src/client/*` |
| Session domain | `packages/api/session-controller/src/*` |
| Forwarded-event allow-list | `packages/api/remotes/src/remote-events.ts` |
| Streaming vocabulary | `packages/llm/llm/src/{types,assistant-stream,message}.ts`, `packages/core/session/src/types.ts` |
| Credentials | `packages/credentials/credentials-local/src/index.ts`, `packages/credentials/credentials/src/*` |
| Launch token / URL | `packages/bundle/web-app/src/index.ts` |
| Deployed gate (installed profile) | `~/.dsh/profiles/web/node_modules/deepseek-harness-auth/lib/{proxy,center,password}.js` |

Live confirmation used a read-only cookie and read-only RPCs only (`llm/listProviders`,
`session/list`, `session/modelCatalog`, `session/page`) plus a read-only WebSocket follow.

---

## 0. TL;DR for the Android client

1. **Transport**: unary calls are `POST /api/<namespace>/<method>` with JSON; live streaming is one
   WebSocket to `/api/remote.mux` that multiplexes independently-openable logical streams.
2. **Envelope**: request `{"type":"client-request","rpcId":"<uuid>","method":"<ns>/<m>","payload":{"args":{…}}}`;
   response `{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":…}}` or
   `{"ok":false,"error":{"code","message","details"}}`.
3. **Auth** is two independent layers (see §5): the harness's own signed cookie `dsh-auth-<b64url(sha256(authority))>`
   and, on the deployed LAN chain, the gate's `dsh_auth_session`. No bearer/API-token auth exists.
4. **Minimal chat call set**: `session/list` → `session/create` → `session/modelCatalog` →
   (WS) `session/follow` → `session/prompt` → (WS) `session/control` → `session/cancel`; plus
   `$events` on the same WS for approvals/questions.
5. **Gotcha**: `args` keys are the JS parameter names. `session/list`'s is `_request` (not `request`),
   verified live: `gateway/arguments-invalid … missing "_request"`.

---

## 1. Transport

### 1.1 The one HTTP carrier

`packages/client/connection/src/index.ts:124-138` mounts exactly one prefix route:

```ts
const route: WebRoute = { kind: 'prefix', path: API_PATH, handler: … }
```

with `export const API_PATH = '/api'` (`api-path.ts:7`). So **everything** below `/api/` is one
prefix route; endpoint dispatch happens inside.

### 1.2 Unary RPC: `POST /api/<namespace>/<method>`

Path grammar (`rpc-host.ts:266-275`): the path after `/api/` is split on `/`; every segment must be
non-empty and match `^[A-Za-z0-9_$.-]+$` (`ENDPOINT_SEGMENT_PATTERN`, line 33), and `.`/`..` are
rejected. `<namespace>/<method>` is exactly two segments. Example: `/api/session/list`,
`/api/session/modelCatalog`, `/api/$events/result`.

Request requirements (`rpc-host.ts:215-252`):

* method must be `POST` → otherwise 404 (`'not found'`)
* `content-type` must be `application/json` (parameters tolerated) → otherwise **415**
  `'content type must be application/json'`
* body must be JSON → otherwise **400** `'body is not JSON'`
* body must parse as the request envelope → otherwise a **200** `server-response` carrying
  `gateway/bad-request` with `details.issues`
* the envelope's `method` must equal the path endpoint → otherwise
  `gateway/bad-request` ("method … does not match endpoint …")
* response is always `200` + `application/json` for an accepted envelope
  (`fullResponse` → `Response.json(body)`, line 281-284)

**Auth/trust run first** (`index.ts:129-134`, `rpc-host.ts:96-100`):
`requestRejection` returns `401` (not authenticated), `403` (untrusted Host/Origin), or `undefined`.
401/403 are plain-text bodies `'unauthorized'` / `'forbidden'`.

`GET`/`HEAD` on an RPC endpoint fall through to `not found` 404 (only the exact Fetch routes own GET/HEAD).

#### Required headers (unary)

| Header | Value | Why |
|---|---|---|
| `Host` | authority the harness believes it is served at, e.g. `127.0.0.1:8081` (direct) or the LAN `host[:port]` when proxied | trust fence + cookie audience (`api-request-trust.ts:99-103`) |
| `Cookie` | `dsh-auth-<b64url(sha256(authority))>=v1.<body>.<sig>` (harness) and, behind the gate, `dsh_auth_session=<token>` | `browser-auth.ts:289-302` |
| `Content-Type` | `application/json` | required, else 415 |
| `Origin` | optional; if present it must equal the Host authority (`api-request-trust.ts:111-117`) | browser cross-site fence |
| `Sec-Fetch-Site` | must **not** be `cross-site` (`api-request-trust.ts:106`) | browser cross-site fence |

There is **no** `X-DSH-*`, `client-version`, or protocol-version request header anywhere in the
transport (grep for `x-dsh`, `protocolVersion`, `clientVersion` finds only ACP/desktop unrelated
code). No `Authorization` header is read by the harness connection layer.

### 1.3 WebSocket stream mux: `/api/remote.mux`

`packages/api/gateway/src/stream-protocol.ts:6`:

```ts
export const REMOTE_STREAM_MUX_PATH = '/api/remote.mux'
```

Registered as an **exact HTTP upgrade route** (`gateway/src/index.ts:205-229`). The upgrade handler
first runs the same trust/auth gate (`webCtx.connection.requestRejection(req)`); on rejection it
writes a raw `HTTP/1.1 401 Unauthorized` / `403 Forbidden` and closes
(`stream-server.ts:213-224`).

* URL: `ws://<authority>/api/remote.mux` (`wss://` under TLS) — `stream-client.ts:304-310`
* Client→server messages are **text JSON**; binary frames close the socket with code `1003`
  (`stream-server.ts:116-120`)
* Malformed JSON closes with `1008` (`stream-server.ts:121-125`)
* Server sends WebSocket **Ping** every `websocketHeartbeatIntervalMs` (default `2000`); after 2
  missed Pongs the socket is terminated (`stream-server.ts:22,77-92`). Any standard WS client
  (OkHttp) answers Pings automatically.
* The client sends a `cancel` per logical stream; a socket close aborts all logical streams
  (`stream-server.ts:129-131`).

One physical socket carries many **logical streams**, each identified by a client-minted `streamId`.

Server→client message union (`stream-protocol.ts:260-263`):

```ts
export type RemoteStreamServerMessage =
  | { readonly type: 'item';  readonly streamId: string; readonly value?: unknown }
  | { readonly type: 'error'; readonly streamId: string; readonly error: RemoteStreamFailure }
  | { readonly type: 'end';   readonly streamId: string }
```

`RemoteStreamFailure` is `{ code: string; message: string; details: object }`
(`stream-protocol.ts:253-257`), produced by `rpcError` (`gateway/src/index.ts:1008-1010`) — i.e.
the same code/message/details vocabulary as unary failures. `value` is omitted when the business
item is `undefined`.

Client→server message union (`stream-protocol.ts:243-250`):

```ts
| { readonly type: 'open';   readonly streamId: string; readonly endpoint: string; readonly payload: unknown }
| { readonly type: 'cancel'; readonly streamId: string }
```

`payload` for a stream endpoint is, like unary, exactly `{"args": {…}}`
(`remoteRequest`, `gateway/src/index.ts:936-951`). `endpoint` is `<namespace>/<method>`, or the two
Gateway-internal endpoints `$events` and `$events/result` (below).

Duplicate `streamId` on an open frame throws and the connection is not recoverable
(`stream-server.ts:140-142`).

### 1.4 Exact Fetch routes (non-RPC)

Registered through `ctx.connection.fetch.register(...)` and dispatched before the RPC interceptor
(`rpc-host.ts:121-136`). Known routes in this checkout:

| Route | Methods | Body | Purpose |
|---|---|---|---|
| `/api/session/uploadFileBinary?sessionId=<id>&name=<optional>` | `POST` | streaming raw bytes, `Content-Type: application/octet-stream` | large file upload (`packages/client/file-upload/src/protocol.ts:2`, `http-route.ts:22-67`) |
| `/api/file?path=<absolute path>` | `GET`, `HEAD` | — | bounded file streaming for attachments/media (`packages/api/session-controller/src/media-references.ts`) |

Upload responses are `application/json; charset=utf-8`:
`{"ok":true,"value":{"receiptId":…,"file":{…}}}` or `{"ok":false,"error":{code,message,details}}`
(`http-route.ts:36-66`). Wrong media type → 415; missing `sessionId` → 400.

### 1.5 Fallback

Anything else on the webserver falls to the registered fallback owner (the SPA dist server) or 404
(`packages/host/webserver/src/index.ts:220-237`). Android does not need it.

---

## 2. Envelope

### 2.1 Request / response (HTTP unary)

`packages/client/connection/src/rpc.ts:60-76` and `rpc-schema.ts:35-47` are the normative shapes:

```ts
export interface ClientRequest {
  readonly type: 'client-request'
  readonly rpcId: RpcId
  readonly method: string
  readonly payload: unknown
}
export interface ServerResponse {
  readonly type: 'server-response'
  readonly rpcId: RpcId
  readonly result: ConnectionRpcResult<unknown>
}
```

* `rpcId` is a **client-minted string**, echoed verbatim by the server. The schema only requires
  `z.string()`; the browser client mints a UUID (`client/rpc.ts:36`). The host echoes the id from
  the request, or `"invalid-request"` when the envelope was unparseable
  (`rpc-host.ts:31,256-259`).
* `payload` for every endpoint is an object with exactly one property, `args`, which must be a plain
  object (`gateway/src/index.ts:942-949`). The browser client always sends `{ args: … }`
  (`client/index.ts:443`).
* `method` equals the URL endpoint `<namespace>/<method>`.

Request example (live):

```json
{"type":"client-request","rpcId":"r1","method":"llm/listProviders","payload":{"args":{}}}
```

Response example (live):

```json
{"type":"server-response","rpcId":"r1",
 "result":{"ok":true,"value":[{"id":"deepseek-official","name":"DeepSeek"},{"id":"dsh-local","name":"Local LLM"}]}}
```

### 2.2 Errors

`ConnectionRpcFailure` (`rpc.ts:17-27`) and `rpcResultSchema` (`rpc-schema.ts:10-32`):

```ts
result: { ok: true,  value: T }
      | { ok: false, error: { code: string, message: string, details: object } }
```

`details` is always an object (empty `{}` when nothing to say). Live example:

```json
{"type":"server-response","rpcId":"r1","result":{"ok":false,
 "error":{"code":"session/agent-busy","message":"subagent Sessions require their durable parent address",
          "details":{"reason":"use subagent delivery for this child session"}}}}
```

Known error-code families (from the merged `RemoteErrorDetailsMap`):

* Gateway infrastructure (`packages/typert/protocol/src/types.ts:47-54` +
  `packages/api/gateway/src/remote-error-codes.ts`):
  `gateway/bad-request {issues?}`, `gateway/cancelled {}`, `gateway/internal {}`,
  `gateway/ambiguous-endpoint`, `gateway/arguments-invalid`, `gateway/binding-invalid`,
  `gateway/context-failed`, `gateway/context-not-found`, `gateway/context-unavailable`,
  `gateway/definition-unavailable`, `gateway/input-invalid`, `gateway/invocation-unavailable`,
  `gateway/lookup-failed`, `gateway/lookup-not-found`, `gateway/lookup-unavailable`,
  `gateway/method-unavailable`, `gateway/provider-mismatch`, `gateway/result-invalid`,
  `gateway/service-unavailable`, `gateway/signature-invalid` — all carrying
  `{ endpoint, field? }`.
* Session domain (`packages/api/session-controller/src/types.ts:186-216`):
  `session/not-found {sessionId}`, `session/model-unavailable {provider,model}`,
  `session/conflict {sessionId,requestedCwd,existingCwd?}`, `session/agent-busy {reason}`,
  `session/invalid-time-zone {value}`, `session/workspace-attach-failed {sessionId,workspaceId}`,
  `agent-preset/conflict {sessionId,requestedPreset,existingPreset?}`,
  `session/attachment-invalid {reason}`, `session/queue-item-not-found {itemId}`,
  `session/steer-unavailable {itemId}`, `session/title-invalid {sessionId}`,
  `session/fork-unavailable {sessionId}`, `subagent/not-found {parentSessionId,childSessionId}`,
  `subagent/catalog-diagnostic {…,reason}`, and (from history.ts) `subagent/unauthorized`.
* Workspace, directory-picker, workspace-file, terminal, settings, goals, agent-team codes — see
  the owning package's `src/types.ts` (`RemoteErrorDetailsMap` augmentation).

### 2.3 Forwarded Host events (`$events`) and the waterfall result RPC

`packages/api/gateway/src/stream-protocol.ts:9-18`:

```ts
export const REMOTE_EVENT_STREAM_ENDPOINT = '$events'
export const REMOTE_EVENT_RESULT_ENDPOINT = '$events/result'
export const REMOTE_EVENT_STREAM_PAYLOAD = { args: {} } as const
export const REMOTE_EVENT_STREAM_READY = { type: 'ready' } as const
```

Open it on the WS mux with `endpoint: "$events"` and `payload: {"args":{}}`. Frames
(`stream-protocol.ts:32-70`):

```ts
// first item of every generation:
{ type: 'ready', clientId: string, host: { home: string } }
// Host notification:
{ type: 'emit', event: string, args: unknown[] }
// Host asks the client to answer a waterfall:
{ type: 'waterfall', event: string, eventId: string, agentId: string, request: object }
// Host withdraws a pending waterfall:
{ type: 'cancel', eventId: string }
```

`request` never contains `agent` or `signal` — the Host projects them off
(`projectRemoteEventRequest`, `stream-protocol.ts:145-172`; client rejects a request that carries
them, `client/remote-events.ts:291-299`). `agentId` is the Session id of the scoped agent.

Answer a `waterfall` with a second **unary** HTTP call:

```
POST /api/$events/result
{"type":"client-request","rpcId":"…","method":"$events/result",
 "payload":{"args":{ "clientId": "<from ready>", "eventId": "<from waterfall>",
                     "outcome": { "kind": "result", "value": <answer> } }}}
```

`RemoteEventResult` (`stream-protocol.ts:87-94`): `outcome` is
`{kind:'next'}` (delegate), `{kind:'result', value?}` (claim) or
`{kind:'rejected', error:{name,message,code?,details?}}` (listener threw). The response is a normal
`server-response` with `value: undefined` on success.

Forwarded event allow-list (`packages/api/remotes/src/remote-events.ts:17-38`) — only these names
ever arrive, with mode:

```
emit:      agent-preset/selected, api-session/added, api-session/removed, api-session/status,
           api-session/activity, api-session/error, commands/change,
           credentials/reference-updated, goal/activation-changed, cordis/request-run,
           cordis/request-run-resolved, cordis/dynamic-package, cordis/dynamic-retract,
           cordis/inspect-query, cordis/inspect-query-resolved, llm/adapters-updated,
           permission-presets/catalog-changed, settings/document-updated
waterfall: approval/request, user-questions/request
```

`emit` frame `args` are the Cordis emit arguments as a JSON array (e.g.
`api-session/status` → `[sessionId, running:boolean]`; `api-session/added` → `[SessionSummary]`;
`api-session/error` → `[sessionId, message]`; `api-session/activity` → `[sessionId, updatedAt]`;
`agent-preset/selected` → `[sessionId, agentPreset]`).

Waterfall request shapes:

* `approval/request` — `request = { toolName: string, callId?: string, reason?: string }`;
  answer value is one of `'allowed-once' | 'rejected'` (client contract
  `packages/client/ui-approval/src/client/contract/slots.ts:64`; full outcome vocabulary
  `ApprovalOutcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'`,
  `packages/interaction/user-approval/src/types.ts:32`).
* `user-questions/request` — `request = { questions: AskUserQuestionItem[] }`; answer value is
  `{ answers: [{ id: string, selected: string[], custom?: string }] }`
  (`packages/interaction/user-questions/src/types.ts:98-155`).

### 2.4 Handshake / "hello"

There is **no** `hello`/`version` RPC. The only handshake is the `$events` stream's first frame:

```json
{"type":"ready","clientId":"3165cb04-…","host":{"home":"/home/user"}}
```

(live probe). The browser client treats receipt of this `ready` as the connection generation being
established (`packages/api/gateway/src/client/remote-events.ts:139-145`,
`packages/client/connection/src/client/connection.ts:216-258`). `host.home` is the only Host fact
exposed; the client uses it to abbreviate paths.

The served `index.html` additionally carries `webserver/index-inject` globals
(`__DSH_CONNECTION_RECOVERY__`, `__DSH_BOOT__`), but those are a **browser bundle graph**, not
protocol: `__DSH_BOOT__` lists client plugin JS chunks (`packages/client/modules/src/index.ts:534`).
A native client ignores them.

---

## 3. Endpoint / method catalog (chat-client relevant)

Rules confirmed by the live server and by `typert` generator/analyzer code:

* Endpoint = `<namespace>/<method>`; namespace defaults to the Cordis service key unless the
  constructor passes `{ namespace: '…' }` (`packages/typert/protocol/src/index.ts:147`).
* A parameter decorated as a lookup (an `Agent`, `Session`, `WorkspaceFileScope`) is **not** the JS
  name on the wire: it is `<lookupKey>Id`. The agent lookup registers
  `{ parameter: 'agent', wire: 'agentId' }` (`packages/core/agent/src/index.ts:258-263`).
* A `signal: AbortSignal` parameter is transport cancellation, never an `args` field.
* `args` must match the resolved descriptor **exactly** (missing and extra both fail). For methods
  whose Host falls back to the SRC descriptor (derived from the JS signature), the wire key is the
  literal JS parameter name — hence `_request` for `session/list`. **Verified live**
  (`missing "_request"`). Treat these as authoritative:

  | Method | args key(s) |
  |---|---|
  | `session/list` | `_request` |
  | `session/search`, `session/create`, `session/selectModel`, `session/rename`, `session/fork`, `session/prompt`, `session/attachment`, `session/updateQueue`, `session/cancel`, `session/page`, `session/follow` | `request` |
  | `skills/list` | `request` (verified live: error names `request`) |
  | `session/modelCatalog`, `session/control`, `session/canOpenWorkspacePath`, `llm/listProviders`, `llm/listConfigurableProviders`, `agentPresets/list`, `permissionPresets/catalog` | `{}` |
  | `fileUploads/upload` | `agentId`, `request` |
  | `fileReferences/list`, `commands/list`, `commands/execute`, `goals/*`, `terminal/*`, `sessionReferenceResolver/candidates` | `agentId` (+ their own JSON params) |
  | `workspaceFiles/*` | `workspaceFileScopeId` (+ `path`/`range`/…) |
  | `directoryPicker/list` | `path` |
  | `directoryPicker/pick` | `{}` |

### 3.1 `session` namespace — the core

Owner: `packages/api/session-controller/src/index.ts`, `super(ctx, 'sessionController', { namespace: 'session' })` (line 121).
Types: `packages/api/session-controller/src/types.ts`.

#### `session/list` — list sessions (cold, no agent activation)

```ts
@Remote('list')
async list(_request: SessionListRequest, signal: AbortSignal): Promise<SessionListValue>
```

* args: `{"_request": {}}` (`SessionListRequest` has `cursor?: string`, currently reserved)
* result: `{ items: SessionSummary[] }` where `SessionSummary` (types.ts:163-172) =
  `{ sessionId, updatedAt: number, running: boolean, blank: boolean, parentSessionId?, origin?: 'subagent', cwd?, projections?: { asOfSeq: number, values: Record<string, JsonValue> } }`
* live result keys inside `projections.values` depended on mounted plugins; observed:
  `title, goal, tokenUsage, contextPressure, contextBreakdown, sessionStats, turnOutline,
  agentPreset, subagentCatalog, subagentTiming, subagent, permissions, modelSelection,
  sessionListMetadata, imageLimits, inbox, todos, plan`.

#### `session/search`

* args `{"request":{"query":"…"}}` → `{items:[{sessionId, snippet}], hasMore:boolean}`.

#### `session/create`

* args `{"request":{"workspaceId"?:string, "cwd"?:string, "sessionId"?:string, "agentPreset"?:string}}`
* result `{sessionId, agentPreset?}`. `workspaceId` and `cwd` are mutually exclusive
  (`gateway/bad-request` otherwise). Passing `sessionId` adopts/resumes that id idempotently.

#### `session/modelCatalog` — models + providers

```ts
@Remote('modelCatalog')
modelCatalog(): Promise<ModelCatalog>
```

args `{}`; result `ModelCatalog` (types.ts:144-150):

```ts
{ default: { provider, model, reasoningEffort? },
  routableProviders: string[],
  groups: [{ id, name, models: [{ id, name, description?, reasoning?: { efforts: [{id,name,description?}], defaultEffort? } }] }],
  failures: [{ id, name, message }] }
```

Live truncated example:

```json
{"default":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"},
 "routableProviders":["deepseek-official","dsh-local","free-mistral","free-groq","free-openrouter"],
 "groups":[{"id":"deepseek-official","name":"DeepSeek",
            "models":[{"id":"deepseek-flash","name":"DeepSeek-V41-Flash",
                       "reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"}]}}]}]}
```

Lower-level provider listing (namespace `llm`, `super(ctx,'llm')`, `packages/llm/llm/src/index.ts:345`):
`llm/listProviders` args `{}` → `[{ id, name }]` (live); `llm/listConfigurableProviders` → the
dormant/configurable directory (`LlmConfigurableProvider[]`);
`llm/discoverModels` args `{settingsNs, request:{provider?,baseURL?,api?,apiKey?}}`.

#### `session/selectModel`

* args `{"request":{"sessionId","provider","model","reasoningEffort"?}}` → `{selected:{…}}`
  (`session/model-unavailable {provider,model}` on failure). Resumes the session.

#### `session/prompt` — send a message

```ts
@Remote('prompt')
prompt(request: SessionPromptRequest, signal: AbortSignal): Promise<SessionPromptValue>
```

* args `{"request":{ "requestId": "<client-minted id>", "sessionId": "…", "mode": "queue"|"steer",
  "content": PromptContentPart[], "clientTimeZone"?: "Europe/Helsinki" }}`
* `clientTimeZone` is accepted only as `UTC` or an `Area/Location` name; anything else fails the
  whole call with `session/invalid-time-zone`. A device whose zone id is a fixed offset
  (`GMT+02:00`) must omit the field, which is what the Android client does
  (`DshViewModel.kt:3446-3449`, `:2722-2725`).
* `PromptContentPart` (types.ts:75-83):
  `{type:'text', text}` | `{type:'image', mediaType, data:<base64>, name?}` |
  `{type:'file', receiptId}` (receipt from `fileUploads/upload` or the binary route)
* result `{accepted: true}` — this only confirms inbox admission; the answer itself arrives on the
  follow stream (§4). `requestId` is echoed into the durable `user/message.source.rpcId` so an
  optimistic UI row can be reconciled. Re-sending the same `requestId` is idempotent
  (`commands.ts:318,582-594`).
* Errors: `gateway/bad-request` (all-whitespace content), `session/model-unavailable` when no
  adapter serves the selected provider, `session/not-found`, `session/agent-busy`,
  `session/attachment-invalid {reason}` (`MODEL_DOES_NOT_SUPPORT_IMAGES`, `FILE_NOT_STAGED`, …),
  `session/invalid-time-zone {value}`.

#### `session/cancel` — abort the active turn

* args `{"request":{"sessionId"}}` → `{accepted:true}`. Semantics: `agent.cancel({kind:'user'}, {keepInbox:true})`
  — cancels the turn but retains the queued inbox (`commands.ts:494-508`).
  `session/not-found` when no live agent is attached.

#### `session/page` — read a transcript page (cold-safe)

* args `{"request":{"address": SessionAddress, "throughSeq": number, "beforeSeq"?: number, "maxMessages"?: number}}`
* `throughSeq` must be a real cursor: passing `-1` yields an empty page; passing a value past the
  end yields `gateway/bad-request "session page through seq N is past cursor M"` (live) — a cheap
  way for a client to discover the cursor. Normal clients take the cursor from the follow snapshot.
* result `{records: SessionHistoryRecord[], hasMore}`; each record is `{type:'event', event: SessionWireEvent}`.
* `SessionAddress` = `{kind:'session', sessionId}` or
  `{kind:'subagent', parentSessionId, childSessionId, mode:'one-shot'|'continuable'}`.
  A subagent session addressed as `{kind:'session'}` is refused with
  `session/agent-busy {reason:'use subagent delivery for this child session'}` (live).
* `maxMessages` default is `50` (`history.ts:38`); pagination is message-aligned
  (`user/message`/`assistant/message` cut points).

#### `session/follow` (stream) — the live transcript

* args `{"request":{"address", "maxMessages"?, "assistantStream"?: true}}`
* frame vocabulary is in §4.1. Open it **before** prompting; frames are gap-free by `seq`.

#### `session/rename` / `session/fork`

* `session/rename` args `{"request":{"sessionId","title"}}` → `{title, seq}`
  (`session/title-invalid` when blank/too long).
* `session/fork` args `{"request":{"sessionId","atSeq"?}}` → `{sessionId}`
  (`session/fork-unavailable` without a completed turn).

#### `session/updateQueue` / `session/attachment`

* `updateQueue` args
  `{"request":{"sessionId","itemId","action":{"kind":"edit","content":ContentBlock[]} | {"kind":"remove"} | {"kind":"steer"}}}`
  → `{accepted:true}` (`session/queue-item-not-found`, `session/steer-unavailable`).
* `attachment` args `{"request":{"sessionId","attachmentId"}}` → `{attachment, data:<base64>}`.

#### `session/openWorkspacePath`, `session/canOpenWorkspacePath`

* `openWorkspacePath` args `{"request":{"path","action"?:"reveal"}}` → `{opened:true}`.
* `canOpenWorkspacePath` args `{}` → `boolean`.
* `session/workspaceDesktop()` has **no** `@Remote` — it is not callable over RPC.

#### Session delete / archive

There is **no** `session/delete`. Hiding a session is `workspace/archiveSession`
(`{"request":{"sessionId"}}` → `{archivedSessionIds}`) and `workspace/unarchiveSession`; the
unarchive UI additionally uses `settings` namespaces. Deleting a *workspace registration* is
`workspace/delete`. There is no RPC that erases session data.

### 3.2 `agentPresets`

Owner `packages/preset/agent-presets/src/index.ts`, `super(ctx,'agentPresets')` (line 167).
`agentPresets/list` args `{}` →
`{presets:[{id,trust:'system'|'user',isDefault,name?,description?,broken?}], authorable, modeSelectionEnabled}`.
Also `agentPresets/read` `{agentPreset}` → `{agentPreset,trust,content,name?,description?}`,
`agentPresets/copy` `{from,id,name?}`, `agentPresets/deletePreset` `{id}`,
`agentPresets/select` `{agentId,agentPreset}` → recorded id.
The durable event `agent-preset/selected` also arrives on the `$events` stream.

### 3.3 `commands`, `permissionPresets`, `skills`

* `commands/list` args `{"agentId"}` → `CommandDescriptor[]`
  (`{definitionId?,name,description,input?:{hint,attachments?}}`); this is how a client discovers
  slash-commands such as `/goal`.
* `commands/execute` args `{"agentId","line","submittedAttachments"}` →
  `{commandId,result:{kind:'success',text?,sourceEventSeq?}|{kind:'error',text}}` (or `undefined`).
* `permissionPresets/catalog` args `{}` → `{options:[{value,name,description?}]}`. The current
  value is read from the session `permissions` projection.
* `skills/list` args `{"request":{"sessionId"}}` →
  `{skills:[{path?,name,description,whenToUse?,modelInvocable}]}`.

### 3.4 Workspaces, directories, cwd

* `workspace/create` args `{"request":{"path"}}` → `{workspace, created}`;
  `workspace/rename` `{workspaceId,title}`; `workspace/delete` `{workspaceId}`;
  `workspace/follow` (stream, no args) → `baseline`/`upsert`/`remove`/`order`/`archived` frames.
  `WorkspaceView = {workspaceId, path, title, sessionIds, createdAt, updatedAt}`.
* There is **no** "change cwd" RPC on a live session. The working directory is chosen at
  `session/create` (`cwd` or `workspaceId`) and is immutable afterwards (`session/conflict` on a
  differing later request). A client that wants a different cwd creates/adopts another session.
* `directoryPicker/list` args `{"path"?}` (absent = home) →
  `{path,home,crumbs:[{name,path,hidden}],entries:[{name,path,hidden}],truncated}`.
* `directoryPicker/createDirectory` args `{"path","name"}` → absolute path.
* `directoryPicker/pick` args `{}` → path or `null` (requires a composed native/browse picker; the
  deployed profile disables the native picker and inserts `dsh-host-directory-picker-browse`).
* `workspaceFiles/*` (`agent`-scoped by `workspaceFileScopeId`): `read` `{path,range:{offset?,limit?}}`,
  `readBytes`/`readAll`/`readRelated`, `stat`, `list`, `changes` (stream). This is the file-tree API.

### 3.5 Attachments / uploads

* `fileUploads/upload` args `{"agentId":"<sessionId>","request":{"data":"<base64>","name"?}}`
  → `{receiptId, file:{attachmentId,name,bytes}}`. Pass `{type:'file',receiptId}` in
  `session/prompt.content`.
* Large files: `POST /api/session/uploadFileBinary?sessionId=…&name=…` with
  `Content-Type: application/octet-stream` and the raw bytes.
* Inline images: `{type:'image',mediaType,data:<base64>}` directly in `session/prompt.content`.
* Read back an image: `session/attachment` or the exact route `GET /api/file?path=…`.

### 3.6 `settings` / `credentials`

* `settings/describe` args `{}` → `{writable,hasDocument,namespaces:[…]}`.
* `settings/update` / `settings/replace` / `settings/mutate` with optimistic `expectedRevision`
  (`settings/conflict {ns,expected,actual}`).
* `credentials/describe` args `{"refs":["DEEPSEEK_API_KEY",…]}` →
  `Record<ref,{configured,source?,writable}>`;
  `credentials/set` args `{"ref","value"}` → `void`; `credentials/unset` args `{"ref"}`.
  This is how a non-browser client can set a **model provider** key (not an HTTP client credential).

### 3.7 `terminal`, `goals`, `feedback`, `pluginInventory`, `agentTeams`

* `terminal/*` is `agentId`-scoped: `environment`, `shells`, `list {sessionId}`, `create {request}`,
  `follow` (stream), `write`, `resize`, `rename`, `close`.
* `goals/*` is `agentId`-scoped: `get`, `create {request:{objective,maxGoalRounds?}}`, `edit`,
  `pause`, `resume`, `complete`, `clear`.
* `messageFeedback/{list,put,delete}`, `sessionFeedback/record`.
* `pluginInventory/list` args `{}` → `{entries, agentPresets?}`.
* `agentTeams/{view,createTask,updateTask}` (experimental).

### 3.8 Tools — not exposed

There is **no** `tools`/`toolCatalog` RPC (the `tools` service is a plain host service), and no
tool-related forwarded event. A client learns about tools only from `tool/call` / `tool/result`
session events and from `agentPresets/read` composition text. `pluginInventory/list` lists plugins,
not tools.

### 3.9 Namespace index

`session`, `skills`, `fileReferences`, `fileUploads`, `workspace`, `workspaceFiles`,
`directoryPicker`, `agentPresets`, `commands`, `permissionPresets`, `settings`, `credentials`,
`terminal`, `goals`, `messageFeedback`, `sessionFeedback`, `pluginInventory`, `agentTeams`,
`sessionReferenceResolver`, `dynamicCordisRunner`, `llm`, `subagents`.
Any endpoint not claimed by a live service returns HTTP 404 `not found` from the shared channel.

---

## 4. Streaming

### 4.1 `session/follow` frames

`SessionFollowFrame` (`session-controller/src/types.ts:515-526`):

```ts
| { type: 'snapshot'; header: SessionWireHeader; cursor: number;
    records: SessionHistoryRecord[]; hasMore: boolean;
    projections: SessionProjectionBaseline;
    assistantStream?: SessionAssistantStreamBaseline }
| { type: 'event'; event: SessionWireEvent }
| { type: 'assistant-stream'; frame: SessionAssistantStreamFrame }
```

Live snapshot (truncated):

```json
{"type":"snapshot",
 "header":{"version":3,"id":"session-c079336f-…","createdAt":1790096828740,"cwd":"/home/user",
           "isSeeded":false,"agentPreset":"standard"},
 "cursor":77,"records":[…11 records…],"hasMore":true,
 "projections":{"asOfSeq":77,"values":{"title":"…","modelSelection":{…},"inbox":{…}, …}},
 "assistantStream":{"revision":13342}}
```

`SessionWireHeader` (`types.ts:401-412`): `version` (session **format** version, currently `3`;
`packages/core/session/src/types.ts:88`), `id`, `createdAt`, `cwd?`, `parentSession?`, `isSeeded`,
`origin?: 'subagent'`, `delegationDepth?`, `agentPreset?`.

**Ordering contract** the Host enforces: after the snapshot, durable `event` frames are emitted in
strictly increasing `seq`, with no gaps — a skip is a stream failure
(`history.ts:225-231`, `gateway/internal "session event stream skipped seq N"`). `assistant-stream`
frames are *cursorless* and carry a dense `revision` counter instead; the client must add exactly
one revision per frame (`client/transport.ts:206-215`). A replacement snapshot restarts the window.

### 4.2 `assistant-stream` frames — text / reasoning / tool-arg deltas

`SessionAssistantStreamFrame` (`types.ts:476-506`):

```ts
| { type: 'start'; attemptId; revision; startedAfterSeq; turn; step }
| { type: 'chunk'; attemptId; revision; index; time; chunk: StreamChunk }
| { type: 'end';   attemptId; revision; index;
    outcome: { kind: 'committed'; eventType: 'assistant/message' | 'assistant/attempt'; seq: number }
           | { kind: 'abandoned' } }
```

`StreamChunk` (`packages/llm/llm/src/types.ts:424-436`) — the exact delta vocabulary:

```ts
| { type: 'block-start';      index: number; blockType: ContentBlockType }
| { type: 'text-delta';       index: number; text: string }
| { type: 'reasoning-delta';  index: number; text: string }
| { type: 'tool-call-delta';  index: number; id: ToolCallId; name?: string; argumentsDelta: string }
| { type: 'block-end';        index: number; block: ContentBlock }
| { type: 'usage';            usage: TokenUsage }
| { type: 'finish';           reason: FinishReason; replayState?: ReplayEnvelope }
```

`ContentBlockType` = `'text' | 'reasoning' | 'image' | 'file' | 'tool-call' | 'tool-result'`
(`types.ts:135-137`). `TokenUsage` (`types.ts:162-176`):
`{ inputTokens, outputTokens, totalTokens?, cacheReadTokens?, cacheWriteTokens?, reasoningTokens? }`
— counts are disjoint; `inputTokens` is uncached input.

Live chunk frames (real):

```json
{"type":"chunk","attemptId":"session-fd58fea2-…:25","revision":26386,"index":258,
 "time":1790097868847,"chunk":{"type":"reasoning-delta","index":0,"text":" known"}}
```

`block-start`/`block-end` bracket each content block; the visible message is assembled by
concatenating deltas per `index`, and finalized by `block-end` (`block` carries the assembled
`ContentBlock`, e.g. `{type:'text',text:"…"}` or `{type:'tool-call',id,name,arguments}`).

On reconnect the snapshot carries `assistantStream = { revision, activeAttempt? }` where
`activeAttempt = { attemptId, startedAfterSeq, turn, step, nextIndex, stream: AssistantStreamRecord[] }`
(`types.ts:456-473`) — a compact replay of the in-flight attempt so the client can rebuild partial
output without refetching (`packages/llm/llm/src/assistant-stream.ts:202-232` expands the records:

```
{type:'text-chunks', time0, index, dt[], texts[]}
{type:'reasoning-chunks', time0, index, dt[], texts[]}
{type:'tool-call-chunks', time0, index, dt[], id, name?, args[]}
{type:'chunk', time, chunk}
```

Reconstruct each member's timestamp as `time0` then `+dt[i-1]`).

### 4.3 Durable session events (`SessionWireEvent`)

Envelope (`types.ts:427-437`) — exactly these keys are accepted by the client validator
(`client/session-wire-event.ts:20-33`):

```ts
{ type: string, seq: number, time: number, data: JsonValue,
  ignorable?: true, sourceEventSeqs?: JsonValue, surfaceOp?: JsonValue }
```

`SessionEventEntry` wraps it: `{ "type": "event", "event": { … } }`.

Core `type` values and their `data` (`packages/core/session/src/types.ts:269-406`, all verified
against live transcript records):

| event `type` | `data` fields | surface? |
|---|---|---|
| `turn/start` | `{ turn }` | log-only |
| `turn/end` | `{ turn, reason: TurnEndReason }` | log-only |
| `step/start` | `{ turn, step }` | log-only |
| `step/end` | `{ turn, step }` | log-only |
| `user/message` | `{ role:'user', id, content: ContentBlock[], source: MessageSource }` | `surfaceOp` required |
| `system/message` | `{ turn, step, message }` | `surfaceOp` required |
| `assistant/message` | `{ turn, step, message: AssistantMessage, stream: AssistantStreamRecord[], usage?: TokenUsage, interrupted?: true }` | `surfaceOp` required |
| `assistant/attempt` | `{ turn, step, stream: AssistantStreamRecord[] }` | log-only |
| `tool/call` | `{ turn, step, callId, name, arguments: string }` (`arguments` is the raw unparsed JSON string) | log-only |
| `tool/result` | `{ turn, step, message: ToolResultMessage, error?: {name,code,reason?}, meta?: JsonValue }` | `surfaceOp` required |
| `request/header` | `{ header, reason, startsSeries? }` | log-only |
| `request/context` | `RequestContext` | log-only |
| `session/end-seed` | `{ inherited?: true }` | log-only |

Live `assistant/message` (abridged but field-exact):

```json
{"type":"event","event":{"type":"assistant/message","seq":75,"time":1790096959995,
 "surfaceOp":"append",
 "data":{"turn":1,"step":2,
   "message":{"id":"29c8b6d1-…","role":"assistant",
              "content":[{"type":"reasoning","text":"…"},{"type":"text","text":"…"}],
              "source":{"kind":"model","provider":"deepseek-official","model":"deepseek-flash",
                        "replayState":{…}}},
   "usage":{"inputTokens":138,"outputTokens":518,"cacheReadTokens":32640,
            "cacheWriteTokens":0,"totalTokens":33296},
   "stream":[{"type":"chunk","time":1790096958557,
              "chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
             {"type":"reasoning-chunks","time0":1790096958729,"index":0,"dt":[…],"texts":[…]},
             {"type":"chunk","time":1790096959995,
              "chunk":{"type":"block-end","index":0,
                       "block":{"type":"reasoning","text":"…"}}}, …]}}}
```

Live `tool/call` / `tool/result`:

```json
{"type":"event","event":{"type":"tool/call","seq":68,"time":…,
 "data":{"turn":1,"step":1,"callId":"call_00_2J7cDgeGMloxNQ1W4d7g7471","name":"bash",
         "arguments":"{\"command\":…}"}}}
{"type":"event","event":{"type":"tool/result","seq":69,"time":…,"surfaceOp":"append",
 "data":{"turn":1,"step":1,"message":{"id":"…","role":"user",
         "content":[{"type":"tool-result","toolCallId":"call_00_…","content":[…],"isError":false}],
         "source":{"kind":"tool","callId":"call_00_…"}}}}}
```

`MessageSource` (`packages/llm/llm/src/message.ts:88-95`): `{kind:'user'}`,
`{kind:'user',rpcId,clientTimeZone?}` (browser prompt, registered at `session-controller/src/types.ts:377-382`),
`{kind:'model',provider,model,replayState?}`, `{kind:'tool',callId}`,
`{kind:'plugin',plugin}` (+ optional `form`/`sections`/`summary`).

**Token usage**: there is no standalone usage event. Usage rides `assistant/message.data.usage`
and the live `{type:'usage'}` chunk. `session/list`/`session/control` projections may carry
aggregates (`tokenUsage` observed live).

**Turn start/end**: `turn/start` / `turn/end` durable events; `agent/status` (running) only arrives
on `$events` as `api-session/status`.

**Errors**: a turn that fails produces `assistant/attempt` (no surface message) and/or
`api-session/error` on `$events` (`{ event:'api-session/error', args:[sessionId, message] }`).

### 4.4 `session/control` — queues, jobs, projections

`SessionControlFrame` (`types.ts:568-572`):

```ts
| { type:'baseline', value:{ queues: Record<SessionId, SessionQueuedItem[]>,
                             jobs:    Record<SessionId, SessionJob[]>,
                             projections: Record<SessionId, SessionProjectionBaseline> } }
| { type:'queue',      sessionId, items: SessionQueuedItem[] }
| { type:'jobs',       sessionId, jobs: SessionJob[] }
| { type:'projection', sessionId, key, value, seq }
```

`SessionQueuedItem` (`types.ts:529-539`): `{id, placement:'queued'|'steering'|'context', rpcId?, message:{id,content}}`.
`SessionJob` (`types.ts:542-550`): `{id,kind,label,status,detail?,startedAt,finishedAt?}` with
`status ∈ {running,stopping,completed,killed,failed}`.

### 4.5 Annotated sequence for one user prompt

Client → server unless marked `S→C`. `rpcId` values shortened. Live-verified shapes are marked ✓.

```text
 1. C: WS connect  GET /api/remote.mux   (Host, Cookie)             [101 Switching Protocols]
 2. C: {"type":"open","streamId":"ev","endpoint":"$events","payload":{"args":{}}}
 3. S→C: {"type":"item","streamId":"ev","value":{"type":"ready",
        "clientId":"3165cb04-…","host":{"home":"/home/user"}}}                      ✓ ready / hello
 4. C: session/list   {"args":{"_request":{}}}                                     ✓
 5. S:   {"ok":true,"value":{"items":[{"sessionId":"…","running":false,"projections":{…}}]}}
 6. C: session/create {"args":{"request":{"cwd":"/home/user/proj"}}}
 7. S:   {"ok":true,"value":{"sessionId":"session-…"}}                             [idempotent adopt]
 8. C: session/modelCatalog {"args":{}}                                             ✓
 9. S:   {"ok":true,"value":{"default":{…},"groups":[…],"routableProviders":[…]}}
10. C: WS open  {"type":"open","streamId":"f","endpoint":"session/follow",
        "payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-…"},
                                      "maxMessages":50,"assistantStream":true}}}}
11. S→C: item snapshot  {header{version:3,id,cwd,agentPreset},cursor:N,records:[…],
        hasMore, projections{asOfSeq,values}, assistantStream{revision:0}}         ✓
12. C: WS open  {"type":"open","streamId":"ctl","endpoint":"session/control","payload":{"args":{}}}
13. S→C: item {type:"baseline", value:{queues,jobs,projections}}
14. C: session/prompt {"args":{"request":{"requestId":"<uuid>","sessionId":"session-…",
        "mode":"queue","content":[{"type":"text","text":"hi"}],"clientTimeZone":"Europe/Helsinki"}}}
15. S:   {"ok":true,"value":{"accepted":true}}
16. S→C follow: event turn/start     {turn:1}                                     (seq N+1)
17. S→C follow: event user/message    {content:[{type:'text',text:'hi'}],
                                       source:{kind:'user',rpcId:'<uuid>',clientTimeZone:'…'}}  [surfaceOp append]
18. S→C follow: event step/start      {turn:1,step:1}
19. S→C ctl:    {type:'queue',sessionId,items:[]}        (the queued item was claimed)
20. S→C follow: assistant-stream {type:'start',attemptId:'session-…:1',revision:1,
        startedAfterSeq:N,turn:1,step:1}
21. S→C follow: assistant-stream {type:'chunk',revision:2,index:0,time,…,
        chunk:{type:'block-start',index:0,blockType:'reasoning'}}
22. S→C follow: assistant-stream chunk reasoning-delta {"index":0,"text":"The"}  (revision 3,4,…)
23. S→C follow: assistant-stream chunk {type:'block-end',index:0,block:{type:'reasoning',text:'…'}}
24. S→C follow: assistant-stream chunk {type:'block-start',index:1,blockType:'tool-call'}
25. S→C follow: assistant-stream chunk {type:'tool-call-delta',index:1,id:'call_…',name:'bash',
        argumentsDelta:'{"comm'}
26. [permission] S→C ev: {"type":"waterfall","event":"approval/request","eventId":"<evid>",
        "agentId":"session-…","request":{"toolName":"bash","callId":"call_…","reason":"…"}}
27. C: POST /api/$events/result {"args":{"clientId":"3165cb04-…","eventId":"<evid>",
        "outcome":{"kind":"result","value":"allowed-once"}}}
28. S→C ev: {"type":"emit","event":"api-session/status","args":["session-…",true]}
29. S→C follow: assistant-stream chunk {type:'block-end',index:1,
        block:{type:'tool-call',id:'call_…',name:'bash',arguments:'{"command":"…"}'}}
30. S→C follow: assistant-stream chunk {type:'usage',usage:{inputTokens,outputTokens,
        cacheReadTokens,totalTokens,…}}
31. S→C follow: assistant-stream {type:'end',revision:R,index:i,
        outcome:{kind:'committed',eventType:'assistant/message',seq:M}}
32. S→C follow: event assistant/message seq M   {turn,step,message{id,role,content,source},
        usage:{…},stream:[…]}          [surfaceOp append]  ← authoritative full text
33. S→C follow: event tool/call  {turn,step,callId,name,arguments}       (log-only)
34. S→C follow: event tool/result {turn,step,message:{content:[{type:'tool-result',…}]},meta?}
                                                                          [surfaceOp append]
35. S→C follow: event step/end {turn:1,step:1}
36. S→C follow: event step/start {turn:1,step:2}
37. …next assistant turn repeats 20–36 with step:2 (assistant-stream revisions keep counting)…
38. S→C ctl: {type:'projection',sessionId,key:'tokenUsage',value:{…},seq:M2}
39. S→C follow: event turn/end {turn:1,reason:{…}}
40. S→C ev: {"type":"emit","event":"api-session/status","args":["session-…",false]}
```

Notes on the example:

* Steps 12–13 (control) and 10–11 (follow) are independent logical streams on the same socket.
  The Android client holds four registrations on the one socket: `$events` (one only — a second
  registration splits every waterfall and leaves the first answer unsettleable), `session/control`,
  `workspace/follow` for the sidebar registry and `session/follow`
  (`AttentionCenter.kt:120-140`, `DshViewModel.kt:1211`, `:1694`, `:2424`). Each is opened exactly
  once per socket generation and replayed when a new socket opens (`RemoteMux.kt:252-270`,
  `:288-289`); follow is re-opened per session and after a process resume, the other three live
  until the config changes or the process ends (`DshViewModel.kt:716-732`).
* Step 9's model catalog is what the picker uses; `session/selectModel` (not shown) changes it.
* Steps 20–31 are the *process-local* live view; steps 32–34 are the *durable* truth. A client may
  render from either; the durable `assistant/message` is authoritative and self-contained (it
  embeds the whole compact `stream`), which is why it replays identically after reconnect.
* Cancellation: the client posts `session/cancel {"request":{"sessionId"}}`; the turn then ends
  with a `turn/end` event, and an `assistant/message` with `"interrupted": true` if text had already
  streamed.

### 4.6 Reconnect rules (what the Host tolerates)

The first three bullets below are the *in-repo web client's* durability policy, from
`RemoteJournalStream` (`packages/api/gateway/src/client/journal-stream.ts`) and
`SessionEventStream` (`session-controller/src/client/transport.ts:172-220`). The Host does not
refuse a stream for violating them, so a native client may run its own reducer, as the Android one
does.

* Re-open `session/follow` with the same request and expect a fresh `snapshot`. The web client
  requires that cursor to be `>=` its last applied `seq` and drops entries at or below it; the
  Android client does neither — `applySnapshot` re-applies every record and `applyEvent` ignores a
  `seq` already held (`Transcript.kt:253-289`, `:309`) — which is what lets a re-subscribe happen
  under a live transcript (`DshViewModel.kt:1097-1101`).
* A gap (`first > last+1`) makes the web client `session/page`-fill and replace the window, and it
  must never accept a partially overlapping entry. The Android client does no gap detection on the
  live stream and tolerates overlaps; it uses `session/page` only to page *backwards* on demand
  (`throughSeq` = the follow `cursor`, `beforeSeq` = the oldest held seq — `Transcript.kt:180-217`,
  `DshViewModel.kt:2305-2345`).
* `assistant-stream.revision` must increase by exactly 1 per frame for the web client, where a
  skipped revision is a carrier error and restarts the stream. The Android client never reads
  `revision`: it rebuilds a mid-attempt stream from the snapshot's
  `assistantStream.activeAttempt` replay and then from seq-keyed durable events
  (`Transcript.kt:273-288`, `:614-647`).
* The gateway WS heartbeat is Ping every 2 s; answer Pongs or the socket is terminated after ~6 s.
  The Android client answers Pongs automatically and adds its own 20 s ping on the mux socket
  (`DshClient.kt:89`).

---

## 5. AUTH — exhaustive

There are **two** independent cookie layers plus optional proxy auth. There is **no** bearer token,
no API key, no client-certificate or device-code protocol in the harness itself. The maintainers
considered and explicitly rejected an `Authorization`-header client contract — this is a documented
non-goal, not an oversight:

> "The HTTP carrier accepts no query token outside the root exchange and no Authorization-header
> token." — `packages/client/connection/README.md:35`

> "**Persist or accept the launch token as an API bearer.** A durable launch token would become a
> second long-lived credential, while Authorization-header support would add a non-browser client
> contract with no current consumer. The process token performs one browser-cookie exchange only."
> — `.agents/notes/implemented/architecture/2026-08-24-browser-token-authentication.md:35`

There is also **no method-specific loopback tier**: every RPC method and every WebSocket stream
requires the browser session (`README.md:35`). Being on loopback satisfies only the *trust fence*,
not authentication (and the fence is a DNS-rebinding/CSRF defense, not identity —
`api-request-trust.ts:9-13`).

### 5.1 Layer A — the harness's own browser-session cookie (mandatory, cannot be disabled)

Owner: `packages/client/connection/src/browser-auth.ts`. `/api` authentication is enforced in
`rpc-host.ts:96-100`:

```ts
requestRejection(request) {
  if (!isTrustedApiRequest(request, this.trustedHosts)) return 403
  return this.browserAuth.isAuthenticated(request) ? undefined : 401
}
```

There is no config switch for this: the connection plugin config only exposes
`recovery`, `trustedHosts`, `cookieMaxAgeDays`, `maxRequestBodyBytes`
(`packages/client/connection/src/index.ts:90-95`). The deployment patch confirms it
(`~/.dsh/profiles/web/cordis.patch.yml`: "DSH's own browser auth is independent of the auth proxy
and cannot be switched off … It can only be SATISFIED").

#### Cookie name

```ts
const COOKIE_PREFIX = 'dsh-auth-'
function cookieName(authority: string): string {
  return COOKIE_PREFIX + encodeBase64Url(createHash('sha256').update(authority).digest())
}
```
(`browser-auth.ts:16,106-108`)

`authority` is `new URL('http://' + Host).host` — hostname lowercased, default port stripped, IPv6
bracketed (`browser-auth.ts:69-78`). For this deployment the upstream Host is `127.0.0.1:8081`, so
the name is `dsh-auth-` + `base64url(sha256("127.0.0.1:8081"))`. (The live nginx config hard-codes
exactly that name.)

#### Cookie value

```ts
const COOKIE_PAYLOAD_VERSION = 1
function encodeCookie(payload, secret) {
  const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), 'utf8'))
  return `v1.${body}.${encodeBase64Url(signature(secret, body))}`
}
function signature(secret, body) { return createHmac('sha256', secret).update(body).digest() }
```
(`browser-auth.ts:17,125-132`)

Payload (`BrowserCookiePayload`, `browser-auth.ts:27-32`):

```ts
{ version: 1, authority: "<host[:port]>", issuedAt: <ms>, expiresAt: <ms> }
```

The HMAC is computed over the **ASCII bytes of the base64url body segment** (the middle segment),
not over the raw JSON: `signature(secret, body)` where `body = encodeBase64Url(JSON.stringify(payload))`
(`browser-auth.ts:125-132`). The secret is exactly 32 bytes decoded from base64url
(`browser-auth.ts:14,80-98`).

Validation (`browser-auth.ts:289-302`): signature must verify against the loaded secret, payload
`authority` must equal the request authority, `issuedAt <= now < expiresAt`, and
`expiresAt - issuedAt <= cookieMaxAgeDays * 86400000`. The deployment sets
`cookieMaxAgeDays: 3650`.

Cookie attributes when minted: `Path=/; HttpOnly; SameSite=Strict; Expires=…; Max-Age=…`
(`browser-auth.ts:120-123`).

#### The signing secret

```ts
const AUTH_RECORD_KEY = credentialKey('client-connection', 'browser-session')
const SECRET_BYTES = 32
interface StoredSecretPayload { version: 1; secret: string }   // base64url
```
(`browser-auth.ts:12-25,161-178`)

It is persisted by the credentials provider. For the installed local provider the document is
`$DSH_HOME/.credentials.yaml` (`packages/credentials/credentials-local/src/index.ts:2,61`), which on
this machine is `~/.dsh/.credentials.yaml`. Confirmed structure (values redacted):

```yaml
version: 1
records:
  client-connection/browser-session:
    kind: grant
    payload: { version: 1, secret: "<base64url, 32 bytes>" }
refs:
  DEEPSEEK_API_KEY: "…"
  OPENROUTER_API_KEY: "…"
  …
```

**Consequence (the single most important non-browser auth fact):** any process that can read
`$DSH_HOME/.credentials.yaml` can mint a valid harness cookie for any authority, offline, with no
login and no involvement of the running server. The secret is *not* derived from the launch token;
it is stable across restarts (created once by `initializeSecret`). The credentials document is
`0600` inside a `0700` directory and the provider refuses group/other-readable files
(`packages/credentials/credentials-local/src/index.ts:127-146`,
`GROUP_OTHER_BITS = 0o077`), but every process that runs as the same OS user (including DSH's own
tool subprocesses) can read it — and nginx's hard-coded cookie is direct proof that minting outside
the token exchange works.

The secret is loaded **once at Connection activation** and held in memory
(`browser-auth.ts:210-216`, `packages/client/connection/src/index.ts:114-118`). Therefore
revocation is: delete/rotate the record and restart the process; changing the file alone does not
invalidate the running instance's already-loaded secret (a cookie signed with the old secret keeps
verifying until restart). There is no logout RPC.

Note the `credentials` Remote namespace **cannot** read or write this record: it only exposes
`describe`/`set`/`unset` over `CredentialRef` names, values flow one way, and `records` are
unreachable (`packages/api/settings-controller/src/credentials.ts:64-118`). So a remote (non-local)
client cannot obtain the secret over the API.

#### Minting flow that does not require reading the secret

`BrowserAuth.authorizeIndex` (`browser-auth.ts:240-282`):

* `GET /?token=<launchToken>` with exactly one `token` param → **303** to `/` with
  `Set-Cookie: dsh-auth-<name>=v1.…; …` (`authorizeIndex` lines 244-266).
* `GET /` with a valid cookie → serves the app; otherwise **401** with body
  `"dsh web authentication required; reopen the URL printed by dsh web.\n"`.

#### The launch token

```ts
const PROCESS_LAUNCH_TOKENS = new WeakMap<object, string>()
function processLaunchToken(owner) { /* 32 random bytes, base64url, once per root context */ }
authenticatedUrl(baseUrl) { url.searchParams.set('token', this.launchToken); return url.href }
```
(`browser-auth.ts:20,52-58,223-230`)

`packages/bundle/web-app/src/index.ts:259-272` prints it:
`console.log('dsh web: ' + authenticatedUrl + (LAN ? ' (LAN: …)' : ''))`.
On this machine that line lands in `~/.dsh/logs/dsh.log`
(observed: `dsh web: http://127.0.0.1:8080/?token=…`). So a local non-browser client can recover a
**fresh** session cookie by scraping the last `dsh web:` line and issuing `GET <url>`. The token is
minted per process; a restart invalidates the URL (the cookie it minted stays valid until expiry).

### 5.2 Layer B — the deployed gate (`deepseek-harness-auth`) and nginx

The harness's own cookie is necessary but not sufficient on the LAN chain:

```
LAN client → nginx :80 (192.168.1.110/.114) → gate 127.0.0.1:8080 (deepseek-harness-auth)
                                            → harness 127.0.0.1:8081
```

The gate (`deepseek-harness-auth@0.4.1`, installed in the `web` profile; `proxy.js` routes) adds a
**password login with optional MFA/captcha and IP whitelist**. It is a real, first-class
non-browser login API:

| Endpoint | Method | Body / params | Result |
|---|---|---|---|
| `/auth/login` | GET | `?error`, `?locked`, `?captcha` | HTML login page |
| `/auth/captcha` | GET | — | SVG captcha, sets `dsh_auth_captcha` (Max-Age 120) |
| `/auth/login` | POST | `application/x-www-form-urlencoded`: `provider` (default `password`), `username`, `password`, `captcha?` | **303** to `/` + `Set-Cookie: dsh_auth_session=<token>; Path=/; SameSite=Strict; HttpOnly; Max-Age=<sessionTtlSeconds>`; failure 303 to `/auth/login?error=1[&captcha=1]`, lockout 303 + `Retry-After` |
| `/auth/logout` | POST | — | clears session (204+JSON if `X-DSH-Auth-Request: 1`, else 303) |
| `/auth/account` | GET | — | JSON `{mode:'session'\|'whitelist', provider?, username?, clientIp, whitelist, captchaMode, …}` |
| `/auth/account/captcha` | PUT | JSON `{"mode":"off"\|"always"\|"after-failures"}`, header `X-DSH-Auth-Request: 1` | captcha policy |
| `/auth/account/whitelist` | PUT | JSON `{"rules":["…"]}`, header `X-DSH-Auth-Request: 1` | whitelist |
| `/auth/account/password` | POST | JSON `{"currentPassword","newPassword"}`, header `X-DSH-Auth-Request: 1` | 204 |

(`proxy.js:238-397`; `center.js:64-131` issues `randomBytes(32).toString('base64url')` sessions.)

Session semantics (`center.js:8-16,119-131`): `sessionTtlSeconds` default `86400`, configurable via
`DSH_AUTH_SESSION_TTL_SECONDS`; sessions live in memory and are revoked when the provider
`revision` changes (password/whitelist edits). Max 10000 sessions; login lockout default 6 attempts
/ 30 s per (IP,user) and per IP.

The gate forwards requests upstream after **stripping only its own cookies**
(`proxy.js:44-52,111-131`): `dsh_auth_session` and `dsh_auth_captcha` are removed; every other
cookie (notably `dsh-auth-*`) is forwarded. It rewrites upstream `Host` to `127.0.0.1:<harness port>`
and, if the client sent an `Origin`, rewrites that to the same loopback authority.

nginx (`/etc/nginx/conf.d/dsh.conf`) is a transparent forwarder that **injects the harness cookie on
every response**:

```nginx
add_header Set-Cookie "dsh-auth-aTsxArFscPyLVjlbcrrOLlx7WWOvLQAU_Z2IkL_y684=v1.<REDACTED payload>.<REDACTED signature>; Path=/; HttpOnly; SameSite=Strict; Max-Age=311040000" always;
proxy_pass http://127.0.0.1:8080;
proxy_set_header Host $http_host;
proxy_set_header Origin $http_origin;
```

(The cookie **name** and the payload's authority/field shape are reproduced above; the secret value
itself is withheld from this document. The name is `dsh-auth-<b64url(sha256("127.0.0.1:8081"))>`, the
payload authority is `127.0.0.1:8081`, and `Max-Age=311040000` = 3600 days.)

### 5.3 Practical options for a native Android client (ranked)

| # | Path | How | Pros | Cons |
|---|---|---|---|---|
| 1 | **LAN via nginx :80 + gate login** | `POST http://<host>/auth/login` form-encoded (`provider=password`, `username`, `password`, optional `captcha`); store `dsh_auth_session` from the 303 `Set-Cookie`; the same response also carries the nginx-injected `dsh-auth-*`. Then `POST /api/…` and `ws://<host>/api/remote.mux` with both cookies. | First-class, documented-by-code login; works from any network; no filesystem access needed; `Host` is the LAN authority nginx forwards. | Gate password must exist (`dsh-auth init`), captcha/2FA may be required; cookies are bearer secrets; gate session TTL default 24 h and in-memory (lost on gate restart). |
| 2 | **Loopback direct to 127.0.0.1:8081** | `Host: 127.0.0.1:8081`; cookie minted from the persisted secret (§5.1) **or** scraped from the `dsh web: …?token=` line in `~/.dsh/logs/dsh.log` and exchanged with `GET /?token=`. | Works without the gate (loopback is in the gate whitelist); secret gives a 10-year cookie. | Only from the host itself; requires read access to `~/.dsh` as the same OS user (on Android this means an on-host helper / SSH / port-forward, not pure remote HTTP). |
| 3 | **Gate whitelist bypass** | Add the phone's IP/CIDR to the gate whitelist (`~/.dsh/auth/state.json` `whitelist`, or `dsh-auth whitelist add`); then only the `dsh-auth-*` cookie is needed (nginx injects it on any response, so one `GET /` captures it). | No password prompt on the phone. | Weaker security; still needs the harness cookie. |
| 4 | **Reverse-proxy auth of your own** | Put the harness behind a proxy that injects both cookies. | Full control. | Out of scope of DSH. |

The Android client takes row 1 (the gate's `POST /auth/login` form) and accepts a pasted raw cookie
as the fallback for a carrier port with no login door; it never mints the harness cookie itself and
never reads the signing secret or the launch token. Both cookies are replayed from a
persistence-backed jar on every unary call and on the mux handshake (`DshClient.kt:64-67`,
`:201-228`, `:172-177`; `PersistentCookieJar.kt:10-17`).

What is **not** possible: there is no `Authorization: Bearer …` path, no `X-API-Key`, no device-code
grant, no OAuth client-credentials flow, and no endpoint that mints a token for a non-browser
client — this is explicitly rejected in the architecture note quoted at the top of §5. Grep of the
checkout for server-side `Authorization`/`Bearer`/`apiKey`-header/`device`/`pair` finds only
provider-OAuth flows (`packages/credentials/authorization` is for **model provider** credentials,
e.g. "Sign in with ChatGPT", not for HTTP clients) and unrelated ACP/terminal code.

### 5.3.1 Non-browser clients that do exist in the tree (and why they don't help)

* **`apps/desktop-host`** is a genuine non-browser client that *bypasses* the browser session
  entirely: it runs as an Electron child process over framed byte pipes (fds 3/4, magic
  `0x44534833`, protocol v3) and calls `connection.createSharedFetchHandler('/api')` directly with
  **no** `requestRejection`/`authorizeIndex` anywhere (`apps/desktop-host/src/index.ts:325,359-363`).
  Its security boundary is the OS pipe/IPC channel with its parent Electron process — unreachable
  from an Android device.
* **`@deepseek-ai/dsh-sdk-client` and `@deepseek-ai/dsh-acp`** are the only first-class non-browser
  protocols, and both are **stdio JSON-RPC to a spawned child process**, not remote HTTP
  (`apps/cli/reference/README.md`: the `sdk`/`sdk-minimal`/`acp` profiles carry the protocol on
  stdio). An Android app cannot use them against a remote host.
* **`packages/ssh/*`** is host→remote-host execution plumbing (OpenSSH alias + digest-pinned
  helper), not a client-facing API.
* **`packages/identity/anonymous-user-id`** (a random UUID in `~/.dsh/.anonymous-user-id`) gates
  nothing; it is sent to the model provider and telemetry only.

### 5.4 What an Android client must actually hold

For the deployed chain (`http://192.168.1.110/…`):

```http
POST /api/session/list HTTP/1.1
Host: 192.168.1.110
Cookie: dsh_auth_session=<gate token>; dsh-auth-<b64url(sha256("127.0.0.1:8081"))>=v1.<body>.<sig>
Content-Type: application/json

{"type":"client-request","rpcId":"…","method":"session/list","payload":{"args":{"_request":{}}}}
```

and for the socket:

```http
GET /api/remote.mux HTTP/1.1
Host: 192.168.1.110
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: …
Cookie: dsh_auth_session=…; dsh-auth-…=v1.…
```

Note the `Host` sent to nginx is the LAN authority (`$http_host` is forwarded verbatim); the
harness cookie's *name/payload* authority is still `127.0.0.1:8081` because nginx forwards `Host` to
the source as `$http_host` and the **gate** rewrites upstream `Host` to `127.0.0.1:8081`. If a client
bypasses the gate and hits :8081 directly it must send `Host: 127.0.0.1:8081`.

### 5.5 On-disk auth artifacts (this machine)

| Path | Content | Relevance |
|---|---|---|
| `~/.dsh/.credentials.yaml` | `records['client-connection/browser-session'].payload.secret` (base64url HMAC secret) | mints the harness cookie (§5.1) |
| `~/.dsh/auth/state.json` | gate account: `{version,revision,username,password:{algorithm,salt,hash,cost,blockSize,parallelization},whitelist:[…]}` (scrypt) | gate login backend; whitelist currently `['127.0.0.0/8','0:0:0:0:0:0:0:1']` (loopback bypasses login) |
| `~/.dsh/auth/store.json` | a *different* account/TOTP store (roles, backup codes) | left over from another login plugin; not the active gate |
| `~/.dsh/logs/dsh.log` | `dsh web: http://127.0.0.1:8080/?token=<launch token>` lines | fresh cookie-minting URL (§5.1) |
| `/etc/nginx/conf.d/dsh.conf` | the static `dsh-auth-*` cookie | LAN cookie injection |
| `~/.dsh/mobile-access/remote/{cpolar,cloudflared,frp,origin}/control.json` | `{version:1,enabled:false}` tunnel toggles | third-party mobile-access plugin, currently disabled |
| `~/.dsh/remote/servers/<id>/{host,client}/…` | `device.json` (`deviceId,name,publicKey`), `device.key`, `server-credentials.json` (`serverUrl,authorizationMethod,account,accessToken,accessTokenExpiresAt,refreshToken,refreshTokenExpiresAt`), `trusted-peers.json` (peer `deviceId,name,platform,publicKey,membershipId,fingerprint,trustedAt`) | evidence that a device-pairing / relay handshake (`@xiaosenho/dsh-plugin-remote-access` / `dsh-mobile-gateway`) existed in this profile |
| `~/.dsh/web-login/{accounts.json,accounts/,ledger/,sessions-in-use.json,diagnostics/}` | a login plugin's account/session ledger | leftover of another (removed) login plugin; not the active gate |
| `/etc/nginx/nginx.conf` (loopback default server) | also emits the static `dsh-auth-*` `Set-Cookie` and `302`s to `http://127.0.0.1:8080/` | "cookie primer": a single loopback `GET /` harvests the harness cookie |

**Why the same-user requirement is easy to satisfy on the host:** the credentials provider doc says
"Only your OS user can read the file … The agent is not another user: its tool processes run as you,
so they can read the file like any other file you own."
(`packages/credentials/credentials-local/README.md:115`).

**Important**: the device-pairing packages are **not installed** in the live profile and **not in the
checkout** (`@xiaosenho/dsh-plugin-remote-access`, `dsh-mobile-gateway`, `dsh-speech` appear only in
`~/.dsh/profiles/web/pnpm-workspace.yaml`'s `allowBuilds`, not in `package.json` dependencies or the
bundle list; the `.bin` symlinks `ds-harness-remote` and `dsh-mobile` currently dangle).
Their source is therefore unavailable here, and their residual token files must not be treated as a
supported protocol. Do not build the Android client against them.

### 5.6 Security reality check for the report reader

The harness cookie is the *only* thing protecting `/api`, and on this deployment it is a static
10-year bearer cookie hard-coded in an nginx config file. Anyone who can read
`/etc/nginx/conf.d/dsh.conf` (or who once received a response from nginx, since nginx injects it on
every response) holds a credential that grants full API access, including `session/prompt`,
`terminal/*`, and `credentials/set`. The gate adds a password layer on the LAN, but it protects only
the *first* hop: once a client has both cookies it can talk to the harness through nginx forever,
and the harness cookie survives DSH restarts.

Mitigations, in order of preference:

1. Use a fresh gate account, fetch the cookies over the gate, and never ship the static cookie in an
   app. Keep the phone on a trusted network.
2. Rotate the signing secret if the nginx config or a device is suspected compromised: stop DSH,
   delete `records['client-connection/browser-session']` from `~/.dsh/.credentials.yaml`, restart
   DSH (a fresh secret is minted), then re-issue the nginx cookie.
3. Lower `cookieMaxAgeDays` from `3650` and re-mint the nginx cookie periodically.
4. Prefer TLS (`secureCookie` / `wss`) so the cookies are not sniffable on the LAN.

---

## 6. Versioning

* **No HTTP protocol-version constant or header exists.** There is no `X-DSH-Protocol-Version`,
  no `Accept-Version`, and the RPC layer does no version negotiation. A client discovers
  capabilities from the method set and the `gateway/*` errors.
* **No client-version header.** The `User-Agent` strings in the tree belong to outbound model /
  web-search requests (`packages/llm/llm/src/attribution.ts`), not to the browser transport.
* **Session format version**: `export const SESSION_FORMAT_VERSION = 3`
  (`packages/core/session/src/types.ts:88`). It appears as `SessionWireHeader.version` (live: `3`)
  and inside the durable event `session-log-deepseek/delivery-accepted`
  (`{sessionId, sessionFormatVersion, throughSeq}`). A client that does not understand a newer
  version should refuse the session rather than guess.
* **Package version**: the checkout is `@deepseek-ai/dsh-root@0.1.6-alpha.1`
  (`package.json`). The gate bundle declares a compatibility baseline of Harness `0.1.x`
  (`deepseek-harness-auth` README).
* **Recovery timing** — the values below are the *web bundle's* injected client defaults, not
  protocol requirements: `backoffBaseMs 500`, `backoffFactor 2`, `backoffMaxMs 10000`,
  `generationReadyWarnMs 3000`, `generationReadyTimeoutMs 15000`
  (`packages/client/connection/src/recovery-config.ts:25-31`). The Android client runs its own
  bounded schedules instead: four unary connect retries spaced 3/6/9/12 s
  (`DshViewModel.kt:3592-3594`, `:1114-1121`), and a mux supervisor that re-dials on a 1 s backoff
  doubling to a 15 s cap (`RemoteMux.kt:120-138`, `:385-389`). Unary calls carry a 15 s connect,
  60 s read and 60 s whole-call timeout (`DshClient.kt:66-74`); the mux socket sets no read or call
  timeout and pings every 20 s (`DshClient.kt:86-89`). A call that never answers is classified as
  host-unreachable, distinct from the 401/403 credential refusal (`DshClient.kt:36`, `:39-48`,
  `:191-193`, `:238-239`).
* **WS heartbeat**: `websocketHeartbeatIntervalMs` default `2000`, configurable
  (`packages/api/gateway/src/index.ts:116-121`).

---

## 7. raw request/response appendix

### 7.1 Minimal successful request (live)

```http
POST /api/session/modelCatalog HTTP/1.1
Host: 127.0.0.1:8081
Content-Type: application/json
Cookie: dsh-auth-<name>=v1.<body>.<sig>

{"type":"client-request","rpcId":"r1","method":"session/modelCatalog","payload":{"args":{}}}
```

```json
{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"default":{…},"routableProviders":[…],"groups":[…],"failures":[]}}}
```

### 7.2 Argument mismatch (live)

```json
{"type":"client-request","rpcId":"r1","method":"session/list","payload":{"args":{}}}
```
```json
{"type":"server-response","rpcId":"r1","result":{"ok":false,"error":{
 "code":"gateway/arguments-invalid",
 "message":"typert gateway: session/list: args fields do not match the descriptor: missing \"_request\"",
 "details":{"endpoint":"session/list"}}}}
```

### 7.3 WS open + follow snapshot (live, abridged)

```json
C→S {"type":"open","streamId":"s-events","endpoint":"$events","payload":{"args":{}}}
S→C {"type":"item","streamId":"s-events","value":{"type":"ready","clientId":"3165cb04-…","host":{"home":"/home/user"}}}
C→S {"type":"open","streamId":"s-follow","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-c079336f-…"},"maxMessages":3,"assistantStream":true}}}}
S→C {"type":"item","streamId":"s-follow","value":{"type":"snapshot","header":{…},"cursor":77,"records":[…],"hasMore":true,"projections":{…},"assistantStream":{"revision":13342}}}
```

### 7.4 WS logical-stream failure

```json
S→C {"type":"error","streamId":"s-follow","error":{"code":"session/not-found",
      "message":"session \"x\" not found","details":{"sessionId":"x"}}}
S→C {"type":"end","streamId":"s-events"}
```

### 7.5 Canonical WS open for a stream endpoint that takes no args

```json
{"type":"open","streamId":"s-ctl","endpoint":"session/control","payload":{"args":{}}}
```

### 7.6 Canonical WS cancel

```json
{"type":"cancel","streamId":"s-follow"}
```
