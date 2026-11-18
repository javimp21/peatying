"""Saca los números de Ray Peat's Newsletter a data/newsletters/issues/<slug>.json, con el mismo formato que los
libros (data/books): una entrada por página del número.

    python scripts/extract_newsletters.py data/newsletters/newsletter_djvu.xml

La fuente es la recopilación "MEGA Master Ray Newsletter" de Internet Archive (el XML del OCR, con la posición de
cada palabra). data/ no se sube al repositorio: las newsletters tienen derechos de autor y su texto nunca se
publica; el bot solo lo usa para entender y explicar con sus palabras.

El OCR junta en una misma línea palabras de las dos columnas, así que el texto se rehace palabra a palabra: se
busca la vertical que separa las columnas (la que menos palabras corta) y se leen primero la izquierda y luego la
derecha. La recopilación incluye también libros que ya tenemos (Mind and Tissue...): solo se usan los rangos de
páginas que son newsletters.
"""
import collections, json, os, re, statistics, sys
import xml.etree.ElementTree as ET

NEWSLETTER_PAGES = [range(0, 641), range(1434, 1474)]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
BUY_URL = "https://raypeat.com/products.shtml"
MIN_CHARS = 200
# Números sin fecha legible en la cabecera: su título, puesto a mano (clave: página donde empiezan).
TITLES = {
    178: "Hayflickers, Dolly and dandruff: the telomeric clock", 192: "Bioelectric fields, regeneration, and the lactic acid myth",
    200: "Tissue firmness and elasticity: forgotten physics and aging", 210: "Recharging the system",
    224: "Optimizing respiration", 391: "Radiation and growth", 499: "Insomnia and hyperactivity",
    511: "Water, darkness, osteoporosis", 531: "Inhibition and vitality", 532: "Menopause",
    546: "A new approach to detoxifying", 570: "Mortality again", 602: "Origins of progesterone therapy",
    625: "Thyroid: misconceptions", 628: "Energy", 632: "The edema of stress",
}
# Páginas que el detector toma por inicio de número y son continuación del anterior (referencias).
NOT_STARTS = {176}
# Si más de esta parte del texto ya está en un artículo de raypeat.com (data/articles), el número se omite.
MAX_OVERLAP = 0.45


def shingles(text, n=8):
    w = re.findall(r"[a-z]+", text.lower())
    return {" ".join(w[i:i + n]) for i in range(len(w) - n)}


def words_of(page):
    words = []
    for w in page.iter("WORD"):
        left, bottom, right, top = map(int, w.get("coords").split(","))
        if w.text and w.text.strip():
            words.append((left, top, right, bottom, w.text.strip()))
    return words


def column_split(words, width):
    """La x que separa las dos columnas, o None si la página es de una sola."""
    if len(words) < 40:
        return None
    best, best_cross = None, None
    for x in range(int(width * 0.35), int(width * 0.65), 10):
        cross = sum(1 for l, t, r, b, _ in words if l < x < r)
        if best_cross is None or cross < best_cross:
            best, best_cross = x, cross
    left = sum(1 for l, t, r, b, _ in words if r <= best)
    right = sum(1 for l, t, r, b, _ in words if l >= best)
    if best_cross > len(words) * 0.03 or min(left, right) < len(words) * 0.15:
        return None
    return best


def lines_of(words):
    """Agrupa palabras en líneas por su altura en la página y las ordena."""
    if not words:
        return []
    heights = [b - t for l, t, r, b, _ in words]
    tol = max(10, statistics.median(heights) * 0.5)
    lines = []
    for w in sorted(words, key=lambda w: (w[1] + w[3]) / 2):
        center = (w[1] + w[3]) / 2
        if lines and abs(lines[-1][0] - center) < tol:
            lines[-1][1].append(w)
        else:
            lines.append([center, [w]])
    return [" ".join(x[4] for x in sorted(ws, key=lambda w: w[0])) for _, ws in lines]


