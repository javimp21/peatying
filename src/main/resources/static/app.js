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
  // Más curiosas (cada una comprobada: la búsqueda encuentra su artículo en primer lugar).
  "¿Por qué decía que la sal no es mala?",
  "¿Qué tiene que ver William Blake con la biología?",
  "¿Por qué desconfiaba del aceite de pescado?",
  "¿Cómo saber si tu tiroides va lenta sin análisis?",
  "¿La leche es buena o mala?",
  "¿Beber mucha agua puede ser malo?",
  "¿Qué opinaba de las verduras?",
  "¿De dónde vienen los sofocos?",
  "¿Por qué le gustaba el aceite de coco?",
  "¿La inteligencia depende del metabolismo?",
];

// Frases que van rotando mientras el bot busca y piensa (sin puntos: los tres puntos los pinta la animación).
const LOADING_PHRASES = [
  "Peatying",
  "Optimizing energy production",
  "Squeezing the oranges",
  "Sunmaxxing",
  "Converting T4 to T3",
  "Lowering serotonin",
  "Sugaring down the cortisol",
];
const FIRST_PHRASE_CYCLES = 2;  // veces que se repite la primera frase antes de pasar a la segunda
const LOADING_CYCLE_MS = 2400;  // solo si las animaciones están desactivadas; si no, manda el CSS (ver showLoading)

const FIRST_PLACEHOLDER = "Prueba: «¿Qué pensaba del azúcar?»";
const NEXT_PLACEHOLDER = "Escribe otra pregunta…";
const INTERNAL_KEY = "rp-internal";
const STORAGE_KEY = "rp-chat-v1";      // donde se guardaba la conversación antes (ver forgetOldConversation)

const form = document.getElementById("ask-form");
const input = document.getElementById("question");
const sendButton = document.getElementById("send");
const errorBox = document.getElementById("error");
const examplesBox = document.getElementById("examples");
const conversation = document.getElementById("conversation");
const newChatButton = document.getElementById("new-chat");
const downloadButton = document.getElementById("download");

// El estado de la conversación: la lista de mensajes. La pantalla se dibuja a partir de aquí,
// y es lo que se descarga.
//   { role: "user", text }   |   { role: "bot", text, sources: [{number, title, url, type, text}] }
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

/**
 * Avisa al servidor de que alguien ha abierto la página, para contar visitas aunque no pregunte.
 * Es anónimo (el servidor solo guarda una huella diaria) y si falla no pasa nada: no molesta al usuario.
 */
function countVisit() {
  if (isInternal) return;               // nuestras propias visitas no cuentan
  fetch("/api/visit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    // De qué página viene (el servidor guarda solo el dominio). Vacío si se llega directo.
    body: JSON.stringify({ referrer: document.referrer }),
  }).catch(() => {});
}

/** Un aviso pequeño abajo que desaparece solo a los 4 segundos. */
function showToast(text) {
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.setAttribute("role", "status");
  toast.textContent = text;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

/**
 * ¿Es este un navegador nuestro? Se marca una vez abriendo peatying.com/?interno (y se desmarca con
 * ?interno=0). Queda guardado en este navegador: sus visitas y preguntas no cuentan en las métricas.
 * No es un secreto ni da ningún permiso: solo sirve para no contarnos a nosotros mismos.
 */
function readInternalFlag() {
  const params = new URLSearchParams(location.search);
  try {
    if (params.has("interno")) {
      const on = params.get("interno") !== "0";
      if (on) localStorage.setItem(INTERNAL_KEY, "1");
      else localStorage.removeItem(INTERNAL_KEY);
      history.replaceState(null, "", location.pathname);   // quitamos ?interno de la dirección
      showToast(on ? "Este navegador ya no cuenta en las métricas." : "Este navegador vuelve a contar en las métricas.");
    }
    return localStorage.getItem(INTERNAL_KEY) === "1";
  } catch (e) {
    return false;                       // sin almacenamiento: contamos la visita, no pasa nada
  }
}

// --- Preguntar sobre un trozo de la respuesta ----------------------------------
// Al seleccionar texto de una respuesta aparece un botón flotante para pedir que lo aclare o lo amplíe,
// sin tener que copiar el trozo y pegarlo en la caja.
const MAX_FRAGMENT = 200;          // la pregunta entera (cita incluida) tiene un máximo de MAX_QUESTION caracteres
const MAX_QUESTION = 3000;         // da para pegar una analítica; igual que MAX_QUESTION_LENGTH en ChatController

const selectionAsk = document.createElement("div");
selectionAsk.className = "selection-ask";
selectionAsk.hidden = true;
let selectedFragment = "";

// "Aclarar esto" pregunta directamente; "Preguntar sobre esto" deja la cita encima de la caja
// (como al responder a un mensaje en WhatsApp) para que la persona escriba su pregunta.
const SELECTION_ACTIONS = [
  ["Aclarar esto", (fragment) => ask("¿Me aclaras esto?", fragment)],
  ["Preguntar sobre esto", (fragment) => { setReply(fragment); input.focus(); }],
];
for (const [label, action] of SELECTION_ACTIONS) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  // mousedown cancelado: si no, el clic quitaría la selección antes de llegar al botón.
  button.addEventListener("mousedown", (event) => event.preventDefault());
  button.addEventListener("click", () => {
    const fragment = selectedFragment;
    hideSelectionAsk();
    window.getSelection().removeAllRanges();
    action(fragment);
  });
  selectionAsk.appendChild(button);
}
// En móvil, el menú del propio sistema (Copiar, Buscar…) sale pegado a la selección, encima o debajo
// según el navegador, y taparía el botón. Ahí lo fijamos abajo, encima de la caja de texto.
if (isTouch) {
  selectionAsk.classList.add("docked");
  form.insertBefore(selectionAsk, document.getElementById("reply"));
} else {
  document.body.appendChild(selectionAsk);
}

