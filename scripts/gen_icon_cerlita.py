#!/usr/bin/env python3
"""Genera los mipmap PNG legacy de Cerlita Chat (API 24-25 no usan adaptive icons).

Diseño idéntico al vector ic_launcher_foreground.xml:
  fondo violeta #2A1E4F (cuadrado redondeado), burbuja crema #F2E8D5 con cola,
  candado violeta (arco + cuerpo) y cerradura en forma de corazón #F08CA0.
Se dibuja a 4x y se reduce con LANCZOS para antialiasing.
"""
from PIL import Image, ImageDraw

BASE = "/home/z/my-project/app/android/app/src/main/res"
SS = 4  # supersampling

# Colores
VIOLET = (42, 30, 79, 255)      # #2A1E4F
CREAM = (242, 232, 213, 255)    # #F2E8D5
ROSE = (240, 140, 160, 255)     # #F08CA0
TRANSPARENT = (0, 0, 0, 0)

# Coordenadas del diseño en viewport 108x108 (las mismas del vector)
BUBBLE = (36, 30, 82, 72)        # x0,y0,x1,y1 (rect redondeado r=10)
TAIL = [(52, 71), (38, 84), (38, 71)]
SHACKLE_CX, SHACKLE_CY, SHACKLE_R = 54, 45, 7
SHACKLE_LINES = [((47, 44), (47, 50)), ((61, 44), (61, 50))]
BODY = (45, 50, 63, 67.5)        # rounded rect r=3.5
HEART_L = (51.7, 57.2, 2.6)      # circulo izq (cx, cy, r)
HEART_R = (56.3, 57.2, 2.6)


def heart_points(scale: float):
    """Puntos de un corazón suave (dos lóbulos + punta)."""
    import math
    pts = []
    for i in range(33):  # lóbulo izquierdo
        a = math.pi - (i / 32) * math.pi
        pts.append((HEART_L[0] + HEART_L[2] * math.cos(a), HEART_L[1] - HEART_L[2] * math.sin(a) * 0.95))
    for i in range(33):  # lóbulo derecho
        a = -(i / 32) * math.pi
        pts.append((HEART_R[0] + HEART_R[2] * math.cos(a), HEART_R[1] - HEART_R[2] * math.sin(a) * 0.95))
    pts.append((54, 63.6))
    return [(x * scale, y * scale) for x, y in pts]


def draw_icon(size: int) -> Image.Image:
    """Dibuja el icono completo a `size` px."""
    w = size * SS
    s = w / 108.0  # escala viewport→px
    img = Image.new("RGBA", (w, w), TRANSPARENT)
    d = ImageDraw.Draw(img)

    # Fondo: cuadrado redondeado full-bleed (legacy no recorta)
    d.rounded_rectangle([0, 0, w - 1, w - 1], radius=int(w * 0.22), fill=VIOLET)

    # Burbuja (rect redondeado) + cola
    bb = [c * s for c in BUBBLE]
    d.rounded_rectangle(bb, radius=10 * s, fill=CREAM)
    d.polygon([(x * s, y * s) for x, y in TAIL], fill=CREAM)

    # Arco del candado (semicírculo superior) + laterales
    arc_box = [
        (SHACKLE_CX - SHACKLE_R) * s, (SHACKLE_CY - SHACKLE_R) * s,
        (SHACKLE_CX + SHACKLE_R) * s, (SHACKLE_CY + SHACKLE_R) * s,
    ]
    lw = max(1, int(4.5 * s))
    d.arc(arc_box, start=180, end=360, fill=VIOLET, width=lw)
    for (x0, y0), (x1, y1) in SHACKLE_LINES:
        d.line([x0 * s, y0 * s, x1 * s, y1 * s], fill=VIOLET, width=lw)

    # Cuerpo del candado
    d.rounded_rectangle([c * s for c in BODY], radius=3.5 * s, fill=VIOLET)

    # Cerradura: corazón
    d.polygon(heart_points(s), fill=ROSE)

    return img.resize((size, size), Image.LANCZOS)


DENSITIES = {  # mipmap-<dpi>: tamaño px de ic_launcher
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

if __name__ == "__main__":
    import os
    for folder, size in DENSITIES.items():
        icon = draw_icon(size)
        out = os.path.join(BASE, folder, "ic_launcher.png")
        icon.save(out, "PNG")
        print(f"{out}  {size}x{size}  {os.path.getsize(out)} B")
    print("Listo.")