def page_text(words, width):
    split = column_split(words, width)
    if split is None:
        lines = lines_of(words)
    else:
        # Lo que cruza la división (títulos a lo ancho) va delante, luego columna izquierda y derecha.
        wide = [w for w in words if w[0] < split < w[2]]
        lines = (lines_of(wide) + lines_of([w for w in words if w[2] <= split])
                 + lines_of([w for w in words if w[0] >= split]))
    text = "\n".join(lines)
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)  # palabras partidas al final de la línea
    text = re.sub(r"\s*\n\s*", " ", text)
    text = re.sub(r"\s*\|\s*", " ", text)  # restos de las líneas que separan columnas
    return re.sub(r" {2,}", " ", text).strip()


def is_start(words, height):
    top = " ".join(w[4] for w in words if w[1] < height * 0.2)
    return bool(re.search(r"Newsletter", top, re.I) and re.search(r"P[ea]{1,2}t", top, re.I))


def issue_date(words, height):
    """Mes y año del número, sacados de la cabecera: el año más alto que aparezca (el del copyright puede ser
    anterior)."""
    top = " ".join(w[4] for w in words if w[1] < height * 0.25)
    month = next((m for m in MONTHS if re.search(m, top)), None)
    years = [int(y) for y in re.findall(r"\b(19[89]\d|20[0-2]\d)\b", top)]
    return month, (max(years) if years else None)



def main(path):
    issues, current = [], None
    page_no = -1
    for _, el in ET.iterparse(path, events=("end",)):
        if el.tag != "OBJECT":
            continue
        page_no += 1
        if any(page_no in r for r in NEWSLETTER_PAGES):
            width, height = int(el.get("width")), int(el.get("height"))
            words = words_of(el)
            if (is_start(words, height) and page_no not in NOT_STARTS) or current is None:
                month, year = issue_date(words, height)
                current = {"first_page": page_no, "month": month, "year": year, "pages": []}
                issues.append(current)
            text = page_text(words, width)
            if len(text) >= MIN_CHARS:
                current["pages"].append(text)
        el.clear()

    articles = {}
    for name in os.listdir(os.path.join("data", "articles")):
        with open(os.path.join("data", "articles", name), encoding="utf-8") as f:
            articles[name] = shingles(f.read())

    out_dir = os.path.join("data", "newsletters", "issues")
    os.makedirs(out_dir, exist_ok=True)
    for old in os.listdir(out_dir):
        os.remove(os.path.join(out_dir, old))
    seen = collections.Counter()
    for issue in issues:
        if not issue["pages"]:
            continue
        own = shingles(" ".join(issue["pages"]))
        name, shared = max(((n, len(own & s)) for n, s in articles.items()), key=lambda x: x[1])
        if own and shared / len(own) > MAX_OVERLAP:
            print(f"ya está como artículo ({shared / len(own):.0%} en {name}), página {issue['first_page']}, se omite")
            continue
        if issue["year"] and issue["month"]:
            key = f"{issue['year']}-{MONTHS.index(issue['month']) + 1:02d}"
            when = f"{issue['month']} {issue['year']}"
        else:
            key, when = f"p{issue['first_page']:04d}", ""
        seen[key] += 1
        if seen[key] > 1:  # el mismo número repetido en la recopilación
            print(f"repetido {key} (página {issue['first_page']}), se omite")
            continue
        # La fecha basta para identificar el número; el título del OCR sale demasiado sucio para usarlo.
        title = "Ray Peat's Newsletter" + (f", {when}" if when else "")
        if not when and issue["first_page"] in TITLES:
            title += f": {TITLES[issue['first_page']]}"
        slug = f"newsletter-{key}"
        doc = {"slug": slug, "title": title, "url": BUY_URL,
               "pages": [{"page": i + 1, "text": t} for i, t in enumerate(issue["pages"])]}
        with open(os.path.join(out_dir, slug + ".json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        print(f"{slug:24} {len(issue['pages']):3} págs  {title}")


if __name__ == "__main__":
    main(sys.argv[1])
