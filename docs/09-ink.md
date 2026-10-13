# 09 · Ink: the suite's pen and display framework

`kit:ink` is the suite's ink SDK: everything a suite app needs to write and draw on a Boox tablet the way Boox's own apps do, with the display drawing each stroke itself. Nib and Instant ink are built on it, and later suite apps will be too. This document says what Boox's own apps (the native apps) do, which display calls exist, what's verified on a Note Air6 C, and how the framework is put together.

It rests on a clean-room study of Boox Notes 46037 and NeoReader 39009 on the maintainer's Note Air6 C (NA6C) with firmware 4.3 (FW 4.3, Android 16). What they do was traced on the tablet from the display's own log while the maintainer wrote. How they do it was read from their decompiled code and the decompiled framework, which stay on the developer's machine in the git-ignored `captures/` folder. No Boox code is copied here or in the framework. Only behaviour, transaction codes, parcel layouts and parameter values are recorded, in the project's own words. `knowledge/experiments.md` has the evidence rows.

Onyx's public developer documentation (onyx-intl/OnyxAndroidDemo, `doc/`) agrees where it overlaps:

- *Onyx-Pen-SDK* and *Scribble-TouchHelper-API*: a limit rectangle with excluded rectangles, stroke width and style, and pen and eraser callbacks. `setRawDrawingEnabled(true)` "enters scribble mode, and the screen will not refresh" until `closeRawDrawing()`. That is the one hold per session traced below.
- *EPD-Update-Mode*: A2-quality is "optimized animation mode with dither" and Regal is "minimal ghosting".
- *EPD-Touch*: apps are offered a way to turn finger touch off in rectangles while keeping chosen areas on (`setAppCTPDisableRegion`, `appResetCTPDisableRegion`).

The SDK itself is a binary-only library, so the suite uses its own clean-room client instead.

## What the native apps do

The display's firmware (SurfaceFlinger, Android's display compositor, and the e-ink controller driver) reads the pen itself and paints each stroke on the screen within milliseconds, in one of its own stroke styles. An app arms it and decides when its own pixels replace that preview. The native apps do it like this (traced on a Note Air6 C, 2026-09-28):

- **One hold per writing session.** From the first pen touch the display holds back the app's frames, so they don't reach the screen (`onyx_android_refreash_enable(): enable[0]`), and the e-ink controller switches to its handwriting update scheme (level 3). In Notes and in NeoReader, three bursts of quick strokes with 3 s pauses in between produced about 45 preview updates. The hold ended once, 12 s later, when the maintainer left the page. Nib 0.2 let the frames through after every stroke, which made the controller switch schemes twice per stroke. That is the lag between quick strokes the maintainer felt.
- **The app renders behind the hold.** At each pen-up (when the pen lifts), Notes draws the stroke into its page and invalidates its view as usual, but nothing reaches the screen. Its SDK has a pen-up refresh timer (500 ms, cancelled by the next touch) that repaints the area the strokes cover together, in the handwriting repaint mode. That works only on monochrome screens and is off on colour ones.
- **The hold ends at breaks.** A break is a tap on a menu, popup or toolbar, undo or redo, a selection, a pan or zoom, the eraser starting, a system window or losing focus. There's no idle timer.
- **The toolbar stays current during a hold** by drawing a bitmap of it straight into the display's handwriting layer (`drawScreenHandWritingBitmap`), so no frame is let through.
- **Pans and zooms** turn the preview off and switch the display to its fast mode, a quick refresh at A2 quality for everything (`applyTransientUpdate`). It is cleared with a reset 3 to 5 s after the last movement. The UI toolkit applies the same mode once a drag has moved 5 px.
- **Each pen configures the preview** with a style, a width, a colour and the style's parameters, so the preview and the app's final ink match. Notes keeps widths in pixels on the 300 dpi screen (0.1 to 2 mm for pens, 0.5 to 8 mm for the marker) and sends width × zoom.
- **Regions.** Notes sets a limit (the rectangle where the display previews the pen) and exclusions (rectangles inside it, such as toolbars, where it doesn't), and uses single-region mode. The display also has a multi-region mode, which keeps every excluded rectangle (verified with two).
- **The eraser end and the lasso** can be previewed by the display as well, through an eraser track style and the dashed style.

### Pens and their preview

| Native pen | Display style | Style parameters (as the native app sets them) | Final ink |
|---|---|---|---|
| Plain pen, pencil | 0 pencil | none | A plain round path at the width |
| Fountain | 1 fountain | `[pressureSensitivity, smoothLevel]`, 0 to 1, defaults 0.3 and 0.6 | Width from pressure and smoothing, computed in native code, 3 screen px wider than the width sent to the display (see *Measured pens*) |
| Marker | 2 marker | left at the display's `[1, 16]` | Round-capped segments at each point's width, drawn opaque, then composited at alpha 128 |
| Brush | 3 neo brush | none | Native |
| Charcoal | 4 charcoal, or 6 charcoal v2 | `[tiltEnabled, tiltScale]`, `[1, 3]` | A 1-bit stipple broadened by tilt (see *Measured pens*). Nib's pencil draws the v2 one |
| Latin or Asian calligraphy | 7 square pen | `[2, min(width, 10), ±45°, smoothLevel]` | Flat nib at the angle |
| Lasso | 5 dash | `[5]` | — |
| Eraser end | 8 eraser track | `[width, 0.5, 0.1]` | — |

The final ink of most native pens is computed in a compiled (native) library that an ordinary app can't load, and bundling it would break the project's licensing rules. So Nib's engine has its own geometry, driven by the same parameters, and each Nib brush sends the display the parameters derived from its own settings (`nib-engine`'s `brush/Preview.kt`).

