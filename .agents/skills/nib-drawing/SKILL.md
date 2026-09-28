---
name: nib-drawing
description: How Nib, the suite's drawing app, is built and tested, and the pitfalls met building it. Use whenever working on Nib's canvas, input, brushes, layers, tiles, the display pen session in Nib, its files and autosave, its Diagnostics probes, or when adding a brush end to end.
---

# Nib drawing

Nib is `:nib` (package `app.booxultimatum.nib`) on top of `:nib-engine` (pure Kotlin: strokes, brushes, layers, history, files) and the kit. [`docs/08-nib.md`](../../../docs/08-nib.md) describes the design as built; this skill is the practical side. Nothing about Nib's use of the display preview is verified on the tablet yet *[verify]*; the Diagnostics page is how to find out.

## Architecture in one breath

- `MainActivity` sees every pen event first and hands stylus hovers and touches to `PenRouter`, which forwards them to the surface on screen (`CanvasView` or `ProbeView`). That is what pauses the session when the pen hovers over Compose controls, which the canvas never sees.
- `PenController` (pure Kotlin, JVM-tested) is Nib's policy over `kit:ink`'s `PenSession`. One `PenSession` exists per process (`NibPen.session`).
- `CanvasView` turns MotionEvents into the engine's `StrokeBuilder` samples, commits through `EditorSession` (the engine's `History` plus the active layer), and draws the page from `TileCache`.
- `EditorSession` sends every history event to `AutoSaver` (one background thread: journal append and sync, compaction) and to the canvas (tile updates).
- `OpenSessions` keeps a drawing open while its editor is anywhere on the back stack.

## Pitfalls already met

- **The View inside Compose isn't clipped.** `canvas.drawColor` in an `AndroidView`'s `onDraw` flooded the whole window, toolbar and status strip included. Clip to the view in `onDraw` and put `clipToBounds()` after the padding on the `AndroidView`.
- **Keep the AndroidView at one place in the composition.** Switching between a `Row` and a `Column` for landscape and portrait recreates the view, losing the viewport, tiles and session. The editor uses one `Box` with padding instead.
- **A constructor parameter shadows the property in `init`.** `EditorSession`'s history listener read `document` (the constructor argument, the drawing as opened) instead of the live one and reset the active layer after every stroke. Name constructor arguments differently from the properties.
- **Android 16 ignores orientation requests on large screens** for targetSdk 36 apps. Instrumented tests rotate with `UiAutomation.setRotation(ROTATION_FREEZE_90)` and restore `ROTATION_FREEZE_0`; `requestedOrientation` does nothing on the Note Air6 C emulator.
- **PorterDuff MULTIPLY on a transparent tile paints nothing.** Use `BlendMode.MULTIPLY` (API 29), which is the separable W3C multiply the engine means.
- **Android sends a hover exit just before every touch.** Pausing on hover exit at once would pause at the moment of touching; `PenController` waits 120 ms for a touch.
- **Never let frames through while the pen touches.** Deferred: blocks, preview changes, and the swap of a previous stroke when a new one starts quickly (both are swapped once, after the second lift).
- **Many digitisers report zero pressure on the lift.** The last point keeps the pressure before it.
- **A stroke's bounds overstate where it inks.** A long diagonal's box covers many tiles it never crosses; `TileCache.reaches` tests segments against the tile before allocating or replaying.
- **Textured strokes are thousands of dabs.** Replaying a whole charcoal stroke into each of its tiles cost 60 to 75 ms per commit on the emulator; `DabRecording` replays each tile's own dabs (about 50 ms), and the worker does the same for background renders (a 70-tile document went from 1.1 s to 0.6 s). `TileCache.DAB_KINDS` must match the engine's Dabs mode (`DabRecordingTest` checks it).
- **Bitmaps dropped during a frame may still be drawn by it.** Released tile bitmaps go to a graveyard and reach the reuse pool at the next frame.
- **Threads.** `History` and `DocumentIndex` are main-thread only; the tile worker and the saver read immutable `Document`, `Layer` and `Stroke` snapshots only.
- **`python -` with a piped here-string can start an interactive Python** on this machine and flood the output; write scripts to a file in the session folder and run them.
- **`adb shell input` can't hover and can't draw a closed loop in one gesture.** `input stylus motionevent DOWN|MOVE|UP x y`, several in one `adb shell` command, draws curves; loops, hover and multi-touch are tested with instrumented tests instead. In landscape the emulator's taskbar sits at the bottom, so a tap there opens another app.

## Adding a brush end to end

1. In `:nib-engine` (coordinate if another agent works there): add the `BrushKind` with a stable `id`, its `BrushSpec.widthRange` and `BrushSpec.defaults` (including its `Preview` style), its place in `BrushCatalog.kinds`, and its rendering mode and texture in `StrokeRenderer.mode` and `texture`. Add engine tests.
2. In `:nib`: add it to a `BrushGroup` in `brush/Presets.kt` (the test `everyNonEraserBrushSitsInExactlyOneGroup` fails otherwise), a label in `ui/Names.brush` and `res/values/strings.xml` (`brush_<id>`), `TileCache.DAB_KINDS` if the engine draws it as dabs, and, for a new texture, a branch in `CanvasSink.dab` and a bitmap in `DabTextures`.
3. If its firmware style is unverified, nothing more is needed: `PreviewPolicy` uses the engine's verified fallback and the pen panel says so. Once a style is verified on the tablet (Diagnostics › Styles, with the log as evidence in `knowledge/experiments.md`), flip `HardwareStyle.verified` in the engine.
4. Run `.\gradlew.bat :nib-engine:test :nib:testDebugUnitTest`, then draw it on the emulator in both orientations and check the pen panel's sample.

## How to test

- **JVM:** `.\gradlew.bat :nib:testDebugUnitTest`. `PenControllerTest` drives the controller with a fake `PenDisplay` and a hand-run `PenScheduler`; extend it for any change to the session policy.
- **Emulator:** `adb devices` must list only `emulator-5554` (AVD `NoteAir6C`, no Boox firmware, so the session is `Unavailable` and Nib draws in software). Build and install with `.\gradlew.bat :nib:assembleDebug` and `adb -e install -r nib\build\outputs\apk\debug\nib-debug.apk`; `adb -e shell input stylus swipe x1 y1 x2 y2 400` draws a line. Screenshots with `adb -e exec-out screencap -p > <file>.png` into the session folder, scaled down with PIL before viewing. Rotate with `settings put system accelerometer_rotation 0` and `user_rotation 1` (landscape) or `0`; put it back to portrait afterwards.
- **Instrumented:** `.\gradlew.bat :nib:connectedDebugAndroidTest`. `EditorTest` opens a drawing straight into the editor with `MainActivity.EXTRA_OPEN_DRAWING`, finds the canvas by `R.id.nib_canvas`, and dispatches stylus MotionEvents (tool type `STYLUS`, source `SOURCE_STYLUS | SOURCE_TOUCHSCREEN`, pressure) to it, or injects them through the window with `UiAutomation.injectInputEvent`. It resets the tool state in `@Before`. Running it uninstalls the app afterwards, so drawings made by hand on the emulator are lost.
- **The tablet:** only the owner draws there. Ship a test build on the test channel; the owner runs Diagnostics (every probe band answered), then About › Share logs. Read the `nib.probe` answers and the `nib.pen` stroke summaries (`previewed`, `held`, `swap ms`, `watchdog`) from the zip.
- **Logs to look for:** `nib.pen stroke` (one per stroke, with `commit ms`), `nib.render tiles rendered` (per batch), `nib.doc drawing opened` (`replayed`, `stop`, `truncated`) and `compacted`.
