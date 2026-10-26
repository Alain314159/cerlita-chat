# Backend — Supabase Edge Functions + Postgres

Estado: **desplegado y verificado E2E (23/23 checks) el 26-oct-2026** en el proyecto
`wuptkddnkahmknolpxtp` (región us-east-2). Fase 1 completa del lado servidor.

## Arquitectura

```
App Android ──HTTPS──> Cloudflare/Supabase gateway ──> Edge Functions ──SQL──> Postgres
                        (gzip automático)               (Deno runtime)

  auth       registro/login/logout/whoami — token propio, Argon2id
  sync       GET incremental por seq — 204 vacío, gzip
  outbox     POST lotes idempotentes — ON CONFLICT DO NOTHING
  heartbeat  latido anti-pausa del plan Free
```

El servidor **solo ve ciphertext** (nunca la frase E2EE, nunca claves). RLS activado
en todas las tablas sin políticas: el acceso vía anon key/PostgREST queda bloqueado;
las funciones conectan como rol postgres (dueño).

## Endpoints

Base: `https://wuptkddnkahmknolpxtp.supabase.co/functions/v1`
Headers comunes: `apikey: <anon>` · `Authorization: Bearer <tok>` · `Content-Type: application/json`

### auth — registro/login

```bash
# Registro (requiere código de invitación)
curl -X POST .../auth -d '{"a":"reg","u":"alain","p":"password8+","d":"<device-uuid>","ic":"<INVITE>"}'
→ 201 {"uid":"...","u":"alain","tok":"<43 chars>","exp":"..."}

# Login (lockout exponencial tras 5 fallos: 30s, 60s, 120s… máx 1h)
curl -X POST .../auth -d '{"a":"in","u":"alain","p":"password8+","d":"<device-uuid>"}'
→ 200 {"uid":"...","u":"alain","tok":"...","exp":"..."}

# Whoami / logout
curl .../auth -H "Authorization: Bearer <tok>"          → 200 {uid, u, did}
curl -X POST .../auth -d '{"a":"out"}' -H "Auth..."     → 200 {ok:true}
```

### sync — recepción incremental

```bash
curl ".../sync?s=4821&l=100" -H "Authorization: Bearer <tok>"
→ 200 {"n":4903,"m":[[msg_id,conv_id,sender_id,seq,nonce_b64,ct_b64,sender_uname,ts_ms],...]}
        # sender_uname: nombre del emisor (el receptor deriva convKey con él — D33)
        # ts_ms: coalesce(client_ts, created_at) epoch ms
        header x-next-seq: 4903 · gzip si > 1 KB (y Cloudflare comprime en tránsito
        siempre que el cliente envíe Accept-Encoding: gzip — OkHttp lo hace solo)
→ 204 sin cuerpo si no hay nada nuevo
```

### outbox — envío por lotes (idempotente)

```bash
curl -X POST .../outbox -H "Authorization: Bearer <tok>" \
  -d '{"to":"pareja","m":[[msg_id_uuidv7, ct_b64, nonce_b64, ts_ms], ...]}'   # máx 64
→ 200 {"a":["<msg_id>",...]}   # acked = existen en BD (insertados ahora o antes)
```

Reintentar es SIEMPRE seguro: `msg_id` (UUIDv7 del cliente) es PK con
`ON CONFLICT DO NOTHING`. La conversación 1-a-1 se auto-crea con el par ordenado
de usuarios (`user_lo < user_hi`, UNIQUE) la primera vez que alguien escribe.

### heartbeat — anti-pausa

```bash
curl .../heartbeat → 200 {"ok":true,"t":"2026-10-26T..."}
```

Respaldado por pg_cron interno cada 6h. Falta el cron externo (cron-job.org) —
ver `docs/FASE-0-VALIDACION.md`.

## Validaciones del servidor

| Regla | Valor |
|---|---|
| username | `^[a-z0-9_]{3,24}$` (normalizado a minúsculas) |
| password | 8-512 chars (Argon2id m=32MiB t=3 p=1, PHC propio) |
| nonce | exactamente 12 bytes |
| ciphertext | 16-8192 bytes (16 = tag de mensaje vacío) |
| lote | máx 64 mensajes, todo-o-nada (se valida antes de escribir) |
| usuarios | máx 10 (tope duro) + registro con invite code |
| sesiones | token 32B aleatorio, se guarda SHA-256, expira a 90 días |
| lockout | 5 fallos → 30s × 2^(n-5), máx 1h |

## Cómo desplegar (desde cero o tras editar código)

```bash
# 0. Requisitos: bun en PATH + secrets en ~/secrets/supabase.env (fuera del repo):
#    SUPA_TOKEN (Management API), SUPA_REF, SUPA_URL, REG_INVITE_CODE
cd backend
python3 scripts/run_migration.py       # aplica migrations/*.sql (idempotente)
python3 scripts/deploy_functions.py    # bun build + deploy multipart + secrets
python3 scripts/smoke_test.py          # 23 checks E2E contra producción (limpia solo)
python3 scripts/deploy_functions.py --debug-off --no-build   # pasar a producción
```

## Hallazgos críticos del deploy (26-oct-2026) — LEER antes de tocar

1. **El endpoint legacy de body base64** (`POST/PATCH /functions` con `"body"`)
   produce funciones que NUNCA arrancan (`BOOT_ERROR`), aunque el deploy devuelva
   200/201. El único camino válido vía Management API es el **multipart**:
   `POST /v1/projects/{ref}/functions/deploy?slug={name}` con metadata
   `{"name","entrypoint_path","verify_jwt":false}` + campo `file`.
2. **El resolver `npm:` del runtime NO resuelve `@noble/hashes`** (scoped + subpath)
   en ninguna variante probada (exports map, ruta interna `esm/…`). Por eso noble
   se **empotra en el bundle** con bun (`backend/package.json`) y solo `postgres`
   queda externo como `npm:postgres@3.4.9` (verificado funcionando: heartbeat
   conecta a la DB).
3. **bun no acepta specifiers `npm:`** → el build usa el glob `--external='npm:*'`
   para conservarlos tal cual en el bundle.
4. **Cloudflare comprime/descomprime según `Accept-Encoding`**: si el cliente no
   pide gzip, el CDN descomprime y quita la cabecera (el smoke test debe enviar
   `Accept-Encoding: gzip`). OkHttp lo envía siempre → la app se beneficia sola.
5. Argon2id m=32MiB en el runtime tarda **~4 s** por login/registro (JS puro).
   Aceptable: sesiones de 90 días → login raro. Si molesta, bajar a m=19456 t=2.
6. `verify_jwt: false` en las 4 funciones: usamos token propio, no Supabase Auth.

## Estructura

```
backend/
  migrations/0001_init.sql   esquema completo (idempotente, RLS, pg_cron)
  functions/<fn>/index.ts    código fuente TypeScript (Deno.serve)
  scripts/
    api.py                   cliente Management API (stdlib, carga secrets externos)
    run_migration.py         ejecuta migraciones por statement
    deploy_functions.py      build (bun) + deploy multipart + secrets
    smoke_test.py            23 checks E2E + limpieza de datos de prueba
  package.json               noble empotrado en el bundle (postgres va externo)
```

## Límites Free del proyecto (500 MB DB · 2 GB bw/mes · 500K invocaciones/mes)

Para 2 usuarios: de sobra. Un mensaje típico ≈ 200 B → 1 GB ≈ 5 millones de mensajes.
