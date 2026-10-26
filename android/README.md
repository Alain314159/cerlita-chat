# App Android — Chat E2EE offline-first

Cliente del chat privado 1-a-1 para Cuba (redes lentas/intermitentes). Package:
`cu.alain.chat` (renombrable — D18/P5). **v0.1.0**.

## Qué hace (Fase 1 — MVP)

- **E2EE real**: Argon2id (64 MiB) → HKDF → ChaCha20-Poly1305 (Tink). El servidor
  jamás ve la frase, las claves ni el plaintext. Ver `crypto/Crypto.kt`.
- **Offline-first**: escribir siempre funciona (Room + outbox persistente con
  reintentos con backoff + jitter). Leer también: los mensajes se guardan
  descifrados en Room y la UI observa por Flow.
- **Transporte HTTP** (D2): `GET /sync` incremental + `POST /outbox` idempotente.
  Polling adaptativo en foreground (5 s → 5 min) + worker periódico de 15 min.
- **Fase E2EE separada del login** (D6): el login es usuario/contraseña contra
  el servidor; la frase de cifrado NUNCA sale del teléfono.
- **Export cifrado** del historial (P4): archivo `.e2ee` con frase propia.
- **Notificaciones locales** (fallback hasta que llegue FCM — D9).

## Cómo compilar

```bash
# Requisitos del entorno de esta máquina (ajusta rutas si cambian):
export JAVA_HOME=/home/z/my-project/jdk-21.0.13+9        # JDK 21 COMPLETO (JRE no vale: falta javac)
export ANDROID_HOME=/home/z/my-project/android-sdk
export GRADLE_USER_HOME=/home/z/my-project/gradle-home

cd android
./gradlew assembleDebug     # APK de desarrollo
./gradlew assembleRelease   # APK firmado + R8 (2.6 MB)
```

La firma release lee `android/keystore.properties` (gitignored) que apunta a
`/home/z/my-project/secrets/app-release.jks`. **Respaldar ese keystore**: sin él
no se puede actualizar la app instalada (no hay Play Store — distribución directa).

## Herramientas / decisiones de build

| Pieza | Valor | Nota |
|---|---|---|
| AGP | 9.1.1 | Kotlin INTEGRADO desde AGP 9: no aplicar `kotlin-android`; KGP se fija con buildscript classpath (D37) |
| Gradle | 9.8.2 | wrapper incluido |
| Kotlin | 2.4.20 | compilador fijado por classpath |
| KSP | 2.3.12 | Room 2.8.5 |
| Compose BOM | 2026.08.00 | requiere compileSdk 37 |
| compileSdk / target / min | 37 / 36 / 24 | desugaring 2.1.5 activo |
| RAM | 3.9 GB host | `kotlin.compiler.execution.strategy=in-process` OBLIGATORIO: con daemon Kotlin aparte el OOM mata el build |
| R8 | ON desde v1 | reglas mínimas en `app/proguard-rules.pro`; mapping.txt v1 en `secrets/mappings/` |

## Estructura

```
app/src/main/java/cu/alain/chat/
  ChatApp.kt            Application + Prefs (service locator manual)
  MainActivity.kt       única Activity; enciende/apaga el polling en onStart/onStop
  core/C.kt             constantes espejo del servidor
  crypto/Crypto.kt      Uuid7 · Argon2id · HKDF · ChaCha20-Poly1305 · framing gzip
  crypto/KeyVault.kt    wrap AndroidKeyStore AES-256-GCM (P3)
  vault/SessionStore.kt ss.bin (sesión cifrada) + kx.bin (salt+check de la frase)
  net/Api.kt            OkHttp: auth/sync/outbox/heartbeat; errores → español
  db/Db.kt              Room: messages, outbox, pending (bloqueado), meta (last_seq)
  engine/ChatEngine.kt  rootKey en RAM · send/syncOnce/drainOutbox · polling
  work/Workers.kt       SyncWorker 15 min + OutboxWorker con jitter
  export/…              export .e2ee (P4)      notify/…  notificación local
  ui/…                  Compose M3: Auth (login/setup/unlock), Chats, Chat, Ajustes
```

## Flujo criptográfico (D3, D33-D35)

```
rootKey = Argon2id(frase, salt16, t=3, m=64MiB, p=1)          [RAM solo]
convKey = HKDF(rootKey, salt="lo|hi" usernames, info="conv-v1")
msgKey  = HKDF(convKey, salt=msg_id, info="msg-v1")
ct      = ChaCha20-Poly1305(msgKey, nonce, pt=[flag][texto|gzip], aad="v1|…")
```

Ambos teléfonos derivan lo mismo con solo: su frase compartida + ambos usernames.
La conversación se auto-crea en el servidor al primer mensaje (par ordenado).

## Pendiente (siguientes pasos)

1. Probar en los 2 móviles reales (instalar `download/chat-e2ee-v0.1.0-release.apk`)
2. Firebase/FCM cuando el usuario pase `google-services.json` (push instantáneo)
3. Import del export .e2ee, auto-lock por inactividad, multi-contacto
4. Cron externo anti-pausa (cron-job.org) — ver docs/FASE-0-VALIDACION.md
