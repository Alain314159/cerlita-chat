#!/usr/bin/env bash
# Test Lab — Cerlita Chat: los 8 tests instrumentados en 1 dispositivo virtual.
# Cuota Spark (10 virtuales + 5 físicas/día): corrida 1 = UN solo virtual con
# TODOS los tests (1 unidad). Verde → decidir si ampliar.
#
# Preflight SIN gastar cuota:
#   - re-autentica la SA (el reciclado del sandbox borra la config de gcloud)
#   - POST testMatrices con cuerpo vacío: 400 = permiso OK (matriz inválida),
#     403 = falta el rol IAM. Cero dispositivos usados.
# Uso: bash lanzar_testlab.sh
set -uo pipefail

GCLOUD=/home/z/my-project/gcloud-sdk/google-cloud-sdk/bin/gcloud
KEY=$HOME/.secrets/fcm-sa.json
APP_APK=/home/z/my-project/android/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=/home/z/my-project/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
OUT=/home/z/my-project/download/testlab
PROJECT=cerlita-chat

echo "== 1/5: precondiciones =="
for f in "$APP_APK" "$TEST_APK" "$KEY"; do
  [ -s "$f" ] || { echo "FALTA: $f"; exit 1; }
done
ls -la "$APP_APK" "$TEST_APK" | awk '{print $5, $NF}'

"$GCLOUD" auth activate-service-account --key-file="$KEY" >/dev/null 2>&1 \
  && echo "SA autenticada" || { echo "No pude autenticar la SA"; exit 1; }
"$GCLOUD" config set project "$PROJECT" >/dev/null 2>&1

echo "== 2/5: permiso de matriz SIN gastar cuota (POST vacío) =="
PERM=$(python3 /home/z/my-project/scripts/probe_permiso_matriz.py 2>&1)
echo "$PERM"
case "$PERM" in
  *OK*)
    echo ">>> Permiso de creación confirmado." ;;
  *)
    echo ">>> AÚN SIN PERMISO: falta el rol IAM en la SA."
    echo ">>> Consola: https://console.cloud.google.com/iam-admin/iam?project=cerlita-chat"
    echo ">>> Añadir a firebase-adminsdk-fbsvc el rol 'Editor' (vía por defecto de gcloud),"
    echo ">>> o el par 'Firebase Test Lab Admin' + 'Firebase Analytics Viewer' (bucket propio)."
    exit 1 ;;
esac

echo "== 3/5: catálogo de dispositivos (evidencia de acceso) =="
"$GCLOUD" firebase test android models list > "$OUT-modelos.txt" 2>&1
if [ $? -ne 0 ]; then
  echo ">>> models list falló (ver $OUT-modelos.txt) — aborto antes de gastar cuota"
  exit 1
fi
head -12 "$OUT-modelos.txt"

echo "== 4/5: lanzando corrida (Pixel 7, API 34, es, portrait) =="
mkdir -p "$OUT"
"$GCLOUD" firebase test android run --type instrumentation \
  --app="$APP_APK" --test="$TEST_APK" \
  --device model=Pixel7,version=34,locale=es,orientation=portrait \
  --timeout 15m \
  --results-dir="cerlita-$(date +%Y%m%d-%H%M)" 2>&1 | tee "$OUT/corrida.log"
MATRIZ=$(grep -oE "test-matrix-[a-z0-9]+" "$OUT/corrida.log" | head -1)

echo "== 5/5: veredicto =="
grep -E "outcome|passed|failed|Test cases|matrix" "$OUT/corrida.log" | tail -20
echo "Matriz: ${MATRIZ:-?} (detalle: gcloud firebase test android describe --matrix-id)"
