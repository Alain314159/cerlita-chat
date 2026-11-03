#!/usr/bin/env python3
"""Muestreo preciso por región de los elementos del mockup (P3 chat, P2 lista, P6 llamada)."""
from PIL import Image, ImageStat

im = Image.open('/home/z/my-project/upload/IMG-20261027-WA0044.jpg').convert('RGB')

def hexc(c):
    return '#{:02X}{:02X}{:02X}'.format(*c)

def avg(img, box):
    """Color medio de una región (x0,y0,x1,y1) relativa al recorte."""
    st = ImageStat.Stat(img.crop(box))
    return tuple(int(v) for v in st.mean)

def sample(name, crop_box, regions):
    crop = im.crop(crop_box)
    print(f"== {name} (crop {crop_box}) ==")
    for label, (x0, y0, x1, y1) in regions.items():
        c = avg(crop, (x0, y0, x1, y1))
        print(f"  {label:38s} {hexc(c)}")
    print()

# P3: chat activo. Crop (680,194,1080,626) 400x432. Pantalla ~x730-1055 => col 3-22
# Basado en el grid 24x30 anterior (cada col=16.7px, fila=14.4px del recorte)
sample('P3 CHAT ACTIVO', (680, 194, 1080, 626), {
    # wallpaper del chat (zonas sin burbujas, fila 24-25 cols 3-18)
    'wallpaper chat (centro-abajo)':       (60, 345, 300, 365),
    'wallpaper chat (centro-arriba)':      (60, 130, 180, 145),
    # burbuja ENVIADA (rosa): filas 2-5 cols 14-19 => y 30-80, x 230-330
    'burbuja enviada (rosa) 1':            (245, 35, 320, 70),
    'burbuja enviada (rosa) 2':            (170, 155, 300, 175),   # filas 10-12
    'burbuja enviada (rosa) 3':            (175, 280, 330, 300),   # filas 19-21
    # burbuja RECIBIDA (gris): filas 6-8 cols 4-15 => y 85-125, x 70-260
    'burbuja recibida (gris) 1':           (75, 90, 250, 120),
    'burbuja recibida (gris) 2':           (75, 205, 250, 240),    # filas 14-17
    'burbuja recibida (gris) 3':           (75, 315, 250, 350),    # filas 22-24
    # texto dentro de burbujas (puntos oscuros vistos en grid)
    'texto burbuja recibida':              (90, 95, 200, 115),
    # barra inferior / input: filas 26-29
    'zona input bar (fondo)':              (60, 375, 300, 395),
    'boton redondo der (mic/enviar)':      (330, 375, 380, 420),
    # header: filas 0-2
    'header superior':                     (60, 10, 300, 28),
})

# P2: lista de chats. Crop (335,194,745,626) 410x432
sample('P2 LISTA DE CHATS', (335, 194, 745, 626), {
    'header (avatar+titulo)':              (60, 10, 300, 30),
    'fondo lista (entre items)':           (60, 200, 350, 215),
    'search bar':                          (100, 70, 300, 90),
    'badge rojo item 1':                   (340, 105, 370, 135),
    'nav inferior fondo':                  (60, 380, 350, 400),
    'tab activo (Chats)':                  (75, 375, 130, 405),
    'item chat fondo':                     (60, 95, 350, 130),
})

# P6: estados + llamada entrante. Crop (680,669,1080,1080) 400x411
sample('P6 ESTADOS+LLAMADA', (680, 669, 1080, 1080), {
    'fondo tarjeta estado':                (60, 130, 340, 160),
    'fondo general pantalla':              (60, 60, 340, 80),
    'boton rechazar (rojo)':               (95, 330, 145, 380),
    'boton aceptar (verde)':               (255, 330, 305, 380),
    'zona botones inferior':               (60, 330, 340, 390),
})

# Grid de P2 para ver estructura de la lista
p2 = im.crop((335, 194, 745, 626))
w, h = p2.size
print('== GRID P2 lista (20x28) ==')
for r in range(28):
    row = []
    for c in range(20):
        x = int((c + 0.5) * w / 20); y = int((r + 0.5) * h / 28)
        row.append(hexc(p2.getpixel((x, y))))
    print(' '.join(row))
