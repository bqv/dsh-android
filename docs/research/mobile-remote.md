# DSH mobile / remote / non-browser client support — research

**Scope:** read-only investigation of whether DeepSeek Harness (DSH) already has
mobile/remote/non-browser client support a native Android app could reuse.
**Instance:** `delta` (this box), `$DSH_HOME=/home/user/.dsh`, DSH `@deepseek-ai/dsh@0.1.6-alpha.1`
(`/home/user/bin/deepseek-harness/apps/cli/package.json`).
**Method:** `ls`/`find`/`cat`/`grep`/`read` on the listed paths; secrets redacted as
`<redacted>`. Third-party plugin behaviour comes from external docs (cited), never invented.

---

## 0. Bottom line

1. **The DSH core repo has no mobile, remote, pairing, device-code, token-issuance or
   network-host feature.** Its only non-browser clients are **stdio/in-process**: the Electron
   desktop host (framed pipes) and the SDK/ACP profiles (newline JSON-RPC over stdio). The only
   network authentication in core is the **browser launch-token → signed cookie** used by the Web
   UI on `/api` and the `/api/remote.mux` WebSocket; this deployment puts the gate's password login
   (`/auth/login`, §2.4) in front of it, and that is how a phone obtains the same cookie (§7).
2. **The `dsh` CLI has no `remote`/`mobile`/`pair`/`token`/`api`/`serve`/`device`/`login`
   subcommand.** Only `web` and `plugin`.
3. **This instance does carry already-provisioned remote credentials and residual mobile-plugin
   data** — but the plugins that create them are **currently uninstalled from the active `web`
   profile**, so no mobile endpoint is live:
   - `~/.dsh/remote/servers/<serverHash>/` — a real **`ds-harness-remote`** account/device
     credential, including a **trusted Android phone** (`CPH2465 · DSH Remote`, platform
     `android`) paired 2026-09-19.
   - `~/.dsh/mobile-access/` — residual **`dsh-mobile`** (saya-ch) data; every tunnel provider is
     disabled, no certs/devices persisted.
   - `~/.dsh/settings.yaml` still holds a `mobile-gateway:` block with a bearer token —
     residual config of **`dsh-mobile-gateway`** (agent-mobile).
4. Probes confirm nothing mobile is live: `GET /m/` → **404**, `POST /m/api/session.list` → **405**,
   `POST /api/session.list` → **401** (normal, cookie required).
5. **What the shipped client actually uses:** `uk.xa0.dsh` speaks the first-party surface only —
   `POST /auth/login` (form) → `dsh_auth_session`, with nginx injecting `dsh-auth-*`, both kept in
   a persistent cookie jar; `POST /api/…` and `ws /api/remote.mux` thereafter, no plugin and no
   host change (`DshClient.kt:201-228`, `PersistentCookieJar.kt:10-17`). A pasted raw cookie covers
   a carrier port that has no login door. The plugin routes below (`dsh-mobile-gateway`,
   `ds-harness-remote`, `dsh-mobile`) are the off-LAN/plugin alternatives, not prerequisites for
   the LAN.

> Redaction note: all secret *values* (tokens, keys, hashes, passwords) are replaced with
> `<redacted>`; key names and JSON/YAML shapes are reported verbatim.

---

## 1. Core DSH repo — what exists (and what does not)

### 1.1 `dsh` CLI — exact subcommands and flags

Source: `/home/user/bin/deepseek-harness/apps/cli/src/args.ts`, `apps/cli/README.md`.

- Subcommands: **`web`** and **`plugin`** only.
  - `dsh web` = alias of `--profile web`.
  - `dsh plugin --profile <name> <pnpm args>` forwards to pnpm in the profile directory.
- Root launcher flags: `--profile <name>`, `--from-default-profile <template>`,
  `--patch <path>` (repeatable), `--dump-config`, `--dump-default-config`, `-V`/`--version`.
