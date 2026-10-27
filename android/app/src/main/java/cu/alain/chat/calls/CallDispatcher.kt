package cu.alain.chat.calls

import cu.alain.chat.ChatApp

/**
 * Despachador de señalización de llamadas (v0.7.0, D75).
 *
 * Los eventos "sig" del timbre Realtime (broadcast REST → WebSocket del par)
 * llegan aquí desde ChatApp.onRealtimeEvent. La implementación completa
 * (WebRTC, pantallas de llamada, registro) se cablea en el milestone de
 * llamadas — este es el punto de entrada único para que el timbre in-app
 * y el FGS compartan el mismo camino.
 */
object CallDispatcher {
    @Volatile
    var enabled: Boolean = false // el milestone de llamadas lo activa

    fun onSignal(app: ChatApp, payload: String) {
        if (!enabled) return // versión sin llamadas: los eventos se ignoran
    }
}
