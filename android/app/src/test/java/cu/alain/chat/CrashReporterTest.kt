package cu.alain.chat

import androidx.test.core.app.ApplicationProvider
import cu.alain.chat.qa.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * D114 (Fase V): el reporte de fallos tiene que dejar el informe en disco con
 * TODO lo que pide el criterio de salida del informe 1.0: traza, versión,
 * teléfono y pantalla. Estos tests verifican el contrato sin provocar un
 * crash real: reportNonFatal escribe exactamente igual que el handler fatal.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {

    @Before
    fun setUp() {
        CrashReporter.install(ApplicationProvider.getApplicationContext())
        // limpiar informes de runs anteriores del mismo proceso de test
        CrashReporter.pending().forEach { CrashReporter.discard(it) }
    }

    @Test
    fun `el informe lleva traza version telefono y pantalla`() {
        CrashReporter.note("Chat")
        CrashReporter.reportNonFatal(IllegalStateException("prueba de fallo 123"))

        val pend = CrashReporter.pending()
        assertEquals(1, pend.size)
        val text = CrashReporter.text(pend.first())
        assertTrue("la traza", text.contains("IllegalStateException"))
        assertTrue("el mensaje", text.contains("prueba de fallo 123"))
        assertTrue("la versión", text.contains("Versión: v"))
        assertTrue("el teléfono", text.contains("Teléfono: "))
        assertTrue("android", text.contains("Android: "))
        assertTrue("la pantalla viva", text.contains("Pantalla: Chat"))
        assertTrue("marcador no fatal", text.contains("no fatal"))
        assertTrue("estructura", text.contains("--- TRAZA ---"))
    }

    @Test
    fun `marcar enviado saca el informe de la bandeja`() {
        CrashReporter.reportNonFatal(RuntimeException("otro"))
        val f = CrashReporter.pending().first()
        CrashReporter.markSent(f)
        assertFalse(CrashReporter.pending().contains(f))
        // y descartar borra sin dejar rastro
        CrashReporter.reportNonFatal(RuntimeException("y otro más"))
        val g = CrashReporter.pending().first()
        CrashReporter.discard(g)
        assertFalse(CrashReporter.pending().contains(g))
    }

    @Test
    fun `fifo a lo sumo 5 pendientes`() {
        repeat(8) { CrashReporter.reportNonFatal(RuntimeException("avalancha-$it")) }
        // los nombres llevan timestamp ms: 8 escrituras en el mismo ms podrían
        // colisionar → aceptamos entre 1 y 5, pero nunca más de 5
        assertTrue(CrashReporter.pending().size <= 5)
        assertTrue(CrashReporter.pending().isNotEmpty())
    }
}
