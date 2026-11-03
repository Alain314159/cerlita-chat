# -*- coding: utf-8 -*-
"""Contenido del Informe de Preparación v1.0 — Cerlita Chat. Capítulos 5-9."""


def add_content(c):
    story = c["story"]
    h1, h2, h3, p, bullets = c["h1"], c["h2"], c["h3"], c["p"], c["bullets"]
    table, stats, quote, chart = c["table"], c["stats"], c["quote"], c["chart"]

    # ══════════════════════ 5. INGENIERÍA DE CALIDAD ══════════════════════
    h1(story, "5. Ingeniería de calidad: la deuda que no se ve")
    p(story, "Comparada con el estándar de un equipo comercial pequeño, la ingeniería de calidad "
             "del proyecto presenta un contraste tajante: la disciplina de proceso (decisiones "
             "documentadas, registro de hallazgos, changelog honesto) está por encima de la media, "
             "y la infraestructura de verificación automática está por debajo. La tabla siguiente "
             "confronta, práctica a práctica, el estado real de la v0.17.0 con el estándar mínimo "
             "que este informe exige para colgar el cartel de 1.0.")
    table(story,
          ["Práctica", "Estado v0.17.0", "Estándar exigido para 1.0"],
          [
              ["Pruebas unitarias (JVM)", "122 en verde, 18 suites, vectores criptográficos cruzados con Python", "Mantener; ninguna regresión admitida"],
              ["Pruebas de integración backend", "6 suites de extremo a extremo contra producción real", "Mantener y ejecutarlas antes de cada despliegue"],
              ["Pruebas de instrumentación", "No existen", "Entre 3 y 4 flujos críticos conectados a la integración continua"],
              ["Reporte de fallos en producción", "No existe", "Reporte activo con traza, versión y modelo de teléfono"],
              ["Integración continua", "GitHub Actions: pruebas, APK firmado y publicación en cada etiqueta", "Añadir las instrumentadas al circuito"],
              ["Análisis estático", "Solo errores fatales, sin base de referencia", "Base de lint publicada y cero avisos nuevos"],
              ["Cobertura de pruebas", "Sin medir", "Umbral publicado para los paquetes críticos"],
              ["Presupuesto de rendimiento", "Sin medir", "Arranque en frío y memoria pico medidos una vez y anotados"],
              ["Documentación", "Excepcional, pero el registro de trabajo vive fuera del repositorio", "Todo el conocimiento dentro del repositorio"],
          ],
          [0.24, 0.40, 0.36],
          caption="Tabla 5. Prácticas de calidad: estado real contra estándar de 1.0.",
          bold_first_col=True)
    h2(story, "5.1. El riesgo que no está en el código")
    p(story, "Hay una dependencia operativa que este informe no puede pasar por alto porque la "
             "verificó en carne propia durante esta misma sesión: el entorno de desarrollo es "
             "perecedero y el proyecto depende de él más de lo necesario. Los hallazgos H19 a H26 "
             "del propio registro documentan reinicios que borraron credenciales, toolchain y "
             "herramientas; la sesión de hoy comenzó con un espacio de trabajo local congelado en "
             "la v0.7.1 mientras el repositorio real avanzaba sesenta y un commits hasta la "
             "v0.16.0, y hubo que reconstruir a mano el SDK, el JDK, la firma y la sincronización "
             "antes de poder tocar una línea de código. La memoria institucional del proyecto —el "
             "registro de trabajo con el detalle de cada sesión— vive en un fichero fuera del "
             "repositorio: el siguiente reinicio puede borrar la historia completa de cómo se "
             "construyó esta app.")
    p(story, "La mitigación es trivial y gratuita: mover el registro de trabajo al repositorio "
             "(o al menos una copia actualizada en cada sesión), documentar la receta de "
             "reconstrucción del entorno que esta sesión tuvo que improvisar, y dar por "
             "sentado que todo lo que no esté en GitHub no existe. El factor de riesgo de "
             "autobús es de 1,0: un solo desarrollador y un agente; cuanto más del saber viva en "
             "el repositorio, menos duele cualquier reinicio.")

    # ══════════════════════ 6. PRODUCTO, UX Y SEGURIDAD ══════════════════════
    h1(story, "6. Producto, experiencia y seguridad residual")
    h2(story, "6.1. Pendientes de producto, por orden de valor")
    p(story, "Más allá de las cinco brechas, la lista de pendientes de producto es corta, lo cual "
             "es una señal de salud. Ordenada por valor para dos usuarios reales: la cámara para "
             "fotos dentro del chat (fricción diaria, ya discutida); una pantalla de arranque con "
             "la identidad Cerlita (el modelo existe y la paleta está medida); el recordatorio "
             "periódico de exportar el respaldo cifrado, porque hoy el respaldo existe, es "
             "excelente y nadie lo usará hasta el día en que pierda el teléfono, que es "
             "exactamente el día en que ya no sirve; y, solo si la pareja lo pide en uso real, "
             "los videomensajes, que hoy no existen y cuya ausencia este informe considera "
             "correcta para la 1.0.")
    h2(story, "6.2. Accesibilidad: la deuda silenciosa")
    p(story, "La auditoría de octubre midió un contraste de 3,4 a 1 para el texto secundario "
             "sobre fondo de burbuja, por debajo del 4,5 a 1 que exige el nivel AA. El tema "
             "Cerlita es precioso y fue medido píxel a píxel para parecerse al modelo, pero "
             "nadie ha medido todavía sus textos para leerse bien con sol o con vista cansada. "
             "El ajuste recomendado ya estaba calculado en la propia auditoría: oscurecer el "
             "color de texto secundario al tono recomendado hasta superar el umbral. Es un cambio "
             "de una constante por tema, y una 1.0 no debería estrenarse con una deuda de "
             "accesibilidad conocida y calculada.")
    h2(story, "6.3. Seguridad residual: qué riesgo es real y cuál está aceptado")
    p(story, "La franqueza de este proyecto en materia de seguridad merece el mismo trato. La "
             "base de datos local no está cifrada en disco: los mensajes se guardan en claro "
             "dentro del almacenamiento privado de la app, protegidos por el cifrado de disco de "
             "Android y por el hecho de que quien tiene el teléfono desbloqueado ya puede leer "
             "el chat en pantalla. Para el modelo de amenaza de una pareja, es una decisión "
             "documentada y razonable; este informe la respeta y no pide cifrado de base de "
             "datos para la 1.0. El servidor ve metadatos —quién habla con quién, cuándo y de "
             "qué tamaño— porque el diseño de sincronización los necesita; es un límite "
             "estructural aceptado, no un descuido. La clave anónima del backend viaja en el "
             "APK por diseño, con permisos mínimos por capability. Y la señalización de "
             "llamadas cifra desde la v0.8.0, con una ventana de compatibilidad con la v0.7.x "
             "que se cierra sola cuando ambos teléfonos actualicen.")
    table(story,
          ["Riesgo", "¿Es real?", "¿Aceptado conscientemente?", "Acción 1.0"],
          [
              ["Base de datos local sin cifrar", "Sí, con el teléfono desbloqueado y en manos", "Sí, documentado", "Ninguna; revisar solo si cambia el modelo de amenaza"],
              ["Metadatos visibles al servidor", "Sí, quien/ cuándo/ tamaño", "Sí, inherente al diseño", "Ninguna"],
              ["Texto secundario bajo contraste AA", "Sí, medido 3,4:1", "No, es un descuido", "Oscurecer el tono y verificar 4,5:1"],
              ["Señalización de llamadas en claro con versiones viejas", "Transitorio, hasta actualizar ambos", "Sí, con fecha de caducidad", "Verificar ambos teléfonos actualizados"],
              ["Clave anónima del backend en el APK", "Sí, pero sin privilegios", "Sí, por diseño", "Ninguna"],
          ],
          [0.30, 0.25, 0.22, 0.23],
          caption="Tabla 6. Riesgos residuales de seguridad: reales, aceptados y con acción.",
          bold_first_col=True)

    # ══════════════════════ 7. PLATAFORMA E INFRAESTRUCTURA ══════════════════════
    h1(story, "7. Riesgos de plataforma e infraestructura")
    p(story, "Toda la cadena funciona con capas gratuitas, lo cual es una restricción de diseño "
             "del proyecto, no un accidente, y en general está bien elegida. Pero gratis no es "
             "igual a garantizado, y una 1.0 debe mirar sus dependencias externas de frente. "
             "Estos son los riesgos de plataforma que este informe considera dignos de mención, "
             "con su probabilidad, su impacto y la mitigación disponible hoy.")
    table(story,
          ["Riesgo", "Probabilidad", "Impacto", "Mitigación disponible"],
          [
              ["Pausa del tier gratuito de Supabase por inactividad", "Media", "Alto: el chat deja de sincronizar", "El registro de trabajo ya documenta el problema; un latido programado o la alerta de la capa ntfy lo detectan"],
              ["ntfy.sh sin acuerdo de servicio (servicio público gratis)", "Media", "Medio: se pierde la capa 2 de aviso", "El servidor ntfy ya es configurable en Ajustes; autoalojado es el plan B documentado"],
              ["TURN de OpenRelay con credenciales públicas de prueba y 20 GB al mes compartidos", "Alta a medio plazo", "Medio: llamadas degradadas a STUN en NAT simétrico", "Migrar a Cloudflare TURN (1000 GB gratis): re-exportar tres variables, ya documentado en D88"],
              ["Bloqueo o sanción geopolítica del proveedor hacia Cuba", "Baja pero históricamente plausible", "Alto: pérdida del backend", "El modelo de salas funciona sobre cualquier Postgres; el export cifrado preserva los datos"],
              ["Pérdida del keystore de firma", "Ya ocurrió una vez", "Alto: la app deja de actualizar en los teléfonos", "El CI ya firma desde secretos del repositorio; mantener esa copia como canónica"],
              ["Reinicio del entorno de desarrollo sin aviso", "Alta (ya ocurrió tres veces)", "Medio: horas de reconstrucción", "Receta de reconstrucción y registro de trabajo dentro del repositorio"],
          ],
          [0.34, 0.14, 0.24, 0.28],
          caption="Tabla 7. Matriz de riesgos de plataforma de la v0.17.0.",
          bold_first_col=True)
    p(story, "Merece subrayarse el caso del TURN porque es el único punto donde la cadena gratis "
             "depende de credenciales públicas de pruebas compartidas con todo internet: "
             "funciona, pero es el eslabón más débil de la cadena de llamadas y su sustituto "
             "mejor ya está identificado y documentado. Y el riesgo geopolítico no se mitiga con "
             "código: se mitiga teniendo el export cifrado como religión y sabiendo que el "
             "modelo de salas es portable a cualquier Postgres en una tarde de trabajo, algo que "
             "el propio diseño de capability convierte en ventaja real.")

    # ══════════════════════ 8. PLAN DE ACCIÓN ══════════════════════
    h1(story, "8. Plan de acción hacia la 1.0")
    p(story, "La decisión D113 de esta misma sesión —eliminar el sistema de avatares 3D y "
             "devolver la app a sus personajes de 2D con accesorios y fotos— es la prueba más "
             "reciente de que el proyecto ya sabe hacer la maniobra que la 1.0 exige: <b>cortar "
             "en lugar de añadir</b>. Doce versiones persiguiendo «avatares como los de Facebook» "
             "terminaron con la mitad del tamaño del binario y los mismos personajes de siempre, "
             "que resultó ser exactamente lo que la pareja quería. La 1.0 se alcanza con la misma "
             "disciplina apuntada a tres blancos concretos.")
    h2(story, "8.1. Fase V — v0.18.0 «Verificación»")
    p(story, "Objetivo: que la app pueda delatarse cuando algo falla y que un teléfono real "
             "valide los flujos que definen el producto. Contenido: reporte de fallos con ACRA "
             "(software libre, sin servidor) o el tier gratuito de Sentry; la primera prueba de "
             "instrumentación del proyecto cubriendo el flujo completo de alta, entrada, envío y "
             "recepción con la app en segundo plano; medición de cobertura con umbral publicado "
             "para los paquetes de criptografía y motor; base de lint pública; y el registro de "
             "trabajo mudado al repositorio junto con la receta de reconstrucción del entorno. "
             "Criterio de salida: llega el primer reporte real de fallo con traza, versión y "
             "modelo, y la integración continua ejecuta en verde la prueba de instrumentación.")
    h2(story, "8.2. Fase I — v0.19.0 «Identidad»")
    p(story, "Objetivo: que cambiar de teléfono deje de ser un pequeño trauma. Contenido: "
             "soporte de varias claves públicas por usuario en el servidor, con la clave de sala "
             "envuelta para cada dispositivo activo y un aviso claro en pantalla cuando el par "
             "detecte que su envoltorio ya no abre, ofreciendo el re-emparejamiento guiado en "
             "lugar del silencio actual; recordatorio mensual de export cifrado con un toque "
             "para hacerlo. Criterio de salida: una prueba de extremo a extremo que reinstale la "
             "app, registre una segunda clave y recupere los chats directos sin intervención "
             "manual más allá de tocar el aviso.")
    h2(story, "8.3. Fase P — v1.0.0 «Primera impresión»")
    p(story, "Objetivo: que los primeros noventa segundos estén a la altura del resto. "
             "Contenido: pantalla de arranque con la identidad Cerlita medida del modelo; botón "
             "de cámara para fotos en el chat; contraste del texto secundario por encima del "
             "umbral AA; y una semana de uso real por la pareja con el reporte de fallos activo "
             "como tribunal final. Criterio de salida: siete días con una tasa de sesiones sin "
             "fallo igual o superior al 99,5 por ciento, cero brechas de las tablas 1 y 5 "
             "abiertas, y las dos suites de pruebas por encima de 130 casos en total.")
    h2(story, "8.4. Lo que la 1.0 no necesita")
    p(story, "Disciplina simétrica: la lista de lo que NO entra es tan vinculante como la de lo "
             "que entra. La 1.0 no necesita notificaciones de Google (las cuatro capas actuales "
             "cumplen y respetan la regla de coste cero); no necesita un servidor de retransmisión "
             "de medios para llamadas (el par a par con TURN basta para dos personas); no necesita "
             "cifrado de la base de datos local (riesgo aceptado con criterio); no necesita "
             "videomensajes, catálogo de juegos ni el conecta-4 por turnos del roadmap de "
             "minijuegos, que quedan para después de la 1.0 y solo si el uso real los pide; y no "
             "necesita volver a tocar el sistema de avatares, cerrado por decisión expresa. "
             "Cada tentación de este párrafo que se acepte aplaza la 1.0 una semana; así de "
             "simple es la aritmética.")
    table(story,
          ["Criterio de salida de la 1.0", "Medición", "Verificación"],
          [
              ["Reporte de fallos en producción", "Activo y recibiendo trazas reales", "Panel o bandeja con al menos un evento real"],
              ["Pruebas de instrumentación", "3 a 4 flujos críticos en la integración continua", "Ejecución verde en cada envío"],
              ["Cobertura de los paquetes críticos", "Umbral publicado y cumplido", "Informe de cobertura en la integración continua"],
              ["Cambio de teléfono sin perder chats directos", "Prueba de extremo a extremo dedicada", "Caso ejecutado en verde"],
              ["Contraste de texto secundario", "Igual o superior a 4,5:1 en los tres temas", "Medición documentada"],
              ["Semana de campo de la pareja", "Sesiones sin fallo igual o superior a 99,5 por ciento", "Reporte de fallos como fuente"],
              ["Cero deuda heredada abierta", "Tablas 3 y 5 sin filas pendientes", "Este informe, actualizado"],
          ],
          [0.34, 0.36, 0.30],
          caption="Tabla 8. Criterios de salida medibles de la versión 1.0.",
          bold_first_col=True)

    # ══════════════════════ 9. CONCLUSIÓN ══════════════════════
    h1(story, "9. Conclusión")
    p(story, "Cerlita Chat se acerca a su 1.0 con una paradoja que este informe quiere dejar "
             "escrita con precisión: es un producto pequeño con una ingeniería de protocolo "
             "seria, mantenido con una disciplina documental ejemplar, y al mismo tiempo es una "
             "aplicación que hoy no puede informar de sus propios fallos ni demostrar su "
             "comportamiento en el único entorno que importa. Las cinco brechas tienen "
             "soluciones conocidas, baratas y sin dependencias de pago; ninguna requiere "
             "investigación. La distancia real hasta la 1.0 son tres versiones de trabajo "
             "enfocado —Verificación, Identidad, Primera impresión— y una semana honesta de uso "
             "real con la app observándose a sí misma.")
    p(story, "El mayor riesgo de este proyecto no es técnico y ya tiene nombre y expediente: el "
             "desfocalazo. El episodio de los avatares 3D consumió doce versiones persiguiendo "
             "una referente estética ajena, y se resolvió el día que alguien dijo en voz alta lo "
             "obvio: la pareja ya tiene sus personajes. La 1.0 pedirá la misma fortaleza en "
             "pequeño: resistir la tentación de la función nueva mientras el plan V, I y P no "
             "esté cerrado. Si el foco aguanta, la siguiente auditoría que merezca este formato "
             "no será sobre lo que falta, sino sobre lo que sobra. Ese es el buen final posible, "
             "y está a tres versiones de distancia.")
