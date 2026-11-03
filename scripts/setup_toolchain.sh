#!/bin/bash
# Reconstrucción del toolchain de build Android (borrado por reinicio del sandbox).
# Basado en android/README.md: JDK 21 completo + cmdline-tools + platform 37 + build-tools 37.
set -x
mkdir -p /home/z/toolchain
cd /home/z/toolchain

# ── 1. JDK 21 completo (Temurin) ─────────────────────────────────────────────
if [ ! -x jdk-21/bin/javac ]; then
  echo "[1/5] Descargando JDK 21..."
  curl -sSL --retry 3 -o jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" || exit 1
  rm -rf jdk-21 && mkdir -p jdk-21
  tar -xzf jdk21.tar.gz -C jdk-21 --strip-components=1 || exit 1
  rm -f jdk21.tar.gz
fi
export JAVA_HOME=/home/z/toolchain/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
java -version || exit 1

# ── 2. Android cmdline-tools ────────────────────────────────────────────────
SDK=/home/z/toolchain/sdk
mkdir -p $SDK/cmdline-tools
if [ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "[2/5] Descargando cmdline-tools..."
  curl -sSL --retry 3 -o cmdtools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" || exit 1
  unzip -q -o cmdtools.zip
  rm -rf $SDK/cmdline-tools/latest
  mv cmdline-tools $SDK/cmdline-tools/latest
  rm -f cmdtools.zip
fi
export ANDROID_HOME=$SDK
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1

# ── 3. Plataforma 37 + build-tools 37 (nombres de paquete variables) ───────
echo "[3/5] Listando paquetes disponibles..."
LIST=$($SDK/cmdline-tools/latest/bin/sdkmanager --list 2>/dev/null)
PLAT=""
for cand in "platforms;android-37" "platforms;android-37.0"; do
  if echo "$LIST" | grep -q "^  $cand *-"; then PLAT="$cand"; break; fi
done
echo "PLATAFORMA ELEGIDA: $PLAT"
BT=""
for cand in "build-tools;37.0.0" "build-tools;37-rc1" "build-tools;36.0.0"; do
  if echo "$LIST" | grep -q "^  $cand *-"; then BT="$cand"; break; fi
done
echo "BUILD-TOOLS ELEGIDO: $BT"
$SDK/cmdline-tools/latest/bin/sdkmanager --install "platform-tools" "$PLAT" "$BT" || exit 1

# AGP con compileSdk=37 busca el dir android-37; el paquete puede instalar android-37.0
cd $SDK/platforms
for d in android-37 android-37.0; do
  if [ -d "$d" ]; then
    [ -d android-37 ] || ln -s "$PWD/android-37.0" android-37
    [ -d android-37.0 ] || ln -s "$PWD/android-37" android-37.0
  fi
done
ls -la $SDK/platforms/

# ── 4. local.properties del proyecto ────────────────────────────────────────
echo "[4/5] local.properties..."
echo "sdk.dir=$SDK" > /home/z/my-project/app/android/local.properties
cat /home/z/my-project/app/android/local.properties

# ── 5. Calentar caches de Gradle con los tests de v0.4.1 (40/40) ───────────
echo "[5/5] Gradle: tests v0.4.1 (valida toolchain + baja dependencias)..."
cd /home/z/my-project/app/android
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
echo "=== TOOLCHAIN SETUP FINISHED (exit $?) ==="
