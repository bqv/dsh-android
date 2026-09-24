# Handoff — layout, traps, open items

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/uk/xa0/dsh/model/` | Pure folds over host records: `Transcript.kt` (the reducer), `TurnProcess.kt`, `ToolCallTree.kt`, `SessionFiles.kt`, `SessionStats.kt`, `Trajectory.kt`, `SessionSearch.kt` |
| `app/src/main/java/uk/xa0/dsh/ui/` | Compose surfaces; `ui/components/` holds the row cards, `ui/composer/` the trigger derivation, `ui/markdown/` the parser and renderer |
| `app/src/main/java/uk/xa0/dsh/net/` | `DshClient.kt` (RPC + auth), `RemoteMux.kt` (the `/api/remote.mux` stream mux) |
| `app/src/main/java/uk/xa0/dsh/data/` | `ConfigStore.kt`, the Keystore-backed config |
| `app/src/main/java/uk/xa0/dsh/DshViewModel.kt` | Every RPC call site, the mux wiring and the UI state |
| `tools/` | Device plumbing: `adb.initd`/`adb.confd`/`adb-keepalive` (execline supervisor) + `adb-keepalive-pass` (its bash worker), `emulator.initd`/`emulator.confd`/`emulator-run`, `adb-discover.py`, `device.sh`, `install.sh`, `dsh-api.mjs` |
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
tools/device.sh 127.0.0.1:5555 .probe/verify.sh   # lease the whole verification block
```

`tools/device.sh` holds one lease for the whole sequence. A per-command lock lets
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

## Device infrastructure

Both the adb server and the emulator are **OpenRC user services**, so they
outlive a shell, a background job or a restart:

```
rc-service --user adb      status|restart     # one adb server + the phone transport
rc-service --user emulator status|restart     # one headless x86_64 emulator
```

- Emulator serial: **`127.0.0.1:5555`** — the odd port beside console 5554, which
  `tools/adb-keepalive` attaches explicitly (from the `emulator-5554` transport)
  because modern adb no longer scans for emulator ports. Use that serial, not
  `emulator-5554`.
- Phone: the LAN host is `192.168.1.100` and the port is random per Wireless
  debugging session, so `tools/adb-discover.py --host 192.168.1.100` finds it
  (mDNS `_adb-tls-connect._tcp`, TCP-scan fallback). Re-pair with
  `adb pair <ip>:<pairing port> <code>` when discovery fails; pairing codes
  expire fast.
- `adb reverse tcp:8081 tcp:8081` is applied by the keepalive whenever an
  emulator registers, so it survives emulator restarts with no manual step.
- Install the services with `install -m 0755 tools/*.initd ~/.config/rc/init.d/`
  plus the confd equivalents.

## Traps

1. **Locks live in the checkout, never `/tmp`.** Each DSH bash call gets a private
   tmpfs, so a `/tmp` flock is not a mutex at all; two holders never meet. Every
   lock is `$ROOT/.build.lock`, `.install.lock`, `.device-<serial>.lock`.
2. **One adb client for everything.** `/usr/bin/adb`, `/opt/android-sdk` and the
   emulator's bundled platform-tools are three versions; when they disagree each
   client restarts the shared server on 5037 and drops live transports. The
   scripts pin `ADB=/opt/android-sdk/platform-tools/adb` and the emulator's copy
   is a symlink to it.
3. **The keepalive must not drop the emulator.** It sweeps transports that are
   not in `device` state, and an emulator mid-boot is exactly that. Emulator
   transports are exempt (odd `55xx` ports and `emulator-*`; even ports are the
   console). It also blocks on `adb track-devices`, whose wire format is a
   4-hex-digit length followed by the **entire device list**, not a per-device
   line — it is read with `dd bs=1` so header and payload cannot desync.
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

## Open items

- **Device verification is not re-checked by a docs pass.** Screen evidence lives
  in `.probe/`; a surface with no capture there is unverified, and this note
  makes no claim either way.
- **Feature gaps live in `docs/PARITY.md`** under "Open work": wiring the drawer's
  content search at its call site, the terminal panel, markdown gaps, the
  interrupted assistant tail tag, sidebar fade/reorder, workspace rename/delete,
  the missing header seats, `settings/*`/skills, and the goal activation label.
- **The emulator service is the only supported way to run it**; a desktop restart
  kills anything started by hand, so check `rc-service --user emulator status`
  before assuming a device is up.
