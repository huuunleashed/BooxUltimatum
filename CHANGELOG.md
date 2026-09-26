# Changelog

All notable changes to BooxUltimatum. Newest first. The project follows [Semantic Versioning](https://semver.org) loosely while it is below 1.0: minor versions add features, and anything may still change between them. Dates are in the tablet's time zone (UTC+7).

## [Unreleased] (towards 0.4.0, the first public preview)

### Added

- **Instant ink** (Ink destination, experimental). While an app you pick is in front, the display draws the pen stroke straight onto the panel as Boox Notes does, and the app's own stroke replaces the preview half a second after the lift. It needs no root, no Shizuku and no change to the app. Found in a test session with the owner drawing in Sketchbook:
  - Boox's display service can hold an app's frames back while the pen draws and let them through after the lift. That swaps the preview for the app's stroke without closing the pen session, so no stroke loses its start.
  - The preview region is a square as large as the panel's long side, because the service reads it in the panel's landscape frame. A portrait-shaped region missed the bottom quarter.
  - Hovering (reported as the brush tool by this pen) arms the session before the nib touches.
  - Fountain pen at 4 px is the default brush, because the pencil is textured and looked broken.
  - The in-app service is built and starts cleanly, but hasn't yet been checked with a real pen.
- **Screenshots** of the app and home screen in `docs/screenshots/`, taken on the tablet and anonymised.
- **A landing page and technical guide** in `site/`, published to GitHub Pages: what it does, the access tiers, and how each part works, with links to the exact files.
- **GitHub project files:** issue forms (bug, device finding, idea), a pull request template, `SECURITY.md`, fuller contributing guidelines, a CI workflow that builds, lints and checks prose, and a Pages deploy workflow.
- **Release signing:** `-Pbu.signing=<properties>` signs with a key kept outside the repository. Without it, builds stay debug-signed.
- **A new icon.** An ordered-dither ramp: grey made only of black and white cells, the way e-ink draws it, with one 2×2 colour cell lit, since one Kaleido colour pixel covers four mono ones. It draws no outline of its own, so launcher masks never double it, and it has a themed single-colour layer. It was chosen from six concepts and replaces the needle-and-scale placeholder. The PNG versions are in `docs/brand/`.

### Changed

- **The in-app glyphs were redrawn as one family:** the same stroke, padding and optical size, true arcs, and legible at 24 dp and inverted in the selected rail pill.
  - Sleep is a properly built crescent with a lamp.
  - Fonts is a clean "Aa".
  - Settings is a gear rather than a sun that read as brightness.
  - The battery terminal, slider knobs, app grid gaps and chevrons were fixed.
  - The header's BooxUltimatum key uses the new mark.

### Fixed

- **Sleep screen after a rotation.** Turning the tablet and sleeping soon after showed the face cropped, because the face for the new shape was only drawn seconds later.
  - Each refresh now also prepares the other orientation, and a turn swaps it in at once.
  - A last check runs as the tablet starts to sleep.
  - Verified: rotate then sleep within 0.2 s in both directions, and rotate and sleep at the same instant from the lock screen.
- **Boox's bottom bar on the sleep screen** (battery and "Press power button to wake up") is now switched off through Boox's settings, and faces use that strip. Boox still forces the bar on while the tablet charges; the studio says so.
- A new version redraws the sleep face on its own after installing.

## [0.3.0] (2026-09-26)

### Added

- **Sleep screen studio** (Sleep destination). Your own sleep screen in your font, instead of Boox's presets.
  - Eight faces, each with its own portrait and landscape layout: Almanac, Instrument, Poster, Under the clock, Photo, Note, Return card and Minimal.
  - Boox reads the picture fresh at every sleep but never while asleep, so the studio redraws it while the tablet is awake (every 5 minutes by default, and on battery, time and rotation changes).
  - The picture is set through the same unprotected broadcast Boox Settings uses, sent from the app itself.
  - *Over Transparent* keeps Boox's Transparent style and lays a plate over it through its sticker file (needs Shizuku).
- **Swipe paging** on home and in All apps.
- A **BooxUltimatum settings key** in the home header, beside Boox Settings and Edit.

### Fixed

- **Battery log:** the "boot" rows were force-stops (Android 15+ sends the boot broadcast to a force-stopped app), so real boots are now told apart by the boot count. The log now records screen, plug and level transitions, front light, Wi-Fi and Doze state. Deep snapshots no longer start the Shizuku helper.
- The home footer and All apps key could vanish or slip down after an inset change, for example switching to gesture navigation. Home now pads only for the system bars, and panels with a text field rise above the keyboard.
- Long one-word app names shrink to fit instead of breaking mid-word.

### Research

- First battery analysis on FW 4.3:
  - Standby is already near the floor (0–12 mA).
  - Screen-on use (430–640 mA, with the processor awake 93 % of the time) is where the battery goes.
  - Doze is off at every boot by design; turning it on changes little, because Boox's own sleep already switches Wi-Fi off and suspends.
- Boox Notes' fast ink is drawn by SurfaceFlinger straight from the pen, and any app can switch that path on over another app (the basis of Instant ink).
- EinkWise's per-app handwriting mode was tried on Sketchbook and didn't engage. Its config was restored byte for byte.

## [0.2.1] (2026-09-26, feedback round)

### Added

- **Whole-tablet font without root.** Fonts › *Use on the whole tablet*, through the broadcast SystemUI handles for Boox Settings (`onyx.action.font.replace.system`). It has a weight choice, a journaled *Restore previous*, and a shortcut to Boox's font settings.
- **Boox apps in All apps:** Notes, Library, Storage, Shop and Boox settings, opened through the Boox home's own entry points.
- **All apps** sorts A–Z, by icon colour or by recent use, as a grid or a list (two columns in landscape).
- Round **glyph keys** for settings, edit, page turns, All apps and Close.

### Fixed

- **Text too thin:** the tablet font here is a variable Manrope whose default instance is ExtraLight. Home now loads the tablet font at every weight, and Appearance › Text sets a weight offset (one step heavier by default).
- The **Settings key** opens Boox Settings. The stock Android app is labelled *Android settings* and the Boox home *Boox home*, so no two icons share a name.
- **System wallpaper** shows through. **Label ink** follows the backdrop: white with a dark halo on dark pictures, black with a paper halo on light ones.
- **Status bar:** the firmware keeps status icons white over apps EinkWise tunes, so they vanished on paper. The app and home draw an ink strip under the bar.
- **EinkWise:** saving an EinkWise setting restricts the app in the background again, so home lifts that on every return.
- **Landscape:** app pages centre and widen to 1200 dp.

### Changed

- BooxUltimatum home became the **default home screen** on the owner's tablet, with the Boox home journaled and one tap away.

## [0.2.0] (2026-09-26)

### Added

- **Launcher:**
  - Folders, icon shapes, wallpapers, and a custom header with 8 Wi-Fi and 8 battery designs.
  - Boox widgets and a grouped widget picker.
  - A two-pane landscape layout, Shorter/Taller widget heights, and a top panel for typing.
- **Appearance** destination: system-wide status bar icons (`icon_blacklist`, journaled restore) and the home's look.
- **Battery log:** a non-wakeup alarm, CSV plus deep JSONL snapshots, a chart, and a one-zip share.
- **Google Fonts browser:** 1946 families with previews, a Vietnamese filter, and install to `/sdcard/fonts` for NeoReader, the home screen or the app.
- Weather with debounced city search and units from the city's country (Open-Meteo, credited).

### Changed

- The Shizuku helper process (about 66 MB) is released after 45 s idle. Measured: the launcher uses about a third of the Boox home's memory and 0 % idle CPU.
- Redrew the logo without a frame, so launcher masks don't double it.

## [0.1.0] (2026-09-25)

### Added

- The hub app:
  - The "Braun Instrument" design.
  - The tweak framework with a journal, and 19 reversible, tiered tweaks.
  - Apps search and per-app detail.
  - Battery drain measurement and wakeup sources.
  - The Settings hub, the Shizuku user service, and the first launcher.
- *Let your apps keep running*: Boox sets `RUN_ANY_IN_BACKGROUND=ignore` on apps you install, which is why music stops and screen sharing goes blank.
- Host tools: `recon.ps1`, `power-logger.ps1`, `pull-apks.ps1` and `start-shizuku.ps1`.
- Research docs: device research, product vision, reverse-engineering plan, architecture, and upstream survey. Package knowledge base seeded from the Note Air 3 C community analysis.

### Decided

- **No root.** The product targets T0–T2 (app, adb grants, Shizuku).
- *Enter deep sleep sooner* was dropped, because Android 16 blocks shell DeviceConfig writes.
