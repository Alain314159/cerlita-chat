# Investigación: chat al siguiente nivel, llamadas y minijuegos estilo Telegram

> **D103 · 29-oct-2026 · SOLO INVESTIGACIÓN** (petición explícita: «esta parte solo investiga»). Sin código.
> Base verificada: repo `Alain314159/cerlita-chat` @ `f800725` (**v0.13.0**, versionCode 15), auditoría del
> 28-oct-2026 (`upload/AUDITORIA-2026-10-28.md`), verificación del código de esta sesión, y documentación
> oficial de Telegram vigente a octubre 2026 (URLs en §9).
> Nota de sesión: el sandbox estaba congelado en v0.7.1; al integrar origin/main se confirmó que la
> auditoría YA se ejecutó en v0.8.0 «Sin letra pequeña» (D84-D89) y que v0.9.0→v0.13.0 fue la fase de
> avatares 3D (D90-D102, ahora pausada). Este documento parte del estado REAL v0.13.0.

---

## 1. Qué se pidió y qué hay en este documento

El usuario decidió **pausar el trabajo de avatares** («olvidemos los avatares por ahora, no es lo más
importante») y reorientar el proyecto hacia tres objetivos: (1) que el chat **funcione bien, los
mensajes lleguen bien y no haya retrasos** — todo lo más rápido y fluido posible; (2) **mejorar las
llamadas y videollamadas**; y (3) **investigar cómo Telegram hace sus minijuegos, bots y toda esa
plataforma**, porque quiere minijuegos así en Cerlita Chat — esta última parte **solo investigación**.

Este documento responde a los tres. Punto de partida importante descubierto en la sesión: el sandbox
local estaba congelado en v0.7.1 y, al integrar origin/main, apareció que **la auditoría ya se ejecutó**:
v0.8.0 «Sin letra pequeña» (D84-D89) arregló los hallazgos críticos (cursores por sala, índices, 401,
mensajes nunca mudos, TURN + eco, señalización cifrada) y v0.9.0→v0.13.0 fue la fase de avatares 3D
(D90-D102, ahora pausada). Esta sesión **verificó en el código v0.13.0** qué queda realmente pendiente
del plan de QoS, y añade lo que la auditoría no cubre: **la investigación completa de la plataforma de
juegos/bots de Telegram y cómo traducirla a Cerlita sin gastar un centavo**. No se escribe ni una línea
de código.

**Estructura**: §2-3 diagnóstico y plan del chat · §4 llamadas/videollamadas · §5 qué es open source
en Telegram de verdad · §6 cómo funcionan sus juegos y bots · §7 propuesta de traducción a Cerlita ·
§8 roadmap recomendado · §9 fuentes.

---

## 2. Diagnóstico del chat actual (verificado en código v0.13.0)

### 2.1 Lo que ya está bien y NO se debe romper

La arquitectura de mensajería es sólida y está bien probada. El **outbox es idempotente** (`msg_id`
UUIDv7 como PK + `ON CONFLICT DO NOTHING` en el servidor): un reintento tras un timeout ambiguo nunca
genera duplicados, y un apagón de días no pierde nada. El **timbre WebSocket solo despierta, jamás
transporta contenido**: el push no lleva ni un byte de plaintext, y la fuente de verdad siempre es
`GET /sync` por cursor — la reconexión es «alcance de reloj», nada se pierde. La **reconexión tiene
backoff con jitter** y re-sincroniza al reconectar. El **cifrado es consistente** (ChaCha20-Poly1305
con AAD ligado a `msgId|sender|room`, claves derivadas por mensaje vía HKDF, claves selladas por
Keystore). Y los recibos de entrega/lectura funcionan de extremo a extremo. Todo esto es el equivalente
casero de lo que hace cualquier mensajero serio, y está bien hecho.

