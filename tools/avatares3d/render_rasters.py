#!/usr/bin/env python3
"""
Cerlita Chat — renders RASTER del modelo 3D para todo el app (D102).

Dos fases (el Python de Blender no tiene PIL, y separar permite re-ajustar
los sprites sin re-renderizar):

  FASE 1 (dentro de Blender):  blender -b -P render_rasters.py -- render
     Construye cada colorway con build_avatars.build_family (con el giro
     canónico D102: la cara a -Y → glTF +Z), renderiza en Cycles con
     iluminación de estudio y fondo transparente:
       - la BASE de cada colorway (sin prendas)
       - cada prenda/accesorio puesta sobre la base canónica de su familia
       - dos renders de determinismo y renders directos de combinaciones
     Todo a qa/rasters/*.png

  FASE 2 (python3 del sistema):  python3 render_rasters.py sprites
     - av_<colorway>.webp : la base tal cual (reemplaza la base IA vieja)
     - av_c_<prenda>_<fam>.webp / av_a_<acc>_<fam>.webp : SPRITES por DIFF
       (render con prenda − render base): sombreado real, alineación exacta
       por construcción (misma cámara), el sistema de capas de la app los
       compone igual que a los stickers viejos.
     - verificación: composite(base+sprites) vs render directo (MSE) +
       previews para inspección visual.

Mismos nombres de archivo → mismos R.drawable → CERO cambios de código.
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RASTER_DIR = os.path.join(HERE, "qa", "rasters")

SIZE = 384          # misma resolución que el arte v0.11
SAMPLES = 72
SEED = 7

CLOTHES = {
    "pig":   ["tee_pink", "tee_violet", "tee_teal", "dots", "stripes", "dress"],
    "koala": ["tee_pink", "tee_violet", "tee_teal", "dots", "stripes", "suit"],
}
ACCS = ["bow", "flower", "party", "cap", "earrings", "glasses", "heart", "scarf",
        "shoes_red", "shoes_blue"]
# combinaciones para la verificación composite-vs-directo y previews finales
CHECK_COMBOS = {
    "pig":   {"B": ["tee_pink", "party"], "C": ["dress"], "D": ["tee_teal", "glasses", "shoes_blue"]},
    "koala": {"B": ["tee_pink", "party"], "C": ["suit"], "D": ["tee_teal", "glasses", "shoes_blue"]},
}

# diff → alpha (unidades 0-255): por debajo de T0 invisible, por encima de T1 opaco
T0, T1 = 10.0, 32.0


def png(name):
    return os.path.join(RASTER_DIR, name + ".png")


# ═══════════════════════ FASE 1: renders (Blender) ════════════════════════════

def setup_studio():
    """Cycles + 3 luces de estudio + mundo suave (look 'juguete de vinilo')."""
    import bpy
    from mathutils import Vector
    import build_avatars as ba

    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = SAMPLES
    sc.cycles.seed = SEED
    sc.cycles.use_denoising = True
    sc.render.resolution_x = SIZE
    sc.render.resolution_y = SIZE
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.view_settings.view_transform = "Standard"

    w = bpy.data.worlds.new("Studio")
    w.use_nodes = True
    bg = w.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (1.0, 0.965, 0.965, 1.0)   # blanco rosado
    bg.inputs[1].default_value = 0.55                        # sombras pastel
    sc.world = w

    def area_light(name, loc, energy, size):
        li = bpy.data.lights.new(name, "AREA")
        li.energy = energy
        li.size = size
        li.color = (1.0, 0.985, 0.96)
        o = bpy.data.objects.new(name, li)
        o.location = loc
        d = -Vector(loc).normalized()
        o.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
        ba.link(o)

    # la cara (tras turn_around) mira a -Y: clave frente-izq-arriba,
    # relleno frente-der, contra desde atrás para recortar la silueta
    area_light("key",  (-1.5, -2.1, 2.5), 95, 1.6)
    area_light("fill", (2.0, -1.5, 1.3), 26, 2.6)
    area_light("rim",  (0.4, 1.9, 2.1), 20, 1.3)

    cam = bpy.data.cameras.new("cam")
    cam.lens = 52
    co = bpy.data.objects.new("cam", cam)
    ba.link(co)
    sc.camera = co
    return co


def studio_shot(co, path, az_deg=14.0, elev_deg=7.0, dist=2.45,
                target=(0.0, 0.02, 0.64)):
    """Frente 3/4 ligero. az 0 = frente (cámara del lado -Y, cara post-D102)."""
    import bpy
    from mathutils import Vector
    az, el = math.radians(az_deg), math.radians(elev_deg)
    co.location = (dist * math.cos(el) * math.sin(az),
                   -dist * math.cos(el) * math.cos(az),
                   target[2] + dist * math.sin(el))
    d = Vector(target) - co.location
    co.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def phase_render(only=None):
    import bpy
    sys.path.insert(0, HERE)
    import build_avatars as ba

    os.makedirs(RASTER_DIR, exist_ok=True)
    for fam in ("pig", "koala"):
        if only and fam not in only:
            continue
        colorways = list(ba.FAMS[fam]["colorways"])
        canon = colorways[0]

        # determinismo: mismo render dos veces (el diff de sprites depende de ello)
        ba.build_family(fam, canon)
        ba.reset_pose(bpy.data.objects["Rig"])
        co = setup_studio()
        ba.set_visible(ba.WEAR_IDS, [])
        studio_shot(co, png(f"{fam}_det0"))
        studio_shot(co, png(f"{fam}_det1"))

        for cw in colorways:
            if cw != canon:
                ba.build_family(fam, cw)
                ba.reset_pose(bpy.data.objects["Rig"])
                co = setup_studio()
            ba.set_visible(ba.WEAR_IDS, [])
            studio_shot(co, png(f"{fam}_base_{cw}"))
            print(f"[render] base {fam}/{cw}", flush=True)

            if cw == canon:
                for gid in CLOTHES[fam] + ACCS:
                    ba.set_visible(ba.WEAR_IDS, [gid])
                    studio_shot(co, png(f"{fam}_{gid}"))
                    print(f"[render] {fam} + {gid}", flush=True)
                # renders directos de las combinaciones de verificación
                for tag, ids in CHECK_COMBOS[fam].items():
                    ba.set_visible(ba.WEAR_IDS, ids)
                    studio_shot(co, png(f"{fam}_direct_{tag}"))
                    print(f"[render] {fam} direct {tag} ({'+'.join(ids)})", flush=True)
    print("[render phase done]", flush=True)


# ═══════════════════════ FASE 2: sprites (python del sistema) ═════════════════

def phase_sprites(only=None):
    import numpy as np
    from PIL import Image, ImageFilter

    res_dir = os.path.normpath(os.path.join(
        HERE, "..", "..", "android", "app", "src", "main", "res", "drawable-nodpi"))
    os.makedirs(res_dir, exist_ok=True)

    def load(name):
        return np.asarray(Image.open(png(name)).convert("RGBA"), dtype=np.float32)

    def save_webp(arr, path):
        Image.fromarray(arr.astype(np.uint8), "RGBA").save(path, "WEBP", quality=92, method=6)

    for fam in ("pig", "koala"):
        if only and fam not in only:
            continue
        colorways = ["pig", "pig_melocoton", "pig_lavanda"] if fam == "pig" else \
                    ["koala", "koala_azul", "koala_cacao", "koala_cielo"]
        canon = colorways[0]

        # 0) determinismo del render (los sprites dependen de diffs limpios)
        d0, d1 = load(f"{fam}_det0"), load(f"{fam}_det1")
        noise = float(np.abs(d0 - d1).max())
        print(f"[{fam}] determinismo render: max Δ = {noise:.1f} "
              f"{'OK' if noise < 3 else '⚠ RUIDO'}", flush=True)

        # 1) bases por colorway
        for cw in colorways:
            arr = load(f"{fam}_base_{cw}")
            save_webp(arr, os.path.join(res_dir, f"av_{cw}.webp"))
            print(f"  base {cw} -> av_{cw}.webp", flush=True)

        # 2) sprites por diff sobre la base canónica
        base = load(f"{fam}_base_{canon}")
        sprites = {}
        for kind, ids in (("c", CLOTHES[fam]), ("a", ACCS)):
            for gid in ids:
                g = load(f"{fam}_{gid}")
                d = np.abs(g[..., :3] - base[..., :3]).max(axis=2)
                a = np.clip((d - T0) / (T1 - T0), 0.0, 1.0)
                a = np.minimum(a, g[..., 3] / 255.0)
                aimg = Image.fromarray((a * 255).astype(np.uint8), "L").filter(
                    ImageFilter.MedianFilter(3))
                a = np.asarray(aimg, dtype=np.float32) / 255.0
                sprite = np.dstack([g[..., :3], a * 255.0])
                out = os.path.join(res_dir, f"av_{kind}_{gid}_{fam}.webp")
                save_webp(sprite, out)
                sprites[gid] = out
                cov = float((a > 0.25).mean())
                flag = "" if 0.005 < cov < 0.65 else "  ⚠ COBERTURA RARA"
                print(f"  {gid:10s} cobertura {cov*100:5.1f}%{flag}", flush=True)
                # preview sobre tablero para inspección
                h, w = a.shape
                yy, xx = np.mgrid[0:h, 0:w]
                board = np.where(((yy // 16 + xx // 16) % 2) == 0, 235, 205).astype(np.float32)
                board = np.dstack([board, board * 0.97, board * 0.97])
                over = board * (1 - a[..., None]) + g[..., :3] * a[..., None]
                Image.fromarray(over.astype(np.uint8), "RGB").save(
                    png(f"prev_{gid}_{fam}"), "PNG")

        # 3) verificación composite-vs-directo
        def compose(ids, out):
            acc = Image.open(png(f"{fam}_base_{canon}")).convert("RGBA")
            for i in ids:
                acc = Image.alpha_composite(acc, Image.open(sprites[i]).convert("RGBA"))
            if out:
                acc.save(out, "WEBP", quality=92, method=6)
            return acc

        for tag, ids in CHECK_COMBOS[fam].items():
            comp = compose(ids, png(f"{fam}_composite_{tag}"))
            direct = Image.open(png(f"{fam}_direct_{tag}")).convert("RGBA")
            a = np.asarray(comp.convert("RGB"), dtype=np.float32)
            b = np.asarray(direct.convert("RGB"), dtype=np.float32)
            bad = float((np.abs(a - b).mean(axis=2) > 12).mean())
            print(f"  check {'+'.join(ids):38s}: {bad*100:5.2f}% px distintos "
                  f"{'OK' if bad < 0.02 else '⚠'}", flush=True)

        # 4) previews finales (lo que verá la app al componer capas)
        compose([], png(f"FINAL_{fam}_A"))
        for tag, ids in CHECK_COMBOS[fam].items():
            compose(ids, png(f"FINAL_{fam}_{tag}"))
    print("[sprites phase done]", flush=True)


def main():
    # blender -b -P render_rasters.py -- render [--only=pig]  → args tras '--'
    # python3 render_rasters.py sprites [--only=pig]           → args normales
    if "--" in sys.argv:
        args = sys.argv[sys.argv.index("--") + 1:]
    else:
        args = sys.argv[1:]
    mode = args[0] if args else "render"
    only = None
    for a in args[1:]:
        if a.startswith("--only="):
            only = a.split("=", 1)[1]
    if mode == "render":
        phase_render(only)
    elif mode == "sprites":
        phase_sprites(only)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
