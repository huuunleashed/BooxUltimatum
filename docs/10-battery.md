# 10 · Battery: a revised section

**Status: plan, written 2026-10-09. Nothing described here is built.** It is based on the hub at 0.8.0, on the owner's Note Air6 C (firmware 4.3, Android 16) and on the night of 2026-10-08, when the display stayed on while the tablet slept and emptied the battery. The evidence is in `knowledge/experiments.md` (rows dated 2026-10-09). This document covers the Battery section and the battery slice of Overview. System › Tweaks is revised later; §11 only fixes the contract between the two.

## 1. Why revise

On 2026-10-08 the display went to state ON with no wake reason and never returned to doze, twice: 18:37 to 21:02 (about 700 mAh) and 23:46 until the battery died at about 06:17 (2 183 mAh at a steady 333 mA). The tablet slept normally for 50.8 h of the same three days at **9 mA**, so the 8.6 stuck hours cost 38 % of everything drawn on battery. The hub had every number needed to see this: its battery log holds the whole night, and its 05:23 snapshot shows no `dream:doze` wake lock. It said nothing, and the owner found out when the tablet was dead.

What the hub had, and what it did with it:

| The hub has | What it shows | Consequence |
|---|---|---|
| A battery log: a row for every screen, plug and 1 % step, with charge counter, mA, uptime and elapsed time | A level chart and two averages over seven days | The stuck hours count as standby. Today's "drain with the screen off" would read about **0.92 %/h** over seven days; without them it is about **0.36 %/h** (recomputed from the log with the page's own rules). |
| A separate manual *Measure drain* with its own twelve-entry history | The Overview "Drain" plate quotes the last clean manual measurement | It read **5.10 %/h from 2026-09-26** in the README's screenshot: the plate reads that store, not the log, so thirteen days of an always-on log don't reach it. Two sources of truth. |
| A deep snapshot every 3 h: wake locks, alarm wakeups, CPU, foreground seconds, `shizuku` | Nothing | The clue is written to disk and never read. |
| Wake section: Doze, held locks, alarm wakeups | Wakeups *since boot*, top 8, needs Shizuku, three `dumpsys` calls on every page open | `TIME_TICK` dominates, with no time window and no cause. |
| Its own recording | Nothing | After the framework restart the hub was background-restricted and recorded nothing from 17:02 to 07:41, unannounced. |
| Android's own per-app list (outside the hub) | Tachiyomi, 683 mAh | Wrong: the display's charge went to the last app in front. |

The page itself has structural problems too:

- **No hierarchy.** One long scroll of eight plates of equal weight: gauge, measure, past measurements, log, wakes, readings, tools. The answer to "is my battery OK?" is nowhere.
- **The loop is split.** Measure, attribute, fix and verify live in five places: Battery, System › Tweaks, System › Apps, Suite › Sleep and Device › Logs.
- **No time axis.** The chart is a level line with plugged spans in alpha green, which Kaleido mutes. Screen state, charging and gaps aren't drawn.
- **Not accessible.** The chart is a bare `Canvas` with no semantics; the page relies on a graphic for its only history.
- **Developer tools on the front page.** *Force idle* is a `dumpsys` toggle that sits beside everyday keys, and the jargon (wakeups, Doze, wake locks) is unexplained.

## 2. What the section is for

It answers six questions, in this order, and each page answers one:

1. **Is my battery OK?** A verdict, before any number.
2. **How long will it last?** An estimate that says what it rests on.
3. **What happened?** Last night, today, last week, as episodes you can read.
4. **What used it?** By state, by app, by what kept the tablet awake.
5. **What do I do?** One action per finding, and the evidence behind it.
6. **Did it help?** Before and after a change, against the tablet's own normal spread.

Principles, on top of those in `PRODUCT.md`:

- **Verdict first, depth on demand.** Normal, Watch or Problem, in words and a shape, never in colour alone.
- **Episodes, not samples.** The log's rows become *asleep 22:16 to 23:46, 25 mAh*, *display on while asleep 23:46 to 06:17, 2 183 mAh*. A sentence beats a chart, and the chart is the index of the sentences.
- **One source of truth.** The log. The manual measurement becomes a mark on it.
- **Honest about coverage.** Gaps are drawn and named, never interpolated. Baselines are the tablet's own median, much as Google Play's anomaly detection judges a metric against its own previous 28 days.
- **No new cost.** No polling, no wake lock, no foreground service. A single one-shot alarm per sleep at most, and only for the opt-in guard.
- **Degrade by tier.** The log alone (T0) is enough to see the stuck night; T1 adds Android's history and the deep snapshots; T2 adds the fixes.

