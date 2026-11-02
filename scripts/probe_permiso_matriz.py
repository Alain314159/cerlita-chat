#!/usr/bin/env python3
"""Preflight de Test Lab SIN gastar cuota.

POST https://testing.googleapis.com/v1/projects/{pid}/testMatrices con cuerpo
válido-mínimo pero inválido ({}): si la SA tiene permiso, responde 400
(matriz inválida — sin tocar dispositivos); si no, 403 (falta rol IAM).

Imprime una línea "PERMISO OK" o "PERMISO FALTA" para que el lanzador decida.
El token OAuth se genera en memoria con la llave SA; nunca se imprime.
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


def main() -> int:
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
    req = urllib.request.Request("https://oauth2.googleapis.com/token", data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    tok = json.load(urllib.request.urlopen(req, timeout=30))["access_token"]

    req = urllib.request.Request(
        f"https://testing.googleapis.com/v1/projects/{PROJECT}/testMatrices",
        data=b"{}",
        headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"PERMISO OK (HTTP {r.status}, respuesta inesperada pero autorizada)")
            return 0
    except urllib.error.HTTPError as e:
        cuerpo = e.read().decode(errors="replace")
        if e.code == 400:
            print(f"PERMISO OK — HTTP 400 (matriz vacía rechazada como se esperaba: "
                  f"permiso de creación PRESENTE, cero cuota gastada)")
            return 0
        if e.code == 403:
            print(f"PERMISO FALTA — HTTP 403: la SA no está autorizada en el proyecto. "
                  f"{cuerpo[:180]}")
            return 1
        print(f"PERMISO ? — HTTP {e.code}: {cuerpo[:180]}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
