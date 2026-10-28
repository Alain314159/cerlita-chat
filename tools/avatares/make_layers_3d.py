#!/usr/bin/env python3
"""
Cerlita Chat — generador de capas v4 (D95): estilo 3D integrado.

Las bases son renders 3D suaves (vinilo, sin contorno, luz arriba-izquierda).
Las capas v2/v3 eran vectores planos con trazo de tinta -> choque de estilos.
Esta version genera RASTER (WebP) con:
  - SIN contorno de tinta
  - degradado de luz (arriba-izquierda mas claro)
  - oscurecimiento de borde (rim) sutil
  - especular suave (brillo vinilo)
  - SOMBRA DE CONTACTO difuminada debajo de cada pieza (ancla la pieza al cuerpo)
  - ropa que ABRAZA la silueta medida del cuerpo (silhouette.json), no cajas

Uso:
  python3 make_layers_3d.py            # genera todo + QA
  python3 make_layers_3d.py --only bow,shoes_red   # solo esos items
"""
import os, sys, json, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT3D = os.path.join(HERE, "work3d", "out3d")
BASES = os.path.join(HERE, "work3d", "out")
MED = os.path.join(HERE, "work3d", "medidas")

W = 1024          # espacio de anclas
SS = 2            # supersample: dibujar a 2048
CW = W * SS       # canvas de trabajo

SHADOW = (62, 40, 55)      # sombra calida (nunca negro)
PAL = dict(
    tee_pink=(242, 166, 185), tee_violet=(193, 166, 221), tee_teal=(146, 207, 200),
    dress=(232, 117, 140), suit=(76, 90, 112), shirt=(245, 241, 234),
    bowtie=(238, 131, 103), suit_btn=(52, 62, 78),
    shoes_red=(217, 90, 99), shoes_blue=(94, 130, 192), sole=(122, 74, 84),
    sole_blue=(70, 94, 138), toe=(244, 234, 228),
    scarf=(232, 169, 79), cap=(238, 131, 103), cap_brim=(214, 108, 84),
    party=(185, 160, 220), flower=(242, 160, 180), flower_c=(245, 215, 114),
    bow=(240, 117, 141), heart=(240, 117, 141),
    glass_rim=(92, 74, 73), glass_lens=(58, 54, 64),
    gold=(231, 185, 78), white=(252, 248, 244),
)

# ── geometria por familia (medida + VLM, ver measure_*.py) ──
FAM = {
    "pig": dict(
        cx=511, chin=474, hat_y=160,
        eye_l=(407, 292), eye_r=(614, 292), eye_r_deg=0.0,
        bow=(243, 233), earring_l=(259, 306), earring_r=(765, 306),
        flower=(772, 238), flower_r=44,
        shirt_top=506, sleeve_end=660, hem=700,
        torso_only={660: (325, 700), 700: (312, 712)},       # brazos fuera
        suit_hem=None, suit_sleeve=None,                       # (traje solo koala)
        scarf_y=508, scarf_half=205, scarf_h=58, scarf_tail=(620, 560),
        cap_c=(512, 162), cap_hw=148, cap_h=92,
        party_c=(512, 158), party_hw=74, party_h=170,
        heart_c=(798, 380), heart_r=34,
        feet=((425, 908, 50), (599, 908, 48)),                 # cx, cy, half_w
        shoe_h=64, shoe_style="bootie",
        glasses_r=(60, 54), glasses_bridge=26,
        bow_size=66, bow_deg=18,
    ),
    "koala": dict(
        cx=510, chin=550, hat_y=205,
        eye_l=(403, 361), eye_r=(618, 335), eye_r_deg=-6.9,
        bow=(372, 252), earring_l=(330, 362), earring_r=(694, 362),
        flower=(648, 250), flower_r=48,
        shirt_top=586, sleeve_end=700, hem=738,
        torso_only={700: (350, 672), 738: (354, 668)},
        suit_hem=782, suit_sleeve=772,
        scarf_y=590, scarf_half=198, scarf_h=54, scarf_tail=(618, 640),
        cap_c=(512, 208), cap_hw=168, cap_h=100,
        party_c=(512, 202), party_hw=84, party_h=192,
        heart_c=(802, 424), heart_r=32,
        feet=((427, 902, 54), (595, 902, 54)),
        shoe_h=70, shoe_style="sneaker",
        glasses_r=(66, 58), glasses_bridge=20,
        bow_size=76, bow_deg=32,
    ),
}

SIL = json.load(open(os.path.join(MED, "silhouette.json")))

# ══════════════════ motor de sombreado suave ══════════════════

def new_mask():
    return Image.new("L", (CW, CW), 0)

def mdraw():
    """drawer que dibuja en coordenadas 1024 (escala SS automatica)."""
    m = new_mask()
    d = ImageDraw.Draw(m)

    class D:
        @staticmethod
        def poly(pts, fill=255):
            d.polygon([(x * SS, y * SS) for x, y in pts], fill=fill)
        @staticmethod
        def ellipse(box, fill=255):
            d.ellipse([v * SS for v in box], fill=fill)
        @staticmethod
        def pieslice(box, a0, a1, fill=255):
            d.pieslice([v * SS for v in box], a0, a1, fill=fill)
        @staticmethod
        def line(pts, width, fill=255):
            d.line([(x * SS, y * SS) for x, y in pts], fill=fill, width=width * SS, joint="curve")
        @staticmethod
        def arc(box, a0, a1, width, fill=255):
            d.arc([v * SS for v in box], a0, a1, fill=fill, width=width * SS)
        @staticmethod
        def rrect(box, radius, fill=255):
            d.rounded_rectangle([v * SS for v in box], radius=radius * SS, fill=fill)
        @staticmethod
        def img():
            return m
    return D

