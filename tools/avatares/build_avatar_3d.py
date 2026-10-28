#!/usr/bin/env python3
"""
Cerlita Chat — pipeline de avatares v3: CUERPO COMPLETO 3D (D92).

Entrada : render 3D sobre fondo verde (work3d/raw_*.png)
Salida  : WebP RGBA con margen generoso (las bases), colorways derivados por
          transferencia de offset (preserva el sombreado del render), PNG QA
          1024 con rejilla, y medidas para la tabla de anclas de make_layers.

Diferencias con v2 (build_avatar.py):
  - El arte es 3D con sombreado -> NO se hace snap a la paleta plana (el
    unmix de v2 asumia pixel = a*relleno + (1-a)*fondo con relleno plano).
  - El fondo verde se quita por DOMINANCIA (G - max(R,B)) porque el render
    mete gradiente: el verde del suelo no es un color exacto.
  - Despill: el borde antialiasado contra el verde trae halo verde -> se
    satura G a max(R,B) en el frente.
  - Colorways por CLUSTERS medidos del propio arte + offset (new = orig +
    (target - centro)): el sombreado se conserva (una sombra del cuerpo
    sigue siendo sombra del color nuevo).
  - Margen de artboard 10% (v2: 3%): el personaje nunca toca el borde ->
    el recorte circular del avatar no corta orejas ni pies (bug v0.9.0).
"""
import os, sys, json, argparse
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# ── colorways: tokens objetivo (mismos hex que v2) ──
PIG_CW = {
    "melocoton": {"body": "#FFD5C2", "deep": "#FFAE96"},
    "lavanda":   {"body": "#E4D4F2", "deep": "#C3A8DE"},
    "menta":     {"body": "#CDEADF", "deep": "#94CDBA"},
}
KOALA_CW = {
    "azul":  {"body": "#BFC9D6", "ear": "#EFF3F8"},
    "cacao": {"body": "#C4A99A", "ear": "#F2E6DC"},
    "cielo": {"body": "#C5D8EE", "ear": "#F1F6FC"},
}

def hex2rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i+2], 16) for i in (0, 2, 4)], dtype=float)

# ── 1. chroma key por dominancia verde ──
def chroma_key(img):
    a = img.astype(float)
    gdom = a[:, :, 1] - np.maximum(a[:, :, 0], a[:, :, 2])
    # fondo: gdom alto. frente: gdom bajo. 30..70 = rampa del borde.
    alpha = np.clip((70.0 - gdom) / 40.0, 0.0, 1.0)
    # despill en el frente: G no puede superar a max(R,B)
    m = (a[:, :, 1] > np.maximum(a[:, :, 0], a[:, :, 2])) & (alpha > 0)
    a[:, :, 1] = np.where(m, np.maximum(a[:, :, 0], a[:, :, 2]), a[:, :, 1])
    out = np.dstack([a, alpha[:, :, None] * 255.0]).astype(np.uint8)
    return out

def clean_alpha(rgba):
    """Erosiona 1px el borde del alfa (se come el halo verde del antialias
    del render contra el fondo) + mediana suave."""
    al = Image.fromarray(rgba[:, :, 3], "L").filter(ImageFilter.MinFilter(3))
    al = np.array(al).astype(np.int16)
    pad = np.pad(al, 1, mode="edge")
    med = np.zeros_like(al)
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            med += pad[dy:dy+al.shape[0], dx:dx+al.shape[1]]
    med //= 9
    rgba[:, :, 3] = np.where((al > 0) & (al < 250), med, al)
    return rgba

# ── 2. artboard cuadrado con margen generoso ──
def crop_and_fit(rgba, size=1024, pad_ratio=0.10):
    a = rgba[:, :, 3]
    ys, xs = np.where(a > 8)
    if len(xs) == 0:
        raise RuntimeError("no hay contenido")
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    crop = rgba[y0:y1 + 1, x0:x1 + 1]
    ch, cw = crop.shape[:2]
    side = int(max(ch, cw) * (1 + pad_ratio * 2))
    canvas = np.zeros((side, side, 4), dtype=np.uint8)
    oy, ox = (side - ch) // 2, (side - cw) // 2
    canvas[oy:oy + ch, ox:ox + cw] = crop
    return np.array(Image.fromarray(canvas, "RGBA").resize((size, size), Image.LANCZOS))

