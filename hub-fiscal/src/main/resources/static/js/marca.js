/*
 * Logo do escritório (cadastrada em Sistema → Logo do escritório).
 * Coloca a imagem nos elementos marcados com data-logo (cabeçalho e tela de login) e no ícone da aba.
 * Sem logo cadastrada, fica o ícone padrão do Hub Fiscal. Arquivo próprio porque a CSP não permite scripts inline
 * e a tela de login (antes do login) também usa este script.
 */
'use strict';

(() => {
  async function aplicar() {
    let info;
    try {
      const r = await fetch('/marca', { headers: { Accept: 'application/json' } });
      if (!r.ok) return;
      info = await r.json();
    } catch (e) {
      return; // sem a informação, fica o padrão
    }
    const url = info.logo ? `/marca/logo?v=${encodeURIComponent(info.versao)}` : null;
    document.querySelectorAll('[data-logo]').forEach((alvo) => {
      alvo.classList.toggle('com-imagem', !!url);
      let img = alvo.querySelector('img');
      if (url) {
        if (!img) {
          img = document.createElement('img');
          img.alt = ''; // decorativa: o nome Hub Fiscal está escrito ao lado
          alvo.appendChild(img);
        }
        img.src = url;
      } else if (img) {
        img.remove();
      }
    });
    const icone = document.querySelector('link[rel="icon"]');
    if (icone) {
      if (!icone.dataset.padrao) icone.dataset.padrao = icone.getAttribute('href');
      icone.setAttribute('href', url || icone.dataset.padrao);
    }
  }

  window.HubMarca = { aplicar };
  aplicar();
})();
