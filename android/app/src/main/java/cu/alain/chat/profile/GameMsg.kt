package cu.alain.chat.profile

import org.json.JSONObject

/**
 * J0/D111: minijuegos de la pareja, estilo Telegram — pero E2EE y offline.
 *
 * La burbuja de juego es un mensaje NORMAL (kind=0, visible) cuyo plaintext
 * lleva el prefijo \u0000G: el servidor solo ve ciphertext, como siempre.
 * El juego en sí son assets locales del APK (assets/games/<id>/index.html,
 * 0 red, crítico en ETECSA) dentro de un WebView con un puente mínimo
 * (games/GameScreen.kt) — el mismo patrón HTML5+puente de Telegram, sin
 * servidor de por medio.
 *
 * Récord de la pareja: cada puntuación viaja como mensaje de CONTROL cifrado
 * (kind=KIND_GAME_SCORE, invisible); gana la mayor. El sobre nunca sale del
 * canal E2EE de la sala — nadie más puede saber la puntuación.
 *
 *   plaintext = "\u0000G" + JSON{"g": gameId, "s": score}
 *   score = 0 → «invitación» (la burbuja con el botón Jugar)
 *   score > 0 → actualización de récord (control invisible)
 */
object GameMsg {
    const val PREFIX = "\u0000G"

    /** Catálogo local (assets/games/<id>/). J0: 2048. */
    const val GAME_2048 = "2048"

    fun encode(gameId: String, score: Long): String =
        PREFIX + JSONObject().put("g", gameId.take(32)).put("s", score).toString()

    fun isGame(plain: String): Boolean = plain.startsWith(PREFIX)

    data class Parsed(val gameId: String, val score: Long)

    fun parse(plain: String): Parsed? = try {
        if (!isGame(plain)) return null
        val j = JSONObject(plain.substring(PREFIX.length))
        val g = j.optString("g", "")
        if (g.isEmpty()) null else Parsed(g, j.optLong("s", 0L))
    } catch (_: Exception) {
        null
    }

    /** Vista para notificaciones y vistas previas («Juego 2048»). */
    fun preview(plain: String): String? = parse(plain)?.let { "🎮 Juego 2048" }

    /** Nombre bonito del juego (el catálogo crece en J1). */
    fun name(gameId: String): String = when (gameId) {
        GAME_2048 -> "2048"
        else -> gameId
    }
}
