#!/usr/bin/env python3
"""Compila y despliega las Edge Functions vía Management API.

Uso:
  python3 backend/scripts/deploy_functions.py             # build + deploy + secrets (DEBUG=1)
  python3 backend/scripts/deploy_functions.py --debug-off # re-fija secrets con DEBUG=0
  python3 backend/scripts/deploy_functions.py --no-build  # deploy sin recompilar

Arquitectura del build (hallazgos 26-oct-2026, ver docs/DECISIONES.md):
- El endpoint legacy de body base64 produce funciones que NUNCA arrancan.
  El único camino válido es el multipart: POST /functions/deploy?slug={name}
  con metadata {name, entrypoint_path, verify_jwt} + campo file.
- El resolver npm: del runtime NO resuelve @noble/hashes (scoped+subpath).
- postgres.js vía npm: SÍ funciona (verificado: heartbeat conecta a la DB).
- ⇒ noble se EMPOTRA en el bundle con bun (build/package.json) y postgres
  queda externo como npm:postgres@3.4.9 (glob --external='npm:*').

Requiere: bun en PATH, y en el entorno SUPA_TOKEN, SUPA_REF, REG_INVITE_CODE
(carga /home/z/my-project/secrets/supabase.env por defecto). Sin secrets al repo.
"""
from __future__ import annotations

import base64
import json
import os
import secrets as pysecrets
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

FUNCTIONS = ["auth", "sync", "outbox", "heartbeat", "files"]
BACKEND = Path(__file__).resolve().parents[1]
BUILD = BACKEND / "build"


def build_functions() -> bool:
    """bun install + bun build de cada función → build/<fn>.js"""
    print("== Build (bun) ==")
    try:
        r = subprocess.run(["bun", "install"], cwd=BACKEND, capture_output=True, text=True, timeout=180)
        if r.returncode != 0:
            print("bun install falló:", r.stderr[-300:])
            return False
        BUILD.mkdir(exist_ok=True)
        for fn in FUNCTIONS:
            src = BACKEND / "functions" / fn / "index.ts"
            out = BUILD / f"{fn}.js"
            r = subprocess.run(
                ["bun", "build", str(src), "--outfile", str(out),
                 "--external=npm:*", "--target=browser", "--format=esm"],
                cwd=BACKEND, capture_output=True, text=True, timeout=120,
            )
            if r.returncode != 0 or not out.exists():
                print(f"[{fn}] build ERROR:", r.stderr[-300:] or r.stdout[-300:])
                return False
            print(f"[{fn}] bundle OK: {out.stat().st_size} bytes")
        return True
    except FileNotFoundError:
        print("ERROR: bun no está en PATH")
        return False
    except subprocess.TimeoutExpired:
        print("ERROR: timeout en el build")
        return False