- Entry modes selected by profile, not subcommands: `--profile acp` (ACP over stdio),
  `--profile headless "<job>"` (one persisted session, prints answer, exits),
  `--profile sdk`, `--profile sdk-minimal` (JSON-RPC over stdio). The profile name `desktop`
  is reserved and rejected by the CLI.
- **Absent:** no `remote`, `mobile`, `pair`, `token`, `api`, `serve`, `device`, or `login`
  subcommand. Any such token would be handed to the booted profile as an app argument, and no
  shipped profile implements one.

### 1.2 `apps/desktop-host` — private Electron child, **not** a network host

- `apps/desktop-host/package.json`: `"name": "@deepseek-ai/dsh-desktop-host"`, description
  *"Private Node-mode host process for the Electron desktop application"*, `"private": true`.
- `apps/desktop-host/src/index.ts`: boots the desktop project **without a listening socket**,
  carrying API + Web assets over **framed byte pipes**.
- `apps/desktop-host/src/wire.ts`: `DESKTOP_HOST_PROTOCOL_VERSION = 3`,
  `DESKTOP_REQUEST_PIPE_FD = 3`, `DESKTOP_RESPONSE_PIPE_FD = 4`,
  `DESKTOP_PIPE_CHUNK_BYTES = 64 * 1024`, `FRAME_MAGIC = 0x44534833`; frame types
  `start`/`data`/`end`/`cancel`; Node IPC carries only `{type:'shutdown'}` and
  `{type:'ready',protocolVersion,dshVersion}` / `{type:'fatal',message}`.
- Routes: `DESKTOP_STREAM_PATH = '/.dsh/remote-stream'` (POST, `application/x-ndjson`),
  `/api/*` → shared Fetch handler, everything else serves Web assets.
- Auth: **none** — there is no socket. `config/desktop.cordis.patch.yml` disables
  `webserver`, `web-startup`, `web-runtime`, etc.

### 1.3 `apps/desktop` — Electron shell, no host auth

- `apps/desktop/README.md`: opens no listening port. Transport = framed pipes + Node IPC +
  custom scheme `dsh-app://` (`src/main.ts`: `SCHEME = 'dsh-app'`,
  `dsh-app://app/index.html`, `dsh-app://shell/startup.html`).
- `src/host-process.ts` spawns `@deepseek-ai/dsh-desktop-host/lib/index.js` with
  `ELECTRON_RUN_AS_NODE=1` and stdio fds 3/4 + `ipc`. No cookie/token/device code.
- Uses `$DSH_HOME/profiles/desktop` and `$DSH_HOME/desktop/pnpm/*` (`src/paths.ts`).

### 1.4 `packages/sdk` — public SDK, **stdio only, no auth**

- Public npm packages `@deepseek-ai/dsh-sdk-client` and `@deepseek-ai/dsh-sdk-protocol`
  (`"publishConfig": { "access": "public" }`).
- Exports `DeepSeekHarness`, `HarnessSession`, `HarnessClient`, `JsonRpcLineTransport`.
- Transport: newline-delimited JSON-RPC 2.0 over a spawned child's stdio; `launch.ts` runs
  `process.execPath` with `--profile <profile> --patch <path>` (default profile `sdk`).
- Methods `initialize`, `session/prompt`, `shutdown`; notifications `session.event`,
  `session.status`, `subagent.started`, `subagent.finished`. No authentication:
  "callers own credential policy" via `env`.

### 1.5 `packages/api/remotes` + `packages/api/gateway` — BFF, **not** an inter-host relay

- `packages/api/remotes/README.md`: "Two-sided BFF for Host Remote capabilities";
  mounts `ctx.remote.$mount()`.
- `docs/api-gateway.md`: Remote calls use the Connection's `/api` route →
  `POST /api/<namespace>/<method>` with body `{ args }`. Streaming uses
  `packages/api/gateway/src/stream-protocol.ts`:
  `REMOTE_STREAM_MUX_PATH = '/api/remote.mux'`,
  `REMOTE_EVENT_STREAM_ENDPOINT = '$events'`, `REMOTE_EVENT_RESULT_ENDPOINT = '$events/result'`.
