-- ============================================================
-- Migración 0003: recibos de ENTREGA (v0.6.0, D68)
--
-- El servidor ya sabe qué mensajes bajó cada miembro por /sync
-- (los entregó). Lo registramos por (room, member) y el /sync lo
-- devuelve al emisor como `rd` → el cliente marca ✓✓ «entregado».
--
-- Metadatos que el servidor YA conocía (quién sincronizó qué):
-- no se filtra nada nuevo. El RECIBO DE LECTURA sigue siendo
-- E2EE (mensaje de control kind=3, invisible para el servidor).
--
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0003_delivery.sql
-- ============================================================

ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS delivered_seq BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN room_members.delivered_seq IS
    'Seq global máximo que este miembro ya bajó por /sync en la sala (recibos de entrega, D68).';
