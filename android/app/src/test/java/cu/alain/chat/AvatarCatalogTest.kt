package cu.alain.chat

import cu.alain.chat.ui.AvatarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catálogo v0.11 (D95/D96): capas raster, cerdita menta y burbujas
 * ELIMINADAS con degradación limpia, ropa/accesorios por familia.
 */
class AvatarCatalogTest {

    @Test
    fun `base desconocida o eliminada degrada a la familia correcta`() {
        // pig_menta se retiró en v0.11: un perfil guardado con ella sigue
        // renderizando (cerdita rosa), no cae a la inicial seca
        assertEquals("pig", AvatarCatalog.base("pig_menta")?.id)
        assertEquals("koala", AvatarCatalog.base("koala_rosa")?.id) // id futuro/inexistente
        assertEquals("pig", AvatarCatalog.base("pig")?.id)
        assertEquals("koala_cacao", AvatarCatalog.base("koala_cacao")?.id)
        assertNull(AvatarCatalog.base(null))
    }

    @Test
    fun `familia de cualquier id`() {
        assertEquals(AvatarCatalog.FAM_PIG, AvatarCatalog.familyOf("pig_menta"))
        assertEquals(AvatarCatalog.FAM_KOALA, AvatarCatalog.familyOf("koala_cacao"))
        assertEquals(AvatarCatalog.FAM_PIG, AvatarCatalog.familyOf(null))
    }

    @Test
    fun `catalogo sin cerdita menta`() {
        assertTrue(AvatarCatalog.bases.none { it.id == "pig_menta" })
        assertEquals(7, AvatarCatalog.bases.size)
        assertEquals(3, AvatarCatalog.bases.count { it.family == AvatarCatalog.FAM_PIG })
        assertEquals(4, AvatarCatalog.bases.count { it.family == AvatarCatalog.FAM_KOALA })
    }

    @Test
    fun `ropa por familia - vestido solo cerdita, traje solo koala`() {
        val pig = AvatarCatalog.clothesFor(AvatarCatalog.FAM_PIG).map { it.id }
        val koala = AvatarCatalog.clothesFor(AvatarCatalog.FAM_KOALA).map { it.id }
        assertTrue("dress" in pig)
        assertFalse("suit" in pig)
        assertTrue("suit" in koala)
        assertFalse("dress" in koala)
        // ids legacy (perfiles v0.9/v0.10) intactos para ambas familias
        listOf("tee_pink", "tee_violet", "tee_teal", "dots", "stripes").forEach {
            assertTrue("$it debe existir para cerdita", it in pig)
            assertTrue("$it debe existir para koala", it in koala)
        }
    }

    @Test
    fun `accesorios nuevos con slots y recursos por familia`() {
        val ids = AvatarCatalog.accessories.map { it.id }
        listOf(
            "bow", "flower", "party", "cap", "earrings",      // head
            "glasses",                                          // eyes
            "heart",                                            // chest
            "scarf",                                            // neck
            "shoes_red", "shoes_blue",                          // feet
        ).forEach { assertTrue("$it falta en el catálogo", it in ids) }
        // cada accesorio de la familia resuelve recurso drawable
        listOf(AvatarCatalog.FAM_PIG, AvatarCatalog.FAM_KOALA).forEach { fam ->
            AvatarCatalog.accessoriesFor(fam).forEach {
                assertNotNull(AvatarCatalog.accRes(it.id, fam))
            }
        }
    }

    @Test
    fun `ids que no existen degradan limpio`() {
        assertNull(AvatarCatalog.clothesRes("dress", AvatarCatalog.FAM_KOALA)) // vestido en koala
        assertNull(AvatarCatalog.clothesRes("capa_magica", AvatarCatalog.FAM_PIG))
        assertNull(AvatarCatalog.accRes("inexistente", AvatarCatalog.FAM_PIG))
        assertNull(AvatarCatalog.clothesRes(null, AvatarCatalog.FAM_PIG))
    }
}
