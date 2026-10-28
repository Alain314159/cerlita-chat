package cu.alain.chat.profile

import org.json.JSONObject

/**
 * Núcleo de perfiles (D61) — SIN dependencias de Android: testeable en JVM.
 *
 * Un perfil viaja a los pares EXACTAMENTE como un mensaje de chat: cifrado
 * con la clave de la sala. El servidor jamás ve nombres, avatares ni fotos.
 * En el wire, el plaintext lleva un prefijo de control que un mensaje de
 * texto normal no puede tener (el compositor nunca produce \u0000):
 *
 *   plaintext = "\u0000P" + JSON{"n": nombre, "a": avatarJson, "p": fotoB64, "t": tsMs}
 *
 * Formato del avatar (extensible a los avatares creados por usuarios en el
 * futuro): {"v":1,"base":"pig","clothes":"tee_pink","acc":{"head":"bow"}}.
 * `acc` mapea slot→id (slots: head, eyes, chest…); los ids desconocidos se
 * ignoran al renderizar → un cliente viejo muestra lo que reconoce.
 */
object ProfileMsg {
    // D62: ESCAPE \u0000 en el fuente (antes había un byte NUL literal incrustado
    // — git trataba el archivo como binario). En runtime es el mismo carácter.
    const val PREFIX = "\u0000P"

    /** Límites del payload (el ct del servidor admite 8192 B; dejamos margen). */
    const val MAX_NAME = 40
    const val MAX_JSON = 600
    const val MAX_PHOTO_B64 = 6600

    /** Codifica el plaintext del anuncio. photoB64 null/blank = sin foto. */
    fun encode(name: String, avatarJson: String, photoB64: String?, ts: Long): String {
        val j = JSONObject()
            .put("n", name.take(MAX_NAME))
            .put("a", avatarJson.take(MAX_JSON))
            .put("t", ts)
        if (!photoB64.isNullOrBlank()) j.put("p", photoB64)
        return PREFIX + j.toString()
    }

    /** true si un plaintext descifrado es un anuncio de perfil. */
    fun isProfile(plain: String): Boolean = plain.startsWith(PREFIX)

    /**
     * Decodifica. null si no es un anuncio válido (se muestra como texto).
     * Tolerante: campos ausentes → defaults, foto sobredimensionada → ignorada.
     */
    fun parse(plain: String): Parsed? {
        if (!isProfile(plain)) return null
        return try {
            val j = JSONObject(plain.substring(PREFIX.length))
            val name = j.optString("n", "").trim()
            if (name.isEmpty() || name.length > MAX_NAME) return null
            Parsed(
                name = name,
                avatarJson = j.optString("a", "").take(MAX_JSON),
                photoB64 = j.optString("p", "").takeIf { it.length in 1..MAX_PHOTO_B64 },
                ts = j.optLong("t", 1L),
            )
        } catch (_: Exception) {
            null
        }
    }

    data class Parsed(
        val name: String,
        val avatarJson: String,   // "" = sin avatar (solo nombre)
        val photoB64: String?,    // null = sin foto / foto demasiado grande
        val ts: Long,
    )
}

/**
 * Configuración del avatar por capas (base + ropa + accesorios por slot).
 *
 * D83: se añade el slot [decor] (la burbuja con corazones). Es OPCIONAL y
 * se serializa solo si hay valor → los perfiles antiguos (v1) siguen siendo
 * válidos y los clientes que no lo conocen lo ignoran sin romperse.
 */
data class AvatarConfig(
    val base: String?,                       // id de la base ("pig", "koala", "pig_menta"…)
    val clothes: String? = null,             // id de ropa (null = sin ropa)
    val acc: Map<String, String> = emptyMap(), // slot ("head","eyes","chest") → id
    val decor: String? = null,               // decoración detrás de la base ("bubbles")
) {
    fun encode(): String {
        val j = JSONObject().put("v", 1)
        if (!base.isNullOrBlank()) j.put("base", base)
        if (!clothes.isNullOrBlank()) j.put("clothes", clothes)
        if (acc.isNotEmpty()) {
            val a = JSONObject()
            acc.forEach { (slot, id) -> a.put(slot, id) }
            j.put("acc", a)
        }
        if (!decor.isNullOrBlank()) j.put("decor", decor)
        return j.toString()
    }

    companion object {
        /** null si el JSON no parsea; ids vacíos/desconocidos se conservan
         *  (el renderizador ignora lo que no conoce → compatibilidad futura). */
        fun parse(json: String?): AvatarConfig? {
            if (json.isNullOrBlank()) return AvatarConfig(null)
            return try {
                val j = JSONObject(json)
                val acc = mutableMapOf<String, String>()
                val a = j.optJSONObject("acc")
                if (a != null) {
                    val it = a.keys()
                    while (it.hasNext()) {
                        val slot = it.next() as String
                        val id = a.optString(slot, "")
                        if (id.isNotBlank()) acc[slot] = id
                    }
                }
                AvatarConfig(
                    base = j.optString("base", "").ifBlank { null },
                    clothes = j.optString("clothes", "").ifBlank { null },
                    acc = acc,
                    decor = j.optString("decor", "").ifBlank { null },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
