# 11 · Not resting: seeing it, saying it early, learning from it

**Status: approved on 2026-10-13 and built as 0.10.2.** The owner chose phases A, B and C, one sample to judge, a note that cannot be swiped away, a tag on the episodes, and a public pre-release. Where the build differs from this plan: the tag and the notes read *The processor stayed awake while the tablet slept* in place of "Not resting", because the owner found "rest" ambiguous (and the two alert switches are renamed *Refresh the sleep screen while asleep* and *Switch off a display that stays on*; `AGENTS.md` now has a rule for names); a notification names no app, as the morning report already does, so the app case points to Battery â€º Causes; and the sampling runs whether or not the refresh switch is on. The text below is the plan as it was approved. It builds on `docs/10-battery.md` (4.1 findings, 4.8 the guard and keep-alive, 10 risks) and on the evidence rows of 2026-10-13 in `knowledge/experiments.md`.

## 1. In plain words

After Android restarts itself, the tablet stops resting while it sleeps. The processor stays awake, so standby costs 10 to 46 mA instead of about 7. About 90 minutes after the sleep screen last refreshed, the display is also forced on and draws over 300 mA until the battery is empty. Only a restart of the tablet has cleared it, both times it was seen.

The hub already has a finding for this ("The processor didn't sleep"). Researching this plan showed it is wired badly:

- **It gives false alarms.** Today currently lists seven "Display on while asleep" Problems that were not stuck displays (the display was in doze, the tablet drew about 35 mA). The restless state fools the rule written to avoid exactly that kind of false alarm.
- **It says nothing until morning**, and only after a sleep of 4 hours or more. A restless daytime sleep triggers no note at all.
- **It pollutes "normal".** The restless hours count as ordinary sleep when the hub works out what is normal, when it estimates time left, and when it judges a before-and-after test.
- **It collects nothing about why.** The one thing worth knowing is what keeps the processor awake, and no snapshot is taken while the state is happening.

The plan has three parts: **A** fix the picture, **B** tell you within about 30 minutes of a restless sleep starting, **C** capture the evidence when it happens. A is small and safe. B and C add a little code to an alarm that already exists. None of it needs root, new permissions or new background wakeups.

## 2. Corrections to what I said earlier

- I said a detector for "asleep but not suspending" was not built. It exists (`Findings.kt`: `NotSleeping` and `StandbyHigh`, specified in `docs/10-battery.md` 4.1). It is defeated by the display-on rule, and it is not connected to any notification.
- I said the restless state costs "3 to 5 times" the normal standby draw, 24 to 36 mA. After the 10-11 restart the daytime sleeps did draw 24 to 39 mA, but the first night's drew 12 to 16 mA. Across both restarts it ranged from 10 to 46 mA, about 1.5 to 7 times the clean mean of 6.8 mA, so no single multiple is honest.
- I said the finding rested on one restart. It rests on two: the 10-08 restart shows the same signature (below).

## 3. What the research found

### 3.1 The evidence

Segments of 20 minutes or more, tablet unplugged and the screen off, from `battery-2026-09.csv` and `battery-2026-10.csv`, replayed with the same rules as `Timelines.kt`.

| State | Sleeps | Processor awake | Draw | Display stuck |
|---|---|---|---|---|
| Clean (no unresolved restart) | 358 segments, 62 episodes of 30 min or more | 0.2 to 28.6 % of the time, median 2.1 % | mean 6.8 mA over the 55 segments of 10-09 to 10-11 (35.8 h) | never |
| After the 10-08 16:32 restart | 17:07 to 18:46 (99 min), 22:16 to 23:54 (99 min) | 100 % | 37 and 46 mA (one 52-minute stretch at 10 mA) | 18:46 to 21:01, 23:54 to 06:12 |
| After the 10-11 20:42 restart | 00:29 to 02:01 (92 min), 14:47 to 22:29 (7.5 h with brief wakes), 00:11 to 01:43 (92 min) | 100 % | 12, 25 to 34 and 32 mA | 02:01 to 06:58, 01:43 to 06:31 |
| After the reboot of 10-13 07:09 | the first 40-minute segment | 99.7 % asleep | back to normal | none |

