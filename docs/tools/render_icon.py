"""Renders the website icons from the banner's app mark (amber tile with a dot and three lines).

    python docs/tools/render_icon.py

Writes site/favicon.ico (16-48 px), site/icon-192.png, site/icon-512.png and site/apple-touch-icon.png (180 px).
Drawn at 1024 px and downscaled for smooth edges.
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(HERE, "..", "..", "site")
AMBER = (255, 176, 32, 255)
ON_AMBER = (36, 24, 0, 255)
DIM = (110, 85, 30, 255)
N = 1024


def mark(full_bleed: bool) -> Image.Image:
    """The banner's 80-unit mark scaled to N px. full_bleed: iOS rounds the corners itself."""
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = N / 80
    if full_bleed:
        d.rectangle([0, 0, N, N], fill=AMBER)
    else:
        d.rounded_rectangle([0, 0, N - 1, N - 1], radius=22 * u, fill=AMBER)
    d.ellipse([34 * u, 12 * u, 46 * u, 24 * u], fill=ON_AMBER)
    d.rounded_rectangle([18 * u, 34 * u, 62 * u, 42 * u], radius=4 * u, fill=ON_AMBER)
    d.rounded_rectangle([18 * u, 48 * u, 54 * u, 56 * u], radius=4 * u, fill=ON_AMBER)
    d.rounded_rectangle([18 * u, 62 * u, 42 * u, 70 * u], radius=4 * u, fill=DIM)
    return img


os.makedirs(SITE, exist_ok=True)
rounded = mark(full_bleed=False)
for size, name in [(192, "icon-192.png"), (512, "icon-512.png")]:
    rounded.resize((size, size), Image.LANCZOS).save(os.path.join(SITE, name), optimize=True)
mark(full_bleed=True).resize((180, 180), Image.LANCZOS).convert("RGB").save(
    os.path.join(SITE, "apple-touch-icon.png"), optimize=True
)
rounded.resize((256, 256), Image.LANCZOS).save(os.path.join(SITE, "favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)])
print("icons written to", os.path.normpath(SITE))
