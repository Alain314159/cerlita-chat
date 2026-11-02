#!/usr/bin/env bash
# Reinstala el toolchain del sandbox tras un reciclado (todo bajo /home/z/my-project,
# área persistente). JDK 21 + gcloud + Android SDK (cmdline-tools 16111833,
# plataforma android-37.2 según el nuevo esquema de versiones — hallazgo H19).
set -euo pipefail
echo "== 1/3: JDK 21 (Temurin) =="
curl -sSL --retry 3 -o /tmp/jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
rm -rf /home/z/my-project/jdk21 && mkdir -p /home/z/my-project/jdk21
tar -xzf /tmp/jdk21.tar.gz -C /home/z/my-project/jdk21 --strip-components=1
echo "JDK: $(/home/z/my-project/jdk21/bin/javac --version)"

echo "== 2/3: gcloud CLI =="
curl -sSL --retry 3 -o /tmp/gcloud.tar.gz "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-x86_64.tar.gz"
rm -rf /home/z/my-project/gcloud-sdk && mkdir -p /home/z/my-project/gcloud-sdk
tar -xzf /tmp/gcloud.tar.gz -C /home/z/my-project/gcloud-sdk
echo "GCLOUD: $(/home/z/my-project/gcloud-sdk/google-cloud-sdk/bin/gcloud --version | head -1)"

echo "== 3/3: Android SDK =="
curl -sSL --retry 3 -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
rm -rf /home/z/my-project/android-sdk /tmp/ct && mkdir -p /home/z/my-project/android-sdk/cmdline-tools /tmp/ct
unzip -q /tmp/cmdtools.zip -d /tmp/ct
mv /tmp/ct/cmdline-tools /home/z/my-project/android-sdk/cmdline-tools/latest
export JAVA_HOME=/home/z/my-project/jdk21
yes | /home/z/my-project/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/z/my-project/android-sdk --licenses >/dev/null 2>&1 || true
/home/z/my-project/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/z/my-project/android-sdk "platform-tools" "platforms;android-37.2" "build-tools;37.0.0" >/tmp/sdkmanager.log 2>&1
echo "SDK plataformas: $(ls /home/z/my-project/android-sdk/platforms/)"
echo "SDK build-tools: $(ls /home/z/my-project/android-sdk/build-tools/)"
rm -f /tmp/jdk21.tar.gz /tmp/gcloud.tar.gz /tmp/cmdtools.zip
echo "== TOOLCHAIN COMPLETO =="