Además, **v0.8.0 ya ejecutó la parte crítica de la auditoría** (confirmado en el código v0.13.0):
**D84** — las notificaciones ya no filtran por antigüedad (`Notifier.kt` filtra solo `!mine`, 1 por
sala con el último mensaje: nunca más mensajes mudos) y el timbre revive tras actualizar
(`MY_PACKAGE_REPLACED`); **D85** — un 401 expira la sesión sin borrar nada local; **D86** — índices
`idx_msg_room_kind_ts`/`idx_msg_room_mine`/`idx_msg_state` y fin del `fallbackToDestructiveMigration`;
**D87** — cursores por sala (el bug B1 del historial invisible al unirse, con E2E 26/26); **D88** —
**TURN gratuito** servido por Edge Function `/turn` con credenciales en secrets del servidor
(`TurnConfig.kt`; OpenRelay por defecto, ExpressTURN/Cloudflare a 1 000 GB/mes con solo re-exportar
variables) + `echoCancellation`/`autoGainControl`/`noiseSuppression` en audio; **D89** — **señalización
de llamadas cifrada** (ChaCha20 con `Crypto.sigKey`, AAD `sig-v1|callId|roomId`, compat legacy claro).

### 2.2 Lo que SIGUE pendiente (verificado en el código v0.13.0)

| # | Hallazgo | Dónde (v0.13.0) | Impacto en «que lleguen bien» |
|---|----------|-------|------------------------------|
| B8 | Adjuntos: subida sin servicio propio, sin streaming, `MAX_FILE` 20 MB | `core/C.kt:54` (`20 * 1024 * 1024`), `ChatEngine.sendAttachment` | OOM / subida muere al salir de la app |
| Q2 | Reconexión del timbre no distingue «sin red» de «servidor caído» | `RealtimeDoorbell.kt` (sin manejo de `UnknownHostException`) | Hasta 60 s de ceguera al volver la red |
| Q3 | No hay despertar al **volver la conectividad** (`NetworkCallback`) | grep sin resultados en el paquete | Tras 20 min sin red, hasta 5 min más de espera |
| Q4 | `/sync` sigue con 4-5 queries secuenciales por poll (sin CTE ni caché de sesión) | `sync/index.ts` | ~2,5 s extra por poll en red de 500 ms RTT |
| Q6 | Sin coalescing: cada timbre dispara su `syncOnce` (N salas → N syncs encolados) | `ChatEngine.kt` (mutex serializa pero no fusiona) | Polls inútiles encadenados |
| U2 | **Sin Paging 3**: `observeChat` re-emite TODO el historial por Flow | `db/Db.kt:186`, 0 en gradle | Recomposición masiva con cada ✓/cambio de estado |
| U3 | Imágenes decodificadas a 1600 px para burbujas de 190 dp; LruCache de 24 entradas | `AttachmentUi.kt` | Presión de memoria y re-decodes |
| U5 | Tema oscuro Cerlita | `Theme.kt` | La app es rosa claro de noche |
| — | AlarmManager `setExactAndAllowWhileIdle` como 3ª capa de despertar (≤5 min) | — | FGS muerto por OEM = hasta 15 min sin avisar |

**A confirmar en uso real** (indicios de arreglo encontrados en v0.13.0): **B2** — `kind` YA viaja en
`RoomListItem` (`db/Db.kt:385`): comprobar que los botones de llamada salen en los DMs abiertos desde
la lista; **B5** — `resetUnread`/`markRead` presentes en `ChatScreen`+`ChatEngine`: comprobar que el
badge baja al leer dentro de la sala. Si alguno falla en las pruebas, entra en S0' tal cual. El O(n²)
mayor (U1) ya está resuelto con `itemsIndexed` (queda un `indexOfFirst` residual puntual).

### 2.3 Matriz de latencia real (lo que hoy «se siente»)

| Escenario | Latencia actual | Objetivo |
|-----------|-----------------|----------|
| App abierta, timbre vivo | ~0,5-2 s | ya bueno |
| App abierta, timbre muerto (OEM lo mató) | ≤ 15 s | ≤ 5 s |
| Background con FGS «avisos instantáneos» (ON por defecto desde v0.7.1) | ~1-3 s | ya bueno |
| Background con FGS muerto | **hasta 15 min** (WorkManager) | ≤ 5 min |
| Red lenta ETECSA | +30-90 s | mitigar por tamaño/queries |

**Conclusión del diagnóstico**: el diseño es correcto y lo peor de la auditoría ya está arreglado
(v0.8.0). Los retrasos restantes vienen de la falta de capas de respaldo cuando el OEM mata el FGS
(alarm + NetworkCallback), de queries de más por poll (Q4/Q6), y de consultas/UI que escalan mal
(Paging, miniaturas). Todo tiene arreglo barato y verificado en este documento.

---

## 3. Cómo lograr «que lleguen bien, sin retrasos, rápido y fluido»

