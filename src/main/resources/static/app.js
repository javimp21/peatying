// Lógica de la página del chat. JavaScript sin frameworks: solo el navegador.

const EXAMPLES = [
  "¿Por qué recomendaba el azúcar?",
  "¿Qué opinaba de los aceites vegetales?",
  "¿Qué pensaba de la serotonina?",
  "¿Por qué el CO2 es protector?",
  "¿Qué decía sobre la gelatina?",
  "¿El café es bueno o malo?",
  "¿Qué pensaba del colesterol?",
  "¿Qué opinaba de la ciencia académica?",
  "¿Qué es el estrés para Peat?",
  "¿Qué decía sobre el hierro?",
];

// Frases que van rotando mientras el bot busca y piensa (sin puntos: los tres puntos los pinta la animación).
const LOADING_PHRASES = [
  "Peatying",
  "Optimizing energy production",
  "Squeezing the oranges",
];
const LOADING_CYCLE_MS = 2400; // solo si las animaciones están desactivadas; si no, manda el CSS (ver showLoading)

const FIRST_PLACEHOLDER = "Prueba: «¿Qué pensaba del azúcar?»";
const NEXT_PLACEHOLDER = "Escribe otra pregunta…";
const STORAGE_KEY = "rp-chat-v1";

const form = document.getElementById("ask-form");
const input = document.getElementById("question");
const sendButton = document.getElementById("send");
const errorBox = document.getElementById("error");
const examplesBox = document.getElementById("examples");
const conversation = document.getElementById("conversation");
const newChatButton = document.getElementById("new-chat");
const downloadButton = document.getElementById("download");

// El estado de la conversación: la lista de mensajes. La pantalla se dibuja a partir de aquí,
// y es lo que guardamos en el navegador y lo que se descarga.
//   { role: "user", text }   |   { role: "bot", text, sources: [{number, title, url, text}] }
let messages = [];

// --- Arranque ----------------------------------------------------------------
showExamples();
restoreConversation();
input.focus();

// --- Estado vacío: 4 ejemplos al azar ---------------------------------------
function showExamples() {
  const shuffled = [...EXAMPLES].sort(() => Math.random() - 0.5).slice(0, 4);
  for (const text of shuffled) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = text;
    button.addEventListener("click", () => ask(text));
    examplesBox.appendChild(button);
  }
}

/** Cambia la página entre "portada" (sin preguntas) y "conversación". */
function setChatting(chatting) {
  document.body.classList.toggle("chatting", chatting); // el CSS encoge la portada y fija la caja abajo
  examplesBox.hidden = chatting;
  newChatButton.hidden = !chatting;
  downloadButton.hidden = !chatting;
  input.placeholder = chatting ? NEXT_PLACEHOLDER : FIRST_PLACEHOLDER;
}

// --- Enviar una pregunta ----------------------------------------------------
form.addEventListener("submit", (event) => {
  event.preventDefault(); // sin esto, el formulario recargaría la página
  ask(input.value);
});

// Enter envía; Mayúsculas+Enter hace un salto de línea.
input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    form.requestSubmit();
  }
});

// La caja crece con el texto (hasta el máximo que marca el CSS) en vez de mostrar una barra de scroll.
input.addEventListener("input", autoGrow);
function autoGrow() {
  input.style.height = "auto";
  input.style.height = input.scrollHeight + "px";
}

async function ask(question) {
  question = question.trim();
  if (!question || sendButton.disabled) return;

  hideError();
  setChatting(true);
  const questionDiv = addMessage({ role: "user", text: question });
  input.value = "";
  autoGrow();
  setBusy(true);
  const loading = showLoading();

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });
    const data = await response.json();

    if (!response.ok) {
      // 400, 429 o 503: el servidor nos manda un mensaje pensado para el usuario.
      undoQuestion(questionDiv, question, data.error || "Algo ha fallado. Inténtalo de nuevo.");
      return;
    }
    addMessage({ role: "bot", text: data.answer, sources: data.sources });
  } catch (e) {
    // Sin conexión, o el servidor no responde.
    undoQuestion(questionDiv, question, "No hay conexión con el servidor. Inténtalo de nuevo.");
  } finally {
    loading.stop();
    setBusy(false);
    input.focus();
  }
}

