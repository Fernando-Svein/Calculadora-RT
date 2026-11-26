/* Auditoria do IBS/CBS destacado nos XMLs (recalcula na calculadora oficial). */
'use strict';

(() => {
  const { esc, fmt, comp } = Hub;

  const ROTULOS = {
    DIVERGENTE: 'Divergente', ERRO_CLASSIFICACAO: 'Erro de classificação', OK: 'OK',
    SEM_GRUPO: 'Sem grupo IBS/CBS', NAO_AUDITADO: 'Não auditado',
  };

  const par = (x, c, tipo = 'moeda') => {
    const f = tipo === 'pct' ? fmt.pct : fmt.valor;
    const dif = x !== null && x !== undefined && c !== null && c !== undefined && Math.abs(Number(x) - Number(c)) > 0.01;
    return `${f(x)}<br><span class="${dif ? 'etq etq-vermelho' : 'sutil'}">${c === null || c === undefined ? '–' : f(c)}</span>`;
  };

  Hub.registrar({
    rota: 'lote-auditoria',
    titulo: 'Auditar XML (IBS/CBS)',
    grupo: 'Processamento em lote',
    icone: '🔍',
    ordem: 2,
    etiquetas: ['Regime Regular'],
    descricao: 'Recalcula na calculadora oficial o IBS/CBS destacado em NF-e, NFC-e e NFS-e e aponta divergências de base, alíquota, valor e classificação.',
    render(el) {
      Hub.telaLote(el, {
        titulo: 'Auditoria do IBS/CBS dos documentos fiscais',
        texto: 'Envie os XMLs (ou um ZIP). Cada item com grupo IBSCBS é recalculado na calculadora oficial do Regime Geral com o mesmo CST/cClassTrib, ' +
          'e a base é refeita a partir dos componentes da nota (produtos, frete, descontos, ICMS, PIS/COFINS...). Diferenças acima de R$ 0,01 são apontadas.',
        url: '/hub/lote/auditoria',
        comData: false,
        aceita: '.xml,.zip,.XML,.ZIP',
        dica: 'XML de NF-e, NFC-e ou NFS-e (padrão nacional), ou ZIP com vários XMLs.',
        msgProcessando: 'Arquivos enviados. Recalculando os documentos na calculadora oficial...',
        mostrar(res, d) {
          const t = d.totais || {};
          const cen = d.cenarioCbs; // CBS com a alíquota do cenário (ex.: TCU 11,3%), lado a lado com a oficial
          // alíquota oficial que gerou a CBS calculada (a mais frequente entre os itens auditados)
          const freq = {};
          (d.linhas || []).forEach((l) => { if (l.calcPCBS !== null && l.calcPCBS !== undefined) freq[l.calcPCBS] = (freq[l.calcPCBS] || 0) + 1; });
          const pOficial = Object.keys(freq).sort((a, b) => freq[b] - freq[a])[0];
          const rotOficial = `alíquota oficial${pOficial !== undefined ? ' ' + fmt.pct(pOficial) : ''}`;
          const rotCen = cen ? `${cen.rotulo} ${fmt.pct(cen.aliquota)}` : '';
          res.innerHTML = `<div class="card"><div class="card-cab"><h2>Resultado – ${d.totalDocumentos} documento(s), ${d.totalItens} item(ns)</h2>
              <div class="acoes"><button class="btn btn-verde" data-xlsx>${Hub.icone('baixar')} Baixar planilha Excel</button></div></div>
            <div class="kpis">
              <div class="kpi"><small>CBS no XML</small><strong>${fmt.moeda(t.xmlVCBS)}</strong></div>
              <div class="kpi"><small>CBS calculada · ${esc(rotOficial)}</small><strong>${fmt.moeda(t.calcVCBS)}</strong></div>
              ${cen ? `<div class="kpi kpi-cenario"><small>CBS calculada · ${esc(rotCen)}</small><strong>${fmt.moeda(t.cenVCBS)}</strong></div>` : ''}
              <div class="kpi"><small>IBS no XML</small><strong>${fmt.moeda(t.xmlVIBS)}</strong></div>
              <div class="kpi"><small>IBS calculado (igual nos dois cenários)</small><strong>${fmt.moeda(t.calcVIBS)}</strong></div>
            </div>
            ${cen ? `<div class="info-caixa">A CBS aparece calculada com as duas alíquotas: <strong>${esc(rotOficial)}</strong> (calculadora oficial) e
              <strong>${esc(rotCen)}</strong> (mesmo cálculo, CBS reescalada no hub). O status OK/Divergente compara o XML com a alíquota oficial,
              que é a que vale para o documento fiscal; a coluna do ${esc(cen.rotulo)} é só simulação.</div>` : ''}
            <div data-cont></div>
            <p class="sutil">Nas colunas de valores, a linha de cima é o XML e a de baixo o calculado (em vermelho quando diverge). Clique num item para ver os detalhes.</p>
            <div data-tab></div>${Hub.arquivosHtml(d.arquivos, d.avisos)}</div>`;
          const tab = comp.tabela(res.querySelector('[data-tab]'), {
            placeholder: 'Buscar número, emitente, produto...',
            colunas: [
              { titulo: 'Status', campo: 'status', render: (l) => comp.status(l.status, l.statusRotulo) },
              { titulo: 'Doc.', campo: 'origem' },
              { titulo: 'Número', campo: 'numero' },
              { titulo: 'Emissão', campo: 'emissao', render: (l) => fmt.data(l.emissao) },
              { titulo: 'Emitente', campo: 'emitenteNome' },
              { titulo: 'Item', campo: 'nItem', tipo: 'num' },
              { titulo: 'Descrição', campo: 'descricao' },
              { titulo: 'NCM/NBS', campo: 'ncmNbs' },
              { titulo: 'CST / cClassTrib', exportar: (l) => [l.cst, l.cClassTrib].filter(Boolean).join(' / ') },
              { titulo: 'Base XML / comp.', render: (l) => par(l.xmlVBC, l.calcVBCComponentes), exportar: (l) => l.xmlVBC, ordenar: false },
              { titulo: `CBS XML / calc. (${rotOficial})`, render: (l) => par(l.xmlVCBS, l.calcVCBS), exportar: (l) => l.xmlVCBS, ordenar: false },
              ...(cen ? [{ titulo: `CBS calc. (${rotCen})`, campo: 'cenVCBS', tipo: 'moeda' }] : []),
              { titulo: 'IBS UF XML / calc.', render: (l) => par(l.xmlVIBSUF, l.calcVIBSUF), exportar: (l) => l.xmlVIBSUF, ordenar: false },
              { titulo: 'IBS Mun XML / calc.', render: (l) => par(l.xmlVIBSMun, l.calcVIBSMun), exportar: (l) => l.xmlVIBSMun, ordenar: false },
            ],
            linhas: d.linhas,
            porPagina: 100,
            detalhe: (l) => `<div class="grade g2"><div><dl class="dl">
                <dt>Chave</dt><dd><code>${esc(l.chave || '–')}</code></dd>
                <dt>Emitente</dt><dd>${esc(fmt.cnpj(l.emitenteCnpj))} ${esc(l.emitenteNome || '')} ${l.emitenteCrt ? '(CRT ' + esc(l.emitenteCrt) + ')' : ''}</dd>
                <dt>Destinatário</dt><dd>${esc(l.destinatario || '–')}</dd>
                <dt>Produto</dt><dd>${esc(l.codigo || '')} – ${esc(l.descricao || '')} · CFOP ${esc(l.cfop || '–')}</dd>
                <dt>Alíquotas XML</dt><dd>CBS ${fmt.pct(l.xmlPCBS)} · IBS UF ${fmt.pct(l.xmlPIBSUF)} · IBS Mun ${fmt.pct(l.xmlPIBSMun)}</dd>
                <dt>Alíquotas calc.</dt><dd>CBS ${fmt.pct(l.calcPCBS)} · IBS UF ${fmt.pct(l.calcPIBSUF)} · IBS Mun ${fmt.pct(l.calcPIBSMun)}</dd>
                ${cen ? `<dt>CBS · ${esc(cen.rotulo)}</dt><dd>${fmt.pct(l.cenPCBS)} → ${fmt.moeda(l.cenVCBS)} <span class="sutil">(oficial: ${fmt.pct(l.calcPCBS)} → ${fmt.moeda(l.calcVCBS)})</span></dd>` : ''}
                <dt>Arquivo</dt><dd><code>${esc(l.arquivo)}</code></dd></dl></div>
              <div>${(l.diferencas || []).length ? `<div class="erro-caixa"><strong>Diferenças</strong><ul>${l.diferencas.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>` : ''}
                ${(l.mensagens || []).length ? `<div class="aviso"><ul>${l.mensagens.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>` : ''}
                ${l.memoriaCalculo ? `<div class="memoria">${esc(l.memoriaCalculo)}</div>` : ''}</div></div>`,
          });
          Hub.contadoresStatus(res.querySelector('[data-cont]'), d.contagemStatus, ROTULOS, tab);
          res.querySelector('[data-xlsx]').onclick = () => Hub.baixarUrl(`/hub/lote/auditoria/${d.id}/excel`, 'auditoria.xlsx');
          Hub.vendasPorNcm(res, d.vendasPorNcm);
        },
      });
    },
  });
})();
