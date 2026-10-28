package cu.alain.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cu.alain.chat.R
import cu.alain.chat.profile.AvatarConfig
import java.io.File

// ───────────────────────── catálogo (D83: arte real) ─────────────────────────

/**
 * Base del avatar. [res] es un raster (WebP con alfa) generado con el
 * pipeline de `docs/AVATARES.md`; [family] agrupa las variantes de color de
 * un mismo personaje, porque las CAPAS (ropa/accesorios) se dibujan con las
 * anclas geométricas de esa familia (Cerdita y Koalita no comparten rig).
 */
data class AvatarBase(val id: String, val label: String, val res: Int, val family: String)

/** Ropa: se dibuja DETRÁS de la base (el cuerpo tapa el cuello). */
data class ClothesItem(val id: String, val label: String, val res: Map<String, Int>)

/** Accesorio: se dibuja ENCIMA de la base. Un accesorio por slot. */
data class AccItem(val id: String, val label: String, val slot: String, val res: Map<String, Int>)

/** Decoración: se dibuja DETRÁS de la base (asoma por encima). */
data class DecorItem(val id: String, val label: String, val res: Map<String, Int>)

/**
 * Catálogo de avatares.
 *
 * D83: el arte deja de ser placeholder. Las bases son raster (WebP) salidos
 * del pipeline medido sobre la ilustración original; ropa, accesorios y
 * decoración son VectorDrawable en un artboard 1024×1024, con el MISMO
 * grosor de trazo y la MISMA paleta, para que compongan encima de la base.
 *
 * COMPATIBILIDAD (D61): los ids "pig", "koala", "tee_pink", "tee_violet",
 * "tee_teal", "dots", "stripes", "bow", "flower", "party", "glasses" y "heart"
 * se MANTIENEN → los perfiles ya guardados en la BD siguen renderizando.
 * Los ids nuevos ("pig_melocoton", "decor"…) solo los entiende un cliente
 * nuevo, que es exactamente el comportamiento degrade-limpio del diseño.
 */
object AvatarCatalog {
    /** Familia de un id de base (para elegir el juego de capas). */
    const val FAM_PIG = "pig"
    const val FAM_KOALA = "koala"

    val bases = listOf(
        AvatarBase("pig", "Cerdita", R.drawable.av_pig, FAM_PIG),
        AvatarBase("pig_melocoton", "Cerdita melocotón", R.drawable.av_pig_melocoton, FAM_PIG),
        AvatarBase("pig_lavanda", "Cerdita lavanda", R.drawable.av_pig_lavanda, FAM_PIG),
        AvatarBase("pig_menta", "Cerdita menta", R.drawable.av_pig_menta, FAM_PIG),
        AvatarBase("koala", "Koalita", R.drawable.av_koala, FAM_KOALA),
        AvatarBase("koala_azul", "Koalita azul", R.drawable.av_koala_azul, FAM_KOALA),
        AvatarBase("koala_cacao", "Koalita cacao", R.drawable.av_koala_cacao, FAM_KOALA),
        AvatarBase("koala_cielo", "Koalita cielo", R.drawable.av_koala_cielo, FAM_KOALA),
    )

    /** null = "sin ropa" (opción explícita del picker). */
    val clothes = listOf(
        ClothesItem(
            "tee_pink", "Camiseta rosa",
            mapOf(FAM_PIG to R.drawable.av_c_tee_pink_pig, FAM_KOALA to R.drawable.av_c_tee_pink_koala),
        ),
        ClothesItem(
            "tee_violet", "Camiseta violeta",
            mapOf(FAM_PIG to R.drawable.av_c_tee_violet_pig, FAM_KOALA to R.drawable.av_c_tee_violet_koala),
        ),
        ClothesItem(
            "tee_teal", "Camiseta turquesa",
            mapOf(FAM_PIG to R.drawable.av_c_tee_teal_pig, FAM_KOALA to R.drawable.av_c_tee_teal_koala),
        ),
        ClothesItem(
            "dots", "Lunares",
            mapOf(FAM_PIG to R.drawable.av_c_dots_pig, FAM_KOALA to R.drawable.av_c_dots_koala),
        ),
        ClothesItem(
            "stripes", "Rayas",
            mapOf(FAM_PIG to R.drawable.av_c_stripes_pig, FAM_KOALA to R.drawable.av_c_stripes_koala),
        ),
    )

    /** Los accesorios de un mismo slot son excluyentes (p. ej. moño O gorro). */
    val accessories = listOf(
        AccItem(
            "bow", "Moño", SLOT_HEAD,
            mapOf(FAM_PIG to R.drawable.av_a_bow_pig, FAM_KOALA to R.drawable.av_a_bow_koala),
        ),
        AccItem(
            "flower", "Flor", SLOT_HEAD,
            mapOf(FAM_PIG to R.drawable.av_a_flower_pig, FAM_KOALA to R.drawable.av_a_flower_koala),
        ),
        AccItem(
            "party", "Gorro de fiesta", SLOT_HEAD,
            mapOf(FAM_PIG to R.drawable.av_a_party_pig, FAM_KOALA to R.drawable.av_a_party_koala),
        ),
        AccItem(
            "glasses", "Gafas de sol", SLOT_EYES,
            mapOf(FAM_PIG to R.drawable.av_a_glasses_pig, FAM_KOALA to R.drawable.av_a_glasses_koala),
        ),
        AccItem(
            "heart", "Corazón", SLOT_CHEST,
            mapOf(FAM_PIG to R.drawable.av_a_heart_pig, FAM_KOALA to R.drawable.av_a_heart_koala),
        ),
    )

