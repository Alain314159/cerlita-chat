package cu.alain.chat.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Message
import cu.alain.chat.db.Room
import cu.alain.chat.vault.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import del export cifrado (P4 + D47): la mitad que faltaba del respaldo.
 *
 * Lee un archivo .e2ee generado por [HistoryExport], lo descifra con la frase
 * de export y restaura salas + claves (al vault sellado por Keystore) e
 * historial (a Room). Es la vía de recuperación cuando se pierde el teléfono
 * o se borra la app: el archivo + la frase devuelven TODO.
 *
 * El parseo es JVM puro (verificable en tests); solo [import] toca Android.
 */
object HistoryImport {

    private val MAGIC = "E2EEEXP2".toByteArray(Charsets.US_ASCII)
    private val AAD = "cerlita-chat-export-v2".toByteArray(Charsets.UTF_8)
    private val AAD_LEGACY = "alain-chat-export-v2".toByteArray(Charsets.UTF_8) // exports de v0.2.0

    /** Tope de tamaño: un export real son KB; 64 MB evita OOM con archivos basura. */
    private const val MAX_BLOB = 64L * 1024 * 1024

    // ─────────────────────────── errores ───────────────────────────

    /** El archivo no tiene el formato de un export (magic/estructura/plaintext). */
    class FormatException(msg: String) : Exception(msg)

    /** La frase no coincide (el tag AEAD no cuadra) o el archivo está corrupto. */
    class BadPassphraseException : Exception("La frase no coincide con este archivo")

    // ─────────────────────────── datos ───────────────────────────

    data class RoomIn(
        val roomId: String,
        val name: String,
        val owner: Boolean,
        val sas: String,
        val keyB64: String?,   // roomKey b64; null si el export no la traía
        val saltB64: String?,
    )

    data class MsgIn(
        val msgId: String,
        val roomId: String,
        val senderUname: String,
        val mine: Boolean,
        val body: String,
        val ts: Long,
        val state: Int,
    )

    data class Parsed(
        val me: String,
        val exportedAt: Long,
        val rooms: List<RoomIn>,
        val messages: List<MsgIn>,
    )

    data class ImportResult(
        val me: String,
        val roomsAdded: Int,
        val keysRestored: Int,
        val messagesRestored: Int,
    )

    // ─────────────────────────── parse (JVM puro) ───────────────────────────

    /**
     * Descifra y parsea el blob. Estados "sending" del export → FAILED
     * (el outbox no viaja en el export: un sending quedaría colgado para siempre).
     * Acepta el AAD actual y el legacy de v0.2.0.
     *
     * [derive] es inyectable SOLO para los tests JVM (argon2kt es JNI — D38);
     * en producción siempre es [HistoryExport.exportKey], compartida con el
     * export para que ambas mitades usen la derivación idéntica.
     */
    fun parse(
        blob: ByteArray,
        passphrase: CharArray,
        derive: (CharArray, ByteArray) -> ByteArray = HistoryExport::exportKey,
    ): Parsed {
        if (blob.size < MAGIC.size + 16 + C.NONCE_LEN + 16) throw FormatException("Archivo demasiado corto")
        if (!Crypto.constEq(blob.copyOfRange(0, 8), MAGIC)) throw FormatException("No es un export de Cerlita Chat")

        val salt = blob.copyOfRange(8, 24)
        val nonce = blob.copyOfRange(24, 36)
        val ct = blob.copyOfRange(36, blob.size)

        val key = derive(passphrase, salt)
        // tag inválido con ambos AAD → frase incorrecta (o archivo corrupto)
        val wire = Crypto.open(key, nonce, ct, AAD)
            ?: Crypto.open(key, nonce, ct, AAD_LEGACY)
            ?: run { Crypto.zero(key); throw BadPassphraseException() }
        Crypto.zero(key)

        val json = Crypto.unframe(wire) ?: throw FormatException("Contenido ilegible")

        val root2 = org.json.JSONObject(json)
        if (root2.optInt("v", 0) != 2) throw FormatException("Versión de export desconocida")

        val rooms = mutableListOf<RoomIn>()
        val rArr = root2.optJSONArray("rooms") ?: org.json.JSONArray()
        for (i in 0 until rArr.length()) {
            val r = rArr.getJSONObject(i)
            val k = r.optString("k", "")
            val s = r.optString("s", "")
            rooms.add(
                RoomIn(
                    roomId = r.optString("id"),
                    name = r.optString("name"),
                    owner = r.optBoolean("owner", false),
                    sas = r.optString("sas", ""),
                    keyB64 = k.ifBlank { null },
                    saltB64 = s.ifBlank { null },
                ),
            )
        }

        val msgs = mutableListOf<MsgIn>()
        val mArr = root2.optJSONArray("messages") ?: org.json.JSONArray()
        for (i in 0 until mArr.length()) {
            val a = mArr.getJSONArray(i)
            val state = a.optInt(6, C.ST_RECEIVED)
            msgs.add(
                MsgIn(
                    msgId = a.optString(0),
                    roomId = a.optString(1),
                    senderUname = a.optString(2),
                    mine = a.optInt(3, 0) == 1,
                    body = a.optString(4),
                    ts = a.optLong(5, 0),
                    // sending sin outbox → failed (no quedará "enviando" eterno)
                    state = if (state == C.ST_SENDING) C.ST_FAILED else state,
                ),
            )
        }

        if (rooms.any { it.roomId.isBlank() } || msgs.any { it.msgId.isBlank() }) {
            throw FormatException("Export con datos incompletos")
        }
        return Parsed(
            me = root2.optString("me", "?"),
            exportedAt = root2.optLong("exported_at", 0),
            rooms = rooms,
            messages = msgs,
        )
    }

