# Changelog

All notable changes to BooxUltimatum. Newest first. The project follows [Semantic Versioning](https://semver.org) loosely while it is below 1.0: minor versions add features, and anything may still change between them. Dates are in the tablet's time zone (UTC+7).

## [Unreleased]

Nothing yet.

## [0.5.0] (2026-09-27)

The sleep screen now updates while the tablet sleeps, with twenty-one faces to use it. Instant ink explains itself on other Boox models, and the app can update itself and file reports. Tested on a Note Air6 C with firmware 4.3 (Android 16).

### Added

- **Faces made for updates while asleep.** Three new faces read what a sleeping tablet can truthfully say every few minutes, each with its own portrait and landscape layout:
  - **Dial:** a Braun-style wall clock with the date, time asleep and the battery.
  - **Clock:** the time as large as the panel allows, with time asleep, the battery and the next event along the foot.
  - **Monitor:** a readout of the sleep itself, with how long, the battery on a tuning scale with what it has used and its rate, the next event counted down and the next alarm.

  Each carries an "Updated 10:25 · every 5 min" line. Without updates while asleep, they show the moment the tablet was put down and say so.
- **The Sleep page puts live first.** Live faces lead the picker with a *Live* tab. *While asleep* sits right under the faces. The preview shows a live face as it reads 1 h 25 min into a sleep. New installs start on the Dial.
- **Ten more faces**, nine of them made for updates while asleep:
  - **Cube:** a cube clock in real 3D perspective, with a cast shadow.
  - **Split-flap:** hours and minutes on flip cards.
  - **Dashboard:** tiles you choose, such as battery, time asleep, alarm, events, day progress, moon, weather and year.
  - **Word clock:** the time spelled out in a letter grid, in five-minute steps.
  - **Day ring:** the whole day on one ring, with events, night and the sleep so far.
  - **Timeline:** today as a line of events, with the sleep marked.
  - **LCD:** a Braun-style display with drawn seven-segment figures.
  - **Sky:** the sun's height through today, sunrise and sunset, and the moon's phase.
  - **Broadsheet:** a newspaper front page, *The Daily Standby*.
  - **Year:** the whole year as dots, and the only still face of the ten.
- **Options for individual faces**, right under the picker:
  - Dial styles: Braun, railway, all numerals, 24-hour.
  - 12- or 24-hour time.
  - The Dashboard's tiles.
  - Cube shading.
  - Word clock style and minute dots.
  - Day ring orientation and night shading.
  - Timeline span.
  - LCD slant and unlit segments.
  - The Broadsheet headline.
  - The Year layout.
- **The face picker is split** into *Live faces* and *Still faces*. Tap the preview to see the face at full size, with the time it took to draw.
- **What faces can know:**
  - The moon phase, worked out on the tablet.
  - Sunrise and sunset for the home screen's weather city (never GPS).
  - The week number and day of the year.
  - The home screen's cached weather, with the time it was read. Nothing is fetched while the tablet sleeps.
- **Live sleep screen (While asleep).** The face can now update while the tablet sleeps, every 5 to 60 minutes, so the date, battery and agenda stay true, and Boox's charging bar is covered. It's off by default. There are options for charging only and for no updates at night. It's been verified on battery on the Note Air6 C: the face goes up a few seconds after sleep and redraws at each step. It needs three things, all allowed on the tablet: an accessibility service (it only holds the picture and can't read the screen), background use, and exact alarms. The Sleep page lists each with a status lamp and a key. The battery cost isn't measured yet.
- **In-app updates.** The Device page's *This app* plate checks GitHub Releases, at most once a day when the app opens or when you ask. It downloads the APK, checks its SHA-256 and signing key, and installs it through Android's installer. Development builds explain that they update from the computer instead.
- **Report a problem or idea.** A form on the Device page fills in the GitHub issue form, or copies or shares the report. You pick what to include and can preview each part: device, app and access, feature status, input devices, battery, and this app's recent log. Nothing personal is included, and nothing is sent until you press a key.
- **One answer to "which tablet is this".** A single device profile recognises Boox from the build and from Boox's system app, and takes the pen from Android's own list of input devices. The launcher, the Apps page, Instant ink and reports all use it.
- **Instant ink diagnostics.** The Ink page shows the pen's input device, the display route, the pen's pressure range and a live pen test, so a report from an untested tablet says what's missing.

