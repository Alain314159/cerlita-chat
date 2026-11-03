#!/usr/bin/env python3
"""Verificación final del APK release v0.4.1:
1. versionCode/Name correctos
2. ViewModels renombrados con constructor (Application) — el fix del crash
3. ChatApp implementa Configuration.Provider (WorkManager on-demand)
4. Signature igual a v0.3.0 (mismo keystore)
"""
from androguard.core.apk import APK
from androguard.core.dex import DEX

NEW = "/home/z/my-project/app/android/app/build/outputs/apk/release/app-release.apk"
OLD = "/home/z/my-project/download/cerlita-chat-v0.3.0-release.apk"

a = APK(NEW)
print("package     :", a.get_package())
print("versionCode :", a.get_androidversion_code())
print("versionName :", a.get_androidversion_name())

# ── firma: comparar con v0.3.0 ──
print()
old = APK(OLD)
new_certs = [c.sha256 for c in a.get_certificates_der_v3() or a.get_certificates_der_v2()]
old_certs = [c.sha256 for c in old.get_certificates_der_v3() or old.get_certificates_der_v2()]
# androguard API: get_certificates_der_v3 devuelve lista de bytes sha? → usar firma simple
try:
    same = (a.get_signature_names() == old.get_signature_names())
except Exception:
    same = None
print("certs v3 new:", new_certs[:1])
print("certs v3 old:", old_certs[:1])
print("FIRMA IGUAL :", new_certs == old_certs)

# ── ViewModels: clase renombrada con <init>(Application) ──
print()
vm_ctors = []
chatapp_provider = False
appclass = None
for i, dex_bytes in enumerate(a.get_all_dex()):
    d = DEX(dex_bytes)
    for c in d.get_classes():
        n = c.get_name()
        # ChatApp: busca la clase que tiene workManagerConfiguration
        if 'workManagerConfiguration' in [f.get_name() for f in c.get_fields()] or \
           any('workManagerConfiguration' in m.get_name() for m in c.get_methods()):
            chatapp_provider = True
        # ViewModel con ctor (Application): AndroidViewModel renombrado no importa:
        # buscar clases cuyo <init> tome Application y tengan campo busy (marker)
        for m in c.get_methods():
            if m.get_name() == '<init>' and m.get_descriptor() == '(Landroid/app/Application;)V':
                # marca: la clase tiene un campo 'busy' de tipo MutableState
                flds = [f.get_name() for f in c.get_fields()]
                if 'busy' in flds:
                    vm_ctors.append(n)

print("ViewModels con <init>(Application) y campo busy:", vm_ctors)
print("ChatApp.workManagerConfiguration presente:", chatapp_provider)

# ── sanity: manifest WorkManagerInitializer removido ──
from androguard.core.axml import AXMLPrinter
content = AXMLPrinter(a.get_file("AndroidManifest.xml")).get_buff().decode("utf-8")
has_wm_meta = "androidx.work.WorkManagerInitializer" in content
print("WorkManagerInitializer en manifest (debe ser False):", has_wm_meta)