- **`/api/events.mux` does not exist in 0.1.6-alpha.1** — grep finds no `events.mux` anywhere in
  `packages/`/`apps/`. (The workspace probe `.probe/ws-probe.cjs` still uses that stale path;
  `.probe/live.cjs` correctly uses `/api/remote.mux`.)

### 1.6 Official network authentication — browser launch token → signed cookie

Source: `packages/client/connection/src/browser-auth.ts` (this is the **only** network client auth
in core, and it is a browser-session mechanism, not device pairing):

- `TOKEN_QUERY = 'token'`; `COOKIE_PREFIX = 'dsh-auth-'`; `SECRET_BYTES = 32`.
- Cookie **name** = `dsh-auth-` + base64url(sha256(authority)); cookie payload
  `{ version, authority, issuedAt, expiresAt }`, HMAC-SHA256 signed; attributes
  `HttpOnly; SameSite=Strict; Path=/`.
- The per-process **launch token** is random 32 bytes (`processLaunchToken`), printed in the
  `dsh web:` startup URL. In this instance it appears in `~/.dsh/logs/dsh.log`, e.g.
  `dsh web: http://127.0.0.1:8080/?token=<redacted>`.
- `authorizeIndex()`: `GET /?token=<launchToken>` on the bound authority → **303** with the
  `Set-Cookie`; a valid cookie lets `/` be served; otherwise **401** with
  `"dsh web authentication required; reopen the URL printed by dsh web."`
- The signing secret is a credential record `client-connection/browser-session`
  (kind `grant`) in `$DSH_HOME/.credentials.yaml`. `cookieMaxAgeDays` defaults to 30; this
  instance's profile patch sets **3650** (`~/.dsh/profiles/web/cordis.patch.yml`).
- The Host/Origin trust fence runs before `browserAuth`; `dsh web --host 0.0.0.0` is unsupported
  in core. So a LAN client cannot simply hit `/api` without the cookie **and** a trusted Host.
  On the deployed chain a phone can get both: `POST /auth/login` on the gate returns
  `dsh_auth_session` and the same response carries nginx's injected `dsh-auth-*`
  (`DshClient.kt:201-228`); only the launch-token exchange is host-local (the token lives in
  `~/.dsh/logs/dsh.log`).

### 1.7 `packages/identity`, `packages/credentials`, `native/`

- Identity: `@deepseek-ai/dsh-anonymous-user-id` = random UUID in `$DSH_HOME/.anonymous-user-id`,
  sent as header `x-deepseek-harness-user-id`. No pairing.
- Credentials: `ctx.credentials`, `credentials-local` (default `$DSH_HOME/.credentials.yaml`),
  `authorization` (`ctx.authorization`). The authorization service implements human-guided
  "OAuth-style sign-in / one-time code / account pick" (`registerFlow`, `AuthorizationSession`) —
  **for model-provider credentials, not client/device authentication.**
- `native/`: `native/system` = `@deepseek-ai/node-addon-system` — Linux `landlock-run` launcher
  (`landlock-run [--ro <path>]... [--rw <path>]... -- <argv>...`) and POSIX
  `tryLockExclusive(fd)`. Platform packages darwin/linux. **No Android, no network.**

**Conclusion for §1:** a native Android app cannot reuse any first-party DSH pairing, device-code
or token-issuance flow — there is none. The reusable first-party surface is the **`/api` RPC +
`/api/remote.mux` WebSocket**, guarded by the browser cookie, plus the **`--profile sdk` stdio
JSON-RPC** (not usable over a network without a wrapper).

---

## 2. Instance-level provisioned remote/mobile artifacts

