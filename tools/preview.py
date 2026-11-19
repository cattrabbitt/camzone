import numpy as np
from PIL import Image, ImageDraw, ImageFont
from build_luts import (gen5_encode, write_cube, apply_cube, WG_TO_709, Y_WG, COL)

W, H = 960, 540
yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
TO_WG = np.linalg.inv(WG_TO_709)


def colour(rgb709, stops):
    """A Rec.709 colour placed at a given luminance in stops from middle gray, in Wide Gamut."""
    wg = TO_WG @ np.array(rgb709, dtype=np.float64)
    wg = wg / (wg @ Y_WG)
    return wg * 0.18 * 2.0 ** np.asarray(stops)[..., None]


# Wall: warm gray, -3 stops at left rising to -1 at right
stops = -3 + 2 * xx / W
scene = colour((0.9, 0.85, 0.78), stops)

# Floor fades into darkness: down to -8 stops
floor = yy > 400
fs = -4 - 4 * (yy - 400) / 140
scene[floor] = colour((0.8, 0.8, 0.8), fs)[floor]

# Window, top right: sky from +4.5 at bottom to +7.5 at top
win = (xx > 640) & (xx < 900) & (yy > 40) & (yy < 250)
ws = 7.5 - 3 * (yy - 40) / 210
scene[win] = colour((0.75, 0.85, 1.0), ws)[win]

# Practical lamp: bulb +9 with glow falling off
d = np.hypot(xx - 120, yy - 110)
lamp = d < 70
ls = 9 - 0.12 * d
scene[lamp] = np.maximum(scene[lamp], colour((1.0, 0.8, 0.55), ls)[lamp])

# Red neon sign: saturated, luminance only +4.4 but the red channel runs hot
neon = (xx > 250) & (xx < 420) & (yy > 60) & (yy < 90)
scene[neon] = colour((1.0, 0.05, 0.08), np.full(xx.shape, 4.4))[neon]

# Face: key light from the left (+1 1/3 on the cheek) to fill on the right (-1 1/3)
fe = ((xx - 480) / 95) ** 2 + ((yy - 250) / 125) ** 2 < 1
fsx = 1.333 - 2.666 * (xx - 385) / 190
scene[fe] = colour((0.85, 0.62, 0.50), fsx)[fe]

# Gray card at exactly middle gray
card = (xx > 600) & (xx < 690) & (yy > 300) & (yy < 380)
scene[card] = colour((1, 1, 1), np.zeros(xx.shape))[card]

# Step wedge along the bottom: -7 to +7 stops
wedge_y = (yy > 470) & (yy < 520)
for i, s in enumerate(range(-7, 8)):
    m = wedge_y & (xx >= 30 + i * 60) & (xx < 86 + i * 60)
    scene[m] = colour((1, 1, 1), np.full(xx.shape, float(s)))[m]

log_img = gen5_encode(np.clip(scene, 0, None))

cubes = {s: write_cube(f'CamZone_{s.title()}_Gen5.cube', s) for s in ('map', 'guard')}
panels = [('Camera log (no LUT)', log_img)] + \
         [(f'{s.title()} LUT', apply_cube(cubes[s], log_img)) for s in ('map', 'guard')]

try:
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 22)
    small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 16)
except OSError:
    font = small = ImageFont.load_default()

pad, top = 16, 40
PW, PH = 640, 360
sheet = Image.new('RGB', (3 * PW + 4 * pad, PH + top + 2 * pad + 70), (24, 24, 24))
dr = ImageDraw.Draw(sheet)
for k, (title, img) in enumerate(panels):
    cx, cy = pad + k * (PW + pad), pad
    dr.text((cx, cy + 6), title, fill=(235, 235, 235), font=font)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).resize((PW, PH), Image.LANCZOS)
    sheet.paste(im, (cx, cy + top))

legend = [('Dark purple', 'crushed', COL['purple']), ('Blue', 'dark', COL['blue']),
          ('Green', 'good', COL['green']), ('Yellow', 'bright', COL['yellow']),
          ('Orange', 'about to clip', COL['orange']), ('Red', 'clipped', COL['red'])]
ly = sheet.height - 60
lx = pad
for name, meaning, c in legend:
    dr.rectangle([lx, ly, lx + 34, ly + 34], fill=tuple(int(v * 255) for v in c))
    dr.text((lx + 44, ly + 7), f'{name}: {meaning}', fill=(230, 230, 230), font=small)
    lx += 330
sheet.save('CamZone_preview.png')
print('saved', sheet.size)
