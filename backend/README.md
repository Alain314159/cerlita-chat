# Backend — Supabase Edge Functions + Postgres

Estado: **desplegado y verificado E2E** (37/37 smoke + **13/13 timbre Realtime** +
**24/24 recibos/salida de sala**, 28-oct-2026) en el proyecto
`wuptkddnkahmknolpxtp` (región us-east-2). Fase 1 completa del lado servidor, **modelo
rooms** (Documento v2 corregido: sala con roomKey compartida por código de invitación
que NUNCA toca el servidor; SAS verificado solo entre clientes).

## Arquitectura

```
App Android ──HTTPS──> Cloudflare/Supabase gateway ──> Edge Functions ──SQL──> Postgres
                        (gzip automático)               (Deno runtime)

  auth       registro/login/logout/whoami + salas (room_new/room_join/room_leave/rooms)
  sync       GET incremental por seq — 204 vacío, gzip, rd (recibos de entrega, D68)
  outbox     POST lotes idempotentes a una sala — ON CONFLICT DO NOTHING
             + timbre Realtime al canal room-<roomId> (D63; solo despierta,
               jamás transporta contenido)
  heartbeat  latido anti-pausa del plan Free

App Android ──WSS──> Supabase Realtime (timbre: broadcast room-<roomId>)
```

El servidor **solo ve ciphertext y room_ids** (nunca la roomKey, nunca el código de
invitación, nunca el SAS). RLS activado en todas las tablas sin políticas: el acceso vía
anon key/PostgREST queda bloqueado; las funciones conectan como rol postgres (dueño).

## Endpoints

Base: `https://wuptkddnkahmknolpxtp.supabase.co/functions/v1`
Headers comunes: `apikey: <anon>` · `Authorization: Bearer <tok>` · `Content-Type: application/json`

### auth — registro/login/salas

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

# Crear sala: room_id lo genera el CLIENTE (HKDF(roomKey, info="room-id")[0..16] como
# UUID). La roomKey NUNCA viaja. Idempotente: reintentar devuelve 201 otra vez.
curl -X POST .../auth -d '{"a":"room_new","r":"<room-uuid>"}' -H "Auth..."
→ 201 {ok:true}   ·  409 room_taken (room_id de otro) · 403 room_cap (>8 salas)

# Unirse a una sala (tras escanear el código de invitación). El SAS NO se envía:
# se compara por voz SOLO entre los dos clientes (decisión D29).
curl -X POST .../auth -d '{"a":"room_join","r":"<room-uuid>"}' -H "Auth..."
→ 200 {ok:true}   ·  404 no_room · 403 room_full (>6 miembros)

# Listar mis salas (multi-dispositivo / reinstalación) — D119: kind y pending
#   kind: 0=grupo · 1=chat directo · pending: 1=tengo wraps sin consumir (no es
#   caso de recuperación: el /sync los traerá)
curl -X POST .../auth -d '{"a":"rooms"}' -H "Auth..."
→ 200 {"rooms":[["<room-uuid>",1 /*owner*/,1 /*kind*/,0 /*pending*/],...]}

# ── D119 (v0.19.0, Fase I): identidad multi-dispositivo ──

# Registrar la pública del teléfono: se AÑADE al conjunto user_keys (hasta 5
# activas; la 6ª retira la más vieja). users.pub queda como espejo de la última
# (clientes v0.18). reg/in también aceptan pk y TODOS devuelven kid.
curl -X POST .../auth -d '{"a":"pk","pk":"<b64 32B>"}' -H "Auth..."
→ 200 {ok:true, kid:"<uuid>"}

# Buscar por prefijo: elemento 2 = pub heredada, elemento 3 = claves ACTIVAS
curl -X POST .../auth -d '{"a":"search","q":"amelia"}' -H "Auth..."
→ 200 {"r":[["<uid>","amelia","<pub-heredada>",[["<kid>","<pub>"],...],...]]}

