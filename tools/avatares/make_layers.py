#!/usr/bin/env python3
"""
Cerlita Chat — generador parametrico de capas vectoriales (v2).

Cada base tiene su TABLA DE ANCLAS, medida sobre el artboard 1024x1024
(rejilla de 64 px).  Las capas se posicionan contra esas anclas -> el
accesorio cae siempre en su sitio, sin depender de heuristicas de color.

Salida:
  out/layers/<id>.svg    -> revision visual (QA con ImageMagick)
  out/drawable/<id>.xml  -> Android VectorDrawable (pathData 1:1)
"""
import os, re, math, json
import numpy as np
from PIL import Image

W = H = 1024
INK = "#503433"; HEART = "#F0758D"; WHITE = "#FEE8EB"; VIOLET = "#BC8FB5"
BLUSH = "#F999A5"; PINK = "#FFC1CC"

# ── TABLA DE ANCLAS por base (medidas sobre la rejilla del artboard 1024) ──
# v2 (D83, solo cabeza): cerdita / koalita
# v3 (D92, cuerpo completo 3D): cerdita3d / koalita3d — medidas automáticas
#     de build_avatar_3d.py (QA visual con rejilla) + body_bottom para la ropa
ANCHORS = {
  "cerdita": dict(cx=512, head_top=72, chin_y=845, head_rx=415,
                  ear_l=(172,178), ear_r=(836,178),
                  eye_l=(305,468), eye_r=(715,468), eye_y=468,
                  cheek_l=(232,556), cheek_r=(792,556), chest_y=905),
  "koalita": dict(cx=512, head_top=100, chin_y=855, head_rx=460,
                  ear_l=(215,245), ear_r=(810,245),
                  eye_l=(335,508), eye_r=(680,508), eye_y=508,
                  cheek_l=(282,610), cheek_r=(740,610), chest_y=930),
  # ── D92: cuerpo completo 3D (medido sobre las bases WebP nuevas) ──
  #     chin_y = fila donde la cabeza estrecha a cuello (medido por ancho de
  #     fila); torso_w = ancho real del cuerpo al 30% del torso (para la ropa);
  #     hat_y = coronilla entre orejas (donde apoya el gorro de fiesta)
  "cerdita3d": dict(cx=511, head_top=85, chin_y=474, head_rx=196,
                  ear_l=(254,113), ear_r=(767,113),
                  eye_l=(407,292), eye_r=(614,292), eye_y=292,
                  cheek_l=(397,347), cheek_r=(624,347), chest_y=659,
                  body_bottom=938, torso_w=569, hat_y=160),
  "koalita3d": dict(cx=510, head_top=86, chin_y=550, head_rx=262,
                  ear_l=(207,117), ear_r=(816,130),
                  eye_l=(403,361), eye_r=(618,335), eye_y=348,
                  cheek_l=(358,421), cheek_r=(662,421), chest_y=600,
                  body_bottom=937, torso_w=414, hat_y=205, hem_frac=0.42),
}

def P(d, fill, stroke=None, sw=0):
    s = f'    <path d="{d}" fill="{fill}"'
    if stroke: s += f' stroke="{stroke}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"'
    return s + "/>\n"

def ellipse(cx, cy, rx, ry):
    k = 0.5523
    return (f"M {cx-rx:.1f},{cy:.1f} "
            f"C {cx-rx:.1f},{cy-ry*k:.1f} {cx-rx*k:.1f},{cy-ry:.1f} {cx:.1f},{cy-ry:.1f} "
            f"C {cx+rx*k:.1f},{cy-ry:.1f} {cx+rx:.1f},{cy-ry*k:.1f} {cx+rx:.1f},{cy:.1f} "
            f"C {cx+rx:.1f},{cy+ry*k:.1f} {cx+rx*k:.1f},{cy+ry:.1f} {cx:.1f},{cy+ry:.1f} "
            f"C {cx-rx*k:.1f},{cy+ry:.1f} {cx-rx:.1f},{cy+ry*k:.1f} {cx-rx:.1f},{cy:.1f} Z")

