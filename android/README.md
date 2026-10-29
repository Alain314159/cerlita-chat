# App Android — Cerlita Chat (E2EE offline-first)

Cliente del chat privado para Cuba (redes lentas/intermitentes). Package:
`cu.alain.chat` (independiente del nombre visible — D18/P5). **v0.6.0 "Cerlita
Chat"**: modelo rooms (D54) + import del respaldo (D58) + icono propio (D60) +
perfiles E2EE con avatares (D61) + notificaciones por timbre Realtime
(D63-D67) + **recibos ✓/✓✓, ajustes de sala y tiempo real SIN notificación
persistente (D68-D72)**.

## Qué hace (Fase 1 — MVP, modelo rooms D41-D61)

- **E2EE por sala**: cada sala nace con `roomKey` aleatoria de 32 B que NUNCA
  sale del teléfono (salvo dentro del código de invitación, D42). Cifrado
  ChaCha20-Poly1305 (Tink) con claves derivadas por HKDF-SHA256. Ver `crypto/Crypto.kt`.
- **Emparejamiento por código de invitación** (D42): A crea la sala → la app
  muestra el código (104 chars, base32+CRC32) con botones Copiar/Compartir →
  B lo pega (con o sin guiones, mayúsculas o minúsculas) y entra. El servidor
  jamás ve el código: solo el `roomId` derivado (capability 2^128).
- **SAS por voz** (D43): número de 6 dígitos idéntico en ambos teléfonos; se
  compara en persona o por llamada. Sin `sas_confirmed` al servidor.
- **Vault sellado por Keystore** (D55): las roomKeys viven en `rooms.bin`
  cifrado con clave AES-256-GCM respaldada por hardware. Sin frase E2EE que
  teclear: arranque instantáneo (mejor UX para D52).
- **Offline-first**: escribir siempre funciona (Room + outbox persistente con
  reintentos con backoff + jitter). Polling adaptativo (5 s → 5 min) + worker
  periódico de 15 min.
- **Multi-dispositivo**: el servidor lista tus salas; en un teléfono nuevo se
  ven con candado y se recuperan re-pegando el código de invitación (E2EE: las
  claves no viajan). Los mensajes que lleguen antes de la clave quedan cifrados
  en `pending` y se descifran solos al recuperarla.
- **Export cifrado v2 + IMPORT** (P4/D47/D58): archivo `.e2ee` (nombre
  `cerlita-chat-<fecha>.e2ee`) con historial Y claves de sala, cifrado con
  Argon2id y una contraseña independiente. **Ajustes → Importar respaldo** lo
  restaura completo en cualquier teléfono (o tras una limpieza): fusión no
  destructiva — no pisa nombres locales, claves solo si faltan, mensajes sin
  duplicar. Acepta respaldos de la v0.2.0 (AAD legacy).
- **Notificaciones instantáneas con la app cerrada (D63-D67; sin Firebase por D44)**:
  el servidor hace un broadcast Realtime al insertar mensajes (SOLO despierta, jamás
  transporta contenido) → el servicio foreground `remoteMessaging` del teléfono
  re-sincroniza por `/sync` (la fuente de verdad) y notifica. Un canal de notificación
  POR SALA (IMPORTANCE_HIGH) con estilo de conversación (hasta 5 burbujas con el
  nombre/avatar del par); tocar abre ese chat. Revive tras reiniciar el teléfono
  (BootReceiver) y tras caídas del WebSocket (reconexión con backoff + re-sync).
  ntfy (D45) queda degradada a Plan B futuro.
- **Perfiles E2EE** (D61): nombre + **foto subida** O **avatar por capas**
  (2 bases placeholder: Cerdita y Koalita; 5 ropas, 5 accesorios). El perfil
  viaja CIFRADO como un mensaje más de la sala (kind=1, invisible): el servidor
  jamás ve nombres ni fotos, y **no hizo falta tocar el backend**. Onboarding al
  entrar sin perfil; editable en Ajustes. La lista de chats, las burbujas y la
  cabecera muestran el nombre/avatar del par. En un teléfono nuevo el perfil se
  auto-restaura del historial de anuncios (re-sync desde 0).

## Flujo criptográfico (D41-D43, D56)

```
roomKey = CSPRNG(32 B) · roomSalt = CSPRNG(16 B)              [creador de la sala]
roomId = UUID( HKDF(roomKey, salt=roomSalt, info="room-id")[0..16] )
inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC32)        [el código ES el secreto]
SAS = 6 dígitos de HKDF(roomKey, salt=roomSalt, info="sas")   [comparación por voz]
convKey = HKDF(roomKey, salt=roomId, info="conv-v1")
msgKey  = HKDF(convKey, salt=msg_id, info="msg-v1")
ct      = ChaCha20-Poly1305(msgKey, nonce, pt=[flag][texto|gzip], aad="v1|msgId|senderId|roomId")
```

