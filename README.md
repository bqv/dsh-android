# DSH — native Android client

A native Jetpack Compose Android client for a DeepSeek Harness (DSH) host. It
reimplements the official DSH web UI **without a WebView and without installing
anything on the host**: every call it makes is one the web client already makes,
against the host's own public HTTP + WebSocket API.

The wire protocol, not the DOM, is the interface. Nothing is injected into the
host and no host-side plugin is required.

## Requirements

- **Android 8.0 (API 26) or newer.** `minSdk` is 26; `compileSdk` and `targetSdk`
  are 34, built with build-tools 35.0.0.
- **JDK 17 and Gradle 8.7**, unpacked under `.toolchain/` as
  `jdk-17.0.20.1+1` and `gradle-8.7`. `build.sh` expects those two directories to
  already exist; it does not download them.
- **An Android SDK** with platform 34 and build-tools 35.0.0. `local.properties`
  points `sdk.dir` at it (`sdk.dir=/opt/android-sdk` on the development box), and
  `build.sh` additionally exports `ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- There is **no Gradle wrapper**. `build.sh` is the entry point for every Gradle
  invocation, and it redirects `GRADLE_USER_HOME`, `ANDROID_USER_HOME` and
  `XDG_DATA_HOME` into `.toolchain/`, so the build writes nothing to `$HOME` and
  works inside a workspace-write sandbox.
- Hosts are normally reached over plain HTTP on a private LAN; the app's network
  security config permits cleartext so a typed `http://` URL works.

## Build and install

```sh
bash build.sh :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

CI builds the same thing on every push to `main` and every pull request, so an
APK can be had without the local toolchain at all:

- the **Actions** tab → the *Build* workflow → the run for your commit →
  **Artifacts** → `dsh-android-debug-<sha>`, or
- the run's summary, which names the file and its size.

That APK is signed with the standard **debug** key, so it installs over an
existing debug build (`uk.xa0.dsh.debug`) without uninstalling it — the same
package the local build produces. `.github/workflows/build.yml` only reproduces
the `.toolchain/` layout and then calls `build.sh`, so local and CI builds cannot
drift apart.

Install it to one device:

```sh
tools/install.sh <serial>              # installs the debug APK just built
tools/install.sh <serial> <apk>        # or an explicit APK
```

- `build.sh` takes the build lock (`.build.lock`) itself, and `tools/install.sh`
  takes `.install.lock`; both live in the checkout because `flock` on `/tmp` is
  not a mutex here. A second caller waits rather than interleaving.
- Debug builds carry the `.debug` application id suffix
  (`uk.xa0.dsh.debug`).
- `tools/device.sh <serial> <command...>` runs a whole device sequence — install,
  launch, tap, screencap — under one per-device lease, so two verification runs
  cannot split each other halfway. `$SERIAL` and `$ADB` are exported to the
  command:

  ```sh
  tools/device.sh 127.0.0.1:5555 sh -c 'adb -s $SERIAL shell input tap 48 128'
  ```

## Point it at a host and sign in

Set **Server** to the address of the host's HTTP door, e.g.
`http://192.168.1.110/`. A bare `host:port` is accepted and gets `http://`.
There are two ways in:

1. **The host's login form.** Enter an account and password; the app posts
   `application/x-www-form-urlencoded` to `POST /auth/login` with
   `provider=password`. The host answers with its session cookie, and nginx in
   front of the harness also injects the `dsh-auth-*` cookie. Both are kept in a
   `CookieJar` that survives process death and are replayed on every later call.
   The password is stored in `EncryptedSharedPreferences` (AES-256-GCM, key in
   the Android Keystore) so an expired session is re-signed-in silently instead
   of dropping the open transcript back on the setup screen.
2. **A pasted cookie.** The **Advanced** field on the setup screen takes a raw
   `name=value` cookie (`dsh-auth-…` or `dsh_auth_session=…`) and bypasses the
   form. This is the path for a host reached directly on its carrier port, which
   has no `/auth/login` door; leave the account blank.

**When the host cannot be reached**, nothing logs you out. A timeout, a refused
connection or a dropped socket is treated as transient: a first connect stays on
the setup screen with the host's message and retries after 3/6/9/12 seconds, and
a drop on a live screen keeps the transcript visible with a banner while the
mux reconnects. Only an HTTP 401/403 (or a redirect to the login page) is a
credential refusal, and only that offers sign-in again.

