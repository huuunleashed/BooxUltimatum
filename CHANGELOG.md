# Changelog

All notable changes to BooxUltimatum. Newest first. The project follows [Semantic Versioning](https://semver.org) loosely while it is below 1.0: minor versions add features, and anything may still change between them. Dates are in the tablet's time zone (UTC+7).

## [Unreleased]

Writing and drawing the way BOOX's own apps do it, continued in Nib. The hub 0.7.0 below keeps what this track already proved on the tablet (the 1-minute live step and batched instant ink). Nothing below has been tried on the tablet yet unless it says so.

### Added

- **Nib writes the way BOOX's own apps do.**
  - Quick strokes follow each other with nothing reaching the panel in between. The display holds Nib's frames for the whole writing session, and Nib's ink replaces the preview only at a break: a touch on a control, a panel, undo, a tool change, a gesture.
  - Every floating card is kept clear of the preview at once.
  - While you write, the cards that change (undo, redo, the pen slots, the sizes) are drawn straight into the display's layer, so they stay current.
- **Nib: Show the finished ink** (Settings › Display preview):
  - **Auto**, the default: at breaks for the pens the display previews faithfully; for textured, blended and see-through ink, after you pause, by drawing Nib's exact ink into the display's layer without a refresh.
  - Or at breaks only, after a pause of your choice (0.4 to 2 s), or after every stroke as before.
- **Nib: the display draws more of what you do.** The pen's eraser end shows its track as you erase, and the lasso its dashed path, instead of pausing the preview.
- **Nib: pens matched to BOOX's own.**
  - The fineliner is the native plain pen.
  - The fountain pen's pressure sensitivity and smoothing are sent to the display, so the preview thins the way the ink does.
  - The marker is laid down opaque and blended at half strength.
  - Pencil, graphite and both charcoals shade broader and lighter when you tilt the pen.
  - A new Asian calligraphy pen joins the Latin one.
  - Every preview style BOOX Notes uses is offered.
- **Nib: fast refresh while you move the page** with your fingers, as in BOOX's own apps, cleaned up once you stop. You can turn it off in Settings › Fingers.
- **Nib: Palm guard** (Settings › Fingers, off until it's confirmed on the tablet): finger touch is switched off over the page while the pen is near, so a resting hand can't move it.
- **Nib: Refresh screen** in the menu, and one gentle clean after a drawing opens or a panel closes.
- **Nib: Diagnostics › Lab**, with a check for each new display call, and the answers logged for the next build.
- **Nib Lab probes pen state 4 and the eraser painters.** One surface holds state 4 (which the firmware names erasing but never sends) and reads it back; another tries eraser painters 0 to 8, one band each. Sessions now set the SDK’s raw-drawing defaults on open (brush previews on, the eraser end previews Nib’s eraser track) and put them back on release, and the stray-session cleanup also tells the display its client died.
- **For developers: `kit:ink` is now the suite's ink SDK.** It has the complete display interface: the pen session, several excluded areas at once, what each part of the pen draws, the style's own parameters, pictures pushed into the display's layer, refreshes in any update mode, the fast mode, the display's geometry and finger touch switched off in areas. On top of that sit a writing choreography every drawing surface shares, a palm guard, and a guard that undoes whatever a crashed process left on the display. `docs/09-ink.md` describes it, with the full call table and a ledger of every finding and where it's used.

### Changed

- **Nib's pens are calibrated to BOOX's own, measured on the tablet.**
  - The fountain pen thins with pressure as BOOX's does (p to the power of twice its sensitivity), a little more when you write fast, and never below 2 px.
  - The brush follows the square root of pressure, and the marker thins to 80 % at a light touch.
  - The pencil keeps its width and lets pressure set how dark it is.
  - The charcoal broadens with tilt from about 15°, exactly as the display's preview does, so the two now match; before, the preview looked much thicker.
  - The pencil, graphite and charcoal no longer thin with pressure.
  - Strokes already drawn keep their look. Drawings are now format 1.2 and open unchanged.
- **Thin pencils and fineliners are previewed in the plain pencil style at every width** (checked on the tablet from 0.5 to 3 px), so their preview is no longer thicker than their ink.
- **Nib ignores a resting pen's repeated samples**, as BOOX's pen reader does, which steadies the ends of strokes. A slow, careful line keeps its shape.

## [0.7.0] (2026-10-01)

Steadier sleep and ink, and a home that keeps frozen apps visible. Tested on a Note Air6 C with firmware 4.3 (Android 16), with the 1-minute live step and instant ink checked on the tablet by the owner.

### Added

- **A Clean screen tile and key** (Quick Settings, and the Instant ink page). They clean all ghosting off the panel in one pass, the way NeoReader cleans a colour panel. The tile waits until Quick Settings has closed.
- **Sleep live updates every minute.** The *While asleep* step now offers 1 minute, for checking that updates arrive without waiting through a 5-minute step. Checked on the tablet: the 1-minute step arrives, and an hour of sleep used about 1 % of battery, sometimes one hour, sometimes two.
- **Home shows Boox-frozen apps instead of hiding them.** Packages Boox froze kept a launch entry stock still opens, but Android hides them from launchers, so Word, Firefox and KOReader vanished from home and search on a reporter’s Lumi (issue #3). The drawer now lists them dimmed with a frozen tag (checked on the tablet with a disabled app), opening still works, and the app panel explains where to stop Boox freezing them again.

### Changed

- **Instant ink batches strokes.** Quick strokes now share one hold, and the app’s own ink replaces the preview once the pen pauses. You choose the pause (0.4 to 2 s, 0.8 s by default), or it happens when the pen leaves, the eraser end comes near, you switch apps or the screen locks. The default pause was half a second before, and the app’s frames went through at each one; the longer default and the longer choices keep more quick strokes in one hold. Each hold is logged with its stroke count and why it ended. Checked on the tablet: instant ink works.
- **Instant ink's recovery** (the Ink page, the notification, and home's check for a leftover session) also clears the display's fast mode, excluded areas and pen settings, and turns finger touch back on. If the hub dies mid-session, its next start ends the session.
- **The Ink page names the computer command for usage access.** On firmware where the switch refuses, it now shows `adb shell appops set app.booxultimatum GET_USAGE_STATS allow` (checked on the tablet).

### Fixed

- **Sleep live updates survive one missed tick.** The alarm receiver re-arms the next update before handing the tick to the service, so a tick that finds the service unbound no longer ends the whole sleep’s chain.

### Research

- **The firmware's full call table, read from the decompiled framework** (`captures/`, read-only; only numbers and short signatures kept): pen-up (16711784) and app-died (16711717) beside the session calls; fed strokes returning the display's computed width; the bitmap, region-config, bypass, turbo and dither payloads; the SDK opening with style 0 and pen state 1 and closing through pause to stop, as `kit:ink` does. NeoReader's wait-for-update is a timed sleep (150 ms minimum, 200 ms on its pen path), not the firmware call. Open tablet probes: fed-stroke widths, pen state 4, eraser painter ids.
- **BOOX's apps hold the app's frames for a whole writing session** (traced on the tablet). In Notes and NeoReader, three bursts of quick strokes with pauses between them produced about 45 preview updates and a single release, when the page was left. The e-ink controller switches to its handwriting scheme once. Nib 0.2 released after every stroke, and the controller switched schemes twice per stroke, which is the lag felt between quick strokes. The native apps release only at breaks: a menu, undo, a pan or zoom, the eraser, leaving.
- **BOOX's pen library measured on the tablet** (penlab, `tools/host/penlab`): the width laws of the fountain, brush, marker, ballpoint and calligraphy, the pencil's opacity, and the charcoal's tilt curve. Only the numbers are kept, in `docs/09-ink.md`. Fast mode, the deep clean and finger-touch areas were also checked from the shell.
- **The display keeps several excluded areas in its multi-region mode** (tested on the tablet with two), so an app can keep all its controls clear of the preview at once.
- **The display's own geometry and pen settings, read back from the tablet:** the panel-to-screen matrix, the digitizer's range, and each preview style's parameters (the fountain's pressure sensitivity and smoothing, the charcoal's tilt, the calligraphy nib's angle). The pen reports tilt, which Android passes to apps.
- **From the decompiled apps and framework**, each still to be tried on the tablet:
  - pictures drawn straight into the display's layer during a hold, which Notes uses to keep its toolbar current;
  - a pen configuration per part (tip, eraser end, side button);
  - the fast mode used while the page moves;
  - update modes by number;
  - finger touch switched off in areas, which Onyx also documents for apps;
  - the widths the display computes for each point.

  The final ink of the native pens is computed in libraries an ordinary app can't load, so Nib keeps its own engine, tuned to the same parameters.

## [0.6.0] (2026-09-28)

BooxUltimatum becomes a suite: the hub you know, a first separate app, Nib 0.2.0 for drawing, and a kit of libraries they share. Tested on a Note Air6 C with firmware 4.3 (Android 16), with a real pen for Nib, and on an emulator shaped like it. Known limits: Nib's preview can still look a little wider than the stroke (Diagnostics › *Match preview* tunes it), the display's preview styles 3 to 7 are still unverified and stand-ins show instead, and neither app has been tried on another Boox model.

### Added

- **Nib's new look and tools.** The canvas fills the screen, with the page lying on a grey desk, and the tools float over it like cut cards: hard black outlines and solid shadows, which stay crisp on e-ink.
  - **The editor:** a pill with Library, Undo and Redo, and one with Brushes, Colour, Layers and the menu. A tool rail holds six pen slots, each drawn as its own stroke, the eraser, lasso, eyedropper and hand, and upright size and opacity sliders. A view chip shows the zoom and the turn, with *Reset view*. There's also a full-screen mode, and the rail can move to the right for left-handers.
  - **Panels** open next to their key. Drag them by the header, pin them to keep them open, and they remember where they were in each orientation.
  - **Every brush shows itself:** the Brushes panel draws each brush's own sample stroke. Brush settings has sliders with − and + keys and typed values for width (with a dot to scale), opacity, pressure (with its curve), smoothing, texture, nib angle, speed and taper.
  - **Colour:** a saturation square and hue strip, a palette tuned for Kaleido, recent colours, hex entry and an eyedropper.
  - **Layers:** thumbnails, opacity, Normal, Multiply or Clip, and keep-transparency.
  - **Turn the page** with a two-finger twist or the hand tool. It snaps upright within 5°.
  - **The lasso** moves, scales and turns strokes, duplicates, recolours or deletes them, and moves them to another layer.
  - **Also:** paper colours and guides (dots, grid, lines); exports with paper, transparent, or each layer as a PNG in a zip; and a straight line when you hold the pen still at the end of a stroke.
  - **The library** has search, sorting, a New card with page sizes (including custom px or mm), and choosing several drawings at once.
  - Everything is undoable, and drawings from 0.1 still open.
- **A font manager** (System › Fonts › Installed), which opens first once anything is installed. It lists every font BooxUltimatum installed: set in its own face, with its styles, size, where it's in use and a lamp for on or off.
  - **Turn off and on.** Turning a font off hands every use back first: the tablet font goes back through the usual restore, the home screen and this app to their own fonts, the sleep screen to the tablet font. The files are then kept aside, so nothing offers the font. Turning it on puts it back.
  - **Delete** does the same hand-back, then removes every copy BooxUltimatum made. Fonts in NeoReader's folder that came from elsewhere are listed but never touched.
  - Also: *Delete unused fonts*, *Turn all off*, sorting, filters, pages, *Use for → Sleep screen*, and an *Installed* filter in the browser.
  - Every change is journaled.
- **Storage** (Device › Storage). It shows what each suite app keeps (logs, crash reports, cache, downloaded updates, shared exports, and your own data such as drawings, fonts and the battery log) and clears temporary files, or logs and reports, per app. Your data is counted but never cleared.
- **Each suite app keeps its leftovers bounded by itself.** Cache files go after three days or past 64 MB, downloaded updates after a day, and shared exports after a week (keeping the newest five). Logs already stay under 8 MB and two weeks.
- **Nib, a drawing app of its own.** It uses Boox's instant pen preview in its own window, like Boox Notes does, with many more brushes, very fine widths and unlimited layers. It installs and uninstalls on its own and works without BooxUltimatum. See `docs/08-nib.md`.
- **A Suite page** (Suite › Apps and modules). It installs, opens, updates and uninstalls the suite's apps, and lists the modules built into BooxUltimatum: Home screen, Sleep screen, Instant ink and Battery log.
  - **Removing a module stops it and undoes what it changed.** The home goes back to Boox, the sleep and power-off screens go back to Boox's own, Instant ink turns off and its Quick Settings tile goes away, and the battery log stops recording.
  - **Its pages leave the menu until you add it back.**
- **Logs for every suite app, in every build.** Each app keeps a logbook of what it did and what went wrong, including crashes and the reason Android gave for each stop, for about two weeks. Device › Logs shows the latest entries, turns on detailed logging for 24 hours, and shares one zip with every suite app's logs. The logs hold no drawings, typed text, or account or network names, and nothing leaves the tablet unless you share it. Problem reports now quote the logbook instead of Android's raw log.
- **Test builds.** *Offer test builds* on the Device page lets the hub install unfinished builds published for checking on a real tablet. They're off by default, and a release always replaces its own test build.

### Changed

- **The menu is five sections instead of eleven pages:** Overview, Suite, Battery, System and Device. A section with several pages shows them as tabs. Links from the home screen, the Quick Settings tile and notifications still open the right page.
- **Home screen switching has its own page** (Suite › Home screen), moved from Settings.
- **The updater looks after every suite app.** It reads more releases, orders test builds correctly before their release, and still checks each APK's SHA-256, package, version and signing key before Android installs it.
- **Instant ink steps aside for suite apps,** as it does for Boox's own, since Nib drives the pen preview itself. Suite apps can't be picked for Instant ink.
- **For developers:**
  - The code is split into modules. `hub/` (formerly `app/`) and `nib/` are the apps, `nib-engine/` holds Nib's drawing model in pure Kotlin, and `kit/` holds the shared libraries (`core`, `log`, `ui`, `ink`, `update`). `docs/07-suite.md` explains how they fit.
  - Unit tests cover the logbook, the release feed and version order, the pen session's display calls and Nib's engine, and CI runs them on every push.
  - `tools/dev/publish.ps1` publishes a release or a test build of either app, with its checksum and R8 mapping file.

### Fixed

- **Home: a faded, shifted copy of the home screen could show under it, and the wallpaper sometimes didn't show.** When the screen turns off, Android keeps a picture of the home screen, and on unlock it can show that picture as a placeholder. Seen through home's transparent wallpaper mode, the picture was a frozen copy from the moment the tablet slept, shifted by the status bar. On other unlocks its white background hid the wallpaper. Home now opts out of those pictures and placeholders, and declares its wallpaper from the start. It's in every refresh mode because it isn't an e-ink effect.
- **System › Tweaks and System › Apps could load forever** once Shizuku was in use (checked on the tablet: with Shizuku running, both pages now load at once). A shell command that never finished blocked the page for good, and a Shizuku service that couldn't be reached cost every call its own ten-second wait.
  - Commands now always time out, and a service that can't be reached makes calls fail at once for a short while.
  - Background restriction is read for every app in one call instead of one call per app.
  - Slow pages and failed commands are logged.
- **Nib: the preview stroke was wider than the stroke that replaced it.** The preview is now sent at the brush's width at your usual pressure, which Nib learns from your strokes, and Diagnostics › *Match preview* tunes it per preview style. On the tablet the preview matched the stroke in the three styles checked, with no extra factor.
- **Nib: the first stroke could lose its start.** The display session now opens drawing rather than paused, and it no longer pauses when the pen leaves hover range over the canvas. On the tablet, the first strokes after opening were whole three times out of three.
- **Nib: a stroke started just after the pen passed over a panel could lose its start.** Nib used to pause the pen preview over its floating panels and resume it at the canvas, and the resume came too late for a quick stroke. Now the preview keeps running and the display is told to leave out the panel under the pen, so the panel stays clean and the next stroke starts at once.
- **Nib: Diagnostics showed the first probe's bands on every tab** and logged no answers for the others. Each tab now has its own surface. The Styles probe sizes its previews like the editor does, so it compares shapes rather than widths.
- **Nib: the marker and highlighter previews didn't show in most colours.** The display drops a translucent preview in any colour but grey or yellow. They're now previewed in solid colour, which covers what's under the stroke until you lift the pen. Settings › Display preview can switch them to a see-through grey as light as the colour instead. Diagnostics has a *Marker colours* probe for this.
- **If Nib was stopped in the middle of drawing, by an update or a crash, the pen preview went on drawing over every app, the home screen included,** until something reset the display. Nib now notes when it holds the display, and its next start ends a session left behind. The home screen also ends any session still drawing a moment after it appears.
- **Fonts › Use for the home screen now switches the home screen's font;** before, it only saved the file. Removing a font from NeoReader no longer removes other fonts whose names start the same way.
- **After a Boox app, Instant ink could open a new session instead of taking over the paused one**, when the display still reported the pause as in progress, and then miss that first stroke.

### Research

- **Region exclusion works on firmware 4.3** (tested on the tablet). One call tells the display to leave a rectangle out of the pen preview: it keeps only the last rectangle sent, an empty list clears it, and it can be given in screen coordinates, which the display turns into its own. Nib uses it for its panels. See `knowledge/experiments.md`.
- **The display's marker style drops translucent colours** (tested on the tablet): at half alpha it shows only greys and yellow, while opaque colours show, very pale ones excepted.
- **The display doesn't notice when the app that opened a pen session dies** (tested on the tablet): the session goes on drawing until any app sets the pen state to stop.
- **Android's list of background-restricted apps comes back one package per line**, which is how the one-call read above parses it.
- **The in-app updater works end to end.** The published 0.5.0 found 0.5.1, downloaded it, checked it and handed it to Android's installer, which updated in place after one "allow installs from this app" prompt. The installed APK matched the published file byte for byte.
- **Firmware 4.3's display helper offers many more calls than Instant ink uses,** read from the decompiled framework: a full refresh, refreshes with a chosen mode, region exclusion, strokes the app feeds itself, stroke parameters. Its eight preview styles are numbered 0 to 7. None of the new calls has been tried on the tablet; Nib's Diagnostics page probes them.
- **A study of steffest/Boox-EinkDraw,** a drawing app for the Note Air4 C. It has no licence and depends on Onyx's binary SDK and a copied system library, so Nib is a clean-room design. Its open zoom problem (the preview stops matching the stroke) shaped Nib's rendering.

## [0.5.1] (2026-09-27)

A steadier Instant ink, rebuilt from measurements with a real pen: the first stroke is previewed, the preview never sticks, and it can be switched from Quick Settings. Tested on a Note Air6 C with firmware 4.3 (Android 16), in Sketchbook and alongside Boox Notes.

### Added

- **An Instant ink switch in Quick Settings.** Turn it on or off without leaving your drawing app. The Ink page has an *Add to Quick Settings* key, and with no app chosen yet the tile opens the Ink page.
- **Turn off and Recover screen on Instant ink's notification**, one pull-down away while you draw.

### Changed

- **Instant ink keeps its display session ready.** It opens as soon as Instant ink is on and waits, paused, whenever the pen is away or another app is in front, then resumes the moment the pen comes near a chosen app. The first stroke now gets the preview: after unlocking, after tapping into the app with the pen, and on quick strokes. Before, the session opened only as the pen arrived, and the display missed the stroke that followed at once.
- **The "While drawing" choice is gone.** The display holds the app's own drawing back while the pen touches, whatever the app does, and letting it through mid-stroke ended the preview. So "Show both" could never show both. The app's brush and colour now always take over after the lift, as *Show only the preview* did.
- **Turning the tablet no longer restarts the session**, since its region already covers both orientations.
- **Boox's own apps are left alone.** Instant ink pauses over them and never ends a session there, since Boox Notes and others run sessions of their own.

### Fixed

- **With "Show both", the black preview stayed on screen for good** and the app's own drawing was held back behind it.
- **The first stroke after unlocking had no preview.** Instant ink now pauses at screen off and resumes, instead of ending and reopening.
- **A quick first stroke lost its touch** while the pen reader was still finding which input is the pen.
- **Recover screen while Instant ink is on** now resets the service's own record too, so the next stroke starts cleanly.
- **A session left behind by an earlier run** (after a crash or an update) no longer keeps the app's frames held: it's cleared when Instant ink starts over this app or a chosen one.
- **The pen reader restarts itself** if its input node goes away, instead of going quiet.
- **The Ink page shows the right state** after the Quick Settings switch or the notification changed it.

### Research

- The firmware holds app frames from each touch by itself (SurfaceFlinger's `HandlePenTrigger`) until `ENABLE_POST` lets them through again. A hold-and-release pulse replaces the preview even when nothing held the frames.
- Letting app frames through while the pen still draws ends the preview for the rest of that stroke.
- A session started after the touch has begun misses that stroke, and one started about 35 ms before it can too. A paused session resumes instantly, even at the touch. On a quick stroke the pen hovers only about 45 ms before touching, and a quick first touch comes in the same batch as the hover.
- A paused session is quiet in other apps: no preview and no held frames. Boox's system app stops the session when Boox Notes opens, and Notes leaves its own session paused when you switch away.

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
