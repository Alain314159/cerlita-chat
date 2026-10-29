package cu.alain.chat.core

/** Constantes globales y configuración inmutable del cliente. */
object C {
    /** Endpoint base de las Edge Functions (editable en Ajustes). */
    const val DEFAULT_BASE = "https://wuptkddnkahmknolpxtp.supabase.co/functions/v1"

    /**
     * Anon key del proyecto Supabase. Es PÚBLICA por diseño (Supabase la
     * documenta como "publishable"): el gateway la exige como apikey para
     * invocar /functions/v1, pero no otorga acceso a datos — la BD está
     * protegida por RLS sin políticas y el auth es nuestro token propio.
     */
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Ind1cHRrZGRua2FobWtub2xweHRwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI5NTMyNTksImV4cCI6MjEwODUyOTI1OX0.Ao7v2HAbmvH5r-WGPyCdtOdM_15Ghi-eYt89w4ijIv0"

    // ---- Límites espejo del servidor (backend/functions/outbox) ----
    const val MAX_CT = 8192        // bytes de ciphertext (tag incluido)
    const val MIN_CT = 16
    const val NONCE_LEN = 12
    const val MAX_BATCH = 64
    const val SYNC_LIMIT = 200

    // ---- Estados de mensaje en Room ----
    const val ST_SENDING = 0
    const val ST_SENT = 1          // ✓ en el servidor (ack del outbox)
    const val ST_FAILED = 2
    const val ST_RECEIVED = 3
    const val ST_UNREADABLE = 4
    const val ST_DELIVERED = 5     // ✓✓ lo bajó el otro (rd del /sync, D68)
    const val ST_READ = 6          // ✓✓ azul: recibo de lectura E2EE (kind=3, D68)
    const val ST_DELETED = 7       // S2/D110: tumba — «borrado para todos»

    // ---- Kinds de mensaje (solo locales: el servidor ve ciphertext opaco) ----
    const val KIND_TEXT = 0        // burbuja normal
    const val KIND_PROFILE = 1     // anuncio de perfil (D61)
    const val KIND_ROOM_INFO = 2   // nombre/foto de la sala (v0.6.0, D69)
    const val KIND_READ = 3        // recibo de lectura (v0.6.0, D68)
    const val KIND_EDIT = 4        // S2/D110: edición de un mensaje propio
    const val KIND_DELETE = 5      // S2/D110: borrado de un mensaje propio
    const val KIND_REACTION = 6    // S2/D110: reacción a un mensaje
    const val KIND_GAME_SCORE = 7  // J0/D111: récord de minijuego de la pareja

    // ---- Tipos de sala (v0.7.0, D74) ----
    const val ROOM_GROUP = 0       // grupo: código de invitación (como siempre)
    const val ROOM_DM = 1          // chat directo 1-a-1: búsqueda por @nombre

    // ---- Registro de llamadas (v0.7.0) ----
    const val CALL_AUDIO = 0
    const val CALL_VIDEO = 1
    const val CALL_OUT = 0         // saliente
    const val CALL_IN = 1          // entrante
    const val CALL_COMPLETED = 0
    const val CALL_MISSED = 1
    const val CALL_DECLINED = 2
    const val CALL_CANCELLED = 3   // colgó antes de que contestaran

    // ---- Adjuntos E2EE (v0.7.0, D76 · S0'/D109: 8 MB) ----
    const val ATTACH_BUCKET = "attachments"
    /** Tope de adjunto. Bajo de 20 a 8 MB (D109): pico de RAM al cifrar queda
     *  en ~24 MB transitorios (aceptable en gamas bajas de 2-3 GB) y las
     *  subidas larguísimas por ETECSA dejan de ser la norma. El streaming
     *  real de la SUBIDA (desde disco, sin RAM) sí está: storageUploadFile. */
    const val MAX_FILE = 8 * 1024 * 1024  // 8 MB cifrado (bucket: 25)
    const val MAX_RECORD_MS = 5 * 60 * 1000L

    // ---- Canal ntfy (D104) ----
    /** Servidor de avisos por defecto; alterno/self-host si ETECSA lo bloquea. */
    const val NTFY_DEFAULT = "https://ntfy.sh"
    /** Umbral de degradación a despertador puro. ntfy.sh convierte bodies >4 KB
     *  en ATTACHMENT con URL pública (hallazgo H25) — nunca provocarlo. */
    const val NTFY_PING_MAX = 3400
    /** Máx filas por ping: presupuesto anti-spam del parse (m ≤ 8). */
    const val NTFY_PING_ROWS = 8

    // ---- Polling adaptativo (D15) ----
    const val POLL_ACTIVE_MS = 5_000L        // tras actividad
    const val POLL_IDLE_MAX_MS = 300_000L    // reposo máximo: 5 min
    const val POLL_BACKOFF = 2.0             // x2 por sondeo vacío
    const val POLL_FG_MAX_MS = 15_000L       // tope con la app ABIERTA (D71):
                                             // aunque el timbre esté muerto, el
                                             // chat abierto nunca pasa de 15 s

    // ---- Cripto (D3, D33, D34, D35) ----
    const val ARGON_T = 3
    const val ARGON_M_KIB = 65_536           // 64 MiB — solo al configurar/desbloquear
    const val ARGON_P = 1
    const val KEY_LEN = 32
    const val GZIP_THRESHOLD = 512           // bytes del plaintext

    const val USERNAME_RE = "^[a-z0-9_]{3,24}$"

    fun validUsername(u: String) = Regex(USERNAME_RE).matches(u)
}
