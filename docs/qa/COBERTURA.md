# Cobertura de pruebas — Cerlita Chat

> D116 · Fase V del informe de preparación 1.0 · publicado por primera vez el 30-10-2026
> con la v0.18.0. «Lo que no se mide, se degrada; y lo que se mide, se defiende solo.»

## Suelos publicados (los rompe el build en CI)

| Métrica | Suelo | Medido al publicar | Dónde se comprueba |
|---|---|---|---|
| Cobertura LINEA del paquete `cu.alain.chat.crypto` | **≥ 70 %** | **75,1 %** (163/217) | `tools/qa/coverage_gate.py` |
| Total de tests unitarios | **≥ 130** | **132** | `tools/qa/coverage_gate.py` |

Subir un suelo es una decisión deliberada (y un commit propio), nunca un
efecto lateral. Bajar un suelo exige justificación escrita en DECISIONES.md.

## Cómo se mide

- **Comando local**: `./gradlew :app:createDebugUnitTestCoverageReport` (debug
  tiene `enableUnitTestCoverage = true`, D116) y después
  `python3 tools/qa/coverage_gate.py app/build/reports/coverage/test/debug/report.xml app/build/test-results/testDebugUnitTest`
- **En CI**: paso «Cobertura (umbral D116)» del job `build` — corre la puerta y
  sube el reporte HTML completo como artefacto `cobertura-html`.

## Números del primer corte (v0.18.0, 30-10-2026)

Paquetes con cobertura medida por los tests JVM puros:

| Paquete | LINEA | Quién lo prueba |
|---|---|---|
| `crypto` | 75,1 % | CryptoRoomsTest, DmCryptoTest (vectores cruzados Python/Kotlin) |
| `profile` | 68,5 % | ProfileTest, ChatMsgsTest, AttachmentMsgTest |
| `export` | 34,8 % | HistoryImportTest |
| `calls` | 9,6 % | CallSignalsTest (la pila WebRTC real solo se verifica en dispositivo) |
| `net` | 13,8 % | NtfyTest, PhoenixTest, MockBackendTest |
| `ui` | 13,0 % | AvatarCatalogTest |

## Límite conocido: Robolectric no entra en el número

Los paquetes `engine`, `notify`, `db`, `qa`, `work` y `vault` se prueban con
Robolectric, cuyo sandbox carga las clases de la app con un classloader propio
que **el agente JaCoCo de AGP no instrumenta**: en el reporte aparecen al 0 %
aunque SUS TESTS EXISTEN Y PASAN (132 en total). Por eso no se publica un
suelo para «motor»: sería un número falso, y este proyecto no publica números
falsos.

**Camino para medirlo en el futuro** (trabajo pendiente, no excusa
permanente): instrumentación OFFLINE de JaCoCo (las clases se instrumentan en
el propio .class antes de empaquetar, el classloader de Robolectric las recoge
ya instrumentadas) o migrar a Kover cuando soporte el Kotlin embebido de
AGP 9. El día que se haga, el suelo de `engine` se publica en esta misma
tabla con el número que salga.

Mientras tanto, la salud del motor se defiende por otras tres vías que ya
existen: la suite Robolectric (132 tests, 0 fallos, en CI), la prueba
instrumentada `FlujoCriticoTest` (D115, dispositivo real) y las suites
extremo a extremo del backend contra producción.

## Lint (D117, misma disciplina)

- `android/app/lint-baseline.xml` congela el estado real al publicar:
  **2 errores, 60 avisos, 9 hints** (71 issues, todos documentados en el XML).
- Cualquier aviso NUEVO rompe el build (`lint { abortOnError = true }` +
  baseline; en CI corre `lintDebug` en cada push).
- Receta para absorber un aviso de una vez: arreglarlo y regenerar la base
  (`./gradlew :app:updateLintBaseline`), o si se decide vivir con él,
  regenerarla con el aviso dentro — pero eso queda escrito en el baseline y
  es visible en el diff del commit.
