#!/usr/bin/env python3
"""E2E del protocolo del CLIENTE Android (modelo rooms) contra Supabase REAL.

Replica EXACTAMENTE lo que hace la app (app/src/main/java/cu/alain/chat/crypto/Crypto.kt),
validado por KATs en CryptoRoomsTest (mismos bytes):

  roomKey/roomSalt aleatorios → roomId = UUID(HKDF(key, salt, "room-id")[0..16])
  inviteCode = base32("V1" ‖ salt ‖ key ‖ CRC32) con guiones cada 5
  SAS = HKDF(key, salt, "sas")[0..4] UInt BE mod 10^6
  convKey = HKDF(roomKey, salt=roomId, "conv-v1") · msgKey = HKDF(convKey, salt=msg_id, "msg-v1")
  AAD = "v1|msgId|senderId|roomId" · AEAD = ChaCha20-Poly1305 · framing [flag][payload]

Flujo: A genera sala (room_new con roomId DERIVADO, no aleatorio) → comparte el
código → B decodifica (pégado sin guiones, como en la UI) → room_join → SAS igual
en ambos → A cifra y envía (outbox {room}) → B sincroniza (sync) y descifra →
eco propio en A (multi-dispositivo) → manipulación de AAD falla → limpieza.

Uso: python3 backend/scripts/e2e_rooms_client.py
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


# ─────────────── cripto del cliente (espejo exacto de Crypto.kt) ───────────────

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


def b32_decode(s: str) -> bytes | None:
    out, buf, bits = bytearray(), 0, 0
    for ch in s:
        v = B32.find(ch)
        if v < 0:
            return None
        buf = (buf << 5) | v
        bits += 5
        if bits >= 8:
            out.append((buf >> (bits - 8)) & 0xFF)
            bits -= 8
    return bytes(out)


def hex_uuid(b: bytes) -> str:
    h = b.hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def room_id(key: bytes, salt: bytes) -> str:
    return hex_uuid(hkdf(key, salt, b"room-id", 16))


def sas6(key: bytes, salt: bytes) -> str:
    d = hkdf(key, salt, b"sas", 4)
    return "%06d" % (int.from_bytes(d, "big") % 1_000_000)


def encode_invite(salt: bytes, key: bytes) -> str:
    body = b"V1" + salt + key
    crc = zlib.crc32(body) & 0xFFFFFFFF
    payload = body + crc.to_bytes(4, "big")
    b32 = b32_encode(payload)
    return "-".join(b32[i:i + 5] for i in range(0, len(b32), 5))


def decode_invite(code: str) -> tuple[bytes, bytes] | None:
    clean = "".join(ch for ch in code.upper() if ch in B32)
    payload = b32_decode(clean)
    if payload is None or len(payload) != 54 or payload[:2] != b"V1":
        return None
    if zlib.crc32(payload[:50]) & 0xFFFFFFFF != int.from_bytes(payload[50:], "big"):
        return None
    return payload[18:50], payload[2:18]


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


def conv_key(key: bytes, room: str) -> bytes:
    return hkdf(key, room.lower().encode(), b"conv-v1")


def seal_msg(key: bytes, room: str, msg_id: str, sender_id: str, text: str):
    mk = hkdf(conv_key(key, room), msg_id.lower().encode(), b"msg-v1")
    aead = ChaCha20Poly1305(mk)
    nonce = pysecrets.token_bytes(12)
    ct = aead.encrypt(nonce, frame(text), aad(msg_id, sender_id, room))
    return nonce, ct


def open_msg(key: bytes, room: str, msg_id: str, sender_id: str, nonce: bytes, ct: bytes) -> str | None:
    mk = hkdf(conv_key(key, room), msg_id.lower().encode(), b"msg-v1")
    aead = ChaCha20Poly1305(mk)
    try:
        return unframe(aead.decrypt(nonce, ct, aad(msg_id, sender_id, room)))
    except Exception:
        return None


# ───────────────────────────────── escenarios ─────────────────────────────────

def main() -> int:
    dev_a, dev_b = str(uuidlib.uuid4()), str(uuidlib.uuid4())
    ua, ub = f"proto_a_{int(time.time()) % 100000}", f"proto_b_{int(time.time()) % 100000}"
    rid = room_id_holder = None

    # 1) registro de A y B
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    check("A registra", st == 201, f"{st} {raw[:120]}")
    ja = json.loads(raw); tok_a, uid_a = ja["tok"], ja["uid"]

    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    check("B registra", st == 201, f"{st} {raw[:120]}")
    jb = json.loads(raw); tok_b, uid_b = jb["tok"], jb["uid"]

    # 2) A genera la sala EN SU TELÉFONO (roomId derivado de roomKey)
    room_key, room_salt = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    room_id_holder = rid = room_id(room_key, room_salt)
    check("roomId derivado es UUID válido",
          bool(uuidlib.UUID(rid)), rid)

    invite = encode_invite(room_salt, room_key)
    check("código de invitación con formato esperado",
          len(invite) == 104 and invite.count("-") == 17, f"len={len(invite)}")

    # 3) A crea la sala en el servidor
    st, _, raw = req("POST", "/auth", {"a": "room_new", "r": rid}, tok_a)
    check("A room_new con roomId derivado", st == 201, f"{st} {raw[:120]}")

    # 4) B pega el código SIN guiones y en minúsculas (como la UI) y se une
    secret = decode_invite(invite.lower().replace("-", ""))
    check("B decodifica el código (minúsculas, sin guiones)", secret is not None)
    b_key, b_salt = secret
    b_rid = room_id(b_key, b_salt)
    check("B deriva el MISMO roomId", b_rid == rid, f"{b_rid} vs {rid}")
    check("SAS idéntico en A y B (comparación por voz)", sas6(room_key, room_salt) == sas6(b_key, b_salt))

    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": b_rid}, tok_b)
    check("B room_join", st == 200, f"{st} {raw[:120]}")

    # 5) A cifra y envía un mensaje (outbox {room})
    msg_id = uuid7()
    text = "Hola desde el protocolo rooms — ¡ñáñara y acentos!"
    nonce, ct = seal_msg(room_key, rid, msg_id, uid_a, text)
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[msg_id, base64.b64encode(ct).decode(),
                                                             base64.b64encode(nonce).decode(),
                                                             int(time.time() * 1000)]]}, tok_a)
    acked = json.loads(raw).get("a", []) if st == 200 else []
    check("A envía y recibe ack", st == 200 and acked == [msg_id], f"{st} {raw[:150]}")

    # 6) B sincroniza y descifra
    st, _, raw = req("GET", "/sync?s=0&l=100", None, tok_b)
    j = json.loads(raw)
    rows = {r[0]: r for r in j.get("m", [])}
    row = rows.get(msg_id)
    check("B recibe el mensaje por sync", row is not None, f"rows={list(rows)}")
    if row:
        got = open_msg(b_key, row[1], row[0], row[2], base64.b64decode(row[4]), base64.b64decode(row[5]))
        check("B descifra con la clave del código de invitación", got == text, f"got={got!r}")

        # manipulación del AAD (sender falso) → debe fallar
        bad = open_msg(b_key, row[1], row[0], uid_b, base64.b64decode(row[4]), base64.b64decode(row[5]))
        check("AAD con emisor falseado NO descifra", bad is None)

    # 7) eco propio: A sincroniza su propio mensaje (ruta multi-dispositivo)
    st, _, raw = req("GET", "/sync?s=0&l=100", None, tok_a)
    j = json.loads(raw)
    rows = {r[0]: r for r in j.get("m", [])}
    row = rows.get(msg_id)
    check("A recibe el eco de su mensaje", row is not None)
    if row:
        eco = open_msg(room_key, row[1], row[0], row[2], base64.b64decode(row[4]), base64.b64decode(row[5]))
        check("A descifra su propio eco (mine=true)", eco == text)

    # 8) mensaje largo (gzip en el framing) B → A
    msg2 = uuid7()
    largo = "línea de prueba con contenido repetido. " * 40  # >512 B → flag gzip
    nonce2, ct2 = seal_msg(b_key, rid, msg2, uid_b, largo)
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[msg2, base64.b64encode(ct2).decode(),
                                                             base64.b64encode(nonce2).decode(),
                                                             int(time.time() * 1000)]]}, tok_b)
    check("B envía mensaje comprimido", st == 200, f"{st} {raw[:150]}")
    st, _, raw = req("GET", f"/sync?s={j.get('n', 0)}&l=100", None, tok_a)
    j2 = json.loads(raw)
    rows2 = {r[0]: r for r in j2.get("m", [])}
    row2 = rows2.get(msg2)
    check("A recibe el mensaje largo", row2 is not None)
    if row2:
        got2 = open_msg(room_key, row2[1], row2[0], row2[2], base64.b64decode(row2[4]), base64.b64decode(row2[5]))
        check("A descifra el mensaje comprimido (framing gzip)", got2 == largo)

    # 9) no-miembro: un tercero NO ve la sala
    uc = f"proto_c_{int(time.time()) % 100000}"
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": uc, "p": "clave-de-prueba-8", "d": str(uuidlib.uuid4()), "ic": INVITE})
    jc = json.loads(raw)
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[uuid7(), base64.b64encode(ct).decode(),
                                                             base64.b64encode(nonce).decode(), int(time.time() * 1000)]]}, jc["tok"])
    check("tercero NO puede escribir en la sala", st == 404, f"{st} {raw[:120]}")

    # 10) rooms list: A ve su sala como owner
    st, _, raw = req("POST", "/auth", {"a": "rooms"}, tok_a)
    rooms = dict((r[0], r[1]) for r in json.loads(raw).get("rooms", []))
    check("rooms lista la sala de A (owner=1)", rooms.get(rid) == 1, f"{rooms}")

    # 11) logout
    st, _, _ = req("POST", "/auth", {"a": "out"}, tok_a)
    check("A logout", st == 200)

    # 12) limpieza total (Management API)
    cleanup = [
        "delete from messages where room_id = '%s'::uuid" % rid,
        "delete from rooms where room_id = '%s'::uuid" % rid,
        "delete from users where username in ('%s','%s','%s')" % (ua, ub, uc),
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
