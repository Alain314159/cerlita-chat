package cu.alain.chat.games

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cu.alain.chat.ChatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * J0/D111: pantalla de minijuego — WebView LOCAL con el puente mínimo
 * `window.Cerlita` (el patrón HTML5+puente de Telegram, sin servidor):
 *
 *  - player():  {name} — quién juega (perfil E2EE local)
 *  - theme():   colores Cerlita vivos (claro/noche según el tema activo)
 *  - record():  {score, holder} — récord E2EE de la pareja en esta sala
 *  - haptic(k): light | heavy → VibrationEffect
 *  - submit(s): puntuación → mensaje de control E2EE (kind=KIND_GAME_SCORE)
 *
 * Superficie mínima y local: el WebView SOLO carga file:///android_asset
 * (cualquier http/https se bloquea), sin red — crítico para ETECSA y para
 * la privacidad. El WebView se destruye al salir (memoria, como Telegram).
 */
class CerlitaBridge(
    private val ctx: Context,
    private val app: ChatApp,
    private val roomId: String,
    private val gameId: String,
    private val themeColors: Map<String, String>,
) {

    @JavascriptInterface
    fun player(): String = try {
        // hilo puente del WebView (no corutina): consulta corta con runBlocking
        val s = app.engine.session
        val name = kotlinx.coroutines.runBlocking {
            s?.let { app.db.profileDao().get(it.uid)?.displayName }
        } ?: s?.uname ?: ""
        JSONObject().put("name", name).toString()
    } catch (_: Exception) {
        JSONObject().put("name", "").toString()
    }

    @JavascriptInterface
    fun theme(): String {
        val j = JSONObject()
        themeColors.forEach { (k, v) -> j.put(k, v) }
        return j.toString()
    }

    @JavascriptInterface
    fun record(): String = try {
        val rec = app.engine.gameRecord(roomId, gameId)
        if (rec != null) JSONObject().put("score", rec.score).put("holder", rec.holder).toString()
        else JSONObject().put("score", 0).toString()
    } catch (_: Exception) {
        JSONObject().put("score", 0).toString()
    }

    @JavascriptInterface
    fun haptic(kind: String) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(
                    VibrationEffect.createOneShot(
                        if (kind == "heavy") 45L else 18L,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else @Suppress("DEPRECATION") v.vibrate(18L)
        } catch (_: Exception) {
        }
    }

    /** La puntuación viaja cifrada como control kind=KIND_GAME_SCORE (D111). */
    @JavascriptInterface
    fun submit(score: Long) {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            try {
                val beaten = app.engine.submitScore(roomId, gameId, score)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        if (beaten) "¡Nuevo récord de la pareja! 🏆" else "Puntuación enviada 💗",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (_: Exception) {
            }
        }
    }

    @JavascriptInterface
    fun close() {
        // la UI nativa decide cerrar; el botón atrás del sistema también sirve
    }
}

/**
 * Pantalla del juego: WebView a pantalla completa con el botón cerrar encima.
 * El tema que se inyecta por el puente es el ACTIVO (Cerlita o Cerlita noche).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameScreen(app: ChatApp, roomId: String, gameId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true // assets locales (API 30+)
                    settings.allowContentAccess = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    settings.mediaPlaybackRequiresUserGesture = true
                    // SOLO assets locales: la red queda fuera del juego
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            !url.startsWith("file:///android_asset/")
                    }
                    addJavascriptInterface(
                        CerlitaBridge(c.applicationContext, app, roomId, gameId, themeMap(c)),
                        "Cerlita",
                    )
                    loadUrl("file:///android_asset/games/$gameId/index.html")
                }
            },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        ) {
            Icon(Icons.Default.Close, "Cerrar juego")
        }
    }
}

/** Colores del tema ACTIVO para inyectar en el juego (claro o noche). */
private fun themeMap(ctx: Context): Map<String, String> {
    val app = ctx.applicationContext as ChatApp
    return if (app.prefs.theme == cu.alain.chat.ui.theme.Themes.CERLITA_NIGHT) {
        mapOf(
            "accent" to "#E8A0AC",
            "bg" to "#20191C",
            "panel" to "#291F23",
            "text" to "#E5D8DC",
            "muted" to "#C9B7BD",
            "grid" to "#3B2F35",
        )
    } else {
        mapOf(
            "accent" to "#D87884",
            "bg" to "#E0C2C4",
            "panel" to "#FBEAEA",
            "text" to "#4A3D3F",
            "muted" to "#7A6167",
            "grid" to "#CCBABA",
        )
    }
}
