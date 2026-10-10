# The homescreen widget

A widget is the only surface this app has that is read **out of process, out of context
and out of time**: a launcher draws it from a `RemoteViews` blob it was handed, the
reader has not just opened the app, and nothing of ours is running. Everything this app
knows, meanwhile, arrives over a live socket.

That is the whole design problem, and this document is the record of how it was
answered. The answer is not a layout.

## What is on the card

A headline, a list, and a stamp.

```
Approval waiting            <- headline: what is waiting on a human
3 running                   <- ...or, when the app can name them, the names themselves:
· Fix the login bug
· Widget sessions
+2 more                     <- what the list had no room for
oldest reading 0:12 ago     <- stamp: a bound over every line above
```

and a tap target on every line: each named row opens *its own* session, and the card
behind the rows opens the session that most needs the user, through the launch path a
notification already uses (`Attention.EXTRA_SESSION`).

The footer's word is load-bearing. It read `checked 0:12 ago` in the first draft, and that
was the wrong sentence: it describes when the *app* last looked, which a reader takes as
"the app is live and nothing is wrong", while the number under it is the age of the
*oldest* reading on the card — the worst line, and the one that bounds how much any of
them may be trusted. `oldest reading` says what the number is. The copy layer could name
*which* line is the oldest, and deliberately does not: the two readings are renewed by two
different owners on two different clocks, so in the app's idle state the answer flips
between "needs you" and "running" every quarter hour for no reason a reader could act on,
and a label that changes for no reason is one people stop reading.

### The running sessions, by name

The count that used to be the second line — `3 running · 1 subagent` — is now drawn only
when the card *cannot* name what is running. When it can, the names are the answer: a
reader running several agents wants to know *which* work is in flight, and a number cannot
tell them that. In order of what the reader is given:

1. **Names.** Up to three running sessions, in the app's own roster order (`session/list`'s
   own sort, `updatedAt` descending — the order the drawer uses, so the card cannot
   disagree with the screen the reader opens next), each on a line of its own with a `·`.
   The count line is gone when this is drawn: three names plus `+2 more` already say
   "five running", and a figure beside them would be the same fact twice.
2. **`+N more`.** When more sessions are running than the card can name, the overflow is a
   line of its own. This is the one thing a bounded list must not do silently: a reader who
   sees three names and no overflow line will take them for the whole world. The count it is
   computed from is the same `working` reading the names came from, so the arithmetic is
   over one list and not a second opinion.
3. **`4 running · 1 subagent`.** The old count line, still drawn when the app knows the
   total but has no titles to put against them — a snapshot written before this change, or
   a roster whose running rows have not been given their names yet. The subagent qualifier
   survives only here: with the names on the card, marking *which* of them is a subagent
   would cost another line, and a subagent's row already carries its label as its title.
4. **`Nothing running`.** The app looked and nothing is running.
5. **`Running: unknown`.** The roster is not known, and no rows are drawn at all. An empty
   list here would be read as number 4, which is a claim the app has not earned.

The list is **fixed rows, not a collection view**. A genuinely scrollable list needs a
`RemoteViewsService`, a second binding into this app's process and a second reader of the
snapshot file, where the design is otherwise one writer and one reader. What it would buy
is scrolling on a surface that does not want it: a placed 3x2 widget on the test handset's
Kvaesitso grid occupies 1040x510 px at the display's 408 override density (2.55x) —
**408x200 dp** — which is a tile that three names and a stamp fit inside with room to
spare, and a tile that is flicked past rather than scrolled. `resizeMode` is
`horizontal|vertical`, so a reader who wants more can have it; the card does not spend a
service on the chance that they do.

The cap is **three**, and it is the same number the snapshot will carry
(`WidgetSnapshot.MAX_NAMED_SESSIONS`): the file cannot hold a row the layout would have to
drop, and a document carrying more is bounded on read rather than trusted.

Titles are the roster's own, trimmed for a tile. Whitespace is collapsed first — a title
can carry a newline, and a row's height is part of a budget spent in advance — and the
title is then cut to `WidgetCopy.TITLE_MAX` characters with `…`, so a reader can tell a
short name from a long one the card shortened for them. The row's own `ellipsize="end"` is
the second, visual bound, and — like all text measurement in this harness — its result on a
device is unverified.

