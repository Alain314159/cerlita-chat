package cu.alain.chat.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * (D101) Puente entre el catálogo de avatares (ids del wire, sin cambios) y los
 * modelos 3D reales (GLB con esqueleto y prendas como mallas).
 *
 * Un GLB por COLORWAY (materiales pre-horneados en el pipeline de Blender):
 * cero recoloreo en runtime, cero riesgo de materiales en dispositivos raros.
 * Las prendas viajan DENTRO del GLB como nodos con el nombre exacto del id del
 * catálogo — la app muestra/oculta nodos, el wire no cambia ni un byte.
 */
object Avatar3D {

    /** Ruta del GLB de una base (degrada con AvatarCatalog: pig_menta→pig). */
    fun assetPath(baseId: String?): String {
        val b = AvatarCatalog.base(baseId) ?: return "models/pig.glb"
        return "models/${b.id}.glb"
    }

    /** ids de nodos-prenda a MOSTRAR para una configuración (familia-aware). */
    fun garmentNodeIds(baseId: String?, clothes: String?, acc: Map<String, String>): Set<String> {
        val fam = AvatarCatalog.familyOf(baseId)
        val out = mutableSetOf<String>()
        // ropa: solo si existe para la familia (dress solo cerdita, suit solo koala)
        if (clothes != null && AvatarCatalog.clothesFor(fam).any { it.id == clothes }) out += clothes
        // accesorios: igual — solo los de la familia
        for (a in acc.values) {
            if (AvatarCatalog.accessoriesFor(fam).any { it.id == a }) out += a
        }
        return out
    }

    /** Aplica la configuración al ModelNode mostrando/ocultando las prendas. */
    fun applyWear(root: ModelNode, baseId: String?, clothes: String?, acc: Map<String, String>) {
        val want = garmentNodeIds(baseId, clothes, acc)
        for (gid in Catalog.ALL_GARMENTS) {
            findNode(root, gid)?.let { it.isVisible = gid in want }
        }
    }

    /** Busca un nodo por nombre en el árbol (los GLB aplanan jerarquías). */
    fun findNode(root: Node, name: String): Node? {
        if (root.name == name) return root
        for (c in root.childNodes) {
            findNode(c, name)?.let { return it }
        }
        return null
    }

    /** Duración (ms) de cada clip tal como se hornearon en Blender. */
    private val CLIP_MS = mapOf("idle" to 4000L, "wave" to 2600L, "react" to 1100L)

    /** El idle (respirar) en bucle infinito. */
    fun startIdle(node: ModelNode) {
        runCatching { node.playAnimation("idle", loop = true) }
    }

    /**
     * Reproduce un clip UNA vez (saludo / reacción al vestirlo) y al terminar
     * reanuda la respiración. La API de animación es la propia de ModelNode
     * (playAnimation/stopAnimation sobre el Animator de gltfio).
     */
    fun playOnce(node: ModelNode, name: String) {
        runCatching {
            node.stopAnimation("idle")
            node.playAnimation(name, loop = false)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            // el nodo puede haberse desmontado si salió de la pantalla
            runCatching { node.playAnimation("idle", loop = true) }
        }, CLIP_MS[name] ?: 1500L)
    }

    /** Todos los ids de prenda que existen en los GLB (nombres de nodos). */
    object Catalog {
        val ALL_GARMENTS = setOf(
            "tee_pink", "tee_violet", "tee_teal", "dots", "stripes", "dress", "suit",
            "shoes_red", "shoes_blue", "glasses", "bow", "flower", "party", "cap",
            "earrings", "heart", "scarf",
        )
    }
}

// ─────────────────────── snapshot del 3D para el chat (PixelCopy) ──────────────

/**
 * Busca el SurfaceView del avatar 3D en la jerarquía (el composable de
 * SceneView 4.x no expone la View) y copia su contenido a un Bitmap con fondo
 * rosa del tema (JPEG no soporta transparencia). API 26+; en 24/25 devuelve null
 * y el chat cae al render 2D de siempre (degradación limpia).
 */
fun capture3DAvatar(rootView: View, sizePx: Int = 512): Bitmap? {
    if (Build.VERSION.SDK_INT < 26) return null
    val sv = findSurfaceView(rootView) ?: return null
    val w = sv.width.takeIf { it > 0 } ?: return null
    val h = sv.height.takeIf { it > 0 } ?: return null
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    return try {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        PixelCopy.request(sv, src, { r -> ok = (r == PixelCopy.SUCCESS); latch.countDown() },
            Handler(Looper.getMainLooper()))
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) return null
        // aplanar alpha sobre el fondo del tema y cuadrar al centro
        val side = minOf(w, h)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0xFB, 0xEA, 0xEA))
        val m = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        val rect = android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        c.drawBitmap(src, android.graphics.Rect(left.toInt(), top.toInt(),
            (left + side).toInt(), (top + side).toInt()), rect, m)
        out
    } catch (_: Exception) {
        null
    } finally {
        src.recycle()
    }
}

private fun findSurfaceView(v: View): SurfaceView? = when (v) {
    is SurfaceView -> v
    is ViewGroup -> {
        for (i in 0 until v.childCount) {
            findSurfaceView(v.getChildAt(i))?.let { return it }
        }
        null
    }
    else -> null
}
