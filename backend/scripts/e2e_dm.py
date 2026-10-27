#!/usr/bin/env python3
"""E2E de CHATS DIRECTOS 1-a-1 (v0.7.0, D74) contra producción.

Valida el bucle completo que usará la app Android:

  1. A y B se registran con clave pública X25519 (aleatoria: la cripto real
     vive en DmCryptoTest; aquí probamos el protocolo de servidor).
  2. A busca "b" por prefijo → encuentra a B con su pública (y a sí mismo NO).
  3. A crea el chat directo (roomId derivado como la app + wrap opaco) → 201.
  4. B sincroniza: la respuesta trae dm=[[roomId, uidA, unameA, pubA, wrap]]
     (¡aunque no haya mensajes!) → B acusa (dm_ack).
  5. B vuelve a sincronizar: ya no aparece el wrap (consumido).
  6. A envía un mensaje al chat → B lo recibe (membresía añadida por dm_new).
  7. Idempotencia: B llama dm_new para la misma pareja → exists=1 + el wrap
     guardado para B (lo creó A); A llama dm_new → exists=1 sin wrap (no hay
     ninguno dirigido a A).
  8. dm_new hacia un usuario sin clave pública (cliente viejo) → 409 peer_no_key.
  9. dm_new con mi propio usuario → 400 self_dm. Búsqueda sin resultados.
 10. Limpieza BD (Management API).

Uso: python3 backend/scripts/e2e_dm.py
"""
from __future__ import annotations

import base64
import json
import os
import secrets as pysecrets
import sys
import time
import uuid as uuidlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

import e2e_rooms_client as e2e  # noqa: E402

INVITE = os.environ.get("REG_INVITE_CODE", "")


def pj(raw):
    """Parse JSON tolerante a gzip (los 4xx también llegan comprimidos)."""
    if not raw:
        return {}
    if raw[:2] == b"\x1f\x8b":
        import gzip as gz
        raw = gz.decompress(raw)
    try:
        return json.loads(raw)
    except Exception:
        return {}


def sync(token: str, since: int):
    st, _, raw = e2e.req("GET", f"/sync?s={since}&l=100", None, token)
    if not raw:
        return st, None
    if raw[:2] == b"\x1f\x8b":  # gzip (el /sync comprime >1 KB)
        import gzip as gz
        raw = gz.decompress(raw)
    try:
        return st, json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return st, None


