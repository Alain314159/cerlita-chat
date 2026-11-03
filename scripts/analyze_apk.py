#!/usr/bin/env python3
"""Analiza el APK entregado: versión, clases del sistema de perfiles, constantes."""
import sys
from androguard.core.apk import APK

APK_PATH = "/home/z/my-project/download/cerlita-chat-v0.3.0-release.apk"

a = APK(APK_PATH)
print("package      :", a.get_package())
print("versionCode  :", a.get_androidversion_code())
print("versionName  :", a.get_androidversion_name())
print("minSdk       :", a.get_min_sdk_version())
print("targetSdk    :", a.get_target_sdk_version())
print("app label    :", a.get_app_name())
print()

# clases en todos los dex
classes = set()
for dex_name in a.get_dex():
    # get_dex devuelve nombres; usar a.get_all_dex() para bytes
    pass

from androguard.core.dex import DEX
for dex_bytes in a.get_all_dex():
    d = DEX(dex_bytes)
    for c in d.get_classes():
        classes.add(c.get_name())

print("total clases :", len(classes))
print()

# ¿Están las clases del sistema de perfiles (v0.4.0)?
checks = [
    "Lcu/alain/chat/profile/ProfileMsg;",
    "Lcu/alain/chat/profile/Photos;",
    "Lcu/alain/chat/profile/AvatarConfig;",
    "Lcu/alain/chat/ui/AvatarView;",
    "Lcu/alain/chat/ui/AvatarView$AvatarCatalog;",
    "Lcu/alain/chat/ui/screens/ProfileScreen;",
    "Lcu/alain/chat/db/Profile;",
    "Lcu/alain/chat/db/RoomPeer;",
    "Lcu/alain/chat/db/RoomListDao;",
    "Lcu/alain/chat/db/AppDatabase;",
    "Lcu/alain/chat/ChatApp;",
]
print("─── clases v0.4.0 ───")
for c in checks:
    print(("✓ PRESENTE " if c in classes else "✗ AUSENTE  "), c)