## 3. Structure

### 3.1 Pages

The rail keeps its five sections. Battery becomes a section of four pages, shown as tabs like System's:

| Tab | Question | What it holds | Moves in from |
|---|---|---|---|
| **Today** | Is it OK? How long? What happened? | Verdict and up to three findings, the gauge and time left, the last 24 hours as a strip and a list of episodes, recording health | Battery (gauge, status), Overview (drain) |
| **History** | How has it been? Did the change help? | Window 24 h, 3, 7 or 30 days; a day table; discharge sessions; standby trend; marks and before and after | Battery › Measure drain, Past measurements, Battery log |
| **Causes** | What used it? | By state, by app in use, what kept the tablet awake and woke it, radios and light, restricted apps | Battery › Why it wakes, Overview › Access (partly) |
| **Health** | Is the battery healthy? | Capacity as learned, cycles, health, temperature, how it charges, the 80 % limit note | Battery › Readings |

**Recording** (pause, resume, export, share a report, where the files are) is a plate at the foot of History, and the module row in Suite stays as it is. **Tools** leaves the section: *Battery usage* becomes a link in Causes, and *Force idle* moves to Device › Access beside the other privileged actions, labelled as a developer test.

### 3.2 Navigation contract

`Destination.Battery` keeps its name and opens Today, because the home screen, Suite and notifications link to pages by name (`MainActivity.EXTRA_DESTINATION`). New destinations are `BatteryHistory`, `BatteryCauses` and `BatteryHealth`. Overview's battery block shows the same verdict and gauge from the same model and links to Today; its *Drain* plate goes.

### 3.3 Where battery things live

| Today | After |
|---|---|
| Overview › Drain (manual, stale) | Overview › Battery block (verdict, gauge, time left) from the log |
| Battery › Measure drain, Past measurements | History › Marks and before and after; old results kept as read-only rows |
| Battery › Battery log | History › Recording |
| Battery › Why it wakes | Causes › What kept it awake (windowed) |
| Battery › Readings | Health |
| Battery › Tools | Causes › link; Device › Access (force idle) |
| System › Tweaks (Sleep, Power, Radios) | Unchanged until §11; findings link into them |
| System › Apps (background restriction) | Unchanged; Causes rows deep-link to an app's page |

### 3.4 Outside this plan

Noted, not designed here: Overview's *Planned* plate gives prime space to unbuilt features; System mixes unrelated things (Tweaks, Apps, Appearance, Fonts, Settings); Device mixes the tablet, access, logs and storage. They belong to the Tweaks round.

## 4. Features

### 4.1 Verdict and findings

The verdict is the worst finding in the window: **Normal**, **Watch** or **Problem**, or **Not enough data**. Thresholds are starting values, to be tuned against the log; they are relative to the tablet's own baseline wherever possible (today 9 mA asleep).

