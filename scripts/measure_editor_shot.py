#!/usr/bin/env python3
"""Mide la caja negra (SceneView 230dp) y la silueta del koala dentro, en la
captura del editor, para deducir el encuadre/FOV por defecto de SceneView."""
from PIL import Image
im = Image.open("/home/z/my-project/upload/Screenshot_2026-10-28-17-42-48-406_cu.alain.chat.jpg").convert("RGB")
W, H = im.size
px = im.load()

def is_dark(p): return p[0] < 38 and p[1] < 34 and p[2] < 38

# Escaneo por la columna central (el avatar está centrado horizontalmente)
cx = W // 2
runs, start = [], None
for y in range(H):
    d = is_dark(px[cx, y])
    if d and start is None: start = y
    if not d and start is not None:
        if y - start > 60: runs.append((start, y))
        start = None
print(f"imagen {W}x{H}, columna central x={cx}: runs negros {runs}")
by0, by1 = max(runs, key=lambda r: r[1] - r[0])
# ancho: escanea la fila central del run
midy = (by0 + by1) // 2
xs = [x for x in range(W) if is_dark(px[x, midy])]
bx0, bx1 = min(xs), max(xs)
print(f"caja negra: x[{bx0},{bx1}] y[{by0},{by1}]  = {bx1-bx0}x{by1-by0} px")

# silueta del personaje: píxeles dentro de la caja que NO son negros
cx0, cx1, cy0, cy1 = bx1, bx0, by1, by0
for y in range(by0, by1, 2):
    for x in range(bx0, bx1, 2):
        p = px[x, y]
        if not is_dark(p):
            cx0 = min(cx0, x); cx1 = max(cx1, x)
            cy0 = min(cy0, y); cy1 = max(cy1, y)
print(f"personaje: x[{cx0},{cx1}] y[{cy0},{cy1}]  = {cx1-cx0}x{cy1-cy0} px")
bh = by1 - by0
ph = cy1 - cy0
print(f"ocupación vertical del personaje en la caja: {ph/bh:.3f}")
print(f"ocupación horizontal: {(cx1-cx0)/(bx1-bx0):.3f}")
# el modelo tras scaleToUnits(1.55) mide 1.55 unidades de alto (o de su dimensión máx)
# fracción = 1.55 / span_vertical(d) ; span = 2*d*tan(fov/2) con d≈2.75 (DEFAULT_Z)
d = 2.75
span = 1.55 / (ph / bh)
import math
fov = 2 * math.degrees(math.atan(span / 2 / d))
print(f"span vertical a d={d}: {span:.2f} unidades → FOV vertical ≈ {fov:.1f}°")
