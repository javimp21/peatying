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
  python scripts/transcribe_interviews.py ge.tsv              # "id<TAB>tema<TAB>url" (p. ej. Generative Energy)
"""
import concurrent.futures
import json
import os
import re
import subprocess
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
    "ge": "Generative Energy",
}

# Lo que conviene saber de un programa para no confundir a Peat con otro invitado.
SHOW_HINTS = {
    "ge": "This is the Generative Energy podcast. Danny Roddy is the host. Georgi Dinkov, when present, is a co-host "
          "who also gives long explanations about biology and studies: label him HOST, never PEAT. "
          "Peat is older, speaks slowly and often tells anecdotes from his own experiments and reading.",
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


def multipart(fields, file=None):
    boundary = uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items()]
    if file:
        name, data = file
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                     f'Content-Type: audio/ogg\r\n\r\n'.encode() + data + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


TMP_DIR = os.path.join("data", "audio_tmp")
GROQ_URL_LIMIT = 100 * 1024 * 1024          # Groq no acepta enlaces a archivos de más de 100 MB


def compressed(url, name):
    """Para audios de más de 100 MB (los de Generative Energy, a 128 kbps estéreo): los bajamos y los dejamos
    en voz mono Opus a 16 kbps (unos 7 MB por hora), que cabe en el límite de subida de 25 MB y Whisper
    entiende igual. Se borran al terminar."""
    os.makedirs(TMP_DIR, exist_ok=True)
    source, target = os.path.join(TMP_DIR, name + ".mp3"), os.path.join(TMP_DIR, name + ".ogg")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=900) as resp, open(source, "wb") as f:
        while chunk := resp.read(1 << 20):
            f.write(chunk)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", source, "-ac", "1", "-ar", "16000",
                    "-c:a", "libopus", "-b:a", "16k", target], check=True)
    with open(target, "rb") as f:
        data = f.read()
    os.remove(source)
    os.remove(target)
    return data


def remote_size(url):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return int(resp.headers.get("content-length") or 0)


def transcribe(url, name):
    """Normalmente le pasamos a Groq el enlace del audio y lo descarga él: nosotros no bajamos nada. Así también
    valen los archivos de más de 25 MB (el límite de subida de la cuenta gratuita). Si pasa de 100 MB (el
    límite de los enlaces), lo comprimimos aquí y lo subimos."""
    fields = {"model": "whisper-large-v3-turbo", "response_format": "verbose_json", "language": "en"}
    # toxinless.com no es accesible desde aquí, y sus archivos caben siempre: ni lo miramos.
    if "toxinless.com" not in url and remote_size(url) > GROQ_URL_LIMIT:
        body, ctype = multipart(fields, (name + ".ogg", compressed(url, name)))
    else:
        body, ctype = multipart({**fields, "url": url})
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


def label_speakers(sentences, hint=""):
    numbered = "\n".join(f"{i}: {s['text']}" for i, s in enumerate(sentences))
    last = len(sentences) - 1
    prompt = f"""This is an automatic transcript of a radio interview or podcast with the biologist Ray Peat, split into numbered sentences.
Decide who speaks in each sentence. Speakers: PEAT (Ray Peat, the guest who answers), HOST (any presenter or interviewer), CALLER (listeners who phone in or whose questions are read).
Peat gives long explanations about biology, hormones, metabolism, history and politics; hosts introduce, ask, read questions and react; callers ask about their own case.
{hint}
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


def metadata(url, name=None, given_topic=None):
    """El id dice programa y fecha ("kmud-081201-cholesterol"). En toxinless.com es el nombre del archivo;
    para otros sitios (enlaces sin nombre útil) viene en la lista junto con el tema."""
    name = name or url.rsplit("/", 1)[-1].removesuffix(".mp3")
    parts = name.split("-")
    show = SHOWS.get(parts[0], parts[0])
    date = None
    if len(parts) > 1 and re.fullmatch(r"\d{6}", parts[1]):
        yy, mm, dd = parts[1][:2], parts[1][2:4], parts[1][4:]
        date = f"{'19' if int(yy) > 50 else '20'}{yy}-{mm}-{dd}"
        topic = " ".join(parts[2:])
    else:
        topic = " ".join(parts[1:])
    return name, show, date, given_topic or topic.capitalize()


def process(job):
    url, name, given_topic = job
    name, show, date, topic = metadata(url, name, given_topic)
    path = os.path.join(OUT_DIR, name + ".json")
    if os.path.exists(path):
        return name, "ya estaba"
    transcript = transcribe(url, name)
    with lock:
        costs["audio_seconds"] += transcript.get("duration", 0)
    sentences = sentences_of(transcript)
    hint = SHOW_HINTS.get(name.split("-")[0], "")
    try:
        ranges = label_speakers(sentences, hint)
    except (ValueError, AttributeError, json.JSONDecodeError):
        ranges = label_speakers(sentences, hint)    # un reintento si el formato vino mal
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
    jobs = []
    for line in open(sys.argv[1], encoding="utf-8"):
        if "\t" in line:                     # "id<TAB>tema<TAB>url": episodios de fuera de toxinless.com
            name, topic, url = line.rstrip("\n").split("\t")
            jobs.append((url, name, topic))
        elif "toxinless.com" in line:        # "estado tamaño url" o solo "url"
            jobs.append((line.split()[-1], None, None))
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
