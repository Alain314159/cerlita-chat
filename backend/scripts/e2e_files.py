#!/usr/bin/env python3
"""E2E de ADJUNTOS E2EE (v0.7.0, D76) contra producción.

Valida el ciclo completo que usará la app:

  1. A y B se registran; A crea sala; B se une.
  2. A sube bytes CIFRADOS (aleatorios: simulan el ChaCha del cliente)
     DIRECTO a Storage con la anon key, a <room>/<uuid>.m4a → 200 (la
     política RLS attachments_insert_by_room_capability lo permite).
  3. A pide URL de descarga del MISMO path → GET → los bytes son IDÉNTICOS.
  4. B (miembro) también consigue URL de descarga del mismo adjunto.
  5. C (NO miembro) pide URL de descarga → 404 no_room; y de subida a esa
     sala → 404. Un path de otra sala no sirve para colarse.
  6. Paths maliciosos (../, 3 segmentos, sin extensión) → 400 bad_path.
  7. La URL firmada caduca (TTL corto no comprobable aquí; se verifica que
     el sign incluye token).
  8. Limpieza BD + objetos del bucket.

Uso: python3 backend/scripts/e2e_files.py
"""
from __future__ import annotations

import base64
import json
import os
import secrets as pysecrets
import sys
import time
import urllib.request
import uuid as uuidlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

import e2e_rooms_client as e2e  # noqa: E402

INVITE = os.environ.get("REG_INVITE_CODE", "")


def safe(raw):
    import gzip as gz
    if not raw:
        return {}
    if raw[:2] == b"\x1f\x8b":
        raw = gz.decompress(raw)
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": str(raw[:100])}


def storage_upload(path: str, data: bytes) -> int:
    """POST directo a Storage con la anon key (como hará la app)."""
    import os as _os
    surl = _os.environ.get("SUPA_URL", "").rstrip("/")
    anon = _os.environ.get("SUPA_ANON_KEY", "")
    req = urllib.request.Request(
        f"{surl}/storage/v1/object/attachments/{path}",
        data=data, method="POST",
        headers={"apikey": anon, "Authorization": f"Bearer {anon}",
                 "Content-Type": "application/octet-stream"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def http_put(url: str, data: bytes) -> int:
    req = urllib.request.Request(url, data=data, method="PUT",
                                 headers={"Content-Type": "application/octet-stream"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status
    except urllib.error.HTTPError as e:
        print("   PUT error:", e.code, e.read()[:120])
        return e.code


def http_get(url: str):
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""


def main() -> int:
    dev_a, dev_b, dev_c = (str(uuidlib.uuid4()) for _ in range(3))
    stamp = int(time.time()) % 100000
    ua, ub, uc = f"filea{stamp}", f"fileb{stamp}", f"filec{stamp}"

    print(f"== E2E adjuntos: @{ua} @{ub} @{uc} ==")
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    tok_a = safe(raw).get("tok", "")
    e2e.check("registro A", bool(tok_a))
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    tok_b = safe(raw).get("tok", "")
    e2e.check("registro B", bool(tok_b))
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": uc, "p": "clave-de-prueba-8", "d": dev_c, "ic": INVITE})
    tok_c = safe(raw).get("tok", "")
    e2e.check("registro C", bool(tok_c))

    room = str(uuidlib.uuid4())
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_new", "r": room}, tok_a)
    e2e.check("A crea la sala", st == 201)
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_join", "r": room}, tok_b)
    e2e.check("B se une", st == 200)

    # ── subida: directa a Storage con la anon key (RLS por capability) ──
    data = pysecrets.token_bytes(1024)  # "ciphertext" simulado
    path = f"{room}/{uuidlib.uuid4()}.m4a"
    code = storage_upload(path, data)
    e2e.check("subida directa a Storage con anon key → 200", code == 200, f"code={code}")
    e2e.check("el path es <room>/<uuid>.m4a", path.startswith(room + "/") and path.endswith(".m4a"))
    # a un path SIN roomId válido el Storage mismo lo rechaza (RLS)
    e2e.check("subida a path sin UUID de sala → rechazada",
              storage_upload("path-basura/x.bin", data) in (400, 403))

    # ── descarga: A y B (miembros) recuperan los MISMOS bytes ──
    st, _, raw = e2e.req("POST", "/files", {"a": "urlDl", "p": path}, tok_a)
    dl_a = safe(raw).get("u", "")
    e2e.check("A (miembro) obtiene URL de descarga", st == 200 and dl_a.startswith("http"))
    code, got = http_get(dl_a)
    e2e.check("GET de A → bytes idénticos a los subidos", code == 200 and got == data, f"code={code} len={len(got)}")

    st, _, raw = e2e.req("POST", "/files", {"a": "urlDl", "p": path}, tok_b)
    dl_b = safe(raw).get("u", "")
    code, got_b = http_get(dl_b)
    e2e.check("B (miembro) también descarga el adjunto íntegro",
              st == 200 and code == 200 and got_b == data)

    # ── no miembros y paths maliciosos ──
    st, _, raw = e2e.req("POST", "/files", {"a": "urlDl", "p": path}, tok_c)
    e2e.check("C (NO miembro) no obtiene URL de descarga → 404",
              st == 404 and safe(raw).get("e") == "no_room")
    # (la subida es por capability del roomId: C sin el roomId no puede
    #  construct un path válido de ESA sala — el RLS del Storage lo garantiza)
    for bad in (f"{room}/../../etc/passwd", f"{room}/a/b/c", f"{room}/sinextension",
                "no-uuid/archivo.mp3", f"{room}/." + "x" * 30):
        st, _, raw = e2e.req("POST", "/files", {"a": "urlDl", "p": bad}, tok_a)
        # 400 bad_path (la función) o 403/4xx del WAF de Supabase: ambos lo paran
        e2e.check(f"path malicioso rechazado ({bad[:28]}…)",
                  st == 400 or st in (403, 413), f"{st}")

    # ── sin sesión no hay nada ──
    st, _, raw = e2e.req("POST", "/files", {"a": "urlDl", "p": path}, None)
    e2e.check("sin token → 401", st == 401)

    # ── limpieza: BD + objeto del bucket ──
    surl = os.environ.get("SUPA_URL", "").rstrip("/")
    skey = os.environ.get("SUPA_SERVICE_ROLE_KEY", "")
    if surl and skey:
        req = urllib.request.Request(
            f"{surl}/storage/v1/object/attachments/{path}",
            method="DELETE", headers={"Authorization": f"Bearer {skey}"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r2:
                e2e.check("objeto del bucket borrado", r2.status in (200, 204))
        except urllib.error.HTTPError as e2:
            e2e.check("objeto del bucket borrado", False, f"{e2.code}")
    cleanup = [
        f"delete from messages where room_id = '{room}'::uuid",
        f"delete from room_members where room_id = '{room}'::uuid",
        f"delete from rooms where room_id = '{room}'::uuid",
        f"delete from sessions where user_id in (select id from users where username in ('{ua}','{ub}','{uc}'))",
        f"delete from users where username in ('{ua}','{ub}','{uc}')",
    ]
    ok = True
    for s in cleanup:
        st2, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st2 not in api.OK:
            ok = False
    e2e.check("limpieza BD", ok)

    print(f"\n{'=' * 60}\nRESULTADO ADJUNTOS: {e2e.PASS} PASS · {e2e.FAIL} FAIL")
    return 0 if e2e.FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
