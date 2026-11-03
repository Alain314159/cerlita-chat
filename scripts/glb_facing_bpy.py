#!/usr/bin/env python3
"""Importa un GLB en Blender y reporta la dirección 'frente' en coords glTF
(+Z = frente canónica). Usa centros de mallas de ojos/nariz/hocico vs cuerpo."""
import bpy, sys, math
from mathutils import Vector, Matrix

path = sys.argv[-1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=path)

objs = bpy.data.objects

def mesh_center(o):
    cs = [Vector(c) for c in o.bound_box]
    c = Vector((0, 0, 0))
    for x in cs: c += x
    return o.matrix_world @ (c / 8)

names = {o.name.lower(): o for o in objs if o.type == 'MESH'}
front_names = [n for n in names if any(k in n for k in ('eye', 'nose', 'snout', 'nariz', 'hocico'))]
body_names = [n for n in names if any(k in n for k in ('body', 'head', 'torso', 'cuerpo', 'cabeza'))]

# centro global de TODAS las mallas visibles
allc = Vector((0, 0, 0)); n = 0
for o in objs:
    if o.type == 'MESH':
        allc += mesh_center(o); n += 1
if n: allc /= n

Rx = Matrix.Rotation(math.radians(-90), 4, 'X')  # blender→gltf
def to_gltf(v): return Rx @ v

print(f"== {path.split('/')[-1]} ==")
print(f"  centro cuerpo (gltf): {tuple(round(c,3) for c in to_gltf(allc))}")
for fn in sorted(front_names):
    c = to_gltf(mesh_center(names[fn]))
    d = c - to_gltf(allc)
    print(f"  {fn:12s} gltf={tuple(round(x,3) for x in c)} delta={tuple(round(x,3) for x in d)}")
for bn in sorted(body_names)[:4]:
    c = to_gltf(mesh_center(names[bn]))
    print(f"  [body] {bn:10s} gltf={tuple(round(x,3) for x in c)}")
# bbox global en glTF
import functools
mins = Vector((1e9,)*3); maxs = Vector((-1e9,)*3)
for o in objs:
    if o.type == 'MESH':
        for corner in o.bound_box:
            w = to_gltf(o.matrix_world @ Vector(corner))
            mins.x = min(mins.x, w.x); mins.y = min(mins.y, w.y); mins.z = min(mins.z, w.z)
            maxs.x = max(maxs.x, w.x); maxs.y = max(maxs.y, w.y); maxs.z = max(maxs.z, w.z)
print(f"  bbox glTF: min {tuple(round(x,2) for x in mins)}  max {tuple(round(x,2) for x in maxs)}")