def multipart_deploy(name: str) -> bool:
    """Despliega build/<name>.js vía el endpoint multipart (única vía que arranca)."""
    src = (BUILD / f"{name}.js").read_text(encoding="utf-8")
    boundary = "----" + pysecrets.token_hex(12)
    metadata = json.dumps({"name": name, "entrypoint_path": "index.js", "verify_jwt": False})

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="metadata"\r\n'
        f"Content-Type: application/json\r\n\r\n"
        f"{metadata}\r\n"
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="index.js"\r\n'
        f"Content-Type: text/javascript\r\n\r\n"
    ).encode() + src.encode() + f"\r\n--{boundary}--\r\n".encode()

    api.load_env()
    req = urllib.request.Request(
        f"{api.API}/v1/projects/{api.ref()}/functions/deploy?slug={name}",
        method="POST",
        data=body,
        headers={
            "Authorization": f"Bearer {os.environ['SUPA_TOKEN']}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            res = json.loads(r.read() or b"{}")
            print(f"[{name}] desplegada (v{res.get('version')})")
            return True
    except urllib.error.HTTPError as e:
        msg = e.read().decode(errors="replace")[:300]
        if e.code in (404, 400) and "not found" in msg.lower():
            # la función no existe: crear registro stub y reintentar una vez
            st, res = api.call("POST", f"/v1/projects/{api.ref()}/functions",
                               {"name": name, "slug": name, "verify_jwt": False,
                                "body": base64.b64encode(b"export default () => new Response('stub');").decode()})
            if st in api.OK or "duplicat" in str(res).lower():
                print(f"[{name}] registro creado, reintento deploy…")
                return multipart_deploy(name)
        print(f"[{name}] ERROR: {e.code} {msg}")
        return False


def set_secrets(debug: str) -> bool:
    invite = os.environ.get("REG_INVITE_CODE")
    svc_jwt = os.environ.get("SUPA_SERVICE_ROLE_KEY", "")
    if not invite or not svc_jwt:
        api.load_env()
        invite = invite or os.environ.get("REG_INVITE_CODE")
        svc_jwt = svc_jwt or os.environ.get("SUPA_SERVICE_ROLE_KEY", "")
    if not invite:
        print("ERROR: falta REG_INVITE_CODE en el entorno")
        return False
    if not svc_jwt.startswith("eyJ"):
        print("ERROR: SUPA_SERVICE_ROLE_KEY del entorno no es un JWT clásico (Storage lo exige)")
        return False

    # v0.7.0: el Storage SOLO acepta JWTs clásicos; el SUPABASE_SERVICE_ROLE_KEY
    # auto-inyectado en las funciones es el formato nuevo (sb_secret_) → 403
    # Invalid Compact JWS. Guardamos la JWT como secret propio.
    secret_sets = [
        {"name": "REG_INVITE_CODE", "value": invite},
        {"name": "DEBUG", "value": debug},
        {"name": "SUPA_SVC_JWT", "value": svc_jwt},
    ]
    for body in (secret_sets, {"secrets": secret_sets}):
        st, res = api.call("POST", f"/v1/projects/{api.ref()}/secrets", body)
        if st in api.OK:
            print("secrets OK (globales)")
            return True
    print("ERROR: no se pudieron fijar los secrets")
    return False


def fetch_anon_key() -> bool:
    """Obtiene la anon key y la añade al archivo de secrets local (fuera del repo)."""
    for path in (f"/v1/projects/{api.ref()}/api-keys", f"/v1/projects/{api.ref()}/keys"):
        st, res = api.call("GET", path)
        if st in api.OK and res:
            anon = None
            if isinstance(res, list):
                for k in res:
                    if k.get("name") in ("anon", "publishable"):
                        anon = k.get("api_key") or k.get("key")
            elif isinstance(res, dict):
                anon = res.get("anon") or res.get("publishable")
            if anon:
                env_file = os.environ.get("SUPA_ENV_FILE", api.DEFAULT_ENV_FILE)
                try:
                    existing = Path(env_file).read_text() if Path(env_file).exists() else ""
                    if "SUPA_ANON_KEY" not in existing:
                        with open(env_file, "a", encoding="utf-8") as f:
                            f.write(f'\nexport SUPA_ANON_KEY="{anon}"\n')
                        print(f"anon key guardada en {env_file}")
                    else:
                        print("anon key ya presente en el archivo de secrets")
                except OSError as e:
                    print(f"aviso: no pude escribir el archivo de secrets: {e}")
                os.environ["SUPA_ANON_KEY"] = anon
                return True
    print("aviso: no pude obtener la anon key (no es crítica para el smoke test)")
    return False


def main() -> int:
    debug = "0" if "--debug-off" in sys.argv else "1"
    api.load_env()

    if "--no-build" not in sys.argv:
        if not build_functions():
            return 1
        print()

    print("== Deploy ==")
    ok = True
    for fn in FUNCTIONS:
        ok = multipart_deploy(fn) and ok
    if not ok:
        return 1
    ok = set_secrets(debug) and ok
    fetch_anon_key()
    print("\nDeploy completo." if ok else "\nDeploy con errores.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
