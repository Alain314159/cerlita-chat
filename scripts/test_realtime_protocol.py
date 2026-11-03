#!/usr/bin/env python3
"""Validación EN VIVO del protocolo Supabase Realtime para el 'timbre' (D63).

Prueba exactamente lo que implementará la app Android (cliente Phoenix v1.0.0
a mano sobre OkHttp) y el Edge Function outbox (broadcast REST):

  1. Conecta WS a wss://<ref>.supabase.co/realtime/v1/websocket?apikey=<anon>&vsn=1.0.0
  2. phx_join a un canal con config de broadcast (lo mismo que mandará el Kotlin)
  3. heartbeat phoenix cada 30 s (verifica phx_reply ok)
  4. Broadcast REST (Authorization: service_role) al mismo canal
  5. Verifica que el evento llega con el topic/payload esperados

Responde la pregunta abierta del formato del topic en el REST broadcast:
¿"realtime:<nombre>" o "<nombre>"?
"""
import asyncio, json, os, sys, time, urllib.request

sys.path.insert(0, "/home/z/my-project/app/backend/scripts")
import api as supa  # carga secrets/supabase.env (fuera del repo)

import websockets

env = supa.load_env() or {}
env = {k: os.environ[k] for k in os.environ if k.startswith("SUPA")}
REF = env["SUPA_REF"]
ANON = env["SUPA_ANON_KEY"]
SROLE = env["SUPA_SERVICE_ROLE_KEY"]
BASE = f"https://{REF}.supabase.co"
WS_URL = f"wss://{REF}.supabase.co/realtime/v1/websocket?apikey={ANON}&vsn=1.0.0"

CHAN = "db-doorbell-test-1"        # nombre de canal SIN prefijo (como channel('x') de JS)
TOPIC = f"realtime:{CHAN}"         # topic phoenix CON prefijo


def rest_broadcast(topic: str, event: str, payload: dict) -> tuple[int, str]:
    body = json.dumps({"messages": [{"topic": topic, "event": event, "payload": payload}]}).encode()
    req = urllib.request.Request(
        f"{BASE}/realtime/v1/api/broadcast",
        data=body, method="POST",
        headers={
            "Authorization": f"Bearer {SROLE}",
            "apikey": ANON,
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


async def main():
    print(f"[1] Conectando a {WS_URL[:60]}...")
    results = {}

    async with websockets.connect(WS_URL) as ws:
        # ── join con config de broadcast (igual que hará el Kotlin) ──
        join = {
            "topic": TOPIC,
            "event": "phx_join",
            "payload": {"config": {"broadcast": {"self": False}, "presence": {"key": ""}}},
            "ref": "1",
        }
        await ws.send(json.dumps(join))
        print(f"[2] phx_join enviado a {TOPIC}")

        # ── heartbeat inmediato para verificar el phx_reply ──
        await ws.send(json.dumps({"topic": "phoenix", "event": "heartbeat", "payload": {}, "ref": "2"}))
        print("[3] heartbeat enviado")

        # ── broadcast REST CON prefijo realtime: ──
        await asyncio.sleep(1.0)
        code, body = rest_broadcast(TOPIC, "new-msg", {"roomId": "11111111-1111-1111-1111-111111111111"})
        print(f"[4] REST broadcast topic={TOPIC!r} -> HTTP {code} {body[:120]}")
        results["rest_con_prefijo"] = code

        got_prefix = None
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            for line in [raw]:
                m = json.loads(line)
                print(f"    <- {json.dumps(m)[:200]}")
                if m.get("event") == "broadcast":
                    got_prefix = m
        except asyncio.TimeoutError:
            print("    (sin mensaje en 5 s)")

        # ── si no llegó: probar SIN prefijo ──
        if got_prefix is None:
            code, body = rest_broadcast(CHAN, "new-msg", {"roomId": "22222222-2222-2222-2222-222222222222"})
            print(f"[5] REST broadcast SIN prefijo topic={CHAN!r} -> HTTP {code} {body[:120]}")
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=5)
                m = json.loads(raw)
                print(f"    <- {json.dumps(m)[:200]}")
                if m.get("event") == "broadcast":
                    got_prefix = m
                    results["formato_ganador"] = "SIN prefijo"
            except asyncio.TimeoutError:
                print("    (sin mensaje en 5 s)")
        else:
            results["formato_ganador"] = "CON prefijo realtime:"

        # ── consume el phx_reply del join/heartbeat que quedó en cola ──
        try:
            while True:
                raw = await asyncio.wait_for(ws.recv(), timeout=1.5)
                m = json.loads(raw)
                print(f"    (en cola) <- {json.dumps(m)[:200]}")
                if m.get("event") == "phx_reply" and m.get("ref") == "1":
                    results["join_ok"] = m["payload"].get("status") == "ok"
                if m.get("event") == "phx_reply" and m.get("ref") == "2":
                    results["heartbeat_ok"] = m["payload"].get("status") == "ok"
        except asyncio.TimeoutError:
            pass

    print("\n=== RESULTADO ===")
    print(json.dumps(results, indent=2, ensure_ascii=False))
    ok = results.get("join_ok") and results.get("heartbeat_ok") and ("formato_ganador" in results)
    print("\nPROTOCOLO VALIDADO ✅" if ok else "\nFALLO ❌")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
