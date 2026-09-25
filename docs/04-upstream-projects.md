# 04 · Upstream projects (fork and borrow candidates)

Checked 2026-09-25 via the GitHub API. The project is GPL-3.0-or-later, so GPL/LGPL/Apache code can be incorporated with attribution in `THIRD_PARTY.md`.

| Project | License | ★ | Last push | What we take |
|---|---|---|---|---|
| [timschneeb/OnyxTweaks](https://github.com/timschneeb/OnyxTweaks) | GPL-3.0 | 66 | 2026-05 | Xposed hooks (per-activity refresh, hidden settings shortcuts, SystemUI tweaks), and its MMKV editor. Targets Android 12 / Go Color 7, so it needs porting to Android 16. |
| [l-althueser/OnyxMMKVEditor](https://github.com/l-althueser/OnyxMMKVEditor) | GPL-3.0 | 9 | 2026-02 | No-root `/onyxconfig` editing, and the handwriting-optimisation app list |
| [onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo) | Apache-2.0 | 222 | 2026-09 | Official SDK usage: `onyxsdk-device` (EPD refresh, front light), `onyxsdk-pen` |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (API) | Apache-2.0 | 30k | 2025-06 | T2 privilege backend (`dev.rikka.shizuku:api`) |
| [aistra0528/Hail](https://github.com/aistra0528/Hail) | GPL-3.0 | 6.7k | 2026-09 | App freeze backends (disable/hide/suspend via Shizuku, root, Device Owner), auto-freeze on screen off |
| [samolego/Canta](https://github.com/samolego/Canta) | LGPL-3.0 | 6k | 2026-09 | Shizuku-based reversible uninstall UX, and its bloat list format |
| [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) | GPL-3.0 | 9.3k | 2026-09 | List schema (`uad_lists.json`). It has **no `com.onyx.*` entries**, so we'll contribute ours upstream. |
| [MuntashirAkon/AppManager](https://github.com/MuntashirAkon/AppManager) | GPL-3.0+ (mixed) | 9k | 2026-09 | Reference for component blocking, appops, and batterystats parsing |
| [Hagb/decryptBooxUpdateUpx](https://github.com/Hagb/decryptBooxUpdateUpx) | WTFPL | 345 | 2026-09 | Firmware decryption. **Missing NoteAir6C key.** |
| [ssut/payload-dumper-go](https://github.com/ssut/payload-dumper-go) | Apache-2.0 | 3.5k | 2026-09 | Extracting `payload.bin` partitions (host tool) |
| [bkerler/edl](https://github.com/bkerler/edl) | GPL-3.0 | 2.6k | 2026-09 | Qualcomm EDL/Sahara probing (host tool, Phase 5 only) |
| [koreader/koreader](https://github.com/koreader/koreader) + android-luajit-launcher | AGPL-3.0 | 30k | 2026-09 | Upstream target: add the `noteair6c` device ID |
| [plateaukao/einkbro](https://github.com/plateaukao/einkbro) | (see repo) | 2k | 2026-09 | Recommended e-ink browser. Reference for e-ink UI patterns. |
| [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed) | GPL-3.0 | 12.5k | 2026-09 | Runtime for the future `:xposed` module (T3) |

Reference guides (docs, not code): [BooxNoteAir4CRootGuide](https://github.com/luisliz/BooxNoteAir4CRootGuide), [temblast EDL](https://www.temblast.com/edl.htm), [fardjad NA3C privacy gist](https://gist.github.com/fardjad/97baf36de97d1c4ae3953b3d359bb918), [carlosonunez Go Color 7 debloat gist](https://gist.github.com/carlosonunez/a0ec3f02576867329bc313bae889563d).

## Forking strategy

We don't fork whole apps into this repo. We vendor small, specific pieces (for example, Hail's Shizuku freeze helper or the OnyxMMKVEditor parser) into the matching module, keep their license headers, and record the source commit in `THIRD_PARTY.md`. That keeps one coherent APK while respecting upstream licenses.