def soft_shape(mask, color, dark=0.22, rim=0.10, spec=0.20, light=(0.38, 0.62)):
    """Convierte una mascara L en pieza 'vinilo': degradado de luz + rim + especular.
    Devuelve RGBA (CW) fuera del bbox para no procesar 2048^2 por pieza."""
    bbox = mask.getbbox()
    if not bbox:
        return Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
    x0, y0, x1, y1 = [v + m for v, m in zip(bbox, (-24, -24, 24, 24))]
    x0, y0 = max(0, x0), max(0, y0); x1, y1 = min(CW, x1), min(CW, y1)
    bw, bh = x1 - x0, y1 - y0
    m = np.asarray(mask.crop((x0, y0, x1, y1))).astype(np.float32) / 255.0
    yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
    t = xx / bw * light[0] + yy / bh * light[1]          # 0 arriba-izq → 1 abajo-der
    f = (1.06 - dark * t)[..., None]                     # luz suave
    # rim: banda de borde oscurecida
    er = np.asarray(mask.crop((x0, y0, x1, y1)).filter(ImageFilter.MinFilter(9))).astype(np.float32) / 255.0
    edge = np.clip(m - er, 0, 1)
    f = f * (1 - rim * edge[..., None])
    col = np.clip(np.array(color, np.float32) * f, 0, 255)
    # especular: blob blanco difuso en el cuarto superior-izquierdo del bbox
    if spec > 0:
        sx, sy = x0 + bw * 0.30, y0 + bh * 0.16
        simg = Image.new("L", (bw, bh), 0)
        ImageDraw.Draw(simg).ellipse([sx - x0 - bw*0.22, sy - y0 - bh*0.10,
                                      sx - x0 + bw*0.30, sy - y0 + bh*0.34], 255)
        simg = simg.filter(ImageFilter.GaussianBlur(bw * 0.10))
        s = np.asarray(simg).astype(np.float32) / 255.0 * spec * er  # solo interior
        col = np.clip(col + s[..., None] * 255.0, 0, 255)
    out = np.dstack([col.astype(np.uint8), (m * 255).astype(np.uint8)])
    rgba = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
    rgba.paste(Image.fromarray(out), (x0, y0))
    return rgba

def contact_shadow(mask, dx=7, dy=13, blur=16, alpha=0.34, size=1.0):
    """Sombra de contacto difuminada, desplazada abajo-derecha (luz arriba-izq)."""
    sh = mask.filter(ImageFilter.GaussianBlur(blur * SS))
    if size != 1.0:
        w, h = sh.size
        sh = sh.resize((int(w * size), int(h * size))).crop((0, 0, w, h))
    canvas = new_mask()
    canvas.paste(sh, (dx * SS, dy * SS))
    a = np.asarray(canvas).astype(np.float32) / 255.0
    a = np.clip(a * alpha * 255, 0, 255).astype(np.uint8)
    out = np.dstack([np.full_like(a, SHADOW[0]), np.full_like(a, SHADOW[1]),
                     np.full_like(a, SHADOW[2]), a])
    return Image.fromarray(out, "RGBA")

def paste(dst, src):
    return Image.alpha_composite(dst, src)

def flat_layer(mask, color, dark=0.20, rim=0.10, spec=0.18, shadow=True, **kw):
    """atajo: sombra de contacto + pieza suave."""
    out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
    if shadow:
        out = paste(out, contact_shadow(mask, **{k: v for k, v in kw.items() if k in ("dx", "dy", "blur", "alpha")}))
    return paste(out, soft_shape(mask, color, dark, rim, spec))

# ── helpers de forma ──

def heart_path(cx, cy, r):
    return [(cx, cy + r), (cx - r * 1.05, cy + r * 0.25), (cx - r * 0.95, cy - r * 0.75),
            (cx - r * 0.40, cy - r * 0.72), (cx, cy - r * 0.18),
            (cx + r * 0.40, cy - r * 0.72), (cx + r * 0.95, cy - r * 0.75),
            (cx + r * 1.05, cy + r * 0.25)]

def bezier(p0, p1, p2, p3, n=24):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3*p0[0] + 3*mt**2*t*p1[0] + 3*mt*t**2*p2[0] + t**3*p3[0]
        y = mt**3*p0[1] + 3*mt**2*t*p1[1] + 3*mt*t**2*p2[1] + t**3*p3[1]
        pts.append((x, y))
    return pts

def sil_rows(fam_key):
    return SIL["cerdita" if fam_key == "pig" else "koalita"]["silhouette"]

def body_profile(fam, y):
    """(x0,x1) del cuerpo CON brazos en la fila y (interpolado)."""
    rows = sil_rows(fam)
    if y <= rows[0][0]:
        return rows[0][1], rows[0][2]
    if y >= rows[-1][0]:
        return rows[-1][1], rows[-1][2]
    for (ya, xa0, xa1), (yb, xb0, xb1) in zip(rows, rows[1:]):
        if ya <= y <= yb:
            t = (y - ya) / (yb - ya)
            return xa0 + (xb0 - xa0) * t, xa1 + (xb1 - xa1) * t
    return rows[-1][1], rows[-1][2]

def smooth_profile(fam, y0, y1, step=8, ease=4.0, clamp_top=None):
    """contorno suavizado [(y, x0, x1)] del cuerpo entre y0..y1 (+ease)."""
    pts = []
    y = y0
    while y <= y1:
        a, b = body_profile(fam, y)
        pts.append((y, a - ease, b + ease))
        y += step
    # suavizar con media movil de 3
    sm = []
    for i, p in enumerate(pts):
        lo = pts[max(0, i - 1)]; hi = pts[min(len(pts) - 1, i + 1)]
        sm.append((p[0], (lo[1] + p[1] * 2 + hi[1]) / 4, (lo[2] + p[2] * 2 + hi[2]) / 4))
    return sm

