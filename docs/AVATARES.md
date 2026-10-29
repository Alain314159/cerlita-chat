# Cerlita Chat — Sistema de avatares: del placeholder al arte real

**Decisión D83** · 28 de octubre de 2026 · pipeline en `tools/avatares/`

> **Nota D113 (v0.17.0)**: este documento describe el sistema D83 (bases raster +
> capas vectoriales), que evolucionó: las capas son hoy sprites WebP y el 3D
> (D92→D112) se ELIMINÓ por completo. El sistema vigente es **2D por capas o
> foto** — ver D113 en `DECISIONES.md`. Este doc se conserva como referencia del
> método (Style Bible, anclas, medir-antes-de-posicionar) para futuras tandas de
> arte 2D.

---

## 0. Qué se logró

Los avatares de la app eran **placeholders vectoriales toscos** (`av_pig.xml`: un círculo rosa con ojos de punto, sin contorno, sin blush, sin hocico — 58 líneas de paths primitivos). La ilustración original de la pareja (Cerdita + Koalita) es el ADN visual de la app: ya es el icono del launcher (D73).

Este documento explica **cómo convertir esa ilustración en un sistema de avatares de calidad profesional, 100 % gratis y reproducible**, y documenta lo que ya está implementado.

**Estado actual:**

| Pieza | Antes | Ahora |
|--------|-------|-------|
| Bases | 2 vectores placeholder | **8 raster WebP** (2 personajes × 4 colorways), arte real generado con la misma estilo |
| Capas | 10 vectores placeholder | **22 VectorDrawable** en artboard 1024, mismo grosor de trazo y paleta |
| Decoración | no existía | **slot `decor`** nuevo (la burbuja con corazones, la firma de la ilustración) |
| Selector | chips de texto | **vista previa real** de cada opción compuesta sobre el personaje |
| Formato de wire | `{"v":1,"base","clothes","acc"}` | igual + `"decor"` opcional → **compatible hacia atrás** |
| Peso | ~14 KB | **+496 KB** (408 raster + 88 vector) sobre una APK de 2,8 MB (+18 %) |

---

## 1. Por qué el placeholder no escalaba

El sistema original era correcto *estructuralmente* (base + ropa + accesorios por slot, ids desconocidos se ignoran, `MAX_JSON = 600` con presupuesto de ciphertext de 8 KB). Lo que no escalaba era **el arte**:

1. **Vectorizar a mano no es automatizable.** Redibujar 10 capas con el mismo grosor de trazo y la misma paleta, dos veces, es trabajo de ilustrador. No se sostiene.
2. **Los modelos de imagen no producen vector.** Producen raster beaux. Intentar convertirlos a paths produce basura.
3. **El placeholder no fija una identidad.** Con un arte genérico, "Cerlita" y "Koalita" son solo dos colores.

El punto de inflexión es: **la ilustración original SÍ es la fuente de verdad**. Si se mide (paleta, grosor de trazo, geometría) y se usa como referencia de estilo, todo lo demás se deriva de ella.

---

## 2. La decisión: raster + vector híbrido

Tres opciones, y por qué gana la tercera:

| Opción | Bases | Capas | Veredicto |
|--------|-------|-------|-----------|
| **A. Todo vector** | ilustración a mano | ilustración a mano | ❌ No escalable, no automatizable, coste humano prohibitivo |
| **B. Todo raster** | IA ✓ | IA ⚠️ | ⚠️ Los accesorios por IA no se alinean con la base ni se pueden recolorear; 15 archivos de imagen para 3 colores de camiseta |
| **C. Híbrido** ✅ | IA → WebP | Vector generado | ✅ La base es donde vive el arte (la IA es buena ahí); las capas son formas simples que el vector hace **perfecto, exacto y recoloreable** |

**La consecuencia clave de la opción C:** las capas comparten un **artboard de 1024 × 1024** con la base. Todas se dibujan con `ContentScale.Fit` en el mismo cuadrado → **componen exacto a cualquier tamaño**, sin transformaciones ni recortes.

Y un hallazgo que ahorra código: **el renderizador actual ya soporta raster**. `painterResource(R.drawable.av_pig)` funciona igual con un `.webp` que con un `.xml`, y `ContextCompat.getDrawable()` en el notificador también. **Cambiar las bases no requirió ni una línea de código de render** — solo sustituir el recurso.

---

## 3. El Style Bible (medido, no estimado)

Todo sale de `tools/avatares/reference/cerlita-koalita-ref.jpg` (1080×1080) con k-means de 14 clusters + muestreo por coordenadas sobre una rejilla.

