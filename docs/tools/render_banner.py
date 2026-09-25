"""Renders docs/images/banner.png (README header, also usable as the GitHub social preview, 1280x640).

    python docs/tools/render_banner.py

Drawn at 2x and downscaled for smooth edges. Fonts: Segoe UI (Windows); pass other TTFs via env vars if needed.
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 2
W, H = 1280 * S, 640 * S
BG = (14, 14, 17)
SURFACE = (23, 23, 27)
TEXT = (242, 242, 244)
MUTED = (161, 161, 170)
DIM = (95, 95, 102)
AMBER = (255, 176, 32)
ON_AMBER = (36, 24, 0)
GREEN = (70, 215, 120)

FONTS = os.environ.get("BANNER_FONTS", r"C:\Windows\Fonts")


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size * S)


def rr(draw, box, r, fill):
    draw.rounded_rectangle([v * S for v in box], radius=r * S, fill=fill)


img = Image.new("RGB", (W, H), BG)

# Soft amber glow behind the phone.
glow = Image.new("RGB", (W, H), BG)
g = ImageDraw.Draw(glow)
g.ellipse([820 * S, 40 * S, 1260 * S, 600 * S], fill=(90, 60, 10))
glow = glow.filter(ImageFilter.GaussianBlur(120 * S))
img = Image.blend(img, glow, 0.55)
d = ImageDraw.Draw(img)

# ---- left: icon, name, tagline, chips ----
rr(d, (96, 150, 176, 230), 22, AMBER)
d.ellipse([130 * S, 162 * S, 142 * S, 174 * S], fill=ON_AMBER)
rr(d, (114, 184, 158, 192), 4, ON_AMBER)
rr(d, (114, 198, 150, 206), 4, ON_AMBER)
rr(d, (114, 212, 138, 220), 4, (110, 85, 30))

d.text((196 * S, 138 * S), "Суфлёр", font=font("seguisb.ttf", 76), fill=TEXT)
d.text((98 * S, 258 * S), "The teleprompter that listens.", font=font("seguisb.ttf", 34), fill=TEXT)
d.text(
    (98 * S, 310 * S),
    "Floats over Instagram, TikTok and the camera,\nfollows your voice, works fully offline.",
    font=font("segoeui.ttf", 24), fill=MUTED, spacing=8 * S,
)

chips = ["Voice follow", "Over any camera", "Offline · private", "Android 10+"]
x = 98
cf = font("seguisb.ttf", 19)
for c in chips:
    w = d.textlength(c, font=cf) / S + 32
    rr(d, (x, 420, x + w, 460), 20, SURFACE)
    d.text(((x + 16) * S, 428 * S), c, font=cf, fill=AMBER if c == chips[0] else MUTED)
    x += w + 12

d.text((98 * S, 540 * S), "Kotlin · Jetpack Compose · sherpa-onnx · GPL-3.0", font=font("segoeui.ttf", 18), fill=DIM)

# ---- right: phone with the prompter hanging from the top edge ----
px, py, pw, ph = 890, 40, 290, 580
rr(d, (px - 10, py - 10, px + pw + 10, py + ph + 10), 50, (42, 42, 48))
rr(d, (px, py, px + pw, py + ph), 42, (48, 56, 64))
# camera scene
rr(d, (px, py + 250, px + pw, py + ph), 42, (58, 66, 74))
d.ellipse([(px + 95) * S, (py + 250) * S, (px + 195) * S, (py + 360) * S], fill=(88, 97, 106))
rr(d, (px + 70, py + 350, px + 220, py + ph), 60, (88, 97, 106))
# overlay panel
panel_bottom = py + 250
d.rounded_rectangle([px * S, py * S, (px + pw) * S, panel_bottom * S], radius=42 * S, fill=(10, 10, 12))
d.ellipse([(px + pw / 2 - 7) * S, (py + 12) * S, (px + pw / 2 + 7) * S, (py + 26) * S], fill=(0, 0, 0))
tf = font("segoeui.ttf", 21)
lines = [("Всем привет! Сегодня", DIM), ("как выбрать первый дрон", TEXT), ("для съёмки путешествий", TEXT), ("и не переплатить.", TEXT)]
ly = py + 44
for i, (t, col) in enumerate(lines):
    if i == 1:
        rr(d, (px + 14, ly + 6, px + 18, ly + 28), 2, AMBER)
    d.text(((px + 26) * S, ly * S), t, font=tf, fill=col)
    ly += 34
# controls row
cy = panel_bottom - 40
d.ellipse([(px + 22) * S, (cy + 8) * S, (px + 30) * S, (cy + 16) * S], fill=GREEN)
d.text(((px + 38) * S, (cy + 1) * S), "слушаю", font=font("segoeui.ttf", 14), fill=MUTED)
def icon(kind, cx, cy_):
    c = [v * S for v in (cx, cy_)]
    if kind == "up":
        d.polygon([(c[0] - 7 * S, c[1] + 5 * S), (c[0] + 7 * S, c[1] + 5 * S), (c[0], c[1] - 6 * S)], fill=TEXT)
    elif kind == "down":
        d.polygon([(c[0] - 7 * S, c[1] - 5 * S), (c[0] + 7 * S, c[1] - 5 * S), (c[0], c[1] + 6 * S)], fill=TEXT)
    elif kind == "pause":
        d.rectangle([c[0] - 6 * S, c[1] - 7 * S, c[0] - 2 * S, c[1] + 7 * S], fill=TEXT)
        d.rectangle([c[0] + 2 * S, c[1] - 7 * S, c[0] + 6 * S, c[1] + 7 * S], fill=TEXT)
    elif kind == "close":
        d.line([c[0] - 6 * S, c[1] - 6 * S, c[0] + 6 * S, c[1] + 6 * S], fill=TEXT, width=3 * S)
        d.line([c[0] - 6 * S, c[1] + 6 * S, c[0] + 6 * S, c[1] - 6 * S], fill=TEXT, width=3 * S)


for k, kind in enumerate(["up", "pause", "down", "close"]):
    icon(kind, px + 160 + k * 34, cy + 12)
rr(d, (px + pw / 2 - 18, panel_bottom - 12, px + pw / 2 + 18, panel_bottom - 8), 2, (120, 120, 126))
# record button
d.ellipse([(px + pw / 2 - 30) * S, (py + ph - 86) * S, (px + pw / 2 + 30) * S, (py + ph - 26) * S], outline=(255, 255, 255), width=5 * S)
d.ellipse([(px + pw / 2 - 20) * S, (py + ph - 76) * S, (px + pw / 2 + 20) * S, (py + ph - 36) * S], fill=(229, 72, 77))

out = os.path.join(os.path.dirname(__file__), "..", "images", "banner.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
img.resize((1280, 640), Image.LANCZOS).save(out, optimize=True)
print("saved", os.path.abspath(out))