# Crear chat directo: ws = un wrap POR clave activa del par (≤16), fk = MI kid
# (el /sync sirve la pública de from_key — la que de verdad hizo el wrap).
curl -X POST .../auth -d '{"a":"dm_new","r":"<room>","peer":"amelia",
  "w":"<wrap-heredado>","ws":[["<kid-par>","<wrap>"],...],"fk":"<mi-kid>"}' -H "Auth..."
→ 201 {ok,room,exists:0}  ·  exists=1 → 200 {room, exists:1, ws:[[pubQueLoHizo,wrap],...]}

# Este teléfono no puede abrir sus wraps (teléfono nuevo): marcar el aviso.
# Consume los wraps pendientes; el /sync del PAR lo devuelve como rr.
curl -X POST .../auth -d '{"a":"dm_stuck","r":"<room>"}' -H "Auth..."
→ 200 {ok:true}

# Re-emparejamiento (lo envía el par que SÍ tiene la roomKey): un wrap por
# clave activa del otro miembro. Limpia su needs_rewrap_at.
curl -X POST .../auth -d '{"a":"dm_wraps","r":"<room>","ws":[["<kid-par>","<wrap>"],...],"fk":"<mi-kid>"}' -H "Auth..."
→ 200 {ok:true}  ·  403 not_member · 400 bad_wrap (kid ajeno, validado ANTES de tocar nada)
```

### sync — recepción incremental

```bash
curl ".../sync?s=4821&l=100" -H "Authorization: Bearer <tok>"
→ 200 {"n":4903,"m":[[msg_id,room_id,sender_id,seq,nonce_b64,ct_b64,sender_uname,ts_ms],...]}
        # sender_uname: nombre del emisor (SOLO para la UI — en modelo rooms no deriva
        #               convKey: el salt es roomId y la clave madre es roomKey, D41/D42)
        # ts_ms: coalesce(client_ts, created_at) epoch ms
        # dm (D74/D119): [[roomId, fromUid, fromUname, fromPubB64, wrappedB64], ...]
        #   wraps de chats directos pendientes — puede haber VARIOS por sala (uno
        #   por dispositivo); fromPubB64 es la de from_key (la que hizo el wrap)
        # rr (D119): [[roomId, peerUid, [[kid, pubB64], ...]], ...]
        #   pedidos de re-emparejamiento: el OTRO miembro no puede abrir sus
        #   wraps — vienen sus claves activas para re-envolver sin otra ida
        header x-next-seq: 4903 · gzip si > 1 KB (y Cloudflare comprime en tránsito
        siempre que el cliente envíe Accept-Encoding: gzip — OkHttp lo hace solo)
→ 204 sin cuerpo si no hay nada nuevo
```

Solo mensajes de salas donde el usuario es miembro (`room_members`).

### outbox — envío por lotes a una sala (idempotente)

```bash
curl -X POST .../outbox -H "Authorization: Bearer <tok>" \
  -d '{"room":"<room-uuid>","m":[[msg_id_uuidv7, ct_b64, nonce_b64, ts_ms], ...]}'   # máx 64