### Paleta

| Token | Hex | Elemento |
|-------|-----|----------|
| `ink` | `#503433` | contorno / tinta — **nunca negro** |
| `pig_body` | `#FFC1CC` | cuerpo de Cerdita |
| `pig_ear` | `#FB96A5` | interior de oreja |
| `pig_snout` | `#FFA2AD` | hocico |
| `blush` | `#F999A5` | mejillas |
| `koala_body` | `#BCB2BA` | cuerpo de Koalita |
| `koala_ear` | `#F9E2DC` | interior de oreja (crema) |
| `heart` | `#F0758D` | corazones |
| `white` | `#FEE8EB` | corazón claro / burbuja crema |
| `violet` | `#BC8FB5` | burbuja violeta |

### Geometría

| Medida | Valor | Origen |
|--------|-------|--------|
| Grosor del trazo | **11 px sobre 1080 = 1,0 % del ancho** | 402 muestras de runs de píxeles oscuros |
| Personajes | 82 % del ancho, 71 % del alto | bbox de la tinta |
| Ojos | arcos cerrados (⌒ invertida), ~45 px de ancho, sin pupilas | rejilla |
| Nariz de Koalita | óvalo vertical, marrón `ink` | rejilla |
| Contraste tinta/fondo | **9,4 : 1** | WCAG |

### ⚠️ El hallazgo que cambió el pipeline

En la referencia, **blush y oreja de Cerdita están a ΔE 2,4** (CIE76 perceptual) — es decir, **son perceptualmente el mismo color**. También oreja↔hocico a ΔE 5,0 y crema↔blanco a ΔE 5,2.

Esto significa que la paleta de la referencia **no es un sistema de tokens separable**, sino una familia de tonos donde cada pieza se distingue por **posición y contorno**, no por tono.

Consecuencia práctica: un "snap al color más cercano" genérico **colapsa el blush dentro de la oreja** y el arte pierde un elemento. La solución adoptada no es tokenizar la referencia, sino **forzar cada región a su token por unmix con el fondo** (ver §5.2), no por proximidad.

Y para **theming** (el futuro "Cerdita azul", "Koalita cacao") sí hace falta tokenizar: ahí se sustituyen tokens y el ΔE entre ellos se empuja a ≥ 15 para que las piezas sigan distinguiéndose.

---

## 4. Arquitectura de capas

### Orden de dibujo

```
1. decor      ── DETRÁS   (burbuja con corazones: asoma por encima)
2. clothes    ── DETRÁS   (camiseta/hombros: el cuerpo tapa el cuello)
3. base       ── el personaje
4. acc        ── ENCIMA   (moño, flor, gorro, gafas, corazón)
```

El orden **detrás/encima** es lo que hace que la burbuja asome por detrás de la cabeza exactamente como en la ilustración original, y que la camiseta se lea como hombros en vez de un parche pegado encima.

### El "rig" de anclas

Cerdita y Koalita **no comparten geometría** (Koalita es más ancha y tiene oreja esponjosa), así que cada familia tiene su **tabla de anclas**, medida sobre el artboard con rejilla de 64 px:

| Ancla | Cerdita | Koalita | Uso |
|-------|---------|---------|-----|
| `cx` | 512 | 512 | centro |
| `head_top` | 72 | 100 | borde superior de la cabeza |
| `chin_y` | 845 | 855 | bajo el mentón |
| `head_rx` | 415 | 460 | medio ancho de la cabeza |
| `ear_l` / `ear_r` | (172,178) / (836,178) | (215,245) / (810,245) | orejas |
| `eye_l` / `eye_r` | (305,468) / (715,468) | (335,508) / (680,508) | ojos |
| `chest_y` | 905 | 930 | pecho |

Las capas se **generan paramétricas** contra esas anclas (`make_layers.py`), no se dibujan a ojo. Añadir un personaje nuevo = medir sus anclas + regenerar.

### Los slots

| Slot | Elementos | Se dibuja |
|------|-----------|-----------|
| `base` | 8 raster (2 × 4 colorways) | centro |
| `clothes` | `tee_pink`, `tee_violet`, `tee_teal`, `dots`, `stripes` | detrás |
| `acc.head` | `bow`, `flower`, `party` | encima |
| `acc.eyes` | `glasses` | encima |
| `acc.chest` | `heart` | encima |
| `decor` | `bubbles` | detrás |

---

## 5. El pipeline de producción

### 5.1 Visión general