// La cita que se está respondiendo (o null). Al bot se le manda delante de la pregunta.
let replyQuote = null;
const replyBox = document.getElementById("reply");
const replyText = document.getElementById("reply-text");
document.getElementById("reply-cancel").addEventListener("click", () => { clearReply(); input.focus(); });

function setReply(fragment) {
  replyQuote = fragment;
  replyText.textContent = fragment;
  replyBox.hidden = false;
  // La cita también ocupa sitio en los MAX_QUESTION caracteres: dejamos para la pregunta solo lo que queda.
  input.maxLength = MAX_QUESTION - withQuote("", fragment).length;
}

function clearReply() {
  replyQuote = null;
  replyBox.hidden = true;
  input.maxLength = MAX_QUESTION;
}

// Al pegar algo más largo de lo que cabe, el navegador lo corta sin decir nada (maxlength).
// Avisamos, para que nadie crea que se ha mandado entero (p. ej. una analítica larga).
input.addEventListener("paste", (event) => {
  const pasted = event.clipboardData?.getData("text") ?? "";
  const selected = input.selectionEnd - input.selectionStart;
  if (input.value.length - selected + pasted.length > input.maxLength) {
    showToast(`El texto es demasiado largo: solo caben ${input.maxLength.toLocaleString("es-ES")} caracteres y se ha cortado el final.`);
  }
});

/** Lo que recibe el bot: la parte de su respuesta a la que se refiere la pregunta, y la pregunta. */
function withQuote(question, quote) {
  return quote ? `Sobre esta parte de tu respuesta anterior: «${quote}»\n\n${question}` : question;
}

// selectionchange salta con el ratón, con el dedo y con el teclado. Esperamos un poco a que la
// selección se quede quieta para no mover el botón con cada letra que se añade.
let selectionTimer;
document.addEventListener("selectionchange", () => {
  clearTimeout(selectionTimer);
  selectionTimer = setTimeout(updateSelectionAsk, 200);
});

function updateSelectionAsk() {
  const selection = window.getSelection();
  if (selection.isCollapsed || selection.rangeCount === 0 || sendButton.disabled) return hideSelectionAsk();

  // Solo dentro de UNA respuesta ya terminada (no en la que se está escribiendo ni en las preguntas).
  const range = toWholeWords(selection.getRangeAt(0));
  const start = elementOf(range.startContainer)?.closest(".message.bot:not(.live) .answer-body");
  const end = elementOf(range.endContainer)?.closest(".message.bot:not(.live) .answer-body");
  if (!start || start !== end) return hideSelectionAsk();

  // El texto sin los botones de cita: seleccionando "…el azúcar [2]." no queremos que salga "el azúcar 2."
  const copy = document.createElement("div");
  copy.appendChild(range.cloneContents());
  copy.querySelectorAll(".cite").forEach((cite) => cite.remove());
  let fragment = copy.textContent.replace(/\s+/g, " ").trim();
  if (!hasSomethingToExplain(fragment)) return hideSelectionAsk();
  if (fragment.length > MAX_FRAGMENT) fragment = fragment.slice(0, MAX_FRAGMENT).trimEnd() + "…";
  selectedFragment = fragment;

  // Debajo de la selección: encima suele estar el menú del propio móvil (Copiar, Buscar…).
  // getBoundingClientRect da la posición en la ventana; sumando el scroll la pasamos a la página.
  selectionAsk.hidden = false;
  if (isTouch) return;                  // en móvil va fijo encima de la caja: no hay que colocarlo
  const rect = range.getBoundingClientRect();
  const width = selectionAsk.offsetWidth;
  const left = Math.min(Math.max(8, rect.left + rect.width / 2 - width / 2),
                        document.documentElement.clientWidth - width - 8);
  selectionAsk.style.left = `${left + window.scrollX}px`;
  selectionAsk.style.top = `${rect.bottom + window.scrollY + 10}px`;
}

