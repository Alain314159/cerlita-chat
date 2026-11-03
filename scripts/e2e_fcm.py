#!/usr/bin/env python3
"""E2E del canal FCM (D121, 5ª capa de despertar) contra producción.

Flujo completo con las funciones YA desplegadas y DEBUG=1:
  1. registro de alice + bob (invite)
  2. alice crea sala, bob se une
  3. bob registra un token FCM FALSO vía POST /push {t: ...}
     (longitud realista ~170 chars; Google lo rechazará → prueba el camino
      OAuth + envío + auto-limpieza, sin gastar cuota de FCM)
  4. alice envía un mensaje vía /outbox → la respuesta DEBUG trae {fcm: "..."}
  5. VERIFICACIÓN: fcm NO es "skip" ni "no-sa" → OAuth contra Google OK y
     envío intentado; el diag muestra "sent X/Y" o el estado del token
  6. verificación en BD: el token muerto quedó auto-limpieado (fcm_token NULL)
  7. limpieza total (usuarios, sala, mensajes)

Nada confidencial se imprime: solo estados y conteos.
"""
from __future__ import annotations

import json
import secrets as pysecrets
import sys
import time
import urllib.error
import urllib.request
import uuid as uuidlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend" / "scripts"))
import api  # noqa: E402

BASE = "https://wuptkddnkahmknolpxtp.supabase.co/functions/v1"


def load_env() -> dict:
    env = {}
    for line in open(api.DEFAULT_ENV_FILE, encoding="utf-8"):
        line = line.strip()
        if line.startswith("export "):
            k, _, v = line[7:].partition("=")
            env[k.strip()] = v.strip().strip('"')
    return env


def req(method: str, path: str, body=None, token: str | None = None):
    headers = {"content-type": "application/json"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def main() -> int:
    env = load_env()
    invite = env["REG_INVITE_CODE"]
    room = str(uuidlib.uuid4())
    dev = str(uuidlib.uuid4())
    # Token FCM falso con formato realista (letras+dígitos, ~170 chars)
    fake_tok = pysecrets.token_hex(85)

    print("== 1. registro alice_t + bob_t ==")
    st, r = req("POST", "/auth", {"a": "reg", "u": "alice_t", "p": "clave-fcm-1", "d": dev, "ic": invite})
    assert st == 201, f"alice reg {st} {r}"
    alice = r["tok"]
    st, r = req("POST", "/auth", {"a": "reg", "u": "bob_t", "p": "clave-fcm-2", "d": str(uuidlib.uuid4()), "ic": invite})
    assert st == 201, f"bob reg {st} {r}"
    bob = r["tok"]
    print("   ambos 201 OK")

    print("== 2. sala + membresía ==")
    st, r = req("POST", "/auth", {"a": "room_new", "r": room}, token=alice)
    assert st == 201, f"room_new {st} {r}"
    st, r = req("POST", "/auth", {"a": "room_join", "r": room}, token=bob)
    assert st == 200, f"room_join {st} {r}"
    print("   sala lista")

    print("== 3. bob registra token FCM falso vía /push ==")
    st, r = req("POST", "/push", {"t": fake_tok}, token=bob)
    assert st == 200 and r.get("ok"), f"push reg {st} {r}"
    print(f"   /push 200 {r}")

    print("== 4. alice envía mensaje → outbox con DEBUG=1 ==")
    import base64
    mid = str(uuidlib.uuid4())
    nonce = base64.b64encode(b"0" * 12).decode()  # fijo: es solo prueba
    ct = base64.b64encode(secrets_box()).decode()
    st, r = req("POST", "/outbox", {"room": room, "m": [[mid, ct, nonce, int(time.time() * 1000)]]}, token=alice)
    print(f"   outbox {st}: {r}")
    assert st == 200, f"outbox {st}"
    fcm = r.get("fcm", "(ausente)")
    print(f"   diag fcm: {fcm!r} | bell: {r.get('bell')!r} | n_ins: {r.get('n_ins')}")

    # 5. verificación del canal
    ok_path = fcm not in ("skip", "no-sa", "(ausente)") and not str(fcm).startswith("no-")
    print(f"\n   ¿Canal FCM activo (no skip/no-sa/no-dest)? {'SÍ' if ok_path else 'NO — revisar'}")

    # 6. auto-limpieza del token muerto (Google responde 404 → columna NULL)
    st, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query",
                       {"query": "select count(*)::int as n from sessions where fcm_token = '%s'" % fake_tok})
    n_tok = res[0]["n"] if st in api.OK and isinstance(res, list) else -1
    print(f"   token falso persistido tras el envío: {n_tok} (0 = auto-limpieza OK)")

    # 7. limpieza
    print("\n== 7. limpieza ==")
    for q in (
        "delete from messages where room_id = '%s'::uuid" % room,
        "delete from rooms where room_id = '%s'::uuid" % room,
        "delete from users where username in ('alice_t','bob_t')",
    ):
        st, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": q})
        if st not in api.OK:
            print(f"   limpieza FALLÓ: {st} {str(res)[:120]}")
            return 1
    print("   limpieza OK")
    return 0 if ok_path else 1


def secrets_box() -> bytes:
    """Ciphertext de prueba: 32 bytes con tag de 16 → dentro de los límites."""
    return pysecrets.token_bytes(48)


if __name__ == "__main__":
    sys.exit(main())
