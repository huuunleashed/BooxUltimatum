# Penlab

A developer tool that runs BOOX's own pen-ink library on the tablet and records what it draws, so Nib's brushes can be measured against BOOX's rather than judged by eye.

It stays a tool: it runs only on the maintainer's tablet over adb, nothing of it goes into an app, and only measured numbers go into the repository (as test values and in `docs/09-ink.md`). The four classes under `src/com/onyx/...` are our own declarations; the library binds its native functions to those class names, so the names and signatures must be these.

## How it works

`app_process` runs `penlab.dex` as the shell user, outside an app's library restrictions. The dex loads `/system/lib64/libneopen_jni.so` and feeds it strokes from a script:

```
pen TYPE field=value ...       a new pen: TYPE as the library numbers them, fields of its configuration
point X Y PRESSURE SIZE TILTX TILTY TIME
run NAME batch=N               down, moves in batches of N, up
```

Pen types: 1 brush, 2 fountain (v1), 3 marker, 4 charcoal, 5 charcoal v2, 6 fountain (v2, what BOOX Notes uses), 7 pencil, 8 ballpoint, 9 square (calligraphy), 10 brush sign. Points are x, y, pressure 0 to 1, size, tilt x and y in degrees, and time in ms. `sweeps.py` writes the configurations BOOX Notes uses for its final ink.

The output lists each result's points (x, y, width for point pens; outlines for the others) and saves texture stamps (charcoal) as PNGs.

## Use

```powershell
.\tools\host\penlab\build.ps1 -Serial <serial>                  # compile, dex and push
python tools\host\penlab\sweeps.py widths > $env:TEMP\s.txt       # or speed, fountain_params, profiles, v1_vs_v2
.\tools\host\penlab\run.ps1 -Script $env:TEMP\s.txt -Out <folder> -Serial <serial>
python tools\host\penlab\widths.py <folder>\result.txt [-v]      # width per stroke
python tools\host\penlab\charcoal_stats.py <folder>              # stamp size, spacing and coverage
```

Keep outputs outside the repository (the session folder or `captures\`). The measurements so far are in `docs/09-ink.md` › *Measured pens*.
