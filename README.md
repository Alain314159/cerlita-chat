# Cerlita Chat — Chat E2EE offline-first

> Chat 1-a-1 con cifrado de extremo a extremo, historial offline y entrega fiable en redes
> de bytes por segundo. Diseñado para el contexto cubano (ETECSA): cara, lenta, intermitente.

**Estado**: Fase 1 COMPLETA — backend desplegado (37/37) y app Android **v0.4.0 "Cerlita
Chat"** migrada al modelo rooms (D54) con import del respaldo cifrado (D58) y
**perfiles E2EE con avatares** (D61). APK release 2.7 MB firmado. **Sin Firebase**
(decisión del usuario).

## Documentación

| Documento | Contenido |
|---|---|
| [docs/PLAN-MAESTRO.md](docs/PLAN-MAESTRO.md) | Fases 0-3: stack, cripto, transporte, cronograma |
| [docs/DECISIONES.md](docs/DECISIONES.md) | Log de decisiones: tomadas vs pendientes |
| [docs/FASE-0-VALIDACION.md](docs/FASE-0-VALIDACION.md) | Checklist de supervivencia y resultados |
| [backend/README.md](backend/README.md) | Backend desplegado: endpoints, deploy, hallazgos |
| [android/README.md](android/README.md) | App v0.4.0: modelo rooms, perfiles, cripto, build, tests |

## Estado (26-oct-2026)

- ✅ **Fase 0 ítem 1**: Supabase accesible desde Cuba
- ✅ **Backend Fase 1 desplegado y verificado E2E (37/37)**, **modelo rooms** (Doc v2
  corregido): salas con roomKey compartida por código de invitación que NUNCA toca el
  servidor; SAS solo entre clientes; eventos (mensajes) separados de estado
  (`room_state`). Funciones: `auth` (+ salas), `sync`, `outbox`, `heartbeat` en
  `https://wuptkddnkahmknolpxtp.supabase.co/functions/v1` — BD limpia, lista para los
  2 usuarios reales (se registran desde la app con código de invitación)
- ✅ **Anti-pausa server-side (D59)**: pg_cron + pg_net llaman por HTTPS a `heartbeat`
  cada 6 h — actividad REAL del gateway, sin depender de servicios externos
  (cron-job.org dejó de ser necesario)
- ✅ **App Android v0.4.0 "Cerlita Chat" (D18/D54/D58/D61)**: emparejamiento por código de
  invitación + SAS por voz, outbox/sync por sala, vault de roomKeys sellado por
  Keystore (sin frase E2EE), export v2 con historial + claves **e import del respaldo**
  (recuperación completa en teléfono nuevo), recuperación de sala en 2º dispositivo.
  Icono propio (burbuja + candado con cerradura de corazón). **Perfiles E2EE**: nombre +
  foto subida o avatar por capas (Cerdita y Koalita placeholder con ropa y accesorios),
  viajan cifrados como mensajes más — el servidor jamás ve nombres ni fotos y no hubo
  que tocar el backend. APK release **2.7 MB** firmado (misma firma: actualiza encima).
  Validación: 33/33 tests JVM (18 cripto con KATs cruzados Python↔Kotlin + 4 del import
  + 11 de perfiles) + E2E del protocolo cliente **22/22** contra producción
- ❌ **Sin Firebase** (orden del usuario): no FCM, no google-services.json
- ⏳ Pendiente: pruebas en los 2 móviles reales, TTFB desde SIM (ítem 5), arte real de
  los avatares (hoy placeholders), notificaciones (ntfy, fase posterior — tema derivado
  de roomKey, publicado por el móvil emisor)

## Reglas de trabajo (acordadas 26-oct-2026)

1. **Siempre que se trabaje en la app: commit + push.** Sin excepciones.
2. **Nunca commitear credenciales**: tokens, `local.properties`, keystores (`.jks` /
   `.keystore`), `google-services.json`, `.env*`. El `.gitignore` ya los excluye — no debilitarlo.
3. Sin código nuevo sin orden del usuario (el backend y la app están escritos y desplegados).
4. ~~El nombre de la app se decide después~~ **RESUELTO: "Cerlita Chat"** (D18) — repo
   renombrado a `cerlita-chat` (GitHub redirige los enlaces antiguos automáticamente).
5. Idioma de trabajo: español.

## Stack (resumen — detalles y justificación en el plan maestro)

| Capa | Elección |
|---|---|
| Lenguaje | Kotlin 2.4.x |
| UI | Jetpack Compose + Material 3 (BOM 2026.08.x) |
| DB local | Room 2.8.x |
| Red | OkHttp 5.x (HTTP/2, keep-alive) |
| Cifrado | PSK: Argon2id (argon2kt) + HKDF-SHA256 + ChaCha20-Poly1305 (Tink) |
| Background | WorkManager 2.12.x (backoff exponencial + jitter completo) |
| Backend | Supabase (Postgres + Edge Functions) — validado en Fase 0 |
| Auth | Propio (username + password) |
| Notificaciones | Sin Firebase. ntfy en fase posterior (tema derivado de roomKey, publicado por el móvil emisor); mientras tanto polling |
| Emparejamiento | Código de invitación = base32(roomSalt ‖ roomKey): ES el secreto, nunca va al servidor. SAS de 6 dígitos verificado solo entre los dos clientes |
| Transporte | HTTP polling (`GET /sync`, `POST /outbox`), compresión antes del AEAD |