# ── 3. clusters de tono (familias de color del personaje) ──
def sample_pig_clusters(rgba):
    """cerdita: body (rosa claro) + deep (orejas/hocico/mejillas, rosa subido)."""
    m = rgba[:, :, 3] > 200
    px = rgba[:, :, :3][m].astype(float)
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    v = px.max(1) / 255.0
    s = np.where(v > 0, (px.max(1) - px.min(1)) / np.maximum(px.max(1), 1), 0)
    pink = (r > g) & (g >= b) & (r - b > 22)
    body_m = pink & (v > 0.78) & (s < 0.34) & (s > 0.06)
    deep_m = pink & (s >= 0.32) & (v > 0.5)
    body = np.median(px[body_m], axis=0) if body_m.sum() > 500 else hex2rgb("#FFC1CC")
    deep = np.median(px[deep_m], axis=0) if deep_m.sum() > 500 else hex2rgb("#FB96A5")
    return {"body": body, "deep": deep}

def sample_koala_clusters(rgba):
    """koalita: body (gris) + ear (crema del interior de orejas)."""
    m = rgba[:, :, 3] > 200
    px = rgba[:, :, :3][m].astype(float)
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    v = px.max(1) / 255.0
    s = np.where(v > 0, (px.max(1) - px.min(1)) / np.maximum(px.max(1), 1), 0)
    grey = (s < 0.16) & (v > 0.45) & (v < 0.95)
    cream = (r > g) & (g > b) & (r - b > 12) & (v > 0.78) & (s < 0.42)
    body = np.median(px[grey], axis=0) if grey.sum() > 500 else hex2rgb("#BCB2BA")
    ear = np.median(px[cream], axis=0) if cream.sum() > 300 else hex2rgb("#F9E2DC")
    return {"body": body, "ear": ear}

def recolor_offset(rgba, clusters, targets, max_dist):
    """new = orig + (target - centro) para el pixel mas cercano a un cluster."""
    out = rgba.copy()
    m = rgba[:, :, 3] > 200
    px = rgba[:, :, :3][m].astype(float)
    v = px.max(1) / 255.0
    s = np.where(v > 0, (px.max(1) - px.min(1)) / np.maximum(px.max(1), 1), 0)
    # no tocar: casi blanco (ojos) ni tinta oscura (contorno)
    keep = (v > 0.965) & (s < 0.11) | (v < 0.36)
    names = list(clusters.keys())
    cents = np.array([clusters[n] for n in names])
    d = np.linalg.norm(px[:, None, :] - cents[None, :, :], axis=2)  # (N, k)
    best = d.argmin(1)
    best_d = d.min(1)
    shift = np.zeros_like(px)
    for i, n in enumerate(names):
        sel = best == i
        shift[sel] = hex2rgb(targets[n]) - cents[i]
    ok = (best_d < max_dist) & ~keep
    px[ok] = np.clip(px[ok] + shift[ok], 0, 255)
    out[:, :, :3][m] = px.astype(np.uint8)
    return out

# ── 4. anclas: medidas para make_layers ──
def _components(mask):
    """Componentes conexas 4-vecinas de una mascara booleana.
    Devuelve [(cx, cy, n)] por componente (BFS — las mascaras son pequeñas)."""
    m = mask.copy()
    h, w = m.shape
    out = []
    ys, xs = np.where(m)
    seen = np.zeros_like(m, dtype=bool)
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        stack = [(y0, x0)]
        seen[y0, x0] = True
        sx = sy = n = 0
        while stack:
            y, x = stack.pop()
            sx += x; sy += y; n += 1
            if y > 0 and m[y - 1, x] and not seen[y - 1, x]:
                seen[y - 1, x] = True; stack.append((y - 1, x))
            if y + 1 < h and m[y + 1, x] and not seen[y + 1, x]:
                seen[y + 1, x] = True; stack.append((y + 1, x))
            if x > 0 and m[y, x - 1] and not seen[y, x - 1]:
                seen[y, x - 1] = True; stack.append((y, x - 1))
            if x + 1 < w and m[y, x + 1] and not seen[y, x + 1]:
                seen[y, x + 1] = True; stack.append((y, x + 1))
        if n >= 10:
            out.append((sx / n, sy / n, n))
    return out

