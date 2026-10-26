# Plan maestro

**Versión 1.2 — 26 de octubre de 2026.**
Integra el plan original (v1.0, del usuario), las correcciones del análisis de viabilidad
del 26-oct-2026 y el **Documento v2 revisado** (especificación principal desde esta
versión: modelo rooms, código de invitación, SAS cliente-cliente, eventos/estado,
notificaciones ntfy pospuestas, sin Firebase). Los bloques marcados **[CORRECCIÓN]** o
**[MEJORA]** difieren del plan original y son vinculantes.

**Objetivo**: chat 1-a-1 con E2EE, historial offline y entrega fiable en redes de bytes por
segundo, para 2 usuarios en Cuba. El costo real de la red no son los bytes: es el RTT y la
intermitencia. Todo el diseño optimiza: (1) minimizar requests, (2) sobrevivir cortes,
(3) jamás perder ni duplicar un mensaje, (4) que el servidor vea solo ciphertext.

---

## Fase 0 — Validación de supervivencia (1 día)

El riesgo principal del proyecto NO es técnico: es geopolítico (OFAC prohíbe a empresas de
EE. UU. servir a residentes en Cuba; Supabase es empresa de EE. UU.).

**Estado**: ítem 1 validado (Supabase accesible y funcional desde Cuba, 26-oct-2026).
Ítems 3-4 (Firebase/GMS) **descartados** por decisión del usuario (sin Firebase).
Ítems 2 y 5 pendientes → ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md).

Regla del plan original adaptada: el BACKEND ya está escrito, desplegado y verificado
(37/37) — la app Android v1 (frase compartida) requiere adaptación al modelo rooms (D54).

---

## Fase 1 — MVP: el chat que funciona (2-3 semanas)

Chat 1-a-1 con E2EE, historial offline y entrega fiable en redes de bytes por segundo.

### 1.1 Stack concreto (versiones verificadas oct-2026)

| Capa | Elección | Versión / criterio |
|---|---|---|
| Lenguaje | Kotlin | 2.4.20 estable (2.5.0 previsto dic-2026) |
| UI | Jetpack Compose + Material 3 | BOM 2026.08.00 (Compose 1.12; requiere compileSdk 37 + AGP 9.1.1+) |
| DB local | Room | 2.8.5 estable (3.0 es alpha KMP — no usar en v1) |
| Red | OkHttp | 5.x estable (HTTP/2, keep-alive; artefactos JVM/Android separados) |
| Serialización | JSON compacto (campos de 1-2 letras) | Sin protobuf ni MessagePack en v1 |
| Cifrado | PSK: Argon2id + HKDF + ChaCha20-Poly1305 | argon2kt + Tink (o libsodium) |
| KDF | argon2kt | 1.6.0 (puro JVM, 0 bytes de .so) |
| AEAD | Tink (`tink-android`) | 1.24.x — repo `tink-crypto/tink-java` activo (commits oct-2026); soporte oficial Android API 24+. Alternativa: libsodium (XChaCha20-Poly1305) |
| Background | WorkManager | 2.12.0 |
| Backend | Supabase Edge Functions + Postgres | Plan A VALIDADO en Fase 0 |
| Auth | Propio (username + password) | NO Supabase Auth (evita el flujo de email) |
| Notificaciones | **Sin Firebase** (decisión del usuario). ntfy en fase posterior: tema derivado de roomKey, publicado por el móvil emisor | Fallback: polling Supabase |

Nota: con minSdk 24 se requiere `coreLibraryDesugaring` para `java.time` (estándar, sin
costo). P1 resuelto: minSdk 24 confirmado ("Usa las recomendadas", 26-oct).

### 1.2 Por qué PSK y no libsignal

| Aspecto | libsignal | PSK (elegido) |
|---|---|---|
| Overhead por mensaje | ~100-150 B | ~40 B |
| Complejidad | Alta (JNI, prekeys, ratchet) | Baja (~1-2 días) |
| Recuperación tras perder móvil | Imposible sin backup | Con la frase (Argon2id) |
| Forward secrecy | Sí | No (trade-off aceptado) |
| Licencia | AGPL-3.0 | Tuya |

En Cuba perder o cambiar de móvil es un hecho, no una excepción: el código de invitación
(guarda una copia) + el export cifrado SON el backup. Con libsignal, perder el móvil =
perder la conversación para siempre.