// Palabras que solas no significan nada: seleccionar "de" o "por la" no tiene nada que aclarar.
const EMPTY_WORDS = new Set(("a al con de del el en es esa ese eso esta este esto la las le les lo los más muy " +
  "no o para pero por que se sin sobre son su sus un una y").split(" "));

/** ¿Hay en el trozo al menos una palabra con significado propio ("prolactina", "tiroides")? */
function hasSomethingToExplain(fragment) {
  const words = fragment.toLowerCase().match(/[\p{L}\p{N}]+/gu) || [];
  return words.some((word) => word.length >= 3 && !EMPTY_WORDS.has(word));
}

function hideSelectionAsk() {
  selectionAsk.hidden = true;
}

/**
 * Copia de la selección ampliada a palabras completas: es fácil soltar el dedo o el ratón a mitad
 * de palabra, y "ales: valoraba … de pollo co" se entiende peor que "tradicionales: … como".
 */
function toWholeWords(original) {
  const range = original.cloneRange();
  const isLetter = (char) => /[\p{L}\p{N}]/u.test(char);
  if (range.startContainer.nodeType === Node.TEXT_NODE) {
    const text = range.startContainer.data;
    let i = range.startOffset;
    // Solo si la selección corta una palabra (letra a los dos lados); si empieza en un espacio, se queda.
    if (isLetter(text[i] ?? "")) while (i > 0 && isLetter(text[i - 1])) i--;
    range.setStart(range.startContainer, i);
  }
  if (range.endContainer.nodeType === Node.TEXT_NODE) {
    const text = range.endContainer.data;
    let i = range.endOffset;
    if (i > 0 && isLetter(text[i - 1])) while (i < text.length && isLetter(text[i])) i++;
    range.setEnd(range.endContainer, i);
  }
  return range;
}

/** El elemento que contiene un nodo (si el nodo es un trozo de texto, su padre). */
function elementOf(node) {
  return node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
}

// --- Easter egg: pulsar "Peat" en el título exprime una naranja ----------------
const juicer = document.getElementById("juicer");
let juicerTimer;
const egg = document.getElementById("egg");
egg.addEventListener("click", () => {
  juicer.hidden = false;
  // Centrado justo debajo de la palabra, sin salirse de la pantalla por los lados.
  const rect = egg.getBoundingClientRect();
  const width = juicer.offsetWidth;
  const left = Math.min(Math.max(8, rect.left + rect.width / 2 - width / 2),
                        document.documentElement.clientWidth - width - 8);
  juicer.style.left = `${left + window.scrollX}px`;
  juicer.style.top = `${rect.bottom + window.scrollY + 4}px`;
  // Quitar y volver a poner la clase reinicia la animación. Leer offsetWidth obliga al navegador a
  // aplicar el cambio entre medias; sin eso, juntaría las dos cosas y no se repetiría.
  juicer.classList.remove("play");
  void juicer.offsetWidth;
  juicer.classList.add("play");
  clearTimeout(juicerTimer);
  juicerTimer = setTimeout(closeJuicer, 4300);   // cuando ya se ha desvanecido (ver juicer-out en el CSS)
});

function closeJuicer() {
  clearTimeout(juicerTimer);
  juicer.hidden = true;
  juicer.classList.remove("play");
}

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
  ask(input.value, replyQuote);
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