The names live in the snapshot, not in a second source of truth, and they are covered by
the `working` reading rather than by a stamp of their own: they are the detail *of* that
reading, taken from the same roster pull at the same instant, so the age in the footer
bounds them already. When the reading loses its basis the names go with it — the publisher
drops them and the card says `Running: unknown` — because a list of names under a reading
the app can no longer assert is exactly the stale-and-quietly-wrong card this feature
exists to prevent.

### What earns a place, and what was cut

Ranked by "would I want to know this without opening the app":

| Fact | On the card? | Why |
| --- | --- | --- |
| Approvals, questions and plan reviews | **Yes**, as a count and the highest-precedence kind | The agent is *blocked* until it is answered, and the host gives up: an approval lapses after 5 minutes, a question after 15. It is the one fact in this app that is both urgent and useless late. `AttentionCenter` already owns it and already drives notifications from it. |
| Sessions and subagents with an agent working | **Yes**, by name | The difference between "still going" and "finished while I was away" is the question a person actually has while their phone is in their hand, and *which one* is the very next question. The roster makes it answerable: `session/list` is a whole-world pull this app already makes, and every row already carries `running`. |
| Which session and model a turn would run | **No** | That is composer state, not a glance: it is a fact about a screen the reader is not on, it is per-session, and the model chip is already the most lie-prone thing in the app (see the `lastUsed`/`next` trap in `docs/HANDOFF.md`). |
| Background jobs | **No** | They are only known for the session the app has **open** — `job/list` is a per-session stream — so a widget showing "2 jobs" would be silently reporting one session's jobs as if they were the world's. A count that is right for one session and wrong for every other is worse than no count. This is exactly why the roster could be listed and the jobs could not: the roster is app-wide, the jobs are per-session. |
| Account balance | **No** | `BalanceState.Idle` until the usage panel is opened, so on a fresh process it is *not known*; it costs a network call to DeepSeek with the user's own key; and a money figure is the worst possible thing to leave on a homescreen going quietly out of date. |

The bar is "would I want to know this without opening the app", and a dashboard would
fail it in the other direction: a widget that shows six things is read as noise and
stops being read at all.

## The honesty problem

The temptation is a card that says

```
Nothing needs you
```

which is a claim about the world. It is only true while the app can still hear the
host. A widget that keeps saying it after the process is gone is the same lie as a
stale model chip, and it is worse, because nobody is looking at the widget with the app
open — they are looking at it instead of opening the app.

### `known` is a field, not an absence

Every reading is `(known, value, asOf)` (`WidgetReading`). `known = false, value = 0` is
**nobody has looked**; `known = true, value = 0` is **the app looked and nothing is
waiting**. They are drawn as `Needs you: unknown` and `Nothing needs you`, and the
publisher treats the difference as a change worth writing. This is the Router tab's rule
for "not asked" versus "reported as empty" — `RouterLoad.slotsUnread` in this codebase —
applied to a homescreen, and for the same reason: an empty answer and no answer are
different answers.

`known` for "what needs you" is `AttentionCenter.isLive()`: the `$events` registration
is open *and* the socket carrying it is `MuxState.OPEN`. A registration on a dropped
socket looks identical from the inside and would let the app promise to hear something
it cannot.

`known` for "what is running" is `rosterLanded && muxState == OPEN`. A whole-world
`session/list` pull is the only thing that can establish a count: a live
`api-session/status` frame is a *transition* that says nothing about any session it did
not name, so counting frames and calling it a total is the trap `docs/HANDOFF.md` §15
records. The same pull is what makes the *names* assertable, and the same `known` field
covers them: when it falls the publisher drops the names and the card says
`Running: unknown`, because an empty list and an unknown one must never be drawn the same
way.

### The age is a ticking view, not a number we maintain

`RemoteViews` has no rich text and no live update, and the three ways to keep a claim
from rotting are not equal:

1. **Print a wall-clock time** (`as of 14:32`). Never a lie, but the reader has to do
   arithmetic, and nothing on the card says the number above it has expired.
2. **Schedule a wake-up to withdraw the claim** (an `AlarmManager` alarm at the moment
   the reading goes stale). Correct while it works — and it does not always work.
   A force-stopped app has its alarms cancelled *and* its receiver disabled, so the
   card would keep its last rendering forever, which is exactly the failure this was
   meant to prevent. It also needs `ACTION_BOOT_COMPLETED` after a reboot.
3. **A `Chronometer` whose base is the reading's instant.** It ticks up in the
   *launcher's* process, so the age stays true with nothing of ours alive. It cannot be
   left behind, because there is nothing to leave: the age is derived from a timestamp at
   the moment it is drawn, not written down. This is also the one premise of the design
   that can be checked without a device, and it was: `android.widget.Chronometer` carries
   the `android.widget.RemoteViews$RemoteView` runtime annotation in the API 34
   `android.jar`, which is what the host inspects before it will inflate a view inside a
   widget (`javap -v` on the platform jar).

