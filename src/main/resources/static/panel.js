// Panel privado: métricas y conversaciones. Lee /api/admin/* con la clave del panel.
// Todo lo que escribieron los visitantes se pinta con textContent (como texto), nunca como HTML.

// La clave solo sirve para abrir una sesión, que queda en una cookie que JavaScript no puede leer (ver AdminController).
const OLD_TOKEN_KEY = "rp-panel-token";   // aquí se guardaba la clave antes: se usa una última vez y se borra
const $ = (id) => document.getElementById(id);
let conversations = [];

// aws/panel.ps1 abre peatying.com/panel#t=CLAVE: la leemos y la quitamos de la dirección.
// (Lo que va tras # nunca se envía al servidor ni queda en sus registros.)
// Con "&movil" (código QR de aws/panel.ps1 -Movil) la clave se queda en la dirección: en el iPhone, el icono
// de "Añadir a pantalla de inicio" guarda la dirección y no comparte datos con Safari.
function keyInAddress() {
  if (!location.hash.startsWith("#t=")) return null;
  return decodeURIComponent(location.hash.slice(3).split("&")[0]);
}
let startupKey = keyInAddress();
if (startupKey && !location.hash.includes("&movil")) history.replaceState(null, "", location.pathname);
try {
  startupKey = startupKey || localStorage.getItem(OLD_TOKEN_KEY);
  localStorage.removeItem(OLD_TOKEN_KEY);
} catch (e) { /* nada */ }

async function api(path) {
  const response = await fetch(path);
  if (response.status === 401 || response.status === 404) throw new Error("clave");
  if (!response.ok) throw new Error("HTTP " + response.status);
  return response.json();
}

async function openSession(key) {
  const response = await fetch("/api/admin/session", { method: "POST", headers: { Authorization: `Bearer ${key}` } });
  if (response.status === 401 || response.status === 404) throw new Error("clave");
  if (response.status === 429) throw new Error("intentos");
  if (!response.ok) throw new Error("HTTP " + response.status);
}

async function show() {
  const [summary, list] = await Promise.all([
    api("/api/admin/summary"),
    api(`/api/admin/conversations?internas=${$("internal").checked}`),
  ]);
  $("login").hidden = true;
  $("content").hidden = false;
  renderSummary(summary);
  conversations = list;
  renderConversations();
}

// Primero con la sesión que ya haya; sin ella, se abre una si hay clave a mano. Así, abrir el panel muchas veces
// (el icono del móvil lleva la clave en la dirección) no abre una sesión nueva cada vez.
async function load() {
  try {
    await show();
  } catch (e) {
    if (e.message !== "clave") return alert("No se pudieron cargar los datos: " + e.message);
    const key = startupKey || keyInAddress();
    startupKey = null;
    if (!key) return showLogin();
    await enter(key);
  }
}

async function enter(key) {
  try {
    await openSession(key);
    await show();
  } catch (e) {
    if (e.message === "clave") showLogin("La clave no es correcta.");
    else if (e.message === "intentos") showLogin("Demasiados intentos. Prueba dentro de una hora.");
    else alert("No se pudieron cargar los datos: " + e.message);
  }
}

function showLogin(error) {
  $("content").hidden = true;
  $("login").hidden = false;
  $("login-error").hidden = !error;
  $("login-error").textContent = error || "";
  $("token").focus();
}

$("login").addEventListener("submit", (event) => {
  event.preventDefault();
  const key = $("token").value.trim();
  $("token").value = "";
  enter(key);
});
$("logout").addEventListener("click", async () => {
  // Para no salir sin querer: después habría que volver a abrirlo con aws/panel.ps1.
  if (!confirm("¿Salir del panel? Se cerrará la sesión de este navegador.")) return;
  try {
    const response = await fetch("/api/admin/session", { method: "DELETE" });
    if (!response.ok) throw new Error("HTTP " + response.status);
    showLogin();
  } catch (e) {
    alert("No se pudo cerrar la sesión: " + e.message);
  }
});
$("reload").addEventListener("click", load);
$("internal").addEventListener("change", load);
$("search").addEventListener("input", renderConversations);
$("only-down").addEventListener("change", renderConversations);

// --- Resumen ------------------------------------------------------------------------

const num = (n) => Number(n || 0).toLocaleString("es-ES");

