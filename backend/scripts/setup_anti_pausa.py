#!/usr/bin/env python3
"""Cron externo ANTI-PAUSA por vía server-side (sin depender de cron-job.org).

Problema: el plan Free de Supabase pausa el proyecto tras ~7 días sin
"actividad"; la actividad que cuentan es el tráfico del API gateway
(REST/Edge Functions), NO los writes internos de pg_cron. El job pg_cron
existente solo tocaba la tabla app_heartbeat → no cuenta como actividad.

Solución: pg_cron + pg_net llaman por HTTPS a la Edge Function `heartbeat`
cada 6 h. Esa invocación SÍ pasa por el gateway → cuenta como actividad y
además mantiene la tabla app_heartbeat al día. Todo dentro del propio
proyecto: cero servicios externos, cero credenciales nuevas (la anon key es
pública por diseño).

Pasos:
  1. Diagnóstico: pg_net disponible? qué jobs hay?
  2. CREATE EXTENSION pg_net (si no está)
  3. Desagenda el job viejo "anti-pausa" (solo UPDATE) si existe
  4. Agenda el nuevo job HTTP (0 */6 * * * = cada 6 h en punto, UTC)
  5. Dispara UNA invocación manual para verificar el circuito completo
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402


def q(sql: str):
    st, payload = api.call("POST", f"/v1/projects/{api.ref()}/database/query", {"query": sql})
    if st not in (200, 201):
        print(f"SQL FALLO [{st}]: {payload}")
        sys.exit(1)
    return payload


def show(title, rows):
    print(f"\n== {title} ==")
    if not rows:
        print("  (sin filas)")
    for r in rows:
        print("  " + json.dumps(r, ensure_ascii=False, default=str))


def main():
    # 1. diagnóstico
    show("extensiones disponibles", q(
        "SELECT name, default_version, installed_version "
        "FROM pg_available_extensions WHERE name IN ('pg_net','pg_cron')"
    ))
    show("jobs pg_cron actuales", q(
        "SELECT jobid, jobname, schedule, active, command FROM cron.job ORDER BY jobid"
    ))

    # 2. instalar pg_net (pg_cron ya está: lo usa la migración 0001)
    q("CREATE EXTENSION IF NOT EXISTS pg_net")
    show("pg_net instalada", q(
        "SELECT extname, extversion FROM pg_extension WHERE extname IN ('pg_net','pg_cron')"
    ))

    # 3. desagenda jobs viejos de anti-pausa (mantener solo el nuevo)
    #    (0001 lo creó como 'heartbeat-anti-pausa': solo UPDATE, no cuenta como
    #    actividad del gateway → sustituido por la versión HTTP)
    show("jobs desagendados", q(
        "SELECT cron.unschedule(jobid) AS removed, jobname FROM cron.job "
        "WHERE jobname IN ('heartbeat-anti-pausa','anti-pausa','heartbeat-db',"
        "'heartbeat','anti-pausa-http')"
    ))

    # 4. job nuevo: latido HTTP cada 6 h (UTC). La anon key es pública (D26/C.kt).
    anon = __import__("os").environ.get("SUPA_ANON_KEY")
    if not anon:
        # api.load_env solo carga SUPA_TOKEN/REF/URL; la anon está en el mismo archivo
        for line in open(api.DEFAULT_ENV_FILE, encoding="utf-8"):
            line = line.strip()
            if line.startswith("export SUPA_ANON_KEY="):
                anon = line.split("=", 1)[1].strip().strip('"').strip("'")
    assert anon, "SUPA_ANON_KEY no encontrada"

    url = f"https://{api.ref()}.supabase.co/functions/v1/heartbeat"
    headers = json.dumps({"apikey": anon})
    cmd = (
        "SELECT net.http_get("
        f"url := '{url}', "
        f"headers := '{headers}'::jsonb, "
        "timeout_milliseconds := 30000)"
    )
    show("job agendado", q(
        "SELECT cron.schedule('anti-pausa-http', '0 */6 * * *', "
        + "$$" + cmd + "$$" + ") AS jobid"
    ))

    # 5. verificación: disparar UNA llamada manual y leer el resultado diferido
    q(cmd)
    import time
    time.sleep(3)
    show("última respuesta HTTP del latido", q(
        "SELECT status_code, error_msg, created "
        "FROM net._http_response ORDER BY created DESC LIMIT 1"
    ))
    show("heartbeat en la tabla", q("SELECT last_beat FROM app_heartbeat WHERE id = 1"))
    print("\nListo: latido HTTP server-side cada 6 h.")


if __name__ == "__main__":
    main()
