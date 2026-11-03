# -*- coding: utf-8 -*-
"""Fusión portada + cuerpo del Informe de Preparación v1.0 (normalizado a A4)."""
from pypdf import PdfReader, PdfWriter

A4_W, A4_H = 595.28, 841.89
COVER = "/home/z/my-project/scripts/informe_v1_cover.pdf"
BODY = "/home/z/my-project/scripts/informe_v1_body.pdf"
OUT = "/home/z/my-project/download/Informe-Preparacion-1.0-Cerlita-Chat.pdf"


def normalize(page):
    w, h = float(page.mediabox.width), float(page.mediabox.height)
    if abs(w - A4_W) > 0.1 or abs(h - A4_H) > 0.1:
        page.scale_to(A4_W, A4_H)
    return page


writer = PdfWriter()
writer.add_page(normalize(PdfReader(COVER).pages[0]))
for pg in PdfReader(BODY).pages:
    writer.add_page(normalize(pg))
writer.add_metadata({
    "/Title": "Informe de Preparación para la Versión 1.0 — Cerlita Chat",
    "/Author": "Z.ai",
    "/Creator": "Z.ai",
    "/Subject": "Análisis de brechas de lanzamiento (release readiness) de la app Cerlita Chat v0.17.0",
})
with open(OUT, "wb") as f:
    writer.write(f)
print("OK:", OUT, "páginas:", len(writer.pages))
