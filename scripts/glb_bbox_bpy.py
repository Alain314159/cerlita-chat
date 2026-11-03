#!/usr/bin/env python3
"""Bbox por objeto de un GLB importado (coords glTF) para calibrar cámara."""
import bpy, sys, math
from mathutils import Vector, Matrix

path = sys.argv[-1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=path)

Rx = Matrix.Rotation(math.radians(-90), 4, 'X')
def to_gltf(v): return Rx @ v

print(f"== {path.split('/')[-1]} ==")
for o in bpy.data.objects:
    if o.type != 'MESH':
        continue
    ws = [to_gltf(o.matrix_world @ Vector(c)) for c in o.bound_box]
    mn = Vector((min(w.x for w in ws), min(w.y for w in ws), min(w.z for w in ws)))
    mx = Vector((max(w.x for w in ws), max(w.y for w in ws), max(w.z for w in ws)))
    hidden = "HIDE" if o.hide_render else "    "
    print(f"  {o.name:14s} {hidden} x[{mn.x:+.2f},{mx.x:+.2f}] y[{mn.y:+.2f},{mx.y:+.2f}] z[{mn.z:+.2f},{mx.z:+.2f}]")