def heart(cx, cy, r, fill, stroke=None, sw=0):
    d = (f"M {cx:.1f},{cy+r:.1f} "
         f"C {cx-r*1.12:.1f},{cy+r*0.20:.1f} {cx-r*0.95:.1f},{cy-r*0.85:.1f} {cx-r*0.42:.1f},{cy-r*0.80:.1f} "
         f"C {cx-r*0.10:.1f},{cy-r*0.76:.1f} {cx:.1f},{cy-r*0.46:.1f} {cx:.1f},{cy-r*0.30:.1f} "
         f"C {cx:.1f},{cy-r*0.46:.1f} {cx+r*0.10:.1f},{cy-r*0.76:.1f} {cx+r*0.42:.1f},{cy-r*0.80:.1f} "
         f"C {cx+r*0.95:.1f},{cy-r*0.85:.1f} {cx+r*1.12:.1f},{cy+r*0.20:.1f} {cx:.1f},{cy+r:.1f} Z")
    return P(d, fill, stroke, sw)

def petal(cx, cy, ang, length, width, fill, stroke, sw):
    """Petalo/lazo: gota que apunta en la direccion `ang` (radianes)."""
    a = math.radians(ang)
    ux, uy = math.cos(a), math.sin(a)
    px, py = -uy, ux
    def P2(lx, ly):
        return (cx + ux*lx + px*ly, cy + uy*lx + py*ly)
    t = P2(length, 0)
    c1 = P2(length*0.28, -width*1.15)
    c2 = P2(length*0.86, -width*0.80)
    c3 = P2(length*0.86,  width*0.80)
    c4 = P2(length*0.28,  width*1.15)
    d = (f"M {cx:.1f},{cy:.1f} "
         f"C {c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {t[0]:.1f},{t[1]:.1f} "
         f"C {c3[0]:.1f},{c3[1]:.1f} {c4[0]:.1f},{c4[1]:.1f} {cx:.1f},{cy:.1f} Z")
    return P(d, fill, stroke, sw)


def cloth_shirt(a, fill, pattern=None):
    """Camiseta DE FRENTE sobre el torso (D92: cuerpo completo → la ropa va
    ENCIMA de la base cubriendo el pecho; en v2 iba detrás como 'hombros').
    Escote recogido en el propio trazo, bajo a la cadera, mangas al costado."""
    cx = a["cx"]
    rx = a["head_rx"]
    top = a["chin_y"] + rx * 0.03            # hombros
    bot = a["chin_y"] + (a.get("body_bottom", a["chest_y"] + rx * 2) - a["chin_y"]) \
        * a.get("hem_frac", 0.55)            # koala: torso corto sobre vientre blanco
    w = a.get("torso_w", rx * 1.84) / 2 * 0.96  # medio ancho del torso MEDIDO
    body = (f"M {cx-w:.1f},{top+w*0.28:.1f} "
            f"C {cx-w:.1f},{top-w*0.02:.1f} {cx-w*0.66:.1f},{top-w*0.12:.1f} {cx-w*0.34:.1f},{top-w*0.08:.1f} "
            f"C {cx-w*0.10:.1f},{top+w*0.10:.1f} {cx+w*0.10:.1f},{top+w*0.10:.1f} {cx+w*0.34:.1f},{top-w*0.08:.1f} "
            f"C {cx+w*0.66:.1f},{top-w*0.12:.1f} {cx+w:.1f},{top-w*0.02:.1f} {cx+w:.1f},{top+w*0.28:.1f} "
            f"C {cx+w*0.98:.1f},{(top+bot)/2:.1f} {cx+w*0.90:.1f},{bot:.1f} {cx+w*0.80:.1f},{bot:.1f} "
            f"L {cx-w*0.80:.1f},{bot:.1f} "
            f"C {cx-w*0.90:.1f},{bot:.1f} {cx-w*0.98:.1f},{(top+bot)/2:.1f} {cx-w:.1f},{top+w*0.28:.1f} Z")
    b = P(body, fill, INK, 16)
    # mangas cortas: solapas al costado
    for sgn in (-1, 1):
        sx = cx + sgn * w * 0.99
        b += P(f"M {sx:.1f},{top+w*0.26:.1f} "
               f"C {cx+sgn*w*1.18:.1f},{top+w*0.30:.1f} {cx+sgn*w*1.22:.1f},{top+w*0.62:.1f} {cx+sgn*w*1.02:.1f},{top+w*0.66:.1f} "
               f"L {sx-sgn*w*0.10:.1f},{top+w*0.52:.1f} Z", fill, INK, 14)
    if pattern == "dots":
        hh = bot - top
        for i in (-2, -1, 0, 1, 2):
            for j in (0, 1, 2):
                b += P(ellipse(cx + i*w*0.34, top + hh*0.34 + j*hh*0.20, w*0.055, w*0.055), WHITE)
    elif pattern == "stripes":
        hh = bot - top
        for j in range(4):
            yb = top + hh*0.30 + j*hh*0.17
            b += P(f"M {cx-w*0.88:.1f},{yb:.1f} L {cx+w*0.88:.1f},{yb:.1f} "
                   f"L {cx+w*0.86:.1f},{yb+hh*0.10:.1f} L {cx-w*0.86:.1f},{yb+hh*0.10:.1f} Z", WHITE)
    return b

