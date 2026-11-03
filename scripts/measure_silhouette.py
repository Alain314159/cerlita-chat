#!/usr/bin/env python3
"""Silueta del cuerpo fila a fila + top de pies + base de orejas -> silhouette.json"""
import json
import numpy as np
from PIL import Image

BASE = "/home/z/my-project/app/tools/avatares/work3d/out"
OUT = "/home/z/my-project/app/tools/avatares/work3d/medidas/silhouette.json"

def analyze(name):
    im = Image.open(f"{BASE}/{name}-1024.png")
    a = np.asarray(im.convert("RGBA"))[:, :, 3]
    h, w = a.shape
    an = json.load(open(f"/home/z/my-project/app/tools/avatares/work3d/medidas/anchors_{name}.json"))
    res = {"feet": [], "silhouette": []}

    # ── top del pie: run contiguo desde el fondo hacia arriba en el cx del pie ──
    for cx in (425, 599) if name == "cerdita" else (427, 595):
        y = h - 1
        # bajar hasta el primer pixel solido desde abajo
        while y > 0 and a[y, cx] <= 40:
            y -= 1
        bottom = y
        while y > 0 and a[y, cx] > 40:
            y -= 1
        top = y + 1
        # ancho del pie en su mitad
        ymid = (top + bottom) // 2
        row = np.where(a[ymid] > 40)[0]
        # blob que contiene cx
        sel = row[np.abs(row - cx) < 80]
        res["feet"].append(dict(cx=int(cx), top=int(top), bottom=int(bottom),
                                x0=int(sel.min()), x1=int(sel.max())))
    # separacion de pies: ultima fila (de abajo arriba) con UN solo blob
    y = int(h * 0.94)
    split_y = None
    while y > int(h * 0.75):
        row = np.where(a[y] > 40)[0]
        if len(row) == 0:
            y -= 2; continue
        gaps = np.diff(row)
        if (gaps > 25).any():
            split_y = int(y)
            break
        y -= 2
    res["feet_split_y"] = split_y

    # ── silueta: half-width por fila desde chin hasta bottom (para ropa) ──
    chin = an["chin_y"]
    for y in range(int(chin), 940, 10):
        row = np.where(a[y] > 40)[0]
        if len(row):
            res["silhouette"].append([int(y), int(row.min()), int(row.max())])

    # ── base de oreja: donde la columna del anchor de oreja deja de ser oreja
    # (subimos desde la cabeza: primera fila solida por encima del hueco) ──
    ears = {}
    for side, key in (("l", "ear_l"), ("r", "ear_r")):
        ex, ey = an[key]
        # buscamos el hueco (fondo) entre oreja y cabeza en la columna ex:
        y = int(ey) + 300
        base = None
        while y > ey:  # de abajo (cabeza) hacia arriba
            if a[y, ex] <= 40:      # fondo = fin de cabeza, la oreja empieza mas arriba
                # buscar siguiente seccion solida (la oreja)
                y2 = y
                while y2 > ey and a[y2, ex] <= 40:
                    y2 -= 1
                base = int(y2 + 1) if y2 > ey else None
                break
            y -= 2
        ears[side] = base
    res["ear_base_y"] = ears
    return res

out = {}
for nm in ("cerdita", "koalita"):
    out[nm] = analyze(nm)
    f = out[nm]
    print(nm, "feet:", f["feet"], "split:", f["feet_split_y"], "ear_base:", f["ear_base_y"])
    print("  silueta chin..940 (cada 50):", [s for s in f["silhouette"] if s[0] % 50 < 10])
json.dump(out, open(OUT, "w"), indent=1)
print("saved", OUT)
