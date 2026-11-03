#!/usr/bin/env python3
"""Extrae los colores PUROS (picos de frecuencia, no mezclas) de la zona de
pantalla de cada teléfono del mockup. Los picos de histograma = colores reales
de los elementos (burbujas, fondos, botones), las mezclas bordes/texto suaves."""
from PIL import Image
import collections

im = Image.open('/home/z/my-project/upload/IMG-20261027-WA0044.jpg').convert('RGB')

def hexc(c):
    return '#{:02X}{:02X}{:02X}'.format(*c)

def pure_peaks(crop, region, label, min_count=25):
    """Top colores puros (cuantizados /6) dentro de una región."""
    x0, y0, x1, y1 = region
    cnt = collections.Counter()
    for px in im.crop((crop[0]+x0, crop[1]+y0, crop[0]+x1, crop[1]+y1)).getdata():
        q = (px[0]//6*6, px[1]//6*6, px[2]//6*6)
        cnt[q] += 1
    print(f"== {label} ==")
    for c, n in cnt.most_common(14):
        if n < min_count: break
        print(f"  {hexc(c)}  x{n}")
    print()

# P3 chat activo: crop (680,194,1080,626); pantalla real ~x 55-375 (dentro del crop)
pure_peaks((680,194,1080,626), (55, 40, 375, 420), 'P3 CHAT — pantalla completa')
# P2 lista: crop (335,194,745,626); pantalla ~x 40-370
pure_peaks((335,194,745,626), (40, 40, 370, 420), 'P2 LISTA — pantalla completa')
# P6 estados/llamada: crop (680,669,1080,1080); pantalla ~x 55-375
pure_peaks((680,669,1080,1080), (55, 30, 375, 400), 'P6 ESTADOS/LLAMADA — pantalla')
