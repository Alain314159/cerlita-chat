package cu.alain.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Navy950 = Color(0xFF0B1220)
private val Navy900 = Color(0xFF111A2C)
private val Navy800 = Color(0xFF1B2740)
private val Teal400 = Color(0xFF2DD4BF)
private val Teal200 = Color(0xFF99F6E4)
private val Slate300 = Color(0xFFCBD5E1)

private val DarkScheme = darkColorScheme(
    primary = Teal400,
    onPrimary = Navy950,
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Teal200,
    secondary = Teal200,
    background = Navy950,
    onBackground = Slate300,
    surface = Navy900,
    onSurface = Slate300,
    surfaceVariant = Navy800,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurface = Color(0xFF1E293B),
)

// ─────────────────────────────────────────────────────────────────────────────
// Tema CERLITA (v0.7.1, D77) — paleta MEDIDA PÍXEL A PÍXEL del mockup de la
// pareja (muestreo real de la imagen, no estimaciones):
//
//   · fondo de listas / ajustes ... #F6D8D8  (rosa clarito)
//   · tarjetas / superficies ....... #FBEAEA  (rosa casi blanco)
//   · fondo del chat ............... #E0C2C4  (rosa polvo — más intenso que la lista)
//   · burbuja ENVIADA .............. #F6C6CC  (rosa pastel) con texto ciruela oscuro
//   · burbuja RECIBIDA ............. #CCBABA  (gris malva) con texto gris oscuro
//   · acento (enviar/FAB/badges) ... #D87884  (coral)
//   · aceptar / rechazar llamada ... #4FA07C / #E07070
//   El texto dentro de las burbujas del mockup es OSCURO (muestreado #6A4849
//   y #5D5050) — no blanco.
// ─────────────────────────────────────────────────────────────────────────────

private val CerlitaScheme = lightColorScheme(
    primary = Color(0xFFD87884),          // coral del mockup: FAB, enviar, badges, tab activo
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF9CBD0),  // píldora del composer / barra de grabación
    onPrimaryContainer = Color(0xFF5C3440),
    secondary = Color(0xFFB08A96),        // rosa malva (iconos de archivo)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DCDF),
    onSecondaryContainer = Color(0xFF4E3038),
    tertiary = Color(0xFFC24D5E),         // ✓✓ leído: rosa profundo
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D2D8),
    onTertiaryContainer = Color(0xFF511223),
    background = Color(0xFFF6D8D8),       // listas / llamadas / ajustes (píxel del mockup)
    onBackground = Color(0xFF4A3D3F),     // ciruela oscuro — nunca negro puro
    surface = Color(0xFFFBEAEA),          // tarjetas y barra inferior
    onSurface = Color(0xFF4A3D3F),
    surfaceVariant = Color(0xFFCCBABA),   // burbujas recibidas (gris malva del mockup)
    onSurfaceVariant = Color(0xFF594649), // D120: AA 4,7-5,3:1 (era #7A6167 → 3,0:1; el
                                          // tono de la auditoría #6B5157 tampoco llegaba:
                                          // 3,8:1 — medir, no estimar, tools/qa/contrast_gate.py)
    surfaceContainer = Color(0xFFFBE9E9),
    surfaceContainerLow = Color(0xFFFBEFEF),
    surfaceContainerHigh = Color(0xFFF8E2E4),
    surfaceContainerHighest = Color(0xFFF5DADD),
    surfaceContainerLowest = Color(0xFFFFF6F7),
    surfaceDim = Color(0xFFEFDBDD),
    surfaceBright = Color(0xFFFFF4F6),
    inverseSurface = Color(0xFF4A3D3F),
    inverseOnSurface = Color(0xFFFBEAEA),
    inversePrimary = Color(0xFFF0AFBB),
    outline = Color(0xFFE3C3C8),
    outlineVariant = Color(0xFFF0DADD),
    error = Color(0xFFD25454),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF8D7D5),
    onErrorContainer = Color(0xFF490909),
    scrim = Color(0xFF4A3D3F),
)

// ─────────────────────────────────────────────────────────────────────────
// S0'/D108 (U5): CERLITA NOCHE — la misma pareja de rosa/ciruela, invertida.
// No es un dark genérico: los tonos siguen la MISMA rueda cálida de D77
// (coral #D87884 → claro #E8A0AC para contraste sobre oscuro; fondos ciruela
// #20191C/#291F23; burbujas enviadas rosa apagado #6E454E con texto rosado
// claro — el pastel de siempre, de noche). «De noche la app es rosa claro»
// era el pendiente U5 de la auditoría.
// ─────────────────────────────────────────────────────────────────────────