Formatos fijados por KATs cruzados Python↔Kotlin (`CryptoRoomsTest`, 18 tests)
y verificados E2E contra producción (`backend/scripts/e2e_rooms_client.py`, 22 checks).

## Cómo compilar

```bash
# Requisitos del entorno de esta máquina (ajusta rutas si cambian).
# Tras el reinicio del entorno (27-oct) el toolchain se reconstruyó aquí:
export JAVA_HOME=/home/z/toolchain/jdk-21.0.13+9                 # JDK 21 COMPLETO
export ANDROID_HOME=/home/z/toolchain/sdk                        # platform 37.0 + build-tools 36/37
cd android
./gradlew :app:testDebugUnitTest   # tests JVM+Robolectric (121: cripto, import, perfiles,
                        # phoenix, notificador, smoke, migración, dm, ice, callsignals,
                        # roominfo, nicknames, avatares, NtfyTest, PushIngestTest)
./gradlew :app:assembleRelease     # APK firmado + R8 (2,8 MB)
```

Nota (27-oct): si Maven Central devuelve `429 Too Many Requests`, `settings.gradle.kts`
ya apunta un espejo de Maven Central operado por Google como primer repositorio.

La firma release lee `android/keystore.properties` (gitignored) que apunta a
`../../secrets/chat-release.keystore`. Ese keystore **y su contraseña** viven en
`/home/z/my-project/secrets/` (fuera del repo, sobrevive a reinicios del entorno).
**RESPALDARLO fuera de esta máquina**: sin él no se puede actualizar la app
instalada (no hay Play Store — distribución directa). Nota: el keystore se
regeneró el 26-oct-2026 tras un reinicio del entorno (la v0.1.0 firmada antes
con otra clave requiere desinstalar antes de instalar v0.3.0; la v0.2.0 se
actualiza encima sin desinstalar: misma firma y mismo protocolo).

RAM del host: 3.9 GB sin swap → `gradle.properties` fijado a `-Xmx2048m`,
`in-process` y `workers.max=1` (con 2.5 GB el OOM mataba el daemon en R8).

## Estructura

```
app/src/main/java/cu/alain/chat/
  ChatApp.kt            Application + Prefs (service locator manual)
  MainActivity.kt       única Activity; enciende/apaga el polling en onStart/onStop
  core/C.kt             constantes espejo del servidor
  crypto/Crypto.kt      Uuid7 · HKDF · base32 · inviteCode · SAS · roomId ·
                        ChaCha20-Poly1305 · framing gzip (Argon2id solo para export)
  crypto/KeyVault.kt    wrap AndroidKeyStore AES-256-GCM (P3)
  vault/SessionStore.kt ss.bin (sesión) + rooms.bin (roomKeys selladas)
  net/Api.kt            OkHttp: auth (reg/in/out/room_new/room_join/rooms),
                        sync, outbox {room}, heartbeat; errores → español
  net/Ntfy.kt           canal ntfy (D104): publish del ping tras el ack
                        (X-Firebase: no), poll multi-tema con cursor since,
                        parse del ping con presupuesto anti-spam (m≤8)
  db/Db.kt              Room v3: rooms, messages (kind/senderUid), outbox, pending,
                        meta, profiles, room_peer · migración REAL 2→3
  engine/ChatEngine.kt  claves por sala en RAM · createRoom/joinRoom/restoreRoomKey ·
                        send/syncOnce/drainOutbox · drainPending por sala · polling ·
                        perfiles: saveProfile + anuncios convergentes (D61)
  work/Workers.kt       SyncWorker 15 min (fallback del timbre) + OutboxWorker
  notify/Phoenix.kt     encuadre Phoenix v1.0.0 (join/heartbeat/leave/parse) —
                        puro, testeado con frames reales de producción
  notify/RealtimeDoorbell.kt  WS a mano sobre OkHttp (~150 líneas, D65):
                        reconexión 5→60 s + jitter, heartbeat 30 s, diff de topics
  notify/DoorbellService.kt   FGS remoteMessaging (D64) + BootReceiver + helper
  notify/NtfyChannel.kt  poll ntfy (D104): loop 15 s si el WS está caído +
                        drain de la caché 12 h en el SyncWorker; estado p/ Ajustes
  profile/Profile.kt    AvatarConfig (capas) + ProfileMsg (formato cable \u0000P)
  profile/Photos.kt     foto cuadrada JPEG con degradación 128→96→64 ≤5 KB + EXIF
  export/…              export .e2ee v2 (historial + claves) + import (D58)
  ui/AvatarView.kt      catálogo + renderizador por capas (foto|avatar|inicial)
  ui/…                  Compose M3: Login, Perfil (onboarding+edición), Salas
                        (crear/unir + SAS + código), Chat, Ajustes
app/src/test/java/cu/alain/chat/
  CryptoRoomsTest.kt    18 tests: base32 RFC4648, roundtrip invite, CRC, KATs
                        roomId/SAS (validados contra Python + RFC 5869), AAD, framing
  HistoryImportTest.kt  4 tests del import: roundtrip completo (gzip + claves),
                        AAD legacy v0.2.0, frase incorrecta, archivos inválidos
                        (derivación inyectada: argon2kt es JNI, D38)
  PhoenixTest.kt        9 tests del encuadre Phoenix (frames capturados de producción)
  NotifierTest.kt       6 tests: canal por sala HIGH, MessagingStyle, política de
                        aviso (propios/viejos/sala abierta NO notifican)
  DoorbellServiceTest.kt smoke del servicio (sin sesión → foreground + stopSelf)
  ProfileTest.kt        11 tests: prefijo NUL+P, límites, corruptos → texto,
                        roundtrip AvatarConfig, ids futuros desconocidos
```

