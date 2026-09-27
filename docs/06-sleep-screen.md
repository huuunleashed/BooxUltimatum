# 06 · Sleep screen studio

Goal: a sleep screen worth looking at. The owner picks a face, sees it exactly as the panel will show it, and BooxUltimatum keeps it current while the tablet is in use, without adding a single wakeup.

## How Boox draws the sleep screen (verified on NA6C FW 4.3, 2026-09-26)

- The sleep screen is a doze dream, `com.onyx/com.onyx.common.dream.OnyxDaydreamService`. Its style (Image, Transparent and several presets) and its pictures live in `com.onyx`'s private MMKV, which no other app can read or write.
- **Image style.** Broadcasting `onyx.action.SCREENSAVER` with `type` = 16 (int), `file` = an absolute path (string) and `show_result_hint` = false (boolean) switches the style to Image with that file as its only picture. The receiver is dynamic, exported and needs no permission, and lives in the always-running `com.onyx` process. Verified from the shell uid; the app uid is expected to work the same way and is still to be confirmed on the tablet.
- A file directly in `/sdcard/Pictures/` is used in place. A file anywhere else is copied into `Pictures/` once, and later overwrites of the original are ignored.
- The file is decoded afresh at every sleep, so overwriting it between sleeps changes the next sleep screen. It is not read again while the tablet sleeps. Files above 10 MB are dropped and Boox falls back to its default.
- Boox scales the picture with centre-crop to the current rotation: 1860 × 2480 in portrait, 2480 × 1860 at rotation 90 or 270 (logcat).
- Boox draws its own overlay on top of any style: a clock (Digital, LED, Analog or None, redrawn every 5 minutes while asleep), a motto (Text display), and a bottom status bar with the battery level and "Press power button to wake up". These are set in Boox Settings › Desktop & Screensaver › Screensaver.
- Boox dithers the picture itself; a normal 24-bit PNG is the right input.
- **Transparent style.** It snapshots the screen at sleep and can lay a Lockscreen Sticker (a PNG with alpha, scaled "centre and fill") over it. The user picks the sticker once in Boox (Transparent › Show lockscreen sticker › Select sticker › + › an album › a picture › OK), and Boox saves the result as `/sdcard/Pictures/Sticker/sticker_<yyyy-MM-dd_HH_mm_ss_SSS>.png` (1860 × 2480 ARGB, owned by the media provider). That file is read again at every sleep, so overwriting it updates the overlay. A portrait sticker is centre-cropped in landscape, losing its top and bottom bands.
- With a PIN, a tablet put to sleep from the lock screen shows a black privacy screen. That is Boox's behaviour and outside our control.
- The screensaver style page is `com.onyx/.common.setting.ui.SettingContainerActivity` with action `onyx.settings.action.DREAM_STYLE_SETTING` (and `onyx.settings.action.LAUNCHER_SCREENSAVER_SETTING` for the Desktop & Screensaver page). The activity is exported and maps these actions to its pages in code, but its intent filter does not list them, so the component is named explicitly. The app falls back to Boox Settings.

## Modes and tiers

| Mode | Tier | Mechanism | Revert |
|---|---|---|---|
| Sleep image | T0 (T2 repeats the broadcast) | Our picture in `Pictures/booxultimatum-sleep.png` through MediaStore, then the type 16 broadcast from the app and, with Shizuku, from the shell | Type 16 with `/system/media/standby-1.png`, our pictures deleted, refreshes stopped |
| Over Transparent | T2 | Our overlay staged in the app's external files, then `cp` over the newest `sticker_*.png` through Shizuku; Boox's own sticker is backed up first and journaled | The backup copied back over the sticker, then the same type 16 restore |

The previous style can't be read back, so Restore sets the Boox default picture and the screen offers Boox's screensaver settings next to it, to pick the old style again. Both apply and restore are journaled with a plain sentence.

For the one-time sticker pick, *Put in Sticker album* writes our overlay to `Pictures/Sticker/booxultimatum.png` through MediaStore (our own picture, so T0), where the Boox picker finds it. The screen lists the Boox steps.

## Keeping it current without waking the tablet

On its own, nothing on the picture changes while the tablet sleeps, because Boox reads it once when the tablet goes to sleep. The only thing Boox itself redraws is its clock. So by default the face is kept current while the tablet is awake, and only then; the opt-in live updates in the next section go further.

