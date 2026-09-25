# Goal chip (GoalBar) — contract and emulator evidence

Probe round 1 of the goal "Probe goal for the goal-chip verification", 2026-09-24.
Scope: the docked goal strip above the composer (`ui/components/ChatRows.kt: GoalBar`,
`DshViewModel.kt: GoalState` / `applySessionProjections`).

## Host contract (read from `~/bin/deepseek-harness`, not guessed)

`packages/goal/goal/lib/index.js:342-363` — `goalProjectionSchema`:

```ts
z.union([z.object({
  goal: z.object({
    id, revision, objective,
    phase: "active" | "paused" | "blocked" | "complete",
    blockedReason?: { code, message },
    maxGoalRounds,
  }),
  roundsStarted, createdAt, updatedAt,
}), z.null()])
```

Two consequences the client must respect:

1. The wire value of the `goal` projection key is the **wrapper**; the snapshot is
   `value.goal`. `DshViewModel` folds frames with
   `merged.put(value.str("key"), value.opt("value"))` (projection handler), so
   `values.obj("goal")` is the wrapper and `.obj("goal")` the snapshot.
2. **`revision` is not a round counter.** `packages/goal/goal/lib/index.js:174`
   requires every mutation to advance it (`goal <op> must advance the current goal
   by one revision`), so a goal that is edited or paused/resumed shows a revision
   that has nothing to do with the round budget. The round budget is
   `roundsStarted`; `:277-278` sets `state.roundsStarted = source.round` when a
   goal round's `user/message` folds, and `:378` caps it at `maxGoalRounds`.

The web GoalBar (`packages/client/ui-goal/lib/client.js`) renders glyph + phase
label + truncated objective + icon actions and has **no counter at all**; the
Android chip's counter is a mobile addition, so its meaning has to be right on its
own — matching the web is not an option. The chip's own labels come from
`phase`: `Paused Goal` / `Blocked Goal` / `Ongoing Goal`, with any other phase
reading `Goal` (`ChatRows.kt:1365-1370`), and a `complete` goal renders nothing
at all (`GoalState.visible`, `DshViewModel.kt:202`).

## Defect found and fixed

`GoalBar` read `"${goal.revision}/${goal.maxRounds}"`, i.e. the CAS revision as the
round counter, and `GoalState` never parsed `roundsStarted` (it was not even in the
data class). With this session's probe goal (`revision: 1`, `roundsStarted: 1` after
round 1 was admitted) the chip rendered `1/4` for the wrong reason; after any Edit /
Pause / Resume from the app's own verbs it would have jumped to `2/4`, `3/4`, …
while still being round 1.

Fix:

- `GoalState` gains `roundsStarted: Int = 0` (`DshViewModel.kt:200`); the projection
  parse reads it from the wrapper (`projection.int("roundsStarted")`,
  `DshViewModel.kt:3098`), with the schema shape noted in a comment.
- The chip renders `"${goal.roundsStarted}/$max"` (`ChatRows.kt:1415-1421`) — the
  round the prompt calls `Round: N/4`, monotone and never above the limit.
- The mutation verbs send the session id as **`agentId`**, the host's own name for
  it, beside the `{id, revision}` CAS ref (`DshViewModel.kt:3144-3148`).

An intermediate cut of the fix used `minOf(roundsStarted + 1, max)` and the device run
below caught the off-by-one: it showed `2/4` while the host had admitted round 1.

## Evidence

- Transcript admission of the probe round:
  `user/message` seq 9 → `source = {kind: "goal", goalId: goal-d8d7c10c…, revision: 1, round: 1}`.
- `goal/change` seq 3 = `create … roundsStarted: 0`; `get_goal` returns the same
  `revision: 1` snapshot with `activation: armed`.
- Emulator, freshly built APK installed to `127.0.0.1:5555` and launched into this
  session (`.probe/goalchip-verify.sh`): `.probe/goalchip-1-open.png` shows the chip
  `Ongoing Goal · Probe goal for the goal-chip verifi… · 2/4` — proof that the
  `roundsStarted` parse works (revision is 1, so the old code would have said `1/4`)
  and that the `+1` was wrong.

## Still open

- The corrected `1/4` still has no emulator capture of its own: the only
  `goalchip-*.png` is the pre-final-cut `.probe/goalchip-1-open.png` (03:00), which
  showed the wrong `2/4`. The compile failure that blocked a re-run is gone —
  `AUTH_ERROR` (`DshViewModel.kt:3597`) and `DshUnreachableException`
  (`net/DshClient.kt:36`, imported `DshViewModel.kt:58`) are both defined — so
  `adblease 127.0.0.1:5555 .probe/goalchip-verify.sh` can be rerun unchanged.
- `activation` is not modelled, so a disarmed goal (after session resume/fork, or a
  `connection/reset`) still renders `Ongoing Goal` instead of the web's
  `Inactive Goal` (`ui-goal/src/client/locales.ts:25`; `GoalBar`'s label has no
  disarmed branch, `ChatRows.kt:1365-1370`); the activation source is
  `remote.goals.get` plus the `goal/activation-changed` stream event.
- `blockedReason` is not parsed. The web does surface it: a blocked goal's bar
  carries `blockedReason.message` as its `title`
  (`ui-goal/src/client/GoalBar.tsx:134`), so a port has a string to render. The
  app shows the phase label only.
