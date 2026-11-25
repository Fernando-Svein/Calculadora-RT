/* Ferramentas da NFS-e: indicador de operação, local da operação, cClassTrib por NBS. */
'use strict';

(() => {
  const { esc, api, comp } = Hub;
  const N = '/calculadora/nfse';

  Hub.registrar({
    rota: 'nfse',
    titulo: 'Ferramentas NFS-e',
    grupo: 'Consultas e tabelas',
    icone: '🧾',
    ordem: 5,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Indicador de operação (cIndOp), local de incidência do IBS, cClassTrib por NBS e validação da combinação da NFS-e.',
    render(el) {
      el.innerHTML = comp.cabecalho('Ferramentas da NFS-e',
        'Apoio ao preenchimento do grupo IBS/CBS da NFS-e padrão nacional: o indicador de operação define o local de incidência do IBS (município/UF de destino).') +
        '<div class="card" data-abas></div>';
      comp.abas(el.querySelector('[data-abas]'), [
        { id: 'ind', titulo: 'Indicador de operação', render: abaIndicador },
        { id: 'loc', titulo: 'Local da operação', render: abaLocal },
        { id: 'nbs', titulo: 'cClassTrib por NBS', render: abaNbs },
        { id: 'val', titulo: 'Validar combinação', render: abaValidar },
        { id: 'tab', titulo: 'Locais (tabelas)', render: abaLocais },
      ]);
    },
  });

  function formLinha(campos) {
    return `<div class="linha">${campos}<button class="btn" data-ok>Consultar</button></div><div data-res style="margin-top:14px"></div>`;
  }

  function abaIndicador(d) {
    d.innerHTML = formLinha(`
      <div class="campo" style="flex:0 0 180px"><label>Data do fato gerador</label><input type="date" name="data" value="${Hub.hoje()}"></div>
      <div class="campo"><label>NBS (opcional)</label><input name="nbs" placeholder="Ex.: 115021000"></div>`);
    comp.autocompletarNomenclatura(d.querySelector('[name=nbs]'), 'nbs');
    const res = d.querySelector('[data-res]');
    d.querySelector('[data-ok]').onclick = async () => {
      const q = new URLSearchParams({ dataOcorrenciaFatoGerador: d.querySelector('[name=data]').value });
      const nbs = d.querySelector('[name=nbs]').value.replace(/\D/g, '');
      if (nbs) q.set('nbs', nbs);
      Hub.carregando(res);
      const r = await api.get(api.rg(`${N}/indicador-operacao?${q}`));
      if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
      comp.tabela(res, {
        colunas: [
          { titulo: 'Código', campo: 'cIndOp', render: (l) => `<code>${esc(l.cIndOp)}</code>` },
          { titulo: 'Tipo de operação', campo: 'tipoOperacao', largura: 60 },
          { titulo: 'Local de fornecimento', campo: 'localFornecimento', largura: 40 },
          { titulo: 'Local de incidência', campo: 'localIncidencia', largura: 30 },
        ],
        linhas: Array.isArray(r.data) ? r.data : [r.data],
        exportar: { arquivo: 'indicadores-operacao-nfse', titulo: 'Indicadores de operação da NFS-e' },
      });
    };
    d.querySelector('[data-ok]').click();
  }

  function abaLocal(d) {
    d.innerHTML = formLinha(`
      <div class="campo" style="flex:0 0 220px"><label>Indicador de operação (cIndOp)</label><input name="cIndOp" placeholder="Ex.: 100301"></div>
      <div class="campo" style="flex:0 0 180px"><label>Data do fato gerador</label><input type="date" name="data" value="${Hub.hoje()}"></div>`);
    const res = d.querySelector('[data-res]');
    d.querySelector('[data-ok]').onclick = async () => {
      const c = d.querySelector('[name=cIndOp]').value.trim();
      if (!c) { Hub.toast('Informe o indicador de operação.', 'erro'); return; }
      Hub.carregando(res);
      const r = await api.get(api.rg(`${N}/local-operacao?cIndOp=${encodeURIComponent(c)}&dataOcorrenciaFatoGerador=${d.querySelector('[name=data]').value}`));
      if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
      const x = r.data;
      res.innerHTML = `<dl class="dl">
        <dt>Indicador de operação</dt><dd><code>${esc(x.cIndOp)}</code></dd>
        <dt>Local de fornecimento</dt><dd>${esc(x.localFornecimento)} <span class="sutil">(código ${esc(x.codigoLocalFornecimento)})</span></dd>
        <dt>Local de incidência do IBS</dt><dd><strong>${esc(x.localIncidencia)}</strong> <span class="sutil">(código ${esc(x.codigoLocalIncidencia)})</span></dd>
      </dl>`;
    };
  }

  function abaNbs(d) {
    d.innerHTML = formLinha(`
      <div class="campo"><label>NBS</label><input name="nbs" placeholder="Código ou descrição do serviço"></div>
      <div class="campo" style="flex:0 0 180px"><label>Data</label><input type="date" name="data" value="${Hub.hoje()}"></div>`);
    const inp = d.querySelector('[name=nbs]');
    comp.autocompletarNomenclatura(inp, 'nbs');
    const res = d.querySelector('[data-res]');
    d.querySelector('[data-ok]').onclick = async () => {
      const nbs = inp.value.replace(/\D/g, '');
      if (!nbs) { Hub.toast('Informe a NBS.', 'erro'); return; }
      Hub.carregando(res);
      const data = d.querySelector('[name=data]').value;
      const r = await api.get(api.rg(`${N}/situacoes-classificacoes-tributarias?nbs=${nbs}&data=${data}`));
      if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
      res.innerHTML = '<p class="sutil">Classificações específicas desta NBS na NFS-e (pelo anexo). Para as que dependem da atividade, use "Classificar NCM / NBS".</p><div></div>';
      comp.tabela(res.lastElementChild, {
        colunas: [
          { titulo: 'CST', campo: 'codigoSituacaoTributaria' },
          { titulo: 'Situação', campo: 'descricaoSituacaoTributaria' },
          { titulo: 'cClassTrib', campo: 'codigoClassificacaoTributaria', render: (l) => `<code>${esc(l.codigoClassificacaoTributaria)}</code>` },
          { titulo: 'Classificação', campo: 'descricaoClassificacaoTributaria', largura: 60 },
        ],
        linhas: r.data,
        aoClicar: (l) => Hub.detalheClassificacao(l.codigoClassificacaoTributaria, data),
        vazio: 'Nenhuma classificação específica para esta NBS.',
      });
    };
  }

  function abaValidar(d) {
    d.innerHTML = `<p class="sutil">Verifica se a combinação de código de tributação nacional, indicador de operação, cClassTrib e NBS é aceita.</p>
      <div class="grade g4">
        ${comp.campo('Código de tributação nacional (cTribNac) – opcional', '<input name="cTribNac" placeholder="Ex.: 010101">')}
        ${comp.campo('Indicador de operação (cIndOp) *', '<input name="cIndOp" placeholder="Ex.: 100301">')}
        ${comp.campo('Classificação tributária (cClassTrib) *', '<input name="cClassTrib" placeholder="Ex.: 000001">')}
        ${comp.campo('NBS *', '<input name="nbs" placeholder="Ex.: 115021000">')}
        ${comp.campo('Data do fato gerador *', `<input type="date" name="dataOcorrenciaFatoGerador" value="${Hub.hoje()}">`)}
      </div>
      <div class="acoes" style="margin-top:12px"><button class="btn" data-ok>Validar</button></div>
      <div data-res style="margin-top:14px"></div>`;
    const res = d.querySelector('[data-res]');
    d.querySelector('[data-ok]').onclick = async () => {
      const corpo = comp.lerCampos(d);
      Hub.carregando(res);
      const r = await api.post(api.rg(`${N}/indicador-operacao/validate`), corpo);
      if (!r.ok) { res.innerHTML = Hub.problema(r, 'Combinação não aceita'); return; }
      const valido = r.data === true || (r.data && (r.data.valido === true || r.data.valid === true));
      res.innerHTML = `<div class="${valido ? 'ok-caixa' : 'aviso'}"><strong>${valido ? 'Combinação válida.' : 'Resultado da validação:'}</strong></div>
        ${typeof r.data === 'object' ? '<div data-tab></div>' : ''}`;
      if (typeof r.data === 'object') comp.jsonTabela(res.querySelector('[data-tab]'), r.data, { busca: false });
    };
  }

  async function abaLocais(d) {
    d.innerHTML = '<div class="grade g2"><div><h3>Locais de incidência</h3><div data-a></div></div><div><h3>Locais de fornecimento</h3><div data-b></div></div></div>';
    const [a, b] = await Promise.all([api.get(api.rg(`${N}/locais-incidencia`)), api.get(api.rg(`${N}/locais-fornecimento`))]);
    const col = [{ titulo: 'Código', campo: 'codigo' }, { titulo: 'Descrição', campo: 'descricao', largura: 60 }];
    if (a.ok) comp.tabela(d.querySelector('[data-a]'), { colunas: col, linhas: a.data, busca: false }); else d.querySelector('[data-a]').innerHTML = Hub.problema(a);
    if (b.ok) comp.tabela(d.querySelector('[data-b]'), { colunas: col, linhas: b.data, busca: false }); else d.querySelector('[data-b]').innerHTML = Hub.problema(b);
  }
})();
