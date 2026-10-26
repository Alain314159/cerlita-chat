# Log de decisiones

Última actualización: 2026-10-26 (fusión: cliente Android v1 + backend modelo rooms del
Documento v2 revisado, con sus 3 correcciones obligatorias)

Orígenes: **Plan v1.0** (documento original del usuario) · **Usuario 26-oct** (indicación
directa) · **Análisis 26-oct** (correcciones del análisis de viabilidad, presentadas sin
objeción; se ratifican al escribir código) · **Cliente 26-oct** (implementación Android v1)
· **Doc v2 revisado 26-oct** (especificación principal: modelo rooms, código de invitación,
SAS cliente-cliente, eventos/estado, ntfy pospuesta; el usuario validó el documento y
ordenó 3 correcciones — D42, D43, D46 — antes de implementar).

## Decididas

| # | Área | Decisión | Origen |
|---|------|----------|--------|
| D1 | Backend | Supabase (Postgres + Edge Functions) — Plan A | Usuario 26-oct: "Supabase funciona" (validado desde Cuba) |
| D2 | Transporte | HTTP polling `GET /sync` + `POST /outbox`; NO WebSocket ni Supabase Realtime | Plan v1.0 |
| D3 | Cifrado | PSK: Argon2id (t=3, m=64 MiB, p=1) → HKDF-SHA256 → ChaCha20-Poly1305 | Plan v1.0 |
| D4 | Lib AEAD | Tink 1.24.x (`tink-crypto/tink-java`, activo oct-2026, soporte Android API 24+). Alternativa: libsodium | Plan v1.0 + Análisis 26-oct (verificación de vida del proyecto) |
| D5 | Lib KDF | argon2kt 1.6.0 (puro JVM, 0 bytes de .so) | Plan v1.0 |
| D6 | Auth | Propia: username + password. La frase E2EE es INDEPENDIENTE del password | Plan v1.0 |
| D7 | DB local | Room 2.8.5 (3.0 es alpha KMP — no usar en v1) | Plan v1.0 + Análisis 26-oct |
| D8 | Background | WorkManager 2.12.x, backoff exponencial + jitter completo | Plan v1.0 |
| D9 | Notificaciones | ~~FCM data-message~~ **SUSTITUIDA por D44/D45**: sin Firebase; ntfy (fase posterior) con tema derivado de roomKey | Usuario 26-oct: "No utilizaremos Firebase para la app" |
| D10 | Compresión | Antes del AEAD, nunca después; gzip solo si plaintext > 512 B; lotes como un solo stream | Plan v1.0 |
| D11 | Idempotencia | `msg_id` = UUIDv7 generado en cliente + `ON CONFLICT DO NOTHING` en servidor | Plan v1.0 |
| D12 | AAD | `msg_id + sender_id + conversation_id` (el plan original solo usaba msg_id) | Análisis 26-oct |
| D13 | Bot (Fase 2) | El bot conoce SOLO `commandKey`, NUNCA rootKey/roomKey. Se corrige la contradicción del plan original | Análisis 26-oct |
| D14 | Claves | `convKey = HKDF(roomKey, salt=roomId, info="conv-v1")` — aislamiento por sala; `msgKey = HKDF(convKey, salt=msg_id, info="msg-v1")` | Análisis 26-oct, actualizada por Doc v2 (modelo rooms) |
| D15 | Polling | Adaptive: 5 s tras actividad → backoff hasta 2-5 min en reposo → reset con envío (o ping ntfy cuando exista) | Análisis 26-oct |
| D16 | UUIDv7 | Implementación propia en cliente (~20 líneas, RFC 9562) — Kotlin 2.4 solo trae UUIDv4 | Análisis 26-oct |
| D17 | Repo | GitHub **privado**; regla permanente: commit + push en cada sesión de trabajo | Usuario 26-oct |
| D18 | Nombre | PENDIENTE — se elige después; el repo se renombra cuando toque | Usuario 26-oct |
| D19 | Código | ~~Sin escribir código hasta nueva orden~~ Backend y cliente v1 escritos con autorización expresa del usuario | Usuario 26-oct |
| D20 | Registro | Protegido por código de invitación (secret REG_INVITE_CODE) + tope duro de 10 usuarios | Análisis 26-oct (evita registros de extraños que conozcan la URL) |
| D21 | Password servidor | Argon2id m=32MiB t=3 p=1 calculado en la Edge Function (noble, embebido en bundle), formato PHC propio | Implementación 26-oct |
| D22 | Sesiones | Token propio 32B, se guarda SHA-256(tok), expira a 90 días, revocable. verify_jwt=false en las 4 funciones | Implementación 26-oct |
| D23 | Conversación | ~~Auto-creada con par ordenado~~ **SUSTITUIDA por D41**: modelo rooms, la sala la crea explícitamente el cliente | Implementación 26-oct, reemplazada por Doc v2 |
| D24 | Lockout login | 5 fallos → 30s × 2^(n-5), máx 1h | Implementación 26-oct |
| D25 | Deploy | Únicamente vía endpoint multipart (el legacy de body base64 produce BOOT_ERROR). noble empotrado con bun; postgres externo npm:postgres@3.4.9. Ver backend/README «Hallazgos» | Implementación 26-oct |
| D26 | gzip | El servidor gzip si > 1 KB; Cloudflare comprime en tránsito según Accept-Encoding. OkHttp lo gestiona solo. Doble capa, sin coste | Implementación 26-oct |
| D33 | convKey salt | ~~Par ORDENADO de usernames~~ **ACTUALIZADA por D41/D42** (modelo rooms): salt = `roomId`, clave madre = `roomKey` del código de invitación. La app Android v1 (frase compartida + par de usernames) se adapta al modelo rooms — ver D54 | Cliente 26-oct, actualizada por Doc v2 |
| D34 | AAD formato | `"v1\|<msgId>\|<senderUid>\|<roomId>"` UTF-8 (D12 con separadores anti-ambigüedad; en modelo rooms el cuarto campo es roomId, antes lo\|hi) | Cliente 26-oct, actualizada por Doc v2 |
| D35 | Framing plaintext | `[flag 1B][payload]`: flag 0 = UTF-8 crudo, 1 = gzip (solo si texto > 512 B). Compresión SIEMPRE antes del AEAD | Cliente 26-oct |
| D36 | Verificación frase | check = HMAC-SHA256(HKDF(rootKey, info="e2ee-check"), constante) — aplica a la app v1 con frase compartida. En el modelo rooms (D41+) el emparejamiento es por código de invitación y la verificación es el SAS (D43); la frase desaparece del flujo | Cliente 26-oct, alcance limitado por Doc v2 |
| D37 | Toolchain Android | AGP 9.1.1 (Kotlin INTEGRADO: no se aplica kotlin-android; KGP 2.4.20 fijado por buildscript classpath) · Gradle 9.8.2 · KSP 2.3.12 · Compose BOM 2026.08.00 · compileSdk 37 · targetSdk 36 · minSdk 24 · desugaring 2.1.5 · R8 ON desde v1 | Verificado 26-oct |
| D38 | argon2kt | CORRECCIÓN de D5: 1.6.0 usa JNI con .so embebidos (4 ABIs, ~350 KB comprimidos), NO es "JVM puro". Funciona igual: 0 configuración, carga fuera del hilo principal | Verificado 26-oct |
| D39 | sync payload | Filas extendidas: `[msg_id, room_id, sender_id, seq, nonce_b64, ct_b64, sender_uname, ts_ms]` — sender_uname pasa a ser SOLO para la UI (en modelo rooms ya no deriva convKey); ts_ms = hora de muestra. Smoke test 37/37 | Servidor 26-oct, actualizada por Doc v2 |
| D40 | Firma APK | Keystore PKCS12 en secrets/app-release.jks (FUERA del repo; contraseñas en secrets/keystore-info.txt — RESPALDARLO). Release 2.6 MB con R8+shrinkResources. mapping.txt v1 en secrets/mappings/ | Cliente 26-oct |
| D41 | Salas | **Modelo rooms**: `room_id` lo genera el CLIENTE (`HKDF(roomKey, salt=roomSalt, info="room-id")[0..16]` como UUID); conocer el room_id (2^128) es la capability de membresía. Tablas `rooms` + `room_members`; tope 8 salas/usuario, 6 miembros/sala | Doc v2 + Implementación 26-oct (migración 0002) |
| D42 | Invitación | **El código de invitación ES el secreto**: `inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC)` — la roomKey viaja EN CLARO DENTRO del código, sin cifrado adicional. JAMÁS se transmite al servidor; se comparte por canal controlado (en persona, WhatsApp, papel). B decodifica y deriva room_id + claves sin hablar con el servidor | Doc v2 [CORRECCIÓN: elimina la auto-contradicción del cifrado del código] |
| D43 | SAS | Número de seguridad de 6 dígitos = `HKDF(roomKey, salt=roomSalt, info="sas")[0..4]`. Se compara SOLO entre los dos clientes, por voz o en persona. El servidor NUNCA recibe `sas_confirmed` — la verificación de identidad es cliente-cliente, no cliente-servidor | Doc v2 [CORRECCIÓN: eliminado sas_confirmed del payload] |
| D44 | Firebase | **NO se usa Firebase para la app** (ni FCM ni google-services.json ni service account). Columna `sessions.fcm_token` eliminada. La Edge Function `push` prevista queda cancelada | Usuario 26-oct (orden directa) |
| D45 | Notificaciones (futuras) | **ntfy**, pospuestas a fase posterior. Tema = derivado de roomKey (`HKDF(roomKey, info="ntfy-topic")`), publicado por el **móvil emisor** tras el ack — el servidor NUNCA conoce el tema y las notificaciones sobreviven a caídas de Supabase. Límites ntfy.sh verificados: 12h caché, 250 msg/día, 60 req/5s, 200 MB/día egress. Fallback: polling. Si ETECSA bloquea ntfy.sh → notificaciones "al abrir la app" (aceptable, no rompe el chat) o self-host en VPS europeo | Doc v2 + Usuario 26-oct ("las notificaciones las dejaremos para después") |
| D46 | Rotación roomKey | Cuando exista ntfy: suscripción DUAL a tema viejo + nuevo durante 24-48h y publicar en AMBOS durante la transición (el receptor aún suscrito al viejo no puede perder el ping). Requiere confirmación de ambos antes de retirar el tema viejo | Doc v2 [CORRECCIÓN: transición definida] |
| D47 | Export | El export de emergencia (P4) se cifra con contraseña INDEPENDIENTE (ni la frase E2EE ni el password de login). Contiene historial + roomKeys; nada en claro. La roomKey jamás se exporta sin cifrar | Doc v2 [CORRECCIÓN del "botón de emergencia"] |
| D48 | Eventos vs estado | Mensajes = eventos append-only; playhead/presencia/"en línea" = estado OVERWRITE (`room_state`, PK room+usuario+clave). Ahorra ancho de banda y elimina duplicados | Doc v2 |
| D49 | Bot (Fase 2) | Refinamiento de D13: el bot opera en una SALA DE COMANDOS separada (`commandKey = HKDF(roomKey, salt=roomSalt, info="commands")`); la sala de conversación jamás toca al bot. HKDF unidireccional: comprometer la función del bot no compromete la conversación | Doc v2 |
| D50 | Región | Proyecto en **us-east-2**. Medir TTFB desde la SIM real (ítem 5 de Fase 0) antes de asumir; migrar de región es barato con los scripts existentes (recrear proyecto + re-ejecutar migraciones + redeploy ~15 min) | Doc v2 [a validar] + estado actual del proyecto |
| D51 | Alcance V1 | Sin media ni "escribiendo..." (una foto a 1 KB/s = 2-4 min; el indicador no aporta con 500 ms de RTT y gasta bytes). Voz en v3: preferir chunks Opus por HTTPS antes que WebRTC (evita 10-20 KB de handshake) — decidir antes de v3 | Doc v2 |
| D52 | Onboarding | La primera ejecución: < 60 segundos y < 5 toques. Invertir en onboarding antes que en features — el riesgo #1 es humano (que ella no instale/teclee/espere), no técnico | Doc v2 |
| D53 | Oracle Cloud | Free Tier DESCARTADO como Plan C: Oracle recupera VMs idle (CPU < 20% durante 7 días; un bot al 2% lo borran) y pide tarjeta + teléfono. Plan C real = VPS europeo (Hetzner/OVH) | Doc v2 |
| D54 | Migración app → rooms | **COMPLETADA (26-oct, v0.2.0)**: la app usa emparejamiento por código de invitación (base32, D42), SAS por voz (D43), outbox `{"room": room_id}`, vault con roomKey sellado por Keystore y AAD con roomId. 18/18 tests de cripto + E2E del protocolo cliente 22/22 contra producción (`backend/scripts/e2e_rooms_client.py`) | Fusión 26-oct |
| D55 | Vault sin frase | La frase E2EE DESAPARECE del flujo (rol histórico de D36): las roomKeys se guardan en `rooms.bin` sellado por la clave hardware del Keystore (P3) y se recargan a RAM al arrancar — desbloqueo instantáneo (mejor D52). Las pantallas Setup/Unlock y el botón "Bloquear" se eliminan: en reposo protegen el cifrado de disco de Android + el bloqueo de pantalla del sistema. El export sigue con contraseña independiente (D47) | Implementación D54 |
| D56 | Detalles de formato rooms | `convKey = HKDF(roomKey, salt=roomId_string, info="conv-v1")` · `AAD = "v1|msgId|senderId|roomId"` (pipe, minúsculas) · `inviteCode = base32("V1"‖salt‖key‖CRC32)` sin padding, grupos de 5 con guion (104 chars) · `SAS = HKDF(key,salt,"sas")[0..4]` UInt-BE mod 10⁶ · roomId = HKDF(...,"room-id")[0..16] como UUID hex (sin bits de versión). Fijado por KATs cruzados Python↔Kotlin | Implementación D54 |
| D57 | Validación del cliente | Doble verificación: (1) tests JVM `CryptoRoomsTest` (18) con vectores conocidos calculados con una implementación Python independiente verificada contra RFC 5869; (2) E2E `e2e_rooms_client.py` (22 checks) que replica el protocolo exacto de la app contra producción y limpia la BD. Encontró y corrigió el bug de `sas6` con Int con signo (SAS negativo cuando el byte alto ≥ 0x80) | Implementación D54 |