### 2.1 `~/.dsh/remote/` — **`ds-harness-remote`** (liguobao) credentials — real, provisioned

Layout:

```
~/.dsh/remote/servers/e9988d504cda7c1541adafcc/
├── host/                                  # this machine as Host
│   ├── device.json
│   ├── device.key
│   ├── server-credentials.json
│   └── trusted-peers.json
└── client/                                # this machine as Client
    ├── device.json
    ├── device.key
    ├── server-credentials.json
    └── trusted-peers.json
```

`e9988d504cda7c1541adafcc` is exactly `sha256("https://dsh.r2049.cn")[:24]` (verified) — i.e.
`serverHash` is derived from the relay URL.

`host/device.json`:

```json
{ "schemaVersion": 1,
  "deviceId": "01a0bb33-6697-76ee-b2e6-6d2c70d364b9",
  "name": "delta",
  "publicKey": "<redacted: 43-char base64url = 32-byte key>" }
```

`host/device.key` — non-JSON, one 43-char base64url string (32 raw bytes), mode `0600`.

`host/server-credentials.json`:

```json
{ "schemaVersion": 1,
  "serverUrl": "https://dsh.r2049.cn",
  "deviceId": "01a0bb33-6697-76ee-b2e6-6d2c70d364b9",
  "authorizationMethod": "owned_device",
  "account": "github-822863",
  "accessToken": "<redacted>",
  "accessTokenExpiresAt": 1789848668234,      // 2026-09-19T20:11:08Z
  "refreshToken": "<redacted>",
  "refreshTokenExpiresAt": 1792439248650 }    // 2026-10-19T19:47:28Z
```

`host/trusted-peers.json`:

```json
{ "schemaVersion": 1,
  "peers": [ {
    "deviceId": "bba9670d-95cb-485b-b3ed-1d1c695543e9",
    "name": "CPH2465 · DSH Remote",
    "platform": "android",
    "publicKey": "<redacted: 43-char base64url>",
    "membershipId": "01a0bb35-9f01-7ad3-ade1-ee8cb0ca78da",
    "fingerprint": "17E9 A8D4 09A1",
    "trustedAt": 1789848083077 } ]            // 2026-09-19T20:01:23Z
```

`client/` has the **same schema** but `authorizationMethod: "account"`, its own `deviceId`
(`01a0bb33-6711-71f9-b6f1-b01c97dac6e7`), and one trusted peer `name: "delta"`,
`platform: "linux"`, `fingerprint: "35A7 50A1 D9A3"`.

**Interpretation:** this is an **already-provisioned remote-access credential**. The box is
enrolled in the hosted relay `https://dsh.r2049.cn` under account `github-822863`; the
**user's phone (`CPH2465 · DSH Remote`, android) is a pinned trusted peer** of the Host
identity, trusted 2026-09-19. Host access token expired the same day but the refresh token is
valid until **2026-10-19**. No `server-credentials.json.refresh-lock` directory exists.

### 2.2 `~/.dsh/mobile-access/` — residual **`dsh-mobile`** (saya-ch) data

```
~/.dsh/mobile-access/
├── logs/dsh-mobile.log          # 2 lines, both "logging initialized", 2026-09-19 19:45/19:46
├── extensions/                  # empty
└── remote/{cpolar,cloudflared,origin,frp}/control.json
```

Each `control.json` is exactly `{ "version": 1, "enabled": false }` — **all remote channels
disabled**. There are no private certs, no paired-device store, and no custom `mobile.css`/
`mobile.js` present, so the LAN setup was never completed or was purged; the plugin ran once on
2026-09-19 and has not since.

### 2.3 `~/.dsh/settings.yaml` — residual **`mobile-gateway`** config (token present)

`~/.dsh/settings.yaml` line 34:

```yaml
mobile-gateway:
  token: <redacted: 64-hex-char bearer token>
```