async function ask(question, quote = null) {
  question = question.trim();
  if (!question || sendButton.disabled) return;

  hideError();
  clearReply();
  const firstOfConversation = messages.length === 0;   // las naranjas solo se van rodando en la primera
  setChatting(true);
  const questionDiv = addMessage(quote ? { role: "user", text: question, quote } : { role: "user", text: question });
  input.value = "";
  autoGrow();
  if (isTouch) input.blur();            // en móvil, cerramos el teclado para que se vea la respuesta
  setBusy(true);
  let loading = showLoading();
  // La pregunta sube hasta arriba y la respuesta se escribe debajo, sin mover la página (la última
  // respuesta tiene altura mínima en el CSS para que siempre haya sitio). Se hace después de crear
  // el "cargando": antes la página aún no es lo bastante alta para subir la pregunta del todo.
  questionDiv.scrollIntoView({ behavior: "smooth", block: "start" });
  let live = null;                      // el mensaje que se va escribiendo en directo
  let finished = false;

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question: withQuote(question, quote), conversationId, internal: isInternal }),
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
        saveCurrentConversation();
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
      nextFrame(frame);
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
        nextFrame(frame);
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

/**
 * Ejecuta fn en el próximo fotograma de la pantalla (requestAnimationFrame) o, si el navegador no los
 * está dibujando (pestaña en segundo plano, ventana minimizada...), con un temporizador de respaldo.
 * Gana el que llegue primero. Sin el respaldo, la respuesta se quedaba "congelada" mientras la
 * pestaña no estuviera a la vista.
 */
function nextFrame(fn) {
  let done = false;
  const run = () => {
    if (!done) {
      done = true;
      fn();
    }
  };
  requestAnimationFrame(run);
  setTimeout(run, 50);
}

/** Seguimos el texto hacia abajo solo si el usuario no ha subido a leer otra cosa. */

/** Si la pregunta falla, la quitamos de la conversación y se la devolvemos a la caja para que no la pierda. */
function undoQuestion(questionDiv, question, errorMessage) {
  const undone = messages.pop();
  if (undone.quote) setReply(undone.quote);
  questionDiv.remove();                 // por referencia: el último elemento podría ser el "cargando"
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
  const div = render(message);
  // Si sustituye a un texto escrito en directo, no queremos que "salte" con la animación de entrada.
  if (!animate) div.classList.add("no-anim");
  return div;
}

// No desplaza la página: al preguntar, ask() sube la pregunta arriba y todo lo demás aparece debajo.
function render(message) {
  const div = message.role === "user" ? renderUser(message)
    : message.role === "doc" ? renderDocNote(message) : renderAnswer(message);
  conversation.appendChild(div);
  return div;
}

function renderDocNote({ name }) {
  const div = document.createElement("div");
  div.className = "message doc";
  div.textContent = `📄 Has adjuntado «${name}». Lo tendré en cuenta en esta conversación.`;
  return div;
}

function renderUser({ text, quote }) {
  const div = document.createElement("div");
  div.className = "message user";
  if (quote) {
    const cited = document.createElement("span");
    cited.className = "user-quote";
    cited.textContent = quote;
    div.appendChild(cited);
  }
  div.append(text);                     // append con texto (como textContent) nunca interpreta HTML: seguro
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
    const label = document.createElement("span");
    label.className = "sources-label";
    label.textContent = "Fuentes";
    list.append(label);
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

// --- El texto original de una fuente, en inglés o en español ------------------------
// En el panel de cada cita, dos botones pegados: "Inglés" (el texto tal cual, por defecto) y "Español".
// La traducción se pide una sola vez y se guarda en la fuente (y en el historial): no se vuelve a pagar.

function sourceLanguageToggle(source, textElement) {
  const group = document.createElement("div");
  group.className = "lang-toggle";
  group.setAttribute("role", "group");
  group.setAttribute("aria-label", "Idioma del texto original");
  const buttons = ["Inglés", "Español"].map((name) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = name;
    group.append(button);
    return button;
  });

  const paint = (spanish) => {
    textElement.textContent = spanish ? source.translation : source.text;
    buttons[0].setAttribute("aria-pressed", String(!spanish));
    buttons[1].setAttribute("aria-pressed", String(spanish));
  };
  buttons[0].addEventListener("click", () => paint(false));
  buttons[1].addEventListener("click", async () => {
    if (!source.translation) {
      buttons[1].disabled = true;
      buttons[1].textContent = "Traduciendo…";
      try {
        const response = await fetch("/api/translate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ texts: [source.text] }),
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "HTTP " + response.status);
        source.translation = data.translations[0];
        saveCurrentConversation();
      } catch (e) {
        showToast("No se ha podido traducir. Prueba otra vez.");
        return;
      } finally {
        buttons[1].disabled = false;
        buttons[1].textContent = "Español";
      }
    }
    paint(true);
  });
  paint(false);
  return group;
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
    // Pulsar el voto que ya tenías lo quita (vuelve a null). Con un 👎, se ofrece contar qué ha fallado.
    button.addEventListener("click", async () => {
      const saved = await vote(message, message.vote === value ? null : value, paint);
      if (saved && message.vote === -1) showReasonBox(row, message);
      else row.nextElementSibling?.classList.contains("feedback-reason") && row.nextElementSibling.remove();
    });
    return button;
  });

  const paint = () => {
    label.textContent = message.vote ? "¡Gracias!" : "¿Te ha servido?";
    buttons[0].setAttribute("aria-pressed", String(message.vote === 1));
    buttons[1].setAttribute("aria-pressed", String(message.vote === -1));
  };
  paint();

  const share = document.createElement("button");
  share.type = "button";
  share.className = "share";
  share.innerHTML = SHARE_SVG;
  share.append("Compartir");
  share.addEventListener("click", () => shareAnswer(message, share));
  row.append(label, ...buttons, share);
  return row;
}

