"""Monta los paquetes a ciegas para el juez: en cada mensaje, la respuesta del bot y la del otro asistente
como A y B en orden aleatorio. La correspondencia (qué es A y qué es B) se guarda aparte, en key.json.

    python -I eval/compare_packets.py eval/results/compare-bot.json <carpeta con sonnet-*.json> <carpeta de salida>
"""
import glob
import json
import os
import random
import sys

bot_file, other_dir, out_dir = sys.argv[1:4]
bot = {c["id"]: c for c in json.load(open(bot_file, encoding="utf-8"))}
other = {}
for path in sorted(glob.glob(os.path.join(other_dir, "sonnet-*.json"))):
    for c in json.load(open(path, encoding="utf-8")):
        other[c["id"]] = c

random.seed(20261028)
ids = [i for i in bot if i in other]
packets, key = [], {}
for conversation_id in ids:
    turns = []
    for i, (b, o) in enumerate(zip(bot[conversation_id]["turns"], other[conversation_id]["turns"])):
        bot_first = random.random() < 0.5
        key[f"{conversation_id}#{i}"] = {"A": "bot" if bot_first else "sonnet", "B": "sonnet" if bot_first else "bot"}
        bot_side = {"text": b["answer"], "shown_sources": [f"[{s['number']}] {s['title']} — {s['url']}" for s in b["sources"]]}
        other_side = {"text": o["answer"], "shown_sources": o.get("links") or []}
        turns.append({"question": b["question"],
                      "A": bot_side if bot_first else other_side,
                      "B": other_side if bot_first else bot_side})
    packets.append({"id": conversation_id, "turns": turns})

os.makedirs(out_dir, exist_ok=True)
for n in range(4):                          # 4 jueces, 3 conversaciones cada uno
    part = packets[n * 3:(n + 1) * 3]
    json.dump(part, open(os.path.join(out_dir, f"packet-{n + 1}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
json.dump(key, open(os.path.join(out_dir, "key.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(len(ids), "conversaciones,", len(key), "mensajes")
