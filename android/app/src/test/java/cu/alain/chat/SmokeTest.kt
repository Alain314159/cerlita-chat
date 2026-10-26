package cu.alain.chat

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke test de arranque — reproduce el cold-start de un teléfono real:
 * ChatApp.onCreate completo (Room, WorkManager on-demand, Notifier, engine) +
 * lanzamiento de MainActivity con la composición Compose de la pantalla Login.
 *
 * Un crash "la toco y se cierra sin abrir" revive aquí con su stack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [24, 28, 31, 33, 35], application = ChatApp::class) // matriz de API reales
class SmokeTest {

    @Test
    fun coldStartFreshInstall() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val looper = shadowOf(Looper.getMainLooper())
                repeat(200) { looper.idle() } // LaunchedEffects + Recomposer
            }
        }
    }
}
