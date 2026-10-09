# Handoff — layout, traps, open items

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/uk/xa0/dsh/model/` | Pure folds over host records: `Transcript.kt` (the reducer), `TurnProcess.kt`, `ToolCallTree.kt`, `SessionFiles.kt`, `SessionStats.kt`, `Trajectory.kt`, `SessionSearch.kt` |
| `app/src/main/java/uk/xa0/dsh/ui/` | Compose surfaces; `ui/components/` holds the row cards, `ui/composer/` the trigger derivation, `ui/markdown/` the parser and renderer |
| `app/src/main/java/uk/xa0/dsh/net/` | `DshClient.kt` (RPC + auth), `RemoteMux.kt` (the `/api/remote.mux` stream mux) |
| `app/src/main/java/uk/xa0/dsh/data/` | `ConfigStore.kt`, the Keystore-backed config |
| `app/src/main/java/uk/xa0/dsh/DshViewModel.kt` | Every RPC call site, the mux wiring and the UI state |
| `tools/` | What is actually this project's: `install.sh` (APK install), `dsh-api.mjs`, `gh-secrets.py`. The adb/emulator services and the device lease are **generic host infrastructure** and live outside this checkout — source in `~/bin/adb-wireless`, services in `~/etc/init.d` + `~/etc/conf.d` |
| `build.sh` | The whole build; owns the build lock |
| `docs/PARITY.md` | The ledger of what the web does vs what this app does. It is the file a future reader trusts most, so a row there must match code |
| `docs/research/*.md` | Two kinds. A web reference (`protocol`, `design-system`, `chrome-ui`, `transcript-ui`, `panels-settings`, `mobile-remote`, `composer-menu-and-tools`, `notices-and-fold`, `journal-compaction`) whose wire contracts and reverse-engineered design are the durable value; and app notes (`trajectory`, `goal-chip`, `files-and-deliverables`, `sidebar-workspace-groups`) |
| `README.md` | The reader-facing document; not part of `docs/` |

`app/src/main/res` bundles no font; the theme uses the platform default and
monospace.

## Build and device loop

```
./build.sh :app:assembleDebug                     # lock is INSIDE build.sh now
tools/install.sh 127.0.0.1:5555                   # lock is INSIDE the script
tools/install.sh <phone serial>                   # install to the phone every time too
adblease 127.0.0.1:5555 .probe/verify.sh   # lease the whole verification block
```

`adblease` holds one lease for the whole sequence. A per-command lock lets
another agent's install land between two of your steps, and an install
force-stops the app — a check of the lineage sheet once came back as a bare
"Loading sessions…" screen exactly that way. The box hosts one emulator (4
threads, load average ~14 with one emulator plus Gradle), so serialise rather
than parallelise. Never drive or capture the user's phone; install to it, do
not touch its screen.

`XDG_DATA_HOME` is pointed into `.toolchain/` by `build.sh`: the Kotlin compile
daemon writes its liveness marker under `$XDG_DATA_HOME`, and with the default
`~/.local/share` (read-only in this sandbox) the handshake failed and every build
fell back to in-process compilation — 4 minutes instead of ~30-50s.

## Releases

```
git tag v0.2.0 && git push origin v0.2.0      # runs .github/workflows/release.yml
```

The workflow builds and signs `:app:assembleRelease`, then creates the GitHub
Release. Its body is **`docs/releases/<tag>.md`**, written in the same commit as
the `versionCode`/`versionName` bump, with a generated download/version/SHA-256
block appended — so the prose is reviewable in the repository instead of only
existing on a web page. Bump the version *before* building the APK you test, or
the About sheet will name the previous one.

There is no `gh` on this box and none is needed: the repository is public, so
`curl -s https://api.github.com/repos/bqv/dsh-android/actions/runs?per_page=3`
reports the run, and the release page is readable with any web fetch.

## Device infrastructure

Both the adb server and the emulator are **OpenRC user services**, so they
outlive a shell, a background job or a restart:

```
rc-service --user adb      status|restart     # one adb server + the phone transport
rc-service --user emulator status|restart     # one headless x86_64 emulator
```

Neither the services nor the binaries they run belong to this checkout. They are
generic host infrastructure: source in **`~/bin/adb-wireless`**, service files in
**`~/etc/init.d`** and **`~/etc/conf.d`** — and `~/.config/rc/init.d` /
`~/.config/rc/conf.d` are symlinks into those, so editing a service there IS
editing the live copy. There is no install step to forget.

- Emulator serial: **`127.0.0.1:5555`** — the odd port beside console 5554, which
  `adbtrack` attaches explicitly (from the `emulator-5554` transport) because
  modern adb no longer scans for emulator ports. Use that serial, not
  `emulator-5554`.
- Phone: the LAN host is `192.168.1.100` and the port is random per Wireless
  debugging session, so `adbdiscover` finds it over mDNS
  (`_adb-tls-connect._tcp`); a TCP scan is available behind `ADB_SCAN=1`. Re-pair
  with `adb pair <ip>:<pairing port> <code>` when discovery fails; pairing codes
  expire fast.
- `adb reverse tcp:8081 tcp:8081` (spec in `ADB_REVERSE`) is applied by
  `adbtrack` whenever an emulator registers, so it survives restarts with no
  manual step. **It registers but does not relay on this box's emulator**
  (measured 2026-09-25, emulator-5554): `reverse --list` lists it and the guest
  gets its listener, but the host side accepts the connection and reads zero
  bytes while the guest gets no reply — reproduced with adb's own client, so it
  is not our code, and restarting the adb server and adbd changed nothing. From
  inside the emulator, reach the host at **`10.0.2.2:<port>`**, which does carry
  data. Watch out when testing this by hand: `toybox nc`'s `-w` is only the
  *connect* timeout, so without `-q 2` it exits at stdin EOF and prints nothing,
  which looks exactly like a broken relay.
- `adblease <serial> <command...>` is the device lease (it replaced
  `tools/device`), installed in `~/bin`.

## Traps

0. **Never launch with `monkey`.** `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
   does not only start the app: it injects a random input event, and a random
   swipe-and-tap opens the notification shade and lands on the rotation tile. Measured
   on the user's phone — `am start` left `accelerometer_rotation` at 0, `monkey` flipped
   it to 1 every time — and it was blamed on this app for an evening, including a fruitless
   hunt through permissions. Launch with `adb shell am start -n <pkg>/<activity>`, and
   check the lock is where the user left it when you are done. The component is not
   `<applicationId>/.MainActivity` on the debug build — the id carries a `.debug` suffix
   and the class does not, so it is `uk.xa0.dsh.debug/uk.xa0.dsh.MainActivity`.
1. **Probe the running host, never its shipped descriptors.** The wire parameter names
   the gateway validates against are *not* always the ones in the `typert.host.js` files
   installed beside it: reading those said `terminal/environment` takes `agent`, the app
   was changed to send `agent`, and the host answered `missing "agentId"; unexpected
   "agent"` — the whole Shell tab, every agent-scoped call, and the Files panel, broken
   by a "fix" that a single request would have refuted. One `POST` to
   `http://127.0.0.1:8081/api/<endpoint>` with the candidate arguments settles any of
   these in a second (cookie recipe in `~/ref/NOTES.md`), and the error names the field.
   Measured that way on 0.2.0-rc.2: `terminal/resize` takes `agentId`, `id`,
   `attachmentId`, `cols` and `rows` — a body carrying `columns` is answered
   `missing "cols"; unexpected "columns"` — so `net/TerminalClient.kt` is right where
   the shipped descriptor's `sessionId` spelling is not. `agentId` is then resolved *as
   a session id* (a bogus one answers `session/not-found` naming `sessionId`). And an
   agent-scoped call made from a **subagent's own session** is refused with
   `session/agent-busy` ("owned by subagent routing"), so these cannot be probed from a
   child session at all — only from a top-level one. (`terminal/list` does work from a
   child: it is the one call here that takes `sessionId`.)
1. **The JVM harness cannot measure text.** Robolectric lays text out with stub
   metrics — probed in `TextOverflowProbeTest`, 24,892 characters measure as a single
   260px line, about 0.6px per glyph — so nothing wraps and `hasVisualOverflow` is false
   however long the string is. Box layout, row positions, gesture effects and list shape
   are all real there; typography is not. **A test whose subject is where text breaks
   cannot be written in this repository** and has to be seen on a device. Two tests have
   been deleted for being unable to see their own subject: one comparing composition cost
   by wall clock (the first run pays the JIT warm-up) and one asserting a "Show more"
   offer appears on a clipped paragraph.
   The **height** is stubbed too, and it is not the height the tokens promise. The harness
   density is 1.0, so px and dp are the same number, and a one-line `bodyMedium` box
   (13sp on a 20sp `lineHeight`) measures ~35dp rather than 20dp: the model chip that draws
   it comes out at 43dp against the 28dp its line height and its own padding add up to. An
   assertion on an absolute height is therefore wrong by a wide margin *even when the
   layout is right*, and it fails on the good build — assert a line count (how many children
   carry `SemanticsProperties.Text`) or the relationship between two measured nodes instead.
   **Width is the same story from the other side: text has no width**, so a long string
   creates no layout pressure at all. A test that needs a row to be tight must take width
   *away* from it (a `Modifier.width` on a state the test can change), not lengthen a name —
   that is what `ComposerOverflowTest`'s squeeze case does, and its long-name cases would
   pass against a deliberately broken row.
1. **Compose's `clickable` has no movement test, so a drag inside a button is a click.**
   Read from the 1.6.8 bytecode of `TapGestureDetectorKt.waitForUpOrCancellation` — the
   function every tap waits in — which compares exactly three things: `changedToUp`
   (the tap), `isConsumed` (cancelled) and `isOutOfBounds` (cancelled). There is no
   touch-slop comparison anywhere in the tap path, so a finger dragged across a node and
   released *inside* it fires `onClick`. It is not a harness artefact: the same code runs
   on the phone. Cost: `PinchCellSizeTest`'s "a one-finger drag is left alone" failed
   against the shipped `clickableNoRipple` overlay on the terminal grid, and the assertion
   was right — a drag must not bring the soft keyboard up. Any surface that has to tell a
   drag from a tap must do it itself; the terminal grid's own handler consumes a
   one-finger gesture once it passes `viewConfiguration.touchSlop` (`ui/Modifiers.kt`,
   `pinchCellSize`).
1. **Locks live in the checkout, never `/tmp`.** Each DSH bash call gets a private
   tmpfs, so a `/tmp` flock is not a mutex at all; two holders never meet. Every
   lock is `$ROOT/.build.lock`, `.install.lock`, `.device-<serial>.lock`.
2. **One adb client for everything.** `/usr/bin/adb`, `/opt/android-sdk` and the
   emulator's bundled platform-tools are three versions; when they disagree each
   client restarts the shared server on 5037 and drops live transports. The adb
   service pins `ADB_BIN=/opt/android-sdk/platform-tools/adb` (nothing it runs
   resolves a bare `adb`), the emulator service pins `ADB` for its own `stop`, and
   `DSH_EMULATOR_ADB_DIR` is what the launcher puts in front of PATH.
3. **Never point `ANDROID_USER_HOME` at a build toolchain home.** `adb.confd` used
   to set it to `.toolchain/android-home`, which holds a debug keystore and **no
   `adbkey` at all**: any adb server started from that environment had no key for
   the phone, so Wireless debugging could not authenticate and every transport
   landed as `offline` — advertised forever, never usable. The keys live in
   `~/.android`, so that file now `unset`s the variable instead of setting it.
4. **The keepalive must not drop the emulator.** `adbtrack` sweeps transports that
   are not in `device` state, and an emulator mid-boot is exactly that. Emulator
   transports are exempt (odd `55xx` ports and `emulator-*`; even ports are the
   console). It blocks on `host:track-devices`, which sends the **entire device
   list** on every change as a 4-hex-digit length followed by exactly that many
   bytes — and sends nothing at all until the first change, so the devices already
   registered have to be adopted once at startup.
4. **Never capture blind.** Gate a screencap on the app actually being focused
   (`dumpsys window | grep mCurrentFocus` contains `uk.xa0.dsh.debug`), and check
   `mScreenState` first: with the display off, `screencap` silently returns a
   stale all-black frame that looks like an app bug. Waking is not enough if the
   display never came up — verify, or treat the frame as "no capture".
5. **The gateway's argument names are exact.** It rejects both extra and missing
   keys with `gateway/arguments-invalid`, so `agent` where the host declares
   `agentId` kills the call loudly. An endpoint that does not exist is the one
   case that fails quietly.
6. **One `$events` registration.** The host fans a waterfall to every registered
   event client and settles it only once each has answered, so a second
   registration makes "Skip" and both attention timeouts unable to settle. The
   single registration is owned by `AttentionCenter`, which relays
   `api-session/status` to the ViewModel. Registering once is necessary but not
   sufficient: a web tab or a second device keeps the waterfall open, so Skip is
   only decisive when this app is the sole `$events` client.
7. **`clientTimeZone` is only sent when it is `UTC` or an Area/Location name.** A
   device pinned to a fixed offset made the host reject the whole prompt with
   `session/invalid-time-zone`.
8. **`session/page` is the only back-pagination route.** `session/follow` opens a
   bounded window (120 messages) and answers `cursor`/`hasMore`; older history
   comes from `session/page` with `beforeSeq` = the oldest seq held and
   `throughSeq` = the window's opening cut. `DshViewModel.loadOlderHistory` is
   wired to scroll-to-top.
9. **A session attaches to a Workspace by `workspaceId`, not by `cwd`.** The host
   takes one or the other and rejects both (`gateway/bad-request`); `cwd` alone
   leaves the session Ungrouped. The drawer's New Session resolves a preferred
   Workspace id first for exactly that reason.
10. **Compaction markers are anchored on the checkpoint**, the replacement
    `user/message` whose `source.plugin` is `compact`, and only enriched from
    `compaction/summary`. `compaction/*` are log-only records, so the summary can
    fall outside the follow window while the checkpoint is inside it. A surface
    replacement is model-visible shadow, not a transcript row: clearing the
    shadowed range erased conversation the user had already seen, so replacement
    copies are skipped as rows instead.
11. **The Files pane lists what the session touched**, from the transcript — not a
    live directory tree. Content is read on demand through `workspaceFiles/read`,
    which is scoped by session id, not by directory. `workspaceFiles/list` is
    never called.
12. **Content search latches off per deployment.** A host with no content index
    ("session search is unavailable/disabled") fails every call the same way; the
    ViewModel latches that and stops sending the RPC. It is also not reachable
    today: the only `SessionsDrawer(...)` call site passes no search state, so the
    drawer's query box filters title/cwd only (`ui/ChatScreen.kt`).
13. **The To-dos dock is seeded from the `todos` projection**, because the
    transcript's `todo/write` row can fall outside the follow snapshot window.
14. **Some endpoints answer a bare value, not an object** — `commands/list`,
    `fileReferences/list`, `sessionReferenceResolver/candidates` and
    `directoryPicker/createDirectory` among them. `DshClient.rpcRaw` returns
    `result.value` untouched; `rpc` casts to `JSONObject` and is the wrong door for
    those.
15. **`session/list`'s `running` is the host's *liveness* answer; `subagentTiming.active`
    is not.** The host builds a row's `running` as
    `ctx.agents.get(id)?.status === 'running'` for a session it has attached, and
    `summarizeCold` hard-codes `running: false, agentAvailable: false` for every one it has
    not (`dsh-api-session-controller/lib/index.js`, `summaryFor` / `summarizeCold`). So
    `running` covers a child the app never saw start, and `agentAvailable` separates
    attached-but-idle from cold. The `subagentTiming` projection that rides in the same row
    means something else: it folds the child's *own journal*, and its `active` field is
    present exactly while a `turn/start` has no matching `turn/end` — a fact about the log,
    **not** about whether anything is running. A host restart mid-turn leaves `active` set
    forever with no agent behind it, and that is not hypothetical: after the box crashed
    under three concurrent Gradle builds and the host came back, the roster held two such
    rows (`fd23b122-1474-4ea6-87a5-d065d522e3b5`, `dcd6240b-4eb5-4170-b6bd-bf971f3007cf`),
    both `running: false, agentAvailable: false` with `active.through` frozen 64–65 minutes
    earlier, while children that really were running in the same snapshot had `through`
    under a minute old. **Reading `active` as liveness paints dead children as running** —
    it was tried on this branch and reverted. The one thing that may hold a running claim a
    pull denies is a live `api-session/status` frame newer than the pull's cut.
16. **`subagentTiming.active` is worth reading for exactly one thing: diagnosing a crash.**
    `active` present, `running: false`, `agentAvailable: false` and an old `through` is the
    signature of a turn that was cut off by a restart. It is a durable fold, so it survives
    the restart even though nothing is running.
17. **Only `session.v4` records get the full projection fold.** 773 session directories hold
    `session.v4.jsonl.zstd` and exactly those 773 roster rows carried `subagentTiming`; the
    1,126 with a legacy `session.v3.jsonl.zstd` fold to `{title}` alone. Nothing in
    `RunningBook` depends on it — `running` is present for every row — but a reader who
    wants the timing projection must not expect it on an old record.
18. **`subagentCatalog` carries no activity, and `subagents/list` does not exist.** The
    projection is identity only (`id`, `createdAt`, `mode`, `label`), so any fold that reads
    a running state out of it is inventing one — an `activity == ""` default once wiped the
    row of every child on every catalog rebuild. `subagents/list` 404s on 0.2.0.

19. **An mDNS answer is not all in the answer section.** Android answers a
    `_adb-tls-connect._tcp` PTR query with the PTR under *answers* and the SRV,
    TXT and A records under **additional**. A parser that reads only `ancount`
    finds the instance, has no port for it, and drops it — which is why the old
    `adb-discover.py` never once found the phone, and why the service answered
    "not found" with a 20,001-port scan every 30 seconds, forever, from a Python
    interpreter with 256 threads. Parse all three sections, and stop listening as
    soon as an instance has both a port and an address: the phone replies in
    single-digit milliseconds, so listening out the timeout is pure latency.
20. **`host:connect` reports failure as `OKAY`.** A refused port comes back as
    `OKAY "failed to connect to '…': Connection refused"` — the status code is
    fine and the verdict is in the message. And `reverse:` is **not** a host
    service: `host-serial:X:reverse:forward:…` is rejected with "unknown host
    service". The reverse has to go out as two requests on one socket —
    `host:transport:<serial>`, then `reverse:forward:<remote>;<local>`.
21. **A spawned session does not reach the app without `api-session/added`.** The roster
    is refreshed only by a whole-world `session/list` pull, and the host's membership
    *pushes* (`api-session/added`, `api-session/removed`, both `mode: 'emit'` in
    `dsh-api-remotes`' allowlist) were consumed by nothing, so a session that appeared
    between two pulls had no row at all. Measured on the device: child `cd66afc5` was
    created 59 s before a probe; the app's roster stayed at **1,891** rows while the host
    listed **1,892**, and the parent's lineage sheet read `running=1` against the host's
    `running=2` for that entire minute. On an idle parent — no follow events, so nothing
    triggers a pull — the lag is unbounded. The `added` payload is the **whole**
    `session/list` row (`summaryFor` builds both), so it is fed straight in
    (`DshViewModel.applySessionAdded`) and a debounced pull is asked for as well, because
    ordering, the archived filter and catalog labels remain a pull's job.
22. **A `session/projections` read can be empty where `session/list` is not.** For
    `session-a771fb9b-5ba8-4cb6-8339-e45de4cebbdd`, `session/projections {sessionId}`
    answered `modelSelection.next = null, lastUsed = null, asOfSeq = null` in two
    back-to-back reads (19:12:34 and 19:12:37) while `session/list` gave
    `next = deepseek-official/deepseek-flash/off`, `lastUsed =
    deepseek-official/deepseek-v4-flash-vision-exp/low` with `asOfSeq = 44`. Observed
    behaviour; the record is `session.v4.jsonl.zstd` and carries seven `model/selection`
    events, so the data is not missing from the log. **Inference, not established**: some
    projections are registered runtime views rather than journal folds (the controller
    declares several as `init: () => null` with `view: () => …`), so a cold read
    legitimately answers null while the live summary has the value. Whatever the cause,
    **cross-checking an app chip against `session/projections` is the wrong instrument** —
    use the `session/list` row, or the `session/follow` snapshot, which is what the app
    itself reads. One evening was lost to probes that used the other endpoint.

## Host behaviour: model selection (probed on 0.2.0-rc.2)

Measured, not inferred. These are host facts, so a change to the chip that contradicts
them is a bug in the app. Everything here was established with `POST
http://127.0.0.1:8081/api/<method>` (cookie recipe in `~/ref/NOTES.md`) against a
throwaway session created for the purpose — **never against a live session**, because a
local model selection can pull gigabytes of weights onto the user's GPU.

- **Argument shapes disagree between methods on the same host.** `session/modelCatalog`
  takes `{}`; `session/list` takes `{"_request":{}}`; `session/projections` and
  `session/selectModel` both take `{"request":{…}}`. The error names the field, so probe.
- **`session/projections` exists but is not in the shipped descriptors.** It is not in
  `docs/research/protocol.md` either — but it answers `{"request":{"sessionId":…}}` with
  the full `values` map, which is the cheapest way to read one session's folded
  projections. The app does not call it; it relies on the projection stream instead.
- **`modelSelection` is `{lastUsed, next}`, and the two are different facts.**
  `next` is what the next turn will use and moves the instant `session/selectModel` is
  accepted (`agents.selectForNextRequest`); `lastUsed` is what the last turn actually ran
  and moves only when a turn runs. Observed disagreeing on a real session:
  `lastUsed: deepseek-official/deepseek-v4-pro high`, `next: deepseek-official/deepseek-flash low`.
  A blank session projects `{lastUsed: null, next: null}`.
- **A refusal is hard, and mutates nothing.** An unlisted model, an unlisted provider, and
  an effort the route does not advertise are all answered
  `session/model-unavailable` ("Select an available model before sending a message." /
  "…does not support reasoning effort \"medium\"") and leave both halves of the projection
  untouched. There is **no** silent substitution of a different route. The only
  "resolves differently" case found is the effort: omitting `reasoningEffort` is answered
  with the route's `defaultEffort` filled in.
- **`session/selectModel` moves the global default `session/modelCatalog.default` — and it
  does so asynchronously.** Immediately after the call the catalog still reports the *old*
  default; the new one appeared after ~0.4 s in one measurement and ~2 s in another. This
  is the trap that matters: a chip built from `modelCatalog.default` has a lag baked into
  it, so a re-read taken right after a selection advertises a model the session is not
  going to run. It is also easy to "disprove" the global write by reading immediately —
  two independent readers did exactly that and were wrong.
- **The host pushes the change.** On the `session/control` stream it sends
  `{"type":"projection","sessionId":…,"key":"modelSelection","value":{lastUsed,next},"seq":…}`
  both when a selection is accepted **and again when `lastUsed` rolls forward after a
  turn**. An app that reads `modelSelection` only from a follow snapshot therefore never
  sees `lastUsed` move. Reached by opening `ws://127.0.0.1:8081/api/remote.mux` and sending
  `{"type":"open","streamId":"s1","endpoint":"session/control","payload":{"args":{}}}`;
  streams cannot be opened on the unary `POST /api/<method>` route at all
  (`gateway/signature-invalid`: "stream Remote methods must be opened through the stream carrier").
- **`session/selectModel` resumes the session**, and `session/prompt` on a session whose
  selection moved but which has not run yet will run the new route and then report it as
  `lastUsed` — which is what makes a pending switch a real, observable state rather than a
  UI invention.

## Host behaviour: the local model server (probed on 0.2.0-rc.2)

Measured, not inferred. This is what the app's fourth tab ("Server") is built on, and the
first two bullets are the reason it does not show a health readout.

- **The llama.cpp endpoints are unreachable from this client, and nothing on the host
  proxies them.** The routers answer their own loopback ports on the host — `GET
  http://127.0.0.1:55555/health` → `{"status":"ok"}` (same on `55556`), and
  `/v1/models` → every GGUF in the scanned directory, each with
  `status.value` ∈ `loaded`/`loading`/`unloaded` and its launch `args` (`--ctx-size` and
  all). But the phone reaches the host through `adb reverse tcp:8081` and one URL, and on
  that port `GET /health`, `/v1/models`, `/props`, `/slots` and `/metrics` all answer
  **404**, `/api/*` is the RPC surface only (401 without a cookie), and nginx fronts the
  gate with a single `location / { proxy_pass http://127.0.0.1:8080; }` (checked with
  `nginx -T`; the only other server blocks are the LAN `192.168.1.110/111:80` ones, same
  single location). **So loaded-model / slots / VRAM / throughput are not obtainable and
  must never be invented.**
- **`llm/discoverModels` is a real live probe the host performs for you — but only if
  you do not name the provider.** `POST /api/llm/discoverModels` with
  `{"settingsNs":"llm-pi-ai","request":{"baseURL":"http://127.0.0.1:55555/v1","api":"openai-completions","apiKey":"…"}}`
  answers the twelve model ids the router advertises; against a port with nothing
  listening it answers `{"ok":false,"error":{"code":"llm/model-discovery-rejected",
  "message":"could not reach http://127.0.0.1:55599/v1/models"}}`. **Add
  `"provider":"<id>"` and that stops being true**: the host checks its shipped pi-ai
  catalog *by provider id* before it touches the network
  (`dsh-llm-pi-ai/lib/index.js:2286-2296`), so `{"provider":"groq",
  "baseURL":"http://127.0.0.1:55599/v1"}` — a dead port — answers `ok:true` with
  Groq's cloud catalogue. A reachability verdict built from that is confident and
  false. With no provider, `catalogModels(undefined)` finds nothing and the call is
  unconditionally `GET <baseURL>/models` — one GET and nothing else
  (`dsh-llm-pi-ai/lib/index.js:2313`), so it can never load a model and is safe for a
  tab to run on open. The namespace is exposed to clients, unlike the rest of `llm/*`:
  `llm/list` and `llm/status` are 404, while `llm/listProviders`,
  `llm/listConfigurableProviders` and `llm/discoverModels` are the three real
  descriptors (`dsh-llm/lib/typert.host.js`) and all three answer.
- **The declared endpoint is in `settings/describe`, and it is the only honest way to say
  which provider is "local".** The `llm-pi-ai` namespace's `value.providers.<id>` holds
  `displayName`, `api`, `baseURL`, `models[]` (each with `contextWindow`, `maxTokens`,
  `input`) and the provider's `defaultContextWindow`/`defaultMaxTokens`/`defaultInput`/
  `headers`. Nothing in `session/modelCatalog` carries an endpoint or a locality flag — a
  group is `{id, name, models}` — and the live local routes are `dsh-local`, `local-2slot`,
  `dsh-compactor` and `dsh-local-aux`, so a name-prefix rule misses `local-2slot`. The
  app's rule is therefore the endpoint: a profile whose host is loopback
  (`127.0.0.0/8`, `localhost`, `::1`). A model's `"input": []` means the *provider's*
  `defaultInput` applies, not "no modalities" (`Qwen3.8-27B-UD-Q4_K_M` is declared that
  way).
- **`dsh-local-llm-controller`'s `localLlm/getState` is not server health.**
  `POST /api/localLlm/getState` answers (`status`, `slot`, `mode`, `preset`, `pid`,
  `lastError`, `logTail`, `config`) and the namespace appears in `settings/describe` — but
  that plugin manages **its own** `llama-server` child on Windows (`serverExe` default
  `llama-server.exe`, `llamaDir` empty here), so on this box it reports
  `status:"stopped"` while both OpenRC routers are in fact serving. Its `status` must not
  be shown as the server's. Its host half does run `curl http://127.0.0.1:<port>/health`
  internally, but exposes no Remote method that returns the result.

## Open items

- **Scrolling is being rebuilt from evidence, not from an account of it.** The
  latent recorder in `uk.xa0.dsh.diag.ScrollDiag` is attached to every scroll
  surface (`docs/SCROLL-DIAG.md`); read it with `adb logcat -d -s DshScroll:I`.
  The `fight` records — the app moving a list while a finger is down — are the
  ones to look at first.

- **Device verification is not re-checked by a docs pass.** Screen evidence lives
  in `.probe/`; a surface with no capture there is unverified, and this note
  makes no claim either way.
- **Feature gaps live in `docs/PARITY.md`** under "Open work": markdown gaps, the
  interrupted assistant tail tag, sidebar fade/reorder, workspace rename/delete,
  the missing header seats, `settings/*`/skills, and the goal activation label.
  (Image and SVG previews and the walk up from a subagent to its parent are both
  done — see the ledger.) The drawer's content search
  is **not** on that list any more: it is wired (`SessionsDrawer` calls
  `session/search` and keeps the availability flag), and this note listed it for
  several days after that was true.
- **The emulator service is the only supported way to run it**; a desktop restart
  kills anything started by hand, so check `rc-service --user emulator status`
  before assuming a device is up.

## trap: a missing instrument reads exactly like a finding

Twice in one session I concluded "the app never receives X" from a diag file that had no
X-reporter in it. The instrumentation had been edited in and then lost — a later `git
status` showed `DshViewModel.kt` modified, so the file *looked* instrumented, and no record
appeared, which read as evidence of absence.

Before drawing a conclusion from a file that records instrumentation, grep the **source**
for the reporter. `grep -c 'note("catalog"' app/src/main/java/uk/xa0/dsh/DshViewModel.kt`
costs nothing and would have caught both.

Related, same file: edits to `DshViewModel.kt` have silently reverted more than once —
always confirm the change is present (`grep`) before building, and again after.

## trap: never let subagents run builds

On 2026-10-08 three subagents were each given a worktree and told to build. Their worktrees each
carry their own `.build.lock` (it lives in the checkout), so the lock serialised nothing: three
Gradle daemons plus a ~2.7 GB Kotlin daemon each ran at once, load hit 27, and the machine
crashed and lost the session.

Two lessons, and the second is the trap:

1. **Builds are top-level and serial.** A subagent edits and reasons; the orchestrating session
   runs the build, one at a time, and feeds the compiler/test output back.
2. **A lock inside a checkout is not a global lock.** `build.sh`'s `.build.lock` is per-worktree,
   which reads as safe when you have one worktree and is worthless when you have three. If you
   parallelise across worktrees, the serialisation has to live somewhere shared.

Check `free -g` and `/proc/loadavg` before starting a build, and never start one while another
is running. One Gradle build on this 4-thread box is roughly 4 GB.

Extended the same night: **tests count as builds.** A subagent may write tests but must not run
them, for the same reason — three agents running `:app:testDebugUnitTest` is three Gradle runs
plus three Kotlin daemons. The orchestrating session builds and tests, batched, top-level, and
feeds the output back. A subagent's job is code, reasoning and host probing.