def main() -> int:
    dev_a, dev_b, dev_c = (str(uuidlib.uuid4()) for _ in range(3))
    stamp = int(time.time()) % 100000
    ua, ub, uc = f"alaindm{stamp}", f"ameliadm{stamp}", f"viejo{stamp}"
    pub_a, pub_b = pysecrets.token_bytes(32), pysecrets.token_bytes(32)
    b64a = base64.b64encode(pub_a).decode()
    b64b = base64.b64encode(pub_b).decode()

    print(f"== E2E chats directos: @{ua} @{ub} @{uc} ==")
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a,
                                           "ic": INVITE, "pk": b64a})
    e2e.check("registro A (con pública)", st == 201 and "tok" in json.loads(raw))
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a,
                                           "ic": INVITE, "pk": b64a})
    e2e.check("registro A: usuario duplicado rechazado", st == 409)
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b,
                                           "ic": INVITE, "pk": b64b})
    tok_b = json.loads(raw)["tok"] if st == 201 else ""
    e2e.check("registro B (con pública)", st == 201 and bool(tok_b))
    # C se registra SIN pública (cliente viejo)
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": uc, "p": "clave-de-prueba-8", "d": dev_c,
                                           "ic": INVITE})
    tok_c = json.loads(raw)["tok"] if st == 201 else ""
    e2e.check("registro C SIN pública (cliente viejo)", st == 201 and bool(tok_c))

    # login de A (re-sube su pública — el camino del teléfono que re-entra)
    st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": ua, "p": "clave-de-prueba-8",
                                           "d": dev_a, "pk": b64a})
    tok_a = json.loads(raw)["tok"] if st == 200 else ""
    e2e.check("login A re-sube su pública", st == 200 and bool(tok_a))

    # ── búsqueda ──
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": f"ameliadm{stamp}"[:20]}, tok_a)
    rows = json.loads(raw).get("r", []) if st == 200 else []
    e2e.check("búsqueda por prefijo encuentra a B con su pública",
              st == 200 and len(rows) == 1 and rows[0][1] == ub and rows[0][2] == b64b,
              f"rows={rows}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": ua[: len(ua)]}, tok_a)
    e2e.check("la búsqueda nunca me devuelve a mí mismo",
              st == 200 and all(r[1] != ua for r in json.loads(raw).get("r", [])))
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": f"nadie{stamp}"[:24]}, tok_a)
    e2e.check("búsqueda sin resultados → lista vacía",
              st == 200 and json.loads(raw).get("r") == [])

    # ── creación del chat directo (roomId derivado como la app + wrap opaco) ──
    room = str(uuidlib.uuid4())
    wrap = base64.b64encode(pysecrets.token_bytes(66)).decode()
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": room, "peer": ub, "w": wrap}, tok_a)
    e2e.check("dm_new crea el chat (201, exists=0)",
              st == 201 and pj(raw).get("exists") == 0 and pj(raw).get("room") == room)

    # ── pickup por /sync ──
    st, resp = sync(tok_b, 0)
    dm = (resp or {}).get("dm", [])
    e2e.check("el /sync de B trae el wrap (con pública y nombre de A)",
              st == 200 and len(dm) == 1 and dm[0][0] == room and dm[0][1] != ""
              and dm[0][3] == b64a and dm[0][4] == wrap, f"dm={dm}")

    st, _, raw = e2e.req("POST", "/auth", {"a": "dm_ack", "r": room}, tok_b)
    e2e.check("dm_ack de B aceptado", st == 200)
    st, resp = sync(tok_b, 0)
    e2e.check("tras el ack el wrap ya no se sirve", st in (200, 204) and not (resp or {}).get("dm"))

    # ── mensaje al chat directo (membresía de ambos por dm_new) ──
    msg_id = str(uuidlib.uuid4())
    inner = list()
    inner.append(msg_id)
    inner.append(base64.b64encode(pysecrets.token_bytes(64)).decode())
    inner.append(base64.b64encode(pysecrets.token_bytes(12)).decode())
    inner.append(int(time.time() * 1000))
    batch = list()
    batch.append(inner)
    body = dict()
    body["room"] = room
    body["m"] = batch
    st, _, raw = e2e.req("POST", "/outbox", body, tok_a)
    e2e.check("A envía un mensaje al chat directo", st == 200, f"{st} {pj(raw)}")
    st, resp = sync(tok_b, 0)
    got = any(m[0] == msg_id for m in (resp or {}).get("m", []))
    e2e.check("B recibe el mensaje del chat directo", st == 200 and got)

    # ── idempotencia por pareja ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ua, "w": wrap}, tok_b)
    r = pj(raw)
    e2e.check("B repite dm_new → el mismo chat (exists=1) + wrap para B",
              st == 200 and r.get("exists") == 1 and r.get("room") == room and r.get("w") == wrap)
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ub, "w": wrap}, tok_a)
    r = pj(raw)
    e2e.check("A repite dm_new → exists=1 SIN wrap para A",
              st == 200 and r.get("exists") == 1 and r.get("room") == room and not r.get("w"))

    # ── errores esperados ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": uc, "w": wrap}, tok_a)
    e2e.check("dm_new a usuario SIN clave pública → 409 peer_no_key",
              st == 409 and pj(raw).get("e") == "peer_no_key")
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ua, "w": wrap}, tok_a)
    e2e.check("dm_new conmigo mismo → 400 self_dm",
              st == 400 and pj(raw).get("e") == "self_dm")
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": "usuario_inexistente", "w": wrap}, tok_a)
    e2e.check("dm_new a usuario inexistente → 404 no_user",
              st == 404 and pj(raw).get("e") == "no_user")

    # ── limpieza ──
    cleanup = [
        "delete from messages where room_id = '%s'::uuid" % room,
        "delete from room_members where room_id = '%s'::uuid" % room,
        "delete from dm_keys where room_id = '%s'::uuid" % room,
        "delete from rooms where room_id = '%s'::uuid" % room,
        "delete from sessions where user_id in (select id from users where username in ('%s','%s','%s'))" % (ua, ub, uc),
        "delete from users where username in ('%s','%s','%s')" % (ua, ub, uc),
    ]
    ok = True
    for s in cleanup:
        st2, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st2 not in api.OK:
            ok = False
            print(f"  limpieza: {st2} {str(res)[:150]}")
    e2e.check("limpieza BD", ok)

    print(f"\n{'=' * 60}\nRESULTADO CHATS DIRECTOS: {e2e.PASS} PASS · {e2e.FAIL} FAIL")
    return 0 if e2e.FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