- **Refresh alarm.** `setInexactRepeating` on `ELAPSED_REALTIME` (not the wakeup variant) at the chosen step: 1, 5 (default), 15 or 30 minutes. It never fires while asleep; a missed one is delivered on wake. An armed alarm is left alone, so opening the app never pushes the next refresh away; a changed step re-arms it.
- **Events the system sends anyway.** Battery level or plug changes (at most one render a minute), time, date and time-zone changes, screen on, and a calendar content observer once READ_CALENDAR is granted. Triggers are coalesced for two seconds, and edits in the studio request a refresh.
- **Both orientations, ready ahead.** Boox centre-crops the picture to the rotation the tablet sleeps in, so a face rendered only after a turn left a few seconds (1.5 s wait, the coalescing, render and PNG encode) in which a sleep showed the old shape cropped; a cover closing or the tablet being picked up can also flip it in the last moment. Every refresh therefore also renders the other orientation and keeps it encoded in `noBackupFilesDir/sleep-cache`, tagged with the face settings it came from. A rotation, seen through a display listener (which fires before configuration reaches the process), swaps that file in at once, and a fresh render follows 1.5 s later. A render that finishes after the tablet turned is kept as the cache instead of being published. Portrait and landscape have the same pixel count, so the full-size sheet is reconfigured rather than allocated twice. Cost: one more render and encode per refresh, only while awake.
- **Last check before sleep.** The framework broadcasts `com.onyx.action.ONYX_SYSTEM_GOING_TO_SLEEP` tens of milliseconds before Onyx decodes the picture; it reaches the app (verified: our picture was written 100 ms after it and 260 ms before the dream decoded it). If the published picture doesn't match the rotation then, the ready one is written straight away; because that write can meet Onyx's read, the style switch is sent again on the next wake in case Onyx dropped the file.
- **Boox's bottom bar.** Boox draws a status bar (battery, "Press power button to wake up") along the bottom unless Screensaver › *Bottom Status Bar* is off, and always while charging (`BaseStyleDream.getStatusBarVisibility()`). Faces don't reserve its strip: the margins keep the bottom clear, and the studio asks owners to turn the bar off.
- **Fingerprint.** Each render first gathers what the face shows (with the put-down time rounded down to the step) and hashes it with the spec, the size, the font file and the photo. An unchanged fingerprint skips the render, the encode and the write.
- **Sleep now** (T2) renders with the exact minute, writes, then sends `input keyevent KEYCODE_SLEEP` through Shizuku.
- The put-down time is phrased honestly: "Put down at 10:45" when it is exact (Sleep now or the 1-minute step), "Put down around 10:45" when it is rounded down to the step.
- Renders run on `Dispatchers.Default`, one at a time. The full-size bitmap and the decoded photo are reused across a burst of renders and released a minute after the last. The status line shows the last publish with its render and encode times, size, file and mode, or that it was skipped.
- In Sleep image mode a refresh only writes the file; the broadcast is sent on Apply and when the path changes. In Over Transparent mode every refresh that changes the face briefly starts the Shizuku helper (about 66 MB, released after 45 s), which is why that mode is T2 and worth a longer step.

## While asleep: live updates (verified on NA6C FW 4.3, 2026-09-27, on battery)

Opt-in, off by default, and Sleep image mode only. The face is redrawn every 5, 10, 15, 30 or 60 minutes while the tablet sleeps.

**What makes it possible:**

- **Onyx's refresh broadcast.** `OnyxDaydreamService` registers an exported receiver for `onyx_dream_refresh`, so any app can send it. The dream then takes a wake lock, calls `stopDozing()` (the display turns on, sent as `onyx.action.DISPLAY_CHANGED_STATE` with `state` 2), refreshes its own views and calls `startDozing()` about 1.2 s later (state 3). Frames drawn while the dream dozes are held until the display is on.
- **An accessibility overlay.** It's the only window the lock screen doesn't hide: `WindowState.canBeHiddenByKeyguard` exempts it. App overlays stay hidden even with `FLAG_SHOW_WHEN_LOCKED`. The overlay sits above the dream, so it also covers Boox's charging bar and its clock.
- **Onyx's power manager.** It clears wake-up alarms at sleep unless the app is on its full-access list, which an app joins when its `RUN_ANY_IN_BACKGROUND` op changes to allowed.

**How it runs:**

