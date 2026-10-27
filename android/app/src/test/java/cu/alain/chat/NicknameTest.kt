package cu.alain.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D78: apodos locales — el nombre personal de cómo me aparece el otro.
 * Round-trip de persistencia + normalización (uid minúsculas, límite 40).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NicknameTest {

    private val prefs = Prefs(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `guardar y leer apodo por uid`() {
        prefs.setNickname("ABC123", "Mi amor")
        assertEquals("Mi amor", prefs.nicknameOf("ABC123"))
        // uid con mayúsculas/minúsculas → misma clave
        assertEquals("Mi amor", prefs.nicknameOf("abc123"))
    }

    @Test
    fun `vacío o null borra el apodo`() {
        prefs.setNickname("u1", "Coco")
        prefs.setNickname("u1", "  ")
        assertNull(prefs.nicknameOf("u1"))

        prefs.setNickname("u2", "Kiko")
        prefs.setNickname("u2", null)
        assertNull(prefs.nicknameOf("u2"))
    }

    @Test
    fun `allNicknames lista solo apodos con uid minúscula`() {
        prefs.setNickname("UserA", "Alain")
        prefs.setNickname("UserB", "Amelia")
        val all = prefs.allNicknames()
        assertEquals("Alain", all["usera"])
        assertEquals("Amelia", all["userb"])
        assertTrue(all.keys.none { it.contains(Regex("[A-Z]")) })
    }

    @Test
    fun `límite de 40 caracteres como máximo`() {
        prefs.setNickname("u3", "x".repeat(80))
        assertEquals(40, prefs.nicknameOf("u3")!!.length)
    }
}
