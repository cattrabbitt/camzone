from PIL import Image, ImageDraw, ImageFont
from build_luts import COL

rows = [
    ('Red', 'Clipped (+6 and up)', 'Gone. Add ND, flag it, or dim the source.', COL['red']),
    ('Orange', 'About to clip (+5 1/3 to +6)', 'Bring it down 1/3 to 2/3 stop.', COL['orange']),
    ('Yellow', 'Bright (+1 1/3 to +5 1/3)', 'Fine for backgrounds. On a face: ND first.', COL['yellow']),
    ('Green', 'Good (-1/2 to +1 1/3)', 'Faces and key subjects belong here.', COL['green']),
    ('Light green', 'Middle gray (0)', 'Gray card dead on.', COL['gray_line']),
    ('Blue', 'Dark (-5 to -1/2)', 'Mood. Add fill if you need the detail.', COL['blue']),
    ('Dark purple', 'Crushed (below -5)', 'No usable detail. Lift it or accept it.', COL['purple']),
]
fix = 'Fix order: ND first, then light, then iris. ISO 3200 only when the scene is truly dark.'

W, rh, top = 1400, 92, 110
img = Image.new('RGB', (W, top + rh * len(rows) + 90), (22, 22, 22))
d = ImageDraw.Draw(img)
B = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
R = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
d.text((40, 34), 'Cam Zone color code  (stops from middle gray, BMD Film Gen 5)', font=ImageFont.truetype(B, 34), fill=(240, 240, 240))
for i, (name, rng, act, c) in enumerate(rows):
    y = top + i * rh
    d.rounded_rectangle([40, y + 12, 150, y + rh - 12], 10, fill=tuple(int(v * 255) for v in c))
    d.text((180, y + 14), name, font=ImageFont.truetype(B, 28), fill=(240, 240, 240))
    d.text((180, y + 50), rng, font=ImageFont.truetype(R, 22), fill=(180, 180, 180))
    d.text((640, y + 30), act, font=ImageFont.truetype(R, 26), fill=(225, 225, 225))
d.text((40, img.height - 64), fix, font=ImageFont.truetype(B, 24), fill=(240, 240, 240))
img.save('CamZone_Legend.png')
print(img.size)