# ══════════════════ PRENDAS ══════════════════

def build_shirt(fam, color, pattern=None, hem=None, sleeve=None, neck_rx=68, neck_ry=22,
                arm_tubes=None):
    """Camiseta que ABRAZA la silueta. arm_tubes=[(cx0,y0,y1,w0,w1),...] anade
    mangas largas independientes (traje: brazos separados del torso)."""
    a = FAM[fam]
    hem = hem or a["hem"]; sleeve = sleeve or a["sleeve_end"]
    d = mdraw()
    # 1) torso con brazos hasta 'sleeve', luego solo torso hasta 'hem'
    prof = smooth_profile(fam, a["shirt_top"], sleeve, ease=5.0)
    tor = a["torso_only"]
    ys = sorted(tor)
    def torso_at(y):
        if y <= ys[0]: return tor[ys[0]]
        if y >= ys[-1]: return tor[ys[-1]]
        for ya, yb in zip(ys, ys[1:]):
            if ya <= y <= yb:
                t = (y - ya) / (yb - ya)
                return (tor[ya][0] + (tor[yb][0] - tor[ya][0]) * t,
                        tor[ya][1] + (tor[yb][1] - tor[ya][1]) * t)
    y = sleeve + 8
    while y <= hem:
        lo, hi = torso_at(y)
        prof.append((y, lo - 3, hi + 3))
        y += 8
    # contorno: hombros redondeados arriba (bezier) + lados + bajo curvo
    top_y = a["shirt_top"]
    left = [(p[1], p[0]) for p in prof]           # (x, y) bajando
    right = [(p[2], p[0]) for p in prof]
    x0t = prof[0][1]; x1t = prof[0][2]
    outline = []
    outline += bezier((x0t, top_y + 26), (x0t + 6, top_y - 14), (x1t - 80, top_y - 16), (a["cx"] - neck_rx + 6, top_y + 2))
    outline += bezier((a["cx"] - neck_rx + 6, top_y + 2), (a["cx"] - 30, top_y + neck_ry), (a["cx"] + 30, top_y + neck_ry), (a["cx"] + neck_rx - 6, top_y + 2))
    outline += bezier((a["cx"] + neck_rx - 6, top_y + 2), (x1t + 80, top_y - 16), (x1t - 6, top_y - 14), (x1t, top_y + 26))
    outline += right
    # bajo curvo (panza): de derecha a izquierda
    (hx_r, _) = right[-1]; (hx_l, _) = left[-1]
    outline += bezier((hx_r, hem), (a["cx"] + 60, hem + 28), (a["cx"] - 60, hem + 28), (hx_l, hem))
    outline += left[::-1]
    d.poly(outline)
    if arm_tubes:
        for (tcx, ty0, ty1, tw0, tw1) in arm_tubes:
            steps = bezier((tcx - tw0, ty0), (tcx - tw0 * 0.9, (ty0 + ty1) / 2),
                           (tcx - tw1, (ty0 + ty1) / 2), (tcx - tw1, ty1))
            steps += bezier((tcx + tw1, ty1), (tcx + tw1, (ty0 + ty1) / 2),
                            (tcx + tw0 * 0.9, (ty0 + ty1) / 2), (tcx + tw0, ty0))
            d.poly(steps)
    body = d.img()
    # 2) mangas: banda de puño en el costado a la altura sleeve
    sleeve_band = new_mask()
    ds = ImageDraw.Draw(sleeve_band)
    lo_x, hi_x = body_profile(fam, sleeve - 12)
    cx = a["cx"]
    for sgn, ex in ((-1, lo_x), (1, hi_x)):
        cxe = cx + sgn * (abs(ex - cx) - 26)
        ds.rounded_rectangle([(cxe - 30) * SS, (sleeve - 22) * SS, (cxe + 30) * SS, (sleeve + 20) * SS],
                             radius=20 * SS, fill=255)
    out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
    out = paste(out, contact_shadow(body, dy=16, blur=18, alpha=0.30))
    out = paste(out, soft_shape(body, color, dark=0.20, rim=0.10, spec=0.16))
    # sombra propia bajo el borde (la prenda proyecta sobre el cuerpo)
    hem_band = new_mask()
    dh = ImageDraw.Draw(hem_band)
    dh.rounded_rectangle([(a["cx"] - 300) * SS, hem * SS, (a["cx"] + 300) * SS, (hem + 22) * SS], radius=12 * SS, fill=255)
    out = paste(out, Image.composite(
        Image.new("RGBA", (CW, CW), (*SHADOW, 0)),
        Image.new("RGBA", (CW, CW), (0, 0, 0, 0)),
        hem_band).filter(ImageFilter.GaussianBlur(10 * SS)))
    return out, body, sleeve_band

def item_tee(color, pattern=None):
    def fn(fam):
        out, body, _ = build_shirt(fam, color, pattern)
        a = FAM[fam]
        if pattern == "dots":
            dd = mdraw()
            for i in (-2, -1, 0, 1, 2):
                for j in range(3):
                    x = a["cx"] + i * 74
                    y = a["shirt_top"] + 60 + j * 56
                    dd.ellipse([x - 9, y - 9, x + 9, y + 9], 255)
            dots = Image.composite(dd.img(), new_mask(), body)   # solo sobre la tela
            out = paste(out, Image.composite(
                Image.new("RGBA", (CW, CW), (*PAL["white"], 255)),
                Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), dots))
        elif pattern == "stripes":
            dd = mdraw()
            for j in range(4):
                y = a["shirt_top"] + 48 + j * 58
                dd.poly([(0, y), (CW / SS, y), (CW / SS, y + 26), (0, y + 26)], 255)
            strip = dd.img()
            strip = Image.composite(strip, new_mask(), body)
            out = paste(out, Image.composite(
                Image.new("RGBA", (CW, CW), (*PAL["white"], 235)),
                Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), strip))
        return out
    return fn