def measure(rgba):
    a = rgba[:, :, 3] > 128
    ys, xs = np.where(a)
    top, bot = ys.min(), ys.max()
    left, right = xs.min(), xs.max()
    h, w = bot - top, right - left
    # ojos: pixeles oscuros del frente en la cabeza -> componentes conexas 2D
    # -> el PAR SIMETRICO MAS ALTO son los ojos (fosas/boca también son
    # simétricas pero están más abajo; ojos cerrados = arcos finos y anchos)
    px = rgba[:, :, :3].astype(float)
    dark = (rgba[:, :, 3] > 200) & (px.max(2) < 115)
    # banda de los ojos: entre el 15% y el 45% de la altura del personaje
    # (por encima están los contornos de las orejas, por debajo hocico/boca)
    dark[:top + int(h * 0.15), :] = False
    dark[top + int(h * 0.45):, :] = False
    eye_l = eye_r = None
    comps = _components(dark)
    cents = [c for c in comps if c[2] >= 10]
    if len(cents) >= 2:
        cxg = (left + right) / 2
        best = None
        for i in range(len(cents)):
            for j in range(i + 1, len(cents)):
                (xl, yl, nl), (xr, yr, nr) = cents[i], cents[j]
                if xl < cxg < xr and abs(xl + xr - 2 * cxg) < 90 and abs(yl - yr) < 60 \
                        and abs(nl - nr) < max(nl, nr) * 3:
                    score = (yl + yr) / 2  # más alto (menor y) = mejor
                    if best is None or score < best[0]:
                        best = (score, (xl, yl), (xr, yr))
        if best:
            eye_l, eye_r = best[1], best[2]
    # ancho de la fila de los ojos -> radio de la cabeza
    ey = (eye_l[1] + eye_r[1]) / 2 if eye_l and eye_r else top + h * 0.30
    row = a[int(round(ey))]
    hw = row.sum() / 2.0
    head_rx = int(min(hw * 0.92, w * 0.5))
    ex = (eye_l[0] + eye_r[0]) / 2 if eye_l and eye_r else (left + right) / 2
    # orejas: extremos del alfa en el 30% superior
    band = a[top:top + int(h * 0.32), :]
    bys, bxs = np.where(band)
    ear_ly = int(top + np.percentile(bys[bxs < ex] if (bxs < ex).any() else bys, 5))
    ear_ry = int(top + np.percentile(bys[bxs >= ex] if (bxs >= ex).any() else bys, 5))
    ear_l = (int(np.percentile(bxs[bxs < ex], 2)) if (bxs < ex).any() else left, ear_ly)
    ear_r = (int(np.percentile(bxs[bxs >= ex], 98)) if (bxs >= ex).any() else right, ear_ry)
    chin_y = int(ey + (ey - top) * 0.88)
    chest_y = int(chin_y + (bot - chin_y) * 0.40)
    return dict(
        cx=int(ex), head_top=int(top), chin_y=chin_y, head_rx=int(head_rx),
        ear_l=ear_l, ear_r=ear_r,
        eye_l=(int(eye_l[0]), int(eye_l[1])) if eye_l else (int(ex - hw * 0.45), int(ey)),
        eye_r=(int(eye_r[0]), int(eye_r[1])) if eye_r else (int(ex + hw * 0.45), int(ey)),
        eye_y=int(ey),
        cheek_l=(int(ex - head_rx * 0.58), int(ey + head_rx * 0.28)),
        cheek_r=(int(ex + head_rx * 0.58), int(ey + head_rx * 0.28)),
        chest_y=chest_y, bbox=dict(x0=int(left), x1=int(right), y0=int(top), y1=int(bot)),
    )

