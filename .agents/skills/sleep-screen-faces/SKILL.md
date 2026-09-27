---
name: sleep-screen-faces
description: How BooxUltimatum's sleep screen faces are built, rendered, kept current and updated while asleep, and how to add or change a face. Use whenever designing, adding or fixing a sleep face or a live element, or working on the Sleep page, the live sleep screen or the power-off screen.
---

# Sleep screen faces

The design doc is [`docs/06-sleep-screen.md`](../../../docs/06-sleep-screen.md); the firmware facts are in the `boox-firmware-interfaces` skill.

## The pipeline

1. `SleepSpec.kt`: `SleepFace` (with its `options` and whether it's `live`, plus `usesCalendar`, `drawsDay` and `readsSky`), `SleepElement`, and `SleepFaceSpec`, stored as JSON with a `"v"` version (3 since per-face options) so older specs get new elements switched on and missing keys fall back to defaults in `fromJson`.
2. `SleepFaceOptions.kt`: `FaceOptions` declares each face's own settings once (key such as `"dial.style"`, label, values, default, `multi` for a set, optional note). They live in `SleepFaceSpec.faceOptions`; faces read `spec.option(o)` / `spec.optionSet(o)`, and the Sleep page shows the selected face's options right under the picker. A missing or retired value means the default.
3. `SleepData.gather(...)`: everything a face shows, already formatted. `SleepLive` carries the live readings: the time, time asleep, battery used and its rate (`used`, and `usedShort` for a line headed "Used asleep"), the next event counted down, the next alarm (`alarmAt` too), the "updated" line, and `nowMs`, `sleptAtMs`, `asleepMin` for arcs and bars. `SleepWords` holds the newer faces' words and templates (the renderer fills them with `String.format`). Faces that aren't live get `SleepLive.NONE`, so their fingerprint doesn't change every minute; only faces that need them get today's events (`day`), the weather cache and the sky.
4. `SleepRenderer`, which calls `SleepFaces` (the static faces), `LiveFaces` (Dial, Clock, Monitor), `CraftFaces` (Cube, Split-flap, LCD, Word clock) or `DayFaces` (Dashboard, Day ring, Timeline, Year, Sky, Broadsheet). `FaceKit.kt` holds what they share. It's pure Canvas with no Context and no I/O, so the same code draws the panel picture, the preview (0.5×), the full-size view (1×) and the thumbnails (0.1×).
5. `SleepStudio`: fingerprint and skip, render, encode, publish through MediaStore and the broadcast, keep both orientations ready, and the power-off picture.
6. `SleepScheduler`: refreshes while the tablet is awake only (a non-wakeup alarm plus system broadcasts).
7. `LiveSleep` and `LiveSleepService`: updates while asleep. An exact wake-up alarm fires, the face renders, `onyx_dream_refresh` is sent, and the face is drawn into the accessibility overlay once the display is on. The overlay is removed when the screen turns on.

## Typesetting grammar

- Sizes are fractions of `s`, 0.72 of the short side. Text is placed by cap height: `text(t, x, capTop, paint)` returns the baseline.
- `frame()` is the area inside the margins. Setting `dry = true` measures without drawing, for centring blocks.
- One dominant element per face, generous margins, nothing below body weight, black on paper, and one accent that fills shapes and never carries text.
- Every face has its own portrait and landscape composition; check both.
- Shared pieces live in `SleepFaces` (`legend`, `row`, `stackedRow`, `batteryLine`, `tuningScale`, `batteryRing`, `monthGrid`, `agenda`, plus the page's `lamp` and `rule`) and `FaceKit` (`hm` for the time in a face's hours option, `masthead`, `pattern` for stipple or hatching, `moonDisc`, `gauge`, `fitFigures`, `rawText`, and readings as `footRows`, `footColumns` or `columnRows`).
- Shade with ink patterns (`pattern("dots")` or `pattern("lines")`), never soft greys: e-ink shows patterns far better. The tile is drawn at the page's scale, so previews shade like the panel. Unlit letters or segments are fine outlines, never grey text.

## Pitfalls met

- `paint(..., figures = true)` turns on tabular figures, which are wider than proportional ones. Fit a time string by measuring the tabular paint itself (`fitFigures`), not with `fit()`.
- `text()` shrinks a line by up to a fifth and then cuts it with an ellipsis if it would cross the margin. A label that silently disappears means its position leaves it no room.
- Inside a transformed canvas (a cube's side through `Matrix.setPolyToPoly`, a flap's tilted half), `text()` measures its margin guard in the wrong space and shrinks the text; use `rawText()` and fit the text to the shape yourself.
- Long readings in narrow columns: `columnRows` and the broadsheet sections set a value up to about a quarter smaller before cutting it, and `columnRows` splits the battery onto two rows ("Battery 82 %", "Used asleep 2 % · 1.4 % an hour"). `footColumns` gives each column the width its reading needs, but three readings don't fit across portrait: use `footRows` there (Clock does).
- A line for now drawn over event labels cuts their words: draw it under the events, and knock the labels out of the stipple with a little paper.
- A section heading with no room for an item under it looks broken; `section()` needs room for one item first.
- Don't say a state twice: the battery line already says "charging", and a legend "Asleep" over "Asleep since 9:00" says it twice.
- Show a reading only when it's true: no "nothing planned" without calendar access, no drain rate from under an hour or under 2 %, no current temperature from a cache over three hours old, and always the cache's age ("As of Sat 1:43"). Sunrise and sunset need the home screen's weather city; without one the Sky face says "Sun by the clock".
- Without live updates, a clock face shows the put-down moment, rounded like the put-down line, and says so rather than pretending to keep time. Live updates land on round multiples of the step, so the word clock's minute dots stay dark while asleep.
- A live face on the power-off screen would freeze a clock, so power-off falls back to the Almanac.
- `String.replace` in PowerShell replaces every match: removing `val time: String,` from one data class removed it from `SleepLive` too. Edit with the editing tool, one unique match at a time.

## Seeing a face at full size

- On the tablet, tap the Sleep page's preview: it opens the face at the panel's full size, and the page then shows "Full size drawn in N ms". Tap to close. Screenshot it with `Save-Shot` (portrait preview centre about 426, 390; landscape about 563, 500). This is the real renderer with the owner's font and data.
- Without the tablet (locked, asleep, or for many variants at once), render on the host with Robolectric's native graphics (4.12 and later run on Windows): a throwaway Gradle project outside the repository whose `sourceSets` point at the repo's `app/src/main` (java, res, aidl, assets and `knowledge`), `testImplementation("org.robolectric:robolectric:4.17")`, `@GraphicsMode(NATIVE)` and `@Config(sdk = [35])`. Call `SleepData.gather` on the Robolectric context, then `copy(...)` in events, battery (`ShadowBatteryManager.setIntProperty`) and live readings, write the launcher's `launcher_weather` preferences for weather and sun, and `SleepRenderer.render` into a 2480 × 1860 bitmap. Two traps: Kotlin's incremental cache fails when the project and the sources are on different drives (set `kotlin.incremental=false` and `-Pkotlin.compiler.execution.strategy=in-process`), and host render times say nothing about the tablet's.

## Adding a face

1. Add it to `SleepFace` with its `options`, and `live = true` if it relies on live readings. Live faces come first in the enum; the picker's *Live faces* tab lists them, the *Still faces* tab the others. Set `usesCalendar`, `drawsDay` or `readsSky` if it needs them.
2. Declare its own settings in `FaceOptions` (and `FaceOptions.of`), with labels in `strings_sleep.xml` (`so_` prefix). Never offer seconds.
3. Draw it in `SleepFaces`, `LiveFaces`, `CraftFaces` or `DayFaces`, then wire it into `SleepRenderer`, `SleepStudio.faceLabel` and `faceNote` in `SleepScreen.kt`. Put its strings in `strings_sleep.xml` with typographic apostrophes, and counts in `plurals`.
4. Look at it at full resolution in both orientations and both inks, with and without calendar access, weather and live updates. Fix everything in one pass, then check once more.
5. For a live face, test a real sleep on battery (see `boox-tablet-testing`): the face should appear about 5 s after sleep and at each step.
6. Update the CHANGELOG, the README's sleep section and `docs/06-sleep-screen.md`.