/** Tras un 👎: un cuadro opcional para contar qué ha fallado. Se manda junto al mismo voto. */
function showReasonBox(row, message) {
  if (row.nextElementSibling?.classList.contains("feedback-reason")) return;
  const box = document.createElement("form");
  box.className = "feedback-reason";
  const text = document.createElement("textarea");
  text.rows = 2;
  text.maxLength = 500;
  text.placeholder = "¿Qué ha fallado? (opcional: algo incorrecto, que no responda a lo que preguntabas…)";
  text.setAttribute("aria-label", "Qué ha fallado en la respuesta");
  const send = document.createElement("button");
  send.type = "submit";
  send.className = "ghost";
  send.textContent = "Enviar";
  const skip = document.createElement("button");
  skip.type = "button";
  skip.className = "link-button";
  skip.textContent = "Ahora no";
  skip.addEventListener("click", () => box.remove());
  box.addEventListener("submit", async (event) => {
    event.preventDefault();
    const reason = text.value.trim();
    if (!reason) return box.remove();
    send.disabled = true;
    try {
      const response = await fetch("/api/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ conversationId: message.conversationId, messageId: message.messageId, value: -1, reason }),
      });
      if (!response.ok) throw new Error("HTTP " + response.status);
      const thanks = document.createElement("p");
      thanks.className = "feedback-reason";
      thanks.textContent = "Gracias: nos sirve para mejorar las respuestas.";
      box.replaceWith(thanks);
    } catch (e) {
      send.disabled = false;
      showToast("No se ha podido enviar. Prueba otra vez.");
    }
  });
  box.append(text, send, skip);
  row.after(box);
  text.focus();
}

// --- Compartir una respuesta -------------------------------------------------------
// Icono de "compartir" de Lucide (licencia ISC).
const SHARE_SVG = `<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none"
  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/>
  <path d="m8.59 13.51 6.83 3.98"/><path d="m15.41 6.51-6.82 3.98"/></svg>`;

/**
 * El servidor guarda una copia de la pregunta y la respuesta y nos da su enlace (peatying.com/r/...).
 * En el móvil se abre el menú de compartir del sistema (WhatsApp, Telegram...); en el ordenador se copia.
 */
async function shareAnswer(message, button) {
  if (!message.shareUrl) {
    button.disabled = true;
    try {
      const response = await fetch("/api/share", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          conversationId: message.conversationId,
          messageId: message.messageId,
          sources: message.sources.map((s) => ({ number: s.number, url: s.url })),
        }),
      });
      if (!response.ok) throw new Error("HTTP " + response.status);
      message.shareUrl = location.origin + (await response.json()).url;
      saveCurrentConversation();
    } catch (e) {
      showToast("No se ha podido crear el enlace. Prueba otra vez.");
      return;
    } finally {
      button.disabled = false;
    }
  }
  if (isTouch && navigator.share) {
    try {
      await navigator.share({ title: "Ray Peat Chatbot", url: message.shareUrl });
    } catch (e) { /* cerró el menú sin compartir: nada que hacer */ }
    return;
  }
  try {
    await navigator.clipboard.writeText(message.shareUrl);
    showToast("Enlace copiado. Pégalo donde quieras compartirlo.");
  } catch (e) {
    prompt("Copia este enlace:", message.shareUrl);
  }
}

/**
 * Cambia el voto en pantalla al momento ("optimista") y lo manda al servidor.
 * Si el servidor falla, lo devolvemos a como estaba, para no mostrar un voto que no se guardó.
 */
