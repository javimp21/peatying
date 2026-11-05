// Avisa al servidor de que alguien ha abierto una página, para contar visitas aunque no pregunte.
// Va en todas las páginas públicas: mucha gente entra por un tema o por el About y no llega a pasar por el chat.
// Es anónimo (el servidor solo guarda una huella diaria) y si falla no pasa nada: no molesta a quien lee.
(() => {
  const params = new URLSearchParams(location.search);
  try {
    // Nuestros navegadores no cuentan. ?interno marca o desmarca este navegador (lo hace app.js).
    if (params.has("interno") || localStorage.getItem("rp-internal") === "1") return;
  } catch (e) { /* sin almacenamiento: se cuenta la visita, no pasa nada */ }
  fetch("/api/visit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    // De qué página viene (el servidor guarda solo el dominio). Vacío si se llega directo.
    // ref: la marca de un enlace que pusimos nosotros (?ref=reddit), para saberlo aunque la app no diga el origen.
    // utm: la marca que añaden otros, como ChatGPT (utm_source=chatgpt.com).
    body: JSON.stringify({ referrer: document.referrer, ref: params.get("ref"), utm: params.get("utm_source") }),
  }).catch(() => {});
})();