This uses (3). `oldest reading 0:12 ago` becomes `oldest reading 3:41:07 ago` by itself, and the
headline above it is read against it. What the card never does is claim *now*: there is
no "live" lamp and no "connected" badge, because both would need (2) to be withdrawn and
(2) has a hole in it.

While the app *is* running it renews the stamp, so the age stays small and means "the app
was still listening a quarter of an hour ago at the outside" rather than "nothing has
changed since whenever". `WidgetPublisher.HEARTBEAT_MS` is fifteen minutes because that
is `AttentionCenter.QUESTION_TIMEOUT_MS`: the window in which a waterfall can still be
answered, and therefore the window in which a count on a homescreen is still actionable.
The named rows do not get a stamp of their own: they are the detail of the `working`
reading, from the same pull at the same instant, so the footer already bounds them.

## How it updates

Nothing is scheduled. `updatePeriodMillis` is **0**.

- **When the app is running**, `AttentionCenter` publishes on every change of the pending
  set, on every socket state change, and from `DshConnectionService`'s keep-alive loop;
  the view model publishes on every roster-running change and from the one-second loop
  that already watches the mux, handing over every running session and its title.
  `WidgetPublisher` collapses all of that into a file write and a widget update at most
  once per `HEARTBEAT_MS`, or immediately when a value moves — and a rename is a value
  moving, because the title is a thing the card draws. The write is off the main thread and
  coalesced through a conflated channel.
- **When the app is not running**, nothing at all happens. Every number on the card
  arrived over a socket; a wake-up could only re-read the same file and draw the same
  bytes, one interval older. The age the launcher is already ticking is what carries the
  staleness, and it needs no alarm, no `JobScheduler`, no `WorkManager` and no
  `RECEIVE_BOOT_COMPLETED`.
- **If no widget is placed**, the publisher writes nothing and updates nothing. The
  reading is still held in memory, so adding a widget draws what this run already knows;
  a user who never placed one pays exactly zero.

Measured comparison, for the record: `updatePeriodMillis` would cost up to 48 process
wake-ups a day *whether or not the app has anything to say*, on a device where the whole
point of the battery-exemption dance is that the process should not be roused for
nothing. This design costs one widget update per quarter hour, and only while a widget is
placed and the app already has a socket open.

## Where the state lives, and who writes it

```
AttentionCenter ─┐
                 ├─► WidgetPublisher ─► files/widget/snapshot.json ─► DshWidgetProvider ─► RemoteViews
DshViewModel ────┘        (the one writer)                                (the one reader)
```

- `files/widget/snapshot.json`, written by a temp-file-and-rename so the reader can never
  see half a document. It is not exportable and not shared: the provider runs in this
  app's own process, so there is nothing to negotiate with.
- **One writer**, `WidgetPublisher`. Its two contributors are each already the authority
  for the fact they push — `AttentionCenter` owns the pending set, the view model owns the
  roster fold — so the card is a projection of existing state and not a second source of
  truth that could disagree with the screen. The running names are not a third contributor:
  they arrive on the same call as the running count, from the same fold, so the two cannot
  drift apart.
- **The document's version is still 1.** `running` was added as an optional key rather
  than a shape change, and both directions of that matter: this build reads a document
  written before it (no names, so the count is drawn) and the previous build reads one
  written after it (it never inspects a key it does not know, so it draws exactly what it
  drew before). A version bump would have blanked **every widget already on a homescreen**
  to "not known" until the app next ran, and this change does not need that. The version
  exists for documents a build cannot read at all.
- **Seeded from the last run at construction.** A restart does not make yesterday's
  reading false, it makes it old, which is what the age already says. Starting from
  nothing would instead claim that nothing was ever seen, which is a *worse* lie than an
  old number. The names come back with it, and are drawn under the new age like every
  other reading.

## Why this is plain `RemoteViews` and not Glance

Established before anything was written, because it decides the whole shape of the work:

- **Glance is not in the toolchain's offline cache.** `androidx.glance` and
  `androidx.datastore` have no entry at all under `.toolchain/gradle-home/caches/modules-2/`,
  and `androidx.core:core-remoteviews` is not there either. `glance-appwidget:1.1.0`'s POM
  pulls the whole glance family, four `androidx.datastore` artifacts, `compose.runtime`,
  `compose.ui:ui-graphics`/`ui-unit`, and a protobuf runtime. Maven is reachable from this
  box (`dl.google.com` answers 200), so it *could* be downloaded — but this repository's
  build is pinned to a cache that works offline and is shared by every worktree, and that
  is a real thing to spend.
- **`minSdk` is not the obstacle.** The `glance-appwidget:1.1.0` AAR declares
  `minSdkVersion="21"`, comfortably below this app's 26. Availability is the obstacle, not
  the API level.
- **Glance could not express this design anyway.** Its primitives cannot tick: a Glance
  widget re-renders when it is told to, which is precisely the wake-up that a force-stopped
  app never receives. The only route to a `Chronometer` is `AndroidRemoteViews`
  (`AndroidRemoteViewsKt` is in that AAR), which takes a hand-built `RemoteViews` — the
  fallback, wearing a dependency tree. Glance is a fine answer to "I want to write widget
  layouts as composables"; this card is a handful of `TextView`s and a clock, and the clock
  is the part Glance cannot give.

So: a plain `AppWidgetProvider`, a plain XML layout, and no new dependency.

## What is not verified

Written honestly, because an inferred number presented as measured is the failure this
whole document is about:

- **Nothing here has run on a device.** No build had been made when this was written. The
  layout, the `Chronometer`, the provider registration and the `exported="true"` choice
  are all reasoned from the platform's API and from what the shipped widgets on the test
  handset declare (checked with `aapt2 dump xmltree` on a pulled APK), not observed.
- **Whether a launcher keeps a widget `Chronometer` ticking** across its own process
  restarts is not measured. That `Chronometer` is a `@RemoteView` — i.e. that a launcher
  will inflate it at all — *is* measured (see above); that it goes on counting after the
  launcher is restarted is documented behaviour, and the cached `RemoteViews` is what a
  restarted launcher re-applies, but it has not been watched.
- **No device verification of the card's appearance.** `app/src/main/res/layout/widget_dsh.xml`
  is asserted to inflate and to carry the right text, and *not* to look right: the JVM
  harness cannot see where text breaks (`docs/HANDOFF.md`), so `ellipsize`/`maxLines` and
  the fixed row count are asserted as layout properties and the visual result is
  unverified. The **tile's size** is measured (a placed 3x2 widget on the test handset's
  Kvaesitso grid: 1040x510 px at density 408, i.e. 408x200 dp — `dumpsys activity` on the
  launcher, reading the `AppWidgetHostView` bounds of an already-placed 3x2 widget); the
  card's height *inside* that tile is arithmetic over Roboto's font metrics
  (about 130 dp with three names, the `+N more` line and the footer), not a measurement,
  and a launcher that hands a 2-cell widget less than that would clip the footer.
- **Text measurement on a widget of any width is unverified**, for the same reason.
- **Whether a launcher delivers a tap to a child view inside a widget** — which is what
  every named row depends on — is not measured here. It is how `setOnClickPendingIntent`
  is specified to work and how the card's own single target already worked, and the request
  codes are set per row precisely so the intents cannot collapse (see `WidgetRenderer`),
  but no device has been tapped.
- **Whether a subagent session can be opened by id from a deep link** is reasoned rather
  than observed. Subagent rows are sessions with their own follow stream and are reached
  through their parent's caret in the drawer, and `MainActivity.consume` →
  `DshViewModel.openSessionExplicit` does not distinguish them, so the tap should land; it
  has not been tried.
- **The 15-minute heartbeat's battery cost is reasoned, not measured.**
- **`WidgetPublisher`'s dispatch is `Dispatchers.Unconfined` in tests and `Dispatchers.IO`
  in the app**; the tests therefore exercise the publish decision, not the thread hop.

## How to see it on the disposable handset

```
./build.sh :app:assembleDebug
tools/install.sh 91e25acb
adb -s 91e25acb shell dumpsys appwidget | grep -A4 uk.xa0.dsh
```

then add the widget from the launcher's picker (Kvaesitso, `de.mm20.launcher2.release`).
Placing a widget is a user action; there is no supported `adb` path to place one, and
`adb shell appwidget` only grants or revokes binding rights. What *can* be checked
without touching the home screen is that the provider is registered and that the snapshot
is being written:

```
adb -s 91e25acb shell run-as uk.xa0.dsh.debug cat files/widget/snapshot.json
```

A fresh install writes nothing until a widget is placed — that is the design, not a bug.