**Emulator pointed at a host on the same machine:** the emulator's own
`127.0.0.1` is the emulator, not the host, so `http://127.0.0.1:8081/` reaches
nothing until the carrier port is reversed with
`adb reverse tcp:8081 tcp:8081` (the emulator's `10.0.2.2` is the other route to
the host's loopback). Either way, paste the carrier cookie — the loopback
carrier has no login form. `tools/adb-keepalive.sh` re-applies the reverse for
this box's own emulator whenever it registers.

## Wire protocol

Unary calls are `POST /api/<namespace>/<method>` carrying the harness envelope:

```json
{"type":"client-request","rpcId":"…","method":"session/list","payload":{"args":{…}}}
→ {"type":"server-response","rpcId":"…","result":{"ok":true,"value":{…}}}
```

Argument keys are the **host service method's parameter names**, which is why
`session/list` takes `_request` while `session/prompt` takes `request`.

Everything streaming rides one WebSocket, `/api/remote.mux`, carrying logical
streams:

```
→ {"type":"open","streamId":"s1","endpoint":"session/follow","payload":{"args":{…}}}
← {"type":"item","streamId":"s1","value":{…}}
← {"type":"end","streamId":"s1"}
```

Four endpoints are used:

- `session/follow` — transcript `snapshot`, durable `event` frames, and live
  `assistant-stream` `start`/`chunk`/`end` frames (token deltas).
- `session/control` — one `baseline` plus `queue` / `projection` / `jobs` deltas
  for every session: the pending queue, background jobs and folded projections.
- `workspace/follow` — the workspace registry the drawer groups by and the
  directory picker adopts from.
- `$events` — forwarded host events, including **waterfalls** such as
  `approval/request` and `user-questions/request`. A waterfall must be answered
  or the agent stalls; it is settled with `POST /api/$events/result` carrying
  `{clientId, eventId, outcome}`.

`RemoteMux` replays its open registrations after a reconnect, so a dropped
socket resumes streaming instead of silently freezing the UI.

## What the app does today

### Sessions and the drawer

- Workspace grouping from the host registry, plus the host's **Ungrouped**
  bucket; Group by Workspace/List, Order by Manual/Last updated, per-section
  collapse and "Show more" paging, an Archived filter, and subagent sessions
  nested under their parent with a per-row subagent disclosure. All three drawer
  preferences persist across restarts.
- Local title/path filter over the roster plus host content search
  (`session/search`), debounced 250 ms and merged in the host's order.
- Status dots: the ongoing square chase, green done, amber warning. Rows that
  finished while you were elsewhere keep a client-side "done" marker until you
  open them.
- Session row actions: **Rename**, **Fork session** (`session/fork`), **Copy
  session id**, **Archive** (`workspace/archiveSession`); Settings unarchives.
- New Session from the hero screen (cwd and workspace resolved against the
  registry), and **Add workspace** through the directory picker
  (`directoryPicker/list`, `directoryPicker/createDirectory`, `workspace/create`).
- Refresh (with a minimum spin so a fast host does not look like a no-op),
  Settings, and New Session in the drawer footer.

### The transcript

- User bubbles, assistant markdown, collapsible "Think" reasoning with a live
  "Thinking…" line, plan/todo cards, plugin and error notices, compaction
  markers, and subagent lineage.
- Per-tool cards: bash/pwsh terminal, write/edit diff, read with line ranges,
  an image the agent read drawn inline, grep/glob hit lists, `web_search` /
  `web_fetch`, and the question/answer record for `ask_user_question`. Nested
  sub-calls render as a tree; anything else falls back to the generic
  disclosure with IN/OUT cards.
- Live token streaming with throttling so hundreds of deltas per second stay
  smooth; a per-turn process fold with a summary; a "Deep diving…" status with
  an elapsed clock; per-message clock and copy actions; a back-to-bottom button.
- Chat / Trajectory view tabs, the second being the model's input/output ledger.
- Back-pagination: scrolling to the oldest loaded row pulls one older page with
  `session/page`, so a long session is not capped at the follow window.
- A deliverables row after each finished turn, opening the text preview.

### The composer

- Send, and stop while a turn runs. While running, a long press on send chooses
  **queue** or **steer**; the default is a preference in Settings.
- Model chip and picker grouped by provider, including the reasoning-effort
  options a route accepts; agent-preset chip and picker (`agentPresets/list`);
  access-mode chip and picker (`permissionPresets/catalog`, applied through the
  host's `/permission` command); a Plan chip that leaves plan mode.
- Context meter with a token-breakdown dialog, and session statistic pills
  (turns/steps with throughput, and token usage with cache-hit), each opening a
  detail panel.
- Docks above the card: approval (Allow once / Reject), questions (options,
  multi-select, custom text, plan review), to-dos, goal bar
  (pause/resume/edit/clear), and the queue dock (edit, remove, steer, with a
  local "Sending…" echo until the host admits the row).
- Attachments: the `+` button opens the add/command menu, the system file picker
  stages a file as an upload receipt (`fileUploads/upload`) or sends an image
  inline as `{type:"image"}`. A picture can be sent with no caption.
- `/` opens the host's command roster (`commands/list` → `commands/execute`);
  `@` opens the reference picker (`fileReferences/list`,
  `sessionReferenceResolver/candidates`). Both are caret-accurate and insert the
  mention at the token, not at the end of the draft.

### Files

- The header folder seat opens the session's Files panel: the files the
  transcript touched, with a numbered text preview. Content the transcript
  already carries is preferred; anything else is read with
  `workspaceFiles/read`, scoped to the open session.
- Deliverable chips open the panel on the file they name.

### Settings

- A read-only port of the web settings modal's five sections — General, Models,
  Plugins, Agent presets, Archived sessions — plus Connection and Session
  sections for this client's own facts.
- Live controls are the client-local ones: theme (system/dark/light), busy-Enter
  behaviour, the new-session default preset, and Archived-session unarchive.
  Everything that would need a host settings write is shown as a value, because
  a remote browser lands in a `memory` settings scope where writes are dropped.
- Sign out clears the cookie jar and the stored credentials.

### Background behaviour

- A `dataSync` foreground service holds the event socket open with no Activity,
  so an approval or a question still reaches a pocketed phone. Its ongoing
  notification is LOW importance on its own "Connection status" channel.
- A separate notifications channel carries approvals, questions, a session going
  idle/done, session errors and a blocked goal. Each deep-links to its session.
- `POST_NOTIFICATIONS` is requested on Android 13+, and the battery-optimisation
  exemption is offered, because an OEM battery manager otherwise reaps the
  process and the socket with it.

## Deliberate divergences and gaps

`docs/PARITY.md` is the element-by-element ledger; the short version:

- Notices render as visible rows where the web hides them inside a collapsed
  turn process — a touch UI has no `hidden="until-found"` reveal to get them
  back.
- A typed note alongside a single-select question is sent together with the pick
  (`{selected, custom}`); the web clears one when you touch the other.
- Settings is read-only apart from client-local preferences (see above).
- Not implemented: the terminal panel (`terminal/follow`), the right-panel tab
  strip beyond Files, drag-to-reorder and the drawer's bottom fade, and
  Markdown images/citations/mermaid/math.
- One host-side limitation the app cannot fix: a waterfall is fanned out to
  every registered `$events` client and settles only once each has answered, so
  a Skip or an approval is decisive only while this app is the sole event client
  (an open browser tab keeps it pending).

## Repository map

```
app/src/main/java/uk/xa0/dsh/
  data/    Encrypted config store: host, credentials, theme, drawer preferences
  net/     DshClient (cookies + unary RPC), RemoteMux (WebSocket multiplexer), PersistentCookieJar
  model/   Journal → renderable rows: transcript reducer, trajectory, files, search, stats, tool tree
  ui/      Compose screens and components: chat, drawer, settings, setup, trajectory, theme, markdown
docs/      HANDOFF.md (working state), PARITY.md (parity ledger), research/ (reverse-engineered spec)
tools/     install.sh, device.sh, adb/emulator init scripts and helpers
build.sh   Gradle entry point; pins the toolchain, the caches and the build lock
.github/   the Build workflow: an installable debug APK per push and per PR
app/build.gradle.kts, settings.gradle.kts, gradle.properties, local.properties   Gradle and SDK config
```

## Documentation

- `docs/HANDOFF.md` — repository layout, the build and device loop, the traps
  that have cost real time, and what is still open.
- `docs/PARITY.md` — the parity roadmap against the vanilla web UI, including
  every deliberate divergence and the reasoning behind it.
- `docs/research/` — the reverse-engineered reference: design system, wire
  protocol, transcript/UI node model, settings and panels, composer menus,
  notification plumbing, and the mobile-remote environment.
