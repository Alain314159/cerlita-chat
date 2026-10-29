#!/usr/bin/env python3
"""
D116 (Fase V del informe 1.0): puerta de cobertura — «umbral publicado para
los paquetes críticos». Corre en CI justo después de los tests unitarios y
ROMPE el build si la calidad medible cae por debajo de los suelos publicados.

Uso:
    python3 tools/qa/coverage_gate.py <report.xml> <directorio-test-results>

Suelos publicados (docs/qa/COBERTURA.md — subirlos requiere decisión):
  1. cu.alain.chat.crypto  · cobertura LINEA ≥ 70 %  (medido al publicar: 75,1 %)
     — el paquete que el informe 1.0 citó como ejemplo: «seguramente rondaría
       cifras excelentes y ganaría el argumento para siempre». Lo rondó.
  2. total de tests unitarios ≥ 130  (medido al publicar: 132)
     — criterio de salida LITERAL de la 1.0 (Fase P): «las dos suites de
       pruebas por encima de 130 casos en total».

Límite conocido y documentado: los tests Robolectric (engine, notify, db, qa…)
corren en el sandbox de Robolectric, que el agente JaCoCo de AGP no captura —
por eso NO se publica suelo para «motor»: sería un número falso. El camino
para medirlo (instrumentación offline de JaCoCo) queda documentado en
docs/qa/COBERTURA.md como trabajo futuro, no como excusa permanente.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

# ── suelos publicados ────────────────────────────────────────────────────────
CRYPTO_MIN_LINE_PCT = 70.0   # paquete cu.alain.chat.crypto
MIN_TOTAL_TESTS = 130        # suite unitaria completa


def pkg_line_coverage(report_xml: str, pkg: str) -> float | None:
    root = ET.parse(report_xml).getroot()
    for p in root.findall("package"):
        if p.get("name") == pkg:
            for c in p.findall("counter"):
                if c.get("type") == "LINE":
                    m, cv = int(c.get("missed")), int(c.get("covered"))
                    return 100.0 * cv / (m + cv) if (m + cv) else 0.0
    return None


def count_tests(results_dir: str) -> tuple[int, int]:
    total = failures = 0
    for p in glob.glob(f"{results_dir}/TEST-*.xml"):
        x = open(p, encoding="utf-8").read()
        m = re.search(
            r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', x
        )
        if m:
            total += int(m.group(1))
            failures += int(m.group(3)) + int(m.group(4))
    return total, failures


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    report_xml, results_dir = sys.argv[1], sys.argv[2]

    ok = True

    crypto = pkg_line_coverage(report_xml, "cu/alain/chat/crypto")
    if crypto is None:
        print("GATE ✗: el reporte no contiene el paquete crypto")
        ok = False
    else:
        status = "✓" if crypto >= CRYPTO_MIN_LINE_PCT else "✗"
        print(f"GATE {status}: crypto LINE {crypto:.1f}% (suelo {CRYPTO_MIN_LINE_PCT}%)")
        ok &= crypto >= CRYPTO_MIN_LINE_PCT

    total, failures = count_tests(results_dir)
    status = "✓" if (total >= MIN_TOTAL_TESTS and failures == 0) else "✗"
    print(f"GATE {status}: {total} tests unitarios, {failures} fallos (suelo {MIN_TOTAL_TESTS}, fallos 0)")
    ok &= total >= MIN_TOTAL_TESTS and failures == 0

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
