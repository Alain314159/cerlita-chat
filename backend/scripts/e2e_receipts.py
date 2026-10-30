#!/usr/bin/env python3
"""E2E de RECIBOS DE ENTREGA (rd, D68) y ROOM_LEAVE (D70) contra producción.

Valida el bucle completo que usará la app Android v0.6.0:

  1. A y B se registran; A crea sala; B se une (roomId derivado como la app)
  2. A envía M1 → B sincroniza (s=0) → B ya "tiene" M1 (delivered_seq)
  3. A sincroniza: la respuesta trae rd=[[room, seq_M1]] → A puede marcar ✓✓
  4. B envía M2 y sincroniza su eco → B delivered=M2
  5. A sincroniza SIN mensajes nuevos → 200 con m:[] y rd=[[room, seq_M2]]
     (el camino nuevo: antes era 204 y la entrega se perdía hasta el
     siguiente mensaje entrante)
  6. B sale de la sala (room_leave): ya no la ve en rooms ni sincroniza M3
  7. A (propietario) sigue viendo la sala y su historial
  8. A sale → sala vacía → se borra entera: room_join da 404 no_room
  9. Limpieza BD (Management API)

Uso: python3 backend/scripts/e2e_receipts.py
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

import e2e_rooms_client as e2e  # noqa: E402  (reutiliza req/check/cripto espejo)

INVITE = os.environ.get("REG_INVITE_CODE", "")


def send_msg(token: str, rid: str, msg_id: str) -> int:
    """POST /outbox con ciphertext opaco (probamos la entrega, no el cifrado)."""
    st, _, raw = e2e.req(
        "POST", "/outbox",
        {"room": rid, "m": [[msg_id, base64.b64encode(pysecrets.token_bytes(64)).decode(),
                             base64.b64encode(pysecrets.token_bytes(12)).decode(),
                             int(time.time() * 1000)]]},
        token,
    )
    return st, raw


def sync(token: str, since: int):
    """GET /sync → (status, parsed_json_or_none)."""
    st, _, raw = e2e.req("GET", f"/sync?s={since}&l=100", None, token)
    try:
        return st, json.loads(raw) if raw else None
    except json.JSONDecodeError:
        return st, None


def rooms_of(token: str):
    """POST rooms → set de room_ids, o None si la llamada falla."""
    st, _, raw = e2e.req("POST", "/auth", {"a": "rooms"}, token)
    if st != 200:
        return None
    return {r[0] for r in json.loads(raw).get("rooms", [])}


def check_gone(who: str, token: str, rid: str) -> None:
    rooms = rooms_of(token)
    e2e.check(f"{who} ya no ve la sala (rooms ok)", rooms is not None,
              "la llamada rooms falló")
    if rooms is not None:
        e2e.check(f"{who} ya no ve la sala", rid not in rooms, f"rooms={rooms}")


def main() -> int:
    dev_a, dev_b, dev_c = (str(uuidlib.uuid4()) for _ in range(3))
    stamp = int(time.time()) % 100000
    ua, ub, uc = f"rcpt_a_{stamp}", f"rcpt_b_{stamp}", f"rcpt_c_{stamp}"

    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    e2e.check("A registra", st == 201, f"{st} {raw[:120]}")
    tok_a = json.loads(raw)["tok"]

    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    e2e.check("B registra", st == 201, f"{st} {raw[:120]}")
    tok_b = json.loads(raw)["tok"]

    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": uc, "p": "clave-de-prueba-8", "d": dev_c, "ic": INVITE})
    e2e.check("C registra (para el join post-borrado)", st == 201, f"{st} {raw[:120]}")
    tok_c = json.loads(raw)["tok"]

    room_key, room_salt = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    rid = e2e.room_id(room_key, room_salt)
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_new", "r": rid}, tok_a)
    e2e.check("A room_new", st == 201, f"{st} {raw[:120]}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_join", "r": rid}, tok_b)
    e2e.check("B room_join", st == 200, f"{st} {raw[:120]}")

    # ── 1. A envía M1; B lo baja → delivered_seq(B)=seq_M1 ──
    m1 = e2e.uuid7()
    st, raw = send_msg(tok_a, rid, m1)
    e2e.check("A envía M1", st == 200, f"{st} {raw[:120]}")

    st, j = sync(tok_b, 0)
    seq_m1 = None
    if st == 200 and j:
        row = next((x for x in j.get("m", []) if x[0] == m1), None)
        seq_m1 = row[3] if row else None
    e2e.check("B baja M1 y conoce su seq", seq_m1 is not None, f"st={st}")

    # ── 2. A sincroniza: rd debe traer la entrega de M1 (camino normal) ──
    st, j = sync(tok_a, 0)
    rd = dict((r[0], r[1]) for r in (j or {}).get("rd", []))
    e2e.check("A sync trae rd con la sala", st == 200 and rid in rd, f"st={st} rd={j.get('rd') if j else None}")
    e2e.check("rd de A = seq de M1 (entregado ✓✓)", rd.get(rid) == seq_m1,
              f"rd={rd.get(rid)} seq_m1={seq_m1}")

    # ── 3. B envía M2 y sincroniza su eco → delivered_seq(B)=seq_M2 ──
    m2 = e2e.uuid7()
    st, raw = send_msg(tok_b, rid, m2)
    e2e.check("B envía M2", st == 200, f"{st} {raw[:120]}")
    seq_m2 = None
    if st == 200:
        st, j = sync(tok_b, seq_m1)
        if st == 200 and j:
            row = next((x for x in j.get("m", []) if x[0] == m2), None)
            seq_m2 = row[3] if row else None
    e2e.check("B sincroniza su eco M2", seq_m2 is not None, f"st={st}")

    # ── 4. A sincroniza SIN mensajes nuevos → 200 con m:[] y rd actualizado ──
    st, j = sync(tok_a, seq_m2)
    ok = (st == 200 and j is not None and j.get("m") == []
          and dict((r[0], r[1]) for r in j.get("rd", [])).get(rid) == seq_m2)
    e2e.check("A sin novedades → 200 {m:[], rd} (antes 204: entrega invisible)",
              ok, f"st={st} raw={json.dumps(j)[:160] if j else '—'}")

    # ── 5. B sale de la sala (D70) ──
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_leave", "r": rid}, tok_b)
    e2e.check("B room_leave", st == 200, f"{st} {raw[:120]}")
    check_gone("B", tok_b, rid)
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_leave", "r": rid}, tok_b)
    e2e.check("room_leave idempotente (2ª vez = ok)", st == 200, f"{st} {raw[:120]}")

    # ── 6. A sigue en la sala con historial; B no recibe lo nuevo ──
    m3 = e2e.uuid7()
    st, raw = send_msg(tok_a, rid, m3)
    e2e.check("A (dueño) sigue pudiendo enviar", st == 200, f"{st} {raw[:120]}")
    st, j = sync(tok_b, 0)
    seen = any(x[0] == m3 for x in (j or {}).get("m", [])) if (st == 200 and j) else False
    e2e.check("B (fuera) NO ve M3", not seen, f"st={st}")
    e2e.check("A aún ve la sala", rid in (rooms_of(tok_a) or set()))

    # ── 7. A sale → sala vacía → borrada; el código muere con ella ──
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_leave", "r": rid}, tok_a)
    e2e.check("A room_leave", st == 200, f"{st} {raw[:120]}")
    check_gone("A", tok_a, rid)
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_join", "r": rid}, tok_c)
    e2e.check("sala borrada: join con el código viejo → 404 no_room", st == 404,
              f"{st} {raw[:120]}")

    # ── limpieza ──
    cleanup = [
        "delete from messages where room_id = '%s'::uuid" % rid,
        "delete from room_state where room_id = '%s'::uuid" % rid,
        "delete from rooms where room_id = '%s'::uuid" % rid,
        "delete from users where username in ('%s','%s','%s')" % (ua, ub, uc),
    ]
    ok = True
    for s in cleanup:
        st2, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st2 not in api.OK:
            ok = False
            print(f"  limpieza: {st2} {str(res)[:150]}")
    e2e.check("limpieza BD", ok)

    print(f"\n{'=' * 60}\nRESULTADO RECIBOS+LEAVE: {e2e.PASS} PASS · {e2e.FAIL} FAIL")
    if e2e.FAIL:
        for mark, name, detail in e2e.RESULTS:
            if mark == "FAIL":
                print(f"  ✗ {name} — {detail}")
    return 1 if e2e.FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