| Finding | Fires when | Severity | Seen in the log | First action |
|---|---|---|---|---|
| Display on while asleep | The display is ON and the tablet isn't interactive for 10 minutes or more (the display column, or Android's history: `+screen` with no `screenwake`). From the log alone: asleep for 30 minutes at 120 mA or more with the processor awake 90 % or more | Problem | 10-08, 18:37 to 21:02 and 23:46 to 06:17 | Put the tablet to sleep again (§4.8), then restart it |
| Standby drain above normal | Asleep for 60 minutes and the rate is over the larger of 3 × baseline and 30 mA; 100 mA is a Problem | Watch | 9 mA baseline | Causes, for that window |
| The processor didn't sleep | Asleep for 30 minutes with uptime over 90 % of elapsed time | Watch | The stuck night: about 100 % | Causes |
| A wake lock kept it awake | A partial wake lock held an hour or more while asleep (Android vitals' *stuck* definition), or two hours in 24, and the processor didn't sleep | Watch | Not seen: 58 s in the whole cycle | The app's page |
| Android restarted | The dropbox has `system_server_crash` or `SYSTEM_RESTART` since the last boot, or `BOOT_COMPLETED` came without a new boot count | Info; Watch when one of the above follows within a day | 10-08, 16:32:41 | Restart the tablet before sleeping |
| The hub can't record | Its background use is `ignore`, or no row for 2 hours on battery with the processor awake | Watch | 17:02 to 07:41 | Allow background use |
| An app used much more than usual | An app's in-use mA is over 2.5 times the tablet's average in-use draw (the baseline's) over at least 30 minutes | Info | Chrome, 867 mA | Open the app's page |
| The battery ran out | The last row is at 1 % or less, and a boot with `shutdown,battery` follows | Report | 06:17 | The morning report (§4.7) |

Every finding states its evidence (*23:46 to 06:17, 333 mA, 2 183 mAh, processor never slept*), never a cause it hasn't shown, and at most three are shown at once. The cause of the stuck display isn't known (§10), so the copy says *happened*, not *because*.

### 4.2 Timeline and episodes

The log, and on T1 Android's history, are turned into episodes of five kinds: **In use** (with the app that was in front most), **Asleep**, **Display on while asleep**, **Charging** and **Not recorded**. An episode has a start, an end, levels, mAh, average mA, the share of time the processor slept and flags. The same list feeds the strip, the episode list, the day table, the findings and the text alternative, so a chart can never disagree with its words.

### 4.3 Time left

The estimate is the stored charge divided by the rate you usually draw: the quiet-asleep and in-use rates of the last 7 days, weighted by how the days were spent, with the range of the daily rates. On this tablet's last week that is 0.78 × 9 mA + 0.22 × 291 mA = 71 mA, so about 53 hours from 3 750 mAh, quoted with its basis (*7 days of your log*). With under a day of unplugged log it says so and shows nothing. Charging shows the rate it is charging at, not a prediction.

### 4.4 History, marks and before-and-after

Windows of 24 hours, 3, 7 and 30 days, with a day pager. Each day is a row: used, in use, asleep, charging, flags. *Discharge sessions* run from unplug to plug. A standby trend shows one bar per day and the median.

A **mark** is a labelled moment in the log. The Journal already timestamps every applied tweak, so those become marks without asking; the owner adds others by typing a short label in the entry panel at the top. **Before and after** compares quiet-asleep rates either side of a mark, with at least 12 hours asleep on each side, and shows the spread of the daily rates beside the difference, so a change smaller than the tablet's ordinary day-to-day variation reads as *not distinguishable from normal* rather than as a win. This replaces *Measure drain*; its past results stay visible.

### 4.5 Causes

- **By state:** in use, asleep, display on while asleep, in mAh and share.
- **By app, in use:** the charge between log rows, split by the app in front. Rows show hours, average mA and Tags (*Background restricted*, *Doze exempt*). Where Android's own per-app figure differs by more than a third, one line says so, because the stuck night showed it can be wrong.
- **What kept it awake and woke it:** wake lock time and alarm wakeups per app, *between* the 3-hourly snapshots rather than since boot, with `TIME_TICK` and the other system tags grouped as *Android itself*.
- **Radios and light:** the share of time Wi-Fi was connected and the average front light, from the log's columns.
- **Restricted apps:** a count, linking to System › Apps.

### 4.6 Recording health

A line on Today says how much of the last 24 hours was recorded, names any gap and what filled it (Android's history), and warns, with the fix, when the hub is background-restricted or Shizuku has stopped. This is the plan's answer to the 17:02 to 07:41 hole.

### 4.7 Morning report

Sent after every qualifying sleep, whatever was found, because a notification that says *Slept 8 h 12 min, used 3 %* is useful on its own and tells the owner the tablet is being watched. It has a **visible on/off switch** on Today (default on). At the first wake after a sleep of 4 hours or more on battery, one low-importance notification (no sound or vibration, no personal text) says what happened in a sentence or two: the normal night's figures, or the finding and its evidence, plus what the guard did if it acted. After a boot that follows `shutdown,battery`, it says when the tablet ran out and what the log shows before that. It uses the existing `POST_NOTIFICATIONS` permission, which Android 13 asks for at runtime, so turning the switch on asks for it. It runs from a broadcast the app already receives and adds no alarm.

### 4.8 Sleep guard (opt-in, Experimental)

Off until the owner turns it on, and labelled **Experimental** on the switch and in its text, because none of its steps has been seen healing a stuck display (§9). Detect first, intervene second. The trigger is *display ON, not interactive, for 10 minutes*; transient refreshes last about 1.3 s, so nothing ordinary reaches it. The ladder, each step logged with its result so the log shows which one healed it:

1. Send `onyx_dream_refresh`, which makes the dream run its show-and-doze path again. Check 15 s later.
2. Still ON: `GLOBAL_ACTION_LOCK_SCREEN` through the accessibility service the live sleep screen already uses (no extra capability needed *[verify]*).
3. Still ON: with Shizuku, `input keyevent 223`.
4. Notify once: *the display was on for 12 minutes while asleep; put to sleep at 23:58*.

It never acts while interactive or within a minute of input, and not while docked, where Android's own dream legitimately keeps the display on. It needs the hub's background use to be allowed, or its single check alarm is cleared at sleep. It ships off and Experimental, and stays labelled so until the experiments in §9 show that steps 1 and 2 heal a stuck display.

### 4.9 Health

Learned capacity against what Android reports as the estimate, cycle count where the firmware gives it, health, temperature while charging, average charge current and time to full, and a note on Boox's 80 % *Charging protection*. Low priority: it answers a question few owners ask.

### 4.10 Battery report

*Share* builds one zip: the last 72 hours of the log, the findings with their evidence, and a one-page text summary. App names are included only if the owner ticks it (usage is behaviour, even without account details). It extends the Logs export and reuses its redaction.

### 4.11 Summary

| Feature | Page | Tier | Priority |
|---|---|---|---|
| Verdict and findings | Today, Overview | T0 for the log rules; T1 for Android's history, wake locks and the dropbox | P0 |
| Display-state column, detector, standby fix | Log, Today | T0 | P0 |
| Recording health | Today | T0; T2 for the fix | P0 |
| Timeline and episodes | Today, History | T0, richer on T1 | P1 |
| Time left | Today, Overview | T0 | P1 |
| History, day table, trend | History | T0 | P1 |
| Causes | Causes | T0 for state and app; T1 for wake locks and alarms | P2 |
| Marks and before-and-after | History | T0 | P2 |
| Battery report | History | T0 | P2 |
| Morning report (always sent, visible switch, default on) | Notification, Today | T0 | P2 |
| Sleep guard | Today | T0 for steps 1, 2 and 4; T2 for step 3 | P3 |
| Health | Health | T0 | P3 |

## 5. Design

The section stays inside the hub's *Braun Instrument* world: paper white, black ink, legend grey, one lamp; hairline rules, engraved plate titles, round keys with 1.5 dp rims; no cards, shadows, gradients or motion. The e-ink conventions that already govern the hub apply: pages and pagers instead of long lists, 48 dp targets, text entry at the top, and no animation.

### 5.1 First viewport

Portrait, Today:

```
Battery                                        [ Read again ]
Read at 08:29 · recorded since Oct 1 (8 days)

 ▲ PROBLEM · Display stayed on while asleep
 23:46 to 06:17 · 6 h 31 min · 61 % to 0 % · 2 183 mAh
 [ See what happened ]

                     27 %
 |....|....|....|....|....|....|....|....|....|....|
 0         20        40        60        80       100
 ● Charging from the wall
 About 53 h at your usual use · 7 days of your log

 Last 24 hours                       [24 h] [3 d] [7 d]
 Level   ‾‾‾‾╲___╲________________╱‾‾
 Screen  ▮▮▮    ▮▮▮▮    ▒▒▒▒▒▒▒▒▒▒▒▒▮
 Power              ═══             ═══
         18    21    00    03    06    09
 ◀ ▶  23:46 to 06:17  display on, not in use · 333 mA

 What happened (newest first)
 06:17  Battery ran out
 23:46 to 06:17  Display on while asleep · 6 h 31 min · 2 183 mAh
 22:16 to 23:46  Asleep · 1 h 30 min · 25 mAh · 17 mA
 ...
 Recorded 21 h 40 min of 24 h · gap 17:02 to 07:41 filled from Android's history
```

Landscape puts the verdict, gauge and findings in the left column and the strip and episode list in the right, using the existing two-column rule (`PlatePair`, 680 dp).

### 5.2 Encoding: shape, pattern and word, never colour

| Meaning | Strip | Words and marker |
|---|---|---|
| In use | Solid black bar | *In use*, app name |
| Asleep | A thin 2.5 dp line | *Asleep* |
| Display on while asleep | Diagonal hatch, 2 dp lines every 6 dp, a ✕ at each end | *Display on while asleep*, with the Problem marker |
| Charging | A double rule with a bolt glyph | *Charging* |
| Not recorded | Dotted gap | *Not recorded* |
| A mark | A numbered ▲ on the level line | *Mark 1: Applied Doze* |
| Verdict | Normal: ring and tick. Watch: outlined triangle. Problem: solid triangle | The word is always there |

The Signal green stays what it means everywhere in the hub, *on*, and only doubles a filled shape. The Alert red is limited to error text, as now. Hatch and dotted patterns are legible on the 300 ppi mono layer and muted on Kaleido's colour layer; both are to be judged on the panel (§9).

### 5.3 New components

The generic ones (`Segmented`, `DayPager`, `RateBar`, `Hatch`) go in `:kit:ui`; the ones that know the battery model (`Verdict`, `DayStrip`, `EpisodeRow`, `Finding`) live in `hub/ui/battery/`.

| Component | What it is |
|---|---|
| `Verdict` | The marker, a title line, an evidence line and at most one key. One per page; announces itself politely after *Read again*. |
| `DayStrip` | The level line above three lanes (Screen, Power, Recorded), the time axis with labels every 3 h, and ◀ ▶ 56 dp keys that step the cursor between episodes. A readout line names the selected episode. No dragging, no hover, no long press. Drawn with a `TextMeasurer` so labels follow font scale. |
| `EpisodeRow` | Time range, kind in words, duration, mAh, mA, a flag Tag, and an inline expand for detail. The accessible twin of the strip. |
| `Finding` | A row with the finding's title, evidence line and one key; deep-links to the page that holds the control. |
| `RateBar` | A horizontal bar with its value printed at the end, solid or hatched, for state and app rows. |
| `Segmented` | The window selector, promoted from `Apps.kt`, where it is styled inline. |
| `DayPager` | Previous day, the date, next day, as `IconKey`s. |
| `Hatch` | The pattern fill shared by the strip and bars. |

Components that already fit are kept as they are: `Plate`, `SpecRow`, `Key`, `Tag`, `Lamp`, `Pager`, `TuningScale`, `ScreenHeader`, `InstrumentPage`.

### 5.4 Layout and refresh

- A tap on an episode expands it in place, a local change that repaints only its rows. Nothing animates; a page appears in one frame.
- Lists over nine rows are paged (`Pager`), as in Apps.
- Nothing refreshes by itself. Data is read on opening and on *Read again*, and the screen paints once when it lands; `rememberReading` keeps the previous values on screen meanwhile.
- Each tab reads only its own data, so opening Today doesn't run the three `dumpsys` calls of Causes.

### 5.5 Copy

- Plain words with the unit explained once: **%/h** is the main unit (*1 000 mA is about 27 % an hour*), with mA beside it. *Wake lock* is *kept the processor awake*; *Doze* is *deep sleep*.
- Durations read *6 h 31 min*; dates and times follow the locale; plurals use string resources.
- Say what was measured and what wasn't: *Recorded 21 h 40 min of 24 h*, *Estimate: 7 days of your log*, *Not enough data yet*.
- Findings state what happened and the evidence, not a cause: *The display stayed on for 6 h 31 min while the tablet slept.*

### 5.6 States

Reading: *Reading…* in words, no spinner. Empty (under 24 hours of log): one sentence saying how long to wait. Degraded: each missing source says which tier unlocks it and links to Access. Recording off: a banner with *Resume*.

## 6. Accessibility

The e-ink panel is the first constraint (`PRODUCT.md`). The rules below add what a data-heavy page needs for people using a screen reader, a switch or keyboard, larger text, or who can't rely on colour.

| Area | Rule for this section | Checked by |
|---|---|---|
| Contrast | Text is Ink black or Legend grey (8.6:1). Rule grey (#9A9A9A) marks ticks and hairlines only, never data. Data strokes are at least 2.5 dp, hatch lines 2 dp. WCAG 1.4.3 and 1.4.11. | A contrast test over the theme; the panel, both layers *[verify]* |
| Not by colour alone | Every state has a word, a shape and a pattern; green and red only repeat them. WCAG 1.4.1. | A 1-bit render of each screen state compared with the colour one |
| Text alternatives | Each chart has a generated summary (*Last 24 hours: the battery fell from 97 % to 0 %. The display stayed on while asleep from 23:46 to 06:17.*) as its description, and the episode list is its table. WCAG 1.1.1. | A Compose test that the fixture night yields that sentence |
| Structure | Page and plate titles are headings; tabs have `Role.Tab` and `selected`; the day table and episode list carry `collectionInfo`; each row merges its descendants; reading order is visual order. | Semantics-tree tests |
| Targets | 48 dp or more, 8 dp apart; the chart is stepped with 56 dp keys, never dragged. No action is gesture-only: row actions are also custom accessibility actions. | Accessibility Test Framework checks in the instrumented tests |
| Text size | Everything is in sp. At 200 % and with the hub's weight-boost and system-font options, tables reflow to stacked rows (never sideways scrolling), strip labels are measured and elided, tabs scroll. | Emulator screenshots at 1.0, 1.5 and 2.0, both orientations |
| Changes | Nothing updates by itself, so no live regions except the verdict, politely, once after *Read again*. Errors use the `error` semantic. | Semantics tests |
| Motion | None. *Reading…* instead of a spinner. | Review |
| Input | D-pad and keyboard focus already draw a 2 dp ring; traversal order matches the layout; nothing times out. | A focus-order test |
| Language | Plain words, a glossary row for mA and %/h, locale numbers, plural resources. | String review |
| Cognitive load | One primary key per page, at most three findings, paged lists, one idea per plate. | Review against §5 |
| Screen readers | Whether TalkBack runs on this firmware is unknown *[verify]*; the semantic tests and the emulator's TalkBack stand in until then. | A pass on the emulator |

## 7. Data and code

### 7.1 Model

A pure-Kotlin package, `core/battery/`, with no Android types in its logic so it runs under JVM tests:

- `Episode(kind, start, end, level0, level1, mah, avgMa, sleptShare, topApp, flags)`.
- `Timeline`: episodes for a window, built from the log rows (T0) and, when available, Android's history and the deep snapshots (T1).
- `Baseline`: the rolling median and spread of the quiet-asleep rate over 28 days, and the average in-use draw. Per-app medians are not kept in 0.9.0, so *an app used much more than usual* compares an app with the tablet's own in-use average.
- `Finding(kind, severity, evidence, action)` and `Verdict`, from the rules in §4.1.
- `Estimate`: time left with its basis (§4.3).

`BatteryLog.summary` and `LogSegment` are replaced by this model; `Measurement`'s store is migrated into marks.

### 7.2 Log changes

- **CSV v3 header** adds `display` (on, doze, off) and `top` (the foreground package), both cheap reads in a receiver that already runs; the existing rename-to-`.v1.csv` logic keeps old files readable.
- **Deep snapshot** adds the display state, whether `dream:doze` is held, the hub's own `RUN_ANY_IN_BACKGROUND`, the dropbox tags since the last snapshot and the boot count.
- **Marks** are rows with `reason` `mark` and a short label.
- A new row reason, `display_stuck`, is written when the detector fires.
- Android's history is parsed on demand, off the main thread, for the last 72 hours only, keeping the screen, plug, `screenwake`, wake lock and top-app events. It needs DUMP (T1), has a timeout, and is cached against the log's modification time.

### 7.3 Read path

Each tab reads only what it shows, through `rememberReading`. The parsed timeline is cached per window and invalidated when the log changes. The 85 KB monthly CSV and the 5 MB history are never parsed on the main thread or on every recomposition.

### 7.4 Tiers

| Tier | Gives |
|---|---|
| T0 | The log: verdict rules from level, mA, uptime, screen and plug; episodes; History; time left; the morning report; guard steps 1, 2 and 4 |
| T1 (DUMP, usage) | Android's history (display state, who woke it, wake locks), the dropbox, the deep snapshots' wake locks and alarms |
| T2 (Shizuku) | Front light in the rows, restricted-app list, allowing background use, guard step 3, force idle |

### 7.5 Tests

- A golden fixture from the capture of 2026-10-06 to 10-09 (CSV rows only, no personal data) must yield *display on while asleep* for 18:37 to 21:02 and 23:46 to 06:17, a standby of about 0.36 %/h and not 0.92, and a Problem verdict on 10-09.
- Rule tests with synthetic nights: quiet, a heavy app, a held wake lock, a restart, a gap, a charge in the middle.
- The history parser against a trimmed real file, including the 30-minute flush that ends it early.
- Semantics and font-scale tests for the components in §5.3, and the contrast and 1-bit checks in §6.

## 8. Phases

| Phase | Ships | Done when | Version |
|---|---|---|---|
| **0, see it** | The `display` and `top` columns, the detector on the log, the standby figure corrected, a *Display on while asleep* line on the current page, the self-check for hub restriction, the golden fixture | The fixture passes; on the tablet the log carries the new columns and the page flags a staged episode | 0.9.0 |
| **1, Today** | `core/battery`, the verdict and findings, the strip and episode list, time left, recording health, the Overview block, `Verdict`, `DayStrip`, `EpisodeRow`, `Finding`, `Segmented`, `Hatch` | Both orientations and 2.0 font scale reviewed on the emulator; hatch legibility judged on the panel; semantics tests green | 0.9.0 |
| **2, History and Causes** | Windows, the day table, sessions, the trend, marks and before-and-after (retiring *Measure drain*), Causes, the battery report | A real before-and-after of one tweak read from the log | 0.9.0 |
| **3, Guard and morning report** | The notification channel and morning report, the opt-in guard, Health | Experiments E1 to E3 (§9) done; the guard's ladder shown to heal or logged as not healing | 0.9.0 |
| **4, with Tweaks** | Measured-effect chips, findings that open the right tweak | After §11 | later |

On 2026-10-09 the owner asked for phases 0 to 3 to be built together and released as one 0.9.0; phase 4 waits for the Tweaks round. Every phase updates `CHANGELOG.md`, the README's battery section and roadmap, and this document, and records the tablet checks in `knowledge/experiments.md`.

## 9. Checking on the tablet

The stuck display can't be summoned by an adb command, so the plan separates what can be tested from what can only be watched.

- **On the JVM and emulator:** the model, rules, semantics, contrast, font scale, both orientations.
- **On the tablet, by looking:** hatch and dotted patterns on both layers, strip legibility at arm's length, the stepping keys with a finger and the stylus.
- **Experiment E1, the control** (no action needed): after Phase 0 is installed, sleep the tablet undisturbed for over 100 minutes on a normal boot and read the log. It shows whether a 90-minute event happens without a framework restart.
- **Experiment E2, the restart** (needs the owner's explicit say-so in that session): restart the framework once (`setprop ctl.restart zygote` from the shell), then sleep undisturbed overnight on a charged battery, and read the log. It tests the correlation of §10 and either shows another stuck period or lowers the suspicion.
- **Experiment E3, healing:** whenever a stuck period happens, the guard's ladder (once built) logs which step restored the display. Until then, if one happens while the tablet is connected, try step 1 by hand.
- **Only a person can tell** whether the page reads well, the patterns are clear and the keys are comfortable.

## 10. Risks and open questions

- **The trigger is unknown.** Two stuck periods began exactly 90 minutes after the display last went to doze, after a framework crash; four earlier sleeps did not. The detector and guard don't depend on the cause, but a fix upstream (Boox) may be needed, and the guard only limits the damage.
- **Thresholds come from one tablet and eight days.** Hence baselines, three severities and *happened*, not *because*. Expect tuning.
- **The guard's steps are untested.** Whether `onyx_dream_refresh` or the lock-screen action restores doze is *[verify]*; so is whether the accessibility service may perform it without more capability.
- **Android's history is a moving target.** Its format can change and it is large; it is optional, bounded and tested against fixtures.
- **UsageStats keeps fine-grained events for only about a week** *[verify]*, which is why the `top` column is stored in the log.
- **Privacy.** App usage is behaviour; the report leaves app names out unless ticked, and the local log already holds them.
- **Hatching on Kaleido** may be muddy at the colour layer's 150 ppi. If so, fall back to dot fill and heavier outlines.
- **Wireless debugging on this firmware** exists after all (`knowledge/experiments.md`, 2026-10-09). If its pairing code lets Shizuku start from the tablet, Recording health can offer the fix on the device instead of a computer *[verify]*.
- **Decided by the owner, 2026-10-09:** the tabs are Today, History, Causes and Health; the guard is opt-in and marked Experimental; the morning report is sent after every qualifying sleep with a visible on/off switch; *Measure drain* folds into marks. Whether to run E2 is still open.

## 11. Tweaks, later

Not designed here. What the Battery work needs from it, and what the round should look at:

- **Marks for free.** Journal entries already carry a timestamp and a subject; Battery reads them as marks. Keep that, and add each tweak's `affects` (standby, in use, radios) and an optional evidence row, so before-and-after can say which rate to compare.
- **Measured effect on the row.** A tweak that has been on for a few days shows its before-and-after chip from History, with its spread.
- **State drift.** A tweak can read On because the journal says so while the system value changed, as when the framework restart reset the hub's background use. Rows need a *changed since you applied it* state and a one-tap re-apply.
- **Findings open tweaks.** *The hub can't record* opens the background control; *Standby drain above normal* opens the Sleep and Radios groups.
- **Groups by goal, not mechanism.** Today they are Apps, Sleep, Power, Radios, Interface and Privacy, 19 tweaks of equal weight. Interface and Privacy don't belong with the battery ones, and presets (*Reading*, *Night*) would sit above the list.
- **Same rules.** Plate and row components from §5.3, 48 dp keys, a word for every state, semantics on every row.

## 12. What shipped in 0.9.0

Built as one release on 2026-10-09 (phases 0 to 3 of §8, which the owner asked to be built together). The code is `hub/.../core/battery/` (the model: `Rows`, `Timelines`, `Summaries`, `Baselines`, `Findings`, `Estimates`, `Marks`, the readers `ForegroundReader`, `SignalReader` and `DeepLog`, `BatteryModel` which every page loads through, and `MorningReport`, `SleepGuard` and `BatteryReport`), the log writer in `core/BatteryLog.kt` with `DisplayWatch`, and the pages in `ui/screens/Battery{Today,History,Causes,Health}.kt` over the components in `ui/battery/`. The generic components (`Segmented`, `RateBar`, `drawHatch` and `Modifier.hatch`, `DayPager`) are in `:kit:ui`.

Checked: the model against two months of the real log (the rows dated 2026-10-09 in `knowledge/experiments.md`), `GoldenNightTest` over the night of 2026-10-08, JVM tests for each rule, the pages on the emulator with the real log pushed into the app's log folder, and Today, the Test Shizuku probe and the new log writer on the Note Air6 C. Not yet seen on the tablet: History, Causes and Health on its own font, the morning report as a notification, the guard's steps, and a night recorded by the display detector (§9's E1 to E3 are still to run).

Decided on the way, differing from the plan above:

- **The fourth tab is Health, not Care.** The guard is opt-in and labelled Experimental, and the morning report is sent after every qualifying sleep, with a visible switch (default on); both switches are the *Alerts* plate at the foot of Today.
- **Baselines are the tablet's own.** The quiet-asleep baseline is the hours-weighted median of its asleep episodes over 28 days, 15 mA on the owner's tablet, not the 9 mA of its quietest nights, so a day of ordinary background sync is not a finding. With under 6 hours of asleep log it is unknown and the standby rule uses 30 mA.
- **Display on while asleep is inferred from the drain when the display column is missing:** 120 mA or more with the processor awake 90 % or more (measured on the real log: quiet sleep keeps the processor asleep a median 0.92 of the time, the stuck display 0.00). A run of such segments continues through a shorter one at the same rate, so one 3.8-minute segment does not split a night in two.
- **An app used much more than usual** compares an app with 2.5 times the baseline's average in-use draw, since per-app medians are not kept.
- **Android's own history (`dumpsys batterystats --history`) is not parsed.** The findings rest on the log, the three-hourly snapshots, the dropbox (`system_server_crash`, `SYSTEM_RESTART`, read with the `READ_LOGS` grant) and the app in front from UsageStats.
- **Not built:** the guard's fourth step (notify once that the display was on and what was done; the morning report mentions it instead), per-app *Background restricted* and *Doze exempt* tags and a restricted-apps count in Causes (they need Shizuku), the line comparing Android's per-app figure with the log's, a day pager on History (windows are chosen with the segmented control), and the Overview block's own strip.
- **Moved:** *Force deep sleep* is a developer test on Device › Access; *Battery usage* is a link in Causes › More; the battery log's controls are History › Recording; the old manual *Measure drain* is gone and its past results are History › Earlier measurements.
- **Marks:** *Add mark* writes through `BatteryLog.record(..., "mark", label)` so the page can re-read when the row exists; tweak marks come from the Journal ("Applied X", "Undid X").
- **The battery report** redacts by column and JSON key (the app in front, the marks' words, the wake sources and per-app usage are left out unless *Include app names* is ticked).
- **Rows added to the log:** `display_on`, `display_stuck`, `mark` and `guard`, and the columns `display`, `top` and `note`.

## Sources

- Android Developers, *Analyze power use with Battery Historian* (a timeline of screen, CPU and wake lock rows under the level line; the tool is no longer maintained) and *Excessive partial wake locks* and *Stuck partial wake locks* in Android vitals (two hours in 24 and one hour in the background), and the Play Developer Reporting API's anomalies, which judge a metric against its own previous 28 days.
- Android's Settings › Battery usage and Samsung Device care's *since last charge* view: a 24-hour chart, screen-on time and per-app foreground and background time are the patterns people already know.
- W3C WCAG 2.2: 1.1.1 Non-text Content, 1.4.1 Use of Color, 1.4.3 Contrast (Minimum), 1.4.11 Non-text Contrast, and the guidance to give charts a text summary and a data table.
- Android Developers, *Semantics in Compose*: custom drawing needs its semantics supplied by hand; headings, `stateDescription`, `liveRegion`, `collectionInfo` and custom actions.
- E-ink UI guidance, as leads rather than facts for this tablet (Onyx's OnyxAndroidDemo guidelines, Mudita Mindful Design, community BOOX notes): no animation, paging over scrolling, thick sans type, thicker and darker lines, 48 dp stylus targets.
- This repository: `PRODUCT.md`, `DESIGN.md` (the hub's world), `docs/01-product-vision.md` (the Battery Doctor loop), `docs/07-suite.md` (navigation) and `knowledge/experiments.md`.