```
referencia 1080×1080
      │
      ├─► (1) IA: image-to-image con la referencia como guía de estilo
      │       prompt con la paleta y el grosor de trazo explícitos
      │       fondo cromático plano (#00FF00) para poder recortarlo
      │
      ├─► (2) Unmatte:  pixel = α·relleno + (1−α)·fondo
      │       · se busca el token de paleta c tal que el píxel cae en [fondo, c]
      │       · de ahí salen α y el color CANÓNICO → el arte queda con los
      │         hex exactos de la referencia, sin depender de lo que hizo el modelo
      │       · snap de alfa: {0, 255} fuera del borde, rampa solo en el borde
      │
      ├─► (3) Recorte + artboard 1024×1024 con margen de seguridad
      │
      ├─► (4) Colorways: sustitución de tokens → 4 variantes por personaje
      │
      ├─► (5) Capas: SVG paramétrico contra el rig de anclas
      │       → render de control con ImageMagick → QA visual
      │       → conversión a Android VectorDrawable (pathData 1:1)
      │
      └─► (6) Export: WebP lossless 384 px (bases) + vectoriales (capas)
```

### 5.2 El unmatte, en detalle

Es el paso que convierte "una imagen bonita del modelo" en "arte con la paleta exacta del proyecto".

Para cada píxel `p` y cada token de paleta `c`:

```
t = clamp( ((p − fondo) · (c − fondo)) / |c − fondo|² , 0, 1 )
reconstrucción = fondo + t·(c − fondo)
error = |p − reconstrucción|²
```

Se gana el `c` con menor error; `t` es el alfa y `c` el color canónico. Los píxeles con error alto (ruido JPEG) conservan su color y alfa 1.

Esto resuelve de golpe dos problemas: **elimina el fondo** y **fuerza la paleta**, con antialiasing correcto en los bordes (un píxel a medio camino entre el verde y el rosa sale como rosa con alfa 0,5).

### 5.3 Recorte de la ilustración original (flood-fill)

Para la **pareja** (que se usa tal cual, sin IA) el fondo es `#FCC2D0` y el cuerpo de Cerdita es `#FFC1CC` — casi idénticos. Un key por color global **se comería el cerdo**.

La solución es **flood-fill desde los bordes**: se marca el fondo y se propaga solo hacia los píxeles conectados al exterior. El contorno oscuro de los personajes actúa de barrera, así que el cuerpo rosa de Cerdita sobrevive intacto. Resultado: 75,8 % de fondo eliminado, la ilustración original conservada píxel a píxel.

### 5.4 Comandos

```bash
cd tools/avatares

# 1) generar (el JSON lleva el prompt y la referencia como input_urls)
mcode-tools connector call connector__matrix__generate_image --args-file gen-args-2.json

# 2) procesar: unmatte + artboard + colorways + export
python3 build_avatar.py <gen.png> ../out/ \
    --name base-cerdita --colorways "rosa,melocoton,lavanda,menta" \
    --sizes 1024,512,384 --manifest ../out/manifest.json

# 3) capas: SVG + VectorDrawable contra el rig de anclas
python3 make_layers.py --base ../out/base-cerdita-rosa-1024.png \
    --anchor cerdita --prefix cerdita --out ../out

# 4) QA visual: componer y MIRAR (no es opcional)
for f in ../out/layers/*.svg; do convert -background none "$f" "${f%.svg}.png"; done
```

### 5.5 El prompt

La parte más delicate del pipeline. Reglas que funcionan:

- **image-to-image con la referencia como `input_urls`** — el modelo hereda el estilo; el texto solo lo guía.
- **Colores con hex explícitos** en el prompt (`#FFC1CC`, `#503433`). Sin hex, el modelo inventa un rosa distinto en cada ejecución.
- **"thick uniform dark chocolate-brown outlines about 1 percent of the image width"** — sin la medida, el modelo hace trazos de 3 px que rompen la escala.
- **"closed happy crescent eyes drawn as simple upward arcs"** — "cute eyes" produce ojos con pupila y brillos, que es un estilo distinto.
- **Fondo cromático plano `#00FF00`** con "completely uniform, no shadows, no gradient, no ground" — es lo que permite el unmatte.
- **Negativos explícitos**: "no text, no speech bubble, no other characters, no accessories" — si no, mete burbujas y se cuela con el par.

---

## 6. Control de calidad

El paso que más valor aporta y el que más se salta todo el mundo: **componer y mirar**.

```
base + capa → PNG → VER
```

