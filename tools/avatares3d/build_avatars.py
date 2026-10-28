#!/usr/bin/env python3
"""
Cerlita Chat — pipeline de avatares 3D REALES (D101).

Un solo script genera, para cada FAMILIA (pig/koala) y cada COLORWAY:
  - Personaje chibi por primitivas (esferas/cápsulas, shade smooth, sin costuras
    visibles porque las piezas se solapan como un juguete de vinilo)
  - Esqueleto (root/spine/head/ears/arms/feet) con vertex groups rígidos por pieza
    (peso 1.0 a UN hueso): comportamiento muñeco con articulaciones esféricas,
    100% predecible, cero peso automático defectuoso
  - TODA la ropa/accesorios del catálogo como MALLAS propias sobre el mismo
    esqueleto (mangas unidas a los huesos de los brazos, gorros a la cabeza...)
  - 3 animaciones: idle (respirar), wave (saludo), react (squash al vestirlo)
  - Export GLB (uno por colorway, materiales pre-coloreados: cero riesgo de
    recolorear en runtime) — la app muestra/oculta nodos por nombre de catálogo

QA: renders Eevee PNG (frente/3-4/perfil, poses wave/react, prendas on/off)
a tools/avatares3d/qa/ para verificación visual (regla D83: mirar ANTES de subir).

Uso:  blender -b -P build_avatars.py -- [--qa] [--only koala] [--no-export]
"""

import bpy
import bmesh
import math
import os
import sys
import shutil
from mathutils import Vector, Euler

# ───────────────────────── paleta (Style Bible D83 + colorways D92) ─────────────
INK      = (0x50/255, 0x34/255, 0x33/255)   # tinta chocolate — ojos, nariz koala
BLUSH    = (0xF9/255, 0x99/255, 0xA5/255)
CORAL    = (0xF0/255, 0x75/255, 0x8D/255)   # corazones, moño
GOLD     = (0xF5/255, 0xC8/255, 0x6A/255)   # flor, aretes
TEAL     = (0x7F/255, 0xC8/255, 0xC2/255)
NAVY     = (0x4A/255, 0x5B/255, 0x7A/255)   # traje koala
SHIRT_W  = (0xFD/255, 0xF6/255, 0xF2/255)   # camisa del traje / lunares / rayas
RED_SHOE = (0xD8/255, 0x48/255, 0x4F/255)
BLU_SHOE = (0x4A/255, 0x6F/255, 0xD8/255)
TEE_PINK = (0xF5/255, 0xA8/255, 0xB5/255)
TEE_VIOL = (0xBC/255, 0x8F/255, 0xB5/255)
TEE_TEAL = (0x8F/255, 0xC9/255, 0xC4/255)
DOTS_BG  = (0xFD/255, 0xF6/255, 0xF2/255)
STR_BASE = (0xF5/255, 0xA8/255, 0xB5/255)
STR_BAND = (0xFD/255, 0xF6/255, 0xF2/255)
DRESS_C  = (0xE8/255, 0xA0/255, 0xB8/255)
BELT_C   = (0xC2/255, 0x7A/255, 0x92/255)
PARTY_C  = (0xF0/255, 0x75/255, 0x8D/255)
CAP_C    = (0x6F/255, 0x9E/255, 0xD8/255)
SCARF_C  = (0xE0/255, 0x8A/255, 0x6A/255)

FAMS = {
    "pig": {
        "body": (0xFF/255, 0xC1/255, 0xCC/255), "ear_in": (0xFB/255, 0x96/255, 0xA5/255),
        "snout": (0xFF/255, 0xA2/255, 0xAD/255),
        "colorways": {
            "pig":           {},
            "pig_melocoton": {"body": (0xFF/255, 0xD5/255, 0xC2/255), "ear_in": (0xFF/255, 0xB4/255, 0xA2/255), "snout": (0xFF/255, 0xB8/255, 0xAB/255)},
            "pig_lavanda":   {"body": (0xE4/255, 0xD4/255, 0xF2/255), "ear_in": (0xD4/255, 0xB8/255, 0xEC/255), "snout": (0xEC/255, 0xC6/255, 0xF5/255)},
        },
    },
    "koala": {
        "body": (0xBC/255, 0xB2/255, 0xBA/255), "ear_in": (0xF9/255, 0xE2/255, 0xDC/255),
        "colorways": {
            "koala":       {},
            "koala_azul":  {"body": (0xBF/255, 0xC9/255, 0xD6/255), "ear_in": (0xEF/255, 0xF3/255, 0xF8/255)},
            "koala_cacao": {"body": (0xC4/255, 0xA9/255, 0x9A/255), "ear_in": (0xF2/255, 0xE6/255, 0xDC/255)},
            "koala_cielo": {"body": (0xC5/255, 0xD8/255, 0xEE/255), "ear_in": (0xF1/255, 0xF6/255, 0xFC/255)},
        },
    },
}

# ───────────────────────── anclas comunes del cuerpo chibi ──────────────────────
A = {
    "head_c":  (0.0, 0.02, 0.86), "head_r": 0.300,
    "torso_c": (0.0, 0.0, 0.40), "torso_s": (0.255, 0.215, 0.300),
    "sh_z": 0.575,
    "arm_head": (0.185, 0.0, 0.575), "arm_tail": (0.315, 0.02, 0.40),
    "arm_r": 0.072, "paw_r": 0.084,
    "feet_c": (0.115, 0.045, 0.072), "feet_s": (0.085, 0.150, 0.072),
    "eye_x": 0.112, "eye_z": 0.915, "eye_r": 0.034,
}

# ─────────────────────────── helpers de escena ─────────────────────────────────

