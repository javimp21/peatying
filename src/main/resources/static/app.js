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
const FIRST_PHRASE_CYCLES = 2;  // veces que se repite la primera frase antes de pasar a la segunda
const LOADING_CYCLE_MS = 2400;  // solo si las animaciones están desactivadas; si no, manda el CSS (ver showLoading)

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
// El id de la conversación en el servidor (null hasta la primera respuesta). Con él, el bot recuerda
// lo anterior y entiende preguntas como "¿y por qué?".
let conversationId = null;

// En pantallas táctiles (móvil, tablet) poner el foco en la caja abre el teclado, que tapa media
// pantalla. Ahí solo lo abrimos cuando la persona pulsa la caja; en ordenador, el foco ayuda.
const isTouch = window.matchMedia("(pointer: coarse)").matches;
const focusInputIfDesktop = () => { if (!isTouch) input.focus(); };

// Móvil: al pulsar fuera de la caja, se cierra el teclado (quitando el foco de la caja).
document.addEventListener("pointerdown", (event) => {
  if (isTouch && document.activeElement === input && !event.target.closest("#ask-form")) input.blur();
});

// --- Arranque ----------------------------------------------------------------
showExamples();
restoreConversation();
focusInputIfDesktop();

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
  const firstOfConversation = messages.length === 0;   // las naranjas solo se van rodando en la primera
  setChatting(true);
  const questionDiv = addMessage({ role: "user", text: question });
  input.value = "";
  autoGrow();
  if (isTouch) input.blur();            // en móvil, cerramos el teclado para que se vea la respuesta
  setBusy(true);
  let loading = showLoading();
  let live = null;                      // el mensaje que se va escribiendo en directo
  let finished = false;

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question, conversationId }),
    });

    if (!response.ok) {
      // 400 o 429: el servidor manda un JSON normal con un mensaje pensado para el usuario.
      const data = await response.json().catch(() => ({}));
      undoQuestion(questionDiv, question, data.error || "Algo ha fallado. Inténtalo de nuevo.");
      return;
    }

    // Leemos el stream de eventos según va llegando.
    for await (const { event, data } of readEvents(response)) {
      if (event === "delta") {
        if (!live) {                    // primer trozo: quitamos el "cargando" y empezamos a escribir
          // Mientras las naranjas se van rodando, el texto que llega se acumula; luego sale sin perder nada.
          await loading.stop({ roll: firstOfConversation });
          loading = null;
          live = startLiveMessage();
        }
        live.append(data.text);
      } else if (event === "discard") {
        // Lo escrito no era la respuesta (Claude va a buscar): lo borramos y volvemos al "cargando".
        if (live) {
          live.remove();
          live = null;
          loading = showLoading();
        }
      } else if (event === "done") {
        // Esperamos a que la máquina de escribir termine de mostrar el texto y entonces la versión final,
        // con las citas pulsables, ocupa su lugar. Tiene el mismo formato, así que no se nota el cambio.
        conversationId = data.conversationId;     // se guarda junto con los mensajes (addMessage)
        const message = {
          role: "bot", text: data.answer, sources: data.sources,
          messageId: data.messageId, conversationId: data.conversationId, vote: null,   // para el 👍/👎
        };
        if (loading) {                  // respuesta sin streaming previo: también despedimos a las naranjas
          await loading.stop({ roll: firstOfConversation });
          loading = null;
        }
        if (live) {
          await live.finish();
          live.element.replaceWith(addMessage(message, { animate: false }));
        } else {
          addMessage(message);
        }
        finished = true;
      } else if (event === "error") {
        live?.remove();
        undoQuestion(questionDiv, question, data.error);
        finished = true;
      }
    }
    if (!finished) {
      // La conexión se cortó sin "done" ni "error".
      live?.remove();
      undoQuestion(questionDiv, question, "Se cortó la conexión. Inténtalo de nuevo.");
    }
  } catch (e) {
    // Sin conexión, o el servidor no responde.
    live?.remove();
    undoQuestion(questionDiv, question, "No hay conexión con el servidor. Inténtalo de nuevo.");
  } finally {
    loading?.stop();
    setBusy(false);
    focusInputIfDesktop();
  }
}

