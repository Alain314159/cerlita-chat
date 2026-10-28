#!/usr/bin/env python3
"""Valida los GLB: JSON chunk -> animaciones, nodos-prenda, skins, meshes."""
import json
import struct
import sys
import os

MODELS = "/home/z/my-project/app/android/app/src/main/assets/models"
WEAR = {"tee_pink", "tee_violet", "tee_teal", "dots", "stripes", "dress", "suit",
        "shoes_red", "shoes_blue", "glasses", "bow", "flower", "party", "cap",
        "earrings", "heart", "scarf"}

ok = True
for fn in sorted(os.listdir(MODELS)):
    if not fn.endswith(".glb"):
        continue
    path = os.path.join(MODELS, fn)
    with open(path, "rb") as f:
        magic, ver, length = struct.unpack("<III", f.read(12))
        assert magic == 0x46546C67, "no es GLB"
        clen, ctype = struct.unpack("<II", f.read(8))
        data = json.loads(f.read(clen).decode("utf-8"))
    names = [n.get("name", "?") for n in data.get("nodes", [])]
    wear_nodes = [n for n in names if n in WEAR]
    anims = [a.get("name", "?") for a in data.get("animations", [])]
    skins = len(data.get("skins", []))
    meshes = len(data.get("meshes", []))
    mats = len(data.get("materials", []))
    fam = "pig" if fn.startswith("pig") else "koala"
    expected = WEAR - ({"dress"} if fam == "koala" else {"suit"})
    missing = expected - set(wear_nodes)
    extra = set(wear_nodes) - expected
    stat = "OK" if (not missing and set(anims) == {"idle", "wave", "react"} and skins == 1) else "FALLO"
    if stat == "FALLO":
        ok = False
    print(f"{fn:24s} {os.path.getsize(path)//1024:5d}KB meshes={meshes:3d} mats={mats:2d} "
          f"skins={skins} anims={anims} prendas={len(wear_nodes)}/16 "
          f"{'faltan:'+str(missing) if missing else ''}{' extra:'+str(extra) if extra else ''} -> {stat}")
print("TODO OK" if ok else "HAY FALLOS")
sys.exit(0 if ok else 1)