    /** Decoración (D83): la burbuja con corazones, la firma de Cerlita. */
    val decors = listOf(
        DecorItem(
            "bubbles", "Burbuja con corazones",
            mapOf(FAM_PIG to R.drawable.av_decor_bubbles_pig, FAM_KOALA to R.drawable.av_decor_bubbles_koala),
        ),
    )

    const val SLOT_HEAD = "head"
    const val SLOT_EYES = "eyes"
    const val SLOT_CHEST = "chest"

    fun base(id: String?) = bases.firstOrNull { it.id == id }

    /** Familia (pig/koala) de un id de base; si no se reconoce, la de Cerdita. */
    fun familyOf(baseId: String?): String = base(baseId)?.family ?: FAM_PIG

    fun clothesRes(id: String?, family: String): Int? =
        clothes.firstOrNull { it.id == id }?.res?.get(family)

    fun accRes(id: String?, family: String): Int? =
        accessories.firstOrNull { it.id == id }?.res?.get(family)

    fun decorRes(id: String?, family: String): Int? =
        decors.firstOrNull { it.id == id }?.res?.get(family)
}

// ───────────────────────── fotos (caché ligera) ─────────────────────────

private val photoCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Decodifica la foto de perfil con submuestreo + caché LRU (evita re-decodificar en listas).
 *  version invalida la caché cuando el archivo fue reescrito (perfil editado). */
fun decodeProfilePhoto(path: String, maxPx: Int = 256, version: Long = 0L): Bitmap? {
    if (path.isEmpty()) return null
    val cacheKey = "$path#$version"
    synchronized(photoCache) { photoCache.get(cacheKey) }?.let { return it }
    val f = File(path)
    if (!f.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    synchronized(photoCache) { photoCache.put(cacheKey, bmp) }
    return bmp
}

// ───────────────────────── renderizador ─────────────────────────

/**
 * Avatar unificado con prioridad: foto pendiente (preview sin guardar) >
 * foto de perfil en disco > avatar por capas > inicial. Cualquier combinación
 * parcial degrada limpia: ids desconocidos no renderizan (compatibilidad con
 * arte futuro servido por red).
 */
@Composable
fun AvatarView(
    photoUid: String?,
    photo: Boolean,
    avatarJson: String?,
    fallbackInitial: String,
    size: Dp,
    modifier: Modifier = Modifier,
    pendingPhotoBytes: ByteArray? = null, // preview recién elegida, aún no guardada
    photoVersion: Long = 0L,              // invalida caché cuando el archivo cambió
) {
    val ctx = LocalContext.current
    val pendingBmp = if (pendingPhotoBytes != null) {
        remember(pendingPhotoBytes) {
            BitmapFactory.decodeByteArray(pendingPhotoBytes, 0, pendingPhotoBytes.size)
        }
    } else null
    val photoPath = if (photo && photoUid != null && pendingBmp == null) {
        File(File(ctx.filesDir, "avatars"), "$photoUid.jpg").absolutePath
    } else null
    val bitmap = if (photoPath != null) {
        remember(photoPath, photoVersion) { decodeProfilePhoto(photoPath, version = photoVersion) }
    } else null

    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                pendingBmp != null -> Image(
                    bitmap = pendingBmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
                else -> {
                    val cfg = remember(avatarJson) { AvatarConfig.parse(avatarJson) }
                    val base = AvatarCatalog.base(cfg?.base)
                    if (base != null) {
                        val family = base.family
                        // D92: margen interno para que el personaje NUNCA toque
                        // el borde del círculo (orejas/pies cortados en v0.9.0).
                        // El fondo del círculo sigue llenando todo el tamaño.
                        val pad = size * 0.07f
                        val layer = Modifier.size(size - pad * 2)
                        Box(modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant).padding(pad)) {
                            // D83: orden de capas -> decoración (DETRÁS) → ropa (DETRÁS)
                            // → base → accesorios (ENCIMA). Todo comparte el
                            // artboard 1024×1024, así que compone exacto.
                            AvatarCatalog.decorRes(cfg?.decor, family)?.let { res ->
                                Image(
                                    painter = painterResource(res), contentDescription = null,
                                    contentScale = ContentScale.Fit, modifier = layer,
                                )
                            }
                            AvatarCatalog.clothesRes(cfg?.clothes, family)?.let { res ->
                                Image(
                                    painter = painterResource(res), contentDescription = null,
                                    contentScale = ContentScale.Fit, modifier = layer,
                                )
                            }
                            Image(
                                painter = painterResource(base.res),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = layer,
                            )
                            // orden estable: head → eyes → chest (los slots no se solapan)
                            listOf(AvatarCatalog.SLOT_HEAD, AvatarCatalog.SLOT_EYES, AvatarCatalog.SLOT_CHEST)
                                .forEach { slot ->
                                    AvatarCatalog.accRes(cfg?.acc?.get(slot), family)?.let { res ->
                                        Image(
                                            painter = painterResource(res), contentDescription = null,
                                            contentScale = ContentScale.Fit, modifier = layer,
                                        )
                                    }
                                }
                        }
                    } else {
                        Text(
                            fallbackInitial.trim().take(1).uppercase().ifEmpty { "?" },
                            style = if (size >= 72.dp) MaterialTheme.typography.headlineMedium
                            else MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}
