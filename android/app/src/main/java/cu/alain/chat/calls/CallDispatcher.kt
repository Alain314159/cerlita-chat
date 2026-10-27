package cu.alain.chat.calls

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.db.CallLog
import cu.alain.chat.db.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Despachador de llamadas (v0.7.0, D75): máquina de estados visible para la
 * UI + registro LOCAL de llamadas. Un solo proceso hace llamadas a la vez.
 *
 * Señalización: broadcast REST al topic de la sala (CallSignals); el medio
 * viaja P2P cifrado DTLS-SRTP (CallSession/WebRTC). Solo funcionan las
 * llamadas ENTRE CHATS DIRECTOS (1-a-1): la señalización necesita que ambos
 * estén suscritos al topic de la sala, y los grupos no tienen destinatario.
 *
 * Limitación honesta: las llamadas ENTRANTE avisan solo con la app abierta
 * o el modo instantáneo activo (el timbre es quien las trae — sin FCM no hay
 * despertar con la app cerrada; el registro queda «perdida» igual que un
 * teléfono sin cobertura).
 */
object CallDispatcher {

    enum class Phase { OUTGOING, INCOMING, ACTIVE, ENDED }

    data class CallState(
        val callId: String,
        val roomId: String,
        val peerUid: String,
        val peerName: String,
        val video: Boolean,
        val dir: Int, // C.CALL_OUT / C.CALL_IN
        val phase: Phase,
        val startedAt: Long,
        val micOn: Boolean = true,
        val camOn: Boolean = true,
        val speakerOn: Boolean = false,
        val connectedAt: Long = 0, // para el cronómetro
    )

    private val _state = MutableStateFlow<CallState?>(null)
    val state: StateFlow<CallState?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: CallSession? = null
    private var appRef: ChatApp? = null
    private var timeoutJob: Job? = null
    private var ringtone: android.media.Ringtone? = null
    private var logId: Long = 0 // fila del registro en curso
    private var suppressUntil = 0L // anti-bucle tras rechazar

    var enabled: Boolean = true // la UI del overlay lo apaga cuando nadie la observa

    /** Arranca una llamada SALIENTE desde un chat directo. */
    fun startOutgoing(app: ChatApp, room: Room, peerName: String, video: Boolean) {
        val me = app.engine.session ?: return
        if (_state.value != null) return // ya hay llamada
        val callId = cu.alain.chat.crypto.Crypto.uuid7()
        val now = System.currentTimeMillis()
        _state.value = CallState(
            callId = callId, roomId = room.roomId, peerUid = room.peerUid ?: "",
            peerName = peerName, video = video, dir = C.CALL_OUT,
            phase = Phase.OUTGOING, startedAt = now,
        )
        appRef = app
        scope.launch {
            logId = app.db.callLogDao().insert(
                CallLog(roomId = room.roomId, peerUid = room.peerUid ?: "", peerName = peerName,
                        kind = if (video) C.CALL_VIDEO else C.CALL_AUDIO, dir = C.CALL_OUT,
                        status = C.CALL_CANCELLED, startedAt = now), // si nadie contesta: cancelada
            )
        }
        audioMode(app, inCall = true, video)
        val s = CallSession(app, room.roomId, video, listener)
        session = s
        s.start { sdp -> // offer listo → señalizar
            scope.launch {
                app.api.broadcast(
                    CallSignals.topicOf(room.roomId), CallSignals.EV,
                    CallSignals.encodeOffer(callId, room.roomId, me.uid, me.uname, video, sdp),
                )
            }
        }
        armTimeout(45_000) { bye(app, "cancelled") }
    }

