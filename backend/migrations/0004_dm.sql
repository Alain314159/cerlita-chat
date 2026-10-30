-- ============================================================
-- Migración 0004: CHATS DIRECTOS 1-a-1 (v0.7.0, D74)
--
-- Buscar a un usuario por @nombre y crear el chat entre los dos SIN
-- código de invitación, manteniendo E2EE:
--
--   1. users.pub: clave pública X25519 de cada usuario (32 B). La sube
--      el cliente al registrarse/entrar; el servidor SOLO la almacena.
--      Última que sube gana (un usuario = un teléfono en la práctica;
--      multi-dispositivo recupera por código de invitación, como siempre).
--   2. rooms.kind: 0=grupo (código de invitación, como siempre),
--      1=chat directo. rooms.dm_pair = least(uidA, uidB) con índice
--      UNIQUE → un solo chat directo por pareja (idempotente).
--   3. dm_keys: la roomKey del chat directo viaja al par ENVUELTA:
--      el creador hace X25519(miPriv, pubDelPar) → HKDF → ChaCha20-
--      Poly1305. El servidor guarda el blob opaco; sin las claves
--      privadas (que JAMÁS salen de los teléfonos) no sirve de nada.
--      El par lo recoge por /sync (campo "dm") y acusa con auth/dm_ack.
--
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0004_dm.sql
-- ============================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pub BYTEA;

COMMENT ON COLUMN users.pub IS
    'Clave pública X25519 (32 B) del último teléfono que inició sesión. Para envolver roomKeys de chats directos (D74); la privada nunca sale del teléfono.';

CREATE TABLE IF NOT EXISTS dm_keys (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    for_user    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_user   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    wrapped     BYTEA NOT NULL CHECK (octet_length(wrapped) BETWEEN 16 AND 256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    UNIQUE (room_id, for_user)
);
CREATE INDEX IF NOT EXISTS idx_dm_keys_pending ON dm_keys(for_user) WHERE consumed_at IS NULL;

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS kind SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN rooms.kind IS '0=grupo (código de invitación) · 1=chat directo (D74)';

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS dm_pair UUID;

COMMENT ON COLUMN rooms.dm_pair IS
    'least(uidA, uidB) del par de un chat directo; UNIQUE → un solo chat directo por pareja.';

CREATE UNIQUE INDEX IF NOT EXISTS idx_rooms_dm_pair ON rooms(dm_pair) WHERE dm_pair IS NOT NULL;

ALTER TABLE dm_keys ENABLE ROW LEVEL SECURITY;
