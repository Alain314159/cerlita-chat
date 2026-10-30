-- ============================================================
-- Migración 0005: ADJUNTOS E2EE en Storage (v0.7.0, D76)
--
-- El bucket privado «attachments» ya existe (setup_storage.py). Esta
-- migración crea la ÚNICA política de acceso: los clientes suben los
-- bytes YA CIFRADOS (ChaCha20-Poly1305; la clave viaja E2EE dentro del
-- mensaje de chat) directamente a Storage con la anon key.
--
-- Por qué es seguro permitir INSERT al rol anónimo con prefijo UUID:
--   - El path es <room_id>/<uuid>.<ext>: la primera carpeta DEBE ser un
--     roomId válido. Conocer un roomId (HKDF de roomKey, 128 bits) YA es
--     la capability de membresía del modelo de salas (room_join acepta a
--     cualquiera que lo conozca y puede enviar mensajes ilimitados por
--     outbox): escribir bytes opacos en su namespace no amplía la
--     superficie de ataque.
--   - SIN SELECT: nadie puede LEER nada por Storage (las descargas pasan
--     por /files, que verifica membresía y devuelve URL firmada corta).
--   - SIN UPDATE/DELETE para anónimo: los nombres son UUID del cliente,
--     reescribir un path ajeno es imposible (INSERT only) y los objetos
--     solo se borran con la service key.
--
-- Ejecutar con: python3 backend/scripts/run_migration.py backend/migrations/0005_attachments.sql
-- ============================================================

-- Solo INSERT al bucket de adjuntos, con primera carpeta = UUID (roomId)
CREATE POLICY "attachments_insert_by_room_capability" ON storage.objects
    FOR INSERT TO anon
    WITH CHECK (
        bucket_id = 'attachments'
        AND (storage.foldername(name))[1]
            ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    );
