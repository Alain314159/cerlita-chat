package cu.alain.chat.calls

import android.content.Context
import cu.alain.chat.ChatApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sesión WebRTC de UNA llamada (v0.7.0, D75).
 *
 * - Audio SIEMPRE (micrófono → pista remota); vídeo si la llamada es de vídeo.
 * - Cámara frontal por defecto (cambiar con switchCamera).
 * - S1/D107 (privacidad): en el RECEPTOR de una videollamada la cámara NO
 *   arranca al sonar — solo al pulsar Aceptar. La PISTA se crea igual (la
 *   negociación SDP la necesita) pero sin capturador: nada sale del teléfono
 *   y el indicador de cámara permanece apagado hasta aceptar.
 * - S1/D107 (calidad adaptativa): captura a 720p si la cámara lo soporta
 *   (con fallback 640×480), bitrate tope 900 kbps y WebRTC adapta la
 *   resolución/fps solo según el ancho de banda real (GCC/REMB) — en red
 *   buena se ve mejor, en 2G degrada solo, sin cortar la llamada.
 * - S1/D107 (señal): monitor getStats cada 3 s → nivel 0/1/2 para la UI
 *   («señal débil» en vez de «se cortó»).
 * - ICE (v0.8.0, B6/R3): STUN + TURN servido por /turn — en NAT simétrico el
 *   relé es la única ruta; el media sigue E2EE (DTLS-SRTP).
 * - La señalización la hace [CallDispatcher]; esta clase solo negocia.
 *
 * La factory se inicializa UNA vez por proceso (el JNI pesa decenas de MB).
 */
