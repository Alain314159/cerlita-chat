package cu.alain.chat.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import cu.alain.chat.ChatApp

/**
 * Q3/S1 (D105): despertar al VOLVER la conectividad.
 *
 * Antes no existía ningún listener de red: tras 20 min sin cobertura, el
 * timbre tardaba hasta 60 s (backoff máximo) en reintentar y el polling
 * hasta 5 min — el mensaje que llegó con la red volvía con retraso «gratis».
 *
 * `registerDefaultNetworkCallback` avisa en el MOMENTO exacto en que vuelve
 * la red (wifi o datos). Reacción: kick del timbre (conexión inmediata) +
 * sync ya (los mensajes del apagón llegan en el primer segundo) + un poll
 * ntfy best-effort (si lo caído era Supabase, ntfy sigue vivo y trae el
 * sobre cifrado por la otra ruta).
 *
 * Se registra UNA vez por proceso (flag) en el onCreate de ChatApp. Sin
 * sesión no hace nada: onNetworkBack lo comprueba. ACCESS_NETWORK_STATE ya
 * está en el manifest desde v0.6.0.
 */
object NetWatch {

    @Volatile
    private var registered = false

    fun ensure(app: ChatApp) {
        if (registered) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Hilo de red del sistema: solo despachar, nada pesado aquí
                    app.onNetworkBack()
                }
            })
            registered = true
        } catch (_: Exception) {
            // OEM sin callback default (raro en 2026): el polling cubre
        }
    }
}
