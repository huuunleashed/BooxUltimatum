# captures/

This is where the raw device captures from `tools/host/*.ps1` go, one folder per session: `<yyyyMMdd-HHmm>-<serial>-<label>/`.

These folders are git-ignored because they can contain personal data such as account names, Wi-Fi SSIDs, and logcat. If you want a finding under version control, copy a curated, anonymised summary into `knowledge/` or `docs/` and cite the capture folder name.