### Fixed

- **Instant ink stayed "Ready" and never switched on without usage access.** Android reports no foreground app without it, and raises no error. The Ink page now checks for it and opens the permission page, and the README no longer lists Instant ink as needing nothing.
- **The pen could be misread on other models.** Android doesn't let apps read the kernel's input device names, so 0.4 always used `event5`, which on other tablets can be a different device. The app now listens to every readable input node, takes the one that reports a pen tool, and remembers it.
- **On tablets other than Boox:**
  - The Apps page opened on an empty *Boox* filter.
  - The Boox shelf widget was offered and placed by default.
  - The Sleep and Ink pages, which need Boox firmware, were shown.
- **Large system fonts:**
  - The left rail couldn't scroll, so its last tabs were unreachable on a 7" tablet.
  - The clock's time overlapped its dial.
  - Calendar days were clipped.
  - Home widget text now follows the system font size up to 115 %.
- **Background work when BooxUltimatum isn't the home screen.** Settings › Home screen now says when Boox's restriction could stop the sleep screen's refreshes and the battery log, and links to the switch that lifts it.
- **The live face now goes up a few seconds after the tablet sleeps**, not only at the first update, and it carries on if the app is updated while the tablet sleeps.
- **The status bar's battery percentage** can't be shown without the battery icon, because Android draws the figure inside the icon. The Appearance page now says so instead of offering a switch that does nothing.
- **The Clock face** no longer cuts its readings short in either orientation. The **Monitor** face fills its landscape layout.

### Research

- **The sleep screen is Onyx's doze dream.** Any app can make it wake the display with `onyx_dream_refresh`, and only an accessibility overlay shows over it while the tablet is locked.
- **Boox's power manager clears wake-up alarms during sleep** for apps that aren't on its full-access list. An app joins the list when its background usage changes to allowed.
- **Boox's charge limit is fixed at 80 %.** Other levels would need root: the threshold files can't be read even by the shell, so Shizuku can't change them.
- **Apps can't read `/sys/class/input`** (SELinux), but they can read the `/dev/input` nodes. Reports from a Note Air 2 Plus and a Go 10.3 Gen II Lumi (#1, #2) led to the Instant ink fixes above.
- **Agent Skills for anyone working on the project with an AI agent**, in `.agents/skills/`: tablet testing, the firmware interfaces found so far, how sleep faces are built, and building and releasing. `tools/host/ui.ps1` drives the app over adb for tests and screenshots.

## [0.4.1] (2026-09-27)

### Fixed

- **Boox App Freeze is now always one tap away.** It's in BooxUltimatum's Settings hub (search "freeze"), and on every app's page whether or not Shizuku is running. In 0.4.0 the button only appeared when Shizuku wasn't running.

## [0.4.0] (2026-09-27, the first public preview)

The first build published as an APK. It folds in the research from the Reddit launch thread, so the answers to it are fixes and features rather than apologies. Tested on one Note Air6 C, firmware 4.3, Android 16.

### Added

- **Instant ink** (Ink destination, experimental). While an app you pick is in front, the display draws the pen stroke straight onto the panel as Boox Notes does, and the app's own stroke replaces the preview half a second after the lift. It needs no root, no Shizuku and no change to the app. Checked with a real pen in Sketchbook:
  - Boox's display service can hold an app's frames back while the pen draws and let them through after the lift. That swaps the preview for the app's stroke without closing the pen session.
  - The frames are held from **hover**, as Onyx's SDK arms at hover, rather than from pen-down. The one stroke in ten that used to lose its first millimetres of preview no longer does.
  - The stroke is sent as **style, then width, then colour**, after the session starts. Choosing a style resets the width to its default, so the width setting had never applied before; it does now.
  - The preview region is a square as large as the panel's long side, because the service reads it in the panel's landscape frame. A portrait-shaped region missed the bottom quarter.
  - The route check also asks for the pen's pressure range, a device constant, so firmware with different codes fails the check instead of silently drawing nothing. A held side button counts as the eraser.
  - It tries Onyx's own firmware helper (`android.onyx.ViewUpdateHelper`, the class the SDK calls) first, then direct display calls. On FW 4.3 Android blocks the helper for third-party apps, so the direct route is used; the Ink page shows which one.
