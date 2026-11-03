# -*- coding: utf-8 -*-
"""Contenido del Informe de Preparación v1.0 — Cerlita Chat. Capítulos 1-4."""


def add_content(c):
    story = c["story"]
    h1_, h2_, h3_, p_, bullets_ = c["h1"], c["h2"], c["h3"], c["p"], c["bullets"]
    table, stats, quote, chart = c["table"], c["stats"], c["quote"], c["chart"]

    # ══════════════════════ 1. RESUMEN EJECUTIVO ══════════════════════
    h1_(story, "1. Resumen ejecutivo y veredicto")
    p_(story, "Este informe examina, sin concesiones, qué le falta a Cerlita Chat para "
              "merecer el número de versión 1.0. La conclusión directa: <b>la aplicación no está "
              "lista hoy, y no por las razones habituales</b>. La funcionalidad que uno espera de un "
              "chat de pareja a 1.0 —cifrado de extremo a extremo real, entrega fiable en red mala, "
              "llamadas, adjuntos, búsqueda, notificaciones instantáneas— ya existe, funciona y está "
              "verificada hasta un nivel que muchos productos comerciales no alcanzan. Lo que falta "
              "es otra cosa: <b>la capacidad de saber cuándo la app falla, y de demostrar que no "
              "falla, en el único tribunal que importa, que es el teléfono real de la pareja</b>.",
       "lead")
    p_(story, "La auditoría de octubre de 2026 encontró ocho defectos (B1 a B8); todos están "
              "cerrados y verificados en el código. La investigación de roadmap de D103 dejó nueve "
              "pendientes de fiabilidad y fluidez; los nueve se cerraron en la v0.15.0. La v0.16.0 "
              "subió la calidad del arte de los avatares y la v0.17.0 —emitida el mismo día que este "
              "informe— eliminó por completo el sistema 3D y devolvió el binario a la mitad de su "
              "tamaño. El historial demuestra capacidad de ejecución excepcional. Y sin embargo, "
              "cada uno de esos arreglos llegó <b>después</b> de que el uso real lo destapara: "
              "llamadas que no conectaban, tema «que no se parecía en nada», botones de navegación "
              "que no se ocultaban, avatar congelado. La suite de tests actual, con 122 pruebas "
              "verdes, no habría detectado ninguno de esos cinco defectos. Ese es el corazón del "
              "problema que este informe diagnostica.")
    stats(story, [
        ("122/122", "tests JVM y Robolectric en verde"),
        ("0", "tests de instrumentación en dispositivo"),
        ("19,5 MB", "APK release firmada (era 39,8)"),
        ("$0/mes", "coste total de infraestructura"),
    ])
    p_(story, "La tabla siguiente resume las cinco brechas que, en el criterio de este informe, "
              "separan a la aplicación de la 1.0. Ninguna es una función nueva. Cuatro de las cinco "
              "son disciplina de ingeniería; la quinta es pulido de primera impresión. Las tres "
              "primeras son bloqueantes en sentido estricto: sin ellas, cualquier «1.0» sería un "
              "nombre de marketing, no un estado verificable del producto.")
    table(story,
          ["N.º", "Brecha", "Por qué bloquea la 1.0", "Severidad"],
          [
              ["1", "Ceguera operativa: sin reporte de fallos",
               "Si la app muere en el teléfono de ella, el desarrollador se entera por una queja verbal, sin traza ni reproducción", "CRÍTICA"],
              ["2", "Cero verificación en dispositivo real",
               "Los cinco defectos más graves de la historia del proyecto eran invisibles para la suite de tests", "CRÍTICA"],
              ["3", "Identidad de dispositivo frágil",
               "Cambiar o reinstalar el teléfono rompe el emparejamiento de los chats directos hasta recuperarlos a mano", "ALTA"],
              ["4", "Primera impresión sin pulir",
               "Splash inexistente, sin cámara para fotos en el chat, onboarding de una sola pantalla", "MEDIA"],
              ["5", "Calidad no medida",
               "Sin cobertura, sin base de lint, sin presupuesto de rendimiento: lo que no se mide, se degrada", "MEDIA"],
          ],
          [0.06, 0.27, 0.52, 0.15],
          caption="Tabla 1. Las cinco brechas hacia la 1.0, en orden de severidad.",
          bold_first_col=True)
    p_(story, "El veredicto, en una frase: <b>Cerlita Chat tiene el producto de una 1.0 y el "
              "proceso de verificación de una beta</b>. Cerrar esa distancia cuesta tres versiones "
              "de trabajo enfocado —estimadas en el capítulo 8— y una semana de uso real "
              "instrumentado. Nada más y nada menos.")

    # ══════════════════════ 2. METODOLOGÍA ══════════════════════
    h1_(story, "2. Metodología y alcance")
    p_(story, "La auditoría se realizó el 30 de octubre de 2026 sobre la v0.17.0 (versionCode 19, "
              "commit 328f120), emitida en la misma sesión que este informe con la eliminación del "
              "sistema de avatares 3D. El objeto de estudio comprende: 14.765 líneas de Kotlin en "
              "el módulo principal de la app Android, 2.290 líneas de test repartidas en 18 suites, "
              "seis funciones Edge del backend (auth, sync, outbox, files, heartbeat y turn) con "
              "cinco migraciones SQL, la configuración de integración continua en GitHub Actions, "
              "y el corpus documental del proyecto: 113 decisiones registradas (D1 a D113), "
              "26 hallazgos de entorno (H1 a H26) y la auditoría previa del 28 de octubre.")
    p_(story, "El método fue la lectura directa del código, no la confianza en la documentación. "
              "Cada afirmación de este informe se verificó de una de tres formas: inspección del "
              "fichero correspondiente con búsqueda dirigida (por ejemplo, la presencia de "
              "resetUnread en ChatScreen para confirmar el cierre del defecto B5, o la ausencia "
              "total del directorio androidTest para afirmar que no existe ni un test "
              "instrumentado), compilación completa de la variante release con firma y ejecución "
              "de la suite completa de tests (resultado: 122 pruebas, cero fallos), o contraste "
              "contra las fuentes primarias del propio repositorio, incluida la tag v0.16.0 para "
              "diferenciar lo que existía antes de la eliminación del 3D.")
    p_(story, "Dos límites de alcance, declarados sin rodeos. Primero: esta auditoría no ejecutó "
              "nada en un teléfono físico; no hay mediciones de batería, de datos móviles ni de "
              "comportamiento real bajo la red de ETECSA — exactamente la clase de evidencia cuya "
              "ausencia constituye la brecha número dos del informe, de modo que el informe también "
              "es culpable de lo que denuncia y lo dice. Segundo: el backend se auditó por lectura "
              "y por sus suites de pruebas extremo a extremo existentes, pero no se ejecutó una "
              "campaña nueva de carga contra producción.")

    # ══════════════════════ 3. LO GANADO ══════════════════════
    h1_(story, "3. Lo que ya está ganado: inventario honesto")
    p_(story, "Ser implacable no significa regatear mérito. Antes de detallar lo que falta, conviene "
              "fijar con precisión lo que existe, porque la base sobre la que se construye la 1.0 "
              "es sólida en un grado poco habitual. El inventario siguiente no es una lista de "
              "deseos: cada fila se verificó en el código de la v0.17.0.")
    table(story,
          ["Subsistema", "Estado", "Evidencia verificada"],
          [
              ["Cifrado de extremo a extremo", "SÓLIDO",
               "ChaCha20-Poly1305 con claves derivadas por HKDF por mensaje; X25519 para chats directos; Argon2id para exportes; claves selladas en AndroidKeyStore; pruebas de vectores conocidos cruzadas entre Python y Kotlin"],
              ["Entrega de mensajes", "SÓLIDO",
               "Cola offline persistente con reintentos y anti-colisión, deduplicación por identificador, cursores por sala que recuperan el historial previo al unirse, siete estados de mensaje"],
              ["Aviso de mensajes", "SÓLIDO",
               "Cuatro capas que degradan limpio: timbre WebSocket instantáneo, canal ntfy con el sobre cifrado dentro del ping, alarma exacta de 4 a 6 minutos y trabajador de 15 minutos"],
              ["Llamadas 1 a 1", "FUNCIONAL",
               "WebRTC punto a punto con TURN de respaldo, señalización cifrada, servicio en primer plano de micrófono y cámara, intención de pantalla completa para llamadas entrantes, indicador de señal"],
              ["Adjuntos", "FUNCIONAL",
               "Fotos, notas de voz con forma de onda real y archivos de hasta 8 MB, cifrados con clave propia por archivo, subida por bloques y reintentable"],
              ["Interfaz de chat", "BUENA",
               "Responder, reacciones, editar y borrar, indicador de escritura, búsqueda global con índice de texto completo, tema Cerlita medido píxel a píxel, modo noche, minijuego 2048 con récord cifrado"],
              ["Avatares", "CERRADO",
               "Sistema 2D por capas con 7 bases, ropa, calzado y accesorios, más foto de perfil cifrada; el 3D se eliminó en la v0.17.0 y el formato de perfil no cambió ni un byte"],
              ["Backend", "SÓLIDO",
               "Sincronización en una sola consulta con CTE, limitación de tasa por instancia, autenticación propia con Argon2id y bloqueo progresivo"],
          ],
          [0.22, 0.13, 0.65],
          caption="Tabla 2. Inventario de subsistemas de la v0.17.0 con su evidencia.",
          bold_first_col=True)
    h2_(story, "3.1. La deuda de octubre está pagada")
    p_(story, "La auditoría del 28 de octubre catalogó ocho defectos concretos, del B1 al B8. "
              "El estado de cada uno en la v0.17.0 es el siguiente, verificado en código y no por "
              "promesa de changelog.")
    table(story,
          ["Defecto", "Descripción", "Cierre verificado"],
          [
              ["B1", "El historial previo al unirse a una sala quedaba invisible para siempre",
               "v0.8.0 (D87): cursores por sala con paginación y relleno de huecos al iniciar"],
              ["B2", "Los botones de llamada no aparecían en chats directos abiertos desde la lista",
               "El tipo de sala y el identificador del par viajan en la lista (verificado en RoomsScreen)"],
              ["B3", "La tabla de mensajes no tenía ni un índice secundario",
               "v0.8.0 (D86): tres índices en la migración 6 con prueba de migración real"],
              ["B4", "Un error 401 dejaba la app muerta en silencio",
               "v0.8.0 (D85): la sesión expira sin borrar nada y el login explica qué pasó"],
              ["B5", "El contador de no leídos no bajaba al leer dentro de la sala",
               "Reinicio del contador al entrar y al llegar mensajes con la sala abierta (verificado en ChatScreen)"],
              ["B6", "La señalización de llamadas viajaba en claro",
               "v0.8.0 (D89): sobre cifrado con dominio de clave separado y compatibilidad con la versión anterior"],
              ["B7", "Una migración olvidada podía borrar el historial en silencio",
               "v0.8.0 (D86): fin del modo destructivo; fallo visible y prueba de migración obligatoria"],
              ["B8", "Los adjuntos grandes causaban hasta cuatro copias en memoria",
               "v0.15.0 (D109): tope de 8 MB, subida por bloques desde fichero y limpieza diaria de caché"],
          ],
          [0.09, 0.44, 0.47],
          caption="Tabla 3. Los ocho defectos de la auditoría de octubre y su cierre verificado.")
    p_(story, "Lo mismo aplica a los nueve pendientes que la investigación de roadmap (D103) "
              "verificó en la v0.13.0: reconexión que distingue red caída de servidor caído, "
              "despertar al volver la conectividad, fusión de sincronizaciones, consulta única "
              "con CTE, ventana de historial en la interfaz, miniaturas por bytes, tema noche, "
              "alarma como tercera capa y adjuntos sin desbordamiento de memoria. Los nueve se "
              "cerraron en la v0.15.0. <b>La deuda heredada está, literalmente, a cero.</b>")
    quote(story, "Entre el 27 y el 30 de octubre la app cerró diecisiete compromisos de calidad "
                 "heredados, añadió un canal de notificaciones cifrado, un minijuego con récord "
                 "cifrado y recortó el binario a la mitad. Ese ritmo es la prueba de que la 1.0 "
                 "no es una quimera: es una cuestión de apuntar el cañón al blanco correcto.")
    p_(story, "Y conviene decirlo con todas las letras: el registro de decisiones D1 a D113, con "
              "justificación, alternativas descartadas y hallazgos de entorno, es una disciplina "
              "documental superior a la de la mayoría de los equipos comerciales pequeños. Si la "
              "auditoría fuera solo sobre proceso de decisión, la nota sería sobresaliente.")

    # ══════════════════════ 4. LAS CINCO BRECHAS ══════════════════════
    h1_(story, "4. Las cinco brechas que separan de la 1.0")

    h2_(story, "4.1. Ceguera operativa: la app no puede pedir ayuda")
    p_(story, "No existe ningún mecanismo de reporte de fallos: ni Crashlytics, ni Sentry, ni "
              "siquiera un capturador propio de excepciones no controladas que escriba la traza a "
              "disco. La verificación es categórica: la búsqueda de cualquier biblioteca de "
              "reporte en el proyecto devuelve cero resultados. Esto significa que el modelo de "
              "soporte de la aplicación es el siguiente: la app muere en el teléfono de ella, ella "
              "lo comenta a la hora de la cena, y el desarrollador intenta reproducir a ciegas un "
              "fallo del que no tiene ni la traza, ni la versión, ni el modelo del teléfono, ni "
              "qué pantalla estaba abierta.")
    p_(story, "Es la brecha número uno por una razón estructural, no cosmética: <b>sin telemetría "
              "de fallos, las demás brechas no se pueden cerrar con evidencia</b>. La semana de "
              "uso real que propone el capítulo 8 como criterio de salida solo produce un "
              "veredicto si hay algo que contar los fallos. La solución es barata y cumple la "
              "regla de coste cero del proyecto: ACRA es software libre maduro que funciona sin "
              "servidor (envía por correo o a cualquier punto HTTP propio), y el tier gratuito de "
              "Sentry también es una opción válida si se prefiere panel visual. Una tarde de "
              "trabajo, cambio de categoría del producto entero.")

    h2_(story, "4.2. Cero verificación en dispositivo real")
    p_(story, "El directorio androidTest no existe. No hay ni una sola prueba de instrumentación "
              "en la historia del proyecto. Las 122 pruebas verdes viven en dos mundos "
              "simulados: la JVM pura (donde viven los vectores criptográficos y los protocolos, "
              "con excelentes resultados) y Robolectric (donde vive una prueba de arranque en "
              "cinco versiones de API y las notificaciones). Ninguno de los dos mundos ejecuta "
              "WebRTC de verdad, ni la pila de audio de un fabricante concreto, ni el gestor de "
              "batería de un Xiaomi, ni la red real de ETECSA con sus cortes y su NAT simétrico.")
    p_(story, "La historia del proyecto es el argumento definitivo. Los cinco defectos más "
              "dañinos en uso real —los candidatos ICE que llegaban antes del SDP y mataban las "
              "llamadas, el altavoz ignorado por la capa de audio de Android 12, el espaciado "
              "doble de la barra de navegación, los botones del sistema que no se ocultaban y el "
              "tema que «no se parecía en nada» al modelo— pasaron indemnes ante la suite de "
              "tests de entonces y de ahora. <b>La suite actual, con 122 pruebas, tampoco habría "
              "cazado ninguno.</b> No es un reproche a los tests que existen, que son buenos; es "
              "la constatación de que cubren el territorio donde la app ya era fuerte (protocolo, "
              "cripto, lógica) y dejan sin cobertura el territorio donde la app históricamente "
              "sangra: el hardware.")
    chart(story, c["chart_png"],
          "Figura 1. Profundidad de verificación por capa del sistema. Estimación fundamentada a "
          "partir del recuento de pruebas por paquete y de su naturaleza (vectores conocidos, "
          "pruebas de extremo a extremo contra producción, Robolectric, instrumentación).")
    p_(story, "El mínimo aceptable para una 1.0 no es una pirámide de pruebas de interfaz: son "
              "tres o cuatro pruebas instrumentadas de los flujos que definen el producto —crear "
              "cuenta y entrar, enviar y recibir un mensaje con la app en segundo plano, "
              "contestar desde la notificación y completar una llamada corta entre dos "
              "dispositivos— conectadas a la integración continua. Con reporte de fallos (brecha "
              "1) y esos flujos, el proyecto pasa de «confío» a «compruebo».")

    h2_(story, "4.3. Identidad de dispositivo frágil")
    p_(story, "Los chats directos se emparejan cifrando la clave de la sala con la clave pública "
              "X25519 del otro teléfono, que se publica en el perfil del servidor bajo la regla "
              "«la última clave escrita gana». El diseño es elegante para dos teléfonos estables y "
              "hostil para la vida real: reinstalar la app, cambiar de teléfono o perder el "
              "equipo reemplaza la pareja de claves, los envoltorios cifrados quedan muertos y "
              "la pareja debe rehacer el vínculo con el código de invitación, a mano, sin "
              "indicación proactiva de qué pasó.")
    p_(story, "Para una aplicación cuya promesa de marca es «te llega todo, siempre», este es el "
              "fallo con mayor capacidad percibida de destruir confianza, porque llega justo en "
              "el momento peor: el cambio de teléfono, cuando el usuario ya está irritado. El "
              "riesgo no es teórico en este proyecto: el keystore de firma ya se perdió una vez "
              "en un reinicio de entorno (hallazgo documentado) y la v0.1.0 quedó inactualizable "
              "para siempre. La solución estándar —mantener un pequeño conjunto de claves por "
              "usuario y envolver la clave de sala para cada una, con un aviso en pantalla cuando "
              "el par detecta que su envoltorio ya no abre— cabe en una o dos sesiones de trabajo "
              "y no toca el formato del servidor más que para permitir varias filas.")

    h2_(story, "4.4. Primera impresión sin pulir")
    p_(story, "Una 1.0 se juzga en los primeros noventa segundos, y hoy esos segundos tienen "
              "tres rozones. La pantalla de arranque no existe como tal: solo un fondo navy "
              "heredado de la plantilla que además no pertenece a la paleta Cerlita, reconocida "
              "como pendiente en el propio registro de trabajo. Las fotos en el chat salen "
              "exclusivamente de la galería: no hay botón de cámara, lo que obliga a salir de la "
              "app para hacer una foto y volver a entrar a enviarla — para una pareja que "
              "seguramente se hace fotos, es una fricción diaria. Y el alta de un usuario nuevo "
              "es una única pantalla de perfil sin acompañamiento. Ninguno de los tres es "
              "difícil; los tres juntos son la diferencia entre «app seria» y «proyecto de "
              "pareja» a ojos de cualquiera que la vea por primera vez.")

    h2_(story, "4.5. Calidad no medida")
    p_(story, "La integración continua ejecuta las pruebas y compila el APK firmado —que es más "
              "de lo que hacen muchos proyectos— pero la vigilancia termina ahí. El análisis "
              "estático se reduce a la variante que solo falla con errores fatales, sin base de "
              "referencia ni avisos visibles; no hay medición de cobertura (ni siquiera del "
              "paquete criptográfico, que seguramente rondaría cifras excelentes y ganaría el "
              "argumento para siempre); no hay presupuesto de arranque en frío ni de memoria, en "
              "un proyecto donde el rendimiento en móviles modestos es parte del contrato "
              "social con ETECSA. La regla de ingeniería que aplica aquí es incómoda pero "
              "cierta: lo que no se mide, se degrada; y lo que se mide, se defiende solo.")
    table(story,
          ["Brecha", "Impacto inmediato en la pareja", "Esfuerzo estimado"],
          [
              ["Sin reporte de fallos", "Los fallos del teléfono de ella tardan días en existir como datos", "1 tarde"],
              ["Sin instrumentación", "Cada actualización se valida con fe y con suerte", "1 a 2 sesiones"],
              ["Identidad frágil", "Cambiar de teléfono rompe el vínculo de los chats directos", "1 a 2 sesiones"],
              ["Pulido de entrada", "Fricción diaria en fotos y una primera impresión mediocre", "1 sesión"],
              ["Calidad sin medir", "Deuda invisible que crece en cada versión", "1 tarde"],
          ],
          [0.26, 0.50, 0.24],
          caption="Tabla 4. Las cinco brechas con su impacto y esfuerzo de cierre estimado.",
          bold_first_col=True)

    from informe_v1_contenido2 import add_content as add2
    add2(c)
