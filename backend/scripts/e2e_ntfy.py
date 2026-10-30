#!/usr/bin/env python3
"""E2E del canal de notificaciones ntfy (D104) contra Supabase REAL + ntfy.sh REAL.

Replica EXACTAMENTE lo que hará la app (espejo de Crypto.ntfyTopic + Ntfy.kt):

  topic  = "c" + base32(HKDF(roomKey, salt=roomId, "ntfy-topic-v1", 16)).lower()
           → 27 chars, 128 bits de entropía; el servidor NUNCA lo conoce
           (se deriva en el MÓVIL EMISOR tras el ack y se publica a ntfy.sh).

  ping   = {"v":1,"r":<roomId>}                          ← despertador puro
           {"v":1,"r":<roomId>,"m":[[msg_id, sender_uid, seq(0),
                                      nonce_b64, ct_b64, sender_uname, ts]]}
           ← despertador + SOBRE CIFRADO: el receptor muestra el mensaje
             SIN round-trip a Supabase (RTT ETECSA 1-3 s) y con Supabase caído.
           El sobre va solo si el ping completo < 3400 chars (límite ntfy 4 KB).

  publish: POST https://ntfy.sh/<topic>  (X-Firebase: no — el proyecto prohíbe
           Firebase; sin título/tags/priority: mínimo metadata).
  poll:    GET https://ntfy.sh/<t1>,<t2>/json?poll=1&since=<unix-30>
           SIEMPRE con since (regla dura: sin cursor relee 12 h de caché → 429).

Orden canónico: ack del outbox ANTES del publish (si el ping llega antes del
commit, el receptor despierta y no encuentra el mensaje).

Seguridad (sección 9.3 del doc v2): ntfy es transporte sin autenticar —
el AEAD es la autenticidad (inyección → tag inválido → descarte silencioso),
el msg_id es el anti-replay (dedup por PK local).

Uso: python3 backend/scripts/e2e_ntfy.py
"""
from __future__ import annotations

import base64
import json
import os
import secrets as pysecrets
import sys
import time
import urllib.error
import urllib.request
import uuid as uuidlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402

api.load_env()

# Reutilizar el espejo cripto validado del cliente (KATs de CryptoRoomsTest).
# El módulo se importa sin ejecutar main() (guard __main__ en el otro script).
from e2e_rooms_client import (  # noqa: E402
    aad, b32_encode, conv_key, decode_invite, encode_invite, hkdf, open_msg,
    req, room_id, seal_msg, uuid7,
)

NTFY = "https://ntfy.sh"
PING_MAX_CHARS = 3400          # umbral de degradación a despertador puro
NTFY_MSG_LIMIT = 4096          # límite real del server (doc v2 §9.4)
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


# ─────────────── derivación del topic (espejo de Crypto.ntfyTopic) ───────────────

def ntfy_topic(room_key: bytes, room: str) -> str:
    k = hkdf(room_key, room.lower().encode(), b"ntfy-topic-v1", 16)
    return ("c" + b32_encode(k)).lower()


# ─────────────── construcción y parseo del ping (espejo de Ntfy.kt) ───────────────

def build_ping(room: str, rows: list[dict]) -> dict:
    """rows: [{mid, uid, uname, seq, nonce, ct, ts}] (seq=0 si desconocido).
    Devuelve el ping; puro (sin m) si el sobre no cabe."""
    full = {"v": 1, "r": room, "m": [[r["mid"], r["uid"], r["seq"], r["nonce"],
                                      r["ct"], r["uname"], r["ts"]] for r in rows]}
    if len(json.dumps(full, separators=(",", ":"))) > PING_MAX_CHARS:
        return {"v": 1, "r": room}
    return full


