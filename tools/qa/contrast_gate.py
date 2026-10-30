#!/usr/bin/env python3
"""D120 (Fase P): medición WCAG del contraste del TEXTO SECUNDARIO.

El informe de preparación 1.0 (tabla 8) exige para la 1.0: «contraste de texto
secundario igual o superior a 4,5:1 en los tres temas, medición documentada».
La auditoría del 28-oct midió 3,4:1 en el tema Cerlita (onSurfaceVariant
#7A6167 sobre la burbuja recibida #CCBABA) y calculó el tono correcto:
#6B5157 (~4,9:1).

H32 (D120): el cálculo de la auditoría estaba MAL — medido con la fórmula
WCAG real, #6B5157 da 3,8:1 sobre la burbuja y 4,3:1 sobre el fondo del chat.
El tono que cumple en TODOS los fondos realistas (misma familia ciruela,
H=350° S=12% L=31%) es #594649: 4,7:1 burbuja · 5,3:1 fondo del chat ·
6,6:1 listas. Lección repetida de D77 («medir píxeles > estimar VLM»):
los tonos de accesibilidad también se calculan, no se estiman a ojo.

Este script MIDE (no estima) cada par texto/fondo de los temas de la app —
los valores están duplicados de Theme.kt/ChatPalette a propósito: si alguien
cambia un color ahí sin repensar el contraste, esta puerta lo destapa (se
ejecuta en CI, igual que la de cobertura D116).

Uso: python3 tools/qa/contrast_gate.py   (exit 0 = todo AA, exit 1 = hay fallo)
"""
from __future__ import annotations

import sys

AA = 4.5  # texto normal (WCAG 2.1 · 1.4.3)


def lin(c: int) -> float:
    """Canal sRGB → lineal (WCAG)."""
    s = c / 255.0
    return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4


def lum(hexv: str) -> float:
    h = hexv.lstrip("#")
    r, g, b = (int(h[i : i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(fg: str, bg: str) -> float:
    a, b = lum(fg), lum(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


# ── Pares medidos: texto secundario (onSurfaceVariant y textos de burbuja)
#    de cada tema, sobre los fondos REALISTAS donde aparecen. Los valores
#    espejan Theme.kt (D77/D108) y ChatPalette. ──
PAIRS = [
    # (tema, texto, fondo, qué es)
    ("Cerlita", "#594649", "#CCBABA", "onSurfaceVariant sobre burbuja recibida"),
    ("Cerlita", "#594649", "#F6D8D8", "onSurfaceVariant sobre fondo de listas"),
    ("Cerlita", "#594649", "#FBEAEA", "onSurfaceVariant sobre tarjetas"),
    ("Cerlita", "#594649", "#E0C2C4", "onSurfaceVariant sobre fondo del chat"),
    ("Cerlita", "#594649", "#F6C6CC", "onSurfaceVariant sobre burbuja enviada"),
    ("Cerlita", "#5E4247", "#F6C6CC", "texto de burbuja enviada (principal)"),
    ("Cerlita", "#4A3E3E", "#CCBABA", "texto de burbuja recibida (principal)"),
    ("Cerlita noche", "#C9B7BD", "#463A40", "onSurfaceVariant sobre burbuja recibida"),
    ("Cerlita noche", "#C9B7BD", "#20191C", "onSurfaceVariant sobre fondo"),
    ("Cerlita noche", "#C9B7BD", "#291F23", "onSurfaceVariant sobre tarjetas"),
    ("Cerlita noche", "#F6E4E7", "#6E454E", "texto de burbuja enviada (principal)"),
    ("Cerlita noche", "#D9C9CE", "#3B2F35", "texto de burbuja recibida (principal)"),
    ("Oscuro", "#94A3B8", "#1B2740", "onSurfaceVariant sobre surfaceVariant"),
    ("Oscuro", "#94A3B8", "#111A2C", "onSurfaceVariant sobre fondo"),
    ("Claro (M3)", "#49454F", "#E7E0EC", "onSurfaceVariant M3 por defecto"),
]


def main() -> int:
    fallos = 0
    print(f"Contraste WCAG del texto — umbral AA {AA}:1 (texto normal)")
    for tema, fg, bg, que in PAIRS:
        r = ratio(fg, bg)
        ok = r >= AA
        print(f"  {'✓' if ok else '✗'} {tema:13s} {fg} sobre {bg} = {r:4.1f}:1  · {que}")
        if not ok:
            fallos += 1
    if fallos:
        print(f"\nGATE ✗: {fallos} par(es) por debajo de {AA}:1 — oscurecer/aclarar el texto")
        return 1
    print(f"\nGATE ✓: todos los pares ≥ {AA}:1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
