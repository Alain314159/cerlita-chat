# ENTORNO — receta de reconstrucción desde cero

> D118 · Fase V del informe 1.0 («el riesgo que no está en el código»).
> El entorno de desarrollo es PERECEDERO: el sandbox se ha reiniciado sin aviso
> al menos tres veces (H19-H26), borrando toolchain, credenciales y estado.
> La regla que nace de este documento: **todo lo que no esté en GitHub no
> existe**. Este archivo vive en el repo para que reconstruir el entorno cueste
> minutos, no sesiones.

## 0. Qué es canónico y dónde

| Cosa | Ubicación canónica | Notas |
|---|---|---|
| Código, docs, decisiones, worklog | `github.com/Alain314159/cerlita-chat` (main) | La copia local es desechable |
| Firma del APK | Secrets del repo (`ANDROID_KEYSTORE_B64` + 3 más) + copia local `~/secrets/chat-release.keystore` | La PERDIÓ una vez (v0.1.0 quedó inactualizable): si el sandbox muere, reponerla desde los secrets del repo |
| Backend (Supabase) | Proyecto `wuptkddnkahmknolpxtp` — Edge Functions en `backend/functions/`, deploy con `backend/deploy_functions.py` y el secret `SUPA_SVC_JWT` | La anon key es pública por diseño (RLS sin políticas + auth propio) |
| APKs entregables | GitHub Releases (CI los publica en cada tag) + copia local `download/` | Si el sandbox muere, los APKs siguen en Releases |
| Registro de trabajo | `docs/worklog.md` (en el repo desde D118) | Antes vivía fuera y un reinicio casi lo borra |

## 1. Toolchain Android (reconstrucción en ~10 min)

```bash
# JDK 21 (Temurin) — el del sistema es JRE sin javac (H22)
mkdir -p ~/toolchain && cd ~/toolchain
curl -LO https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
tar xf eclipse && mv jdk-21* jdk-21

# SDK: cmdline-tools + plataforma + build-tools
mkdir -p ~/toolchain/sdk/cmdline-tools
# (descargar commandlinetools-linux-*.zip de developer.android.com y descomprimir en cmdline-tools/latest)
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
# H19: AGP compileSdk=37 busca el dir "android-37" pero el sdkmanager moderno
# instala "android-37.0" → COPIA (cp -r), no symlink:
cp -r ~/toolchain/sdk/platforms/android-37.0 ~/toolchain/sdk/platforms/android-37
```

Variables que usa cada build (alias recomendado):

```bash
export JAVA_HOME=~/toolchain/jdk-21
export ANDROID_HOME=~/toolchain/sdk
```

En `android/gradle.properties` ya está fijado
`org.gradle.java.installations.paths=/home/z/toolchain/jdk-21`.

## 2. Firma del APK

`android/keystore.properties` (gitignored, apunta FUERA del repo):

```properties
storeFile=../../secrets/chat-release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

El keystore real vive en `~/secrets/chat-release.keystore`. Si se pierde,
recrearlo desde los secrets del repo (base64) — el workflow de CI hace
exactamente eso en cada run.

## 3. RAM del sandbox (H26): flags que evitan el OOM-killer

El sandbox tiene 3,9 GB sin swap. `android/gradle.properties` ya está afinado:

- `-Xmx2048m` para el daemon (compilar release funciona con esto).
- En el sandbox: añadir `-Dorg.gradle.jvmargs=-Xmx1280m` cuando se corren
  TESTS (el daemon de 2048 + el executor de tests de 2g se comen al host).
- Tests: `maxHeapSize = "2g"` (Robolectric lo necesita; con 1g el runtime
  nativo no carga).
- **Vigilar el DISCO**: el sandbox tiene ~10 GB y se llena (una sesión llegó
  al 100 % y Robolectric falló con «No space left on device» confundiéndose
  con un fallo de tests). Comprobar `df -h /` antes de culpar al código.
  Los navegadores de Playwright/Puppeteer (`~/.cache`) son 1,2 GB que se
  pueden borrar si hacen falta espacio.

## 4. Git y credenciales

```bash
git clone https://github.com/Alain314159/cerlita-chat
# credenciales (username:TOKEN — el @ del email rompía el store una vez):
git config credential.helper store
echo "https://Alain314159:<TOKEN>@github.com" > ~/.git-credentials
```

Regla de la casa: commit+push AL TERMINAR CADA BLOQUE, no al final de la
sesión. El push está protegido por GitHub (secret scanning): si un commit
lleva credenciales, lo rechaza — gracias a eso el snapshot accidente de la
sesión v0.18.0 nunca llegó a salir.

## 5. CI (GitHub Actions, repo público → gratis)

- `build`: tests → cobertura con umbral (D116) → lint con base (D117) → APK
  release FIRMADO → artefactos → Release en cada tag `v*`.
- `instrumented`: emulador ATD gestionado (Pixel 6, API 34, aosp-atd) con
  KVM — la prueba del flujo crítico (D115). Necesita el paso udev de KVM
  (receta en el propio workflow).
- Secrets necesarios: `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## 6. Comandos de siempre

```bash
cd android
./gradlew :app:testDebugUnitTest                     # suite JVM+Robolectric (132)
./gradlew :app:createDebugUnitTestCoverageReport     # cobertura (puerta: ../tools/qa/coverage_gate.py …)
./gradlew :app:lintDebug                             # lint con base (cero avisos nuevos)
./gradlew :app:assembleRelease                       # APK firmado (~19,5 MB)
./gradlew :app:pixelAtdDebugAndroidTest              # instrumentada (local: solo si hay KVM y RAM)
```

Robolectric baja android-all de Maven Central, que devuelve 429 a esta IP:
el build ya lo apunta al espejo de Google (`robolectric.dependency.repo.url`
en build.gradle.kts). Igual que Gradle con `maven-central.storage-download…`
en settings.gradle.kts.

## 7. Backend (solo si hay que tocarlo)

Edge Functions Supabase (auth, sync, outbox, files, heartbeat, turn) +
migraciones SQL en `backend/migrations/`. Deploy: `backend/deploy_functions.py`
(exige `SUPA_SVC_JWT`, la service key JWT clásica — H17: las `sb_secret_`
nuevas dan 403). Las pruebas e2e del backend corren contra PRODUCCIÓN:
cuidado con `MAX_USERS=10` (H16 — quemar usuarios de prueba exige limpiar).

## 8. Si el sandbox amanece congelado (ya pasó, tres veces)

1. `git fetch origin && git log origin/main` — ¿el repo está POR DELANTE?
   Entonces el workspace local quedó congelado en una versión vieja: hacer
   `git stash` o rama de respaldo (`workspace-vX`), resetear a `origin/main`
   y resincronizar.
2. Reconstruir toolchain (§1), firma (§2), credenciales (§4).
3. Verificar `df -h /` y `free -m` ANTES de culpar al código.
4. El worklog canónico ya está en el repo (`docs/worklog.md`, D118) — la
   historia no se pierde otra vez.
