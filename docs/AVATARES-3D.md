# Avatares 3D (D101) — pipeline y arquitectura

El sistema D83/D92/D95-D98 (foto de render IA + capas pintadas encima) tenía un
techo ESTRUCTURAL: la ropa era pegatina sobre una fotografía y el giro era una
imagen rotando (D99 lo documenta). Desde v0.12.0 el editor usa **geometría real**.

## Piezas
- **Generador**: `tools/avatares3d/build_avatars.py` (Blender 4.2 headless).
  `blender -b -P build_avatars.py` regenera los 7 GLB + hoja de QA (renders PNG).
  `--only=pig|koala`, `--no-export`, `--no-qa`, `--wave=Y` (eje del saludo).
- **Validador**: `validate_glbs.py` — 16 nodos-prenda + 3 animaciones + 1 skin por GLB.
- **App**: `ui/Avatar3D.kt` (mapeos catálogo⇄GLB/nodos, animaciones, snapshot)
  y `ui/Avatar3DPreview.kt` (composable: órbita, saludo, react, fallback 2D).

## Reglas del sistema
1. **Los ids del catálogo son el contrato** — un GLB nuevo no rompe perfiles
   guardados: prendas de otra familia no se dibujan, ids retirados degradan.
2. **Un GLB por colorway**, materiales horneados. Añadir colorway = par de
   líneas en `FAMS` + regenerar.
3. **Cada prenda es un Empty** con el nombre EXACTO del id (`tee_pink`...) que
   agrupa sus mallas — la app oculta/muestra el Empty.
4. **Las mangas van a los huesos de los brazos**; sombreros/gafas a la cabeza;
   la ropa de torso a spine. Nada flota: todo sigue al esqueleto.
5. **QA visual obligatorio antes de subir** (regla D83): renders en
   `tools/avatares3d/qa/<familia>/` — mirar frente/3-4/perfil/espalda/pose.
6. **Animaciones**: idle (bucle), wave (2.6s), react (1.1s). Duraciones en
   `Avatar3D.CLIP_MS` — si cambias un clip en Blender, actualiza ese mapa.

## Bugs de Blender 4.x que ya pagamos (no repetir)
- `transform_apply()` con defaults aplica TAMBIÉN location: las piezas rotadas
  post-creación vuelan alrededor del origen del mundo. Hornear SIEMPRE en el
  origen y posicionar DESPUÉS.
- `bmesh.ops.spin` gira alrededor del eje dado — para revolución de un perfil
  (r,z) el eje es **Z**, no Y (con Y salen anillos horizontales).
- El exportador glTF se salta los tracks NLA muteados; y `reset_pose` jamás
  debe tocar `animation_data` (borra los tracks stashheados).
- `primitive_cylinder_add` en 4.x solo acepta `radius` (sin radius1/radius2).

## Qué NO es 3D (a propósito)
El chat, las listas y las notificaciones: usan la FOTO (snapshot PixelCopy del
editor al guardar, que viaja cifrada como foto de perfil) o el render 2D de
siempre. Cero escenas 3D en filas de lista (caro e innecesario a 40dp).

## Rasters D102: el look 3D en perfil, chats y notificaciones (v0.13.0)
`tools/avatares3d/render_rasters.py` (dos fases: render en Blender, sprites en
Python del sistema — el Python de Blender no trae PIL) reemplaza TODO el arte
2D viejo (bases IA + stickers vectoriales de v0.9-0.11) por renders del modelo
3D real, con los MISMOS nombres de archivo (`av_*.webp` en drawable-nodpi) así
que la app no cambia ni una línea:

- **Bases** (7, una por colorway): frente 3/4 (az 14°, elev 7°), estudio de 3
  luces (clave cálida frente-izq, relleno frente-der, contra detrás para
  recortar la silueta) + mundo pastel suave, 384×384 con alfa, Cycles 72
  samples + denoise, semilla fija.
- **Sprites de prendas** (ropa + accesorios por familia): DIFF entre el render
  con prenda y el render base — alpha proporcional a |Δcolor| (umbral 10-32),
  mediana 3×3 contra motas. La sombra que la prenda pinta en el cuerpo y el
  sombreado real del tejido VIAJAN dentro del sprite: al componer capas el
  resultado es el render 3D auténtico, no una pegatina. Alineación exacta por
  construcción (misma cámara/pose/luz); los sprites de una familia se reutilizan
  en todos sus colorways (la geometría del cuerpo no cambia entre colorways).
- **Verificación**: determinismo del render (Δ=0 exigido), composite(base+
  sprites) vs render directo de la misma combinación (<2% px distintos),
  previews sobre tablero en qa/rasters/ y facing GLB por script (ojos/nariz a
  +Z tras `turn_around()`).

## Orientación canónica (D102, la lección del koala de espaldas)
El cuerpo se construye mirando a +Y en Blender, que es la ESPALDA de la
convención (frente = -Y; glTF exporta con +Z = frente). El bug del "azimut
invertido" del QA de D101 compensó la cámara y escondió que el modelo estaba
girado: la cámara por defecto de SceneView (en +Z) le veía la espalda.
Arreglo: `turn_around()` en build_family gira TODO (rig + mallas + empties,
keep-transform con un Empty padre) 180° en Z antes de exportar; `qa_shot`
volvió a az 0 = frente (lado -Y). Regla: toda pieza nueva del pipeline mira a
-Y en Blender, sin excepción.

## v0.16.0 (D112): la escultura v2 — qué cambió en el pipeline

Regenerar TODO tras el overhaul: `blender -b -P build_avatars.py` (GLB+QA) y
`render_rasters.py` (bases+sprites). El resto de las reglas de arriba SIGUE
igual (ids de catálogo como contrato, un GLB por colorway, prendas=Empties,
QA antes de subir). Cambios concretos:

- **Constantes nuevas**: `TEE_FIT=1.16` (holgura, era 1.135), `SOLE_D`
  (suelas oscuras). `A` actualizado (ojos 0.047, brazos cortos, pies mayores).
- **Materiales**: body 0.34 / tinta 0.32 / `hi` blanco 0.05 (brillos). El
  specular JAMÁS por debajo de 0.25 en rasgos oscuros: los lava (lección
  medida con PIL: rasgos a lum 125-175 = cara «neutra»).
- **Ropa**: `solidify()` 0.012-0.014 ANTES de posicionar; cuellos/puños con
  `darken(color)`; el gorro fiesta se ancla con `ellipsoid_surface` + banda.
- **La boca U**: `smile_chain()` (cilindros+esferas). NO usar
  `bpy.ops.object.convert` (curvas→malla) en headless: poll() falla.
- **QA**: `setup_render` usa AHORA el estudio 3-luces + MUNDO PASTEL de
  render_rasters.py (sin mundo, GI=0 → renders a 40/255). Dos planos nuevos:
  09_face_closeup y 10_wave_front.
- **Puerta de calidad**: rubro VLM (glm-5v) por familia hasta «APROBADO»,
  más legibilidad de rasters a 384 px. 7+2 iteraciones pagaron: boca plana →
  ω «gusanito» → U ancha 0.032; gorro flotando → banda de contacto.