- `core/sleep/LiveSleepService.kt` is the accessibility service. It subscribes to one rare event type it ignores, can't read window content, and adds its overlay only while the screen is off.
- On `SCREEN_OFF`, the first update runs 4 s after Onyx has drawn and dozed. After that, an exact `RTC_WAKEUP` alarm fires on each round multiple of the step (`LiveSleep.schedule`). Each update:
  1. takes a partial wake lock for up to 8 s;
  2. renders the face at panel size with the put-down moment pinned (`SleepStudio.renderLive`);
  3. sends `onyx_dream_refresh`;
  4. shows the face when Onyx reports state 2, or after 3 s if it never does;
  5. repaints everything (`SurfaceInk.repaintEverything`) on every sixth update to clear e-ink traces, as Onyx's own dream does.
- On `SCREEN_ON`, the overlay is removed at once, so the lock screen and the PIN pad are never covered, and the alarm is cancelled.
- Drawing first and refreshing 150 ms later reached the panel while plugged in but not on battery. That's why the face waits for Onyx's display-on.

**What it needs, as the Sleep page's checklist shows:**

| Need | Why | How to allow it |
|---|---|---|
| The accessibility service | The only window over the lock screen | Android's accessibility settings (T0), or *Turn on for me* with `WRITE_SECURE_SETTINGS` (T1). The previous list is journalled, and *Turn it off* takes only ours out. |
| Background use | Boox clears the alarms of restricted apps | Android's battery page (T0). *Register with Boox* (T2) toggles the op to put the app on Boox's full-access list. |
| Exact alarms | Updates on the minute | `USE_EXACT_ALARM`, granted with the app |

**Status on the page:** after each sleep it shows the number of updates and the time of the last one. A sleep long enough for two updates that got none is flagged as missed, with the fix.

**Options:** only while charging, which means no wake-ups on battery at all, and not at night, which skips updates from 23:00 to 07:00.

**Still to measure:** the battery cost on battery, for each step *[verify]*, and whether Boox's full-access list survives a reboot *[verify]*.

**Faces:** twelve faces are made for live updates (see *Faces* below). They read the time now, the time asleep, the battery used and its rate, the next event counted down, the next alarm, and say when they were last drawn. Without live updates they show the moment the tablet was put down and say so.

## Faces

Twenty-one full-screen faces for Sleep image, each with its own portrait and landscape composition, and four plates for Over Transparent. The picker splits them into two tabs, *Live faces* (twelve, made for updates while asleep, marked with a *Live* tab) and *Still faces* (nine); the tab opens on the selected face's group. Tapping the preview shows the face at the panel's full size, and the page then reports how long that full-size render took.

**Live faces.** Each reads the time now, keeps to the studio grammar and ends on the honest foot line ("Updated 10:25 · every 5 min", or "Put down around 10:25" without live updates, with the lamp dark):

