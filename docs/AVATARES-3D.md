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
