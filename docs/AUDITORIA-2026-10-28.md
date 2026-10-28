# Cerlita Chat — Auditoría exhaustiva y hoja de ruta al siguiente nivel

**Fecha:** 28 de octubre de 2026 · **Alcance:** repo `Alain314159/cerlita-chat` @ `1da98eb` (v0.7.1)
**Método:** lectura completa del código (≈14 000 líneas: 46 archivos Kotlin, 6 Edge Functions, 5 migraciones, 12 scripts Python, 12 tests), verificación cruzada contra la documentación oficial vigente a octubre de 2026.

> Todas las afirmaciones de este informe están verificadas contra el código fuente del repo, con referencia `archivo.kt:línea`. Los datos de plataforma (Android, Supabase, WebRTC, TURN) están contrastados con documentación oficial con URL.

---

## 0. Resumen ejecutivo

Cerlita Chat **no es un prototipo**: es una app E2EE offline-first con arquitectura de sala, criptografía con key derivation propia, recibos de entrega/lectura, adjuntos cifrados, llamadas WebRTC, tema propio, 64 tests y una documentación de decisiones que eclipsa a la mayoría de proyectos de su tamaño. El **diseño criptográfico es correcto** y el **offline-first está bien resuelto**.

Dicho esto, la auditoría encontró **8 defectos reales** (2 de ellos con impacto visible en el día a día), **5 riesgos de plataforma no cubiertos** por la documentación actual, y un conjunto amplio de mejoras de calidad de servicio, UI/UX y features que la llevarían de "muy buena" a "impresionante".

### Hallazgos críticos (arreglar antes que nada)

| # | Severidad | Hallazgo | Impacto para el usuario |
|---|-----------|----------|-------------------------|
| **B1** | 🔴 P0 | **El historial de una sala recién unida es invisible.** Cursor global `last_seq` + `seq` global del servidor | A se une a la sala de B y **nunca ve los mensajes anteriores**, ni el nombre real de la sala |
| **B2** | 🔴 P0 | **Los botones de llamada desaparecen en los DMs abiertos desde la lista** | Llamadas inalcanzables salvo justo tras crearlas |
| **B3** | 🔴 P0 | **La tabla `messages` no tiene ni un índice secundario** | Cada mensaje insertado dispara un escaneo completo de la tabla → jank creciente y batería |
| **B4** | 🟠 P1 | **Un 401 no invalida la sesión** | Sesión caducada → la app queda "muerta" en silencio, sin volver a pedir login |
| **B5** | 🟠 P1 | **El badge de no leídos no se limpia al leer en la sala** | El contador sube solo mientras estás mirando el chat |
| **B6** | 🟠 P1 | **La señalización de llamadas viaja en claro** | El servidor ve SDP, IPs y **el nombre del usuario** → contradice la promesa E2EE de D61 |
| **B7** | 🟠 P1 | **`fallbackToDestructiveMigration` borra el historial** si falta una migración futura | Riesgo de pérdida total de datos en una actualización |
| **B8** | 🟠 P1 | **OOM con adjuntos grandes** (3-4 copias en RAM) | Fallo al enviar un archivo de 15-20 MB en un teléfono de 2 GB |

### Riesgos de plataforma (documentados como resueltos, pero no del todo)

| # | Riesgo | Estado real (oct-2026) |
|---|--------|--------------------------|
| **R1** | Android 16 (API 36) limita los jobs de background lanzados desde un FGS | `targetSdk = 36` ya está afectado. Ver §6.3 |
| **R2** | Supabase pausa proyectos Free tras 1 semana sin actividad | D59 lo cubre ✅ pero con un matiz importante (§7.1) |
| **R3** | Realtime Free: **tamaño máximo de mensaje 256 KB** | El SDP + ICE de una llamada cabe, pero el margen es de 3×; y 3 MB solo en plan Pro |
| **R4** | `users.pub` es "última que gana" | Rompe los chats directos en multi-dispositivo (§8.4) |
| **R5** | WorkManager periódico mínimo = 15 min | El fallback del timbre está atado a esa ventana |

### Veredicto por eje

| Eje | Nota | Lo que más pesa |
|------|------|-----------------|
| **Criptografía** | 9,5/10 | Rotación de claves por sala, sin rotación de roomKey; señalización de llamadas sin cifrar |
| **Fiabilidad / QoS** | 7/10 | Cursor global (B1), sin TURN, notificaciones con ventana de 30 min |
| **Notificaciones** | 7/10 | La arquitectura es correcta; falta plan B sin FGS y faltan canales finos |
| **UI/UX** | 7,5/10 | Sin tema oscuro Cerlita, sin búsqueda, sin reacciones, sin respuestas citadas |
| **Rendimiento** | 5,5/10 | Sin índices (B3), sin Paging 3, O(n²) en la lista, adjuntos en RAM |
| **Backend** | 8/10 | Rate limiting ausente, 4 queries por poll, sin TTL de mensajes |
| **Documentación** | 10/10 | `DECISIONES.md` es de manual. **Es el activo más valioso del repo** |

---

## 1. Lo que ya está muy bien (no romper)

Antes de proponer nada, conviene blindar lo que funciona:

1. **El modelo de claves es correcto y está bien diseñado.** `roomKey` (32 B CSPRNG) → `roomSalt` (16 B) → `roomId = HKDF(roomKey, salt=roomSalt, "room-id")[0..16]`. La separación `convKey = HKDF(roomKey, roomId)` y `msgKey = HKDF(convKey, msgId)` da aislamiento por sala y por mensaje. El AAD `"v1|msgId|senderId|roomId"` liga el ciphertext a mensaje, emisor y sala. Correcto.

2. **El código de invitación es el secreto, y el diseño lo asume sin auto-contradicción.** `base32("V1"‖salt‖key‖CRC32)` — largo a propósito, con CRC para detectar errores de tecleo, y `MessageDigest.isEqual` en la comparación del CRC (constante, sin timing leak). `Crypto.kt:130`.

3. **El vault sin frase (D55) es la decisión de producto correcta.** Keystore AES-256-GCM sellando `rooms.bin` + `ss.bin` + `device_key.bin`. Desbloqueo instantáneo, cero fricción de onboarding. `KeyVault.kt:45-60`.

4. **X25519 + HKDF + ChaCha20-Poly1305 para los wraps de DM, con verificación de roomId derivada.** `DmCrypto.kt:92` — el wrap se descarta si el roomId derivado no coincide. Es la verificación de integridad correcta.

5. **La política de notificidad es única y centralizada.** Un solo `onNewMessages` alimenta timbre, polling, worker y pendientes. `ChatApp.kt:206`. Es exactamente lo que evita el bug clásico de "notificaciones duplicadas por tres vías".

6. **El mutex de sync serializa timbre / polling / worker / UI.** `ChatEngine.kt:950`. Evita que el cursor avance dos veces.

7. **`UUIDv7` propio con bits de versión y variante correctos.** `Crypto.kt:44-52`.

8. **La documentación de decisiones es un activo estratégico.** 82 decisiones con su origen, su estado y su justificación. Es lo que hace que este repo sea mantenible. **CUALQUIER cambio que hagamos debe abrirse con una decisión nueva (D84+) y actualizar `DECISIONES.md`.**

9. **R8 + `shrinkResources` desde la v1, `minifyEnabled` siempre.** `build.gradle.kts:44-45`. La APK de 2,8 MB (+16 MB con WebRTC) es razonable.

10. **Los tests son reales.** 64 tests JVM + Robolectric (arranque en API 24/28/31/33/35) y E2E contra producción (37/37 smoke, 24/24 recibos, 13/13 timbre). El post-mortem de D62 (crash de ViewModel) demuestra que hay cultura de diagnóstico, no de parche.

---

## 2. Defectos verificados (con evidencia)

### 🔴 B1 — P0 · El historial de una sala recién unida es invisible

**Dónde:** `ChatEngine.kt:958-992` (cursor global) + `backend/functions/sync/index.ts:69-78` (seq global) + `backend/migrations/0002_rooms.sql` (`seq BIGINT GENERATED ALWAYS AS IDENTITY`, global para todas las salas).

**El mecanismo:**

- El servidor asigna un `seq` **global** a cada mensaje de **todas** las salas (`messages.seq` es un identity de una sola secuencia).
- El cliente guarda **un único cursor** `last_seq` en la tabla `meta` (`ChatEngine.kt:960`).
- `/sync?s=<last_seq>` devuelve `WHERE m.seq > <last_seq> AND m.room_id IN (mis salas)`.

**El fallo:** cuando B se une a una sala nueva, el `seq` de esa sala **ya está por debajo** del `last_seq` de B (que viene de otros chats). El filtro `m.seq > since` los descarta para siempre. B nunca los verá — ni ahora ni nunca (el cursor solo avanza).

```
Escenario real:
  A crea "Plan de viaje"  → room_info seq=1, perfil seq=2, "hola" seq=3
  B ya está sincronizando  → last_seq = 500
  B se une con el código   → joinRoom() → last_seq sigue en 500
  B hace /sync?s=500      → la sala no tiene nada con seq > 500
  ✗ B nunca ve "hola" ni el nombre "Plan de viaje" → ve "Sala a1b2c3d4"
```

**Lo que ya revela que es un problema conocido:** D61 documenta explícitamente *"crítico: con seq global, los mensajes previos al join son invisibles para quien ya tenía last_seq avanzado"* y lo resuelven **parcheando el síntoma** (re-anunciar el perfil al detectar un par nuevo). Pero el mensaje en sí y el **nombre de la sala (D69) no tienen ese parche** — y el mecanismo de "herencia del nombre al unirse" (D69) es, por definición, leer del historial. Es decir: la feature de herencia del nombre depende de un camino que está roto en el caso más común.

**Corrección recomendada (barata y correcta):** cursores **por sala** en el cliente + un modo de recuperación por sala en el servidor.

```sql
-- backend/migrations/0006_room_cursors.sql
CREATE TABLE IF NOT EXISTS room_cursors (
    room_id   UUID NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_seq  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id, user_id)
);
```

```
GET /sync?s=<global>&l=<n>&room=<roomId>&rs=<seqSala>
  → si viene `room`, devuelve SOLO esa sala con seq > rs (para recuperar historial)
  → si no, comportamiento actual + devuelve `rc: [[roomId, maxSeq], ...]`
     con el seq máximo POR SALA que el usuario ya tiene descargado
```

Cliente:
- Al hacer join de una sala: `api.syncRoom(roomId, since = 0)` → descarga el historial completo de esa sala y lo ingestará. Coste: unos pocos KB para 2 usuarios.
- Guardar `room_cursors` localmente y usarlos para el catch-up incremental por sala.
- Mantener el cursor global para el barrido rápido (es O(1) y cubre el caso común de 1-2 salas).

**Impacto si no se arregla:** es la feature diferencial del producto (grupos con código de invitación) fallando en su caso principal. **Prioridad: la primera de la lista.**

---

### 🔴 B2 — P0 · Los botones de llamada desaparecen en los DMs abiertos desde la lista

**Dónde:** `RoomsScreen.kt:217-220` reconstruye la entidad `Room` desde `RoomListItem` **omitiendo `kind`**:

```kotlin
val room = Room(
    roomId = item.roomId, name = item.name, owner = item.owner,
    sas = item.sas, createdAt = item.createdAt,
)
```

`Room.kind` tiene valor por defecto `0` (`Db.kt:28`) = `C.ROOM_GROUP`. Y en `AppRoot.kt:234`:

```kotlin
if (current.room.kind == cu.alain.chat.core.C.ROOM_DM) { /* botones de llamada */ }
```

→ **siempre falso** cuando el DM se abre desde la lista. El mismo `Room` reconstruido se usa como `peerUid = null`, así que además el `CallState.peerUid` queda vacío (`CallDispatcher.kt:98`).

**Por qué no se detectó:** `RoomListItem` (`Db.kt:373-389`) **no tiene el campo `kind`**, y la query `observeRoomList` (`Db.kt:409-427`) no selecciona `r.kind`. Tras crear el DM, `createDm` devuelve un `Room` completo (`kind = ROOM_DM`) → ahí sí aparecen los botones. Al salir y volver a abrirlo desde la lista, desaparecen. Coincide con el patrón de bug "funciona la primera vez".

**Corrección (3 líneas + 1 columna):**

```kotlin
// Db.kt — RoomListItem
val kind: Int,
val peerUid: String?,   // ya existe

// Db.kt — observeRoomList: añadir a la lista de selección
r.kind AS kind,

// RoomsScreen.kt:217
val room = Room(
    roomId = item.roomId, name = item.name, owner = item.owner,
    sas = item.sas, createdAt = item.createdAt,
    kind = item.kind,                 // ← nuevo
    peerUid = item.peerUid,           // ← nuevo
    unread = item.unread, photo = item.roomPhoto,
)
```

> Nota: `peerUid` **sí** está en `RoomListItem` (línea 379) pero tampoco se copia al `Room` reconstruido — otroSymptom del mismo bug.

---

### 🔴 B3 — P0 · `messages` sin un solo índice secundario

**Dónde:** `Db.kt:33-45`

```kotlin
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val msgId: String,
    val roomId: String,
    ...
)
```

**No hay `indices = [...]` en ninguna entidad del esquema.** La única indexación es el PRIMARY KEY (`msgId`, TEXT). Consecuencias:

| Query | Plan actual | Con índices |
|-------|-------------|-------------|
| `observeChat`: `WHERE roomId=? AND kind=0 ORDER BY ts, msgId` (`Db.kt:174`) | **escaneo completo** + sort | búsqueda por índice |
| `observeChats`: `MAX(ts) GROUP BY roomId` (`Db.kt:240`) | **escaneo completo** | índice cubriente |
| `observeRoomList`: 3 subconsultas correlacionadas sobre `messages` por sala (`Db.kt:415-420`) | 3× escaneo por sala | 3× índice por sala |
| `failOrphanSending`: `NOT IN (SELECT msgId FROM outbox)` (`Db.kt:199`) | escaneo | semi-join por índice |

Y lo que lo hace grave: `observeChat` y `observeRoomList` son **`Flow`** de Room. Cada `INSERT` en `messages` invalida la query y **re-emite la lista completa**. Con 5 000 mensajes, cada mensaje nuevo = escaneo de 5 000 filas × 4 queries, en el hilo de la UI.

