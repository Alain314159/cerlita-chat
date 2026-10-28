# Avatares 3D de verdad — diagnóstico y hoja de ruta (D99)

**Contexto.** Usuario (28-oct-2026, sobre v0.11.0): «Mira esa imagen es para que veas que
la ropa está horrible. Cuando giran se ve que son 2D, no 3D. Quiero avatares como los de
Facebook. Dime qué necesitamos para conseguir. No crees código — vamos a analizar bien
este problema primero.»

Este documento es ese análisis. No cambia ni una línea de la app.

---

## 1. Diagnóstico: por qué se ve como se ve

### 1.1 Lo que hay hoy (arquitectura v0.11.0)

| Pieza | Qué es realmente | Dónde se genera |
|---|---|---|
| Base (cuerpo) | **FOTO** de un muñeco 3D: imagen generada con IA sobre fondo verde, con chroma-key + despill + colorways | `tools/avatares/gen_avatar_3d.mjs` → `build_avatar_3d.py` |
| Ropa / calzado / accesorios | **Imágenes planas** (WebP 384px) pintadas ENCIMA de la foto, con sombreado pintado a mano (degradado de luz, rim, especular, sombra de contacto) | `make_layers_3d.py` (D95/D97) |
| Giro (D98) | `graphicsLayer { rotationY }` de Compose sobre la composición entera: **una foto girando**, con `cameraDistance` para un leve truco de perspectiva | `ProfileScreen.kt` |
| Saludo / brinco / squash | Animaciones de la IMAGEN (escala/rebote), no del personaje | D98 |

Es decir: **no existe ni un vértice de geometría 3D en todo el sistema**. Lo que hay es
una fotografía de algo 3D, con pegatinas de ropa encima, girando como una tarjeta.

### 1.2 Por qué la ropa se ve «horrible»

El veredicto VLM sobre la captura del usuario lo resume: la camiseta es «un plano
rectangular colocado delante del torso, sin seguir su geometría», con «cero física de
tela», sombras que no reaccionan a las del cuerpo, y mangas que se comen los brazos.
En los propios chips del selector ya se ve la prenda «pegada» al muñeco.

Lo importante: **no es un problema de calidad de pintura, es estructural**. Entre D95 y
D97 ya gastamos dos rondas de motor de sombreado propio + QA visual, y el techo sigue
ahí. Pintar volumen sobre una foto plana tiene un límite físico: la prenda no puede
envolver un cuerpo que no existe, no puede seguir al brazo cuando el brazo no se mueve,
y sus sombras jamás casarán con las de la base en todos los ángulos.

### 1.3 Por qué al girar se ve 2D

`rotationY` sobre una imagen plana = girar un cartel de cartón. La silueta NUNCA cambia
(no se ve jamás el perfil del koala), no hay profundidad entre nariz/orejas/cuerpo, y a
partir de ~30° la perspectiva delata el truco. La ropa gira solidaria de la foto, así
que el efecto «sticker en puerta giratoria» se acentúa con prenda puesta. Es exactamente
lo que el usuario percibe: «cuando giran se ve que son 2D».

### 1.4 El techo arquitectural

Sin geometría 3D real es imposible conseguir, por mucho que se pinte:

- **Rotación de verdad** — ver el personaje desde cualquier ángulo con silueta real.
- **Ropa con volumen** — que la prenda envuelva el cuerpo, cuelgue, y proyecte reciba
  la misma luz que el cuerpo.
- **Parallax** — que la nariz pase por delante de las orejas al girar.
- **Animación esquelética** — saludar moviendo el brazo, respirar hombros, caminar.

Además la vía actual tiene **explosión combinatoria**: cada prenda × ángulo × pose habría
que dibujarla a mano. La mejora de D95→D97 ya mostró rendimientos decrecientes: cada
ronda de «pintura» sube menos que la anterior. Cuando eso pasa, no es un bug: es el
techo. La única forma de subir el techo es cambiar de arquitectura.

---

## 2. Qué significa «como los de Facebook» — los 3 niveles que existen

1. **Nivel 1 — Ilustración 2D vectorial** (Facebook Avatars clásicos, Bitmoji de chat):
   ni giran ni tienen volumen. Su «look pulido» es un sistema de capas vectoriales con
   muchísimo arte manual detrás. Si el objetivo fuera SOLO esto, bastaría con contratar
   arte 2D — pero no cumple lo que el usuario pide (girar, volumen, reaccionar).
2. **Nivel 2 — Pseudo-3D (2.5D)**: sprites pre-renderizados por ángulo (8–16 fotos por
   combinación base×ropa) o parallax por capas. Es lo que tenemos, un poco mejor.
   Sigue delatándose al girar y multiplica el arte por el nº de ángulos.
