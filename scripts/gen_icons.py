#!/usr/bin/env python3
"""Genera los PNG de ic_launcher para API 24-25 (fallback del adaptive icon).

Dibuja el mismo diseño que drawable/ic_launcher_foreground.xml:
fondo #0B1220, burbuja #2DD4BF, candado #0B1220 con cerradura #2DD4BF.
"""
from PIL import Image, ImageDraw
from pathlib import Path

RES = Path("/home/z/my-project/app/android/app/src/main/res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (11, 18, 32, 255)       # 0B1220
TEAL = (45, 212, 191, 255)   # 2DD4BF
DARK = (11, 18, 32, 255)


def rounded(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def draw_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 108.0  # escala desde el viewport 108

    # Fondo redondeado (full-bleed con esquinas suaves como los launcher icons)
    margin = size * 0.04
    rounded(d, (margin, margin, size - margin, size - margin), radius=size * 0.18, fill=BG)

    # Burbuja: rect redondeado + cola triangular
    x0, y0, x1, y1 = 30 * s, 36 * s, 88 * s, 76 * s
    r = 10 * s
    rounded(d, (x0, y0, x1, y1), radius=r, fill=TEAL)
    # cola abajo-izquierda
    tail = [(58 * s, 76 * s), (58 * s - 12 * s, 88 * s), (58 * s + 10 * s, 76 * s)]
    d.polygon(tail, fill=TEAL)

    # Candado: arco (círculo recortado)
    arc_box = (47 * s, 40 * s, 61 * s, 54 * s)  # círculo r=7 centrado (54,47)
    d.arc(arc_box, start=180, end=360, fill=DARK, width=max(2, int(4 * s)))

    # Candado: cuerpo
    rounded(d, (43 * s, 57 * s, 65 * s, 75 * s), radius=4 * s, fill=DARK)

    # Cerradura
    kw = max(2, int(2 * s))
    d.rectangle((53 * s, 63 * s, 53 * s + kw, 68 * s), fill=TEAL)
    return img


def main():
    for dpi, size in SIZES.items():
        out = RES / f"mipmap-{dpi}" / "ic_launcher.png"
        img = draw_icon(size)
        img.save(out)
        print(f"{out} ({size}x{size})")


if __name__ == "__main__":
    main()
