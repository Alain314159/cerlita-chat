package cu.alain.chat.ui

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import cu.alain.chat.profile.AvatarConfig
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * El editor de avatar con 3D DE VERDAD (D101):
 *  - El modelo es geometría real (GLB con esqueleto) → arrastrar ORBITA la
 *    cámara alrededor del muñeco (SceneView lo trae de fábrica): cualquier
 *    ángulo, silueta real, sin "cartel de cartón".
 *  - La ropa son MALLAS al mismo esqueleto → visten con volumen y giran con el
 *    cuerpo (nada de pegatinas).
 *  - TOCARLO = saludo (animación esquelética del brazo, no un rebote de imagen).
 *  - Vestirlo → reacción squash feliz (sabe que lo vistes, D98 en 3D).
 *  - Respira en reposo (idle en bucle).
 *
 * (D102) Presentación a la altura del look de la app:
 *  - Fondo TRANSPARENTE (isOpaque=false): el negro de v0.12.0 desaparece; el
 *    muñeco flota sobre un escenario del tema (degradado suave + sombra de
 *    suelo), estilo Memoji — y el mismo degradado se ve a través del render.
 *  - Los GLB ahora miran a +Z (frente canónica glTF, arreglo en el pipeline):
 *    la cámara por defecto de SceneView ve la CARA, no la espalda.
 *  - scaleToUnits 1.9 + centerOrigin: el muñeco centrado y grande (v0.12 lo
 *    dejaba chico y abajo por los pies en el origen).
 *  - Fallback limpio: mientras carga (o si el dispositivo no puede) se ve el
 *    render 2D de siempre (rasters D102) — nunca un hueco negro.
 */
@Composable
fun Avatar3DPreview(
    cfg: AvatarConfig,
    modifier: Modifier = Modifier,
    onWave: (() -> Unit)? = null,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val path = Avatar3D.assetPath(cfg.base)
    val inst = rememberModelInstance(modelLoader, path)

    var waveTick by remember { mutableIntStateOf(0) }
    var downX by remember { mutableStateOf(0f) }
    var downY by remember { mutableStateOf(0f) }
    var downT by remember { mutableStateOf(0L) }

    val stageC = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLowest
    val stageE = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    val shadowC = androidx.compose.material3.MaterialTheme.colorScheme.scrim

    Box(modifier.clip(RoundedCornerShape(28.dp))) {
        // (D102) escenario: degradado radial suave + sombra elíptica de suelo.
        // Se dibuja DETRÁS: el render 3D es transparente y lo deja ver.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        Brush.radialGradient(
                            listOf(stageC, stageE),
                            center = Offset(w * 0.5f, h * 0.36f),
                            radius = maxOf(w, h) * 0.78f,
                        )
                    )
                    val cx = w * 0.5f
                    val cy = h * 0.885f
                    val r = w * 0.30f
                    withTransform({ scale(scaleX = 1f, scaleY = 0.16f, pivot = Offset(cx, cy)) }) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(shadowC.copy(alpha = 0.16f), Color.Transparent),
                                center = Offset(cx, cy),
                                radius = r,
                            ),
                            radius = r,
                            center = Offset(cx, cy),
                        )
                    }
                }
        )
        // placeholder/fallback 2D: visible solo si el 3D no está listo
        if (inst == null) {
            val base = AvatarCatalog.base(cfg.base)
            if (base != null) LayeredAvatar(base, cfg, Modifier.fillMaxSize())
        } else {
            var node by remember { mutableStateOf<ModelNode?>(null) }

            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                isOpaque = false, // (D102) transparente: se ve el escenario detrás
                onTouchEvent = { e, _ ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                        }
                        MotionEvent.ACTION_UP -> {
                            val dt = System.currentTimeMillis() - downT
                            val dx = kotlin.math.abs(e.x - downX)
                            val dy = kotlin.math.abs(e.y - downY)
                            // tap corto y quieto (el arrastre es de la cámara)
                            if (dt < 350 && dx < 24f && dy < 24f) {
                                waveTick++
                                onWave?.invoke()
                            }
                        }
                    }
                    false // no consumir: el orbit de la cámara sigue funcionando
                },
            ) {
                ModelNode(
                    modelInstance = inst,
                    autoAnimate = false,
                    // (D102) 1.9 unidades + centrado en el origen: llena ~83%
                    // del encuadre de la cámara por defecto de SceneView
                    // (FOV≈45°, z=2.75) y queda centrado, no "anclado a los pies"
                    scaleToUnits = 1.9f,
                    apply = {
                        centerOrigin()
                        node = this
                        Avatar3D.applyWear(this, cfg.base, cfg.clothes, cfg.acc)
                    },
                )
                // respirar en bucle en cuanto el nodo existe
                LaunchedEffect(node) {
                    node?.let { Avatar3D.startIdle(it) }
                }
                // vestirlo → prendas + reacción feliz
                LaunchedEffect(node, cfg.clothes, cfg.acc) {
                    node?.let { n ->
                        Avatar3D.applyWear(n, cfg.base, cfg.clothes, cfg.acc)
                        Avatar3D.playOnce(n, "react")
                    }
                }
                // tocarlo → saludo
                LaunchedEffect(waveTick) {
                    if (waveTick > 0) node?.let { Avatar3D.playOnce(it, "wave") }
                }
            }
        }
    }
}