The two states do not overlap: the most the processor was ever awake in a clean sleep was 28.6 %, and in every sleep of 20 minutes or more after a restart that I could measure it was 100 %. A threshold of 90 % awake (the existing `NOT_SLEEPING_MAX_SLEPT` of 0.10) separates them with a wide margin. The restless state also precedes the stuck display: on both restarts the first long sleep was restless before the display went on.

What is not shown: that the restart *causes* it (two restarts and one reboot), what keeps the processor awake, and why the draw varies from 10 to 46 mA.

### 3.2 The display-on rule is fooled by the restless state

`Timelines.segmentOf` (line 62) counts a bare sample whose `display` column reads ON as a stuck display if the processor also could not sleep (`sleptShare <= 0.10`). 0.10.0 added that second condition to stop the live screen's 1.3 s refresh from being taken for a stuck display, on the assumption that the processor normally sleeps. In the restless state `sleptShare` is always about 0, so any 30-minute tick that lands in a refresh (the ticks and the live updates both fall on the :25 and :55) turns the whole segment into "Display on while asleep".

Replay over the whole log:

| Rule | Display-on episodes of 10 minutes or more | Real (over 120 mA) | False alarms |
|---|---|---|---|
| Current | 11 | 4 | 7 (10-12, 15:25 to 22:29, 28 to 39 mA, 30 to 58 minutes each) |
| Proposed (section 4, A1) | 4 | 4 | 0 |

The seven are why Today shows "8 more findings in the log".

### 3.3 What reads the "quiet asleep" rate

Everything below filters on `EpisodeKind.Asleep` only, so restless hours are in all of it.

| Where | What it does with the rate |
|---|---|
| `Baselines.of` | The weighted median `quietAsleepMa` and the day-to-day spread |
| `Findings.evaluate` | `standbyLimit` is the larger of 3 times the baseline and 30 mA, so a polluted baseline moves the alarm |
| `Marks.quietRate` | Before-and-after of a tweak: a test run during the state would read "Higher" |
| `Estimates.timeLeft` | Time left uses `quietAsleepMa` and the unplugged daily rates |

### 3.4 What tells you, and when

- `MorningReport.onWoke` runs only for a sleep of 4 hours or more, on battery for most of it.
- `RestartAdvice` runs only for a dropbox `system_server_crash` or `SYSTEM_RESTART` that no reboot has cleared.
- Neither looks at the processor. A restless daytime sleep, or a restart the dropbox did not record, says nothing.

### 3.5 What can be captured

Read on the tablet on 2026-10-13, read-only, as the shell (the hub reads `dumpsys` with the DUMP permission, as `SystemState` already does).

| Source | Result |
|---|---|
| `/sys/class/wakeup/*`, `/sys/kernel/wakeup_reasons/*`, `/sys/power/wake_lock`, `/sys/power/wakeup_count` | **Permission denied** even for the shell. A kernel-level answer needs root (T3), which is out of scope |
| `dumpsys suspend_control`, `suspend_control_internal` | Listed by `service list` but "Can't find service" for `dumpsys` |
| `dumpsys power` | **Available.** Wake locks, `mWakefulness`, `mHoldingDisplaySuspendBlocker`, `mHoldingWakeLockSuspendBlocker`, `mDozeScreenStateOverrideFromDreamManager`, and a `Suspend Blockers:` block with a count for `WakeLocks`, `Display`, `Broadcasts`, `Booting` and `WirelessChargerDetector` |
| `dumpsys display` | **Available.** `mState` and `mCommittedState`, and the display power request with `policy=` and `dozeScreenState=` (UNKNOWN while awake; the first healthy sleep read `mState=DOZE` with `dozeScreenState=UNKNOWN`, see the lead below) |
| `dumpsys deviceidle`, dropbox crash text | Available (the crash text is already read for the timestamp; its stack names system code) |

The onset snapshot added in 0.10.1 is taken two minutes into a stuck display, and lacks the `Suspend Blockers:` block. The restless state begins hours earlier, so the snapshot is taken at the wrong time.

A lead, not a finding *[verify]*: in Android the display holds a suspend blocker while it is ON or DOZE and lets go for DOZE_SUSPEND or OFF. If, after the restart, Boox's dream leaves the display in DOZE, the processor cannot suspend, which would fit the symptom. The capture in C1 is designed to confirm or refute that. **Weakened on 2026-10-13:** the first healthy reference (`processor_ref`, from a nap on the 0.10.2 build) already reads DOZE, with `mHoldingDisplaySuspendBlocker=false` and the `Display` suspend blocker at 0, while the processor was awake 1.6 % of the time. DOZE alone is therefore not the difference; the restless snapshot is compared with it on the `Display` blocker, `mWakeLockSummary`, `mWakefulness` and a `WakeLocks` count above 1 (it includes the hub