→ 200 {"a":["<msg_id>",...]}   # acked = existen en BD (insertados ahora o antes)
```

Reintentar es SIEMPRE seguro: `msg_id` (UUIDv7 del cliente) es PK con
`ON CONFLICT DO NOTHING`. El servidor NO conoce el destinatario por username — solo
verifica la membresía del emisor en la sala (404 genérico si no es miembro: no revela
si la sala existe). Conocer el room_id (2^128, derivado de la roomKey) ES la capability.

**Timbre Realtime (D63)**: si el lote insertó filas NUEVAS (RETURNING — un reintento
idempotente no vuelve a timbrar), la función hace
`POST /realtime/v1/api/broadcast {messages:[{topic:"room-<room_id>", event:"new-msg",
payload:{roomId}}]}` con la anon key (hallazgo 11). Los clientes con el WebSocket
abierto reciben el aviso y responden con `/sync` — el timbre NUNCA transporta
contenido. Best-effort: si falla, el sync periódico de la app (15 min) alcanza.
E2E: `python3 scripts/e2e_doorbell.py` (13 checks, requiere `pip install websockets`).

### heartbeat — anti-pausa

```bash
curl .../heartbeat → 200 {"ok":true,"t":"2026-10-26T..."}
```

**Anti-pausa server-side ACTIVO (D59)**: pg_cron + pg_net llaman por HTTPS a esta
función cada 6 h (job `anti-pausa-http`). La invocación pasa por el gateway →
cuenta como actividad REAL del proyecto (los writes internos de pg_cron NO
cuentan para el auto-pause del plan Free). Ya NO hace falta cron-job.org ni
ningún servicio externo. Ver `scripts/setup_anti_pausa.py` (idempotente).

## Validaciones del servidor

| Regla | Valor |
|---|---|
| username | `^[a-z0-9_]{3,24}$` (normalizado a minúsculas) |
| password | 8-512 chars (Argon2id m=32MiB t=3 p=1, PHC propio) |
| nonce | exactamente 12 bytes |
| ciphertext | 16-8192 bytes (16 = tag de mensaje vacío) |
| lote | máx 64 mensajes, todo-o-nada (se valida antes de escribir) |
| usuarios | máx 10 (tope duro) + registro con invite code |
| salas | máx 8 por usuario · máx 6 miembros por sala |
| sesiones | token 32B aleatorio, se guarda SHA-256, expira a 90 días |
| lockout | 5 fallos → 30s × 2^(n-5), máx 1h |

## Cómo desplegar (desde cero o tras editar código)

```bash
# 0. Requisitos: bun en PATH + secrets en ~/secrets/supabase.env (fuera del repo):
#    SUPA_TOKEN (Management API), SUPA_REF, SUPA_URL, REG_INVITE_CODE
cd backend
python3 scripts/run_migration.py                     # 0001 (idempotente)
python3 scripts/run_migration.py migrations/0002_rooms.sql   # modelo rooms
python3 scripts/deploy_functions.py                  # bun build + deploy + secrets
python3 scripts/smoke_test.py                        # 37 checks E2E (limpia solo)
python3 scripts/deploy_functions.py --debug-off     # pasar a producción (recompila)
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
7. **CTE data-modifying no se ve a sí mismo**: en Postgres el statement principal corre
   con la MISMA snapshot que el `WITH INSERT` → `WITH ins AS (INSERT rooms…)
   INSERT members WHERE EXISTS(rooms…)` nunca ve la sala recién insertada (la 1ª
   llamada fallaba con 409). Solución: queries SEPARADAS en secuencia.
8. **`ON CONFLICT DO NOTHING` + `RETURNING` no devuelve filas del conflicto**: el
   reintento legítimo (membresía ya existente) devolvía vacío y se confundía con
   error. Solución: verificar condición con SELECT aparte; el insert idempotente
   va sin RETURNING.
9. `deploy_functions.py --no-build` re-despliega el BUNDLE PREVIO: si se editó código
   TS, SIEMPRE re-compilar (sin `--no-build`) o el fix no llega a producción.
10. **El REST broadcast de Realtime exige el topic SIN prefijo `realtime:`**: con
    `realtime:<canal>` el gateway responde 202 (aceptado) pero NADIE lo recibe —
    el mensaje se publica en el canal `realtime:realtime:<canal>`. El cliente se
    suscribe a `realtime:<canal>`; el REST publica en `<canal>` a secas.
11. **La autorización del REST broadcast en canales NO privados es la publishable
    (anon) key**, no la service role: las claves nuevas `sb_secret_` dan 401/403
    en Realtime (no son JWT). `Authorization: Bearer <anon>` + `apikey: <anon>`
    → 202 y entrega verificada. Para canales privados haría falta RLS sobre
    realtime (innecesario aquí: el nombre del canal ES la capability, D63).
