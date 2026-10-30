-- ============================================================
-- Migración 0007: CANAL FCM (D121 — Firebase Cloud Messaging)
--
-- Problema que cierra: las 4 capas de despertar actuales (timbre WS
-- D63, ntfy D104, alarma D105, worker 15 min) dependen del proceso
-- vivo, de un WebSocket que el OEM puede matar o de un poll que
-- ETECSA puede frenar. El canal persistente de Google Play Services
-- entrega con la app MUERTA y sin gastar batería en mantener nada.
--
-- Diseño (espejo deliberado de D104/ntfy):
--   * sessions.fcm_token: el token de ESTA sesión. Una sesión = un
--     dispositivo = un token. Logout (revoke) apaga el grifo solo.
--   * El token se registra desde la app (POST /push) SOLO si el
--     teléfono tiene Play Services; sin él la columna queda NULL y
--     el outbox ni lo intenta — las otras capas cubren igual.
--   * Lo que viaja por FCM es el MISMO ping cifrado del canal ntfy
--     ({"v":1,"r":…,"m":[…]}): el sobre AEAD con la roomKey que el
--     servidor de Google nunca ve. Sin contenido nuevo en claro.
--
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0007_push.sql
-- ============================================================

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS fcm_token TEXT;

COMMENT ON COLUMN sessions.fcm_token IS
    'FCM (D121): token de Firebase Cloud Messaging del dispositivo de esta sesión. Se registra por POST /push (solo teléfonos con Play Services). El outbox lo usa como 5ª capa de despertar; el contenido viaja cifrado en el ping igual que ntfy (D104). NULL = este dispositivo no usa FCM.';

-- Búsqueda de destinatarios en el outbox: sesiones vivas con token,
-- miembro de la sala. Índice parcial: las revocadas/expiradas no se tocan.
CREATE INDEX IF NOT EXISTS idx_sessions_fcm_active
    ON sessions(user_id) WHERE fcm_token IS NOT NULL AND revoked_at IS NULL;
