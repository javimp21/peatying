// Página "Sobre Ray Peat": el texto va apareciendo (de invisible y borroso a nítido) según bajas.

// Se ejecuta en la cabecera, antes de pintar nada: con esta clase el CSS oculta los textos que
// aparecerán al bajar. Si JavaScript fallara, la clase no se pone y todo el texto se ve normal.
document.documentElement.classList.add("js");

document.addEventListener("DOMContentLoaded", () => {
  // IntersectionObserver avisa cuando un elemento entra en pantalla, sin tener que escuchar el scroll.
  // rootMargin negativo abajo: aparece cuando ya ha subido un poco, no justo al asomar por el borde.
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add("visible");
        observer.unobserve(entry.target);   // una vez visible, se queda así
      }
    }
  }, { threshold: 0.2, rootMargin: "0px 0px -10% 0px" });

  document.querySelectorAll(".reveal").forEach((element) => observer.observe(element));
});
