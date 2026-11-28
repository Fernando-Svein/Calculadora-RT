/* Classificação de cClassTrib em lote a partir de SPED e XML, com planilha Excel. */
'use strict';

(() => {
  const { esc, fmt, comp } = Hub;

  const ROTULOS = {
    DIVERGENTE: 'Divergente', REVISAR: 'Revisar', SUGERIDO: 'Sugerido', CONFERIDO: 'Conferido',
    INVALIDO: 'NCM/NBS inválido', SEM_NOMENCLATURA: 'Sem NCM/NBS',
  };

  // botões "Detalhes" das alternativas: o código vai num atributo data-* (nunca dentro de onclick="..."),
  // assim nenhum texto vira código JavaScript, e a política CSP pode bloquear scripts inline
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-detalhe-cct]');
    if (b) Hub.detalheClassificacao(b.dataset.detalheCct);
  });


  function detalhe(l) {
    const alts = (l.alternativas || []).map((c) => `<div class="candidata ${c.cClassTrib === l.cClassTribSugerido ? 'principal' : ''} u-p-8-12">
        <div class="candidata-cab"><span class="cod">${esc(c.cClassTrib)}</span><span class="etq etq-azul">${esc(c.tratamento || '')}</span>
        ${Number(c.reducaoCbs) > 0 ? `<span class="etq etq-teal">Redução ${fmt.num(c.reducaoCbs)}%</span>` : ''}
        ${c.condicional ? '<span class="etq etq-amarelo">Condicional</span>' : ''}${c.dfeCompativel ? '' : '<span class="etq etq-vermelho">Não admitido no documento</span>'}
        <button class="btn btn-sec btn-peq" type="button" data-detalhe-cct="${esc(c.cClassTrib)}">Detalhes</button></div>
        <div>${esc(c.descricao)}</div>${c.textoAnexo ? `<div class="sutil u-fs-12">Anexo ${esc(c.anexo)}, item ${esc(c.itemAnexo)}: ${esc(c.textoAnexo)}</div>` : ''}</div>`).join('');
    return `<div class="grade g2">
      <div>
        <dl class="dl">
          <dt>${esc(l.tipoNomenclatura || 'NCM/NBS')}</dt><dd><code>${esc(l.nomenclatura || '–')}</code> ${esc(l.descricaoNomenclatura || '')}</dd>
          <dt>Tipo (SPED)</dt><dd>${esc(l.tipoItemSped || '–')}</dd>
          <dt>Origem</dt><dd>${esc(l.origens)}</dd>
          <dt>CFOPs</dt><dd>saída: ${esc(l.cfopsSaida || '–')} · entrada: ${esc(l.cfopsEntrada || '–')}</dd>
          <dt>Sugestão</dt><dd><strong>${esc(l.cClassTribSugerido || '–')}</strong> ${esc(l.descricaoSugerido || '')}</dd>
          ${l.oficialAtual ? `<dt>Calculadora oficial (atual ${esc(l.cClassTribAtual)})</dt><dd>${esc(l.oficialAtual)}</dd>` : ''}
          ${l.oficialSugerido ? `<dt>Calculadora oficial (sugerido)</dt><dd>${esc(l.oficialSugerido)}</dd>` : ''}
          <dt>Fundamentação</dt><dd>${esc(l.fundamentacaoSugerido || '–')}</dd>
          ${l.anexoSugerido ? `<dt>Anexo</dt><dd>${esc(l.anexoSugerido)}</dd>` : ''}
        </dl>
        ${l.revisar ? `<div class="aviso"><strong>O que revisar:</strong> ${esc(l.revisar)}</div>` : ''}
        ${(l.observacoes || []).length ? `<div class="aviso"><ul>${l.observacoes.map((o) => `<li>${esc(o)}</li>`).join('')}</ul></div>` : ''}
        ${(l.sugestoesPorCfop || []).length ? `<div class="info-caixa"><strong>Pelos CFOPs:</strong><ul>${l.sugestoesPorCfop.map((o) => `<li>${esc(o)}</li>`).join('')}</ul></div>` : ''}
      </div>
      <div>
        ${alts ? `<h4>Classificações do anexo para este ${esc(l.tipoNomenclatura || 'código')}</h4><div class="candidatas">${alts}</div>` : '<p class="sutil">Nenhuma classificação vinculada por anexo.</p>'}
        ${(l.outrasPossibilidades || []).length ? `<details><summary>Outras possibilidades (dependem da atividade)</summary><ul>${l.outrasPossibilidades.map((o) => `<li>${esc(o)}</li>`).join('')}</ul></details>` : ''}
      </div></div>`;
  }

  Hub.registrar({
    rota: 'lote-classificacao',
    titulo: 'Classificar cadastro (SPED/XML)',
    grupo: 'Processamento em lote',
    icone: '📥',
    ordem: 1,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Importe SPED (EFD ICMS/IPI, Contribuições) e XMLs: sugere o cClassTrib de cada produto, confere o usado nas notas e gera planilha Excel.',
    render(el) {
      Hub.telaLote(el, {
        titulo: 'Classificação de cClassTrib a partir de SPED e XML',
        texto: 'Lê o cadastro de itens (registro 0200) e os movimentos do SPED, e os itens dos XMLs. Para cada produto ou serviço valida o NCM/NBS, ' +
          'lista as classificações dos anexos da LC 214/2025, sugere a mais provável, confere o cClassTrib já usado nas notas e aponta o que precisa de revisão.',
        url: '/hub/lote/classificacao',
        comData: true,
        aceita: '.txt,.xml,.zip,.TXT,.XML,.ZIP',
        dica: 'SPED EFD ICMS/IPI ou EFD Contribuições (.txt), XML de NF-e/NFC-e/NFS-e, ou ZIP com vários arquivos.',
        msgProcessando: 'Arquivos enviados. Classificando os itens na base oficial...',
        mostrar(res, d) {
          res.innerHTML = `<div class="card"><div class="card-cab"><h2>Resultado – ${d.totalItens} item(ns)</h2>
              <div class="acoes"><button class="btn btn-verde" data-xlsx>${Hub.icone('baixar')} Baixar planilha Excel</button></div></div>
            ${(d.contribuintes || []).length ? `<p>${d.contribuintes.map(esc).join('<br>')}</p>` : ''}
            <p class="sutil">Vigência considerada: ${fmt.data(d.dataReferencia)} · gerado em ${esc(d.geradoEm)}. Clique num item para ver as alternativas.</p>
            ${d.conferenciaOficial ? `<p class="sutil">${esc(d.conferenciaOficial)}</p>` : ''}
            <div data-cont></div><div data-tab></div>${Hub.arquivosHtml(d.arquivos, d.avisos)}</div>`;
          const tab = comp.tabela(res.querySelector('[data-tab]'), {
            placeholder: 'Buscar código, descrição, NCM...',
            colunas: [
              { titulo: 'Status', campo: 'status', render: (l) => comp.status(l.status, l.statusRotulo) },
              { titulo: 'Código', campo: 'codigo' },
              { titulo: 'Descrição', campo: 'descricao' },
              { titulo: 'NCM/NBS', campo: 'nomenclatura', render: (l) => `<code>${esc(l.nomenclatura || '')}</code>${l.impostoSeletivo ? ' <span class="etq etq-vermelho">IS</span>' : ''}` },
              { titulo: 'CFOPs saída', campo: 'cfopsSaida' },
              { titulo: 'Ocorr.', campo: 'ocorrencias', tipo: 'num' },
              { titulo: 'Valor', campo: 'valorTotal', tipo: 'moeda' },
              { titulo: 'Atual', campo: 'cClassTribAtual', render: (l) => `<code>${esc(l.cClassTribAtual || '')}</code>` },
              { titulo: 'Sugerido', campo: 'cClassTribSugerido', render: (l) => `<code><strong>${esc(l.cClassTribSugerido || '')}</strong></code>` },
              { titulo: 'Tratamento', campo: 'tratamentoSugerido' },
              { titulo: 'Observação', render: (l) => esc(l.revisar || (l.observacoes || [])[0] || ''), exportar: (l) => [l.revisar, ...(l.observacoes || [])].filter(Boolean).join(' | ') },
            ],
            linhas: d.linhas,
            porPagina: 100,
            detalhe,
          });
          Hub.contadoresStatus(res.querySelector('[data-cont]'), d.contagemStatus, ROTULOS, tab);
          res.querySelector('[data-xlsx]').onclick = () => Hub.baixarUrl(`/hub/lote/classificacao/${d.id}/excel`, 'classificacao.xlsx');
          Hub.vendasPorNcm(res, d.vendasPorNcm);
        },
      });
    },
  });
})();
