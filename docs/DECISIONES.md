# Log de decisiones

Última actualización: 2026-10-26 (fusión: cliente Android v1 + backend modelo rooms del
Documento v2 revisado, con sus 3 correcciones obligatorias)

Orígenes: **Plan v1.0** (documento original del usuario) · **Usuario 26-oct** (indicación
directa) · **Análisis 26-oct** (correcciones del análisis de viabilidad, presentadas sin
objeción; se ratifican al escribir código) · **Cliente 26-oct** (implementación Android v1)
· **Doc v2 revisado 26-oct** (especificación principal: modelo rooms, código de invitación,
SAS cliente-cliente, eventos/estado, ntfy pospuesta; el usuario validó el documento y
ordenó 3 correcciones — D42, D43, D46 — antes de implementar).

## Decididas

| # | Área | Decisión | Origen |
|---|------|----------|--------|
| D1 | Backend | Supabase (Postgres + Edge Functions) — Plan A | Usuario 26-oct: "Supabase funciona" (validado desde Cuba) |
| D2 | Transporte | HTTP polling `GET /sync` + `POST /outbox`; NO WebSocket ni Supabase Realtime | Plan v1.0 |
| D3 | Cifrado | PSK: Argon2id (t=3, m=64 MiB, p=1) → HKDF-SHA256 → ChaCha20-Poly1305 | Plan v1.0 |
| D4 | Lib AEAD | Tink 1.24.x (`tink-crypto/tink-java`, activo oct-2026, soporte Android API 24+). Alternativa: libsodium | Plan v1.0 + Análisis 26-oct (verificación de vida del proyecto) |
| D5 | Lib KDF | argon2kt 1.6.0 (puro JVM, 0 bytes de .so) | Plan v1.0 |
| D6 | Auth | Propia: username + password. La frase E2EE es INDEPENDIENTE del password | Plan v1.0 |
| D7 | DB local | Room 2.8.5 (3.0 es alpha KMP — no usar en v1) | Plan v1.0 + Análisis 26-oct |
| D8 | Background | WorkManager 2.12.x, backoff exponencial + jitter completo | Plan v1.0 |
| D9 | Notificaciones | ~~FCM data-message~~ **SUSTITUIDA por D44/D45**: sin Firebase; ntfy (fase posterior) con tema derivado de roomKey | Usuario 26-oct: "No utilizaremos Firebase para la app" |
| D10 | Compresión | Antes del AEAD, nunca después; gzip solo si plaintext > 512 B; lotes como un solo stream | Plan v1.0 |
| D11 | Idempotencia | `msg_id` = UUIDv7 generado en cliente + `ON CONFLICT DO NOTHING` en servidor | Plan v1.0 |
| D12 | AAD | `msg_id + sender_id + conversation_id` (el plan original solo usaba msg_id) | Análisis 26-oct |
| D13 | Bot (Fase 2) | El bot conoce SOLO `commandKey`, NUNCA rootKey/roomKey. Se corrige la contradicción del plan original | Análisis 26-oct |
| D14 | Claves | `convKey = HKDF(roomKey, salt=roomId, info="conv-v1")` — aislamiento por sala; `msgKey = HKDF(convKey, salt=msg_id, info="msg-v1")` | Análisis 26-oct, actualizada por Doc v2 (modelo rooms) |
| D15 | Polling | Adaptive: 5 s tras actividad → backoff hasta 2-5 min en reposo → reset con envío (o ping ntfy cuando exista) | Análisis 26-oct |
| D16 | UUIDv7 | Implementación propia en cliente (~20 líneas, RFC 9562) — Kotlin 2.4 solo trae UUIDv4 | Análisis 26-oct |
| D17 | Repo | GitHub **privado**; regla permanente: commit + push en cada sesión de trabajo | Usuario 26-oct |
| D18 | Nombre | **Cerlita Chat** (26-oct-2026). Icono propio: burbuja crema + candado con cerradura de corazón sobre violeta; repo renombrado a `cerlita-chat`; el applicationId sigue `cu.alain.chat` (P5) | Usuario 26-oct: "La app se llamará cerlita chat" |
| D19 | Código | ~~Sin escribir código hasta nueva orden~~ Backend y cliente v1 escritos con autorización expresa del usuario | Usuario 26-oct |
| D20 | Registro | Protegido por código de invitación (secret REG_INVITE_CODE) + tope duro de 10 usuarios | Análisis 26-oct (evita registros de extraños que conozcan la URL) |
| D21 | Password servidor | Argon2id m=32MiB t=3 p=1 calculado en la Edge Function (noble, embebido en bundle), formato PHC propio | Implementación 26-oct |
| D22 | Sesiones | Token propio 32B, se guarda SHA-256(tok), expira a 90 días, revocable. verify_jwt=false en las 4 funciones | Implementación 26-oct |
| D23 | Conversación | ~~Auto-creada con par ordenado~~ **SUSTITUIDA por D41**: modelo rooms, la sala la crea explícitamente el cliente | Implementación 26-oct, reemplazada por Doc v2 |
| D24 | Lockout login | 5 fallos → 30s × 2^(n-5), máx 1h | Implementación 26-oct |
| D25 | Deploy | Únicamente vía endpoint multipart (el legacy de body base64 produce BOOT_ERROR). noble empotrado con bun; postgres externo npm:postgres@3.4.9. Ver backend/README «Hallazgos» | Implementación 26-oct |
| D26 | gzip | El servidor gzip si > 1 KB; Cloudflare comprime en tránsito según Accept-Encoding. OkHttp lo gestiona solo. Doble capa, sin coste | Implementación 26-oct |
| D33 | convKey salt | ~~Par ORDENADO de usernames~~ **ACTUALIZADA por D41/D42** (modelo rooms): salt = `roomId`, clave madre = `roomKey` del código de invitación. La app Android v1 (frase compartida + par de usernames) se adapta al modelo rooms — ver D54 | Cliente 26-oct, actualizada por Doc v2 |
| D34 | AAD formato | `"v1\|<msgId>\|<senderUid>\|<roomId>"` UTF-8 (D12 con separadores anti-ambigüedad; en modelo rooms el cuarto campo es roomId, antes lo\|hi) | Cliente 26-oct, actualizada por Doc v2 |
| D35 | Framing plaintext | `[flag 1B][payload]`: flag 0 = UTF-8 crudo, 1 = gzip (solo si texto > 512 B). Compresión SIEMPRE antes del AEAD | Cliente 26-oct |
| D36 | Verificación frase | check = HMAC-SHA256(HKDF(rootKey, info="e2ee-check"), constante) — aplica a la app v1 con frase compartida. En el modelo rooms (D41+) el emparejamiento es por código de invitación y la verificación es el SAS (D43); la frase desaparece del flujo | Cliente 26-oct, alcance limitado por Doc v2 |
| D37 | Toolchain Android | AGP 9.1.1 (Kotlin INTEGRADO: no se aplica kotlin-android; KGP 2.4.20 fijado por buildscript classpath) · Gradle 9.8.2 · KSP 2.3.12 · Compose BOM 2026.08.00 · compileSdk 37 · targetSdk 36 · minSdk 24 · desugaring 2.1.5 · R8 ON desde v1 | Verificado 26-oct |
| D38 | argon2kt | CORRECCIÓN de D5: 1.6.0 usa JNI con .so embebidos (4 ABIs, ~350 KB comprimidos), NO es "JVM puro". Funciona igual: 0 configuración, carga fuera del hilo principal | Verificado 26-oct |
| D39 | sync payload | Filas extendidas: `[msg_id, room_id, sender_id, seq, nonce_b64, ct_b64, sender_uname, ts_ms]` — sender_uname pasa a ser SOLO para la UI (en modelo rooms ya no deriva convKey); ts_ms = hora de muestra. Smoke test 37/37 | Servidor 26-oct, actualizada por Doc v2 |
| D40 | Firma APK | Keystore PKCS12 en secrets/app-release.jks (FUERA del repo; contraseñas en secrets/keystore-info.txt — RESPALDARLO). Release 2.6 MB con R8+shrinkResources. mapping.txt v1 en secrets/mappings/ | Cliente 26-oct |
| D41 | Salas | **Modelo rooms**: `room_id` lo genera el CLIENTE (`HKDF(roomKey, salt=roomSalt, info="room-id")[0..16]` como UUID); conocer el room_id (2^128) es la capability de membresía. Tablas `rooms` + `room_members`; tope 8 salas/usuario, 6 miembros/sala | Doc v2 + Implementación 26-oct (migración 0002) |
| D42 | Invitación | **El código de invitación ES el secreto**: `inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC)` — la roomKey viaja EN CLARO DENTRO del código, sin cifrado adicional. JAMÁS se transmite al servidor; se comparte por canal controlado (en persona, WhatsApp, papel). B decodifica y deriva room_id + claves sin hablar con el servidor | Doc v2 [CORRECCIÓN: elimina la auto-contradicción del cifrado del código] |
| D43 | SAS | Número de seguridad de 6 dígitos = `HKDF(roomKey, salt=roomSalt, info="sas")[0..4]`. Se compara SOLO entre los dos clientes, por voz o en persona. El servidor NUNCA recibe `sas_confirmed` — la verificación de identidad es cliente-cliente, no cliente-servidor | Doc v2 [CORRECCIÓN: eliminado sas_confirmed del payload] |
| D44 | Firebase | **NO se usa Firebase para la app** (ni FCM ni google-services.json ni service account). Columna `sessions.fcm_token` eliminada. La Edge Function `push` prevista queda cancelada | Usuario 26-oct (orden directa) |
| D45 | Notificaciones (futuras) | **DEGRADADA a Plan B (27-oct, D63)**: el timbre Realtime resuelve el caso principal sin depender del teléfono del emisor ni de un tercero. ntfy queda como sustituto SOLO si algún día se abandona Supabase. Tema = derivado de roomKey (`HKDF(roomKey, info="ntfy-topic")`), publicado por el **móvil emisor** tras el ack — el servidor NUNCA conoce el tema. Límites ntfy.sh verificados: 12h caché, 250 msg/día, 60 req/5s, 200 MB/día egress | Doc v2 + revisión 27-oct | Tema = derivado de roomKey (`HKDF(roomKey, info="ntfy-topic")`), publicado por el **móvil emisor** tras el ack — el servidor NUNCA conoce el tema y las notificaciones sobreviven a caídas de Supabase. Límites ntfy.sh verificados: 12h caché, 250 msg/día, 60 req/5s, 200 MB/día egress. Fallback: polling. Si ETECSA bloquea ntfy.sh → notificaciones "al abrir la app" (aceptable, no rompe el chat) o self-host en VPS europeo | Doc v2 + Usuario 26-oct ("las notificaciones las dejaremos para después") |
| D46 | Rotación roomKey | Cuando exista ntfy: suscripción DUAL a tema viejo + nuevo durante 24-48h y publicar en AMBOS durante la transición (el receptor aún suscrito al viejo no puede perder el ping). Requiere confirmación de ambos antes de retirar el tema viejo | Doc v2 [CORRECCIÓN: transición definida] |
| D47 | Export | El export de emergencia (P4) se cifra con contraseña INDEPENDIENTE (ni la frase E2EE ni el password de login). Contiene historial + roomKeys; nada en claro. La roomKey jamás se exporta sin cifrar | Doc v2 [CORRECCIÓN del "botón de emergencia"] |
| D48 | Eventos vs estado | Mensajes = eventos append-only; playhead/presencia/"en línea" = estado OVERWRITE (`room_state`, PK room+usuario+clave). Ahorra ancho de banda y elimina duplicados | Doc v2 |
| D49 | Bot (Fase 2) | Refinamiento de D13: el bot opera en una SALA DE COMANDOS separada (`commandKey = HKDF(roomKey, salt=roomSalt, info="commands")`); la sala de conversación jamás toca al bot. HKDF unidireccional: comprometer la función del bot no compromete la conversación | Doc v2 |
| D50 | Región | Proyecto en **us-east-2**. Medir TTFB desde la SIM real (ítem 5 de Fase 0) antes de asumir; migrar de región es barato con los scripts existentes (recrear proyecto + re-ejecutar migraciones + redeploy ~15 min) | Doc v2 [a validar] + estado actual del proyecto |
| D51 | Alcance V1 | Sin media ni "escribiendo..." (una foto a 1 KB/s = 2-4 min; el indicador no aporta con 500 ms de RTT y gasta bytes). Voz en v3: preferir chunks Opus por HTTPS antes que WebRTC (evita 10-20 KB de handshake) — decidir antes de v3 | Doc v2 |
| D52 | Onboarding | La primera ejecución: < 60 segundos y < 5 toques. Invertir en onboarding antes que en features — el riesgo #1 es humano (que ella no instale/teclee/espere), no técnico | Doc v2 |
| D53 | Oracle Cloud | Free Tier DESCARTADO como Plan C: Oracle recupera VMs idle (CPU < 20% durante 7 días; un bot al 2% lo borran) y pide tarjeta + teléfono. Plan C real = VPS europeo (Hetzner/OVH) | Doc v2 |
| D54 | Migración app → rooms | **COMPLETADA (26-oct, v0.2.0)**: la app usa emparejamiento por código de invitación (base32, D42), SAS por voz (D43), outbox `{"room": room_id}`, vault con roomKey sellado por Keystore y AAD con roomId. 18/18 tests de cripto + E2E del protocolo cliente 22/22 contra producción (`backend/scripts/e2e_rooms_client.py`) | Fusión 26-oct |
| D55 | Vault sin frase | La frase E2EE DESAPARECE del flujo (rol histórico de D36): las roomKeys se guardan en `rooms.bin` sellado por la clave hardware del Keystore (P3) y se recargan a RAM al arrancar — desbloqueo instantáneo (mejor D52). Las pantallas Setup/Unlock y el botón "Bloquear" se eliminan: en reposo protegen el cifrado de disco de Android + el bloqueo de pantalla del sistema. El export sigue con contraseña independiente (D47) | Implementación D54 |
| D56 | Detalles de formato rooms | `convKey = HKDF(roomKey, salt=roomId_string, info="conv-v1")` · `AAD = "v1|msgId|senderId|roomId"` (pipe, minúsculas) · `inviteCode = base32("V1"‖salt‖key‖CRC32)` sin padding, grupos de 5 con guion (104 chars) · `SAS = HKDF(key,salt,"sas")[0..4]` UInt-BE mod 10⁶ · roomId = HKDF(...,"room-id")[0..16] como UUID hex (sin bits de versión). Fijado por KATs cruzados Python↔Kotlin | Implementación D54 |
| D57 | Validación del cliente | Doble verificación: (1) tests JVM `CryptoRoomsTest` (18) con vectores conocidos calculados con una implementación Python independiente verificada contra RFC 5869; (2) E2E `e2e_rooms_client.py` (22 checks) que replica el protocolo exacto de la app contra producción y limpia la BD. Encontró y corrigió el bug de `sas6` con Int con signo (SAS negativo cuando el byte alto ≥ 0x80) | Implementación D54 |
| D58 | Import del export | **Ajustes → Copia de seguridad → Importar respaldo** (v0.3.0): lee el .e2ee con SAF, descifra (misma derivación compartida `HistoryExport.exportKey` — una sola fuente de verdad) y restaura salas + claves (vault) + historial. Fusión no destructiva: no pisa nombres locales, claves solo si faltan, mensajes INSERT OR IGNORE; `sending`→`failed` (el outbox no viaja). Acepta el AAD legacy de v0.2.0. Testeado JVM con derivación inyectada (argon2kt es JNI — D38): 4 tests del contrato de formato | Implementación v0.3.0 |
| D59 | Anti-pausa | **Server-side**: pg_cron + pg_net llaman por HTTPS a la Edge Function `heartbeat` cada 6 h (`backend/scripts/setup_anti_pausa.py`). La invocación pasa por el gateway → cuenta como actividad REAL del proyecto (los writes internos de pg_cron NO cuentan). Sustituye al plan de cron-job.org: cero dependencias externas. Job único `anti-pausa-http` | Implementación 26-oct |
| D60 | Icono/branding | Adaptive icon (fondo violeta #2A1E4F, burbuja crema #F2E8D5, candado con cerradura de corazón #F08CA0) + variante monocroma para iconos temáticos + PNGs legacy regenerados (API 24-25 no usan adaptive: clave en Cuba). El AAD del export pasa a `cerlita-chat-export-v2` (import acepta también el legacy) | Implementación v0.3.0 |
| D61 | Perfiles E2EE | **El perfil (nombre + foto/avatar) viaja como un mensaje cifrado MÁS de la sala** (kind=1, invisible en el chat; plaintext = `\u0000P`+JSON `{n,a,p,t}`): el servidor NUNCA ve nombres ni fotos — cero cambios de backend. Foto: JPEG cuadrado con degradación adaptativa 128→96→64 px hasta ≤5 KB (presupuesto del ct de 8 KB). **Anuncios convergentes**: al crear/unirse/recuperar sala + al detectar un par NUEVO en sync (crítico: con seq global, los mensajes previos al join son invisibles para quien ya tenía last_seq avanzado) + al editar el perfil (broadcast a todas las salas). El eco propio restaura el perfil en un teléfono nuevo desde el historial (re-sync desde 0). Avatar por capas `AvatarConfig` JSON (`base`/`clothes`/`acc` por slot) con **2 bases placeholder (Cerdita y Koalita), 5 ropas y 5 accesorios**; los ids desconocidos se ignoran → listo para avatares creados por usuarios. DB v3 con migración REAL 2→3 (kind+senderUid en messages, tablas profiles y room_peer; ya hay teléfonos reales). El export excluye kind=1 (se auto-restaura del historial). 11 tests JVM nuevos (33/33 total) | Implementación v0.4.0 |
| D62 | Crash al abrir (v0.1–v0.3) | **Causa raíz**: los ViewModels (`AuthViewModel`, `RoomsViewModel`) declaraban constructor `(ChatApp)` — `AndroidViewModelFactory` instancia por reflexión buscando `(Application)` → `NoSuchMethodException` al componer la pantalla de login → la app moría al abrir en CUALQUIER teléfono (jamás detectado: los tests JVM no ejercen la capa Android y la app nunca se había ejecutado en ningún lado). **Fix v0.4.1**: constructores `(Application)` + regla R8 `-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }` (lifecycle 2.11 no trae consumer rules). **Además**: WorkManager pasa a on-demand (`ChatApp` implementa `Configuration.Provider`, inicializador por defecto retirado del manifest — más robusto ante OEMs agresivos; verificado en el dex final: R8 devirtualizó e inlinó la cadena), `Profile.kt` reescrito sin byte NUL literal (git lo trataba como binario; escape `\u0000` + límites 40/600/6600), carga del perfil en `ProfileScreen` asíncrona (antes `runBlocking` en composición). **Nueva infra de pruebas**: Robolectric — `SmokeTest` arranca ChatApp+MainActivity+Login en API 24/28/31/33/35 (5/5) y `MigrationTest` reproduce la actualización real v0.3.0→v0.4.x con datos (schema v2 + migración + consultas nuevas). 40/40 tests | Post-mortem 27-oct |

| D63 | Notificaciones: timbre Realtime | **Supabase Realtime Broadcast como "timbre" + /sync como fuente de verdad** (veredicto técnico del usuario, 27-oct): el push SOLO despierta la app — NUNCA transporta contenido. El Edge Function `outbox`, tras insertar mensajes nuevos, hace un REST broadcast al canal `room-<roomId>` con payload `{roomId}` (hallazgo 10: topic SIN prefijo `realtime:`; hallazgo 11: la publishable/anon key ES la autorización del broadcast en canales no privados — la `sb_secret_` nueva da 401/403). El canal no es privado a propósito: el nombre ES la capability (roomId = HKDF(roomKey), 128 bits inadivinables); un atacante con la anon key solo vería "hubo actividad", nunca contenido. FCM prohibido (D44). El cliente al recibir el timbre → `syncOnce()` (mutex: timbre/polling/worker/UI serializados). Reintento idempotente del mismo msg_id NO vuelve a timbrar (insert RETURNING da solo filas nuevas). Timbre best-effort: si falla, el worker de 15 min alcanza. E2E contra producción: `backend/scripts/e2e_doorbell.py` 13/13 | Veredicto usuario 27-oct + validación en vivo |
| D64 | Servicio foreground `remoteMessaging` | El WebSocket vive en un FGS tipo **`remoteMessaging`** (no `dataSync`): sin el límite de 6 h que Android 15+ impone a dataSync, permiso `FOREGROUND_SERVICE_REMOTE_MESSAGING` de nivel normal, y está en `FGS_BOOT_COMPLETED_ALLOWLIST` → el `BootReceiver` lo revive tras reiniciar el teléfono (dataSync tiene prohibido arrancar desde BOOT en 15+). `START_STICKY` + arranque en cada foreground de la Activity. Anti-asesinato OEM: exención de optimización de batería pedida en la app (D67) + ajustes manuales Xiaomi (Autoinicio ON, batería Sin restricciones) y Samsung (fuera de Sleeping apps). Ruta de escalada si MIUI mata el FGS igual: **Mi Push SDK** (no ntfy) | Veredicto usuario 27-oct |
| D65 | Cliente Phoenix a mano | WebSocket del timbre **escrito a mano sobre OkHttp** (~150 líneas, `notify/Phoenix.kt` + `notify/RealtimeDoorbell.kt`), NO supabase-kt: arrastra Ktor+kotlinx-serialization (~2 MB contra APK de 2,8 MB) y realtime-kt exige API 26+ (minSdk 24). Protocolo mínimo: phx_join con config de broadcast, heartbeat phoenix cada 30 s (el servidor corta a los mudos ~75 s), OkHttp pingInterval 25 s detecta sockets muertos, reconexión con backoff 5→60 s + jitter ±25 %, diff de topics sin cerrar conexión al cambiar las salas. **Tras cada reconexión → syncOnce** (lo timbrado durante el corte no se pierde). Framing testeado con frames REALES capturados de producción (`PhoenixTest`, 9 tests) | Implementación v0.5.0 |
| D66 | Notificador por sala | **Un canal de notificación por sala** (`room_<roomId[:8]>`, IMPORTANCE_HIGH: sonido + heads-up) con el nombre del par (perfil E2EE) — el usuario silencia cada conversación desde Ajustes del sistema. `MessagingStyle` (hasta 5 burbujas de contexto, Person con el nombre real), grupo `messages` + resumen, `setAutoCancel`, tocar abre ESA sala (extra `room` → `pendingOpenRoom` → navegación). **Política única de aviso** (una sola vía: callback del engine, la comparten timbre/polling/worker/pendientes): mensajes **ajenos** (ecos propios multi-dispositivo no avisan) + **recientes** (≤30 min: el historial que baja de golpe en un teléfono nuevo no spammea) + **no de la sala abierta en foreground**. Al abrir un chat se cancela su aviso. Icono `ic_launcher_monochrome` (D60). Robolectric: `NotifierTest` 6 tests | Implementación v0.5.0 |
| D67 | Batería/OEM | Pedir UNA vez (con explicación) la exención `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permiso normal; app fuera de Play). Ajustes muestra el estado del timbre (conectado/reconectando/detenido), del permiso de notificaciones y de la batería, con botones para arreglar cada uno + recordatorio Xiaomi (Autoinicio + Sin restricciones) / Samsung (fuera de Sleeping apps, "optimización de notificaciones" desactivada). Sin la exención el Doze puede congelar el socket con la pantalla apagada | Implementación v0.5.0 |
| D68 | Recibos de entrega y lectura (✓/✓✓) | **Entrega (✓✓ gris)**: metadato que el servidor YA conocía (quién sincronizó qué) — `room_members.delivered_seq` (migración 0003) = seq global máximo servido a ese miembro; `/sync` lo devuelve como `rd: [[room, seq]]` y responde **200 con `m:[]`** aunque no haya mensajes nuevos (antes 204: la entrega quedaba invisible hasta el siguiente mensaje). El cliente marca `mine=1 AND state=ST_SENT AND seq<=rd` → `ST_DELIVERED`. **Lectura (✓✓ azul)**: recibo E2EE kind=3 (`\u0000L`+JSON `{u,t}` = «vi todo lo de esta sala hasta seq u»), invisible para el servidor, enviado SOLO con la sala abierta (`maybeSendReadReceipt`: dedupe por meta `rr_<room>`; eco propio ignorado). **BUG crítico corregido durante el desarrollo**: el eco propio se descartaba en `ingest` ANTES de guardar el seq del servidor → los mensajes propios vivían con `seq NULL` y NINGÚN recibo los encontraría jamás; ahora `fillSeq` rellena el seq sin tocar state. UI: reloj (enviando) → ✓ (servidor) → ✓✓ gris (entregado) → ✓✓ azul (leído, `tertiary`). E2E `e2e_receipts.py` 24/24 | Implementación v0.6.0 |
| D69 | Ajustes de sala (nombre/foto compartidos) | **Pantalla Ajustes de la sala** (engranaje del chat): nombre y FOTO de la sala viajan E2EE como mensaje de control kind=2 (`\u0000S`+JSON `{n,p?,t}`, latest-ts-wins con meta `ri_<room>` — mismo truco que los perfiles D61, presupuesto de foto idéntico ≤5 KB). Los dos teléfonos se renombran solos. **Al crear una sala SIEMPRE se anuncia el nombre** (aunque sea el default): quien entra con el código SIN poner nombre hereda el nombre real de la sala por el historial (bug 5). Sin clave en el teléfono: solo renombra local. La sala también muestra SAS, código de invitación (reconstruido del vault) con copiar/compartir, miembros vistos y zona peligrosa (vaciar historial local / eliminar). Lista y cabecera del chat priorizan foto+nombre propios si la sala fue personalizada; si no, el perfil del par (D61) | Implementación v0.6.0 |
| D70 | Eliminar salas (room_leave) | `auth` acción **`room_leave`** (idempotente): borra la membresía; si la sala queda VACÍA se borra entera (messages + room_state + rooms, en orden: 0002 no tiene CASCADE) y el código de invitación muere con ella. Si queda alguien, conserva su historial y el que salió puede volver con el código. Cliente `deleteRoom`: el leave del servidor DEBE salir bien antes de borrar lo local (sin red → error claro, nada se toca: la sala no reaparece vacía en el próximo sync); borra clave del vault+RAM, mensajes, outbox, pendientes, pares, foto y metas. Acceso: long-press en la lista (menú: marcar leído / vaciar historial / eliminar) y Ajustes de la sala. E2E 24/24 incluye leave idempotente + borrado total + join post-borrado 404 | Implementación v0.6.0 |
| D71 | Tiempo real garantizado con la app abierta | Doble seguro para «tardo en llegar con la app abierta»: (1) **tope de polling en foreground 15 s** (`POLL_FG_MAX_MS`; el backoff normal sigue hasta 5 min en background): aunque TODOS los timbres mueran, un mensaje con la app abierta tarda ≤15 s; (2) **watchdog cada 30 s** que re-invoca `syncDoorbell()` con la app abierta (idempotente). Además `onSessionChanged` (login/logout) enciende/apaga el timbre sin esperar al siguiente onStart | Implementación v0.6.0 |
| D72 | SIN notificación persistente (nuevo default) | **Petición del usuario: «no me gusta esa notificación persistente»**. Nuevo reparto: (1) **app ABIERTA**: el WebSocket del timbre vive DENTRO del proceso (una instancia `RealtimeDoorbell` gestionada por `ChatApp.syncDoorbell()`, sin servicio, sin notificación — Android no puede matar el proceso de una Activity visible) → tiempo real garantizado + D71 de respaldo; (2) **app CERRADA (default)**: NINGÚN servicio — el WorkManager periódico (~15 min) sincroniza y notifica igual (aviso normal, sin permanente); (3) **«Avisos instantáneos con la app cerrada»** (toggle en Ajustes, default OFF): solo entonces corre el FGS `remoteMessaging` de la v0.5.0 con su notificación permanente (requisito de Android para FGS). `syncDoorbell()` es el ÚNICO punto de decisión (lo llaman onStart/onStop, login/logout, el toggle y el watchdog). El `BootReceiver` solo revive el FGS si el modo instantáneo está activo | Petición usuario + implementación v0.6.0 |

## Resueltas (26-oct-2026 — usuario: «Usa las recomendadas»)

| # | Área | Decisión | Origen |
|---|------|----------|--------|
| P1 | minSdk | **24** (máximo alcance en Cuba; Tink soporta 24+; requiere core library desugaring) | Recomendada, confirmada |
| P2 | Estilo UI | **Oscuro por defecto, sigue al sistema** (Material 3 dynamic color cuando esté disponible) | Recomendada, confirmada |
| P3 | rootKey en dispositivo | **Keystore wrap** — Argon2id se calcula 1 vez al configurar; rootKey envuelto por clave AES hardware-backed; desbloqueo instantáneo después | Recomendada, confirmada |
| P4 | Extras día 1 | **Export cifrado del historial** (con contraseña independiente — D47) + indicador de red como parte natural del estado de sincronización | Recomendada, confirmada |
| P5 | applicationId | **`cu.alain.chat`** — package de trabajo acordado; independiente del nombre visible (D18); renombrable antes de publicación | Acordado + recomendada |
| P6 | Licencia | **Propietaria** (uso personal, 2 usuarios); revisar solo si se publica | Recomendada, confirmada |

Sin decisiones pendientes. ~~Próximo hito: adaptar la app Android al modelo rooms (D54)~~ **D54 completada**. Próximo hito: **pruebas en los 2 móviles reales** (instalar `cerlita-chat-v0.3.0-release.apk`, emparejar por código de invitación, verificar SAS por voz y entrega con red lenta) + ítems 2/5 de Fase 0.

## Fase 0 — estado del checklist

Ver [FASE-0-VALIDACION.md](FASE-0-VALIDACION.md). Ítem 1 (Supabase accesible): ✅.
Ítems 3-4 (Firebase/GMS): ❌ descartados (D44 — sin Firebase). Ítems 2 y 5: pendientes
del usuario.

## Backend — estado (26-oct-2026, modelo rooms)

**Desplegado y verificado E2E (37/37)**: migraciones 0001+0002 aplicadas (modelo rooms:
`rooms`, `room_members`, `room_state`, `messages.room_id`, sin `conversations`, sin
`fcm_token`), 4 Edge Functions en producción (auth con `room_new`/`room_join`/`rooms`,
sync con sender_uname+ts_ms, outbox por sala, heartbeat), BD limpia. **Anti-pausa
server-side ACTIVO (D59)**: pg_net instalado, job `anti-pausa-http` cada 6 h llama por
HTTPS a `heartbeat` (verificado 200 + tabla al día). Detalle técnico y hallazgos del
deploy: [backend/README.md](../backend/README.md). Sin Firebase (D44). **Timbre Realtime DESPLEGADO (D63, 27-oct)**: `outbox`
broadcast a `room-<roomId>` tras insertar (E2E 13/13, `scripts/e2e_doorbell.py`);
smoke 37/37 (ajustado para convivir con los usuarios reales alain/amelia).

## Cliente Android — estado (28-oct-2026, v0.6.0)

**"Cerlita Chat" (D18), modelo rooms (D54) + perfiles E2EE (D61) + tiempo real
SIN notificación persistente (D63-D67, D72), firmada**: APK
release **~2,8 MB** (misma firma que v0.2.0+ → actualiza sin desinstalar).
**v0.4.1 arregla el crash al abrir de las v0.1–v0.4.0** (D62: constructores de
ViewModels + WorkManager on-demand). Emparejamiento por código de invitación (D42) con
pantalla de SAS (D43), outbox/sync por sala, vault de roomKeys sellado por
Keystore (D55), export v2 (D47) + import del respaldo (D58), icono propio (D60),
onboarding y editor de perfil (nombre + foto subida O avatar por capas —
Cerdita/Koalita placeholder con ropa y accesorios, D61), lista de chats y
burbujas con el nombre y avatar del par.
**v0.6.0 (lista de bugs del usuario + notificación persistente fuera)**:
registro con repetir contraseña y ojo ver/ocultar (login y registro); botón
ATRÁS del teléfono navega en vez de cerrar la app + atrás en «Editar perfil»;
Ajustes de la sala con nombre/foto compartidos E2EE, SAS, código, miembros,
vaciar y eliminar (D69/D70); quien entra sin nombre hereda el de la sala;
long-press en la lista → marcar leído / vaciar / eliminar, badge numérico de
no leídos; ✓/✓✓/✓✓ azul de entrega y lectura (D68, con el fix del seq del eco);
**tiempo real con la app abierta SIN notificación persistente** (D71/D72:
timbre in-app + tope de polling 15 s; FGS solo si se activa «Avisos
instantáneos»). Validación: **64/64 tests JVM+Robolectric** (18 cripto + 4
import + 9 room-info/recibos + 11 perfiles + 5 smoke de arranque API 24-35 +
1 migración DB v2→v4 + 6 notificador + 9 phoenix + 1 servicio) + E2E
backend 24/24 recibos/leave y 37/37 smoke.
Ver [android/README.md](../android/README.md).

## D73 — Identidad visual Cerlita (v0.7.0)
El icono pasa a la ilustración de la pareja (cerdito + koala con corazones, imagen aportada): adaptive icon con foreground bitmap (contenido nítido al 66% sobre relleno desenfocado), legacy PNGs y monochrome vectorial (dos cabezas + corazón) para iconos temáticos. Tema «Cerlita» (paleta del mockup: burbujas rosa salmón/rosa pálido, acento lavanda, texto ciruela) seleccionable en Ajustes → Apariencia junto a Sistema/Claro/Oscuro; default cerlita. El modo se persiste en prefs y recompone en vivo (themeFlow).

## D74 — Chats directos 1-a-1 sin código de invitación (v0.7.0)
Búsqueda de usuarios por prefijo de @username (máx 10, sin auto-inclusión) y creación del chat de la pareja en un paso. E2EE intacto: cada teléfono genera un par X25519 (privada sellada por Keystore en device_key.bin, sobrevive al logout — es la identidad del TELÉFONO); el creador envuelve roomKey+roomSalt con X25519(miPriv, pubDelPar)→HKDF→ChaCha20-Poly1305 (AAD ligado a roomId+receptor, verificación de roomId derivado) y el servidor guarda el blob opaco en dm_keys. El par lo recoge por /sync (campo dm, con la pública del creador) y acusa con dm_ack; si la respuesta se pierde, el próximo sync lo vuelve a servir. rooms.dm_pair = least(a,b) UNIQUE → idempotente por pareja. Despertar del par en línea: broadcast al topic personal user-<uid> (el timbre se suscribe). Multi-dispositivo y reinstalación: la keypair cambia → wrap muerto → recuperación por código de invitación (igual que los grupos, mostrado en Ajustes del chat como «Código de recuperación»). rooms.kind: 0=grupo, 1=DM (los DM no se renombran: el nombre es el perfil del par).

## D75 — Llamadas de voz y vídeo 1-a-1 con WebRTC (v0.7.0)
Señalización: broadcast REST al topic de la sala (offer/answer/ice/bye, JAMÁS secretos — solo metadatos de conexión; el eco propio se ignora por uid). El medio viaja P2P cifrado DTLS-SRTP (libwebrtc vía io.getstream:stream-webrtc-android 1.1.3; STUN Google+Cloudflare, SIN TURN — NAT simétrico = «no se pudo conectar», TURN pendiente). Registro LOCAL por teléfono (tabla calls: completada/perdida/rechazada/cancelada + duración). Ocupado automático, timeouts 45 s/40 s, timbre del sistema + vibración, MODE_IN_COMMUNICATION. Entrantes SOLO con app abierta o modo instantáneo (el timbre las trae; sin FCM no hay despertar) — el que llama registra «cancelada» y el otro no se entera, como un teléfono sin cobertura. APK +16 MB (solo arm64-v8a+armeabi-v7a en release).

## D76 — Adjuntos E2EE: notas de voz, fotos y archivos (v0.7.0)
Cada archivo se cifra con fileKey ALEATORIA propia (ChaCha20-Poly1305, AAD "file-v1|room|msg") y sube CIFRADO directo a Storage (bucket privado «attachments»); la clave viaja E2EE dentro del mensaje de chat (prefijo \u0000A + JSON: tipo, nombre, mime, tamaño, path, dimensiones, duración, waveform). El servidor ve bytes opacos en <roomId>/<uuid>.<ext>. Descarga: función /files verifica membresía contra el roomId DEL PATH y devuelve URL firmada de 10 min. Fotos: reescalado a 1600px + EXIF enderezado + re-codificación sin EXIF (fuera la geolocalización). Notas de voz: AAC/M4A 48 kbps mono, tope 5 min, waveform real (muestreo 10 Hz, escala log). Subidas con progreso en la burbuja, reintento desde caché local (attcache), huérfanas marcadas fallidas al arrancar.

### Hallazgos v0.7.0
- **H15 (entorno)**: la salida de la consola del sandbox ELIMINA la secuencia literal «[m» de los textos mostrados — los `[[msg_id,...]]` del outbox se veían como arrays planos y un script e2e copiado de pantalla falló con bad_msg. Se construyen listas con append y se verifican archivos con dumps espaciados.
- **H16 (producción)**: el tope MAX_USERS=10 se agota rápido si un e2e crashea antes de su limpieza (usuarios de prueba huérfanos). Limpieza manual selectiva (siempre preservando a alain y amelia, los usuarios reales).
- **H17 (Storage)**: el SUPABASE_SERVICE_ROLE_KEY auto-inyectado en las Edge Functions es el formato nuevo (sb_secret_) y el API de Storage lo RECHAZA (403 Invalid Compact JWS): exige JWT clásico. La JWT clásica se guarda como secret propio SUPA_SVC_JWT en el deploy.
- **H18 (Storage)**: el endpoint upload-sign responde NoSuchBucket aunque el bucket exista (no disponible en este plan/versión) → la subida se hace directa con anon key + política RLS attachments_insert_by_room_capability (INSERT solo a <roomId>/<uuid>.<ext>: conocer el roomId ya ES la capability de la sala, mismo modelo que room_join/outbox; sin SELECT: leer exige /files con membresía).

## D77 — Tema Cerlita fiel al mockup (v0.7.1)
La primera versión del tema (D73) se pintó con estimaciones del modelo de visión y «no se parecía en nada» (usuario). Rehecha MIDIENDO la imagen: muestreo por regiones + picos de histograma de los píxeles reales (los VLM sobreestiman la luminosidad). Paleta medida: listas #F6D8D8, tarjetas #FBEAEA, fondo del chat #E0C2C4 (más rosado que las listas, como el mockup), burbuja enviada #F6C6CC con texto ciruela OSCURO (¡el mockup NO usa texto blanco!), recibida gris malva #CCBABA, acento coral #D87884 (FAB/enviar/badges/tabs), aceptar #4FA07C / rechazar #E07070, ticks leído #C24D5E. LocalChatPalette desacopla burbujas de los roles M3 (claro/oscuro derivan del esquema: look intacto); el chat pinta su propio fondo y la barra superior va a juego. Regla aprendida: los colores de una imagen de referencia se MIDEN (PIL), no se estiman.

## D78 — Apodo personal del contacto (v0.7.1)
«Cómo me aparece a ti»: nombre local y privado por uid (prefs nick_<uid>, nunca sale del teléfono — la otra persona no se entera; vacío = nombre real). Flow reactivo en ChatApp (nicknames) leído por: título del chat (tocar el título lo edita, DMs), lista de chats (menú long-press «Renombrar contacto»), registro y pantalla de llamadas, y las notificaciones. Prioridad: apodo > perfil E2EE del par > nombre de sala. Extra: «volver a llamar» resuelve el @usuario real desde la sala DM (peerName podía ser un nombre de perfil y la búsqueda fallaba).

## D79 — Notificaciones de verdad + respuesta directa (v0.7.1)
«Las notificaciones son horribles» → rediseño: avatar real (foto de sala → foto de perfil → cerdita/koalita por capas rasterizadas a Canvas → inicial coral circular), color acento Cerlita, título con apodo (D78). Acción «Responder» con RemoteInput → ReplyReceiver (goAsync): envía cifrado por el engine como cualquier mensaje (outbox + reintentos) y retira el aviso; acción «Marcar leído» baja el badge y manda el recibo ✓✓. PendingIntent de la respuesta MUTABLE (el sistema inyecta el texto — regla de Android 12+).

## D80 — Descargar lo recibido, en un toque (v0.7.1)
«Los archivos que recibo no tienen opción de descargar» — las fotos no tenían NINGUNA forma de guardarse y los documentos abrían el selector del sistema. AttachSaver: API 29+ MediaStore sin permisos (Descargas/Cerlita y Imágenes/Cerlita, IS_PENDING y confirmación) con toast «Guardado ✓»; API <29 cae al selector SAF (sin permisos también). Fotos: botón «Guardar» en el visor a pantalla completa; notas de voz: botón de descarga junto al play; documentos: un toque.

## D81 — Llamadas que nunca conectaban: 3 bugs (v0.7.1)
Síntoma: el otro contestaba, le salía «en la llamada», pero el que llamaba seguía en «Llamando…» y no se oían. (1) ICE: SDP y cada candidato viajan en POSTs separados sin orden; addIceCandidate antes del SDP remoto se pierde en silencio → sin ruta → FIX IceQueue (mutex + cola en orden, drenada al fijar el SDP, en ambos lados) + orden en el ENVÍO (offer/answer sale primero, ICE propios esperan) + reintento x3 del broadcast. (2) Fases: la del que llamante solo cambiaba si WebRTC conectaba → fase CONNECTING al llegar el answer («Conectando…» al instante) y al aceptar (el cronómetro ya no arranca de mentira); «failed» tras 35 s de CONNECTING. (3) Audio Android 12+: setSpeakerphoneOn deprecado e ignorado por OEMs → setCommunicationDevice (TYPE_BUILTIN_SPEAKER/EARPIECE) en API 31+. Además: aceptar espera a que el offer quede aplicado (CompletableDeferred, tope 5 s) y FAILED corta siempre (adiós llamadas zombis). Todo 100 % gratis: P2P + señalización propia (sin SFU ni TURN — TURN solo si aparecen NAT simétricos).

## D82 — Avisos instantáneos por defecto ON (v0.7.1)
«Haz que la notificación persistente, si es la única forma, esté activa»: default instantBg=true + migración única v0.7.1 (enciende el FGS a quien venía con el default antiguo OFF; quien lo apague luego, manda). Regresión corregida de paso: AppRoot arrancaba el FGS incondicionalmente tras el login, esquivando el punto único de decisión (D72) — ahora llama a syncDoorbell().

### Hallazgos v0.7.1
- **H19 (entorno)**: el SDK platform 37 ya no publica «platforms;android-37» con separador «-» en el listado del sdkmanager (formato con «|») — los scripts que grep-ean «*-» fallan; y el symlink del sandbox prohibido (H14) se sustituye por cp -r android-37.0 → android-37.
- **H20 (credenciales)**: tras reinicio del sandbox ~/.git-credentials desaparece; el nombre de usuario del store debe ser el de GitHub (Alain314159), NO el email — la @ del email rompe el parseo de la URL del store y git intenta preguntar interactivo (sin TTY).
- **H21 (notificaciones)**: NotificationCompat con MessagingStyle en API 28+ NO puebla notification.largeIcon (el avatar vive en el Person del estilo) — aserciones de test sobre largeIcon fallan en Robolectric; se verifica el RemoteInput y las acciones.

## D83 — Sistema de avatares: arte real (híbrido raster + vector)

**Decisión (28-oct-2026).** El arte de los avatares deja de ser placeholder: las BASES
son raster (WebP con alfa) generados con un pipeline reproducible, y las CAPAS son
VectorDrawable parametrizados. Documentación completa y comandos: `docs/AVATARES.md`.

**Por qué híbrido y no todo-uno:**

- La base es donde vive el arte → un modelo de imagen la clona bien; vectorizar a mano
  no escala. Base = WebP (8 piezas: Cerdita y Koalita × 4 colorways).
- Las capas (ropa, accesorios, decoración) son formas simples → el vector las hace
  **exactas, escalables y recoloreables**. 22 VectorDrawable en artboard 1024×1024.
- **El renderizador actual ya soportaba raster** (`painterResource` y
  `ContextCompat.getDrawable`): cambiar las bases no costó ni una línea de render.

**Style Bible medido** sobre la ilustración original (k-means + muestreo por rejilla):
tinta `#503433` (nunca negro), cuerpo `#FFC1CC` / `#BCB2BA`, oreja `#FB96A5` / `#F9E2DC`,
hocico `#FFA2AD`, blush `#F999A5`, corazón `#F0758D`, blanco `#FEE8EB`, violeta `#BC8FB5`.
Trazo de **11 px sobre 1080 = 1,0 % del ancho**. Hallazgo: blush↔oreja están a **ΔE 2,4**
— en la referencia los rosas son casi indistinguibles y se separan por POSICIÓN y contorno,
no por tono. Por eso el pipeline **no tokeniza la referencia** sino que fuerza cada región
a su token por *unmatte* (descomposición `pixel = α·relleno + (1−α)·fondo`), no por
proximidad de color (que colapsaría el blush dentro de la oreja).

**Sistema de capas.** Orden: `decor` (DETRÁS) → `clothes` (DETRÁS) → `base` → `acc`
(ENCIMA). El "detrás" es lo que hace que la burbuja asome por encima de la cabeza como en
la ilustración y que la camiseta se lea como hombros. Cada familia (pig/koala) tiene su
**tabla de anclas** medida sobre rejilla de 64 px (orejas, ojos, mentón, pecho) y las
capas se generan paramétricas contra ella → añadir personaje = medir anclas + regenerar.

**Contrato de wire: el arte cambia, el contrato no.** Se **mantienen** todos los ids
existentes (`pig`, `koala`, `tee_pink`, `tee_violet`, `tee_teal`, `dots`, `stripes`, `bow`,
`flower`, `party`, `glasses`, `heart`) → los perfiles ya guardados en la BD siguen
renderizando. Se **añade** `decor` (opcional, se serializa solo si hay valor) y los ids de
base con colorway (`pig_melocoton`, `koala_azul`…). Un cliente que no conoce un id lo
ignora: degradación limpia, igual que D61.

**Selector.** Los chips de texto se sustituyen por **vista previa real**: cada opción se
compone sobre el personaje elegido antes de mostrarse.

**Peso**: +496 KB (408 raster + 88 vector) sobre una APK de 2,8 MB (+18 %).

**Arte de la pareja**: se usa la **ilustración original recortada** (flood-fill desde los
bordes, porque el fondo `#FCC2D0` y el cuerpo de Cerdita `#FFC1CC` son casi idénticos y
un key por color se comería el cerdo) → `av_pair.webp`.

**Aprendizaje de D62 aplicado**: el QA visual (componer + mirar) es **obligatorio**. En la
primera iteración los 6 accesorios salieron mal colocados por deducir anclas de
heurísticas de color; se corrigió midiendo sobre rejilla. La regla queda escrita en el
doc: *medir antes de posicionar*.

## D84 — Notificaciones que nunca callen + timbre que revive tras actualizar (v0.8.0)
Auditoría §3.2/N1/N2. La ventana de 30 min hacía que un mensaje importante llegara EN SILENCIO (teléfono 2 h sin red → mensaje de hace 45 min: nada). Regla nueva: SIEMPRE se avisa del último mensaje de cada sala — 1 notificación por sala con contador, cero spam; la única tanda descartada es la re-sincronización evidente (>12 y todo con >24 h: historial de un teléfono nuevo, no noticia). N2: MY_PACKAGE_REPLACED en BootReceiver — el timbre revive tras ACTUALIZAR la app sin abrirla (antes moría silenciosamente hasta la próxima apertura). LOCKED_BOOT_COMPLETED queda fuera A PROPÓSITO: exige directBootAware y migrar DB/vault/prefs a storage device-encrypted — un timbre bajo direct boot no puede leer nada de lo que necesita; mejor honesto que un crash.

## D85 — Un 401 sale sin borrar nada, y el usuario ve por qué (v0.8.0)
Auditoría B4. Un 401 en sync/outbox dejaba la app muerta en silencio (polling eterno, sin ruta al login). sessionExpired() limpia SOLO la sesión: historial, claves del vault y adjuntos intactos — al re-entrar con la misma cuenta todo sigue ahí (borrarlo todo era demasiado agresivo para un token caducado). El login muestra «Tu sesión expiró» (sessionExpiredHint) para que el usuario sepa QUÉ pasó. Se llama desde ambos puntos 401 (syncOnce y drainOutbox).

## D86 — Índices en messages y fin del fallback destructivo (v0.8.0)
Auditoría B3/B7. La tabla messages no tenía NI UN índice secundario: las 4 queries calientes de la UI (observeChat, observeChats, subconsultas de observeRoomList, failOrphanSending) hacían escaneo completo — y al ser Flow, cada INSERT las re-disparaba. Migración 5→6 con idx_msg_room_kind_ts / idx_msg_room_mine / idx_msg_state (0 KB de APK; el jank desaparece a partir de ~500 mensajes). Y fuera fallbackToDestructiveMigration: una migración olvidada borraba el historial EN SILENCIO; ahora es crash visible en desarrollo + MigrationTest obligatorio por salto (upgradeV5toV6Indexes reproduce la actualización real con 50 mensajes).

## D87 — Cursores por sala: el historial al unirse, al fin (v0.8.0)
Auditoría B1 — el bug más grave del producto. seq GLOBAL del servidor + cursor GLOBAL del cliente = al unirse a una sala con historia, sus mensajes quedan por debajo del cursor y se descartan PARA SIEMPRE (ni mensajes, ni el nombre heredado del room_info, D69; D61 lo sabía y parcheaba el síntoma). Fix de causa: modo sala en /sync (`?room=&s=`) con membresía verificada (403 ajena, 400 inválido) + syncRoomHistory() cliente con cursor propio rs_<roomId> por páginas, ingiriendo por el mismo camino que el sync global, sin notificar y con badge a 0. Lo llaman joinRoom, restoreRoomKey y backfillRoomCursors() (una pasada por sala sin cursor al login: recupera huecos de salas pre-v0.8.0 sin re-introducir códigos). E2E 26/26 contra producción, incluida la reproducción del bug original.

## D88 — TURN gratis + eco cancelado: llamadas que conectan de verdad (v0.8.0)
Auditoría R3 + defectos de audio. Con solo STUN, dos teléfonos tras NAT simétrico (típico en redes móviles cubanas) no tienen ruta P2P — la llamada moría en «Conectando…» aunque D81 arreglara la señalización. /turn (Edge Function) sirve STUN+TURN con credenciales en secrets del servidor: NUNCA en el APK, rotables sin actualizar. Defaults: OpenRelay free (20 GB/mes, creds públicas de prueba de Metered); cambiar a ExpressTURN/Cloudflare TURN (1000 GB/mes gratis) = re-exportar 3 env y re-desplegar. El media sigue E2EE (DTLS-SRTP): el relay transporta bytes opacos. Cliente: TurnConfig con caché de 10 min + prefetch en arranque (llamar es instantáneo; si el fetch falla, STUN = comportamiento v0.7.x — ninguna llamada se cancela por esto). Además: audio con echoCancellation + autoGainControl + noiseSuppression (sin AEC, con altavoz el otro oía tu propia voz con retardo — el defecto de audio #1).

## D89 — Señalización de llamadas cifrada: la promesa E2EE también aquí (v0.8.0)
Auditoría B6. El offer/answer/ICE/bye viajaba EN CLARO por el broadcast: el servidor veía el SDP (con las IPs de ambos), los candidatos ICE y el NOMBRE real (fn) — contradecía D61 de frente. Ahora todo va bajo sobre ChaCha20-Poly1305 con Crypto.sigKey (roomKey → convKey → salt=callId, dominio separado de los mensajes), AAD "sig-v1|callId|roomId"; el servidor solo ve topic (roomId) y un callId aleatorio. Todos los emits cifrados (offer/answer/ice/bye/busy/declined); los receives ADEMÁS parsean legacy claro de v0.7.x (un teléfono sin actualizar todavía suena — para E2EE completo, actualizar ambos). CallSignalsTest +4: roundtrip, ice/bye cifrados, clave/AAD equivocados → null, compat legacy.

### Hallazgos v0.8.0
- **H22 (herramienta)**: un MultiEdit con un solo old_str erróneo falla TODO el lote «atómicamente»… pero los edits previos del mismo lote YA quedaron aplicados en disco — verificar SIEMPRE el estado real del archivo tras un fallo antes de reintentar (grep de la firma nueva), nunca asumir rollback.
- **H23 (tests)**: los XML de test-results se reescriben ASÍNCRONAMENTE al terminar la tarea Gradle — un parse inmediatamente tras el BUILD puede leer un XML stale y contar tests viejos; verificar el mtime o re-leer.
- **H24 (Android)**: los nombres de test con backticks NO admiten puntos (v0.7.x → error JVM «Name contains illegal characters»); usar guiones bajos.

## D90 — La app se compila sola: GitHub Actions (v0.10.0)
Cada push a main corre tests (JVM+Robolectric) y construye el APK release FIRMADO (artefacto descargable de la run); cada tag v* lo publica como Release con notas automáticas. Gratis en repos públicos. La firma sale de secrets del repo (keystore en base64 + passwords, subidos por API cifrados con la clave pública del repo — jamás en el código): el APK de CI lleva la MISMA firma 37b6d4…, actualiza encima. En PRs de forks (sin secrets) compila sin firma y avisa. Hallazgo del primer run: lintVitalAnalyzeRelease muere de OOM con el -Xmx2048m pensado para el sandbox de 3,9 GB → en CI se sube a 4g con -Dorg.gradle.jvmargs (gradle.properties local intacto).

## D91 — Pantalla completa como el mockup (v0.10.0)
Tres defectos visuales del uso real: (1) la barra inferior flotaba "bien arriba" — el padding de navegación se aplicaba DOBLE (el del Scaffold + el de la propia NavigationBar) → Scaffold con contentWindowInsets(0); las barras se defienden solas. (2) Los botones del teléfono no se ocultaban → modo inmersivo por WindowInsetsController (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: deslizar desde el borde los trae un momento); la barra de estado SE QUEDA (el mockup la muestra); se re-ocultan al recuperar el foco. (3) Los avatares se veían CORTADOS en los círculos (orejas/pies) → margen interno del 7% en AvatarView y en el rasterizador de notificaciones; el fondo del círculo sigue llenándolo todo.

## D92 — Avatares de cuerpo completo en 3D (v0.10.0)
El arte pasa de "solo cabeza" a PERSONAJE ENTERO estilo render 3D (como pidió el usuario). Pipeline v3 (tools/avatares/build_avatar_3d.py): chroma-key por DOMINANCIA verde (el render mete gradiente en el fondo: color exacto no sirve) + despill + erosión 1px del borde (adiós halo) + colorways por CLUSTERS medidos del propio arte con transferencia de offset (new = orig + target − centro: el sombreado del 3D se CONSERVA — el unmix plano de v2 lo destruía) + artboard con margen 10% (v2: 3%). Las 22 capas se regeneran contra anclas MEDIDAS del arte nuevo: ojos por componentes conexas de píxeles oscuros (par simétrico más alto — las fosas nasales también son simétricas), cuello por ancho de fila, coronilla real entre orejas (hat_y: el gorro flotaba apoyado en la punta de las orejas), ancho de torso MEDIDO (el koala de cabeza enorme: head_rx ya no sirve de torso). La ROPA pasa de detrás (leía "hombros" con sola cabeza) a ENCIMA del torso (camiseta de verdad con escote, mangas y bajo por familia — el koala con torso corto hem_frac propio). Orden final: decor → base → ropa → accesorios. QA visual obligatorio (regla D83): hojas de contactos compuestas + verificación por VLM — 3 iteraciones (gorro flotante, camiseta del koala baja x2) hasta todo PASS, también a 64px. av_pair.webp ahora es la pareja 3D abrazándose (sigue sin usarse en código).

## D93 — La sesión de 90 días, con contador (v0.10.0)
El usuario preguntó por qué existe y si es obligatoria. Es NUESTRO backend (migración 0001: sessions.expires_at default now()+90 días), no un capricho de Android/Supabase: es el compromiso de seguridad (teléfono perdido/robado → el token muere solo; re-login ~4 veces al año) y al expirar NO se borra NADA local (D85: historial, claves y adjuntos intactos). Se queda en 90 días (ajustable si molesta). Lo que SÍ faltaba: saber CUÁNTO queda → GET /auth (whoami, ya existía para health) devuelve ahora expires_at; Session lo guarda en ss.bin (las sesiones viejas lo piden al servidor una vez al arrancar — ensureSessionExpiry); Ajustes muestra "quedan N días" y avisa en rojo a ≤7 ("expira mañana"). El exp ISO se parsea con OffsetDateTime (desugaring ya activo por minSdk 24).

## D94 — El avatar propio también en los mensajes enviados (v0.10.0)
En el mockup de referencia AMBOS lados del chat llevan avatar; la app solo lo ponía en los recibidos. Ahora los mensajes ENVIADOS muestran el avatar propio a la derecha de la burbuja (mismo tamaño 32dp, alineado arriba) — igual que la referencia.

## D95 — Accesorios que integran con el 3D: adiós a los "stickers" (v0.11.0)
El usuario: "los accesorios están súper mal y horrible". Diagnóstico VLM brutal pero exacto: las bases v0.10 son renders 3D suaves (vinilo, sin contorno, luz arriba-izquierda) y las capas seguían siendo VECTORES planos con trazo de tinta #503433 → choque de estilos; la ropa además era MÁS ESTRECHA que la panza real (camiseta half 273 vs panza 292 medida) por eso parecía una caja. Fix de fondo: las capas pasan a RASTER (WebP 384 lossless, ~12 KB c/u) generadas por make_layers_3d.py con motor de sombreado propio: degradado de luz diagonal + rim oscurecido + especular difuso (vinilo) + SOMBRA DE CONTACTO difuminada bajo cada pieza + NADA de trazo de tinta. QA visual en bucle (regla D83) y veredicto comparativo antes/después 5/5 "por amplio margen" — lección: con VLM hay que comparar CONTRA la versión anterior, no pedir perfección absoluta (cada ronda sube la meta y entra en nitpicking imposible de "cloth simulation").

## D96 — Pestañas Cerdita | Koala y limpieza a petición (v0.11.0)
Para buscar un koala había que pasar por todas las cerditas → FilterChips Cerdita|Koala filtran el selector de base; ropa/calzado/accesorios también se filtran por familia (el VESTIDO solo existe para cerdita y el TRAJE solo para koala — items por familia con degradación limpia en el wire: un koala con "dress" simplemente no lo dibuja). Se ELIMINAN a petición la cerdita menta (se veía fea) y la burbuja con corazones (categoría decoración entera fuera, también de AvatarView y Notifier). Perfiles ya guardados con "pig_menta" o "bubbles": base desconocida → default de la MISMA familia (nunca inicial seca: pig_menta→cerdita rosa, koala_futuro→koala gris) y el decor que ya no existe no se dibuja. Los ids legacy (tee_*, bow, party, glasses, heart…) intactos.

## D97 — Ropa de verdad: vestido, traje y calzado sobre anatomía medida (v0.11.0)
No existía nada para "la parte de abajo". Ahora: VESTIDO de la cerdita (ajustado al pecho, cae CUBRIENDO la panza — que es lo más ancho del cuerpo, half 292 — abre en A hasta half 333, bajo ONDULADO en 5 scallops, mangas globo, cinturón con lazo y cuello peter-pan) y TRAJE del koala (chaqueta en columna sobre el torso MEDIDO ~350-670, mangas que siguen los lóbulos reales de los brazos separados desde y≈710 con puño y costura-sombra, camisa en V, solapas curvadas con filo iluminado, pajarita coral con nudo y botones). CALZADO propio (slot "feet"): botitas bulbosas para la cerdita, sneakers de puntera ancha para el koala (las patas del koala son más grandes, estilo por familia). Todo posicionado contra silueta medida FILA A FILA (silhouette.json), no contra constantes: la lección D77 otra vez.

## D98 — El avatar está vivo: saluda, gira y sabe que lo vistes (v0.11.0)
La vista previa del editor deja de ser una foto fija (D98, "quiero que el avatar sepa que le voy poniendo las cosas, poder girarlo y que salude"): (1) RESPIRA en reposo (transición infinita, ±7dp + cabeceo 1.4°); (2) al tocar cualquier ropa/accesorio REACCIONA con squash-stretch + meneo (sabe que lo vistes); (3) TOCARLO = saludo con brinquitos decrecientes + burbuja "¡Hola!" (nada de corazones, D96); (4) ARRASTRARLO lo gira en 3D real (rotationY con cameraDistance de perspectiva; vertical inclina ±14°) y al soltar vuelve con muelle al frente más cercano; (5) cambiar de personaje da una vuelta completa de 360°. Los saltos van en px convertidos con LocalDensity (se ven iguales en cualquier pantalla). El chat y las notificaciones siguen con el render estático (barato en listas); LayeredAvatar queda como composable compartido.

## D99 — Avatares 3D de verdad: diagnóstico y ruta aprobada para analizar (v0.12.0, propuesta)
Usuario: "la ropa está horrible, cuando giran se ve que son 2D, quiero avatares como los de Facebook — dime qué necesitamos, no crees código todavía". ANÁLISIS completo en docs/AVATAR-3D-ANALISIS.md (sin cambios de código en este commit). Diagnóstico de fondo: las bases son FOTOS de renders 3D (IA generativa) y la ropa son pegatinas pintadas encima (D95/D97 mejoraron la PINTURA, no el problema); el giro de D98 es rotationY de una imagen plana (cartel de cartón) — techo ESTRUCTURAL del sistema "foto + capas": sin geometría no hay rotación real, ni volumen de prenda, ni animación esquelética, y cada ronda de pintura rinde menos (D95→D97 ya lo mostró). "Como los de Facebook" = nivel Memoji/Meta-3D/Zepeto: MALLA glTF + ESQUELETO con animaciones + PRENDAS COMO MALLAS vestidas al mismo rig + motor 3D en tiempo real (Filament vía SceneView, ambos gratis). Ruta elegida: pipeline Blender bpy programático y reproducible (misma filosofía de tools/avatares; IA solo para referencias, bases libres rigged solo como referencia de proporciones) → GLB comprimidos; SceneView SOLO en el editor (girar de verdad, tocar=saludo, vestir=swap de malla); chat/listas/notificaciones siguen con snapshots rasterizados (sistema actual, cero 3D en filas); wire/backend/E2EE intactos (los ids del catálogo se mantienen). Plan con PUERTA DE PARADA: F0 = prueba de concepto de 1 sesión (Koalita + rig + 2 clips + 1 camiseta + pantalla de prueba girando) — si el look no convence, se para ahí. F1 catálogo completo, F2 editor 3D + snapshots + pareja abrazada, F3 vida (reacciones, poses). Fuera de alcance a propósito: física de tela, escaneo fotorrealista, blendshapes faciales (F4+ solo si se piden). APK estimada +4-8 MB por el motor (medir en F0; plan B arm64-only, precedente WebRTC D75).

## D100 — Investigación ampliada verificada: el plan D99 sobrevive al contraste (v0.12.0, propuesta)
Usuario aportó una lista externa de opciones (3D-Avatar-Chatbot, three.ws avatar-sdk, dlp3d, react-ai-voice-avatar, RPM+TalkingHead, SceneView, Materia, VRM/VRoid, VtuberCamera_KMP, avataryug) y pidió buscar más y analizar. Verificación contra fuentes primarias (sección 9 de AVATAR-3D-ANALISIS.md): (1) el 80 % de las opciones web son inintegrables (Three.js/Babylon/React en navegador) y además responden a OTRO producto (compañero conversacional con LLM+lipsync; nuestro avatar es identidad decorativa de perfil); (2) Ready Player Me fue comprada por Netflix y CERRÓ el 31-ene-2026 — la recomendación RPM+TalkingHead llegó obsoleta, y filtro anti-desvío: verificar fecha y plataforma de toda recomendación futura de avatares; (3) VRM/VRoid descartado por DESAJUSTE DE DOMINIO: formato pensado para humanoides anime (rig humano estandarizado + expresiones faciales normalizadas) que pelea contra animales chibi — glTF + rig propio es menos fricción; (4) avataryug/bitHuman/AvatarSDK = comerciales, fuera (todo gratis); (5) Materia es alpha ("not for production" según su propio README) → lista de vigilancia, no hoy. GANANCIAS reales: korender (Apache-2.0, glTF+animaciones, se empotra en Compose, activo) queda como PLAN C de motor junto a "Filament directo"; VtuberCamera_KMP (Kotlin+Filament+GLB/VRM con manipulación de huesos) y ruslanmv/3D-Avatar-Chatbot (Apache-2.0, UX de vestuario/try-on + behavior director) quedan como REPOS DE REFERENCIA para F0-F1; TRELLIS.2 (Microsoft, MIT, imagen→malla GLB) queda como ACELERADOR OPCIONAL de la escultura inicial solo si la vía 100 % programática no convence en F0 (topología generada requiere rehacerse igual). Quaternius Ultimate Animated Animals (CC0, glTF rigueado+animado) como referencia de estructura de rig/animales. CONCLUSIÓN: no existe atajo gratis ni librería todo-en-uno para Kotlin — la lista externa llega a la misma conclusión ("empieza con SceneView") que D99. El plan F0-F3 queda intacto y refuerzado.

## D101 — Avatares 3D de verdad: el plan D99 ejecutado (v0.12.0)
"Solo hazlo todo pero dame un avatar que sirva" — F0-F3 en una sesión. **Geometría real**: pipeline Blender reproducible (tools/avatares3d/build_avatars.py) genera Cerdita y Koalita chibi por primitivas + esqueleto propio (root/spine/head/ears/arms/feet) con pesos RÍGIDOS por pieza (peso 1.0 a un hueso: muñeco de articulaciones esféricas, predecible, cero skinning automático defectuoso). **La ropa son MALLAS al mismo esqueleto** (todo el catálogo: 5 camisetas + vestido + traje + 2 calzados + 8 accesorios): mangas unidas a los huesos de los brazos (giran con el saludo), camiseta/vestido/chaqueta por revolución de perfil (lathe), gafas/gorros/lazos a la cabeza. **3 clips** horneados (idle=respirar, wave=saludo con brazo, react=squash al vestirlo). **Un GLB por colorway** (7, ~1.4MB c/u) con materiales pre-horneados: cero recoloreo runtime, cero riesgo en GPUs raras; las prendas viajan DENTRO como nodos con el nombre exacto del id del catálogo → la app muestra/oculta nodos y **el wire no cambia ni un byte** (perfiles viejos siguen funcionando, vestido-en-koala simplemente no se dibuja). **App**: SceneView 4.62 (Apache-2.0, Filament 1.72) SOLO en el editor — órbita de cámara de fábrica (girar de verdad, cualquier ángulo), tocar=saludo esquelético, vestir=swap de malla+react, respiración en bucle, fallback 2D mientras carga (nunca hueco negro). **Snapshot**: al guardar, PixelCopy del SurfaceView → JPEG → foto de perfil por el pipeline E2EE existente → el otro teléfono y las notificaciones ven el muñeco 3D exacto que vestiste (API<26 o fallo → degradación al 2D de siempre). Chat/listas siguen SIN render 3D (barato en filas). QA visual obligatorio (D83) en bucle con Cycles: koala 7/7 PASS, cerdita 7/7 PASS — la ropa ya NO es pegatina: mangas, volumen, misma luz. **Bugs cazados por el bucle**: transform_apply con defaults aplica también LOCATION (piezas rotadas volaban alrededor del origen del mundo); spin del lathe en eje Y (anillos horizontales en vez de prendas); tracks NLA muteados no se exportan; reset_pose borraba los tracks recién stashheados; cyl de Blender 4.x solo acepta "radius". APK 19.2→37.7 MB (+10.5 GLB + Filament 2 ABIs; arm64-only ahorraría ~7MB si molesta). Tests 110/110 (6 nuevos: mapeos id→GLB/nodo, vestido-solo-cerdita, degradación de ids retirados).

## D102 — El editor presenta su muñeco y el look 3D llega a TODO el app (v0.13.0)
Capturas del usuario tras v0.12.0: (1) el perfil de Ajustes "se sigue viendo igual que siempre" (composite 2D viejo: bases IA + stickers); (2) el editor 3D "se ve horrible": fondo NEGRO puro, koala DE ESPALDAS, colores apagados, sin sombra, choque total con la UI rosa. **Diagnóstico**: el cuerpo se construye mirando a +Y en Blender (atrás de la convención); el "fix de azimut" del QA de D101 compensó la CÁMARA y dejó el modelo girado → el GLB mira a -Z y la cámara por defecto de SceneView (z=+2.75, FOV≈45°, mira al origen) ve la ESPALDA; SceneView renderiza OPACO sin skybox → negro; y el perfil/listas solo cambiaban si guardabas un snapshot 3D que nadie había guardado. **Arreglos**: (1) pipeline: `turn_around()` gira todo 180° en Z con un Empty padre keep-transform (rotaciones locales y NLA intactos) → frente canónico -Y Blender = +Z glTF, re-exportados los 7 GLB, `qa_shot` az 0 = frente otra vez (lado -Y); (2) editor: `isOpaque=false` (transparente: se ve el escenario detrás), `scaleToUnits=1.9` + `centerOrigin()` (centrado en el origen y llenando ~83% del encuadre — v0.12 lo dejaba chico y abajo) y escenario Compose detrás del render: degradado radial surfaceContainerLowest→High + sombra elíptica de suelo scrim 16% + esquinas 28dp — estilo Memoji, cero negro; (3) **el look 3D en todo el app**: `render_rasters.py` (fase render en Blender + fase sprites en Python) genera las bases y prendas RASTER del modelo REAL: 7 bases (una por colorway, frente 3/4, estudio de 3 luces + mundo pastel, 384×384 alfa, semilla fija, determinismo Δ=0) y sprites de prendas por DIFF (render con prenda − render base; alpha ∝ |Δcolor|, mediana 3×3): el sombreado REAL (pliegues, sombra del gorro en la cabeza, oclusión) viaja dentro del sprite y la alineación es exacta POR CONSTRUCCIÓN (misma cámara, misma pose) — el diff captura también la sombra que la prenda pinta en el cuerpo, que es justo lo que la hacía parecer pegatina. Mismos nombres `av_*.webp` → mismos `R.drawable` → CERO cambios de código: perfil, chats, notificaciones y tiles del editor muestran el look 3D NUEVO sin que nadie re-guarde nada; el snapshot 3D al guardar sigue siendo la foto exacta del muñeco vestido. Verificación automática: composite(base+sprites) vs render directo de la combinación < 2% de píxeles distintos + previews VLM + facing GLB (ojos/nariz a +Z) por script.

## D103 — Pausa de avatares 3D; prioridad al chat (fiabilidad/velocidad), llamadas y minijuegos estilo Telegram (solo investigación)
El usuario pausa el trabajo de avatares («no es lo más importante») y reorienta: (1) chat que funcione bien, mensajes que lleguen sin retrasos, todo rápido y fluido; (2) mejorar llamadas y videollamadas; (3) investigar la plataforma de juegos/bots de Telegram para traer minijuegos a Cerlita — esta parte SOLO investigación, sin código. Hallazgo de sesión: el sandbox estaba congelado en v0.7.1; al rebasear se descubre que origin/main ya está en v0.13.0 con v0.8.0 «Sin letra pequeña» (D84-D89: auditoría arreglada — cursores por sala, índices, 401 sin borrar nada, mensajes nunca mudos, TURN+AEC, señalización cifrada) y D90-D102 (avatares 3D GLB/SceneView). Se redacta `docs/INVESTIGACION-CHAT-TELEGRAM.md` sobre el estado real v0.13.0: quedan verificados como PENDIENTES B2/B5/B8 (kind en lista, badge al leer, streaming de adjuntos), QoS (reconexión que distingue red caída, NetworkCallback, sync en 1 query, AlarmManager 3ª capa, coalescing), UI (Paging 3, O(n²), miniaturas, tema oscuro) y llamadas (FGS microphone+camera, full-screen intent, cámara solo al aceptar, calidad adaptativa, stats). Investigación Telegram: clientes open source (GPL), TDLib y servidor Bot API Boost 1.0, SERVIDOR CERRADO; sus juegos/Mini Apps son HTML5 en WebView + puente JS (games.js / telegram-web-app.js) con el chat como capa social (mensaje de juego, récords del chat, mensaje de servicio al batir récord, multiplayer compartido caso de primera clase) — patrón replicable gratis en Cerlita: assets locales en el APK (offline-first ETECSA) + puente propio mínimo (tema/háptica/jugador/score) + `kind` de control `\u0000G` + récords E2EE + juegos por turnos sobre los propios mensajes + tiempo real reutilizando el canal de señalización de llamadas. Roadmap propuesto: S0' restos de auditoría → S1 QoS/llamadas → S2 features de chat → J0-J2 minijuegos; avatares retomables tras J2.


## D104 — Notificaciones ntfy: el despertador que no pasa por el chat del servidor (v0.14.0)

**Decisión (29-oct-2026).** El usuario pidió «añadamos las notificaciones con ntfy o
como se llame, quiero notificaciones en tiempo real y una app de calidad; si quieres
puedes usar Cloudflare Worker o algo similar que sea gratis si eso va a servir para
las notificaciones o cualquier otra cosa». D45 quedó degradada a «sustituto solo si
algún día se abandona Supabase» — el usuario la adelanta, y se implementa el canal
ntfy del doc v2 §9 adaptado al modelo rooms+DM de v0.7.0, con la mejora del diseño
original §9.2: **el ping lleva el sobre cifrado**, no solo el despertador.

**Arquitectura — 3 capas de aviso, cada una degrada limpia, ninguna decide:**
1. **Timbre Realtime WS** (D63, ya existía): el primario, instantáneo, pasa por Supabase.
2. **Canal ntfy** (nuevo): 1 tema por conversación `c+base32(HKDF(roomKey,
   salt=roomId, "ntfy-topic-v1", 16))` — 27 chars, 128 bits, no enumerable.
   Publicador = **el móvil emisor, tras el ack del outbox** (orden canónico
   ack→publish): el servidor NUNCA conoce el tema → cero cambios de backend, cero
   SQL, cero intermediarios. El ping lleva ≤8 filas del lote (mismo orden posicional
   que /sync, seq=0) y el receptor las ingiere por el MISMO camino del sync (mismo
   Mutex, dedup por PK): el mensaje se VE al instante sin round-trip — en ETECSA
   (RTT de segundos) esa es la diferencia que se nota, y con Supabase caído sigue
   llegando. Si el sobre no cabe (>[C.NTFY_PING_MAX]=3400 chars): despertador puro
   y el /sync que el propio ping provoca trae el lote.
3. **SyncWorker ~15 min** (ya existía): ahora drena la caché ntfy (12 h) ANTES del
   /sync — con la app cerrada y sin FGS, los pings pendientes se ingieren y
   notifican SIN abrir la app.

**Por qué ntfy y no Cloudflare Worker** (la pregunta del usuario): el publicador es
el propio móvil tras el ack — meter un Worker en el camino haría PEOR: vería los
temas y los sobres (hoy no existe intermediario), añadiría latencia y un SPOF más.
Cloudflare queda para el futuro bot de comandos (doc v2 §6.4), donde sí aporta.
Cero Firebase: `X-Firebase: no` en todo publish (ntfy.sh reenviaría a FCM por
defecto — hallazgo auditoría §4.3).

**Seguridad** (doc v2 §9.3, verificada e2e 32/32 en vivo): publicar es gratis para
quien conozca el tema, pero LEER solo con la roomKey — el AEAD tira lo inyectado
(tag inválido), el msgId (PK local) tira los replays, el eco propio es exists y no
notifica. El parse descarta en silencio, sin log (el atacante no aprende nada).
**H25 (nuevo hallazgo ntfy.sh)**: los bodies >4 KB NO se rechazan — se convierten en
ATTACHMENT con URL pública y 3 h de retención; el umbral 3400 lo evita de raíz y el
receptor tira el evento «You received a file…» porque no es JSON de ping.

**Límites ntfy.sh respetados** (12 h caché, 250/día, 60 req/5 s, 200 MB/día — los
polls cuentan desde ntfy 2.28): SIEMPRE `since` con cursor `ntfy_since` en meta
(solape 30 s que deshace el dedup por PK); el poll SOLO cuando el WS está caído
(cada 15 s, el bucle del NtfyChannel) o en la pasada del worker (15 min); 1 publish
por lote ackeado (≤8 filas), nunca por mensaje.

**Cambios de código**: `Crypto.ntfyTopic`; `net/Ntfy.kt` (publish/poll/parse con
presupuesto anti-spam); ChatEngine: `ntfyTopicFor` (patrón sigKeyFor), ping
best-effort tras el ack en drainOutbox, `ingestFromPush` público, y fillSeq ahora
`seq>0` SIN restricción de mine — un ajeno llegado por push con seq 0 recibe su seq
en el /sync de confirmación y los ✓✓ lo encuentran (antes el eco ajeno jamás se
rellenaba); `notify/NtfyChannel` (loop + estado para Ajustes); SyncWorker con drain
previo; `Prefs.ntfyUrl` (self-host/alterno si ETECSA bloquea ntfy.sh) + Ajustes:
estado del canal, servidor editable y botón «Guardar y probar» (publish+poll a un
tema aleatorio con latencia); `announceIncoming` público (lo llama el poller).
Cursor en `meta` → **cero migraciones de BD**.

**Qué NO es**: no sustituye al timbre (D63 sigue como primario), no es FCM (sin
Google), y con la app cerrada y el proceso MUERTO por el OEM sigue mandando el
SyncWorker de 15 min — el AlarmManager exacto (3ª capa de despertar) queda para
S1 (v0.15.0), como propone el roadmap de la investigación D103.

**Verificación en 3 capas** (misma regla que el resto del protocolo): e2e_ntfy.py
32/32 contra Supabase REAL + ntfy.sh REAL (espejo Python: tema, ping con sobre,
degradación, inyección/replay, multi-tema, since, attachment); NtfyTest JVM (KAT
cruzado Python↔Kotlin del tema, build/parse, límites); PushIngestTest Robolectric
(push→NewMsg, replay→dedup, fillSeq, eco mine, pending sin clave).


## D105 — S1 QoS del despertar: reconexión inteligente + NetworkCallback + coalescing + AlarmManager 3ª capa (v0.15.0)

**Decisión (29-oct-2026).** Ejecución del roadmap D103 (S1) tras el «dale haz todo S1 y el resto también». Cuatro arreglos que blindan la entrega, cada uno degrada limpio y ninguno rompe la arquitectura:

1. **Q2 — «sin red» ≠ «servidor caído»** (`RealtimeDoorbell`): `UnknownHostException`/`ConnectException` falla RÁPIDO y barato → el backoff se REINICIA a 5 s (antes escalaba a 60 s: hasta un minuto de ceguera justo cuando la red vuelve). Los fallos de servidor/protocolo mantienen la escalada 5→60 s de siempre. Además `kick()`: cancela la reconexión pendiente y conecta YA.
2. **Q3 — NetWatch** (`net/NetWatch.kt`, nuevo): `registerDefaultNetworkCallback` → `onAvailable` dispara kick de AMBOS timbres (in-app y FGS) + `requestSync()` + un poll ntfy best-effort (si lo caído era Supabase, ntfy trae el sobre por la otra ruta). El mensaje del apagón llega en el PRIMER segundo de red, no «hasta 5 min después».
3. **Q6 — coalescing de syncs** (`ChatEngine.requestSync`): N timbres en ráfaga = 1-2 ciclos de /sync, no N encolados por el Mutex (cada uno con su RTT). AtomicBoolean «hay uno en marcha → marca repetición»; la carrera del exit-window se cubre en el finally (jamás se pierde un despertar). Lo usan timbre, red y alarma; polling y workers siguen con `syncOnce` directo (ya tienen su propio ritmo).
4. **3ª capa — SyncAlarm** (`notify/SyncAlarm.kt`, nuevo): `setExactAndAllowWhileIdle` cada 4-6 min con jitter. En Doze es de las pocas excepciones que SÍ se ejecuta (WorkManager clavado a 15 min, FGS matable por el OEM): el peor caso con FGS muerto baja de 15 min a ≤6 min (Doze profundo lo espacia a ~9 min — límite del sistema, documentado). Sin el permiso exacto (Android 13+ no lo concede por defecto) degrada a `setAndAllowWhileIdle`. Se replanifica sola, revive en BOOT/MY_PACKAGE_REPLACED, y la enciende/apaga `syncDoorbell` (punto único D72) según sesión.

**Capas de aviso resultantes (todas gratis, ninguna decide)**: WS timbre (instantáneo) → ntfy con sobre cifrado (D104, no pasa por Supabase) → alarma exacta 4-6 min (Doze incluido) → WorkManager 15 min. El servidor sigue sin cambiar NADA.

## D106 — S1 /sync en UNA query con CTE + rate limiting (v0.15.0)

**Decisión.** Q4: cada poll pagaba 5 queries secuenciales (sesión, mensajes, UPDATE delivered, entregas de otros, wraps dm) — ~2,5 s de siesta por poll en red de 500 ms RTT, con el poll cada 15 s en foreground y por timbre. Ahora TODO en **un solo round-trip** con CTEs encadenadas: `sess` (valida token y saca uid) → `served` (mensajes, global o por sala) → `upd` (delivered_seq a partir de lo servido — CTE de modificación: Postgres garantiza ejecutarla exactamente una vez, se lea o no) → `rd` (entregas de otros) → `dmk` (wraps). El modo sala (?room=) comparte la MISMA query; `memb` distingue 403 de 204.

**La caché de sesión propuesta en la investigación resultó INNECESARIA**: al viajar la validación en la misma query no cuesta ningún round-trip extra y la revocación de un token sigue siendo INMEDIATA (mejor que cachear 60 s). **Rate limiting blando** por instancia cálida: 40 sync/min por token → 429 `rate_limited` (polling activo 12/min + timbre + red + alarma + worker caben de sobra; solo corta bucles desbocados; cliente con mensaje en español).

**Hallazgo del e2e**: `encode(..., 'base64')` de Postgres mete `\n` cada 76 chars → `translate(..., chr(10), '')` en nonce/ciphertext/wrapped (el smoke test lo cazó comparando bytes EXACTOS). Verificado en producción deploy v76: smoke 37/37 + receipts 24/24 + room_history 26/26 + dm 20/20.

## D107 — S1 llamadas a prueba de todo: FGS mic+cámara, full-screen intent, cámara solo al aceptar, calidad adaptativa + señal (v0.15.0)

**Decisión.** Los 4 pendientes de la investigación D103 §4.2 + el indicador de señal:

1. **FGS de llamada** (`calls/CallService.kt`, tipo `microphone|camera`): sin esto, apagar la pantalla o salir de la app podía CONGELAR el proceso y matar la llamada a los segundos aunque WebRTC estuviera conectado. Arranca al marcar/aceptar (siempre desde UI: permisos concedidos, requisito Android 14 para los tipos mic/cam) y se para al colgar — la notificación vive y muere con la llamada.
2. **Full-screen intent** (`USE_FULL_SCREEN_INTENT` + canal `calls` IMPORTANCE_HIGH): la llamada ENTRANTE con pantalla apagada/bloqueada/en otra app antes «no existía». Ahora prende la pantalla sobre el bloqueo con Rechazar directo (CallActionReceiver); en 14+ sin permiso FSI degrada a heads-up. Pantalla de llamada con `FLAG_SHOW_WHEN_LOCKED|TURN_SCREEN_ON` (los métodos Window.setShowWhenLocked fueron REMOVIDOS del SDK 37 — hallazgo de sesión) y `FLAG_KEEP_SCREEN_ON` en videollamada activa. Los permisos de llamada se piden ANTES de marcar (el FGS mic/cam los exige concedidos; las entrantes ya los pedían al aceptar).
3. **Cámara solo al aceptar** (`asCallee`): el receptor de una videollamada crea la PISTA (la negociación SDP la exige) pero SIN capturador — el indicador de cámara ya NO se enciende antes de tocar Aceptar; nada sale del teléfono. `startCameraIfNeeded()` en `accept()`.
4. **Calidad adaptativa**: captura 720p si la cámara lo soporta (suelo 640×480 verificado por `getSupportedFormats`) + techo 900 kbps (`RtpSender.parameters.encodings.maxBitrateBps`) que protege los datos en ETECSA — por debajo del techo WebRTC adapta resolución/fps solo (GCC/REMB), la llamada nunca se corta por calidad.
5. **Señal** (`getStats` cada 3 s → nivel 0/1/2 por RTT del candidate-pair nominado): barritas + «señal débil/muy mala» en la llamada activa — «se cortó» deja de ser la primera noticia de red mala.

## D108 — S0' fluidez: ventana de historial, miniaturas por bytes y Cerlita noche (v0.15.0)

**Decisión.** Los tres pendientes de UI de la auditoría:

1. **U2 — ventana de historial** (en vez de Paging 3): `observeChatWindow(room, limit)` emite SOLO los últimos 200 mensajes; el scroll arriba sube el límite +300 (carga bajo demanda estilo Telegram, claves estables → sin saltos). MISMO objetivo que Paging 3 (no re-emitir 5 000 filas por cada ✓) con ~30 líneas y CERO dependencias/arquitectura nueva (LazyPagingItems+reverseLayout traía más riesgo que valor). El chip «↓N» y el auto-scroll ahora solo reaccionan a mensajes NUEVOS al final (cambia el último msgId): el prepend de historia no mueve nada ni inventa «nuevos».
2. **U3 — miniaturas**: decodificación con `inSampleSize` al tamaño de burbuja (512 px) + LruCache por BYTES (6 MB) en vez de 24 bitmaps completos de 4-9 MB; el visor a pantalla completa decodifica su propia versión 1440 px desde el archivo en caché.
3. **U5 — Cerlita noche** (opción 5 en Ajustes): la MISMA rueda cálida de D77 invertida — coral aclarado #E8A0AC para contraste AA, fondos ciruela #20191C/#291F23, burbujas enviadas rosa apagado #6E454E con texto rosado claro. «De noche la app es rosa claro» resuelto sin perder la identidad.

## D109 — S0' adjuntos sin OOM: 8 MB, subida streaming y reintentable, limpieza diaria (v0.15.0)

**Decisión.** B8 de la auditoría. `MAX_FILE` 20→8 MB (pico de RAM al cifrar ~24 MB transitorios, aceptable en gamas bajas; las subidas larguísimas por ETECSA dejan de ser la norma). **El AEAD a trozos (formato v2) se evaluó y se RECHAZÓ**: rompería la descarga de TODOS los adjuntos ya en el servidor — el coste no justifica el beneficio con tope de 8 MB. Lo que sí cambió: (1) `storageUploadFile` — el cuerpo sale del ARCHIVO por bloques de 32 KB (RequestBody con FileInputStream), nunca entero en RAM (la versión por bytes quedó sin uso y se retiró); (2) el blob cifrado se escribe a attcache ANTES de subir — si el proceso muere a mitad de subida, `recoverOrphanUploads` marca FALLIDA y el botón Reintentar tiene el material entero (antes: reenviar a mano); (3) el picker comprueba el TAMAÑO por metadatos antes de leer un byte; (4) `AttCacheCleanWorker` diario: attcache (dl-* y *.bin) crecía INDEFINIDAMENTE (auditoría S4) — borra lo de +30 días, lo vivo se re-descarga al abrirlo.

## D110 — S2 «el chat que esperabas»: responder, reacciones, editar/borrar, escribiendo…, búsqueda FTS (v0.15.0)

**Decisión.** Las 5 features más echadas de menos, todas E2EE con el patrón de prefijos de control de siempre (`\u0000` + letra que un texto jamás produce): **Q** citar · **E** editar · **D** borrar · **R** reaccionar.

- **Responder**: la referencia viaja E2EE DENTRO del plaintext (`\u0000Q{"i","t"}`) — el servidor sigue sin ver relaciones entre mensajes. Tira de cita sobre el composer + avance citado dentro de la burbuja (fallback «Mensaje original» si la cita está fuera de la ventana).
- **Reacciones**: UNA por usuario y mensaje (la nueva pisa, como WhatsApp) — tabla `reactions` (DB v7); fila de emojis rápidos en el long-press (❤️😂👍😮😢🔥) + chips bajo la burbuja con contador y resaltado de la propia.
- **Editar** (propio, texto) y **Eliminar para todos** (tumba `ST_DELETED` discreta): `applyEdit`/`applyDelete` verifican AUTOR (senderUid) — un control ajeno jamás toca mensajes de otro.
- **«Escribiendo…»**: broadcast Realtime EFÍMERO (evento `typ` por el canal que ya usa la señalización de llamadas) — no persiste en el servidor, no ocupa el /sync, no deja rastro; throttle 2,5 s, expira a los 4 s, el título del chat lo muestra en vivo.
- **Búsqueda global FTS4** (la F4 de la auditoría): tabla virtual `messages_fts` con rowid=messages.rowid y 3 triggers (insert/delete/update-of-body), creada por callback `onOpen` IDEMPOTENTE (instalaciones nuevas y migradas quedan idénticas; Room ignora tablas ajenas). Por qué no `@Fts4(contentEntity)`: Room no crea los triggers de sincronización — el índice quedaría huérfano al primer INSERT. Por eso la query es cruda (`searchRaw`, suspend+IO: Room prohíbe query() en main thread — hallazgo cazado por MigrationTest). Input escapado con comillas por término (AND implícito, sin sorpresas de sintaxis FTS). Lupa en la pestaña Chats → SearchScreen con debounce 250 ms.

**DB v6→v7** (MIGRATION_6_7 REAL: replyTo + reactions), menú de long-press para TODOS los mensajes (Reintentar ya no era el único habitante). Tests: ChatMsgsTest (framing) + migración con cita+reacción+FTS sobre historial migrado. **128/128**.

## D111 — J0 minijuego 2048: el patrón de Telegram, E2EE y offline (v0.15.0)

**Decisión.** Ejecución del J0 del roadmap D103: replicar el patrón «el juego ES un mensaje del chat» de Telegram sin su servidor. **Assets locales del APK** (`assets/games/2048/index.html`, ~13 KB propio en vanilla JS, 0 red — crítico en ETECSA) dentro de un WebView que SOLO carga `file:///android_asset` (http/https bloqueado) con **puente mínimo** `window.Cerlita`: `player()` (quién juega, del perfil E2EE local), `theme()` (paleta Cerlita VIVA — claro o noche según Ajustes), `record()` (récord de la pareja), `haptic()` (VibrationEffect), `submit(score)`. Cinco métodos inocuos, todo local, nada de initData que falsificar.

**Burbuja de juego**: mensaje VISIBLE cuyo cuerpo es `\u0000G{"g","s"}` cifrado como cualquier texto (el servidor solo ve ciphertext) — burbuja con título, récord de la pareja en vivo y botón «Jugar» (menú adjuntar gana «Juego 🕹️»). **Récord E2EE de la pareja**: cada puntuación viaja como control INVISIBLE kind=KIND_GAME_SCORE; gana la mayor; persiste en meta `gm_<room>_<game>_r`; `gameRecords` StateFlow vivo — la burbuja se actualiza sola cuando alguien bate la marca, con toast «¡Nuevo récord de la pareja!». Notificaciones y citas muestran «🎮 Juego 2048», no el sobre crudo. J1 (catálogo 4-5 juegos + mensajes de servicio) y J2 (conecta-4 por turnos sobre mensajes) reutilizan TODO esto tal cual.

## D112 — Avatares 3D «bien hechos»: escultura v2 con puerta de calidad VLM (v0.16.0)

**Contexto.** El usuario probó los avatares de v0.13/v0.15 y pidió «consígueme los avatares bien hechos que quiero», aportando un plan externo (VRoid Studio, three.ws, Sketchfab/Sloyd/Meshy, SceneView). Ya verificado en AVATAR-3D-ANALISIS §9 (y re-verificado hoy): VRoid es freeware propietario de humanoides anime, three.ws es web puro, Sloyd tiene descargas directas CC-BY pero koalas de «juguete», Meshy exige cuenta y trae topología sucia — la vía sigue siendo GLB propio + SceneView (D99/D101). El problema no era la arquitectura: era la CALIDAD de la escultura. Diagnóstico VLM de las capturas del usuario: expresión vacía, ropa «pintada», gorro flotando, proporciones «muñeco de nieve».

**Decisión.** Overhaul del generador Blender (`tools/avatares3d/build_avatars.py`) con **QA visual ITERATIVO por VLM como puerta objetiva** (regla D83 «mirar ANTES de subir», ahora automatizada): cada iteración regenera, mide (PIL) y pregunta al modelo de visión con rubro; se aprueba solo con «APROBADO» en ambas familias Y en los rasters a 384 px. 7 iteraciones koala + 2 cerdita. Lo que cambió:

1. **Cara** (el driver nº1 de calidad): ojos 40% mayores con **brillo blanco geométrico** (`EyeHiL/R`, `NoseHi` — la firma kawaii), **boca U ancha** siguiendo la superficie (cadena de cilindros+esferas; la cápsula plana leía «neutra o sorprendida»), rubor mayor y más bajo, nariz koala brillante con «nariz húmeda».
2. **Materiales vinilo**: body rough 0.34 (era 0.55 mate). **Lección medida (PIL, no a ojo)**: tinta a rough 0.12 + luz clave → sobre-especular → LAVA los rasgos a gris (lum 125-175) y la cara leía «neutra»; tinta a 0.32 y el brillo de ojos como GEOMETRÍA, no especular.
3. **Cuerpo**: cuello, orejas caídas 42° con interior rosa (cerdita), cola de 4 anillos = rizo (3 leía «C»), brazos/pies regordetes.
4. **Ropa con GROSOR real** (solidify 0.012-0.014): cuellos y puños acanalados un tono abajo (helper `darken`), dobladillo al borde de la falda, pañuelo del traje, suelas oscuras que «apoyan». Holgura 1.16 (era 1.135 = pegada al cuerpo).
5. **Accesorios**: gorro fiesta anclado a la superficie del elipsoide + banda de contacto (tras 2 iteraciones de «flota»).
6. **QA/rasters**: el estudio 3-luces + MUNDO PASTEL de D102 en `setup_render` — sin mundo no hay Iluminación Global en Cycles y los renders salían a 40/255 (casi negros; medido).
7. **App**: `Avatar3DPreview` con estudio en vivo (fill cálido 30 klux + rim frío 20 klux vía `LightNode` DIRECTIONAL sobre la main por defecto, `fillLightNode = null`). GLB y rasters drop-in (mismos nombres de nodos y `av_*.webp`) → wire y E2EE intactos, perfiles guardados intactos.

Validación: `validate_glbs` 16/16 prendas + 3 anims + skin por GLB; rasters determinismo Δ=0 y composites vs directo 0.04-0.09% px distintos; 91/91 tests. APK 39,8 MB (v0.14.0: 37,5), firma idéntica 37b6d4…, versionCode 18.