    /** Llegó señalización del par (la conecta ChatApp.onRealtimeEvent). */
    fun onSignal(app: ChatApp, payload: String) {
        if (!enabled) return
        val myUid = app.engine.session?.uid ?: return
        val sig = CallSignals.parse(payload, myUid) ?: return
        val cur = _state.value
        when (sig) {
            is CallSignals.Signal.Offer -> {
                val now = System.currentTimeMillis()
                if (cur != null) { // ocupado: rechazo automático
                    scope.launch {
                        app.api.broadcast(CallSignals.topicOf(sig.roomId), CallSignals.EV,
                            CallSignals.encodeBye(sig.callId, myUid, "busy"))
                    }
                    return
                }
                if (now < suppressUntil) return // acabo de rechazar otra: silencio
                // registrar + estado RINGING
                val now2 = System.currentTimeMillis()
                _state.value = CallState(
                    callId = sig.callId, roomId = sig.roomId, peerUid = sig.from,
                    peerName = sig.fromName, video = sig.video, dir = C.CALL_IN,
                    phase = Phase.INCOMING, startedAt = now2,
                )
                appRef = app
                scope.launch {
                    logId = app.db.callLogDao().insert(
                        CallLog(roomId = sig.roomId, peerUid = sig.from, peerName = sig.fromName,
                                kind = if (sig.video) C.CALL_VIDEO else C.CALL_AUDIO, dir = C.CALL_IN,
                                status = C.CALL_MISSED, startedAt = now2), // si no contesto: perdida
                    )
                }
                ring(app)
                val pending = CallSession(app, sig.roomId, sig.video, listener)
                pending.prepareAsCallee(sig.sdp)
                session = pending
                armTimeout(40_000) { bye(app, "missed") } // no contesté → perdida
            }

            is CallSignals.Signal.Answer -> {
                if (cur == null || cur.callId != sig.callId || cur.phase != Phase.OUTGOING) return
                cancelTimeout()
                session?.onAnswer(sig.sdp)
            }

            is CallSignals.Signal.Ice -> {
                if (cur == null || cur.callId != sig.callId) return
                session?.onIce(sig)
            }

            is CallSignals.Signal.Bye -> {
                if (cur == null || cur.callId != sig.callId) return
                finish(app, when (sig.reason) {
                    "declined" -> C.CALL_DECLINED
                    "busy" -> C.CALL_DECLINED
                    "missed" -> C.CALL_MISSED
                    else -> if (cur.phase == Phase.ACTIVE) C.CALL_COMPLETED else C.CALL_CANCELLED
                }, notify = sig.reason != "hangup" || cur.phase == Phase.ACTIVE)
            }
        }
    }

    /** El usuario ACEPTA la llamada entrante. */
    fun accept(app: ChatApp) {
        val cur = _state.value ?: return
        if (cur.phase != Phase.INCOMING) return
        val myUid = app.engine.session?.uid ?: return
        cancelTimeout()
        stopRing()
        audioMode(app, inCall = true, cur.video)
        _state.value = cur.copy(phase = Phase.ACTIVE, connectedAt = System.currentTimeMillis())
        session?.answer { sdp ->
            scope.launch {
                app.api.broadcast(CallSignals.topicOf(cur.roomId), CallSignals.EV,
                    CallSignals.encodeAnswer(cur.callId, myUid, sdp))
            }
        }
        armTimeout(120_000) { if (_state.value?.phase == Phase.ACTIVE && _state.value?.connectedAt == 0L) bye(app, "missed") }
    }

    /** El usuario RECHAZA la llamada entrante. */
    fun decline(app: ChatApp) {
        val cur = _state.value ?: return
        val myUid = app.engine.session?.uid ?: return
        suppressUntil = System.currentTimeMillis() + 5_000
        scope.launch {
            app.api.broadcast(CallSignals.topicOf(cur.roomId), CallSignals.EV,
                CallSignals.encodeBye(cur.callId, myUid, "declined"))
        }
        finish(app, C.CALL_DECLINED, notify = false)
    }

    /** El usuario CUELGA (o cierra la pantalla de llamada). */
    fun hangup(app: ChatApp? = appRef) {
        val cur = _state.value ?: return
        val a = app ?: return
        val myUid = a.engine.session?.uid ?: return
        scope.launch {
            a.api.broadcast(CallSignals.topicOf(cur.roomId), CallSignals.EV,
                CallSignals.encodeBye(cur.callId, myUid, "hangup"))
        }
        val status = if (cur.phase == Phase.ACTIVE) C.CALL_COMPLETED else C.CALL_CANCELLED
        finish(a, status, notify = false)
    }

