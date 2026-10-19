# 06 · Sleep screen studio

Goal: a sleep screen worth looking at. You choose a face, see it exactly as the screen will show it, and BooxUltimatum keeps it current while the tablet is in use, without adding a single wakeup.

**Words used here.** The *sleep screen* is the picture the tablet shows while it sleeps (Boox calls it the screensaver). A *face* is a sleep screen design that BooxUltimatum draws to fill the whole screen, in Sleep image mode. A *plate* is the small paper card BooxUltimatum draws over the picture in Over Transparent mode. A *sticker* is Boox's lockscreen sticker, the see-through picture Boox lays over its snapshot of the screen in its Transparent style. BooxUltimatum draws the plate onto the sticker. *Shizuku* is an app that, once started, lets BooxUltimatum run commands with the same privileges as an adb shell (adb is Android's debugging tool, normally run from a computer). *Modes and tiers* says when each mode needs it.

**The Sleep page.** In landscape, the preview and its buttons sit in a left pane and the cards scroll on the right. In portrait, the preview and the buttons sit at the top and the cards scroll below. The buttons are *Apply* (*Apply again* once applied), *Sleep now*, *Restore Boox default* and *Open Boox screensaver settings*, with *Use as power-off screen* under them. The cards, in order, are *Mode*, *Face* (*Plate* in Over Transparent mode), the selected face's own options, *While asleep* (Sleep image mode only), *What it shows*, *Type*, *Ink*, *Refresh* and *Boox settings*.

## How Boox draws the sleep screen (verified on NA6C FW 4.3, 2026-09-26)

NA6C FW 4.3 is the maintainer's Note Air6 C on Boox firmware 4.3, the tablet the checks in this document were made on unless the text names another.

- The sleep screen is a doze dream, `com.onyx/com.onyx.common.dream.OnyxDaydreamService`. A dream is Android's name for a screensaver service, and this one keeps drawing while the tablet dozes in its low-power state. Its style (Image, Transparent and several presets) and its pictures live in `com.onyx`'s private MMKV (a key-value store), which no other app can read or write.
- **Image style.** Broadcasting `onyx.action.SCREENSAVER` with `type` = 16 (int), `file` = an absolute path (string) and `show_result_hint` = false (boolean) switches the style to Image with that file as its only picture. The receiver is dynamic (registered while the process runs), exported (open to other apps) and needs no permission, and lives in the always-running `com.onyx` process. This was verified from the shell's user ID (uid), the one adb commands run as. BooxUltimatum's own uid is expected to work the same way and is still to be confirmed on the tablet.
- A file directly in `/sdcard/Pictures/` is used in place. A file anywhere else is copied into `Pictures/` once, and later overwrites of the original are ignored.
- The file is decoded afresh at every sleep, so overwriting it between sleeps changes the next sleep screen. It is not read again while the tablet sleeps. Files above 10 MB are dropped and Boox falls back to its default.
- Boox scales the picture with centre-crop to the current rotation, which means it fills the screen and cuts off what overflows: 1860 × 2480 in portrait, 2480 × 1860 at rotation 90 or 270 (logcat).
- Boox draws its own overlay on top of any style: a clock (Digital, LED, Analog or None, redrawn every 5 minutes while asleep), a motto (Text display), and a bottom status bar with the battery level and "Press power button to wake up". You set these in Boox Settings › Desktop & Screensaver › Screensaver.
- Boox dithers the picture itself, turning shades into patterns of dots that e-ink can show. A normal 24-bit PNG is the right input.
- **Transparent style.** It snapshots the screen at sleep (on `com.onyx.action.ONYX_SYSTEM_GOING_TO_SLEEP`, into a cache the dream collects) and can lay a sticker over it. Boox calls it a Lockscreen Sticker, and it is a PNG with alpha (see-through areas). You choose the sticker once in Boox (Transparent › Show lockscreen sticker › Select sticker › + › an album › a picture › OK). Boox saves the result as `/sdcard/Pictures/Sticker/sticker_<yyyy-MM-dd_HH_mm_ss_SSS>.png` (1860 × 2480 ARGB, owned by Android's media provider, the system service behind MediaStore). The dream reads it from one stored path at every sleep — `TransparentDream.onAttachedToWindow` → `PrepareTransparentDreamAction` → `DreamBundle.getTransparentStickerPath()` — and never reads it again during a sleep, so a rewritten sticker appears at the next one (verified from the decompiled `com.onyx`, Boox's own app turned back into readable code, 2026-10-02).
- **How the sticker is scaled.** It is decoded at the screen's size *in the rotation the tablet sleeps in*, with `ImageView.ScaleType.CENTER_CROP` by default (`DreamSettingBean`). A 1860 × 2480 sheet shown at 2480 × 1860 is scaled to 2480 × 3307 and cut back to the middle 1860 rows. Those are rows 542 to 1937 of the sheet, and the other way round. Only the centred square of 0.75 of the short side (1395 px) survives either rotation. Boox's own dream settings offer *fit* beside *crop* for the style (`DreamStyleSettingBaseFragment`).
- **No app can switch Boox to Transparent.** `onyx.action.SCREENSAVER` carries only three types — 1 wallpaper, 16 fixed screensaver image, 17 shutdown image (`ScreensaverReceiver`, `ScreenSaverUtils`) — so the style and the one-time sticker pick stay yours.
- With a PIN, a tablet put to sleep from the lock screen shows a black privacy screen. That is Boox's behaviour, and BooxUltimatum can't change it.
- The screensaver style page is `com.onyx/.common.setting.ui.SettingContainerActivity` with action `onyx.settings.action.DREAM_STYLE_SETTING` (and `onyx.settings.action.LAUNCHER_SCREENSAVER_SETTING` for the Desktop & Screensaver page). The activity is exported and maps these actions to its pages in code, but its intent filter does not list them, so the component is named explicitly. *Open Boox screensaver settings* falls back to Boox Settings.

