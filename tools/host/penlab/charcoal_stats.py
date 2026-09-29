"""Summarises a penlab charcoal run: per stroke, the typical stamp size, stamp spacing and mean coverage."""
import collections
import os
import re
import sys

from PIL import Image

out = sys.argv[1]
lines = open(os.path.join(out, 'result.txt')).read().splitlines()
sizes = collections.defaultdict(list)
files = collections.defaultdict(list)
xs = collections.defaultdict(list)
order = []
cur = None
for l in lines:
    m = re.match(r'pen (\S+) ', l)
    if m:
        cur = m.group(1)
        order.append(cur)
    m = re.match(r'bitmap (\S+) (\d+) (\d+) (\d+) (\S+)', l)
    if m:
        sizes[m.group(1)].append((int(m.group(3)), int(m.group(4))))
        files[m.group(1)].append(m.group(5))
    if l.startswith('data') and cur:
        v = [float(t) for t in l.split()[1:]]
        xs[cur].extend(v[0::2])

for k in order:
    v = sizes[k]
    if not v:
        print(k, 'no stamps')
        continue
    c = collections.Counter(v).most_common(1)[0][0]
    x = xs[k]
    sp = [b - a for a, b in zip(x, x[1:])]
    spacing = sorted(sp)[len(sp) // 2] if sp else 0
    cov = []
    for fn in files[k][5:10]:
        p = os.path.join(out, fn)
        if os.path.exists(p):
            a = Image.open(p).getchannel('A')
            h = a.histogram()
            cov.append(sum(i * n for i, n in enumerate(h)) / (a.width * a.height))
    print(f"{k:10s} stamps={len(v):3d} size={c[0]:3d}x{c[1]:3d} spacing={spacing:5.2f} alpha={sum(cov)/max(1,len(cov)):5.1f}")
