#!/usr/bin/env python3
"""Turns each hand-crop into ink on nothing, ready for the theme.

Run from the repository root, after putting your crops in cropped/:

    python3 tools/motif-sources/finish.py

What it does, and why: alpha from the lines, paper gone.

Every earlier attempt drew the paper as well, and the paper is the problem. An
engraving is line art on a sheet, and the sheet carries no information — but it does
carry luminance, so any version of this that paints it ends up as a pale rectangle
inside the card, whatever the blend mode. Softening its edges only made it a
rectangle with rounded corners.

So the alpha channel comes from how much ink is at each pixel and the paper becomes
transparent. What is left is lines floating on the card's own surface.

The pixels themselves are a flat mid grey, and that is deliberate: it sits between
the dark scheme's surface and the light scheme's, so one file reads on both. The
theme's `color` blend then gives the lines their hue. Two sets of files, one per
scheme, would have been the obvious alternative and this is cheaper and has no way
to drift.
"""
from PIL import Image, ImageOps, ImageDraw, ImageFilter, ImageChops
import glob, os

W, H = 760, 380
FEATHER = 40
INK = 150            # mid grey: lighter than the dark surface, darker than the light one
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "cropped")
DEST = os.path.join(HERE, "..", "..", "server", "src", "main", "resources",
                    "META-INF", "resources", "media")

for src in sorted(glob.glob(f"{SRC}/*.jpg")):
    name = os.path.basename(src).replace(".jpg", ".webp")
    plate = ImageOps.grayscale(Image.open(src))
    plate = ImageOps.autocontrast(plate, cutoff=1)
    plate.thumbnail((W - 16, H - 16), Image.LANCZOS)
    iw, ih = plate.size

    # Ink, not paper: invert so lines are bright, then autocontrast so an aged sheet
    # does not read as a faint wash of ink everywhere.
    ink = ImageOps.autocontrast(ImageOps.invert(plate), cutoff=(2, 0))

    canvas_alpha = Image.new("L", (W, H), 0)
    ox, oy = (W - iw) // 2, (H - ih) // 2
    canvas_alpha.paste(ink, (ox, oy))

    # Fade the picture's border so the crop does not end on a hard line.
    border = Image.new("L", (W, H), 0)
    ImageDraw.Draw(border).rectangle(
        [ox + FEATHER // 2, oy + FEATHER // 2, ox + iw - FEATHER // 2, oy + ih - FEATHER // 2],
        fill=255)
    border = border.filter(ImageFilter.GaussianBlur(FEATHER / 3))
    alpha = ImageChops.multiply(canvas_alpha, border)

    out = Image.merge("RGBA", (Image.new("L", (W, H), INK),) * 3 + (alpha,))
    dest = f"{DEST}/{name}"
    out.save(dest, "WEBP", quality=80, alpha_quality=100, method=6)
    print(f"  {name:20} ink coverage {sum(alpha.getdata())/(W*H)/255*100:4.1f}%  {os.path.getsize(dest)//1024} kB")

print("\nCopy them to pi-reader too:")
print("  cp server/src/main/resources/META-INF/resources/media/*.webp \\")
print("     pi-reader/src/main/resources/META-INF/resources/media/")