### 1.3 Modelo criptográfico (Doc v2 corregido: sala con roomKey, no frase compartida)

El emparejamiento NO es por frase: A crea la sala con una clave aleatoria y la comparte
con B mediante un **código de invitación**. La contraseña de login y la contraseña del
export siguen siendo secretos SEPARADOS de todo esto.

```
1. A crea la sala en su móvil:
   roomKey = CSPRNG(32 B)   ·   roomSalt = CSPRNG(16 B)
2. roomId = UUID( HKDF-SHA256(roomKey, salt=roomSalt, info="room-id")[0..16] )
   → el servidor SOLO conoce roomId (capability de membresía, 2^128)
3. [CORRECCIÓN-DOC] inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC32)
   → El código ES el secreto: la roomKey viaja EN CLARO dentro del código,
     sin cifrado adicional. NUNCA se transmite al servidor. Se comparte por un
     canal controlado (en persona, WhatsApp, papel). B decodifica y deriva roomId
     + todas las claves sin hablar con el servidor.
4. [CORRECCIÓN-DOC] SAS = 6 dígitos de HKDF(roomKey, salt=roomSalt, info="sas")
   → Se compara SOLO entre los dos clientes (por voz / en persona).
     El servidor JAMÁS recibe "sas_confirmed": solo registra "A y B están en la sala X".
5. convKey = HKDF-SHA256(roomKey, salt=roomId, info="conv-v1")
6. Para cada mensaje:
   - msgKey = HKDF-SHA256(convKey, salt = msg_id, info = "msg-v1")
   - nonce = random(12 bytes)
   - AAD = msg_id || sender_id || roomId
   - ciphertext = ChaCha20-Poly1305(key = msgKey, nonce, plaintext, aad)
7. Fase 2: commandKey = HKDF(roomKey, salt=roomSalt, info="commands") — SOLO el bot.
8. Export de emergencia: cifrado con Argon2id(contraseña INDEPENDIENTE)
   [CORRECCIÓN-DOC]; contiene historial + roomKeys; nada en claro.
9. El servidor solo ve ciphertext. No puede derivar nada: no tiene roomKey.
```

Recuperación tras perder el móvil (nuevo modelo): re-escanear el código de invitación
(la roomKey está dentro) + importar el export cifrado. Si se pierden AMBOS, la sala se
re-crea de cero (la frase compartida del plan v1.0 queda descartada como mecanismo
de emparejamiento; su rol de "secreto de respaldo" lo asume la contraseña del export).

[CORRECCIÓN] UUIDv7: ni Java ni Kotlin lo traen nativo (Kotlin 2.4 estabilizó UUID v4).
Se implementa en el cliente (~20 líneas, RFC 9562): timestamp + random, ordenable por
tiempo, ideal como PK de idempotencia.

### 1.4 Esquema de la base de datos (desplegado — migraciones 0001 + 0002)

```sql
-- Salas: roomId lo genera el CLIENTE (derivado de roomKey). Capability de membresía.
CREATE TABLE rooms (
    room_id    UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- El servidor SOLO sabe quién está en qué sala. SIN sas_confirmed (D29):
-- la verificación de identidad es cliente-cliente.
CREATE TABLE room_members (
    room_id   UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

-- Estado OVERWRITE (playhead, presencia — Fase 3): separado de eventos.
CREATE TABLE room_state (
    room_id UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key     TEXT NOT NULL, value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id, key)
);

-- Mensajes (solo ciphertext): eventos append-only
CREATE TABLE messages (
    msg_id           UUID PRIMARY KEY,  -- UUIDv7 generado en el cliente
    room_id          UUID NOT NULL REFERENCES rooms(room_id),
    sender_id        UUID NOT NULL,
    sender_device_id UUID NOT NULL,
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,  -- orden global del servidor
    ciphertext       BYTEA NOT NULL,
    nonce            BYTEA NOT NULL,
    created_at       TIMESTAMPTZ DEFAULT now(),
    client_ts        TIMESTAMPTZ
);
CREATE INDEX idx_messages_sync ON messages(room_id, seq);
```

Idempotencia por construcción: `msg_id` (UUIDv7) es PK; el servidor hace
`INSERT ... ON CONFLICT (msg_id) DO NOTHING`. Reintentar es siempre seguro.