This is the persisted config of **`dsh-mobile-gateway`** (agent-mobile). The plugin is *not*
currently loaded, but the token survives, and per that plugin's docs a token rotation is written
back here and takes effect hot. (Other top-level keys: `ui-onboarding`, `llm-deepseek`,
`ui-conversation`, `agent-default-model`, `ui-chat`, `local-llm`.)

### 2.4 `~/.dsh/auth/` — the `:8080` auth proxy (`deepseek-harness-auth`, third-party)

- `state.json`: `{ version: 1, revision, username, password: { algorithm: "scrypt", salt,
  hash, cost: 32768, blockSize: 8, parallelization: 1 }, whitelist: ["127.0.0.0/8",
  "0:0:0:0:0:0:0:1"] }`.
- `store.json`: `{ version: 1, secret: <redacted>, accounts: [ { username: "admin",
  role: "admin", passwordHash: <redacted>, protected: true, createdAt, updatedAt,
  lastLoginAt, totp: { secret: <redacted>, verified: false, createdAt },
  backupCodes: [ { hash: <redacted> } × 10 ] } ] }`.

This proxy owns the public `:8080` port in front of the Harness `:8081` (`~/.config/rc/conf.d/dsh`),
so it is part of the effective auth chain for any LAN client, but it is **not** a device-pairing
system.

### 2.5 `~/.dsh/web-login/` — DeepSeek web-chat login capture (not DSH client auth)

- `accounts.json`: `{ version: 1, activeId: "acc_ba530236" }`.
- `accounts/acc_ba530236.json`: `{ id, token: <redacted>, cookie: <redacted/empty>,
  hifDliq, hifLeim, wasmUrl, userAgent, capturedAt, user: { display, id }, serverId,
  lastVerifiedAt }`. Used by `dsh-deepseek-web-login`, unrelated to remote clients.

### 2.6 Active profile is **not** running any mobile plugin

- `~/.dsh/profiles/web/package.json` bundles: `@deepseek-ai/dsh-base`, `@deepseek-ai/dsh-web-app`,
  `dsh-shared-terminal`, `dsh-session-surgeon`, `deepseek-harness-auth`,
  `dsh-local-llm-controller`, `@michengai/dsh-automation`. **No mobile/remote bundle.**
- `~/.dsh/profiles/web/node_modules/.modules.yaml` and `pnpm-workspace.yaml` still list
  `allowBuilds` for `@xiaosenho/dsh-plugin-remote-access`, `dsh-mobile-gateway` (GitHub
  `agent-mobile`), `dsh-speech` — stale entries.
- `~/.dsh/profiles/web/node_modules/.bin/` holds **dangling** symlinks `dsh-mobile` and
  `ds-harness-remote` (plus `qrcode`, `multicast-dns`); their package dirs are gone.
- `~/.dsh/logs/dsh.err` shows a historical crash loop:
  `dsh: cannot resolve profile bundle "@xgone/dsh-remote" ...` (a removed/unresolved plugin).

**Live probes (2026-09-22):** `GET http://127.0.0.1:8081/m/` → `404`;
`POST /m/api/session.list` → `405`; `POST /api/session.list` → `401` (cookie required); port
`11471` not answering. So **no mobile gateway is mounted right now.**

---

## 3. Documented "remote access" features (ecosystem) — how they work end to end

The catalog `/home/user/tmp/dsh-research/catalog/package/plugins.json` (awesome-dsh-plugin,
updated 2026-09-20, 4062 plugins) has **124 plugins in category `remote`**. The three relevant to
the artifacts on this box:

### 3.1 `dsh-mobile-gateway` (`github:agent-mobile/dsh-mobile-gateway`) — LAN bearer-token gateway

Referenced by `settings.yaml`'s `mobile-gateway` block. External docs:
<https://github.com/agent-mobile/dsh-mobile-gateway> (README/USAGE).

- **Transport:** LAN only (no public tunnel). The plugin re-binds the official webserver to
  `0.0.0.0` and narrows the official `/api` to loopback (`trustedHosts: []`).
