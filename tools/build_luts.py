"""Build the PYXIS exposure LUTs (Map, Clean, Guard) for Blackmagic Film Gen 5,
plus a preview scene rendered through each LUT.

Input to every LUT: Blackmagic Design Film Gen 5 log, BMD Wide Gamut Gen 5.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ---------- Blackmagic Film Gen 5 transfer ----------
A, B, C = 0.08692876065491224, 0.005494072432257808, 0.5300133392291939
D, E, LIN_CUT = 8.283605932402494, 0.09246575342465753, 0.005
LOG_CUT = D * LIN_CUT + E


def gen5_encode(x):
    x = np.asarray(x, dtype=np.float64)
    return np.where(x < LIN_CUT, D * x + E, A * np.log(np.maximum(x, LIN_CUT) + B) + C)


def gen5_decode(y):
    y = np.asarray(y, dtype=np.float64)
    return np.where(y < LOG_CUT, (y - E) / D, np.exp((y - C) / A) - B)


# ---------- DaVinci Intermediate transfer (DaVinci Wide Gamut timelines) ----------
DI_A, DI_B, DI_C, DI_M = 0.0075, 7.0, 0.07329248, 10.44426855
DI_LIN_CUT, DI_LOG_CUT = 0.00262409, 0.02740668


def di_encode(x):
    x = np.asarray(x, dtype=np.float64)
    return np.where(x <= DI_LIN_CUT, x * DI_M, (np.log2(np.maximum(x, DI_LIN_CUT) + DI_A) + DI_B) * DI_C)


def di_decode(y):
    y = np.asarray(y, dtype=np.float64)
    return np.where(y <= DI_LOG_CUT, y / DI_M, 2.0 ** (y / DI_C - DI_B) - DI_A)


# ---------- Gamut: BMD Wide Gamut Gen 5 -> Rec.709 ----------
def rgb_to_xyz(prim, white=(0.3127, 0.3290)):
    xyz = lambda x, y: np.array([x / y, 1.0, (1 - x - y) / y])
    P = np.stack([xyz(*p) for p in prim], axis=1)
    S = np.linalg.solve(P, xyz(*white))
    return P * S


M_WG = rgb_to_xyz([(0.7177, 0.3171), (0.2280, 0.8616), (0.1006, -0.0820)])
M_709 = rgb_to_xyz([(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)])
WG_TO_709 = np.linalg.inv(M_709) @ M_WG
Y_WG = M_WG[1]  # luminance weights in Wide Gamut Gen 5
M_DWG = rgb_to_xyz([(0.8000, 0.3130), (0.1682, 0.9877), (0.0790, -0.1155)])
DWG_TO_WG = np.linalg.inv(M_WG) @ M_DWG

# Input spaces: name -> (decode to linear, matrix into Wide Gamut Gen 5)
SPACES = {
    'gen5': (lambda v: gen5_decode(v), np.eye(3)),
    'dwg': (lambda v: di_decode(v), DWG_TO_WG),
}

GRAY = 0.18
CLIP_STOPS = 6.0          # starting value, confirmed later with the zebra check
WARN_STOPS = CLIP_STOPS - 2 / 3
# Rating: the PYXIS is exposed this much brighter than true middle gray (like rating ISO 400 at 250), so
# shadows and night scenes come out clean. The gray line, green, blue and purple are measured from this
# rated gray; the clip warnings stay at the sensor's real limit.
RATING = 2 / 3
CRUSH_RATED = -4.0       # purple: will sit near black in Rec.709

COL = {
    'red': (0.92, 0.12, 0.10),
    'orange': (1.00, 0.52, 0.00),
    'yellow': (0.98, 0.86, 0.12),
    'green': (0.16, 0.72, 0.24),
    'gray_line': (0.70, 1.00, 0.62),
    'blue': (0.12, 0.38, 0.92),
    'purple': (0.30, 0.06, 0.40),
    'target': (0.15, 0.85, 1.00),
}


def look_display(lin_wg, style):
    """A cinematic monitoring look: filmic contrast and roll-off ('film'), plus warm highlights and
    teal shadows ('teal'). For judging lighting on set, not for baking in."""
    rgb = np.clip(lin_wg @ WG_TO_709.T, 0, None)
    Y = rgb @ np.array([0.2126, 0.7152, 0.0722])
    # gentle desaturation, like film
    rgb = Y[..., None] + (rgb - Y[..., None]) * 0.88
    if style == 'teal':
        w = np.clip(np.log2(np.maximum(Y, 1e-6) / GRAY) / 4 + 0.5, 0, 1)[..., None]   # 0 shadows .. 1 highlights
        warm = np.array([1.06, 1.0, 0.90]); cool = np.array([0.90, 1.0, 1.08])
        rgb = rgb * (cool * (1 - w) + warm * w)
    x = rgb * 0.75
    t = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)   # filmic S-curve with soft highlight roll-off
    return np.clip(t, 0, 1) ** (1 / 2.2)


def display_rec709(lin_wg):
    """Neutral monitoring view: Wide Gamut -> Rec.709, soft highlight roll-off, 2.4 gamma."""
    rgb = np.clip(lin_wg @ WG_TO_709.T, 0, None)
    k = 0.794  # puts middle gray at about 42% on screen
    t = rgb * k / (1 + rgb * k)
    return np.clip(t, 0, 1) ** (1 / 2.4)


def classify(lin_wg):
    """Return stops of luminance and stops of the brightest channel."""
    Y = np.clip(lin_wg @ Y_WG, 1e-6, None)
    mx = np.clip(lin_wg.max(axis=-1), 1e-6, None)
    return np.log2(Y / GRAY), np.log2(mx / GRAY)


def paint(log_rgb, style, space='gen5', shift=0.0, target=1.0):
    """shift moves the green 'on target' range for a mood: 0 normal, -1 moody, -1.5 low-key."""
    dec, mtx = SPACES[space]
    lin = dec(log_rgb) @ mtx.T
    s, smax = classify(lin)
    disp = display_rec709(lin)
    luma = disp @ np.array([0.2126, 0.7152, 0.0722])
    frac = s - np.floor(s)                       # position inside the stop
    shade = (0.80 + 0.30 * frac)[..., None]       # subtle step at every stop line
    out = np.empty_like(log_rgb)

    gray_img = np.repeat((luma * 0.85)[..., None], 3, axis=-1)
    if style in ('guard', 'face'):
        out[:] = disp
    elif style in ('film', 'teal'):
        out[:] = look_display(lin, style)
    elif style == 'clean':
        out[:] = gray_img
    else:  # map: start from a dim gray picture
        out[:] = gray_img

    def put(mask, color, texture=True):
        c = np.array(color)
        if texture:
            v = np.clip(c * shade * (0.75 + 0.5 * luma[..., None]), 0, 1)
        else:
            v = np.broadcast_to(c, out.shape)
        out[mask] = v[mask] if v.shape == out.shape else c

    red = smax >= CLIP_STOPS
    orange = (smax >= WARN_STOPS) & ~red
    r = s - RATING                      # stops from rated gray
    purple = r < CRUSH_RATED
    lo, hi = -1 / 3 + shift, 4 / 3 + shift
    green = (r >= lo) & (r < hi)
    line = np.abs(r) < 1 / 6
    if style == 'map':
        blue = (r >= CRUSH_RATED) & (r < lo)
        yellow = (r >= hi) & (smax < WARN_STOPS)
        # blue gets darker the deeper it goes: dim near purple, bright near green
        bshade = np.clip((r - CRUSH_RATED) / max(lo - CRUSH_RATED, 0.5), 0, 1)[..., None]
        bv = np.array(COL['blue']) * (0.45 + 0.55 * bshade) * (0.80 + 0.30 * frac)[..., None]
        out[blue] = np.clip(bv, 0, 1)[blue]
        put(yellow, COL['yellow'])
    if style in ('map', 'clean'):
        put(green, COL['green'])
        put(line, COL['gray_line'], texture=False)
    if style == 'face':
        # Face target band: skin at the mood's target shows cyan, so you can see the face land
        put(np.abs(r - target) < 1 / 6, COL['target'], texture=False)
    put(purple, COL['purple'], texture=False)
    put(orange, COL['orange'], texture=False)
    put(red, COL['red'], texture=False)
    return np.clip(out, 0, 1)


SPACE_NAMES = {'gen5': 'Blackmagic Film Gen 5', 'dwg': 'DaVinci Wide Gamut Intermediate'}
VERSION = '1.4'


def write_cube(path, style, n=33, space='gen5', shift=0.0, label=None, target=1.0):
    g = np.linspace(0, 1, n)
    b, gg, r = np.meshgrid(g, g, g, indexing='ij')  # red changes fastest
    grid = np.stack([r, gg, b], axis=-1).reshape(-1, 3)
    vals = paint(grid, style, space, shift, target)
    with open(path, 'w') as f:
        f.write(f'TITLE "Cam Zone {label or style.title()} v{VERSION} - {SPACE_NAMES[space]}"\n')
        f.write(f'# Input: {SPACE_NAMES[space]}. Monitoring only, remove before export.\n')
        f.write(f'# Clip warning set for +{CLIP_STOPS:g} stops over middle gray (starting value)\n')
        f.write(f'LUT_3D_SIZE {n}\n')
        for v in vals:
            f.write(f'{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n')
    return vals.reshape(n, n, n, 3)  # indexed [b][g][r]


def apply_cube(cube, img):
    """Trilinear lookup, the way a camera applies a 33-point LUT."""
    n = cube.shape[0]
    p = np.clip(img, 0, 1) * (n - 1)
    i0 = np.floor(p).astype(int).clip(0, n - 2)
    f = p - i0
    out = np.zeros_like(img)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = ((f[..., 0] if dr else 1 - f[..., 0]) *
                     (f[..., 1] if dg else 1 - f[..., 1]) *
                     (f[..., 2] if db else 1 - f[..., 2]))
                out += w[..., None] * cube[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out