function renderSummary({ visits, daily, referrers, locations, votes }) {
  const last30 = (rows) => rows.filter((r) => new Date(r.day) > Date.now() - 30 * 864e5);
  const sum = (rows, key) => rows.reduce((total, r) => total + Number(r[key] || 0), 0);
  const visitors = sum(last30(visits), "visitors");
  const kpis = [
    ["Visitantes", num(sum(last30(visits), "visitors"))],
    ["Conversaciones", num(sum(last30(daily), "conversations"))],
    ["Preguntas", num(sum(last30(daily), "questions"))],
    ["Coste IA", sum(last30(daily), "cost").toLocaleString("es-ES", { style: "currency", currency: "USD" })],
    ["Votos 👍 / 👎", `${num(votes.up)} / ${num(votes.down)}`],
    // De la suma de los días, no la media de porcentajes: un día de 5 visitantes no pesa como uno de 200.
    ["Visitantes que preguntan",visitors ? `${Math.round(100 * sum(last30(visits), "askers") / visitors)} %` : "–"],
  ];
  $("kpis").replaceChildren(...kpis.map(([label, value]) => {
    const box = document.createElement("div");
    const v = document.createElement("strong");
    v.textContent = value;
    const l = document.createElement("span");
    l.textContent = label;
    box.append(v, l);
    return box;
  }));
  renderChart(last30(visits));
  fillTable($("referrers"), referrers.map((r) => [r.origin, r.visitors]));
  fillTable($("locations"), locations.map((r) => [r.country, r.visitors]));
  // Con el desplegable cerrado, los tres primeros se ven igualmente en la cabecera.
  $("referrers-top").textContent = top3(referrers.map((r) => [r.origin, r.visitors]));
  $("locations-top").textContent = top3(locations.map((r) => [r.country, r.visitors]));
}

const top3 = (rows) => rows.slice(0, 3).map(([name, n]) => `${name} ${num(n)}`).join(" · ");