def parse_ping(message: str) -> tuple[str, list[dict]] | None:
    """Ping → (roomId, filas). None si no es un ping válido (se descarta)."""
    try:
        j = json.loads(message)
    except Exception:
        return None
    if j.get("v") != 1 or not isinstance(j.get("r"), str) or len(j.get("r", "")) > 64:
        return None
    rows_raw = j.get("m")
    if rows_raw is None:
        return j["r"], []
    if not isinstance(rows_raw, list) or len(rows_raw) > 8:  # presupuesto anti-spam
        return j["r"], []
    rows = []
    for f in rows_raw:
        if (not isinstance(f, list) or len(f) != 7
                or not all(isinstance(f[i], str) for i in (0, 1, 3, 4, 5))
                or not isinstance(f[2], int) or not isinstance(f[6], int)):
            return j["r"], []
        if len(f[4]) > 12000 or len(f[0]) > 64 or len(f[1]) > 64:
            return j["r"], []
        rows.append({"mid": f[0], "uid": f[1], "seq": f[2], "nonce": f[3],
                     "ct": f[4], "uname": f[5], "ts": f[6]})
    return j["r"], rows


# ─────────────── HTTP ntfy (espejo de Ntfy.publish/poll) ───────────────

def ntfy_publish(topic: str, body: str) -> tuple[int, dict]:
    r = urllib.request.Request(
        f"{NTFY}/{topic}",
        method="POST",
        data=body.encode(),
        headers={
            "Content-Type": "application/octet-stream",
            "X-Firebase": "no",  # el proyecto prohíbe Firebase (D9/auditoría §4.3)
        },
    )
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def ntfy_poll(topics: list[str], since: int) -> tuple[int, list[dict]]:
    """GET /t1,t2/json?poll=1&since=<unix>. Devuelve (status, eventos message)."""
    url = f"{NTFY}/{','.join(topics)}/json?poll=1&since={max(0, since)}"
    r = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            ndjson = resp.read().decode()
            evs = []
            for line in ndjson.splitlines():
                if not line.strip():
                    continue
                try:
                    j = json.loads(line)
                except Exception:
                    continue
                if j.get("event") == "message":
                    evs.append(j)
            return resp.status, evs
    except urllib.error.HTTPError as e:
        return e.code, []


# ─────────────── escenario ───────────────

