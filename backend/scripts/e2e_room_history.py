#!/usr/bin/env python3
"""E2E del fix B1 (auditoría): recuperación de historial por sala — sync?room=.

Escenario EXACTO del bug en producción:
  1. A crea la sala R3 y manda 3 mensajes (seq bajos del sistema).
  2. A crea otra sala R2 con B; B sincroniza SOLO R2 → su cursor global
     queda por ENCIMA de los seq de R3.
  3. B se une tarde a R3 (room_join).
  4. sync global ?s=<cursor> → R3 no aparece (seq < cursor) ← EL BUG:
     sin el modo sala, B jamás vería esa historia (ni el nombre D69).
  5. sync ?room=R3&s=0 → devuelve los 3 mensajes y B los descifra ← EL FIX.
  6. Membresía verificada: una sala ajena → 403; roomId inválido → 400.

Uso: python3 backend/scripts/e2e_room_history.py
"""
from __future__ import annotations

import base64
import gzip as gz
import hashlib
import hmac as hmac_mod
import json
import os
import secrets as pysecrets
import sys
import time
import urllib.error
import uuid as uuidlib
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305  # noqa: E402

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
    url = BASE + path
    headers = {"apikey": ANON, "content-type": "application/json", "accept-encoding": "gzip"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=35) as resp:
            raw = resp.read()
            if resp.headers.get("content-encoding") == "gzip":
                raw = gz.decompress(raw)
            return resp.status, resp.headers, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, e.headers, raw


# ─────────────── cripto espejo de Crypto.kt (idéntico a e2e_rooms_client) ───────

B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def hkdf(ikm: bytes, salt: bytes, info: bytes, l: int = 32) -> bytes:
    prk = hmac_mod.new(salt, ikm, hashlib.sha256).digest()
    ok, t, i = b"", b"", 1
    while len(ok) < l:
        t = hmac_mod.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        ok += t
        i += 1
    return ok[:l]


def b32_encode(data: bytes) -> str:
    bits, buf, out = 0, 0, []
    for byte in data:
        buf = (buf << 8) | byte
        bits += 8
        while bits >= 5:
            out.append(B32[(buf >> (bits - 5)) & 0x1F])
            bits -= 5
    if bits:
        out.append(B32[(buf << (5 - bits)) & 0x1F])
    return "".join(out)


def hex_uuid(b: bytes) -> str:
    h = b.hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def room_id(key: bytes, salt: bytes) -> str:
    return hex_uuid(hkdf(key, salt, b"room-id", 16))


def encode_invite(salt: bytes, key: bytes) -> str:
    body = b"V1" + salt + key
    crc = zlib.crc32(body) & 0xFFFFFFFF
    payload = body + crc.to_bytes(4, "big")
    return "-".join(b32_encode(payload)[i:i + 5] for i in range(0, len(b32_encode(payload)), 5))


def uuid7() -> str:
    ts = int(time.time() * 1000)
    b = bytearray(ts.to_bytes(6, "big") + pysecrets.token_bytes(10))
    b[6] = (b[6] & 0x0F) | 0x70
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuidlib.UUID(bytes=bytes(b)))


def frame(text: str) -> bytes:
    raw = text.encode()
    if len(raw) > 512:
        return b"\x01" + gz.compress(raw, 1)
    return b"\x00" + raw


def unframe(wire: bytes) -> str | None:
    try:
        if wire[0] == 1:
            return gz.decompress(wire[1:]).decode()
        if wire[0] == 0:
            return wire[1:].decode()
    except Exception:
        return None
    return None


def aad(msg_id: str, sender_id: str, room: str) -> bytes:
    return f"v1|{msg_id}|{sender_id.lower()}|{room.lower()}".encode()


def seal_msg(key: bytes, room: str, msg_id: str, sender_id: str, text: str):
    mk = hkdf(hkdf(key, room.lower().encode(), b"conv-v1"), msg_id.lower().encode(), b"msg-v1")
    aead = ChaCha20Poly1305(mk)
    nonce = pysecrets.token_bytes(12)
    ct = aead.encrypt(nonce, frame(text), aad(msg_id, sender_id, room))
    return nonce, ct


