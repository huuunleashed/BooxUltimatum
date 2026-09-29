"""Generates penlab scripts: sets of strokes fed to the firmware's pen-ink library with the settings BOOX Notes uses.

    python sweeps.py NAME > script.txt

Each sweep is a list of (name, pen type, config, points). Points are (x, y, pressure 0..1, size, tilt x, tilt y, ms).
The configs follow what Notes builds for its final ink (docs/09-ink.md, penlab section).
"""
import math
import sys

# Notes' configs for its final ink, per native pen type (NeoPenConfig defaults plus what each shape sets).
BASE = dict(minWidth=0.001, tiltScale=3, maxTouchPressure=1, dpi=320, displayScaleX=1, displayScaleY=1,
            scalePrecision=1, brushSpacing=0.25, brushRatio=5, pressureSensitivity=0.3, velocitySensitivity=0.5,
            smoothLevel=0.6)


def fountain(w, sens=0.3, smooth=0.6):
    # FountainShapes.createNeoPenV2: width + 3, minWidth 1, fast mode on.
    return 6, dict(BASE, width=w + 3, minWidth=1, pressureSensitivity=sens, smoothLevel=smooth, fastMode='true')


def marker(w):
    return 3, dict(BASE, width=w)


def brush(w):
    return 1, dict(BASE, width=w)


def square(w, angle=45, smooth=0.6):
    return 9, dict(BASE, width=w * 2, brushShape=2, brushRatio=min(w, 10), brushAngle=angle, smoothLevel=smooth)


def ballpoint(w):
    return 8, dict(BASE, width=w, smoothLevel=0.6)


def charcoal(w, v2=False, tilt=True):
    return (5 if v2 else 4), dict(BASE, width=w, tiltEnabled='true' if tilt else 'false')


def pencil(w):
    return 7, dict(BASE, width=w, minWidth=1, pressureSensitivity=0, velocitySensitivity=0, dpi=300)


def line(p=0.5, step=4.0, dt=8, n=40, tx=0, ty=0, y=500.0, x0=100.0):
    """A straight horizontal stroke at constant pressure, [step] px apart every [dt] ms."""
    return [(x0 + i * step, y, p, 0, tx, ty, i * dt) for i in range(n + 1)]


def ramp(p0=0.05, p1=1.0, step=4.0, dt=8, n=60):
    return [(100 + i * step, 500.0, p0 + (p1 - p0) * i / n, 0, 0, 0, i * dt) for i in range(n + 1)]


def swell(step=4.0, dt=8, n=60):
    return [(100 + i * step, 500.0, 0.1 + 0.8 * math.sin(math.pi * i / n), 0, 0, 0, i * dt) for i in range(n + 1)]


def sweep_widths():
    out = []
    for name, cfgf in (('fountain', fountain), ('marker', marker), ('brush', brush), ('ballpoint', ballpoint), ('square', square)):
        for w in (2, 4, 8, 16):
            t, c = cfgf(w)
            for p in (0.05, 0.2, 0.5, 0.8, 1.0):
                out.append((f'{name}_w{w}_p{p}', t, c, line(p)))
    return out


def sweep_speed():
    out = []
    for name, cfgf in (('fountain', fountain), ('brush', brush), ('marker', marker), ('ballpoint', ballpoint)):
        t, c = cfgf(8)
        for step in (0.5, 1, 2, 4, 8, 16, 32):
            out.append((f'{name}_s{step}', t, c, line(0.5, step=step, n=max(20, int(160 / step)))))
    return out


def sweep_fountain_params():
    out = []
    for sens in (0, 0.15, 0.3, 0.5, 0.75, 1.0):
        for p in (0.05, 0.2, 0.5, 0.8, 1.0):
            t, c = fountain(8, sens=sens)
            out.append((f'fsens{sens}_p{p}', t, c, line(p)))
    return out


def sweep_profiles():
    out = []
    for name, cfgf in (('fountain', fountain), ('brush', brush), ('marker', marker), ('ballpoint', ballpoint), ('square', square)):
        t, c = cfgf(8)
        out.append((f'{name}_ramp', t, c, ramp()))
        out.append((f'{name}_swell', t, c, swell()))
    return out


def fountain_v1(w, sens=0.3, smooth=0.6):
    return 2, dict(BASE, width=w, pressureSensitivity=sens, smoothLevel=smooth)


def sweep_v1_vs_v2():
    out = []
    for w in (2, 4, 8):
        for p in (0.05, 0.2, 0.5, 0.8, 1.0):
            t, c = fountain_v1(w)
            out.append((f'v1_w{w}_p{p}', t, c, line(p)))
            t, c = fountain(w)
            out.append((f'v2_w{w}_p{p}', t, c, line(p)))
    for name, cfgf in (('brush', brush), ('square', square)):
        for sens in (0.0, 0.3, 1.0):
            t, c = cfgf(8)
            c = dict(c, pressureSensitivity=sens)
            out.append((f'{name}_sens{sens}_p0.5', t, c, line(0.5)))
    return out


def sweep_pencil():
    out = []
    for w in (2, 4, 8):
        t, c = pencil(w)
        for p in (0.05, 0.2, 0.5, 0.8, 1.0):
            out.append((f'pencil_w{w}_p{p}', t, c, line(p)))
    t, c = pencil(4)
    for step in (1, 4, 16):
        out.append((f'pencil_s{step}', t, c, line(0.5, step=step, n=max(20, int(160 / step)))))
    for tx in (0, 45, 70):
        out.append((f'pencil_tilt{tx}', t, c, line(0.5, tx=tx)))
    return out


SWEEPS = {'pencil': sweep_pencil, 'v1_vs_v2': sweep_v1_vs_v2, 'widths': sweep_widths, 'speed': sweep_speed, 'fountain_params': sweep_fountain_params, 'profiles': sweep_profiles}


def render(strokes):
    lines = []
    for name, t, cfg, pts in strokes:
        lines.append(f'pen {t} ' + ' '.join(f'{k}={v}' for k, v in cfg.items()))
        for p in pts:
            lines.append('point ' + ' '.join(f'{v:.4f}' if isinstance(v, float) else str(v) for v in p))
        lines.append(f'run {name} batch=1000')
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    names = sys.argv[1:] or list(SWEEPS)
    strokes = []
    for n in names:
        strokes += SWEEPS[n]()
    sys.stdout.write(render(strokes))