def grid_overlay(rgba, anchors, path):
    im = Image.fromarray(rgba, "RGBA").convert("RGB")
    d = ImageDraw.Draw(im)
    for x in range(0, 1025, 64):
        d.line([(x, 0), (x, 1024)], fill=(200, 200, 200), width=1)
        if x % 128 == 0:
            d.text((x + 2, 2), str(x), fill=(90, 90, 90))
    for y in range(0, 1025, 64):
        d.line([(0, y), (1024, y)], fill=(200, 200, 200), width=1)
    for k in ("ear_l", "ear_r", "eye_l", "eye_r", "cheek_l", "cheek_r"):
        x, y = anchors[k]
        d.ellipse([x - 10, y - 10, x + 10, y + 10], outline=(0, 120, 255), width=3)
    for k in ("head_top", "chin_y", "chest_y", "eye_y"):
        y = anchors[k]
        d.line([(0, y), (1024, y)], fill=(255, 0, 0), width=2)
        d.text((4, y + 3), f"{k}={y}", fill=(255, 0, 0))
    x = anchors["cx"]
    d.line([(x, 0), (x, 1024)], fill=(255, 0, 0), width=2)
    d.line([(anchors["bbox"]["x0"], 0), (anchors["bbox"]["x0"], 1024)], fill=(0, 160, 0), width=2)
    d.line([(anchors["bbox"]["x1"], 0), (anchors["bbox"]["x1"], 1024)], fill=(0, 160, 0), width=2)
    im.save(path)

# ── main ──
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("family", choices=["pig", "koala", "pair"])
    ap.add_argument("outdir")
    ap.add_argument("--sizes", default="384")
    args = ap.parse_args()

    os.makedirs(args.outdir, exist_ok=True)
    img = np.array(Image.open(args.src).convert("RGB"))
    rgba = clean_alpha(chroma_key(img))
    art = crop_and_fit(rgba, 1024)

    name = {"pig": "cerdita", "koala": "koalita", "pair": "pareja"}[args.family]
    clusters = sample_pig_clusters(art) if args.family == "pig" else (
        sample_koala_clusters(art) if args.family == "koala" else None)
    cws = PIG_CW if args.family == "pig" else (KOALA_CW if args.family == "koala" else {})
    maxd = 105 if args.family == "pig" else 70

    variants = [(None, art)] + [
        (cw, recolor_offset(art, clusters, t, maxd)) for cw, t in cws.items()
    ]
    manifest = []
    for cw, a in variants:
        nm = name if cw is None else f"{name}-{cw}"
        an = measure(a)
        grid_overlay(a, an, os.path.join(args.outdir, f"qa_{nm}.png"))
        json.dump(an, open(os.path.join(args.outdir, f"anchors_{nm}.json"), "w"), indent=1)
        for s in [int(x) for x in args.sizes.split(",")]:
            im = Image.fromarray(a, "RGBA").resize((s, s), Image.LANCZOS) if s != 1024 else Image.fromarray(a, "RGBA")
            wp = os.path.join(args.outdir, f"{nm}-{s}.webp")
            im.save(wp, "WEBP", lossless=True, quality=100)
            pp = os.path.join(args.outdir, f"{nm}-{s}.png")
            im.save(pp, optimize=True)
            manifest.append(dict(name=nm, size=s, webp_kb=round(os.path.getsize(wp) / 1024, 1)))
        print(f"{nm:<18} webp={manifest[-1]['webp_kb']}KB anchors: cx={an['cx']} eye_y={an['eye_y']} "
              f"head_top={an['head_top']} chin={an['chin_y']} rx={an['head_rx']} chest={an['chest_y']}")
    json.dump(manifest, open(os.path.join(args.outdir, f"manifest_{name}.json"), "w"), indent=1)
    print("clusters:", {k: [round(x) for x in v] for k, v in (clusters or {}).items()})

if __name__ == "__main__":
    main()
