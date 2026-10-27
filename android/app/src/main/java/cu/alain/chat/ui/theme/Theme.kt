package cu.alain.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
// Tema CERLITA (v0.7.0, D73): la paleta del mockup de la pareja — cerdito rosa
// + koala gris. Claro, rosado y cálido: burbujas propias salmón, recibidas
// rosa pálido, acentos lavanda (la burbuja del koala) y ✓✓ en rosa profundo.
// ─────────────────────────────────────────────────────────────────────────────
private val CerlitaScheme = lightColorScheme(
    primary = Color(0xFFE56B8C),          // rosa medio: FAB, burbujas propias
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBD0DD),  // chips, tarjetas de acento suaves
    onPrimaryContainer = Color(0xFF5C2E3E),
    secondary = Color(0xFF9B87C9),        // lavanda (burbuja del koala)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E0F7),
    onSecondaryContainer = Color(0xFF3E3059),
    tertiary = Color(0xFFC2185B),         // ✓✓ azul → aquí rosa profundo
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D4E2),
    onTertiaryContainer = Color(0xFF51122F),
    background = Color(0xFFFCF2F5),       // blanco rosado cálido (mockup)
    onBackground = Color(0xFF4A3540),     // ciruela oscuro, no negro puro
    surface = Color(0xFFFFF9FB),
    onSurface = Color(0xFF4A3540),
    surfaceVariant = Color(0xFFF6E3EB),   // burbujas recibidas: rosa pálido
    onSurfaceVariant = Color(0xFF8A6B76),
    surfaceContainer = Color(0xFFFFF7FA),
    surfaceContainerLow = Color(0xFFFFFAFB),
    surfaceContainerHigh = Color(0xFFFBEFF4),
    surfaceContainerHighest = Color(0xFFF8E6ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF3DEE6),
    surfaceBright = Color(0xFFFFF9FB),
    inverseSurface = Color(0xFF4A3540),
    inverseOnSurface = Color(0xFFFBEFF4),
    inversePrimary = Color(0xFFF3A7BE),
    outline = Color(0xFFE5C6D2),
    outlineVariant = Color(0xFFF2DEE6),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF4A3540),
)

/** Modos de tema (v0.7.0): sistema / claro / oscuro / cerlita. */
object Themes {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val CERLITA = "cerlita"

    val OPTIONS = listOf(SYSTEM, LIGHT, DARK, CERLITA)

    /** Etiqueta en español para la UI. */
    fun label(mode: String): String = when (mode) {
        SYSTEM -> "Según el sistema"
        LIGHT -> "Claro"
        DARK -> "Oscuro"
        else -> "Cerlita 💗"
    }
}

/**
 * Tema (P2 + D73): el modo lo elige el usuario en Ajustes (default: cerlita).
 *  - cerlita → paleta fija de la pareja (ignora el color dinámico)
 *  - claro/oscuro → esquemas propios; en API 31+ usan color dinámico del
 *    fondo de pantalla, como siempre (solo se reserva el modo cerlita)
 *  - sistema → claro/oscuro según el teléfono (API <29 siempre oscuro)
 */
@Composable
fun ChatTheme(mode: String = Themes.SYSTEM, content: @Composable () -> Unit) {
    val dark = if (Build.VERSION.SDK_INT >= 29) isSystemInDarkTheme() else true
    val scheme = when (mode) {
        Themes.CERLITA -> CerlitaScheme
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
    MaterialTheme(colorScheme = scheme, content = content)
}