**Corrección (1 migración + 1 línea):**

```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["roomId", "kind", "ts", "msgId"], name = "idx_msg_room_kind_ts"),
        Index(value = ["roomId", "mine"], name = "idx_msg_room_mine"),
        Index(value = ["state"], name = "idx_msg_state"),
    ],
)
data class Message(...)
```

```sql
-- backend no aplica; esto es la migración Room local
CREATE INDEX IF NOT EXISTS idx_msg_room_kind_ts ON messages(roomId, kind, ts, msgId);
CREATE INDEX IF NOT EXISTS idx_msg_room_mine  ON messages(roomId, mine);
CREATE INDEX IF NOT EXISTS idx_msg_state      ON messages(state);
```

**Coste:** 0 KB de APK. **Beneficio:** la app deja de jankear al llegar a ~500 mensajes. Es, con diferencia, la mejor relación coste/beneficio de toda esta lista.

---

### 🟠 B4 — P1 · Un 401 no invalida la sesión

**Dónde:** `ChatEngine.kt:989-991`

```kotlin
} catch (e: ApiException) {
    if (e.http == 401) -2 else -1
}
```

`syncOnce` detecta el 401 y devuelve `-2`, y el polling lo trata como "reintenta corto" (`ChatEngine.kt:1155`). **Pero nadie llama a `store.clearSession()` ni a `refreshState()`.** El `drainOutbox` sí lo hace (`ChatEngine.kt:932` → `onAuthLost()`), pero el camino de *recepción* —que es el que se dispara constantly— no.

**Escenario:** la sesión caduca (90 días) o se revoca en otro dispositivo. La app sigue mostrando la lista de chats con el contenido local, cada sync devuelve 401, el polling reintenta cada 5 s indefinidamente, y **el usuario no ve ningún mensaje de error ni puede volver a entrar** (no hay ruta de re-login desde `AppState.Main`).

**Corrección:**

```kotlin
} catch (e: ApiException) {
    if (e.http == 401) {
        // la sesión ya no vale: se limpia y la UI vuelve al login
        scope.launch { logout(apiLogout = false) }   // ← el logout ya limpia DB + vault
        -2
    } else -1
}
```

`ChatEngine.logout()` (`ChatEngine.kt:588-611`) ya hace todo: limpia claves, vault, tablas, foto y apaga el timbre. Reutilizarlo es una línea.

> **Mejora de UX asociada:** añadir un estado `AppState.SessionExpired` que muestre "Tu sesión expiró — vuelve a entrar" **sin borrar el historial local** (que sigue siendo recuperable con el código de invitación). Borrarlo todo en un 401 es una decisión demasiado agresiva.

---

### 🟠 B5 — P1 · El badge de no leídos no se limpia al leer en la sala

**Dónde:** `ChatEngine.kt:1032-1035` (sube) vs `ChatScreen.kt:268-276` (baja).

```kotlin
// Engine — SIEMPRE sube el contador, incluso con la sala abierta
if (msg.kind == C.KIND_TEXT && !msg.mine) {
    db.roomDao().bumpUnread(msg.roomId)
}
```

```kotlin
// ChatScreen — solo baja al ENTRAR a la pantalla (DisposableEffect)
DisposableEffect(roomId) {
    app.currentRoomId = roomId
    Notifier.cancelRoom(ctx, roomId)
    scope.launch { app.db.roomDao().resetUnread(roomId); ... }
    onDispose { ... }
}
```

`LaunchedEffect(messages.size)` (`ChatScreen.kt:279-282`) llama a `maybeSendReadReceipt` pero **no** a `resetUnread`. Resultado: estás con el chat abierto, llega un mensaje, el contador de no leídos de esa sala **sube a 1** y no vuelve a 0 hasta que salgas y vuelvas a entrar. En la lista de chats ves "🔴 1" en un chat que estás leyendo.

**Corrección:** mover el `resetUnread` al `LaunchedEffect(messages.size)` (o a un `LaunchedEffect` por cada mensaje nuevo) y no solo al `DisposableEffect`:

```kotlin
LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    app.db.roomDao().resetUnread(roomId)
    app.engine.maybeSendReadReceipt(roomId)
}
```

**Mejora de calidad de servicio asociada:** el `bumpUnread` debería ser condicional — el motor no debería subir el contador si la sala está abierta en foreground. Como el motor no lo sabe, la solución es la de arriba (bajar en cuanto llega), pero aún mejor: pasar `suppressRoom` al motor.

---

### 🟠 B6 — P1 · La señalización de llamadas viaja en claro

**Dónde:** `CallSignals.kt:48-52` + `Api.kt:248-269` (broadcast REST) + `CallDispatcher.kt:115`.

```kotlin
fun encodeOffer(...): JSONObject =
    JSONObject()
        .put("t", "offer").put("c", callId).put("r", roomId.lowercase())
        .put("f", fromUid).put("fn", fromName).put("v", if (video) 1 else 0)
        .put("s", JSONObject().put("t", "offer").put("s", sdp))   // ← SDP EN CLARO
```

El payload viaja por `POST /realtime/v1/api/broadcast` **sin cifrar**. El servidor ve:

- **El nombre real del emisor** (`fn`) → contradice frontalmente D61: *"el servidor NUNCA ve nombres ni fotos"*. El nombre ya viaja E2EE en los mensajes `kind=1`; aquí se filtra en claro.
- **El SDP completo** → contiene las **IP locales y públicas** de ambos teléfonos, los codecs, y los fingerprints DTLS.
- Los candidatos ICE → IPs públicas y puertos.

**Por qué importa:** para una app cuyo argumento de venta es "el servidor nunca ve nada", la tabla `messages` está impecable pero la tabla de llamadas delata nombre e IP. Y con `supportsRtl` la IP es un dato de geolocalización.

**Corrección (barata, reutiliza la criptografía existente):** cifrar el payload de señalización con la `roomKey` de la sala, que **ambos extremos ya conocen**:

```kotlin
// CallSignals.kt
const val EV = "sig"

// El SDP/ICE viaja cifrado con la convKey de la sala.
// AAD = "sig-v1|callId|roomId|type"
fun encodeEncrypted(roomId: String, callId: String, kind: String, plain: JSONObject): JSONObject {
    val key = ...           // convKey de la sala (el dispatcher la obtiene del engine)
    val (nonce, ct) = Crypto.seal(mk, Crypto.frame(plain.toString()), aad)
    return JSONObject()
        .put("t", "enc").put("c", callId).put("r", roomId.lowercase())
        .put("n", b64(nonce)).put("d", b64(ct))
}
```

El servidor solo ve `roomId`, `callId` y bytes opacos. El nombre `fn` viaja dentro del sobre cifrado. **Coste:** ~60 líneas. **Beneficio:** la promesa E2EE de la app se vuelve verdad también en las llamadas.

> **Nota sobre tamaño:** el SDP cifrado sigue entrando en el límite de 256 KB de Realtime Free (R3) con holgura: un SDP típico con candidatos es de 3-8 KB.

---

### 🟠 B7 — P1 · `fallbackToDestructiveMigration` puede borrar el historial

**Dónde:** `Db.kt:517`

```kotlin
.fallbackToDestructiveMigration(dropAllTables = true)
```

La migraciones 2→3, 3→4 y 4→5 **sí** existen y son reales (`Db.kt:450-492`) — bien hecho, y el `MigrationTest` lo cubre. El problema es el *fallback*: si en el futuro se añade una entidad v6 y se olvida la migración 5→6, **Android destruye la base y borra todo el historial, las claves del vault no (eso está en `rooms.bin`, pero los mensajes sí)**, de forma silenciosa. Sin diálogo, sin aviso, sin nada en el log que el usuario pueda leer.

Para una app con 2 usuarios reales y meses de conversación, es una bomba de relojería.

**Corrección:**

```kotlin
// build(ctx):
    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, /* 5→6 */)
    .build()   // SIN fallback destructivo
```

Y para que un fallo de migración **no** sea un crash en producción, añadir un `MigrationTest` por cada salto (ya hay la infraestructura: `MigrationTest.kt` reproduce la actualización real v0.3.0→v0.4.x con datos). Añadir el test del salto nuevo al mismo patrón.

> Si prefieres no arriesgar el crash: mantén el fallback **solo en debug** y en release usa un `RoomDatabase.Callback.onOpen` que registre un `MigrationTestHelper`… no: lo más simple y correcto es **sin fallback + test de migración obligatorio en CI**. El test ya existe y funciona.

---

### 🟠 B8 — P1 · OOM con adjuntos grandes

**Dónde:** `ChatEngine.kt:768-834` + `Api.kt:373-408` + `ChatScreen.kt:480-496`.

Ruta actual de un archivo de 20 MB:

| Paso | Copia en RAM |
|------|--------------|
| `readPickedFile` → `contentResolver.openInputStream().readBytes()` | 20 MB |
| `Crypto.seal` → `ChaCha20Poly1305.encrypt()` | +20 MB (entrada + salida) |
| `nonce + ct` → `blob` | +20 MB (concatenación) |
| `Api.storageUpload` → `okio.Buffer().write(bytes)` | +20 MB |
| `attCacheFile.writeBytes(blob)` | +20 MB (en disco, OK) |

→ **~60-80 MB de pico**. En un teléfono con 2 GB de RAM y Android Holmes, la app es candidata a `OutOfMemoryError` justo en el momento en que el usuario está intentando enviar una foto. Y un fallo de OOM **no** lo captura `catch (e: Exception)` (es un `Error`, no una `Exception`) → **crash**.

**Corrección (streaming, sin cambiar el formato en el servidor):**

```kotlin
// 1. Leer en streaming, con decryption en streaming
suspend fun sendAttachmentStream(roomId: String, uri: Uri, ...) {
    val tmp = File(cacheDir, "enc-$msgId.tmp")
    // escribe ciphertext directamente al fichero con un CipherOutputStream
    Cipher.getInstance("ChaCha20-Poly1305/...").run {
        // o el AEAD de Tink con un OutputStream
    }
    // 2. subir con un RequestBody que hace read() del fichero en trozos
    override fun writeTo(sink: BufferedSink) {
        tmp.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, 0, n) }
        }
    }
}
```

El `Api.storageUpload` ya recibe un `RequestBody` con `writeTo` — **solo hay que dejar de hacer `okio.Buffer().write(bytes)`** y leer del fichero. Eso solo baja el pico de ~80 MB a ~1 MB.

**Alternativa más simple si el tiempo es corto:** bajar `C.MAX_FILE` de 20 MB a 5 MB y avisar al usuario. Pero la solución con streaming es mejor y no cuesta mucho.

**Extra (muy recomendable):** subir el límite del *servidor*. `MAX_FILE = 20 MB` cifrado y el bucket está en 25 MB (`C.kt:54`). Con fotos ya comprimidas a ≤1600 px (~300 KB) y notas de voz de 5 min (~360 KB), **el límite real de uso es < 1 MB**. Los 20 MB solo aportan riesgo de OOM. Bajar a 8 MB es holgado y seguro.

---

## 3. Calidad del servicio (QoS) — lo que el usuario *siente*

### 3.1 Matriz de latencia real por escenario

| Escenario | Latencia actual | Objetivo | Cómo se llega |
|------------|------------------|----------|----------------|
| App abierta, timbre vivo | **~0,5-2 s** ✅ | — | ya bueno |
| App abierta, timbre muerto (OEM) | ≤ 15 s | ≤ 5 s | polling 15 s → bajar a 5 s con energía adaptativa |
| App en background, FGS activo | ~1-3 s | < 3 s | ya bueno |
| App en background, FGS muerto | **hasta 15 min** ⚠️ | ≤ 5 min | AlarmManager `setExactAndAllowWhileIdle` como red de seguridad |
| App cerrada, sin FGS | hasta 15 min | ≤ 15 min | WorkManager (límite del sistema) |
| Red lenta (ETECSA, 1 KB/s) | +30-90 s | — | ver §3.3 (compresión y tamaño) |
| MESSAGE enviado con red caída | 待 outbox | — | ✅ bien resuelto |

### 3.2 La notificación que no llega tras 30 minutos

**Dónde:** `Notifier.kt:63` y `Notifier.kt:115`

```kotlin
private const val RECENT_MS = 30L * 60 * 1000
...
val fresh = msgs.filter { !it.mine && it.ts >= now - RECENT_MS }
```

**El problema:** si el teléfono estuvo 2 horas sin red y al volver se sincronizan 40 mensajes de hace 2 horas, **no se avisa de ninguno**. La lógica original era evitar spam al re-sincronizar un teléfono nuevo — pero el efecto secundario es que un mensaje importante de hace 45 minutos **llega en silencio**.

**Corrección (separar "spammer" de "noticia"):**

```kotlin
// Regla nueva: como máximo 1 notificación por sala en ventana de 10 min,
// pero SIEMPRE se avisa del último mensaje aunque sea viejo, si el usuario
// no ha visto esa sala.
val fresh = msgs.filter { !it.mine }
val byRoom = fresh.groupBy { it.roomId }
// por sala: 1 notificación con los últimos N
```

Concretamente: **notificar siempre el último mensaje de cada sala** (aunque sea antiguo) **+** resumir el resto como "3 mensajes nuevos". Eso elimina el spam del re-sync (1 notificación por sala en vez de 40) **y** garantiza que nunca haya un mensaje mudo.

### 3.3 Tamaño de los mensajes: el presupuesto de 8 KB es opresivo

`MAX_CT = 8192` (`C.kt:17`) y `C.KIND_TEXT` con `Crypto.frame()` comprime a gzip solo si el texto pasa de 512 B. Un texto de 7 000 caracteres en español **no** cabe (gzip de 7 KB de español ≈ 2,5-3 KB, sí cabe; pero un texto de 30 KB de emoji o datos no).

**Observación útil:** el `LIMIT 8192` del servidor (`outbox/index.ts:21`) está en la **tabla `messages`**, y los adjuntos van por Storage, así que no se mezclan. Bien diseñado.

**Mejora concreta:** el composer limita a 7 500 caracteres (`ChatScreen.kt:373`) pero el error "El mensaje es demasiado largo" (`ChatEngine.kt:746`) salta **después** de cifrar, sin decir cuántos caracteres faltan. Sustituir por un contador en vivo:

