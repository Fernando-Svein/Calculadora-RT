/*
  Tema claro/escuro do HUB Fiscal.
  Carregado no <head> (antes do CSS pintar) para a página já abrir no tema certo, sem "piscar".
  A escolha fica no navegador (localStorage); sem escolha, segue o tema do Windows.
  Qualquer botão com [data-alternar-tema] troca o tema, com o efeito de círculo a partir do botão.
*/
(() => {
  'use strict';
  const CHAVE = 'hub-tema';
  const raiz = document.documentElement;
  const sistemaEscuro = window.matchMedia('(prefers-color-scheme: dark)');

  function salvo() {
    try { return localStorage.getItem(CHAVE); } catch (e) { return null; }
  }
  function atual() {
    return raiz.dataset.tema === 'escuro' ? 'escuro' : 'claro';
  }
  function aplicar(tema) {
    raiz.dataset.tema = tema;
    document.querySelectorAll('[data-alternar-tema]').forEach((b) => {
      const rotulo = tema === 'escuro' ? 'Mudar para o modo claro' : 'Mudar para o modo escuro';
      b.title = rotulo;
      b.setAttribute('aria-label', rotulo);
      b.setAttribute('aria-pressed', tema === 'escuro');
    });
  }

  aplicar(salvo() || (sistemaEscuro.matches ? 'escuro' : 'claro'));

  // sem escolha salva, acompanha a troca de tema do Windows
  sistemaEscuro.addEventListener('change', (e) => { if (!salvo()) aplicar(e.matches ? 'escuro' : 'claro'); });

  function alternar(botao) {
    const novo = atual() === 'escuro' ? 'claro' : 'escuro';
    try { localStorage.setItem(CHAVE, novo); } catch (e) { /* navegador sem armazenamento */ }

    const semMovimento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!document.startViewTransition || semMovimento) {
      // navegador sem View Transitions: troca com transição suave das cores
      raiz.classList.add('tema-trocando');
      aplicar(novo);
      setTimeout(() => raiz.classList.remove('tema-trocando'), 400);
      return;
    }

    // círculo que nasce no botão e cobre a tela inteira
    const r = botao.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    const raio = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    const transicao = document.startViewTransition(() => aplicar(novo));
    transicao.ready.then(() => {
      raiz.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${raio}px at ${x}px ${y}px)`] },
        { duration: 600, easing: 'cubic-bezier(.4, 0, .2, 1)', pseudoElement: '::view-transition-new(root)' }
      );
    });
  }

  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-alternar-tema]');
    if (b) alternar(b);
  });
  // botões criados depois do carregamento recebem o rótulo certo
  document.addEventListener('DOMContentLoaded', () => aplicar(atual()));

  window.HubTema = { alternar, atual };
})();
