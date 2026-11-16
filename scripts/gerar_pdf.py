#!/usr/bin/env python3
"""
Gera docs/MANUAL.pdf a partir de docs/MANUAL.md.

Requisitos: python3 com `markdown`, `pypdf` e `reportlab`; Google Chrome (ou Chromium) instalado.
Uso:  python3 scripts/gerar_pdf.py
"""
import io
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import date
from pathlib import Path

import markdown
from pypdf import PdfReader, PdfWriter
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

RAIZ = Path(__file__).resolve().parent.parent
DOCS = RAIZ / "docs"
MD = DOCS / "MANUAL.md"
PDF = DOCS / "MANUAL.pdf"
VERSAO = "0.1.0"
REPO = "https://github.com/EduardoMRT/gateway-api"

CSS = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; }
* { box-sizing: border-box; }
body { font-family: 'DejaVu Sans', 'Liberation Sans', Arial, sans-serif; font-size: 9.6pt;
       line-height: 1.5; color: #1d2433; }
h1 { font-size: 22pt; color: #0b3d91; }
h2 { font-size: 16pt; color: #0b3d91; border-bottom: 2px solid #0b3d91; padding-bottom: 4px;
     margin-top: 30px; page-break-after: avoid; }
h2.sem-quebra { margin-top: 10px; }
h3 { font-size: 12pt; color: #174ea6; margin-top: 18px; page-break-after: avoid; }
p, li { orphans: 3; widows: 3; }
code { font-family: 'DejaVu Sans Mono', monospace; font-size: 8.4pt; background: #f1f3f6;
       padding: 1px 3px; border-radius: 3px; }
pre { background: #f6f8fa; border: 1px solid #d8dee4; border-left: 4px solid #1f6feb;
      border-radius: 4px; padding: 8px 10px; white-space: pre-wrap; word-wrap: break-word;
      page-break-inside: avoid; }
pre code { background: none; padding: 0; font-size: 8pt; line-height: 1.4; }
table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 8.6pt;
        page-break-inside: auto; }
tr { page-break-inside: avoid; }
th { background: #0b3d91; color: #fff; text-align: left; padding: 5px 7px; }
td { border: 1px solid #cfd6df; padding: 4px 7px; vertical-align: top; }
tr:nth-child(even) td { background: #f7f9fc; }
blockquote { margin: 10px 0; padding: 8px 12px; background: #fff8e1; border-left: 4px solid #d98e04;
             page-break-inside: avoid; }
blockquote p { margin: 3px 0; }
img { max-width: 100%; max-height: 165mm; width: auto; display: block; margin: 12px auto;
      page-break-inside: avoid; }
hr { border: none; border-top: 1px solid #d8dee4; margin: 14px 0; }
a { color: #1f6feb; text-decoration: none; }
.capa { height: 255mm; page-break-after: always; display: flex; flex-direction: column;
        justify-content: center; padding: 0 12mm; border-left: 10px solid #0b3d91; }
.capa .titulo { font-size: 34pt; font-weight: bold; color: #0b3d91; line-height: 1.15; }
.capa .sub { font-size: 14pt; color: #444; margin-top: 18px; }
.capa .meta { margin-top: 60px; font-size: 10pt; color: #666; line-height: 1.8; }
"""


def slug(valor, separador="-"):
    """Mesma regra de âncoras do GitHub: minúsculas, sem pontuação, espaços viram hífens."""
    valor = re.sub(r"[^\w\s-]", "", valor.lower(), flags=re.UNICODE).strip()
    return re.sub(r"\s+", separador, valor)


def gerar_html(destino: Path) -> None:
    texto = MD.read_text(encoding="utf-8")
    texto = re.sub(r"^# .*\n", "", texto, count=1)  # o título vai na capa
    corpo = markdown.markdown(
        texto,
        extensions=["tables", "fenced_code", "toc", "sane_lists"],
        extension_configs={"toc": {"slugify": slug}},
    )
    # O sumário e a introdução ficam na mesma página, sem quebra antes do primeiro h2
    corpo = corpo.replace("<h2 id=\"sumário\">", "<h2 class=\"sem-quebra\" id=\"sumário\">", 1)
    capa = f"""
    <div class="capa">
      <div class="titulo">Manual completo<br>do gateway-api</div>
      <div class="sub">Como consumir APIs modernas a partir do Oracle PL/SQL 9i,<br>
        sem escrever HTTP nem parse de JSON na mão</div>
      <div class="meta">Versão {VERSAO}<br>{date.today().strftime('%d/%m/%Y')}<br>{REPO}</div>
    </div>"""
    destino.write_text(
        f"<!DOCTYPE html><html lang='pt-BR'><head><meta charset='utf-8'>"
        f"<title>Manual do gateway-api</title><style>{CSS}</style></head>"
        f"<body>{capa}{corpo}</body></html>",
        encoding="utf-8",
    )


def chrome() -> str:
    for nome in ("google-chrome", "chromium", "chromium-browser", "google-chrome-stable"):
        caminho = shutil.which(nome)
        if caminho:
            return caminho
    sys.exit("Chrome/Chromium não encontrado no PATH.")


def numerar(pdf_entrada: Path, pdf_saida: Path) -> None:
    """Coloca 'pág. N' no rodapé de todas as páginas, exceto a capa."""
    leitor = PdfReader(str(pdf_entrada))
    escritor = PdfWriter()
    total = len(leitor.pages)
    for i, pagina in enumerate(leitor.pages):
        if i > 0:
            largura = float(pagina.mediabox.width)
            buf = io.BytesIO()
            c = canvas.Canvas(buf, pagesize=(largura, float(pagina.mediabox.height)))
            c.setFont("Helvetica", 8)
            c.setFillGray(0.4)
            c.drawString(46, 22, "Manual do gateway-api")
            c.drawRightString(largura - 46, 22, f"pág. {i + 1} de {total}")
            c.save()
            buf.seek(0)
            pagina.merge_page(PdfReader(buf).pages[0])
        escritor.add_page(pagina)
    escritor.add_metadata({"/Title": "Manual completo do gateway-api", "/Author": "gateway-api"})
    with open(pdf_saida, "wb") as f:
        escritor.write(f)


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        html = DOCS / "_manual_tmp.html"  # na pasta docs/ para as imagens relativas (img/...) funcionarem
        bruto = Path(tmp) / "bruto.pdf"
        try:
            gerar_html(html)
            subprocess.run(
                [chrome(), "--headless=new", "--no-sandbox", "--disable-gpu",
                 "--no-pdf-header-footer", f"--print-to-pdf={bruto}", html.as_uri()],
                check=True, capture_output=True, timeout=180,
            )
        finally:
            html.unlink(missing_ok=True)
        numerar(bruto, PDF)
    print(f"Gerado: {PDF} ({len(PdfReader(str(PDF)).pages)} páginas)")


if __name__ == "__main__":
    main()