/**
 * Lee una respuesta SSE (Server-Sent Events) y va devolviendo sus eventos uno a uno.
 * El formato es texto: cada evento son líneas "event: nombre" y "data: {json}", y entre eventos
 * hay una línea en blanco. Los datos llegan en trozos que pueden cortar un evento por la mitad,
 * así que guardamos lo que sobra (buffer) hasta que llega el resto.
 * (El navegador trae EventSource para esto, pero solo sirve con GET, y nosotros enviamos un POST.)
 */
async function* readEvents(response) {
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += value.replace(/\r\n/g, "\n");
    let end;
    while ((end = buffer.indexOf("\n\n")) !== -1) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = "message";
      const dataLines = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
      }
      if (dataLines.length > 0) yield { event, data: JSON.parse(dataLines.join("\n")) };
    }
  }
}

/**
 * Un mensaje del bot que se va escribiendo en directo, como una máquina de escribir.
 *
 * El texto llega del servidor a trompicones (a veces una palabra, a veces media frase). En vez de
 * pintarlo tal cual llega, lo guardamos en "target" y en cada fotograma de la pantalla
 * (requestAnimationFrame, ~60 veces por segundo) mostramos unas pocas letras más. Si se acumula mucho
 * texto pendiente, avanzamos más rápido para no quedarnos atrás.
 */
function startLiveMessage() {
  const div = document.createElement("div");
  div.className = "message bot live";
  div.setAttribute("aria-hidden", "true");  // el lector de pantalla leerá la versión final, completa
  conversation.appendChild(div);

  let target = "";      // todo el texto recibido
  let shown = 0;        // cuántas letras se ven ya
  let running = false;
  let onCaughtUp = null;

  const frame = () => {
    const pending = target.length - shown;
    if (pending > 0) {
      // Mínimo 1 letra por fotograma (~60/s); con mucho pendiente, lo que haga falta para alcanzarlo en ~1/3 s.
      shown += Math.max(1, Math.ceil(pending / 20));
      div.innerHTML = renderMarkdown(target.slice(0, shown), null, true);
      followIfAtBottom();
      requestAnimationFrame(frame);
    } else {
      running = false;
      onCaughtUp?.();
    }
  };

  return {
    element: div,
    append(delta) {
      target += delta;
      if (!running) {
        running = true;
        requestAnimationFrame(frame);
      }
    },
    /** Una promesa que se cumple cuando ya se ve todo el texto recibido. */
    finish() {
      return new Promise((resolve) => {
        if (!running) resolve();
        else onCaughtUp = resolve;
      });
    },
    remove() {
      div.remove();
    },
  };
}