def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def make_mat(name, rgb, rough=0.55, metallic=0.0, alpha=1.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bs = m.node_tree.nodes.get("Principled BSDF")
    bs.inputs["Base Color"].default_value = (*rgb, 1.0)
    bs.inputs["Roughness"].default_value = rough
    bs.inputs["Metallic"].default_value = metallic
    if alpha < 1.0:
        bs.inputs["Alpha"].default_value = alpha
        m.blend_method = "BLEND"
    return m


def link(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def _finish(obj, mat, bone, smooth=True):
    if mat:
        obj.data.materials.append(mat)
    if smooth:
        for p in obj.data.polygons:
            p.use_smooth = True
    if bone:
        vg = obj.vertex_groups.new(name=bone)
        vg.add(list(range(len(obj.data.vertices))), 1.0, "REPLACE")
    return obj


def sphere(name, loc, r, scale=(1.0, 1.0, 1.0), mat=None, bone=None, seg=32, ring=16, rot=None):
    # SIEMPRE en el origen: hornear escala+rotación ANTES de posicionar
    # (transform_apply con defaults aplicaría también la LOCATION y las
    #  piezas rotadas saldrían volando alrededor del origen del mundo)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=ring, radius=r,
                                         location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    if scale != (1.0, 1.0, 1.0):
        o.scale = scale
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = loc
    return _finish(o, mat, bone)


def capsule(name, a, b, r, mat=None, bone=None, seg=24):
    """Cápsula entre dos puntos (a→b), horneando TODO antes de posicionar."""
    d = Vector(b) - Vector(a)
    L = d.length
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=12, radius=r,
                                         location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    o.scale = (1.0, 1.0, 1.0 + L / (2 * r))
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    q = Vector((0, 0, 1)).rotation_difference(d)
    o.rotation_euler = q.to_euler()
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = ((Vector(a) + Vector(b)) / 2.0).to_tuple()
    return _finish(o, mat, bone)


def cyl(name, loc, r1, r2, depth, mat=None, bone=None, rot=None, seg=24):
    # Blender 4.x: cylinder_add solo acepta "radius" (sin taper)
    bpy.ops.mesh.primitive_cylinder_add(vertices=seg, radius=max(r1, r2),
                                        depth=depth, location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = loc
    return _finish(o, mat, bone)


def cone(name, loc, r, depth, mat=None, bone=None, rot=None, seg=24):
    bpy.ops.mesh.primitive_cone_add(vertices=seg, radius1=r, radius2=0.0,
                                    depth=depth, location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = loc
    return _finish(o, mat, bone)


def torus(name, loc, major, minor, mat=None, bone=None, rot=None, seg=48, rseg=12, scale=(1.0, 1.0, 1.0)):
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=minor,
                                     major_segments=seg, minor_segments=rseg,
                                     location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    if scale != (1.0, 1.0, 1.0):
        o.scale = scale
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = loc
    return _finish(o, mat, bone)


def cube(name, loc, size, mat=None, bone=None, rot=None, scale=(1.0, 1.0, 1.0)):
    bpy.ops.mesh.primitive_cube_add(size=size, location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    if scale != (1.0, 1.0, 1.0):
        o.scale = scale
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
    o.location = loc
    return _finish(o, mat, bone)


def lathe(name, profile_pts, mat=None, bone=None, steps=40):
    """Revolución de un perfil [(r, z), ...] alrededor de Z — faldas, etc."""
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    verts = [bm.verts.new((r, 0.0, z)) for (r, z) in profile_pts]
    for i in range(len(verts) - 1):
        bm.edges.new((verts[i], verts[i + 1]))
    geom = bm.verts[:] + bm.edges[:]
    # revolución alrededor de Z (el perfil vive en el plano YZ como (r, 0, z))
    bmesh.ops.spin(bm, geom=geom, axis=(0, 0, 1), cent=(0, 0, 0),
                   steps=steps, angle=math.radians(360.0))
    bm.to_mesh(mesh)
    bm.free()
    o = bpy.data.objects.new(name, mesh)
    link(o)
    return _finish(o, mat, bone)


def ellipsoid_surface(center, scale, dir_vec, offset=0.0):
    """Punto sobre (o cerca de) la superficie de un elipsoide en una dirección."""
    d = Vector(dir_vec).normalized()
    sc = Vector(scale)
    k = math.sqrt((d.x / sc.x) ** 2 + (d.y / sc.y) ** 2 + (d.z / sc.z) ** 2)
    return Vector(center) + d * (1.0 / k + offset)


# ─────────────────────────── el cuerpo por familia ─────────────────────────────

def build_armature():
    arm = bpy.data.armatures.new("Rig")
    rig = bpy.data.objects.new("Rig", arm)
    link(rig)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    eb = arm.edit_bones

    def bone(name, head, tail, parent=None):
        b = eb.new(name)
        b.head = Vector(head)
        b.tail = Vector(tail)
        if parent:
            b.parent = eb[parent]
            b.use_connect = False
        return b

    bone("root", (0, 0, 0.02), (0, 0, 0.10))
    bone("spine", (0, 0, 0.16), (0, 0, 0.56), "root")
    bone("head", (0, 0, 0.56), (0, 0.02, 0.88), "spine")
    # orejas: huesos cortos desde el borde de la cabeza hacia afuera
    bone("ear.L", (0.245, 0.02, 1.03), (0.335, 0.02, 1.085), "head")
    bone("ear.R", (-0.245, 0.02, 1.03), (-0.335, 0.02, 1.085), "head")
    # brazos: hombro -> codo (la pata cuelga)
    bone("arm.L", (0.185, 0.0, 0.575), (0.315, 0.02, 0.40), "spine")
    bone("arm.R", (-0.185, 0.0, 0.575), (-0.315, 0.02, 0.40), "spine")
    # pies: casi estáticos (solo para reaccionar)
    bone("foot.L", (0.115, 0.0, 0.16), (0.115, 0.09, 0.05), "root")
    bone("foot.R", (-0.115, 0.0, 0.16), (-0.115, 0.09, 0.05), "root")

    bpy.ops.object.mode_set(mode="OBJECT")
    return rig


def armaturize(rig, objs):
    """Modificador Armature a cada malla (las prendas incluidas)."""
    for o in objs:
        m = o.modifiers.new("Arm", "ARMATURE")
        m.object = rig


def turn_around():
    """(D102) Gira TODO el personaje 180° en Z antes de exportar.

    El cuerpo se construye mirando a +Y, pero la convención de Blender (y por
    herencia la de glTF, que el exportador produce con +Z = frente) es mirar a
    -Y: sin este giro el GLB sale mirando a -Z y la cámara por defecto de
    SceneView (en +Z) ve la ESPALDA — el bug del editor 3D de v0.12.0.
    Se hace con un Empty padre (keep-transform), así las rotaciones locales de
    orejas/hocico y las animaciones NLA no se tocan.
    """
    turn = bpy.data.objects.new("Turn", None)
    link(turn)
    tops = [o for o in bpy.data.objects if o.parent is None and o != turn]
    for o in tops:            # turn está en identidad: local == world → sin salto
        o.parent = turn
        o.matrix_local = o.matrix_world.copy()
    turn.rotation_euler = Euler((0.0, 0.0, math.pi), "XYZ")
    bpy.context.view_layer.update()


def build_body(fam, mats):
    """Cuerpo chibi. Devuelve (lista de objetos, dict anclas de familia)."""
    objs = []
    hc = Vector(A["head_c"]); hr = A["head_r"]
    tc = Vector(A["torso_c"]); ts = Vector(A["torso_s"])
    m_body, m_earin = mats["body"], mats["ear_in"]

    # torso pera + cadera (dos elipsoides solapados = silueta suave)
    objs.append(sphere("Body", tc, 1.0, scale=ts, mat=m_body, bone="spine"))
    objs.append(sphere("Hips", (0, 0, 0.20), 1.0, scale=(ts.x * 0.94, ts.y * 0.94, 0.16),
                       mat=m_body, bone="spine"))
    # cabeza
    objs.append(sphere("Head", hc, hr, mat=m_body, bone="head"))

    if fam == "pig":
        # orejas caídas: elipsoides inclinados a los lados-arriba
        for sx, nm in ((1, "EarL"), (-1, "EarR")):
            e = sphere(nm, (sx * 0.225, 0.0, 1.06), 0.105,
                       scale=(0.62, 0.30, 1.0), mat=m_body, bone="ear.L" if sx > 0 else "ear.R",
                       rot=(math.radians(24), 0, math.radians(-sx * 16)))
        # hocico (bien fuera, como la nariz del koala)
        spos = ellipsoid_surface(hc, (hr, hr, hr), (0, 0.90, -0.06), 0.010)
        objs.append(sphere("Snout", spos, 1.0,
                           scale=(0.085, 0.058, 0.065), mat=mats["snout"], bone="head"))
        for sx in (1, -1):
            sp = Vector(spos) + Vector((sx * 0.032, 0.052, 0.0))
            objs.append(sphere("Nostril" + ("L" if sx > 0 else "R"), sp, 0.012,
                               mat=mats["ink"], bone="head"))
        # colita: espiral aplastada (toro parcial no—toro pequeño pegado al cuerpo)
        objs.append(torus("Tail", (0, -ts.y * 0.92, 0.34), 0.035, 0.013,
                          mat=m_body, bone="spine", rot=(math.radians(90), 0, 0)))
    else:
        # orejas koala: GRANDES, arriba a los lados + disco interior
        for sx, bn, nm in ((1, "ear.L", "EarL"), (-1, "ear.R", "EarR")):
            objs.append(sphere(nm, (sx * 0.255, 0.01, 1.055), 0.125,
                               scale=(0.92, 0.55, 1.0), mat=m_body, bone=bn))
            objs.append(sphere(nm + "In", (sx * 0.262, 0.062, 1.05), 0.062,
                               scale=(0.95, 0.42, 0.95), mat=m_earin, bone=bn, seg=24, ring=12))
        # nariz grande vertical (bien AFUERA de la cabeza)
        npos = ellipsoid_surface(hc, (hr, hr, hr), (0, 0.94, 0.10), 0.012)
        objs.append(sphere("Nose", npos, 1.0,
                           scale=(0.062, 0.058, 0.096), mat=mats["ink"], bone="head"))

    # ojos: esferas oscuras SOBRE la superficie de la cara
    for sx in (1, -1):
        p = ellipsoid_surface(hc, (hr, hr, hr), (sx * 0.42, 0.95, 0.12), -0.004)
        objs.append(sphere("Eye" + ("L" if sx > 0 else "R"), p,
                           A["eye_r"] + 0.003, scale=(1.0, 0.62, 1.25), mat=mats["ink"], bone="head"))
    # rubor: discos empotrados mirando hacia afuera (rotación pasada al helper)
    for sx in (1, -1):
        n = (Vector((sx * 0.66, 0.50, -0.42))).normalized()
        p = ellipsoid_surface(hc, (hr, hr, hr), n, -0.006)
        objs.append(sphere("Blush" + ("L" if sx > 0 else "R"), p, 0.052,
                           scale=(1.0, 0.35, 0.78), mat=mats["blush"], bone="head",
                           seg=20, ring=10, rot=n.to_track_quat("Y", "X").to_euler()))
    # sonrisa: elipsoide plano sobre la superficie, bajo el hocico/nariz
    mpos = ellipsoid_surface(hc, (hr, hr, hr), (0, 0.93, -0.34), -0.004)
    objs.append(sphere("Mouth", mpos, 0.032,
                       scale=(1.25, 0.30, 0.42), mat=mats["ink"], bone="head", seg=20, ring=10))

    # brazos (cápsulas hombro->pata) + patas
    for sx, bn, nm, pn in ((1, "arm.L", "ArmL", "PawL"), (-1, "arm.R", "ArmR", "PawR")):
        objs.append(capsule(nm, (sx * A["arm_head"][0], A["arm_head"][1], A["arm_head"][2]),
                            (sx * A["arm_tail"][0], A["arm_tail"][1], A["arm_tail"][2]),
                            A["arm_r"], mat=m_body, bone=bn))
        objs.append(sphere(pn, (sx * A["arm_tail"][0], A["arm_tail"][1] + 0.01, A["arm_tail"][2]),
                           A["paw_r"] if fam == "koala" else A["paw_r"] * 0.92,
                           mat=m_body, bone=bn))

    # pies
    for sx, bn, nm in ((1, "foot.L", "FootL"), (-1, "foot.R", "FootR")):
        objs.append(sphere(nm, (sx * A["feet_c"][0], A["feet_c"][1], A["feet_c"][2]),
                           1.0, scale=A["feet_s"], mat=m_body, bone=bn))

    return objs


def family_anchors(fam):
    """Anclas de prendas por familia (medidas fijas contra el cuerpo de arriba)."""
    hc = Vector(A["head_c"]); hr = A["head_r"]
    tc = Vector(A["torso_c"]); ts = Vector(A["torso_s"])
    base = {
        "hc": hc, "hr": hr, "tc": tc, "ts": ts,
        # superficie frontal del torso a la altura del pecho (para corazón)
        "chest": ellipsoid_surface(tc, ts, (0, 1, 0.05), 0.0),  # noqa: F841 (Vector)
        # hombro: punto de anclaje de mangas
        "shoulder": Vector((A["arm_head"][0], A["arm_head"][1], A["arm_head"][2])),
        "arm_tail": Vector((A["arm_tail"][0], A["arm_tail"][1], A["arm_tail"][2])),
        "eye_y": hc.y + hr * 0.74, "eye_z": A["eye_z"], "eye_x": A["eye_x"],
        "eye_pos": {sx: ellipsoid_surface(hc, (hr, hr, hr), (sx * 0.42, 0.95, 0.12), 0.0)
                     for sx in (1, -1)},
        "head_top": Vector((0, hc.y * 0.35, hc.z + hr * 0.97)),
        "ear_out": Vector((0.30, 0.03, 0.97)),
        "feet": [Vector((A["feet_c"][0], A["feet_c"][1], A["feet_c"][2])),
                 Vector((-A["feet_c"][0], A["feet_c"][1], A["feet_c"][2]))],
        "neck_z": 0.60,
    }
    if fam == "pig":
        base["hat_pos"] = Vector((0, 0.02, 1.14))       # entre orejas caídas
        base["ear_out"] = Vector((0.27, 0.02, 1.10))
    else:
        base["hat_pos"] = Vector((0, 0.0, 1.20))        # sobre orejas grandes
        base["ear_out"] = Vector((0.33, 0.05, 1.02))
    return base


# ─────────────────────────── la ropa (mallas sobre el rig) ─────────────────────

def wear_empty(gid):
    """Empty que agrupa la prenda: la app oculta/muestra por este nombre."""
    e = bpy.data.objects.new(gid, None)
    link(e)
    return e


def parent_to(objs, parent):
    for o in objs:
        o.parent = parent


def ellip_profile(cz, rz, scale_x, z_lo, z_hi, grow=1.0, steps=13):
    """Perfil (r, z) de un elipsoide XZ entre z_lo y z_hi (cerrado arriba)."""
    pts = []
    for i in range(steps):
        z = z_lo + (z_hi - z_lo) * i / (steps - 1)
        f = (z - cz) / rz
        f = max(-1.0, min(1.0, f))
        pts.append((max(0.002, scale_x * math.sqrt(1 - f * f) * grow), z))
    pts.append((0.002, z_hi))
    return pts


def cyl_between(name, a, b, r, mat=None, bone=None, seg=20):
    d = Vector(b) - Vector(a)
    rot = Vector((0, 0, 1)).rotation_difference(d).to_euler()
    return cyl(name, ((Vector(a) + Vector(b)) / 2).to_tuple(), r, r, d.length,
               mat=mat, bone=bone, rot=rot, seg=seg)


def build_tee(gid, color, fam, anc, dots=False, stripes=False, base_color=None):
    """Camiseta: casquete elipsoidal (lathe) + dobladillo + mangas en los brazos."""
    tc, ts = anc["tc"], anc["ts"]
    m = make_mat("M_" + gid, base_color or color)
    objs = []
    shell = lathe("tee_shell_" + gid, ellip_profile(tc.z, ts.z, ts.x, 0.305, 0.70, 1.135),
                  mat=m, bone="spine")
    shell.scale = (1.0, ts.y / ts.x, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    objs.append(shell)
    # dobladillo que tapa el borde abierto inferior
    hem_r = ts.x * 1.135 * math.sqrt(1 - ((0.305 - tc.z) / ts.z) ** 2)
    objs.append(torus("tee_hem_" + gid, (0, 0, 0.307), hem_r, 0.020, mat=m, bone="spine",
                      scale=(1.0, ts.y / ts.x, 1.35)))
    if dots:
        md = make_mat("M_dotsdot", TEE_PINK)
        n, ga = 26, math.pi * (3 - math.sqrt(5))
        for i in range(n):
            y = 1 - 2 * i / (n - 1)
            rad = math.sqrt(max(0, 1 - y * y))
            th = ga * i
            d = Vector((math.cos(th) * rad, math.sin(th) * rad * 0.9 + 0.25, y * 0.9)).normalized()
            p = ellipsoid_surface(tc, (ts.x * 1.135, ts.y * 1.135, ts.z * 1.135), d, 0.008)
            if p.z > 0.315 and p.z < 0.66:
                objs.append(sphere("dot%d" % i, p, 0.021, mat=md, bone="spine", seg=12, ring=8))
    if stripes:
        mb = make_mat("M_band", STR_BAND)
        for z in (0.335, 0.425, 0.515):
            f = (z - tc.z) / ts.z
            rr = math.sqrt(max(0.0, 1 - f * f)) * 1.135
            objs.append(torus("band%d" % int(z * 100), (0, 0, z), 0.10, 0.026, mat=mb,
                              bone="spine", scale=(ts.x * rr / 0.10, ts.y * rr / 0.10, 1.15)))
    # mangas unidas a los huesos de los brazos (giran con el saludo)
    for sx, bn in ((1, "arm.L"), (-1, "arm.R")):
        a = Vector((sx * anc["shoulder"].x, anc["shoulder"].y, anc["shoulder"].z))
        d = (Vector((sx * anc["arm_tail"].x, anc["arm_tail"].y, anc["arm_tail"].z)) - a).normalized()
        mid = a + d * 0.095
        objs.append(cyl_between("sleeve_" + bn, a + d * 0.01, mid + d * 0.075, 0.098,
                                mat=m, bone=bn))
    return wear(gid, objs)


def wear(gid, objs):
    e = wear_empty(gid)
    parent_to(objs, e)
    return e


def build_dress(fam, anc):
    """Vestido de la cerdita: canesú + falda acampanada (lathe) + cinturón + mangas globo."""
    tc, ts = anc["tc"], anc["ts"]
    m = make_mat("M_dress", DRESS_C)
    mb = make_mat("M_belt", BELT_C)
    objs = []
    bod = lathe("dress_bod", ellip_profile(tc.z + 0.10, ts.z * 0.68, ts.x, 0.435, 0.66, 1.10),
                mat=m, bone="spine")
    bod.scale = (1.0, ts.y / ts.x, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    objs.append(bod)
    skirt = lathe("dress_skirt",
                  [(ts.x * 1.02, 0.455), (ts.x * 1.10, 0.40), (ts.x * 1.24, 0.33),
                   (ts.x * 1.38, 0.26), (ts.x * 1.47, 0.20), (ts.x * 1.50, 0.16),
                   (ts.x * 1.47, 0.155)], mat=m, bone="spine")
    skirt.scale = (1.0, ts.y / ts.x, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    objs.append(skirt)
    objs.append(torus("dress_belt", (0, 0, 0.465), ts.x * 1.10, 0.024, mat=mb, bone="spine",
                      scale=(1.0, ts.y / ts.x, 1.2)))
    # lazo del cinturón
    objs.append(sphere("belt_knot", (0, ts.y * 1.12, 0.465), 0.030, mat=mb, bone="spine", seg=16, ring=10))
    for sx in (1, -1):
        objs.append(cone("belt_ribbon%d" % sx, (sx * 0.055, ts.y * 1.13, 0.465), 0.030, 0.062,
                         mat=mb, bone="spine", rot=(0, 0, math.radians(90 if sx > 0 else -90)), seg=16))
    # mangas globo
    for sx, bn in ((1, "arm.L"), (-1, "arm.R")):
        a = Vector((sx * anc["shoulder"].x, anc["shoulder"].y, anc["shoulder"].z))
        d = (Vector((sx * anc["arm_tail"].x, anc["arm_tail"].y, anc["arm_tail"].z)) - a).normalized()
        p = a + d * 0.055
        objs.append(sphere("puff_" + bn, p, 0.098, scale=(1.0, 1.0, 1.15), mat=m, bone=bn, seg=20, ring=12))
    return wear("dress", objs)


def build_suit(fam, anc):
    """Traje del koala: chaqueta columna (lathe) + camisa V + solapas + pajarita + botones + mangas."""
    tc, ts = anc["tc"], anc["ts"]
    mj = make_mat("M_jacket", NAVY)
    mw = make_mat("M_shirtw", SHIRT_W)
    objs = []
    jack = lathe("suit_jack",
                 [(ts.x * 1.16, 0.315), (ts.x * 1.20, 0.38), (ts.x * 1.22, 0.47),
                  (ts.x * 1.20, 0.55), (ts.x * 1.13, 0.615), (ts.x * 0.95, 0.665),
                  (ts.x * 0.55, 0.70)], mat=mj, bone="spine")
    jack.scale = (1.0, ts.y / ts.x, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    objs.append(jack)
    front_y = ts.y * 1.225
    # camisa en V: panel blanco + dos prismas que la enmarcan
    objs.append(cube("suit_shirt", (0, front_y, 0.56), 1.0, mat=mw, bone="spine",
                     rot=(0, 0, math.radians(45)), scale=(0.115, 0.014, 0.115)))
    for sx in (1, -1):
        objs.append(cube("lapel%d" % sx, (sx * 0.082, front_y + 0.004, 0.585), 1.0, mat=mj,
                         bone="spine", rot=(0.42, 0, math.radians(-sx * 0.55)),
                         scale=(0.085, 0.012, 0.10)))
    # pajarita coral con nudo
    objs.append(cone("bt_L", (-0.045, front_y + 0.012, 0.632), 0.036, 0.062, mat=make_mat("M_bt", CORAL),
                     bone="spine", rot=(0, 0, math.radians(-90)), seg=16))
    objs.append(cone("bt_R", (0.045, front_y + 0.012, 0.632), 0.036, 0.062, mat=mj,
                     bone="spine", rot=(0, 0, math.radians(90)), seg=16))
    objs[-1].data.materials[0] = bpy.data.materials["M_bt"]
    objs.append(sphere("bt_knot", (0, front_y + 0.014, 0.632), 0.024, mat=bpy.data.materials["M_bt"],
                       bone="spine", seg=16, ring=10))
    for z in (0.50, 0.415):
        objs.append(sphere("btn%.0f" % (z * 100), (0, front_y + 0.006, z), 0.017,
                           mat=mw, bone="spine", seg=14, ring=8))
    # mangas largas hasta la pata
    for sx, bn in ((1, "arm.L"), (-1, "arm.R")):
        a = Vector((sx * anc["shoulder"].x, anc["shoulder"].y, anc["shoulder"].z))
        b = Vector((sx * anc["arm_tail"].x, anc["arm_tail"].y, anc["arm_tail"].z))
        objs.append(cyl_between("slv_" + bn, a, b + (b - a).normalized() * 0.06, 0.088, mat=mj, bone=bn))
    return wear("suit", objs)


def build_shoes(gid, color, fam, anc):
    m = make_mat("M_" + gid, color)
    ms = make_mat("M_sole", SHIRT_W)
    objs = []
    for sx, bn in ((1, "foot.L"), (-1, "foot.R")):
        fx, fy, fz = sx * anc["feet"][0].x, anc["feet"][0].y, anc["feet"][0].z
        if fam == "pig":  # botitas: caña + pie bulbo
            objs.append(cyl("cuff_" + bn, (fx, fy - 0.012, fz + 0.085), 0.062, 0.058, 0.075,
                            mat=m, bone=bn))
            objs.append(sphere("boot_" + bn, (fx, fy + 0.014, fz - 0.010), 1.0,
                               scale=(0.098, 0.168, 0.080), mat=m, bone=bn))
            objs.append(torus("bootrim_" + bn, (fx, fy - 0.012, fz + 0.122), 0.061, 0.012,
                              mat=ms, bone=bn, scale=(1.0, 1.0, 1.2)))
        else:  # sneakers: caja redondeada + suela blanca + puntera
            objs.append(cube("sneak_" + bn, (fx, fy + 0.012, fz + 0.006), 1.0, mat=m, bone=bn,
                             scale=(0.096, 0.170, 0.070)))
            objs.append(cube("sole_" + bn, (fx, fy + 0.012, 0.024), 1.0, mat=ms, bone=bn,
                             scale=(0.102, 0.180, 0.028)))
            objs.append(sphere("toe_" + bn, (fx, fy + 0.115, 0.035), 1.0,
                               scale=(0.070, 0.060, 0.042), mat=ms, bone=bn, seg=16, ring=10))
    return wear(gid, objs)


def build_glasses(fam, anc):
    m = make_mat("M_glasses", INK, rough=0.35)
    ml = make_mat("M_lens", (0.35, 0.42, 0.50), alpha=0.42)
    objs = []
    for sx in (1, -1):
        ep = Vector(anc["eye_pos"][sx])
        fwd = (Vector(ep) - anc["hc"]).normalized()
        c = Vector(ep) + fwd * 0.052
        objs.append(torus("rim%d" % sx, c, 0.058, 0.011, mat=m, bone="head",
                          rot=(math.radians(90), 0, 0)))
        objs.append(cyl("lens%d" % sx, c - fwd * 0.006, 0.052, 0.052, 0.006, mat=ml,
                        bone="head", rot=(math.radians(90), 0, 0)))
        objs.append(cyl_between("temple%d" % sx, c + Vector((sx * 0.056, 0, 0.006)),
                                Vector((sx * anc["ear_out"].x * 0.94, anc["ear_out"].y * 0.3,
                                        anc["eye_z"] + 0.01)), 0.008, mat=m, bone="head"))
    # puente entre las dos lentes
    bl = Vector(anc["eye_pos"][1]) + Vector(anc["eye_pos"][-1])
    bl = bl / 2 + (Vector(anc["eye_pos"][1]) - anc["hc"]).normalized() * 0.075
    objs.append(cyl("gbridge", bl, 0.014, 0.014, 0.058, mat=m, bone="head",
                    rot=(0, 0, math.radians(90))))
    return wear("glasses", objs)


def build_bow(fam, anc):
    m = make_mat("M_bow", CORAL)
    # (D102) moño VISIBLE: sobre la superficie superior-lateral de la cabeza con
    # los conos inclinados ±42° hacia afuera. Antes estaba a z=1.12 bajo el
    # eje… pero con la cabeza r=0.30 en z .86 los conos (x ±0.106) quedaban
    # DENTRO de la esfera → invisible desde cualquier ángulo (nunca entró al QA).
    p = Vector((0.155, 0.055, 1.12))
    objs = [cone("bowL", (p.x - 0.052, p.y, p.z + 0.018), 0.046, 0.095, mat=m, bone="head",
                 rot=(0, math.radians(-42), 0), seg=18),
            cone("bowR", (p.x + 0.052, p.y, p.z + 0.018), 0.046, 0.095, mat=m, bone="head",
                 rot=(0, math.radians(42), 0), seg=18),
            sphere("bowknot", p, 0.032, mat=m, bone="head", seg=16, ring=10)]
    return wear("bow", objs)


def build_flower(fam, anc):
    mp = make_mat("M_petal", (0xFB/255, 0x96/255, 0xA5/255))
    mc = make_mat("M_fcenter", GOLD)
    # (D102) la flor MIRA AL FRENTE: antes el disco de pétalos quedaba
    # perpendicular a la dirección cabeza→oreja (de canto para la cámara) y
    # medio escondida detrás de la oreja caída. Ahora se adelanta y encara.
    pos = Vector(anc["ear_out"]) + Vector((0.0, 0.085, -0.03))
    d = Vector((0.25, 0.95, 0.18)).normalized()
    u = d.cross(Vector((0, 0, 1))).normalized()
    v = d.cross(u).normalized()
    objs = [sphere("fc", pos + d * 0.012, 0.027, mat=mc, bone="head", seg=14, ring=10)]
    for i in range(5):
        th = math.radians(90 + i * 72)
        pp = pos + (u * math.cos(th) + v * math.sin(th)) * 0.052 + d * 0.008
        objs.append(sphere("fp%d" % i, pp, 0.037, scale=(1.0, 1.0, 0.52), mat=mp,
                           bone="head", seg=14, ring=10,
                           rot=d.to_track_quat("Z", "Y").to_euler()))
    return wear("flower", objs)


def build_party(fam, anc):
    m = make_mat("M_party", PARTY_C)
    mp = make_mat("M_pompom", SHIRT_W)
    base = anc["hat_pos"] + Vector((0, 0.035, 0.09))
    objs = [cone("hat", base, 0.105, 0.235, mat=m, bone="head", rot=(math.radians(-9), 0, 0)),
            sphere("pompom", base + Vector((0, 0.033, 0.145)), 0.040, mat=mp, bone="head", seg=16, ring=10)]
    return wear("party", objs)


def build_cap(fam, anc):
    m = make_mat("M_cap", CAP_C)
    objs = [
        sphere("cap_crown", (0, 0.01, anc["hc"].z + 0.145), anc["hr"] * 0.97,
               scale=(1.0, 1.0, 0.74), mat=m, bone="head"),
        cube("cap_brim", (0, anc["hc"].y + anc["hr"] * 0.72, anc["hc"].z + 0.135), 1.0, mat=m,
             bone="head", rot=(math.radians(8), 0, 0), scale=(0.145, 0.135, 0.020)),
        sphere("cap_btn", (0, 0.01, anc["hc"].z + anc["hr"] * 0.60), 0.022, mat=m, bone="head",
               seg=12, ring=8),
    ]
    return wear("cap", objs)


def build_earrings(fam, anc):
    m = make_mat("M_gold", GOLD, rough=0.3, metallic=0.35)
    objs = []
    # (D102) anillos FUERA de la cabeza: la superficie a z≈.89 llega a x≈0.30,
    # los aros colgaban a x .262/.298 (mayormente dentro). Ahora cuelgan al
    # costado, bajo la oreja, y de frente se ven de lleno (toro con eje Y).
    x = 0.322 if fam == "pig" else 0.345
    for sx in (1, -1):
        objs.append(torus("ring%d" % sx, (sx * x, 0.05, 0.885), 0.030, 0.010, mat=m,
                          bone="head", rot=(math.radians(90), 0, 0)))
    return wear("earrings", objs)


def build_heart(fam, anc):
    m = make_mat("M_heart", CORAL, rough=0.4)
    p = anc["chest"]
    objs = [
        sphere("hl", (p.x - 0.030, p.y, p.z + 0.022), 0.042, mat=m, bone="spine", seg=16, ring=10),
        sphere("hr", (p.x + 0.030, p.y, p.z + 0.022), 0.042, mat=m, bone="spine", seg=16, ring=10),
        cone("hb", (p.x, p.y, p.z - 0.028), 0.069, 0.088, mat=m, bone="spine",
             rot=(math.radians(180), 0, 0), seg=18),
    ]
    return wear("heart", objs)


def build_scarf(fam, anc):
    m = make_mat("M_scarf", SCARF_C)
    objs = [
        torus("scarf_ring", (0, 0, 0.615), 0.198, 0.052, mat=m, bone="spine",
              scale=(1.0, 0.94, 1.0)),
        cube("scarf_tail", (0.058, anc["ts"].y * 1.02, 0.50), 1.0, mat=m, bone="spine",
             rot=(0, 0, math.radians(6)), scale=(0.052, 0.016, 0.115)),
        sphere("scarf_knot", (0.058, anc["ts"].y * 1.02, 0.585), 0.038, mat=m, bone="spine", seg=16, ring=10),
    ]
    return wear("scarf", objs)


def build_all_wear(fam, anc):
    build_tee("tee_pink", TEE_PINK, fam, anc)
    build_tee("tee_violet", TEE_VIOL, fam, anc)
    build_tee("tee_teal", TEAL, fam, anc)
    build_tee("dots", DOTS_BG, fam, anc, dots=True)
    build_tee("stripes", STR_BASE, fam, anc, stripes=True)
    if fam == "pig":
        build_dress(fam, anc)
    else:
        build_suit(fam, anc)
    build_shoes("shoes_red", RED_SHOE, fam, anc)
    build_shoes("shoes_blue", BLU_SHOE, fam, anc)
    build_glasses(fam, anc)
    build_bow(fam, anc)
    build_flower(fam, anc)
    build_party(fam, anc)
    build_cap(fam, anc)
    build_earrings(fam, anc)
    build_heart(fam, anc)
    build_scarf(fam, anc)


# ─────────────────────────── animaciones (3 clips) ─────────────────────────────

def reset_pose(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "XYZ"
        pb.rotation_euler = (0.0, 0.0, 0.0)
        pb.scale = (1.0, 1.0, 1.0)
        pb.location = (0.0, 0.0, 0.0)
    # OJO: aquí NO se toca animation_data (borraría los tracks NLA stashheados)


def key(pb, path, f):
    pb.keyframe_insert(data_path=path, frame=f)


def act_idle(rig):
    reset_pose(rig)
    rig.animation_data_create()
    a = bpy.data.actions.new("idle")
    rig.animation_data.action = a
    sp = rig.pose.bones["spine"]; hd = rig.pose.bones["head"]
    for f, k in ((1, 0.0), (49, 1.0), (97, 0.0)):
        s = 1.0 + 0.03 * math.sin(math.pi * k)
        sp.scale = (1.0 + 0.012 * math.sin(math.pi * k), 1.0 + 0.012 * math.sin(math.pi * k), s)
        key(sp, "scale", f)
        hd.rotation_euler = (math.radians(2.2 * math.sin(math.pi * k)), 0, 0)
        key(hd, "rotation_euler", f)
        for e in ("ear.L", "ear.R"):
            pb = rig.pose.bones[e]
            pb.rotation_euler = (0, 0, math.radians(3.5 * math.sin(math.pi * k)))
            key(pb, "rotation_euler", f)
    return a


def act_wave(rig, axis="Z", lift=1.95):
    reset_pose(rig)
    rig.animation_data_create()
    a = bpy.data.actions.new("wave")
    rig.animation_data.action = a
    arm = rig.pose.bones["arm.R"]   # derecha del personaje = izquierda en pantalla
    hd = rig.pose.bones["head"]
    seq = [
        (1,   0.0, 0.0),
        (9,   lift, 5.0),
        (17,  lift * 0.86, -4.0),
        (25,  lift * 1.08, 5.0),
        (33,  lift * 0.86, -4.0),
        (41,  lift, 3.0),
        (62,  0.0, 0.0),
    ]
    for f, ang, tilt in seq:
        v = (0, 0, ang) if axis == "Z" else (0, ang, 0)
        arm.rotation_euler = v
        key(arm, "rotation_euler", f)
        hd.rotation_euler = (0, 0, math.radians(tilt * 0.6))
        key(hd, "rotation_euler", f)
    return a


def act_react(rig):
    reset_pose(rig)
    rig.animation_data_create()
    a = bpy.data.actions.new("react")
    rig.animation_data.action = a
    sp = rig.pose.bones["spine"]
    seq = [(1, 1.0, 1.0), (5, 1.07, 0.88), (12, 0.965, 1.05), (18, 1.02, 0.985), (26, 1.0, 1.0)]
    for f, sxz, sz in seq:
        sp.scale = (sxz, sxz, sz)
        key(sp, "scale", f)
    for e in ("ear.L", "ear.R"):
        pb = rig.pose.bones[e]
        for f, r in ((1, 0), (6, 8), (13, -5), (20, 3), (26, 0)):
            pb.rotation_euler = (0, 0, math.radians(r))
            key(pb, "rotation_euler", f)
    return a


def stash_actions(rig, actions):
    rig.animation_data_create()
    ad = rig.animation_data
    for a in actions:
        t = ad.nla_tracks.new()
        t.name = a.name
        t.strips.new(a.name, 1, a)
        t.mute = True   # congelado mientras se construye
    ad.action = None
    reset_pose(rig)


def unmute_for_export(rig):
    """El exportador glTF se salta los tracks muteados: desmutear ANTES de exportar
    (frame 1 de cada action = pose rest, así que la pose evaluada queda limpia)."""
    ad = rig.animation_data
    if ad:
        for t in ad.nla_tracks:
            t.mute = False
    bpy.context.scene.frame_set(1)
    bpy.context.view_layer.update()


# ─────────────────────────── QA: renders Eevee ─────────────────────────────────

def setup_render(dirpath, w=680, h=820):
    sc = bpy.context.scene
    # sandbox sin GPU: Eevee exige EGL -> Cycles CPU (lento pero fiel)
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = 96
    sc.cycles.use_denoising = True
    sc.render.resolution_x = w
    sc.render.resolution_y = h
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.view_settings.view_transform = "Standard"
    # luz: sol cálida desde el frente-izquierda + relleno desde frente-derecha
    sun = bpy.data.lights.new("sun", "SUN")
    sun.energy = 2.6
    sun.angle = math.radians(10)
    so = bpy.data.objects.new("sun", sun)
    so.rotation_euler = Euler((math.radians(-48), 0, math.radians(28)), "XYZ")
    link(so)
    fill = bpy.data.lights.new("fill", "AREA")
    fill.energy = 70
    fill.size = 4.0
    fo = bpy.data.objects.new("fill", fill)
    fo.location = (2.4, 1.8, 2.2)
    d = -Vector(fo.location).normalized()
    fo.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    link(fo)
    cam = bpy.data.cameras.new("cam")
    cam.lens = 52
    co = bpy.data.objects.new("cam", cam)
    link(co)
    sc.camera = co
    return co


def qa_shot(co, name, outdir, azimuth_deg, elev_deg=8, dist=2.55, target=(0, 0.02, 0.62)):
    # az 0 = FRENTE. Tras turn_around() la cara mira a -Y (frente canónica de
    # Blender): cámara en el lado -Y (D102; antes del giro era el lado +Y).
    az, el = math.radians(azimuth_deg), math.radians(elev_deg)
    co.location = (dist * math.cos(el) * math.sin(az), -dist * math.cos(el) * math.cos(az),
                   target[2] + dist * math.sin(el))
    d = Vector(target) - co.location
    co.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.render.filepath = os.path.join(outdir, name + ".png")
    bpy.ops.render.render(write_still=True)


def _hide_tree(obj, hide):
    obj.hide_render = hide
    obj.hide_viewport = hide
    for ch in obj.children:
        _hide_tree(ch, hide)


def set_visible(wear_ids, visible_ids):
    """Muestra solo las prendas de visible_ids (empty + TODOS sus hijos)."""
    for gid in wear_ids:
        e = bpy.data.objects.get(gid)
        if e:
            _hide_tree(e, gid not in visible_ids)


def set_pose_frame(rig, action_name, frame):
    """Aplica un frame de un action stasheado para el render QA."""
    a = bpy.data.actions.get(action_name)
    if not a:
        return
    reset_pose(rig)
    rig.animation_data_create()
    rig.animation_data.action = a
    bpy.context.scene.frame_set(frame)
    bpy.context.view_layer.update()


# ─────────────────────────── build por familia + export ────────────────────────

WEAR_IDS = ["tee_pink", "tee_violet", "tee_teal", "dots", "stripes", "dress", "suit",
            "shoes_red", "shoes_blue", "glasses", "bow", "flower", "party", "cap",
            "earrings", "heart", "scarf"]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "android", "app", "src",
                                           "main", "assets", "models"))
QA_DIR = os.path.join(HERE, "qa")


def build_family(fam, colorway_id, wave_axis="Z", wave_lift=1.95):
    """Escena completa de una variante. Devuelve (rig, wear_empties)."""
    clear_scene()
    spec = FAMS[fam]
    cw = spec["colorways"][colorway_id]
    body = cw.get("body", spec["body"])
    ear_in = cw.get("ear_in", spec["ear_in"])
    snout = cw.get("snout", spec.get("snout", spec["body"]))
    mats = {
        "body": make_mat("M_Body", body),
        "ear_in": make_mat("M_EarIn", ear_in),
        "snout": make_mat("M_Snout", snout),
        "ink": make_mat("M_Ink", INK, rough=0.35),
        "blush": make_mat("M_Blush", BLUSH, rough=0.7),
    }
    rig = build_armature()
    objs = build_body(fam, mats)
    anc = family_anchors(fam)
    build_all_wear(fam, anc)
    armaturize(rig, [o for o in bpy.data.objects if o.type == "MESH"])
    actions = [act_idle(rig), act_wave(rig, axis=wave_axis, lift=wave_lift), act_react(rig)]
    stash_actions(rig, actions)
    turn_around()   # (D102) frente canónico: -Y en Blender → +Z en glTF
    return rig, anc


def export_glb(colorway_id, rig=None):
    os.makedirs(OUT_ASSETS, exist_ok=True)
    if rig is not None:
        unmute_for_export(rig)
    fp = os.path.join(OUT_ASSETS, colorway_id + ".glb")
    bpy.ops.export_scene.gltf(
        filepath=fp,
        export_format="GLB",
        export_apply=True,
        export_yup=True,
        export_animations=True,
        export_animation_mode="NLA_TRACKS",
        export_optimize_animation_size=True,
        export_texcoords=False,
        export_normals=True,
        export_materials="EXPORT",
        export_extras=False,
        export_skins=True,
        export_force_sampling=True,
    )
    return fp


def qa_family(fam, rig, anc):
    os.makedirs(QA_DIR, exist_ok=True)
    co = setup_render(QA_DIR)
    sub = os.path.join(QA_DIR, fam)
    os.makedirs(sub, exist_ok=True)
    S = lambda n: qa_shot(co, n, sub, n[0] if False else 0, **({"azimuth_deg": 0}))  # placeholder

    def shot(name, az, el=8, dist=2.55, target=(0, 0.02, 0.62)):
        qa_shot(co, name, sub, az, elev_deg=el, dist=dist, target=target)

    # 1 frente desnudo
    set_visible(WEAR_IDS, [])
    shot("01_front_base", 0)
    # 2 camiseta rosa
    set_visible(WEAR_IDS, ["tee_pink"])
    shot("02_front_tee", 0)
    # 3 vestido / traje
    big = "dress" if fam == "pig" else "suit"
    set_visible(WEAR_IDS, [big])
    shot("03_front_big", 0)
    # 4 gafas + gorro fiesta + zapatos azules
    set_visible(WEAR_IDS, ["glasses", "party", "shoes_blue"])
    shot("04_front_acc", 0)
    # 5 3/4 con turquesa + lunares... solo turquesa + bufanda
    set_visible(WEAR_IDS, ["tee_teal", "scarf"])
    shot("05_threeq_tee", 38)
    # 6 perfil (verificar orejas/nariz/cola)
    set_visible(WEAR_IDS, [])
    shot("06_side_base", 90)
    # 7 saludo (brazo arriba) con camiseta
    set_visible(WEAR_IDS, ["tee_pink"])
    set_pose_frame(rig, "wave", 12)
    shot("07_wave", 18)
    reset_pose(rig)
    # 8 trasera 3/4 (verificar cola del cerdo / espalda de prendas)
    set_visible(WEAR_IDS, [])
    shot("08_back_threeq", 142)
    bpy.context.scene.frame_set(1)
    return sub


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = None
    cws = None   # filtro por colorway (coma) para partir la ejecución en trozos
    do_export = "--no-export" not in argv
    do_qa = "--no-qa" not in argv
    for a in argv:
        if a.startswith("--only="):
            only = a.split("=", 1)[1]
    for a in argv:
        if a.startswith("--cw="):
            cws = set(a.split("=", 1)[1].split(","))
    wave_axis = "Z"
    for a in argv:
        if a.startswith("--wave="):
            wave_axis = a.split("=", 1)[1]

    built = []
    for fam in ("pig", "koala"):
        if only and fam not in only:
            continue
        first_cw = list(FAMS[fam]["colorways"])[0]
        for cw in FAMS[fam]["colorways"]:
            if cws and cw not in cws:
                continue
            print(f"[build] {fam} / {cw} ...", flush=True)
            rig, anc = build_family(fam, cw, wave_axis=wave_axis)
            if do_export:
                fp = export_glb(cw, rig=rig)
                print(f"  -> {fp} ({os.path.getsize(fp)//1024} KB)", flush=True)
            if do_qa and cw == first_cw and (not cws or first_cw in cws):
                sub = qa_family(fam, rig, anc)
                print(f"  QA -> {sub}", flush=True)
            built.append(cw)
    print("[done]", built)


if __name__ == "__main__":
    main()