def acc_clothes_pink(a):   return cloth_shirt(a, "#F2A0B4")
def acc_clothes_violet(a): return cloth_shirt(a, "#C3A8DE")
def acc_clothes_teal(a):   return cloth_shirt(a, "#8FD4CE")
def acc_clothes_dots(a):   return cloth_shirt(a, "#F2A0B4", "dots")
def acc_clothes_stripes(a):return cloth_shirt(a, "#F2A0B4", "stripes")

# ── capas ──
def acc_bow(a):
    x, y = a["ear_l"]; s = a["head_rx"]*0.16
    b = petal(x, y+s*0.10, 205, s*1.45, s*0.78, HEART, INK, 14)
    b += petal(x, y-s*0.10, -25, s*1.45, s*0.78, HEART, INK, 14)
    b += P(ellipse(x, y, s*0.36, s*0.32), INK)
    return b

def acc_flower(a):
    x, y = a["ear_r"]; r = a["head_rx"]*0.13
    b = ""
    for i in range(5):
        ang = math.radians(-90 + i*72)
        b += P(ellipse(x + r*1.30*math.cos(ang), y + r*1.30*math.sin(ang), r*0.66, r*0.66), HEART, INK, 13)
    b += P(ellipse(x, y, r*0.42, r*0.42), WHITE, INK, 13)
    return b

def acc_party(a):
    """Gorro de fiesta apoyado en la coronilla (hat_y = fila del craneo entre
    orejas, medida — D92: en cuerpo completo la cabeza se hunde entre las
    orejas y head_top (punta de la oreja) dejaba el gorro flotando)."""
    x = a["cx"]; y = a.get("hat_y", a["head_top"] + a["head_rx"] * 0.35)
    w = a["head_rx"] * 0.20; h = a["head_rx"] * 0.42
    b = P(f"M {x-w*1.05:.1f},{y+h*0.22:.1f} L {x-w*0.22:.1f},{y-h:.1f} L {x+w*0.72:.1f},{y-h*0.28:.1f} Z", VIOLET, INK, 14)
    b += P(ellipse(x-w*0.12, y+h*0.26, w*1.62, h*0.20), VIOLET, INK, 14)
    b += heart(x-w*0.06, y-h-h*0.24, a["head_rx"] * 0.06, HEART, INK, 10)
    return b

def acc_glasses(a):
    """Gafas de sol redondas: lentes grandes que cubren los ojos."""
    y = a["eye_y"]; r = a["head_rx"]*0.24
    lx, rx_ = a["eye_l"][0], a["eye_r"][0]
    b = P(ellipse(lx, y, r, r*0.90), INK)
    b += P(ellipse(rx_, y, r, r*0.90), INK)
    b += P(f"M {lx+r*0.86:.1f},{y-r*0.10:.1f} L {rx_-r*0.86:.1f},{y-r*0.10:.1f}", INK)
    b += P(ellipse(lx-r*0.32, y-r*0.26, r*0.22, r*0.14), WHITE)
    b += P(ellipse(rx_-r*0.32, y-r*0.26, r*0.22, r*0.14), WHITE)
    return b

def acc_heart(a):
    """Corazon flotando al lado de la cabeza (como el de la referencia)."""
    return heart(a["cx"] + a["head_rx"]*0.92, a["chin_y"] - a["head_rx"]*0.30,
                 a["head_rx"]*0.15, HEART, INK, 16)