class CallSession(
    private val app: ChatApp,
    roomId: String,
    private val withVideo: Boolean,
    private val listener: Listener,
    /** ICE servers (STUN+TURN) — el dispatcher los trae de TurnConfig. */
    iceServers: List<PeerConnection.IceServer>? = null,
    /** true = RECEPTOR: la cámara espera a [startCameraIfNeeded] (Aceptar). */
    private val asCallee: Boolean = false,
) {

    interface Listener {
        /** El par conectó (DTLS listo): arranca el cronómetro. */
        fun onConnected()

        /** Candidato ICE local → señalizar al par. */
        fun onIce(sdpMid: String?, lineIndex: Int, candidate: String)

        /** Fallo irrecuperable (sin ruta de red, cámara muerta…). */
        fun onFailed()

        /** S1/D107: nivel de señal 0 (malo) · 1 (débil) · 2 (bueno). */
        fun onSignal(level: Int)
    }

    companion object {
        @Volatile private var factory: PeerConnectionFactory? = null

        private fun ensureFactory(ctx: Context): PeerConnectionFactory =
            factory ?: synchronized(this) {
                factory ?: run {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(ctx)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions(),
                    )
                    PeerConnectionFactory.builder()
                        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl().eglBaseContext, true, true))
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl().eglBaseContext))
                        .createPeerConnectionFactory()
                        .also { factory = it }
                }
            }

        /** UNA sola EGL base por proceso (compartida por encoder/captura/render). */
        @Volatile private var eglBase: EglBase? = null
        fun egl(): EglBase = eglBase ?: synchronized(this) {
            eglBase ?: EglBase.create().also { eglBase = it }
        }

        private val STUN = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** D81: SDP e ICE se serializan — cada uno llega por un POST propio y
     *  sin orden; el mutex + IceQueue garantizan que NINGÚN candidato se
     *  pierda por llegar "demasiado pronto". */
    private val negotiate = Mutex()
    private val iceQ = IceQueue()

    /** Completa cuando el offer del par quedó aplicado (prepareAsCallee). */
    private val remoteReady = CompletableDeferred<Unit>()

    private val pc: PeerConnection
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoHelper: SurfaceTextureHelper? = null

    /** Pista de vídeo del par: la UI la enlaza a su renderer. */
    @Volatile var remoteVideoTrack: VideoTrack? = null
        private set
    @Volatile var localVideoTrack: VideoTrack? = null
        private set

    /** Se dispara al aparecer la pista remota (la UI re-enlaza el render). */
    @Volatile var onRemoteVideo: ((VideoTrack) -> Unit)? = null

    private var closed = false
    private var connectedNotified = false
    private var captureStarted = false
    private var camW = 640
    private var camH = 480

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onIce(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (!connectedNotified) { connectedNotified = true; listener.onConnected() }
                    startStatsMonitor() // S1/D107: señal para la UI
                }
                PeerConnection.PeerConnectionState.FAILED ->
                    // D81: SIEMPRE termina (antes, si ya había conectado una vez,
                    // la llamada quedaba zombi sin forma de colgarla)
                    listener.onFailed()
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    if (!connectedNotified) listener.onFailed()
                else -> {}
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.let { track ->
                remoteVideoTrack = track
                onRemoteVideo?.invoke(track)
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    init {
        val f = ensureFactory(app)
        val servers = iceServers ?: STUN.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = f.createPeerConnection(rtcConfig, pcObserver)
            ?: throw IllegalStateException("no se pudo crear la conexión")
        // Audio con procesamiento DSP (B6 auditoría): sin AEC, en una llamada
        // con altavoz el otro oye TU propia voz con retardo — el defecto de
        // audio #1. AGC y NS son gratis del mismo tirón.
        val audio = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
        }
        audioSource = f.createAudioSource(audio)
        audioTrack = f.createAudioTrack("audio0", audioSource).also {
            pc.addTrack(it, listOf("stream0"))
        }
        if (withVideo) createVideoTrack(f, startCameraNow = !asCallee)
    }

    /**
     * Crea la pista de vídeo y la añade al PC SIEMPRE (la negociación SDP lo
     * exige); la CÁMARA solo arranca si toca. Resolución: 720p si la cámara
     * lo soporta (suelo: 640×480) y bitrate tope 900 kbps — el codificador
     * degrada solo según la red, la llamada nunca se corta por calidad.
     */
    private fun createVideoTrack(f: PeerConnectionFactory, startCameraNow: Boolean) {
        try {
            val enumerator = Camera2Enumerator(app)
            val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull() ?: return
            val formats = try { enumerator.getSupportedFormats(front) } catch (_: Exception) { null }
            if (formats?.any { it.width >= 1280 && it.height >= 720 } == true) {
                camW = 1280; camH = 720
            }
            val capturer = enumerator.createCapturer(front, null) ?: return
            val source = f.createVideoSource(false)
            val helper = SurfaceTextureHelper.create("call-capture", egl().eglBaseContext)
            capturer.initialize(helper, app, source.capturerObserver)
            videoCapturer = capturer; videoSource = source; videoHelper = helper
            localVideoTrack = f.createVideoTrack("video0", source).also {
                pc.addTrack(it, listOf("stream0"))
            }
            // S1/D107: techo de 900 kbps — protege los datos en ETECSA; por
            // debajo del techo WebRTC usa lo que la red deje (adaptativo).
            try {
                pc.senders.filter { it.track()?.kind() == "video" }.forEach { s ->
                    val p = s.parameters
                    p.encodings.firstOrNull()?.maxBitrateBps = 900_000
                    s.parameters = p
                }
            } catch (_: Exception) {
            }
            if (startCameraNow) startCamera()
        } catch (_: Exception) {
            listener.onFailed()
        }
    }

    private fun startCamera() {
        val c = videoCapturer ?: return
        if (captureStarted) return
        try {
            c.startCapture(camW, camH, 24)
            captureStarted = true
        } catch (_: Exception) {
            try { // cámara sin 720p: suelo seguro de toda la vida
                c.startCapture(640, 480, 24)
                camW = 640; camH = 480
                captureStarted = true
            } catch (_: Exception) {
            }
        }
    }

    /** S1/D107: la cámara del receptor arranca AQUÍ (al pulsar Aceptar). */
    fun startCameraIfNeeded() = startCamera()

    // ────────────────────────── llamante ──────────────────────────

    /** Crea el offer, lo fija local y lo entrega para señalizar. */
    fun start(onOffer: (String) -> Unit) {
        scope.launch {
            try {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
                }
                val offer = pc.createOfferSuspend(constraints)
                pc.setLocalSuspend(offer)
                onOffer(offer.description)
            } catch (_: Exception) {
                listener.onFailed()
            }
        }
    }

    // ────────────────────────── llamado ──────────────────────────

    /** Aplica el offer del llamante (nada se envía hasta aceptar). Los ICE
     *  del llamante que lleguen mientras tanto se acumulan en la IceQueue. */
    fun prepareAsCallee(remoteSdp: String) {
        scope.launch {
            try {
                negotiate.withLock {
                    pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
                    iceQ.remoteApplied()
                    iceQ.drain().forEach { addIceSafe(it) }
                }
                remoteReady.complete(Unit)
            } catch (_: Exception) {
                listener.onFailed()
            }
        }
    }

    /** Tras pulsar Aceptar: crea el answer (esperando el offer si hace
     *  falta) y lo entrega para señalizar. */
    fun answer(onAnswer: (String) -> Unit) {
        scope.launch {
            try {
                // D81: aceptar puede ganarle a prepareAsCallee (SDP aún en vuelo)
                val okOffer = withTimeoutOrNull(5_000) { remoteReady.await() }
                if (okOffer == null) return@launch listener.onFailed()
                val ans = negotiate.withLock {
                    pc.createAnswerSuspend(MediaConstraints())
                        .also { pc.setLocalSuspend(it) }
                }
                onAnswer(ans.description)
            } catch (_: Exception) {
                listener.onFailed()
            }
        }
    }

    /** Llegó el answer del par: fija el SDP remoto y drena los ICE que
     *  llegaron antes (D81 — antes se perdían y la llamada nunca conectaba). */
    fun onAnswer(remoteSdp: String) {
        scope.launch {
            try {
                negotiate.withLock {
                    pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
                    iceQ.remoteApplied()
                    iceQ.drain().forEach { addIceSafe(it) }
                }
                remoteReady.complete(Unit) // inofensivo si ya estaba
            } catch (_: Exception) {
                listener.onFailed()
            }
        }
    }

    fun onIce(sig: CallSignals.Signal.Ice) {
        scope.launch {
            negotiate.withLock {
                val c = IceCandidate(sig.sdpMid.ifEmpty { null }, sig.lineIndex, sig.candidate)
                if (iceQ.add(c)) addIceSafe(c)
            }
        }
    }

    private fun addIceSafe(c: IceCandidate) {
        try {
            pc.addIceCandidate(c)
        } catch (_: Exception) {
        }
    }

    // ────────────────────────── controles ──────────────────────────

    fun setMic(on: Boolean) { audioTrack?.setEnabled(on) }

    fun setCam(on: Boolean) {
        localVideoTrack?.setEnabled(on)
        try {
            if (on) startCamera()
            else if (captureStarted) {
                videoCapturer?.stopCapture()
                captureStarted = false
            }
        } catch (_: Exception) {}
    }

    fun switchCamera() {
        try { videoCapturer?.switchCamera(null) } catch (_: Exception) {}
    }

    fun close() {
        if (closed) return
        closed = true
        statsJob?.cancel()
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { videoHelper?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { pc.dispose() } catch (_: Exception) {}
    }

    // ────────────────── señal (S1/D107): getStats → nivel 0/1/2 ──────────────────

    private var statsJob: Job? = null

    private fun startStatsMonitor() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (isActive && !closed) {
                delay(3_000)
                val lvl = computeSignalLevel()
                if (lvl >= 0) listener.onSignal(lvl)
            }
        }
    }

    /** RTT del candidate-pair nominado: <250 ms bueno · <550 ms débil · malo. */
    private suspend fun computeSignalLevel(): Int = try {
        val report = suspendCancellableCoroutine<org.webrtc.RTCStatsReport?> { cont ->
            try {
                pc.getStats { r -> if (cont.isActive) cont.resume(r) }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        } ?: return -1
        var rtt = -1.0
        for (s in report.statsMap.values) {
            if (s.type != "candidate-pair") continue
            val m = s.members
            if (m["nominated"] == true && m["state"] == "succeeded" &&
                m["currentRoundTripTime"] is Number
            ) {
                rtt = (m["currentRoundTripTime"] as Number).toDouble()
                break
            }
        }
        when {
            rtt < 0 -> -1 // sin datos aún: no opinar
            rtt < 0.25 -> 2
            rtt < 0.55 -> 1
            else -> 0
        }
    } catch (_: Exception) {
        -1
    }

    // ────────────────────────── puentes suspend ──────────────────────────

    private suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createOffer(CreateObserver({ cont.resume(it) }, { cont.resumeWithException(IllegalStateException(it ?: "offer")) }), constraints)
        }

    private suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createAnswer(CreateObserver({ cont.resume(it) }, { cont.resumeWithException(IllegalStateException(it ?: "answer")) }), constraints)
        }

    private suspend fun PeerConnection.setLocalSuspend(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { cont ->
            setLocalDescription(SetObserver({ cont.resume(Unit) }, { cont.resumeWithException(IllegalStateException(it ?: "local")) }), sdp)
        }

    private suspend fun PeerConnection.setRemoteSuspend(sdp: SessionDescription): Unit =
        suspendCancellableCoroutine { cont ->
            setRemoteDescription(SetObserver({ cont.resume(Unit) }, { cont.resumeWithException(IllegalStateException(it ?: "remote")) }), sdp)
        }

    /** Observador de «crear offer/answer»: entrega el sdp o el error. */
    private class CreateObserver(
        private val onOk: (SessionDescription) -> Unit,
        private val onErr: (String?) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) { onOk(sdp ?: return) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { onErr(error) }
        override fun onSetFailure(error: String?) { onErr(error) }
    }

    /** Observador de «fijar sdp local/remoto»: confirma o falla. */
    private class SetObserver(
        private val onOk: () -> Unit,
        private val onErr: (String?) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { onOk() }
        override fun onCreateFailure(error: String?) { onErr(error) }
        override fun onSetFailure(error: String?) { onErr(error) }
    }
}