- **Dial:** a wall clock with the date, the battery, the time asleep and the next event.
- **Clock:** the time set as large as the panel allows (measured with the tabular figures it's set in); the readings in columns along the foot in landscape, each as wide as it needs, and as ruled rows in portrait, where three columns don't fit.
- **Monitor:** a readout of the sleep itself: how long, the battery on a tuning scale with its use and rate, the next event and the alarm. In landscape the readings grow to fill their column.
- **Cube:** a cube desk clock in true perspective (a 3D projection with a camera, not an isometric trick): the time on the side facing you, the date on the side in shade in solid ink, a round accent button on the top, and a cast shadow. The top and the shadow are shaded with ink patterns, stipple or hatching, never soft greys. Text is drawn through `Matrix.setPolyToPoly`, so it lies on the faces in perspective.
- **Split-flap:** hours and minutes on split-flap cards, the stack of flaps behind, the upper flap leaning back on its hinge (a slight perspective on its top half), a dark cut through the figures at the split and two accent hinge pins; the weekday, day, month and AM or PM on small flaps.
- **Dashboard:** a grid of instrument tiles, three columns in portrait and four in landscape: the time and the date (always), then the battery with a gauge and what it has used, the time asleep, the alarm, the next three events, how far through the day, the moon, the home screen's weather and how far through the year. A row left short widens its last tile, so the grid has no holes, and a grid of few tiles sits in the middle of the page. Small tiles share one value size per row.
- **Word clock:** the time in words in a grid of letters (our own layout, English), to the five minutes below, with up to four accent dots for the minutes in between. Unlit letters are fine outlines, never grey text; *Words alone* sets the same words as a poster instead.
- **Day ring:** the whole day on one 24-hour ring: today's events as arcs, the night in stipple on the outer band (for the weather city), the sleep so far as an accent arc, now as a hand with a paper halo, the time in the middle.
- **Timeline:** today along a line, vertical in portrait and horizontal in landscape: events as blocks in up to three lanes (labels above the bars in landscape, on a paper knock-out), the night in a light stipple, the sleep in the accent, now as a marker drawn under the events so it never cuts their words. Events from the coming days join when the span runs past midnight.
- **LCD:** a Braun-style LCD clock: a black body, a paper window with seven-segment figures drawn as bevelled polygons (not a font), optional ghosts of the unlit segments, small fields for the date (in the locale's day and month order), time asleep, battery and alarm, and a row of keys with one in the accent.
- **Sky:** the sun's height through today for the weather city, with the daylight in stipple above the horizon and the night drawn shallower below it, the sun where it is now (in the accent when it's up), sunrise, sunset and day length; the moon as it looks tonight, how much of it is lit, and the next full or new moon. Without a weather city the sun follows the clock on a plain curve and the face says "Sun by the clock".
- **Broadsheet:** a front page: the masthead (*The Daily Standby*) with the weather and battery in its ears, a dateline with the issue number (the day of the year) and the edition time, the next event as the headline (or the quote), and columns for the sleep, what's ahead and the almanac (moon, daylight, week), with the thought for the day when the headline is an event.

**Still faces:**

- **Almanac:** weekday and year, a big day number, the month, put-down time and battery beside it, the month grid with today in the accent and event days dotted, and the next events.
- **Year:** the year at a glance, every day a dot: the past filled, today larger in the accent, the rest as rims; the year, the share of it already past, the day and week numbers and the days left. Months (twelve small grids), a row a month, or weeks (a strip, split in two in portrait).
- **Instrument:** a Braun tuning scale for the battery with the figure riding an accent needle, the date, a lamp lit while charging, and ruled readings (put down, next event, owner).
- **Poster:** the weekday set as large as the panel allows over an accent bar, then the quote of the day (a small bundled public-domain set) or the user's own, or the day number when the quote is off.
- **Under the clock:** composed around the Boox clock: a rule and a lamp under it, then the date, a battery ring, readings, events, note and owner.
- **Photo:** the user's picture (picked with the photo picker, EXIF rotation applied, stored in app files), filled or fitted, optionally pre-dithered (Floyd–Steinberg, 16 levels per channel, on the photo only), with an optional caption band along the bottom. Photos without dither go out as JPEG; everything else is PNG, falling back to JPEG above 9.5 MB.
- **Note:** the user's note set as large as it fits.
- **Return card:** "If found, please return to", the owner's name, contact and reward line.
- **Minimal:** the date and the lamp.
- **Plates** for Over Transparent: bottom band, top band, corner card and centre card, on a transparent sheet with a soft paper edge and an ink rim. Each is measured before it is painted; a card that would not fit drops its most optional parts (quote, note, owner, events) first.

**Leave room for the Boox clock** keeps the top 22 % free on any face for users who keep a live clock. The guidance on screen asks for Clock style and Text display set to None and the bottom status bar off for a clean face.

Type follows the owner's choice: the tablet font by default (the Boox system font file, copied once through Shizuku if it can't be read directly, instanced by `wght` when it is variable), bundled Archivo, or any kept Google family (its real weight files). Large type is Semibold to Black, small type Regular to Semibold; nothing thinner is offered. Ink is black on paper or white on black; the accent (green, red, blue, orange, yellow or ink) only fills shapes, never text. The new faces were reviewed at full size in both inks.

### Options that belong to one face

`SleepFaceSpec.faceOptions` is a map from a key such as `"dial.style"` to a value, stored in the spec's JSON (version 3) and sorted so the fingerprint is stable. `FaceOptions` declares each option once: its key, its label, its values with their labels, its default, whether it holds a set (a *multi* option, stored comma-separated in declaration order), and an optional note. A missing key or a value no longer offered falls back to the default, so older specs keep working; the faces read `spec.option(...)` and `spec.optionSet(...)`. The Sleep page shows the selected face's options as a plate right under the picker: a choice as a row of keys, a multi option as keys that switch on and off.