def item_dress():
    """Vestido de la cerdita (D95): ajustado al pecho, cae CUBRIENDO la panza
    (la panza es lo mas ancho: half 292) y abre en A hasta half 333, con bajo
    ondulado (scallops), mangas globo, cinturon alto y cuello white peter-pan."""
    def fn(fam):
        a = FAM[fam]
        d = mdraw()
        cx = a["cx"]
        y0 = a["shirt_top"] - 4          # 502: escote
        y_fit = 700                       # fin de la parte ajustada
        yh = 880                          # bajo del vestido
        lo56, hi56 = body_profile(fam, 560)
        lo70, hi70 = body_profile(fam, y_fit)
        ease = 8
        half70 = (hi70 - lo70) / 2
        hem_half = half70 + 48            # vuelo real por DEBAJO de la panza
        def scallop(x_from, x_to, y, dip=28, n=5):
            pts, w = [], (x_to - x_from) / n
            for i in range(n):
                x0 = x_from + i * w; x1 = x0 + w
                pts += bezier((x0, y), (x0 + w * 0.30, y + dip), (x1 - w * 0.30, y + dip), (x1, y))
            return pts
        # contorno (sentido horario desde hombro izq):
        top = bezier((lo56 + 44, y0 + 30), (lo56 + 12, y0 - 8), (cx - 60, y0 - 10), (cx - 46, y0 + 8))
        top += bezier((cx - 46, y0 + 8), (cx - 18, y0 + 26), (cx + 18, y0 + 26), (cx + 46, y0 + 8))
        top += bezier((cx + 46, y0 + 8), (cx + 60, y0 - 10), (hi56 - 12, y0 - 8), (hi56 - 44, y0 + 30))
        fit_r = bezier((hi56 - 44, y0 + 30), (hi56 + ease + 4, y0 + 90),
                       (hi70 + ease, (y0 + y_fit) / 2), (hi70 + ease, y_fit))
        flare_r = bezier((hi70 + ease, y_fit), (cx + hem_half + 12, 790),
                         (cx + hem_half + 6, yh - 12), (cx + hem_half, yh))
        hem = scallop(cx + hem_half, cx - hem_half, yh)
        flare_l = bezier((cx - hem_half, yh), (cx - hem_half - 6, yh - 12),
                         (cx - hem_half - 12, 790), (lo70 - ease, y_fit))
        fit_l = bezier((lo70 - ease, y_fit), (lo70 - ease, (y0 + y_fit) / 2),
                       (lo56 - ease - 4, y0 + 90), (lo56 + 44, y0 + 30))
        d.poly(top + fit_r + flare_r + hem + flare_l + fit_l)
        body = d.img()
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        out = paste(out, contact_shadow(body, dy=16, blur=18, alpha=0.30))
        out = paste(out, soft_shape(body, PAL["dress"], dark=0.20, rim=0.07, spec=0.15))
        # pliegues verticales suaves de la falda
        df = mdraw()
        for k in (-0.55, 0.0, 0.55):
            df.line([(cx + k * hem_half * 0.8, y_fit + 40), (cx + k * hem_half, yh - 14)], width=9)
        folds = df.img().filter(ImageFilter.GaussianBlur(7 * SS))
        out = paste(out, Image.composite(
            Image.new("RGBA", (CW, CW), (150, 60, 76, 60)),
            Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), folds))
        # mangas globo
        dg = mdraw()
        for sgn in (-1, 1):
            gx = cx + sgn * (abs((lo56 if sgn < 0 else hi56) - cx) - 14)
            dg.ellipse([gx - 52, y0 + 16, gx + 52, y0 + 108], 255)
        puff = dg.img()
        out = paste(out, contact_shadow(puff, dy=8, blur=12, alpha=0.26))
        out = paste(out, soft_shape(puff, PAL["dress"], dark=0.18, rim=0.08, spec=0.22))
        # cinturon alto (bajo el pecho)
        lo_b, hi_b = body_profile(fam, 622)
        db = mdraw()
        db.rrect([lo_b - 4, 618, hi_b + 4, 666], radius=20)
        belt = db.img()
        out = paste(out, soft_shape(belt, (196, 88, 108), dark=0.22, rim=0.10, spec=0.10))
        # lazo del cinturon
        dl = mdraw()
        dl.ellipse([cx - 10, 626, cx + 10, 658], 255)
        out = paste(out, soft_shape(dl.img(), (176, 72, 92), dark=0.20, rim=0.10, spec=0.16))
        # cuello blanco redondo (peter-pan)
        dc = mdraw()
        for sgn in (-1, 1):
            dc.ellipse([cx + sgn * 46 - 30, y0 - 2, cx + sgn * 46 + 30, y0 + 40], 255)
        collar = dc.img()
        out = paste(out, soft_shape(collar, PAL["shirt"], dark=0.12, rim=0.06, spec=0.20))
        return out
    return fn

