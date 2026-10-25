# Plan maestro

**Versión 1.1 — 26 de octubre de 2026.**
Integra el plan original (v1.0, del usuario) con las correcciones del análisis de viabilidad
del 26-oct-2026. Los bloques marcados **[CORRECCIÓN]** o **[MEJORA]** difieren del plan
original y son vinculantes.

**Objetivo**: chat 1-a-1 con E2EE, historial offline y entrega fiable en redes de bytes por
segundo, para 2 usuarios en Cuba. El costo real de la red no son los bytes: es el RTT y la
intermitencia. Todo el diseño optimiza: (1) minimizar requests, (2) sobrevivir cortes,
(3) jamás perder ni duplicar un mensaje, (4) que el servidor vea solo ciphertext.

---

## Fase 0 — Validación de supervivencia (1 día)

El riesgo principal del proyecto NO es técnico: es geopolítico (OFAC prohíbe a empresas de
EE. UU. servir a residentes en Cuba; Supabase es empresa de EE. UU.).

**Estado**: ítem 1 validado (Supabase accesible y funcional desde Cuba, 26-oct-2026).
Ítems 2-5 pendientes de confirmar → ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md).

Regla del plan original que se mantiene: **no se escribe código hasta cerrar Fase 0.**

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
| Auth | Propio (username + password + frase) | NO Supabase Auth (evita el flujo de email) |
| Notificaciones | FCM (data message con ciphertext) | Plan B: notificación local |

Nota: con minSdk 24 se requiere `coreLibraryDesugaring` para `java.time` (estándar, sin
costo). Pendiente decisión P1 del log de decisiones.

### 1.2 Por qué PSK y no libsignal

| Aspecto | libsignal | PSK (elegido) |
|---|---|---|
| Overhead por mensaje | ~100-150 B | ~40 B |
| Complejidad | Alta (JNI, prekeys, ratchet) | Baja (~1-2 días) |
| Recuperación tras perder móvil | Imposible sin backup | Con la frase (Argon2id) |
| Forward secrecy | Sí | No (trade-off aceptado) |
| Licencia | AGPL-3.0 | Tuya |

En Cuba perder o cambiar de móvil es un hecho, no una excepción: la frase ES el backup.
Con libsignal, perder el móvil = perder la conversación para siempre.

### 1.3 Modelo criptográfico (paso a paso, con correcciones)

```
1. El usuario elige una "frase" (independiente del password de login).
2. rootKey = Argon2id(frase, salt, t=3, m=64 MiB, p=1) → 32 bytes.
   [MEJORA] La derivación es cara (1-2 s en gama baja): se ejecuta UNA vez y la rootKey
   se guarda envuelta en Android Keystore (decisión P3 del log de decisiones).
3. [MEJORA] convKey = HKDF-SHA256(rootKey, salt = conversation_id, info = "conv-v1").
   Aísla conversaciones y deja abierta la puerta a un tercer participante futuro sin
   re-ingresar frases.
4. Para cada mensaje:
   - msgKey = HKDF-SHA256(convKey, salt = msg_id, info = "msg-v1")
   - nonce = random(12 bytes)
   - [CORRECCIÓN] AAD = msg_id || sender_id || conversation_id
     (el plan original solo blindaba msg_id; blindar también emisor y conversación evita
     el re-etiquetado de mensajes entre conversaciones, a costo cero)
   - ciphertext = ChaCha20-Poly1305(key = msgKey, nonce, plaintext, aad)
5. El servidor solo ve ciphertext. No puede derivar nada: no tiene la frase.
```

Regla de oro (inalterable): la frase NUNCA es la contraseña de login. Si lo fuera, el
servidor podría atacar el hash de la contraseña y recuperar la clave E2EE. Dos secretos
separados: password → autenticación; passphrase → criptografía.

[CORRECCIÓN] UUIDv7: ni Java ni Kotlin lo traen nativo (Kotlin 2.4 estabilizó UUID v4).
Se implementa en el cliente (~20 líneas, RFC 9562): timestamp + random, ordenable por
tiempo, ideal como PK de idempotencia.

### 1.4 Esquema de la base de datos (Postgres)

```sql
-- Usuarios y sesiones (auth propio)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT UNIQUE NOT NULL,
    pass_hash TEXT NOT NULL,  -- Argon2id, NUNCA la frase
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    device_id UUID NOT NULL,
    token_hash TEXT NOT NULL,  -- SHA-256 del token
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

-- Mensajes (solo ciphertext)
CREATE TABLE messages (
    msg_id UUID PRIMARY KEY,  -- UUID v7 generado en el cliente
    conversation_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    sender_device_id UUID NOT NULL,
    seq BIGSERIAL,  -- orden del servidor
    ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_messages_sync ON messages(conversation_id, seq);
```

Idempotencia por construcción: `msg_id` (UUIDv7) es PK; el servidor hace
`INSERT ... ON CONFLICT (msg_id) DO NOTHING`. Reintentar es siempre seguro.

### 1.5 Transporte: HTTP polling, no WebSocket

Supabase Realtime como transporte principal queda descartado: un mensaje cuesta ~146 B
(a 1 kb/s ≈ 1.17 s) y en el CGNAT de ETECSA las conexiones WebSocket ociosas mueren sin
aviso (bug silencioso). El RTT es el coste real, no los bytes: 5 requests × 500 ms = 2.5 s;
1 request = 500 ms.