## Modes and tiers

The tier says what a mode needs. T0 needs only BooxUltimatum. T1 needs an adb grant, a permission given from a computer. T2 needs Shizuku, and T3 needs root. The Sleep page shows each mode's tier beside its name.

| Mode | Tier | Mechanism | Revert |
|---|---|---|---|
| Sleep image | T0 (T2 repeats the broadcast) | BooxUltimatum's picture in `Pictures/booxultimatum-sleep.png` through MediaStore (Android's shared database of pictures), then the type 16 broadcast from BooxUltimatum and, with Shizuku, from the shell | Type 16 with `/system/media/standby-1.png`, BooxUltimatum's pictures deleted, refreshes stopped |
| Over Transparent | T2 (T0 for the route without Shizuku) | The plate staged in BooxUltimatum's external files, then written over the sticker Boox saved — through Shizuku, or by BooxUltimatum itself with the system's write consent | Every sticker BooxUltimatum touched is copied back from its own backup, then refreshes stopped |

Without Shizuku, BooxUltimatum asks for the photo permission and the write consent on the Sleep page itself.

The previous style can't be read back, so *Restore Boox default* sets the Boox default picture and the page offers *Open Boox screensaver settings* next to it, so you can choose your old style again. Both apply and restore are journaled with a plain sentence. Restoring Boox's sticker comes first, and the style is given back only in Sleep image mode. In Over Transparent mode the style was your own choice. A failed sticker write leaves the studio on rather than claiming to be off while the plate still shows.

