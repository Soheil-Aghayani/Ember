"""Measure WCAG contrast of every text node in an Android screenshot.

usage: contrast.py <screenshot.png> <uiautomator.xml> [min_ratio]
For each node with text, the background is the median colour of a thin frame just outside the text box and the text
colour is the pixel inside the box that differs most from that background.
"""
import re
import sys
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image


def lin(c):
    c = c / 255.0
    return np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lum(rgb):
    r, g, b = lin(np.array(rgb, dtype=np.float64))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


img = np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(np.int32)
threshold = float(sys.argv[3]) if len(sys.argv) > 3 else 4.5
tree = ET.parse(sys.argv[2])
rows = []
for node in tree.iter("node"):
    text = node.get("text") or node.get("content-desc") or ""
    if not text.strip() or node.get("package") != "com.soheil.ember":
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds"))
    x0, y0, x1, y1 = map(int, m.groups())
    if x1 - x0 < 6 or y1 - y0 < 6:
        continue
    pad = 6
    xa, ya, xb, yb = max(x0 - pad, 0), max(y0 - pad, 0), min(x1 + pad, img.shape[1]), min(y1 + pad, img.shape[0])
    frame = np.concatenate([
        img[ya:y0, xa:xb].reshape(-1, 3), img[y1:yb, xa:xb].reshape(-1, 3),
        img[y0:y1, xa:x0].reshape(-1, 3), img[y0:y1, x1:xb].reshape(-1, 3),
    ])
    if len(frame) == 0:
        continue
    bg = np.median(frame, axis=0)
    box = img[y0:y1, x0:x1].reshape(-1, 3)
    far = np.abs(box - bg).sum(axis=1)
    fg = box[np.argsort(far)[-max(len(far) // 60, 3):]].mean(axis=0)  # the strongest ~2% of pixels = the glyph cores
    rows.append((ratio(fg, bg), text.strip()[:40], tuple(int(v) for v in fg), tuple(int(v) for v in bg)))

bad = [r for r in rows if r[0] < threshold]
for r in sorted(rows)[:200]:
    flag = "LOW " if r[0] < threshold else "ok  "
    print(f"{flag}{r[0]:5.2f}  {r[1]!r:44} text={r[2]} bg={r[3]}")
print(f"-- {len(rows)} texts, {len(bad)} under {threshold}")