```
GET /sync?since=<seq>&limit=100
→ 200, application/octet-stream, Content-Encoding: gzip
   [mensajes desde seq]  +  header "x-next-seq: 4821"
→ 204 si no hay nada (0 bytes de cuerpo)

POST /outbox
Content-Type: application/json
[{msg_id, ciphertext_b64, nonce_b64}, ...]   -- lote
→ 200 {"acked": [msg_id...], "seqs": {...}}
```

[MEJORA] Adaptive polling: 5 s tras actividad visible → backoff geométrico hasta 2-5 min
en reposo → reset inmediato al llegar un push FCM o al enviar un mensaje. Ahorra batería
y datos sin dañar la sensación de inmediatez.

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

### 1.8 Notificaciones: FCM con el ciphertext dentro

FCM admite 4096 bytes por data message; un texto cifrado ocupa ~100 B:

```json
{ "seq": 4821, "t": 1, "n": "<nonce b64>", "c": "<ciphertext b64>" }
```

El teléfono descifra y muestra SIN tocar la red — vale oro con la red caída o a 1 kb/s.

Caveat: FCM requiere Google Play Services. [MEJORA] La app detecta la ausencia de GMS al
arrancar y conmuta sola a modo poll (cada 15-30 min, WorkManager) + notificación local.
Sin configuración manual. (Pendiente ítem 4 de Fase 0: confirmar GMS en ambos móviles.)

---

## Fase 2 — Bot, cupones y un minijuego (1-2 semanas)

### 2.1 Dos canales separados — el bot NO puede leer el chat

Un bot de servidor que conozca rootKey convierte el E2EE en "cifrado contra todos menos
el servidor". [CORRECCIÓN] El plan original contenía una contradicción ("el bot conoce
roomKey" vs "el bot solo tiene una clave derivada"). Lo vinculante es esto:

```
Canal 1 — CONVERSACIÓN (humano-humano)
   E2EE con convKey. El servidor ve solo ciphertext. El bot NUNCA toca este canal.

Canal 2 — COMANDOS (app ↔ bot)
   commandKey = HKDF-SHA256(rootKey, salt = conversation_id, info = "commands")
   El bot conoce SOLO commandKey (provisionada una vez desde la app de un usuario).
   El servidor no puede derivar commandKey: no tiene rootKey.
   HKDF es unidireccional: con commandKey tampoco se recupera rootKey.
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
5. Jugador B recibe el nuevo estado vía FCM o en el siguiente poll.
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
1-2 kbps.

---

## Riesgos y Planes B/C (actualizado 26-oct-2026)

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Supabase suspende la cuenta por OFAC | 🔴 Proyecto muerto | Ítem 1 de Fase 0 validado ✅ + email a soporte (ítem 2, pendiente). Plan C: proveedor NO estadounidense |
| FCM no disponible (sin GMS) | 🟠 Notificaciones tardías | Detección automática + poll local cada 15-30 min |
| ETECSA bloquea el dominio | 🟠 App inutilizable | Evidencia real: India bloqueó `*.supabase.co` completo en feb-2026. Mitigación: dominio propio + export cifrado + cliente tras interfaz `Transport` intercambiable |
| Plan Free se pausa a los 7 días | 🟡 DB congelada | [CORRECCIÓN] Cron EXTERNO gratuito (cron-job.org, Alemania) llamando una Edge Function cada 3 días — más fiable que pg_cron interno como señal de actividad |
| Perder la frase | 🔴 Historial irrecuperable | UI explícita: "si pierdes la frase, pierdes el historial" + exportación cifrada desde el día 1 |
| [NUEVO] Migrar a Cloudflare (Plan B original) como "hedge" | 🟠 Falsa sensación de seguridad | Cloudflare es empresa de EE. UU.: MISMO riesgo OFAC que Supabase. El Plan B es solo hedge técnico. El hedge geopolítico es el Plan C: VPS europeo (Hetzner/OVH, ~4 €/mes) o Supabase self-hosted — la UE prohíbe acatar el embargo extraterritorialmente (Reg. UE 2271/96) |
| [NUEVO] Límites D1 (solo si Plan B) | 🟡 Quotas | Desde sept-2026 D1 aplica enforcement duro: 5M lecturas/día, 100K escrituras/día, 5 GB storage. Para 2 usuarios sobra, pero pasarse de quota ahora SÍ falla |

Diseño anti-secuestro de proveedor (se aplica desde v1):

1. El cliente solo habla 2 endpoints tras una interfaz `Transport` intercambiable.
2. El protocolo (JSON compacto + 2 rutas) es portable a cualquier servidor en ~300 líneas.
3. Exportación cifrada del historial desde el día 1.

Límites Free de Supabase: 500 MB DB, 2 GB bandwidth/mes, 500K invocaciones de Edge
Functions/mes, 150 s máx por invocación. Para 2 usuarios: de sobra.

---

## Cronograma realista

| Semana | Entregable | Criterio de éxito |
|---|---|---|
| 1 | Fase 0 (validación) + proyecto Android + auth propio | Login funciona con username/password/frase |
| 2 | Cifrado PSK + envío/recepción de texto + Room + outbox | Mensaje enviado sin red se entrega al recuperar red, sin duplicados |
| 3 | Sync incremental + FCM + manejo de errores | Mensaje llega con app cerrada; backfill funciona tras 1 h offline |
| 4 | Pruebas de caos: 1 kb/s, cortes de 30 min, cierre forzado | 0 duplicados, 0 pérdidas, orden correcto |
| 5-6 | Fase 2: bot + cupones + 1 minijuego | Canje atómico; el juego recupera estado tras 3 h offline |
| 7-8 | Fase 3: imágenes + watch party | Imagen de 100 KB en < 3 min a 1 kb/s; playhead sync funciona |
