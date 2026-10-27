package cu.alain.chat

import android.content.Intent
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import cu.alain.chat.notify.DoorbellService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke del servicio del timbre (D63): sin sesión debe entrar a foreground
 * (contrato de startForegroundService) y pararse solo, sin crash ni WebSocket.
 *
 * El camino CON sesión no es testeable en JVM (el vault ss.bin usa
 * AndroidKeyStore real); en producción lo cubre el E2E del timbre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // mismo runtime nativo que SmokeTest
// NOTA: config ÚNICA — con múltiples SDK, Robolectric 4.16 dispara un bug de
// zipfs (FileSystemAlreadyExistsException en maybeCopyFonts) en la última
// sandbox de una clase NUEVA (reproducido con un test vacío); la matriz de
// arranque multi-API ya la cubre SmokeTest.
@Config(sdk = [28], application = ChatApp::class)
class DoorbellServiceTest {

    @Test
    fun `sin sesión arranca foreground y se para`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(ctx.applicationContext is ChatApp)

        // ContextCompat: en API 24-25 cae a startService (startForegroundService es 26+)
        ContextCompat.startForegroundService(ctx, Intent(ctx, DoorbellService::class.java))
        val looper = shadowOf(Looper.getMainLooper())
        repeat(50) { looper.idle() } // onCreate → startForeground → onStartCommand → stopSelf

        // stopSelf procesado → onDestroy corrió → running=false y sin crash
        assert(!DoorbellService.running) { "el servicio debió pararse solo (sin sesión)" }
    }
}