async function vote(message, value, paint) {
  const previous = message.vote;
  message.vote = value;
  paint();
  try {
    const response = await fetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conversationId: message.conversationId, messageId: message.messageId, value }),
    });
    if (!response.ok) throw new Error("HTTP " + response.status);
    saveCurrentConversation();          // que el historial recuerde el voto
    return true;
  } catch (e) {
    message.vote = previous;
    paint();
    return false;
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

/** "https://raypeat.com/articles/articles/gelatin.shtml" -> "raypeat.com/articles/articles/gelatin.shtml" (recortada si es larga). */
function shortUrl(url) {
  try {
    const { host, pathname } = new URL(url);
    const short = host.replace(/^www\./, "") + decodeURIComponent(pathname);
    return short.length > 52 ? short.slice(0, 51) + "…" : short;
  } catch {
    return url;
  }
}

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

  // Como una nota al pie de un libro: número y tipo en letra de máquina, título, el texto original
  // y la dirección de donde sale.
  const interview = source.type === "interview";
  const meta = document.createElement("div");
  meta.className = "source-meta";
  const number = document.createElement("span");
  number.className = "source-n";
  number.textContent = source.number;
  const title = document.createElement("h3");
  title.textContent = source.title;
  const text = document.createElement("p");
  text.className = "source-text";
  text.textContent = source.text;       // el texto original de Peat, en inglés
  meta.append(number, interview ? "Entrevista" : "Artículo", sourceLanguageToggle(source, text));
  const link = document.createElement("a");
  link.href = source.url;
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = `${shortUrl(source.url)} ↗`;
  link.setAttribute("aria-label", interview ? "Escuchar el audio original" : "Leer el artículo completo");

  panel.append(meta, title, text);
  // Las transcripciones son automáticas (Whisper): avisamos de que puede haber errores de reconocimiento.
  if (interview) {
    const note = document.createElement("p");
    note.className = "source-note";
    note.textContent = "Transcripción automática del audio original: puede tener algún error.";
    panel.append(note);
  }
  panel.append(link);
  // Justo debajo de donde está la cita: dentro del punto de la lista, o después del párrafo.
  const item = button.closest("li");
  if (item) item.appendChild(panel);
  else button.closest("p").after(panel);
  messageDiv.querySelectorAll(`.cite[data-n="${source.number}"]`)
    .forEach((b) => b.setAttribute("aria-expanded", "true"));
}

// --- Historial ------------------------------------------------------------------------
// Cada conversación se guarda en ESTE navegador (localStorage) al terminar cada respuesta. Al abrir
// la página se empieza siempre de cero, pero desde "Historial" se puede volver a cualquiera y seguirla
// (el servidor la recuerda por su conversationId). No hay cuentas: otro navegador no ve este historial.
const HISTORY_KEY = "rp-history-v1";
const HISTORY_MAX = 30;                 // las más recientes; las más viejas se van borrando

const historyButton = document.getElementById("history");
const historyDialog = document.getElementById("history-dialog");
const historyList = document.getElementById("history-list");

function loadHistory() {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY)) ?? [];
  } catch (e) {
    return [];                          // sin almacenamiento (modo privado estricto) o datos dañados
  }
}

/** Guarda la lista; si no cabe (el navegador da unos 5 MB), quita las conversaciones más viejas y reintenta. */
function storeHistory(history) {
  while (history.length > 0) {
    try {
      localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
      break;
    } catch (e) {
      if (e.name !== "QuotaExceededError") break;    // sin almacenamiento: no se guarda y ya está
      history = history.slice(0, -1);
    }
  }
  if (history.length === 0) {
    try { localStorage.removeItem(HISTORY_KEY); } catch (e) { /* nada que hacer */ }
  }
  historyButton.hidden = history.length === 0;
}

/** La conversación actual pasa a ser la primera del historial (o se actualiza si ya estaba). */
function saveCurrentConversation() {
  if (!conversationId || messages.length === 0) return;
  const history = loadHistory();
  // El nombre: el puesto a mano con "Renombrar" o, si no, la primera pregunta (o el documento, si aún no hay).
  const previous = history.find((h) => h.id === conversationId);
  const automatic = (messages.find((m) => m.role === "user")?.text
    ?? (currentDocumentName() ? `📄 ${currentDocumentName()}` : "Conversación")).slice(0, 120);
  const title = previous?.renamed ? previous.title : automatic;
  const entry = { id: conversationId, title, renamed: Boolean(previous?.renamed), updatedAt: Date.now(), messages };
  const others = history.filter((h) => h.id !== conversationId);
  storeHistory([entry, ...others].slice(0, HISTORY_MAX));
}

