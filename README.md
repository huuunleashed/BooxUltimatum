# BooxUltimatum

A single sideloadable Android app that is meant to become the go-to hub of tweaks and tricks for the **BOOX Note Air6 C** (and, eventually, other Boox tablets). Its goals are longer battery life, simpler access to Boox's scattered settings, and privacy. Every tweak will be measured, explained, and reversible. The project is licensed GPL-3.0-or-later so we can build on the open-source Boox community's work.

> This README changes as the project moves. It's the single place to see where things stand. Agents and contributors update it with every meaningful change (see `AGENTS.md`).

## Status

| Area | State |
|---|---|
| Phase | **Building on the device (phase 3).** Version `0.3.0` is installed on the tablet (release build) at tier T2 and validated screen by screen in portrait and landscape. |
| App | Ten destinations: Overview, Battery, Tweaks, Apps, Appearance, Fonts, Sleep, Settings, Device and Access. The "Braun Instrument" design runs throughout: paper white, black ink, one green lamp, Archivo, and no animation. |
| Tweaks | 19 reversible, tiered tweaks with a journal. Each one is listed with its mechanism and verification status in `knowledge/experiments.md`. They include *Let your apps keep running*, which fixes music stopping and Discord screen share going blank (Boox background-restricts apps you install). |
| Battery | Drain measurement, a time-left estimate, wakeup sources, force idle, and a battery log that never wakes the tablet: samples every 30 min of awake time, a row at every screen, plug and level change, and deep snapshots every 3 h, with a level chart, screen-off and screen-on drain, and a one-zip export. First analysis (2026-09-26): standby is already near the floor (0–12 mA); screen-on use (430–640 mA, SoC awake 93 % of the time) is where the battery goes. |
| Sleep screen | **Sleep screen studio:** eight faces (Almanac, Instrument, Poster, Under the clock, Photo, Note, Return card, Minimal) set in the owner's font, portrait and landscape, published as Boox's sleep picture and refreshed while the tablet is awake (every 1–30 min, plus battery, time and rotation changes). Verified live on the tablet. An *Over Transparent* mode lays a plate over Boox's Transparent style through its sticker (T2). |
| Home screen | **BooxUltimatum home is the default home screen on the tablet (since 2026-09-26, 06:20).** It has widgets (including Boox's own), folders, app shortcuts, icon shapes, wallpapers, a two-pane landscape layout, and a custom header. It uses about a third of the Boox home's memory and 0 % CPU when idle (`docs/05-launcher.md`). The Boox home is saved and one tap away: see *Going back to the Boox home*. |
| Appearance and fonts | System-wide status bar icons with one-tap restore, the home's look, and a full Google Fonts browser (1946 families, Vietnamese coverage, install to NeoReader). |
| Device facts | Core facts verified: QCS6690 "volcano", kernel 6.1 GKI, Android 16, **locked bootloader**, Doze off in firmware, and all Onyx apps Doze-allowlisted. See `docs/00-device-research.md` §0 and `knowledge/experiments.md`. |
| Root | **Out of scope (decided 2026-09-25).** The product targets T0–T2 (app, adb grants, Shizuku). |

### Next up
1. **Instant ink** (deferred): BooxUltimatum can already make the display draw the pen stroke instantly over any app, as Boox Notes does, but the swap back to the app's own stroke after a lift is unsolved (see `knowledge/experiments.md`). The prototype is parked outside the repo.
2. **Reading mode:** one key for Wi-Fi and Bluetooth off, Battery Saver on and auto-sync paused while unplugged, journaled. The battery analysis found Wi-Fi active 75 % of battery time and the SoC awake 93 % of screen-on time.
3. Unplugged overnight run with the improved battery log, then the first A/B (*Pause idle Boox apps*, *Let Boox apps sleep*, *Turn on Doze*).
4. Record round trips for the three tweaks not yet verified on the tablet (`doze.boox_allowlist`, `power.autosync`, `privacy.ota`).
5. Launcher extras: notification dots (opt-in listener), Onyx front-light and refresh quick actions, layout backup.

Note: Shizuku stops on every reboot. Restart it with `.\tools\host\start-shizuku.ps1`. NA6C FW 4.3 hides Wireless debugging, so on-device restarts without a PC are still unverified. *Turn on Doze* also resets on reboot.

## Why this exists

The most credible independent review ([eWritable](https://ewritable.net/brands/boox/tablets/boox-note-air6-c/)) calls the NA6C an excellent, versatile tablet held back by **poor battery life** (≈2 %/h awake idle, ≈10 %/h in native Notes on FW 4.3) and by **complex, scattered software**. BooxUltimatum goes after exactly those two problems, plus privacy. See `docs/01-product-vision.md`.

## Repository map

| Path | What |
|---|---|
| `docs/00-device-research.md` | NA6C hardware/software research, the NA5C comparison, known issues, and privilege tiers |
| `docs/01-product-vision.md` | Pillars: Battery Doctor, Settings Hub, E-Ink Profiles, Privacy & Debloat, Power-user |
| `docs/02-reverse-engineering-plan.md` | The phased plan for testing together, and the battery test protocol |
| `docs/03-architecture.md` | App architecture, tweak model, and sampler design |
| `docs/04-upstream-projects.md` | Open-source projects we fork or borrow from, with their licenses |
| `docs/05-launcher.md` | The BooxUltimatum home screen: compatibility rules, features, and its measured resource budget |
| `PRODUCT.md` | Product and design context: users, tone, and the Braun Instrument design language |
| `app/` | Android app (Kotlin, Jetpack Compose, e-ink-first UI), including the launcher in `launcher/` |
| `knowledge/` | Onyx package knowledge base (also bundled as app assets) and the experiments log |
| `tools/host/` | PowerShell scripts run from the PC over adb |
| `tools/dev/` | Repo hygiene tools (for example `prose_wrap.py`) |
| `captures/` | Raw device captures (git-ignored, may contain personal data) |

## Quick start

Prerequisites (already installed on the dev PC): JDK 21, Android SDK (platform 36, build-tools 36) in `%LOCALAPPDATA%\Android\Sdk`, and platform-tools on `PATH`.

```powershell
# Build the app (release: minified, debug-signed so it updates the installed copy in place)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'
.\gradlew.bat assembleRelease        # -> app\build\outputs\apk\release\app-release.apk

# With the tablet plugged in (USB debugging on)
.\tools\host\recon.ps1 -Label factory
adb install -r app\build\outputs\apk\release\app-release.apk
.\tools\host\start-shizuku.ps1        # after every tablet reboot
.\tools\host\power-logger.ps1 -Action Start -Interval 60   # then unplug
```

## Going back to the Boox home

BooxUltimatum saved the Boox home (`com.onyx/.StartupActivity`) before switching, so going back never loses anything:

- **On the tablet:** open BooxUltimatum › Settings › Home screen and tap *Back to Boox*. Or, from BooxUltimatum home, tap *Notes · Boox home* on the Boox shelf to use the Boox home for a moment without switching.
- **From a computer, if BooxUltimatum ever can't open:** `adb shell cmd package set-home-activity --user 0 com.onyx/.StartupActivity`.
- **Without Shizuku or a computer:** Android Settings › Apps › Default apps › Home app, and pick ONYX Launcher.

Recents, gestures, NaviBall and EInkWise belong to the system, not the home app, so they behave the same under either home.
## Privilege tiers

Every feature declares the minimum tier it needs and degrades gracefully without it. **T0** is the plain app. **T1** is permissions granted once over adb. **T2** is Shizuku (shell uid). **T3** is root (Magisk/KernelSU and LSPosed). The core product must stay useful at T0–T2.

## Changelog

- **2026-09-26 (afternoon):** Sleep screen after a rotation, and a cleaner sleep screen.
  - **Rotation:** turning the tablet and then sleeping showed the face cropped, because the face was only redrawn a few seconds after the turn, so Boox still had the picture for the old shape. Each refresh now also prepares the other orientation, and a turn swaps the file in at once; a last check runs as the tablet starts to sleep. Verified on the tablet: rotate then sleep within 0.2 s in both directions, and rotate and sleep at the same instant from the lock screen, all showed the right shape.
  - **Boox's bottom bar** (battery and "Press power button to wake up") is off in Boox's screensaver settings, and faces no longer leave room for it. Boox still forces it on while the tablet is charging (read from its code, confirmed on battery); the studio says so.
  - A new version now redraws the sleep face on its own after installing.
- **2026-09-26 (midday, 0.3.0):** Sleep screen, battery analysis, launcher fixes, pen research.
  - **Sleep screen studio (new Sleep destination):** your own sleep screen in your font instead of Boox's presets. Boox reads its sleep picture fresh at every sleep but never while asleep, so the studio re-renders while the tablet is awake (every 5 min by default) and on battery, time and rotation changes. Found and verified the unprotected broadcast Boox Settings uses to set the picture, working from the app itself. *Over Transparent* keeps Boox's Transparent style and lays a plate over it through its sticker file (T2). With Boox's own clock and motto set to None, only the face shows.
  - **Battery log fixes from the first real log:** the 33 "boot" rows were force-stops (Android 15+ sends boot to a force-stopped app), so real boots are now told apart by the boot count. The log now records screen, plug and level transitions, front light, Wi-Fi and Doze state, and its deep snapshots no longer start the Shizuku helper. Analysis: standby is near the floor, and screen-on use with the SoC awake is where the battery goes.
  - **Launcher:** swipe left or right to turn pages on home and in All apps. The page footer and All apps key could vanish or slip down after an inset change (for example switching to gesture navigation), because home froze itself at the tallest height it had seen; home now pads only for the system bars and ignores the keyboard, and panels with a text field rise above it. A BooxUltimatum settings key sits in the header beside Boox Settings and Edit. Long one-word app names shrink to fit rather than breaking mid-word.
  - **Pen latency research:** Boox Notes' ink is drawn by SurfaceFlinger straight from the pen, and any app, BooxUltimatum included, can switch that path on over another app. Swapping the preview back to the app's stroke isn't solved yet, so *Instant ink* is deferred. EinkWise's per-app handwriting mode was also tried on Sketchbook and didn't engage; its config was restored byte for byte.
- **2026-09-26 (morning, feedback round):** Fixes from the first day as default home.
  - **Whole-tablet font without root:** found the switch Boox Settings uses (a broadcast SystemUI handles, `onyx.action.font.replace.system`) and built it into Fonts › *Use on the whole tablet*, with a weight choice, journaled *Restore previous*, and a shortcut to Boox's font settings. Verified: Inter applied tablet-wide and Manrope restored, both from the app.
  - **Text too thin:** the system font here is a variable Manrope, and Android hands apps its ExtraLight default. Home now loads the tablet font itself at every weight, custom fonts get real weights, and Appearance › Text sets how much heavier than designed (default one step).
  - **Settings key** opens Boox Settings. The stock Android one is labelled *Android settings* and the Boox home *Boox home*, so no two icons share a name.
  - **Boox apps in All apps:** Notes, Library, Storage, Shop and Boox settings now appear, opened through the Boox home's own entry points.
  - **All apps:** sort A–Z, by icon colour, or recently used, and a grid or list view (two columns in landscape).
  - **Icon keys:** settings, edit, page turns, All apps and Close are round glyph keys.
  - **System wallpaper** now shows through (the window had kept a white background). **Label ink** follows the backdrop: white with a dark halo on dark pictures, black with a paper halo on light ones; header and footer sit on paper plates over a wallpaper. The paper veil starts at 50 and steps by 10.
  - **Status bar:** this firmware keeps status icons white over apps EinkWise tunes, so they vanished on paper. The app and home now draw an ink strip under the bar.
  - **EinkWise:** Regal saves when chosen from the EinkWise panel (verified in the Onyx config). Saving an EinkWise setting re-restricts the app in the background, so home now lifts that on every return.
  - **Landscape:** app pages centre and widen to 1200 dp, so Settings no longer leaves a gap on the right.
- **2026-09-26 (morning):** Made **BooxUltimatum home the default home screen** through Settings › Home screen, so the Boox home is journaled and restorable in one tap. Verified as default: the Home key and Back stay on home, app shortcuts appear on long-press (Settings offers Wi‑Fi and Battery), Library, Notes (the Boox home) and Storage open from the Boox shelf, Recents (onyxquickstep) opens and returns to home, and after the app process is killed the Home key brings home straight back with the default kept. Added *Going back to the Boox home* to this README.
- **2026-09-26 (night):** A full validation pass on the tablet, in both orientations, with every problem found fixed on the spot.
  - **Landscape:** home now puts widgets and apps side by side. The launcher's Edit and widget picker, and every panel, are width-capped. State survives rotation in the app and the launcher.
  - **Widgets:** Shorter/Taller height steps. Hosted widgets get a realistic default height (the Boox Library widget no longer crops its covers). A widget that doesn't fit shrinks to its standard height before it's skipped, it never pushes the apps off screen, and Edit and the footer say when one is hidden. The half-width clock shows the time and date when there's room.
  - **Typing on home:** city search and the note now open in a top panel above the keyboard, and home no longer reflows when the keyboard opens. Before this, the weather field lost focus in landscape as soon as the keyboard appeared.
  - **Weather:** debounced city search with searching, no-match and offline states. Units follow the chosen city's country. A new city now refreshes at once (a stale timestamp used to delay it by up to an hour). Open-Meteo is credited.
  - **Resources:** the Shizuku helper process (about 66 MB) is released after 45 s idle instead of living as long as home. Measured: the launcher uses about a third of the Boox home's memory and 0 % idle CPU.
  - **Battery log:** opening the app no longer postpones the timed sample. Samples on open are limited to one per 10 minutes. Logs share as one zip with a README. The "asleep" figure explains that it's low while plugged in.
  - **Icons and look:** Boox's own icon frames are detected and removed inside shapes, so each icon has one outline. Tile labels are one weight heavier for e-ink. Picture wallpapers apply EXIF rotation and can be removed. Choosing *A picture* reuses the stored one.
  - **Appearance:** the status bar list shows the common icons first, and the battery icon is always offered.
  - **Tweaks:** changes run in an app-wide scope, so the serif font overlay (which recreates the screen) no longer logs a false failure. Journal entries read as sentences ("Status bar: E-ink refresh mode hidden").
  - **Polish:** plural-correct counts ("1 app"), a clearer launcher description in Settings, lint clean of new warnings, version 0.2.0. Docs updated: `experiments.md` evidence log, `05-launcher.md`, `03-architecture.md`, `THIRD_PARTY.md`.
- **2026-09-26:** The launcher gained folders, icon shapes, wallpapers, a custom header (8 Wi-Fi and 8 battery designs, network name through Shizuku), Boox widgets and a grouped widget picker. The new Appearance destination controls status bar icons system-wide (`icon_blacklist`, journaled restore) and the home's look. The battery log was added (non-wakeup alarm, CSV plus deep JSONL, chart, share). The Google Fonts browser covers 1946 families with previews, a Vietnamese filter, and install to `/sdcard/fonts` for NeoReader, the home screen or the app. A serif system font tweak was added. Redrew the logo without a frame, so launcher masks don't double it.
- **2026-09-25 (late night):** Built the BooxUltimatum launcher (widgets: clock, month, agenda, weather, battery, alarm, note, Boox shelf, and hosted app widgets), plus the home-screen switcher with a journaled restore. Found why music and screen share stop in the background (Boox sets `RUN_ANY_IN_BACKGROUND=ignore` on installed apps) and added the fix as a tweak and per-app key. Added the Shizuku user service, the tweak framework and journal, 19 tweaks, Apps search and per-app detail, battery drain measurement and wakeup sources, and the Settings hub. Designed the "Braun Instrument" UI. Dropped *Enter deep sleep sooner*, because Android 16 blocks shell DeviceConfig writes.
- **2026-09-25 (night):** Decided **no root**; the product targets T0–T2. Installed the probe app and Shizuku v13.6.0 (signature verified: CN=Rikka) on the tablet. Granted the T1 permissions and confirmed tier T2 on the device. Added `tools/host/start-shizuku.ps1`. The probe app now reports when Shizuku is unreachable and refreshes on Shizuku binder and permission events.
- **2026-09-25 (evening):** First device connection. On FW 4.3, developer mode is enabled via *Settings → More Settings → USB Debug Mode*. Captured the read-only factory baseline. Verified the SoC (QCS6690 "volcano"), kernel 6.1 GKI, locked bootloader, partition layout and shell permission limits. Found that all 21 Onyx packages are Doze-whitelisted. Switched `power-logger.ps1` to `dumpsys battery` (charge counter). Knowledge base updated with presence data and 13 new packages.
- **2026-09-25:** Project initialised. Device research, product vision, reverse-engineering plan, architecture, and upstream survey written. Android toolchain installed. Probe app `0.1.0-probe` scaffolded and building. Host recon, power-logger and APK-pull scripts added. Package knowledge base seeded from the NA3C community analysis. Repo rules added (no hard-wrapped prose, reversible tweaks, GPL-3.0).

## License

GPL-3.0-or-later (see `LICENSE`). Third-party code and attributions are listed in `THIRD_PARTY.md`.
