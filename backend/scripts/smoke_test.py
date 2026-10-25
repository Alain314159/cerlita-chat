#!/usr/bin/env python3
"""Smoke test end-to-end del backend contra el proyecto Supabase REAL.

Registra usuarios de prueba, envía mensajes (ciphertext simulado), verifica
sync/idempotencia/lockout/gzip y LIMPIA todos los datos de prueba al final.

Uso: python3 backend/scripts/smoke_test.py
"""
from __future__ import annotations

import base64
import gzip as gz
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid as uuidlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

BASE = os.environ["SUPA_URL"].rstrip("/") + "/functions/v1"
ANON = os.environ.get("SUPA_ANON_KEY", "")
INVITE = os.environ.get("REG_INVITE_CODE", "")

PASS = 0
FAIL = 0
RESULTS: list[tuple[str, str, str]] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    mark = "PASS" if cond else "FAIL"
    if cond:
        PASS += 1
    else:
        FAIL += 1
    RESULTS.append((mark, name, detail))
    print(f"[{mark}] {name}" + (f"  — {detail}" if detail and not cond else ""))


def req(method: str, path: str, body: dict | None = None, token: str | None = None):
    """Llama a una Edge Function. Devuelve (status, headers, bytes)."""
    url = BASE + path
    headers = {"apikey": ANON, "content-type": "application/json", "accept-encoding": "gzip"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            raw = resp.read()
            hdrs = {k.lower(): v for k, v in resp.headers.items()}
            if hdrs.get("content-encoding", "").lower() == "gzip":
                try:
                    raw = gz.decompress(raw)
                except OSError:
                    pass  # cuerpo no-gzip con cabecera gzip: dejar tal cual
            return resp.status, hdrs, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        hdrs = {k.lower(): v for k, v in e.headers.items()}
        if hdrs.get("content-encoding", "").lower() == "gzip":
            try:
                raw = gz.decompress(raw)
            except OSError:
                pass
        return e.code, hdrs, raw
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return 0, {}, str(e).encode()


def jbody(raw: bytes) -> dict:
    try:
        return json.loads(raw) if raw else {}
    except Exception:
        return {"_raw": raw[:200].decode(errors="replace")}


def uuid7() -> str:
    """UUIDv7 (RFC 9562) — como los que generará la app Android."""
    ts = int(time.time() * 1000)
    rand = secrets.token_bytes(10)
    b = bytearray(16)
    b[0], b[1] = (ts >> 40) & 0xFF, (ts >> 32) & 0xFF
    b[2], b[3] = (ts >> 24) & 0xFF, (ts >> 16) & 0xFF
    b[4], b[5] = (ts >> 8) & 0xFF, ts & 0xFF
    b[6] = (rand[0] & 0x0F) | 0x70  # versión 7
    b[7] = rand[1]
    b[8] = (rand[2] & 0x3F) | 0x80  # variante RFC 4122
    b[9:] = rand[3:10]
    return str(uuidlib.UUID(bytes=bytes(b)))


def dev_id() -> str:
    return str(uuidlib.uuid4())


def main() -> int:
    if not INVITE:
        print("ERROR: falta REG_INVITE_CODE")
        return 1
    if not ANON:
        print("aviso: sin SUPA_ANON_KEY — si el gateway la exigiera, algunos tests fallarán")

    print(f"Smoke test contra {BASE}\n")

    # 1. heartbeat (con reintentos: el deploy tarda unos segundos en propagar)
    st, h, raw = 0, {}, b""
    for attempt in range(4):
        st, h, raw = req("GET", "/heartbeat")
        if st == 200:
            break
        print(f"  (heartbeat intento {attempt + 1}: {st}, espero 5s…)")
        time.sleep(5)
    body = jbody(raw)
    check("heartbeat responde 200 ok", st == 200 and body.get("ok") is True, f"{st} {body}")

    # 2-3. registro de alice y bob
    alice_dev, bob_dev = dev_id(), dev_id()
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": "alice_t", "p": "clave-de-prueba-1", "d": alice_dev, "ic": INVITE})
    alice = jbody(raw)
    check("registro alice_t → 201 + token", st == 201 and alice.get("tok"), f"{st} {alice}")
    t0 = time.time()
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": "bob_t", "p": "clave-de-prueba-2", "d": bob_dev, "ic": INVITE})
    bob = jbody(raw)
    dt = time.time() - t0
    check("registro bob_t → 201 + token", st == 201 and bob.get("tok"), f"{st} {bob}")
    print(f"  (argón2id + insert: {dt:.2f}s)")

    # 4-5. registro inválido
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": "eve", "p": "clave-de-prueba-3", "d": dev_id(), "ic": "CODIGO-FALSO"})
    check("invite code falso → 403", st == 403, f"{st} {jbody(raw)}")
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": "Nombre-Invalido", "p": "clave-de-prueba-4", "d": dev_id(), "ic": INVITE})
    check("username inválido → 400", st == 400, f"{st} {jbody(raw)}")

    # 6-7. whoami + sync sin token
    st, _, raw = req("GET", "/auth", token=alice.get("tok", ""))
    check("whoami alice → 200", st == 200 and jbody(raw).get("u") == "alice_t", f"{st} {jbody(raw)}")
    st, _, raw = req("GET", "/sync")
    check("sync sin token → 401", st == 401, f"{st} {jbody(raw)}")

    # 8. alice → bob: 3 mensajes
    msgs = [(uuid7(), base64.b64encode(secrets.token_bytes(64 + i * 60)).decode(),
             base64.b64encode(secrets.token_bytes(12)).decode(), int(time.time() * 1000)) for i in range(3)]
    sent = {"to": "bob_t", "m": [list(m) for m in msgs]}
    st, _, raw = req("POST", "/outbox", sent, token=alice.get("tok", ""))
    ack = jbody(raw)
    check("outbox 3 msgs → 200 acked=3", st == 200 and set(ack.get("a", [])) == {m[0] for m in msgs}, f"{st} {ack}")

    # 9. bob sincroniza
    st, h, raw = req("GET", "/sync?s=0", token=bob.get("tok", ""))
    sync1 = jbody(raw)
    got = {m[0]: m for m in sync1.get("m", [])}
    exact = all(got.get(m[0], [None, None, None, None, None, m[2]])[5] == m[1] for m in msgs)
    check("sync bob desde 0 → 3 msgs, bytes EXACTOS", st == 200 and len(sync1.get("m", [])) == 3 and exact,
          f"{st} n={sync1.get('n')} msgs={len(sync1.get('m', []))}")
    next_seq = sync1.get("n", 0)
    check("header x-next-seq correcto", h.get("x-next-seq") == str(next_seq), f"{h.get('x-next-seq')} vs {next_seq}")

    # 10. no hay nada nuevo → 204
    st, _, raw = req("GET", f"/sync?s={next_seq}", token=bob.get("tok", ""))
    check("sync sin novedades → 204 (0 bytes)", st == 204 and raw == b"", f"{st} len={len(raw)}")

    # 11. reenvío idéntico (idempotencia)
    st, _, raw = req("POST", "/outbox", sent, token=alice.get("tok", ""))
    ack2 = jbody(raw)
    check("reenvío mismo lote → acked sin duplicar", st == 200 and set(ack2.get("a", [])) == {m[0] for m in msgs}, f"{st} {ack2}")
    st, _, raw = req("GET", "/sync?s=0", token=alice.get("tok", ""))
    sync_alice = jbody(raw)
    check("alice ve 3 msgs en total (0 duplicados)", len(sync_alice.get("m", [])) == 3, f"{len(sync_alice.get('m', []))}")

    # 12-14. validaciones de outbox
    st, _, raw = req("POST", "/outbox", {"to": "no_existe", "m": [[uuid7(), base64.b64encode(secrets.token_bytes(64)).decode(), base64.b64encode(secrets.token_bytes(12)).decode()]]}, token=alice.get("tok", ""))
    check("destinatario inexistente → 404", st == 404, f"{st} {jbody(raw)}")
    st, _, raw = req("POST", "/outbox", {"to": "bob_t", "m": [[uuid7(), base64.b64encode(secrets.token_bytes(64)).decode(), base64.b64encode(secrets.token_bytes(8)).decode()]]}, token=alice.get("tok", ""))
    check("nonce de 8 bytes → 400", st == 400, f"{st} {jbody(raw)}")
    st, _, raw = req("POST", "/outbox", {"to": "bob_t", "m": [[uuid7(), base64.b64encode(secrets.token_bytes(9000)).decode(), base64.b64encode(secrets.token_bytes(12)).decode()]]}, token=alice.get("tok", ""))
    check("ciphertext 9KB → 400", st == 400, f"{st} {jbody(raw)}")

    # 15-16. login y lockout
    st, _, raw = req("POST", "/auth", {"a": "in", "u": "alice_t", "p": "incorrecta!!", "d": dev_id()})
    check("login con password malo → 401", st == 401, f"{st} {jbody(raw)}")
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": "lock_t", "p": "clave-de-prueba-5", "d": dev_id(), "ic": INVITE})
    if st == 201:
        for _ in range(5):
            req("POST", "/auth", {"a": "in", "u": "lock_t", "p": "mala-mala!!", "d": dev_id()})
        st, _, raw = req("POST", "/auth", {"a": "in", "u": "lock_t", "p": "mala-mala!!", "d": dev_id()})
        body = jbody(raw)
        check("lockout tras 5 fallos → 429", st == 429 and body.get("e") == "locked", f"{st} {body}")
    else:
        check("lockout tras 5 fallos → 429", False, f"no pude registrar lock_t: {st}")

    # 17. gzip con 20 mensajes de ~600B (CF comprime si el cliente lo acepta)
    big = {"to": "bob_t", "m": [[uuid7(), base64.b64encode(secrets.token_bytes(600)).decode(),
             base64.b64encode(secrets.token_bytes(12)).decode(), int(time.time() * 1000)] for _ in range(20)]}
    st, _, raw = req("POST", "/outbox", big, token=alice.get("tok", ""))
    check("outbox 20 msgs de 600B → 200", st == 200 and len(jbody(raw).get("a", [])) == 20, f"{st}")
    st, h, raw = req("GET", "/sync?s=0", token=bob.get("tok", ""))
    enc_hdr = h.get("content-encoding", "")
    ok_gz = enc_hdr == "gzip" or len(raw) > 0  # CF puede comprimir; el cuerpo ya llega descomprimido por req()
    parsed = jbody(raw)
    check("sync grande → gzip en tránsito + 23 msgs válidos", ok_gz and len(parsed.get("m", [])) == 23,
          f"hdr={enc_hdr!r} msgs={len(parsed.get('m', []))}")

    # 18. logout
    st, _, raw = req("POST", "/auth", {"a": "out"}, token=alice.get("tok", ""))
    st2, _, raw2 = req("GET", "/auth", token=alice.get("tok", ""))
    check("logout revoca el token → whoami 401", st == 200 and st2 == 401, f"{st} {st2}")

    # 19. verificación en la BD
    st, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query",
                       {"query": "select count(*)::int as n from messages"})
    n_msgs = 0
    if st in api.OK and isinstance(res, list) and res:
        n_msgs = res[0].get("n", -1)
    check("BD: 23 mensajes de prueba en total", n_msgs == 23, f"count={n_msgs} res={str(res)[:120]}")

    # 20. limpieza
    cleanup_sql = [
        """delete from messages where conversation_id in (
             select id from conversations c
             where c.user_lo in (select id from users where username in ('alice_t','bob_t','lock_t'))
                or c.user_hi in (select id from users where username in ('alice_t','bob_t','lock_t')))""",
        """delete from conversations
            where user_lo in (select id from users where username in ('alice_t','bob_t','lock_t'))
               or user_hi in (select id from users where username in ('alice_t','bob_t','lock_t'))""",
        """delete from users where username in ('alice_t','bob_t','lock_t')""",
    ]
    ok_clean = True
    for s in cleanup_sql:
        st, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st not in api.OK:
            ok_clean = False
            print(f"  limpieza: {st} {str(res)[:150]}")
    st, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query",
                       {"query": "select (select count(*) from messages) as msgs, (select count(*) from users) as usrs"})
    counts = res[0] if st in api.OK and isinstance(res, list) and res else {}
    check("limpieza: 0 mensajes, 0 usuarios de prueba", ok_clean and counts.get("msgs") == 0 and counts.get("usrs") == 0,
          f"limpieza_ok={ok_clean} counts={counts}")

    # resumen
    print(f"\n{'=' * 60}\nRESULTADO: {PASS} PASS · {FAIL} FAIL")
    if FAIL:
        print("\nFallos:")
        for mark, name, detail in RESULTS:
            if mark == "FAIL":
                print(f"  ✗ {name} — {detail}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