3. **Nivel 3 — 3D real** (Memoji de Apple, Meta Avatars 3D desde 2022, Zepeto,
   Ready Player Me): **malla + esqueleto + prendas como mallas vestidas al mismo
   esqueleto + motor 3D en tiempo real**. Esto es lo que el usuario describe: girar de
   verdad, ropa que se ve prenda, saludar con el brazo.

La petición («girar 3D + ropa bonita + que salude») es **Nivel 3**. No hay atajo: ningún
nivel de pintura 2D llega ahí. Meta/Apple emplean equipos enteros de artistas 3D, pero
su alcance también es otro: nosotros necesitamos 2 personajes chibi + ~15 prendas, un
problema varias órdenes de magnitud más pequeño y perfectamente factible.

---

## 3. Lo que necesitamos — las 4 piezas

1. **Geometría**: modelos 3D de Koalita y Cerdita (estilo chibi/kawaii: formas simples
   — esferas, cápsulas, orejas — con subdivisiones suaves) en formato **glTF/GLB**, el
   estándar 3D de facto en móvil/web, abierto y gratuito.
2. **Esqueleto (rig) + animaciones**: ~15–25 huesos (raíz, cuerpo, cabeza, orejas ×2,
   brazos ×2, piernas ×2…). Los clips de animación viajan DENTRO del propio GLB:
   `idle-respirar`, `saludo`, `brinco-feliz`, `reacción-al-vestir`, `giro`.
3. **Ropa como mallas**: cada prenda (camiseta, vestido, traje, zapatos, gafas, lazo…)
   es una malla propia «vestida» sobre el mismo esqueleto (skin weights compartidos).
   Al girar o animar, la prenda se mueve CON el cuerpo, con volumen y recibiendo la
   misma luz. Los colorways pasan a ser materiales → gratis, sin regenerar arte.
4. **Motor 3D en la app**: **Filament** (motor de render PBR de Google, código abierto)
   a través de **SceneView** (SDK libre que lo hace declarativo en Compose: una escena
   es un composable, un modelo es un nodo). Arrastrar = orbitar la cámara alrededor del
   muñeco; tocar = disparar el clip de saludo; cambiar prenda = intercambiar nodos.

Todo lo anterior es **gratis y de código abierto**; no hay licencias, suscripciones ni
servicios de terceros.

---

## 4. De dónde salen los modelos — las 3 vías

| Vía | Qué es | Pros | Contras |
|---|---|---|---|
| **A. Blender programático (bpy)** | Scripts Python que construyen el personaje (primitivas + deformadores), lo riguean y exportan GLB, headless y reproducible | 100 % control de topología/UV/rig; reproducible (misma filosofía de `tools/avatares`: pipelines medibles); gratis; cada prenda nueva es barata una vez el sistema anda | El esfuerzo mayor está en la primera escultura; el look depende de nuestro ojo (mitigado con QA visual en bucle, regla D83) |
| **B. IA generativa 3D** (Meshy, Tripo, Rodin) | Texto/foto → malla | Rápido para prototipos | Topología sucia (retopología manual), rig pobre o ausente, el estilo chibi kawaii les cuesta, no reproducible, licencias de uso a revisar caso por caso |
| **C. Bases libres rigged** (Quaternius, Kenney, Sketchfab CC0) | Personajes animales low-poly ya rigueados | Punto de partida con rig probado | No son NUESTROS personajes; adaptar el look a Cerdita/Koalita es trabajo de escultura igualmente; licencias heterogéneas |

**Recomendación: vía A como columna vertebral**, usando C como referencia de
proporciones y estructura de rig (skeleton humanoide simple estilo Mixamo), y B
restringida a generar imágenes de referencia de texturas, nunca mallas finales. Es la
misma apuesta que ya ganó en 2D (D83/D92): pipeline reproducible + medidas + QA visual,
en vez de arte a mano no repetible.

---

## 5. Integración sin romper nada de lo que hay

- **Editor de perfil**: la vista previa pasa a ser una escena SceneView — girar con el
  dedo (órbita real), tocar = saludo, vestir = swap de malla con transición. Las
  pestañas Cerdita|Koala (D96) y el catálogo se quedan.
- **Chat, lista de chats, llamadas, notificaciones**: **CERO render 3D en filas**
  (caro e innecesario). Se rasteriza el muñeco 3D a una imagen snapshot UNA vez por
  cambio de perfil y se cachea — exactamente el sistema de snapshots/avatar que ya
  existe. Las listas siguen tan baratas como hoy.