### 3.1 Fiabilidad de entrega (que nada llegue tarde ni en silencio)

La notificación «1 por sala con el último mensaje» ya está (D84). Lo que falta es **blindar el
despertar**. Hoy hay dos capas: el FGS `remoteMessaging` (tipo correcto, arrancable desde
BOOT_COMPLETED) y el WorkManager periódico de 15 min. La tercera capa es **AlarmManager
`setExactAndAllowWhileIdle` cada 4-5 min**: en Doze es de las pocas excepciones que sí se ejecuta, no
tiene el mínimo de 15 min de WorkManager, cuesta 0 € y ~80 líneas; degrada a `setAndAllowWhileIdle` si
el usuario no concede alarma exacta (auditoría §4.3). Junto a eso: **reconexión inteligente** que
reinicia el backoff a 5 s ante `UnknownHostException`/`ConnectException` (fallo de red, Q2) en vez de
subirlo a 60 s, **`registerDefaultNetworkCallback` → `syncOnce()` inmediato** al volver la red (Q3), y
**coalescing de syncs** con un `AtomicBoolean` para no encolar N sincronizaciones cuando llegan N
timbres (Q6). Nota: `LOCKED_BOOT_COMPLETED` fue evaluado y **descartado a propósito** en D84 (exige
directBootAware y migrar DB/vault a storage device-encrypted; un timbre bajo direct boot no puede leer
nada de lo que necesita) — decisión razonada, no un olvido.

### 3.2 Velocidad de red (menos round-trips por poll)

Cada poll paga 4-5 queries secuenciales en el servidor más el RTT; en una red de 500 ms eso son ~2,5 s
de siesta por poll, y el poll ocurre cada 15 s en foreground. La corrección pendiente: **fusionar las
queries del `/sync` en una sola con CTEs y cachear el lookup de sesión por token** (60 s en memoria de
la Edge Function) — con 2 usuarios, revalidar la sesión en cada poll es un desperdicio puro (Q4). Los
índices y los cursores por sala ya están (D86/D87); el gzip > 512 B ya existe y es correcto; y la
serialización binaria (protobuf) no compensa para el mensaje suelto (~44 B de diferencia, irrelevante
a 1 kb/s — análisis en `arquitectura-v2-ntfy.md` §3.2: la unidad de coste real es el REQUEST, no el byte).

### 3.3 Fluidez de UI (que se sienta instantáneo)

El O(n²) mayor ya está resuelto (`itemsIndexed`). Quedan cuatro arreglos con la mejor relación
valor/esfuerzo del plan: **Paging 3** en el historial para no re-emitir 5 000 filas con cada tick de
estado (U2); **miniaturas decodificadas al tamaño de la burbuja** (`inSampleSize`) con caché por bytes
en vez de 24 entradas de bitmap completo (U3); el **chip «↓ N mensajes nuevos»** con scroll
condicional en vez del `animateScrollToItem` incondicional; y el **tema oscuro Cerlita** (U5,
inversión de la paleta medida en D77). Con esto, los objetivos de frame time < 16 ms p95 y arranque en
frío < 1,2 s son alcanzables sin tocar la arquitectura.

---

## 4. Llamadas y videollamadas: cómo mejorarlas

### 4.1 Lo que ya está resuelto (v0.8.0 — verificado en código)

Tras el fix D81 (señalización ordenada SDP→ICE con IceQueue) y v0.8.0, la llamada ya conecta donde
antes moría: **TURN gratuito** con credenciales servidas por la Edge Function `/turn` (D88 — nunca en
el APK, rotables sin actualizar; OpenRelay por defecto, ExpressTURN/Cloudflare a 1 000 GB/mes con solo
re-exportar 3 env; el medio sigue E2EE DTLS-SRTP: el relay transporta bytes opacos), **eco cancelado**
(`echoCancellation`/`autoGainControl`/`noiseSuppression`, `CallSession.kt:180`) y **señalización
cifrrada E2EE** (D89: offer/answer/ICE/bye bajo ChaCha20 con `Crypto.sigKey`; el servidor solo ve topic
+ `callId` aleatorio, con compatibilidad legacy para teléfonos sin actualizar). El objetivo de la
auditoría de **> 97 % de primera conexión** está al alcance de lo que falta (§4.2, puntos 1-3).

### 4.2 Lo que sigue pendiente (verificado en código v0.13.0)