function openConversation(entry) {
  startOver();
  conversationId = entry.id;
  setChatting(true);
  for (const message of entry.messages) addMessage(message, { animate: false });
  if (currentDocumentName()) showDocChip(`📄 ${currentDocumentName()}`);
  historyDialog.close();
  // Abajo del todo, donde se sigue preguntando (sin animación: es una conversación que ya existía).
  window.scrollTo({ top: document.body.scrollHeight });
}

function showHistory() {
  historyList.replaceChildren();
  const dateFormat = new Intl.DateTimeFormat("es-ES", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
  for (const entry of loadHistory()) {
    const item = document.createElement("li");
    const open = document.createElement("button");
    open.type = "button";
    open.className = "history-open";
    const title = document.createElement("span");
    title.textContent = entry.title;
    const date = document.createElement("small");
    date.textContent = dateFormat.format(entry.updatedAt);
    open.append(title, date);
    open.addEventListener("click", () => openConversation(entry));

    // Renombrar: el título se convierte en una caja de texto; Enter (o salir de la caja) guarda, Escape cancela.
    const rename = document.createElement("button");
    rename.type = "button";
    rename.className = "history-action";
    rename.textContent = "Renombrar";
    rename.setAttribute("aria-label", `Renombrar «${entry.title}»`);
    rename.addEventListener("click", () => {
      const field = document.createElement("input");
      field.className = "history-rename";
      field.value = entry.title;
      field.maxLength = 120;
      field.setAttribute("aria-label", "Nuevo nombre de la conversación");
      let done = false;
      const finish = (save) => {
        if (done) return;
        done = true;
        const name = field.value.trim();
        if (save && name) {
          entry.title = name;
          storeHistory(loadHistory().map((h) => (h.id === entry.id ? { ...h, title: name, renamed: true } : h)));
          title.textContent = name;
        }
        field.replaceWith(open);
        rename.focus();                 // el foco vuelve a "Renombrar", que es donde estaba
      };
      field.addEventListener("keydown", (event) => {
        // preventDefault: si no, ese mismo Enter "pulsaría" el título (que recibe el foco) y abriría la conversación.
        if (event.key === "Enter") { event.preventDefault(); finish(true); }
        if (event.key === "Escape") { event.preventDefault(); finish(false); }   // sin cerrar la ventana entera
      });
      field.addEventListener("blur", () => finish(true));
      open.replaceWith(field);
      field.focus();
      field.select();
    });

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "history-action";
    remove.textContent = "Borrar";
    remove.setAttribute("aria-label", `Borrar «${entry.title}»`);
    remove.addEventListener("click", () => {
      storeHistory(loadHistory().filter((h) => h.id !== entry.id));
      item.remove();
      if (!historyList.children.length) historyDialog.close();
    });
    item.append(open, rename, remove);
    historyList.appendChild(item);
  }
  historyDialog.showModal();
}

historyButton.addEventListener("click", showHistory);
document.getElementById("history-close").addEventListener("click", () => historyDialog.close());
document.getElementById("history-clear").addEventListener("click", () => {
  if (!confirm("¿Borrar todas las conversaciones guardadas en este navegador?")) return;
  storeHistory([]);
  historyDialog.close();
});
// Un clic en el fondo oscuro (fuera de la ventana) también la cierra.
historyDialog.addEventListener("click", (event) => {
  if (event.target === historyDialog) historyDialog.close();
});

// Antes se guardaba solo la última conversación, con otra clave: borramos lo que pudiera quedar.
function forgetOldConversation() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch (e) { /* sin acceso al almacenamiento: no hay nada que borrar */ }
}

newChatButton.addEventListener("click", startOver);
// El nombre de arriba a la izquierda también vuelve a la portada, como en casi todas las webs.
document.getElementById("home").addEventListener("click", (event) => {
  event.preventDefault();               // es un enlace a "/": sin esto recargaría la página
  startOver();
});

function startOver() {
  messages = [];
  conversationId = null;                // la próxima pregunta empieza una conversación nueva en el servidor
  conversation.replaceChildren();       // vacía la conversación en pantalla
  showDocChip("");                      // el documento era de la conversación anterior
  input.value = "";
  autoGrow();
  hideError();
  setChatting(false);
  clearReply();
  hideSelectionAsk();
  window.scrollTo({ top: 0, behavior: "smooth" });
  focusInputIfDesktop();
}

