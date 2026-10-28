package cu.alain.chat

import cu.alain.chat.ui.Avatar3D
import cu.alain.chat.ui.AvatarCatalog
import cu.alain.chat.profile.AvatarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puente catálogo ⇄ 3D (D101): los ids del wire apuntan a GLB y a nodos-prenda
 * de la familia correcta. El wire NO cambia: un perfil viejo degrada igual.
 */
class Avatar3DTest {

    @Test
    fun `assetPath resuelve por base y degrada ids eliminados`() {
        assertEquals("models/pig.glb", Avatar3D.assetPath("pig"))
        assertEquals("models/pig_melocoton.glb", Avatar3D.assetPath("pig_melocoton"))
        assertEquals("models/koala_cielo.glb", Avatar3D.assetPath("koala_cielo"))
        // pig_menta retirada (D96) → default de la MISMA familia
        assertEquals("models/pig.glb", Avatar3D.assetPath("pig_menta"))
        assertEquals("models/koala.glb", Avatar3D.assetPath("koala_futuro"))
    }

    @Test
    fun `prendas de la familia correcta - vestido solo cerdita, traje solo koala`() {
        // dress en un koala: el GLB del koala NO tiene nodo dress → no se pide
        assertTrue("dress" !in Avatar3D.garmentNodeIds("koala", "dress", emptyMap()))
        assertTrue("dress" in Avatar3D.garmentNodeIds("pig", "dress", emptyMap()))
        assertTrue("suit" in Avatar3D.garmentNodeIds("koala", "suit", emptyMap()))
        assertFalse("suit" in Avatar3D.garmentNodeIds("pig", "suit", emptyMap()))
    }

    @Test
    fun `ropa y accesorios componen el set de nodos a mostrar`() {
        val ids = Avatar3D.garmentNodeIds(
            "koala_azul", "tee_teal",
            mapOf("eyes" to "glasses", "feet" to "shoes_red"),
        )
        assertEquals(setOf("tee_teal", "glasses", "shoes_red"), ids)
    }

    @Test
    fun `ropa de familia equivocada no se muestra (wire tolerante)`() {
        // un perfil corrupto con dress+koala simplemente no dibuja el vestido
        val ids = Avatar3D.garmentNodeIds("koala", "dress", mapOf("head" to "bow"))
        assertEquals(setOf("bow"), ids)
    }

    @Test
    fun `sin ropa ni accesorios el set es vacio`() {
        assertTrue(Avatar3D.garmentNodeIds("pig", null, emptyMap()).isEmpty())
        // acc vacío
        assertTrue(Avatar3D.garmentNodeIds("pig", null, mapOf()).isEmpty())
    }

    @Test
    fun `todos los ids del catalogo existen como nodos en los GLB`() {
        val nodos = Avatar3D.Catalog.ALL_GARMENTS
        for (c in AvatarCatalog.clothes) assertTrue("falta ${c.id}", c.id in nodos)
        for (a in AvatarCatalog.accessories) assertTrue("falta ${a.id}", a.id in nodos)
    }
}