### 1.5 Transporte: HTTP polling, no WebSocket

Supabase Realtime como transporte principal queda descartado: un mensaje cuesta ~146 B
(a 1 kb/s ≈ 1.17 s) y en el CGNAT de ETECSA las conexiones WebSocket ociosas mueren sin
aviso (bug silencioso). El RTT es el coste real, no los bytes: 5 requests × 500 ms = 2.5 s;
1 request = 500 ms.

```
GET /sync?s=<seq>&l=<límite>          (Bearer token de sesión)
→ 200 {n: next_seq, m: [[msg_id, room_id, sender_id, seq, nonce_b64, ct_b64], ...]}
   gzip si el cuerpo supera 1 KB · header "x-next-seq"
→ 204 si no hay nada nuevo (0 bytes de cuerpo)

POST /outbox
{room: "<room_id>", m: [[msg_id, ct_b64, nonce_b64, ts_ms?], ...]}   -- lote ≤ 64
→ 200 {a: [msg_id con ack...]}

POST /auth {a: "reg"|"in"|"out"|"room_new"|"room_join"|"rooms", ...}
```

[MEJORA] Adaptive polling: 5 s tras actividad visible → backoff geométrico hasta 2-5 min
en reposo → reset inmediato al enviar un mensaje (y, cuando existan notificaciones, al
llegar el ping de ntfy). Ahorra batería y datos sin dañar la sensación de inmediatez.

### 1.6 Compresión: antes de cifrar, nunca después

El gzip de HTTP sobre ciphertext no sirve: el ciphertext es pseudoaleatorio e
incompresible. La compresión útil ocurre antes del AEAD:

```
plaintext → (gzip si > 512 B) → AEAD → ciphertext → red
```

- Mensajes de texto típicos (20-200 B): NO comprimir (la cabecera gzip de 18 B + CPU no
  compensan).
- Lotes de historial: comprimir el stream completo, no cada mensaje. 50 mensajes comprimen
  2-3× mejor como un solo stream.
- [CORRECCIÓN] No contar con el `Content-Encoding: gzip` de `/sync` como ahorro real: el
  cuerpo son ciphertexts (incompresibles). El ahorro está en el emisor (comprimir plaintext)
  y en un framing binario compacto (campos de 1-2 letras, sin JSON verbose en el cuerpo).
- Imágenes: reescalar a 1600 px + recomprimir a q=72 ANTES de cifrar. Es la mayor palanca
  de ahorro de datos.

### 1.7 Cola offline con WorkManager

Requisitos: outbox persistente en Room; envío por lotes; backoff exponencial con jitter
COMPLETO (no solo el backoff por defecto de WorkManager, que es 30 s → 60 s → 120 s…) para
evitar la tormenta de reconexión cuando la red vuelve y todos los workers disparan a la vez.

```kotlin
// Especificación de diseño (referencia) — el código final se escribe en Fase 1
val workRequest = OneTimeWorkRequestBuilder<OutboxWorker>()
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.SECONDS)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .build()

// Jitter completo dentro del worker:
val delay = min(cap, base * 2.0.pow(attempts)) * Random.nextDouble(0.7, 1.3)
```

### 1.8 Notificaciones: POSPUESTAS — ntfy cuando toque (sin Firebase)

Decisión del usuario (26-oct): "No utilizaremos Firebase para la app" y "las
notificaciones las dejaremos para después". El diseño aprobado para esa fase:

- Tema ntfy = derivado de roomKey (`HKDF(roomKey, info="ntfy-topic")`): el servidor
  NUNCA conoce el tema; las notificaciones sobreviven a caídas de Supabase.
- El **móvil emisor** publica el ping (mensaje mínimo, sin ciphertext) tras recibir el
  ack del outbox — no hay Edge Function `push`.
- Rotación de roomKey: suscripción DUAL a tema viejo + nuevo 24-48h, publicar en ambos
  durante la transición [CORRECCIÓN-DOC].
- Límites verificados de ntfy.sh: 12 h de caché, 250 msg/día, 60 req/5 s, 200 MB/día de
  egress. Si ETECSA bloquea ntfy.sh → notificaciones "al abrir la app" (aceptable, no
  rompe el chat) o self-host en VPS europeo.
