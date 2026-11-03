#!/usr/bin/env python3
"""Sube los secrets de firma del APK a GitHub Actions (API + libsodium sealed box)."""
import base64
import json
import os
import sys
import urllib.request

from nacl import encoding, public

REPO = "Alain314159/cerlita-chat"
TOKEN_FILE = "/home/z/my-project/upload/folder/26ab1b36/Credenciales de github que pue.txt"
KS_FILE = "/home/z/my-project/secrets/chat-release.keystore"
KS_PROPS = "/home/z/my-project/secrets/keystore.properties"


def api(method, path, body=None):
    token = os.environ["GH_TOKEN"]
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read()
            return r.status, json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    token = None
    for line in open(TOKEN_FILE, encoding="utf-8", errors="ignore"):
        for word in line.split():
            if word.startswith("ghp_"):
                token = word
                break
        if token:
            break
    if not token:
        sys.exit("no se encontró el token")
    os.environ["GH_TOKEN"] = token

    # leer contraseñas del keystore.properties (fuera del repo)
    props = {}
    for line in open(KS_PROPS):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            props[k] = v

    ks_b64 = base64.b64encode(open(KS_FILE, "rb").read()).decode()

    status, pk = api("GET", "actions/secrets/public-key")
    if status != 200:
        sys.exit(f"public-key HTTP {status}: {pk}")
    print(f"public-key OK (id={pk.get('key_id')})")

    pk_obj = public.PublicKey(pk["key"].encode(), encoding.Base64Encoder())
    sealed = public.SealedBox(pk_obj)

    secrets = {
        "ANDROID_KEYSTORE_B64": ks_b64,
        "ANDROID_KEYSTORE_PASSWORD": props["storePassword"],
        "ANDROID_KEY_ALIAS": props["keyAlias"],
        "ANDROID_KEY_PASSWORD": props["keyPassword"],
    }
    for name, value in secrets.items():
        enc = base64.b64encode(sealed.encrypt(value.encode())).decode()
        status, resp = api(
            "PUT",
            f"actions/secrets/{name}",
            {"encrypted_value": enc, "key_id": pk["key_id"]},
        )
        print(f"{name}: HTTP {status}")
        if status != 201 and status != 204:
            sys.exit(f"FALLO subiendo {name}: {resp}")
    print("TODOS LOS SECRETS SUBIDOS")


if __name__ == "__main__":
    main()
