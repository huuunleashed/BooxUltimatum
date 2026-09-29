# 09 · Ink: the suite's pen and display framework

`kit:ink` is the suite's ink SDK: everything a suite app needs to write and draw on a BOOX tablet the way BOOX's own apps do, with the display drawing each stroke itself. Nib and Instant ink are built on it, and later suite apps will be too. This document says what the native apps do, which display calls exist, what's verified on the tablet, and how the framework is put together.

It rests on a clean-room study of BOOX Notes 46037 and NeoReader 39009 on a Note Air6 C with firmware 4.3 (Android 16). What they do was traced on the tablet from the display's own log while the owner wrote. How they do it was read from their decompiled code and the decompiled framework, which stay on the developer's machine in the git-ignored `captures/` folder. No BOOX code is copied here or in the framework: only behaviour, transaction codes, parcel layouts and parameter values, written in our own words. `knowledge/experiments.md` has the evidence rows.

Onyx's public developer documentation (onyx-intl/OnyxAndroidDemo, `doc/`) agrees where it overlaps:

- *Onyx-Pen-SDK* and *Scribble-TouchHelper-API*: a limit rectangle with excluded rectangles, stroke width and style, and pen and eraser callbacks. `setRawDrawingEnabled(true)` "enters scribble mode, and the screen will not refresh" until `closeRawDrawing()`, which is the one hold per session traced above.
- *EPD-Update-Mode*: A2-quality is "optimized animation mode with dither" and Regal is "minimal ghosting".
- *EPD-Touch*: turning finger touch off in rectangles, with areas kept on, is offered to apps (`setAppCTPDisableRegion`, `appResetCTPDisableRegion`).

The SDK itself is a binary-only library, so the suite uses its own clean-room client instead.

## What the native apps do

The display's firmware (SurfaceFlinger and the e-ink controller driver) reads the pen itself and paints each stroke on the panel within milliseconds, in one of its own stroke styles. An app arms it and decides when its own pixels replace that preview. The native apps do it like this (traced on the tablet, 2026-09-28):

- **One hold per writing session.** From the first pen touch the display holds the app's frames (`onyx_android_refreash_enable(): enable[0]`), and the e-ink controller switches to its handwriting update scheme (level 3). In Notes and in NeoReader, three bursts of quick strokes with 3 s pauses in between produced about 45 preview updates, and the hold ended once, 12 s later, when the owner left the page. Nib 0.2 let the frames through after every stroke, which made the controller switch schemes twice per stroke: that's the lag between quick strokes the owner felt.
- **The app renders behind the hold.** At each pen-up, Notes draws the stroke into its page and invalidates its view as usual; nothing reaches the panel. Its SDK has a pen-up refresh timer (500 ms, cancelled by the next touch) that repaints the union of the strokes in the handwriting repaint mode, but only on monochrome panels: on colour ones it's off.
- **The hold ends at breaks.** A menu, popup or toolbar press, undo or redo, a selection, a pan or zoom, the eraser starting, a system window, losing focus. There's no idle timer.
- **The toolbar stays current during a hold** by drawing a bitmap of it straight into the display's handwriting layer (`drawScreenHandWritingBitmap`), so no frame is let through.
- **Pans and zooms** turn the preview off and switch the display to its fast mode: A2-quality updates for everything (`applyTransientUpdate`), cleared with a reset 3 to 5 s after the last movement. The UI toolkit applies the same mode once a drag has moved 5 px.
- **Each pen configures the preview** with a style, a width, a colour and the style's parameters, so the preview and the app's final ink match. Notes keeps widths in pixels on the 300 dpi panel (0.1 to 2 mm for pens, 0.5 to 8 mm for the marker) and sends width × zoom.
- **Regions.** Notes sets a limit and exclusions for its toolbars and uses single-region mode. The display also has a multi-region mode, which keeps every excluded rectangle (verified with two).
- **The eraser end and the lasso** can be previewed by the display as well: an eraser track style and the dashed style.

