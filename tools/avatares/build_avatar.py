#!/usr/bin/env python3
"""
Cerlita Chat — pipeline de produccion de avatares (v2).

Entrada : raster de IA sobre fondo cromatico plano (verde).
Salida  : PNG RGBA con alfa limpio, colores SNAPPED a la paleta canonica,
          opcionalmente re-coloreado (colorways) y en buckets de densidad.

Ideas clave
-----------
1. UNMATTE por descomposicion:  pixel = alpha*relleno + (1-alpha)*fondo.
   Se busca el color de paleta c tal que el pixel cae en el segmento [bg, c].
   De ahi salen alpha y color canonico -> el arte queda 100% consistente
   con la referencia, Hex exacto, sin depender de lo que hizo el modelo.
2. SNAP DE ALFA: el JPEG mete ruido en el verde plano -> alpha 250-254 en
   vez de 255. Se cuantiza a {0, 255} y se conserva solo la rampa real
   del borde (0 < alpha < 250).
3. COLORWAYS: el mismo arte se recolorea por sustitucion de paleta -> 6 bases
   a partir de 2 generaciones.
4. MEDICION DE ANCLAS: se mide el bbox de la tinta y del relleno base para
   que las capas vectoriales se posicionen con exactitud.
"""
import sys, os, json, argparse
import numpy as np
from PIL import Image

# ── STYLE BIBLE: medido sobre la referencia original (1080x1080) ──
PALETTE = {
    "ink":       "#503433",   # contorno / tinta (NO negro)
    "pig_body":  "#FFC1CC",   # cuerpo cerdita
    "pig_ear":   "#FB96A5",   # interior oreja cerdita
    "pig_snout": "#FFA2AD",   # hocico cerdita
    "blush":     "#F999A5",   # mejillas
    "koala_body":"#BCB2BA",   # cuerpo koalita
    "koala_ear": "#F9E2DC",   # interior oreja koalita
    "heart":     "#F0758D",   # corazones rosa
    "white":     "#FEE8EB",   # corazon claro / burbuja crema
    "violet":    "#BC8FB5",   # burbuja violeta
}
PAL_ORDER = list(PALETTE.keys())
def hex2rgb(h):
    h = h.lstrip('#'); return np.array([int(h[i:i+2], 16) for i in (0, 2, 4)], dtype=float)
PAL_RGB = np.array([hex2rgb(PALETTE[v]) for v in PAL_ORDER])

# ── COLORWAYS: sustituciones de tokens (mismo arte, distinto color) ──
COLORWAYS = {
    "rosa":       {},
    "melocoton": {"pig_body": "#FFD5C2", "pig_ear": "#FFAE96", "pig_snout": "#FFBCA8", "blush": "#FF9E88"},
    "lavanda":   {"pig_body": "#E4D4F2", "pig_ear": "#C3A8DE", "pig_snout": "#D6BEEA", "blush": "#F09CC0"},
    "menta":     {"pig_body": "#CDEADF", "pig_ear": "#94CDBA", "pig_snout": "#AFD9C9", "blush": "#F0909F"},
    "azul":      {"koala_body": "#BFC9D6", "koala_ear": "#EFF3F8"},
    "gris":      {},
    "cacao":     {"koala_body": "#C4A99A", "koala_ear": "#F2E6DC"},
    "cielo":     {"koala_body": "#C5D8EE", "koala_ear": "#F1F6FC", "blush": "#F2A0B0"},
}

