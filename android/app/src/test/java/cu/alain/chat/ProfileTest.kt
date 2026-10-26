package cu.alain.chat

import cu.alain.chat.profile.AvatarConfig
import cu.alain.chat.profile.ProfileMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM del sistema de perfiles (D61): formato del anuncio (prefijo NUL+P,
 * límites, tolerancia a basura) y roundtrip del AvatarConfig por capas.
 * El cripto del anuncio (seal/open con AAD) ya está cubierto por CryptoRoomsTest
 * — aquí probamos SOLO la capa de perfil, sin Android.
 */
class ProfileTest {

    // ───────────── ProfileMsg: formato de cable ─────────────

    @Test
    fun `el prefijo es NUL + P y no aparece en texto normal`() {
        assertEquals("\u0000P", ProfileMsg.PREFIX)
        assertFalse(ProfileMsg.isProfile("hola"))
        assertFalse(ProfileMsg.isProfile(""))
        assertFalse(ProfileMsg.isProfile("{\"n\":\"x\",\"t\":1}")) // JSON pelado ≠ anuncio
        assertTrue(ProfileMsg.isProfile(ProfileMsg.encode("Ana", "{}", null, 1L)))
    }

    @Test
    fun `roundtrip completo con foto`() {
        val b64 = "A" .repeat(100)
        val plain = ProfileMsg.encode("Cerlita", """{"v":1,"base":"pig"}""", b64, 123456L)
        val p = ProfileMsg.parse(plain)
        assertNotNull(p)
        assertEquals("Cerlita", p!!.name)
        assertEquals("""{"v":1,"base":"pig"}""", p!!.avatarJson)
        assertEquals(b64, p!!.photoB64)
        assertEquals(123456L, p!!.ts)
    }

    @Test
    fun `roundtrip sin foto`() {
        val p = ProfileMsg.parse(ProfileMsg.encode("Alain", "", null, 7L))
        assertNotNull(p)
        assertEquals("Alain", p!!.name)
        assertEquals("", p!!.avatarJson)
        assertNull(p!!.photoB64)
    }

    @Test
    fun `foto sobredimensionada se ignora al parsear`() {
        val huge = "Q".repeat(ProfileMsg.MAX_PHOTO_B64 + 1)
        val p = ProfileMsg.parse(ProfileMsg.encode("Ana", "{}", huge, 1L))
        assertNotNull(p)
        assertNull(p!!.photoB64) // se muestra el avatar, la foto se pierde
        assertEquals("Ana", p!!.name)
    }

    @Test
    fun `anuncios corruptos devuelven null y se muestran como texto`() {
        assertNull(ProfileMsg.parse("\u0000Pno-json"))
        assertNull(ProfileMsg.parse("\u0000P"))                       // sin JSON
        assertNull(ProfileMsg.parse("\u0000P{\"t\":1}"))              // sin nombre
        assertNull(ProfileMsg.parse("\u0000P{\"n\":\"\",\"t\":1}"))   // nombre vacío
        assertNull(ProfileMsg.parse("\u0000P{\"n\":\"${"x".repeat(41)}\",\"t\":1}")) // largo
        // texto con NUL pegado deliberadamente pero sin JSON válido → texto normal
        assertFalse(ProfileMsg.parse("\u0000PX") != null || !ProfileMsg.isProfile("\u0000PX"))
    }

    @Test
    fun `el nombre se trunca al codificar`() {
        val plain = ProfileMsg.encode("y".repeat(80), "{}", null, 1L)
        val p = ProfileMsg.parse(plain)
        assertNotNull(p)
        assertEquals(ProfileMsg.MAX_NAME, p!!.name.length)
    }

    // ───────────── AvatarConfig: capas y futuro ─────────────

    @Test
    fun `roundtrip del avatar por capas`() {
        val cfg = AvatarConfig(
            base = "koala",
            clothes = "tee_pink",
            acc = mapOf("head" to "bow", "eyes" to "glasses", "chest" to "heart"),
        )
        val parsed = AvatarConfig.parse(cfg.encode())
        assertEquals(cfg, parsed)
    }

    @Test
    fun `avatar minimo`() {
        val parsed = AvatarConfig.parse(AvatarConfig("pig").encode())
        assertEquals("pig", parsed?.base)
        assertNull(parsed?.clothes)
        assertTrue(parsed!!.acc.isEmpty())
    }

    @Test
    fun `json invalido o vacio degrada limpio`() {
        assertNull(AvatarConfig.parse("esto no es json"))
        assertNull(AvatarConfig.parse("{roto"))
        // vacío/null = "sin avatar" (solo nombre) — el renderer mostrará inicial
        assertEquals(AvatarConfig(null), AvatarConfig.parse(null))
        assertEquals(AvatarConfig(null), AvatarConfig.parse(""))
    }

    @Test
    fun `ids desconocidos del futuro se conservan`() {
        // un cliente futuro añade slots/ids que hoy no dibujamos: no se pierden
        val json = """{"v":2,"base":"dragon","clothes":"cape","acc":{"wings":"red","head":"crown"}}"""
        val parsed = AvatarConfig.parse(json)
        assertNotNull(parsed)
        assertEquals("dragon", parsed!!.base)   // el renderer ignorará lo que no conozca
        assertEquals("cape", parsed!!.clothes)
        assertEquals("red", parsed!!.acc["wings"])
        assertEquals("crown", parsed!!.acc["head"])
    }

    @Test
    fun `acc con ids vacios se descartan`() {
        val json = """{"v":1,"base":"pig","acc":{"head":"","eyes":"glasses"}}"""
        val parsed = AvatarConfig.parse(json)
        assertNotNull(parsed)
        assertFalse(parsed!!.acc.containsKey("head"))
        assertEquals("glasses", parsed!!.acc["eyes"])
    }
}
