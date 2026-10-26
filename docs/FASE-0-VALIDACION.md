# Fase 0 — Validación de supervivencia

Objetivo: confirmar que el proyecto PUEDE existir antes de escribir código.
El riesgo principal no es técnico: es geopolítico (OFAC).

## Resultados del checklist

| # | Prueba | Estado | Notas |
|---|--------|--------|-------|
| 1 | Crear proyecto Supabase desde IP cubana; Dashboard, Auth, Edge Functions y Storage responden | ✅ **APROBADA** | Confirmado por el usuario, 2026-10-26: "Supabase funciona" |
| 2 | Email a support@supabase.com preguntando explícitamente si el servicio está disponible para residentes en Cuba | ⏳ PENDIENTE | Recomendado aunque el ítem 1 pase: la respuesta por escrito es la única certeza jurídica. Si responden que no → Plan C |
| 3 | ~~Crear proyecto Firebase desde Cuba; registrar token FCM~~ | ❌ **DESCARTADA** | Decisión del usuario 26-oct: "No utilizaremos Firebase para la app". Sin FCM, sin google-services.json |
| 4 | ~~¿Ambos móviles tienen Google Play Services?~~ | ❌ **DESCARTADA** | Irrelevante sin FCM. Las notificaciones se resuelven con ntfy (fase posterior) — validar ntfy.sh desde Cuba cuando toque |
| 5 | Medir TTFB real hacia `*.supabase.co` (`curl -w`) desde la red habitual | ⏳ PENDIENTE | Calibra los timeouts del cliente. Comparar us-east-2 (actual) vs alternativas; migrar de región es barato con los scripts existentes |

Comando para el ítem 5:

```bash
curl -o /dev/null -s -w "DNS: %{time_namelookup}s  Conexión: %{time_connect}s  TTFB: %{time_starttransfer}s  Total: %{time_total}s\n" https://<proyecto>.supabase.co
```

## Contexto de riesgo verificado (2026-10-26)

- **OFAC activo**: el programa de sanciones a Cuba sigue vigente y fue actualizado el
  15-oct-2026 (ofac.treasury.gov). Supabase es empresa de EE. UU. y está sujeta a él.
- **El bloqueo de dominio no es teórico**: India bloqueó `*.supabase.co` completo en
  febrero de 2026 por orden gubernamental, tirando todos los proyectos del país
  (supabase.com/blog/navigating-regional-network-blocks). Ser multi-tenant significa
  compartir destino de bloqueo con miles de proyectos ajenos.
- **El Plan B original (Cloudflare) comparte el mismo riesgo**: Cloudflare es empresa de
  EE. UU. sujeta a OFAC. Cambiar de proveedor no cambia el riesgo geopolítico — es solo
  un hedge técnico.
- **Plan C real (hedge geopolítico)**: proveedor NO estadounidense — VPS europeo
  (Hetzner/OVH, ~4 €/mes) o Supabase self-hosted (es open source). La UE prohíbe a sus
  empresas acatar el embargo de EE. UU. extraterritorialmente (Reglamento UE 2271/96).

## Mitigaciones adoptadas en el diseño (independientes del proveedor)

1. Cliente aislado tras una interfaz `Transport` intercambiable — cambiar de backend es
   cambiar una implementación, no la app.
2. Exportación cifrada del historial desde el día 1.
3. Dominio propio delante del backend cuando exista, para no depender de `*.supabase.co`.
4. El protocolo completo (2 endpoints, JSON compacto) es portable a cualquier servidor en
   ~300 líneas.

## Anti-pausa: estado (26-oct-2026)

- ✅ **Edge Function `heartbeat` desplegada y verificada** (200 OK): hace un write real
  en `app_heartbeat` en cada invocación.
- ✅ **pg_cron interno activo**: `heartbeat-anti-pausa` cada 6h (minuto 17).
- ⏳ **Cron externo** (cron-job.org o similar, gratis): llamar a
  `https://wuptkddnkahmknolpxtp.supabase.co/functions/v1/heartbeat` cada 3 días.
  Es la capa principal (pg_cron no cuenta garantizado como "actividad" ante una pausa).
  Crear la cuenta cuando toque; no bloquea nada.

## Backend desplegado y actualizado (26-oct-2026, modelo rooms)

Migración 0002 aplicada (modelo rooms: `rooms`, `room_members`, `room_state`,
`messages.room_id`; sin `conversations`, sin `fcm_token`) y 4 Edge Functions verificadas
E2E con el smoke test de **37 checks** — ver [../backend/README.md](../backend/README.md).
BD limpia (0 usuarios: los 2 reales se registran desde la app con el código de invitación).
