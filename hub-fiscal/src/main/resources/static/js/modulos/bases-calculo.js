/* Calculadoras de base de cálculo (NF-e/NFC-e, mercadorias, IS e NFS-e). */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;
  const anoAtual = new Date().getFullYear();

  const FORMS = [
    {
      id: 'unificada', titulo: 'Unificada NF-e/NFC-e', url: '/calculadora/base-calculo/base-calculo-unificada',
      desc: 'Calcula de uma vez a base do IBS/CBS no regime regular, a do Imposto Seletivo e a receita do Simples Nacional, aplicando a vigência de cada componente ao ano (ex.: PIS/COFINS deixam de existir em 2027).',
      campos: [
        ['anoFatoGerador', 'Ano do fato gerador *', 'int', anoAtual],
        ['tipoDocumento', 'Documento *', ['55|NF-e (55)', '65|NFC-e (65)'], '55', 'int'],
        ['natureza', 'Natureza *', ['bem|Mercadoria (bem)', 'servico|Serviço'], 'bem'],
        ['vProd', 'Valor dos produtos'], ['vFrete', 'Frete'], ['vSeg', 'Seguro'], ['vOutro', 'Outras despesas'], ['vDesc', 'Desconto'],
        ['vII', 'Imposto de Importação'], ['vIS', 'Imposto Seletivo'], ['vICMS', 'ICMS'], ['vICMSUFDest', 'ICMS UF destino'],
        ['vFCP', 'FCP'], ['vFCPUFDest', 'FCP UF destino'], ['vICMSMono', 'ICMS monofásico'], ['vPIS', 'PIS'], ['vCOFINS', 'COFINS'], ['vISSQN', 'ISSQN'],
      ],
    },
    {
      id: 'cibs', titulo: 'CBS/IBS mercadorias', url: '/calculadora/base-calculo/cbs-ibs-mercadorias',
      desc: 'Base do IBS/CBS pela composição legal: valor do bem + acréscimos, juros, multas, encargos, frete, IS, demais importâncias − tributos que não integram a base − descontos incondicionais.',
      campos: [
        ['anoFatoGerador', 'Ano do fato gerador *', 'int', anoAtual],
        ['valorBem', 'Valor do bem'], ['ajusteAcrescimos', 'Acréscimos'], ['juros', 'Juros'], ['multas', 'Multas'], ['encargos', 'Encargos'],
        ['frete', 'Frete cobrado'], ['impostoSeletivo', 'Imposto Seletivo'], ['outrosTributos', 'Outros tributos e preços públicos'],
        ['demaisImportancias', 'Demais importâncias (seguros, taxas)'], ['icms', 'ICMS'], ['iss', 'ISS'], ['pis', 'PIS'], ['pisImportacao', 'PIS importação'],
        ['cofins', 'COFINS'], ['cofinsImportacao', 'COFINS importação'], ['cosip', 'COSIP'], ['ipi', 'IPI'], ['descontoIncondicional', 'Desconto incondicional'],
      ],
    },
    {
      id: 'is', titulo: 'Imposto Seletivo', url: '/calculadora/base-calculo/is-mercadorias',
      desc: 'Base do Imposto Seletivo na venda de mercadorias, com bonificações (art. 417) e devoluções (art. 418).',
      campos: [
        ['anoFatoGerador', 'Ano do fato gerador *', 'int', Math.max(anoAtual, 2027)],
        ['valorBem', 'Valor do bem'], ['ajusteAcrescimos', 'Acréscimos'], ['juros', 'Juros'], ['multas', 'Multas'], ['encargos', 'Encargos'],
        ['freteCobrado', 'Frete cobrado'], ['outrosTributos', 'Outros tributos e preços públicos'], ['demaisImportancias', 'Demais importâncias'],
        ['icms', 'ICMS'], ['iss', 'ISS'], ['cosip', 'COSIP'], ['ipi', 'IPI'], ['descontoIncondicional', 'Desconto incondicional'],
        ['bonificacao', 'Bonificações'], ['devolucaoVendas', 'Devoluções de vendas'],
      ],
    },
    {
      id: 'nfse', titulo: 'NFS-e', url: '/calculadora/nfse/base-calculo',
      desc: 'Base do IBS/CBS na NFS-e: valor do serviço − desconto incondicional − reembolsos/repasses − deduções − ISS/PIS/COFINS (conforme a vigência).',
      campos: [
        ['anoFatoGerador', 'Ano do fato gerador *', 'int', anoAtual],
        ['valorServico', 'Valor do serviço'], ['descontoIncondicional', 'Desconto incondicional'],
        ['vCalcReeRepRes', 'Reembolsos, repasses e ressarcimentos'], ['vCalcDedRedIBSCBS', 'Deduções/reduções da base (locação etc.)'],
        ['iss', 'ISS'], ['pis', 'PIS'], ['cofins', 'COFINS'],
      ],
    },
    {
      id: 'nfse-unif', titulo: 'Unificada NFS-e (experimental)', url: '/calculadora/base-calculo/base-calculo-unificada-nfse',
      desc: 'Versão experimental da calculadora oficial: bases da NFS-e considerando a opção pelo Simples, salão-parceiro e locação (9903), com ajustes documentados.',
      campos: [
        ['anoFatoGerador', 'Ano do fato gerador *', 'int', anoAtual],
        ['opSimpNac', 'Situação do prestador no Simples *', ['1|Não optante', '2|MEI', '3|Optante – ME ou EPP', '4|Opção pelo Simples pendente'], '1'],
        ['situacao', 'Situação *', ['comum|Comum', 'salaoParceiro|Salão-parceiro', 'locacao9903|Locação (9903)'], 'comum'],
        ['regApIBSCBSSN', 'Como recolhe o IBS e a CBS', ['|Não informar', '1|IBS e CBS dentro do Simples', '2|CBS no Simples e IBS fora', '3|IBS e CBS fora do Simples (regime regular)'], ''],
        ['vServ', 'Valor do serviço'], ['vDescIncond', 'Desconto incondicional'], ['vISSQN', 'ISSQN'], ['vPIS', 'PIS'], ['vCOFINS', 'COFINS'],
      ],
      extra: 'nfseUnificada',
    },
  ];

  /**
   * Vigência dos tributos que entram/saem da base. As calculadoras oficiais (exceto a unificada)
   * recusam com HTTP 400 vazio um tributo informado fora da vigência – o hub remove e avisa.
   */
  const VIGENCIA = {
    pis: [2026, 2026], cofins: [2026, 2026], pisImportacao: [2026, 2026], cofinsImportacao: [2026, 2026], vPIS: [2026, 2026], vCOFINS: [2026, 2026],
    icms: [2026, 2032], iss: [2026, 2032], vISSQN: [2026, 2032],
    impostoSeletivo: [2027, 9999],
  };
  const NOMES = { pis: 'PIS', cofins: 'COFINS', pisImportacao: 'PIS importação', cofinsImportacao: 'COFINS importação', vPIS: 'PIS', vCOFINS: 'COFINS', icms: 'ICMS', iss: 'ISS', vISSQN: 'ISSQN', impostoSeletivo: 'Imposto Seletivo' };

  function aplicarVigencia(f, corpo) {
    const ignorados = [];
    if (f.id === 'unificada') return ignorados; // a oficial já ignora e devolve avisos
    const ano = corpo.anoFatoGerador;
    for (const [campo, [ini, fim]] of Object.entries(VIGENCIA)) {
      if (corpo[campo] !== undefined && (ano < ini || ano > fim)) {
        ignorados.push(`${NOMES[campo]} (vigência ${ini}${fim === 9999 ? ' em diante' : fim === ini ? '' : '–' + fim})`);
        delete corpo[campo];
      }
    }
    return ignorados;
  }

  function campoHtml([nome, rotulo, tipo, padrao, conv]) {
    if (Array.isArray(tipo)) {
      return comp.campo(rotulo, `<select name="${nome}" ${conv === 'int' ? 'data-int' : ''}>${tipo.map((o) => { const [v, t] = o.split('|'); return `<option value="${v}" ${String(padrao) === v ? 'selected' : ''}>${esc(t)}</option>`; }).join('')}</select>`);
    }
    if (tipo === 'int') return comp.campo(rotulo, `<input name="${nome}" data-int value="${padrao ?? ''}">`);
    return comp.campo(rotulo, `<input name="${nome}" data-num placeholder="0,00">`);
  }

  function ler(raiz) {
    const o = {};
    raiz.querySelectorAll('[name]').forEach((i) => {
      const v = i.value.trim();
      if (v === '' || i.closest('[data-extra]')) return;
      if (i.dataset.int !== undefined) o[i.name] = parseInt(v, 10);
      else if (i.dataset.num !== undefined) o[i.name] = Hub.numero(v);
      else o[i.name] = v;
    });
    return o;
  }

  function formulario(d, f) {
    d.innerHTML = `<p class="sutil">${esc(f.desc)}</p>
      <div class="grade g4">${f.campos.map(campoHtml).join('')}</div>
      ${f.extra === 'nfseUnificada' ? `<div data-extra>
        <fieldset style="margin-top:12px"><legend>Locação de imóvel (situação 9903)</legend><div class="grade g4">
          ${comp.campo('% copropriedade', '<input data-l="pCopropriedade" value="100">')}
          ${comp.campo('Valor total da operação', '<input data-l="vTotOper">')}
          ${comp.campo('Desconto incondicional total', '<input data-l="vDescIncondTot">')}
          ${comp.campo('Soma dos ajustes', '<input data-l="somaAjustesLocImoveis">')}</div></fieldset>
        <fieldset><legend>Documentos de ajuste da base</legend><div data-docs></div>
          <button class="btn btn-sec btn-peq" data-add-doc type="button">+ Ajuste</button></fieldset></div>` : ''}
      <div class="acoes" style="margin-top:12px"><button class="btn" data-ok>Calcular</button><button class="btn btn-sec" data-json>Ver dados técnicos</button></div>
      <div data-res style="margin-top:14px"></div>`;
    const res = d.querySelector('[data-res]');
    if (f.extra === 'nfseUnificada') {
      const docs = d.querySelector('[data-docs]');
      d.querySelector('[data-add-doc]').onclick = () => {
        docs.appendChild(Hub.el(`<div class="grade g4" style="margin-bottom:8px">
          ${comp.campo('Tipo de ajuste', '<select data-d="tpAjusteBC"><option value="101">Subcontratação</option><option value="102">Consórcio</option><option value="103">Intermediação</option><option value="104">Cessão de mão de obra</option><option value="105">Ressarcimento</option><option value="9">Profissional-parceiro (salão-parceiro)</option><option value="199">Outros (descrever)</option></select>')}
          ${comp.campo('Valor aplicado', '<input data-d="vAjusteAplic">')}
          ${comp.campo('Descrição (obrigatória no tipo Outros)', '<input data-d="xTpAjusteBC">')}</div>`));
      };
    }
    const montar = () => {
      const corpo = ler(d);
      if (f.extra === 'nfseUnificada') {
        if (corpo.situacao === 'locacao9903') {
          const loc = {};
          d.querySelectorAll('[data-l]').forEach((i) => { const n = Hub.numero(i.value); if (n !== null) loc[i.dataset.l] = n; });
          corpo.locacao = loc;
        }
        const docs = [];
        d.querySelectorAll('[data-docs] > .grade').forEach((g) => {
          const t = g.querySelector('[data-d=tpAjusteBC]').value.trim();
          const v = Hub.numero(g.querySelector('[data-d=vAjusteAplic]').value);
          if (t && v !== null) docs.push({ tpAjusteBC: t, vAjusteAplic: v, xTpAjusteBC: g.querySelector('[data-d=xTpAjusteBC]').value.trim() || undefined });
        });
        if (docs.length) corpo.documentos = docs;
      }
      return corpo;
    };
    d.querySelector('[data-json]').onclick = () => comp.verJson(montar(), 'Dados a enviar');
    d.querySelector('[data-ok]').onclick = async () => {
      const corpo = montar();
      if (f.id === 'is' && corpo.anoFatoGerador < 2027) {
        res.innerHTML = '<div class="aviso">O Imposto Seletivo começa a ser cobrado em 2027. Informe um ano a partir de 2027.</div>';
        return;
      }
      const ignorados = aplicarVigencia(f, corpo);
      Hub.carregando(res, 'Calculando...');
      const r = await api.post(api.rg(f.url), corpo);
      if (!r.ok) { res.innerHTML = Hub.problema(r, 'Cálculo rejeitado', 'algum valor informado não é aceito para o ano escolhido. Confira o ano do fato gerador.'); return; }
      const dados = r.data;
      const numeros = Object.entries(dados).filter(([, v]) => typeof v === 'number' || (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v)));
      const avisos = [].concat(ignorados.length ? [`Não compõem a base em ${corpo.anoFatoGerador} e foram ignorados: ${ignorados.join(', ')}.`] : [], dados.avisos || []);
      res.innerHTML = `<div class="kpis">${numeros.map(([k, v]) => `<div class="kpi ${/^base/i.test(k) ? 'destaque' : ''}"><small>${esc(Hub.rotulos.campo(k))}</small><strong>${fmt.moeda(v)}</strong></div>`).join('')}</div>
        ${avisos.length ? `<div class="aviso"><ul>${avisos.map((a) => `<li>${esc(typeof a === 'object' ? JSON.stringify(a) : a)}</li>`).join('')}</ul></div>` : ''}
        <details><summary>Resposta técnica completa</summary><pre class="json">${esc(JSON.stringify(dados, null, 2))}</pre></details>`;
    };
  }

  Hub.registrar({
    rota: 'bases',
    titulo: 'Bases de cálculo',
    grupo: 'Calculadoras – Regime Regular',
    icone: '📐',
    ordem: 2,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Base unificada da NF-e/NFC-e (regular, IS e Simples), base detalhada de mercadorias, base do IS e bases da NFS-e.',
    render(el, params) {
      el.innerHTML = comp.cabecalho('Bases de cálculo', 'Calculadoras oficiais de base de cálculo. A base unificada mostra numa só chamada as bases do regime regular, do Imposto Seletivo e do Simples Nacional.') +
        '<div class="card" data-abas></div>';
      comp.abas(el.querySelector('[data-abas]'), FORMS.map((f) => ({ id: f.id, titulo: f.titulo, render: (d) => formulario(d, f) })), params.aba);
    },
  });
})();