def open_msg(key: bytes, room: str, msg_id: str, sender_id: str, nonce: bytes, ct: bytes) -> str | None:
    mk = hkdf(hkdf(key, room.lower().encode(), b"conv-v1"), msg_id.lower().encode(), b"msg-v1")
    aead = ChaCha20Poly1305(mk)
    try:
        return unframe(aead.decrypt(nonce, ct, aad(msg_id, sender_id, room)))
    except Exception:
        return None


def main() -> int:
    dev_a, dev_b = str(uuidlib.uuid4()), str(uuidlib.uuid4())
    stamp = int(time.time()) % 100000
    ua, ub = f"hist_a_{stamp}", f"hist_b_{stamp}"

    # 1) registro de A y B
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    check("A registra", st == 201, f"{st} {raw[:120]}")
    tok_a, uid_a = json.loads(raw)["tok"], json.loads(raw)["uid"]

    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    check("B registra", st == 201, f"{st} {raw[:120]}")
    tok_b, uid_b = json.loads(raw)["tok"], json.loads(raw)["uid"]

    # 2) A crea R3 (la sala con historia que B descubrirá tarde)
    k3, s3 = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    r3 = room_id(k3, s3)
    st, _, raw = req("POST", "/auth", {"a": "room_new", "r": r3}, tok_a)
    check("A crea R3", st == 201, f"{st} {raw[:120]}")

    texts = ["hola del pasado", "mensaje 2 de la historia", "el último antes del join"]
    sent = []
    for t in texts:
        mid = uuid7()
        nonce, ct = seal_msg(k3, r3, mid, uid_a, t)
        st, _, raw = req("POST", "/outbox", {"room": r3, "m": [
            [mid, base64.b64encode(ct).decode(), base64.b64encode(nonce).decode(),
             int(time.time() * 1000)]]}, tok_a)
        acked = json.loads(raw).get("a", []) if st == 200 else []
        check(f"A envía «{t}»", st == 200 and acked == [mid], f"{st} {raw[:150]}")
        sent.append(mid)

    # 3) A crea R2 y mete a B; B sincroniza SOLO R2 → cursor global sube
    k2, s2 = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    r2 = room_id(k2, s2)
    st, _, raw = req("POST", "/auth", {"a": "room_new", "r": r2}, tok_a)
    check("A crea R2", st == 201, f"{st} {raw[:120]}")
    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": r2}, tok_a)  # A miembro de la suya
    check("A join R2", st == 200, f"{st} {raw[:120]}")
    invite2 = encode_invite(s2, k2)
    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": r2}, tok_b)
    check("B join R2", st == 200, f"{st} {raw[:120]}")

    mid2 = uuid7()
    nonce2, ct2 = seal_msg(k2, r2, mid2, uid_a, "mensaje de R2 que sube el cursor de B")
    st, _, raw = req("POST", "/outbox", {"room": r2, "m": [
        [mid2, base64.b64encode(ct2).decode(), base64.b64encode(nonce2).decode(),
         int(time.time() * 1000)]]}, tok_a)
    check("A envía en R2", st == 200, f"{st} {raw[:150]}")

    st, _, raw = req("GET", "/sync?s=0&l=100", None, tok_b)
    j = json.loads(raw)
    rows_r2 = [r for r in j.get("m", []) if r[1] == r2]
    check("B sync global ve R2 (cursor sube)", len(rows_r2) == 1, f"{len(rows_r2)} filas")
    cursor = j.get("n", 0)
    check("cursor global de B > seq de R3", cursor >= 4, f"cursor={cursor}")

    # 4) B se une TARDE a R3 — el momento del bug original
    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": r3}, tok_b)
    check("B join tardío a R3", st == 200, f"{st} {raw[:120]}")

    st, _, raw = req("GET", f"/sync?s={cursor}&l=100", None, tok_b)
    if st == 204:
        rows = []
    else:
        rows = json.loads(raw).get("m", []) if st == 200 else None
    r3_global = [r for r in (rows or []) if r[1] == r3]
    check("BUG reproducido: sync global NO sirve la historia de R3",
          len(r3_global) == 0, f"{len(r3_global)} filas de R3 (deberían ser 0)")

    # 5) EL FIX: modo sala
    st, _, raw = req("GET", f"/sync?room={r3}&s=0&l=100", None, tok_b)
    check("sync?room=R3 200", st == 200, f"{st} {raw[:150]}")
    j = json.loads(raw)
    rows = j.get("m", [])
    check("modo sala devuelve los 3 mensajes", len(rows) == 3, f"{len(rows)} filas")
    check("modo sala: solo R3", all(r[1] == r3 for r in rows))
    check("modo sala: next seq = seq del último", j.get("n") == rows[-1][3] if rows else False)

    # B descifra la historia recuperada
    ok_dec = 0
    for r in rows:
        msg_id, _, sender_id, _seq, nb, ctb, _uname, _ts = r
        plain = open_msg(k3, r3, msg_id, sender_id, base64.b64decode(nb), base64.b64decode(ctb))
        if plain in texts:
            ok_dec += 1
    check("B descifra los 3 mensajes de la historia", ok_dec == 3, f"{ok_dec}/3")

    # paginación del modo sala: rs = seq del penúltimo → solo el último
    if rows:
        rs_penultimo = rows[1][3]
        st, _, raw = req("GET", f"/sync?room={r3}&s={rs_penultimo}&l=100", None, tok_b)
        j2 = json.loads(raw) if st == 200 else {}
        check("modo sala pagina por rs (1 fila tras el penúltimo)",
              len(j2.get("m", [])) == 1, f"{len(j2.get('m', []))} filas")
        # 204 cuando no queda nada
        rs_final = j.get("n", 0)
        st, _, _ = req("GET", f"/sync?room={r3}&s={rs_final}&l=100", None, tok_b)
        check("modo sala: 204 al estar al día", st == 204, f"{st}")

    # 6) seguridad del modo sala
    st, _, _ = req("GET", f"/sync?room={r2}&s=0&l=100", None, tok_a)  # r2 también es de A → 200
    check("modo sala: el dueño también puede", st == 200, f"{st}")
    # sala ajena: creamos R4 solo de A y pedimos con token de B
    k4, s4 = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    r4 = room_id(k4, s4)
    st, _, _ = req("POST", "/auth", {"a": "room_new", "r": r4}, tok_a)
    st, _, _ = req("GET", f"/sync?room={r4}&s=0&l=100", None, tok_b)
    check("modo sala: sala ajena → 403", st == 403, f"{st}")
    st, _, _ = req("GET", "/sync?room=no-es-un-uuid&s=0&l=100", None, tok_b)
    check("modo sala: roomId inválido → 400", st == 400, f"{st}")

    # 7) el sync global normal sigue intacto (sin room)
    st, _, raw = req("GET", f"/sync?s={cursor}&l=100", None, tok_b)
    check("sync global sin room sigue respondiendo", st in (200, 204), f"{st}")

    # 8) limpieza total
    cleanup = [
        "delete from messages where room_id in ('%s'::uuid, '%s'::uuid, '%s'::uuid)" % (r3, r2, r4),
        "delete from rooms where room_id in ('%s'::uuid, '%s'::uuid, '%s'::uuid)" % (r3, r2, r4),
        "delete from users where username in ('%s','%s')" % (ua, ub),
    ]
    ok = True
    for s in cleanup:
        st2, res = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": s})
        if st2 not in api.OK:
            ok = False
            print(f"  limpieza: {st2} {str(res)[:150]}")
    check("limpieza BD", ok)

    print(f"\n{'=' * 60}\nRESULTADO: {PASS} PASS · {FAIL} FAIL")
    if FAIL:
        for mark, name, detail in RESULTS:
            if mark == "FAIL":
                print(f"  ✗ {name} — {detail}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
