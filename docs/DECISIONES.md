# Log de decisiones

Última actualización: 2026-10-26

Orígenes: **Plan v1.0** (documento original del usuario) · **Usuario 26-oct** (indicación
directa) · **Análisis 26-oct** (correcciones del análisis de viabilidad, presentadas sin
objeción; se ratifican al escribir código).

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
| D9 | Notificaciones | FCM data-message con ciphertext en el payload; fallback automático a poll + notificación local si no hay GMS | Plan v1.0 + Análisis 26-oct (fallback automático) |
| D10 | Compresión | Antes del AEAD, nunca después; gzip solo si plaintext > 512 B; lotes como un solo stream | Plan v1.0 |
| D11 | Idempotencia | `msg_id` = UUIDv7 generado en cliente + `ON CONFLICT DO NOTHING` en servidor | Plan v1.0 |
| D12 | AAD | `msg_id + sender_id + conversation_id` (el plan original solo usaba msg_id) | Análisis 26-oct |
| D13 | Bot (Fase 2) | El bot conoce SOLO `commandKey = HKDF(rootKey, info="commands")`, NUNCA rootKey/roomKey. Se corrige la contradicción del plan original | Análisis 26-oct |
| D14 | Claves | `convKey = HKDF(rootKey, salt=conversation_id)` — aislamiento por conversación, prepara multi-conversación | Análisis 26-oct |
| D15 | Polling | Adaptive: 5 s tras actividad → backoff hasta 2-5 min en reposo → reset con FCM o envío | Análisis 26-oct |
| D16 | UUIDv7 | Implementación propia en cliente (~20 líneas, RFC 9562) — Kotlin 2.4 solo trae UUIDv4 | Análisis 26-oct |
| D17 | Repo | GitHub **privado**; regla permanente: commit + push en cada sesión de trabajo | Usuario 26-oct |
| D18 | Nombre | PENDIENTE — se elige después; el repo se renombra cuando toque | Usuario 26-oct |
| D19 | Código | Sin escribir código hasta nueva orden explícita | Usuario 26-oct |
| D20 | Registro | Protegido por código de invitación (secret REG_INVITE_CODE) + tope duro de 10 usuarios | Análisis 26-oct (evita registros de extraños que conozcan la URL) |
| D21 | Password servidor | Argon2id m=32MiB t=3 p=1 calculado en la Edge Function (noble, embebido en bundle), formato PHC propio | Implementación 26-oct |
| D22 | Sesiones | Token propio 32B, se guarda SHA-256(tok), expira a 90 días, revocable. verify_jwt=false en las 4 funciones | Implementación 26-oct |
| D23 | Conversación | Auto-creada con par ordenado (user_lo < user_hi, UNIQUE) al primer mensaje. Escala a más usuarios sin migración | Implementación 26-oct |
| D24 | Lockout login | 5 fallos → 30s × 2^(n-5), máx 1h | Implementación 26-oct |
| D25 | Deploy | Únicamente vía endpoint multipart (el legacy de body base64 produce BOOT_ERROR). noble empotrado con bun; postgres externo npm:postgres@3.4.9. Ver backend/README «Hallazgos» | Implementación 26-oct |
| D26 | gzip | El servidor gzip si > 1 KB; Cloudflare comprime en tránsito según Accept-Encoding. OkHttp lo gestiona solo. Doble capa, sin coste | Implementación 26-oct |
| D33 | convKey salt | Par ORDENADO de usernames `"lo\|hi"` (ambos extremos lo conocen sin lookup; el servidor ya conoce ambos → misma seguridad que conversation_id, que es aleatorio y no predecible por el cliente) | Cliente 26-oct |
| D34 | AAD formato | `"v1\|<msgId>\|<senderUid>\|<lo>\|<hi>"` UTF-8 (D12 con separadores anti-ambigüedad) | Cliente 26-oct |
| D35 | Framing plaintext | `[flag 1B][payload]`: flag 0 = UTF-8 crudo, 1 = gzip (solo si texto > 512 B). Compresión SIEMPRE antes del AEAD | Cliente 26-oct |
| D36 | Verificación frase | check = HMAC-SHA256(HKDF(rootKey, info="e2ee-check"), constante) — valida la frase sin servidor ni Keystore. Sesión (token) cifrada en ss.bin con clave AES-256-GCM de AndroidKeyStore | Cliente 26-oct |
| D37 | Toolchain Android | AGP 9.1.1 (Kotlin INTEGRADO: no se aplica kotlin-android; KGP 2.4.20 fijado por buildscript classpath) · Gradle 9.8.2 · KSP 2.3.12 · Compose BOM 2026.08.00 · compileSdk 37 · targetSdk 36 · minSdk 24 · desugaring 2.1.5 · R8 ON desde v1 | Verificado 26-oct |
| D38 | argon2kt | CORRECCIÓN de D5: 1.6.0 usa JNI con .so embebidos (4 ABIs, ~350 KB comprimidos), NO es "JVM puro". Funciona igual: 0 configuración, carga fuera del hilo principal | Verificado 26-oct |
| D39 | sync payload | Filas extendidas: `[msg_id, conv_id, sender_id, seq, nonce_b64, ct_b64, sender_uname, ts_ms]` — el receptor necesita sender_uname para derivar convKey (D33) y ts_ms para mostrar la hora. Smoke test 24/24 | Servidor 26-oct |
| D40 | Firma APK | Keystore PKCS12 en secrets/app-release.jks (FUERA del repo; contraseñas en secrets/keystore-info.txt — RESPALDARLO). Release 2.6 MB con R8+shrinkResources. mapping.txt v1 en secrets/mappings/ | Cliente 26-oct |

## Resueltas (26-oct-2026 — usuario: «Usa las recomendadas»)

| # | Área | Decisión | Origen |
|---|------|----------|--------|
| P1 | minSdk | **24** (máximo alcance en Cuba; Tink soporta 24+; requiere core library desugaring) | Recomendada, confirmada |
| P2 | Estilo UI | **Oscuro por defecto, sigue al sistema** (Material 3 dynamic color cuando esté disponible) | Recomendada, confirmada |
| P3 | rootKey en dispositivo | **Keystore wrap** — Argon2id se calcula 1 vez al configurar; rootKey envuelto por clave AES hardware-backed; desbloqueo instantáneo después | Recomendada, confirmada |
| P4 | Extras día 1 | **Export cifrado del historial** (riesgo de pérdida documentado en el plan) + indicador de red como parte natural del estado de sincronización | Recomendada, confirmada |
| P5 | applicationId | **`cu.alain.chat`** — package de trabajo acordado; independiente del nombre visible (D18); renombrable antes de publicación | Acordado + recomendada |
| P6 | Licencia | **Propietaria** (uso personal, 2 usuarios); revisar solo si se publica | Recomendada, confirmada |

Sin decisiones pendientes. Próximo hito: Fase 1 cliente Android.

## Fase 0 — estado del checklist

Ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md). Ítem 1 (Supabase accesible): ✅.
Ítems 2-5: pendientes de confirmar.

## Backend — estado (26-oct-2026)

**Desplegado y verificado E2E (23/23)**: migración SQL aplicada, 4 Edge Functions en
producción (auth/sync/outbox/heartbeat), pg_cron anti-pausa activo, smoke test completo
en verde y BD limpia. Detalle técnico y hallazgos del deploy: [backend/README.md](../backend/README.md).
Pendiente del lado servidor: solo el cron externo (cron-job.org) y FCM (fase Firebase).