- **Wire, backend, E2EE: NO cambian.** Los ids de base/ropa/accesorio se mantienen; el
  catálogo pasa de «recurso WebP» a «nodo GLB». Los perfiles ya guardados siguen
  funcionando; un cliente viejo que no conoce un id lo ignora (degradación limpia, D61/D83).
- **Peso de APK**: SceneView+Filament añade ~4–8 MB según ABIs (a medir en F0; si molesta,
  arm64-only como ya hicimos con WebRTC en D75). Los modelos comprimen muy bien
  (Draco/meshopt): ~200–500 KB por personaje, 50–150 KB por prenda. Catálogo completo
  estimado < 3 MB de assets.
- **Rendimiento**: un muñeco chibi de 5–15k triángulos es trivial para cualquier GPU
  móvil de la última década; menos trabajo que scrollar la lista de chats. minSdk 24 sin
  problema (Filament pide GLES 3.0, universal desde 2013).
- **Plan B técnico**: si SceneView diera guerra en algún Xiaomi concreto, Filament se
  puede incrustar directamente — el plan no depende de la capa Compose.

---

## 6. Plan por fases (con puerta de parada)

- **F0 — Prueba de concepto (1 sesión, decisión de seguir o parar)**
  Blender headless en el entorno de build → Koalita chibi programático + rig básico +
  2 clips (idle-respirar + saludo) + 1 camiseta como malla + export GLB + pantalla de
  prueba con SceneView: girar con el dedo, tocar = saludo, poner/quitar camiseta.
  **Si el look convence frente a la referencia actual → seguimos. Si no → paramos
  habiendo gastado exactamente una sesión.**
- **F1 — Los dos personajes**: Cerdita, colorways como materiales (adiós a regenerar
  arte por color), rig compartido, migración del catálogo inicial de prendas
  (camisetas, vestido, traje, calzado, gafas, lazo…).
- **F2 — Editor 3D completo**: las pestañas actuales sobre la escena viva, miniaturas
  del selector renderizadas como imagen (no 12 escenas simultáneas), snapshots para
  chat/notificaciones, pareja abrazada (`av_pair` 3D).
- **F3 — Vida**: reacción al vestirlo, giro completo al cambiar de personaje, poses
  expresivas.

---

## 7. Riesgos y mitigaciones

- **R1 — Look «lego» de la escultura programática.** El estilo chibi kawaii es el MÁS
  amable para modelar por código (primitivas + subsurf + colores planos), y la regla
  D83/D77 ya nos da el método: medir la referencia, componer, QA visual en bucle. F0
  existe precisamente para validar esto antes de invertir más.
- **R2 — Peso del motor.** Se mide en F0. Si supera lo razonable: arm64-only, o escena
  3D solo en el editor y snapshots en el resto (que ya es el diseño propuesto).
- **R3 — Blender en el sandbox/CI.** Blender headless es un binario gratuito (~350 MB,
  sin GUI) que corre con `blender -b -P script.py`; GitHub Actions ya compila la app
  (D90) y puede regenerar los assets igual que hoy regenera el APK.
- **R4 — Scope creep.** QUÉDA FUERA: física de tela, escaneo fotorrealista (Codec
  Avatars de Meta), blendshapes faciales ricos. Memoji/Meta tampoco simulan tela: las
  prendas son mallas rígidas ligadas al esqueleto y ya se ven como ropa de verdad. La
  animación facial puede esperar a una F4 si el usuario la pide.
- **R5 — Miniaturas del selector.** Se renderizan como imágenes en el pipeline (una vez
  por prenda×familia), no como escenas 3D vivas: una sola escena interactiva por pantalla.

---

## 8. Resumen ejecutivo

- **Problema**: la ropa es pintura sobre una fotografía y el giro es una fotografía
  girando. Es un techo ESTRUCTURAL del sistema actual (foto + pegatinas), no un fallo
  de ejecución — dos rondas de mejora de pintura (D95–D97) ya chocaron contra él.
- **Solución**: geometría 3D real (GLB con esqueleto y animaciones) + prendas como
  mallas al mismo rig + SceneView/Filament en la app. Todo gratis, todo de código
  abierto, todo local (sin servicios de terceros, E2EE intacta).
- **Coste**: 1 sesión de prueba de concepto con puerta de parada (F0) + 2–3 sesiones
  para el sistema completo. Nada de backend, wire ni criptografía cambia.
- **Lo que el usuario ganaría**: girar de verdad con el dedo (cualquier ángulo), ropa
  que se ve prenda (volumen, mangas, misma luz), saludo con el brazo, reacciones al
  vestirlo, y colorways infinitos gratis.