## Pendiente (siguientes pasos)

1. **Instalar v0.6.0 en los 2 móviles reales** (actualiza encima, misma firma) y
   PROBAR: tiempo real con la app abierta (sin notificación permanente — ya no
   existe por defecto), ✓✓ al bajar y ✓✓ azul al abrir la sala, long-press en
   la lista (marcar leído/vaciar/eliminar), ajustes de la sala (nombre/foto
   compartidos), atrás del teléfono en cada pantalla. Si quieren avisos
   instantáneos CON la app cerrada: Ajustes → «Avisos instantáneos con la app
   cerrada» (esa opción sí muestra la notificación permanente — la exige
   Android); en Xiaomi: Autoinicio ON + batería «Sin restricciones»
2. Medir TTFB desde la SIM real (ítem 5 de Fase 0 — D50)
3. ~~Avatares reales~~ **HECHO en v0.9.0 (D83)** — ver `docs/AVATARES.md`; siguiente paso: avatar de pareja (`av_pair.webp` ya está en el repo)
4. Si Xiaomi mata el FGS instantáneo a pesar de todo → escalar a **Mi Push
   SDK** (D64; ntfy ya no es la ruta)

## v0.6.0 — lista de bugs del usuario + sin notificación persistente (D68-D72, 28-oct-2026)

