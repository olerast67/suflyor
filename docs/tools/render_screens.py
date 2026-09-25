"""Combines the screenshots in docs/images/screens/ into docs/images/screens.png for the README.

    python docs/tools/render_screens.py

Takes up to four PNGs in name order (1-library.png, 2-script.png, ...), rounds their corners, adds a soft shadow
and lays them out side by side on the banner's dark background.
"""
import glob
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "..", "images", "screens")
OUT = os.path.join(HERE, "..", "images", "screens.png")
BG = (14, 14, 17)
PHONE_W = 300
GAP = 36
PAD = 48
RADIUS = 28


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, (0, 0), mask)
    return out


files = sorted(glob.glob(os.path.join(SHOTS, "*.png")))[:4]
if not files:
    raise SystemExit("No screenshots in docs/images/screens - run tools/capture-screenshots.ps1 first")
phones = []
for f in files:
    img = Image.open(f).convert("RGB")
    h = round(img.height * PHONE_W / img.width)
    phones.append(rounded(img.resize((PHONE_W, h), Image.LANCZOS), RADIUS))

height = max(p.height for p in phones) + PAD * 2
width = PAD * 2 + len(phones) * PHONE_W + (len(phones) - 1) * GAP
canvas = Image.new("RGBA", (width, height), BG + (255,))

shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
sd = ImageDraw.Draw(shadow)
x = PAD
for p in phones:
    sd.rounded_rectangle([x, PAD + 10, x + p.width, PAD + 10 + p.height], radius=RADIUS, fill=(0, 0, 0, 170))
    x += PHONE_W + GAP
canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(16)))

x = PAD
for p in phones:
    canvas.alpha_composite(p, (x, PAD))
    ImageDraw.Draw(canvas).rounded_rectangle(
        [x, PAD, x + p.width - 1, PAD + p.height - 1], radius=RADIUS, outline=(60, 60, 68), width=2,
    )
    x += PHONE_W + GAP

canvas.convert("RGB").save(OUT, optimize=True)
print(f"{OUT}: {width}x{height}, {len(phones)} screens")