- **Endpoints (token-gated mirror of the official API):**
  - `POST /m/api/<method>` ↔ official `POST /api/<method>`.
  - `POST /m/api/respond` ↔ official approval/question respond.
  - `WS /m/api/events.mux`, `WS /m/api/events.host` ↔ official downlink streams.
  - Management page `GET /m/` — **loopback only** (LAN Host → 403).
- **Auth:** `Authorization: Bearer <token>`; constant-time compare; no token / wrong token →
  `401`; method outside whitelist → `403`; blocked device → `403`. Token mandatory (docs differ
  by version on fail-closed vs management-page bootstrap), stored in `~/.dsh/settings.yaml` under
  `mobile-gateway.token`, rotatable hot from `/m/`.
- **Pairing:** QR encodes `服务器|令牌` = `server|token`; the companion Android app
  (`dsh_mobile_app` / Dart SDK ≥ v1.0.35) scans it, defaults to `apiPrefix: '/m/api'`.
- **Method whitelist:** `session.*`, workspace, `host.describe/listDirectory/createDirectory`,
  subagent, goal, `skill.list`, `commands/list|execute`, `llm.models/providers`, plus optional
  `settings.*` / `credentials.*`. Always denied: `host.pickDirectory/openPath`,
  `llm.discoverModels`, agent-preset management.
- **Config keys:** `token`, `allowSettings`, `allowCredentials`, `extraMethods`,
  `maxRequestBodyBytes`, `uiEntry`.

### 3.2 `dsh-mobile` (saya-ch, npm `dsh-mobile@0.4.4`) — LAN HTTPS + tunnels + pairing

Owns `$DSH_HOME/mobile-access/`. External doc: <https://github.com/saya-ch/dsh-mobile>.

- **Install/run:** `dsh plugin --profile web add dsh-mobile@latest` then
  `dsh plugin --profile web exec dsh-mobile setup`, then `dsh --profile web`.
- **LAN:** independent HTTPS listener on **:3443** (`dsh-mobile setup --port`), private CA +
  **certificate pinning**; only paired devices pass. Management API `…/api/mobile-access`
  requires a loopback TCP peer. Pairing: management panel "生成并复制密钥" → QR / link / pairing
  key; persistent per-device trust; device tokens stored in the **Android Keystore**.
- **Remote channels (opt-in, each independent):** Tailscale Funnel, cpolar, cloudflared
  quick/named tunnel, self-hosted FRP, self-hosted reverse proxy. Stored under
  `mobile-access/remote/<provider>/` (matches the four `control.json` files here).
- **Customisation:** `/mobile <prompt>` edits `mobile-access/mobile.css`/`mobile.js` and
  `mobile-access/extensions/` (`host.mjs` runs with host-user rights).
- **Android app:** a Kotlin WebView shell, not a separate UI; it adds native bridge
  (`androidx.webkit` WebMessage), device list, notifications, optional voice.
- Verified against DSH `0.1.6-alpha.2` (plugin 0.4.4).

### 3.3 `ds-harness-remote` (liguobao, npm `0.4.17`) — account + relay + E2EE (off-LAN)

Owns `~/.dsh/remote/servers/<serverHash>/<role>/`. External docs:
<https://github.com/liguobao/ds-harness-remote>, relay `https://dsh.r2049.cn`.

- **Install:** `dsh plugin --profile web add -w ds-harness-remote@0.4.17` (or DSH Desktop, which
  bundles it; or `curl -fsSL https://dsh.r2049.cn/app/install.sh | bash`).
- **Flow:** sign in with GitHub/Zhihu QR (or account/password) → enable remote control for the
  machine → the **Host opens outbound connections only**, no public port. Clients: DSH Desktop,
  Remote Web, and an **Android APK** (GitHub Releases). Transport negotiates
  **LAN → P2P → TURN → Relay**.