### Pens and their preview

| Native pen | Display style | Style parameters (as the native app sets them) | Final ink |
|---|---|---|---|
| Plain pen, pencil | 0 pencil | none | A plain round path at the width |
| Fountain | 1 fountain | `[pressureSensitivity, smoothLevel]`, 0 to 1, defaults 0.3 and 0.6 | Width from pressure and smoothing, computed in native code |
| Marker | 2 marker | left at the display's `[1, 16]` | Round-capped segments at each point's width, drawn opaque, then composited at alpha 128 |
| Brush | 3 neo brush | none | Native |
| Charcoal | 4 charcoal, or 6 charcoal v2 | `[tiltEnabled, tiltScale]`, `[1, 3]` | Native texture, broadened by tilt |
| Latin or Asian calligraphy | 7 square pen | `[2, min(width, 10), ±45°, smoothLevel]` | Flat nib at the angle |
| Lasso | 5 dash | `[5]` | — |
| Eraser end | 8 eraser track | `[width, 0.5, 0.1]` | — |

The final ink of most native pens is computed in a native library an ordinary app can't load, and bundling it would break the project's licensing rules. So Nib's engine has its own geometry, driven by the same parameters, and each Nib brush sends the display parameters derived from its own settings (`nib-engine`'s `brush/Preview.kt`).

Pressure reaches the pens divided by the pen's maximum (4096). Tilt reaches apps on every stylus MotionEvent as `AXIS_TILT` and `AXIS_ORIENTATION`, so apps don't need to read the pen node for it.

## Measured pens

BOOX's own pen library was measured on the tablet with penlab (`tools/host/penlab`), driven with the configurations BOOX Notes uses for its final ink. Widths are full stroke widths in pixels, W is the configured width and p the pressure from 0 to 1. Nib's engine is calibrated to these numbers, and its tests check them.

| Pen | Width | Notes |
|---|---|---|
| Fountain | max(2, W · v · p^(2s)) | s is the `pressureSensitivity` (0.3 by default), exact for s from 0.15 to 1; s = 0 gives a constant width. v = 1 / (1 + 0.077 · speed in px/ms): 0.99 when slow, 0.76 at 4 px/ms. BOOX Notes configures W as the pen's width + 3. The display's fountain preview takes the same s and responds the same way (thinner as s rises). |
| Brush | 2W · √p | Pressure sensitivity is ignored. |
| Marker | about W · (0.8 + 0.2p) | 0.8W up to p 0.2, 0.9W at 0.5, W from 0.8. Speed is ignored. |
| Ballpoint | W | Constant. |
| Calligraphy (square) | about W | BOOX Notes configures 2W with the nib ratio min(W, 10). Pressure is ignored. |
| Pencil | stamps of exactly W, every 0.27W | With the settings BOOX Notes uses, pressure sets each stamp's opacity (0.6p), not its size. Each stamp is turned to a random angle, and tilt and speed are ignored. The display previews it as a constant-width line. |
| Charcoal | stamps of 1.2W + 5.2, every 9 px | Size doesn't follow pressure; coverage does: a mean stamp alpha of 16 at p 0.1, 28 at 0.3, 44 at 0.7 and 55 at 1. |

**Charcoal and tilt.** With tilt on and a tilt scale of s (3 by default), the width grows by 1 + s · g(θ), where θ is the pen's angle from upright. The angle combines both tilt axes: tilts of 45° and 45° give 60°. g is 0 up to 10°, then 0.07 at 20°, 0.2 at 30°, 0.51 at 45°, 1.04 at 60°, 1.49 at 70° and 2.02 at 80°. That's close to 1/cos θ − 1 up to 60°. A broader stroke isn't lighter. At a usual writing tilt of 40° to 50°, a charcoal lays down two to three times its width, which is why Nib's charcoal preview looked much thicker than its ink when Nib ignored tilt below 30°.


## The display interface

