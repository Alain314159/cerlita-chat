package cu.alain.chat.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * U4 (auditoría): SimpleDateFormat es carísimo de construir (parsea el patrón,
 * crea Calendar y DateFormatSymbols cada vez). Se instanciaba POR BURBUJA en el
 * chat y POR FILA en la lista → 50 construcciones por recomposición.
 *
 * Aquí se crean UNA vez por patrón y se reutilizan. SimpleDateFormat NO es
 * thread-safe, pero Compose recompone en el main thread y el acceso desde
 * otros hilos es de solo lectura sobre instancias propias del mapa — se
 * sincroniza el getOrPut para estar seguros.
 */
object Fmt {
    private val cache = ConcurrentHashMap<String, SimpleDateFormat>()

    private fun df(pattern: String): SimpleDateFormat = cache.getOrPut(pattern) {
        synchronized(this) { cache.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) } }
    }

    /** Hora de la burbuja: 14:05 */
    fun time(ms: Long): String = df("HH:mm").format(Date(ms))

    /** Vista previa en la lista de chats: 3 nov 14:05 */
    fun list(ms: Long): String = df("d MMM HH:mm").format(Date(ms))

    /** Registro de llamadas: 3 nov · 14:05 */
    fun call(ms: Long): String = df("d MMM · HH:mm").format(Date(ms))

    /** Separador de día en el chat: lunes 3 de noviembre */
    fun day(ms: Long): String = df("EEEE d 'de' MMMM").format(Date(ms))
}