/** Seguimos el texto hacia abajo solo si el usuario no ha subido a leer otra cosa. */
function followIfAtBottom() {
  const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 160;
  if (nearBottom) window.scrollTo({ top: document.body.scrollHeight });
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

  // Como mucho DOS frases por respuesta, al azar. La primera se repite unas veces antes de pasar
  // a la segunda (que ya se queda): si la respuesta llega rápido, solo se ve una.
  const [first, second] = [...LOADING_PHRASES].sort(() => Math.random() - 0.5);

  const show = (text) => {
    // Estructura: <span class="phrase">Peatying</span><span class="dots"><i class="orange"></i>×3</span>
    // Al reemplazar los elementos, las animaciones CSS empiezan de cero con cada frase nueva.
    const phrase = document.createElement("span");
    phrase.className = "phrase";
    phrase.textContent = text;
    const dots = document.createElement("span");
    dots.className = "dots";
    for (let d = 0; d < 3; d++) {
      const orange = document.createElement("i");
      orange.className = "orange";        // una mini naranja dibujada con CSS (ver styles.css)
      dots.appendChild(orange);
    }
    div.replaceChildren(phrase, dots);
    return dots;
  };

  let dots = show(first);
  div.scrollIntoView({ behavior: "smooth", block: "end" });

  // Cambiamos de frase justo cuando termina un ciclo completo de la animación (brillo + saltos), para
  // que nunca se corte a medias. Lo avisa el propio navegador con el evento "animationiteration".
  // Escuchamos la PRIMERA naranja: no tiene retraso, así que su ciclo coincide con el del brillo.
  let cycles = 0;
  const onCycle = () => {
    if (++cycles === FIRST_PHRASE_CYCLES && second) {
      dots.firstChild.removeEventListener("animationiteration", onCycle);
      dots = show(second);
    }
  };
  dots.firstChild.addEventListener("animationiteration", onCycle);

  // Si el usuario tiene desactivadas las animaciones, ese evento no llega nunca: usamos un temporizador.
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const timer = reducedMotion && second
    ? setTimeout(() => { dots = show(second); }, FIRST_PHRASE_CYCLES * LOADING_CYCLE_MS)
    : null;

  let stopped = false;
  return {
    /**
     * Quita el "cargando". Con roll: true, antes las naranjas se van rodando hacia la derecha.
     * Devuelve una promesa que se cumple cuando ya ha desaparecido. Llamarlo dos veces no hace nada.
     */
    stop({ roll = false } = {}) {
      if (stopped) return Promise.resolve();
      stopped = true;
      clearTimeout(timer);
      dots.firstChild?.removeEventListener("animationiteration", onCycle);
      const remove = () => {
        div.remove();
        conversation.removeAttribute("aria-busy");
      };
      if (!roll || reducedMotion) {
        remove();
        return Promise.resolve();
      }
      div.classList.add("rolling");
      return new Promise((resolve) => {
        const done = () => { remove(); resolve(); };
        // La primera naranja es la última en irse (la de delante sale antes): cuando termina ella, fuera.
        div.querySelector(".orange").addEventListener("animationend", done, { once: true });
        setTimeout(done, 1600);         // por si el evento no llegara nunca
      });
    },
  };
}

// --- Mensajes: estado + pantalla --------------------------------------------
function addMessage(message, { animate = true } = {}) {
  messages.push(message);
  saveConversation();
  const div = render(message, animate);
  // Si sustituye a un texto escrito en directo, no queremos que "salte" con la animación de entrada.
  if (!animate) div.classList.add("no-anim");
  return div;
}

function render(message, scroll) {
  const div = message.role === "user" ? renderUser(message.text) : renderAnswer(message);
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

function renderAnswer(message) {
  const { text: answer, sources } = message;
  const div = document.createElement("div");
  div.className = "message bot";

  const byNumber = new Map(sources.map((s) => [s.number, s]));

  const body = document.createElement("div");
  body.className = "answer-body";
  body.innerHTML = renderMarkdown(answer, byNumber, false);
  div.appendChild(body);

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

  // Respuestas guardadas de antes de existir los votos no tienen messageId: a esas no se puede votar.
  if (message.messageId) div.appendChild(feedbackRow(message));

  // Un solo "escuchador" para todos los botones de cita del mensaje (delegación de eventos).
  div.addEventListener("click", (event) => {
    const button = event.target.closest(".cite");
    if (button) toggleSource(div, button, byNumber.get(Number(button.dataset.n)));
  });
  return div;
}

// --- 👍/👎 ----------------------------------------------------------------------
// Icono de pulgar (de la colección libre Lucide, licencia ISC). El de "no" es el mismo girado con CSS.
const THUMB_SVG = `<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none"
  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M7 10v12"/><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"/></svg>`;

function feedbackRow(message) {
  const row = document.createElement("div");
  row.className = "feedback";
  const label = document.createElement("span");
  const buttons = [
    { value: 1, name: "Me ha servido" },
    { value: -1, name: "No me ha servido" },
  ].map(({ value, name }) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = value === 1 ? "vote up" : "vote down";
    button.setAttribute("aria-label", name);
    button.title = name;
    button.innerHTML = THUMB_SVG;
    // Pulsar el voto que ya tenías lo quita (vuelve a null).
    button.addEventListener("click", () => vote(message, message.vote === value ? null : value, paint));
    return button;
  });

  const paint = () => {
    label.textContent = message.vote ? "¡Gracias!" : "¿Te ha servido?";
    buttons[0].setAttribute("aria-pressed", String(message.vote === 1));
    buttons[1].setAttribute("aria-pressed", String(message.vote === -1));
  };
  paint();
  row.append(label, ...buttons);
  return row;
}