## Resueltas (26-oct-2026 — usuario: «Usa las recomendadas»)

| # | Área | Decisión | Origen |
|---|------|----------|--------|
| P1 | minSdk | **24** (máximo alcance en Cuba; Tink soporta 24+; requiere core library desugaring) | Recomendada, confirmada |
| P2 | Estilo UI | **Oscuro por defecto, sigue al sistema** (Material 3 dynamic color cuando esté disponible) | Recomendada, confirmada |
| P3 | rootKey en dispositivo | **Keystore wrap** — Argon2id se calcula 1 vez al configurar; rootKey envuelto por clave AES hardware-backed; desbloqueo instantáneo después | Recomendada, confirmada |
| P4 | Extras día 1 | **Export cifrado del historial** (con contraseña independiente — D47) + indicador de red como parte natural del estado de sincronización | Recomendada, confirmada |
| P5 | applicationId | **`cu.alain.chat`** — package de trabajo acordado; independiente del nombre visible (D18); renombrable antes de publicación | Acordado + recomendada |
| P6 | Licencia | **Propietaria** (uso personal, 2 usuarios); revisar solo si se publica | Recomendada, confirmada |

Sin decisiones pendientes. ~~Próximo hito: adaptar la app Android al modelo rooms (D54)~~ **D54 completada**. Próximo hito: **pruebas en los 2 móviles reales** (instalar `chat-e2ee-v0.2.0-release.apk`, emparejar por código de invitación, verificar SAS por voz y entrega con red lenta) + ítems 2/5 de Fase 0.

