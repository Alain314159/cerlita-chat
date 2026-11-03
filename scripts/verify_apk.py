#!/usr/bin/env python3
"""Verifica que cada componente del manifest exista en el dex + busca anomalías."""
from androguard.core.apk import APK
from androguard.core.dex import DEX

APK_PATH = "/home/z/my-project/download/cerlita-chat-v0.3.0-release.apk"
a = APK(APK_PATH)

classes = set()
for dex_bytes in a.get_all_dex():
    d = DEX(dex_bytes)
    for c in d.get_classes():
        classes.add(c.get_name())

print("─── componentes del manifest vs dex ───")
def check(desc: str, cls_name: str):
    name = "L" + cls_name.replace(".", "/") + ";"
    print(("OK  " if name in classes else "¡¡FALTA!! "), desc, "=", cls_name)

check("application", a.get_attribute_value("application", "name") or "android.app.Application")
for act in a.get_activities():
    if act.startswith("cu."):
        check("activity", act)
for svc in a.get_services():
    if svc.startswith("cu."):
        check("service", svc)
for rcv in a.get_receivers():
    if rcv.startswith("cu."):
        check("receiver", rcv)
for prov in a.get_providers():
    if prov.startswith("cu."):
        check("provider", prov)

print()
print("─── meta manifiesto ───")
print("debuggable  :", a.get_attribute_value("application", "debuggable"))
print("has_native  :", a.get_libraries())

# ¿minificado? comprueba algunas clases de androidx que R8 no debería romper
print()
print("─── sanity R8 (clases que el arranque necesita) ───")
for c in [
    "Landroidx/startup/InitializationProvider;",
    "Landroidx/work/impl/WorkManagerInitializer;",
    "Landroidx/profileinstaller/ProfileInstallerInitializer;",
    "Lkotlinx/coroutines/android/AndroidDispatcherFactory;",
]:
    print(("OK  " if c in classes else "¡¡FALTA!! "), c)

# strings del dex: busca la URL del backend (para confirmar que el código llegó completo)
all_strs = set()
for dex_bytes in a.get_all_dex():
    d = DEX(dex_bytes)
    for s in d.get_strings():
        all_strs.add(s)
print()
print("─── constantes clave en el dex ───")
for probe in ["DEFAULT_BASE", "supabase", "cerlita", "heartbeat", "last_seq", "outbox"]:
    hits = [s for s in all_strs if probe.lower() in s.lower()][:4]
    print(f"{probe}: {hits if hits else 'NO ENCONTRADO'}")