def main() -> int:
    dev_a, dev_b = str(uuidlib.uuid4()), str(uuidlib.uuid4())
    stamp = int(time.time()) % 100000
    ua, ub = f"ntfy_a_{stamp}", f"ntfy_b_{stamp}"

    # 1) usuarios + sala con código (flujo canónico del protocolo rooms)
    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ua, "p": "clave-de-prueba-8", "d": dev_a, "ic": INVITE})
    check("A registra", st == 201, f"{st} {raw[:120]}")
    ja = json.loads(raw); tok_a, uid_a = ja["tok"], ja["uid"]
    uname_a = ua

    st, _, raw = req("POST", "/auth", {"a": "reg", "u": ub, "p": "clave-de-prueba-8", "d": dev_b, "ic": INVITE})
    check("B registra", st == 201, f"{st} {raw[:120]}")
    jb = json.loads(raw); tok_b, uid_b = jb["tok"], jb["uid"]

    room_key, room_salt = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    rid = room_id(room_key, room_salt)
    st, _, raw = req("POST", "/auth", {"a": "room_new", "r": rid}, tok_a)
    check("A room_new", st == 201, f"{st} {raw[:120]}")
    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": rid}, tok_b)
    check("B room_join", st == 200, f"{st} {raw[:120]}")

    # 2) derivación del topic (el corazón de D104)
    topic_ab = ntfy_topic(room_key, rid)
    topic_ba = ntfy_topic(room_key, rid)  # misma clave → mismo topic (1 por conversación)
    other_key = pysecrets.token_bytes(32)
    topic_other = ntfy_topic(other_key, rid)
    check("topic formato c + 26 base32 (27 chars, 128 bits)",
          len(topic_ab) == 27 and topic_ab[0] == "c" and topic_ab[1:].isalnum(),
          f"topic={topic_ab}")
    check("misma roomKey → mismo topic", topic_ab == topic_ba)
    check("roomKey distinta → topic distinto (no enumerable)", topic_ab != topic_other)

    # 3) A envía con ack y PÚBLICA el ping (orden: ack ANTES que publish)
    msg_id = uuid7()
    text = "Ping con sobre cifrado — ¡ñáñara y corazones! 💕"
    nonce, ct = seal_msg(room_key, rid, msg_id, uid_a, text)
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[msg_id, base64.b64encode(ct).decode(),
                                                            base64.b64encode(nonce).decode(),
                                                            int(time.time() * 1000)]]}, tok_a)
    acked = json.loads(raw).get("a", []) if st == 200 else []
    check("A envía y recibe ack", st == 200 and msg_id in acked, f"{st} {raw[:150]}")

    ping = build_ping(rid, [{"mid": msg_id, "uid": uid_a, "uname": ua, "seq": 0,
                             "nonce": base64.b64encode(nonce).decode(),
                             "ct": base64.b64encode(ct).decode(),
                             "ts": int(time.time() * 1000)}])
    check("ping con sobre (mensaje corto)", "m" in ping, json.dumps(ping)[:120])
    st, j = ntfy_publish(topic_ab, json.dumps(ping, separators=(",", ":")))
    check("publish del ping tras el ack (X-Firebase: no)", st == 200 and "id" in j, f"{st} {j}")
    ntfy_id_1 = j.get("id", "")
    expires_in = j.get("expires", 0) - j.get("time", 0)
    check("caché del ping = 12 h (buffer anti-desconexión)", 43000 <= expires_in <= 43500,
          f"expires_in={expires_in}")

    # 4) B poll y descifra SIN tocar Supabase
    time.sleep(2)
    since = int(time.time()) - 300
    st, evs = ntfy_poll([topic_ab], since)
    check("B poll del topic", st == 200 and len(evs) >= 1, f"{st} evs={len(evs)}")
    got_text = None
    seen_mid = None
    for e in evs:
        parsed = parse_ping(e.get("message", ""))
        if parsed is None:
            continue
        r, rows = parsed
        if r != rid:
            continue  # ping de otra sala en el mismo topic: se ignora
        for row in rows:
            if row["mid"] == msg_id:
                seen_mid = row["mid"]
                got_text = open_msg(room_key, rid, row["mid"], row["uid"],
                                    base64.b64decode(row["nonce"]), base64.b64decode(row["ct"]))
    check("B descifra el sobre del ping SIN round-trip a Supabase", got_text == text, f"got={got_text!r}")
    check("el ping no lleva seq (0=desconocido; lo llena el /sync posterior)",
          seen_mid == msg_id)

    # 5) B hace /sync de confirmación → el mismo msgId trae SU seq (fillSeq real)
    st, _, raw = req("GET", "/sync?s=0&l=100", None, tok_b)
    j = json.loads(raw)
    rows_sync = {r[0]: r for r in j.get("m", [])}
    row_sync = rows_sync.get(msg_id)
    check("B sync trae el mismo msgId con seq (para fillSeq)", row_sync is not None and row_sync[3] > 0,
          f"seq={row_sync[3] if row_sync else None}")

    # 6) inyección: ct falsificado y emisor falseado → AEAD los tira
    bad_ping = build_ping(rid, [{"mid": uuid7(), "uid": uid_a, "uname": ua, "seq": 0,
                                 "nonce": base64.b64encode(nonce).decode(),
                                 "ct": base64.b64encode(b"falsificado" * 40).decode(),
                                 "ts": int(time.time() * 1000)}])
    st, _ = ntfy_publish(topic_ab, json.dumps(bad_ping, separators=(",", ":")))
    fake_sender = open_msg(room_key, rid, msg_id, uid_b, nonce, ct)  # AAD con emisor falseado
    tampered = open_msg(room_key, rid, uuid7(), uid_a, nonce, ct)   # AAD con msgId ajeno
    check("inyectado no descifra (AEAD) — publicar es gratis, leer NO",
          st == 200 and fake_sender is None and tampered is None)

    # 7) replay del ping legítimo → mismo msgId → dedup por PK local
    st, _ = ntfy_publish(topic_ab, json.dumps(ping, separators=(",", ":")))
    check("replay aceptado por ntfy (dedup lo resuelve el receptor)", st == 200)
    time.sleep(2)
    st, evs = ntfy_poll([topic_ab], int(time.time()) - 300)
    mids = [e.get("id") for e in evs]
    check("el poll devuelve ambos eventos; el receptor deduplica por msg_id",
          len(evs) >= 2 and ntfy_id_1 in mids, f"evs={len(evs)}")

    # 8) eco propio: A poll su propio topic → su propio ping → exists → sin aviso
    st, evs = ntfy_poll([topic_ab], since)
    own = None
    for e in evs:
        parsed = parse_ping(e.get("message", ""))
        if parsed and parsed[0] == rid:
            for row in parsed[1]:
                if row["mid"] == msg_id and row["uid"] == uid_a:
                    own = row
    check("A ve su propio eco por ntfy (exists local: sin duplicado ni notificación)",
          own is not None)

    # 9) mensaje grande INCOMPRESIBLE → ping puro (degradación por límite de 4 KB)
    big_id = uuid7()
    big_text = pysecrets.token_hex(2600)  # 5200 chars hex: gzip apenas lo reduce (ct ~3.6 KB)
    nonce2, ct2 = seal_msg(room_key, rid, big_id, uid_a, big_text)
    check("el texto grande viaja real (>2.5 KB tras cifrar, <MAX_CT 8 KB)",
          2500 < len(ct2) < 8192, f"len_ct={len(ct2)}")
    big_rows = [{"mid": big_id, "uid": uid_a, "uname": ua, "seq": 0,
                 "nonce": base64.b64encode(nonce2).decode(),
                 "ct": base64.b64encode(ct2).decode(), "ts": int(time.time() * 1000)}]
    big_ping = build_ping(rid, big_rows)
    check("mensaje grande degrada a despertador puro (sin sobre)", "m" not in big_ping,
          json.dumps(big_ping)[:120])
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[big_id, base64.b64encode(ct2).decode(),
                                                             base64.b64encode(nonce2).decode(),
                                                             int(time.time() * 1000)]]}, tok_a)
    check("outbox acepta el mensaje grande", st == 200 and big_id in json.loads(raw).get("a", []),
          f"{st} {raw[:150]}")
    st, _ = ntfy_publish(topic_ab, json.dumps(big_ping, separators=(",", ":")))
    check("publish del ping puro OK", st == 200)
    pure_pub_ts = int(time.time())
    time.sleep(2)
    st, evs = ntfy_poll([topic_ab], int(time.time()) - 300)
    found_pure = None
    for e in evs:
        parsed = parse_ping(e.get("message", ""))
        if parsed and parsed[0] == rid and not parsed[1] and e.get("time", 0) >= pure_pub_ts - 2:
            found_pure = e
    check("B recibe el ping puro: despierta y hace /sync (no hay sobre)", found_pure is not None,
          f"evs={len(evs)}")
    st, _, raw = req("GET", "/sync?s=0&l=200", None, tok_b)
    j = json.loads(raw)
    big_row = {r[0]: r for r in j.get("m", [])}.get(big_id)
    big_ok = False
    if big_row:
        big_ok = open_msg(room_key, big_row[1], big_row[0], big_row[2],
                          base64.b64decode(big_row[4]), base64.b64decode(big_row[5])) == big_text
    check("B descifra el mensaje grande por /sync", big_ok,
          f"row={'None' if not big_row else 'ok'} raw={raw[:120]}")

    # 10) multi-topic: 2 salas en un solo poll
    room2_key, room2_salt = pysecrets.token_bytes(32), pysecrets.token_bytes(16)
    rid2 = room_id(room2_key, room2_salt)
    st, _, raw = req("POST", "/auth", {"a": "room_new", "r": rid2}, tok_a)
    st, _, raw = req("POST", "/auth", {"a": "room_join", "r": rid2}, tok_b)
    topic2 = ntfy_topic(room2_key, rid2)
    m2 = uuid7()
    t2 = "mensaje de la sala dos"
    n2, c2 = seal_msg(room2_key, rid2, m2, uid_a, t2)
    st, _, raw = req("POST", "/outbox", {"room": rid2, "m": [[m2, base64.b64encode(c2).decode(),
                                                             base64.b64encode(n2).decode(),
                                                             int(time.time() * 1000)]]}, tok_a)
    ping2 = build_ping(rid2, [{"mid": m2, "uid": uid_a, "uname": ua, "seq": 0,
                               "nonce": base64.b64encode(n2).decode(),
                               "ct": base64.b64encode(c2).decode(), "ts": int(time.time() * 1000)}])
    st, _ = ntfy_publish(topic2, json.dumps(ping2, separators=(",", ":")))
    time.sleep(2)
    st, evs = ntfy_poll([topic_ab, topic2], int(time.time()) - 300)
    rooms_seen = set()
    for e in evs:
        parsed = parse_ping(e.get("message", ""))
        if parsed:
            rooms_seen.add(parsed[0])
    check("multi-topic: un poll recibe ambas salas", rid in rooms_seen and rid2 in rooms_seen,
          f"vistas={len(rooms_seen)}")

    # 12) since como cursor: solo lo nuevo
    time.sleep(4)
    m3 = uuid7()
    t3 = "posterior al cursor"
    n3, c3 = seal_msg(room_key, rid, m3, uid_a, t3)
    st, _, raw = req("POST", "/outbox", {"room": rid, "m": [[m3, base64.b64encode(c3).decode(),
                                                            base64.b64encode(n3).decode(),
                                                            int(time.time() * 1000)]]}, tok_a)
    check("outbox del 3º mensaje", st == 200, f"{st} {raw[:120]}")
    st, _ = ntfy_publish(topic_ab, json.dumps(build_ping(rid, [{"mid": m3, "uid": uid_a, "uname": ua, "seq": 0,
                                                               "nonce": base64.b64encode(n3).decode(),
                                                               "ct": base64.b64encode(c3).decode(),
                                                               "ts": int(time.time() * 1000)}]),
                                              separators=(",", ":")))
    check("publish del 3º ping", st == 200, f"{st}")
    time.sleep(2)
    cursor = int(time.time()) - 3  # después del ping 1 y 2, antes del 3
    st, evs = ntfy_poll([topic_ab], cursor)
    mids_seen = set()
    for e in evs:
        parsed = parse_ping(e.get("message", ""))
        if parsed:
            for row in parsed[1]:
                mids_seen.add(row["mid"])
    check("since=<unix> filtra: el poll no relee lo viejo", m3 in mids_seen and msg_id not in mids_seen,
          f"st={st} evs={len(evs)} vistos={sorted(mids_seen)[:3]}")

    # 13) HALLAZGO ntfy.sh: body > 4 KB NO se rechaza — se convierte en ATTACHMENT
    # (retención 3 h + URL pública). Por eso el cliente NUNCA manda pings gordos:
    # el umbral 3400 lo evita de raíz y el parse_ping del receptor tira el evento
    # (message="You received a file…" no es JSON del ping → descarte silencioso).
    st, j = ntfy_publish(topic_ab, "x" * (NTFY_MSG_LIMIT + 512))
    is_attachment = st == 200 and "attachment" in j and "You received a file" in j.get("message", "")
    check("body > 4 KB se vuelve attachment (nunca provocarlo: umbral 3400)", is_attachment,
          f"{st} {str(j)[:150]}")
    junk = parse_ping("You received a file: attachment.txt")
    check("el receptor descarta el evento attachment (no es un ping)", junk is None)

    # 14) limpieza
    st, _, _ = req("POST", "/auth", {"a": "out"}, tok_a)
    st, _, _ = req("POST", "/auth", {"a": "out"}, tok_b)
    cleanup = [
        "delete from messages where room_id in ('%s'::uuid, '%s'::uuid)" % (rid, rid2),
        "delete from rooms where room_id in ('%s'::uuid, '%s'::uuid)" % (rid, rid2),
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
        for m, n, d in RESULTS:
            if m == "FAIL":
                print(f"  ✗ {n}  {d}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
