#!/bin/bash
# Reconstrucción del toolchain Android tras reinicio del sandbox (worklog H14/H19).
# IMPORTANTE (H21): el sandbox MATA los procesos background entre llamadas →
# correr SIEMPRE en FOREGROUND: bash /home/z/my-project/scripts/rebuild_toolchain.sh
# Las descargas reanudan con curl -C - (reinvocar si una llamada corta por timeout).
set -ex
TC=/home/z/toolchain
mkdir -p "$TC"; cd "$TC"

# JDK 21 completo (Temurin latest GA)
if ! ls -d "$TC"/jdk-21* >/dev/null 2>&1; then
  curl -C - -sSL -o jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf jdk21.tar.gz
fi
JDK=$(ls -d "$TC"/jdk-21* | head -1)

# cmdline-tools + platform + build-tools (H14: cp -r en vez de symlink)
mkdir -p "$TC/sdk/cmdline-tools"
if [ ! -d "$TC/sdk/cmdline-tools/latest" ]; then
  curl -C - -sSL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q cmdtools.zip -d "$TC/sdk/cmdline-tools"
  mv "$TC/sdk/cmdline-tools/cmdline-tools" "$TC/sdk/cmdline-tools/latest"
fi
export JAVA_HOME="$JDK"
yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$TC/sdk/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;37.0.0" >/dev/null
if [ ! -d "$TC/sdk/platforms/android-37" ]; then cp -r "$TC/sdk/platforms/android-37.0" "$TC/sdk/platforms/android-37"; fi

cat > "$TC/env.sh" <<EOF
export JAVA_HOME=$JDK
export ANDROID_HOME=$TC/sdk
EOF

# keystore.properties → android/ (gitignored; apunta a ../../secrets/)
cp -f /home/z/my-project/secrets/keystore.properties /home/z/my-project/app/android/keystore.properties

echo TOOLCHAIN_DONE
