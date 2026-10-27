#!/usr/bin/env python3
"""E2E del TIMBRE Realtime (D63) contra producción: outbox → broadcast → WS.

Valida el bucle completo que usará la app Android:

  1. A y B se registran; A crea sala (roomId DERIVADO como la app); B se une
  2. Suscriptor WebSocket (anon key) hace phx_join a realtime:room-<roomId>
     — exactamente lo que hará el DoorbellService de la app
  3. A envía por /outbox → la Edge Function toca el timbre (REST broadcast
     con service_role, topic SIN prefijo — hallazgo 10)
  4. El suscriptor recibe {"event":"new-msg","payload":{"roomId":...}}
  5. Reintento idempotente del MISMO msg_id → NO se vuelve a tocar el timbre
  6. B sincroniza por /sync (la fuente de verdad) y ve el mensaje
  7. Limpieza BD (Management API)

Uso: python3 backend/scripts/e2e_doorbell.py   (requiere websockets: pip)
"""
from __future__ import annotations

import asyncio
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

import websockets  # noqa: E402
import e2e_rooms_client as e2e  # noqa: E402  (reutiliza req/check/cripto espejo)

BASE_WS = os.environ["SUPA_URL"].replace("https://", "wss://")
ANON = os.environ.get("SUPA_ANON_KEY", "")
INVITE = os.environ.get("REG_INVITE_CODE", "")


def send_msg(token: str, rid: str, msg_id: str, nonce: bytes, ct: bytes):
    """POST /outbox con un mensaje (ciphertext opaco: probamos el timbre, no el cifrado)."""
    st, _, raw = e2e.req(
        "POST", "/outbox",
        {"room": rid, "m": [[msg_id, base64.b64encode(ct).decode(),
                             base64.b64encode(nonce).decode(),
                             int(time.time() * 1000)]]},
        token,
    )
    return st, raw


async def recv_until(ws, pred, timeout: float):
    """Consume frames hasta que pred(frame) sea True. Devuelve el frame o None."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        remain = deadline - asyncio.get_event_loop().time()
        if remain <= 0:
            return None
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remain)
        except asyncio.TimeoutError:
            return None
        m = json.loads(raw)
        if pred(m):
            return m


async def ws_doorbell_flow(rid: str, tok_a: str, tok_b: str) -> None:
    url = f"{BASE_WS}/realtime/v1/websocket?apikey={ANON}&vsn=1.0.0"
    chan = f"room-{rid}"
    async with websockets.connect(url) as ws:
        # join (lo mismo que mandará el Kotlin)
        await ws.send(json.dumps({
            "topic": f"realtime:{chan}", "event": "phx_join",
            "payload": {"config": {"broadcast": {"self": False}, "presence": {"key": ""}}},
            "ref": "1",
        }))
        r = await recv_until(ws, lambda m: m.get("event") == "phx_reply" and m.get("ref") == "1", 8)
        e2e.check("WS phx_join ok", r is not None and r["payload"].get("status") == "ok")

        # heartbeat
        await ws.send(json.dumps({"topic": "phoenix", "event": "heartbeat", "payload": {}, "ref": "2"}))
        r = await recv_until(ws, lambda m: m.get("event") == "phx_reply" and m.get("ref") == "2", 8)
        e2e.check("WS heartbeat ok", r is not None and r["payload"].get("status") == "ok")

        # ── timbre por mensaje nuevo ──
        m1 = e2e.uuid7()
        nonce1, ct1 = pysecrets.token_bytes(12), pysecrets.token_bytes(64)
        st, raw = await asyncio.to_thread(send_msg, tok_a, rid, m1, nonce1, ct1)
        e2e.check("A envía (outbox 200)", st == 200, f"{st} {raw[:120]}")

        r = await recv_until(ws, lambda m: m.get("event") == "broadcast", 8)
        ok = (r is not None
              and r.get("topic") == f"realtime:{chan}"
              and r["payload"].get("event") == "new-msg"
              and r["payload"].get("payload", {}).get("roomId") == rid)
        e2e.check("timbre llega al WS con roomId (sin contenido)", ok,
                  json.dumps(r)[:160] if r else "sin broadcast en 8 s")

        # ── 2º mensaje → 2º timbre ──
        m2 = e2e.uuid7()
        st, _ = await asyncio.to_thread(send_msg, tok_a, rid, m2,
                                        pysecrets.token_bytes(12), pysecrets.token_bytes(64))
        r = await recv_until(ws, lambda m: m.get("event") == "broadcast", 8)
        ok = r is not None and r["payload"].get("payload", {}).get("roomId") == rid
        e2e.check("2º mensaje → 2º timbre", ok)

        # ── reintento idempotente del MISMO msg_id → SIN timbre ──
        st, raw = await asyncio.to_thread(
            send_msg, tok_a, rid, m2, pysecrets.token_bytes(12), pysecrets.token_bytes(64))
        # nota: mismo msg_id m2 (el ct/nonce no importan: ON CONFLICT DO NOTHING)
        e2e.check("reintento idempotente acked", st == 200 and m2 in json.loads(raw).get("a", []))
        r = await recv_until(ws, lambda m: m.get("event") == "broadcast", 3.5)
        e2e.check("reintento NO vuelve a tocar el timbre", r is None,
                  "llegó broadcast inesperado: " + json.dumps(r)[:120] if r else "")

        # ── /sync sigue siendo la fuente de verdad ──
        st, _, raw = await asyncio.to_thread(e2e.req, "GET", "/sync?s=0&l=100", None, tok_b)
        rows = [x for x in json.loads(raw).get("m", []) if x[0] in (m1, m2)]
        e2e.check("B ve ambos mensajes por /sync", len(rows) == 2, f"n={len(rows)}")


def main() -> int:
    dev_a, dev_b = str(uuidlib.uuid4()), str(uuidlib.uuid4())
    stamp = int(time.time()) % 100000
    ua, ub = f"bell_a_{stamp}", f"bell_b_{stamp}"

    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    e2e.check("A registra", st == 201, f"{st} {raw[:120]}")
    tok_a, uid_a = json.loads(raw)["tok"], json.loads(raw)["uid"]

    st, _, raw = e2e.req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    e2e.check("B registra", st == 201, f"{st} {raw[:120]}")
    tok_b = json.loads(raw)["tok"]

    room_key, room_salt = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    rid = e2e.room_id(room_key, room_salt)
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_new", "r": rid}, tok_a)
    e2e.check("A room_new", st == 201, f"{st} {raw[:120]}")
    st, _, raw = e2e.req("POST", "/auth", {"a": "room_join", "r": rid}, tok_b)
    e2e.check("B room_join", st == 200, f"{st} {raw[:120]}")

    asyncio.run(ws_doorbell_flow(rid, tok_a, tok_b))

    cleanup = [
        "delete from messages where room_id = '%s'::uuid" % rid,
        "delete from rooms where room_id = '%s'::uuid" % rid,
        "delete from users where username in ('%s','%s')" % (ua, ub),
    ]
    ok = True
    for s in cleanup:
        st2, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st2 not in api.OK:
            ok = False
            print(f"  limpieza: {st2} {str(res)[:150]}")
    e2e.check("limpieza BD", ok)

    print(f"\n{'=' * 60}\nRESULTADO TIMBRE: {e2e.PASS} PASS · {e2e.FAIL} FAIL")
    if e2e.FAIL:
        for mark, name, detail in e2e.RESULTS:
            if mark == "FAIL":
                print(f"  ✗ {name} — {detail}")
    return 1 if e2e.FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
