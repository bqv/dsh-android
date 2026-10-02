# Latent scroll diagnostics

Scrolling in this app has been rebuilt more than once from a description of the
symptom rather than from the gesture — "it jumps", "it fights my finger" — and
each rebuild traded one story for another. `uk.xa0.dsh.diag.ScrollDiag` records
what actually happens on the device so the next rebuild can start from evidence.

It is **latent**: it draws nothing, changes no layout, consumes no pointer event,
never scrolls anything, and appears nowhere in the UI. With the diagnostics
attached the app must look and behave exactly as it did without them.

## Reading it back

The debug build is debuggable, so the app's own files are readable over adb
without root:

```
adb -s <serial> logcat -d -s DshScroll:I
adb -s <serial> shell run-as uk.xa0.dsh.debug cat files/diag/scroll.jsonl
```

`scroll.1.jsonl` holds the previous segment if the log has rotated. Records are
one JSON object per line; `t` is milliseconds since process start and `k` is the
kind. The file path is under `filesDir` and is only created once
`ScrollDiag.attach` has run, which `DshApplication.onCreate` does.

Notable records also go to logcat under the `DshScroll` tag, so `logcat -d -s
DshScroll:I` is the quick read and the file is the complete one. Both are rate
limited (`RATE_PER_SECOND`), and a `drop` record reports anything the limiter
discarded.

## The record kinds

| kind | what it is evidence of |
| --- | --- |
| `meta` | Build identity, written once per process. |
| `surf` | A scroll surface appeared, with its item count and viewport. |
| `down` | A finger landed. `fx`/`fy` are its position as a fraction of the surface, which is what identifies a strip at an edge eating the gesture. |
| `up` | The gesture ended: `dx`/`dy` travelled, `ms` taken, `child` = a child scrollable consumed it, `moved` = the surface's own offset changed. `child=1 moved=0` is a gesture the surface never saw. |
| `prog` | A mark dropped immediately before the app moves a list itself. `g` is whether a finger was down at that instant. |
| `fight` | The app moved the list **while a finger was down or a fling was running**. Carries the `tag` of the call site. |
| `move` | A deliberate move by the app, large enough to be worth recording. Carries `tag`. |
| `shift` | The position changed with no finger down, no fling, and no mark — nothing in the app asked for it. |
| `jank` | More than 150 ms between position changes while the list was moving. |

`shift` and `move` carry `di` (index delta), `doff` (offset delta), `ditem` (item
count delta), `kept` (percent of the previously visible keys still visible) and
`dt` (ms since the previous change). A `ditem` that accounts for `di` is an
ordinary insertion; `di` with `ditem=0` is the list moving with no new content to
explain it.

## Surfaces

Instrumented with `DiagLazyList`/`DiagScrollColumn` (position) and `diagDrag`
(gestures): `chat`, `drawer`, `settings`, `files:tree`, `files:preview`,
`files:image`, `files:svg`, `sheet:models`, `sheet:presets`, `trajectory`,
`browser`.

## Tests

The judgements the recorder makes — is this step a jump, does this mark still
explain this sample, how much of the visible set survived — are pure functions in
`ScrollMath.kt` and are covered by `ScrollMathTest`. Everything else is I/O and
Compose wiring, which is exercised on a device.

## Removing it

The instrumentation is additive and one call site per surface. Deleting the
`uk.xa0.dsh.diag` package, the `diagDrag`/`DiagLazyList`/`DiagScrollColumn` call
sites and the `attach` line in `DshApplication` leaves no trace. The `prog` marks
inside the chat's scroll effects should go with it.
