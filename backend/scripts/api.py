"""Cliente mínimo de la Supabase Management API — solo stdlib.

Carga las variables SUPA_TOKEN / SUPA_REF / SUPA_URL / REG_INVITE_CODE desde el
entorno o, si no están, del archivo de secrets FUERA del repo (por defecto
/home/z/my-project/secrets/supabase.env). Ningún secreto entra jamás al repo.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.supabase.com"
DEFAULT_ENV_FILE = "/home/z/my-project/secrets/supabase.env"
OK = (200, 201, 202, 204)


def load_env(path: str = DEFAULT_ENV_FILE) -> None:
    """Sourcea el archivo de secrets si las variables aún no están en el entorno."""
    if os.environ.get("SUPA_TOKEN"):
        return
    if not os.path.exists(path):
        sys.exit(
            f"No hay SUPA_TOKEN en el entorno y no existe {path}.\n"
            "Crea el archivo con: export SUPA_TOKEN=... SUPA_REF=... SUPA_URL=..."
        )
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.startswith("export "):
            k, _, v = line[7:].partition("=")
            v = v.strip().strip('"').strip("'")
            os.environ[k.strip()] = v


def call(method: str, path: str, data=None, token: str | None = None):
    """Llama a la Management API. Devuelve (status, payload_json_o_texto)."""
    load_env()
    token = token or os.environ["SUPA_TOKEN"]
    req = urllib.request.Request(
        f"{API}{path}",
        method=method,
        data=json.dumps(data).encode() if data is not None else None,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def ref() -> str:
    load_env()
    return os.environ["SUPA_REF"]
