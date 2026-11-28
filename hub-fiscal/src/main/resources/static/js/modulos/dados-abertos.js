/* Outras tabelas dos dados abertos da calculadora. */
'use strict';

(() => {
  const { api, comp, esc } = Hub;
  const DA = '/calculadora/dados-abertos';

  const CONJUNTOS = [
    { id: 'ncm-vigentes', titulo: 'NCM vigentes', url: (d) => `${DA}/ncm/vigentes?data=${d}`, data: true, desc: 'Lista completa de NCM vigentes na data.' },
    { id: 'nbs-lista', titulo: 'NBS vigentes', url: (d) => `${DA}/nbs/lista?data=${d}`, data: true, desc: 'Lista completa da Nomenclatura Brasileira de Serviços.' },
    { id: 'dfe', titulo: 'Tipos de documento (DF-e)', url: (d) => `${DA}/dfe/grupos?data=${d}`, data: true, desc: 'Documentos fiscais suportados pela calculadora, agrupados.', achatar: (x) => x.flatMap((g) => (g.tipos || []).map((t) => Object.assign({ grupo: g.titulo }, t))) },
    { id: 'atores', titulo: 'Atores (fornecedor/adquirente)', url: (d) => `${DA}/ator/grupos?data=${d}`, data: true, desc: 'Tipos de fornecedor e adquirente usados para filtrar classificações.', achatar: (x) => x.flatMap((g) => (g.atores || []).map((a) => ({ grupo: g.descricao, id: a.id, ator: a.descricao }))) },
    { id: 'redutores', titulo: 'Redutor de compra governamental', url: () => `${DA}/redutores-compra-governamental`, desc: 'Percentual de redução nas compras governamentais (arts. 370 e 472 da LC 214/2025).' },
    { id: 'transf-cbs', titulo: 'Transferência CBS a entes', url: () => `${DA}/transferencias-cbs`, desc: 'Percentuais de transferência da CBS nas compras governamentais por período.' },
    { id: 'transf-ibs', titulo: 'Transferência IBS a entes', url: () => `${DA}/transferencias-ibs`, desc: 'Percentuais de transferência do IBS nas compras governamentais por período.' },
    { id: 'cct-is', titulo: 'cClassTrib do Imposto Seletivo', url: (d) => `${DA}/classificacoes-tributarias/imposto-seletivo?data=${d}`, data: true, desc: 'Classificações do Imposto Seletivo.' },
    { id: 'versao', titulo: 'Versão da calculadora e da base', url: () => `${DA}/versao`, desc: 'Versão do aplicativo e do banco de dados oficial em uso.' },
  ];

  Hub.registrar({
    rota: 'dados-abertos',
    titulo: 'Outras tabelas oficiais',
    grupo: 'Consultas e tabelas',
    icone: '🗂️',
    ordem: 6,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'NCM e NBS vigentes, tipos de documento, atores, redutores e transferências de compras governamentais.',
    render(el, params) {
      el.innerHTML = comp.cabecalho('Outras tabelas oficiais', 'Demais tabelas publicadas pela calculadora (dados abertos). Todas podem ser exportadas para Excel.') +
        `<div class="card"><div class="linha">
          <div class="campo u-flex-2-1-320"><label>Tabela</label><select data-conj>${CONJUNTOS.map((c) => `<option value="${c.id}">${esc(c.titulo)}</option>`).join('')}</select></div>
          <div class="campo u-flex-0-0-180"><label>Data</label><input type="date" data-data value="${Hub.hoje()}"></div>
          <button class="btn" data-ok>Carregar</button></div><p class="sutil u-m-10-0-0" data-desc></p></div>
        <div class="card" data-res></div>`;
      const sel = el.querySelector('[data-conj]');
      const desc = el.querySelector('[data-desc]');
      const res = el.querySelector('[data-res]');
      if (params.t) sel.value = params.t;
      const atualizarDesc = () => { desc.textContent = CONJUNTOS.find((c) => c.id === sel.value).desc; };
      sel.onchange = atualizarDesc;
      atualizarDesc();
      el.querySelector('[data-ok]').onclick = async () => {
        const c = CONJUNTOS.find((x) => x.id === sel.value);
        const data = el.querySelector('[data-data]').value;
        Hub.carregando(res, 'Carregando tabela...');
        const r = await Hub.getCache(api.rg(c.url(data)));
        if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
        // as descrições oficiais de NCM/NBS trazem marcações HTML (<i>nome científico</i>)
        const limpar = (v) => (typeof v === 'string' ? v.replace(/<\/?[a-z][^>]*>/gi, '') : v);
        const bruto = c.achatar ? c.achatar(r.data) : r.data;
        const dados = Array.isArray(bruto)
          ? bruto.map((x) => (x && typeof x === 'object' ? Object.fromEntries(Object.entries(x).map(([k, v]) => [k, limpar(v)])) : limpar(x)))
          : bruto;
        comp.jsonTabela(res, dados, { porPagina: 200, exportar: { arquivo: c.id + (c.data ? '-' + data : ''), titulo: c.titulo } });
      };
      el.querySelector('[data-ok]').click();
    },
  });
})();