def decor_bubbles(a):
    """La firma de Cerlita. Se dibuja DETRAS de la base: asoma por encima."""
    rx = a["head_rx"]*0.46; ry = a["head_rx"]*0.36
    cx1, cy1 = a["cx"] - a["head_rx"]*0.55, a["head_top"] + ry*0.30
    cx2, cy2 = a["cx"] + a["head_rx"]*0.60, a["head_top"] + ry*0.02
    b = P(ellipse(cx2, cy2, rx*0.62, ry*0.66), VIOLET)
    b += heart(cx2, cy2, rx*0.20, WHITE)
    b += P(ellipse(cx1, cy1, rx, ry), WHITE, INK, 16)
    b += P(f"M {cx1-rx*0.50:.1f},{cy1+ry*0.70:.1f} L {cx1-rx*0.74:.1f},{cy1+ry*1.62:.1f} L {cx1-rx*0.12:.1f},{cy1+ry*0.86:.1f} Z", WHITE, INK, 16)
    for dx, dy in ((-0.42, 0.10), (0.0, -0.16), (0.42, 0.06)):
        b += heart(cx1 + rx*dx, cy1 + ry*dy, rx*0.155, HEART)
    return b

LAYERS = {
    "bow": (acc_bow, "head"), "flower": (acc_flower, "head"), "party": (acc_party, "head"),
    "glasses": (acc_glasses, "eyes"), "heart": (acc_heart, "chest"), "bubbles": (decor_bubbles, "decor"),
    "tee_pink": (acc_clothes_pink, "clothes"), "tee_violet": (acc_clothes_violet, "clothes"),
    "tee_teal": (acc_clothes_teal, "clothes"), "dots": (acc_clothes_dots, "clothes"),
    "stripes": (acc_clothes_stripes, "clothes"),
}

def to_vectordrawable(body_svg):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="96dp" android:height="96dp"',
           f'    android:viewportWidth="{W}" android:viewportHeight="{H}">']
    for m in re.finditer(r'<path\b([^>]*)/>', body_svg):
        at = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(1)))
        s = f'    <path\n        android:pathData="{at.get("d","")}"\n        android:fillColor="{at.get("fill","#000000")}"'
        if at.get("stroke") and at["stroke"] != "none":
            s += f'\n        android:strokeColor="{at["stroke"]}"\n        android:strokeWidth="{at.get("stroke-width","0")}"'
        s += '\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round" />'
        out.append(s)
    out.append("</vector>")
    return "\n".join(out) + "\n"

def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--anchor", required=True, choices=list(ANCHORS))
    ap.add_argument("--prefix", required=True)
    ap.add_argument("--out", default="out")
    ap.add_argument("--compose", default=None,
                    help="PNG de la base para componer QA (capa sobre la base)")
    ap.add_argument("--compose-out", default=None)
    args = ap.parse_args()
    os.makedirs(f"{args.out}/layers", exist_ok=True)
    os.makedirs(f"{args.out}/drawable", exist_ok=True)
    a = dict(ANCHORS[args.anchor])
    for lid, (fn, slot) in LAYERS.items():
        body = fn(a)
        s = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">\n' + body + "</svg>\n"
        open(f"{args.out}/layers/{args.prefix}_{lid}.svg", "w").write(s)
        open(f"{args.out}/drawable/{args.prefix}_{lid}.xml", "w").write(to_vectordrawable(body))
        print(f"  {args.prefix}_{lid:<10} slot={slot}")
        if args.compose:
            import cairosvg
            from PIL import Image
            layer = cairosvg.svg2png(bytestring=s.encode(), output_width=W, output_height=H)
            base = Image.open(args.compose).convert("RGBA")
            lay = Image.open(__import__("io").BytesIO(layer))
            composed = base.copy()
            if slot == "decor":  # decor DETRAS
                composed = Image.alpha_composite(lay, composed)
            else:                # ropa/accesorios ENCIMA (D92: la ropa ya tambien)
                composed = Image.alpha_composite(composed, lay)
            composed.save(f"{args.compose_out or args.out}/compose_{args.prefix}_{lid}.png")

if __name__ == "__main__":
    main()
