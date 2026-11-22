"""Writes every Cam Zone LUT into ../luts:  python make_all.py"""
import os
import numpy as np
from build_luts import write_cube, paint, gen5_encode, di_encode, COL

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'luts')
names = {'gen5': 'Gen5', 'dwg': 'DWG'}
for space in ('gen5', 'dwg'):
    for style in ('map', 'guard'):
        p = os.path.join(out, f'CamZone_{style.title()}_{names[space]}.cube')
        write_cube(p, style, space=space)
        print('wrote', os.path.basename(p), os.path.getsize(p) // 1024, 'KB')
    # Mood versions of the Map: the green "on target" range moves down for darker looks
    for label, shift in (('Moody', -1.0), ('LowKey', -1.5)):
        p = os.path.join(out, f'CamZone_Map_{label}_{names[space]}.cube')
        write_cube(p, 'map', space=space, shift=shift, label=f'Map {label}')
        print('wrote', os.path.basename(p), os.path.getsize(p) // 1024, 'KB')

# check: gray patches at known stops land in the right color, for both inputs
enc = {'gen5': gen5_encode, 'dwg': di_encode}
expect = [(-6, 'purple'), (-2, 'blue'), (0, 'gray_line'), (1, 'green'), (3, 'yellow'), (5.6, 'orange'), (6.5, 'red')]
for space in ('gen5', 'dwg'):
    for st, name in expect:
        v = enc[space](np.full((1, 3), 0.18 * 2 ** st))
        c = paint(v, 'map', space)[0]
        ref = np.array(COL[name])
        hue_ok = np.argmax(c) == np.argmax(ref) or np.allclose(c, ref, atol=0.25)
        print(f'{space:5} {st:+5}  {name:9} {np.round(c, 2)}  {"ok" if hue_ok else "CHECK"}')