Every call goes to the `SurfaceFlinger` service with the interface token `android.ui.ISurfaceComposer`, then the payload. Codes and layouts are those FW 4.3's `android.onyx.ViewUpdateHelper` sends; an ordinary app may make them (the helper class itself is a hidden API for a targetSdk-36 app). ✓ marks calls verified on the tablet.

| Area | Call | Code | Payload → reply |
|---|---|---|---|
| Session | pen state ✓ | 16711693 | int state (0 stop, 1 start, 2 draw, 3 pause), int pid |
| | get pen state ✓ | 1048643 | → int (4 when paused) |
| | valid pen state | 1048641 | → int |
| Regions | limit ✓ | 16711694 | int screen?1:0, int[] l,t,r,b… |
| | exclude ✓ | 16711714 | same; an empty array clears |
| | region mode ✓ | 1048620 | int: 0 multi (all rectangles count), 1 single (the last) |
| | region pen config | 1049090 / 1049091 | int 1, int 1, then a size-prefixed record: l, t, r, b, and a typed array of pen configs (a size-prefixed record: button 0 tip, 1 eraser end, 2 side; then a typed stroke config: a size-prefixed record of style, width, colour, float[] params, int eraser preview, int painter) / int 0, int 0 for all, or int 1, int 1, l, t, r, b |
| Stroke | style ✓, width ✓, colour ✓ | 16711688, 16711687, 16711686 | int, float, int ARGB (send style first) |
| | get / set style parameters (get ✓) | 1049088 / 1049089 | int style → int n, n floats / int style, int n, n floats |
| | eraser preview | 1048833 | int on, int style |
| | brush preview | 1048834 | int on |
| | pen side button | 1048832 | int on |
| | fed-stroke widths | 16711697 / 16711698 / 16711699 | start / add / finish: float base width, x, y, pressure, size, time → float width |
| Hold | enable post ✓ | 16711692 | int −1, int on, int pid |
| | autosync ✓ | 1048722 | int on |
| Refresh | handwriting repaint | 1048647 | int flag, int[] l,t,r,b |
| | repaint everything ✓ / with a mode ✓ | 16711700 / 16711715 | — / int mode (deep GC ran waveform 12 over the whole panel) |
| | refresh a rectangle | 16711681 | int left, top, **width, height**, mode |
| | GC once | 16711718 | — (marks the next update; on its own it refreshes nothing) |
| | wait for updates | 16711703 | — |
| | fast mode on ✓ / off ✓ | 16711782 / 16711783 | int mode (A2 quality: the controller's fast scheme, fast mode index 3) / int reset (1 repaints the whole panel) |
| | regal for app frames | 16711722 | int on |
| | fast mode index | 1048656 | → int (0 normal) ✓ |
| Layer | draw a bitmap | 1049092 | int x, y, w, h, byteCount, blob of `copyPixelsToBuffer` ARGB_8888 bytes, 2 MB at most |
| Geometry | screen → panel ✓ | 16711724 | float x, y → float, float |
| | digitizer → screen ✓ | 16711723 | float x, y → float, float |
| | panel → screen matrix ✓ | 1049344 | → int n, n floats (3 × 3 row-major, then the digitizer scale) |
| | panel size ✓, digitizer size ✓ | 16711727/16711712, 16711706/16711707 | → float |
| | max pressure ✓ | 1048618 | → float (4096) |

Update modes (the firmware's numbers): DU 1, GU 2, GC4 3, A2 4, default 5, Regal 6, Regal plus 9, GC 98, GCC 107, deep GC 108, handwriting repaint 524290, DU quality 2305, A2 quality 2308, DU4 2312, mono A2 33554436.

Read from the tablet in portrait: the panel is 2480 × 1860 in its own frame, the digitizer 20832 × 15624 (0.119 panel pixels per unit), and the panel-to-screen matrix is `[0, −1, 1860; 1, 0, 0; 0, 0, 1]`, which is the mapping measured by hand earlier (panel = (y, 1860 − x)). The fountain's parameters read `[0.3, 0.6]`, the marker's `[1, 16]`, charcoal's `[1, 3]`, dash's `[5, 9, 9, 0]` and the square pen's `[2, 10, 45, −1]`.

Finger touch can be switched off in screen areas through Android's input manager, which BOOX extends (`android.hardware.input.IInputManager`, AIDL, so replies start with an exception header): reset 82, query 83, set 84 (int[] areas to disable, int[] areas to keep), enable 85 (int on). From the shell the calls work: set 84 with a region, enable 85, and the query 83 reads true until reset 82 (tested 2026-09-29). Onyx documents it for apps (*EPD-Touch*), but whether an app may call it on FW 4.3 is *[verify]*.

The display keeps all of this after the process that set it dies: an app killed mid-session left its preview drawing over every app (2026-09-28).

## How `kit:ink` is built

```
kit/ink/src/main/java/app/booxultimatum/kit/ink/
  epd/      Epd (the display client), UpdateMode, HandwritingLayer, ElevatedRoute
  session/  InkSession, InkStroke, InkDisplay (+ EpdInkDisplay), InkGuard
  canvas/   InkCanvasController, InkScheduler
  input/    PenInput (pen events from the kernel), TouchPanel, PalmGuard
  eink/     Eink (refreshes and fast mode for any suite screen)
```

- **`Epd`** is the display client: the table above as typed calls. `connect()` takes the direct route, or a privileged one (Shizuku, for Instant ink) when this process is refused, and only accepts a route that answers the pen state and a plausible pressure range. It logs the first failure of each call. `release()` ends any session and undoes everything a session changes.
- **`InkSession`** is one app's pen session. It opens in multi-region mode with a limit (screen rectangles, or the whole panel), sends the stroke after START, and draws at once. It tracks the hold: `penDown` starts it, and it lasts across strokes until `release(reason)`. `pause(reason)` releases, then pauses; `flush` pulses the hold for a touch the app didn't see. `setExclusions` sends all the app's controls in one call and only when they change. A stroke's style parameters are applied after reading the display's own, which are put back at `close()`.
- **`InkGuard`** records every display-wide change this process makes: an open session, fast mode, finger touch switched off, and replaced parameters with their originals. It keeps the record in a small file (`noBackupFilesDir`). At the next start, `attach()` undoes whatever an ended process of the app left: it ends a live session left drawing (a paused one is left alone, since it draws nothing and may belong to another app by then), clears fast mode, resets finger touch and restores parameters.
- **`InkCanvasController`** is the writing choreography every drawing surface shares, and it's what the native apps do:
  - The hold lasts the whole writing session.
  - Releases happen at breaks only: `controlsTouched`, `block` for panels and menus, `gestureStarted`, the eraser end, and `releaseNow` for app decisions such as undo, a tool change or a selection. A release waits for the app's frame with every stroke (`frameShown`, or 250 ms at most).
  - It has three reveal policies: `AtBreaks` (native), `AfterPause` (after a pause it first pushes the exact ink into the display's layer through `Host.pushInk`, keeping the hold, and releases only if that fails) and `EveryStroke` (the old behaviour).
  - `controlsChanged` pushes the app's controls during a hold (`Host.pushControls`).
  - Gestures turn on fast mode (`Host.fastMode`) until 600 ms after the last one.
  - It's pure Kotlin, tested on the JVM with a fake display and clock.
- **`HandwritingLayer`** pushes a bitmap, a view or part of a view into the display's layer, in bands of up to 2 MB.
- **`PalmGuard`** switches finger touch off over the canvas, except the app's controls, while the pen is near, and back on 500 ms after it leaves. It's recorded in `InkGuard`.
- **`Eink`** gives any suite screen a full clean, a clean of one view or rectangle, and the native fast mode with its guard record.
- **`PenInput`** reads hover, touch and the eraser end straight from the pen's kernel node, for Instant ink, which watches other apps.

## Nib on the framework

- The canvas drives an `InkCanvasController`. Every floating control records its screen rectangle, and all of them are the session's exclusions.
- Reveal policy (Settings › Display preview): at breaks, as the native apps do (the default); or after a pause, which pushes Nib's exact rendering into the display's layer; or after every stroke.
- During a hold, the pills whose state changed (undo and redo, the pen slots, the size) are pushed as bitmaps.
- Finger pans, zooms and turns use the native fast mode and clean up after.
- The lasso path is previewed by the display in its dashed style, and the eraser end pauses the preview, as in the native app.
- With Palm guard on, a resting hand can't touch the canvas while the pen is near.
- Each brush sends the display the style and parameters its own settings imply. Tilt reaches the engine from every sample.

## Instant ink on the framework

Instant ink runs in the hub for other apps, which render their own strokes, so it can't push their ink. It uses `Epd` directly (through Shizuku when this process is refused) and keeps its own service logic: a session kept ready and paused, taking over a paused BOOX session, never ending a BOOX app's session, stepping aside for suite apps.

- **Batched swaps (`HoldPolicy`, pure Kotlin, JVM-tested).** Frames are held from the pen's approach across quick strokes. They go through once the pen has rested the owner's chosen pause after a lift: 400, 500, 800, 1200 or 2000 ms, as BOOX's own screen notes offer, with 800 by default. A new touch cancels a pending swap.
- **Ending a hold.** It ends at once when the pen leaves range, the eraser end comes near, the chosen app leaves the front, the lock screen shows or the screen turns off. After any swap, a hovering pen holds again only after 500 ms, NeoReader's wait for the colour panel, while a touch holds at once.
- **Log and watchdog.** Each hold with strokes is logged under `ink` (strokes, time held, why it ended). A watchdog lets the frames through after 30 s without a touch.
- **Recovery.** Recovery (the Ink page, the notification) and home's stray-session check use `Epd.release()` and `TouchPanel.reset()`, which also clear fast mode, the region mode, exclusions, pen-part configurations and finger-touch suppression. `InkGuard` records a drawing session, so a hub that dies mid-session is cleaned up at its next start.
- **Clean screen.** A Quick Settings tile and a key on the Ink page run `Eink.cleanScreen()` (a deep-GC repaint of everything), for ghosting. The tile waits until Quick Settings has closed.

## Verified and not

- Verified on the tablet (NA6C FW 4.3): the native apps' single hold per session and the controller's scheme switches; multi-region mode with two exclusions; the geometry calls and the matrix; the style parameters read back; the fast mode index; every call Nib 0.2 already made.
- Not yet *[verify]*:
  - bitmaps pushed from a non-system app, and how the layer shows colour;
  - finger touch control from an ordinary app;
  - fast mode's look on the colour panel;
  - the eraser and lasso previews;
  - writing style parameters (the originals are always restored);
  - a limit given in screen coordinates;
  - `startStroke`/`addStrokePoint` (16711697 to 16711699), which return the width the display computes.

  Nib's Diagnostics probes each one.

## Findings ledger

Every finding from the study, and where it's used. Nothing is kept only in notes.

| Finding | Where it's used |
|---|---|
| One hold per writing session; releases only at breaks | `InkSession`, `InkCanvasController` (Nib); Instant ink's batched swaps |
| The controller switches update schemes at every release | The reason for the above; logged per hold (strokes, reason) |
| Pen-up refresh timer (500 ms, 400 to 2000 ms, cancelled by a touch, union of strokes) | Instant ink's swap delay; `InkCanvasController.Reveal.AfterPause` |
| Handwriting repaint mode only on monochrome panels | `AfterPause` pushes bitmaps rather than repainting; the mode is in `UpdateMode` for other panels |
| `drawScreenHandWritingBitmap` for controls during a hold | `HandwritingLayer`, `Host.pushControls` (Nib's pills), `Host.pushInk` (Nib's exact ink after a pause) |
| Multi-region mode keeps every excluded rectangle | `InkSession` opens in multi-region mode; Nib excludes all its cards at once |
| Region pen config per pen part | `Epd.setRegionPenConfig`, `InkSession.setPenButtons`: the eraser end previews an eraser track, the side button a chosen tool (Nib) |
| Stroke styles 0 to 7 in production use by the native app | Every style offered by default in Nib; the old "unverified" gate is only for styles the native app doesn't use |
| Style parameters (fountain sensitivity and smoothing, charcoal tilt, square nib) | `InkStroke.params` from each Nib brush's settings; originals restored at close and on crash |
| Widths in pixels at 300 dpi, × zoom; pens 0.1 to 2 mm, marker 0.5 to 8 mm | `nib-engine` width units and ranges |
| The native pens' width, pressure, speed and tilt responses, measured with penlab | `nib-engine`'s brush defaults, `PressureCurve.ofSensitivity`, `TiltShading`, and their tests |
| Marker drawn opaque, then composited at alpha 128 | `nib-engine` marker rendering |
| Colour passed through on colour panels; translucent previews dropped | Nib's `MarkerPreview` (solid colour or see-through grey) |
| Pressure divided by 4096 | Nib's pressure pipeline (Android already normalises it) |
| Tilt on every stylus MotionEvent | `nib-engine` samples and files; charcoal and pencil shading |
| Stationary repeat samples filtered (under 0.005 px/ms, pressure within 2/4096) | `nib-engine` stroke builder |
| Fast mode: A2 quality, cleared with a reset 3 to 5 s after the movement | `Eink.fastMode`, `InkCanvasController` gestures (Nib) |
| Regal once for page changes; GC repaint for "refresh page" | Nib after a drawing opens and a panel closes; `Eink.cleanScreen` for the Clean screen tile and menus |
| Finger touch off in regions (documented for apps) | `TouchPanel`, `PalmGuard` (Nib's Palm guard) |
| Geometry calls and the panel matrix | `Epd` geometry; Nib's Diagnostics shows them and checks rotation mapping |
| A session outlives its process | `InkGuard` and home's stray-session check |
| Screen-note choreography (repaint after a latency, no state change) | Instant ink's batched swaps |
| Fed-stroke widths | Nib's Diagnostics probe; if it returns widths without drawing, it calibrates the fountain |
| Eraser end reported by the pen (`BTN_TOOL_RUBBER`) and side buttons (`BTN_STYLUS`, `BTN_STYLUS2`) | `PenInput` (Instant ink); Nib's eraser end and side button tool |
| The native apps' own reader library, stroke library and display listener | Not usable: an ordinary app may not load them and bundling them breaks the licensing rules. Our own client and engine replace them |
| NeoReader re-arms only after the panel's update finishes (200 ms, 500 ms on colour panels, 600 ms in Regal) | `InkCanvasController.rearmMs` (Nib: 500 ms on colour panels); a pen touch still resumes at once |
| NeoReader: a stylus touch drops fast mode | `InkCanvasController.down()` ends fast mode at once |
| NeoReader: full cleans on colour panels use deep GC, or GC then a repaint | `Eink.cleanScreen(deep)`, the Clean screen tile |
| NeoReader: one central check turns raw drawing off for any popup, dialog, keyboard, toast or focus loss | Nib's blocks (panels, menus, entry bar, focus) |
| NeoReader: several limit areas on one pen surface, each with its own refresh | Not needed by Nib (one canvas); `InkSession.setLimit` takes several rectangles for a later app |
| NeoReader: Regal Plus for pages with pictures or grey areas, GC or deep GC every N pages, the first render in GC; fast mode by app-scope updates with a turbo level | For a later reader app and the home screen's page turns; `UpdateMode` has every mode |
| Turbo level (1048661), dither threshold (a separate system service), `penUp`, `appDie`, bypass counters | Not used: they can't be put back reliably or their meaning isn't known. Listed here in case a later test explains them |