| # | Mejora | Detalle | Coste |
|---|--------|---------|-------|
| 1 | **FGS de llamada `microphone`+`camera`** | El manifest NO declara `FOREGROUND_SERVICE_MICROPHONE`/`_CAMERA` (verificado): si la app pasa a background o se apaga la pantalla, Android puede congelar el proceso y la llamada muere | Bajo |
| 2 | **Full-screen intent para entrantes** | Sin `USE_FULL_SCREEN_INTENT` ni `setFullScreenIntent` (verificado): con pantalla apagada o en otra app, la llamada entrante no existe para el usuario — el ringtone+vibración solo actúan con la app visible o el FGS timbre vivo | Medio |
| 3 | **Cámara solo al aceptar** | `CallSession.kt:188` sigue arrancando la cámara al RECIBIR la videollamada — el indicador de cámara se enciende ANTES de tocar «Aceptar» (privacidad); separar `startCameraIfNeeded()` → `answer()` | 30 min |
| 4 | Calidad adaptativa de vídeo | `adaptOutputFormat`: 720p con red buena, 180p en 2G (hoy fijo 640×480@24) | Medio |
| 5 | Indicador de calidad de señal | `pc.getStats()` → «señal débil» en vez de «se cortó» | Medio |
| 6 | Compartir pantalla | `MediaProjection` + `ScreenCapturer` (futuro, no crítico para 2 personas) | Medio |

### 4.3 Qué hace Telegram (y qué no copiar)

Las llamadas de Telegram son E2EE con **su propio protocolo basado en MTProto** (intercambio
Diffie-Hellman + verificación por emojis, según su FAQ técnico) — no usan señalización WebRTC
estándar. Nosotros usamos **WebRTC (libwebrtc vía stream-webrtc-android 1.1.3), el estándar de WhatsApp
y Signal**, con medio DTLS-SRTP P2P: esa elección es correcta y no se cambia. La lección aplicable de
Telegram no es el protocolo sino la **obsesión por conectar en redes malas** — requisito de diseño
explícito de MTProto según su propio FAQ. En WebRTC eso se traduce en TURN de respaldo (ya hecho) +
códecs/bitrate adaptativos (§4.2 punto 4).

---

## 5. Telegram: qué es open source de verdad (y qué no)

«Telegram es de código abierto» es cierto solo a medias, y conviene tenerlo claro antes de decidir
qué se puede copiar, estudiar o reutilizar. La verificación de esta sesión (repos y docs oficiales,
octubre 2026):

| Componente | ¿Open source? | Licencia | Qué significa para nosotros |
|------------|---------------|----------|-----------------------------|
| Cliente Android oficial (DrKLO/Telegram) | Sí | GPL (copyleft) | Se puede **leer y estudiar** cómo implementan el WebView de juegos, la DB local, el streaming de medios. Copiar código exige publicar el nuestro bajo GPL — **estudiar sí, copiar con cuidado** |
| Telegram X (TGX-Android) | Sí | GPL | Ídem; código más limpio en varias áreas |
| Clientes iOS/Desktop/Web | Sí | GPL | Referencia del mismo diseño multiplataforma |
| **TDLib** (tdlib/td) | Sí | **Boost Software License 1.0** (permisiva) | Librería C++ para **construir clientes Telegram**: red, cifrado, almacenamiento local, orden garantizado de updates. Reutilizable comercialmente sin copyleft. Solo sirve para hablar con *los servidores de Telegram* |
| **Servidor Bot API** (tdlib/telegram-bot-api) | Sí | **Boost 1.0** | El servidor de bots es autoalojable; relevante si algún día se quisiera un backend de bots propio |
| Spec del protocolo MTProto 2.0 | Abierta (documentada) | — | Diseño de referencia para redes débiles: AES-256-IGE, RSA-2048, SHA-256, archivos de hasta 4 GB |
| **Servidor de Telegram** | **NO** | — | El corazón (el clúster que sirve a 900 M+ usuarios) es cerrado. Existe una reimplementación NO oficial en Go (teamgram, Apache-2.0, 2,3 k estrellas) — nada que usar aquí |

**Conclusión**: no hay un «servidor de Telegram» que llevarse; lo aprovechable es (a) el **conocimiento
de diseño** (MTProto para redes débiles, DB local-first en el cliente, todo se sirve de la DB local y
nunca de la red — por eso Telegram «vuela»), y (b) el **modelo de su plataforma de bots/juegos** que
es, en esencia, un patrón de integración que sí podemos replicar con nuestros propios medios.

