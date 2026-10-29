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

    enum class Phase { OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

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

    /** S1/D107: nivel de señal de la llamada (0 malo · 1 débil · 2 bueno). */
    private val _signal = MutableStateFlow(2)
    val signal: StateFlow<Int> = _signal

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: CallSession? = null
    private var appRef: ChatApp? = null
    private var timeoutJob: Job? = null
    private var ringtone: android.media.Ringtone? = null
    private var logId: Long = 0 // fila del registro en curso
    private var suppressUntil = 0L // anti-bucle tras rechazar

    // D81: orden de la señalización — el offer/answer (SDP) sale PRIMERO y
    // los ICE propios esperan a que ese POST termine; si no, un candidato
    // chiquito puede llegar al par ANTES que el SDP grande y perderse.
    private var sdpSent = false
    private val iceHold = mutableListOf<Triple<String?, Int, String>>()

    var enabled: Boolean = true // la UI del overlay lo apaga cuando nadie la observa

    /** Broadcast de señalización CIFRADA (B6) con reintentos (la llamada muere si se pierde). */
    private suspend fun broadcastSig(app: ChatApp, roomId: String, callId: String, payload: org.json.JSONObject): Boolean {
        val topic = CallSignals.topicOf(roomId)
        // B6: el SDP (con las IPs), los ICE y el nombre viajan bajo sobre
        // cifrado con la clave de señalización de la sala; el servidor solo
        // ve topic (roomId, que ya sabe) y un callId aleatorio.
        val wire = app.engine.sigKeyFor(roomId, callId)?.let { k ->
            CallSignals.wrapEncrypted(k, callId, roomId, payload)
        } ?: payload // sin clave (no debería ocurrir en un DM): legacy en claro
        repeat(3) {
            if (app.api.broadcast(topic, CallSignals.EV, wire)) return true
            kotlinx.coroutines.delay(1_500)
        }
        return false
    }

    private fun resetSignalingOrder() {
        sdpSent = false
        iceHold.clear()
    }

    /** Arranca una llamada SALIENTE desde un chat directo. */
    fun startOutgoing(app: ChatApp, room: Room, peerName: String, video: Boolean) {
        val me = app.engine.session ?: return
        if (_state.value != null) return // ya hay llamada
        val callId = cu.alain.chat.crypto.Crypto.uuid7()
        val now = System.currentTimeMillis()
        resetSignalingOrder()
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
        audioMode(app, inCall = true, speakerOn = video) // D81: ruta de audio desde ya
        CallService.start(app) // S1/D107: el FGS mic/cam evita que Android
        // congele la llamada al apagar la pantalla o salir de la app
        // B6/R3: los ICE servers (STUN+TURN) se resuelven ANTES de crear la
        // sesión — la caché del login lo hace instantáneo casi siempre; si
        // hay que ir al servidor, 4 s tope y luego STUN (nunca se cancela
        // una llamada por esto).
        scope.launch {
            val ice = TurnConfig.ensure(app)
            val s = CallSession(app, room.roomId, video, listener, ice, asCallee = false)
            session = s
            s.start { sdp -> // offer listo → señalizar; los ICE esperan su turno (D81)
                scope.launch {
                    broadcastSig(app, room.roomId, callId, CallSignals.encodeOffer(callId, room.roomId, me.uid, me.uname, video, sdp))
                    sdpSent = true
                    iceHold.forEach { (m, l, c) ->
                        broadcastSig(app, room.roomId, callId, CallSignals.encodeIce(callId, me.uid, m, l, c))
                    }
                    iceHold.clear()
                }
            }
        }
        armTimeout(45_000) { bye(app, "cancelled") }
    }

    /** Llegó señalización del par (la conecta ChatApp.onRealtimeEvent). */
    fun onSignal(app: ChatApp, payload: String) {
        if (!enabled) return
        val myUid = app.engine.session?.uid ?: return
        // B6: abrir el sobre cifrado v0.8.0 antes de parsear. roomId/callId van
        // fuera (el servidor ya conoce el roomId por el topic; callId es
        // aleatorio) — TODO lo demás viaja cifrado. Los payloads legacy en
        // claro de v0.7.x siguen entrando (un v0.7 llamando a un v0.8 suena).
        val inner: String = try {
            val j = org.json.JSONObject(payload)
            if (CallSignals.isEncrypted(j)) {
                val encCallId = j.optString("c", "")
                val encRoomId = j.optString("r", "")
                val k = app.engine.sigKeyFor(encRoomId, encCallId) ?: return
                CallSignals.unwrapEncrypted(k, encCallId, encRoomId, j) ?: return
            } else payload
        } catch (_: Exception) {
            return
        }
        val sig = CallSignals.parse(inner, myUid) ?: return
        val cur = _state.value
        when (sig) {
            is CallSignals.Signal.Offer -> {
                val now = System.currentTimeMillis()
                if (cur != null) { // ocupado: rechazo automático
                    scope.launch {
                        broadcastSig(app, sig.roomId, sig.callId,
                            CallSignals.encodeBye(sig.callId, myUid, "busy"))
                    }
                    return
                }
                if (now < suppressUntil) return // acabo de rechazar otra: silencio
                // registrar + estado RINGING
                val now2 = System.currentTimeMillis()
                resetSignalingOrder()
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
                // S1/D107 (§4.2-2): con la app en background/pantalla apagada la
                // llamada «no existía» — notificación full-screen que prende la
                // pantalla por encima del bloqueo (si el sistema lo permite).
                if (!app.inForeground) {
                    cu.alain.chat.notify.Notifier.showIncomingCall(
                        app, sig.fromName, sig.video, sig.roomId,
                    )
                }
                // B6/R3: resolver ICE (STUN+TURN) antes de crear la sesión
                scope.launch {
                    val ice = TurnConfig.ensure(app)
                    // asCallee=true (D107): la cámara NO arranca hasta Aceptar
                    val pending = CallSession(app, sig.roomId, sig.video, listener, ice, asCallee = true)
                    pending.prepareAsCallee(sig.sdp)
                    session = pending
                }
                armTimeout(40_000) { bye(app, "missed") } // no contesté → perdida
            }

            is CallSignals.Signal.Answer -> {
                if (cur == null || cur.callId != sig.callId || cur.phase != Phase.OUTGOING) return
                cancelTimeout()
                // D81: el que llama VE que contestaron al instante (antes se
                // quedaba en «Llamando…» hasta que conectara el medio — y si
                // el ICE fallaba, para siempre)
                _state.value = cur.copy(phase = Phase.CONNECTING)
                session?.onAnswer(sig.sdp)
                armTimeout(35_000) {
                    if (_state.value?.phase == Phase.CONNECTING) bye(app, "failed")
                }
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
        val callId = cur.callId
        val roomId = cur.roomId
        cancelTimeout()
        stopRing()
        cu.alain.chat.notify.Notifier.cancelIncomingCall(app) // FSI cumplió
        audioMode(app, inCall = true, speakerOn = cur.video)
        CallService.start(app) // S1/D107: mic/cám vivos aunque se apague la pantalla
        session?.startCameraIfNeeded() // D107: la cámara del receptor vive AHORA
        // D81: CONNECTING hasta que el medio conecte (el cronómetro ya no
        // arranca «de mentira» al aceptar)
        _state.value = cur.copy(phase = Phase.CONNECTING)
        session?.answer { sdp ->
            scope.launch {
                // el answer sale PRIMERO; los ICE propios esperan su turno
                broadcastSig(app, roomId, callId, CallSignals.encodeAnswer(callId, myUid, sdp))
                sdpSent = true
                iceHold.forEach { (m, l, c) ->
                    broadcastSig(app, roomId, callId, CallSignals.encodeIce(callId, myUid, m, l, c))
                }
                iceHold.clear()
            }
        }
        armTimeout(35_000) {
            if (_state.value?.phase == Phase.CONNECTING) bye(app, "failed")
        }
    }

    /** El usuario RECHAZA la llamada entrante. */
    fun decline(app: ChatApp) {
        val cur = _state.value ?: return
        val myUid = app.engine.session?.uid ?: return
        suppressUntil = System.currentTimeMillis() + 5_000
        cu.alain.chat.notify.Notifier.cancelIncomingCall(app)
        scope.launch {
            broadcastSig(app, cur.roomId, cur.callId,
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
            broadcastSig(a, cur.roomId, cur.callId,
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
        audioMode(app, inCall = true, speakerOn = on) // D81: ruta real del altavoz
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
                // D81: sin offer/answer en vuelo, los ICE salen directos; si aún
                // no salió el SDP, esperan (el POST del SDP va primero)
                if (sdpSent) {
                    broadcastSig(a, cur.roomId, cur.callId, CallSignals.encodeIce(cur.callId, myUid, sdpMid, lineIndex, candidate))
                } else {
                    iceHold.add(Triple(sdpMid, lineIndex, candidate))
                }
            }
        }

        override fun onFailed() {
            // WebRTC murió (sin ruta de red p.ej.) → colgar todo
            appRef?.let { hangup(it) }
        }

        override fun onSignal(level: Int) { _signal.value = level } // S1/D107
    }

    private fun bye(app: ChatApp, reason: String) {
        val cur = _state.value ?: return
        val myUid = app.engine.session?.uid ?: return
        scope.launch {
            broadcastSig(app, cur.roomId, cur.callId, CallSignals.encodeBye(cur.callId, myUid, reason))
        }
        finish(app, when (reason) {
            "missed" -> C.CALL_MISSED
            "failed" -> C.CALL_CANCELLED // contestó pero no conectó (D81)
            "cancelled" -> C.CALL_CANCELLED
            else -> C.CALL_CANCELLED
        }, notify = false)
    }

    private fun finish(app: ChatApp, status: Int, notify: Boolean) {
        cancelTimeout()
        stopRing()
        resetSignalingOrder()
        session?.close()
        session = null
        _signal.value = 2 // S1/D107: reset para la próxima
        cu.alain.chat.notify.Notifier.cancelIncomingCall(app)
        CallService.stop(app) // S1/D107: fuera el FGS de la llamada
        val cur = _state.value
        _state.value = cur?.copy(phase = Phase.ENDED)
        audioMode(app, inCall = false, speakerOn = false)
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

    /** Ruta de audio de la llamada (D81): API 31+ usa setCommunicationDevice
     *  (lo exige Android 12+ — setSpeakerphoneOn está deprecado y varios OEM
     *  lo ignoran, dejando la llamada sin audio); abajo, la vía clásica. */
    private fun audioMode(app: ChatApp, inCall: Boolean, speakerOn: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (inCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val wanted = if (speakerOn) android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type == wanted }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = speakerOn // vídeo → altavoz; voz → oído
                }
                try {
                    am.requestAudioFocus(
                        null, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    )
                } catch (_: Exception) {
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) {
                    try { am.clearCommunicationDevice() } catch (_: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
                try { am.abandonAudioFocus(null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }
}