/** Si la pregunta falla, la quitamos de la conversación y se la devolvemos a la caja para que no la pierda. */
function undoQuestion(questionDiv, question, errorMessage) {
  messages.pop();
  questionDiv.remove();                 // por referencia: el último elemento podría ser el "cargando"
  saveConversation();
  if (messages.length === 0) setChatting(false);
  input.value = question;
  autoGrow();
  showError(errorMessage);
}

// --- Estado "cargando": frases que van rotando ------------------------------
function showLoading() {
  const div = document.createElement("div");
  div.className = "message bot loading";
  div.setAttribute("aria-hidden", "true");      // que el lector de pantalla no lea cada frase
  conversation.setAttribute("aria-busy", "true"); // en su lugar, avisa de que está ocupado
  conversation.appendChild(div);

  const phrases = [...LOADING_PHRASES].sort(() => Math.random() - 0.5);
  let i = 0;
  const showNext = () => {
    // Estructura: <span class="phrase">Peatying</span><span class="dots"><i>.</i><i>.</i><i>.</i></span>
    // Al reemplazar los elementos, las animaciones CSS empiezan de cero con cada frase nueva.
    const phrase = document.createElement("span");
    phrase.className = "phrase";
    phrase.textContent = phrases[i++ % phrases.length];
    const dots = document.createElement("span");
    dots.className = "dots";
    for (let d = 0; d < 3; d++) {
      const dot = document.createElement("i");
      dot.textContent = ".";
      dots.appendChild(dot);
    }
    div.replaceChildren(phrase, dots);
    // Cambiamos de frase justo cuando termina un ciclo completo de la animación (brillo + saltos), para
    // que nunca se corte a medias. Lo avisa el propio navegador con el evento "animationiteration".
    // Escuchamos el PRIMER punto: no tiene retraso, así que su ciclo coincide con el del brillo.
    dots.firstChild.addEventListener("animationiteration", showNext, { once: true });
  };
  showNext();
  div.scrollIntoView({ behavior: "smooth", block: "end" });

  // Si el usuario tiene desactivadas las animaciones, ese evento no llega nunca: usamos un temporizador.
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const timer = reducedMotion ? setInterval(showNext, LOADING_CYCLE_MS) : null;

  return {
    stop() {
      clearInterval(timer);
      div.remove();
      conversation.removeAttribute("aria-busy");
    },
  };
}

// --- Mensajes: estado + pantalla --------------------------------------------
function addMessage(message) {
  messages.push(message);
  saveConversation();
  return render(message, true);
}

function render(message, scroll) {
  const div = message.role === "user" ? renderUser(message.text) : renderAnswer(message.text, message.sources);
  conversation.appendChild(div);
  if (scroll) div.scrollIntoView({ behavior: "smooth", block: message.role === "user" ? "end" : "start" });
  return div;
}

function renderUser(text) {
  const div = document.createElement("div");
  div.className = "message user";
  div.textContent = text;               // textContent nunca interpreta HTML: seguro
  return div;
}

function renderAnswer(answer, sources) {
  const div = document.createElement("div");
  div.className = "message bot";

  const byNumber = new Map(sources.map((s) => [s.number, s]));

  // Cada párrafo de la respuesta (separados por línea en blanco) es un <p>.
  for (const paragraph of answer.split(/\n\s*\n/)) {
    const p = document.createElement("p");
    p.innerHTML = formatParagraph(paragraph, byNumber);
    div.appendChild(p);
  }

  // Lista de fuentes al final, con enlace al artículo (un artículo solo una vez).
  if (sources.length > 0) {
    const list = document.createElement("p");
    list.className = "sources";
    list.append("Fuentes: ");
    const seen = new Set();
    for (const s of sources) {
      if (seen.has(s.url)) continue;
      seen.add(s.url);
      if (seen.size > 1) list.append(" · ");
      const a = document.createElement("a");
      a.href = s.url;
      a.target = "_blank";
      a.rel = "noopener";
      a.textContent = s.title;
      list.appendChild(a);
    }
    div.appendChild(list);
  }

  // Un solo "escuchador" para todos los botones de cita del mensaje (delegación de eventos).
  div.addEventListener("click", (event) => {
    const button = event.target.closest(".cite");
    if (button) toggleSource(div, button, byNumber.get(Number(button.dataset.n)));
  });
  return div;
}