---

## 6. Cómo hace Telegram sus juegos, bots y Mini Apps (docs oficiales)

### 6.1 Los bots: apps conversacionales sobre un API HTTP

Un bot de Telegram es una cuenta especial controlada por programa. El desarrollador registra el bot
con @BotFather (que además crea los juegos, §6.2), obtiene un token, y a partir de ahí el bot es un
servidor HTTP cualquiera que (a) recibe updates por webhook o long-polling de `api.telegram.org`, y
(b) responde con métodos del Bot API (`sendMessage`, `sendGame`, `setGameScore`, `answerWebAppQuery`…).
No hay SDK obligatorio ni lenguaje obligatorio: el «cerebro» del bot vive en cualquier servidor (o
incluso en una Edge Function). Los bots pueden mandar mensajes, teclados personalizados, pagos,
ubicaciones, encuestas… y desde 2022, **abrir Mini Apps** (§6.3). El servidor oficial del Bot API es
open source (Boost 1.0) y autoalojable, con modo `--local` que sube el límite de archivos a 2 GB.

### 6.2 Los juegos (2016): HTML5 en un WebView con puente JS

La plataforma de juegos original funciona así, paso a paso (doc oficial `core.telegram.org/bots/games`):

1. El desarrollador crea el juego con `/newgame` en @BotFather (nombre, descripción, foto).
2. El bot lo envía al chat con el método **`sendGame`** (o por inline mode): aparece un **mensaje
   normal con botón «Play»** integrado — el juego ES un mensaje del chat.
3. Al pulsar Play, el bot recibe un callback query y entrega la **URL del juego para ese usuario**.
4. El juego se abre en el **navegador in-app de Telegram (un WebView)**: no hay motor de juegos
   nativo — el juego es una **página HTML5 normal (HTML/CSS/JS)**, jugable sola o compitiendo en
   grupos y chats 1-a-1.
5. La página incluye `<script src="https://telegram.org/js/games.js">` que expone **`TelegramGameProxy`**:
   `shareScore()` (compartir el récord a cualquier chat como mensaje), `initParams` (parámetros de
   lanzamiento en el fragmento de la URL). La librería solo funciona dentro de Telegram.
6. **Récords**: el bot llama a `setGameScore` (Bot API); el mensaje del juego muestra la tabla de
   récords del chat y, cuando alguien bate el récord, **Telegram manda un mensaje de servicio al
   chat** («X batió el récord de Y»). `getGameHighScores` sirve para pintar tablas dentro del juego.

Desde abril de 2022, Telegram recomienda construir juegos como **Web Apps / Mini Apps**, que es la
plataforma evolucionada (más APIs, fullscreen, sensores). El modelo de «el juego es una página web +
un mensaje en el chat + récords compartidos» es el corazón replicable.

### 6.3 Las Mini Apps (hoy): la plataforma completa

Una Mini App es también **HTML5 en un WebView**, pero con un puente JS mucho más rico:
`<script src="https://telegram.org/js/telegram-web-app.js">` → objeto global `window.Telegram.WebApp`.
Capacidades verificadas en la doc oficial (`core.telegram.org/bots/webapps`), con la versión de Bot
API que las trajo:

| Capacidad | API | Nota para juegos |
|-----------|-----|------------------|
| Identidad segura | `initData` (válido con HMAC del token del bot), `initDataUnsafe` | El juego sabe quién eres sin login |
| Tema del cliente **en vivo** | `themeParams`, `colorScheme` | El juego se ve «nativo», día/noche |
| Botones nativos | `BottomButton` (ex MainButton), `BackButton`, `SettingsButton` | UI nativa sobre el WebView |
| **Háptica** | `HapticFeedback.impactOccurred/notificationOccurred/selectionChanged` | La sensación «viva» de Telegram |
| Almacenamiento | `CloudStorage` (por bot, sincronizado), `DeviceStorage`/`SecureStorage` (9.0, abr-2025) | Progreso y récords persistentes |
| **Pantalla completa + landscape** | `requestFullscreen`, `safeAreaInset` (8.0, nov-2024 — «allowing them to host more games») | La mejora explícitamente pensada para juegos |
| **Sensores de movimiento** | `Accelerometer`, `Gyroscope`, `DeviceOrientation`, `lockOrientation` (8.0) | Juegos tipo «inclina el teléfono», VR inmersiva |
| Ubicación | `LocationManager` (8.0) | Juegos con puntos de interés |
| Compartir | `shareMessage` (media a cualquier chat o historia), `switchInlineQuery`, `downloadFile` | Viralidad |
| Datos al bot | `sendData` (service message), `answerWebAppQuery` (mensaje en nombre del usuario al chat) | El resultado de la partida **vuelve al chat como mensaje** |
| Pagos | `openInvoice`, Telegram Stars, suscripciones (8.0) | Monetización (no aplica a Cerlita) |
| Biometría | `BiometricManager` | Desbloquear contenido |