    fun toggleMic() {
        val cur = _state.value ?: return
        session?.setMic(!cur.micOn)
        _state.value = cur.copy(micOn = !cur.micOn)
    }

    fun toggleCam(app: ChatApp) {
        val cur = _state.value ?: return
        session?.setCam(!cur.camOn)
        _state.value = cur.copy(camOn = !cur.camOn)
    }

    fun toggleSpeaker(app: ChatApp) {
        val cur = _state.value ?: return
        val on = !cur.speakerOn
        audioMode(app, inCall = true, video = on) // altavoz = ruta de vídeo
        _state.value = cur.copy(speakerOn = on)
    }

    /** Pistas de vídeo para la UI (overlay). */
    fun currentRemoteTrack(): org.webrtc.VideoTrack? = session?.remoteVideoTrack
    fun currentLocalTrack(): org.webrtc.VideoTrack? = session?.localVideoTrack

    fun switchCam() { session?.switchCamera() }

    // ────────────────────────── plumbing ──────────────────────────

    private val listener = object : CallSession.Listener {
        override fun onConnected() {
            _state.value = _state.value?.let {
                if (it.phase != Phase.ACTIVE) it.copy(phase = Phase.ACTIVE, connectedAt = System.currentTimeMillis())
                else it
            }
            cancelTimeout()
            // cronómetro: al conectar se confirma «completada» al colgar
            val a = appRef ?: return
            val cur = _state.value ?: return
            scope.launch {
                a.db.callLogDao().finish(logId, C.CALL_COMPLETED, cur.connectedAt, 0)
            }
        }

        override fun onIce(sdpMid: String?, lineIndex: Int, candidate: String) {
            val a = appRef ?: return
            val cur = _state.value ?: return
            val myUid = a.engine.session?.uid ?: return
            scope.launch {
                a.api.broadcast(CallSignals.topicOf(cur.roomId), CallSignals.EV,
                    CallSignals.encodeIce(cur.callId, myUid, sdpMid, lineIndex, candidate))
            }
        }

        override fun onFailed() {
            // WebRTC murió (sin ruta de red p.ej.) → colgar todo
            appRef?.let { hangup(it) }
        }
    }

    private fun bye(app: ChatApp, reason: String) {
        val cur = _state.value ?: return
        val myUid = app.engine.session?.uid ?: return
        scope.launch {
            app.api.broadcast(CallSignals.topicOf(cur.roomId), CallSignals.EV,
                CallSignals.encodeBye(cur.callId, myUid, reason))
        }
        finish(app, when (reason) {
            "missed" -> C.CALL_MISSED
            "cancelled" -> C.CALL_CANCELLED
            else -> C.CALL_CANCELLED
        }, notify = false)
    }

    private fun finish(app: ChatApp, status: Int, notify: Boolean) {
        cancelTimeout()
        stopRing()
        session?.close()
        session = null
        val cur = _state.value
        _state.value = cur?.copy(phase = Phase.ENDED)
        audioMode(app, inCall = false, false)
        if (cur != null && logId > 0) {
            val durSec = if (cur.connectedAt > 0) (System.currentTimeMillis() - cur.connectedAt) / 1000 else 0
            scope.launch {
                app.db.callLogDao().finish(
                    logId,
                    status,
                    cur.connectedAt,
                    durSec,
                )
                logId = 0
            }
        }
        // ENDED se muestra 900 ms y se limpia (la UI observa el flow)
        scope.launch {
            delay(900)
            if (_state.value?.phase == Phase.ENDED) _state.value = null
        }
    }

    private fun armTimeout(ms: Long, action: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            action()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel(); timeoutJob = null
    }

    private fun ring(app: ChatApp) {
        stopRing()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                play()
            }
        } catch (_: Exception) {
        }
        try {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600), 0))
            } else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 400, 600), 0)
        } catch (_: Exception) {
        }
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try {
            (appRef?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun audioMode(app: ChatApp, inCall: Boolean, video: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (inCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                // vídeo (o altavoz manual) → altavoz; llamada de voz → oído
                am.isSpeakerphoneOn = video
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            } else {
                am.mode = AudioManager.MODE_NORMAL
                am.isSpeakerphoneOn = false
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }
}