    // ─────────────────────────── aplicar (Android) ───────────────────────────

    /**
     * Punto de entrada de la UI: lee el archivo del picker, descifra y aplica.
     *
     * Política de fusión (offline-first, nunca destruye lo local):
     *  - salas nuevas → insertar; existentes → NO pisa el nombre local
     *  - claves → se añaden SOLO si este teléfono no las tenía (la local manda)
     *  - mensajes → INSERT OR IGNORE (dedupe por msgId)
     */
    suspend fun import(
        ctx: Context,
        db: AppDatabase,
        store: SessionStore,
        uri: Uri,
        passphrase: CharArray,
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val blob = readBlob(ctx, uri) ?: return@withContext Result.failure(FormatException("No se pudo leer el archivo"))
            val parsed = parse(blob, passphrase)

            var roomsAdded = 0
            var keysRestored = 0
            val now = System.currentTimeMillis()
            parsed.rooms.forEachIndexed { i, r ->
                val existing = db.roomDao().get(r.roomId)
                if (existing == null) {
                    db.roomDao().upsert(
                        Room(
                            roomId = r.roomId,
                            name = r.name.ifBlank { "Sala ${r.roomId.take(8)}" },
                            owner = r.owner,
                            sas = r.sas,
                            // preserva el orden del export aunque se importen a la vez
                            createdAt = now - (parsed.rooms.size - i),
                        ),
                    )
                    roomsAdded++
                }
                if (r.keyB64 != null && r.saltB64 != null && !store.hasRoomKey(r.roomId)) {
                    store.putRoomKey(r.roomId, b64d(r.keyB64), b64d(r.saltB64))
                    keysRestored++
                }
            }

            var messagesRestored = 0
            parsed.messages.forEach { m ->
                val inserted = db.msgDao().insert(
                    Message(
                        msgId = m.msgId,
                        roomId = m.roomId,
                        senderUname = m.senderUname,
                        mine = m.mine,
                        body = m.body,
                        ts = m.ts,
                        state = m.state,
                        seq = null, // el import no conoce el seq del servidor
                    ),
                )
                if (inserted != -1L) messagesRestored++
            }

            Result.success(ImportResult(parsed.me, roomsAdded, keysRestored, messagesRestored))
        } catch (e: BadPassphraseException) {
            Result.failure(e)
        } catch (e: FormatException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(FormatException("Archivo ilegible o dañado"))
        }
    }

    private fun readBlob(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length > MAX_BLOB) return null
            afd.createInputStream()?.use { it.readBytes() }
        }
    } catch (_: Exception) {
        null
    }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
