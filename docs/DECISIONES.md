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

## Pendientes (bloquean el arranque de Fase 1)

| # | Área | Opciones | Recomendación |
|---|------|----------|---------------|
| P1 | minSdk | 24 / 26 / 21 / 28 | 24 (máximo alcance; Tink soporta 24+; requiere desugaring) |
| P2 | Estilo UI | oscuro+auto / claro M3 / solo oscuro / ultra ligero | Oscuro por defecto, sigue al sistema |
| P3 | rootKey en dispositivo | Keystore wrap / re-derivar siempre / configurable | Keystore (derivación 1 vez; luego desbloqueo instantáneo) |
| P4 | Extras día 1 | export cifrado / indicador de red / bloqueo bio / modo ahorro | Export cifrado (riesgo documentado en el plan) |
| P5 | applicationId / package | — | Esperar al nombre de la app |
| P6 | Licencia | propietaria / MIT / Apache-2.0 / AGPL | Propietaria (uso personal) salvo publicación |

## Fase 0 — estado del checklist

Ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md). Ítem 1 (Supabase accesible): ✅.
Ítems 2-5: pendientes de confirmar.
