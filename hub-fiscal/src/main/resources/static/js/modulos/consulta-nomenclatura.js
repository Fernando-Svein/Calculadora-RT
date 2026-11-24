/* Consulta: classificação de um NCM ou NBS. */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;

  const CATEGORIAS = {
    MONOFASICO: 'Tributação monofásica',
    BENEFICIO_PRODUTO: 'Benefício do produto/serviço',
    BENEFICIO_ATIVIDADE: 'Depende da atividade/adquirente',
    REGIME_ESPECIAL: 'Regime especial/condicional',
    INTEGRAL: 'Tributação integral',
    OPERACAO: 'Situação da operação',
  };
  Hub.rotuloCategoria = (c) => CATEGORIAS[c] || c || '';

  /** Cartão de uma classificação candidata. */
  function cartaoCandidata(c, principal) {
    return `<div class="candidata ${principal ? 'principal' : ''}">
      <div class="candidata-cab">
        <span class="cod">${esc(c.cClassTrib)}</span>
        <span class="etq">CST ${esc(c.cst)}</span>
        ${principal ? '<span class="etq etq-verde">Sugestão principal</span>' : ''}
        <span class="etq etq-azul">${esc(c.tratamento || '')}</span>
        ${Number(c.reducaoCbs) > 0 ? `<span class="etq etq-teal">${esc(reducao(c))}</span>` : ''}
        ${c.condicional ? '<span class="etq etq-amarelo" title="Depende do adquirente, região, regime ou operação">Condicional</span>' : ''}
        ${c.dfeCompativel ? '' : '<span class="etq etq-vermelho">Não admitido neste documento</span>'}
        <span class="espaco"></span>
        <button class="btn btn-sec btn-peq" data-det="${esc(c.cClassTrib)}">Detalhes</button>
      </div>
      <div>${esc(c.descricao)}</div>
      ${c.anexo ? `<div class="memoria"><strong>Anexo ${esc(c.anexo)}${c.itemAnexo ? ', item ' + esc(c.itemAnexo) : ''}:</strong> ${esc(c.textoAnexo || '')}</div>` : ''}
      ${c.fundamentacao ? `<div class="sutil" style="font-size:12.5px">Fundamentação: ${esc(c.fundamentacao)}</div>` : ''}
    </div>`;
  }

  function reducao(c) {
    const cbs = Number(c.reducaoCbs || 0);
    const uf = Number(c.reducaoIbsUf || 0);
    return cbs === uf ? `Redução ${fmt.num(cbs)}%` : `CBS −${fmt.num(cbs)}% / IBS −${fmt.num(uf)}%`;
  }

  /** Modal com o detalhe de uma classificação (usado em várias telas). */
  Hub.detalheClassificacao = async (cClassTrib, data) => {
    const c = Hub.modal('<div class="carregando"><div class="giro"></div>Carregando...</div>');
    const r = await api.get(`/hub/classificacao/${cClassTrib}?data=${data || Hub.hoje()}`);
    if (!r.ok) { c.innerHTML = Hub.problema(r); return; }
    const d = r.data;
    const flags = [
      ['Crédito do adquirente (CBS)', d.indicaApropriacaoCreditoAdquirenteCbs],
      ['Crédito do adquirente (IBS)', d.indicaApropriacaoCreditoAdquirenteIbs],
      ['Crédito presumido (fornecedor)', d.indicaCreditoPresumidoFornecedor],
      ['Crédito presumido (adquirente)', d.indicaCreditoPresumidoAdquirente],
      ['Exige grupo de desoneração', d.exigeGrupoDesoneracao],
      ['Incompatível com suspensão', d.incompativelComSuspensao],
    ];
    c.innerHTML = `<h2>cClassTrib ${esc(d.codigo)} <span class="etq">CST ${esc(String(d.codigo).substring(0, 3))}</span></h2>
      <p>${esc(d.descricao)}</p>
      <dl class="dl">
        <dt>Tratamento</dt><dd>${esc(d.descricaoTratamentoTributario)}</dd>
        <dt>Tipo de alíquota</dt><dd>${esc(d.tipoAliquota)}</dd>
        <dt>Redução</dt><dd>CBS ${fmt.pct(d.percentualReducaoCbs)} · IBS UF ${fmt.pct(d.percentualReducaoIbsUf)} · IBS Mun ${fmt.pct(d.percentualReducaoIbsMun)}</dd>
        <dt>Nomenclatura</dt><dd>${esc(d.nomenclatura)}</dd>
        <dt>Crédito na operação antecedente</dt><dd>${esc(d.creditoOperacaoAntecedente || '–')}</dd>
        <dt>Documentos admitidos</dt><dd>${(d.tiposDfeClassificacao || []).map((t) => `<span class="etq" title="${esc(t.descricao)}">${esc(t.sigla)} (${t.tipo})</span>`).join(' ')}</dd>
        <dt>Fundamentação</dt><dd>${esc(d.fundamentacao || '–')}</dd>
        <dt>Atualização</dt><dd>${fmt.data(d.dataAtualizacao)}</dd>
      </dl>
      <div style="margin:10px 0">${flags.map(([t, v]) => `<span class="etq ${v ? 'etq-verde' : ''}">${v ? '✓' : '✗'} ${esc(t)}</span>`).join(' ')}</div>
      <div data-anexo-ncm></div><div data-anexo-nbs></div>`;
    const montarAnexo = (alvo, lista, tipo) => {
      if (!lista || !lista.length) return;
      alvo.innerHTML = `<h3 style="margin-top:14px">${tipo} do anexo (${lista.length})</h3><div></div>`;
      comp.tabela(alvo.lastElementChild, {
        colunas: [
          { titulo: tipo + ' (prefixo)', campo: 'codigo', render: (l) => `<code>${esc(l.codigo)}</code>` },
          { titulo: 'Descrição', campo: 'descricao' },
          { titulo: 'Anexo/Item', render: (l) => esc([l.anexo, l.itemAnexo].filter(Boolean).join(' / ')) },
          { titulo: 'Exceções', render: (l) => esc((l.excecoes || []).join(', ')) },
          { titulo: 'Texto do anexo', campo: 'textoItemAnexo' },
        ],
        linhas: lista,
        porPagina: 50,
        exportar: { arquivo: `anexo-${tipo.toLowerCase()}-${d.codigo}`, titulo: `${tipo} do anexo – cClassTrib ${d.codigo}` },
      });
    };
    montarAnexo(c.querySelector('[data-anexo-ncm]'), d.anexoNcm, 'NCM');
    montarAnexo(c.querySelector('[data-anexo-nbs]'), d.anexoNbs, 'NBS');
  };

  Hub.registrar({
    rota: 'classificar-item',
    titulo: 'Classificar NCM / NBS',
    grupo: 'Consultas e tabelas',
    icone: '🔎',
    ordem: 1,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Informe um NCM ou NBS e veja os cClassTrib possíveis, benefícios do anexo, Imposto Seletivo e fundamentação.',
    render(el, params) {
      el.innerHTML = comp.cabecalho('Classificar NCM / NBS',
        'Mostra as classificações tributárias (cClassTrib) vinculadas ao produto ou serviço pelos anexos da LC 214/2025, ' +
        'usando a mesma regra da calculadora oficial. Classificações que dependem da operação (exportação, bonificação, ' +
        'regimes especiais) aparecem como condicionais.') +
        `<div class="card"><div class="linha">
          <div class="campo" style="flex:0 0 150px"><label>Tipo</label>
            <select name="tipo"><option value="ncm">NCM (bens)</option><option value="nbs">NBS (serviços)</option></select></div>
          <div class="campo" style="flex:2 1 300px"><label>Código ou descrição</label>
            <input name="codigo" placeholder="Ex.: 10063021 ou 'arroz'" autofocus></div>
          <div class="campo" style="flex:0 0 170px"><label>Data de referência</label><input type="date" name="data" value="${Hub.hoje()}"></div>
          <div class="campo" style="flex:0 0 200px"><label>Documento</label>
            <select name="dfe"><option value="">Todos</option><option value="55">NF-e (55)</option><option value="65">NFC-e (65)</option>
            <option value="91">NFS-e (91)</option><option value="57">CT-e (57)</option></select></div>
          <button class="btn" data-consultar>Consultar</button>
        </div></div>
        <div data-resultado></div>`;
      const tipo = el.querySelector('[name=tipo]');
      const codigo = el.querySelector('[name=codigo]');
      const data = el.querySelector('[name=data]');
      const dfe = el.querySelector('[name=dfe]');
      const res = el.querySelector('[data-resultado]');
      tipo.onchange = () => { if (tipo.value === 'nbs' && !dfe.value) dfe.value = '91'; };
      comp.autocompletarNomenclatura(codigo, () => tipo.value, () => consultar());
      codigo.onkeydown = (e) => { if (e.key === 'Enter') consultar(); };
      el.querySelector('[data-consultar]').onclick = consultar;
      if (params.ncm) { codigo.value = params.ncm; consultar(); } else if (params.nbs) { tipo.value = 'nbs'; dfe.value = '91'; codigo.value = params.nbs; consultar(); }

      async function consultar() {
        const cod = codigo.value.replace(/\D/g, '');
        if (!cod) { Hub.toast('Informe o código.', 'erro'); return; }
        Hub.carregando(res);
        const q = new URLSearchParams({ data: data.value });
        if (dfe.value) q.set('tipoDfe', dfe.value);
        const r = await api.get(`/hub/${tipo.value}/${cod}?${q}`);
        if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
        mostrar(r.data);
      }

      function mostrar(a) {
        if (!a.valida) {
          res.innerHTML = `<div class="card"><h2>${esc(a.tipo)} ${esc(a.codigo)}</h2>
            <div class="erro-caixa">${esc(a.mensagem)}</div>${a.descricaoCompleta ? `<p>${esc(a.descricaoCompleta)}</p>` : ''}</div>`;
          return;
        }
        const compat = a.especificas.filter((c) => c.dfeCompativel);
        const principal = compat.find((c) => !c.condicional);
        const html = [];
        html.push(`<div class="card"><div class="card-cab"><h2>${esc(a.tipo)} <code>${esc(a.codigo)}</code></h2>
          <div class="acoes">
            ${a.impostoSeletivo ? '<span class="etq etq-vermelho">Sujeito ao Imposto Seletivo</span>' : '<span class="etq">Sem Imposto Seletivo</span>'}
            ${a.lc116 ? `<span class="etq etq-azul">LC 116: ${esc(a.lc116)}</span>` : ''}
            <a class="btn btn-sec btn-peq" href="#/calc-rg?${a.tipo.toLowerCase()}=${a.codigo}&cClassTrib=${principal ? principal.cClassTrib : '000001'}">Simular no Regime Regular</a>
          </div></div>
          <p style="margin:0">${esc(a.descricaoCompleta)}</p>
          ${(a.observacoes || []).length ? `<div class="aviso"><ul>${a.observacoes.map((o) => `<li>${esc(o)}</li>`).join('')}</ul></div>` : ''}</div>`);
        html.push(`<div class="card"><h3>Classificações vinculadas ao ${a.tipo === 'NCM' ? 'produto' : 'serviço'} (anexos)</h3>`);
        if (!a.especificas.length) {
          html.push(`<div class="ok-caixa">Nenhum benefício vinculado a este ${esc(a.tipo)} nos anexos.
            Em operação normal de venda, a classificação é <strong>000001 – tributação integral</strong> (CST 000).
            Situações da operação (exportação, bonificação, transferência...) seguem as classificações próprias.</div>`);
        } else {
          if (!principal) html.push('<div class="aviso">Todos os tratamentos vinculados são condicionais. Sem o requisito, use 000001 – tributação integral.</div>');
          html.push('<div class="candidatas">' + a.especificas.map((c) => cartaoCandidata(c, c === principal)).join('') + '</div>');
        }
        html.push('</div>');
        if (a.situacionais.length) {
          html.push(`<div class="card"><details ${a.tipo === 'NBS' ? 'open' : ''}><summary>${a.tipo === 'NBS'
            ? 'Classificações que dependem da atividade, do prestador ou da operação'
            : 'Outras classificações aplicáveis a qualquer NCM (dependem da operação ou do adquirente)'} (${a.situacionais.length})</summary>
            <div data-sit style="margin-top:10px"></div></details></div>`);
        }
        res.innerHTML = html.join('');
        res.querySelectorAll('[data-det]').forEach((b) => { b.onclick = () => Hub.detalheClassificacao(b.dataset.det, a.data); });
        const sit = res.querySelector('[data-sit]');
        if (sit) {
          comp.tabela(sit, {
            colunas: [
              { titulo: 'cClassTrib', campo: 'cClassTrib', render: (l) => `<code>${esc(l.cClassTrib)}</code>` },
              { titulo: 'CST', campo: 'cst' },
              { titulo: 'Descrição', campo: 'descricao' },
              { titulo: 'Tratamento', campo: 'tratamento' },
              { titulo: 'Redução CBS', campo: 'reducaoCbs', tipo: 'pct' },
              { titulo: 'Categoria', exportar: (l) => Hub.rotuloCategoria(l.categoria) },
            ],
            linhas: a.situacionais,
            porPagina: 50,
            aoClicar: (l) => Hub.detalheClassificacao(l.cClassTrib, a.data),
          });
        }
      }
    },
  });
})();