## 4. The plan

### Phase A: correct the picture (log only)

| Step | Change | Where |
|---|---|---|
| A1 | A bare ON sample counts as display-on only with the drain of a stuck display (`rate >= STUCK_MA`, 120 mA), or as a segment shorter than `RUN_START_HOURS` that continues a display-on run (the rule the inferred path already has). The hub's own `display_on` and `display_stuck` rows still count by themselves | `Timelines.segmentOf` |
| A2 | `Episode.restless`: an Asleep episode of at least 15 minutes with `sleptShare <= 0.10`, one shared constant used by A3 and by `NotSleeping` | `Model.kt` |
| A3 | The quiet rate, the day-to-day spread and `Marks.quietRate` use Asleep episodes that are not restless. The time-left estimate follows | `Baselines.of`, `Marks.quietRate` |
| A4 | The "processor didn't sleep" text gains the advice and the honest count: a restart has cleared it each time it has been seen (twice). Both findings stay Watch | `strings_battery_text.xml` (`bt_s_not_sleeping`), `Findings.kt` unchanged |
| A5 | A restless episode in the list carries a "Not resting" tag, in words (no colour), beside the existing Problem tag | `EpisodeRow.kt`, `strings` |
| A6 | Tests: the real numbers as rows (the 10-12 15:25 to 15:55 stretch: 24 mA, 30 minutes, 0 % slept, display ON at the tick) must not be display-on; the four real stuck episodes must still be found, unbroken; baselines and marks ignore restless sleeps | `TimelinesTest`, `BaselinesTest`, `MarksTest`, `FindingsTest` |
| A7 | Docs: 4.1 rows for "Display on while asleep" and "The processor didn't sleep", `docs/10-battery.md` 7.5, changelog, README | docs |

Classification is recomputed from the log on every read, so after the update Today stops listing the seven false Problems at once, with no migration.

### Phase B: say it early

| Step | Change | Where |
|---|---|---|
| B1 | **A sample at the keep-alive alarm.** It already wakes the tablet every 30 minutes (and every wake in the restless state costs nothing, the processor is awake anyway). Store `SystemClock.elapsedRealtime()` and `uptimeMillis()` at each alarm and at screen-off. At an alarm, if the interval was 25 minutes or more, the tablet was not charging, and the processor was awake for 90 % or more of it, the sleep is restless. One sample is enough: the clean maximum is 28.6 % | `SleepKeepAlive.onAlarm`, a pure `RestlessPolicy` |
| B2 | **Two causes, two pieces of advice.** On a restless sample, read `dumpsys power` once. If a partial wake lock held by a non-system app other than the hub is found, name the app and do not advise a restart. Otherwise it is the system: advise a restart | `SystemState.power`, `RestlessPolicy` |
| B3 | The result is one `restless` row in the log (`awake=100% min=30 by=system`, or `by=<app>`), at most one per sleep, and one note per boot. The note reuses the restart note's slot (id 63, ongoing, own channel), so a restart note and a restless note never stack. It clears itself on reboot | `BatteryLog`, `RestartAdvice` (a second trigger), `BatteryReport.keepsNote` |
| B4 | Draft text. Title: *The tablet isn't resting while it sleeps*. Body: *Its processor stayed awake through a 30-minute sleep. That uses several times as much battery, and can leave the display on all night. A restart has cleared it each time it has been seen. Hold the power button, then Restart.* For the app case: *An app is keeping the tablet awake: <app>.* | `strings_battery_alerts.xml` |
| B5 | Tests: the policy as pure functions on the real numbers (a clean 30-minute sleep at 2 %, a restless one at 100 %, a charging one, a sleep with a non-system wake lock), the dedupe per boot, and the second trigger in `RestartAdviceTest` | tests |

### Phase C: learn from it