/**
 * Cambia el voto en pantalla al momento ("optimista") y lo manda al servidor.
 * Si el servidor falla, lo devolvemos a como estaba, para no mostrar un voto que no se guardó.
 */
async function vote(message, value, paint) {
  const previous = message.vote;
  message.vote = value;
  paint();
  saveConversation();
  try {
    const response = await fetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conversationId: message.conversationId, messageId: message.messageId, value }),
    });
    if (!response.ok) throw new Error("HTTP " + response.status);
  } catch (e) {
    message.vote = previous;
    paint();
    saveConversation();
  }
}

const CARET_MARK = ""; // un carácter "privado" que nunca aparece en un texto normal

/**
 * Convierte el Markdown sencillo que escribe el bot en HTML: párrafos, listas (1. / -) y **negrita**.
 * Se usa igual mientras se escribe (live) y al final, para que el formato no "salte" al terminar.
 *
 * Funciona línea a línea: las líneas que empiezan por "1." o "-" son puntos de una lista, las demás
 * forman párrafos, y una línea en blanco cierra el párrafo.
 */
function renderMarkdown(text, byNumber, live) {
  if (live) {
    // Mientras se escribe, una negrita puede estar a medias ("**conce"): la cerramos provisionalmente
    // para no ver los asteriscos. Y marcamos dónde va el punto que "respira" (el final del texto).
    if ((text.match(/\*\*/g) || []).length % 2 === 1) text += "**";
    text += CARET_MARK;
  }

  let html = "";
  let list = null;              // "ol", "ul" o null
  let paragraph = [];
  const flushParagraph = () => {
    if (paragraph.length) html += `<p>${formatInline(paragraph.join(" "), byNumber)}</p>`;
    paragraph = [];
  };
  const closeList = () => {
    if (list) html += `</${list}>`;
    list = null;
  };
  const openList = (type) => {
    if (list !== type) {
      closeList();
      html += `<${type}>`;
      list = type;
    }
  };

  for (const rawLine of text.split("\n")) {
    const line = rawLine.trim();
    let match;
    if (!line) {
      flushParagraph();               // la lista sigue abierta: los puntos a veces vienen separados por líneas en blanco
    } else if ((match = line.match(/^\d+[.)]\s+(.*)$/))) {
      flushParagraph();
      openList("ol");
      html += `<li>${formatInline(match[1], byNumber)}</li>`;
    } else if ((match = line.match(/^[-*•]\s+(.*)$/))) {
      flushParagraph();
      openList("ul");
      html += `<li>${formatInline(match[1], byNumber)}</li>`;
    } else {
      closeList();
      paragraph.push(line.replace(/^#+\s*/, ""));   // si aun así pone un título "## ...", lo tratamos como texto
    }
  }
  flushParagraph();
  closeList();

  return live ? html.replace(CARET_MARK, '<span class="caret"></span>') : html;
}

/**
 * Convierte un trozo de texto en HTML seguro:
 *  1. Escapamos TODO (<, >, &...) para que el texto del modelo no pueda meter HTML en la página.
 *  2. Sobre el texto ya escapado añadimos solo lo que nosotros controlamos: **negrita** y las citas [n].
 * Mientras se escribe (byNumber = null) las citas aún no se pueden pulsar: se pintan igual, pero inactivas.
 */
function formatInline(text, byNumber) {
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    // La cita y el signo que la sigue ("[2]." o "[2],") van juntos en un <span> que no se parte de línea:
    // si no, a veces el punto se quedaba solo al principio de la línea siguiente.
    .replace(/\[(\d+)\]([.,;:)]?)/g, (match, n, punctuation) => {
      let chip;
      if (!byNumber) chip = `<span class="cite pending">${n}</span>`;
      else if (byNumber.has(Number(n)))
        chip = `<button type="button" class="cite" data-n="${n}" aria-expanded="false" aria-label="Ver el texto original de la fuente ${n}">${n}</button>`;
      else return match;
      return `<span class="cite-wrap">${chip}${punctuation}</span>`;
    });
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

// --- Panel con el texto original de una cita --------------------------------

// Un clic fuera del panel (y fuera de las citas) lo cierra. También la tecla Escape.
document.addEventListener("click", (event) => {
  if (!event.target.closest(".source-panel, .cite")) closeSourcePanels();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeSourcePanels();
});

function closeSourcePanels() {
  document.querySelectorAll(".source-panel").forEach((panel) => panel.remove());
  document.querySelectorAll('.cite[aria-expanded="true"]').forEach((b) => b.setAttribute("aria-expanded", "false"));
}

function toggleSource(messageDiv, button, source) {
  const open = messageDiv.querySelector(".source-panel");
  const wasThisOne = open && open.dataset.n === button.dataset.n;

  // Solo un panel abierto a la vez en toda la página.
  closeSourcePanels();
  if (wasThisOne || !source) return;

  const panel = document.createElement("div");
  panel.className = "source-panel";
  panel.dataset.n = button.dataset.n;

  const title = document.createElement("h3");
  title.textContent = `${source.number} · ${source.title}`;
  const text = document.createElement("p");
  text.textContent = source.text;       // el texto original de Peat, en inglés
  const link = document.createElement("a");
  link.href = source.url;
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = "Leer el artículo completo →";

  panel.append(title, text, link);
  // Justo debajo de donde está la cita: dentro del punto de la lista, o después del párrafo.
  const item = button.closest("li");
  if (item) item.appendChild(panel);
  else button.closest("p").after(panel);
  messageDiv.querySelectorAll(`.cite[data-n="${source.number}"]`)
    .forEach((b) => b.setAttribute("aria-expanded", "true"));
}

// --- Guardar la conversación en el navegador ---------------------------------
// localStorage guarda texto en el navegador de cada persona: no viaja a nuestro servidor.
// Puede fallar (modo privado, almacenamiento lleno...), así que siempre va dentro de try/catch:
// si falla, la página sigue funcionando, solo que sin recordar la conversación.
function saveConversation() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ conversationId, messages }));
  } catch (e) { /* sin almacenamiento: no pasa nada */ }
}

function restoreConversation() {
  let saved = null;
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
  } catch (e) { /* datos corruptos o sin acceso: empezamos de cero */ }

  if (Array.isArray(saved)) {
    messages = saved;                   // formato antiguo (solo la lista): sin id, el servidor creará uno nuevo
  } else {
    messages = Array.isArray(saved?.messages) ? saved.messages : [];
    conversationId = saved?.conversationId ?? null;
  }
  messages.forEach((m) => render(m, false));
  setChatting(messages.length > 0);
  if (messages.length > 0) conversation.lastElementChild.scrollIntoView({ block: "start" });
}

newChatButton.addEventListener("click", () => {
  messages = [];
  conversationId = null;                // la próxima pregunta empieza una conversación nueva en el servidor
  saveConversation();
  conversation.replaceChildren();       // vacía la conversación en pantalla
  input.value = "";
  autoGrow();
  hideError();
  setChatting(false);
  window.scrollTo({ top: 0, behavior: "smooth" });
  focusInputIfDesktop();
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