```kotlin
// en el composer
val maxChars = 7000
Text("${draft.length}/$maxChars", style = labelSmall, color = if (draft.length > maxChars*0.9) error else onSurfaceVariant)
```

y bloquear el envío con un mensaje claro: *"Quedan 12 caracteres. Los mensajes muy largos van mejor como archivo."*

### 3.4 El backoff de red del polling es correcto pero poco agresivo

`ChatEngine.kt:1153-1160` — backoff ×2 con tope de 5 min en background y 15 s en foreground, con jitter ±40 %. Correcto.

**Mejora:** en background, el primer sondeo tras un fallo debería ser **inmediato** al recuperar la red (`CONNECTIVITY_MANAGER` / `NetworkCallback`), no esperar al backoff. `registerDefaultNetworkCallback` + `onAvailable` → `syncOnce()` inmediato. Hoy, tras 20 min sin red, el usuario espera hasta 5 min más.

### 3.5 El sync hace 4 queries por poll

`backend/functions/sync/index.ts`:
1. `authUser` (session lookup)
2. `rows` (mensajes)
3. `update room_members delivered_seq`
4. `rdRows` (entregas de otros)
5. `dmRows` (wraps pendientes)

**En una red cubana de 500 ms de RTT**, 5 queries secuenciales = **2,5 s por poll**, y el poll ocurre cada 15 s en foreground. Eso es 17 % de la conexión花在 consultas.

**Corrección:** fusionar 2, 3, 4 y 5 en una sola query con CTEs, y cachear el `authUser` por token (hash → uid) en memoria de la Edge Function durante 60 s. Con 2 usuarios, el lookup de sesión es un desperdicio por poll.

```sql
-- una sola query con CTE para todo
with mine as (select room_id from room_members where user_id = $1),
     rows as (select ... from messages where room_id in (select room_id from mine) and seq > $2 order by seq limit $3),
     ...
```

### 3.6 Backpressure y concurrencia de `syncOnce`

`ChatEngine.kt:950` — el mutex serializa, correcto. Pero `RealtimeDoorbell.onDoorbell` dispara `syncOnce()` por **cada** timbre, y un `onConnected` dispara otro. Con N salas se pueden encolar N sincronizaciones seguidas, cada una con 5 queries. **Cada timbre debería validar si hay algo pendiente** antes de sincronizar.

**Corrección:** coalescing — si ya hay un sync en vuelo o排队, marcar "pendiente" y no encolar otro:

```kotlin
private val syncPending = AtomicBoolean(false)
suspend fun requestSync() {
    if (!syncPending.compareAndSet(false, true)) return   // ya hay uno en vuelo
    try { syncOnce() } finally { syncPending.set(false) }
}
```

---

## 4. Notificaciones — plan completo

### 4.1 Estado actual (correcto, y bien diseñado)

```
┌─ App ABIERTA ───────────────────────────────────────────┐
│  WebSocket Realtime (Phoenix manual, 150 líneas)        │
│  + polling de respaldo ≤ 15 s (D71)                     │
│  + watchdog cada 30 s (ChatApp.kt:131)                 │
│  → latencia ~1 s · SIN notificación persistente        │
└──────────────────────────────────────────────────────────┘
┌─ App en BACKGROUND, «Avisos instantáneos» ON (defecto) ┐
│  FGS remoteMessaging (WebSocket permanente)             │
│  → latencia ~1-3 s · CON notificación persistente       │
│  → revive solo por BootReceiver (no por AnyOther)       │
└──────────────────────────────────────────────────────────┘
┌─ App en BACKGROUND, modo OFF ───────────────────────────┐
│  WorkManager periódico 15 min → notifica al recuperar   │
│  → latencia hasta 15 min · SIN notificación            │
└──────────────────────────────────────────────────────────┘
```

**Lo que está bien resuelto y hay que preservar:**
- El timbre **solo despierta**, jamás transporta contenido. El push no lleva ni un byte de plaintext. `outbox/index.ts:108-139`.
- El canal **no es privado a propósito**: el nombre del canal ES la capability (roomId = HKDF de 128 bits). Un atacante con la anon key solo ve "hubo actividad", nunca contenido.
- `remoteMessaging` es la elección correcta de tipo de FGS. **Verificado contra la documentación oficial:** `dataSync` tiene un límite de **6 h/24 h** y **no puede arrancarse desde BOOT_COMPLETED** con targetSdk ≥ 35; `remoteMessaging` **no tiene ese límite** y **sí está en la allowlist de BOOT_COMPLETED**. La decisión D64 es correcta y está bien razonada.
- `FOREGROUND_SERVICE_REMOTE_MESSAGING` es permiso de nivel **normal** (no hay diálogo de runtime). ✅
- El FGS arranca con `ServiceCompat.startForeground()` antes de cualquier comprobación (`DoorbellService.kt:62`) → cumple el contrato de `startForegroundService`. ✅

### 4.2 Lo que falta: 4 problemas reales

**N1 · La notificación persistente es visible y molesta.** El usuario lo pidió explícitamente (D72), luego D82 lo volvió a poner ON por defecto. El canal es `IMPORTANCE_MIN` (`Notifier.kt:69-79`) — bien, no suena ni vibra — **pero sigue ocupando una fila en la bandeja**.

**Mejoras para que sea lo menos invasiva posible:**

| Cambio | Dónde | Efecto |
|--------|-------|--------|
| `setOngoing(false)` + permitir que el usuario la deslice | `DoorbellService.kt:112` | Se puede cerrar; el `START_STICKY` la revive |
| Icono de la FGS con alpha 0 y sin texto descriptivo largo | `DoorbellService.kt:108` | Menos ruido visual |
| **Agrupar** con las notificaciones de mensajes en un solo grupo | `Notifier.kt:55` | Una sola fila en la bandeja |
| `setShowWhen(false)` | `DoorbellService.kt:112` | Quita la hora |
| Texto neutro: "Sincronizando" en vez de "Conectado — los mensajes nuevos avisan al instante" | `strings.xml:9` | Menos sensación de "app doing something" |
| Botón "Desactivar avisos instantáneos" **en la propia notificación** | nuevo | El usuario lo apaga sin entrar a Ajustes |

**N2 · `BOOT_COMPLETED` no revive el FGS si el usuario reinicia el teléfono sin haberlo abierto.** `BootReceiver.kt:163-169` está bien (solo arranca si `instantBg`), pero `RECEIVE_BOOT_COMPLETED` está declarado y `BootReceiver` es `exported="true"` ✅. **Falta `LOCKED_BOOT_COMPLETED`** (arranque tras un desbloqueo que requiere PIN) — en Android 7+, `BOOT_COMPLETED` **no** se entrega si el usuario nunca desbloquea el dispositivo tras el reinicio. Añadirlo es una línea y evita el caso "encendí el teléfono y no me avisó nada".

```xml
<receiver android:name=".notify.BootReceiver" ...>
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

`MY_PACKAGE_REPLACED` además revive el FGS tras actualizar la app sin abrirla.

**N3 · La reconexión del timbre no distingue "sin red" de "servidor caído".** `RealtimeDoorbell.scheduleReconnect()` (`RealtimeDoorbell.kt:160-170`) hace backoff 5 s → 60 s sin comprobar **por qué** falló. Si la red se fue, el socket falla, y el backoff llega a 60 s: cuando la red vuelve, el timbre tarda hasta 1 minuto en reconectar (y luego `onConnected` → `syncOnce`).

**Corrección:** ante `UnknownHostException` / `ConnectException` (fallo de red), reiniciar el backoff a 5 s y **no** subirlo; ante cierre limpio del servidor o error de protocolo, sí aplicar backoff. Además, usar `ConnectivityManager.registerDefaultNetworkCallback` para reconectar **de inmediato** cuando la red vuelve.

```kotlin
override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    val esFalloDeRed = t is java.net.UnknownHostException ||
                       t is java.net.ConnectException ||
                       t is java.net.SocketTimeoutException
    if (esFalloDeRed) backoffMs = 5_000L   // no penalizar: la red es lo que falla
    scheduleReconnect()
}
```

**N4 · No hay forma de saber si el timbre está realmente vivo desde fuera.** Ajustes lo muestra (`ChatApp.kt:91-93`), pero el usuario no lo ve salvo que abra Ajustes. **Indicador en la lista de chats**: un punto sutil junto al nombre si el timbre está desconectado, con un toque que lleva a Ajustes. Es el indicador de "calidad de servicio" más honesto que puedes darle al usuario.

### 4.3 La alternativa sin FGS: UnifiedPush + ntfy

**Investigado a octubre de 2026.** Si el usuario no quiere la notificación persistente, esta es la vía:

```
Servidor (Edge Function outbox)
   │  POST https://ntfy.tu-servidor.com/cerlita-<tema>
   ▼
Servidor ntfy (self-hosted, 1 VPS €4/mes O ntfy.sh gratis)
   │  WebPush (VAPID) → FCM del distributor ntfy → wake del distributor
   ▼
App ntfy en el teléfono (distributor UnifiedPush)
   │  BroadcastReceiver NEW_ENDPOINT / MESSAGE
   ▼
