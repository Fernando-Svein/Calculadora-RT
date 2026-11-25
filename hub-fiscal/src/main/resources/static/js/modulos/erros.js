/* Catálogo de códigos de erro das calculadoras (REG- e VSN-). */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  async function buscar(codigo) {
    const c = codigo.trim().toUpperCase();
    const url = c.startsWith('VSN')
      ? api.sn(`/calculadora/observabilidade/simples-nacional/erros?codigo=${encodeURIComponent(c)}`)
      : api.rg(`/calculadora/observabilidade/erros?codigo=${encodeURIComponent(c)}`);
    return Hub.getCache(url);
  }

  Hub.registrar({
    rota: 'erros',
    titulo: 'Códigos de erro',
    grupo: 'Consultas e tabelas',
    icone: '⚠️',
    ordem: 7,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Significado dos códigos de validação devolvidos pelas calculadoras (REG- no Regime Geral, VSN- no Simples).',
    render(el) {
      el.innerHTML = comp.cabecalho('Códigos de erro das calculadoras',
        'Consulte um código específico ou carregue o catálogo completo de cada calculadora.') +
        `<div class="card"><div class="linha">
          <div class="campo" style="flex:0 0 220px"><label>Código</label><input data-cod placeholder="Ex.: REG-011 ou VSN-027"></div>
          <button class="btn" data-ok>Consultar</button>
          <span class="espaco"></span>
          <button class="btn btn-sec" data-cat="REG">Catálogo REG- (Regime Geral)</button>
          <button class="btn btn-sec" data-cat="VSN">Catálogo VSN- (Simples)</button>
        </div><div data-um style="margin-top:12px"></div></div>
        <div class="card" data-res hidden></div>`;
      const inp = el.querySelector('[data-cod]');
      const um = el.querySelector('[data-um]');
      const res = el.querySelector('[data-res]');
      const consultar = async () => {
        if (!inp.value.trim()) return;
        Hub.carregando(um);
        const r = await buscar(inp.value);
        um.innerHTML = r.ok
          ? `<div class="info-caixa"><strong>${esc(r.data.codigo)} – ${esc(r.data.titulo)}</strong><br>${esc(r.data.descricao)}</div>`
          : `<div class="aviso">Código ${esc(inp.value)} não encontrado.</div>`;
      };
      el.querySelector('[data-ok]').onclick = consultar;
      inp.onkeydown = (e) => { if (e.key === 'Enter') consultar(); };
      el.querySelectorAll('[data-cat]').forEach((b) => {
        b.onclick = async () => {
          const pref = b.dataset.cat;
          res.hidden = false;
          Hub.carregando(res, 'Carregando catálogo...');
          const linhas = [];
          let faltas = 0;
          for (let i = 1; i < 400 && faltas < 15; i += 10) {
            const lote = await Promise.all(Array.from({ length: 10 }, (_, k) => buscar(`${pref}-${String(i + k).padStart(3, '0')}`)));
            lote.forEach((r) => { if (r.ok) { linhas.push(r.data); faltas = 0; } else faltas++; });
          }
          comp.tabela(res, {
            colunas: [
              { titulo: 'Código', campo: 'codigo', render: (l) => `<code>${esc(l.codigo)}</code>` },
              { titulo: 'Título', campo: 'titulo', largura: 40 },
              { titulo: 'Descrição', campo: 'descricao', largura: 90 },
            ],
            linhas,
            porPagina: 300,
            exportar: { arquivo: `erros-${pref.toLowerCase()}`, titulo: `Códigos de erro ${pref}-` },
          });
        };
      });
    },
  });
})();
