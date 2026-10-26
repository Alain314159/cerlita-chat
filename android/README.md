# App Android — Chat E2EE offline-first

Cliente del chat privado para Cuba (redes lentas/intermitentes). Package:
`cu.alain.chat` (renombrable — D18/P5). **v0.2.0 — modelo rooms**.

## Qué hace (Fase 1 — MVP, modelo rooms D41-D54)

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
- **Export cifrado v2** (P4/D47): archivo `.e2ee` con historial Y claves de
  sala, cifrado con Argon2id y una contraseña independiente.
- **Notificaciones locales** al recibir mensajes con la app cerrada (D9; sin
  Firebase por D44 — ntfy queda para fase posterior, D45).

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
# Requisitos del entorno de esta máquina (ajusta rutas si cambian):
export JAVA_HOME=/home/z/jdk21                                  # JDK 21 COMPLETO (JRE no vale)
export ANDROID_HOME=/home/z/android-sdk                         # platform 37.0 + build-tools 37
cd android
./gradlew :app:testDebugUnitTest   # tests de cripto (18, JVM puro)
./gradlew :app:assembleRelease     # APK firmado + R8 (2.6 MB)
```

La firma release lee `android/keystore.properties` (gitignored) que apunta a
`../../secrets/chat-release.keystore`. Ese keystore **y su contraseña** viven en
`/home/z/my-project/secrets/` (fuera del repo, sobrevive a reinicios del entorno).
**RESPALDARLO fuera de esta máquina**: sin él no se puede actualizar la app
instalada (no hay Play Store — distribución directa). Nota: el keystore se
regeneró el 26-oct-2026 tras un reinicio del entorno (la v0.1.0 firmada antes
con otra clave requiere desinstalar antes de instalar v0.2.0 — el cambio de
protocolo igual requiere instalación limpia).

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
  db/Db.kt              Room v2: rooms, messages, outbox, pending (sin clave), meta
  engine/ChatEngine.kt  claves por sala en RAM · createRoom/joinRoom/restoreRoomKey ·
                        send/syncOnce/drainOutbox · drainPending por sala · polling
  work/Workers.kt       SyncWorker 15 min + OutboxWorker con jitter
  export/…              export .e2ee v2 (historial + claves)   notify/…  notificación
  ui/…                  Compose M3: Login, Salas (crear/unir + SAS + código),
                        Chat, Ajustes
app/src/test/java/cu/alain/chat/
  CryptoRoomsTest.kt    18 tests: base32 RFC4648, roundtrip invite, CRC, KATs
                        roomId/SAS (validados contra Python + RFC 5869), AAD, framing
```

## Pendiente (siguientes pasos)

1. Probar en los 2 móviles reales (instalar el APK release v0.2.0): registrar
   ambos usuarios, crear sala, compartir código, verificar SAS por voz
2. Medir TTFB desde la SIM real (ítem 5 de Fase 0 — D50)
3. Import del export .e2ee (restaurar en otro teléfono)
4. Notificaciones ntfy (D45, fase posterior) + cron externo anti-pausa
