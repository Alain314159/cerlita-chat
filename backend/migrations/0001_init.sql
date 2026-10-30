-- ============================================================
-- Migración 0001: esquema inicial del chat E2EE (Fase 1)
-- Ejecutar con: python3 backend/scripts/run_migration.py
-- Idempotente: seguro de re-ejecutar.
-- ============================================================

-- gen_random_uuid() (normalmente ya activa en Supabase)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------------------
-- Usuarios: auth propio. pass_hash = Argon2id (PHC) calculado en
-- la Edge Function. La frase E2EE NUNCA llega al servidor.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT UNIQUE NOT NULL CHECK (username ~ '^[a-z0-9_]{3,24}$'),
    pass_hash     TEXT NOT NULL,
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sesiones: token propio de 32 bytes; se guarda SHA-256(token_b64url)
CREATE TABLE IF NOT EXISTS sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id    UUID NOT NULL,
    token_hash   TEXT UNIQUE NOT NULL,
    fcm_token    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT now() + interval '90 days',
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- Conversaciones 1-a-1: par ordenado (user_lo < user_hi) = único
CREATE TABLE IF NOT EXISTS conversations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_lo    UUID NOT NULL REFERENCES users(id),
    user_hi    UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_lo, user_hi),
    CHECK (user_lo < user_hi)
);

-- Mensajes: SOLO ciphertext. msg_id = UUIDv7 del cliente (idempotencia)
CREATE TABLE IF NOT EXISTS messages (
    msg_id           UUID PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES conversations(id),
    sender_id        UUID NOT NULL REFERENCES users(id),
    sender_device_id UUID NOT NULL,
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,
    ciphertext       BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 16 AND 8192),
    nonce            BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_ts        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_messages_sync ON messages(conversation_id, seq);

-- Latido anti-pausa (plan Free)
CREATE TABLE IF NOT EXISTS app_heartbeat (
    id        INTEGER PRIMARY KEY CHECK (id = 1),
    last_beat TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO app_heartbeat (id) VALUES (1) ON CONFLICT DO NOTHING;

-- RLS activado SIN políticas: bloquea el acceso vía PostgREST/anon key.
-- Las Edge Functions conectan como rol postgres (dueño) y no se ven afectadas.
ALTER TABLE users         ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages      ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_heartbeat ENABLE ROW LEVEL SECURITY;

-- Latido interno cada 6h (respaldo; el cron externo será el principal)
CREATE EXTENSION IF NOT EXISTS pg_cron;
SELECT cron.unschedule('heartbeat-anti-pausa')
 WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'heartbeat-anti-pausa');
SELECT cron.schedule('heartbeat-anti-pausa', '17 */6 * * *',
                     $$update app_heartbeat set last_beat = now()$$);
