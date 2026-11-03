#!/usr/bin/env python3
"""Busca la MainDispatcherFactory bajo cualquier nombre (R8 renombra) y
localiza usos de Dispatchers.Main en el dex de la app."""
from androguard.core.apk import APK
from androguard.core.dex import DEX

APK_PATH = "/home/z/my-project/download/cerlita-chat-v0.3.0-release.apk"
a = APK(APK_PATH)

for i, dex_bytes in enumerate(a.get_all_dex()):
    d = DEX(dex_bytes)
    # 1) clases que implementan kotlinx.coroutines.internal.MainDispatcherFactory
    for c in d.get_classes():
        ifs = [c.get_superclassname()] + list(c.get_interfaces())
        if any("MainDispatcherFactory" in (x or "") for x in ifs):
            print(f"dex#{i} IMPLEMENTA MainDispatcherFactory:", c.get_name())
        # la factory real referencia "Main.looper" / "kotlinx.coroutines.android"
        for m in c.get_methods():
            pass
    # 2) strings con pistas
    for s in d.get_strings():
        if "coroutines.android" in s or "Main dispatcher" in s or "Module with the Main" in s:
            print(f"dex#{i} string:", repr(s))

# 3) ¿la app usa Dispatchers.Main? busca campos/method refs en clases cu.alain.chat
print()
print("─── refs a Dispatchers en cu.alain.chat ───")
for i, dex_bytes in enumerate(a.get_all_dex()):
    d = DEX(dex_bytes)
    for c in d.get_classes():
        if not c.get_name().startswith("Lcu/alain"):
            continue
        for f in c.get_fields():
            pass
        for m in c.get_methods():
            for inst in m.get_instructions():
                out = inst.get_output() if hasattr(inst, "get_output") else ""
                if "Dispatchers" in str(out) and ("Main" in str(out) or "IO" in str(out) or "Default" in str(out)):
                    print(c.get_name(), "->", m.get_name(), ":", str(out).strip()[:110])
                    break