/**
 * Convierte un párrafo en HTML seguro:
 *  1. Escapamos TODO (<, >, &...) para que el texto del modelo no pueda meter HTML en la página.
 *  2. Sobre el texto ya escapado añadimos solo lo que nosotros controlamos: **negrita** y [n].
 */
function formatParagraph(text, byNumber) {
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/\[(\d+)\]/g, (match, n) =>
      byNumber.has(Number(n))
        ? `<button type="button" class="cite" data-n="${n}" aria-expanded="false" aria-label="Ver el texto original de la fuente ${n}">${n}</button>`
        : match);
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

// --- Panel con el texto original de una cita --------------------------------
function toggleSource(messageDiv, button, source) {
  const open = messageDiv.querySelector(".source-panel");
  const wasThisOne = open && open.dataset.n === button.dataset.n;

  // Solo un panel abierto a la vez por mensaje.
  if (open) open.remove();
  messageDiv.querySelectorAll(".cite").forEach((b) => b.setAttribute("aria-expanded", "false"));
  if (wasThisOne || !source) return;

  const panel = document.createElement("div");
  panel.className = "source-panel";
  panel.dataset.n = button.dataset.n;

  const title = document.createElement("h3");
  title.textContent = `[${source.number}] ${source.title}`;
  const text = document.createElement("p");
  text.textContent = source.text;       // el texto original de Peat, en inglés
  const link = document.createElement("a");
  link.href = source.url;
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = "Leer el artículo completo en raypeat.com →";

  panel.append(title, text, link);
  button.closest("p").after(panel);     // justo después del párrafo donde está la cita
  messageDiv.querySelectorAll(`.cite[data-n="${source.number}"]`)
    .forEach((b) => b.setAttribute("aria-expanded", "true"));
}

// --- Guardar la conversación en el navegador ---------------------------------
// localStorage guarda texto en el navegador de cada persona: no viaja a nuestro servidor.
// Puede fallar (modo privado, almacenamiento lleno...), así que siempre va dentro de try/catch:
// si falla, la página sigue funcionando, solo que sin recordar la conversación.
function saveConversation() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(messages));
  } catch (e) { /* sin almacenamiento: no pasa nada */ }
}

function restoreConversation() {
  let saved = [];
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");
  } catch (e) { /* datos corruptos o sin acceso: empezamos de cero */ }

  messages = Array.isArray(saved) ? saved : [];
  messages.forEach((m) => render(m, false));
  setChatting(messages.length > 0);
  if (messages.length > 0) conversation.lastElementChild.scrollIntoView({ block: "start" });
}

newChatButton.addEventListener("click", () => {
  messages = [];
  saveConversation();
  conversation.replaceChildren();       // vacía la conversación en pantalla
  input.value = "";
  autoGrow();
  hideError();
  setChatting(false);
  window.scrollTo({ top: 0, behavior: "smooth" });
  input.focus();
});

// --- Descargar la conversación como Markdown --------------------------------
downloadButton.addEventListener("click", () => {
  const lines = ["# Conversación · Ray Peat en español", ""];
  for (const m of messages) {
    if (m.role === "user") {
      lines.push(`## ${m.text}`, "");
    } else {
      lines.push(m.text, "");
      if (m.sources.length > 0) {
        lines.push("Fuentes:");
        m.sources.forEach((s) => lines.push(`- [${s.number}] ${s.title}: ${s.url}`));
        lines.push("");
      }
    }
  }
  lines.push("---", "Respuestas generadas por IA a partir de los artículos de raypeat.com. No son consejo médico.");

  // Creamos el archivo en memoria (Blob) y simulamos un clic en un enlace de descarga.
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `ray-peat-chat-${new Date().toISOString().slice(0, 10)}.md`;
  link.click();
  URL.revokeObjectURL(link.href);       // liberamos la memoria del archivo temporal
});

// --- Utilidades -------------------------------------------------------------
function setBusy(busy) {
  sendButton.disabled = busy;
  input.disabled = busy;
}

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = false;
}

function hideError() {
  errorBox.hidden = true;
}
