package cu.alain.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

// ───────────────────────── catálogo (D61: placeholders) ─────────────────────────

data class AvatarBase(val id: String, val label: String, val res: Int)
data class ClothesItem(val id: String, val label: String, val res: Int)
data class AccItem(val id: String, val label: String, val slot: String, val res: Int)

/**
 * Catálogo de avatares. HOY: 2 bases dibujadas (Cerdita y Koalita) + ropa y
 * accesorios placeholder. MAÑANA (avatares creados por el usuario): los ids
 * nuevos simplemente se añaden aquí o se sirven por red; [AvatarConfig] y el
 * renderizador ya son agnósticos al origen del arte.
 */
object AvatarCatalog {
    val bases = listOf(
        AvatarBase("pig", "Cerdita", R.drawable.av_pig),
        AvatarBase("koala", "Koalito", R.drawable.av_koala),
    )

    /** null = "sin ropa" (opción explícita del picker). */
    val clothes = listOf(
        ClothesItem("tee_violet", "Camiseta violeta", R.drawable.av_c_tee_violet),
        ClothesItem("tee_pink", "Camiseta rosa", R.drawable.av_c_tee_pink),
        ClothesItem("tee_teal", "Camiseta turquesa", R.drawable.av_c_tee_teal),
        ClothesItem("dots", "Lunares", R.drawable.av_c_dots),
        ClothesItem("stripes", "Rayas", R.drawable.av_c_stripes),
    )

    /** Los accesorios de un mismo slot son excluyentes (p. ej. moño O gorro). */
    val accessories = listOf(
        AccItem("glasses", "Gafas", SLOT_EYES, R.drawable.av_a_glasses),
        AccItem("bow", "Moño", SLOT_HEAD, R.drawable.av_a_bow),
        AccItem("party", "Gorro de fiesta", SLOT_HEAD, R.drawable.av_a_party),
        AccItem("flower", "Flor", SLOT_HEAD, R.drawable.av_a_flower),
        AccItem("heart", "Corazón", SLOT_CHEST, R.drawable.av_a_heart),
    )

    const val SLOT_HEAD = "head"
    const val SLOT_EYES = "eyes"
    const val SLOT_CHEST = "chest"

    fun base(id: String?) = bases.firstOrNull { it.id == id }
    fun clothesRes(id: String?) = clothes.firstOrNull { it.id == id }?.res
    fun accRes(id: String?) = accessories.firstOrNull { it.id == id }?.res
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
                        Box(modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            // capas en orden: base → ropa → accesorios
                            Image(
                                painter = painterResource(base.res),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(size),
                            )
                            AvatarCatalog.clothesRes(cfg?.clothes)?.let { res ->
                                Image(
                                    painter = painterResource(res), contentDescription = null,
                                    contentScale = ContentScale.Fit, modifier = Modifier.size(size),
                                )
                            }
                            // orden estable: head → eyes → chest (los slots no se solapan)
                            listOf(AvatarCatalog.SLOT_HEAD, AvatarCatalog.SLOT_EYES, AvatarCatalog.SLOT_CHEST)
                                .forEach { slot ->
                                    AvatarCatalog.accRes(cfg?.acc?.get(slot))?.let { res ->
                                        Image(
                                            painter = painterResource(res), contentDescription = null,
                                            contentScale = ContentScale.Fit, modifier = Modifier.size(size),
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