- **Power-off screen.** Sleep › *Use as power-off screen* makes the chosen face what the tablet shows when switched off, through the same Boox broadcast with type 17, in portrait and without the battery and put-down time. Boox confirms it saved the picture; how it looks at a real power-off is still to check.
- **Works without Shizuku** in more places:
  - **The whole tablet's font:** the font is written to `Documents/BooxUltimatum/` through MediaStore and the switch is broadcast by the app itself. Verified with Shizuku stopped.
  - **Making BooxUltimatum the home screen:** opens Android's own Default home app page when Shizuku isn't running, still remembering the Boox home for going back.
  - **Background restrictions:** each app's page links to Android's App battery usage page ("Allow background usage", the same switch Boox turns off) and to Boox's App Freeze page.
- **Boox's hidden App Freeze page.** Firmware 4.3 still has the Freeze Settings page a Redditor described (Freeze new apps, Auto Freeze, a switch per app), but not in its menus. BooxUltimatum opens it directly. Thanks to the Redditors who described the freeze settings and the long-press Optimize › Others route.
- **Setup scripts** for owners with a computer: `tools/host/grant-permissions` grants the four one-time permissions, and `tools/host/start-shizuku` starts Shizuku after a reboot, each for Windows (`.ps1`) and macOS or Linux (`.sh`).
- **Screenshots** of the app and home screen in `docs/screenshots/`, taken on the tablet and anonymised.
- **A landing page and technical guide** in `site/`, published to GitHub Pages: what it does, the access levels, and how each part works, with links to the exact files.
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
- **The README explains Shizuku:** what it is, what each access level adds, and how to start it with the bundled scripts.

### Fixed

- **Corrected: "undocumented" was wrong for Instant ink and the sleep screen.** The fast pen path is a documented Onyx feature, offered to app developers through Onyx's pen SDK ([onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo)) for drawing inside their own app, and the screensaver broadcast is in the same SDK. What Instant ink adds is using the path on behalf of apps that don't include the SDK, and handing the stroke back after the lift. The README, the website and `THIRD_PARTY.md` now say so and credit the SDK. Thanks to the Redditor who pointed it out.
- **Corrected: the status bar claim.** Tablet-wide, BooxUltimatum shows or hides the system's status bar icons (and this needs only the one-time adb grants, not Shizuku). The custom Wi-Fi and battery designs are drawn in its own home header. Replacing the system's icon artwork would need a system overlay, which Android allows only with root.
- **Corrected: Freeze Settings.** An earlier note said the option wasn't on firmware 4.3; the page is there, only hidden from the menus.
- **Sleep screen after a rotation.** Turning the tablet and sleeping soon after showed the face cropped, because the face for the new shape was only drawn seconds later.
  - Each refresh now also prepares the other orientation, and a turn swaps it in at once.
  - A last check runs as the tablet starts to sleep.
  - Verified: rotate then sleep within 0.2 s in both directions, and rotate and sleep at the same instant from the lock screen.
- **The sleep screen style switch is never sent while the tablet is asleep.** Other developers report that doing so blanks the sleep screen to white; it now waits for the next wake.
- **Boox's bottom bar on the sleep screen** (battery and "Press power button to wake up") is now switched off through Boox's settings, and faces use that strip. Boox still forces the bar on while the tablet charges; the studio says so.
- A new version redraws the sleep face on its own after installing.

### Research

- Three research passes: Onyx's SDK and open-source apps that use it (Notable, CalliPlus's notes, KOReader, Readest), getting Shizuku through restarts without a computer, and e-ink UI and sleep-screen tools. The findings behind this release are in `knowledge/experiments.md`. Shizuku surviving restarts and a full-refresh key are planned for 0.5 and 0.6.

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
