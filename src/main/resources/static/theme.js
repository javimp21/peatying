// Tema de la web: claro por defecto, que es el del diseño (un cartel impreso sobre papel), y oscuro para quien lo elija
// con el botón de arriba. La elección se recuerda en este navegador.
// Se carga antes del CSS y sin "defer": así, quien eligió el oscuro no ve un destello claro al abrir cada página.
(function () {
  const KEY = "rp-theme";
  const root = document.documentElement;

  try {
    if (localStorage.getItem(KEY) === "dark") root.dataset.theme = "dark";
  } catch (e) { /* sin almacenamiento: tema claro */ }

  const en = root.lang === "en";
  const LABELS = en
    ? { toDark: "Switch to dark mode", toLight: "Switch to light mode" }
    : { toDark: "Cambiar a modo oscuro", toLight: "Cambiar a modo claro" };
  const ICONS = '<svg class="icon-moon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>'
    + '<svg class="icon-sun" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2'
    + 'M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>';

  // Todas las páginas tienen la misma barra (.actions): el botón se añade aquí y no en cada una de ellas.
  document.addEventListener("DOMContentLoaded", () => {
    const actions = document.querySelector(".actions");
    if (!actions) return;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ghost icon-ghost theme-toggle";
    button.innerHTML = ICONS;
    const label = () => {
      const text = root.dataset.theme === "dark" ? LABELS.toLight : LABELS.toDark;
      button.setAttribute("aria-label", text);
      button.title = text;
    };
    label();
    button.addEventListener("click", () => {
      const dark = root.dataset.theme !== "dark";
      if (dark) root.dataset.theme = "dark";
      else delete root.dataset.theme;
      try {
        localStorage.setItem(KEY, dark ? "dark" : "light");
      } catch (e) { /* sin almacenamiento: vale para esta página */ }
      label();
    });
    actions.prepend(button);
  });
})();
