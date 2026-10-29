package cu.alain.chat.qa

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D114 (Fase V del informe 1.0): reporte de fallos SIN servidor y SIN coste.
 *
 * La brecha número uno del informe era la «ceguera operativa»: cuando la app
 * moría en el teléfono de ella, el fallo no existía como dato — solo como
 * comentario a la hora de la cena. Esto lo cierra con la arquitectura más
 * simple que sigue siendo honesta:
 *
 *  1. FATAL: handler de excepciones no capturadas que ESCRIBE la traza a
 *     disco (filesDir/crashes) y luego encadena al handler del sistema, así
 *     el diálogo «la app se cerró» de Android sigue igual.
 *  2. NO FATAL: un CoroutineExceptionHandler en el scope de la app para que
 *     un fallo de corutina también deje huella sin matar el proceso.
 *  3. ENTREGA: al siguiente arranque, la bandeja (AppRoot) ofrece «Enviar
 *     informe» → ACTION_SEND de texto — la pareja elige Telegram/correo y el
 *     desarrollador recibe traza + versión + modelo + pantalla. Cero backend,
 *     cero API key, cero superficie de ataque nueva: el informe sale solo si
 *     la usuaria quiere, por el canal que ella elija.
 *
 * Se descartaron ACRA (trae sender de correo/HTTP que aquí no aportan y suma
 * dependencia) y Sentry (tier gratis ok, pero mete un SDK, un DSN y un punto
 * de fuga de metadatos que el modelo E2EE no necesita). El informe lleva
 * traza, versión, modelo, Android, pantalla activa y cola de logcat propia —
 * exactamente lo que pide el criterio de salida de la Fase V.
 */
object CrashReporter {

    /** Extensiones de informe: pendiente de enviar vs ya enviado. */
    private const val EXT_PENDING = ".txt"
    private const val EXT_SENT = ".sent"

    /** Máximo de informes pendientes (FIFO: el más viejo se descarta). */
    private const val MAX_PENDING = 5

    /** Líneas de logcat conservadas (las del propio proceso, best-effort). */
    private const val LOGCAT_LINES = 250

    private lateinit var appCtx: Context
    private var versionLine: String = "versión ?"
    @Volatile
    var lastScreen: String = "?"
        private set

    private val dir: File get() = File(appCtx.filesDir, "crashes").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Última pantalla viva (la anota AppRoot en cada cambio de ruta). */
    fun note(screen: String) {
        lastScreen = screen
    }

    /**
     * Instala el capturador. PRIMERA línea de ChatApp.onCreate: cualquier
     * fallo posterior (incluida la propia inicialización) deja traza.
     */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        versionLine = try {
            val pi = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "v${pi.versionName} ($vc)"
        } catch (_: Exception) {
            "v? (?)"
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write(fatal = true, thread = t, e = e)
            } catch (_: Throwable) {
                // jamás enmascarar el crash real con otro
            }
            previous?.uncaughtException(t, e)
        }
        prune()
    }

    /** Fallo no fatal (CoroutineExceptionHandler del scope de la app). */
    fun reportNonFatal(e: Throwable) {
        try {
            write(fatal = false, thread = Thread.currentThread(), e = e)
        } catch (_: Throwable) {
        }
    }

    private fun write(fatal: Boolean, thread: Thread, e: Throwable) {
        val ts = System.currentTimeMillis()
        // sufijo de secuencia: dos informes en el mismo ms no se pisan
        val name = "crash-" + (if (fatal) "f" else "n") + "-" + ts + "-" + seq.incrementAndGet() + EXT_PENDING
        val logcat = readOwnLogcat()
        File(dir, name).writeText(buildReport(fatal, thread, e, ts, logcat))
        prune()
    }

    private fun buildReport(fatal: Boolean, thread: Thread, e: Throwable, ts: Long, logcat: String): String {
        val sb = StringBuilder()
        sb.append("CERLITA CHAT — INFORME DE FALLO\n")
        sb.append("Fecha: ").append(fmt.format(Date(ts))).append('\n')
        sb.append("Tipo: ").append(if (fatal) "cierre inesperado (fatal)" else "error interno (no fatal)").append('\n')
        sb.append("Versión: ").append(versionLine).append('\n')
        sb.append("Teléfono: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Pantalla: ").append(lastScreen).append('\n')
        sb.append("Hilo: ").append(thread.name).append('\n')
        sb.append("--- TRAZA ---\n")
        sb.append(android.util.Log.getStackTraceString(e)).append('\n')
        if (logcat.isNotEmpty()) {
            sb.append("--- LOGCAT (último) ---\n")
            sb.append(logcat)
        }
        sb.append("--- FIN DEL INFORME ---\n")
        return sb.toString()
    }

    /** Logcat del PROPIO proceso (Android lo permite sin permisos). Best-effort. */
    private fun readOwnLogcat(): String = try {
        val pid = android.os.Process.myPid()
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", LOGCAT_LINES.toString(), "--pid=$pid"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (_: Throwable) {
        ""
    }

    /** Informes pendientes de enviar, del más reciente al más viejo. */
    fun pending(): List<File> =
        dir.listFiles { f -> f.name.endsWith(EXT_PENDING) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** Texto completo de un informe (para VER / compartir). */
    fun text(f: File): String = f.readText()

    /** Marca como enviado: pasa a .sent y se auto-borra a los 7 días. */
    fun markSent(f: File) {
        val to = File(dir, f.name + EXT_SENT)
        if (!f.renameTo(to)) f.delete()
        prune()
    }

    /** Descarta el informe (elección explícita de la usuaria). */
    fun discard(f: File) {
        f.delete()
    }

    /** FIFO de pendientes + limpieza de enviados viejos. */
    private fun prune() {
        pending().drop(MAX_PENDING).forEach { it.delete() }
        val week = 7L * 24 * 3600 * 1000
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.name.endsWith(EXT_SENT) }
            ?.filter { now - it.lastModified() > week }
            ?.forEach { it.delete() }
    }
}
