"""Cuts the status and navigation bars off a phone screenshot (used by tools/capture-screenshots.ps1).

    python docs/tools/crop_screen.py raw.png out.png <top px> <bottom px>
"""
import sys
from PIL import Image

src, dst, top, bottom = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
img = Image.open(src).convert("RGB")
w, h = img.size
img.crop((0, top, w, h - bottom)).save(dst, optimize=True)
