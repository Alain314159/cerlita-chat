-- ============================================================
-- Migración 0006: IDENTIDAD MULTI-DISPOSITIVO (v0.19.0, D119 —
-- Fase I del informe 1.0, brecha 3 «identidad de dispositivo frágil»)
--
-- Problema que cierra: los chats directos se emparejan cifrando la
-- roomKey con la pública X25519 del otro teléfono bajo la regla
-- «la última clave escrita gana» (users.pub, D74). Reinstalar la app
-- o cambiar de teléfono reemplaza el par de claves → los envoltorios
-- quedan muertos → la pareja rehace el vínculo a mano, en silencio.
--
-- Solución (estándar del informe §4.3): un pequeño CONJUNTO de claves
-- por usuario y la roomKey envuelta para cada dispositivo activo:
--
--   1. user_keys: varias claves públicas por usuario (cap 5 en auth).
--      users.pub se CONSERVA como espejo «la más reciente» para los
--      clientes v0.18 (compatibilidad: buscan y envuelven solo esa).
--   2. dm_keys.for_key: PARA qué clave del receptor se hizo el wrap
--      (null = legado D74). from_key: con qué clave del CREADOR se
--      hizo — sin esto, si el creador rota claves, el /sync serviría
--      su pública ACTUAL y el unwrap fallaría aunque el receptor
--      esté sano (bug latente de D74, ahora cerrado).
--   3. room_members.needs_rewrap_at: el aviso de re-emparejamiento.
--      El teléfono que no puede abrir sus wraps lo marca; el /sync
--      del PAR se lo devuelve (campo rr) con sus claves activas para
--      que re-envuelva la roomKey sin intervención manual.
--
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0006_multidevice.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS user_keys (
    key_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pub        BYTEA NOT NULL CHECK (octet_length(pub) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (user_id, pub)
);

COMMENT ON TABLE user_keys IS
    'Claves públicas X25519 activas por usuario (D119): un conjunto pequeño de dispositivos; los wraps de chats directos se hacen para cada una. users.pub queda como espejo de la última para clientes v0.18.';

CREATE INDEX IF NOT EXISTS idx_user_keys_active
    ON user_keys(user_id) WHERE revoked_at IS NULL;

-- Backfill: la pub existente de cada usuario se convierte en su 1ª clave.
INSERT INTO user_keys (user_id, pub)
SELECT id, pub FROM users WHERE pub IS NOT NULL
ON CONFLICT DO NOTHING;

-- ── dm_keys: el wrap sabe para qué clave y desde qué clave ──
ALTER TABLE dm_keys
    ADD COLUMN IF NOT EXISTS for_key UUID REFERENCES user_keys(key_id) ON DELETE CASCADE;

ALTER TABLE dm_keys
    ADD COLUMN IF NOT EXISTS from_key UUID REFERENCES user_keys(key_id) ON DELETE SET NULL;

COMMENT ON COLUMN dm_keys.for_key IS
    'Clave del RECEPTOR para la que se envolvió la roomKey (null = wrap legado D74 hecho para users.pub de entonces).';
COMMENT ON COLUMN dm_keys.from_key IS
    'Clave del CREADOR con la que se envolvió: /sync sirve ESA pública (no la actual del creador) para que el unwrap no muera por rotación del creador.';

-- Una fila por (sala, receptor, clave). NULLS NOT DISTINCT: los wraps
-- legados (for_key null) siguen siendo únicos por (sala, receptor) como
-- en 0004; los nuevos conviven con ellos.
ALTER TABLE dm_keys DROP CONSTRAINT IF EXISTS dm_keys_room_id_for_user_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_dm_keys_room_user_key
    ON dm_keys(room_id, for_user, for_key) NULLS NOT DISTINCT;

-- ── aviso de re-emparejamiento (Fase I) ──
ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS needs_rewrap_at TIMESTAMPTZ;

COMMENT ON COLUMN room_members.needs_rewrap_at IS
    'Fase I/D119: este miembro no puede abrir sus wraps de chats directos (teléfono nuevo/reinstalado). El /sync del OTRO miembro lo ve (campo rr) y re-envuelve la roomKey para sus claves activas.';
