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

/**
 * Tema (P2): oscuro por defecto, sigue al sistema.
 * En API < 29 no existe el modo oscuro del sistema → siempre oscuro.
 * Dynamic color solo en 31+ y respetando el modo actual.
 */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val dark = if (Build.VERSION.SDK_INT >= 29) isSystemInDarkTheme() else true
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            LocalContext.current.let { if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it) }
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