- **Crypto:** fixed `Noise_IK_25519_ChaChaPoly_SHA256`; both account membership and the locally
  pinned device identity key must authorize a connection.
- **Credential files:** exactly the schema in §2.1; `serverHash = sha256(serverUrl)[:24]`. Docs
  describe removing a stale `server-credentials.json.refresh-lock` *directory* beside the
  credentials after an abnormal exit, and `/remote login [github|zhihu]` to re-authorize.
- **Revocation:** "Removing a device revokes its credentials, membership, and active connections."

Other catalog examples (not installed here): `dsh-plugin-tether` (iroh P2P), `@linxin666/dsh-remote-web-ui`
(QR pairing + SSE), `dsh-mobile-pairing` (QR offers + revocable Android Device Tokens),
`@xiazhi88/dshgo` (LAN port + Android client), `dsh-webgate`/`dsh-lan-gateway`/`dsh-one-gateway`
(reverse proxies/tunnels).

---

## 4. Does `~/.dsh/remote/` look like an already-provisioned credential for the user's phone?

**Yes.** `host/trusted-peers.json` contains a peer named **`CPH2465 · DSH Remote`**, `platform:
android`, fingerprint `17E9 A8D4 09A1`, pinned `2026-09-19T20:01:23Z`. This is a phone that an
Android client already paired to this Host on the `ds-harness-remote` relay. The Host identity
(`host/device.json`, `host/device.key`) and its relay account (`github-822863`) also persist, and
the Host refresh token remains valid until `2026-10-19T19:47:28Z`. However, the Host plugin is
**not installed/running**, so the pairing is dormant until `ds-harness-remote` is reinstalled.

---

## 5. Exact CLI commands relevant to remote/mobile/API

- Official install of a mobile/remote plugin (the only supported extension route):
  - `dsh plugin --profile web add github:agent-mobile/dsh-mobile-gateway`
  - `dsh plugin --profile web add dsh-mobile@latest` + `dsh plugin --profile web exec dsh-mobile setup`
  - `dsh plugin --profile web add -w ds-harness-remote@0.4.17`
  - remove: `dsh plugin --profile web remove <name>`
- Official server/client entry points (no remote/auth flags):
  - `dsh web --port 8080` (web app flags are handed to the profile after the launcher boundary)
  - `dsh --profile sdk` / `dsh --profile sdk-minimal` (JSON-RPC over stdio)
  - `dsh --profile acp` (ACP over stdio), `dsh --profile headless "<job>"`
- **There is no `dsh remote`, `dsh mobile`, `dsh pair`, `dsh token`, `dsh api`, `dsh serve`,
  `dsh device`, or `dsh login`.** Anything that looks like pairing ships inside a plugin and
  exposes its own HTTP/CLI surface (e.g. `dsh-mobile`'s `setup` subcommand via
  `dsh plugin --profile web exec`).

---

## 6. Official API surface a native app can speak (first-party, no plugin)

If the app talks directly to the Harness webserver (`127.0.0.1:8081` here):

1. Obtain the per-process **launch token** — printed by `dsh web` as
   `http://<authority>/?token=<launchToken>`; in this instance it is in
   `~/.dsh/logs/dsh.log` (`dsh web: http://127.0.0.1:8080/?token=<redacted>`).
2. `GET /?token=<launchToken>` with the exact upstream `Host` (here `127.0.0.1:8081`, as rewritten
   by the auth proxy) → **303** + `Set-Cookie: dsh-auth-<b64url(sha256(authority))>=…`.
3. That recipe is host-local; a phone cannot read the log. Its two routes to the same cookie are
   the gate's `POST /auth/login` (returns `dsh_auth_session` while nginx injects `dsh-auth-*`) and
   a pasted raw `name=value` cookie for a carrier port with no login door
   (`DshClient.kt:201-228`, `:172-177`).
