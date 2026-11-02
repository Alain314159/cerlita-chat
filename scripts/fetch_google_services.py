#!/usr/bin/env python3
"""Recupera google-services.json desde la API de Firebase (Management API).

La sesión anterior registró la app `cu.alain.chat` en el proyecto Firebase,
pero el sandbox se recicló y el archivo (gitignored) se perdió. Este script
re-obtiene la configuración con la cuenta de servicio:

  1. JWT RS256 firmado con la private_key de la SA → access_token de Google
  2. GET /v1beta1/projects/{pid}/androidApps  → localiza la app por paquete
  3. GET .../androidApps/{appId}/config       → fileContent = google-services.json

Escribe android/app/google-services.json y una copia a download/.
No imprime tokens ni el contenido de la configuración.
"""
import json
import sys
import time
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

SA = Path.home() / ".secrets" / "fcm-sa.json"
OUT_APP = Path("/home/z/my-project/android/app/google-services.json")
OUT_DL = Path("/home/z/my-project/download/google-services.json")
EXPECTED_PKG = "cu.alain.chat"


def http(url: str, data=None, headers=None, method=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, json.loads(r.read().decode())


def b64url(raw: bytes) -> str:
    import base64
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def oauth_token(sa: dict) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/firebase "
                 "https://www.googleapis.com/auth/cloud-platform",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = key.sign((header + "." + claims).encode(),
                   padding.PKCS1v15(), hashes.SHA256())
    assertion = header + "." + claims + "." + b64url(sig)
    body = ("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion="
            + assertion).encode()
    _, tok = http("https://oauth2.googleapis.com/token", data=body,
                  headers={"Content-Type": "application/x-www-form-urlencoded"})
    return tok["access_token"]


def main() -> int:
    sa = json.loads(SA.read_text())
    pid = sa["project_id"]
    print(f"SA: {sa['client_email'].split('@')[0]} @ proyecto {pid}")

    tok = oauth_token(sa)
    print("OAuth: access_token obtenido")
    auth = {"Authorization": "Bearer " + tok}

    _, apps = http(f"https://firebase.googleapis.com/v1beta1/projects/{pid}/androidApps",
                   headers=auth)
    apps_list = apps.get("apps", [])
    print(f"Apps Android registradas: {len(apps_list)}")
    target = None
    for a in apps_list:
        print(f"  - {a.get('packageName')} (appId {a.get('appId', '?')[:12]}…, "
              f"estado {a.get('state')})")
        if a.get("packageName") == EXPECTED_PKG:
            target = a
    if not target:
        print(f"ERROR: no hay app con paquete {EXPECTED_PKG}")
        return 1

    app_id = target["appId"]
    _, cfg = http(
        f"https://firebase.googleapis.com/v1beta1/projects/{pid}/androidApps/"
        f"{app_id}/config", headers=auth)
    content = cfg.get("configFileContents") or cfg.get("fileContent") or ""
    try:
        json.loads(content)
    except json.JSONDecodeError:
        # la API devuelve el archivo codificado en base64
        import base64
        content = base64.b64decode(content).decode()
    if not content:
        print("ERROR: respuesta sin fileContent:", json.dumps(cfg)[:200])
        return 1

    OUT_APP.write_text(content)
    OUT_DL.write_text(content)
    parsed = json.loads(content)
    print(f"google-services.json restaurado:")
    print(f"  - proyecto: {parsed.get('project_id')}")
    print(f"  - paquete:  {parsed['client'][0]['client_info']['android_client_info']['package_name']}")
    print(f"  - api_key:  {parsed['client'][0]['api_key'][0]['current_key'][:10]}…")
    print(f"  - escrito:  {OUT_APP}")
    print(f"  - copia:    {OUT_DL}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
