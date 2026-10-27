"""Saca el texto de los libros de Ray Peat (PDF) a data/books/<slug>.json, una entrada por página impresa.

    python scripts/extract_books.py <carpeta con los PDF>

data/ no se sube al repositorio: los libros tienen derechos de autor y su texto nunca se publica. La web solo
muestra «Libro, p. N» y un enlace para comprarlo; el texto lo usa el bot para entender y explicar.

Cada PDF numera sus páginas impresas con un desfase fijo respecto a la página del PDF (portada, índice...).
Lo calculamos con los números que aparecen al principio o al final de cada página y nos quedamos con el más
repetido: así un número mal reconocido ("3S" en vez de "35") no estropea nada.
"""
import collections, json, os, re, sys

import pypdf

BOOKS = {
    "FROM PMS to MENOPAUSE.pdf": ("from-pms-to-menopause", "From PMS to Menopause"),
    "generative-energy-restoring-the-wholeness-of-life.pdf": ("generative-energy", "Generative Energy"),
    "Mind And Tissue  Ray Peat.pdf": ("mind-and-tissue", "Mind and Tissue"),
    "nutrition-for-women.pdf": ("nutrition-for-women", "Nutrition for Women"),
    "Progesterone in Orthomolecular Medicine.pdf": ("progesterone-orthomolecular-medicine", "Progesterone in Orthomolecular Medicine"),
}
BUY_URL = "https://raypeat.com/products.shtml"
MIN_CHARS = 200  # menos que esto es una portada, un índice o una página en blanco


def page_number(lines):
    """El número impreso de la página, si está en la primera o la última línea."""
    for line in (lines[0], lines[-1]) if lines else ():
        if re.fullmatch(r"\d{1,3}", line.strip()):
            return int(line.strip())
    return None


def clean(lines, title):
    """Quita el número de página y la cabecera con el título, y une las líneas en un solo párrafo."""
    if lines and re.fullmatch(r"\S{1,4}", lines[0].strip()):
        lines = lines[1:]
    if lines and re.fullmatch(r"\S{1,4}", lines[-1].strip()):
        lines = lines[:-1]
    if lines and lines[0].strip().lower().startswith(title.lower()[:12]):
        lines = lines[1:]
    text = "\n".join(l.strip() for l in lines)
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)  # palabras partidas con guion al final de la línea
    text = re.sub(r"\s*\n\s*", " ", text)
    return re.sub(r" {2,}", " ", text).strip()


def main(folder):
    out_dir = os.path.join("data", "books")
    os.makedirs(out_dir, exist_ok=True)
    for file, (slug, title) in BOOKS.items():
        reader = pypdf.PdfReader(os.path.join(folder, file))
        pages = [(p.extract_text() or "").strip().splitlines() for p in reader.pages]
        offsets = collections.Counter(i + 1 - n for i, lines in enumerate(pages) if (n := page_number(lines)))
        offset = offsets.most_common(1)[0][0]

        entries = []
        for i, lines in enumerate(pages):
            printed = i + 1 - offset
            text = clean(lines, title)
            if printed >= 1 and len(text) >= MIN_CHARS:
                entries.append({"page": printed, "text": text})
        book = {"slug": slug, "title": title, "url": BUY_URL, "pages": entries}
        with open(os.path.join(out_dir, slug + ".json"), "w", encoding="utf-8") as f:
            json.dump(book, f, ensure_ascii=False, indent=1)
        print(f"{title}: {len(reader.pages)} páginas de PDF, desfase {offset} "
              f"(seguro en {offsets[offset]} de {sum(offsets.values())}), {len(entries)} páginas guardadas")


if __name__ == "__main__":
    main(sys.argv[1])
