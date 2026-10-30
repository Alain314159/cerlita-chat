#!/usr/bin/env python3
"""Recupera las credenciales del commit huérfano 65079af SIN imprimirlas.

Fuentes:
  1. .workspace-backup/upload/folder/26ab1b36/Credenciales de github que pue.txt
     (el archivo que subió el usuario)
  2. skills/github-creds-tienda/SKILL.md (token base64 del mismo usuario)

Acción: valida candidatos contra la API de GitHub (GET /user), escribe el
válido en ~/.git-credentials y restaura secrets/supabase.env a ~/secrets/.
Nunca imprime un token; solo validity/username/scopes.
"""
import base64
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path("/home/z/my-project")
ORPHAN = "65079af"

def git_show(path: str) -> str:
    r = subprocess.run(["git", "-C", str(REPO), "show", f"{ORPHAN}:{path}"],
                       capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else ""

def candidatos_github() -> list:
    """Devuelve tokens candidatos (raw) de ambas fuentes, sin duplicados."""
    toks = []
    txt = git_show(".workspace-backup/upload/folder/26ab1b36/Credenciales de github que pue.txt")
    skill = git_show("skills/github-creds-tienda/SKILL.md")
    for src in (txt, skill):
        toks += re.findall(r"(ghp_[A-Za-z0-9]{30,})", src)
        toks += re.findall(r"(github_pat_[A-Za-z0-9_]{30,})", src)
    for src in (txt, skill):
        for b64 in re.findall(r"[Tt]oken\s*\(base64\)\s*:\s*([A-Za-z0-9+/=]{40,})", src):
            try:
                dec = base64.b64decode(b64).decode()
                if re.match(r"^(ghp_|github_pat_)", dec):
                    toks.append(dec)
            except Exception:
                pass
    seen, out = set(), []
    for t in toks:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out

def valida(token: str) -> dict:
    req = urllib.request.Request("https://api.github.com/user",
                                 headers={"Authorization": "Bearer " + token,
                                          "User-Agent": "cerlita-recovery"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.load(r)
            return {"login": data.get("login"),
                    "scopes": r.headers.get("x-oauth-scopes", "")}
    except Exception as e:
        return {"error": str(e)[:120]}

def main() -> int:
    cands = candidatos_github()
    print("candidatos de token encontrados: %d" % len(cands))
    bueno = None
    for t in cands:
        res = valida(t)
        if res and "login" in res:
            print("  VALIDO: usuario=%s scopes=%s" % (res["login"], res["scopes"]))
            bueno = (t, res["login"])
            break
        print("  invalido: %s" % res.get("error", "?"))
    if not bueno:
        print("NINGUN token valido — hace falta que el usuario reponga el PAT")
        return 1
    tok, login = bueno
    cred_line = "https://Alain314159:%s@github.com\n" % tok
    Path.home().joinpath(".git-credentials").write_text(cred_line)
    subprocess.run(["git", "config", "--global", "credential.helper", "store"])
    print("~/.git-credentials escrito para %s (valor no impreso)" % login)
    supa = git_show("secrets/supabase.env")
    if supa:
        p = Path.home().joinpath("secrets")
        p.mkdir(exist_ok=True)
        (p / "supabase.env").write_text(supa)
        print("~/secrets/supabase.env restaurado (valor no impreso)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
