#!/usr/bin/env python3
"""Vector de referencia del tema Realtime (D64).

HKDF-SHA256(ikm=roomId, salt="cerlita-rt-v1", info="realtime-topic", L=16)
→ hex 32 chars → topic completo "cerlita:room:<hex>".

Se usa para verificar que Kotlin (javax.crypto), TS (Web Crypto en la Edge
Function) y Python derivan EXACTAMENTE el mismo tema. Cualquier cambio de
parámetros rompe el timbre silenciosamente — por eso el vector va a los tests.
"""
import hashlib
import hmac

SALT = b"cerlita-rt-v1"
INFO = b"realtime-topic"
L = 16


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    # RFC 5869: extract
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    # expand
    t = b""
    okm = b""
    counter = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), hashlib.sha256).digest()
        okm += t
        counter += 1
    return okm[:length]


def topic_for(room_id: str) -> str:
    hex_ = hkdf_sha256(room_id.strip().lower().encode(), SALT, INFO, L).hex()
    return f"cerlita:room:{hex_}"


if __name__ == "__main__":
    cases = [
        "00000000-0000-0000-0000-000000000000",
        "0192f0ac-1234-7000-8000-3b2c4d5e6f70",
        "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",
    ]
    for c in cases:
        hex_ = topic_for(c).removeprefix("cerlita:room:")
        print(f'("{c}", "{hex_}"),')