For the one-time sticker pick, *Put in Sticker album* writes the current plate to `Pictures/Sticker/booxultimatum.png` through MediaStore (BooxUltimatum's own picture, so T0), where the Boox picker finds it. The page lists the Boox steps.

### Reaching the sticker without Shizuku

Boox resolves the sticker by a path only it can read, so BooxUltimatum has to recognise the file another way. It looks for the newest `sticker_*.png` in `Pictures/Sticker`, which is BooxUltimatum's after a write and Boox's after a new pick. With Shizuku that is `ls -t`. Without it, BooxUltimatum makes a MediaStore query under `READ_MEDIA_IMAGES` (*Allow reading pictures* on the page). It writes the picture through the row with the write consent Android grants for it (`MediaStore.createWriteRequest`, asked once from the Sleep page with *Ask Android to allow it*). A newer file than the one remembered means you picked again in Boox. BooxUltimatum then follows the new sticker, puts the old file back the way Boox made it, and journals a list of every sticker it has written, so Restore can return all of them. Each write is a copy beside the target and a rename through the shell, and the size is read back afterwards, so Boox never decodes half a picture.

Whether the consent survives a reboot, and whether Boox re-reads a sticker changed through the media provider, are both still to check *[verify]*.

## Keeping it current without waking the tablet

On its own, nothing on the picture changes while the tablet sleeps, because Boox reads it once when the tablet goes to sleep. The only thing Boox itself redraws is its clock. So by default BooxUltimatum keeps the face current while the tablet is awake, and only then. The opt-in live updates in the next section go further.

- **Refresh alarm.** It is `setInexactRepeating` on `ELAPSED_REALTIME` (not the wakeup variant) at the chosen step: 1, 5 (default), 15 or 30 minutes. An inexact alarm is one Android may delay to batch it with others, and this variant never wakes the tablet. It never fires while asleep, and a missed one is delivered on wake. An armed alarm is left alone, so opening BooxUltimatum never pushes the next refresh away, and a changed step re-arms it. The step is the *While the tablet is awake, redraw every* choice on the page.
- **Events the system sends anyway.** These are battery level or plug changes (at most one render a minute), time, date and time-zone changes, screen on, and a calendar content observer (a watcher on the calendar's data) once READ_CALENDAR is granted. Triggers are coalesced for two seconds, and edits in the studio request a refresh.
- **Both orientations, ready ahead.** Boox centre-crops the picture to the rotation the tablet sleeps in. A face rendered only after a turn therefore left a few seconds (1.5 s wait, the coalescing, render and PNG encode) in which a sleep showed the old shape cropped. A cover closing or the tablet being picked up can also flip the rotation in the last moment. Every refresh therefore also renders the other orientation and keeps it encoded in `noBackupFilesDir/sleep-cache`, tagged with the face settings it came from. A rotation, seen through a display listener (which fires before the configuration reaches the process), swaps that file in at once, and a fresh render follows 1.5 s later. A render that finishes after the tablet turned is kept as the cache instead of being published. Portrait and landscape have the same pixel count, so the full-size sheet is reconfigured rather than allocated twice. Cost: one more render and encode per refresh, only while awake.
- **Last check before sleep.** The framework broadcasts `com.onyx.action.ONYX_SYSTEM_GOING_TO_SLEEP` tens of milliseconds before Boox decodes the picture, and it reaches BooxUltimatum. This was verified: BooxUltimatum's picture was written 100 ms after it and 260 ms before the dream decoded it. If the published picture doesn't match the rotation then, the ready one is written straight away. That write can meet Boox's read, so the style switch is sent again on the next wake in case Boox dropped the file. This is Sleep image mode only. A plate is drawn inside the band Boox keeps in either rotation, so nothing has to be swapped for it. Boox takes the Transparent snapshot in that same window, so nothing of BooxUltimatum's may be on screen then.
- **Boox's bottom bar.** Boox draws a status bar (battery, "Press power button to wake up") along the bottom unless Screensaver › *Bottom status bar* is off, and always while charging (`BaseStyleDream.getStatusBarVisibility()`). Faces don't reserve its strip. The margins keep the bottom clear, and the Sleep page asks you to turn the bar off.
- **Fingerprint.** Each render first gathers what the face shows (with the put-down time rounded down to the step) and hashes it with the spec, the size, the font file and the photo, which boils it down to a short code. An unchanged fingerprint skips the render, the encode and the write, and the page says the file was left as it is.
- **Sleep now** (T2) renders with the exact minute, writes, then sends `input keyevent KEYCODE_SLEEP` through Shizuku.
- The put-down time is phrased honestly: "Put down at 10:45" when it is exact (*Sleep now* or the 1-minute step), and "Put down around 10:45" when it is rounded down to the step.
- Renders run on `Dispatchers.Default`, one at a time. The full-size bitmap and the decoded photo are reused across a burst of renders and released a minute after the last. The status line shows the last publish with its render and encode times, size, file and mode, or that it was skipped.
- In Sleep image mode a refresh only writes the file. The broadcast is sent on Apply and when the path changes. In Over Transparent mode every refresh that changes the face writes the sticker. That goes either through Shizuku, which briefly starts the helper process (about 66 MB, released after 45 s), or through BooxUltimatum's own granted access to the picture, which needs no helper at all. That is why the mode is worth a longer step when it goes through the shell.

## While asleep: live updates (verified on NA6C FW 4.3, 2026-09-27, on battery)

Opt-in, off by default, and Sleep image mode only. The face is redrawn every 1, 5, 10, 15, 30 or 60 minutes while the tablet sleeps. On the Sleep page this is the *While asleep* card, with its *Turn on* button and its *Update every* step.

**What makes it possible:**

- **Boox's refresh broadcast.** `OnyxDaydreamService` registers an exported receiver for `onyx_dream_refresh`, so any app can send it. The dream then takes a wake lock (a request that keeps the processor awake), calls `stopDozing()` (the display turns on, sent as `onyx.action.DISPLAY_CHANGED_STATE` with `state` 2), refreshes its own views and calls `startDozing()` about 1.2 s later (state 3). Frames drawn while the dream dozes are held until the display is on.
- **An accessibility overlay.** An accessibility service is an Android service meant for assistive tools, and it may draw an overlay, a window above all others. This overlay is the only window the lock screen doesn't hide: `WindowState.canBeHiddenByKeyguard` exempts it. App overlays stay hidden even with `FLAG_SHOW_WHEN_LOCKED`. The overlay sits above the dream, so it also covers Boox's charging bar and its clock.
- **Boox's power manager.** It clears wake-up alarms at sleep unless the app is on its full-access list. An app joins that list when its `RUN_ANY_IN_BACKGROUND` op (an Android per-app switch) changes to allowed.

**How it runs:**

- `core/sleep/LiveSleepService.kt` is the accessibility service. It subscribes to one rare event type it ignores, can't read window content, and adds its overlay only while the screen is off.
- On `SCREEN_OFF`, the first update runs 4 s after Boox has drawn and dozed. After that, an exact `RTC_WAKEUP` alarm (one that fires on time and wakes the tablet) fires on each round multiple of the step (`LiveSleep.schedule`). The step in force is your choice, except on battery, where nothing shorter than 5 minutes runs (`LiveSleep.effectiveStep`). The one-minute step is kept for charging, where its measured 1.6 mAh an hour costs nothing that matters. Each update:
  1. takes a partial wake lock (it keeps the processor awake, not the screen) for up to 4 s. That was measured against a display that dozes again 1.2–1.6 s after the refresh.
  2. renders the face at screen size with the put-down moment pinned (`SleepStudio.renderLive`).
  3. sends `onyx_dream_refresh`.
  4. shows the face when Boox reports state 2, or after 3 s if it never does.
  5. repaints everything (`SurfaceInk.repaintEverything`) on every sixth update to clear e-ink traces, as Boox's own dream does.
- On `SCREEN_ON`, the overlay is removed at once, so the lock screen and the PIN pad are never covered, and the alarm is cancelled.
- Drawing first and refreshing 150 ms later reached the screen while plugged in but not on battery. That's why the face waits for Boox's display-on.

**What it needs, as the checklist on the Sleep page (*What live updates need*) shows:**

| Need | Why | How to allow it |
|---|---|---|
| The accessibility service | The only window over the lock screen | Android's accessibility settings (T0, *Open settings* on the page), or *Turn on for me* with `WRITE_SECURE_SETTINGS` (T1). The previous list is journalled, and *Turn it off* takes only BooxUltimatum's own service out. |
| Background use | Boox clears the alarms of restricted apps | Android's battery page (T0, *Open battery page*). *Register with Boox* (T2) toggles the op to put BooxUltimatum on Boox's full-access list. |
| Exact alarms | Updates on the minute | `USE_EXACT_ALARM`, granted with BooxUltimatum (*Allow* on the page if it isn't) |

**Why Transparent can't have this yet.** A rewritten sticker is only read at the next sleep (Boox's refresh does not reload it, above), so live updates in Over Transparent mode can't go through the sticker at all. The one route left is the overlay this section already uses, drawn with only the plate on it (`PixelFormat.TRANSLUCENT`, the rest transparent) over Boox's own snapshot, which needs no sticker write and no Shizuku. It is not built. Two things need a real sleep on battery to judge: the double plate it would show until the sticker is blanked, and e-ink ghosting (faint traces of earlier pictures) under a mostly-transparent overlay. It is recorded as an open item rather than guessed at.

**Status on the page:** after each sleep it shows the number of updates and the time of the last one. A sleep long enough for two updates that got none is flagged as missed, with the fix.

**Options:** *Only while charging*, which means no updates on battery, so nothing wakes the screen. And *Not at night*, which skips updates between two hours you set (*Pause from* and *Resume at*: 23:00 and 07:00 until 0.9.2, when the hours became steppers on the Sleep page). **Quiet hours say so:** a live face whose next update would land in them carries "Paused until 07:00" at its top left edge (`SleepData.pausedUntil`, drawn by `SleepPaused`), so a picture that stands still all night reads as meant. The first frame of a sleep is drawn in quiet hours too (one wake, `LiveSleepService.update(first = true)`), which is what puts the note on the screen and covers Boox's charging bar. **The bar at the start of a charging sleep:** this was measured on 2026-10-12. Boox's dream draws its picture and bar about 0.9 s after the sleep, and BooxUltimatum's face landed 6.9 s after it (4 s delay, render and refresh). Since 0.9.2 the overlay carries the published picture straight away when the tablet is charging (`SleepStudio.coverBitmap`, decoded from the cached copy, then replaced by the fresh face as before). How long the bar now shows is *[verify]*.

**Measured on the maintainer's Note Air6 C (2026-10-04, a 32 h charge cycle on battery):** the one-minute step woke the display 1 384 times, which is 98.7 % of every display wake on the tablet. It cost about 1.6 mAh an hour: 50.8 mAh of display power billed to BooxUltimatum, 38 mAh a day. A five-minute step is a fifth of that. The wake lock was held for a mean of 6.68 s per tick against a display that dozes 1.2–1.6 s after it: 1 392 holds, 2 h 35 m, 98.6 % of all partial wake-lock time on the tablet. Since 0.7.2 the one-minute step therefore runs only while charging, and the hold is 4 s. **Still to measure:** the same cost for the 10, 15, 30 and 60-minute steps, and whether Boox's full-access list survives a reboot *[verify]*.

**Faces:** twelve faces are made for live updates (see *Faces* below). They read the time now, the time asleep, the battery used and its rate, the next event counted down, the next alarm, and say when they were last drawn. Without live updates they show the moment the tablet was put down and say so.

## Faces

Twenty-one full-screen faces for Sleep image, each with its own portrait and landscape composition, and four plates for Over Transparent. The picker splits the faces into two groups with two buttons: *12 live faces* (made for updates while asleep, each with a small *Live* label on its thumbnail) and *9 still faces*. The picker opens on the selected face's group, and says so if the selected face is in the other group. Tapping the preview shows the face at the screen's full size, and the page then reports how long that full-size render took.

**Live faces.** Each reads the time now, keeps to the studio's typesetting rules and ends on the honest foot line ("Updated 10:25 · every 5 min", or "Put down around 10:25" without live updates, with the lamp dark):

- **Dial:** a wall clock with the date, the battery, the time asleep and the next event.
- **Clock:** the time set as large as the screen allows (measured with the tabular figures it's set in, digits that are all the same width). The readings sit in columns along the foot in landscape, each as wide as it needs. In portrait, where three columns don't fit, they are ruled rows.
- **Monitor:** a readout of the sleep itself: how long, the battery on a tuning scale with its use and rate, the next event and the alarm. In landscape the readings grow to fill their column.
- **Cube:** a cube desk clock in true perspective (a 3D projection with a camera, not an isometric trick). The time is on the side facing you, the date on the side in shade in solid ink, a round accent button on the top, and there is a cast shadow. The top and the shadow are shaded with ink patterns, stipple or hatching, never soft greys. Text is drawn through `Matrix.setPolyToPoly`, so it lies on the cube's sides in perspective.
- **Split-flap:** hours and minutes on split-flap cards, the stack of flaps behind, the upper flap leaning back on its hinge (a slight perspective on its top half), a dark cut through the figures at the split and two accent hinge pins. The weekday, day, month and AM or PM are on small flaps.
- **Dashboard:** a grid of instrument tiles, three columns in portrait and four in landscape. The time and the date, with the week, always show. Then come the battery with a gauge and what it has used, the time asleep, the alarm, the next three events, how far through the day, the moon, the home screen's weather and how far through the year. A row left short widens its last tile, so the grid has no holes, and a grid of few tiles sits in the middle of the page. Small tiles share one value size per row.
- **Word clock:** the time in words in a grid of letters (BooxUltimatum's own layout, English), rounded down to the five minutes below, with up to four accent dots for the minutes in between. Unlit letters are fine outlines, never grey text. *Words alone* sets the same words as a poster instead.
- **Day ring:** the whole day on one 24-hour ring: today's events as arcs, the night in stipple on the outer band (for the weather city), the sleep so far as an accent arc, now as a hand with a paper halo, and the time in the middle.
- **Timeline:** today along a line, vertical in portrait and horizontal in landscape. Events are blocks in up to three lanes (labels above the bars in landscape, on a paper knock-out), the night is in a light stipple, the sleep in the accent, and now is a marker drawn under the events so it never cuts their words. Events from the coming days join when the span runs past midnight.
- **LCD:** a Braun-style LCD clock: a black body, a paper window with seven-segment figures drawn as bevelled polygons (not a font), optional ghosts of the unlit segments, small fields for the date (in the locale's day and month order), time asleep, battery and alarm, and a row of buttons with one in the accent.
- **Sky:** the sun's height through today for the weather city, with the daylight in stipple above the horizon and the night drawn shallower below it, the sun where it is now (in the accent when it's up), sunrise, sunset and day length. It also shows the moon as it looks tonight, how much of it is lit, and the next full or new moon. Without a weather city the sun follows the clock on a plain curve and the face says "Sun by the clock".
- **Broadsheet:** a front page: the masthead (*The Daily Standby*) with the weather and battery in its ears, a dateline with the issue number (the day of the year) and the edition time, the next event as the headline (or the quote), and columns for the sleep, what's ahead and the almanac (moon, daylight, week), with the thought for the day when the headline is an event.

**Still faces:**

- **Almanac:** weekday and year, a big day number, the month, put-down time and battery beside it, the month grid with today in the accent and event days dotted, and the next events.
- **Year:** the year at a glance, every day a dot: the past filled, today larger in the accent, the rest as rims. It shows the year, the share of it already past, the day and week numbers and the days left. The layout is months (twelve small grids), a row a month, or weeks (a strip, split in two in portrait).
- **Instrument:** a Braun tuning scale for the battery with the figure riding an accent needle, the date, a lamp lit while charging, and ruled readings (put down, next event, your name).
- **Poster:** the weekday set as large as the screen allows over an accent bar, then the quote of the day (a small bundled public-domain set) or your own, or the day number when the quote is off.
- **Under the clock:** composed around the Boox clock: a rule and a lamp under it, then the date, a battery ring, readings, events, note and your name.
- **Photo:** your picture (picked with the photo picker, EXIF rotation applied, stored in BooxUltimatum's files), filled or fitted (*Fill the screen* or *Show it whole*), optionally pre-dithered (*Pre-dither*: Floyd–Steinberg, 16 levels per channel, on the photo only), with an optional caption band along the bottom. Photos without dither go out as JPEG. Everything else is PNG, falling back to JPEG above 9.5 MB.
- **Note:** your note set as large as it fits.
- **Return card:** "If found, please return to", your name, contact and reward line.
- **Minimal:** the date and a small accent-coloured lamp.
- **Plates** for Over Transparent: *Bottom band*, *Top band*, *Corner card* and *Centre card*, on a transparent sheet with a soft paper edge and an ink rim. Every plate is drawn inside the square that survives Boox's centre-crop in either rotation (`SleepPage.safe()`, `SleepCrop`), inset by the reach of its own soft edge, so one sticker file is right whichever way the tablet sleeps. A plate that would not fit drops its most optional parts (quote, note, your name, events) first.

**Leave room for the Boox clock** keeps the top 22 % free on any face, for anyone who keeps a live Boox clock. The guidance on the page asks for Clock style and Text display set to None and the bottom status bar off for a clean face. To keep a live time instead, it suggests leaving Clock style on Digital and turning on *Leave room for the Boox clock*, or choosing *Under the clock*.

Type follows your choice under *Font*. The default is *Tablet font*: the Boox system font file, copied once through Shizuku if it can't be read directly, and instanced by `wght` when it is variable (a variable font holds many weights in one file, and `wght` picks one). The other choices are the bundled *Archivo* and any Google font family you keep on the Fonts page (its real weight files). Large type is Semibold to Black, small type Regular to Semibold, and nothing thinner is offered. Ink is black on paper or white on black. The accent (green, red, blue, orange, yellow or ink) only fills shapes, never text. The new faces were reviewed at full size in both inks.

### Options that belong to one face

`SleepFaceSpec.faceOptions` is a map from a key such as `"dial.style"` to a value, stored in the spec's JSON (version 3) and sorted so the fingerprint is stable. `FaceOptions` declares each option once: its key, its label, its values with their labels, its default, whether it holds a set (a *multi* option, stored comma-separated in declaration order), and an optional note. A missing key or a value no longer offered falls back to the default, so older specs keep working. The faces read `spec.option(...)` and `spec.optionSet(...)`. The Sleep page shows the selected face's options in a card titled with the face's name and *options*, right under the picker. A choice is a row of buttons, and a multi option is buttons that switch on and off.

| Face | Options |
|---|---|
| Dial | Style: Braun, railway, all numerals, 24-hour (the hour hand goes round once a day, 24 at the top) |
| Clock, Cube, Split-flap, Dashboard, LCD | Hours: same as the tablet, 12-hour or 24-hour (LCD and Split-flap set 24-hour hours on two digits) |
| Cube | Shading: stipple or hatching |
| Dashboard | Tiles: battery, time asleep, alarm, next events, day, moon, weather, year |
| Word clock | Style: letter grid or words alone; minute dots on or off |
| Day ring | At the top: noon or midnight; shade the night on or off |
| Timeline | Span: around now (two hours back, eight ahead), 6:00 to midnight, or the whole day |
| LCD | Figures slanted or upright; unlit segments on or off |
| Broadsheet | Headline: the next event or the quote |
| Year | Layout: months, a row a month, or weeks |

Nothing offers a seconds display: nothing on e-ink should pretend to tick.

### What the new faces read

- **Moon:** worked out locally from Meeus' low-precision phase angle (*Astronomical Algorithms*, ch. 48), about a percent in the lit fraction, at the moment the face shows. The next full or new moon is found by stepping two hours at a time. Checked for 27 September 2026: full moon 26 September 16:49 UTC and new moon 10 October, as published.
- **Sun:** NOAA's fractional-year series for declination and the equation of time, for the city chosen for the home screen's weather. That is the only place BooxUltimatum knows, and it never uses the GPS. Checked for Ho Chi Minh City on 27 September 2026: sunrise 5:42, sunset 17:46, 12 h 4 min, as published. Times are shown in the tablet's time zone.
- **Weather:** the home screen's Open-Meteo cache (`launcher_weather`), read and never fetched from the sleep path. The current reading is shown only for three hours, and today's forecast (high, low, sky) while the cache holds today. Nothing is shown when the cache is over three days old. The reading's age is always on the face ("As of 8:10", "As of Sat 1:43").
- **Today's events**, the ones already over included, for the Day ring, Timeline and Broadsheet. All-day events are kept to today by their UTC date. Nothing is claimed without calendar access: the event parts are left out, never replaced by "nothing planned".
- **Week number** by the locale's own counting, the day of the year and the days left.
- Only the faces that show them read the sky, the weather and today's events (`SleepFace.readsSky`, `drawsDay`), so the other faces' fingerprints don't change when they do.

Full-size renders of the new faces took 14 to 44 ms on the maintainer's Note Air6 C (Split-flap the slowest), measured with the preview's full-size view.

**Power-off screen.** *Use as power-off screen* sets the chosen face as the picture shown while the tablet is off, in portrait, without the battery, the put-down time or the time asleep. It needs Sleep image mode. Boox keeps a copy, so it stays as it was set. A live face would stop its clock at that moment, so every live face hands over to the Almanac there. The Year and the other still faces are used as they are. After you change the face, *Update power-off screen* sets it again, and *Restore Boox power-off screen* brings the Boox picture back.

## Code

- `core/sleep/SleepSpec.kt`: modes, faces, plates, elements, the serialisable `SleepFaceSpec` (with `faceOptions`), `SleepStore` and `SleepStatus`.
- `core/sleep/SleepFaceOptions.kt`: `FaceOption`, `FaceChoice` and `FaceOptions`, every face's own options declared once.
- `core/sleep/SleepData.kt`: what a face shows, in display form, and its fingerprint; `SleepLive` (the live readings), `SleepWords` (the newer faces' words and templates), today's events, the weather cache and the sky.
- `core/sleep/SleepSky.kt`: `SleepSky`, `SleepWeather` and `Astro`, the local moon and sun arithmetic.
- `core/sleep/SleepRenderer.kt` and `SleepFaces.kt`: pure Canvas typesetting (cap-height alignment, fitted blocks, the scale, ring, grid and plates) and the dither. They use no Context and no I/O, so a face renders the same at full size, as a preview and as a thumbnail.
- `core/sleep/LiveFaces.kt`: Dial, Clock and Monitor. `CraftFaces.kt`: Cube, Split-flap, LCD and Word clock. `DayFaces.kt`: Dashboard, Day ring, Timeline, Year, Sky and Broadsheet. `FaceKit.kt`: what they share (the time in a face's hours, the masthead, ink patterns, the moon disc, gauges, readings as rows, columns or a stack).
- `core/sleep/SleepFonts.kt`: fonts at any weight.
- `core/sleep/SleepPublisher.kt`: MediaStore pictures, the broadcast, the sticker copy and its backup, and the Boox settings link.
- `core/sleep/SleepStudio.kt`: render, skip, encode, publish, apply, Sleep now, restore.
- `core/sleep/SleepScheduler.kt`: the alarm, the receivers (registered from `BooxUltimatumApplication` in the main process) and `SleepReceiver`.
- `core/sleep/LiveSleep.kt` and `LiveSleepService.kt`: live updates while asleep, their preferences, the checklist readings and the accessibility service that owns the overlay.
- `ui/screens/SleepScreen.kt`: the studio. There are two panes in landscape. In portrait the preview and the action buttons sit on top. The face picker has two groups with the selected face's options under it, the preview opens full size on a tap, and text entry goes in a card at the top.

## Still to verify on the tablet

- The type 16 broadcast sent from BooxUltimatum's own uid, without Shizuku.
- MediaStore overwrites with `openOutputStream(uri, "wt")` picked up at the next sleep.
- The Boox clock zone used for the layout guide (top 22 %).
- Encode times at full size for each face, and the PNG size of a pre-dithered photo.
- The explicit `DREAM_STYLE_SETTING` launch opening the style page.
- **A real sleep with each of the four plates, in both orientations** *[verify]*: the centre-crop arithmetic above is read from the decompiled `com.onyx` and checked against emulator renders (`SleepOverlayTest`), never against a sleeping tablet.
- **Over Transparent without Shizuku** *[verify]*: whether the write consent `MediaStore.createWriteRequest` grants survives a reboot, whether Boox re-reads a sticker changed through the media provider, and whether the picture permission alone finds the newest sticker row on FW 4.3.
- **A re-pick in Boox while the studio is on** *[verify]*: BooxUltimatum should follow the new `sticker_*.png`, put the old one back and say which file it is writing.
- **Restore after a failed sticker write** *[verify]*: it should leave the studio on and say why, with the plate still showing.
- A real sleep on battery with each of the nine newer live faces *[verify]*: they use the same live pipeline as Dial, Clock and Monitor, but haven't been through a sleep yet.
- How the stipple, hatching and outlined letters look on the Kaleido screen itself *[verify]*: Kaleido is the colour e-ink layer. They were reviewed on screenshots and full-size renders, which the screen's own dithering can change.
