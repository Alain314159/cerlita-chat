#!/usr/bin/env python3
"""Recupera las llaves del proyecto Supabase vía Management API y completa
secrets/supabase.env (fuera del repo). Necesita SUPA_TOKEN y SUPA_REF ya
presentes en el entorno o en secrets/supabase.env.

Añade (si faltan):
  SUPA_ANON_KEY           — llave publishable/anon (la exige el gateway)
  SUPA_SERVICE_ROLE_KEY   — JWT clásico (eyJ...) que exige el Storage de
                            Supabase (la sb_secret_ nueva da 403, hallazgo v0.7)

JAMÁS imprime los valores de las llaves: solo qué encontró y en qué formato.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend" / "scripts"))
import api  # noqa: E402

ENV_FILE = api.DEFAULT_ENV_FILE


def fmt(key: str) -> str:
    """Formato de la llave SIN revelarla: prefijo + longitud."""
    if key.startswith("eyJ"):
        return f"JWT-clásico ({len(key)} chars)"
    return f"{key[:9]}… ({len(key)} chars)"


def main() -> int:
    api.load_env()
    ref = api.ref()

    st, proj = api.call("GET", f"/v1/projects/{ref}")
    if st not in api.OK:
        print(f"ERROR obteniendo proyecto: {st} {proj}")
        return 1
    print(f"Proyecto: {proj.get('name')} · región {proj.get('region')} · estado {proj.get('status')}")
    if proj.get("status") not in ("ACTIVE", "ACTIVE_HEALTHY"):
        print("ERROR: el proyecto no está activo")
        return 1

    # Probar ambos endpoints (api-keys es el nuevo; keys el legacy)
    keys: list[dict] = []
    for path in (f"/v1/projects/{ref}/api-keys", f"/v1/projects/{ref}/keys"):
        st, res = api.call("GET", path)
        if st in api.OK and isinstance(res, list):
            keys = res
            print(f"llaves obtenidas de {path}")
            break
    if not keys:
        print("ERROR: ningún endpoint de llaves respondió")
        return 1

    anon = ""
    svc = ""
    for k in keys:
        name = k.get("name", "")
        val = k.get("api_key") or k.get("key") or ""
        if not val:
            continue
        if name in ("anon", "publishable") and not anon:
            anon = val
        elif name in ("service_role", "secret") and val.startswith("eyJ"):
            svc = val  # SOLO el JWT clásico sirve para Storage (hallazgo v0.7)
    print(f"anon encontrada: {fmt(anon) if anon else 'NO'}")
    print(f"service_role JWT clásico: {fmt(svc) if svc else 'NO'}")

    if not anon or not svc:
        print("ERROR: faltan llaves (anon o service_role clásica)")
        return 1

    existing = Path(ENV_FILE).read_text(encoding="utf-8") if Path(ENV_FILE).exists() else ""
    with open(ENV_FILE, "a", encoding="utf-8") as f:
        if "SUPA_ANON_KEY" not in existing:
            f.write(f'export SUPA_ANON_KEY="{anon}"\n')
            print("SUPA_ANON_KEY añadida al env")
        else:
            print("SUPA_ANON_KEY ya presente")
        if "SUPA_SERVICE_ROLE_KEY" not in existing:
            f.write(f'export SUPA_SERVICE_ROLE_KEY="{svc}"\n')
            print("SUPA_SERVICE_ROLE_KEY añadida al env")
        else:
            print("SUPA_SERVICE_ROLE_KEY ya presente")
    os.chmod(ENV_FILE, 0o600)
    print("OK — env completo, permisos 600")
    return 0


if __name__ == "__main__":
    sys.exit(main())
