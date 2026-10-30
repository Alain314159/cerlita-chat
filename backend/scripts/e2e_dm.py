#!/usr/bin/env python3
"""E2E de CHATS DIRECTOS 1-a-1 (v0.7.0 D74 + v0.19.0 D119) contra producción.

Valida el bucle completo que usará la app Android:

  1. A y B se registran con clave pública X25519 (aleatoria: la cripto real
     vive en DmCryptoTest; aquí probamos el protocolo de servidor).
     D119: el registro/login devuelven kid (id de la clave en user_keys).
  2. A busca "b" por prefijo → encuentra a B con su pública heredada (elem 2)
     Y sus claves activas [[kid, pub], …] (elem 3, D119).
  3. A crea el chat directo con ws=[[kid, wrap], …] (un wrap POR clave
     activa del par) + fk (su kid) → 201.
  4. B sincroniza: la respuesta trae dm=[[roomId, uidA, unameA, pubA, wrap], …]
     — UNA FILA POR WRAP (D119) → B acusa (dm_ack).
  5. REINSTALACIÓN (criterio de salida Fase I, lado servidor): B entra con
     una TERCERA clave (teléfono nuevo) → rooms marca kind=1 pending=0 →
     B marca dm_stuck → el /sync de A trae rr=[[room, uidB, claves activas
     de B (incluida la nueva)]] → A re-envuelve (dm_wraps) → el rr desaparece
     → el /sync de B trae el wrap nuevo → B acusa.
  6. Errores: dm_wraps de un no-miembro (403), dm_new con kid ajeno (400),
     dm_stuck con sala inválida (400), cap de 5 claves por usuario.
  7. Idempotencia por pareja y envío de mensaje (D74, intacto).
  8. Limpieza BD (Management API).

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
    e2e.check("registro A (con pública)", st == 201 and "tok" in pj(raw))
    kid_a = pj(raw).get("kid", "")
    e2e.check("D119: registro devuelve kid", bool(kid_a))
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a,
                                           "ic": INVITE, "pk": b64a})
    e2e.check("registro A: usuario duplicado rechazado", st == 409)
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b,
                                           "ic": INVITE, "pk": b64b})
    r = pj(raw)
    tok_b = r.get("tok", "") if st == 201 else ""
    kid_b1 = r.get("kid", "")
    e2e.check("registro B (con pública)", st == 201 and bool(tok_b))
    # C se registra SIN pública (cliente viejo)
    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": uc, "p": "clave-de-prueba-8", "d": dev_c,
                                           "ic": INVITE})
    tok_c = pj(raw).get("tok", "") if st == 201 else ""
    e2e.check("registro C SIN pública (cliente viejo)", st == 201 and bool(tok_c))

    # login de A (re-sube su pública — el camino del teléfono que re-entra)
    st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": ua, "p": "clave-de-prueba-8",
                                           "d": dev_a, "pk": b64a})
    r = pj(raw)
    tok_a = r.get("tok", "") if st == 200 else ""
    e2e.check("login A re-sube su pública (mismo kid: idempotente)",
              st == 200 and bool(tok_a) and r.get("kid") == kid_a)

    # ── búsqueda ──
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": f"ameliadm{stamp}"[:20]}, tok_a)
    rows = pj(raw).get("r", []) if st == 200 else []
    e2e.check("búsqueda por prefijo encuentra a B con su pública",
              st == 200 and len(rows) == 1 and rows[0][1] == ub and rows[0][2] == b64b,
              f"rows={rows}")
    e2e.check("D119: la búsqueda trae las claves activas [[kid, pub], …]",
              st == 200 and len(rows) == 1 and len(rows[0][3]) == 1
              and rows[0][3][0] == [kid_b1, b64b], f"keys={rows[0][3] if rows else None}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": ua[: len(ua)]}, tok_a)
    e2e.check("la búsqueda nunca me devuelve a mí mismo",
              st == 200 and all(r[1] != ua for r in pj(raw).get("r", [])))
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": f"nadie{stamp}"[:24]}, tok_a)
    e2e.check("búsqueda sin resultados → lista vacía",
              st == 200 and pj(raw).get("r") == [])

    # ── B añade un SEGUNDO dispositivo (D119: conjunto, no reemplazo) ──
    pub_b2 = pysecrets.token_bytes(32)
    b64b2 = base64.b64encode(pub_b2).decode()
    st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": ub, "p": "clave-de-prueba-8",
                                           "d": str(uuidlib.uuid4()), "pk": b64b2})
    kid_b2 = pj(raw).get("kid", "")
    e2e.check("D119: 2º dispositivo de B → kid distinto (no reemplaza)",
              st == 200 and bool(kid_b2) and kid_b2 != kid_b1)
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": f"ameliadm{stamp}"[:20]}, tok_a)
    keys_b = pj(raw).get("r", [])[0][3] if st == 200 else []
    e2e.check("D119: la búsqueda ve las DOS claves de B",
              len(keys_b) == 2 and [k[0] for k in keys_b] == [kid_b1, kid_b2],
              f"keys={keys_b}")

    # ── creación del chat directo: un wrap POR clave activa del par ──
    room = str(uuidlib.uuid4())
    wrap1 = base64.b64encode(pysecrets.token_bytes(66)).decode()  # para kid_b1
    wrap2 = base64.b64encode(pysecrets.token_bytes(66)).decode()  # para kid_b2
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": room, "peer": ub, "w": wrap2, "fk": kid_a,
                          "ws": [[kid_b1, wrap1], [kid_b2, wrap2]]}, tok_a)
    e2e.check("dm_new crea el chat con wraps multi-dispositivo (201, exists=0)",
              st == 201 and pj(raw).get("exists") == 0 and pj(raw).get("room") == room,
              f"{st} {pj(raw)}")

    # ── pickup por /sync: UNA FILA POR wrap ──
    st, resp = sync(tok_b, 0)
    dm = (resp or {}).get("dm", [])
    e2e.check("el /sync de B trae los DOS wraps (uno por dispositivo)",
              st == 200 and len(dm) == 2 and all(d[0] == room for d in dm)
              and sorted(d[4] for d in dm) == sorted([wrap1, wrap2])
              and all(d[3] == b64a for d in dm), f"dm={dm}")

    st, _, raw = e2e.req("POST", "/auth", {"a": "dm_ack", "r": room}, tok_b)
    e2e.check("dm_ack de B aceptado (los dos)", st == 200)
    st, resp = sync(tok_b, 0)
    e2e.check("tras el ack los wraps ya no se sirven", st in (200, 204) and not (resp or {}).get("dm"))

    # ── mensaje al chat directo (membresía de ambos por dm_new) ──
    msg_id = str(uuidlib.uuid4())
    inner = [msg_id,
             base64.b64encode(pysecrets.token_bytes(64)).decode(),
             base64.b64encode(pysecrets.token_bytes(12)).decode(),
             int(time.time() * 1000)]
    st, _, raw = e2e.req("POST", "/outbox", {"room": room, "m": [inner]}, tok_a)
    e2e.check("A envía un mensaje al chat directo", st == 200, f"{st} {pj(raw)}")
    st, resp = sync(tok_b, 0)
    got = any(m[0] == msg_id for m in (resp or {}).get("m", []))
    e2e.check("B recibe el mensaje del chat directo", st == 200 and got)

    # ── idempotencia por pareja (exists=1; wraps consumidos ya no se sirven) ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ua, "w": wrap1}, tok_b)
    r = pj(raw)
    e2e.check("B repite dm_new → el mismo chat (exists=1)",
              st == 200 and r.get("exists") == 1 and r.get("room") == room)
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ub, "w": wrap1}, tok_a)
    r = pj(raw)
    e2e.check("A repite dm_new → exists=1 SIN wraps pendientes para A",
              st == 200 and r.get("exists") == 1 and r.get("room") == room and not r.get("w"))

    # ════ REINSTALACIÓN de B: el flujo completo de la Fase I (D119) ════
    print("== Fase I: B cambia de teléfono (3ª clave) ==")
    pub_b3 = pysecrets.token_bytes(32)
    b64b3 = base64.b64encode(pub_b3).decode()
    st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": ub, "p": "clave-de-prueba-8",
                                           "d": str(uuidlib.uuid4()), "pk": b64b3})
    tok_b3 = pj(raw).get("tok", "")
    kid_b3 = pj(raw).get("kid", "")
    e2e.check("B entra desde el teléfono nuevo (3ª clave registrada)",
              st == 200 and bool(tok_b3) and bool(kid_b3))

    # rooms del teléfono nuevo: chat directo, SIN wraps pendientes → aviso
    st, _, raw = e2e.req("POST", "/auth", {"a": "rooms"}, tok_b3)
    rooms = pj(raw).get("rooms", [])
    e2e.check("rooms del teléfono nuevo: kind=1 y pending=0 (→ aviso de recuperación)",
              st == 200 and any(r[0] == room and r[2] == 1 and r[3] == 0 for r in rooms),
              f"rooms={rooms}")

    # B marca que no puede abrir → aviso al par
    st, _, raw = e2e.req("POST", "/auth", {"a": "dm_stuck", "r": room}, tok_b3)
    e2e.check("dm_stuck de B aceptado", st == 200)

    # el /sync de A ve el pedido CON las claves activas de B (la 3ª incluida)
    st, resp = sync(tok_a, 0)
    rr = (resp or {}).get("rr", [])
    e2e.check("el /sync de A trae el pedido rr con las claves activas de B",
              st == 200 and len(rr) == 1 and rr[0][0] == room
              and [k[0] for k in rr[0][2]] == [kid_b1, kid_b2, kid_b3], f"rr={rr}")

    # A re-envuelve para el teléfono nuevo
    wrap3 = base64.b64encode(pysecrets.token_bytes(66)).decode()
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_wraps", "r": room, "fk": kid_a,
                          "ws": [[kid_b3, wrap3]]}, tok_a)
    e2e.check("dm_wraps de A aceptado (re-emparejamiento)", st == 200, f"{st} {pj(raw)}")

    # el pedido desaparece del sync de A
    st, resp = sync(tok_a, 0)
    e2e.check("tras re-emparejar, el rr desaparece del /sync de A",
              st in (200, 204) and not (resp or {}).get("rr"))

    # rooms de B ahora pending=1 (wrap sin consumir) y el sync lo trae
    st, _, raw = e2e.req("POST", "/auth", {"a": "rooms"}, tok_b3)
    rooms = pj(raw).get("rooms", [])
    e2e.check("rooms de B: pending=1 (el wrap nuevo espera)",
              st == 200 and any(r[0] == room and r[3] == 1 for r in rooms), f"rooms={rooms}")
    st, resp = sync(tok_b3, 0)
    dm = (resp or {}).get("dm", [])
    e2e.check("el /sync del teléfono nuevo de B trae el wrap re-hecho",
              st == 200 and len(dm) == 1 and dm[0][0] == room and dm[0][4] == wrap3
              and dm[0][3] == b64a, f"dm={dm}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "dm_ack", "r": room}, tok_b3)
    e2e.check("dm_ack del teléfono nuevo", st == 200)

    # ── errores esperados (D74, intactos) ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": uc, "w": wrap1}, tok_a)
    e2e.check("dm_new a usuario SIN clave pública → 409 peer_no_key",
              st == 409 and pj(raw).get("e") == "peer_no_key")
    # C actualiza y ya tiene clave: sirve para probar el kid ajeno con par NUEVO
    pub_c1 = base64.b64encode(pysecrets.token_bytes(32)).decode()
    st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": uc, "p": "clave-de-prueba-8",
                                           "d": str(uuidlib.uuid4()), "pk": pub_c1})
    e2e.check("C entra y sube su 1ª clave", st == 200)
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": uc,
                          "ws": [[kid_a, wrap1]]}, tok_a)
    e2e.check("dm_new con kid que NO es del par → 400 bad_wrap",
              st == 400 and pj(raw).get("e") == "bad_wrap", f"{st} {pj(raw)}")
    # ── errores esperados del protocolo nuevo ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_wraps", "r": room, "ws": [[kid_b3, wrap3]]}, tok_c)
    e2e.check("dm_wraps de un NO miembro → 403 not_member",
              st == 403 and pj(raw).get("e") == "not_member", f"{st} {pj(raw)}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "dm_stuck", "r": "no-es-uuid"}, tok_a)
    e2e.check("dm_stuck con sala inválida → 400", st == 400)

    # ── errores esperados (D74, intactos) ──
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": ua, "w": wrap1}, tok_a)
    e2e.check("dm_new conmigo mismo → 400 self_dm",
              st == 400 and pj(raw).get("e") == "self_dm")
    st, _, raw = e2e.req("POST", "/auth",
                         {"a": "dm_new", "r": str(uuidlib.uuid4()), "peer": "usuario_inexistente", "w": wrap1}, tok_a)
    e2e.check("dm_new a usuario inexistente → 404 no_user",
              st == 404 and pj(raw).get("e") == "no_user")

    # ── cap de claves por usuario (D119: 5 activas) ──
    for i in range(6):
        pk = base64.b64encode(pysecrets.token_bytes(32)).decode()
        st, _, raw = e2e.req("POST", "/auth", {"a": "in", "u": uc, "p": "clave-de-prueba-8",
                                               "d": str(uuidlib.uuid4()), "pk": pk})
        if st != 200:
            break
    st, _, raw = e2e.req("POST", "/auth", {"a": "search", "q": uc[:20]}, tok_a)
    keys_c = pj(raw).get("r", [])[0][3] if st == 200 else []
    e2e.check("cap D119: C nunca supera 5 claves activas (la más vieja se retira)",
              st == 200 and len(keys_c) == 5, f"n={len(keys_c)}")
    # ── limpieza ──
    cleanup = [
        # defensivo: cualquier sala huérfana creada por los usuarios de prueba
        "delete from messages where room_id in (select room_id from rooms where created_by in (select id from users where username in ('%s','%s','%s')))" % (ua, ub, uc),
        "delete from room_members where room_id in (select room_id from rooms where created_by in (select id from users where username in ('%s','%s','%s')))" % (ua, ub, uc),
        "delete from dm_keys where room_id in (select room_id from rooms where created_by in (select id from users where username in ('%s','%s','%s')))" % (ua, ub, uc),
        "delete from rooms where created_by in (select id from users where username in ('%s','%s','%s'))" % (ua, ub, uc),
        "delete from sessions where user_id in (select id from users where username in ('%s','%s','%s'))" % (ua, ub, uc),
        "delete from user_keys where user_id in (select id from users where username in ('%s','%s','%s'))" % (ua, ub, uc),
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