- Mientras tanto: polling adaptativo como único mecanismo de entrega.

---

## Fase 2 — Bot, cupones y un minijuego (1-2 semanas)

### 2.1 Dos canales separados — el bot NO puede leer el chat

Un bot de servidor que conozca rootKey convierte el E2EE en "cifrado contra todos menos
el servidor". [CORRECCIÓN] El plan original contenía una contradicción ("el bot conoce
roomKey" vs "el bot solo tiene una clave derivada"). Lo vinculante es esto:

```
Canal 1 — CONVERSACIÓN (humano-humano)
   E2EE con convKey. El servidor ve solo ciphertext. El bot NUNCA toca este canal.

Canal 2 — COMANDOS (app ↔ bot, en una SALA separada)
   commandKey = HKDF-SHA256(roomKey, salt = roomSalt, info = "commands")
   El bot conoce SOLO commandKey (provisionada una vez desde la app de un usuario).
   El servidor no puede derivar commandKey: no tiene roomKey.
   HKDF es unidireccional: con commandKey tampoco se recupera roomKey.
```

El bot es un dispositivo de confianza con una clave derivada de mínimo privilegio, no con
la clave principal.

### 2.2 Cupones: validación atómica en Postgres

```sql
-- Canje atómico: solo si hay saldo y el cupón está activo
UPDATE coupons
   SET redeemed_by = $1, redeemed_at = now()
 WHERE id = $2
   AND active
   AND NOT EXISTS (SELECT 1 FROM coupon_ledger WHERE coupon_id = $2)
RETURNING id;
-- 0 filas → ya canjeado o no existe → no hay doble canje ni con reintentos
```

Regla de oro: el canje es UNA sola sentencia condicional. Postgres serializa las
escrituras. Nunca "leer saldo → calcular → escribir" (race condition).

### 2.3 Minijuego por turnos

Sin WebRTC (10-20 KB de handshake, 3 RTTs). Un juego por turnos necesita 50 B y 1 request:

```
1. Jugador A mueve → se cifra con commandKey.
2. Se envía como comando estructurado (no texto libre).
3. El bot (Edge Function) valida el movimiento contra el estado en Postgres.
4. El bot actualiza estado y responde con el nuevo estado cifrado.
5. Jugador B recibe el nuevo estado en el siguiente poll (o ping ntfy cuando exista).
```

Renderizado: Compose con Canvas y primitivas vectoriales, 0 assets binarios. Un juego que
descarga 5 MB de arte al abrir es inviable en este contexto.

---

## Fase 3 — Media y Watch Party (1-2 semanas)

### 3.1 Imágenes: miniatura primero, original bajo demanda

```
1. Cliente reescala a 1600 px + recomprime a q=72 (~60-120 KB).
2. Cifra con clave por archivo: fileKey = HKDF(rootKey, info = "file", salt = fileId).
3. Sube a Supabase Storage vía multipart reanudable (API S3).
4. Envía mensaje con fileId, fileKey cifrada y metadatos.
```

Modo low-data: primero miniatura (~10 KB); el original solo bajo pedido o con WiFi.

### 3.2 Watch Party: playhead sync, nunca streaming

Streaming es inviable: 1 kb/s ≈ 45 KB/hora; una película de 1.5 GB = 9 días.

```
1. Ambos tienen el archivo local (USB / SD / WiFi share).
2. Ambos lo abren en su reproductor.
3. Se sincroniza el PLAYHEAD, no el vídeo:
   PLAYHEAD { pos_ms: 1234567, playing: true, rate: 1.0 }  ≈ 40 B
4. El host envía su posición cada 2-5 s.
5. Receptor: |drift| > 300 ms → seek; 50-300 ms → ajuste de rate (0.97×/1.03×).
```

Coste: 40 B / 5 s = 8 B/s. Funciona incluso a 1 kbps. La latencia no importa: 2 s de
diferencia entre posiciones es invisible en una película.

Voz (opcional, v3): Opus 16 kbps = 2 KB/s por dirección. Factible a 10 kbps, marginal a
1-2 kbps. [DOC v2] Para 2 usuarios, preferir chunks de Opus por HTTPS antes que WebRTC
(evita el handshake de 10-20 KB) — decisión vinculante antes de empezar v3.

---

