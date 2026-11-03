#!/usr/bin/env python3
"""Extrae META-INF/services/q5 y /vr: ¿la clase que referencian existe en el dex?
¿Tiene la forma de una DispatcherFactory (Looper/Handler)?"""
import zipfile
from androguard.core.apk import APK
from androguard.core.dex import DEX

APK_PATH = "/home/z/my-project/download/cerlita-chat-v0.3.0-release.apk"

z = zipfile.ZipFile(APK_PATH)
svc_contents = {}
for name in z.namelist():
    if "META-INF/services/" in name:
        content = z.read(name).decode("utf-8", "replace").strip()
        svc_contents[name] = content
        print(f"{name} -> {content!r}")

a = APK(APK_PATH)
classes = {}
for i, dex_bytes in enumerate(a.get_all_dex()):
    d = DEX(dex_bytes)
    for c in d.get_classes():
        classes[c.get_name()] = (i, c)

print()
for name, content in svc_contents.items():
    impl = content.replace(".", "/")
    impl_name = "L" + impl + ";"
    if impl_name in classes:
        i, c = classes[impl_name]
        print(f"OK {name}: la clase {impl_name} EXISTE (dex#{i})")
        # inspecciona: ¿usa Looper/Handler (forma de AndroidDispatcherFactory)?
        uses = set()
        for m in c.get_methods():
            for inst in m.get_instructions():
                out = str(inst.get_output()) if hasattr(inst, "get_output") else ""
                for probe in ["Looper", "Handler", "MainLooper", "asCoroutineDispatcher", "ExceptionHandlerService"]:
                    if probe in out:
                        uses.add(probe)
        print("   referencias:", uses if uses else "(ninguna visible)")
        # ¿implementa alguna interfaz?
        print("   super:", c.get_superclassname(), "| interfaces:", list(c.get_interfaces()))
    else:
        print(f"¡¡ROTO!! {name}: la clase {impl_name} NO EXISTE en ningún dex → ServiceConfigurationError")

# además: ¿qué interfaces del dex se llaman como los services (q5, vr)?
print()
base = [n.split("/")[-1] for n in svc_contents]
for i, dex_bytes in enumerate(a.get_all_dex()):
    d = DEX(dex_bytes)
    for c in d.get_classes():
        short = c.get_name()[1:-1].split("/")[-1]
        if short in base:
            print(f"dex#{i}: clase {c.get_name()} super={c.get_superclassname()} ifaces={list(c.get_interfaces())}")
