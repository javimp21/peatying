"""
Transcribe las entrevistas de Ray Peat a partir de sus audios públicos (toxinless.com) y separa quién habla.

  1. Groq (Whisper large v3 turbo): audio -> texto literal con marcas de tiempo.
  2. Claude Sonnet: decide quién dice cada frase (PEAT / HOST / CALLER). No reescribe nada: solo
     devuelve rangos de frases y su hablante, así el texto sigue siendo exactamente lo que se oye.
  3. Guarda un JSON por episodio en data/transcripts/, que luego importa el comando index-interviews.

Reanudable: los episodios ya guardados se saltan. Si algo falla (límite de gasto, red...), se relanza
y sigue donde se quedó.

Uso (desde la raíz del proyecto, con GROQ_API_KEY y ANTHROPIC_API_KEY en el entorno):
  python scripts/transcribe_interviews.py urls.txt            # un enlace .mp3 por línea
  python scripts/transcribe_interviews.py urls.txt --limit 3  # solo los 3 primeros (prueba)
"""
import concurrent.futures
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

OUT_DIR = os.path.join("data", "transcripts")
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
SPEAKER_MODEL = "claude-sonnet-5-5"
UA = "rag-peat/0.1 (personal project)"

# El nombre del archivo dice el programa: "kmud-081201-cholesterol..." -> Ask the Herb Doctor (KMUD).
SHOWS = {
    "kmud": "Ask the Herb Doctor (KMUD)", "polsci": "Politics & Science", "orn": "One Radio Network",
    "ewh": "EastWest Healing", "jf": "JodelleFit", "sourcenutritional": "Source Nutritional Show",
    "rainmaking": "It's Rainmaking Time", "eluv": "ELUV", "blp": "Butter Living Podcast",
    "wp": "World Puja", "voiceofamerica": "Voice of America", "kkvv": "KKVV Radio",
}

lock = threading.Lock()
costs = {"audio_seconds": 0.0, "input_tokens": 0, "output_tokens": 0}


def http_json(req, timeout=600, retries=5):
    """Hace la petición y, si el proveedor dice que vayamos más despacio (429/529), espera y reintenta."""
    for attempt in range(retries):
        try:
            return json.loads(urllib.request.urlopen(req, timeout=timeout).read())
        except urllib.error.HTTPError as e:
            body = e.read()[:500]
            if e.code in (429, 500, 502, 503, 529) and attempt < retries - 1:
                wait = float(e.headers.get("retry-after") or 20 * (attempt + 1))
                print(f"   {e.code}, espero {wait:.0f} s…", flush=True)
                time.sleep(min(wait, 300))
                continue
            raise RuntimeError(f"HTTP {e.code}: {body!r}") from None


def multipart(fields):
    boundary = uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items()]
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def transcribe(url):
    """Le pasamos a Groq el enlace del audio y lo descarga él: nosotros no bajamos nada. Así también
    valen los archivos de más de 25 MB (el límite de subida de la cuenta gratuita)."""
    fields = {"model": "whisper-large-v3-turbo", "response_format": "verbose_json", "language": "en", "url": url}
    body, ctype = multipart(fields)
    req = urllib.request.Request(GROQ_URL, data=body, method="POST", headers={
        "Authorization": f"Bearer {os.environ['GROQ_API_KEY']}", "User-Agent": UA, "Content-Type": ctype})
    return http_json(req)


def sentences_of(transcript):
    """Frases con su segundo de inicio. Por frases y no por trozos de Whisper: un trozo puede mezclar a dos personas."""
    result = []
    for seg in transcript["segments"]:
        for s in re.split(r"(?<=[.!?])\s+", seg["text"].strip()):
            if s:
                result.append({"start": seg["start"], "text": s})
    return result