En la primera iteración los 6 accesorios estavam **mal colocados**: el moño flotaba entre las orejas, la flor se quedaba pegada al marco, el gorro salía volando fuera del lienzo, las gafas tapaban un solo ojo y el corazón quedaba a medio cortar. Todo ello porque las anclas se habían deducido de heurísticas de color en lugar de medirse.

La corrección fue: **rejilla sobre el artboard + lectura visual de las coordenadas** → tabla de anclas fija → el generador paramétrico coloca cada pieza en su sitio.

Reglas de QA:
1. **Siempre componer sobre la base y mirar.** Un SVG que "se ve bien" solo puede estar mal alineado.
2. **QA en las dos familias.** Si el accesorio funciona en Cerdita y no en Koalita, el rig está mal.
3. **Comprobar el recorte circular.** La notificación hace `circleCrop`: lo que asoma del círculo se ve, lo que no, no.

---

## 7. Compatibilidad del contrato de wire

**El arte cambia; el contrato no.** Esto es lo que permite soltar la v0.8 sin romper los perfiles ya guardados.

```json
{"v":1,"base":"pig","clothes":"tee_pink","acc":{"head":"bow"}}
```

| Cambio | Efecto en un perfil existente |
|--------|------------------------------|
| Base "pig" → WebP nuevo | ✅ se ve el arte real |
| `clothes`/`acc` con ids iguales | ✅ se ven las capas nuevas |
| `decor` ausente | ✅ sin decoración (degradación limpia) |
| Base "pig_melocoton" (nuevo) | solo en clientes nuevos |

`ProfileMsg.MAX_JSON = 600` sigue de sobra: el JSON completo ocupa ~130 caracteres.

El id `decor` es **nuevo** y opcional. Un cliente viejo que reciba `{"decor":"bubbles"}` lo ignora (no lo conoce) y renderiza todo lo demás — es exactamente el comportamiento degrade-limpio que D61 ya definía.

---

## 8. Coste

| Recurso | Coste |
|---------|-------|
| Generación IA | ya incluida en la suscripción (3 imágenes por tanda, 2 K) |
| Procesado | Python + PIL/numpy, local, **0 €** |
| Vectorización | no hace falta: las capas se **escriben** como paths |
| Almacenamiento | 496 KB en el APK |
| Tiempo | ~4 h el primer pase (medir, generar, iterar QA); ~40 min por personaje nuevo |

**Y lo más importante: el pipeline es reproducible.** `gen-args-2.json` + `build_avatar.py` + `make_layers.py` regeneran los avatares de forma determinista desde la referencia.

---

## 9. Qué queda por hacer (roadmap de arte)

| # | Idea | Coste | Nota |
|---|------|-------|------|
| 1 | **Expresiones** (feliz / dormida / sorpresa / enamorada) | 4 gen + 4 KAT | El salto de calidad más grande: un avatar que reacciona. Requiere 4 bases por personaje |
| 2 | **Avatar de pareja** (los dos juntos) | 1 gen | Ya está el arte de la ilustración original recortada (`av_pair.webp`, 86 KB) |
| 3 | **Avatares creados por el usuario** | medio | Con `kind`/`clothes`/`acc` ya extensible; falta un editor |
| 4 | **Tema oscuro del avatar** | bajo | Darken las bases con un filtro por tonalidad |
| 5 | **Regenerar el icono del launcher** desde `av_pair.webp` | bajo | El icono actual (D73) ya usa la imagen original; con el recorte se puede mejorar el fondo |
| 6 | **Más colorways** | trivial | Solo sustituciones de token en `COLORWAYS` |
| 7 | **Stickers** | medio | Un set propio de Cerlita/Koalita |
| 8 | **Animación** (respirar, parpadear) | medio | Lottie; solo en el perfil, no en la lista |

### Reglas para ampliar el sistema

- **Nunca editar a mano un PNG de base.** Se regenera.
- **Todo accesorio nuevo** entra en `make_layers.py` (paramétrico) y se exporta a las dos familias.
- **Todo base nuevo** necesita: su tabla de anclas + su set de capas.
- **Medir antes de posicionar.** Rejilla de 64 px, leer, escribir la tabla.

---

## 10. Ficheros

