package cu.alain.chat.ui

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cu.alain.chat.profile.AvatarConfig
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * (D101) El editor de avatar con 3D DE VERDAD:
 *  - El modelo es geometría real (GLB con esqueleto) → arrastrar ORBITA la
 *    cámara alrededor del muñeco (SceneView lo trae de fábrica): cualquier
 *    ángulo, silueta real, sin "cartel de cartón".
 *  - La ropa son MALLAS al mismo esqueleto → visten con volumen y giran con el
 *    cuerpo (nada de pegatinas).
 *  - TOCARLO = saludo (animación esquelética del brazo, no un rebote de imagen).
 *  - Vestirlo → reacción squash feliz (sabe que lo vistes, D98 en 3D).
 *  - Respira en reposo (idle en bucle).
 *  - Fallback limpio: mientras carga (o si el dispositivo no puede) se ve el
 *    render 2D de siempre — nunca un hueco negro.
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

    Box(modifier) {
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
                    scaleToUnits = 1.55f,
                    apply = {
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
