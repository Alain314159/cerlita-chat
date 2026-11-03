#!/usr/bin/env python3
"""Genera los drawable vectoriales del sistema de avatares (D61, v0.4.0).

Geometría común (viewBox 120x120) para que TODAS las capas encajen sobre
cualquier base: ojos en (48,47) y (72,47); torso 20..100 x 90..120;
tope de cabeza ~y16. Placeholders — el arte real vendrá después.

Salida: android/app/src/main/res/drawable/av_*.xml
"""
import os

OUT = "/home/z/my-project/app/android/app/src/main/res/drawable"

HEAD = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="96dp" android:height="96dp"
    android:viewportWidth="120" android:viewportHeight="120">
{body}
</vector>
"""

def path(d, fill=None, stroke=None, sw=None, alpha=None, cap="round"):
    s = f'    <path\n        android:pathData="{d}"\n'
    if fill: s += f'        android:fillColor="{fill}"\n'
    if stroke:
        s += f'        android:strokeColor="{stroke}"\n        android:strokeWidth="{sw}"\n'
        s += f'        android:strokeLineCap="{cap}"\n        android:strokeLineJoin="round"\n'
    if alpha: s += f'        android:fillAlpha="{alpha}"\n'
    return s + "    />"

def circle(cx, cy, r, fill):
    return path(f"M {cx},{cy} m -{r},0 a {r},{r} 0 1 0 {2*r},0 a {r},{r} 0 1 0 -{2*r},0", fill)

# ─────────── geometría compartida ───────────
TORSO = "M20,120 v-6 c0,-15 13,-24 40,-24 s40,9 40,24 v6 z"

FILES = {}

# ─────────── bases ───────────
FILES["av_pig.xml"] = HEAD.format(body="\n".join([
    # orejas (detrás de la cabeza)
    path("M34,30 L26,10 L50,22 Z", "#F7A8C4"),
    path("M86,30 L94,10 L70,22 Z", "#F7A8C4"),
    path("M36,27 L31,15 L45,22 Z", "#EE8FB4"),
    path("M84,27 L89,15 L75,22 Z", "#EE8FB4"),
    circle(60, 50, 31, "#F7A8C4"),                       # cabeza
    path("M38,58 m-4.6,0 a4.6,2.8 0 1 0 9.2,0 a4.6,2.8 0 1 0 -9.2,0", "#F48FB1", alpha="0.7"),  # rubor
    path("M82,58 m-4.6,0 a4.6,2.8 0 1 0 9.2,0 a4.6,2.8 0 1 0 -9.2,0", "#F48FB1", alpha="0.7"),
    circle(48, 47, 3.1, "#3A2B4F"),                      # ojos (posición común)
    circle(72, 47, 3.1, "#3A2B4F"),
    path("M60,52.5 m-12.5,0 a12.5,8.5 0 1 0 25,0 a12.5,8.5 0 1 0 -25,0", "#F290B8"),  # hocico
    path("M55.5,58 m-2,0 a2,3.1 0 1 0 4,0 a2,3.1 0 1 0 -4,0", "#C96A9C"),
    path("M64.5,58 m-2,0 a2,3.1 0 1 0 4,0 a2,3.1 0 1 0 -4,0", "#C96A9C"),
    path(TORSO, "#F7A8C4"),                              # hombros (piel)
]))

FILES["av_koala.xml"] = HEAD.format(body="\n".join([
    circle(29, 33, 15, "#9FB0C4"),                       # orejas grandes
    circle(91, 33, 15, "#9FB0C4"),
    circle(29, 33, 8.5, "#E9D2DE"),
    circle(91, 33, 8.5, "#E9D2DE"),
    circle(60, 51, 30, "#BCC7D8"),                       # cabeza
    circle(48, 47, 3.0, "#33304A"),                      # ojos (posición común)
    circle(72, 47, 3.0, "#33304A"),
    path("M60,51 m-6.5,0 a6.5,9 0 1 0 13,0 a6.5,9 0 1 0 -13,0", "#4A3B5C"),  # nariz grande
    path("M58,55 m-1.5,0 a1.5,2.2 0 1 0 3,0 a1.5,2.2 0 1 0 -3,0", "#6A587E"),
    path(TORSO, "#BCC7D8"),
]))

# ─────────── ropa (encima del torso; misma silueta) ───────────
def tee(main, collar):
    return HEAD.format(body="\n".join([
        path(TORSO, main),
        path("M60,87.5 m-8,0 a8,3.5 0 1 0 16,0 a8,3.5 0 1 0 -16,0", collar),  # escote
    ]))

FILES["av_c_tee_violet.xml"] = tee("#7C6BD9", "#6A59C4")
FILES["av_c_tee_pink.xml"]   = tee("#F08CA0", "#E2778E")
FILES["av_c_tee_teal.xml"]   = tee("#5BC4B8", "#47AFA2")

FILES["av_c_dots.xml"] = HEAD.format(body="\n".join([
    path(TORSO, "#F2E8D5"),
    path("M60,87.5 m-8,0 a8,3.5 0 1 0 16,0 a8,3.5 0 1 0 -16,0", "#E3D5BE"),
    circle(44, 102, 2.3, "#E85D75"), circle(60, 107, 2.3, "#7C6BD9"),
    circle(76, 101, 2.3, "#E85D75"), circle(52, 114, 2.3, "#7C6BD9"),
    circle(68, 115, 2.3, "#E85D75"),
]))

FILES["av_c_stripes.xml"] = HEAD.format(body="\n".join([
    path(TORSO, "#F2A0B5"),
    path("M22,101 h76 v6 h-76 z", "#E2778E"),
    path("M24,110 h72 v6 h-72 z", "#E2778E"),
    path("M60,87.5 m-8,0 a8,3.5 0 1 0 16,0 a8,3.5 0 1 0 -16,0", "#D96A87"),
]))

# ─────────── accesorios ───────────
# gafas (slot eyes) — alineadas con los ojos comunes (48,47)/(72,47)
FILES["av_a_glasses.xml"] = HEAD.format(body="\n".join([
    path("M41,47 m-7,0 a7,7 0 1 0 14,0 a7,7 0 1 0 -14,0", stroke="#3A2B4F", sw="2.4"),
    path("M65,47 m-7,0 a7,7 0 1 0 14,0 a7,7 0 1 0 -14,0", stroke="#3A2B4F", sw="2.4"),
    path("M55,47 h10", stroke="#3A2B4F", sw="2.4"),
    path("M41,45 L33,41", stroke="#3A2B4F", sw="2.4"),
    path("M79,45 L87,41", stroke="#3A2B4F", sw="2.4"),
]))

# moño (slot head) — tope centro, entre orejas de cerdita y sobre cabeza de koalito
FILES["av_a_bow.xml"] = HEAD.format(body="\n".join([
    path("M58,16 L44,6 L44,26 Z", "#E85D75"),
    path("M62,16 L76,6 L76,26 Z", "#E85D75"),
    circle(60, 16, 4.6, "#D14A63"),
]))

# gorro de fiesta (slot head)
FILES["av_a_party.xml"] = HEAD.format(body="\n".join([
    path("M60,4 L48,30 L72,30 Z", "#F08CA0"),
    path("M52,22 L68,22", stroke="#F2E8D5", sw="2.2"),
    circle(60, 4, 3.4, "#F2E8D5"),
]))

# flor (slot head) — junto a la base de la oreja derecha
FILES["av_a_flower.xml"] = HEAD.format(body="\n".join([
    circle(74, 17, 3.0, "#F5A9C4"), circle(69.5, 20.5, 3.0, "#F5A9C4"),
    circle(71, 26, 3.0, "#F5A9C4"), circle(77, 26, 3.0, "#F5A9C4"),
    circle(78.5, 20.5, 3.0, "#F5A9C4"),
    circle(74, 22, 2.4, "#F2E8D5"),
]))

# corazón (slot chest) — broche sobre la ropa
FILES["av_a_heart.xml"] = HEAD.format(body="\n".join([
    path("M44,100 c-2.4,-4.4 -9,-2.4 -9,2.2 c0,4.6 9,9.8 9,9.8 c0,0 9,-5.2 9,-9.8 c0,-4.6 -6.6,-6.6 -9,-2.2 z",
         "#E85D75"),
]))

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, content in FILES.items():
        with open(os.path.join(OUT, name), "w") as f:
            f.write(content)
    print(f"{len(FILES)} drawables escritos en {OUT}:")
    for name in sorted(FILES):
        print("  -", name)