## Riesgos y Planes B/C (actualizado 26-oct-2026)

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Supabase suspende la cuenta por OFAC | 🔴 Proyecto muerto | Ítem 1 de Fase 0 validado ✅ + email a soporte (ítem 2, pendiente). Plan C: proveedor NO estadounidense |
| FCM/ntfy no disponible | 🟠 Notificaciones tardías | Sin Firebase (D30). ntfy pospuesto (D31); mientras tanto polling. Si ntfy.sh se bloquea desde Cuba: notificación "al abrir la app" o self-host VPS europeo |
| ETECSA bloquea el dominio | 🟠 App inutilizable | Evidencia real: India bloqueó `*.supabase.co` completo en feb-2026. Mitigación: dominio propio + export cifrado + cliente tras interfaz `Transport` intercambiable |
| Plan Free se pausa a los 7 días | 🟡 DB congelada | [CORRECCIÓN] Cron EXTERNO gratuito (cron-job.org, Alemania) llamando una Edge Function cada 3 días — más fiable que pg_cron interno como señal de actividad |
| Perder código de invitación + export | 🔴 Sala irrecuperable | Guardar el código (papel/nube personal) + export cifrado periódico; la sala se re-crea de cero como último recurso |
| [NUEVO] Migrar a Cloudflare (Plan B original) como "hedge" | 🟠 Falsa sensación de seguridad | Cloudflare es empresa de EE. UU.: MISMO riesgo OFAC que Supabase. El Plan B es solo hedge técnico. El hedge geopolítico es el Plan C: VPS europeo (Hetzner/OVH, ~4 €/mes) o Supabase self-hosted — la UE prohíbe acatar el embargo extraterritorialmente (Reg. UE 2271/96) |
| [NUEVO] Límites D1 (solo si Plan B) | 🟡 Quotas | Desde sept-2026 D1 aplica enforcement duro: 5M lecturas/día, 100K escrituras/día, 5 GB storage. Para 2 usuarios sobra, pero pasarse de quota ahora SÍ falla |

Diseño anti-secuestro de proveedor (se aplica desde v1):

1. El cliente solo habla 2 endpoints tras una interfaz `Transport` intercambiable.
2. El protocolo (JSON compacto + 2 rutas) es portable a cualquier servidor en ~300 líneas.
3. Exportación cifrada del historial desde el día 1.

Límites Free de Supabase: 500 MB DB, 2 GB bandwidth/mes, 500K invocaciones de Edge
Functions/mes, 150 s máx por invocación. Para 2 usuarios: de sobra.

---

## Criterios de aceptación verificables (Doc v2)

- **Fase 1**: tras un corte de red de 72 h, todos los mensajes pendientes se entregan sin
  duplicados y en orden (seq del servidor + idempotencia por msg_id).
- **Onboarding**: primera ejecución < 60 segundos y < 5 toques hasta poder escribir.
- **Red hostil**: mensaje de texto entregado con RTT de 500 ms y 1 kb/s sin error visible.
- **APK**: medir tamaño real con Tink antes de comprometer objetivo (estimación inicial
  < 15 MB — verificar al compilar).
- **Fase 2**: canje de cupón concurrente (dos toques simultáneos) → exactamente 1 canje.
- **Fase 3**: playhead sync a 8 B/s; imagen de 100 KB en < 3 min a 1 kb/s.

## Cronograma realista

| Semana | Entregable | Criterio de éxito |
|---|---|---|
| 1 | ✅ Backend (hecho, 37/37) + proyecto Android + auth propio | Login funciona con username/password |
| 2 | Cifrado sala (roomKey/inviteCode/SAS) + envío/recepción + Room + outbox | Mensaje enviado sin red se entrega al recuperar red, sin duplicados |
| 3 | Sync incremental + manejo de errores + export cifrado | Backfill funciona tras 1 h offline; export restaura en otro móvil |
| 4 | Pruebas de caos: 1 kb/s, cortes de 30 min y 72 h, cierre forzado | 0 duplicados, 0 pérdidas, orden correcto |
| 5-6 | Fase 2: bot (sala de comandos) + cupones + 1 minijuego | Canje atómico; el juego recupera estado tras 3 h offline |
| 7-8 | Fase 3: imágenes + watch party (+ evaluar notificaciones ntfy) | Imagen de 100 KB en < 3 min a 1 kb/s; playhead sync funciona |
