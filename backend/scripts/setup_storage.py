#!/usr/bin/env python3
"""Crea el bucket de Storage «attachments» (v0.7.0, D76) — idempotente.

Bucket PRIVADO (todo pasa por URLs firmadas que genera la función /files
tras verificar la membresía de la sala). Límite 25 MB por archivo: los
archivos viajan CIFRADOS desde el teléfono (ChaCha20-Poly1305 con clave
viaja E2EE dentro del propio mensaje), el Storage solo ve bytes opacos.

Uso: python3 backend/scripts/setup_storage.py
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

BUCKET = "attachments"
SIZE_LIMIT = 25 * 1024 * 1024  # 25 MB


def main() -> int:
    surl = os.environ.get("SUPA_URL", "").rstrip("/")
    skey = os.environ.get("SUPA_SERVICE_ROLE_KEY", "")
    if not surl or not skey:
        print("ERROR: faltan SUPA_URL o SUPA_SERVICE_ROLE_KEY en el entorno")
        return 1

    # ¿existe ya?
    req = urllib.request.Request(
        f"{surl}/storage/v1/bucket/{BUCKET}",
        headers={"Authorization": f"Bearer {skey}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"bucket {BUCKET} ya existe:", json.loads(r.read() or b"{}").get("name"))
            return 0
    except urllib.error.HTTPError as e:
        msg = e.read()
        # el Storage responde 400+NoSuchBucket (no 404) cuando no existe
        if e.code != 404 and b"NoSuchBucket" not in msg:
            print(f"ERROR consultando bucket: {e.code} {msg[:200]}")
            return 1

    body = json.dumps({
        "name": BUCKET,
        "public": False,
        "file_size_limit": SIZE_LIMIT,
        "allowed_mime_types": None,  # los bytes son cifrados: el mime real no dice nada
    }).encode()
    req = urllib.request.Request(
        f"{surl}/storage/v1/bucket",
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {skey}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"bucket {BUCKET} CREADO:", r.status)
            return 0
    except urllib.error.HTTPError as e:
        msg = e.read()[:300]
        if b"already exists" in msg or e.code == 409:
            print(f"bucket {BUCKET} ya existe (409)")
            return 0
        print(f"ERROR creando bucket: {e.code} {msg}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