Pressure reaches the pens divided by the pen's maximum (4096). Tilt reaches apps on every stylus MotionEvent as `AXIS_TILT` and `AXIS_ORIENTATION`, so apps don't need to read the pen's input device for it.

## Measured pens

Boox's own pen library was measured on a Note Air6 C with penlab (`tools/host/penlab`), driven with the configurations Boox Notes uses for its final ink. Widths are full stroke widths in pixels. W is the configured width and p the pressure from 0 to 1. Nib's engine is calibrated to these numbers, and its tests check them.

| Pen | Width | Notes |
|---|---|---|
| Fountain | max(2, W · v · p^(2s)) | s is the `pressureSensitivity` (0.3 by default). The law is exact for s from 0.15 to 1, and s = 0 gives a constant width. v = 1 / (1 + 0.077 · speed in px/ms): 0.99 when slow, 0.76 at 4 px/ms. Boox Notes configures W as the pen's width + 3, while it sends the display the pen's width. The display's fountain preview of a pen sent w px wide is this law at w + 3, and its thinning follows s the same way. |
| Fountain, the older algorithm | 2 + (W + 1) · p² | Boox's library still has it (its type 2). Its width ignores sensitivity, speed and the minimum width, so the display, whose preview thins with sensitivity, isn't drawing it. The constant in Notes that adds the 3 px is named after it (`FOUNTAIN_PEN_V1_COMPENSATION`): at full pressure the newer law at W + 3 equals this one at W. |
| Brush | 2W · √p | Pressure sensitivity is ignored. The width is exactly 2W·√p when fast and about 0.6 px wider when slow (2026-10-07). The display's brush style draws the same law for the width it's sent: its update rectangles grow by 3 px from pressure 0.24 to 0.72 at 4 px, as 2W·√p does, plus a fixed 13 px margin (measured while the maintainer drew). The library's type 10 (brush sign) keeps a constant width. |
| Marker | about W · (0.8 + 0.2p) | 0.8W up to p 0.2, 0.9W at 0.5, W from 0.8. Speed is ignored. |
| Ballpoint | W | Constant. |
| Calligraphy (square) | about W | Boox Notes configures 2W with the nib ratio min(W, 10). Pressure is ignored. |
| Pencil | stamps (a dab repeated along the path) of exactly W, every 0.27W | Boox's library pencil (its type 7), with the settings of its `NeoPencilPen` defaults. Pressure sets each stamp's opacity (0.6p), not its size. Each stamp is turned to a random angle, and tilt and speed are ignored. No pen of Boox Notes 46037 draws with it: Notes' pencil is a plain round path (`NormalPencilShape`), previewed in the pencil style, which draws a constant-width plain line. |
| Charcoal (v1, the display's charcoal style) | stamps of about 1.17W + 6, every 3 px along the path | Measured 2026-10-07. Each stamp is black pixels on transparent (1-bit, no grey) in a dispersed stipple, a pattern of dots. The on-pixels avoid each other (at p 0.5 a pixel is on 0.33 of the time, but 0.11 next to one that is). The stamp is dense at the centre and almost empty beyond 0.6 of the radius. The pattern is fixed to the stroke, not the page: the same stroke gives the same pixels, and moved by 1 px it moves with it. Per stamp, 0.02 + 0.11p of the pixels are on. Where stamps overlap on the centre line the cover is 0.27 at p 0.2, 0.44 at 0.5 and 0.71 at 1, and nothing reaches 3.5 px from the line at W 4. Spacing, size and density ignore speed. The points' own spacing matters only beyond 4 px. |
| Charcoal v2 (the display's charcoal v2 style) | stamps of about 1.13W + 5.7, at every point up to 2 px apart (4 px for stamps over about 16 px) | Measured 2026-10-07. 1-bit too, but anchored to the page: a pixel is on when a fixed, uniform, uncorrelated threshold at that page pixel is below the pressure there. So on the centre line the cover equals the pressure (0.03 at p 0.05, 0.20 at 0.2, 0.53 at 0.5, 0.87 at 0.85, 1 at 1: solid). Overlapping stamps don't build up, and a lighter stroke's pixels are a subset of a darker one's at the same place (0.95 to 0.97). Across the stroke the cover is flat out to 0.7 of the stamp's radius, then falls about linearly to nothing at its edge (at W 4, the pressure out to 3 px from the line, 0.4 of it at 4 px, none at 5). The same stroke 1 px further keeps 0.96 of its pixels where they were. Speed is ignored. |
| Charcoal and tilt | the stamp × (1 + s · g(θ)) | Both versions: tilt scales the whole stamp evenly, and doesn't make it lighter (45° gives about 2.5 times, for W 1 to 6). |

**Charcoal and tilt.** With tilt on and a tilt scale of s (3 by default), the width grows by 1 + s · g(θ), where θ is the pen's angle from upright. The angle combines both tilt axes: tilts of 45° and 45° give 60°. g is 0 up to 10°, then 0.07 at 20°, 0.2 at 30°, 0.51 at 45°, 1.04 at 60°, 1.49 at 70° and 2.02 at 80°. That's close to 1/cos θ − 1 up to 60°. A broader stroke isn't lighter. At a usual writing tilt of 40° to 50°, a charcoal lays down two to three times its width. That is why Nib's charcoal preview looked much thicker than its ink when Nib ignored tilt below 30°.

## The display interface

Every call goes to the `SurfaceFlinger` service with the interface token `android.ui.ISurfaceComposer`, then the payload. Codes and layouts are the ones that FW 4.3's `android.onyx.ViewUpdateHelper` sends. An ordinary app may make them (the helper class itself is a hidden API for a targetSdk-36 app). ✓ marks calls verified on the maintainer's Note Air6 C.

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
| | repaint everything ✓ / with a mode ✓ | 16711700 / 16711715 | — / int mode (deep GC ran waveform 12 over the whole screen) |
| | refresh a rectangle | 16711681 | int left, top, **width, height**, mode |
| | GC once | 16711718 | — (marks the next update; on its own it refreshes nothing) |
| | wait for updates | 16711703 | — |
| | fast mode on ✓ / off ✓ | 16711782 / 16711783 | int mode (A2 quality: the controller's fast scheme, fast mode index 3) / int reset (1 repaints the whole screen) |
| | regal for app frames | 16711722 | int on |
| | fast mode index | 1048656 | → int (0 normal) ✓ |
| Layer | draw a bitmap | 1049092 | int x, y, w, h, byteCount, blob of `copyPixelsToBuffer` ARGB_8888 bytes, 2 MB at most |
| Geometry | screen → display ✓ | 16711724 | float x, y → float, float |
| | pen sensor (digitizer) → screen ✓ | 16711723 | float x, y → float, float |
| | display → screen matrix ✓ | 1049344 | → int n, n floats (3 × 3 row-major, then the pen sensor scale) |
| | display size ✓, pen sensor size ✓ | 16711727/16711712, 16711706/16711707 | → float |
| | max pressure ✓ | 1048618 | → float (4096) |

Update modes are the refresh modes the display can run. These are the firmware's numbers: DU 1, GU 2, GC4 3, A2 4, default 5, Regal 6, Regal plus 9, GC 98, GCC 107, deep GC 108, handwriting repaint 524290, DU quality 2305, A2 quality 2308, DU4 2312, mono A2 33554436.

Read from the maintainer's Note Air6 C in portrait: the display is 2480 × 1860 in its own frame, and the pen sensor 20832 × 15624 (0.119 display pixels per unit). The display-to-screen matrix is `[0, −1, 1860; 1, 0, 0; 0, 0, 1]`, which is the mapping measured by hand earlier (display = (y, 1860 − x)). The fountain's parameters read `[0.3, 0.6]`, the marker's `[1, 16]`, charcoal's `[1, 3]`, dash's `[5, 9, 9, 0]` and the square pen's `[2, 10, 45, −1]`.

Finger touch can be switched off in screen areas through Android's input manager, which Boox extends (`android.hardware.input.IInputManager`, AIDL, so replies start with an exception header). The calls are reset 82, query 83, set 84 (int[] areas to disable, int[] areas to keep) and enable 85 (int on). From the adb shell (a command line on a computer connected to the tablet) they work: set 84 with a region, enable 85, and the query 83 reads true until reset 82 (tested 2026-09-29). Onyx documents it for apps (*EPD-Touch*), but whether an app may call it on FW 4.3 is *[verify]*.

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

- **`Epd`** is the display client: the table above as typed calls. `connect()` takes the direct route, or a privileged one when this process is refused, and only accepts a route that answers the pen state and a plausible pressure range. The privileged route goes through Shizuku, a helper app that lets BooxUltimatum run commands with the same rights as adb, and Instant ink uses it. `Epd` logs the first failure of each call. `release()` ends any session and undoes everything a session changes.
- **`InkSession`** is one app's pen session. It opens in multi-region mode with a limit (screen rectangles, or the whole display), sends the stroke after START, and draws at once. It tracks the hold: `penDown` starts it, and it lasts across strokes until `release(reason)`. `pause(reason)` releases, then pauses. `flush` pulses the hold for a touch the app didn't see. `setExclusions` sends all the app's controls in one call, and only when they change. A stroke's style parameters are applied after reading the display's own, which are put back at `close()`.
- **`InkGuard`** records every display-wide change this process makes: an open session, fast mode, finger touch switched off, and replaced parameters with their originals. It keeps the record in a small file (`noBackupFilesDir`). At the next start, `attach()` undoes whatever an ended process of the app left. It ends a live session left drawing (a paused one is left alone, since it draws nothing and may belong to another app by then), clears fast mode, resets finger touch and restores parameters.
- **`InkCanvasController`** is the writing choreography every drawing surface shares, and it's what the native apps do:
  - The hold lasts the whole writing session.
  - Releases happen at breaks only: `controlsTouched`, `block` for cards and menus, `gestureStarted`, the eraser end, and `releaseNow` for app decisions such as undo, a tool change or a selection. A release waits for the app's frame with every stroke (`frameShown`, or 250 ms at most). Nib reports that frame when SurfaceFlinger has taken it. A transaction with a committed listener rides on the frame that draws the stroke (`FrameLatch`, Android 13 and later), so a release never lets an older frame through.
  - A reveal policy says when the app's own ink replaces the display's preview. There are three: `AtBreaks` (native), `AfterPause` (after a pause it first pushes the exact ink into the display's layer through `Host.pushInk`, keeping the hold, and releases only if that fails) and `EveryStroke` (the old behaviour).
  - `controlsChanged` pushes the app's controls during a hold (`Host.pushControls`).
  - Gestures turn on fast mode (`Host.fastMode`) until 600 ms after the last one.
  - It's pure Kotlin, tested on the JVM with a fake display and clock.
- **`HandwritingLayer`** pushes a bitmap, a view or part of a view into the display's layer, in bands of up to 2 MB.
- **`PalmGuard`** (Nib's Palm guard) switches finger touch off over the canvas, except the app's controls, while the pen is near, and back on 500 ms after it leaves. It's recorded in `InkGuard`.
- **`Eink`** gives any suite screen a full clean, a clean of one view or rectangle, and the native fast mode with its guard record.
- **`PenInput`** reads hover, touch and the eraser end straight from the pen's input device in the kernel, for Instant ink, which watches other apps.

## Nib on the framework

- The canvas drives an `InkCanvasController`. Every floating control records its screen rectangle, and all of them are the session's exclusions.
- The reveal policy is Settings › Writing › Show the finished ink. *Auto* is the default. It uses *At breaks*, as the native apps do, for pens the display previews faithfully, and *After a pause* for textured, blended and see-through ones. *At breaks* always waits for a break. *After a pause* pushes Nib's exact rendering into the display's layer once the pen rests. *After every stroke* lets each stroke's frame through.
- During a hold, the pills (the rounded groups of controls) whose state changed (undo and redo, the pen slots, the size) are pushed as bitmaps.
- Finger pans, zooms and turns use the native fast mode and clean up after.
- The lasso path is previewed by the display in its dashed style, and the eraser end pauses the preview, as in the native app.
- With Palm guard on, a resting hand can't touch the canvas while the pen is near. It's off by default, and it works only while Nib's Instant ink switch is on.
- Each brush sends the display the style and parameters its own settings imply. Tilt reaches the engine from every sample.

## Instant ink on the framework

Instant ink runs in BooxUltimatum and serves other apps. Those apps render their own strokes, so Instant ink can't push their ink. It uses `Epd` directly (through Shizuku when this process is refused) and keeps its own service logic: a session kept ready and paused, taking over a paused Boox session, never ending a Boox app's session and stepping aside for suite apps.

- **Batched swaps (`HoldPolicy`, pure Kotlin, JVM-tested).** A swap is the moment the app's own strokes replace the preview. Frames are held from the pen's approach across quick strokes. They go through once the pen has rested the chosen pause after a lift (the setting *Show the app’s strokes after a pause of*): 400, 500, 800, 1200 or 2000 ms, as Boox's own screen notes offer, with 800 by default. A new touch cancels a pending swap.
- **Ending a hold.** It ends at once when the pen leaves range, the eraser end comes near, the chosen app leaves the front, the lock screen shows or the screen turns off. After any swap, a hovering pen holds again only after 500 ms, NeoReader's wait for the colour screen, while a touch holds at once.
- **Log and watchdog.** Each hold with strokes is logged under `ink` (strokes, time held, why it ended). A watchdog lets the frames through after 30 s without a touch.
- **Recovery.** Recovery (the *Recover screen* button on the Ink page and in the notification) and BooxUltimatum home's stray-session check use `Epd.release()` and `TouchPanel.reset()`. These also clear fast mode, the region mode, exclusions, pen-part configurations and finger-touch suppression. `InkGuard` records a drawing session, so a session left behind when BooxUltimatum dies mid-session is cleaned up at its next start.
- **Preview styles** (from BooxUltimatum 0.8.0): pencil, fountain pen, marker, brush pen (the brush style, `2w·√p`) and grainy pencil (charcoal v2, Boox's 1-bit stipple, with the display's own tilt defaults), each at the width chosen. They're the styles measured for Nib. Instant ink never changes a style's parameters, so another app's session finds them as the display keeps them.
- **Clean screen.** The *Clean screen* tile in Quick Settings and the *Clean screen* button on the Ink page run `Eink.cleanScreen()` (a deep-GC repaint of everything), for ghosting, the faint traces of earlier content. The tile waits until Quick Settings has closed.

## Verified and not

- Verified on the maintainer's Note Air6 C (NA6C, FW 4.3): the native apps' single hold per session and the controller's scheme switches, multi-region mode with two exclusions, the geometry calls and the matrix, the style parameters read back, the fast mode index, and every call Nib 0.2 already made. On 2026-10-06, from the shell and with Android's stylus input command (no preview, since the display reads only the real pen), SurfaceFlinger's committed listener reported each held stroke's frame in Nib, and Nib's padded fountain ink measured as computed.
- Not yet *[verify]*:
  - bitmaps pushed from a non-system app, and how the layer shows colour;
  - finger touch control from an ordinary app;
  - fast mode's look on the colour screen;
  - the eraser and lasso previews;
  - writing style parameters (the originals are always restored);
  - a limit given in screen coordinates;
  - `startStroke`/`addStrokePoint` (16711697 to 16711699), which return the width the display computes. The other app-fed calls, `moveTo`, `quadTo` and `penUp`, are accepted but draw nothing (from the shell, 2026-10-06);
  - whether the fountain preview and its padded ink now look the same to the eye (the maintainer's check).

  Nib's Diagnostics › Lab probes each one.

## Findings ledger

Every finding from the study, and where it's used. Nothing is kept only in notes.

| Finding | Where it's used |
|---|---|
| One hold per writing session; releases only at breaks | `InkSession`, `InkCanvasController` (Nib); Instant ink's batched swaps |
| The controller switches update schemes at every release | The reason for the above; logged per hold (strokes, reason) |
| Pen-up refresh timer (500 ms, 400 to 2000 ms, cancelled by a touch, union of strokes) | Instant ink's swap delay; `InkCanvasController.Reveal.AfterPause` |
| Handwriting repaint mode only on monochrome screens | `AfterPause` pushes bitmaps rather than repainting; the mode is in `UpdateMode` for other screens |
| `drawScreenHandWritingBitmap` for controls during a hold | `HandwritingLayer`, `Host.pushControls` (Nib's pills), `Host.pushInk` (Nib's exact ink after a pause) |
| Multi-region mode keeps every excluded rectangle | `InkSession` opens in multi-region mode; Nib excludes all its cards at once |
| Region pen config per pen part | `Epd.setRegionPenConfig`, `InkSession.setPenButtons`: the eraser end previews an eraser track, the side button a chosen tool (Nib) |
| Stroke styles 0 to 7 in production use by the native app | Every style offered by default in Nib; the old "unverified" gate is only for styles the native app doesn't use |
| Style parameters (fountain sensitivity and smoothing, charcoal tilt, square nib) | `InkStroke.params` from each Nib brush's settings; originals restored at close and on crash |
| Widths in pixels at 300 dpi, × zoom; pens 0.1 to 2 mm, marker 0.5 to 8 mm | `nib-engine` width units and ranges |
| The native pens' width, pressure, speed and tilt responses, measured with penlab | `nib-engine`'s brush defaults, `PressureCurve.ofSensitivity`, `TiltShading`, and their tests |
| Marker drawn opaque, then composited at alpha 128 | `nib-engine` marker rendering |
| Colour passed through on colour screens; translucent previews dropped | Nib's `MarkerPreview` (solid colour or see-through grey) |
| Pressure divided by 4096 | Nib's pressure pipeline (Android already normalises it) |
| Tilt on every stylus MotionEvent | `nib-engine` samples and files; charcoal and pencil shading |
| Stationary repeat samples filtered (under 0.005 px/ms, pressure within 2/4096) | `nib-engine` stroke builder |
| Fast mode: A2 quality, cleared with a reset 3 to 5 s after the movement | `Eink.fastMode`, `InkCanvasController` gestures (Nib) |
| Regal once for page changes; GC repaint for "refresh page" | Nib after a drawing opens and a card closes; `Eink.cleanScreen` for the Clean screen tile and menus |
| Finger touch off in regions (documented for apps) | `TouchPanel`, `PalmGuard` (Nib's Palm guard) |
| Geometry calls and the display-to-screen matrix | `Epd` geometry; Nib's Diagnostics shows them and checks rotation mapping |
| A session outlives its process | `InkGuard` and BooxUltimatum home's stray-session check, which also notify the display its client died (`APP_DIE` with the dead pid) |
| Sessions set the SDK's raw-drawing defaults (brush previews on, the eraser end previews Nib's eraser track) and put them back on release | `InkSession.arm`, `Epd.release` |
| Screen-note choreography (repaint after a latency, no state change) | Instant ink's batched swaps |
| Fed-stroke widths | None: `startStroke`, `addStrokePoint` and `finishStroke` return 0 on FW 4.3 (2026-10-07) |
| Eraser end reported by the pen (`BTN_TOOL_RUBBER`) and side buttons (`BTN_STYLUS`, `BTN_STYLUS2`) | `PenInput` (Instant ink); Nib's eraser end and side button tool |
| The native apps' own reader library, stroke library and display listener | Not usable: an ordinary app may not load them and bundling them breaks the licensing rules. The suite's own client and engine replace them |
| NeoReader re-arms only after the display's update finishes (200 ms, 500 ms on colour screens, 600 ms in Regal) | `InkCanvasController.rearmMs` (Nib: 500 ms on colour screens); a pen touch still resumes at once |
| NeoReader: a stylus touch drops fast mode | `InkCanvasController.down()` ends fast mode at once |
| NeoReader: full cleans on colour screens use deep GC, or GC then a repaint | `Eink.cleanScreen(deep)`, the Clean screen tile |
| NeoReader: one central check turns raw drawing off for any popup, dialog, keyboard, toast or focus loss | Nib's blocks (cards, menus, entry bar, focus) |
| NeoReader: several limit areas on one pen surface, each with its own refresh | Not needed by Nib (one canvas); `InkSession.setLimit` takes several rectangles for a later app |
| NeoReader: Regal Plus for pages with pictures or grey areas, GC or deep GC every N pages, the first render in GC; fast mode by app-scope updates with a turbo level | For a later reader app and the home screen's page turns; `UpdateMode` has every mode |
| Turbo level (1048661, one int, with a getter), dither threshold (effectively 255 on, 128 off, refused below), `penUp` (16711784, no payload), `appDie` (16711717, pid) | Still not used: turbo and dither also reach the hardware through reflection and their visible effects are unmeasured; `penUp`/`appDie` have no established use in the suite's paths. Listed here with payloads in case a later test explains them |
| Fed strokes take six floats and return the width the display computes; `moveTo` carries width, `lineTo`/`quadTo` an update mode | Nib's Diagnostics probe; if it returns widths without drawing, it calibrates the fountain |
| Handwriting bitmaps are x, y, width, height, byte count and an ARGB blob, capped at 2 MB | `HandwritingLayer`, `Host.pushControls`, `Host.pushInk` |
| Region config wraps one rectangle with its pen configs; eraser raw preview defaults off (painter 5), brush raw on | `Epd.setRegionPenConfig`, `InkSession.setPenButtons` |
| The SDK opens with style 0 and pen state 1, and closes through pause (3) to stop (0) | `InkSession` already follows this order; no change |
| NeoReader's wait-for-update is a timed sleep of at least 150 ms, and its pen path re-arms 200 ms after disabling raw drawing | `InkCanvasController.rearmMs`; confirms the 200 ms value |
| The SDK names pen state 4 erasing but never sends it | Held on the maintainer's Note Air6 C 2026-09-29: sending 4 reads back 2, and the tip’s preview shows. Lab › Pen state 4 keeps the probe. |
| The eraser's raw painter defaults to 5 with the preview off | Painters 0 to 8 all draw a track (the maintainer’s test 2026-09-29, one band each); Lab › Eraser painters keeps the probe. |
| Boox Notes sends the display a fountain pen's width and draws its ink 3 screen px wider, with a 1 px minimum at the zoom it was drawn at (`FOUNTAIN_PEN_V1_COMPENSATION`) | `BrushSpec.inkAt`: Nib's fountain ink gets the same 3 screen px, its 2 px floor and its speed thinning on screen. Unlike Notes, Nib sizes the preview so that the stroke is the same width on the page at every zoom: the display is sent `(w + 3) x zoom - 3` (the pencil's charcoal v2, whose stamp is `1.16 s + 5`, `((1.16 w + 5) x zoom - 5) / 1.16`), never below 1 px (`BrushSpec.sentWidthPx`) *[verify]* |
| The older fountain algorithm is 2 + (w + 1) · p² and ignores sensitivity | Rules it out as the display's preview; nothing else uses it |
| Boox Notes previews its ballpoint (a constant width) in the pencil style | Nib's ballpoint previews in the pencil style; so does the dash's stand-in |
| Boox Notes' pencil is a plain round line previewed in the pencil style; its only textured pen, the charcoal, is previewed in the charcoal styles with tilt (decompiled Notes 46037, 2026-10-07) | Nib's grainy, tilted pencil can't match the plain pencil style; a textured, tilted preview needs a charcoal style |
| The charcoal v2 style's ink is a 1-bit stipple: on where a fixed page threshold is below the pressure, stamps of 1.16w + 5 px solid to 0.6 of the radius, tilt broadening without lightening (penlab, 2026-10-07) | Nib's pencil is that stipple (`Stipple`), previewed in charcoal v2 with tilt on and the brush's tilt scale; checked against penlab's numbers and side by side with its strokes (`StippleTest`). The look on the screen is the maintainer's check *[verify]* |
| An app's frame is in SurfaceFlinger once a transaction riding on it is committed (`applyTransactionOnDraw`, `addTransactionCommittedListener`) | Nib's `FrameLatch` reports the frame with the last stroke before a release; verified on the maintainer's Note Air6 C (every held stroke's frame reported) |
| The app-fed preview calls (`moveTo`, `quadTo`, `penUp`) are accepted on FW 4.3 but draw nothing, and the display's pen reader opens only the pen's own node (it picks devices named `onyx_emp`, `Wacom` or `hanvon`) | No use: the display's preview can only be seen with the real pen |
| The kernel logs every 20th preview update (`HANDWRITE update_marker`, with its rectangle) | Counting updates and placing strokes from a log; not a measure of width |