// --- Descargar la conversación como Markdown --------------------------------
downloadButton.addEventListener("click", () => {
  const lines = ["# Conversación · Ray Peat en español", ""];
  for (const m of messages) {
    if (m.role === "user") {
      lines.push(`## ${m.text}`, "");
      if (m.quote) lines.push(`> ${m.quote}`, "");
    } else if (m.role === "doc") {
      lines.push(`_Documento adjunto: ${m.name}_`, "");
    } else {
      lines.push(m.text, "");
      if (m.sources.length > 0) {
        lines.push("Fuentes:");
        m.sources.forEach((s) => lines.push(`- [${s.number}] ${s.title}: ${s.url}`));
        lines.push("");
      }
    }
  }
  lines.push("---", "Respuestas generadas por IA a partir de los artículos de raypeat.com y de sus entrevistas de radio. No son consejo médico.");

  // Creamos el archivo en memoria (Blob) y simulamos un clic en un enlace de descarga.
  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `ray-peat-chat-${new Date().toISOString().slice(0, 10)}.md`;
  link.click();
  URL.revokeObjectURL(link.href);       // liberamos la memoria del archivo temporal
});

/**
 * peatying.com/?q=... (los botones de las páginas de temas): deja la pregunta escrita en la caja,
 * sin enviarla. Así ni un buscador que siga el enlace gasta una pregunta, ni se pregunta sin querer.
 */
function prefillFromLink() {
  const params = new URLSearchParams(location.search);
  const question = params.get("q");
  if (!question) return;
  input.value = question.slice(0, MAX_QUESTION);
  autoGrow();
  params.delete("q");
  history.replaceState(null, "", location.pathname + (params.size ? "?" + params : ""));
  input.focus();
}

// --- Adjuntar un documento (analítica, informe...) ---------------------------------
// El servidor saca el texto del PDF o de la foto, lo guarda con la conversación sin datos personales y
// descarta el archivo. Desde ese momento el bot lo tiene delante en todas las preguntas de la conversación.
const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
const attachButton = document.getElementById("attach");
const docInput = document.getElementById("doc-input");
const docChip = document.getElementById("doc-chip");
const docChipText = document.getElementById("doc-chip-text");

attachButton.addEventListener("click", () => docInput.click());
docInput.addEventListener("change", () => {
  const file = docInput.files[0];
  docInput.value = "";                  // así se puede volver a elegir el mismo archivo
  if (file) uploadDocument(file);
});

function showDocChip(text) {
  docChipText.textContent = text;
  docChip.hidden = !text;
}

async function uploadDocument(file) {
  hideError();
  if (file.size > MAX_DOCUMENT_BYTES) return showError("El archivo tiene que pesar menos de 5 MB.");
  attachButton.disabled = true;
  showDocChip(`📄 ${file.name} · leyendo el documento…`);
  try {
    const form = new FormData();
    form.append("file", file);
    if (conversationId) form.append("conversationId", conversationId);
    form.append("internal", String(isInternal));
    const response = await fetch("/api/document", { method: "POST", body: form });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "No he podido leer el documento. Prueba otra vez.");
    conversationId = data.conversationId;
    setChatting(true);
    addMessage({ role: "doc", name: data.name });
    showDocChip(`📄 ${data.name}`);
    saveCurrentConversation();
    focusInputIfDesktop();
  } catch (e) {
    showDocChip(currentDocumentName() ? `📄 ${currentDocumentName()}` : "");
    showError(e.message);
  } finally {
    attachButton.disabled = false;
  }
}

/** El último documento adjuntado en esta conversación (se puede sustituir por otro). */
function currentDocumentName() {
  return [...messages].reverse().find((m) => m.role === "doc")?.name ?? null;
}

// --- Utilidades -------------------------------------------------------------
function setBusy(busy) {
  sendButton.disabled = busy;
  input.disabled = busy;
  attachButton.disabled = busy;
}

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = false;
}

function hideError() {
  errorBox.hidden = true;
}

// --- Arranque ----------------------------------------------------------------
// Al final del archivo a propósito: las constantes declaradas con const no existen hasta que se llega
// a su línea, y así todo lo que se usa al arrancar ya está definido.
showExamples();
forgetOldConversation();
prefillFromLink();
historyButton.hidden = loadHistory().length === 0;
const isInternal = readInternalFlag();
// Para poder comprobarlo de un vistazo: en nuestros navegadores, una nota al final del pie.
if (isInternal) {
  const note = document.createElement("span");
  note.className = "footer-links";
  note.textContent = "Este navegador no cuenta en las métricas (?interno=0 para volver a contar).";
  document.querySelector(".footer").appendChild(note);
}
focusInputIfDesktop();
countVisit();
