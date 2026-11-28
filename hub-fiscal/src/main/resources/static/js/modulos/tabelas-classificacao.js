/* Tabelas oficiais: cClassTrib, CST, Imposto Seletivo, atores e fundamentação legal. */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;
  const DA = '/calculadora/dados-abertos';

  Hub.registrar({
    rota: 'tabela-cclasstrib',
    titulo: 'Tabela cClassTrib e CST',
    grupo: 'Consultas e tabelas',
    icone: '📚',
    ordem: 2,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Todas as classificações tributárias vigentes, CST, Imposto Seletivo, filtros por documento, por adquirente e fundamentação legal.',
    render(el) {
      el.innerHTML = comp.cabecalho('Tabela cClassTrib e CST',
        'Tabelas oficiais da calculadora, sempre pela vigência da data escolhida. Clique numa classificação para ver detalhes, documentos admitidos e o anexo de NCM/NBS.') +
        `<div class="card"><div class="linha">
          <div class="campo u-flex-0-0-190"><label>Data de referência</label><input type="date" data-data value="${Hub.hoje()}"></div>
          <span class="sutil">As tabelas são recarregadas ao mudar a data.</span></div></div>
        <div class="card" data-abas></div>`;
      const inpData = el.querySelector('[data-data]');
      const dataRef = () => inpData.value || Hub.hoje();
      let abasCtl;
      const montar = () => {
        const alvo = el.querySelector('[data-abas]');
        alvo.innerHTML = '';
        abasCtl = comp.abas(alvo, [
          { id: 'cct', titulo: 'cClassTrib (CBS/IBS)', render: (d) => abaClassificacoes(d, dataRef()) },
          { id: 'cst', titulo: 'CST × cClassTrib', render: (d) => abaCst(d, dataRef(), 'cbs-ibs') },
          { id: 'is', titulo: 'Imposto Seletivo', render: (d) => abaCst(d, dataRef(), 'is') },
          { id: 'atores', titulo: 'Por fornecedor / adquirente', render: (d) => abaAtores(d, dataRef()) },
          { id: 'fund', titulo: 'Fundamentação legal', render: (d) => abaFundamentacao(d, dataRef()) },
        ]);
      };
      inpData.onchange = montar;
      montar();
    },
  });

  async function abaClassificacoes(d, data) {
    Hub.carregando(d);
    const r = await Hub.getCache(api.rg(`${DA}/classificacoes-tributarias/cbs-ibs?data=${data}`));
    if (!r.ok) { d.innerHTML = Hub.problema(r); return; }
    const lista = r.data;
    const csts = [...new Set(lista.map((c) => c.codigo.substring(0, 3)))].sort();
    const docs = new Map();
    lista.forEach((c) => (c.tiposDfeClassificacao || []).forEach((t) => docs.set(t.tipo, t.sigla)));
    const extras = Hub.el(`<span class="acoes">
      <select class="u-w-auto" data-f-cst aria-label="Filtrar por CST"><option value="">Todos os CST</option>${csts.map((c) => `<option>${c}</option>`).join('')}</select>
      <select class="u-w-auto" data-f-doc aria-label="Filtrar por documento fiscal"><option value="">Todos os documentos</option>${[...docs].sort((a, b) => a[0] - b[0]).map(([t, s]) => `<option value="${t}">${esc(s)} (${t})</option>`).join('')}</select>
      <label class="check"><input type="checkbox" data-f-red> Só com redução</label>
    </span>`);
    const tab = comp.tabela(d, {
      extras,
      placeholder: 'Buscar código, descrição...',
      colunas: [
        { titulo: 'cClassTrib', campo: 'codigo', render: (l) => `<code><strong>${esc(l.codigo)}</strong></code>` },
        { titulo: 'CST', exportar: (l) => l.codigo.substring(0, 3) },
        { titulo: 'Descrição', campo: 'descricao', largura: 60 },
        { titulo: 'Tratamento', campo: 'descricaoTratamentoTributario', largura: 30 },
        { titulo: 'Red. CBS', campo: 'percentualReducaoCbs', tipo: 'pct' },
        { titulo: 'Red. IBS UF', campo: 'percentualReducaoIbsUf', tipo: 'pct' },
        { titulo: 'Red. IBS Mun', campo: 'percentualReducaoIbsMun', tipo: 'pct' },
        { titulo: 'Tipo de alíquota', campo: 'tipoAliquota' },
        { titulo: 'Nomenclatura', campo: 'nomenclatura' },
        { titulo: 'Crédito adquirente', exportar: (l) => (l.indicaApropriacaoCreditoAdquirenteCbs || l.indicaApropriacaoCreditoAdquirenteIbs ? 'Sim' : 'Não') },
        { titulo: 'Documentos', exportar: (l) => (l.tiposDfeClassificacao || []).map((t) => t.sigla).join(', '), largura: 40 },
      ],
      linhas: lista,
      porPagina: 200,
      exportar: { arquivo: `cclasstrib-${data}`, titulo: `Classificações tributárias CBS/IBS vigentes em ${fmt.data(data)}` },
      aoClicar: (l) => Hub.detalheClassificacao(l.codigo, data),
    });
    const fCst = extras.querySelector('[data-f-cst]');
    const fDoc = extras.querySelector('[data-f-doc]');
    const fRed = extras.querySelector('[data-f-red]');
    const aplicar = () => tab.filtrar((l) => (!fCst.value || l.codigo.startsWith(fCst.value)) &&
      (!fDoc.value || (l.tiposDfeClassificacao || []).some((t) => String(t.tipo) === fDoc.value)) &&
      (!fRed.checked || Number(l.percentualReducaoCbs) > 0 || Number(l.percentualReducaoIbsUf) > 0));
    [fCst, fDoc, fRed].forEach((x) => { x.onchange = aplicar; });
  }

  async function abaCst(d, data, tipo) {
    Hub.carregando(d);
    const r = await Hub.getCache(api.rg(`${DA}/situacoes-tributarias/${tipo}?data=${data}`));
    if (!r.ok) { d.innerHTML = Hub.problema(r); return; }
    const linhas = [];
    r.data.forEach((cst) => {
      const filhos = cst.classificacoesTributarias || [];
      if (!filhos.length) linhas.push({ cst: cst.codigo, descCst: cst.descricao, codigo: '', descricao: '' });
      filhos.forEach((c) => linhas.push({ cst: cst.codigo, descCst: cst.descricao, codigo: c.codigo, descricao: c.descricao }));
    });
    const titulo = tipo === 'is' ? 'Imposto Seletivo' : 'CBS/IBS';
    comp.tabela(d, {
      colunas: [
        { titulo: 'CST', campo: 'cst', render: (l) => `<code>${esc(l.cst)}</code>` },
        { titulo: 'Situação tributária', campo: 'descCst', largura: 40 },
        { titulo: 'cClassTrib', campo: 'codigo', render: (l) => `<code>${esc(l.codigo)}</code>` },
        { titulo: 'Descrição', campo: 'descricao', largura: 70 },
      ],
      linhas,
      porPagina: 300,
      vazio: tipo === 'is' ? 'Nenhum registro nesta data. O Imposto Seletivo vigora a partir de 01/01/2027: altere a data de referência.' : 'Nenhum registro.',
      exportar: { arquivo: `cst-${tipo}-${data}`, titulo: `CST × cClassTrib – ${titulo} (${fmt.data(data)})` },
      aoClicar: tipo === 'is' ? null : (l) => l.codigo && Hub.detalheClassificacao(l.codigo, data),
    });
  }

  async function abaAtores(d, data) {
    Hub.carregando(d);
    const r = await Hub.getCache(api.rg(`${DA}/ator/grupos?data=${data}`));
    if (!r.ok) { d.innerHTML = Hub.problema(r); return; }
    const opcoes = r.data.map((g) => `<optgroup label="${esc(g.descricao)}">${(g.atores || []).map((a) => `<option value="${a.id}">${esc(a.descricao)}</option>`).join('')}</optgroup>`).join('');
    d.innerHTML = `<p class="sutil">Filtra as classificações aplicáveis conforme quem fornece e quem adquire (ex.: venda a não contribuinte, a órgão público, por produtor rural).</p>
      <div class="linha">
        <div class="campo"><label>Fornecedor</label><select data-forn><option value="">Qualquer</option>${opcoes}</select></div>
        <div class="campo"><label>Adquirente</label><select data-adq><option value="">Qualquer</option>${opcoes}</select></div>
        <div class="campo u-flex-0-0-160"><label>Documento</label><select data-dfe><option value="">Todos</option>
          <option>NFE</option><option>NFCE</option><option>NFSE</option><option>CTE</option><option>CTEOS</option><option>BPE</option><option>NF3E</option><option>NFCOM</option></select></div>
        <button class="btn" data-filtrar>Filtrar</button>
      </div><div class="u-mt-14" data-res></div>`;
    d.querySelector('[data-filtrar]').onclick = async () => {
      const res = d.querySelector('[data-res]');
      const q = new URLSearchParams({ data });
      const f = d.querySelector('[data-forn]').value;
      const a = d.querySelector('[data-adq]').value;
      const s = d.querySelector('[data-dfe]').value;
      if (f) q.set('fornecedor', f);
      if (a) q.set('adquirente', a);
      if (s) q.set('siglaDfe', s);
      Hub.carregando(res);
      const rr = await api.get(api.rg(`${DA}/classificacoes-tributarias/cbs-ibs/por-atores?${q}`));
      if (!rr.ok) { res.innerHTML = Hub.problema(rr); return; }
      comp.tabela(res, {
        colunas: [
          { titulo: 'cClassTrib', campo: 'codigo', render: (l) => `<code>${esc(l.codigo)}</code>` },
          { titulo: 'Descrição', campo: 'descricao', largura: 60 },
          { titulo: 'Tratamento', campo: 'descricaoTratamentoTributario' },
          { titulo: 'Red. CBS', campo: 'percentualReducaoCbs', tipo: 'pct' },
        ],
        linhas: rr.data,
        exportar: { arquivo: `cclasstrib-por-atores-${data}`, titulo: 'Classificações por fornecedor/adquirente' },
        aoClicar: (l) => Hub.detalheClassificacao(l.codigo, data),
      });
    };
  }

  async function abaFundamentacao(d, data) {
    Hub.carregando(d);
    const r = await Hub.getCache(api.rg(`${DA}/fundamentacoes-legais?data=${data}`));
    if (!r.ok) { d.innerHTML = Hub.problema(r); return; }
    comp.tabela(d, {
      colunas: [
        { titulo: 'cClassTrib', campo: 'codigoClassificacaoTributaria', render: (l) => `<code>${esc(l.codigoClassificacaoTributaria)}</code>` },
        { titulo: 'CST', campo: 'codigoSituacaoTributaria' },
        { titulo: 'Tributos', campo: 'conjuntoTributo' },
        { titulo: 'Classificação', campo: 'descricaoClassificacaoTributaria', largura: 50 },
        { titulo: 'Base legal', campo: 'textoCurto' },
        { titulo: 'Dispositivo', campo: 'texto', largura: 40 },
        { titulo: 'Referência normativa', render: (l) => `<details><summary>ver</summary><div class="u-ws-pre-wrap u-maxw-520">${esc(String(l.referenciaNormativa || '').replace(/\\n/g, '\n'))}</div></details>`, exportar: (l) => String(l.referenciaNormativa || '').replace(/\\n/g, '\n'), largura: 80 },
      ],
      linhas: r.data,
      porPagina: 200,
      exportar: { arquivo: `fundamentacao-legal-${data}`, titulo: `Fundamentação legal das classificações (${fmt.data(data)})` },
    });
  }
})();