| Face | Options |
|---|---|
| Dial | Style: Braun, railway, all numerals, 24-hour (the hour hand goes round once a day, 24 at the top) |
| Clock, Cube, Split-flap, Dashboard, LCD | Hours: as the tablet, 12-hour or 24-hour (LCD and Split-flap set 24-hour hours on two digits) |
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
- **Sun:** NOAA's fractional-year series for declination and the equation of time, for the city chosen for the home screen's weather: the only place the app knows, never the GPS. Checked for Ho Chi Minh City on 27 September 2026: sunrise 5:42, sunset 17:46, 12 h 4 min, as published. Times are shown in the tablet's time zone.
- **Weather:** the home screen's Open-Meteo cache (`launcher_weather`), read and never fetched from the sleep path. The current reading is shown only for three hours; today's forecast (high, low, sky) while the cache holds today; nothing when the cache is over three days old. The reading's age is always on the face ("As of 8:10", "As of Sat 1:43").
- **Today's events**, the ones already over included, for the Day ring, Timeline and Broadsheet; all-day events are kept to today by their UTC date. Nothing is claimed without calendar access: the event parts are left out, never replaced by "nothing planned".
- **Week number** by the locale's own counting, the day of the year and the days left.
- Only the faces that show them read the sky, the weather and today's events (`SleepFace.readsSky`, `drawsDay`), so the other faces' fingerprints don't change when they do.

Full-size renders of the new faces took 14 to 44 ms on the tablet (Split-flap the slowest), measured with the preview's full-size view.

**Power-off screen.** *Use as power-off screen* sets the chosen face as the picture shown while the tablet is off, in portrait, without the battery, the put-down time or the time asleep. Boox keeps a copy, so it stays as it was set. A live face would stop its clock at that moment, so every live face hands over to the Almanac there; the Year and the other still faces are used as they are.

## Code

- `core/sleep/SleepSpec.kt`: modes, faces, plates, elements, the serialisable `SleepFaceSpec` (with `faceOptions`), `SleepStore` and `SleepStatus`.
- `core/sleep/SleepFaceOptions.kt`: `FaceOption`, `FaceChoice` and `FaceOptions`, every face's own options declared once.
- `core/sleep/SleepData.kt`: what a face shows, in display form, and its fingerprint; `SleepLive` (the live readings), `SleepWords` (the newer faces' words and templates), today's events, the weather cache and the sky.
- `core/sleep/SleepSky.kt`: `SleepSky`, `SleepWeather` and `Astro`, the local moon and sun arithmetic.
- `core/sleep/SleepRenderer.kt` and `SleepFaces.kt`: pure Canvas typesetting (cap-height alignment, fitted blocks, the scale, ring, grid and plates) and the dither. No Context and no I/O, so a face renders the same at full size, as a preview and as a thumbnail.
- `core/sleep/LiveFaces.kt`: Dial, Clock and Monitor. `CraftFaces.kt`: Cube, Split-flap, LCD and Word clock. `DayFaces.kt`: Dashboard, Day ring, Timeline, Year, Sky and Broadsheet. `FaceKit.kt`: what they share (the time in a face's hours, the masthead, ink patterns, the moon disc, gauges, readings as rows, columns or a stack).
- `core/sleep/SleepFonts.kt`: typefaces at any weight.
- `core/sleep/SleepPublisher.kt`: MediaStore pictures, the broadcast, the sticker copy and its backup, and the Boox settings link.
- `core/sleep/SleepStudio.kt`: render, skip, encode, publish, apply, Sleep now, restore.
- `core/sleep/SleepScheduler.kt`: the alarm, the receivers (registered from `BooxUltimatumApplication` in the main process) and `SleepReceiver`.
- `core/sleep/LiveSleep.kt` and `LiveSleepService.kt`: live updates while asleep, their preferences, the checklist readings and the accessibility service that owns the overlay.
- `ui/screens/SleepScreen.kt`: the studio. Two panes in landscape, sheet and keys on top in portrait; the face picker in two tabs with the selected face's options under it; the preview opens full size on a tap; text entry in a panel at the top.

## Still to verify on the tablet

- The type 16 broadcast sent from the app uid, without Shizuku.
- MediaStore overwrites with `openOutputStream(uri, "wt")` picked up at the next sleep.
- The Boox clock zone used for the layout guide (top 22 %).
- Encode times at full size for each face, and the PNG size of a pre-dithered photo.
- The explicit `DREAM_STYLE_SETTING` launch opening the style page.
- A real sleep on battery with each of the nine newer live faces *[verify]*: they use the same live pipeline as Dial, Clock and Monitor, but haven't been through a sleep yet.
- How the stipple, hatching and outlined letters look on the Kaleido panel itself *[verify]*: they were reviewed on screenshots and full-size renders, which the panel's own dithering can change.
