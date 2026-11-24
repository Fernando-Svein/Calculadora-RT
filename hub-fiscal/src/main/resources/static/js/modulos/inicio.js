/* Início: painel compacto com busca, ações rápidas e todas as ferramentas. */
'use strict';

(() => {
  const { esc } = Hub;

  /** Ações mais usadas pelo departamento fiscal. */
  const RAPIDAS = [
    { rota: 'lote-classificacao', cor: 'ambar', titulo: 'Classificar SPED/XML', texto: 'Planilha de cClassTrib do cadastro do cliente' },
    { rota: 'classificar-item', cor: 'azul', titulo: 'Consultar NCM/NBS', texto: 'cClassTrib, anexo e fundamentação' },
    { rota: 'calc-rg', cor: 'indigo', titulo: 'Simular Regime Regular', texto: 'CBS, IBS e IS de uma operação' },
    { rota: 'calc-sn', cor: 'verde', titulo: 'Simular Simples', texto: 'Valores do período no Simples Nacional' },
  ];

  function saudacao() {
    const h = new Date().getHours();
    return h < 12 ? 'Bom dia' : h < 18 ? 'Boa tarde' : 'Boa noite';
  }

  const ferramentas = () => Hub.modulos.filter((m) => m.menu);

  function linhaFerramenta(m) {
    return `<a class="ferr" href="#/${m.rota}" title="${esc(m.descricao || '')}">
      <span class="ferr-ico tile-${Hub.corGrupo(m.grupo)}">${Hub.iconeRota(m.rota)}</span>
      <span class="ferr-texto"><strong>${esc(m.titulo)}</strong><small>${esc(m.descricao || '')}</small></span>
      <span class="ferr-seta">${Hub.icone('chevron')}</span></a>`;
  }

  function painelGrupos() {
    return `<div class="painel-grupos">${Hub.GRUPOS.map((g) => {
      const itens = ferramentas().filter((m) => m.grupo === g).sort((a, b) => a.ordem - b.ordem);
      if (!itens.length) return '';
      return `<section class="grupo-card">
        <header><span class="tile tile-${Hub.corGrupo(g)}">${Hub.iconeGrupo(g)}</span><h3>${esc(g)}</h3><span class="qtd">${itens.length}</span></header>
        ${itens.map(linhaFerramenta).join('')}</section>`;
    }).join('')}</div>`;
  }

  Hub.registrar({
    rota: '',
    titulo: 'Início',
    menu: false,
    render(el) {
      const d = new Date().toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
      const hoje = d.charAt(0).toUpperCase() + d.slice(1);
      const rec = Hub.recentes().filter((r) => r).slice(0, 4).map((r) => Hub.modulos.find((m) => m.rota === r));
      el.innerHTML = `
        <section class="ola">
          <div class="ola-texto">
            <span class="ola-data">${esc(hoje)}</span>
            <h1>${saudacao()}! O que vamos fazer hoje?</h1>
          </div>
          <label class="busca-home">
            ${Hub.icone('busca')}
            <input type="search" data-busca placeholder="Buscar ferramenta: NCM, Simples, XML, alíquota, pedágio..." autocomplete="off">
            <kbd>/</kbd>
          </label>
        </section>

        <div class="rapidas">${RAPIDAS.map((a) => `
          <a class="rapida rapida-${a.cor}" href="#/${a.rota}">
            <span class="rapida-ico">${Hub.iconeRota(a.rota)}</span>
            <span><strong>${esc(a.titulo)}</strong><small>${esc(a.texto)}</small></span>
          </a>`).join('')}</div>

        ${rec.length ? `<div class="recentes"><span class="recentes-rot">${Hub.icone('relogio')} Usados recentemente</span>
          ${rec.map((m) => `<a class="chip" href="#/${m.rota}">${Hub.iconeRota(m.rota)}${esc(m.titulo)}</a>`).join('')}</div>` : ''}

        <div data-lista>${painelGrupos()}</div>

        <p class="nota-transicao">${Hub.icone('info')}<span><strong>Transição:</strong> em 2026 valem as alíquotas de teste (CBS 0,9% e IBS 0,1%).
          A partir de 2027 a base oficial não traz alíquotas de referência e os simuladores marcam o resultado como <em>simulado</em>.</span></p>`;

      const lista = el.querySelector('[data-lista]');
      const busca = el.querySelector('[data-busca]');
      busca.addEventListener('input', () => {
        const q = Hub.semAcento(busca.value.trim());
        if (!q) { lista.innerHTML = painelGrupos(); return; }
        const achados = ferramentas().filter((m) => {
          const t = Hub.semAcento(`${m.titulo} ${m.descricao || ''} ${m.grupo} ${(m.etiquetas || []).join(' ')}`);
          return q.split(/\s+/).every((p) => t.includes(p));
        });
        lista.innerHTML = achados.length
          ? `<section class="grupo-card resultados"><header><h3>${achados.length} ferramenta(s) encontrada(s)</h3></header>${achados.map(linhaFerramenta).join('')}</section>`
          : `<div class="vazio-busca">Nenhuma ferramenta encontrada para “${esc(busca.value.trim())}”.</div>`;
      });
      busca.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { const a = lista.querySelector('a.ferr'); if (a) location.hash = a.getAttribute('href'); }
      });
    },
  });
})();