def item_suit():
    """Traje del koala (D95): chaqueta en columna (torso medido, brazos FUERA),
    mangas que siguen los lobulos reales de los brazos con puño, camisa en V,
    solapas curvadas y pajarita con volumen. Todo en el mismo lenguaje vinilo."""
    def fn(fam):
        a = FAM[fam]
        cx = a["cx"]
        y0 = a["shirt_top"] + 4            # 590: hombros bajo la barbilla
        hem = a["suit_hem"]                # 782
        # ── cuerpo de la chaqueta: SOLO torso (los brazos van en mangas) ──
        # perfil torso koala medido: ~350-672 constante con ligera curva
        d = mdraw()
        sh_l, sh_r = 356, 666              # hombros (ancho del cuello koala)
        wt_l, wt_r = 348, 674              # cintura
        hm_l, hm_r = 352, 670              # bajo
        outline = []
        outline += bezier((sh_l, y0 + 30), (sh_l + 4, y0 - 10), (cx - 90, y0 - 12), (cx - 62, y0 + 4))
        outline += bezier((cx - 62, y0 + 4), (cx - 24, y0 + 26), (cx + 24, y0 + 26), (cx + 62, y0 + 4))
        outline += bezier((cx + 62, y0 + 4), (cx + 90, y0 - 12), (sh_r - 4, y0 - 10), (sh_r, y0 + 30))
        outline += bezier((sh_r, y0 + 30), (sh_r + 10, y0 + 120), (wt_r, (y0 + hem) // 2 - 40), (wt_r, (y0 + hem) // 2))
        outline += bezier((wt_r, (y0 + hem) // 2), (wt_r + 4, hem - 30), (hm_r + 6, hem - 8), (hm_r, hem))
        outline += bezier((hm_r, hem), (cx + 60, hem + 22), (cx - 60, hem + 22), (hm_l, hem))
        outline += bezier((hm_l, hem), (hm_l - 6, hem - 8), (wt_l - 4, hem - 30), (wt_l, (y0 + hem) // 2))
        outline += bezier((wt_l, (y0 + hem) // 2), (wt_l, (y0 + hem) // 2 - 40), (sh_l - 10, y0 + 120), (sh_l, y0 + 30))
        d.poly(outline)
        body = d.img()
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        out = paste(out, contact_shadow(body, dy=14, blur=16, alpha=0.30))
        out = paste(out, soft_shape(body, PAL["suit"], dark=0.22, rim=0.10, spec=0.14))

        # ── mangas: siguen los lobulos de brazo medidos (720: c=305/714, 760: c=299/719) ──
        for sgn, ax_top, ax_bot in ((-1, 305, 300), (1, 714, 720)):
            ytop, ybot = y0 + 34, hem - 12
            w_top, w_mid, w_bot = 40, 36, 28
            ymid = (ytop + ybot) / 2
            dm = mdraw()
            side_o = bezier((ax_top + sgn * w_top, ytop), (ax_top + sgn * w_mid, ymid),
                            (ax_bot + sgn * w_bot, ybot - 24), (ax_bot + sgn * w_bot, ybot))
            side_i = bezier((ax_bot - sgn * w_bot, ybot), (ax_bot - sgn * w_bot, ybot - 24),
                            (ax_top - sgn * (w_mid - 6), ymid), (ax_top - sgn * (w_top - 10), ytop))
            dm.poly(side_o + side_i)
            sleeve = dm.img()
            out = paste(out, contact_shadow(sleeve, dy=10, blur=12, alpha=0.30))
            out = paste(out, soft_shape(sleeve, PAL["suit"], dark=0.22, rim=0.10, spec=0.14))
            # puño claro
            dcf = mdraw()
            dcf.rrect([ax_bot - w_bot - 2, ybot - 22, ax_bot + w_bot + 2, ybot + 2], radius=12)
            out = paste(out, soft_shape(dcf.img(), (88, 102, 124), dark=0.20, rim=0.10, spec=0.18))
            # costura sombra entre manga y cuerpo
            dseam = mdraw()
            sx = ax_top - sgn * (w_top - 8)
            dseam.line([(sx, ytop + 26), (sx - sgn * 6, (ytop + ybot) / 2), (ax_bot - sgn * (w_bot + 4), ybot - 16)], width=10)
            seam = dseam.img().filter(ImageFilter.GaussianBlur(6 * SS))
            out = paste(out, Image.composite(
                Image.new("RGBA", (CW, CW), (*SHADOW, 80)),
                Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), seam))

        # ── camisa blanca en V ──
        v_bottom = y0 + 116
        dsh = mdraw()
        dsh.poly([(cx - 66, y0 + 6), (cx, v_bottom), (cx + 66, y0 + 6), (cx, y0 + 30)])
        out = paste(out, soft_shape(dsh.img(), PAL["shirt"], dark=0.10, rim=0.05, spec=0.22))

        # ── solapas: forma alargada curvada, tela mas oscura + filo iluminado ──
        for sgn in (-1, 1):
            dlp = mdraw()
            p = bezier((cx + sgn * 64, y0 + 2), (cx + sgn * 92, y0 + 46),
                       (cx + sgn * 84, y0 + 100), (cx + sgn * 34, v_bottom + 4))
            p += bezier((cx + sgn * 34, v_bottom + 4), (cx + sgn * 52, y0 + 96),
                        (cx + sgn * 58, y0 + 44), (cx + sgn * 38, y0 + 6))
            dlp.poly(p)
            lapel = dlp.img()
            out = paste(out, soft_shape(lapel, (62, 74, 93), dark=0.24, rim=0.10, spec=0.10))
            # filo interior iluminado (bicel de la solapa)
            dle = mdraw()
            dle.line(bezier((cx + sgn * 36, y0 + 8), (cx + sgn * 54, y0 + 48),
                            (cx + sgn * 50, y0 + 94), (cx + sgn * 34, v_bottom)), width=6)
            out = paste(out, Image.composite(
                Image.new("RGBA", (CW, CW), (255, 255, 255, 60)),
                Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), dle.img()))

        # ── pajarita coral con volumen (dos lazos + nudo) ──
        ty = y0 + 18
        dtk = mdraw()
        for sgn in (-1, 1):
            pts = bezier((cx + sgn * 12, ty - 2), (cx + sgn * 56, ty - 30),
                         (cx + sgn * 64, ty + 22), (cx + sgn * 16, ty + 18))
            pts += bezier((cx + sgn * 16, ty + 18), (cx + sgn * 40, ty + 30),
                          (cx + sgn * 50, ty + 6), (cx + sgn * 12, ty - 2))
            dtk.poly(pts)
        loops = dtk.img()
        out = paste(out, contact_shadow(loops, dy=7, blur=8, alpha=0.28))
        out = paste(out, soft_shape(loops, PAL["bowtie"], dark=0.22, rim=0.10, spec=0.20))
        dknot = mdraw()
        dknot.ellipse([cx - 13, ty - 12, cx + 13, ty + 14], 255)
        knot = dknot.img()
        out = paste(out, soft_shape(knot, (222, 112, 88), dark=0.18, rim=0.10, spec=0.30))

        # ── botones ──
        for yy in (v_bottom + 26, v_bottom + 64):
            ddb = mdraw(); ddb.ellipse([cx - 7, yy - 7, cx + 7, yy + 7], 255)
            out = paste(out, soft_shape(ddb.img(), PAL["suit_btn"], dark=0.15, rim=0.10, spec=0.35))
        return out
    return fn

def item_shoes(base, sole, style="auto"):
    def fn(fam):
        a = FAM[fam]
        style = a["shoe_style"]
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        for (fx, fy, hw) in a["feet"]:
            d = mdraw()
            h = a["shoe_h"]
            if style == "bootie":       # cerdita: botita redonda bulbosa
                d.ellipse([fx - hw - 6, fy - h * 0.55, fx + hw + 6, fy + h * 0.62], 255)
            else:                        # koala: sneaker puntera ancha
                d.rrect([fx - hw - 8, fy - h * 0.62, fx + hw + 8, fy + h * 0.55], radius=hw + 6)
            body = d.img()
            out = paste(out, contact_shadow(body, dy=10, blur=12, alpha=0.38))
            out = paste(out, soft_shape(body, base, dark=0.24, rim=0.12, spec=0.20))
            # suela
            ds = mdraw()
            ds.rrect([(fx - hw - 8), (fy + h * 0.22), (fx + hw + 8), (fy + h * 0.58)], radius=10)
            out = paste(out, soft_shape(ds.img(), sole, dark=0.26, rim=0.10, spec=0.08))
            # puntera clara
            dt = mdraw()
            dt.ellipse([fx - hw - 4, fy + h * 0.02, fx + hw * 0.5, fy + h * 0.42], 255)
            out = paste(out, soft_shape(dt.img(), PAL["toe"], dark=0.14, rim=0.08, spec=0.26))
            if style == "sneaker":       # cordones
                dl = mdraw()
                for k in (-1, 0, 1):
                    dl.line([(fx - 20 + k * 6, fy - h * 0.34 + k * 7), (fx + 20 + k * 6, fy - h * 0.46 + k * 7)], width=7)
                out = paste(out, soft_shape(dl.img(), (252, 248, 244), dark=0.10, rim=0.0, spec=0.0))
        return out
    return fn

# ══════════════════ ACCESORIOS ══════════════════

def item_bow():
    def fn(fam):
        a = FAM[fam]
        x, y = a["bow"]; s = a["bow_size"]; deg = a["bow_deg"]
        th = math.radians(deg)
        def rot(px, py):
            dx, dy = px - x, py - y
            return (x + dx * math.cos(th) - dy * math.sin(th), y + dx * math.sin(th) + dy * math.cos(th))
        d = mdraw()
        # dos lazos
        for sgn in (-1, 1):
            pts = bezier((x + sgn * 8, y), (x + sgn * (s * 0.55), y - s * 0.52),
                         (x + sgn * s, y - s * 0.18), (x + sgn * s, y + s * 0.28))
            pts += bezier((x + sgn * s, y + s * 0.28), (x + sgn * (s * 0.9), y + s * 0.55),
                          (x + sgn * (s * 0.4), y + s * 0.44), (x + sgn * 8, y + 4))
            d.poly([rot(px, py) for px, py in pts])
        d.ellipse([x - s * 0.20, y - s * 0.22, x + s * 0.20, y + s * 0.22], 255)
        body = d.img()
        out = contact_shadow(body, dx=6, dy=10, blur=10, alpha=0.32)
        return paste(out, soft_shape(body, PAL["bow"], dark=0.22, rim=0.12, spec=0.24))
    return fn

def item_flower():
    def fn(fam):
        a = FAM[fam]
        x, y = a["flower"]; r = a["flower_r"]
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        d = mdraw()
        for i in range(5):
            ang = math.radians(-90 + i * 72)
            px, py = x + r * 0.62 * math.cos(ang), y + r * 0.62 * math.sin(ang)
            d.ellipse([px - r * 0.46, py - r * 0.46, px + r * 0.46, py + r * 0.46], 255)
        petals = d.img()
        out = paste(out, contact_shadow(petals, dx=5, dy=9, blur=9, alpha=0.30))
        out = paste(out, soft_shape(petals, PAL["flower"], dark=0.20, rim=0.10, spec=0.22))
        dc = mdraw(); dc.ellipse([x - r * 0.30, y - r * 0.30, x + r * 0.30, y + r * 0.30], 255)
        out = paste(out, soft_shape(dc.img(), PAL["flower_c"], dark=0.16, rim=0.10, spec=0.35))
        return out
    return fn

def item_party():
    def fn(fam):
        a = FAM[fam]
        x, y = a["party_c"]; hw = a["party_hw"]; h = a["party_h"]
        d = mdraw()
        tipx = x - hw * 0.35; tipy = y - h
        d.poly([(x - hw, y + h * 0.10), (tipx, tipy), (x + hw * 0.9, y - h * 0.30)])
        cone = d.img()
        out = contact_shadow(cone, dy=10, blur=12, alpha=0.30)
        out = paste(out, soft_shape(cone, PAL["party"], dark=0.24, rim=0.12, spec=0.18))
        db = mdraw()
        db.ellipse([x - hw * 1.06, y + h * 0.02, x + hw * 0.96, y + h * 0.24], 255)
        out = paste(out, soft_shape(db.img(), (206, 184, 232), dark=0.18, rim=0.08, spec=0.16))
        dp = mdraw(); dp.ellipse([tipx - 22, tipy - 22, tipx + 22, tipy + 22], 255)
        out = paste(out, soft_shape(dp.img(), PAL["white"], dark=0.10, rim=0.08, spec=0.30))
        return out
    return fn

def item_cap():
    def fn(fam):
        a = FAM[fam]
        x, y = a["cap_c"]; hw = a["cap_hw"]; h = a["cap_h"]
        d = mdraw()
        d.pieslice([x - hw, y - h, x + hw, y + h * 0.9], 180, 360, 255)
        dome = d.img()
        out = contact_shadow(dome, dy=8, blur=12, alpha=0.30)
        out = paste(out, soft_shape(dome, PAL["cap"], dark=0.22, rim=0.12, spec=0.20))
        db = mdraw()
        db.ellipse([x - hw * 1.22, y - h * 0.16, x + hw * 1.22, y + h * 0.26], 255)
        brim = db.img()
        out = paste(out, contact_shadow(brim, dy=6, blur=10, alpha=0.28))
        out = paste(out, soft_shape(brim, PAL["cap_brim"], dark=0.22, rim=0.10, spec=0.20))
        dbt = mdraw(); dbt.ellipse([x - 12, y - h - 12, x + 12, y - h + 12], 255)
        out = paste(out, soft_shape(dbt.img(), PAL["cap_brim"], dark=0.15, rim=0.10, spec=0.30))
        return out
    return fn

def item_glasses():
    def fn(fam):
        a = FAM[fam]
        (lx, ly), (rx_, ry_) = a["eye_l"], a["eye_r"]
        rw, rh = a["glasses_r"]; br = a["glasses_bridge"]
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        # lentes: oscuro translucido con brillo
        dl = mdraw()
        dl.ellipse([lx - rw, ly - rh, lx + rw, ly + rh], 255)
        dl.ellipse([rx_ - rw, ry_ - rh, rx_ + rw, ry_ + rh], 255)
        dl.rrect([lx + rw * 0.7, min(ly, ry_) - 8, rx_ - rw * 0.7, max(ly, ry_) + 8], radius=10)
        lens = dl.img()
        lens_rgba = Image.new("RGBA", (CW, CW), (*PAL["glass_lens"], 150))
        lens_img = Image.composite(lens_rgba, Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), lens)
        # sombra de las gafas sobre la cara
        out = paste(out, contact_shadow(lens, dx=4, dy=7, blur=8, alpha=0.22))
        out = paste(out, lens_img)
        # brillo diagonal en cada lente
        dsh = mdraw()
        for (ex, ey) in ((lx, ly), (rx_, ry_)):
            dsh.line([(ex - rw * 0.5, ey + rh * 0.42), (ex - rw * 0.05, ey - rh * 0.5)], width=10)
            dsh.line([(ex - rw * 0.12, ey + rh * 0.5), (ex + rw * 0.35, ey - rh * 0.35)], width=5)
        shine = dsh.img()
        out = paste(out, Image.composite(
            Image.new("RGBA", (CW, CW), (255, 255, 255, 120)),
            Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), shine))
        # montura: anillo grueso
        dr = mdraw()
        for (ex, ey) in ((lx, ly), (rx_, ry_)):
            dr.ellipse([ex - rw - br, ey - rh - br, ex + rw + br, ey + rh + br], 255)
            dr.ellipse([ex - rw, ey - rh, ex + rw, ey + rh], 0)
        dr.rrect([lx + rw * 0.55, (ly + ry_) / 2 - 9, rx_ - rw * 0.55, (ly + ry_) / 2 + 9], radius=8)
        # patillas hacia las orejas
        dr.line([(lx - rw - br + 6, ly - 6), (lx - rw - 46, ly - 26)], width=11)
        dr.line([(rx_ + rw + br - 6, ry_ - 6), (rx_ + rw + 46, ry_ - 26)], width=11)
        rim = dr.img()
        out = paste(out, soft_shape(rim, PAL["glass_rim"], dark=0.26, rim=0.14, spec=0.16))
        return out
    return fn

def item_heart():
    def fn(fam):
        a = FAM[fam]
        x, y = a["heart_c"]; r = a["heart_r"]
        d = mdraw()
        d.poly(heart_path(x, y, r), 255)
        body = d.img()
        out = contact_shadow(body, dx=10, dy=14, blur=14, alpha=0.25)
        out = paste(out, soft_shape(body, PAL["heart"], dark=0.26, rim=0.12, spec=0.30))
        return out
    return fn

def item_earrings():
    def fn(fam):
        a = FAM[fam]
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        for (ex, ey) in (a["earring_l"], a["earring_r"]):
            d = mdraw()
            d.arc([ex - 22, ey - 22, ex + 22, ey + 22], 0, 360, width=9)
            hoop = d.img()
            out = paste(out, contact_shadow(hoop, dx=4, dy=6, blur=6, alpha=0.25))
            out = paste(out, soft_shape(hoop, PAL["gold"], dark=0.20, rim=0.10, spec=0.30))
            dp = mdraw(); dp.ellipse([ex - 5, ey - 26, ex + 5, ey - 16], 255)
            out = paste(out, soft_shape(dp.img(), PAL["gold"], dark=0.15, rim=0.08, spec=0.35))
        return out
    return fn

def item_scarf():
    def fn(fam):
        a = FAM[fam]
        y = a["scarf_y"]; half = a["scarf_half"]; h = a["scarf_h"]; cx = a["cx"]
        out = Image.new("RGBA", (CW, CW), (0, 0, 0, 0))
        # cola colgando primero (detras de la banda)
        tx = a["scarf_tail"][0]
        d = mdraw()
        d.poly([(tx - 34, y + 8), (tx + 34, y + 8), (tx + 26, y + 128), (tx - 26, y + 128)])
        tail = d.img()
        out = paste(out, contact_shadow(tail, dx=6, dy=10, blur=10, alpha=0.30))
        out = paste(out, soft_shape(tail, PAL["scarf"], dark=0.22, rim=0.10, spec=0.12))
        # flecos
        df = mdraw()
        for k in (-2, -1, 0, 1, 2):
            df.line([(tx + k * 11, y + 126), (tx + k * 11, y + 158)], width=9)
        out = paste(out, soft_shape(df.img(), (208, 148, 66), dark=0.18, rim=0.08, spec=0.0))
        # banda al cuello
        band = new_mask()
        ImageDraw.Draw(band).rounded_rectangle(
            [(cx - half) * SS, (y - h / 2) * SS, (cx + half) * SS, (y + h / 2) * SS],
            radius=(h / 2) * SS, fill=255)
        out = paste(out, contact_shadow(band, dy=9, blur=11, alpha=0.32))
        out = paste(out, soft_shape(band, PAL["scarf"], dark=0.20, rim=0.10, spec=0.16))
        # pliegues
        dpl = mdraw()
        for k in (-1.5, -0.5, 0.5, 1.5):
            dpl.line([(cx + k * half * 0.5, y - h * 0.30), (cx + k * half * 0.5 - 8, y + h * 0.30)], width=6)
        out = paste(out, Image.composite(
            Image.new("RGBA", (CW, CW), (255, 255, 255, 40)),
            Image.new("RGBA", (CW, CW), (0, 0, 0, 0)), dpl.img()))
        return out
    return fn

# ══════════════════ catalogo ══════════════════

ITEMS = {
    # ropa (slot "clothes")
    "tee_pink":   (item_tee(PAL["tee_pink"]),        "clothes", ("pig", "koala")),
    "tee_violet": (item_tee(PAL["tee_violet"]),      "clothes", ("pig", "koala")),
    "tee_teal":   (item_tee(PAL["tee_teal"]),        "clothes", ("pig", "koala")),
    "dots":       (item_tee(PAL["tee_pink"], "dots"), "clothes", ("pig", "koala")),
    "stripes":    (item_tee(PAL["tee_pink"], "stripes"), "clothes", ("pig", "koala")),
    "dress":      (item_dress(),                     "clothes", ("pig",)),
    "suit":       (item_suit(),                      "clothes", ("koala",)),
    # accesorios
    "bow":        (item_bow(),        "head", ("pig", "koala")),
    "flower":     (item_flower(),     "head", ("pig", "koala")),
    "party":      (item_party(),      "head", ("pig", "koala")),
    "cap":        (item_cap(),        "head", ("pig", "koala")),
    "earrings":   (item_earrings(),   "head", ("pig", "koala")),
    "glasses":    (item_glasses(),    "eyes", ("pig", "koala")),
    "heart":      (item_heart(),      "chest", ("pig", "koala")),
    "scarf":      (item_scarf(),      "neck", ("pig", "koala")),
    "shoes_red":  (item_shoes(PAL["shoes_red"], PAL["sole"], "mixed"), "feet", ("pig", "koala")),
    "shoes_blue": (item_shoes(PAL["shoes_blue"], PAL["sole_blue"], "mixed"), "feet", ("pig", "koala")),
}

PREFIX = {("clothes", "pig"): "av_c_{}_pig", ("clothes", "koala"): "av_c_{}_koala",
          ("acc", "pig"): "av_a_{}_pig", ("acc", "koala"): "av_a_{}_koala"}

def main():
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1].split(",")
    os.makedirs(f"{OUT3D}/drawable-nodpi", exist_ok=True)
    os.makedirs(f"{OUT3D}/qa", exist_ok=True)
    bases = {f: Image.open(f"{BASES}/{n}-1024.png").convert("RGBA")
             for f, n in (("pig", "cerdita"), ("koala", "koalita"))}
    comp = {"pig": [], "koala": []}
    for item_id, (fn, slot, fams) in ITEMS.items():
        if only and item_id not in only:
            continue
        for fam in fams:
            rgba = fn(fam)                     # 2048
            # export: 384 lossless webp
            ex = rgba.resize((384, 384), Image.LANCZOS)
            kind = "c" if slot == "clothes" else "a"
            name = f"av_{kind}_{item_id}_{fam}"
            ex.save(f"{OUT3D}/drawable-nodpi/{name}.webp", "WEBP", lossless=True, quality=100)
            kb = os.path.getsize(f"{OUT3D}/drawable-nodpi/{name}.webp") / 1024
            # QA compuesto sobre la base
            base = bases[fam].resize((512, 512), Image.LANCZOS)
            lay = rgba.resize((512, 512), Image.LANCZOS)
            comp_img = Image.alpha_composite(base, lay)
            comp_img.save(f"{OUT3D}/qa/compose_{name}.png")
            comp[fam].append((item_id, comp_img))
            print(f"  {name:<26} {kb:5.1f} KB  slot={slot}")
    # contact sheet por familia
    for fam, lst in comp.items():
        if not lst:
            continue
        thumb = 256
        cols = 5
        rows = (len(lst) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * thumb, rows * (thumb + 22)), "white")
        dr = ImageDraw.Draw(sheet)
        for i, (item_id, im) in enumerate(lst):
            x, y = (i % cols) * thumb, (i // cols) * (thumb + 22)
            sheet.paste(im.resize((thumb, thumb)), (x, y + 22))
            dr.text((x + 6, y + 4), item_id, fill="black")
        sheet.save(f"{OUT3D}/qa/sheet_{fam}.png")
        print(f"QA sheet {fam}: {OUT3D}/qa/sheet_{fam}.png  ({len(lst)} items)")

if __name__ == "__main__":
    main()
