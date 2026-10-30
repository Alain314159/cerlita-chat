-- ============================================================
-- Migración 0002: modelo ROOMS (documento v2 corregido, oct-2026)
--
-- Cambios estructurales respecto a 0001:
--   1. La sala la identifica room_id, generado por el CLIENTE como
--      HKDF(roomKey, salt=roomSalt, info="room-id")[0..16] formateado
--      como UUID. Conocer el room_id es la "capability" de membresía
--      (2^128); la roomKey JAMÁS viaja al servidor.
--   2. El código de invitación ES el secreto:
--        inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC)
--      Contiene la roomKey EN CLARO dentro del código. Se comparte por
--      un canal controlado (en persona / WhatsApp / papel). El servidor
--      nunca lo recibe. [CORRECCIÓN-DOC: sin cifrado adicional]
--   3. El SAS (número de seguridad de 6 dígitos derivado de roomKey) se
--      compara SOLO entre los dos clientes, por voz o en persona.
--      El servidor NUNCA recibe sas_confirmed — solo registra
--      "A y B están en la room X". [CORRECCIÓN-DOC: sin sas_confirmed]
--   4. room_state separa ESTADO (overwrite: playhead, presencia) de
--      EVENTOS (append-only: messages).
--   5. Sin Firebase: se elimina sessions.fcm_token. Las notificaciones
--      (ntfy, tema derivado de roomKey publicado por el móvil emisor)
--      quedan para una fase posterior.
--
-- La BD está limpia (sin datos reales): se reconstruye messages.
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0002_rooms.sql
-- ============================================================

-- ------------------------------------------------------------
-- Salas: solo metadatos mínimos. room_id lo genera el cliente.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rooms (
    room_id    UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rooms_owner ON rooms(created_by);

-- ------------------------------------------------------------
-- Membresía: el servidor SOLO sabe quién está en qué sala.
-- SIN columna sas_confirmed: la verificación de identidad es un
-- problema cliente-cliente, no cliente-servidor.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS room_members (
    room_id   UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_room_members_user ON room_members(user_id);

-- ------------------------------------------------------------
-- Estado de sala (OVERWRITE, no append): playhead de media,
-- presencia, "en línea". Fase 3; se crea ya porque cuesta 0.
-- Clave por (room, usuario): cada miembro escribe su propio estado.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS room_state (
    room_id    UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key        TEXT NOT NULL CHECK (key ~ '^[a-z0-9_]{1,32}$'),
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id, key)
);

-- ------------------------------------------------------------
-- Mensajes: room_id sustituye a conversation_id.
-- BD limpia → reconstrucción directa (DROP + CREATE).
-- seq sigue siendo GLOBAL (identity): un único cursor de sync
-- por usuario aunque pertenezca a varias salas.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS messages;
CREATE TABLE messages (
    msg_id           UUID PRIMARY KEY,
    room_id          UUID NOT NULL REFERENCES rooms(room_id),
    sender_id        UUID NOT NULL REFERENCES users(id),
    sender_device_id UUID NOT NULL,
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,
    ciphertext       BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 16 AND 8192),
    nonce            BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_ts        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_messages_sync ON messages(room_id, seq);

-- Modelo por par de usuarios (0001) obsoleto: ya no se usa.
DROP TABLE IF EXISTS conversations;

-- Sin Firebase para la app: no hay tokens FCM que guardar.
ALTER TABLE sessions DROP COLUMN IF EXISTS fcm_token;

-- RLS activado SIN políticas (bloquea PostgREST/anon key).
ALTER TABLE rooms        ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_state   ENABLE ROW LEVEL SECURITY;
