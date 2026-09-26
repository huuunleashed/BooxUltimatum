# 06 · Sleep screen studio

Goal: a sleep screen worth looking at. The owner picks a face, sees it exactly as the panel will show it, and BooxUltimatum keeps it current while the tablet is in use, without adding a single wakeup.

## How Boox draws the sleep screen (verified on NA6C FW 4.3, 2026-09-26)

- The sleep screen is a doze dream, `com.onyx/com.onyx.common.dream.OnyxDaydreamService`. Its style (Image, Transparent and several presets) and its pictures live in `com.onyx`'s private MMKV, which no other app can read or write.
- **Image style.** Broadcasting `onyx.action.SCREENSAVER` with `type` = 16 (int), `file` = an absolute path (string) and `show_result_hint` = false (boolean) switches the style to Image with that file as its only picture. The receiver is dynamic, exported and needs no permission, and lives in the always-running `com.onyx` process. Verified from the shell uid; the app uid is expected to work the same way and is still to be confirmed on the tablet.
- A file directly in `/sdcard/Pictures/` is used in place. A file anywhere else is copied into `Pictures/` once, and later overwrites of the original are ignored.
- The file is decoded afresh at every sleep, so overwriting it between sleeps changes the next sleep screen. It is not read again while the tablet sleeps. Files above 10 MB are dropped and Boox falls back to its default.
- Boox scales the picture with centre-crop to the current rotation: 1860 × 2480 in portrait, 2480 × 1860 at rotation 90 or 270 (logcat).
- Boox draws its own overlay on top of any style: a clock (Digital, LED, Analog or None, redrawn every 5 minutes while asleep, the only live element possible), a motto (Text display), and a bottom status bar with the battery level and "Press power button to wake up". These are set in Boox Settings › Desktop & Screensaver › Screensaver.
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

Nothing on the picture can change while the tablet sleeps, because Boox reads it once when the tablet goes to sleep. The only live element is the Boox clock. So the face is kept current while the tablet is awake, and only then:

- **Refresh alarm.** `setInexactRepeating` on `ELAPSED_REALTIME` (not the wakeup variant) at the chosen step: 1, 5 (default), 15 or 30 minutes. It never fires while asleep; a missed one is delivered on wake. An armed alarm is left alone, so opening the app never pushes the next refresh away; a changed step re-arms it.
- **Events the system sends anyway.** Battery level or plug changes (at most one render a minute), rotation, time, date and time-zone changes, screen on, and a calendar content observer once READ_CALENDAR is granted. Triggers are coalesced for two seconds, and edits in the studio request a refresh.
- **Fingerprint.** Each render first gathers what the face shows (with the put-down time rounded down to the step) and hashes it with the spec, the size, the font file and the photo. An unchanged fingerprint skips the render, the encode and the write.
- **Sleep now** (T2) renders with the exact minute, writes, then sends `input keyevent KEYCODE_SLEEP` through Shizuku.
- The put-down time is phrased honestly: "Put down at 10:45" when it is exact (Sleep now or the 1-minute step), "Put down around 10:45" when it is rounded down to the step.
- Renders run on `Dispatchers.Default`, one at a time. The full-size bitmap and the decoded photo are reused across a burst of renders and released a minute after the last. The status line shows the last publish with its render and encode times, size, file and mode, or that it was skipped.
- In Sleep image mode a refresh only writes the file; the broadcast is sent on Apply and when the path changes. In Over Transparent mode every refresh that changes the face briefly starts the Shizuku helper (about 66 MB, released after 45 s), which is why that mode is T2 and worth a longer step.

## Faces

Eight full-screen faces for Sleep image, each with its own portrait and landscape composition, and four plates for Over Transparent:

- **Almanac:** weekday and year, a big day number, the month, put-down time and battery beside it, the month grid with today in the accent and event days dotted, and the next events.
- **Instrument:** a Braun tuning scale for the battery with the figure riding an accent needle, the date, a lamp lit while charging, and ruled readings (put down, next event, owner).
- **Poster:** the weekday set as large as the panel allows over an accent bar, then the quote of the day (a small bundled public-domain set) or the user's own, or the day number when the quote is off.
- **Under the clock:** composed around the Boox clock: a rule and a lamp under it, then the date, a battery ring, readings, events, note and owner.
- **Photo:** the user's picture (picked with the photo picker, EXIF rotation applied, stored in app files), filled or fitted, optionally pre-dithered (Floyd–Steinberg, 16 levels per channel, on the photo only), with a caption band that holds the Boox status bar. Photos without dither go out as JPEG; everything else is PNG, falling back to JPEG above 9.5 MB.
- **Note:** the user's note set as large as it fits.
- **Return card:** "If found, please return to", the owner's name, contact and reward line.
- **Minimal:** the date and the lamp.
- **Plates** for Over Transparent: bottom band, top band, corner card and centre card, on a transparent sheet with a soft paper edge and an ink rim. Each is measured before it is painted; a card that would not fit drops its most optional parts (quote, note, owner, events) first.

Every face leaves the bottom strip to the Boox status bar, and **Leave room for the Boox clock** keeps the top 22 % free on any face for users who keep a live clock. The guidance on screen asks for Clock style and Text display set to None for a clean face.

Type follows the owner's choice: the tablet font by default (the Boox system font file, copied once through Shizuku if it can't be read directly, instanced by `wght` when it is variable), bundled Archivo, or any kept Google family (its real weight files). Large type is Semibold to Black, small type Regular to Semibold; nothing thinner is offered. Ink is black on paper or white on black; the accent (green, red, blue, orange, yellow or ink) only fills shapes, never text.

## Code

- `core/sleep/SleepSpec.kt`: modes, faces, plates, elements, the serialisable `SleepFaceSpec`, `SleepStore` and `SleepStatus`.
- `core/sleep/SleepData.kt`: what a face shows, in display form, and its fingerprint.
- `core/sleep/SleepRenderer.kt` and `SleepFaces.kt`: pure Canvas typesetting (cap-height alignment, fitted blocks, the scale, ring, grid and plates) and the dither. No Context and no I/O, so a face renders the same at full size, as a preview and as a thumbnail.
- `core/sleep/SleepFonts.kt`: typefaces at any weight.
- `core/sleep/SleepPublisher.kt`: MediaStore pictures, the broadcast, the sticker copy and its backup, and the Boox settings link.
- `core/sleep/SleepStudio.kt`: render, skip, encode, publish, apply, Sleep now, restore.
- `core/sleep/SleepScheduler.kt`: the alarm, the receivers (registered from `BooxUltimatumApplication` in the main process) and `SleepReceiver`.
- `ui/screens/SleepScreen.kt`: the studio. Two panes in landscape, sheet and keys on top in portrait; text entry in a panel at the top.

## Still to verify on the tablet

- The type 16 broadcast sent from the app uid, without Shizuku.
- MediaStore overwrites with `openOutputStream(uri, "wt")` picked up at the next sleep.
- The Boox status bar and clock zones used for the layout guides (bottom 6.5 %, top 22 %).
- Render and encode times at full size for each face, and the PNG size of a pre-dithered photo.
- The explicit `DREAM_STYLE_SETTING` launch opening the style page.