```
tools/avatares/
  reference/cerlita-koalita-ref.jpg   la ilustración original (fuente de verdad)
  build_avatar.py                      unmatte + artboard + colorways + export
  make_layers.py                       rig de anclas + capas SVG/VectorDrawable
  gen-args-1.json / gen-args-2.json    prompts de generación (versionados)

android/app/src/main/res/
  drawable-nodpi/  av_pig.webp … av_koala_cielo.webp, av_pair.webp   (8 bases + pareja)
  drawable/        av_a_*.xml, av_c_*.xml, av_decor_*.xml             (22 capas)

android/app/src/main/java/cu/alain/chat/
  ui/AvatarView.kt          catálogo + orden de capas
  ui/screens/ProfileScreen.kt   selector con vista previa real
  profile/Profile.kt        AvatarConfig con el slot `decor`
  notify/Notifier.kt        rasterizado del avatar por capas
```

---

*Implementado el 28-oct-2026. El pipeline es reproducible; regenera los avatares desde la referencia en cualquier momento.*

---

# v0.11 — Las capas se vuelven 3D de verdad (D95-D98)

**28 de octubre de 2026** · pipeline v4 en `tools/avatares/make_layers_3d.py`

Las bases pasaron a renders 3D en v0.10 (D92), pero las capas seguían siendo
vectores planos con trazo de tinta → "stickers pegados" (el usuario: "súper
mal y horrible"). Esta versión rehace TODA la ropa y los accesorios como
**raster con sombreado suave** en el mismo lenguaje vinilo de las bases.

## Qué cambió

| Pieza | v0.10 (vector con tinta) | v0.11 (raster vinilo) |
|--------|--------------------------|------------------------|
| Contorno | trazo #503433 de 14-16px | **NINGUNO** — silueta por sombreado |
| Volumen | plano | degradado de luz diagonal + rim oscurecido + especular difuso |
| Anclaje | nada | **sombra de contacto** difuminada bajo cada pieza |
| Ajuste ropa | constantes (más estrecha que la panza) | **silueta medida fila a fila** (`medidas/silhouette.json`) |
| Catálogo | 5 ropa + 5 acc | 7 ropa (vestido/traje) + 10 acc (gorra, aretes, bufanda, 2 zapatos) |
| Bases | 8 | **7** (cerdita menta eliminada a petición) |
| Decoración | burbuja con corazones | **ELIMINADA** a petición |

## Motor de sombreado (soft_shape)

1. Máscara L supersampleada ×2 (dibujo a 2048, exporta 384).
2. Degradado: `f = 1.06 − dark·t` con `t = 0.38·x + 0.62·y` (luz arriba-izquierda).
3. Rim: erosión 9px → banda de borde oscurecida (define la silueta SIN tinta).
4. Especular: blob blanco difuso en el cuarto superior-izquierdo del bbox.
5. Sombra de contacto: máscara difuminada desplazada (+7,+13), color cálido
   `(62,40,55)` alpha ~30 % — NUNCA negro puro.

## Geometría medida (lección D77 otra vez)

- **Panza de la cerdita = half 292 px** (fila 654): la camiseta v0.10 tenía
  half 273 → por eso parecía una caja. Ahora la ropa sigue `body_profile(y)`
  interpolado de la silueta real con ease de 5-8 px.
- **Brazos del koala separados del torso desde y≈710** (blobs por fila):
  las mangas del traje son tubos independientes sobre los lóbulos reales
  (c≈305/716) con puño; el traje-corpo va SOLO sobre el torso (350-670).
- **Pies**: cerdita cascos pequeños (cx 425/599, w 83), koala patas grandes
  (w 95) → botita bulbosa vs sneaker de puntera ancha, estilo por familia.
- **Ojos del koala en línea inclinada** (izq y=361, der y=335): las gafas
  compensan con min/max en el puente.

## QA

Bucle de hojas de contactos + VLM (regla D83). Punto clave: la 3.ª ronda el
VLM entró en nitpicking imposible ("cloth simulation") — el veredicto útil
fue el COMPARATIVO antes/después: **5/5 "gana la nueva por amplio margen"**.
Verificación numérica de la geometría sobre los webp exportados (blobs por
fila: vestido en A real 208→178, scallops confirmados, traje 3 blobs abajo).

## Ficheros nuevos

```
tools/avatares/
  make_layers_3d.py           motor soft_shape + 17 items × familia → 32 WebP
  work3d/medidas/silhouette.json   silueta fila a fila de ambos cuerpos

android/app/src/main/res/drawable-nodpi/
  av_c_*.webp, av_a_*.webp    32 capas raster (antes 22 XML en drawable/)
```

Peso: **+300 KB** de capas (368 KB lossless en disco, 39 WebP dentro del APK).
Tests: AvatarCatalogTest (6) — fallback por familia, filtrado, ids legacy.