| Step | Change | Where |
|---|---|---|
| C1 | On the first restless sample of a boot, write a deep snapshot holding: the `Suspend Blockers:` block, `mHoldingDisplaySuspendBlocker`, `mHoldingWakeLockSuspendBlocker`, `mDozeScreenStateOverrideFromDreamManager`, `mWakefulness`, `mState`, `mCommittedState` and `dozeScreenState` from `dumpsys display`, the deviceidle state, and the wake locks by owner. It counts as app data in a shared report, like `onset` | `BatteryLog.writeDeep`, `SystemState.power` |
| C2 | **A healthy reference.** The first keep-alive alarm of the first sleep of 30 minutes or more after each boot, when that sleep is healthy, writes the same snapshot with reason `rest_ref`. Without it, C1 has nothing to compare against | same |
| C3 | **Did anything we do cause the restart?** When `RestartAdvice` sees the crash, keep its header (exception class and the first frames: system code) and the hub's own actions in the five minutes before it (tweaks applied, a frozen app opened or refrozen, Shizuku commands, an accessibility change). Two restarts on a tablet that runs the hub is not a control group, and this is the only way to test it | `SignalReader`, `Journal`, deep log |
| C4 | The shared report carries C1 to C3, so one zip is a complete report for Boox | `BatteryReport` |

### Phase D: not planned

| Idea | Why not |
|---|---|
| Restart the tablet automatically | A surprise reboot loses open work, and Shizuku (needed to reboot from the app) does not survive it, which switches off refreezing and the guard's key step until the start script is run again |
| A one-tap Restart key | Needs Shizuku and saves only a few taps: the power-button menu does the same |
| Read the kernel's wakeup sources | Permission denied without root |
| Clear the state without a reboot | The framework's state cannot be rebuilt from an app |

## 5. Sizing and order

| Phase | Files touched | Risk |
|---|---|---|
| A | `Timelines`, `Model`, `Baselines`, `Marks`, `EpisodeRow`, two string files, four test files, docs | Low: pure functions, tested on the real log, no permissions |
| B | `SleepKeepAlive`, `RestartAdvice`, `SystemState`, `BatteryLog`, `BatteryReport`, strings, tests | Medium: one new trigger on an alarm that already runs |
| C | `BatteryLog`, `SystemState`, `SignalReader`, tests | Medium: logging only |

Recommendation: A and B together as 0.10.2, with C1 and C2 if they fit. A fixes what Today shows right now and is safe alone. B is the part that would have warned you on 10-12 afternoon, about 30 minutes into the 14:47 sleep. C3 and C4 can follow.

## 6. Verification

- **Done already:** the replay in section 3 over the whole log. It becomes the A6 fixtures, with the real numbers and no app names.
- **Unit tests** for every pure function above, on the JVM.
- **On the tablet:** after the update Today stops listing the seven false Problems. The healthy reference (C2) appears in the deep log after the next sleep of 30 minutes or more.
- **Not testable now:** B and C1 only fire in the restless state, which needs the next restart of Android by itself. I will not try to cause one. The release notes say so under *Not yet seen*.

## 7. Risks and costs

| Risk | Mitigation |
|---|---|
| A false restless note when an app legitimately keeps the processor awake (a download, music) | B2 names the app and does not advise a restart; the clean data never exceeded 28.6 % awake |
| A stuck display slips under A1's 120 mA rule | Every real episode drew 289 to 396 mA; the hub's own confirmed rows still count regardless of drain |
| Battery cost | None added: the sample rides the keep-alive alarm; one `dumpsys power` per restless sleep and one per boot |
| Privacy | C1 to C3 may hold app names, so they join the app-data class a shared report drops by default |
| Copy that overclaims | "Cleared it each time it has been seen" is two cases, and the text says so as a count, not a cause |

## 8. What I need from you

1. **Scope:** A only, A and B (my recommendation), or A, B and C?
2. **Threshold:** one sample of 25 minutes or more at 90 % awake (my recommendation, 28.6 % was the clean maximum), or two in a row (a note after about an hour, fewer false alarms)?
3. **The note:** ongoing like the restart note, so a swipe doesn't clear it (my recommendation), or an ordinary dismissable one?
4. **The "Not resting" tag** on episodes in the list: yes or no?
5. **Release:** 0.10.2 as a public pre-release, as before?

## 9. Open questions

- What keeps the processor awake, and why does the draw vary from 10 to 46 mA?
- Does the restart cause it, or does something cause both? C3 is the test.
- Does it ever happen with no crash in the dropbox? The detector in B is the first thing that would show it.