## Fase 0 — estado del checklist

Ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md). Ítem 1 (Supabase accesible): ✅.
Ítems 3-4 (Firebase/GMS): ❌ descartados (D44 — sin Firebase). Ítems 2 y 5: pendientes
del usuario.

## Backend — estado (26-oct-2026, modelo rooms)

**Desplegado y verificado E2E (37/37)**: migraciones 0001+0002 aplicadas (modelo rooms:
`rooms`, `room_members`, `room_state`, `messages.room_id`, sin `conversations`, sin
`fcm_token`), 4 Edge Functions en producción (auth con `room_new`/`room_join`/`rooms`,
sync con sender_uname+ts_ms, outbox por sala, heartbeat), pg_cron anti-pausa activo,
BD limpia. Detalle técnico y hallazgos del deploy: [backend/README.md](../backend/README.md).
Pendiente del lado servidor: cron externo (cron-job.org). Sin Firebase (D44); las
notificaciones (ntfy, D45) se implementan en fase posterior.

## Cliente Android — estado (26-oct-2026, v0.2.0)

**Migrada al modelo rooms (D54) y firmada**: APK release **2.6 MB**. Emparejamiento por
código de invitación (D42) con pantalla de SAS (D43), outbox/sync por sala, vault de
roomKeys sellado por Keystore (D55), export v2 con historial + claves (D47), recuperación
de sala sin clave en 2º dispositivo (re-pegar el código). Validación: 18/18 tests JVM +
E2E del protocolo 22/22 contra producción. Ver [android/README.md](../android/README.md).