def label_speakers(sentences):
    numbered = "\n".join(f"{i}: {s['text']}" for i, s in enumerate(sentences))
    last = len(sentences) - 1
    prompt = f"""This is an automatic transcript of a radio interview or podcast with the biologist Ray Peat, split into numbered sentences.
Decide who speaks in each sentence. Speakers: PEAT (Ray Peat, the guest who answers), HOST (any presenter or interviewer), CALLER (listeners who phone in or whose questions are read).
Peat gives long explanations about biology, hormones, metabolism, history and politics; hosts introduce, ask, read questions and react; callers ask about their own case.
Be careful at turn changes: a short reaction ("Wow.", "Right.") or a question at the start or end of a sentence block usually belongs to someone else.
Reply ONLY with a JSON list of consecutive ranges covering every sentence from 0 to {last}, like:
[{{"from": 0, "to": 12, "speaker": "HOST"}}, {{"from": 13, "to": 40, "speaker": "PEAT"}}]

{numbered}"""
    body = json.dumps({"model": SPEAKER_MODEL, "max_tokens": 16000,
                       "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(ANTHROPIC_URL, data=body, method="POST", headers={
        "x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01", "content-type": "application/json"})
    resp = http_json(req, timeout=900)
    with lock:
        costs["input_tokens"] += resp["usage"]["input_tokens"]
        costs["output_tokens"] += resp["usage"]["output_tokens"]
    text = "".join(b.get("text", "") for b in resp["content"])
    ranges = json.loads(re.search(r"\[.*\]", text, re.S).group(0))
    covered = sorted(i for r in ranges for i in range(r["from"], r["to"] + 1))
    if covered != list(range(last + 1)) or any(r.get("speaker") not in ("PEAT", "HOST", "CALLER") for r in ranges):
        raise ValueError("rangos incompletos o hablante desconocido")
    return ranges


def metadata(url):
    name = url.rsplit("/", 1)[-1].removesuffix(".mp3")
    parts = name.split("-")
    show = SHOWS.get(parts[0], parts[0])
    date = None
    if len(parts) > 1 and re.fullmatch(r"\d{6}", parts[1]):
        yy, mm, dd = parts[1][:2], parts[1][2:4], parts[1][4:]
        date = f"{'19' if int(yy) > 50 else '20'}{yy}-{mm}-{dd}"
        topic = " ".join(parts[2:])
    else:
        topic = " ".join(parts[1:])
    return name, show, date, topic.capitalize()


def process(url):
    name, show, date, topic = metadata(url)
    path = os.path.join(OUT_DIR, name + ".json")
    if os.path.exists(path):
        return name, "ya estaba"
    transcript = transcribe(url)
    with lock:
        costs["audio_seconds"] += transcript.get("duration", 0)
    sentences = sentences_of(transcript)
    try:
        ranges = label_speakers(sentences)
    except (ValueError, AttributeError, json.JSONDecodeError):
        ranges = label_speakers(sentences)          # un reintento si el formato vino mal
    turns = [{"speaker": r["speaker"], "start": round(sentences[r["from"]]["start"], 1),
              "text": " ".join(s["text"] for s in sentences[r["from"]:r["to"] + 1])} for r in ranges]
    episode = {"id": name, "show": show, "date": date, "topic": topic, "audio_url": url,
               "duration": transcript.get("duration"), "turns": turns}
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(episode, f, ensure_ascii=False, indent=1)
    os.replace(path + ".tmp", path)           # se escribe entero o nada: así un corte no deja archivos a medias
    peat = sum(len(t["text"].split()) for t in turns if t["speaker"] == "PEAT")
    return name, f"{transcript.get('duration', 0) / 60:.0f} min, {len(turns)} turnos, {peat} palabras de Peat"


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    lines = [l.split() for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
    jobs = [parts[-1] for parts in lines if "toxinless.com" in parts[-1]]   # líneas "estado tamaño url" o solo "url"
    if "--limit" in sys.argv:
        jobs = jobs[:int(sys.argv[sys.argv.index("--limit") + 1])]
    started, failed = time.time(), []
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        futures = {pool.submit(process, u): u for u in jobs}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                name, info = future.result()
                print(f"[{done}/{len(jobs)}] {name}: {info}", flush=True)
            except Exception as e:
                failed.append(futures[future])
                print(f"[{done}/{len(jobs)}] FALLO {futures[future]}: {e}", flush=True)
    cost = costs["audio_seconds"] / 3600 * 0.04 + costs["input_tokens"] * 2 / 1e6 + costs["output_tokens"] * 10 / 1e6
    print(f"\nHecho en {(time.time() - started) / 60:.0f} min. Audio: {costs['audio_seconds'] / 3600:.1f} h. "
          f"Sonnet: {costs['input_tokens']} entrada, {costs['output_tokens']} salida. Coste aprox.: ${cost:.2f}")
    if failed:
        print(f"Fallaron {len(failed)} (relánzalo y se reintentan):", *failed, sep="\n  ")


if __name__ == "__main__":
    main()
