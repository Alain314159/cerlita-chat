# La App (nombre pendiente) — Chat E2EE offline-first

> Chat 1-a-1 con cifrado de extremo a extremo, historial offline y entrega fiable en redes
> de bytes por segundo. Diseñado para el contexto cubano (ETECSA): cara, lenta, intermitente.

**Estado**: backend Fase 1 desplegado y verificado en producción (37/37, modelo rooms,
26-oct-2026). App Android pendiente. **Sin Firebase** (decisión del usuario).

## Documentación

| Documento | Contenido |
|---|---|
| [docs/PLAN-MAESTRO.md](docs/PLAN-MAESTRO.md) | Fases 0-3: stack, cripto, transporte, cronograma |
| [docs/DECISIONES.md](docs/DECISIONES.md) | Log de decisiones: tomadas vs pendientes |
| [docs/FASE-0-VALIDACION.md](docs/FASE-0-VALIDACION.md) | Checklist de supervivencia y resultados |
| [backend/README.md](backend/README.md) | Backend desplegado: endpoints, deploy, hallazgos |

## Estado (26-oct-2026)

- ✅ **Fase 0 ítem 1**: Supabase accesible desde Cuba
- ✅ **Backend Fase 1 desplegado y verificado E2E (37/37)**, **modelo rooms** (Doc v2
  corregido): salas con roomKey compartida por código de invitación que NUNCA toca el
  servidor; SAS solo entre clientes; eventos (mensajes) separados de estado
  (`room_state`). Funciones: `auth` (+ salas), `sync`, `outbox`, `heartbeat` en
  `https://wuptkddnkahmknolpxtp.supabase.co/functions/v1` — BD limpia, lista para los
  2 usuarios reales (se registran desde la app con código de invitación)
- ❌ **Sin Firebase** (orden del usuario): no FCM, no google-services.json
- ⏳ Pendiente: app Android (Fase 1 cliente), cron externo, notificaciones (ntfy, fase
  posterior — tema derivado de roomKey, publicado por el móvil emisor)

## Reglas de trabajo (acordadas 26-oct-2026)

1. **Siempre que se trabaje en la app: commit + push.** Sin excepciones.
2. **Nunca commitear credenciales**: tokens, `local.properties`, keystores (`.jks` /
   `.keystore`), `google-services.json`, `.env*`. El `.gitignore` ya los excluye — no debilitarlo.
3. **Sin código de app hasta nueva orden** (el backend ya está escrito y desplegado).
4. El nombre de la app se decide después; el repo se renombra cuando toque (GitHub redirige
   los enlaces antiguos automáticamente).
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
