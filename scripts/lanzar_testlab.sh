#!/usr/bin/env bash
# Test Lab — Cerlita Chat: 8 tests instrumentados en 1 dispositivo virtual.
# Estrategia de cuota (Spark: 10 virtuales + 5 físicas al día):
#   corrida 1 = UN solo dispositivo virtual con TODOS los tests (1 unidad).
#   Si sale verde, se decide si ampliar matriz (API baja + física).
# Uso: bash lanzar_testlab.sh
set -uo pipefail

GCLOUD=/home/z/my-project/gcloud-sdk/google-cloud-sdk/bin/gcloud
APP_APK=/home/z/my-project/android/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=/home/z/my-project/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
OUT=/home/z/my-project/download/testlab
PROJECT=cerlita-chat

echo "== 1/4: precondiciones =="
for f in "$APP_APK" "$TEST_APK"; do
  [ -s "$f" ] || { echo "FALTA: $f"; exit 1; }
done
ls -la "$APP_APK" "$TEST_APK"

echo "== 2/4: catálogo accesible (APIs habilitadas?) =="
"$GCLOUD" firebase test android models list 2>&1 | head -8 || {
  echo ">>> APIs de Test Lab aún inaccesibles. Habilitar:"
  echo ">>> https://console.cloud.google.com/apis/library/testing.googleapis.com?project=cerlita-chat"
  echo ">>> https://console.cloud.google.com/apis/library/toolresults.googleapis.com?project=cerlita-chat"
  exit 1
}

echo "== 3/4: lanzando corrida (Pixel 7, API 34, es, portrait) =="
mkdir -p "$OUT"
"$GCLOUD" firebase test android run --type instrumentation \
  --app="$APP_APK" --test="$TEST_APK" \
  --device model=Pixel7,version=34,locale=es,orientation=portrait \
  --timeout 15m \
  --results-dir=cerlita-$(date +%Y%m%d-%H%M) 2>&1 | tee "$OUT/corrida.log"

echo "== 4/4: veredicto =="
grep -E "outcome|passed|failed|Test cases|matrix" "$OUT/corrida.log" | tail -20
