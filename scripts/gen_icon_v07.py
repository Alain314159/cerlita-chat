#!/usr/bin/env python3
"""Icono v0.7.0 — cerdito + koala (imagen del usuario) → adaptive + legacy.

Estrategia (análisis VLM de IMG-20261027-WA0042.jpg):
  - El grupo (cerdito izq + koala der + burbujas) ocupa ~8-92% del ancho y
    12-88% del alto. Recorte seguro: [0.07, 0.11, 0.93, 0.91].
  - Adaptive foreground (108dp): la zona visible es el círculo central ~66%.
    Fondo = imagen completa desenfocada (relleno perfecto, sin costuras);
    contenido nítido al 66% centrado (los animales caben en el círculo).
  - Legacy (API 24-25, sin adaptive): cuadrado completo con esquinas
    redondeadas 20% — Xiaomi antiguos usan este.
  - Monochrome (API 33+, iconos temáticos): vector geométrico de las dos
    cabezas + corazón (el bitmap no sirve: necesita alfa plano).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

SRC = Path("/home/z/my-project/upload/IMG-20261027-WA0042.jpg")
RES = Path("/home/z/my-project/app/android/app/src/main/res")

# Recorte del contenido (fracciones del cuadrado original)
CROP = (0.07, 0.10, 0.93, 0.92)

# Densidades: legacy ic_launcher / adaptive foreground
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def make_canvas(img: Image.Image, size: int, content_scale: float, corner: float | None) -> Image.Image:
    """Compone: fondo = imagen completa a sangre + blur; contenido nítido centrado."""
    # fondo desenfocado que llena todo el lienzo (recorte central si hace falta)
    bg = img.resize((size, size), Image.LANCZOS).filter(ImageFilter.GaussianBlur(size * 0.06))
    bg = bg.point(lambda p: min(255, int(p * 1.02)))  # aclara un pelín

    # contenido nítido: recorte → cuadrado (pad con clon del propio recorte)
    w, h = img.size
    box = (int(CROP[0] * w), int(CROP[1] * h), int(CROP[2] * w), int(CROP[3] * h))
    content = img.crop(box)
    cw, ch = content.size
    side = max(cw, ch)
    sq = Image.new("RGB", (side, side))
    sq.paste(content, ((side - cw) // 2, (side - ch) // 2))

    inner = int(size * content_scale)
    sq = sq.resize((inner, inner), Image.LANCZOS)
    canvas = bg.copy()
    canvas.paste(sq, ((size - inner) // 2, (size - inner) // 2))

    if corner:  # legacy: esquinas redondeadas (radio = corner * size)
        mask = Image.new("L", (size, size), 0)
        d = ImageDraw.Draw(mask)
        r = int(size * corner)
        d.rounded_rectangle((0, 0, size - 1, size - 1), radius=r, fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(canvas, (0, 0), mask)
        return out
    return canvas


def main() -> None:
    img = Image.open(SRC).convert("RGB")

    for dpi, size in LEGACY.items():
        out = RES / f"mipmap-{dpi}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        make_canvas(img, size, content_scale=0.80, corner=0.20).save(out, "PNG")
        print(f"[legacy] {out} ({size}px)")

    for dpi, size in FOREGROUND.items():
        out = RES / f"mipmap-{dpi}" / "ic_launcher_foreground.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        make_canvas(img, size, content_scale=0.66, corner=None).save(out, "PNG")
        print(f"[foreground] {out} ({size}px)")

    print("OK — iconos regenerados")


if __name__ == "__main__":
    main()
