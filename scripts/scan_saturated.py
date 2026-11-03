#!/usr/bin/env python3
"""Escaneo dirigido: colores saturados (botones/badges/corazones) y color de
texto dentro de burbujas (píxeles oscuros vs claros dentro de cada burbuja)."""
from PIL import Image
import collections

im = Image.open('/home/z/my-project/upload/IMG-20261027-WA0044.jpg').convert('RGB')

def hexc(c):
    return '#{:02X}{:02X}{:02X}'.format(*c)

def scan_saturated(crop, region, label):
    x0, y0, x1, y1 = region
    reds, greens, pinks, darks, whites = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    for px in im.crop((crop[0]+x0, crop[1]+y0, crop[0]+x1, crop[1]+y1)).getdata():
        r, g, b = px
        if r > 185 and r - g > 55 and r - b > 40: reds[(r//8*8, g//8*8, b//8*8)] += 1
        elif g > 110 and g - r > 25 and g - b > -10: greens[(r//8*8, g//8*8, b//8*8)] += 1
        elif r > 235 and g < 200 and b > 170 and b < 215: pinks[(r//8*8, g//8*8, b//8*8)] += 1
        elif r < 110 and g < 110 and b < 110: darks[(r//8*8, g//8*8, b//8*8)] += 1
        elif r > 245 and g > 240 and b > 235: whites[(r//8*8, g//8*8, b//8*8)] += 1
    print(f"== {label} ==")
    for name, cnt in [('ROJOS/CORALES', reds), ('VERDES', greens), ('ROSAS BRILLANTES', pinks), ('OSCUROS (texto)', darks), ('BLANCOS', whites)]:
        top = cnt.most_common(4)
        if top:
            print(f"  {name:18s}: " + ', '.join(f'{hexc(c)} x{n}' for c, n in top))
    print()

# P3 chat: botón enviar (mic) abajo-derecha; burbujas para texto
scan_saturated((680,194,1080,626), (55, 40, 375, 425), 'P3 CHAT — todos los saturados')

# Texto DENTRO de una burbuja enviada rosa: P3 grid filas 10-12, cols 12-21
# (x 200-355, y 145-180 del crop)
crop3 = im.crop((680+200, 194+145, 680+355, 194+180))
pxs = list(crop3.getdata())
dark = [p for p in pxs if sum(p) < 330]
light = [p for p in pxs if sum(p) > 690]
def avg(lst):
    if not lst: return None
    return tuple(sum(c[i] for c in lst)//len(lst) for i in range(3))
print(f"Burbuja ENVIADA #F6C6CC: pixeles oscuros={len(dark)} media={hexc(avg(dark)) if dark else '—'} | claros={len(light)} media={hexc(avg(light)) if light else '—'}")
print()

# Texto DENTRO de una burbuja recibida gris: P3 grid filas 6-8, cols 4-14
crop4 = im.crop((680+70, 194+85, 680+245, 194+125))
pxs = list(crop4.getdata())
dark = [p for p in pxs if sum(p) < 330]
light = [p for p in pxs if sum(p) > 690]
print(f"Burbuja RECIBIDA #CCBABA: pixeles oscuros={len(dark)} media={hexc(avg(dark)) if dark else '—'} | claros={len(light)} media={hexc(avg(light)) if light else '—'}")
print()

# P6: botones aceptar/rechazar de la llamada
scan_saturated((680,669,1080,1080), (55, 30, 375, 400), 'P6 LLAMADA — saturados')

# P2: badges y tab activo
scan_saturated((335,194,745,626), (40, 40, 370, 420), 'P2 LISTA — saturados')
