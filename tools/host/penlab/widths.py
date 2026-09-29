"""Width profiles from a penlab run.

Outline pens (fountain V2 6, square 9, ballpoint 8) return polygons; a round pen's segment is a hexagon whose two
sideways points give the radius at each end. Point pens (brush 1, fountain V1 2, marker 3) return (x, y, size).
Prints the median width over the middle 60 % of each stroke and the width at its first and last tenths; with -v the
whole profile as (x, width).
"""
import math
import re
import sys
from statistics import median

path = sys.argv[1]
verbose = '-v' in sys.argv
runs, kinds, order = {}, {}, []
cur = None
sizes = []
for l in open(path).read().splitlines():
    m = re.match(r'pen (\S+) type=(\d+)', l)
    if m:
        cur = m.group(1)
        order.append(cur)
        runs[cur] = []
        kinds[cur] = int(m.group(2))
        continue
    m = re.match(r'ink (\S+) \S+ real n=(\d+) sizes=([\d,]*)', l)
    if m:
        sizes = [int(s) for s in m.group(3).split(',') if s]
        continue
    if l.startswith('data') and cur:
        v = [float(t) for t in l.split()[1:]]
        k = kinds[cur]
        if k in (1, 2, 3) or (sizes and all(s == 3 for s in sizes)):
            for i in range(0, len(v) - 2, 3):
                runs[cur].append((v[i], v[i + 1], v[i + 2]))
        elif k == 7:
            for i in range(0, len(v) - 4, 5):
                runs[cur].append((v[i], v[i + 1], v[i + 2]))
        else:
            off = 0
            for n in sizes or [len(v)]:
                poly = [(v[off + 2 * j], v[off + 2 * j + 1]) for j in range(n // 2)]
                off += n
                if len(poly) == 6:
                    # c0 - r0*d, c0 + r0*n, c1 + r1*n, c1 + r1*d, c1 - r1*n, c0 - r0*n
                    a, b = poly[1], poly[5]
                    c0 = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    r0 = math.dist(a, b) / 2
                    runs[cur].append((c0[0], c0[1], 2 * r0))
                elif poly:
                    xs = [p[0] for p in poly]
                    ys = [p[1] for p in poly]
                    runs[cur].append(((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, min(max(xs) - min(xs), max(ys) - min(ys))))

for k in order:
    p = runs[k]
    if not p:
        print(f'{k:14s} no ink')
        continue
    n = len(p)
    mid = [w for _, _, w in p[int(n * 0.2):max(int(n * 0.8), int(n * 0.2) + 1)]]
    head = [w for _, _, w in p[:max(1, n // 10)]]
    tail = [w for _, _, w in p[-max(1, n // 10):]]
    print(f'{k:14s} n={n:4d} mid={median(mid):6.2f} start={median(head):6.2f} end={median(tail):6.2f} max={max(w for _, _, w in p):6.2f}')
    if verbose:
        print('   ', ' '.join(f'{x:.0f}:{w:.2f}' for x, _, w in p[::max(1, n // 25)]))
