// El email de contacto se monta aquí y no está escrito en el HTML: así los programas que recorren
// webs buscando direcciones para mandar spam no lo encuentran. Sin JavaScript se ve "usuario [arroba] dominio".
for (const link of document.querySelectorAll("a.email[data-user][data-domain]")) {
  const address = `${link.dataset.user}@${link.dataset.domain}`;
  link.href = `mailto:${address}`;
  link.textContent = address;
}
