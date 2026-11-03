#!/usr/bin/env python3
"""Determina hacia dónde mira cada GLB: usa la posición de ojos/nariz/hocico
relativa al centro del cuerpo. glTF: +Z = frente canónica."""
import json, struct, sys

def read_glb(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:4] == b'glTF', path
    json_len = struct.unpack('<I', data[12:16])[0]
    return json.loads(data[20:20+json_len]), data

def node_world(gltf, idx, parent=None, out=None):
    return out

def main(path):
    gltf, blob = read_glb(path)
    nodes = gltf['nodes']

    # matriz identidad → acumular TRS simple (suficiente: el rig usa rotaciones livianas)
    import numpy as np
    def mat_of(n):
        if 'matrix' in n:
            m = np.array(n['matrix'], dtype=float).reshape(4, 4)
            return m
        t = n.get('translation', [0, 0, 0]); r = n.get('rotation', [0, 0, 0, 1]); s = n.get('scale', [1, 1, 1])
        x, y, z, w = r
        # quaternion → matriz (column-major glTF → numpy row-major transpuesta)
        q = np.array([w, x, y, z])
        R = quat_to_mat(q)
        M = np.eye(4)
        M[:3, :3] = R * np.array(s)
        M[:3, 3] = t
        return M
    def quat_to_mat(q):
        w, x, y, z = q
        return np.array([
            [1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)],
            [2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)],
            [2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)],
        ])

    scenes = gltf['scenes'][gltf.get('scene', 0)]
    world = {}
    def walk(idx, parent):
        n = nodes[idx]
        M = mat_of(n)
        W = (parent @ M) if parent is not None else M
        world[idx] = W
        for c in n.get('children', []):
            walk(c, W)
    for r in scenes.get('nodes', scenes.get('roots', [])):
        walk(r, None)

    # buscar nodos con nombres de rasgos frontales
    FRONT_HINTS = ('eye', 'eye_l', 'eye_r', 'ojos', 'nose', 'nariz', 'muzzle', 'hocico', 'snout')
    front_pts = []
    all_pts = []
    for i, n in enumerate(nodes):
        if i not in world: continue
        name = (n.get('name') or '').lower()
        p = world[i][:3, 3]
        if i in world and 'mesh' in n:
            all_pts.append((name, p))
        if any(h in name for h in FRONT_HINTS):
            front_pts.append((n.get('name'), p))
    if not all_pts:
        print(f"{path}: sin nodos con mesh"); return
    arr = np.array([p for _, p in all_pts])
    center = arr.mean(axis=0)
    mins, maxs = arr.min(axis=0), arr.max(axis=0)
    print(f"== {path.split('/')[-1]} ==")
    print(f"  centro global: {np.round(center,3)}  bbox min {np.round(mins,2)} max {np.round(maxs,2)}")
    for name, p in front_pts:
        d = p - center
        print(f"  frente '{name}': pos={np.round(p,3)} delta_centro={np.round(d,3)}")

if __name__ == '__main__':
    for p in sys.argv[1:]:
        main(p)