/** Barras por día hechas a mano en SVG (la web no carga librerías de fuera). */
function renderChart(rows) {
  const box = $("chart");
  if (rows.length === 0) { box.textContent = "Todavía no hay visitas."; return; }
  const W = 640, H = 180, pad = 8, top = 42;    // top: sitio para las dos cifras encima de la barra más alta
  const max = Math.max(...rows.map((r) => Number(r.visitors)), 1);
  // Con pocos días, barras de 28 px como mucho (si no, un solo día ocuparía todo el ancho).
  // (24 de margen a la derecha: la fecha de la última barra es más ancha que la barra).
  const step = Math.min((W - pad - 24) / rows.length, 40), bar = Math.max(Math.min(step * 0.7, 28), 2);
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${top + H + 24}`);
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "Visitantes por día");
  const rect = (x, y, w, h, cls, title) => {
    const r = document.createElementNS(svg.namespaceURI, "rect");
    Object.entries({ x, y, width: w, height: Math.max(h, 0) }).forEach(([k, v]) => r.setAttribute(k, v));
    r.setAttribute("class", cls);
    const t = document.createElementNS(svg.namespaceURI, "title");
    t.textContent = title;
    r.appendChild(t);
    svg.appendChild(r);
  };
  rows.forEach((r, i) => {
    const x = pad + i * step, hv = (r.visitors / max) * H, ha = (r.askers / max) * H;
    rect(x, top + H - hv, bar, hv, "bar-visits", `${r.day}: ${r.visitors} visitantes`);
    rect(x, top + H - ha, bar, ha, "bar-askers", `${r.day}: ${r.askers} preguntan`);
  });
  const label = (x, y, text, cls) => {
    const t = document.createElementNS(svg.namespaceURI, "text");
    t.setAttribute("x", x); t.setAttribute("y", y); t.setAttribute("text-anchor", "middle");
    if (cls) t.setAttribute("class", cls);
    t.textContent = text;
    svg.appendChild(t);
  };
  // La fecha debajo de su barra: todas si caben (hasta 14 días); si no, una de cada pocas.
  // Encima de cada barra (las mismas que llevan fecha), cuántos visitantes hubo y qué parte preguntó.
  const every = Math.ceil(rows.length / 14);
  rows.forEach((r, i) => {
    if (i % every !== 0 && i !== rows.length - 1) return;
    const x = pad + i * step + bar / 2, barTop = top + H - (r.visitors / max) * H;
    label(x, top + H + 19, `${r.day.slice(8)}/${r.day.slice(5, 7)}`);
    if (Number(r.visitors) > 0) {
      label(x, barTop - 23, String(r.visitors), "chart-count");
      label(x, barTop - 5, `${Math.round(100 * r.askers / r.visitors)}%`);
    }
  });
  box.replaceChildren(svg);
}

function fillTable(table, rows) {
  table.replaceChildren(...(rows.length ? rows : [["Todavía nada", ""]]).map(([name, n]) => {
    const tr = document.createElement("tr");
    const a = document.createElement("td");
    a.textContent = name;
    const b = document.createElement("td");
    b.textContent = n;
    tr.append(a, b);
    return tr;
  }));
}

// --- Conversaciones ----------------------------------------------------------------

const when = new Intl.DateTimeFormat("es-ES", {
  timeZone: "Europe/Madrid", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
});

function renderConversations() {
  const query = $("search").value.trim().toLowerCase();
  const onlyDown = $("only-down").checked;
  const shown = conversations.filter((c) =>
    (!onlyDown || c.messages.some((m) => m.feedback === -1))
    && (!query || c.messages.some((m) => m.content.toLowerCase().includes(query))));
  $("count").textContent = `${shown.length} de ${conversations.length} conversaciones`;
  $("conversations").replaceChildren(...shown.map(conversationCard));
}

function conversationCard(c) {
  const details = document.createElement("details");
  details.className = "conv" + (c.internal ? " conv-internal" : "");
  const summary = document.createElement("summary");
  const questions = c.messages.filter((m) => m.role === "user");
  const title = document.createElement("strong");
  title.textContent = questions[0]?.content.slice(0, 140) ?? "(sin preguntas)";
  const meta = document.createElement("span");
  const place = c.country || "país desconocido";
  const down = c.messages.filter((m) => m.feedback === -1).length;
  const up = c.messages.filter((m) => m.feedback === 1).length;
  // La hora de la última pregunta (las conversaciones vienen ordenadas por ella); si empezó antes, también el inicio.
  const last = when.format(new Date(c.last_at || c.started));
  const started = when.format(new Date(c.started));
  meta.textContent = [last === started ? last : `${last} (empezó ${started})`, place, c.referrer || "directo",
    `${questions.length} pregunta${questions.length === 1 ? "" : "s"}`,
    up ? `${up} 👍` : "", down ? `${down} 👎` : "", c.document_name ? `📄 ${c.document_name}` : "",
    c.internal ? "nuestra" : ""].filter(Boolean).join(" · ");
  summary.append(title, meta);
  details.appendChild(summary);

  // Los mensajes se crean al abrirla: con cientos de conversaciones, la página va más ligera.
  details.addEventListener("toggle", () => {
    if (!details.open || details.querySelector(".msg")) return;
    if (c.document_text) {
      const doc = document.createElement("div");
      doc.className = "msg msg-doc";
      const head = document.createElement("small");
      head.textContent = `Documento adjunto · ${c.document_name || "sin nombre"} (texto extraído, sin datos personales)`;
      const text = document.createElement("p");
      text.textContent = c.document_text;
      doc.append(head, text);
      details.appendChild(doc);
    }
    for (const m of c.messages) {
      const div = document.createElement("div");
      div.className = `msg msg-${m.role}`;
      const head = document.createElement("small");
      head.textContent = (m.role === "user" ? "Pregunta" : "Respuesta") + " · " + when.format(new Date(m.at))
        + (m.latency_ms ? ` · ${(m.latency_ms / 1000).toFixed(1)} s` : "")
        + (m.feedback === 1 ? " · 👍" : m.feedback === -1 ? " · 👎" : "");
      const text = document.createElement("p");
      text.textContent = m.content.replace(/\*\*/g, "");   // sin los ** de la negrita: se lee mejor
      div.append(head, text);
      if (m.feedback_reason) {
        const why = document.createElement("p");
        why.className = "msg-reason";
        why.textContent = "Qué ha fallado, según quien votó: " + m.feedback_reason;
        div.appendChild(why);
      }
      details.appendChild(div);
    }
  }, { once: false });
  return details;
}

load();