**Los 8 bugs reportados + el rediseño del timbre** (petición: «no me gusta esa
notificación persistente"):

1. **Registro**: campo «Repetir la contraseña» + ojo ver/ocultar en login y
   registro (contraseñas que no coinciden → error claro, botón deshabilitado)
2. **Botón ATRÁS del teléfono**: `BackHandler` en la navegación — atrás entre
   pantallas, solo sale de la app desde la lista de chats (antes: la cerraba
   desde cualquier pantalla)
3. **«Editar perfil» sin botón atrás**: el arrow-back ahora aparece en toda
   sub-pantalla (excepto el onboarding obligatorio de primera ejecución)
4. **Ajustes dentro de la sala** (engranaje del chat, D69): nombre y FOTO de
   sala compartidos E2EE (kind=2, latest-ts-wins — los dos teléfonos se
   actualizan solos), SAS, código de invitación con copiar/compartir,
   miembros, vaciar historial local y eliminar la sala
5. **Unirse sin nombre**: el creador SIEMPRE anuncia el nombre → quien entra
   sin escribir nada hereda el nombre real de la sala al primer sync
6. **Long-press en la lista de chats**: menú con Marcar como leído / Vaciar
   historial / Eliminar sala (D70) + badge numérico de no leídos por sala
7. **Recibos ✓/✓✓/✓✓ azul** (D68): entrega = metadato del servidor
   (`delivered_seq` → `rd` en /sync); lectura = recibo E2EE kind=3 solo con la
   sala abierta. BUG crítico corregido: el eco propio no guardaba el seq del
   servidor → sin `fillSeq` NINGÚN recibo habría funcionado jamás
8. **Tiempo real con la app abierta** (D71/D72): el timbre WebSocket vive
   DENTRO del proceso con la app abierta (sin servicio, sin notificación) +
   tope de polling 15 s como respaldo. Con la app cerrada: worker ~15 min,
   SIN notificación permanente. Toggle opcional «Avisos instantáneos con la
   app cerrada» (FGS remoteMessaging de la v0.5.0) para quien lo quiera

Validación: 64/64 tests (9 nuevos de RoomInfoMsg/ReadReceipt) + migración DB
v2→v4 validada con datos reales + E2E backend 24/24 (recibos + leave) y
smoke 37/37. APK 2,8 MB, misma firma (versionCode 7).

## v0.4.1 — fix del crash al abrir (D62, 27-oct-2026)

**Síntoma**: «la toco y se cierra sin siquiera abrir» en los móviles reales con
v0.3.0. **Causa raíz**: `AuthViewModel`/`RoomsViewModel` declaraban su constructor
con `(ChatApp)`; `AndroidViewModelFactory` (lifecycle 2.11) los instancia por
reflexión buscando exactamente `(Application)` → `NoSuchMethodException` al
componer la pantalla de login → la app moría antes de mostrar nada. Nunca se
detectó porque los tests JVM no ejercen la capa Android y el APK nunca se había
ejecutado (ni en emulador — no hay KVM en el sandbox).

**Arreglos de v0.4.1**:
- Constructores `(Application)` en ambos ViewModels + regla R8
  `-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }` (lifecycle
  2.11 no trae consumer rules — verificado en el dex final del release)
- WorkManager on-demand: `ChatApp` implementa `Configuration.Provider` y el
  inicializador por defecto se retira del manifest (`tools:node="remove"`) — sin
  dependencia del InitializationProvider, más robusto ante OEMs agresivos
- `Profile.kt` reescrito sin byte NUL literal en `PREFIX` (escape `\u0000`; antes
  git trataba el archivo como binario) + límites 40/600/6600 (antes 41/611/6611)
- `ProfileScreen`: carga del perfil asíncrona (antes `runBlocking` en composición)

**Nueva infra de pruebas (Robolectric)** — corre la app de verdad en JVM:
- `SmokeTest`: ChatApp.onCreate completo + MainActivity + pantalla Login en
  **API 24, 28, 31, 33 y 35** (5/5) — un crash de arranque revive aquí con su stack
- `MigrationTest`: crea un chat.db con el schema v2 exacto de v0.3.0 + datos,
  abre con la DB v3 (dispara `MIGRATION_2_3` + validación de Room) y corre las
  consultas nuevas (lista de chats, kind=1 invisible)

```bash
# correr todo (40/40)
./gradlew :app:testDebugUnitTest
```

## v0.5.0 — notificaciones instantáneas por timbre Realtime (D63-D67, 27-oct-2026)

**Arquitectura** (veredicto técnico del usuario tras revisar todo el repo): el push
SOLO despierta — el contenido JAMÁS viaja por él. `/sync` es la fuente de verdad.

```
Emisor                          Servidor Supabase                    Receptor (app cerrada)
  │ POST /outbox ──────────────►│                                      │
  │                             │ INSERT messages (RETURNING: nuevos) │
  │                             │ POST /realtime/v1/api/broadcast ────┤ WSS: {"event":"new-msg",
  │◄─ ack ──────────────────────│   topic: room-<roomId>,             │        "payload":{"roomId":…}}
  │                             │   payload: {roomId} (NADA más)      │ → syncOnce() → descifra
  │                             │ (reintento idempotente NO timbra)   │ → notificación por sala
```

- **Cliente a mano, sin supabase-kt (D65)**: arrastraría Ktor+serialization (~2 MB)
  y realtime-kt pide API 26+ (minSdk 24). El protocolo necesario (phx_join,
  heartbeat 30 s, broadcast, phx_leave) son ~150 líneas sobre el OkHttp existente —
  el APK solo creció ~20 KB.
- **FGS `remoteMessaging` (D64)**: sin el límite de 6 h de `dataSync` (Android 15+)
  y permitido arrancar desde BOOT_COMPLETED (allowlist) → `BootReceiver` lo revive
  al reiniciar el teléfono. `START_STICKY` + arranque en cada foreground.
- **Reconexión autosanadora**: backoff 5→60 s con jitter; tras CADA reconexión hace
  `syncOnce()` — lo timbrado durante el corte no se pierde. El worker de 15 min
  queda como malla de seguridad.
- **Notificador (D66)**: canal POR SALA (silenciar una conversación no silencia las
  demás), MessagingStyle con el nombre real del par (perfil E2EE), grupo + resumen,
  tocar abre ese chat. Política única: ajenos + ≤30 min + no la sala abierta.
- **Hallazgos del protocolo** (validados en vivo): el REST broadcast exige el topic
  SIN prefijo `realtime:` (con prefijo: 202 pero nadie lo recibe) y la anon/publishable
  key ES la autorización (la `sb_secret_` nueva da 401/403 en Realtime). Detalle y
  matriz completa: `backend/README.md` (hallazgos 10-11).

**Validación**: `PhoenixTest` (9, frames capturados de producción) +
`NotifierTest` (6, Robolectric) + `DoorbellServiceTest` (smoke) + E2E del timbre
contra producción `backend/scripts/e2e_doorbell.py` **13/13** + smoke backend 37/37.
Total app: **55/55**.

**Nota Robolectric 4.16**: las clases de test nuevas usan config ÚNICA de SDK — con
múltiples SDK la última sandbox de una clase nueva dispara
`FileSystemAlreadyExistsException` en `maybeCopyFonts` (reproducido con un test
vacío; SmokeTest no se ve afectado). La matriz multi-API de arranque la cubre
SmokeTest (24/28/31/33/35).

## v0.7.0 — «Cerlita» (icono y tema de la pareja, chats directos, adjuntos, llamadas, pestañas)

- **D73 Icono + tema Cerlita**: icono = ilustración cerdito+koala (adaptive bitmap
  + monochrome vectorial); tema rosa del mockup en Ajustes → Apariencia
  (Sistema/Claro/Oscuro/Cerlita, default Cerlita, cambia en vivo).
- **D74 Chats directos**: buscar por @usuario → chat 1-a-1 SIN código. Cripto:
  par X25519 por teléfono (privada en Keystore) + envoltura ChaCha20-Poly1305
  de la roomKey para el par; pickup por /sync (dm) + ack; idempotente por
  pareja (dm_pair UNIQUE). Grupos siguen con código de invitación.
- **D75 Llamadas**: voz y vídeo P2P (WebRTC/stream-webrtc 1.1.3), señalización
  por broadcast REST, registro local (tabla calls), pantalla de llamada estilo
  mockup (aceptar/rechazar, PIP, cronómetro), altavoz/cámara/micrófono.
  Entrantes: solo con app abierta o modo instantáneo.
- **D76 Adjuntos E2EE**: notas de voz (m4a, waveform), fotos (1600px, sin EXIF)
  y archivos ≤20 MB; cifrados con fileKey propia, subida directa a Storage
  (RLS por roomId), descarga por /files con membresía; progreso, reintento.
- **Pestañas**: Chats · Llamadas · Ajustes (sin «estados», por decisión).
- BD v5 (rooms.kind/peerUid + calls), migración real v4→v5.
- Tests 84/84 · APK 19,2 MB (WebRTC; arm64+armv7) · versionCode 8.

## v0.7.1 — «Contestó» (fix de llamadas + peticiones del uso real)
- **Llamadas arregladas (D81)**: 3 bugs — (1) candidatos ICE perdidos por llegar antes que el SDP remoto (POSTs separados sin orden): IceQueue los acumula y drena en orden + el offer/answer sale siempre antes que los ICE propios + broadcast con reintento x3; (2) fase CONNECTING al contestar («Conectando…» visible al instante; cronómetro solo con el medio conectado); (3) audio Android 12+ con setCommunicationDevice (el deprecado lo ignoraban varios OEM). Aceptar ya no le gana a la aplicación del offer; FAILED corta siempre.
- **Tema Cerlita medido al píxel (D77)**: la paleta anterior era estimación del VLM; ahora cada color se MUESTREÓ de la imagen (listas #F6D8D8, chat #E0C2C4, enviada #F6C6CC con texto ciruela OSCURO, recibida #CCBABA, coral #D87884). LocalChatPalette desacopla burbujas de acentos; claro/oscuro intactos.
- **Apodos (D78)**: tocar el título del chat (o long-press → «Renombrar contacto») pone el nombre que QUIERAS ver — local y privado. Aplican en lista, llamadas y notificaciones.
- **Notificaciones (D79)**: avatar real (foto → cerdita/koalita → inicial coral), color Cerlita, **Responder sin abrir la app** (RemoteInput, envía cifrado con reintentos) y Marcar leído.
- **Descargas (D80)**: fotos con botón «Guardar» en el visor; notas de voz y archivos a Descargas/Cerlita en UN toque (API 29+; API <29 selector SAF). Todo sin permisos nuevos.
- **Instantáneo ON por defecto (D82)**: la notificación persistente (canal MIN: sin sonido ni icono) queda activada por defecto — es la única vía de tiempo real con la app cerrada sin FCM; migración única para quien venía de v0.7.0. Arreglado: AppRoot ya no arranca el FGS esquivando el D72.
- Tests: **91/91** (4 apodos + 3 IceQueue + 1 acciones de notificación nuevos). APK 19,2 MB, misma firma (actualiza encima).

## v0.8.0 — «Sin letra pequeña» (auditoría: 8 defectos reales, todos arreglados)

Arreglo completo de la auditoría del 28-oct-2026 (leída contra el código, línea por línea):

- **El historial al unirse a una sala (B1, D87)**: el cursor global dejaba ciega la sala nueva para siempre — ni mensajes ni nombre. Modo sala en `/sync` + cursores por sala + backfill al login. E2E 26/26 contra producción.
- **Llamadas que conectan de verdad (D88)**: TURN gratis servido por `/turn` (creds en el servidor, nunca en el APK; OpenRelay free por defecto, ExpressTURN/Cloudflare al cambiar 3 env) + echoCancellation/AGC/NS. El NAT simétrico ya no mata la llamada.
- **Señalización E2EE (B6, D89)**: offer/answer/ICE/nombre cifran con la clave de la sala — el servidor solo ve topic + callId aleatorio. Compat: un v0.7.x que llama a un v0.8.0 suena; para E2EE completo, actualizar ambos.
- **Botones de llamada que desaparecían (B2)**: `kind`/`peerUid` ahora viajan en la lista de chats.
- **401 sin app muerta (B4, D85)**: sale limpio sin borrar NADA local y el login explica qué pasó.
- **Badge en vivo (B5)** + **scroll que no te arranca (U2, chip «↓ N nuevos»)** + **O(n²) → O(1) (U3)** + **fechas cacheadas (U4)**.
- **Índices en messages (B3, D86)**: fin del jank con historial largo. Migración 5→6 REAL + test. Fuera el fallback destructivo (B7): jamás borrado silencioso.
- **Notificaciones que nunca callen (D84)**: mensaje importante aunque sea viejo SIEMPRE avisa (1 por sala); el timbre revive tras actualizar la app (MY_PACKAGE_REPLACED).
- versionCode 10, APK 19,2 MB, MISMA firma (37b6d4…) → actualiza encima. **Actualizar AMBOS teléfonos** (la señalización cifrada es entre iguales). 98/98 tests.

## v0.9.0 — «Nuestra cara» (avatares de verdad, D83)

Merge de `feature/avatares-real-d83` sobre v0.8.0 (la rama nació de v0.7.1; el
merge trae además `docs/AUDITORIA-2026-10-28.md`, el análisis que motivó D84-D89):

- **Arte real por capas (D83)**: las bases dejan de ser placeholder — 8 bases
  raster (WebP con alfa, 384 px: Cerdita × rosa/melocotón/lavanda/menta y
  Koalita × gris/azul/cacao/cielo) generadas image-to-image con la ilustración
  original como guía, y 22 capas VectorDrawable (ropa, accesorios, decoración)
  en artboard 1024×1024 con el MISMO trazo (1,0 % del ancho) y la MISMA paleta
  medida por k-means (tinta #503433, nunca negro). Documentación y pipeline
  reproducible: `docs/AVATARES.md` + `tools/avatares/`.
- **Orden de capas**: decoración (DETRÁS) → ropa (DETRÁS) → base → accesorios
  (ENCIMA) — la burbuja con corazones asoma por detrás de la cabeza como en la
  ilustración y la camiseta se lee como hombros. Cada familia (pig/koala) tiene
  su rig de anclas medido (orejas, ojos, mentón, pecho): las capas se generan
  paramétricas contra él (`tools/avatares/make_layers.py`).
- **Compatibilidad total del wire**: todos los ids existentes se mantienen
  (`pig`, `koala`, `tee_*`, `dots`, `stripes`, `bow`, `flower`, `party`,
  `glasses`, `heart`) → los perfiles guardados siguen renderizando sin migrar;
  `decor` es opcional y solo se serializa si hay valor. Un cliente viejo que
  recibe un id nuevo lo ignora (degradación limpia, D61).
- **Selector con vista previa real**: los chips de texto se sustituyen por
  fichas que componen cada opción sobre el personaje elegido — se ve exactamente
  lo que va a salir antes de tocar.
- **Notificaciones**: el avatar rasterizado (Notifier) usa el mismo orden de
  capas nuevo, con las capas de la familia correcta.
- APK 19,5 MB (+0,3 MB de arte; `av_pair.webp` queda en el repo para el futuro
  avatar de pareja — el resource shrinker lo omite del APK al no usarse aún).
  versionCode 11, MISMA firma (37b6d4…) → actualiza encima. 98/98 tests
  (la compilación de la rama se verificó AQUÍ por primera vez: la nota del
  commit D83 advertía que nunca se había podido compilar).

## v0.11.0 — «Se vive» (accesorios 3D + avatar interactivo)

El usuario probó v0.10.0: las bases perfectas, los accesorios "súper mal y
horrible" — diagnóstico exacto: choque 2D-plano-con-tinta contra bases 3D
suaves, y ropa más estrecha que la panza medida.

- **Accesorios y ropa RASTER estilo 3D (D95)**: 32 WebP con sombreado suave,
  luz diagonal, especular vinilo, sombra de contacto y SIN trazo de tinta —
  el mismo lenguaje visual que las bases. Veredicto comparativo 5/5.
- **Ropa de verdad para abajo (D97)**: VESTIDO de la cerdita (escote, mangas
  globo, cinturón, bajo ondulado en A sobre la panza medida) y TRAJE del
  koala (chaqueta con solapas, camisa en V, pajarita, mangas con puño que
  siguen los brazos reales). CALZADO nuevo: botitas / sneakers por familia.
- **Más accesorios**: gorra, aretes de aro, bufanda (slot cuello), zapatos
  rojos y azules (slot pies) — 10 accesorios en 5 slots.
- **Pestañas Cerdita | Koala (D96)**: buscar un koala ya no requiere pasar
  por todas las cerditas; la ropa se filtra por familia (vestido solo
  cerdita, traje solo koala). Cerdita menta ELIMINADA y burbuja con
  corazones ELIMINADA a petición; los perfiles guardados con esos ids
  degradan limpio (menta → cerdita rosa de la misma familia).
- **El avatar está VIVO (D98)**: la vista previa respira, REACCIONA cuando
  le pones ropa (brinca feliz), SALUDA al tocarlo (brinquitos + burbuja
  "¡Hola!"), GIRA en 3D al arrastrarlo (vuelve con muelle) y da una vuelta
  completa al cambiar de personaje.
- 104/104 tests (+6 del catálogo), APK 19,0 MB versionCode 13, misma firma
  37b6d4… → actualiza encima.

## v0.10.0 — «Muévanse» (CI + pantalla completa + avatares 3D cuerpo entero)

Todo nació del uso real de v0.9.0 (los screenshots del usuario mandaron):

- **GitHub Action que compila la app (D90)**: cada push a main → tests + APK
  release FIRMADO como artefacto; cada tag v* → Release descargable. Firma en
  secrets del repo (nunca en código), misma firma 37b6d4… → actualiza encima.
- **Pantalla completa como el mockup (D91)**: la barra inferior ya no flota
  (padding doble eliminado), los botones del teléfono se OCULTAN (deslizar
  desde el borde los trae un momento) y los avatares ya no se cortan en los
  círculos (margen interno del 7% en app y notificaciones).
- **Avatares CUERPO COMPLETO en 3D (D92)**: las 8 bases son renders 3D estilo
  Pixar generados con la ilustración original de guía + pipeline v3 (chroma
  por dominancia, colorways que conservan el sombreado, margen 10%). Las 22
  capas (ropa/accesorios/decoración) re-ancladas al arte nuevo con medidas
  automáticas — la ropa ahora es una camiseta DE VERDAD sobre el torso
  (antes 'hombros' detrás). QA visual iterado hasta todo PASS (también a
  64px). La pareja 3D abrazada queda en av_pair.webp para el futuro.
- **Sesión con contador (D93)**: Ajustes muestra cuántos días le quedan a la
  sesión (rojo + aviso a ≤7 días). Los 90 días son de NUESTRO backend
  (seguridad: token robado muere solo; al expirar NO se borra nada local —
  D85). ¿Molesta? Es un número de una migración SQL, se cambia.
- **Avatar propio en los mensajes enviados (D94)**: como en el mockup, ambos
  lados del chat llevan avatar.
- APK ~19,6 MB, versionCode 12, MISMA firma → actualiza encima. 98/98 tests.

## v0.12.0 — «3D de verdad» (geometría real + SceneView, D101)

El plan D99/D100 ejecutado: pipeline Blender reproducible (tools/avatares3d)
con Cerdita y Koalita chibi por primitivas, esqueleto propio, TODA la ropa como
mallas al mismo rig y 3 animaciones (idle/wave/react); 7 GLB (uno por colorway,
materiales pre-horneados); editor 3D con SceneView/Filament (órbita, saludo,
reacción, respiración) y snapshot PixelCopy al guardar como foto E2EE. El wire
no cambió ni un byte. APK 37,7 MB (Filament 2 ABIs), versionCode 14. Detalle
completo en docs/AVATARES-3D.md y D101.

## v0.13.0 — «Preséntate» (editor presentable + look 3D en todo el app, D102)

Las capturas del usuario tras v0.12.0 destaparon dos cosas: el editor enseñaba
el muñeco DE ESPALDAS sobre fondo NEGRO (el GLB miraba a -Z por una convención
invertida en el pipeline que el fix de azimut del QA había enmascarado, y
SceneView renderiza opaco sin skybox), y el perfil de Ajustes seguía con el
composite 2D viejo.

- **Frente canónica**: `turn_around()` en el pipeline gira todo 180° (Empty
  padre keep-transform) → los 7 GLB re-exportados miran a +Z y la cámara por
  defecto de SceneView ve la CARA. `qa_shot` az 0 = frente otra vez.
- **Editor sin negro**: `isOpaque=false` (render transparente) + escenario
  Compose detrás (degradado radial del tema + sombra elíptica de suelo,
  esquinas 28dp) + `scaleToUnits=1.9` con `centerOrigin()` → muñeco centrado
  llenando ~83% del encuadre, estilo Memoji.
- **El look 3D en TODO el app**: `render_rasters.py` reemplaza el arte 2D
  (bases IA + stickers) por renders del modelo 3D real — 7 bases de estudio +
  sprites de prendas por DIFF (el sombreado real y la sombra que la prenda
  pinta en el cuerpo viajan dentro del sprite). Mismos nombres `av_*.webp` →
  cero cambios de código: perfil, chats, notificaciones y tiles del editor
  muestran el look nuevo SIN re-guardar nada.
- Verificación: facing GLB por script (ojos/nariz a +Z), determinismo del
  render (Δ=0), composite(base+sprite) vs render directo <2% px, previews VLM.

## v0.14.0 — «Aviso de amor» (notificaciones ntfy, D104)

El usuario pidió notificaciones en tiempo real con ntfy y «una app de calidad».
Se implementó el canal ntfy del doc v2 §9 (D45 adelantada) con la mejora §9.2:
**el ping lleva el sobre cifrado** — el receptor descifra y VE el mensaje sin
round-trip a Supabase, y el aviso sigue llegando con Supabase caído (el tema se
deriva de la roomKey en el móvil; el servidor NUNCA lo conoce: cero cambios de
backend). 3 capas de aviso: timbre Realtime (primario) → canal ntfy (respaldo
con sobre, poll 15 s solo si el WS está caído) → SyncWorker 15 min (ahora
drena la caché ntfy de 12 h ANTES del /sync: notifica sin abrir la app).

- `Crypto.ntfyTopic`: HKDF(roomKey, salt=roomId, "ntfy-topic-v1") a 16 B →
  base32 minúsculas con prefijo c (27 chars, 128 bits, KAT cruzado Python↔Kotlin).
- Emisor: ping best-effort tras el ack del outbox (orden canónico ack→publish),
  ≤8 filas por lote; si el sobre no cabe (>3400 chars) → despertador puro.
  `X-Firebase: no` en todo publish (ntfy.sh reenviaría a FCM por defecto).
- Receptor: `ingestFromPush` (mismo Mutex y camino del /sync: dedup por PK);
  el /sync de confirmación rellena seq (fillSeq ahora aplica también a ajenos,
  y seq 0 se guarda NULL — antes un eco ajeno jamás se rellenaba).
- Ajustes: estado del canal + servidor de avisos editable (self-host si ETECSA
  bloquea ntfy.sh) + botón «Guardar y probar» con latencia medida.
- Seguridad (e2e 32/32 en vivo): AEAD tira inyecciones, msgId tira replays,
  eco propio = exists. Hallazgo H25: bodies >4 KB se vuelven ATTACHMENT con
  URL pública en ntfy.sh — el umbral 3400 lo evita de raíz.
- Verificación: e2e_ntfy.py 32/32 (producción + ntfy.sh REAL), NtfyTest 7,
  PushIngestTest 4 (Robolectric) → 121/121. APK 37,5 MB, misma firma 37b6d4…
  (versionCode 16). Cero migraciones de BD (cursor en `meta`).

Entorno (H21 reconfirmado): el sandbox MATA los procesos background entre
llamadas — el toolchain se reconstruye SIEMPRE en FOREGROUND
(`bash /home/z/my-project/scripts/rebuild_toolchain.sh`, descargas con
`curl -C -`). El AndroidKeyStore no existe en Robolectric: KeyVault expone
`sealFn/unsealFn` inyectables solo para tests (patrón H5), restauradas en
@AfterClass.
