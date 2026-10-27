package cu.alain.chat.calls

import org.webrtc.IceCandidate

/**
 * Cola de candidatos ICE (D81) — el bug que mataba las llamadas.
 *
 * El SDP (offer/answer) y cada candidato ICE viajan en broadcasts REST
 * SEPARADOS, sin orden garantizado. Aplicar `addIceCandidate` antes de que
 * el SDP remoto esté fijado lo descarta en silencio — y sin candidatos no
 * hay ruta de red: «contestó pero no se conecta ni se oye nada».
 *
 * Regla: hasta que el SDP remoto se aplica, los ICE se acumulan EN ORDEN;
 * después, se aplican directos. El drenado lo hace quien fija el SDP.
 */
class IceQueue {

    private val q = ArrayDeque<IceCandidate>()

    @Volatile
    var remoteSet: Boolean = false
        private set

    /** El SDP remoto quedó aplicado: a partir de aquí los ICE van directos. */
    fun remoteApplied() {
        remoteSet = true
    }

    /** true = puede aplicarse YA (el SDP ya está); false = quedó encolado. */
    fun add(candidate: IceCandidate): Boolean =
        if (remoteSet) true else {
            q.add(candidate)
            false
        }

    /** Candidatos acumulados antes del SDP, en orden de llegada. */
    fun drain(): List<IceCandidate> = q.toList().also { q.clear() }

    val size: Int get() = q.size
}
