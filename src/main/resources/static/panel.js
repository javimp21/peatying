// Panel privado: métricas y conversaciones. Lee /api/admin/* con la clave del panel.
// Todo lo que escribieron los visitantes se pinta con textContent (como texto), nunca como HTML.

const TOKEN_KEY = "rp-panel-token";
const $ = (id) => document.getElementById(id);
let conversations = [];

// aws/panel.ps1 abre peatying.com/panel#t=CLAVE: la guardamos y la quitamos de la dirección.
// (Lo que va tras # nunca se envía al servidor ni queda en sus registros.)
// Con "&movil" (código QR de aws/panel.ps1 -Movil) la clave se queda en la dirección: en el iPhone, el icono
// de "Añadir a pantalla de inicio" guarda la dirección y no comparte datos con Safari.
if (location.hash.startsWith("#t=")) {
  const [value, ...flags] = location.hash.slice(3).split("&");
  try { localStorage.setItem(TOKEN_KEY, decodeURIComponent(value)); } catch (e) { /* nada */ }
  if (!flags.includes("movil")) history.replaceState(null, "", location.pathname);
}
const token = () => { try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; } };

async function api(path) {
  const response = await fetch(path, { headers: { Authorization: `Bearer ${token()}` } });
  if (response.status === 401 || response.status === 404) throw new Error("clave");
  if (!response.ok) throw new Error("HTTP " + response.status);
  return response.json();
}

async function load() {
  if (!token()) return showLogin();
  try {
    const [summary, list] = await Promise.all([
      api("/api/admin/summary"),
      api(`/api/admin/conversations?internas=${$("internal").checked}`),
    ]);
    $("login").hidden = true;
    $("content").hidden = false;
    renderSummary(summary);
    conversations = list;
    renderConversations();
  } catch (e) {
    if (e.message === "clave") {
      try { localStorage.removeItem(TOKEN_KEY); } catch (err) { /* nada */ }
      showLogin("La clave no es correcta.");
    } else {
      alert("No se pudieron cargar los datos: " + e.message);
    }
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
  try { localStorage.setItem(TOKEN_KEY, $("token").value.trim()); } catch (e) { /* nada */ }
  $("token").value = "";
  load();
});
$("logout").addEventListener("click", () => {
  // Para no salir sin querer: después habría que volver a abrirlo con aws/panel.ps1.
  if (!confirm("¿Salir del panel? Este navegador olvidará la clave.")) return;
  try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* nada */ }
  showLogin();
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
  const kpis = [
    ["Visitantes", num(sum(last30(visits), "visitors"))],
    ["Conversaciones", num(sum(last30(daily), "conversations"))],
    ["Preguntas", num(sum(last30(daily), "questions"))],
    ["Coste IA", sum(last30(daily), "cost").toLocaleString("es-ES", { style: "currency", currency: "USD" })],
    ["Votos 👍 / 👎", `${num(votes.up)} / ${num(votes.down)}`],
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
  fillTable($("locations"), locations.map((r) => [r.city ? `${r.city} (${r.country})` : r.country, r.visitors]));
}

/** Barras por día hechas a mano en SVG (la web no carga librerías de fuera). */
function renderChart(rows) {
  const box = $("chart");
  if (rows.length === 0) { box.textContent = "Todavía no hay visitas."; return; }
  const W = 640, H = 180, pad = 24;
  const max = Math.max(...rows.map((r) => Number(r.visitors)), 1);
  // Con pocos días, barras de 28 px como mucho (si no, un solo día ocuparía todo el ancho).
  const step = Math.min((W - pad) / rows.length, 40), bar = Math.max(Math.min(step * 0.7, 28), 2);
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H + 20}`);
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
    rect(x, H - hv, bar, hv, "bar-visits", `${r.day}: ${r.visitors} visitantes`);
    rect(x, H - ha, bar, ha, "bar-askers", `${r.day}: ${r.askers} preguntan`);
  });
  const label = (x, y, text, anchor = "start") => {
    const t = document.createElementNS(svg.namespaceURI, "text");
    t.setAttribute("x", x); t.setAttribute("y", y); t.setAttribute("text-anchor", anchor);
    t.textContent = text;
    svg.appendChild(t);
  };
  label(0, 12, String(max));
  // La fecha debajo de su barra: todas si caben (hasta 14 días); si no, una de cada pocas.
  const every = Math.ceil(rows.length / 14);
  rows.forEach((r, i) => {
    if (i % every === 0 || i === rows.length - 1) label(pad + i * step + bar / 2, H + 16, `${r.day.slice(8)}/${r.day.slice(5, 7)}`, "middle");
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
  const place = c.city ? `${c.city}, ${c.country}` : (c.country || "ubicación desconocida");
  const down = c.messages.filter((m) => m.feedback === -1).length;
  const up = c.messages.filter((m) => m.feedback === 1).length;
  meta.textContent = [when.format(new Date(c.started)), place, c.referrer || "directo",
    `${questions.length} pregunta${questions.length === 1 ? "" : "s"}`,
    up ? `${up} 👍` : "", down ? `${down} 👎` : "", c.internal ? "nuestra" : ""].filter(Boolean).join(" · ");
  summary.append(title, meta);
  details.appendChild(summary);

  // Los mensajes se crean al abrirla: con cientos de conversaciones, la página va más ligera.
  details.addEventListener("toggle", () => {
    if (!details.open || details.querySelector(".msg")) return;
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