4. Then:
   - `POST /api/<namespace>/<method>` with JSON `{ "args": { … } }` and the cookie.
   - `WS /api/remote.mux` with the cookie (and valid Host/Origin); logical streams include
     `$events` (`REMOTE_EVENT_STREAM_ENDPOINT`) and `$events/result`, plus `session/follow`,
     `session/control` and `workspace/follow`, which the Android client holds for the transcript,
     the queue and the sidebar (`protocol.md` §4.5).
5. `/api` and the WS are behind the Host/Origin trust fence + cookie; without it → `401`.
   `dsh web --host 0.0.0.0` is unsupported, so off-host clients need the auth proxy, a tunnel, or
   a plugin. Keeping the socket open with no Activity is an Android-side problem rather than a
   protocol one: the client runs a `dataSync` foreground service with a LOW-importance channel for
   it (`DshConnectionService.kt:38-59`, `Attention.kt:86-96`).

Note the stale path: **`/api/events.mux` is not a route in 0.1.6-alpha.1**; use
`/api/remote.mux`. The current entity/probe scripts should be updated accordingly.

---

## 7. Recommendation for the native Android app (`uk.xa0.dsh`)

Ranked by effort/robustness for *this* instance. **Settled:** the shipped client (`uk.xa0.dsh`)
takes item 3 — the official cookie chain, no plugin and no host change — so items 1-2 are
alternatives for other clients, not prerequisites.

1. **Plugin route — `dsh-mobile-gateway` and its bearer token.** Purpose-built for a native app,
   the simplest auth (one static `Authorization: Bearer <token>`, no cookie handling), and this
   instance **already has a `mobile-gateway.token`** in `~/.dsh/settings.yaml`. Protocol:
   `POST http://<host>:<port>/m/api/<method>` + `WS /m/api/events.mux` / `.host`, QR
   `server|token`. Add it with
   `dsh plugin --profile web add github:agent-mobile/dsh-mobile-gateway`; a DSH restart is
   required to load it (which ends live sessions — schedule it). Limitation: LAN HTTP is
   plaintext, and the client then speaks a mirrored API rather than the host's own.
2. **Best for off-LAN / relay — reuse `ds-harness-remote`.** The box already holds a Host
   identity + relay account and a **paired Android phone**; reinstalling
   `ds-harness-remote@0.4.17` can restore the existing pairing (refresh token valid until
   2026-10-19). If the goal is to *use* a phone app, install the published **Android APK** rather
   than reimplementing `Noise_IK` + relay. Hand-rolling the client protocol is a large lift.
3. **The shipped path — the official cookie auth over the gate.** `POST /auth/login` on the
   `deepseek-harness-auth` proxy returns `dsh_auth_session`, nginx injects `dsh-auth-*`, and both
   are persisted in a cookie jar and replayed on `/api/…`, `/api/remote.mux` and the sign-in
   re-check (`DshClient.kt:201-228`, `:64-67`; `PersistentCookieJar.kt:10-17`). It needs no plugin
   and exposes the full official API; the launch-token exchange is host-local and unused, and the
   pasted-cookie fallback covers a carrier port with no `/auth/login`. There is no
   device-management UX, LAN exposure still requires the proxy/nginx or a tunnel, and the gate
   session is in-memory with a 24 h default TTL — `ConfigStore.kt:72-88` keeps the password in
   `EncryptedSharedPreferences` (AES-256-GCM, key in the Android Keystore) so a gate restart
   re-signs-in silently.
4. **If HTTPS LAN + mTLS + QR pairing UX is wanted — `dsh-mobile`** (saya-ch). `~/.dsh/mobile-access/`
   already exists; `dsh plugin --profile web add dsh-mobile@latest` + `exec dsh-mobile setup`
   restores it. Heavier protocol (custom CA/pinning, device tokens) and its own WebView-shell app.

**Do not** expect a first-party `dsh pair` / `dsh token` command or a built-in relay — neither
exists in the core project at 0.1.6-alpha.1.
