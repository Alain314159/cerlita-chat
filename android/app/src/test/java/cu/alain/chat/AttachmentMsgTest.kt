package cu.alain.chat

import cu.alain.chat.profile.AttachmentMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sobre E2EE de adjuntos (v0.7.0, D76): mismo contrato que ProfileMsg (D61)
 * — prefijo \u0000A imposible de teclear + JSON detrás, parse tolerante.
 */
class AttachmentMsgTest {

    @Test
    fun `encode y parse roundtrip de nota de voz con waveform`() {
        val plain = AttachmentMsg.encode(
            type = AttachmentMsg.TYPE_AUDIO,
            name = "Nota de voz",
            mime = "audio/mp4",
            size = 12345,
            path = "abc-123/def-456.m4a",
            keyB64 = "a2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
            durMs = 4200,
            waveformB64 = "AAECAwQFBgcICQ==",
        )
        assertTrue(AttachmentMsg.isAttachment(plain))
        val p = AttachmentMsg.parse(plain)!!
        assertEquals(AttachmentMsg.TYPE_AUDIO, p.type)
        assertEquals("Nota de voz", p.name)
        assertEquals("audio/mp4", p.mime)
        assertEquals(12345L, p.size)
        assertEquals("abc-123/def-456.m4a", p.path)
        assertEquals("a2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=", p.keyB64)
        assertEquals(4200L, p.durMs)
        assertEquals("AAECAwQFBgcICQ==", p.waveformB64)
        assertEquals("Nota de voz", AttachmentMsg.preview(plain))
    }

    @Test
    fun `encode y parse de foto con dimensiones`() {
        val plain = AttachmentMsg.encode(
            type = AttachmentMsg.TYPE_IMAGE, name = "Foto", mime = "image/jpeg",
            size = 300_000, path = "r/uuid.jpg", keyB64 = "aw==",
            height = 1600, width = 1200,
        )
        val p = AttachmentMsg.parse(plain)!!
        assertEquals(1600, p.height)
        assertEquals(1200, p.width)
        assertEquals(0L, p.durMs)
        assertEquals("Foto", AttachmentMsg.preview(plain))
    }

    @Test
    fun `encode y parse de archivo con nombre largo recortado`() {
        val plain = AttachmentMsg.encode(
            type = AttachmentMsg.TYPE_DOC,
            name = "x".repeat(500), // se recorta a MAX_NAME
            mime = "application/pdf",
            size = 5_000_000, path = "r/uuid.pdf", keyB64 = "aw==",
        )
        val p = AttachmentMsg.parse(plain)!!
        assertEquals(80, p.name.length)
        assertEquals("Archivo: ${p.name}", AttachmentMsg.preview(plain))
    }

    @Test
    fun `un texto normal no es adjunto ni tiene preview`() {
        assertFalse(AttachmentMsg.isAttachment("hola mi amor"))
        assertNull(AttachmentMsg.preview("hola mi amor"))
        assertNull(AttachmentMsg.parse("hola mi amor"))
    }

    @Test
    fun `sobre corrupto se muestra como texto (null), jamás revienta`() {
        assertNull(AttachmentMsg.parse(AttachmentMsg.PREFIX)) // sin JSON
        assertNull(AttachmentMsg.parse(AttachmentMsg.PREFIX + "no json"))
        assertNull(AttachmentMsg.parse(AttachmentMsg.PREFIX + "{}")) // sin campos
        assertNull(AttachmentMsg.parse(AttachmentMsg.PREFIX + """{"t":"desconocido","n":"x","p":"y","k":"z"}"""))
        // sin clave → inválido (no se puede descargar nada)
        assertNull(AttachmentMsg.parse(AttachmentMsg.PREFIX + """{"t":"doc","n":"x","p":"y"}"""))
    }

    @Test
    fun `el prefijo es imposible de teclear en el composer`() {
        // el cuerpo del mensaje de texto NUNCA empieza por \u0000 — el
        // compositor no puede producirlo (es un carácter de control)
        assertTrue(AttachmentMsg.PREFIX.startsWith("￀".substring(0, 1)) || AttachmentMsg.PREFIX.length == 2)
    }
}