12. **La BD de producción ya tiene usuarios reales (27-oct: alain, amelia)**: los
    smoke tests cuentan SOLO sus propios datos (`where room_id in (mis salas)`),
    nunca absolutos. `smoke_test.py` ajustado en consecuencia.

## Estructura

```
backend/
  migrations/0001_init.sql   users/sessions + heartbeat + RLS + pg_cron (idempotente)
  migrations/0002_rooms.sql  modelo rooms: rooms/room_members/room_state + messages.room_id
  functions/<fn>/index.ts    código fuente TypeScript (Deno.serve)
  scripts/
    api.py                   cliente Management API (stdlib, carga secrets externos)
    run_migration.py         ejecuta migraciones por statement
    deploy_functions.py      build (bun) + deploy multipart + secrets
    smoke_test.py            37 checks E2E + limpieza de datos de prueba
  package.json               noble empotrado en el bundle (postgres va externo)
```

## Límites Free del proyecto (500 MB DB · 2 GB bw/mes · 500K invocaciones/mes)

Para 2 usuarios: de sobra. Un mensaje típico ≈ 200 B → 1 GB ≈ 5 millones de mensajes.

## v0.7.0 — chats directos + adjuntos (D74/D76)

- Migración 0004: users.pub (X25519 pública), dm_keys (wraps opacos),
  rooms.kind + dm_pair UNIQUE por pareja. Acciones auth: search/pk/dm_new/
  dm_ack; /sync gana campo dm (wrap + pública del creador, hasta el ack);
  broadcast al topic personal user-<uid> al crear un DM.
- Migración 0005 + bucket «attachments» (setup_storage.py, privado, 25 MB):
  política RLS INSERT-only a <roomId>/<uuid>.<ext> (capability del roomId);
  función /files: URL firmada de descarga (10 min) SOLO para miembros.
- HALLAZGOS 15-18 en docs/DECISIONES.md (mangling de consola, tope de
  usuarios en e2e, Storage exige JWT clásico → secret SUPA_SVC_JWT,
  upload-sign no disponible → subida directa con anon key).
- e2e_dm 20/20 · e2e_files 20/20 · smoke 37/37 · funciones v56.

## v0.14.0 — canal ntfy (D104): CERO cambios de backend

Las notificaciones ntfy (D45 adelantada por el usuario) NO tocan el servidor:
el tema se deriva de la roomKey en el MÓVIL (`HKDF(roomKey, salt=roomId,
"ntfy-topic-v1")` → `c`+26 base32 = 27 chars, 128 bits) — el emisor publica el
ping a ntfy.sh DESPUÉS del ack del outbox (orden canónico), y el receptor lo
poll-ea con cursor `since`. El servidor jamás conoce los temas; Supabase puede
caerse que el aviso (con el sobre cifrado dentro) sigue llegando.

- `scripts/e2e_ntfy.py` 32/32 contra producción + ntfy.sh REAL: tema derivado,
  ping con sobre descifrado SIN round-trip a Supabase, degradación a puro
  >3400 chars, inyección/replay (AEAD + dedup por msgId), eco propio, multi-tema
  en un poll, `since=<unix>` como cursor, mensaje grande por /sync.
- **H25 (hallazgo ntfy.sh)**: un body >4 KB NO se rechaza — se convierte en
  ATTACHMENT (retención 3 h + URL pública) y el `message` del evento deja de
  ser el ping. El umbral propio de 3400 chars lo evita de raíz; el parse del
  cliente descarta el evento en silencio.
- `X-Firebase: no` en todo publish (ntfy.sh reenviaría a FCM por defecto; el
  proyecto prohíbe Firebase). Límites respetados: 12 h caché, 250 msg/día,
  60 req/5 s, 200 MB/día (los polls cuentan desde ntfy 2.28) — SIEMPRE `since`.
