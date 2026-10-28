"""Pasa las conversaciones de eval/compare-conversations.json por el bot (servidor local, marcadas como internas)
y guarda cada respuesta con sus fuentes. Es la mitad "bot" de la comparación con un LLM genérico.

    python -I eval/compare_bot.py eval/compare-conversations.json eval/results/compare-bot.json

Arranca el servidor con más margen que el límite normal por persona: --chat.limit.per-ip=200.
Si se corta, al volver a lanzarlo sigue por las conversaciones que faltan.
"""
import json
import os
import sys
import urllib.request

BASE = "http://localhost:8080/api/chat"


def ask(question, conversation_id):
    body = json.dumps({"question": question, "conversationId": conversation_id, "internal": True}).encode()
    request = urllib.request.Request(BASE, data=body, headers={"Content-Type": "application/json"})
    event = None
    with urllib.request.urlopen(request, timeout=240) as response:
        for raw in response:
            line = raw.decode("utf-8").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:") and event in ("done", "error"):
                return event, json.loads(line[5:])
    return "sin-final", {}


conversations = json.load(open(sys.argv[1], encoding="utf-8"))
results = json.load(open(sys.argv[2], encoding="utf-8")) if os.path.exists(sys.argv[2]) else []
done = {r["id"] for r in results}
for conversation in conversations:
    if conversation["id"] in done:
        continue
    conversation_id = None
    turns = []
    for question in conversation["turns"]:
        event, data = ask(question, conversation_id)
        conversation_id = data.get("conversationId", conversation_id)
        sources = [{"number": s["number"], "title": s["title"], "url": s["url"], "type": s.get("type")}
                   for s in data.get("sources") or []]
        turns.append({"question": question, "answer": data.get("answer") if event == "done" else f"[{event}] {data}",
                      "sources": sources})
        print(f"{conversation['id']}: {question[:40]} -> {event}", flush=True)
    results.append({"id": conversation["id"], "turns": turns})
    json.dump(results, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("ok")