**Lanzamiento (7 vías)**: botón de teclado `web_app`, botón inline `web_app`, botón de menú del bot,
Mini App principal desde el perfil, inline mode, **enlace directo** `t.me/bot?startapp=` y menú de
adjuntos. El dato clave para nosotros: la Mini App principal y el enlace directo reciben `chat_type`
y `chat_instance`, lo que Telegram documenta explícitamente como soporte para **«uso compartido y
concurrente por varios miembros del chat — pizarras colaborativas, pedidos grupales, juegos
multijugador»**. Es decir: el multiplayer compartido es un caso de uso de primera clase.

### 6.4 La lección de arquitectura (lo que de verdad hay que copiar)

Telegram **no tiene motores de juegos nativos ni plugins mágicos**. Su ecosistema de juegos es
exactamente esto, y nada más:

```
[HTML5 del juego, en cualquier servidor HTTPS]
        │  carga un <script> que expone un objeto JS
        ▼
[WebView in-app del cliente]
        │  puente nativo ↔ JS (identidad, tema, háptica, almacenamiento, compartir)
        ▼
[El chat como capa social: el juego se lanza desde un mensaje,
 el resultado/récord vuelve al chat como mensaje, el récord es del chat]
```

Cualquier app con WebView y mensajería puede replicar este patrón. La «magia» de Telegram está en los
detalles de integración (identidad sin login, tema en vivo, háptica, récords ligados al chat) — todos
replicables localmente en Cerlita sin depender de nada externo.

---

## 7. Traducción a Cerlita Chat: minijuegos «como en Telegram» (propuesta, sin implementar)

### 7.1 Mapeo de conceptos

| Telegram | Cerlita equivalente | Coste |
|----------|--------------------|-------|
| Bot + BotFather | **No aplica** — app de 2 personas; los juegos vienen de fábrica | 0 |
| Servidor HTTPS del juego | **Assets empaquetados en el APK** (`assets/games/<id>/`) — 0 red, funciona offline (crítico en ETECSA); ampliable desde Storage sin actualizar la app | bajo |
| WebView in-app | `WebView` de Android (gratis, 0 dependencias) | bajo |
| `telegram-web-app.js` | **Puente propio mínimo**: `@JavascriptInterface` → objeto JS global `Cerlita` con ~8 métodos | medio |
| `initData` (identidad) | No hace falta: el WebView es local y de confianza; el puente pasa el nombre/avatar del jugador directamente | 0 |
| `themeParams` en vivo | `Cerlita.theme()` — inyecta la paleta Cerlita medida (D77) como CSS variables | bajo |
| `HapticFeedback` | `Cerlita.haptic("light")` → `VibrationEffect` (1 línea por tipo) | bajo |
| `sendGame` → mensaje con botón Play | **`kind` nuevo de control `\u0000G`** (patrón existente de `\u0000P` perfil / `\u0000S` sala / `\u0000L` leído / `\u0000A` adjunto): burbuja con miniatura + botón «Jugar» que abre el WebView | medio |
| `setGameScore` + tabla de récords del chat | Mensaje de control E2EE `{gameId, score}` — la UI muestra «Récord de la pareja: X» y mensaje de servicio al batirlo (igual que Telegram) | medio |
| `shareScore` a otro chat | El resultado viaja como mensaje E2EE al chat — aquí incluso mejor: ya está cifrado | bajo |
| Multiplayer (`chat_instance`) | **Por turnos**: las jugadas viajan como mensajes de control E2EE (ajedrez/conecta-4 por correspondencia — el outbox ya garantiza entrega). **Tiempo real**: reutilizar el canal de broadcast Realtime que ya usa la señalización de llamadas (patrón D75/D81/D89 probado) | medio/alto |

