# App Android — Cerlita Chat (E2EE offline-first)

Cliente del chat privado para Cuba (redes lentas/intermitentes). Package:
`cu.alain.chat` (independiente del nombre visible — D18/P5). **v0.4.0 "Cerlita
Chat"**: modelo rooms (D54) + import del respaldo (D58) + icono propio (D60) +
**sistema de perfiles E2EE con avatares (D61)**.

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
- **Notificaciones locales** al recibir mensajes con la app cerrada (D9; sin
  Firebase por D44 — ntfy queda para fase posterior, D45).
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
./gradlew :app:testDebugUnitTest   # tests JVM+Robolectric (40: cripto + import + perfiles + smoke + migración)
./gradlew :app:assembleRelease     # APK firmado + R8 (2.8 MB)
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
  db/Db.kt              Room v3: rooms, messages (kind/senderUid), outbox, pending,
                        meta, profiles, room_peer · migración REAL 2→3
  engine/ChatEngine.kt  claves por sala en RAM · createRoom/joinRoom/restoreRoomKey ·
                        send/syncOnce/drainOutbox · drainPending por sala · polling ·
                        perfiles: saveProfile + anuncios convergentes (D61)
  work/Workers.kt       SyncWorker 15 min + OutboxWorker con jitter
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
  ProfileTest.kt        11 tests: prefijo NUL+P, límites, corruptos → texto,
                        roundtrip AvatarConfig, ids futuros desconocidos
```

## Pendiente (siguientes pasos)

1. **Instalar v0.4.1 en los 2 móviles reales** (actualiza encima, misma firma).
   ⚠️ IMPORTANTE: las versiones v0.1–v0.4.0 tenían un crash al abrir (D62 — bug de
   constructores de ViewModels); si v0.3.0 «se cerraba sin abrir» era esto, ya
   está arreglado. Registrar ambos usuarios, configurar los perfiles (nombre +
   Cerdita/Koalita o foto), crear sala, compartir código, verificar SAS por voz
   y que cada uno VEA el perfil del otro
2. Medir TTFB desde la SIM real (ítem 5 de Fase 0 — D50)
3. Avatares reales: sustituir los drawables placeholder (el catálogo y el
   AvatarConfig por capas ya son agnósticos al arte — D61)
4. Notificaciones ntfy (D45, fase posterior) — el cron externo anti-pausa ya
   quedó resuelto server-side (D59: pg_cron + pg_net → heartbeat cada 6 h)

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