private val CerlitaNightScheme = darkColorScheme(
    primary = Color(0xFFE8A0AC),          // coral aclarado para contraste AA
    onPrimary = Color(0xFF4A2530),
    primaryContainer = Color(0xFF5A3640),
    onPrimaryContainer = Color(0xFFF6D4DA),
    secondary = Color(0xFFD4B4BC),
    onSecondary = Color(0xFF3B2A30),
    secondaryContainer = Color(0xFF4E3A41),
    onSecondaryContainer = Color(0xFFEFE0E4),
    tertiary = Color(0xFFE58A97),         // ✓✓ leído de noche
    onTertiary = Color(0xFF47232C),
    tertiaryContainer = Color(0xFF5C3038),
    onTertiaryContainer = Color(0xFFF8D6DC),
    background = Color(0xFF20191C),       // ciruela muy oscuro (no negro)
    onBackground = Color(0xFFE5D8DC),
    surface = Color(0xFF291F23),
    onSurface = Color(0xFFE5D8DC),
    surfaceVariant = Color(0xFF463A40),
    onSurfaceVariant = Color(0xFFC9B7BD),
    surfaceContainer = Color(0xFF2E2428),
    surfaceContainerLow = Color(0xFF2A2124),
    surfaceContainerHigh = Color(0xFF352A2E),
    surfaceContainerHighest = Color(0xFF3E3236),
    surfaceContainerLowest = Color(0xFF1C1618),
    surfaceDim = Color(0xFF181215),
    surfaceBright = Color(0xFF443639),
    inverseSurface = Color(0xFFE5D8DC),
    inverseOnSurface = Color(0xFF2E2428),
    inversePrimary = Color(0xFF8E4E5B),
    outline = Color(0xFF5E4C52),
    outlineVariant = Color(0xFF463A40),
    error = Color(0xFFE88080),
    onError = Color(0xFF2E1515),
    errorContainer = Color(0xFF5C2626),
    onErrorContainer = Color(0xFFF8D5D5),
    scrim = Color(0xFF000000),
)

/**
 * Colores del CHAT que NO caben en el esquema M3 (D77): en Cerlita las
 * burbujas propias son PASTEL con texto oscuro y el fondo del chat es más
 * rosado que el fondo de las listas — el mockup lo hace así. En claro/oscuro
 * se derivan del esquema y el look queda exactamente igual que siempre.
 */
data class ChatPalette(
    /** Fondo del área de chat; null = usar el fondo por defecto del esquema. */
    val chatWallpaper: Color? = null,
    val sentBubble: Color,
    val onSentBubble: Color,
    val recvBubble: Color,
    val onRecvBubble: Color,
    /** Color del ✓✓ de «leído» dentro de la burbuja propia. */
    val readTick: Color,
    /** Acento fuerte (waveform reproducida, FAB, enviar). */
    val accent: Color,
) {
    companion object {
        fun fromScheme(s: androidx.compose.material3.ColorScheme) = ChatPalette(
            chatWallpaper = null,
            sentBubble = s.primary,
            onSentBubble = s.onPrimary,
            recvBubble = s.surfaceVariant,
            onRecvBubble = s.onSurface,
            readTick = s.tertiary,
            accent = s.primary,
        )
    }
}

val LocalChatPalette = staticCompositionLocalOf {
    ChatPalette.fromScheme(androidx.compose.material3.lightColorScheme())
}

/** Botones de llamada del mockup (D77): verde menta y rojo coral. */
object CallColors {
    val accept = Color(0xFF4FA07C)   // «Aceptar» (muestreado #58A080)
    val reject = Color(0xFFE07070)   // «Rechazar» (muestreado #E07070)
}

/** Modos de tema (v0.7.0): sistema / claro / oscuro / cerlita (+ noche, D108). */
object Themes {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val CERLITA = "cerlita"
    const val CERLITA_NIGHT = "cerlita_night"

    val OPTIONS = listOf(SYSTEM, LIGHT, DARK, CERLITA, CERLITA_NIGHT)

    /** Etiqueta en español para la UI. */
    fun label(mode: String): String = when (mode) {
        SYSTEM -> "Según el sistema"
        LIGHT -> "Claro"
        DARK -> "Oscuro"
        CERLITA_NIGHT -> "Cerlita noche 🌙"
        else -> "Cerlita 💗"
    }
}

/**
 * Tema (P2 + D73/D77): el modo lo elige el usuario en Ajustes (default: cerlita).
 *  - cerlita → paleta MEDIDA del mockup (ignora el color dinámico)
 *  - claro/oscuro → esquemas propios; en API 31+ usan color dinámico del
 *    fondo de pantalla (solo se reserva el modo cerlita)
 *  - sistema → claro/oscuro según el teléfono (API <29 siempre oscuro)
 */
@Composable
fun ChatTheme(mode: String = Themes.SYSTEM, content: @Composable () -> Unit) {
    val dark = if (Build.VERSION.SDK_INT >= 29) isSystemInDarkTheme() else true
    val scheme = when (mode) {
        Themes.CERLITA -> CerlitaScheme
        Themes.CERLITA_NIGHT -> CerlitaNightScheme
        Themes.LIGHT ->
            if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(LocalContext.current)
            else LightScheme
        Themes.DARK ->
            if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current)
            else DarkScheme
        else -> when {
            Build.VERSION.SDK_INT >= 31 ->
                LocalContext.current.let { if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it) }
            dark -> DarkScheme
            else -> LightScheme
        }
    }
    val palette = when (mode) {
        Themes.CERLITA -> ChatPalette(
            chatWallpaper = Color(0xFFE0C2C4),
            sentBubble = Color(0xFFF6C6CC),
            onSentBubble = Color(0xFF5E4247),
            recvBubble = Color(0xFFCCBABA),
            onRecvBubble = Color(0xFF4A3E3E),
            readTick = Color(0xFFC24D5E),
            accent = Color(0xFFD87884),
        )
        Themes.CERLITA_NIGHT -> ChatPalette(
            chatWallpaper = Color(0xFF1C1518),
            sentBubble = Color(0xFF6E454E),      // rosa apagado: el pastel, de noche
            onSentBubble = Color(0xFFF6E4E7),
            recvBubble = Color(0xFF3B2F35),
            onRecvBubble = Color(0xFFD9C9CE),
            readTick = Color(0xFFE58A97),
            accent = Color(0xFFE8A0AC),
        )
        else -> ChatPalette.fromScheme(scheme)
    }

    CompositionLocalProvider(LocalChatPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
