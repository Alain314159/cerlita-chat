#!/bin/bash
# Reconstrucción del toolchain de build Android — v2 (sin symlinks: el sandbox
# los prohibió, H14; renombrar/copiar en su lugar). Basado en android/README.md.
set -e
mkdir -p /home/z/toolchain
cd /home/z/toolchain

# ── 1. JDK 21 completo (Temurin) ─────────────────────────────────────────
if [ ! -x jdk-21/bin/javac ]; then
  echo "[1/4] Descargando JDK 21 (reanudable)..."
  curl -sSL -C - --retry 5 --retry-all-errors -o jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf jdk-21 && mkdir -p jdk-21
  tar -xzf jdk21.tar.gz -C jdk-21 --strip-components=1
  rm -f jdk21.tar.gz
fi
export JAVA_HOME=/home/z/toolchain/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
javac -version

# ── 2. Android cmdline-tools ────────────────────────────────────────────
SDK=/home/z/toolchain/sdk
mkdir -p $SDK/cmdline-tools
if [ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "[2/4] Descargando cmdline-tools..."
  curl -sSL -C - --retry 5 --retry-all-errors -o cmdtools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf cmdline-tools-tmp && unzip -q -o cmdtools.zip -d .
  rm -rf $SDK/cmdline-tools/latest
  mv cmdline-tools $SDK/cmdline-tools/latest
  rm -f cmdtools.zip
fi
export ANDROID_HOME=$SDK
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true

# ── 3. Plataforma 37 + build-tools (nombres de paquete variables) ───────
echo "[3/4] Instalando plataforma + build-tools..."
LIST=$($SDK/cmdline-tools/latest/bin/sdkmanager --list 2>/dev/null)
PLAT=""
for cand in "platforms;android-37" "platforms;android-37.0"; do
  if echo "$LIST" | grep -q "^  $cand *-"; then PLAT="$cand"; break; fi
done
BT=""
for cand in "build-tools;37.0.0" "build-tools;37-rc1" "build-tools;36.0.0"; do
  if echo "$LIST" | grep -q "^  $cand *-"; then BT="$cand"; break; fi
done
echo "PLATAFORMA: $PLAT | BUILD-TOOLS: $BT"
$SDK/cmdline-tools/latest/bin/sdkmanager --install "platform-tools" "$PLAT" "$BT" > /dev/null

# AGP con compileSdk=37 busca el dir "android-37". SIN SYMLINK (H14): copiar.
cd $SDK/platforms
if [ -d android-37.0 ] && [ ! -d android-37 ]; then
  echo "Copiando android-37.0 -> android-37 (sin symlink, H14)..."
  cp -r android-37.0 android-37
fi
ls $SDK/platforms/

# ── 4. local.properties del proyecto ────────────────────────────────────
echo "[4/4] local.properties..."
echo "sdk.dir=$SDK" > /home/z/my-project/app/android/local.properties
echo "=== TOOLCHAIN OK ==="
