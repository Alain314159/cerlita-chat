#!/usr/bin/env python3
"""Mide anclas nuevas (pies, orejas, cuello, paleta) sobre las bases 1024."""
import json
import numpy as np
from PIL import Image

BASE = "/home/z/my-project/app/tools/avatares/work3d/out"

def alpha(im):
    return np.asarray(im.convert("RGBA"))[:, :, 3]

def row_width(a, y):
    row = a[y] > 40
    return row.sum()

def col_range(a, y):
    row = np.where(a[y] > 40)[0]
    return (row.min(), row.max()) if len(row) else (None, None)

def measure(name):
    im = Image.open(f"{BASE}/{name}-1024.png")
    a = alpha(im)
    h, w = a.shape
    print(f"\n=== {name} ===")
    # ── pies: blobs en las filas 88-99% ──
    y_foot = int(h * 0.90)
    row = np.where(a[y_foot] > 40)[0]
    # separar blobs contiguos
    blobs, start = [], None
    prev = None
    for x in row:
        if start is None:
            start = x
        elif x - prev > 12:  # hueco > 12px = otro pie
            blobs.append((start, prev)); start = x
        prev = x
    if start is not None:
        blobs.append((start, prev))
    print(f"fila {y_foot}: blobs={blobs}")
    # para cada blob: extension vertical (desde donde empieza el pie)
    for i, (x0, x1) in enumerate(blobs[:4]):
        col = a[:, (x0 + x1) // 2] > 40
        ys = np.where(col)[0]
        # el pie = ultima seccion continua que termina en el fondo
        top = ys.max()
        # subir mientras la columna sea continua
        while top - 1 in ys:
            top -= 1
        print(f"  pie{i}: x=[{x0},{x1}] cx={(x0+x1)//2} w={x1-x0} top_y={top} bottom={ys.max()}")
    # ── cuello: ancho de fila alrededor del chin ──
    an = json.load(open(f"/home/z/my-project/app/tools/avatares/work3d/medidas/anchors_{name}.json"))
    chin = an["chin_y"]
    for dy in (10, 20, 30, 40):
        y = chin + dy
        print(f"  ancho fila chin{+dy} (y={y}): {row_width(a, y)}  rango={col_range(a, y)}")
    # torso max width
    widths = [(y, row_width(a, y)) for y in range(chin, int(h*0.93), 8)]
    ymax, wmax = max(widths, key=lambda t: t[1])
    print(f"  torso max: y={ymax} w={wmax}")
    # ── orejas: extension del blob de oreja alrededor de ear anchor ──
    for side, (ex, ey) in (("l", an["ear_l"]), ("r", an["ear_r"])):
        # alrededor del anchor buscar el bbox del blob conectado (region local 300x300)
        y0, y1 = max(0, ey - 80), ey + 260
        x0, x1 = max(0, ex - 120), min(w, ex + 120)
        reg = a[y0:y1, x0:x1] > 40
        ys, xs = np.where(reg)
        print(f"  oreja {side}: anchor=({ex},{ey}) bbox_local y=[{y0+ys.min()},{y0+ys.max()}] x=[{x0+xs.min()},{x0+xs.max()}]")
    # ── paleta: colores dominantes del cuerpo (RGB medios de cuantizacion) ──
    rgb = np.asarray(im.convert("RGB")).reshape(-1, 3)
    al = a.reshape(-1)
    solid = rgb[al > 200]
    # cuantizar a pasos de 24 y contar
    q = (solid // 24 * 24 + 12)
    from collections import Counter
    cnt = Counter(map(tuple, q[::7]))
    print("  paleta dominante:", [f"#{r:02x}{g:02x}{b:02x} x{c}" for (r, g, b), c in cnt.most_common(6)])
    # color en puntos concretos
    for label, (px, py) in {"piel_cabeza": (512, 250), "panza": (512, 800), "mejilla": (0, 0)}.items():
        if px:
            r, g, b = im.convert("RGB").getpixel((px, py))
            print(f"  {label} @(512,250)=#{r:02x}{g:02x}{b:02x}" if label == "piel_cabeza" else f"  {label} @(512,800)=#{r:02x}{g:02x}{b:02x}")

for nm in ("cerdita", "koalita"):
    measure(nm)

# ¿las colorways comparten geometria? diff de anchors
import itertools, glob
for fam in ("cerdita", "koalita"):
    files = sorted(glob.glob(f"/home/z/my-project/app/tools/avatares/work3d/medidas/anchors_{fam}*.json"))
    files += sorted(glob.glob(f"/home/z/my-project/app/tools/avatares/work3d/medidas/anchors_{'koalita' if fam=='cerdita' else 'cerdita'}*.json"))
    base = json.load(open(f"/home/z/my-project/app/tools/avatares/work3d/medidas/anchors_{fam}.json"))
    for f in files:
        if fam in f:
            other = json.load(open(f))
            keys = [k for k in base if k != "bbox"]
            diff = {k: (base[k], other[k]) for k in keys if base[k] != other[k]}
            print(f"\n{f.split('/')[-1]} diff vs {fam}: {diff if diff else 'IDENTICA'}")
