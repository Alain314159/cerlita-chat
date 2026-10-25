#!/usr/bin/env python3
"""Ejecuta las migraciones SQL contra el proyecto Supabase vía Management API.

Uso: python3 backend/scripts/run_migration.py [archivo.sql]
Divide el archivo en statements (consciente de $dollar-quoting$, strings y
comentarios) y los ejecuta uno a uno. Idempotente gracias a IF NOT EXISTS.
"""
from __future__ import annotations

import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api  # noqa: E402


def split_sql(text: str) -> list[str]:
    stmts: list[str] = []
    buf: list[str] = []
    i, n = 0, len(text)
    while i < n:
        two = text[i : i + 2]
        if two == "--":  # comentario hasta fin de línea
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if two == "/*":  # comentario en bloque
            j = text.find("*/", i)
            i = n if j < 0 else j + 2
            continue
        ch = text[i]
        if ch in ("'", '"'):  # string: consumir hasta la comilla de cierre
            j = i + 1
            while j < n:
                if text[j] == ch:
                    if j + 1 < n and text[j + 1] == ch:
                        j += 2
                        continue
                    break
                j += 1
            buf.append(text[i : j + 1])
            i = j + 1
            continue
        if ch == "$":  # $tag$ ... $tag$ (dollar-quoting)
            m = re.match(r"\$[A-Za-z_]*\$", text[i:])
            if m:
                tag = m.group(0)
                j = text.find(tag, i + len(tag))
                j = n if j < 0 else j + len(tag)
                buf.append(text[i:j])
                i = j
                continue
        if ch == ";":
            s = "".join(buf).strip()
            if s:
                stmts.append(s)
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        stmts.append(tail)
    return stmts


def main() -> int:
    sql_file = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "migrations" / "0001_init.sql"
    if not sql_file.exists():
        print(f"ERROR: no existe {sql_file}")
        return 1

    r = api.ref()
    st, proj = api.call("GET", f"/v1/projects/{r}")
    if st not in api.OK:
        print(f"ERROR obteniendo proyecto: {st} {proj}")
        return 1
    print(f"Proyecto: {proj.get('name')} · región {proj.get('region')} · estado {proj.get('status')}")

    if proj.get("status") != "ACTIVE_HEALTHY" and proj.get("status") != "ACTIVE":
        print(f"Estado {proj.get('status')} — intentando restaurar el proyecto…")
        st2, _ = api.call("POST", f"/v1/projects/{r}/restore")
        print(f"restore -> {st2}")
        for _ in range(24):
            time.sleep(5)
            st3, p3 = api.call("GET", f"/v1/projects/{r}")
            if p3.get("status") in ("ACTIVE", "ACTIVE_HEALTHY"):
                print("Proyecto restaurado.")
                break
        else:
            print("El proyecto no se restauró a tiempo.")
            return 1

    statements = split_sql(sql_file.read_text(encoding="utf-8"))
    print(f"\nEjecutando {len(statements)} statements de {sql_file.name}…\n")

    failed = 0
    for s in statements:
        first = next((l for l in s.splitlines() if l.strip() and not l.strip().startswith("--")), s[:60])
        st, res = api.call("POST", f"/v1/projects/{r}/database/query", {"query": s})
        mark = "OK " if st in api.OK else "FAIL"
        print(f"[{mark} {st}] {first[:78]}")
        if st not in api.OK:
            print(f"       {str(res)[:300]}")
            failed += 1

    print(f"\nResultado: {len(statements) - failed}/{len(statements)} OK, {failed} fallos")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