### 7.2 Arquitectura propuesta (boceto)

```
[assets/games/2048/index.html]        (o descargado de Storage si hay catálogo remoto)
        │ <script> window.Cerlita </script>
        ▼
[GameScreen: WebView local]  ←→  [CerlitaBridge (Kotlin)]
   · Cerlita.player()        →      nombre/avatar (perfil E2EE)
   · Cerlita.theme()         →      paleta Cerlita viva (claro/oscuro)
   · Cerlita.haptic(t)       →      VibrationEffect
   · Cerlita.submit(score)   →      engine.send(\u0000G {gameId, score})  ← E2EE
   · Cerlita.close()         →      volver al chat
        ▼
[Chat: burbuja de juego]  →  tocar «Jugar» abre GameScreen
   · Récord de la pareja visible bajo el título del juego
   · «Amelia batió tu récord» como mensaje de servicio (patrón Telegram)
```

Puntos de diseño importantes. **Todo E2EE**: las puntuaciones y jugadas viajan como mensajes de
control cifrados con el mismo sobre de siempre — el servidor sigue sin ver nada. **Offline-first**:
los juegos empaquetados funcionan sin red; un catálogo remoto (Storage) es una ampliación posterior,
no un requisito. **Sin WebView en el camino crítico del chat** (regla de `arquitectura-v2-ntfy.md`
§2): el WebView solo existe dentro de GameScreen, la lista de mensajes sigue siendo Compose nativo.
**Seguridad del puente**: el WebView carga solo `file:///android_asset/` (sin red por defecto), el
puente expone 5-8 métodos inocuos y nada más; al ser local no hay `initData` que falsificar.

### 7.3 Catálogo inicial (candidatos, licencias a verificar al implementar)

Juegos HTML5 open source adaptables al puente (cada uno son cientos de KB, no MB):

| Juego | Tipo | Licencia (a verificar en el momento de integrar) | Multijugador pareja |
|-------|------|------------|---------------------|
| **2048** (original de Gabriele Cirulli) | Puzzle un jugador | MIT (la original) | Por récords |
| Hextris | Puzzle arcade | Open source | Por récords |
| Clumsy Bird | Arcade (flappy) | MIT | Por récords |
| Conecta-4 / Tres en raya (a medida, canvas) | **Por turnos** | Propio | **Directo por mensajes** |
| Ajedrez (chess.js + tablero canvas) | **Por turnos** | Chess.js BSD | **Directo por mensajes** |
| Quiz de la pareja (a medida) | Trivia | Propio | Por turnos |

La recomendación es empezar con **dos juegos de récords** (2048 + uno arcade) y **uno por turnos**
(conecta-4: las jugadas caben en < 32 B, perfectas para el modelo de mensajes). El ajedrez y el quiz
vienen después con la misma mecánica.

### 7.4 Riesgos y mitigaciones

El WebView pesa en memoria (~30-60 MB por instancia en móviles viejos) — se destruye al salir de
GameScreen, como hace Telegram. La variabilidad de rendimiento en gamas bajas se mitiga con juegos
ligeros (DOM/canvas, sin motores 3D — para nada comparables con los 37 MB de los GLB de avatares que
ya carga el editor). El tamaño del APK crece poco (los juegos HTML5 pesan KB; el WebView ya viene con
Android — 0 dependencias nuevas). Y el riesgo de mantener un «mini-navegador» se acota con un puente
de superficie mínima y assets locales firmados dentro del APK.

### 7.5 Esfuerzo estimado (referencia para planificar)

PoC (un juego + puente + burbuja de juego + récord E2EE): **~10-14 h**. Catálogo de 4-5 juegos con
récords y mensajes de servicio: +10-12 h. Primer juego por turnos (conecta-4 sobre mensajes):
+6-8 h. Tiempo real (canal estilo llamadas): +10-15 h. Total de la fase completa de juegos:
**~40 h**, repartible en semanas sin bloquear el trabajo de QoS del chat.

---

## 8. Roadmap recomendado (estado v0.13.0 + esta investigación)

La secuencia importa: **primero blindar lo que existe, luego las features, luego los juegos** — igual
que Telegram construyó el mensajero (2013) antes que los bots (2015), los juegos (2016) y las Mini Apps
(2022). El «Sprint 0» de la auditoría **ya está hecho** (v0.8.0, D84-D89); esto es lo que queda:

| Fase | Nombre | Contenido | Horas |
|------|--------|-----------|-------|
| **S0'** | «Restos de la auditoría» | Streaming de adjuntos + `MAX_FILE` 8 MB (B8), confirmar B2/B5 en uso real, Paging 3 (U2), miniaturas (U3), tema oscuro Cerlita (U5), chip «↓ N» → **v0.14.0** | ~10-12 h |
| **S1** | «QoS + llamadas a prueba de todo» | AlarmManager 3ª capa, NetworkCallback, reconexión inteligente (Q2), coalescing (Q6), `/sync` en 1 query (Q4), FGS mic+cámara, full-screen intent, cámara solo al aceptar, rate limiting backend → **v0.15.0** | ~20-25 h |
| **S2** | «El chat que esperabas» | Reacciones, responder/citar, editar/borrar, «escribiendo…», reenviar, búsqueda FTS, widget, álbum de adjuntos → **v0.16.0** | ~35 h |
| **J0-J2** | «Minijuegos» | J0: PoC (2048 + puente + burbuja + récord E2EE) · J1: catálogo 4-5 juegos + récords de la pareja + mensajes de servicio · J2: primer juego por turnos → **v0.17.0** | ~25-35 h |
| **J3+** | «Tiempo real» | Juegos por tiempo real (canal estilo llamadas), catálogo remoto desde Storage | futuro |
| — | Avatares 3D | **Pausados** (D103) — retomar tras J2 si procede | — |

Cada fase cierra con commit + push + DECISIONES.md, como siempre.

---

## 9. Fuentes

**Telegram (oficiales, verificadas esta sesión):**
- Juegos para bots: https://core.telegram.org/bots/games (flujo sendGame → WebView → games.js → récords)
- Mini Apps / Web Apps: https://core.telegram.org/bots/webapps (telegram-web-app.js, API completa, changelog Bot API 6.1 → 10.1)
- FAQ técnico (MTProto, llamadas E2EE): https://core.telegram.org/techfaq
- Protocolo MTProto: https://core.telegram.org/mtproto
- Cliente Android oficial (GPL): https://github.com/DrKLO/Telegram · Telegram X: https://github.com/TGX-Android/Telegram
- TDLib (Boost 1.0): https://github.com/tdlib/td · Servidor Bot API (Boost 1.0): https://github.com/tdlib/telegram-bot-api
- Servidor cerrado; reimplementación no oficial en Go (Apache-2.0): https://github.com/teamgram/teamgram-server

**TURN gratuito (verificado):**
- Open Relay / Metered (20 GB/mes, puertos 80/443, TURNS): https://www.metered.ca/tools/openrelay — ya integrado como default en la app (D88)
- ExpressTURN, Cloudflare TURN, coturn/eturnal self-hosted: tabla comparativa en auditoría §8.2

**Internas del proyecto:**
- `docs/DECISIONES.md` — D84-D89 (v0.8.0 «Sin letra pequeña»: auditoría ejecutada), D90-D102 (avatares 3D, ahora pausados), D103 (esta investigación)
- `upload/AUDITORIA-2026-10-28.md` — auditoría exhaustiva v0.7.1 (B1-B8, QoS §3, notificaciones §4, llamadas §8, features §10, roadmap §11) — sus hallazgos críticos ya ejecutados en v0.8.0
- `upload/arquitectura-v2-ntfy.md` — especificación v2 con reglas de red ETECSA (§3: «el byte es barato y el RTT es caro»)
- `docs/DECISIONES.md` D44 (sin Firebase), D75 (llamadas WebRTC), D81 (fix señalización), D82 (FGS ON por defecto), D88/D89 (TURN + señalización cifrada)
- Verificación de código v0.13.0 de esta sesión (greps sobre `f800725`): resueltos — `TurnConfig.kt` + `/turn`, `echoCancellation` (`CallSession.kt:180`), `Notifier` sin ventana de 30 min, `itemsIndexed`, `kind` en `RoomListItem`; pendientes — Paging (0 en gradle), NetworkCallback (0), `UnknownHostException` en doorbell (0), `FOREGROUND_SERVICE_MICROPHONE` (0), `USE_FULL_SCREEN_INTENT` (0), `startCamera` al recibir (`CallSession.kt:188`), CTE en `sync/index.ts` (0), `MAX_FILE` 20 MB (`C.kt:54`)
