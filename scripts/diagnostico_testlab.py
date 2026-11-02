#!/usr/bin/env python3
"""Diagnóstico definitivo del acceso a Firebase Test Lab.

Objetivo: determinar SI el bloqueo es (a) APIs deshabilitadas, (b) roles IAM
que faltan en la SA, o (c) un error METODOLOGICO MÍO (endpoint mal formado,
interpretación equivocada del 404-HTML).

Estrategia — 3 capas de evidencia:
  1. Discovery docs oficiales (sin auth): me dan las RUTAS exactas de la API.
     Si mi endpoint era incorrecto, el 404-HTML anterior fue culpa mía.
  2. Probes SIN autenticar: una API habilitada responde 401-JSON pidiendo
     credenciales; una deshabilitada o ruta inexistente responde 404-HTML.
  3. Probes AUTENTICADOS con la SA: el JSON de error trae el campo "reason"
     exacto: SERVICE_DISABLED = API apagada; PERMISSION_DENIED = rol IAM.
     Esta es la prueba que no puede mentir.

No imprime tokens; solo códigos HTTP y cuerpos de error.
"""
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

SA = Path.home() / ".secrets" / "fcm-sa.json"
PROJECT = "cerlita-chat"


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def fetch(url: str, token: str | None = None, data: bytes | None = None):
    headers = {"User-Agent": "cerlita-diagnostico"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def clasifica(status: int, body: bytes) -> str:
    """Clasifica la respuesta: JSON de API vs HTML del frontal de Google."""
    try:
        j = json.loads(body)
        err = j.get("error", {})
        reason = ""
        det = err.get("details", [])
        for d in det if isinstance(det, list) else []:
            if isinstance(d, dict) and d.get("reason"):
                reason = d["reason"]
                break
        return f"JSON api | {err.get('code', status)} | reason={reason or '-'} | {err.get('message', '')[:110]}"
    except (json.JSONDecodeError, UnicodeDecodeError):
        es_html = b"<html" in body[:200].lower()
        return f"{'HTML frontal Google' if es_html else 'otro'} | {status}"


def token_sa() -> str:
    sa = json.loads(SA.read_text())
    now = int(time.time())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    head = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/cloud-platform",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now, "exp": now + 3600,
    }).encode())
    sig = key.sign((head + "." + claims).encode(), padding.PKCS1v15(), hashes.SHA256())
    body = ("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion="
            + head + "." + claims + "." + b64url(sig)).encode()
    st, resp = fetch("https://oauth2.googleapis.com/token", data=body)
    if st != 200:
        print(f"FALLO OAuth: {st} {resp[:150]}")
        sys.exit(1)
    return json.loads(resp)["access_token"]


def main() -> int:
    print("=" * 72)
    print("CAPA 1: discovery docs — ¿eran correctas MIS rutas?")
    print("=" * 72)
    for nombre, url in [
        ("testing v1", "https://testing.googleapis.com/$discovery/rest?version=v1"),
        ("toolresults v1beta3", "https://toolresults.googleapis.com/$discovery/rest?version=v1beta3"),
    ]:
        st, body = fetch(url)
        if st == 200:
            doc = json.loads(body)
            recursos = doc.get("resources", {})
            # localiza el recurso del catálogo de dispositivos Android
            for r in ("projects", "androidDeviceCatalog"):
                if r in recursos:
                    gets = [m for m in recursos[r].get("methods", {})
                            if "deviceCatalog" in m or m == "get"]
                    for m, spec in recursos[r].get("methods", {}).items():
                        if "deviceCatalog" in spec.get("path", "") or "androidModels" in spec.get("path", ""):
                            print(f"  {nombre}: recurso '{r}' método '{m}' → path {spec['path']}")
            hr = recursos.get("projects", {}).get("methods", {})
            if "histories" in str(recursos):
                for m, spec in recursos["projects"].get("methods", {}).items():
                    if "histor" in spec.get("path", ""):
                        print(f"  {nombre}: histories → path {spec['path']}")
        else:
            print(f"  {nombre}: HTTP {st} (¿doc inaccesible sin API habilitada?)")

    print()
    print("=" * 72)
    print("CAPA 2: SIN autenticar (401-JSON = habilitada; 404 = apagada o ruta mala)")
    print("=" * 72)
    rutas = [
        ("testing  deviceCatalog", f"https://testing.googleapis.com/v1/projects/{PROJECT}/deviceCatalog/androidModels"),
        ("toolresults histories", f"https://toolresults.googleapis.com/v1beta3/projects/{PROJECT}/histories"),
        ("firebase androidApps (control)", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT}/androidApps"),
    ]
    for nombre, url in rutas:
        st, body = fetch(url)
        print(f"  {nombre:32s} HTTP {st} → {clasifica(st, body)}")

    print()
    print("=" * 72)
    print("CAPA 3: AUTENTICADO con la SA (el reason exacto no miente)")
    print("=" * 72)
    tok = token_sa()
    print("  token OAuth obtenido")
    for nombre, url in rutas:
        st, body = fetch(url, token=tok)
        linea = clasifica(st, body)
        if st == 200:
            n = len(json.loads(body).get("androidModels", json.loads(body).get("histories", [])))
            linea = f"OK 200 — {n} elementos"
        print(f"  {nombre:32s} HTTP {st} → {linea}")

    # IAM: metadatos públicos de los roles que pedí al usuario (valida MI instrucción)
    print()
    print("=" * 72)
    print("CAPA 4: ¿existen los roles que le pedí al usuario en IAM?")
    print("=" * 72)
    for rol in ("roles/cloudtesting.admin", "roles/cloudtoolresults.editor"):
        st, body = fetch(f"https://iam.googleapis.com/v1/{rol}")
        if st == 200:
            j = json.loads(body)
            print(f"  {rol}: EXISTE — {j.get('title')} — {j.get('description', '')[:90]}")
        else:
            print(f"  {rol}: HTTP {st} → {body[:100]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
