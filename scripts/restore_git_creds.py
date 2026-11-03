#!/usr/bin/env python3
"""Restaura ~/.git-credentials desde el archivo subido por el usuario.
NUNCA imprime el token. Confirmación enmascarada + prueba ls-remote."""
import os, re, subprocess, sys

SRC = "/home/z/my-project/upload/folder/26ab1b36/Credenciales de github que pue.txt"
USER = "Alain314159"  # H20: username de GitHub (NO el email — la @ rompe el store)

raw = open(SRC, "r", encoding="utf-8", errors="replace").read()
# Buscar token: ghp_*, github_pat_*, gho_*, o un token de 40+ chars tras "token"
m = (re.search(r"\b(ghp_[A-Za-z0-9]{30,})\b", raw)
     or re.search(r"\b(github_pat_[A-Za-z0-9_]{30,})\b", raw)
     or re.search(r"\b(gho_[A-Za-z0-9]{30,})\b", raw)
     or re.search(r"\b([A-Za-z0-9]{40,})\b", raw))
if not m:
    print("FALLO: no se encontró token en el archivo"); sys.exit(1)
tok = m.group(1)

home = os.path.expanduser("~")
cred = f"https://{USER}:{tok}@github.com\n"
with open(os.path.join(home, ".git-credentials"), "w") as f:
    f.write(cred)
os.chmod(os.path.join(home, ".git-credentials"), 0o600)
subprocess.run(["git", "config", "--global", "credential.helper", "store"], check=False)

# Pruba: ls-remote (no imprime el token)
r = subprocess.run(["git", "ls-remote", "https://github.com/Alain314159/cerlita-chat.git",
                    "HEAD"], capture_output=True, text=True, timeout=60)
ok = r.returncode == 0 and "HEAD" in (r.stdout or "")
print("CREDENCIALES OK — token enmascarado:", tok[:4] + "…" + tok[-3:], f"(len {len(tok)})")
print("ls-remote:", "ACCESO VERIFICADO" if ok else f"FALLO rc={r.returncode}: {r.stderr[:120]}")
sys.exit(0 if ok else 1)