def detect_bg(img):
    h, w, _ = img.shape
    border = np.concatenate([img[:6].reshape(-1, 3), img[-6:].reshape(-1, 3),
                             img[:, :6].reshape(-1, 3), img[:, -6:].reshape(-1, 3)])
    q = (border // 8 * 8).astype(np.int32)
    key = q[:, 0] * 65536 + q[:, 1] * 256 + q[:, 2]
    vals, cnts = np.unique(key, return_counts=True)
    k = vals[cnts.argmax()]
    return np.array([(k >> 16) & 255, (k >> 8) & 255, k & 255], dtype=float)

def unmatte(img, tol=520.0):
    """Devuelve RGBA con la paleta canonica aplicada y alfa limpio."""
    h, w, _ = img.shape
    flat = img.reshape(-1, 3).astype(float)
    bg = detect_bg(img)
    best_err = np.full(len(flat), np.inf)
    best_a = np.ones(len(flat)); best_i = np.full(len(flat), -1)
    for i, c in enumerate(PAL_RGB):
        d = c - bg; den = float(d @ d)
        if den < 1e-6: continue
        t = np.clip(((flat - bg) @ d) / den, 0.0, 1.0)
        recon = bg + t[:, None] * d
        err = ((flat - recon) ** 2).sum(1)
        b = err < best_err
        best_err[b] = err[b]; best_a[b] = t[b]; best_i[b] = i
    ok = best_err < tol
    out = np.zeros((len(flat), 4), dtype=np.uint8)
    out[:, :3] = np.where(ok[:, None], PAL_RGB[np.clip(best_i, 0, len(PAL_RGB) - 1)], flat)
    a = np.where(ok, best_a, 1.0)
    # snap de alfa: fuera del borde es 0 o 255; en el borde se conserva la rampa
    a = np.where(a < 0.04, 0.0, np.where(a > 0.965, 1.0, a))
    out[:, 3] = (a * 255).round().astype(np.uint8)
    return out.reshape(h, w, 4), bg

def recolor(rgba, mapping):
    """Sustituye tokens de color (colorways) sobre la imagen ya procesada."""
    out = rgba.copy()
    for src, dst in mapping.items():
        if src not in PAL_ORDER: continue
        s = hex2rgb(PALETTE[src]).astype(np.uint8)
        d = hex2rgb(dst).astype(np.uint8)
        m = (rgba[:, :, 0] == s[0]) & (rgba[:, :, 1] == s[1]) & (rgba[:, :, 2] == s[2]) & (rgba[:, :, 3] > 0)
        out[m, 0], out[m, 1], out[m, 2] = d
    return out

def crop_and_fit(rgba, size=1024, pad_ratio=0.03, anchor="ink"):
    """Recorta al bbox con alfa, centra y escala al artboard."""
    a = rgba[:, :, 3]
    ys, xs = np.where(a > 8)
    if len(xs) == 0: raise RuntimeError("no hay contenido")
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    crop = rgba[y0:y1 + 1, x0:x1 + 1]
    ch, cw = crop.shape[:2]
    side = int(max(ch, cw) * (1 + pad_ratio * 2))
    canvas = np.zeros((side, side, 4), dtype=np.uint8)
    oy, ox = (side - ch) // 2, (side - cw) // 2
    canvas[oy:oy + ch, ox:ox + cw] = crop
    return Image.fromarray(canvas, "RGBA").resize((size, size), Image.LANCZOS)

def measure(rgba):
    """Anclas geometricas: bbox de la tinta y del relleno base."""
    a = rgba[:, :, 3]
    ink = hex2rgb(PALETTE["ink"]).astype(np.uint8)
    m_ink = (rgba[:, :, 0] == ink[0]) & (rgba[:, :, 1] == ink[1]) & (rgba[:, :, 2] == ink[2]) & (a > 128)
    out = {}
    for nm, m in (("ink", m_ink), ("all", a > 128)):
        ys, xs = np.where(m)
        if len(xs):
            out[nm] = dict(x0=int(xs.min()), x1=int(xs.max()), y0=int(ys.min()), y1=int(ys.max()),
                           w=int(xs.max() - xs.min()), h=int(ys.max() - ys.min()),
                           cx=int((xs.min() + xs.max()) / 2), cy=int((ys.min() + ys.max()) / 2))
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("outdir")
    ap.add_argument("--name", default=None)
    ap.add_argument("--colorways", default="", help="lista separada por comas")
    ap.add_argument("--sizes", default="1024", help="buckets separados por comas")
    ap.add_argument("--manifest", default=None)
    args = ap.parse_args()

    os.makedirs(args.outdir, exist_ok=True)
    img = np.array(Image.open(args.src).convert("RGB"))
    rgba, bg = unmatte(img)
    base_name = args.name or os.path.splitext(os.path.basename(args.src))[0]
    print(f"\n=== {base_name} ===  fondo #{int(bg[0]):02X}{int(bg[1]):02X}{int(bg[2]):02X}")

    cws = [c for c in args.colorways.split(",") if c] or [None]
    sizes = [int(s) for s in args.sizes.split(",") if s]
    manifest = []
    for cw in cws:
        r = recolor(rgba, COLORWAYS.get(cw, {})) if cw else rgba
        art = crop_and_fit(r, 1024)
        anchors = measure(np.array(art))
        nm = f"{base_name}-{cw}" if cw else base_name
        for s in sizes:
            im = art.resize((s, s), Image.LANCZOS) if s != 1024 else art
            p = os.path.join(args.outdir, f"{nm}-{s}.png")
            im.save(p, optimize=True)
            wp = os.path.join(args.outdir, f"{nm}-{s}.webp")
            im.save(wp, "WEBP", lossless=True, quality=100)
            manifest.append(dict(name=nm, colorway=cw, size=s, png=os.path.basename(p),
                                 webp=os.path.basename(wp),
                                 png_kb=round(os.path.getsize(p) / 1024, 1),
                                 webp_kb=round(os.path.getsize(wp) / 1024, 1)))
        print(f"  {nm:<22} anclas: {anchors.get('ink')}")
    if args.manifest:
        json.dump(manifest, open(args.manifest, "w"), indent=2)
    return manifest

if __name__ == "__main__":
    main()