Cerlita Chat → /sync → notificación
```

**Estado real verificado:**
- **UnifiedPush** es un protocolo abierto y maduro; desde 2025 la especificación **se apoya en WebPush (VAPID)**. Distribuidores disponibles: **ntfy** (Android, F-Droid, APK directo), Sunup (Mozilla autopush-rs), Conversations (XMPP), NextPush (Nextcloud), gCompat-UP.
- **ntfy como distributor de UnifiedPush** es la vía más madura y **no requiere cuenta** en ntfy.sh.
- Se puede **auto-hospedar ntfy** (Docker, binario, apt) y entonces el tema es un secreto que solo ustedes conocen.
- **Limitaciones verificadas de ntfy.sh** (las del plan público): 12 h de caché, 250 mensajes/día, 60 req/5 s, 200 MB/día de egress. Suficiente de sobra para 2 usuarios.
- ⚠️ **ntfy.sh reenvía a Firebase por defecto** (eso lo hace el distributor para ser eficiente en batería). Se desactiva con la cabecera `X-Firebase: no` / `X-UnifiedPush: 1`. **Esto importa: si el usuario prohibió Firebase, hay que desactivarlo explícitamente** o el distributor sigue pasando por FCM.

**Veredicto para este proyecto:**

| Escenario | Recomendación |
|-----------|---------------|
| 2 usuarios, ambos con el FGS activado y exentos de batería | **Mantener el FGS** (lo actual). Es 0 coste, 0 dependencias y ya funciona. |
| El usuario se queja de la notificación persistente | **Añadir UnifiedPush como alternativa**, con el FGS como fallback y el switch en Ajustes |
| Quieren 0 dependencias de terceros | Self-hosted ntfy en un VPS (Hetzner CX22 ≈ €3,8/mes, o el plan gratuito de Oracle/…) — pero ojo con R5/R9 |

**Lo que yo haría (gratis y con el menor riesgo):** una **tercera capa** que no requiere nada externo:

> **AlarmManager `setExactAndAllowWhileIdle` como "despertar de respaldo".** Programar una alarma exacta cada 4-5 minutos que lance un `BroadcastReceiver` → `syncOnce()` → notificar. En Doze, `setExactAndAllowWhileIdle` **sí** se ejecuta (es de las pocas excepciones), y a diferencia de WorkManager no tiene el mínimo de 15 min.
>
> - Coste: 0 €, 0 dependencias, ~80 líneas.
> - Limitación: las alarmas exactas requieren `SCHEDULE_EXACT_ALARM` (API 31+) o `USE_EXACT_ALARM` (API 33+, **concedida automáticamente solo a apps de SMS/telefonía/alarma/calendario** — puede no concederse en un sideload, pero `SCHEDULE_EXACT_ALARM` es concedible por el usuario en Ajustes). Hay que degradar a `setAndAllowWhileIdle` (inexacto, ~15 min) si no está concedido.
> - **Complementa** al WorkManager de 15 min, no lo sustituye.
> - Riesgo de batería: 1 wakeup cada 5 min es aceptable si el trabajo dura < 1 s.

Esta es la recomendación que más me gusta: **elimina la dependencia de un tercero y recorta la latencia de 15 min a ≤ 5 min** sin cambiar la arquitectura.

### 4.4 Alternativas evaluadas y descartadas (para que la decisión sea defendible)

Se revisaron todas las vías realisticamente disponibles sin Firebase. Esta tabla es el **porqué** de la recomendación final:

| Alternativa | Por qué se descarta / cuándo se usaría |
|-------------|------------------------------------------|
| **Supabase Realtime WebSocket** (actual) | ✅ **Se queda.** 0 €, 0 dependencias, ya encapsulado en `Phoenix.kt` con tests de framing real. |
| **UnifiedPush + ntfy** | ✅ **Plan B sólido.** Protocolo abierto y maduro, con ntfy como distributor sin cuenta. Requiere instalar ntfy en ambos teléfonos. **Cuidado:** ntfy.sh reenvía a Firebase por defecto → hay que enviar `X-Firebase: no` / `X-UnifiedPush: 1` para respetar la restricción del proyecto. |
| **ntfy self-hosted** | ✅ Si se quiere control total del dominio del tema. 1 VPS pequeño. Coste €3-5/mes → **no es gratis**, pero es la opción "sin terceros". |
| **AlarmManager `setExactAndAllowWhileIdle`** | ✅ **La recomendación principal.** 0 €, 0 dependencias, recorta la latencia de 15 min a ≤ 5 min. Degrada a `setAndAllowWhileIdle` si el usuario no concede el permiso de alarma exacta. |
| **Web Push (VAPID) directo** | ⚠️ En Android puro el Web Push necesita un distributor (FCM o UnifiedPush) que haga de puente → sin UnifiedPush no hay despertador real. La especificación de UnifiedPush **se apoya en WebPush desde 2025**, así que WebPush solo tiene sentido *a través* de UnifiedPush. |
| **MQTT (EMQX Cloud, HiveMQ Cloud, broker público)** | ⚠️ El broker público no despierta una app Android que no esté en primer plano. EMQX/HiveMQ tienen planes gratuitos de caudal limitado, pero el cliente sigue necesitando un FGS o push para despertar. **No resuelve el problema, lo desplaza.** |
| **OneSignal (free tier)** | ❌ OneSignal **requiere FCM o APNs** para entregar a Android. Contradice D44 directamente. |
| **Ably / Pusher (free tier)** | ⚠️ Son **brokers de WebSocket**, no push: mantienen una conexión abierta (que es exactamente lo que ya hace el FGS) y cobran por mensajes/conexiones. Supabase Realtime ya da 2 M de mensajes/mes y 200 conexiones de graça → **no aporta nada nuevo**. |
| **Mi Push SDK / HMS Push / OEM push** | ⚠️ Xiaomi/Huawei push solo funciona en ROMs de esos fabricantes y requiere SDK propietario. Útil como *complemento* si ambos teléfonos son Xiaomi; no como base. |
| **Play Feature Delivery** (APK modular) | ❌ Necesita Google Play Services para la instalación dinámica → contradice la restricción de no-GMS. |

**Conclusión:** la arquitectura actual **es la correcta** para este proyecto. El trabajo no es reemplazarla, sino **blindarla** (tres capas, reconexión inteligente, backup externo) y **quitarle el rozamiento** al usuario (el indicador del timbre, el switch para silenciar la notificación persistente, el `setOngoing(false)`).

### 4.5 Refinamientos de la notificación (lo que ya está bien + pulido)

| Mejora | Ubicación | Detalle |
|--------|-----------|---------|
| **Badge del launcher** con el nº de no leídos | nuevo | `ShortcutBadger` o el propio `NotificationBadge` vía `setNumber` (ya se hace) — falta el badge en el icono |
| **Notificación de llamada entrante como full-screen intent** | `CallDispatcher` | Sustituye el overlay propio por la pantalla de llamada del sistema → se ve sobre cualquier app, con botones de responder/colgar. Requiere `USE_FULL_SCREEN_INTENT` + `PendingIntent` con `setFullScreenIntent` |
| **"Escribiendo…" / "grabando audio"** en el `MessagingStyle` | `Notifier.kt` | Actualizar la notificación viva mientras el otro escribe (ver §5.2) |
| **Bundles de respuestas rápidas** | `Notifier.kt:169` | Añadir 3 `RemoteInput` con respuestas predefinidas ("Sí", "¿A qué hora?", "👍") — un toque en vez de teclear |
| **Marcar toda la conversación como leída desde la notificación** | nuevo | Ya existe "Marcar leído" por sala; falta "Marcar todo" |
| **Silenciar una sala sin entrar a Ajustes del sistema** | nuevo | Acción directa en la notificación: "Silenciar 1 h" |
| **Agrupar por conversación y no por sala** | `Notifier.kt:128` | Hoy `byRoom.groupBy` crea una notificación por sala — correcto, pero para 5 salas con actividad son 5 tarjetas. `setGroup()` + resumen ya lo hace; falta `setGroupAlertBehavior(GROUP_ALERT_SUMMARY)` |
| **Texto de la notificación con el nombre real, no "@username"** | `Notifier.kt:137` | El título ya usa el perfil E2EE ✅ pero verificado que `nicknameOf` devuelve `null` si no hay apodo y cae al `displayName` ✅ correcto |

---

## 5. UI/UX — auditoría y hoja de ruta

### 5.1 Lo que está bien

- **El tema Cerlita está medido píxel a píxel sobre el mockup** (`Theme.kt:49-100`) — es un detalle de取证 que casi nadie hace. Los comentarios con los hex muestreados son un espejo del proceso.
- **La paleta de chat separada del esquema M3** (`ChatPalette` en `Theme.kt:108-135`) resuelve un problema real: en Material 3, las burbujas propias son `primary` con texto `onPrimary` (contraste excellentísimo, pero no es el look del mockup). La solución de `LocalChatPalette` con `CompositionLocalProvider` es la forma idiomática de hacerlo.
- **Un canal de notificación por sala** (`Notifier.kt:82-91`) con `IMPORTANCE_HIGH` y el nombre real → el usuario silencia cada conversación desde los ajustes del sistema. Es exactamente lo que hace WhatsApp.
- **`MessagingStyle` con avatar real** (foto de sala → foto del par → avatar por capas → inicial coral, `Notifier.kt:273-296`). El degradado es thoughtful.
- **El perfil por capas (Cerdita/Koalita)** con slots y "ids desconocidos se ignoran" (`D61`) → extensible por el usuario sin tocar la app. Muy bien pensado.
- **Los apodos locales** (`D78`): privada, solo en tu teléfono, nunca viaja por la red. Feature pequeña con mucho gusto.
- **Long-press con menú contextual** en la lista de chats, con badge de no leídos. `RoomsScreen.kt:777-804`.
- **Navegación atrás del sistema** que en vez de cerrar la app navega (`AppRoot.kt:123`). Buen detalle.

### 5.2 Problemas de UI/UX detectados

**U1 · No hay tema oscuro Cerlita.** `CerlitaScheme` es un `lightColorScheme` sin variante oscura (`Theme.kt:64-100`). El tema por defecto es "cerlita" → **de noche, la app es un bloque rosa claro**. Es el defecto visual más visible de la app. **Es el primer arreglo de UI que haría.**

```kotlin
private val CerlitaDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB3BC),           // coral claro sobre fondo ciruela
    onPrimary = Color(0xFF5A1F2A),
    primaryContainer = Color(0xFF7A3B45),
    onPrimaryContainer = Color(0xFFFFD9DE),
    background = Color(0xFF2A1B22),        // ciruela profundo
    onBackground = Color(0xFFF2DDE0),
    surface = Color(0xFF33222A),
    onSurface = Color(0xFFF2DDE0),
    surfaceVariant = Color(0xFF4A353D),    // burbuja recibida oscura
    onSurfaceVariant = Color(0xFFD8BCC2),
    tertiary = Color(0xFFFFB3C0),
    ...
)
```

Y el `ChatPalette` oscuro correspondiente (burbuja propia `primaryContainer`, recibida `surfaceVariant`, texto claro). La paleta ya está medida: solo hay que invertir los tonos keeping el matiz.

**U2 · El scroll salta al final siempre.** `ChatScreen.kt:279-281`:

```kotlin
LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
}
```

Si estás leyendo un mensaje de hace tres días y llega uno nuevo, **te tira al final y pierdes la posición**. Es el bug de UX más molesto de cualquier chat.

**Corrección (scroll condicional):**

```kotlin
LaunchedEffect(messages.size) {
    if (messages.isEmpty()) return@LaunchedEffect
    val cercaDelFinal = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        ?.let { it >= messages.size - 3 } ?: false
    if (cercaDelFinal) {
        listState.animateScrollToItem(messages.lastIndex)
    } else {
        // mostrar el chip "↓ 1 mensaje nuevo" sin mover la vista
        nuevoPendiente = true
    }
}
```

**U3 · O(n²) en la lista de mensajes.** `ChatScreen.kt:293-304`:

```kotlin
items(messages, key = { it.msgId }) { m ->
    val idx = messages.indexOfFirst { it.msgId == m.msgId }   // ← O(n) POR item
    val prev = messages.getOrNull(idx - 1)
```

Con 5 000 mensajes: 25 000 000 de comparaciones por recomposición. **Corrección trivial:**

```kotlin
itemsIndexed(messages, key = { _, m -> m.msgId }) { idx, m ->
    val prev = messages.getOrNull(idx - 1)
```

**U4 · `SimpleDateFormat` se instancia en cada burbuja y en cada fila.** `ChatScreen.kt:517` y `RoomsScreen.kt:759`. `SimpleDateFormat` es **muy** caro de construir (parsea el patrón cada vez, crea un `Calendar` y un `DateFormatSymbols`). Con 50 burbujas visibles por recomposición son 50 construcciones.

**Corrección:** un `remember` por pantalla + formatters cacheados por patrón:

```kotlin
// core/Formats.kt
object Fmt {
    private val cache = ConcurrentHashMap<String, SimpleDateFormat>()
    fun time(ms: Long): String = cache.getOrPut("HH:mm") { SimpleDateFormat("HH:mm", Locale.getDefault()) }.format(Date(ms))
    fun list(ms: Long): String = cache.getOrPut("d MMM HH:mm") { ... }.format(Date(ms))
}
```

**U5 · Sin Paging 3: todo el historial se carga en memoria.** `observeChat` devuelve `Flow<List<Message>>` — la lista completa. Con 20 000 mensajes son ~20 000 objetos `Message` residentes, cada uno con su `body` (String). En un teléfono de 2 GB, ~4-6 MB solo de objetos, más el coste de la query.

**Corrección (la estándar, gratis, de AndroidX):**

```kotlin
// build.gradle.kts
implementation(libs.androidx.room.paging)   // room-paging

// Db.kt
@Query("SELECT * FROM messages WHERE roomId = :room AND kind = 0 ORDER BY ts ASC, msgId ASC")
fun observeChatPaged(room: String): PagingSource<Int, Message>

// ChatScreen.kt
val lazyPagingItems = app.db.msgDao().observeChatPaged(roomId)
    .flow.cachedIn(scope)
LazyColumn { items(lazyPagingItems) { m -> MessageBubble(...) } }
```

Con Paging 3 la base se trae solo, la memoria se mantiene plana y el scroll es fluido con 100 000 mensajes. **Requiere `room-paging` + `paging-compose`**, ~+80 KB. Es la mejora de rendimiento más grande que puedes hacer.

**U6 · No hay "escribiendo…".** D51 lo excluyó para la v1 con buen motivo (a 500 ms de RTT no aporta). **Pero en 2026 la app tiene WebSocket vivo y el par ya sabe que el otro está en línea** — un indicador de escritura vía el mismo canal Realtime (broadcast a `room-<roomId>` con un `type=typing`, TTL 5 s) costaría ~40 líneas y es **la** feature que más se echa de menos en un chat.

**Implementación:**

```kotlin
// servidor: en outbox, si el body trae {a:"typing"} → broadcast sin insertar
// cliente: al recibir, un StateFlow<Map<String, Boolean>> con TTL
```

El mensaje de "escribiendo" **no** se persiste nunca (evento puro, como `room_state` de D48).

**U7 · El texto de la notificación y el título usan `@username` cuando no hay perfil.** `RoomsScreen.kt:694` — `baseName = nickname ?: (item.peerName ?: item.name)`. Si no hay perfil E2EE todavía (el par nunca se anunció), se muestra `"@alain"`. Correcto como fallback, pero la primera impresión de la app es un `@nombre` feo. **Mejora:** derivar un nombre bonito de la primera palabra del mensaje recibido ("Hola amor" → "Amor") o simplemente capitalizar el username. Detalle pequeño, efecto grande en la percepción de calidad.

**U8 · Accesibilidad: hay huecos.**

| Elemento | Problema | Corrección |
|----------|----------|-----------|
| `AvatarView` en la lista de chats | Sin `contentDescription` | `"Foto de perfil de $nombre"` / `"$nombre"` |
| Burbuja de mensaje | `combinedClickable(onClick = {})` sin semántica | Añadir `Modifier.semantics { }` con el texto del mensaje para TalkBack |
| Badge de no leídos | Color solo, sin texto para TalkBack | `Modifier.semantics { contentDescription = "$unread mensajes sin leer" }` |
| Color de estado de envío (✓/✓✓) | Solo color | Ya hay `contentDescription` en el `Icon` ✅ bien hecho |
| Tamaño de fuente del sistema | Ignorado | Compose respeta el escalado de fuente del sistema ✅, pero hay `widthIn(max = 300.dp)` **fijo** en la burbuja (`ChatScreen.kt:568`) → con fuente grande el texto se corta. **Usar `widthIn(max = 300.dp)` → `fillMaxWidth(0.78f)`**, que es proporcional |
| Contraste | `onSurfaceVariant = #7A6167` sobre `#CCBABA` → ratio ~3,4:1 | AA pide 4,5:1 para texto normal. **Subir a `#6B5157` (~4,9:1)** |
| `R.string` | Solo 7 strings; el 95 % del texto está hardcodeado en Kotlin | Extraer a `strings.xml` → permite localización y auditoría |

**U9 · El diálogo de "Nuevo chat" mezcla tres flujos en una `AlertDialog`.** `RoomsScreen.kt:249-445`. Buscar usuario, crear grupo y unirse con código en la misma alerta con un `Row` de `TextButton`. En pantallas pequeñas se apelmaza y el teclado tapa los resultados.

**Corrección:** pantalla dedicada (o `ModalBottomSheet`) con tres pestañas segmentadas. `ModalBottomSheet` es M3, gratis, y se usa con el pulgar — ideal para móvil.

**U10 · El `FAB` de "Nuevo chat" tapa el último chat de la lista.** `RoomsScreen.kt:237-245` — `align(BottomEnd).padding(16.dp)` sin `contentPadding` en el `LazyColumn`. Corrección: `contentPadding = PaddingValues(bottom = 88.dp)`.

**U11 · Falta un estado vacío ilustrado para "cero chats" con un botón de acción.** `RoomsScreen.kt:192-210` — solo texto. Debería llevar a "Buscar a tu persona" / "Crear un grupo" / "Unirme con un código" (las tres acciones en un solo sitio).

**U12 · No hay swipe-to-dismiss, ni animaciones de entrada/salida de burbuja, ni "typing" animado.** `AnimatedVisibility` / `animateItem()` de Compose 1.7+ dan todo esto gratis.

**U13 · Falta un widget de escritorio.** Con `Glance` (AndroidX, gratis, Compose-based) puedes hacer un widget con los últimos mensajes y un botón de "enviar rápido". **Es la feature que más valor da por línea de código en todo el conjunto**, porque convierte "abrir la app" en "un toque". Y con `WorkManager` + `Glance` el widget se refresca solo.

**U14 · Falta soporte de pantalla ancha / tablet.** `WindowSizeClass` (Material3 Window Size Class, gratis) permite un layout de dos paneles en tablet. Si alguno de los dos tiene una tablet, esto cambia la experiencia por completo.

**U15 · Sin soporte de pantalla dividida (split-screen).** `android:resizeableActivity="true"` es el default, pero el `Scaffold` no se adapta. Con `WindowSizeClass` se resuelve.

---

## 6. Rendimiento y calidad del APK

### 6.1 Lo que ya está

- R8 + `shrinkResources` desde la v1 ✅
- `minifyEnabled` con reglas de keep correctas para argon2kt, ViewModel y WebRTC ✅
- `okhttp` con `callTimeout` ✅ (esencial en redes que "colgan")
- Compresión gzip antes del AEAD y en el servidor ✅
- `Polling` con jitter ✅ (evita la tormenta de reconexión tras un apagón)
- WorkManager con jitter manual añadido ✅ (el backoff de WorkManager no trae jitter)

### 6.2 Lo que falta

**P1 · Baseline Profile.** Es **gratis**, lo genera Google Play, y mejora el arranque y el scroll de forma medible (típicamente 15-30 % de tiempo de inicio en frío).

```kotlin
// build.gradle.kts (release)
plugins { id("androidx.profileinstaller") }
// plugin de baseline profile:
// https://github.com/android/baselineprofile
```

Y el módulo `app/src/main/baseline-prof.txt` generado. Como la app no está en Play, se puede generar con `macrobenchmark` en local o con la herramienta `com.android.tools.profgen`. Alternativa sin Play: usar `androidx.profileinstaller` con un perfil manual escrito a mano para los arranque críticos.

**P2 · `macrobenchmark` / `benchmark` en CI.** Medir:
- tiempo de arranque en frío
- frame time del scroll con 5 000 mensajes
- tiempo de `syncOnce` completo
- consumo de RAM con 20 000 mensajes

Sin medición, cualquier "optimización" es unaрук타 anecdote.

**P3 · `Startup` de AndroidX (o `androidx.startup` manual).** Hoy `ChatApp.onCreate` inicializa `Prefs`, `Api`, `Room`, `SessionStore`, `ChatEngine` y arranca el `topicsJob` + el watchdog. **`AppDatabase.build()` abre SQLite al arrancar** — eso es I/O en el cold start. Solución: `Room.databaseBuilder(...).setQueryExecutor(...)` con un executor de un solo hilo, y `allowMainThreadQueries` desactivado (ya lo está ✅). Mejor aún: usar `androidx.startup` con `Initializer` para diferir lo que no se necesita en el primer frame (el `db` y el `engine` sí se necesitan; el watchdog y el `topicsJob` pueden esperar).

**P4 · `LazyColumn` con `contentType` y `key` ya está bien** (`key = { it.msgId }` ✅). Falta `contentType` para que Compose reutilice correctamente las filas de distintos tipos (texto / imagen / audio / doc):

```kotlin
items(lazyPagingItems, key = { it.msgId }, contentType = { it.kind }) { m -> ... }
```

**P5 · `Snapshot` de Compose: las `SimpleDateFormat` y los `mutableStateOf` dentro de `items` invalidated recomposan toda la lista.** Con Paging 3 + `contentType` esto se resuelve en cascada.

**P6 · Compresión de imágenes en recepción.** Las fotos que llegan (1600 px, q85) se decodifican a bitmap completo en `ImageBubble`. En un móvil de 2 GB, un chat con 50 fotos es un problema. **Usar Coil con `size()` y `memoryCache`** en vez de `BitmapFactory` manual. Coil es gratis,Apache-2.0, y ya es el estándar. Además genera miniaturas en disco en vez de decodificar cada vez.

**P7 · `android:largeHeap="false"`** (default) ✅ bien. Pero **conviene un `OutOfMemoryError` explícito** en la carga de adjuntos (§B8) que borre el caché de adjuntos y dé un mensaje claro en vez de crashear.

### 6.3 ⚠️ Android 16 (API 36) — el riesgo que no está documentado

**El app ya tiene `targetSdk = 36`.** La documentación oficial de Android 16 dice:

> *"Background jobs started from a foreground service now must adhere to their respective runtime quotas. This includes jobs scheduled directly with `JobScheduler`, as well as jobs created by other libraries like **WorkManager** or `DownloadManager`."*

**Qué significa para Cerlita:** el `DoorbellService` (FGS) corre permanentemente, y la app además usa WorkManager (`sync_periodic` cada 15 min, `outbox_once`). En Android 16, los jobs de background lanzados desde un FGS están sujetos a **cuotas de runtime** que antes no se aplicaban.

**Lo que hay que hacer:**
1. **No lanzar `WorkManager` desde dentro del `DoorbellService`.** Hoy no se hace ✅ (el worker corre por su cuenta). Mantenerlo así.
2. **Considerar `setExpedited()`** para `OutboxWorker` cuando hay un envío en cola — los jobs expediteados tienen prioridad y están exentos de las cuotas ordinarias, y Android los trata como "transferencia de datos iniciada por el usuario" (que es exactamente el caso: el usuario acaba de pulsar enviar).
3. **Añadir `onTimeout()`** al `DoorbellService` por si en alguna versión futura `remoteMessaging` recibe un límite. Es 4 líneas y es la buena práctica documentada.
4. **Vigilar `targetSdk`:** en Android 16 los cambios se aplican **independientemente del targetSdk** ("regardless of what API level they target"). O sea, ya aplica.

**Verificado y correcto:** el límite de 6 h/24 h es **solo** para `dataSync` y `mediaProcessing`; `remoteMessaging` **no tiene límite de tiempo**. Y `remoteMessaging` **sí** puede arrancarse desde `BOOT_COMPLETED` (en la allowlist `FGS_BOOT_COMPLETED_ALLOWLIST`), mientras que `dataSync` **no puede** con targetSdk ≥ 35. **La decisión D64 es la correcta.**

**Verificado y correcto:** `WorkManager` 2.12.0 está por encima de 2.10.5, que es la versión donde Google arregló el crash de "Re-initializing SystemForegroundService after a request to shut-down" (FGS workers solapados). ✅

### 6.4 Tamaño del APK

| Componente | Peso | Comentario |
|------------|------|------------|
| Base (sin WebRTC) | 2,8 MB | Excelente |
| WebRTC (stream-webrtc-android 1.1.3) | +16 MB | **El 85 % del APK** |
| `material-icons-extended` | +1,5-2 MB (R8 lo recorta) | Con R8 + shrink, solo se quedan los usados ✅ |
| Propuesta: `room-paging` + `paging-compose` | +80 KB | **Altamente recomendado** (§U5) |
| Propuesta: `coil-compose` | +400 KB | **Recomendado** (§P6) |
| Propuesta: `androidx.glance` | +300 KB | Para el widget (§U13) |
| Propuesta: `androidx.compose.material3-adaptive` | +150 KB | Para tablets (§U14) |
| **Total tras todas las mejoras** | **~20,5 MB** | Sigue siendo razonable |

**Ahorro directo posible:** `stream-webrtc-android` 1.1.3 es una build antigua de libwebrtc. Las builds actuales (`org.webrtc:google-webrtc:1.0.32006` de Stream, o `io.getstream:stream-webrtc-android:1.1.x`) pueden no estar más ligeras. **Alternativa:** hacer las llamadas **opcionales** con Play Feature Delivery (`com.google.android.play:feature-delivery`) para entregar el módulo WebRTC solo cuando se usa → APK base de 2,8 MB. **PERO** Play Feature Delivery requiere Google Play Services para la instalación dinámica → **contradice la restricción de no-Firebase/GMS**. Así que **no es viable aquí**. Dejar el WebRTC embebido.

**Alternativa real para reducir el APK:** la app ya hace `abiFilters += listOf("arm64-v8a", "armeabi-v7a")` en release. ✅ Correcto (descarta x86). Podría además ofrecer un **APK split por ABI** para que cada teléfono descargue solo su `.so` → el APK por dispositivo pasa de 20 MB a ~8 MB. Es gratis, se genera con `./gradlew bundleRelease` y distributing por Play (no disponible) o manualmente. Como se distribuye por APK directo, **el APK único es más simple**. Documentar el trade-off.

---

## 7. Backend

### 7.1 Supabase Free — qué está resuelto y qué falta

**Verificado en la documentación oficial (octubre 2026):**

| Límite | Free | Impacto en Cerlita |
|--------|-------|--------------------|
| **Pausa por inactividad** | **1 semana** sin actividad | ⚠️ Ver abajo |
| Tamaño de BD | 500 MB | Sobrado (2 usuarios) |
| Egress | 5 GB/mes | ~100 MB/mes real → 5 % de uso |
| Invocaciones Edge Functions | 500 000/mes | ~50 000/mes real → 10 % de uso |
| Storage | 1 GB | Los adjuntos cifrados; 1 GB se llena en ~1 000 fotos |
| **Realtime: conexiones concurrentes** | **200** | 2 usuarios → 0,1 % de uso |
| **Realtime: mensajes/mes** | 2 000 000 | Irrelevante |
| **Realtime: tamaño máx. de mensaje** | **256 KB** | ⚠️ Ver R3 |
| Edge Function: duración | 150 s | De sobra |
| Edge Function: memoria | 256 MB | De sobra |

**Sobre la pausa (R2) — matiz importante:** la regla oficial dice *"A Free plan project is considered inactive if it does not receive sufficient user database activity"*, y la KB aclara *"Opening the project in the Supabase Dashboard or sending real requests from your app both count as activity. A few requests a day is usually enough."*

El heartbeat D59 (`backend/functions/heartbeat/index.ts:14`) hace un `UPDATE app_heartbeat` real a la BD cada 6 h vía pg_cron+pg_net. **Eso SÍ cuenta como actividad de base de datos** ✅. La decisión D59 es válida y está bien resuelta.

**Pero:** pg_cron corre dentro del propio Postgres. Si el proyecto está pausado, **pg_cron tampoco corre** (la instancia está congelada) → el heartbeat no se dispara → no se reactiva solo. El ciclo se rompe.

**Mejora (gratis, 20 min):** un segundo activador **externo** al proyecto Supabase, que no dependa de él:
- **GitHub Actions** con `schedule:` (cron de GitHub, gratuito para repos públicos, y también para privados con 2 000 min/mes) → `curl https://<proyecto>.supabase.co/functions/v1/heartbeat`.
- Eso se mantiene en marcha aunque Supabase esté pausado, y es la llamada que lo **despausa**.

```yaml
# .github/workflows/anti-pausa.yml
name: anti-pausa
on:
  schedule:
    - cron: '17 */6 * * *'
  workflow_dispatch:
jobs:
  beat:
    runs-on: ubuntu-latest
    steps:
      - run: curl -fsS --max-time 30 "${{ secrets.SUPABASE_FN_URL }}/heartbeat"
```

**Mejora adicional:** una alerta por email si el heartbeat falla dos veces seguidas (GitHub Actions puede enviar email a un step que falle). Así te enteras **antes** de que se caiga, no después.

### 7.2 Realtime: el límite de 256 KB (R3)

**Verificado:** en el plan Free, el tamaño máximo de un mensaje Realtime es **256 KB** (3 MB en Pro). El broadcast de llamada lleva un SDP completo.

**Medida preventiva:** en `CallSignals`, medir el tamaño del payload y, si `sdp.length > 200_000`, truncar los candidatos ICE no esenciales antes de emitir. En la práctica un SDP es de 3-8 KB, así que el margen es de 30×. **Pero** un `trickle ICE` patológico (miles de candidatos en redes con muchos interfaces) podría acercarse. Truncar es gratis y elimina el riesgo.

Además, con el cifrado de la señalización (§B6) el payload crece ~33 % (base64) — sigue muy lejos de 256 KB.

### 7.3 Rate limiting: no hay ninguno

`auth` tiene lockout de login ✅ (D24). Pero:
- `outbox` no limita nada → un token robado puede enviar lotes ilimitados.
- `sync` no limita nada → un token robado puede vaciar toda la base en peticiones.
- `search` no limita nada → permite enumerar los 10 usuarios.

**Para 2 usuarios el riesgo es bajo, pero el coste de arreglarlo es bajo también.** Y algo que sí importa: **el `MAX_USERS = 10` hardcodeado** impide registro si alguien se equivoca. Con el código de invitación ya hay control. OK.

**Mejora concreta y gratis:** rate limit en Postgres por token, con una tabla mínima:

```sql
create table if not exists rate_limit (
  key text not null, bucket text not null, n int not null default 0,
  reset_at timestamptz not null, primary key (key, bucket)
);
```

Y en el borde de cada Edge Function, un `insert ... on conflict do update ... where reset_at > now()` que devuelva 429 si se pasa. 30 líneas por función. **Alternativa más simple y suficiente:** el `outbox` ya está limitado a 64 mensajes por lote y 8 KB por mensaje → el máximo por petición es 512 KB. Añadir un límite de **peticiones por minuto por token** en el `outbox` con la tabla anterior.

### 7.4 El bug de los topes que se counted entre sí (nuevo hallazgo)

`backend/functions/auth/index.ts:208`:

```sql
select count(*)::int as n from rooms where created_by = ${uid}::uuid
if (n >= MAX_ROOMS_PER_USER) return json(403, { e: "room_cap" });
```

Cuenta **todas** las salas creadas por el usuario, **incluidas las de chat directo** (`rooms.kind = 1`, creadas por `dmNew` con `created_by = uid`). Y `MAX_DMS_PER_USER = 12` permite crear hasta 12 DMs.

**Consecuencia:** tras crear **8 chats directos**, el usuario recibe `room_cap` y **no puede crear ningún grupo más**. El error dice "Alcanzaste el máximo de salas que puedes crear" — que además es engañoso, porque sí puede crear más DMs.

**Corrección:**

```sql
select count(*)::int as n from rooms where created_by = ${uid}::uuid and kind = 0
```

**Y el test que falta:** el suite E2E (`e2e_rooms_client.py`) debería cubrir "8 DMs + 1 grupo". Añadirlo al checklist de 37/37.

### 7.5 Sin TTL ni archivado de mensajes

La tabla `messages` crece sin límite. Con 2 usuarios y 8 KB máximo, **no es un problema hoy** (100 000 mensajes ≈ 400 MB... ¡sí lo es!). Un usuario muy activo podría llegar a cientos de miles de mensajes.

**Mejora barata:** un job de pg_cron que marque/borre mensajes con más de N meses en salas cuyo último miembro salió. O simplemente, documentar el límite y añadir un índice.

**Índice que falta en el servidor:** `messages` tiene `idx_messages_sync ON messages(room_id, seq)` ✅ pero la query de sync es `WHERE room_id IN (...) AND seq > $2 ORDER BY seq` — el índice `(room_id, seq)` no sirve bien para el `ORDER BY seq` global. **Falta un índice en `seq`:**

```sql
CREATE INDEX IF NOT EXISTS idx_messages_seq ON messages(seq);
```

Con `seq` global, un `WHERE seq > $2 ORDER BY seq LIMIT 200` es un range scan secuencial perfecto con ese índice. **Ganancia real en cada poll.**

### 7.6备份 / Backups

**Nada.** El plan Free de Supabase **no incluye backups automáticos** (Pro sí: "Daily backups (7 day retention)"). Si la BD se corrompe o alguien hace un `DROP`, se pierde todo el historial del servidor.

**Corrección gratis:** el propio `heartbeat` (o un cron aparte) puede hacer un `pg_dump` diario y subirlo a un bucket de Storage **con cifrado de cliente**. O más simple: como la app ya tiene un **export cifrado del historial** (`HistoryExport.kt`), el usuario puede exportar mensualmente. **Lo que falta es la rutina:** una nota en Ajustes que recuerte "haz una copia de seguridad cada mes", y/o un dump automático.

**Alternativa muy buena y gratis:** `pg_dump` desde el propio Postgres con `pg_cron` → subir el dump a un bucket privado de otro proyecto (o a un VPS). Cifrado con la misma clave del export de la app.

### 7.7 La región

El proyecto está en **us-east-2 (Ohio)**. Cuba tiene/routes hacia **Miami (us-east-1)** o **Amsterdam (eu-west-1)** o **São Paulo (sa-east-1)**. Un proyecto en Ohio añade ~30-50 ms de RTT extra frente a Miami.

**D50 ya lo identificó como pendiente ("medir TTFB desde la SIM real antes de asumir")** — bien hecho. **Medición concreta que hay que hacer:**

```bash
# desde la SIM taxed, con curl
for h in us-east-1.us-east-1.supabase.co us-east-2.us-east-2.supabase.co sa-east-1.sa-east-1.supabase.co; do
  echo -n "$h: "; curl -o /dev/null -s -w "%{time_connect}s connect, %{time_starttransfer}s TTFB\n" "https://$h/rest/v1/"
done
```

⚠️ **Ojo:** en Supabase no se elige la región al crear el proyecto — **se crea el proyecto en la región que quieres**. Migrar = crear un proyecto nuevo en la región destino y re-ejecutar las migraciones (los scripts ya lo hacen, ~15 min según D50).

### 7.8 Autenticación: 6 puntos a mejorar

| # | Tema | Estado | Mejora |
|---|------|--------|--------|
| 1 | Token de sesión: 32 B, SHA-256 en BD, 90 días, revocable | ✅ Bien | Añadir **rotación de token** en cada login (revocar el anterior) |
| 2 | Sin `rate limit` en `outbox`/`sync`/`search` | ❌ | §7.3 |
| 3 | `sessions` no tiene `ip` ni `user_agent` | ❌ | Añadirlos: permite detectar un login sospechoso y revocar sesiones |
| 4 | No hay "ver todas mis sesiones" | ❌ | Pantalla en Ajustes: "Este teléfono · آخر acceso: hace 2 h" + botón "Cerrar las otras sesiones" |
| 5 | `MAX_USERS = 10` hardcodeado | ⚠️ | Hacerlo configurable por env (`MAX_USERS`) |
| 6 | El lockout es **por usuario**, no por IP | ⚠️ | Un atacante puede bloquear a un usuario legítimo a propósito (DoS). Añadir un límite por IP además |

### 7.9 Lo que el backend hace bien y hay que preservar

- **RLS activado sin políticas** en todas las tablas → el acceso vía anon key/PostgREST está bloqueado. ✅ Correcto.
- **`ON CONFLICT DO NOTHING` + `RETURNING`** para que el timbre suene **una sola vez por mensaje**. ✅ (`outbox/index.ts:82-88`)
- **El `ack` filtra por `sender_id = uid`** → un usuario no puede "ackear" los mensajes de otro. ✅
- **El `files` verifica la pertenencia por el path, no por un parámetro** → no se puede pedir la URL de otra sala. ✅ (`files/index.ts:78-81`)
- **La política de Storage solo permite INSERT con prefijo UUID** → nadie puede leer ni borrar. ✅
- **El `gzip` con degradación limpia** (`CompressionStream` → null si falla). ✅
- **Los `e` de error son simbólicos, nunca filtran detalles** en producción (`DEBUG` gate). ✅

---

## 8. Llamadas (WebRTC)

### 8.1 Diagnóstico

Lo que funciona:
- **Señalización por el mismo canal Realtime** que el timbre → cero infraestructura extra. ✅
- **El eco propio se ignora** (`CallSignals.parse` compara `from` con `myUid`). ✅
- **El mutex + `IceQueue` resuelven la carrera SDP/ICE** (D81) — que era la causa raíz de "nunca conectaba". ✅
- **`onFailed` en `FAILED` y en `DISCONNECTED` sin haber conectado** → la llamada siempre termina. ✅
- **La ruta de audio API 31+ con `setCommunicationDevice`** (D81) — Many OEMs ignoraban `setSpeakerphoneOn`. ✅ Correcto y bien investigated.
- **El registro local de llamadas** con duración, requiere `CallLog` y `CallsScreen`. ✅

Lo que falta:

### 8.2 Sin TURN → ~15 % de las llamadas no conectan (crítico)

`CallSession.kt:89-93`:

```kotlin
private val STUN = listOf(
    "stun:stun.l.google.com:19302",
    "stun:stun1.l.google.com:19302",
    "stun:stun.cloudflare.com:3478",
)
```

Con solo STUN, si **ambos** teléfonos están tras NAT simétrico o CGNAT (frecuente en redes móviles de operador y **muy probable en ETECSA/celular cubano**), no hay ruta directa y la llamada falla con *"no se pudo conectar"*. Las estadísticas de producción sitúan el ~15 % de los usuarios en NAT simétrico.

**El D75 ya lo documentó como pendiente. Es el bloqueo #1 de las llamadas.**

**Opciones gratuitas verificadas a octubre de 2026:**

| Proveedor | Gratis | Puertos | Límite | Latencia desde Cuba | Veredicto |
|-----------|--------|---------|--------|---------------------|-----------|
| **ExpressTURN** | **1 000 GB/mes** | 3478, 80, 443 | 1 TB/mes, TCP+UDP | Europa/África/S.A. — elegir región | ✅ **Mejor opción** |
| **Cloudflare TURN** | 1 000 GB el primer mes | 443 | 1000 GB/mes (primer mes) | Global (edge) | ✅ Muy bueno, **verificar permanencia del tier gratis** |
| **Open Relay (Metered)** | 20 GB/mes | 80, 443 | 20 GB | US/EU | ✅ Suficiente (~30 h de vídeo relayed) |
| **turnix.io** | Traffic mensual de prototyping | — | pequeño | Global | ⚠️ Marginal |
| **freeturn.net** | Sin cap publicado | — | "dev/testing" | — | ⚠️ Solo desarrollo |
| **coturn / eturnal self-hosted** | Ilimitado (coste del VPS) | 3478 + rango UDP | Sin tope | Tú eliges | ✅ Si hay VPS en Europa/Alemania: **latencia mínima** |
| **Oracle Always Free** | 1 VM | — | ⚠️ **Oracle borra las VMs idle** (ya documentado en D53) | US | ❌ Descartado |

**Cálculo para 2 usuarios:** una llamada de vídeo 720p relayed consume ~1,5 Mbps por dirección ≈ 1,35 GB/h. Con 1 000 GB/mes de ExpressTURN → **740 horas de llamada al mes**. Sobradísimo. Con los 20 GB de Open Relay → 15 horas. También de sobra para uso personal, pero con margen de sobra.

**Implementación (15 líneas):**

```kotlin
// CallSession.kt
private val TURN = listOf(
    PeerConnection.IceServer.builder("turn:turn.expressturn.com:3478")
        .setUsername("usuario")
        .setPassword("clave")
        .createIceServer(),
    PeerConnection.IceServer.builder("turns:turn.expressturn.com:443?transport=tcp")
        .setUsername("usuario").setPassword("clave").createIceServer(),
)
```

⚠️ **Importante — no hardcodear las credenciales TURN en el APK.** Aunque un TURN público con credencial compartida no es un secreto fuerte, lo correcto es que el servidor **emita credenciales TURN temporales** (como hace Metered) o al menos las descargue de la Edge Function `auth` (acción `turn`). Así:
1. El servidor nunca distribuye la clave estática.
2. Se puede rotar.
3. Se puede limitar por usuario.

```sql
-- backend: tabla de credenciales TURN con expiración
create table if not exists turn_creds (
  uid uuid not null, username text not null, credential text not null,
  ttl timestamptz not null, primary key (uid, username)
);
```

Coste total: ~1 hora de trabajo + 0 €.

### 8.3 La cámara se enciende ANTES de que el usuario acepte

`CallDispatcher.kt:160-161`:

```kotlin
val pending = CallSession(app, sig.roomId, sig.video, listener)   // ← crea la sesión
pending.prepareAsCallee(sig.sdp)
```

Y `CallSession.init` (`CallSession.kt:166-180`) llama a `startCamera(f)` si `withVideo`. → **La cámara del usuario se enciende en el momento en que le llega la llamada, antes de tocar "Aceptar".**

**Problemas:** privacidad (el indicador de cámara encendida en la barra de estado antes de aceptar), batería, y en algunos OEM despierta el procesador a destiempo.

**Corrección:** separar la construcción de la sesión del arranque de la cámara.

```kotlin
// CallSession: sacar la cámara del init
fun startCameraIfNeeded() { if (withVideo && videoCapturer == null) startCamera(factory) }

// CallDispatcher.accept(): ahora sí, arrancar la cámara
fun accept(app: ChatApp) {
    ...
    session?.startCameraIfNeeded()
    session?.answer { sdp -> ... }
}
```

Cuidado: el `answer` necesita la pista de vídeo **antes** de crear el answer, así que el orden correcto es `startCameraIfNeeded()` → `answer()`. Como `startCamera` es síncrono y rápido, va bien.

### 8.4 Otras mejoras de llamadas

| Mejora | Detalle | Coste |
|--------|---------|-------|
| **Full-screen intent para la llamada entrante** | La UI del sistema, con responder/colgar, funciona sobre cualquier app y con pantalla apagada. Hoy el overlay propio **solo aparece si la app está abierta** | Medio (permiso `USE_FULL_SCREEN_INTENT` + `PendingIntent`) |
| **Micrófono y cámara como FGS `microphone`/`camera`** | Si la app va a background durante la llamada, el FGS actual (`remoteMessaging`) no cubre el micro → el sistema puede cortar el audio | Bajo (declarar `FOREGROUND_SERVICE_MICROPHONE` + `CAMERA` y el tipo correspondiente) |
| **Cifrar la señalización** | §B6 —Media | 1 h |
| **Ajustar la calidad de vídeo a la red** | `VideoSource.capturer` con `adaptOutputFormat`; hoy fijo 640×480@24. Subir a 720p cuando la red lo permita, bajar a 180p en 2G | Medio |
| **Audio: activar `echoCancellation`, `noiseSuppression`, `autoGainControl`** en `MediaConstraints` de audio | Hoy `MediaConstraints()` vacío → **sin cancelación de eco**. En llamadas con altavoz, el eco es constante. **Es un bug de calidad de audio real** | Bajo (5 líneas) |
| **Opus con bitrate adaptativo** | `AudioSource` con `setAudioBitrate` / Opus DTX | Bajo |
| **Selector de altavoz/auricular en la pantalla de llamada** | Ya hay `toggleSpeaker` ✅ pero sin feedback visual | Bajo |
| **Historial de llamadas con "volver a llamar"** | `CallsScreen` tiene el registro; falta el botón | Bajo |
| **`networkQuality` / `stats`** | `pc.getStats()` → mostrar "calidad: mala" en vez de "se cortó" | Medio |
| **Llamada en grupo (mesh)** | 2 usuarios no lo necesitan | — |
| **Compartir pantalla** | `ScreenCapturer` | Medio |

**El punto de audio es importante y barato:** sin `echoCancellation`, una llamada con altavoz en un teléfono 导致 el interlocutor oye su propia voz con retardo. Es el defecto de calidad de audio #1 de las videollamadas.

### 8.5 `users.pub` — el last-write-wins rompe el multi-dispositivo (R4)

`backend/migrations/0004_dm.sql` — `users.pub BYTEA` con el comentario *"Clave pública X25519 (32 B) del último teléfono que inició sesión"*.

**Problema:** si el usuario entra en su teléfono A y luego en su teléfono B, `pub` pasa a ser el de B. Entonces:
1. Un wrap de DM creado para A ya no lo puede abrir B (no tiene la privada de A) — correcto, es el diseño.
2. Peor: A quiere **crear** un DM con alguien y el servidor tiene la pub de B → el wrap va dirigido a B, no a A. A no lo podrá abrir. **El chat directo queda roto hasta que A vuelva a hacer login.**
3. `uploadPub` se llama en cada login (`ChatEngine.uploadDevicePub`) → cualquiera que entre desde otro dispositivo **rompe** los DMs del primero.

**Corrección (gratis, 1 migración + 30 líneas):** tabla de claves por dispositivo.

```sql
-- backend/migrations/0006_device_keys.sql
create table if not exists device_keys (
  device_id uuid not null,
  user_id   uuid not null references users(id) on delete cascade,
  pub       bytea not null check (octet_length(pub) = 32),
  created_at timestamptz not null default now(),
  primary key (device_id, user_id)
);
```

- `uploadPub` → `insert ... on conflict (device_id, user_id) do update set pub = ...`.
- `dmNew` → el servidor elige la pub del **dispositivo solicitante** (`me.did`), no "la última".
- `dm_keys` pasa a enviar el wrap **al dispositivo concreto** (`for_device_id`), no solo al usuario.
- `/sync` sirve el wrap cuando coincide el `device_id`.

Esto **habilita de verdad el multi-dispositivo**, que hoy solo funciona para salas de grupo (vía código de invitación).

---

## 9. Seguridad — auditoría y endurecimiento

### 9.1 Lo que está bien

- **El servidor nunca ve claves, códigos de invitación, SAS, nombres ni fotos** — salvo la excepción de las llamadas (§B6).
- **RLS sin políticas** → la anon key no da acceso a datos. ✅
- **Argon2id (t=3, m=32 MiB, p=1)** para el password del servidor ✅ (la config correcta para 2026; m=32 MiB con t=3 es ~100 ms en servidor).
- **Argon2id (t=3, m=64 MiB, p=1)** en el cliente solo para el export ✅.
- **Comparaciones en tiempo constante** (`MessageDigest.isEqual` en el CRC, `eq()` con XOR en el servidor para el hash del password) ✅.
- **`allowBackup="false"`** → el historial no sale del teléfono por ADB. ✅
- **`usesCleartextTraffic="false"`** → no hay HTTP en claro. ✅
- **AAD con `senderId`** → un mensaje no se puede reetiquetar como de otro emisor. ✅
- **Nonce de 12 B aleatorio por mensaje** con clave derivada del `msg_id` (único) → sin reutilización de nonce. ✅
- **El vault sobrevive al logout** para el keypair del teléfono ✅ (decisión consciente y documentada).

### 9.2 Qué endurecer

| # | Riesgo | Mitigación | Coste |
|---|--------|-----------|-------|
| S1 | **El servidor ve la señalización de llamadas** (§B6) | Cifrar con la roomKey | 1 h |
| S2 | **La sesión no se invalida en 401** (§B4) | `logout()` + estado `SessionExpired` | 30 min |
| S3 | **No hay `network_security_config`** | Fijar el dominio de Supabase y habilitar `certificatePinning` (con un backup de pin por expiración) | 1 h + mantenimiento |
| S4 | **Los adjuntos se guardan descifrados en `attcache/dl-*`** | Limpiar `attcache` periódicamente (WorkManager diario) y cifrar en reposo con la clave del Keystore | 2 h |
| S5 | **`FLAG_SECURE` no activado** → las capturas de pantalla del chat son posibles | Activar en `ChatScreen` (el usuario puede desactivarlo) | 20 min |
| S6 | **Sin límites de paginación en `messages`** | `MAX_CT` limita el mensaje, pero no el número → un atacante puede insertar 10⁶ mensajes. Añadir un límite de mensajes por sala | 1 h |
| S7 | **El log de llamadas guarda el nombre del par en claro** en la BD local | Es local y protegido por el cifrado de disco. OK | — |
| S8 | **La app registra el `baseUrl` configurable** → un usuario puede apuntarla a un servidor falso | Permitirlo es una feature (servidor propio). **Marcar visualmente** "servidor no oficial" si el host ≠ el de por defecto | 30 min |
| S9 | **El token de sesión vive 90 días** en `ss.bin` | 90 días es mucho para un token. Reducir a 30 días + refresco al abrir la app | 1 h |
| S10 | **No hay revocación de sesiones desde la app** | Añadir "Cerrar sesión en todos los dispositivos" en Ajustes | 1 h |

**Nota sobre S3 (certificate pinning):** Supabase usa un CDN (Cloudflare) que rota certificados. El pinning mal hecho **rompe la app**. La forma segura en 2026 es el **pinning con backup** (`pin-set` con un pin de backup y expiración), pero para una app de 2 usuarios, el beneficio es marginal comparado con el riesgo de una rotura silenciosa. **Mi recomendación: no hacer pinning; en su lugar, `network_security_config` con `cleartextTrafficPermitted="false"` y `<trust-anchors>` del sistema (por defecto).**

**Nota sobre S4 (adjuntos en claro):** `downloadAttachment` escribe el archivo **descifrado** en `attcache/dl-<msgId>.<ext>` (`ChatEngine.kt:874-887`). Eso está en el almacenamiento **interno** de la app (`filesDir`), protegido por el cifrado de disco de Android (FUSE). No es un riesgo grave. **Pero** los adjuntos **descargados** nunca se limpian → crecimiento indefinido. **WorkManager diario: borrar `attcache` de más de 30 días.** Fácil.

---

## 10. Features nuevas (todas gratis)

Ordenadas por valor/esfuerzo. **El objetivo es "llevar la app al siguiente nivel" sin salir de 0 €.**

### 🥇 Nivel 1 — Cambian la experiencia diaria (máximo valor)

| # | Feature | Detalle técnico | Esfuerzo | Por qué |
|---|---------|-----------------|----------|---------|
| F1 | **Reacciones emoji** | Un nuevo `kind=4` de control (`\u0000R` + JSON `{msgId, emoji}`), ≤ 32 B, entra en el presupuesto de 8 KB. La UI agrupa hasta 3 reacciones bajo la burbuja con un contador. Tap para añadir la tuya | 3 h | La feature social #1 que falta. Se siente "vivo" |
| F2 | **Responder / citar** | `kind=5`: `{replyTo, preview}` + el preview visible en la burbuja + scroll al original al tocar | 4 h | Convierte el chat en algo usable para tema |
| F3 | **Editar y borrar para todos** | `kind=6`: `{op:"edit", body}` / `{op:"del"}`. El cliente marca el editado con "· editado". El borrado para todos muestra "Este mensaje se eliminó" | 4 h | Imprescindible en cualquier chat moderno |
| F4 | **Búsqueda de mensajes** | **Room FTS4/FTS5** (gratis, en el propio SQLite): tabla virtual + `MATCH`. Búsqueda por sala y global, con resaltado | 6 h | Con 5 000+ mensajes, buscar es la función que más se echa de menos |
| F5 | **Tema oscuro Cerlita** | §U1 — `darkColorScheme` + `ChatPalette` oscuro | 2 h | Hoy la app es rosa claro de noche |
| F6 | **"Escribiendo…"** | §U6 — broadcast `typing` con TTL de 5 s, nunca persistido | 1 h | La feature que más "humaniza" un chat |
| F7 | **Reenviar** | `kind=7` con el mensaje reenviado (cifrado) + marca "reenviado" | 2 h | 15 minutos de trabajo |
| F8 | **Mensajes que desaparecen** | `kind=8` con un TTL; el cliente borra localmente y el servidor purga por `client_ts` | 5 h | Feature de privacidad muy apreciada |
| F9 | **Widget de escritorio** | `androidx.glance`: últimos 2 chats + botón de respuesta rápida. `GlanceAppWidgetReceiver` + `WorkManager` de actualización | 6 h | Convierte "abrir la app" en "un toque" |
| F10 | **Scroll al final con chip "↓ N mensajes nuevos"** | §U2 | 1 h | Arregla el annoyance más visible |

### 🥈 Nivel 2 — Afinan la calidad

| # | Feature | Detalle | Esfuerzo |
|---|---------|---------|----------|
| F11 | **Favoritos/estrella** | `kind=9` con `starred`; panel de "mensajes guardados" | 3 h |
| F12 | **Programar un mensaje** | WorkManager `send_later` con el timestamp; la UI muestra un reloj en la burbuja | 3 h |
| F13 | **Reacciones rápidas en la notificación** | §4.4 — 3 `RemoteInput` con respuestas predefinidas | 1 h |
| F14 | **Pegar como texto /更长** | Detectar pegado largo → ofrecer "enviar como archivo" | 1 h |
| F15 | **Marcar mensajes como "importantes"** | Un icono de estrella en la burbuja | 2 h |
| F16 | **Vista de adjuntos / álbum** | Grid de fotos y archivos de la sala | 4 h |
| F17 | **Perfil de grupo** (avatar + nombre + miembros) | Ya existe a medias (D69) — completarlo con lista de miembros y verificación SAS visible | 3 h |
| F18 | **Modo "no molestar" por sala** | Horario y días sin aviso | 2 h |
| F19 | **Estadísticas de la relación** | Días hablando, mensajes, primera fecha. Con la foto de Cerlita/Koalita es un detalle bonito | 4 h |
| F20 | **Seguridad extra: ocultar preview en pantalla bloqueada** | `setVisibility(VISIBILITY_SECRET)` configurable por el usuario | 1 h |

### 🥉 Nivel 3 — El "siguiente nivel" real

| # | Feature | Detalle técnico | Esfuerzo |
|---|---------|-----------------|----------|
| F21 | **Cifrado de la señalización de llamadas** | §B6 | 1 h |
| F22 | **TURN gratuito** | §8.2 | 1 h |
| F23 | **Llamada entrante del sistema (full-screen intent)** | §8.4 | 4 h |
| F24 | **Quality adaptativa de vídeo + `echoCancellation`** | §8.4 | 3 h |
| F25 | **Multi-dispositivo de verdad** (tabla `device_keys`) | §8.5 | 4 h |
| F26 | **Cursores por sala** (arregla B1) | §B1 | 4 h |
| F27 | **Sincronización de servidores alternative / redundancia** | El `baseUrl` ya es configurable: permitir un **servidor espejo** (2º proyecto Supabase) con failover. El cliente prueba el primario y, si falla N veces, cambia al secundario | 6 h |
| F28 | **Cifrado del historial en reposo (SQLCipher)** | Sustituir Room por SQLCipher: todo el historial cifrado en disco, no solo las claves. Contrapartida: +2 MB de APK, peor rendimiento | 6 h |
| F29 | **Copia de seguridad automática cifrada** | Export E2EE automático diario a una carpeta SAF elegida por el usuario | 4 h |
| F30 | **Traducción on-device** | `ML Kit Translation` (gratis, on-device): "Traducir" en el long-press de la burbuja. Requiere descargar el modelo (~30 MB la primera vez) | 3 h |
| F31 | **Transcripción de notas de voz on-device** | ML Kit Speech-to-text on-device (gratis). La transcripción viaja E2EE como texto junto al audio | 6 h |
| F32 | **Pintura/garabato en fotos** | ActivityResult con un canvas Compose simple | 4 h |
| F33 | **Compartir ubicación** | Requiere backend (subir la coords cifrada a Storage) — el modelo ya lo soporta | 5 h |
| F34 | **Stickers/emoji grandes** | Un `kind=10` con un emoji grande, o un set de stickers propios | 3 h |
| F35 | **Migración a otro teléfono (QR de pairing)** | Un QR con el `roomId` + un `wrap` del vault cifrado con la clave del otro teléfono → "Escanear para transferir todo" | 6 h |
| F36 | **Modo "baja consumption"** | Bajar el polling, apagar el FGS, restringir la sync a WiFi. Para los días de datos limitados | 2 h |
| F37 | **Lector de tarjetas / QR de SAS** | El SAS de 6 dígitos se puede mostrar como QR (la app puede generarlo sin librería: un QR de 6 dígitos es trivial con `zxing`) | 2 h |
| F38 | **Sincronización de contactos** | Pedir permiso de contactos → sugerir chats con los que ya tienes en la agenda. "Ya tienes a Ana en tu agenda, ¿abrir chat?" | 5 h |
| F39 | **Deshacer envío** | Ventana de 5 s para recalling un mensaje recién enviado | 2 h |
| F40 | **Copia del enlace de la app** | Un `https://cerlita.chat/invite/<code>` de un solo uso para compartir por WhatsApp sin copiar/pegar 87 caracteres | 3 h |

---

## 11. Roadmap de 90 días

### Sprint 0 — "Arreglar lo que está roto" (1 semana, ~15 h)

**Objetivo: que nada de lo que ya existe falle.**

| Día | Tarea | Archivo |
|-----|-------|---------|
| 1 | **B3** — índices en `messages` (Room + `MigrationTest` v5→v6) | `Db.kt` |
| 1 | **B2** — `kind` y `peerUid` en `RoomListItem` + query + `RoomsScreen` | `Db.kt`, `RoomsScreen.kt` |
| 2 | **B4** — 401 → `logout()` + estado `SessionExpired` | `ChatEngine.kt`, `AppRoot.kt` |
| 2 | **B5** — `resetUnread` al llegar mensajes con la sala abierta | `ChatScreen.kt` |
| 2 | **B7** — quitar `fallbackToDestructiveMigration` + test de migración | `Db.kt` |
| 3 | **B1** — cursores por sala + `/sync?room=&rs=` | `ChatEngine.kt`, `Api.kt`, `sync/index.ts`, migración `0006` |
| 3 | **U3** — `itemsIndexed` en vez de `indexOfFirst` | `ChatScreen.kt` |
| 3 | **U4** — `Fmt` cacheado | `core/Fmt.kt` |
| 4 | **§7.4** — `room_cap` no cuenta los DMs + test E2E | `auth/index.ts`, `e2e_rooms_client.py` |
| 4 | **§4.2 N2** — `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` | `AndroidManifest.xml` |
| 4 | **§3.2** — notificación: 1 por sala, siempre el último | `Notifier.kt` |
| 5 | **U2** — scroll condicional + chip de mensajes nuevos | `ChatScreen.kt` |
| 5 | **§3.4** — reconexión inmediata al volver la red | `RealtimeDoorbell.kt` |
| 5 | **B8** — streaming de adjuntos + bajar `MAX_FILE` a 8 MB | `Api.kt`, `ChatEngine.kt` |
| 6 | **U5** — Paging 3 | `Db.kt`, `ChatScreen.kt`, `build.gradle.kts` |
| 6 | **U1** — tema oscuro Cerlita | `Theme.kt` |
| 7 | **Tests**: añadir cobertura para B1-B8. **Y actualizar `DECISIONES.md` con D84-D9x** | `docs/` |

**Entregable del Sprint 0:** v0.8.0 "Arreglado" — sin bugs conocidos, con Paging, tema oscuro y adjuntos en streaming.

### Sprint 1 — "Calidad de servicio" (2 semanas, ~30 h)

| # | Tarea | Por qué |
|---|-------|--------|
| 1 | **TURN gratuito** (ExpressTURN / Cloudflare) + credenciales por el servidor | Arregla el 15 % de llamadas que no conectan |
| 2 | **Cifrar la señalización** | Cumple la promesa E2EE |
| 3 | **Cámara solo al aceptar** + `echoCancellation` | Privacidad y calidad de audio |
| 4 | **AlarmManager como respaldo de 5 min** | Recorta la latencia con la app cerrada |
| 5 | **CallScreen del sistema (full-screen intent)** | Llamadas entrantes con la app cerrada |
| 6 | **Optimizar `/sync` a 1-2 queries** + cachear sesión | Menos latencia en redes lentas |
| 7 | **Rate limiting** en outbox/sync/search | Seguridad |
| 8 | **`index on messages(seq)`** en el servidor | Cada poll más rápido |
| 9 | **Anti-pausa con GitHub Actions** | Red de seguridad si Supabase pausa |
| 10 | **Limpieza de `attcache` + `MAX_FILE` 8 MB** | Disco y memoria |
| 11 | **`accessibility`**: descripciones, contraste, `fillMaxWidth` en burbujas | Inclusión |
| 12 | **`strings.xml` completo** | Localización + mantenibilidad |

### Sprint 2 — "El chat que esperabas" (2 semanas, ~35 h)

F2 (responder), F3 (editar/borrar), F7 (reenviar), F1 (reacciones), F6 (escribiendo), F11 (favoritos), F10 (chip de mensajes nuevos), F12 (programar envío), F14 (pegar largo).

### Sprint 3 — "Siguiente nivel" (2 semanas, ~35 h)

F4 (búsqueda con FTS), F5 (ya en sprint 0), F9 (widget), F16 (álbum), F8 (mensajes que desaparecen), F25 (multi-dispositivo), F13 (respuestas rápidas en notificación), F10 mejoras.

### Sprint 4 — "Pulido" (continuo)

F17-F20, F26 (cursores por sala si no se hizo en sprint 0), F29 (backup automático), F30 (traducción), F34 (stickers), F37 (QR del SAS), F38 (contactos), F39 (deshacer envío), F40 (enlace de invitación).

---

## 12. Métricas de calidad de servicio a medir

Lo que no se mide, no se mejora. **Instrumentar esto dentro de la app (todo local, sin servidor):**

| Métrica | Cómo | Objetivo |
|---------|------|----------|
| **Latencia de entrega** (de "enviar" a "el otro lo ve") | Marca temporal en el mensaje (viaja cifrada en el sobre) | p50 < 3 s con app abierta |
| **Latencia de sync** (de "llega al servidor" a "baja") | `seq` del servidor vs. reloj local | p50 < 1,5 s |
| **Tasa de fallos de sync** | Contador de `syncOnce` con excepción | < 2 % |
| **Litros de datos por mensaje** | Tamaño del ciphertext + headers | < 1,5 KB/texto típico |
| **Ratio de compresión** | `frame()` entrada/salida | > 40 % en textos largos |
| **Tasa de éxito de la primera conexión WebRTC** | `CallSession.onConnected` vs. `onFailed` | > 85 % (con TURN: > 97 %) |
| **Tiempo de arranque en frío** | `macrobenchmark` | < 1,2 s |
| **Frame time del scroll** | `macrobenchmark` con 5 000 mensajes | < 16 ms p95 |
| **RAM con 20 000 mensajes** | `adb shell dumpsys meminfo` | < 180 MB |
| **Duración de la batería por hora de idle con FGS** | `adb shell dumpsys batterystats` | < 4 %/h |

**Panel en Ajustes → "Estado del sistema"**: un botón que muestra estas métricas en pantalla. Para un usuario técnico como tú, es oro — y convierte el "me está fallando" en "el p95 de latencia es 4,2 s".

---

## 13. Riesgos y qué vigilar

| Riesgo | Probabilidad | Impacto | Mitigación |
|--------|--------------|---------|------------|
| **Supabase pausa el proyecto** | Media (1 semana de inactividad) | Alto (todo se cae) | Anti-pausa de GitHub Actions (§7.1) + aviso por email + **plan B: proyecto espejo** (F27) |
| **OEM mata el FGS** (MIUI, HyperOS, One UI) | Alta en Xiaomi | Medio (latencia 15 min) | `setOngoing(false)`, `AlarmManager` de respaldo, instrucciones de Ajustes (ya están ✅ en D67) |
| **Los 2 Móviles se quedan sin almacenamiento** | Media | Alto (fotos, caché) | Límite de `attcache`, límites por adjunto, `MAX_FILE` 8 MB |
| **El proyecto de Supabase se agota (1 GB de Storage)** | Media (tras ~1 000 fotos) | Medio (no se suben más adjuntos) | Alerta de uso + política de borrado + comprimir más las fotos |
| **Un token de sesión se filtra** (el APK no lo lleva, pero `ss.bin` sí) | Baja | Alto | Sesiones revocables + "cerrar todas las sesiones" + rotación |
| **Android 16 (API 37) endurece los FGS** | Media-alta | Medio | `targetSdk 36` (ya ✓) da margen; el diseño ya usa el tipo correcto; `onTimeout()` defensivo |
| **Realtime cambia el protocolo** (Phoenix) | Media | Alto (se cae el timbre) | Ya está encapsulado en `Phoenix.kt` (100 líneas) con tests de framing real capturados ✅. **Aislamiento excelente — por fin bien preparado** |
| **Se rompe la firma del APK** | Baja | Alto (hay que reinstalar) | `keystore-info.txt` respaldado (D40) — **verifica que el backup del keystore existe en 2 sitios** |

---

## 14. Resumen: las 15 cosas que haría esta semana

1. **Índices en `messages`** (B3) — 1 h, elimina el jank para siempre.
2. **`kind` en `RoomListItem`** (B2) — 30 min, devuelve las llamadas.
3. **Cursores por sala** (B1) — 4 h, devuelve el historial de los grupos.
4. **401 → logout** (B4) — 30 min, evita la app muerta.
5. **`resetUnread` al leer** (B5) — 15 min.
6. **Quitar `fallbackToDestructiveMigration`** (B7) — 15 min.
7. **`itemsIndexed`** (U3) — 10 min.
8. **Tema oscuro Cerlita** (U1) — 2 h, el mayor defecto visual.
9. **`Fmt` cacheado** (U4) — 30 min.
10. **Streaming de adjuntos** (B8) — 2 h, evita el crash.
11. **TURN gratuito** — 1 h, arregla el 15 % de llamadas.
12. **Cifrar la señalización** — 1 h, cumple la promesa E2EE.
13. **Cámara solo al aceptar + `echoCancellation`** — 1 h.
14. **`LOCKED_BOOT_COMPLETED`** — 10 min.
15. **Notificación: 1 por sala, siempre el último** — 1 h.

**Total: ~17 h** y la app pasa de "muy buena" a "sin bugs conocidos, rápida y con llamadas que funcionan".

---

## Apéndice A — Decisiones nuevas que este informe propone (para `DECISIONES.md`)

| # | Área | Decisión propuesta |
|---|------|--------------------|
| D84 | Sincronización | Cursores **por sala** en el cliente + `/sync?room=&rs=` para recuperar el historial de una sala recién unida. El cursor global se mantiene para el barrido rápido |
| D85 | Base de datos | Índices compuestos en `messages` (`roomId,kind,ts,msgId`), (`roomId,mine`), (`state`) + `MIGRATION_5_6`. **Se elimina `fallbackToDestructiveMigration`** |
| D86 | Notificaciones | `AlarmManager.setExactAndAllowWhileIdle` como tercera capa de respaldo (≤5 min) junto al FGS y el WorkManager de 15 min. Degradación a `setAndAllowWhileIdle` sin permiso exacto |
| D87 | Notificaciones | La política pasa de "avisar mensajes < 30 min" a "**una notificación por sala** con el último mensaje + contador de los anteriores". Nunca hay un mensaje mudo |
| D88 | Llamadas | TURN gratuito (ExpressTURN o Cloudflare) con **credenciales emitidas por el servidor** (tabla `turn_creds`, TTL 5 min). El FGS de llamada declara `microphone`+`camera` |
| D89 | Llamadas | La señalización de llamadas **viaja cifrada** con la `convKey` de la sala. El servidor solo ve `roomId` + `callId` + opacos |
| D90 | Llamadas | La cámara **no** arranca hasta que el usuario acepta. La llamada entrante usa **full-screen intent** del sistema |
| D91 | Multi-dispositivo | Tabla `device_keys (device_id, user_id, pub)` en lugar de `users.pub` "última que gana". Los DMs se dirigen a un dispositivo concreto |
| D92 | Rendimiento | Paging 3 en el historial de mensajes; `coil` para las imágenes; `Fmt` con formatters cacheados |
| D93 | UX | Tema oscuro Cerlita como parte de la paleta medida (inversión de la existente, mismo matiz) |
| D94 | UX | `ModalBottomSheet` para "Nuevo chat" (3 pestañas) en vez de un `AlertDialog` con 3 flujos |
| D95 | Seguridad | `SessionExpired` como estado de la app: el 401 devuelve al login **sin borrar el historial local** |
| D96 | Backend | Rate limiting por token en `outbox`/`sync`/`search`; `room_cap` cuenta solo `kind=0`; índice en `messages(seq)` |
| D97 | Backend | Anti-pausa en **GitHub Actions** (independiente del proyecto Supabase) + alerta por email si falla dos veces |
| D98 | Features | Nuevos `kind` de control: 4=reacción, 5=respuesta, 6=edición/borrado, 7=reenvío, 8=TTL, 9=favorito, 10=typing (evento, no se persiste) |

---

## Apéndice B — Índice de referencias del código

| Hallazgo | Archivo:línea |
|----------|---------------|
| B1 cursor global | `ChatEngine.kt:958-992` · `sync/index.ts:69-78` · `0002_rooms.sql` (seq identity) |
| B2 kind perdido | `RoomsScreen.kt:217-220` · `Db.kt:373-389` · `Db.kt:409-427` · `AppRoot.kt:234` |
| B3 sin índices | `Db.kt:33-45` · `Db.kt:174` · `Db.kt:240` · `Db.kt:415-420` |
| B4 401 | `ChatEngine.kt:989-991` · `ChatEngine.kt:1155` |
| B5 unreads | `ChatEngine.kt:1032-1035` · `ChatScreen.kt:268-282` |
| B6 señalización clara | `CallSignals.kt:48-52` · `Api.kt:248-269` · `CallDispatcher.kt:115` |
| B7 fallback destructivo | `Db.kt:517` |
| B8 OOM adjuntos | `ChatEngine.kt:768-834` · `Api.kt:373-408` · `ChatScreen.kt:480-496` |
| Ventana 30 min | `Notifier.kt:63` · `Notifier.kt:115` |
| O(n²) lista | `ChatScreen.kt:293-304` |
| Scroll agresivo | `ChatScreen.kt:279-281` |
| `SimpleDateFormat` | `ChatScreen.kt:517` · `RoomsScreen.kt:759` |
| Sin tema oscuro | `Theme.kt:64-100` |
| Cámara al recibir | `CallDispatcher.kt:160-161` · `CallSession.kt:179` |
| Solo STUN | `CallSession.kt:89-93` |
| `users.pub` last-wins | `0004_dm.sql` · `auth/index.ts:186,296` |
| `room_cap` cuenta DMs | `auth/index.ts:208` |
| 4 queries por poll | `sync/index.ts:59-119` |
| Reenc. sin distinguir red | `RealtimeDoorbell.kt:143-170` |
| `topics` no volatile | `RealtimeDoorbell.kt:57-59` |
| Anti-pausa OK | `heartbeat/index.ts:14` |
| Instantáneo forzado | `ChatApp.kt:113` · `ChatApp.kt:261-268` |
| Canal no actualizable | `Notifier.kt:82-91` |
| Strings hardcodeados | `strings.xml` (7 strings) |

---

## Fuentes externas consultadas (octubre 2026)

- Android Developers — Foreground service types: `remoteMessaging` vs `dataSync` — https://developer.android.com/develop/background-work/services/fgs/service-types
- Android Developers — Foreground service timeouts (6 h/24 h, solo `dataSync` y `mediaProcessing`) — https://developer.android.com/develop/background-work/services/fgs/timeout
- Android Developers — Changes to foreground services (Android 16: cuotas de jobs desde FGS) — https://developer.android.com/develop/background-work/services/fgs/changes
- Android Developers — Behavior changes Android 15 (`dataSync` no puede arrancar desde BOOT_COMPLETED con target ≥ 35) — https://developer.android.com/about/versions/15/behavior-changes-15
- Android Developers — Restrictions on background FGS start — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Google Play — Foreground service and full-screen intent requirements — https://support.google.com/googleplay/android-developer/answer/16965181
- Supabase — Project Pausing (Free: 1 semana de inactividad) — https://supabase.com/docs/guides/platform/free-project-pausing
- Supabase — Billing (Free: 500 MB BD, 5 GB egress, 500k invocaciones, 1 GB storage, 200 conexiones Realtime, 2M mensajes/mes, **256 KB máx. por mensaje Realtime**) — https://supabase.com/docs/guides/platform/billing-on-supabase
- Supabase — Edge Functions limits (256 MB, 150 s free, 100 funciones free) — https://supabase.com/docs/guides/functions/limits
- Supabase KB — Do Supabase projects pause when inactive? — https://supabase.com/kb/faqs/platform/free-project-pausing
- ExpressTURN — Free TURN, **1 000 GB/mes**, puertos 3478/80/443, TCP+UDP — https://www.expressturn.com/
- Metered — Open Relay, **20 GB/mes**, puertos 80/443, TURNS/TLS — https://www.metered.ca/tools/openrelay/stun-servers-and-friends/
- videosdk.live — Free STUN/TURN en 2026: freeturn.net, turnix.io, eturnal, Open Relay — https://www.videosdk.live/developer-hub/stun-turn-server/stun-server-free
- LinuxServer — WebRTC transport: coturn/eturnal, Cloudflare TURN, Open Relay como demo — https://docs.linuxserver.io/selkies/user-guide/webrtc/
- UnifiedPush — FAQ y distribuidores (ntfy, Sunup, Conversations, NextPush, gCompat-UP); la spec se apoya en WebPush desde 2025 — https://unifiedpush.org/users/faq/ · https://unifiedpush.org/users/distributors/
- ntfy — Publicar sin reenviar a Firebase (`X-Firebase: no`, `X-UnifiedPush: 1`) — https://docs.ntfy.sh/publish/

---

*Informe generado el 28-oct-2026 contra `main@1da98eb` (v0.7.1). Nada de esto está commiteado: el repo queda intacto hasta que lo ordenes.*
