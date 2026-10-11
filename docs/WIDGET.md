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
Approval waiting            <- headline: the most useful true fact, in priority order
3 running                   <- ...or, when the app can name them, the names themselves:
· Fix the login bug
· Widget sessions
+2 more                     <- what the list had no room for
oldest reading 11 Oct 12:03 <- stamp: when the oldest line above was confirmed
```

and a tap target on every line: each named row opens *its own* session, and the card
behind the rows opens the session that most needs the user, through the launch path a
notification already uses (`Attention.EXTRA_SESSION`).

The footer's word is load-bearing. It read `checked 0:12 ago` in the first draft, and that
was the wrong sentence: it describes when the *app* last looked, which a reader takes as
"the app is live and nothing is wrong", while the value under it is a fact about the
*oldest* reading on the card — the worst line, and the one that bounds how much any of
them may be trusted. `oldest reading` says what the value is. The copy layer could name
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
reading, taken from the same roster pull at the same instant, so the instants in the footer
bound them already. When the reading loses its basis the names go with it — the publisher
drops them and the card says `Running: unknown` — because a list of names under a reading
the app can no longer assert is exactly the stale-and-quietly-wrong card this feature
exists to prevent.

### The headline is the most useful true fact

An earlier revision spent the headline on `No agents active` whenever nothing was
waiting. That is almost always true, which is exactly the problem: a headline that is
nearly always the same words has stopped being a headline, and the reader learns to skip
the first line of a card they have deliberately put where they cannot skip it. The
sentence is not a lie; it is the *least informative* true thing the card can say.

So the headline has a priority order now, and every branch of it is a reading with a
`known` flag and an instant:

1. **`Approval waiting` / `3 need you`.** Something is parked on a human. It is the only
   urgent fact in the app and the only one that is useless late, and the host gives up on
   it (an approval lapses after five minutes, a question after fifteen).
2. **`Needs you: unknown`.** The app cannot rule an approval out. An unknown that could be
   urgent outranks a positive that is merely interesting, so this sits above everything
   below even when a running list is in hand.
3. **`Working`.** Sessions are running *and* the host has spoken to this app inside the
   activity window — the indicator the next section is about. The list is not merely being
   displayed; the app is watching the host work.
4. **`Running`.** Sessions are running per the roster, with no live traffic to confirm it
   — an agent inside a long tool call, or a reading renewed from the last whole-world
   pull. It is the roster's own word for the condition (`session/list`'s `running`), so it
   asserts exactly what was established and nothing about this second.
5. **`No agents active`.** Nothing is waiting and nothing is running. This is the
   fallback now, and the story above is why it had to become one.
6. **`Running: unknown`.** The roster cannot be read, so what is running cannot be said at
   all. The second line is dropped rather than repeating the sentence.

The card's second line is unchanged: the running sessions by name when they can be named,
otherwise the count and its subagent qualifier, otherwise the reason it cannot say. The
headline takes no space from it — the layout is the same set of `TextView`s it always was,
and no new view, colour or dependency was added.

### What "active" means, and what it does not

`active` is a reading, `(known, value, asOf)`, exactly like the others:

- **`value` is 1 while the host has delivered a frame on the mux within
  `WidgetPublisher.ACTIVE_WINDOW_MS`** (two minutes), and 0 once that window has lapsed.
- **`known` is the mux socket being `OPEN`** — the app's ability to hear at all. A socket
  that is down is `known = false`, which is a different statement from a confirmed zero
  and is never drawn as one.
- **`asOf` is renewed by the one-second loop that already reads the socket**, so the
  footer's bound covers the word the headline draws from it, like every other reading.

It is deliberately **not a second count of sessions.** The roster already answers "how
many are running" (`working`), and it is the host's own liveness answer. What the roster
cannot say is whether the app is still hearing the host at all — a `session/list` pull is
a cut, and a cut does not go on being true. `active` is that fact, and it is what lets the
card say *working* rather than *running*.

**It costs no new Remote call.** The host already tells us: `RemoteMux.Listener.onMessage`
is where every frame lands, and it stamps an `AtomicLong` before it even parses the frame.
The reading is then reduced on the one-second loop the view model already runs, and the
publisher draws it only when the value moves. To keep is one `AtomicLong` store per frame
and one comparison per second; nothing new is registered, polled or woken.

**The definition had to be true of the whole system, and that is what chose it.** The
candidates that were rejected, and why:

| Candidate | Verdict |
| --- | --- |
| A turn streaming in a session | **Rejected.** `session/follow` is opened for exactly **one** session — the one on screen, through `DshViewModel.startFollow`. A turn streaming in any other session is invisible to this app, so this fact cannot speak for the world. (The view model's own `lastEventAt` is stamped from that one stream, for the same reason.) |
| A live background job | **Rejected.** `job/list` is per-session and the running 0.2.0-rc.2 host no longer pushes an app-wide `jobs` block on `session/control`. A job figure would present one session's jobs as the world's — the same ground the jobs count was cut on. |
| A running subagent | **Rejected, but only as an *indicator*.** It *is* whole-world — it comes from the roster — but it is a subset of `working`, which is already on the card, so it would add a number rather than a fact. |
| Socket-level activity | **Chosen.** Every logical stream the app holds — the forwarded host events, `session/control`, `workspace/follow`, the followed session, jobs and terminals — rides the one mux socket. A frame arriving is therefore a fact about the whole system: the host is doing something somewhere. |

**Why frames and not pings.** This is the part that makes the reading mean anything. The
stream client dials with `pingInterval(20, SECONDS)` (`DshClient.buildClients`), and
OkHttp handles ping, pong and close as WebSocket *control* frames inside the socket rather
than through `WebSocketListener.onMessage`. So a socket kept alive by nothing but its own
pings looks quiet here. A live TCP connection is not activity; a text frame is.

**Why the value is a window and not an instant.** `value = 1` means "the host has spoken
recently", which is a state the card can draw and a word it can put at the top. Printing
the frame instant instead would move the value on every frame — a token stream delivers
many a second — and the publisher's rule is that a moved value is drawn at once, which is
precisely the per-second cost this feature just had removed. The window is what collapses
a stream of frames into one state that changes only at its edges.

**What the card shows when the app cannot tell.** When the socket is down, `active` is
`known = false`, and the copy layer withholds the stronger word: the headline falls to
`Running` (if the roster is known) or to the unknown sentences above it. An unknown
activity reading is never rendered as "nothing is active" — it is not rendered as
anything at all, because the card withholds the claim rather than asserting its negation.
The same is true of a document written before this key existed: an absent `active` decodes
as never confirmed, which is the same withholding.

**What it does not claim.** `Working` is drawn only when the roster already reports
something running *and* the activity reading is confirmed. Host traffic with nothing
running — opening a session, a queue frame, a workspace change — cannot turn the headline
into a claim of work. And when the host falls quiet for longer than the window the card
falls back to `Running`, which is still true; the activity signal can strengthen a running
list but it can never manufacture one.

**Why `Working` is not the forbidden "live" lamp.** It is a word, not a badge, and it is
drawn from a reading whose instant the footer prints — the activity reading is part of
`WidgetSnapshot.confirmedAt`, so `oldest reading 11 Oct 12:03` bounds it exactly as it
bounds a name. A lamp is a claim about *now* with nothing under it to age; `Working` is the
stronger of two true words for a fact the two readings already carry, and it expires in the
reader's own eyes the moment its instant looks old. When the app is alive the card withdraws
it by redrawing on the `1 → 0` edge; when the app is dead it is bounded by the same stamp
as everything else on the card. There is no new failure mode here, and there is no new
wake-up: the draw that withdraws the word is one the publisher was already going to make
for the value change.

### What earns a place, and what was cut

Ranked by "would I want to know this without opening the app":

| Fact | On the card? | Why |
| --- | --- | --- |
| Approvals, questions and plan reviews | **Yes**, as a count and the highest-precedence kind | The agent is *blocked* until it is answered, and the host gives up: an approval lapses after 5 minutes, a question after 15. It is the one fact in this app that is both urgent and useless late. `AttentionCenter` already owns it and already drives notifications from it. |
| Sessions and subagents with an agent working | **Yes**, by name | The difference between "still going" and "finished while I was away" is the question a person actually has while their phone is in their hand, and *which one* is the very next question. The roster makes it answerable: `session/list` is a whole-world pull this app already makes, and every row already carries `running`. |
| Whether the app is still hearing the host | **Yes**, as the headline's word | It is the difference between a list that is being watched and one that was true when it was pulled. Every stream shares one socket, so a frame on it is a fact about the whole system, and the app is already reading that socket once a second — the reading costs one clock comparison per tick and is drawn only when it moves. |
| Which session and model a turn would run | **No** | That is composer state, not a glance: it is a fact about a screen the reader is not on, it is per-session, and the model chip is already the most lie-prone thing in the app (see the `lastUsed`/`next` trap in `docs/HANDOFF.md`). |
| Background jobs | **No** | They are only known for the session the app has **open** — `job/list` is a per-session stream — so a widget showing "2 jobs" would be silently reporting one session's jobs as if they were the world's. A count that is right for one session and wrong for every other is worse than no count. This is exactly why the roster could be listed and the jobs could not: the roster is app-wide, the jobs are per-session. |
| Account balance | **No** | `BalanceState.Idle` until the usage panel is opened, so on a fresh process it is *not known*; it costs a network call to DeepSeek with the user's own key; and a money figure is the worst possible thing to leave on a homescreen going quietly out of date. |

The bar is "would I want to know this without opening the app", and a dashboard would
fail it in the other direction: a widget that shows six things is read as noise and
stops being read at all.

## The honesty problem

The temptation is a card that says

```
No agents active
```

which is a claim about the world. It is only true while the app can still hear the
host. A widget that keeps saying it after the process is gone is the same lie as a
stale model chip, and it is worse, because nobody is looking at the widget with the app
open — they are looking at it instead of opening the app.

### `known` is a field, not an absence

Every reading is `(known, value, asOf)` (`WidgetReading`). `known = false, value = 0` is
**nobody has looked**; `known = true, value = 0` is **the app looked and nothing is
waiting**. They are drawn as `Needs you: unknown` and `No agents active`, and the
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

`known` for "is the host talking to us" is `muxState == OPEN` alone — the activity reading
is built from frames on the socket, so the ability to observe it is the socket and nothing
else. The two unknowns differ and are kept apart: a dropped socket is "I cannot tell
whether anything is active", while `known = true, value = 0` is "I am listening and the
host has been quiet for two minutes". Neither is drawn as "nothing is active"; the card
withholds the word rather than asserting its negation.

### The stamp is an instant, not a ticking view

`RemoteViews` has no rich text and no live update, and the three ways to keep a claim
from rotting are not equal:

1. **Print a wall-clock instant** (`oldest reading 11 Oct 12:03`). Complete when it is
   written and true forever after, because it claims nothing that time can take away. The
   reader does the subtraction, against the clock already on the same screen. Costs
   nothing per second and needs no wake-up to correct, because there is nothing to
   correct.
2. **Schedule a wake-up to withdraw the claim** (an `AlarmManager` alarm at the moment
   the reading goes stale). Correct while it works — and it does not always work.
   A force-stopped app has its alarms cancelled *and* its receiver disabled, so the
   card would keep its last rendering forever, which is exactly the failure this was
   meant to prevent. It also needs `ACTION_BOOT_COMPLETED` after a reboot.
3. **A `Chronometer` whose base is the reading's instant.** It ticks up in the
   *launcher's* process, so the age stays true with nothing of ours alive. It cannot be
   left behind, because there is nothing to leave: the age is derived from a timestamp at
   the moment it is drawn, not written down.

This uses (1). (3) was how this widget was first written, and it was then removed rather
than tuned; the reason is worth recording in full, because it was a good design and it is
tempting to put back:

- **The tick is once a second and cannot be slowed.** `Chronometer.updateRunning` gates
  on `mVisible && mStarted && isShown()` and, when all three hold, calls
  `postDelayed(mTickRunnable, 1000)`. A hardcoded second: not a parameter, and no
  `RemoteViews` call reaches it. "Staggering" a `Chronometer` is not a thing that exists.
- **A widget cannot bound it either.** The launcher owns the view, and the only lever
  `RemoteViews.setChronometer` has is the one-way `started` flag baked into the blob when
  it is applied. `started = false` is not "tick slowly", it is "print the age at the
  instant this blob was applied, then stop": the first apply of a healthy reading prints
  `0:00 ago`, and with no process of ours alive to re-apply it, that is what the card says
  until the launcher happens to re-bind it. That is precisely the stale-and-quietly-wrong
  card this design exists to prevent, so it is not an option — the two positions are
  *ticking* and *frozen*, and only one of them is honest.
- **What it does not cost is a wake-up.** The framework bounds it already: the ticker
  only runs while the widget is actually shown. `Chronometer.onWindowVisibilityChanged`
  clears `mVisible` whenever the app's window is not visible — `ViewRootImpl.performTraversals`
  dispatches that from `getHostVisibility()`, which is `GONE` while `mAppVisible` is
  false — so with the screen off, or the launcher behind another app, there is no ticker
  at all. The standing cost is one extra `TextView` relayout per second in the launcher
  while somebody is looking at a home screen that has this card on it.
- **And that relayout buys nothing.** The difference between an age of 3:41:07 and
  3:41:08 is not a difference a reader can act on; the difference between *today* and
  *three days ago* is the entire question the footer answers, and an instant answers it
  exactly. A per-second redraw for a number whose rate of change is uninformative is work
  done for its own sake.

(3) also carried a trap that cost a real homescreen, and it is worth keeping on the record
because the shape of it is not obvious: a `Chronometer` counts from a
`SystemClock.elapsedRealtime()` base, not a wall-clock one, so a card handed
`System.currentTimeMillis()` made the launcher compute `uptime - wallClock` and drew
`oldest reading -406865:16:56` — a negative age of roughly the forty-six years between
1970 and the machine's uptime. The conversion that fixed it (`chronometerBase`, clamped at
both ends) was correct and exactly testable, and it is the one thing worth regretting about
deleting the view. With no view there is no second clock, so there is no longer a timebase
to get wrong; the only clocks left on this card are the reader's own.

An instant also removes the failure mode a ticking view was uniquely built to survive,
by removing the thing that could be left behind: nothing has to be kept true, so nothing
can be left untrue. What the card never does is claim *now*: there is no "live" lamp and
no "connected" badge, because both would need (2) to be withdrawn and (2) has a hole in
it. An instant claims only the past, and the past does not change.

Two details of the format are load-bearing and both are in `WidgetCopy.stampTime`:

- **The date is always written.** A clock time is unambiguous only inside its own day, and
  the card is drawn once and then read at a moment it cannot know and cannot be woken for.
  `12:03` drawn yesterday reads as seven minutes old to somebody glancing at the card just
  after noon, and nothing would ever correct it. That was the one thing a relative age was
  genuinely better at; the date is what an instant pays for it.
- **It is the reader's zone and the reader's month name**, from `Locale.getDefault()` and
  `TimeZone.getDefault()`, because the stamp is a wall-clock reading for the person holding
  the phone — but the *order* (`d MMM HH:mm`) is the app's, matching the `d MMM` the
  drawer's own `relativeTime` falls back to, so the two surfaces write a date the same way.

While the app *is* running it re-stamps and re-draws, so the instant in the footer stays
inside the heartbeat and means "the app was still listening a quarter of an hour ago at the
outside" rather than "nothing has changed since whenever". `WidgetPublisher.HEARTBEAT_MS`
is fifteen minutes because that is `AttentionCenter.QUESTION_TIMEOUT_MS`: the window in
which a waterfall can still be answered, and therefore the window in which a count on a
homescreen is still actionable. The named rows do not get a stamp of their own: they are
the detail of the `working` reading, from the same pull at the same instant, so the footer
already bounds them.

## How it updates

Nothing is scheduled. `updatePeriodMillis` is **0**.

- **When the app is running**, `AttentionCenter` publishes on every change of the pending
  set, on every socket state change, and from `DshConnectionService`'s keep-alive loop;
  the view model publishes on every roster-running change and from the one-second loop
  that already watches the mux, handing over every running session and its title, and — on
  the same tick — whether a host frame arrived inside `ACTIVE_WINDOW_MS`.
  `WidgetPublisher` collapses all of that into a file write and a widget update at most
  once per `HEARTBEAT_MS`, or immediately when a value moves — and a rename is a value
  moving, because the title is a thing the card draws, and so is an activity change,
  because it decides the headline's word. The write and the draw are off the main thread
  and coalesced through a conflated channel.

  That collapse is now real on **both** halves, and the gap between them is the largest
  cost this feature ever had. The write was gated on the heartbeat and the *draw* was not,
  so every renewal of the one-second loop became an `updateAppWidget`: a binder call into
  the system service, which then re-applied a `RemoteViews` in the launcher's process —
  up to 86,400 a day for as long as the app held its socket, screen on or off, widget on
  screen or not. `WidgetPublisher.publishDue` is that gate, and it sits in front of
  `getAppWidgetIds` too, so a phone with no widget placed is no longer asked once a second
  either. This is what "staggering" the widget actually turned out to mean: the ticking
  view could not be staggered, and did not need to be, because the cost that could be
  removed was entirely our own.

  **The activity reading does not reopen that cost.** It is recomputed on every tick —
  one `lastHostFrameAt()` read and one subtraction — but the value only moves at the two
  edges of a burst of work: `0 → 1` when the host starts talking and `1 → 0` a window
  after it stops. While a turn streams, frames arrive many times a second and the value
  stays at 1, so not one of them reaches the launcher. While nothing is happening it stays
  at 0. An idle app therefore draws exactly what it drew before this reading existed: the
  quarter-hour heartbeat, and nothing else.
- **When the app is not running**, nothing at all happens. Every number on the card
  arrived over a socket; a wake-up could only re-read the same file and draw the same
  bytes, one interval older. The instant the card already carries is what says how old it
  is, and it needs no alarm, no `JobScheduler`, no `WorkManager` and no
  `RECEIVE_BOOT_COMPLETED`.
- **If no widget is placed**, the publisher writes nothing and updates nothing. The
  reading is still held in memory, so adding a widget draws what this run already knows;
  a user who never placed one pays exactly zero.

Measured comparison, for the record: `updatePeriodMillis` would cost up to 48 process
wake-ups a day *whether or not the app has anything to say*, on a device where the whole
point of the battery-exemption dance is that the process should not be roused for
nothing. This design costs one widget update per quarter hour, and only while a widget is
placed and the app already has a socket open. Nothing here ticks, per second or per
minute, in either process.

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

  `active` is optional on the same terms and for the same reason. An absent key decodes as
  `WidgetReading.NEVER` — "nobody said" — which is a state the copy layer already knows how
  to draw: it withholds the word that would otherwise have claimed activity, and the
  headline falls back to the roster's own `Running`. A *present* key this build cannot
  parse is a different thing, a document contradicting itself, and is refused rather than
  guessed at.
- **Seeded from the last run at construction.** A restart does not make yesterday's
  reading false, it makes it old, which is what its instant already says. Starting from
  nothing would instead claim that nothing was ever seen, which is a *worse* lie than an
  old number. The names and the activity reading come back with it, and are drawn under the
  new stamp like every other reading.

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
- **Glance could not express this design anyway** — and it is no longer the design. Its
  primitives re-render only when they are told to, which is precisely the wake-up that a
  force-stopped app never receives; the only route to a `Chronometer` is
  `AndroidRemoteViews` (`AndroidRemoteViewsKt` is in that AAR), which takes a hand-built
  `RemoteViews` — the fallback, wearing a dependency tree. That mattered when the footer
  was a live age. It does not any more: the footer is a string, which is the one thing
  every widget toolkit can already draw. The conclusion is unchanged and now for a
  smaller reason: this card is a handful of `TextView`s, and a dependency tree to write
  five of them as composables buys nothing.

So: a plain `AppWidgetProvider`, a plain XML layout, and no new dependency.

## What is not verified

Written honestly, because an inferred number presented as measured is the failure this
whole document is about:

- **The layout has been seen on a device, but only in its earlier shape.** The layout, the
  provider registration and the `exported="true"` choice were reasoned from the platform's
  API and from what the shipped widgets on the test handset declare (checked with
  `aapt2 dump xmltree` on a pulled APK) rather than observed. Since then the card has
  drawn itself on a real homescreen at least once — the `Chronometer` conversion bug
  recorded above is only visible if it did — but the footer *this* build draws has not been
  looked at. The `TextView` that replaced the `Chronometer` is the simplest view in the
  layout, which is not a measurement either.
- **The ticker's behaviour is read from the platform's own code, not watched on a
  device.** Every claim in "The stamp is an instant, not a ticking view" about what a
  `Chronometer` does — the `postDelayed(mTickRunnable, 1000)`, the
  `mVisible && mStarted && isShown()` gate, `onWindowVisibilityChanged` clearing
  `mVisible`, and `ViewRootImpl.performTraversals` dispatching that from
  `getHostVisibility()` — comes from disassembling `android.widget.Chronometer`,
  `android.view.View` and `android.view.ViewRootImpl` out of the Robolectric
  `android-all` jar, which is AOSP framework code with real bodies (the SDK's own
  `android.jar` is stubs). That the launcher is actually driven through those callbacks on
  a given OEM build has not been watched, and neither has a `Chronometer` ticking on a
  homescreen.
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
- **Text measurement on a widget of any width is unverified**, for the same reason. That
  includes the footer's new width: `oldest reading 11 Oct 12:03` is about four characters
  longer than `oldest reading 0:12 ago`, and the arithmetic over the 408 dp tile says it
  fits with room to spare, which is still arithmetic.
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
- **The battery argument is reasoned, not measured, and there was nothing to measure it
  with.** No widget of this app is placed on either attached handset — read from
  `dumpsys appwidget` on the disposable one and on the daily phone — and placing one is a
  user action with no supported `adb` path, so neither the per-second `updateAppWidget`
  this change removes nor a ticking `Chronometer` has ever been observed from here. What is
  *not* guesswork is where the cost was: the draw gate is absent in the previous revision's
  source and the one-second caller is in it (`DshViewModel.publishWidgetWorking`), and the
  framework's tick interval is in its bytecode. Everything else — how much of a launcher's
  CPU one extra `TextView` relayout costs per second, and what that is in mAh — is
  arithmetic and judgement, not a reading from `batterystats`. The claim this change makes
  is therefore the narrow one: a per-second binder call into the system service and a
  per-second `RemoteViews` re-apply are gone, and nothing on the card re-renders on a timer
  any more.
- **The activity reading's cadence is reasoned, not watched.** That the mux is quiet when
  nothing is happening is an argument from the source: the `$events` stream forwards
  transitions (`AttentionCenter` handles `emit` frames only), `session/control`
  pushes on change, `workspace/follow` pushes on change, and the only periodic traffic on
  the socket is the 20-second WebSocket ping, which OkHttp answers as a control frame that
  never reaches `onMessage`. No frame count has been taken from a running app, because the
  mux's own logging prints the head of each stream only (`seen <= 3`) and instrumenting the
  app to measure it would be a change made to observe a change. What *is* pinned in source
  is where the reading comes from (`RemoteMux.Listener.onMessage` stamps `lastFrameAt`) and
  the two edges the publisher draws on.
- **The `Working` / `Running` words have not been read on a device.** They are two strings
  in a `TextView` that already exists, so there is no new layout risk; what is unverified is
  whether a reader finds the distinction as legible as this document claims, which is a
  judgement and not something a JVM harness can answer.
- **`WidgetPublisher`'s dispatch is `Dispatchers.Unconfined` in tests and `Dispatchers.IO`
  in the app**; the tests therefore exercise the decisions — `publishDue`'s among them —
  and neither the thread hop nor the binder call the decisions lead to, which a JVM cannot
  make.

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
