#!/usr/bin/env python3
"""Muestreo de colores exactos del mockup de UI (IMG-20261027-WA0044.jpg).
6 teléfonos en grilla 3x2. Extrae colores dominantes + grid de muestras
de la pantalla de chat (P3) y la lista de chats (P2) para el tema Cerlita."""
from PIL import Image
import collections

im = Image.open('/home/z/my-project/upload/IMG-20261027-WA0044.jpg').convert('RGB')
W, H = im.size  # 1080x1080

def hexc(c):
    return '#{:02X}{:02X}{:02X}'.format(*c)

def crop_phone(cx_pct, cy_pct, name):
    """Recorta un teléfono alrededor de su centro estimado."""
    cx, cy = int(W * cx_pct / 100), int(H * cy_pct / 100)
    # teléfono ~34% de ancho, ~36% de alto (con margen)
    half_w, half_h = int(W * 0.19), int(H * 0.20)
    box = (max(0, cx - half_w), max(0, cy - half_h), min(W, cx + half_w), min(H, cy + half_h))
    return im.crop(box), box

def dominant_colors(img, n=8):
    """Colores dominantes por cuantización simple."""
    small = img.resize((60, 60))
    quant = collections.Counter()
    for px in small.getdata():
        # cuantizar a bloques de 8 para agrupar tonos
        q = (px[0] // 8 * 8, px[1] // 8 * 8, px[2] // 8 * 8)
        quant[q] += 1
    return [(hexc(c), cnt) for c, cnt in quant.most_common(n)]

def grid_map(img, cols=24, rows=30, box_out=None):
    """Mapa de colores en grilla gruesa."""
    w, h = img.size
    lines = []
    for r in range(rows):
        row = []
        for c in range(cols):
            x = int((c + 0.5) * w / cols)
            y = int((r + 0.5) * h / rows)
            px = img.getpixel((x, y))
            row.append(hexc(px))
        lines.append(' '.join(row))
    return '\n'.join(lines)

# P2 lista de chats (centro 50%,38%) y P3 chat activo (centro 82%,38%)
p2, box2 = crop_phone(50, 38, 'P2')
p3, box3 = crop_phone(82, 38, 'P3')
p6, box6 = crop_phone(82, 82, 'P6')  # estados + llamada entrante

print(f"== P2 (lista de chats) box={box2} ==")
print("Dominantes:", dominant_colors(p2))
print()
print(f"== P3 (chat activo) box={box3} ==")
print("Dominantes:", dominant_colors(p3))
print()
print(f"== P6 (estados/llamada) box={box6} ==")
print("Dominantes:", dominant_colors(p6))
print()

# Guardar recortes ampliados para inspección
for nm, img in [('p2_chats', p2), ('p3_chat', p3), ('p6_call', p6)]:
    img2 = img.resize((img.width * 2, img.height * 2), Image.LANCZOS)
    img2.save(f'/home/z/my-project/scripts/mockup_{nm}.png')
    print(f"guardado scripts/mockup_{nm}.png {img.size}")

# Grid de color del chat activo (P3) — para ubicar burbujas/fondos exactos
print()
print("== GRID P3 chat activo (24x30) ==")
print(grid_map(p3))
