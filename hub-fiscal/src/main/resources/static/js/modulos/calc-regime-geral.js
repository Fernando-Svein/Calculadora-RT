/* Simulador de operação – Regime Regular (CBS, IBS e Imposto Seletivo). */
'use strict';

(() => {
  const { esc, fmt, api, comp, numero } = Hub;

  const DOCS = [['55', 'NF-e (55)'], ['65', 'NFC-e (65)'], ['91', 'NFS-e (91)'], ['57', 'CT-e (57)'], ['67', 'CT-e OS (67)'],
    ['63', 'BP-e (63)'], ['62', 'NFCom (62)'], ['66', 'NF3e (66)'], ['75', 'NFAg (75)']];
  const COMPONENTES = [['vProd', 'Valor dos produtos'], ['vFrete', 'Frete'], ['vSeg', 'Seguro'], ['vOutro', 'Outras despesas'],
    ['vDesc', 'Desconto'], ['vII', 'Imposto de Importação'], ['vIS', 'Imposto Seletivo'], ['vICMS', 'ICMS'],
    ['vICMSUFDest', 'ICMS UF destino'], ['vFCP', 'FCP'], ['vFCPUFDest', 'FCP UF destino'], ['vICMSMono', 'ICMS monofásico'],
    ['vPIS', 'PIS'], ['vCOFINS', 'COFINS'], ['vISSQN', 'ISSQN']];

  /** Busca a primeira ocorrência de uma chave em qualquer nível. */
  function prof(o, chave) {
    if (!o || typeof o !== 'object') return undefined;
    if (Object.prototype.hasOwnProperty.call(o, chave)) return o[chave];
    for (const v of Object.values(o)) {
      const r = prof(v, chave);
      if (r !== undefined) return r;
    }
    return undefined;
  }
  Hub.prof = prof;

  Hub.registrar({
    rota: 'calc-rg',
    titulo: 'Simulador Regime Regular',
    grupo: 'Calculadoras – Regime Regular',
    icone: '🧮',
    ordem: 1,
    etiquetas: ['Regime Regular'],
    descricao: 'Calcula CBS, IBS e Imposto Seletivo de uma operação com vários itens, com memória de cálculo, XML do grupo e exportação.',
    render(el, params) {
      el.innerHTML = comp.cabecalho('Simulador de operação – Regime Regular',
        'Calcula CBS, IBS (estadual e municipal) e Imposto Seletivo item a item na calculadora oficial do Regime Geral. ' +
        'Informe a base de cálculo ou os componentes da NF-e para que a calculadora monte a base.') +
        `<div data-empresa></div>
        <div class="card">
          <div class="card-cab"><h3>Operação</h3><div class="acoes">
            <button class="btn btn-sec btn-peq" type="button" data-importar title="Lê NCM/NBS, CST, cClassTrib, quantidades e valores dos documentos">${Hub.icone('importar')} Importar XML/SPED</button>
            <input type="file" data-arq-importar hidden multiple accept=".xml,.zip,.txt,.XML,.ZIP,.TXT" aria-label="Arquivos XML, ZIP ou SPED para importar">
            <button class="btn btn-sec btn-peq" data-exemplo>Carregar exemplo</button>
            <button class="btn btn-sec btn-peq" data-json-env>Ver dados técnicos</button></div></div>
          <div class="grade g4">
            ${comp.campo('Data do fato gerador *', `<input type="date" name="data" value="${Hub.hoje()}">`)}
            ${comp.campo('Hora', '<input type="time" name="hora" value="10:00">')}
            ${comp.campo('Documento', `<select name="tpDoc"><option value="">Não informar</option>${DOCS.map(([v, t]) => `<option value="${v}" ${v === '55' ? 'selected' : ''}>${t}</option>`).join('')}</select>`)}
            ${comp.campo('Modo de cálculo', `<select name="modo"><option value="obs">Calcular todos e mostrar o erro de cada item</option><option value="estrito">Parar no primeiro item com erro</option></select>`)}
          </div>
          <div data-loc style="margin-top:12px"></div>
          <details style="margin-top:8px"><summary>Venda para órgão público (compra governamental)</summary>
            <div class="grade g4" style="margin-top:8px">
              ${comp.campo('Órgão comprador', `<select name="tpEnteGov"><option value="">Não é compra governamental</option>
                <option value="1">1 – União</option><option value="2">2 – Estado</option><option value="3">3 – Distrito Federal</option><option value="4">4 – Município</option></select>`)}
              ${comp.campo('Momento do pagamento', '<select name="tpOperGov"><option value="">Não informar</option><option value="1">Fornecimento com pagamento posterior</option><option value="2">Recebimento do pagamento com fornecimento já realizado</option><option value="3">Fornecimento com pagamento já realizado</option><option value="4">Recebimento do pagamento com fornecimento posterior</option><option value="5">Fornecimento e pagamento ao mesmo tempo</option></select>')}
            </div></details>
          <fieldset data-aliq style="margin-top:12px"><legend>Alíquotas nominais (obrigatórias a partir de 2027)</legend>
            <div class="grade g4">
              ${comp.campo('CBS %', '<input name="aCbs" value="8,8" data-num>')}
              ${comp.campo('IBS estadual %', '<input name="aIbsUf" value="0,05" data-num>')}
              ${comp.campo('IBS municipal %', '<input name="aIbsMun" value="0,05" data-num>')}
            </div>
            <span class="ajuda sutil">Valores iniciais são uma estimativa para 2027 – confira as alíquotas vigentes. O resultado sai como cálculo simulado.</span>
          </fieldset>
        </div>
        <div class="card"><div class="card-cab"><h3>Itens</h3><button class="btn btn-sec btn-peq" data-add>+ Adicionar item</button></div>
          <div data-importado></div>
          <div class="itens-lista" data-itens></div></div>
        <div class="acoes" style="margin-bottom:16px"><button class="btn" data-calc>Calcular</button>
          <button class="btn btn-sec" data-limpar>Limpar</button></div>
        <div data-res></div>`;

      const loc = comp.seletorMunicipio(el.querySelector('[data-loc]'), { uf: 'SP', municipio: 3550308 });
      const lista = el.querySelector('[data-itens]');
      const res = el.querySelector('[data-res]');
      const inpData = el.querySelector('[name=data]');
      const fsAliq = el.querySelector('[data-aliq]');
      const ajustarAliq = () => { fsAliq.hidden = Number(inpData.value.substring(0, 4)) < 2027; };
      inpData.onchange = ajustarAliq;
      ajustarAliq();
      // 2027 em diante: a CBS do cenário (seletor no topo) vai como alíquota nominal, calculada pela própria calculadora
      Hub.cenarioCbs.ligarCampoNominal(el.querySelector('[name=aCbs]'), '8,8');

      // empresa da simulação: município do estabelecimento, documento e cClassTrib usuais vêm do cadastro
      let cctPadrao = null;
      function aplicarEmpresa(e) {
        cctPadrao = (e && e.cClassTribPadrao) || null;
        if (!e) return {};
        const D = Hub.empresas.definir;
        const usadas = [];
        if (e.uf && e.municipio) {
          usadas.push('município do estabelecimento como local da operação');
          loc.pronto.then(() => loc.definir(e.uf, e.municipio)).then(() => {
            D(el.querySelector('[data-loc] [data-uf]'), e.uf);
            D(el.querySelector('[data-loc] [data-mun]'), e.municipio);
          });
        }
        if (D(el.querySelector('[name=tpDoc]'), e.documentoPadrao)) usadas.push('documento usual ' + (Hub.empresas.DOCUMENTOS[e.documentoPadrao] || e.documentoPadrao));
        if (cctPadrao) {
          lista.querySelectorAll('[name=cClassTrib]').forEach((i) => {
            if (i.value) return;
            D(i, cctPadrao);
            D(i.closest('.item-bloco').querySelector('[name=cst]'), cctPadrao.substring(0, 3));
          });
          usadas.push(`cClassTrib usual ${cctPadrao} nos itens sem classificação`);
        }
        let aviso = '';
        if (Hub.empresas.ehSimples(e) && !e.ibscbsForaSimples) {
          aviso = 'Empresa do Simples Nacional: o IBS e a CBS dela são recolhidos no DAS. Para o valor devido, use o ' +
            '<a href="#/calc-sn">Simulador Simples Nacional</a>; aqui o cálculo segue o regime regular.';
        } else if (e.regime === 'MEI') {
          aviso = 'MEI recolhe valores fixos no DAS-MEI e não calcula IBS/CBS por documento: este cálculo segue o regime regular.';
        }
        return { usadas, aviso };
      }
      const emp = Hub.empresas.seletor(el.querySelector('[data-empresa]'), { raiz: el, aoMudar: aplicarEmpresa });

      function novoItem(dados = {}) {
        // cClassTrib usual da empresa só em item novo (undefined); '' vindo de importação/duplicação fica vazio
        if (cctPadrao && dados.cClassTrib === undefined) dados = Object.assign({}, dados, { cClassTrib: cctPadrao, cst: cctPadrao.substring(0, 3) });
        const n = lista.children.length + 1;
        const b = Hub.el(`<div class="item-bloco">
          <div class="item-cab"><strong>Item <span data-num>${n}</span></strong><span class="espaco"></span>
            <button class="btn btn-sec btn-peq" data-dup>Duplicar</button><button class="btn btn-perigo btn-peq" data-rm>Remover</button></div>
          <div class="grade g6">
            ${comp.campo('Nomenclatura', '<select name="tipoNom"><option value="ncm">NCM</option><option value="nbs">NBS</option><option value="">Nenhuma</option></select>')}
            ${comp.campo('Código NCM/NBS', '<input name="codNom" placeholder="código ou descrição">')}
            ${comp.campo('Classificação tributária (cClassTrib) *', '<input name="cClassTrib" placeholder="000001" list="">')}
            ${comp.campo('CST *', '<input name="cst" placeholder="000">')}
            ${comp.campo('Quantidade', '<input name="quantidade" data-num>')}
            ${comp.campo('Unidade', '<input name="unidade" placeholder="UN, KG, LT...">')}
          </div>
          <div class="acoes" style="margin:6px 0"><button class="btn-link" data-sug>Sugerir cClassTrib pelo NCM/NBS</button><span class="sutil" data-sug-info></span></div>
          <div class="grade g4" style="margin-top:4px">
            ${comp.campo('Base de cálculo', '<select name="modoBase"><option value="base">Informar a base</option><option value="comp">Calcular pelos componentes (NF-e)</option></select>')}
            ${comp.campo('Base de cálculo (R$)', '<input name="baseCalculo" data-num placeholder="0,00">')}
          </div>
          <div class="grade g6" data-comp hidden style="margin-top:8px">${COMPONENTES.map(([k, t]) => comp.campo(t, `<input name="c_${k}" data-num placeholder="0,00">`)).join('')}</div>
          <details style="margin-top:8px"><summary>Imposto Seletivo do item</summary>
            <div class="grade g6" style="margin-top:8px">
              ${comp.campo('CST do Imposto Seletivo', '<input name="is_cst" placeholder="000">')}
              ${comp.campo('Classificação do Imposto Seletivo', '<input name="is_cClassTrib" placeholder="000001">')}
              ${comp.campo('Base do Imposto Seletivo (R$)', '<input name="is_base" data-num>')}
              ${comp.campo('Quantidade tributada', '<input name="is_qtd" data-num>')}
              ${comp.campo('Unidade de medida', '<input name="is_un">')}
              ${comp.campo('Imposto Seletivo já destacado (R$)', '<input name="is_inf" data-num>')}
              ${comp.campo('Alíquota sobre o valor (%) – a partir de 2027', '<input name="is_adv" data-num>')}
              ${comp.campo('Valor fixo por unidade (R$) – a partir de 2027', '<input name="is_adr" data-num>')}
            </div></details>
          <details data-tr style="margin-top:6px"><summary>Tributação regular (suspensão, diferimento, ZFM/ALC)</summary>
            <p class="sutil" style="margin:8px 0">Obrigatória quando a classificação suspende ou desonera a operação (ex.: CST 550, cClassTrib 200022/200024):
              informe o tratamento que valeria sem o benefício. A calculadora mostra o tributo que deixou de ser cobrado (coluna "Diferido / suspenso").</p>
            <div class="grade g6">
              ${comp.campo('CST regular', '<input name="tr_cst" placeholder="000">')}
              ${comp.campo('cClassTrib regular', '<input name="tr_cClassTrib" placeholder="000001">')}
            </div></details>
        </div>`);
        lista.appendChild(b);
        const tipoNom = b.querySelector('[name=tipoNom]');
        const codNom = b.querySelector('[name=codNom]');
        const cct = b.querySelector('[name=cClassTrib]');
        const cst = b.querySelector('[name=cst]');
        const modoBase = b.querySelector('[name=modoBase]');
        comp.autocompletarNomenclatura(codNom, () => tipoNom.value || 'ncm');
        const trCst = b.querySelector('[name=tr_cst]');
        const trCct = b.querySelector('[name=tr_cClassTrib]');
        trCct.oninput = () => { const v = trCct.value.replace(/\D/g, ''); if (v.length >= 3) trCst.value = v.substring(0, 3); };
        cct.oninput = () => {
          const v = cct.value.replace(/\D/g, '');
          if (v.length >= 3) cst.value = v.substring(0, 3);
          // classificações que exigem o grupo de tributação regular (erro REG-009 sem ele)
          if (/^550|^200022$|^200024$/.test(v)) {
            b.querySelector('[data-tr]').open = true;
            if (!trCct.value) { trCct.value = '000001'; trCst.value = '000'; }
          }
        };
        modoBase.onchange = () => {
          b.querySelector('[data-comp]').hidden = modoBase.value !== 'comp';
          b.querySelector('[name=baseCalculo]').disabled = modoBase.value === 'comp';
        };
        b.querySelector('[data-rm]').onclick = () => { b.remove(); renumerar(); };
        b.querySelector('[data-dup]').onclick = () => novoItem(lerItemBruto(b));
        b.querySelector('[data-sug]').onclick = () => sugerir(b);
        // preencher
        for (const [k, v] of Object.entries(dados)) {
          const i = b.querySelector(`[name="${k}"]`);
          if (i && v !== undefined && v !== null) i.value = v;
        }
        modoBase.onchange();
        return b;
      }

      function lerItemBruto(b) {
        const o = {};
        b.querySelectorAll('[name]').forEach((i) => { o[i.name] = i.value; });
        return o;
      }

      function renumerar() {
        lista.querySelectorAll('[data-num]').forEach((s, i) => { if (s.tagName === 'SPAN') s.textContent = i + 1; });
      }

      async function sugerir(b) {
        const tipo = b.querySelector('[name=tipoNom]').value || 'ncm';
        const cod = b.querySelector('[name=codNom]').value.replace(/\D/g, '');
        const info = b.querySelector('[data-sug-info]');
        if (!cod) { Hub.toast('Informe o NCM/NBS do item.', 'erro'); return; }
        info.textContent = 'consultando...';
        const tpDoc = el.querySelector('[name=tpDoc]').value;
        const r = await api.get(`/hub/${tipo}/${cod}?data=${inpData.value}${tpDoc ? '&tipoDfe=' + tpDoc : ''}`);
        if (!r.ok) { info.textContent = ''; Hub.toast('Falha na consulta.', 'erro'); return; }
        const a = r.data;
        if (!a.valida) { info.textContent = a.mensagem; return; }
        const opcoes = a.especificas.filter((c) => c.dfeCompativel);
        const principal = opcoes.find((c) => !c.condicional);
        const escolha = principal ? principal.cClassTrib : '000001';
        b.querySelector('[name=cClassTrib]').value = escolha;
        b.querySelector('[name=cst]').value = escolha.substring(0, 3);
        info.innerHTML = ` → ${esc(escolha)} ${principal ? '(' + esc(principal.tratamento) + ')' : '(tributação integral)'}` +
          (opcoes.length > (principal ? 1 : 0) ? ` · outras: ${opcoes.filter((c) => c !== principal).map((c) => esc(c.cClassTrib)).join(', ')}` : '') +
          (a.impostoSeletivo ? ' · <strong>sujeito ao IS</strong>' : '');
      }

      function montarOperacao() {
        const f = comp.lerCampos(el.querySelector('.card'));
        const l = loc.valor();
        const ano = Number(f.data.substring(0, 4));
        const op = {
          id: 'hub-' + Date.now(),
          versao: '1.0.0',
          dhFatoGerador: Hub.dataHoraComFuso(f.data, f.hora || '10:00'),
          municipio: l.municipio,
          uf: l.uf,
          itens: [],
        };
        if (f.tpDoc) op.tpDoc = Number(f.tpDoc);
        if (f.tpEnteGov) {
          op.gCompraGov = { tpEnteGov: Number(f.tpEnteGov) };
          if (f.tpOperGov) op.gCompraGov.tpOperGov = Number(f.tpOperGov);
        }
        Array.from(lista.children).forEach((b, i) => {
          const c = comp.lerCampos(b);
          const it = { numero: i + 1, cst: c.cst, cClassTrib: c.cClassTrib };
          const cod = (c.codNom || '').replace(/\D/g, '');
          if (cod && c.tipoNom) it[c.tipoNom] = cod;
          if (c.quantidade !== undefined) it.quantidade = c.quantidade;
          if (c.unidade) it.unidade = c.unidade;
          if (c.modoBase === 'comp') {
            const g = {};
            COMPONENTES.forEach(([k]) => { if (c['c_' + k] !== undefined && c['c_' + k] !== null) g[k] = c['c_' + k]; });
            it.gComponentesBC = g;
          } else if (c.baseCalculo !== undefined) {
            it.baseCalculo = c.baseCalculo;
          }
          if (c.tr_cst || c.tr_cClassTrib) {
            it.tributacaoRegular = { cst: c.tr_cst || (c.tr_cClassTrib || '').substring(0, 3), cClassTrib: c.tr_cClassTrib };
          }
          if (c.is_cst || c.is_cClassTrib) {
            it.impostoSeletivo = { cst: c.is_cst, cClassTrib: c.is_cClassTrib, baseCalculo: c.is_base };
            if (c.is_qtd !== undefined) it.impostoSeletivo.quantidade = c.is_qtd;
            if (c.is_un) it.impostoSeletivo.unidade = c.is_un;
            if (c.is_inf !== undefined) it.impostoSeletivo.impostoInformado = c.is_inf;
          }
          if (ano >= 2027) {
            it.aliquotasNominais = { cbs: f.aCbs, ibsEstadual: f.aIbsUf, ibsMunicipal: f.aIbsMun };
            if (c.is_adv !== undefined || c.is_adr !== undefined) {
              it.aliquotasNominais.impostoSeletivo = {};
              if (c.is_adv !== undefined) it.aliquotasNominais.impostoSeletivo.adValorem = c.is_adv;
              if (c.is_adr !== undefined) it.aliquotasNominais.impostoSeletivo.adRem = c.is_adr;
            }
          }
          op.itens.push(it);
        });
        return { op, modo: f.modo };
      }

      async function calcular() {
        const { op, modo } = montarOperacao();
        if (!op.municipio) { Hub.toast('Selecione o município.', 'erro'); return; }
        if (!op.itens.length) { Hub.toast('Inclua ao menos um item.', 'erro'); return; }
        // empresa e local no momento do cálculo (vão para a faixa do resultado e para os relatórios)
        const ctx = { empresa: emp.empresa(), usadas: emp.usadas(), local: loc.valor(), tpDoc: el.querySelector('[name=tpDoc]').value };
        Hub.carregando(res, 'Calculando na calculadora oficial...');
        const url = modo === 'estrito' ? '/calculadora/regime-geral' : '/calculadora/observabilidade/regime-geral';
        const r = await api.post(api.rg(url), op);
        if (!r.ok) { res.innerHTML = Hub.problema(r, 'A calculadora rejeitou a operação'); return; }
        // até 2026 a calculadora só usa a alíquota oficial (erro REG-036 se informada): o hub reescala a CBS da resposta
        if (Hub.cenarioCbs.ativo() && Number(op.dhFatoGerador.substring(0, 4)) < 2027) {
          const c = await Hub.cenarioCbs.aplicar('regime-geral', r.data, op.dhFatoGerador.substring(0, 10));
          if (!c.ok) { res.innerHTML = Hub.problema(c, 'Não foi possível aplicar o cenário de CBS'); return; }
          r.data = c.data;
        }
        mostrarResultado(op, r, ctx);
      }

      function mostrarResultado(op, r, ctx) {
        const d = r.data;
        const tot = (d.total && d.total.tribCalc) || {};
        const ibscbs = tot.IBSCBSTot || {};
        const vCBS = prof(ibscbs.gCBS, 'vCBS');
        const vIBS = prof(ibscbs.gIBS, 'vIBS');
        const vIS = prof(tot.ISTot, 'vIS');
        const base = ibscbs.vBCIBSCBS;
        const linhas = (d.objetos || []).map((o) => {
          const g = prof(o.tribCalc, 'gIBSCBS') || {};
          const it = op.itens.find((x) => x.numero === o.nObj) || {};
          return {
            n: o.nObj,
            nom: it.ncm || it.nbs || '',
            cst: prof(o.tribCalc, 'CST') || it.cst,
            cct: prof(o.tribCalc, 'cClassTrib') || it.cClassTrib,
            estado: o.estadoItem || (o.calculoSimulado ? 'CALCULO_SIMULADO' : 'CALCULADO'),
            vBC: g.vBC,
            pCBS: prof(g.gCBS, 'pCBS'), pCBSEf: prof(g.gCBS, 'pAliqEfet'), vCBS: prof(g.gCBS, 'vCBS'),
            pIBSUF: prof(g.gIBSUF, 'pIBSUF'), vIBSUF: prof(g.gIBSUF, 'vIBSUF'),
            pIBSMun: prof(g.gIBSMun, 'pIBSMun'), vIBSMun: prof(g.gIBSMun, 'vIBSMun'),
            red: prof(g.gCBS, 'pRedAliq'),
            vIS: prof(o.tribCalc && o.tribCalc.IS, 'vIS'),
            // tributo diferido (gDif) ou que deixou de ser cobrado por suspensão/benefício (gTribRegular)
            naoCobrado: (() => {
              const dif = ['gCBS', 'gIBSUF', 'gIBSMun'].reduce((s, k) => s + Number(prof(prof(g[k], 'gDif'), 'vDif') || 0), 0);
              const tr = g.gTribRegular || {};
              const reg = ['vTribRegCBS', 'vTribRegIBSUF', 'vTribRegIBSMun'].reduce((s, k) => s + Number(tr[k] || 0), 0);
              const v = dif || reg;
              return v ? v : undefined;
            })(),
            memoria: prof(o.tribCalc, 'memoriaCalculo'),
            erros: o.erros || [],
            bruto: o,
          };
        });
        const estados = { CALCULADO: 'etq-verde', CALCULO_SIMULADO: 'etq-azul', INCONSISTENCIA_ENTRADA: 'etq-vermelho', NAO_IMPLEMENTADO: 'etq-amarelo', FALHA_TECNICA: 'etq-vermelho' };
        res.innerHTML = `<div class="card"><div class="card-cab"><h2>Resultado</h2><div class="acoes">
            <select data-xml-tipo style="width:auto" aria-label="Tipo de documento do XML"><option value="nfe">XML NF-e</option><option value="nfce">XML NFC-e</option>
              <option value="cte">XML CT-e</option><option value="bpe">XML BP-e</option><option value="nf3e">XML NF3e</option></select>
            <button class="btn btn-sec btn-peq" data-xml>Gerar XML do grupo IBS/CBS</button>
            ${Hub.relatorio.botoes()}
            <button class="btn btn-sec btn-peq" data-json>Ver resposta técnica</button></div></div>
          ${Hub.empresas.faixaResultado(ctx.empresa)}
          ${r.aviso ? `<div class="aviso">${esc(Hub.avisoSimulado(r.aviso))}</div>` : ''}
          ${Hub.cenarioCbs.aviso(d)}
          <div class="kpis">
            <div class="kpi"><small>Base IBS/CBS</small><strong>${fmt.moeda(base)}</strong></div>
            <div class="kpi"><small>CBS</small><strong>${fmt.moeda(vCBS)}</strong></div>
            <div class="kpi"><small>IBS (UF + Mun)</small><strong>${fmt.moeda(vIBS)}</strong></div>
            ${vIS !== undefined ? `<div class="kpi"><small>Imposto Seletivo</small><strong>${fmt.moeda(vIS)}</strong></div>` : ''}
            <div class="kpi destaque"><small>Total de tributos</small><strong>${fmt.moeda(Number(vCBS || 0) + Number(vIBS || 0) + Number(vIS || 0))}</strong></div>
          </div>
          <div data-tab></div><p class="sutil">Clique num item para ver a memória de cálculo e os erros.</p></div>`;
        const tab = comp.tabela(res.querySelector('[data-tab]'), {
          busca: false,
          colunas: [
            { titulo: 'Item', campo: 'n', tipo: 'num' },
            { titulo: 'NCM/NBS', campo: 'nom' },
            { titulo: 'CST', campo: 'cst' },
            { titulo: 'cClassTrib', campo: 'cct' },
            { titulo: 'Situação', campo: 'estado', render: (l) => `<span class="etq ${estados[l.estado] || ''}">${esc(Hub.rotulos.estado(l.estado))}</span>`, exportar: (l) => Hub.rotulos.estado(l.estado) },
            { titulo: 'Base', campo: 'vBC', tipo: 'moeda' },
            { titulo: 'Redução', campo: 'red', tipo: 'pct' },
            { titulo: 'Alíq. CBS', campo: 'pCBS', tipo: 'pct' },
            { titulo: 'CBS', campo: 'vCBS', tipo: 'moeda' },
            { titulo: 'Alíq. IBS UF', campo: 'pIBSUF', tipo: 'pct' },
            { titulo: 'IBS UF', campo: 'vIBSUF', tipo: 'moeda' },
            { titulo: 'Alíq. IBS Mun', campo: 'pIBSMun', tipo: 'pct' },
            { titulo: 'IBS Mun', campo: 'vIBSMun', tipo: 'moeda' },
            { titulo: 'Imp. Seletivo', campo: 'vIS', tipo: 'moeda' },
            { titulo: 'Diferido / suspenso', campo: 'naoCobrado', tipo: 'moeda' },
            { titulo: 'Erros', exportar: (l) => l.erros.map((e) => `${e.code}: ${e.detail}`).join(' | '), render: (l) => (l.erros.length ? `<span class="etq etq-vermelho">${l.erros.length}</span>` : '') },
          ],
          linhas,
          // a exportação da tabela agora sai pelos botões "Relatório PDF / Excel" (com empresa e resumo)
          detalhe: (l) => {
            const mem = l.memoria && typeof l.memoria === 'object' ? Object.entries(l.memoria).map(([k, v]) => `<div class="memoria"><strong>${esc(Hub.rotulos.memoria(k))}:</strong> ${esc(v)}</div>`).join('') : '';
            const errs = l.erros.length ? `<div class="erro-caixa"><ul>${l.erros.map((e) => `<li><strong>${esc(e.code)}</strong> – ${esc(e.title || '')}: ${esc(e.detail)} <span class="sutil">${esc(Hub.rotulos.ponteiro(e.pointer))}</span></li>`).join('')}</ul></div>` : '';
            return (mem || '<span class="sutil">Sem memória de cálculo.</span>') + errs;
          },
        });
        res.querySelector('[data-json]').onclick = () => comp.verJson(d, 'Resposta da calculadora');
        res.querySelector('[data-xml]').onclick = () => gerarXml(op, res.querySelector('[data-xml-tipo]').value);
        Hub.relatorio.ligar(res, async () => {
          const x = tab.exportacao();
          const total = Number(vCBS || 0) + Number(vIBS || 0) + Number(vIS || 0);
          const comErro = linhas.filter((l) => l.erros.length);
          return {
            titulo: 'Simulação – Regime Regular (CBS, IBS e IS)',
            arquivo: 'simulacao-regime-regular',
            empresa: await Hub.empresas.linhasRelatorio(ctx.empresa, ctx.usadas),
            resumo: [
              ['Data e hora do fato gerador', `${fmt.data(op.dhFatoGerador)} ${op.dhFatoGerador.substring(11, 16)}`],
              ['Local da operação', ctx.local.nomeMunicipio ? `${ctx.local.nomeMunicipio}/${ctx.local.uf} (IBGE ${ctx.local.municipio})` : String(op.municipio)],
              ['Documento', ctx.tpDoc ? (Hub.empresas.DOCUMENTOS[ctx.tpDoc] || ctx.tpDoc) : 'Não informado'],
              ['Itens calculados', `${linhas.length - comErro.length} de ${linhas.length}`],
              ['Base IBS/CBS', fmt.moeda(base)],
              ['CBS', fmt.moeda(vCBS)],
              ['IBS (UF + Município)', fmt.moeda(vIBS)],
            ].concat(vIS !== undefined ? [['Imposto Seletivo', fmt.moeda(vIS)]] : [], [['Total de tributos', fmt.moeda(total)]],
              d.cenarioCbs ? [['Cenário de CBS', `${d.cenarioCbs.rotulo}: ${String(d.cenarioCbs.aliquota).replace('.', ',')}% (oficial ${String(d.cenarioCbs.aliquotaOficial ?? '-').replace('.', ',')}%)`]] : []),
            colunas: x.colunas,
            linhas: x.linhas,
            observacoes: [
              r.aviso ? Hub.avisoSimulado(r.aviso) : null,
              d.cenarioCbs ? 'Cenário de CBS: simulação com alíquota diferente da oficial. Não usar em documento fiscal.' : null,
              ...comErro.map((l) => `Item ${l.n}: ${l.erros.map((e) => `${e.code} – ${e.detail}`).join(' | ')}`),
              'Valores calculados pela calculadora oficial da Receita Federal (Regime Regular).',
            ],
          };
        });
      }

      async function gerarXml(op, tipo) {
        // a geração de XML exige o resultado do cálculo estrito
        const r1 = await api.post(api.rg('/calculadora/regime-geral'), op);
        if (!r1.ok) { Hub.modal(Hub.problema(r1, 'Para gerar o XML todos os itens precisam ser calculados sem erro')); return; }
        const r2 = await api.post(api.rg(`/calculadora/xml/generate?tipo=${tipo}`), r1.data);
        if (!r2.ok) { Hub.modal(Hub.problema(r2, 'Falha ao gerar o XML')); return; }
        const xml = r2.texto;
        const c = Hub.modal(`<h2>XML do grupo de tributação (${esc(tipo.toUpperCase())})</h2>
          ${Hub.cenarioCbs.ativo() ? '<p class="sutil">O XML usa sempre a alíquota oficial da calculadora: o cenário de CBS vale só para simulação.</p>' : ''}
          <div class="acoes" style="margin-bottom:8px"><button class="btn btn-sec btn-peq" data-val>Validar no leiaute oficial</button>
          <button class="btn btn-sec btn-peq" data-copy>Copiar</button><button class="btn btn-sec btn-peq" data-down>Baixar</button><span data-st></span></div>
          <pre class="json">${esc(xml)}</pre>`);
        c.querySelector('[data-copy]').onclick = () => { navigator.clipboard.writeText(xml); Hub.toast('Copiado.'); };
        c.querySelector('[data-down]').onclick = () => Hub.baixarTexto(xml, `grupo-ibscbs-${tipo}.xml`, 'application/xml');
        c.querySelector('[data-val]').onclick = async () => {
          const st = c.querySelector('[data-st]');
          st.innerHTML = '<span class="sutil">validando...</span>';
          const rv = await api.post(api.rg(`/calculadora/xml/validate?tipo=${tipo}&subtipo=grupo`), xml, { contentType: 'application/xml' });
          st.innerHTML = rv.ok ? '<span class="etq etq-verde">XML válido</span>' : Hub.problema(rv, 'XML inválido');
        };
      }

      function exemplo() {
        lista.innerHTML = '';
        inpData.value = '2026-03-10';
        ajustarAliq();
        novoItem({ tipoNom: 'ncm', codNom: '10063021', cClassTrib: '200003', cst: '200', baseCalculo: '100,00', quantidade: '20', unidade: 'KG' });
        novoItem({ tipoNom: 'ncm', codNom: '73181500', cClassTrib: '000001', cst: '000', modoBase: 'comp', c_vProd: '1000,00', c_vFrete: '50,00', c_vDesc: '20,00', c_vICMS: '180,00', c_vPIS: '16,50', c_vCOFINS: '76,00', quantidade: '1', unidade: 'UN' });
        novoItem({ tipoNom: 'ncm', codNom: '30049099', cClassTrib: '200030', cst: '200', baseCalculo: '300,00', quantidade: '10', unidade: 'CX' });
        Array.from(lista.children).forEach((b) => b.querySelector('[name=modoBase]').onchange());
      }

      // ---------------------------------------------------------------- importação de XML/SPED
      const arqImp = el.querySelector('[data-arq-importar]');
      const importado = el.querySelector('[data-importado]');
      const num = (v) => (v === null || v === undefined || v === '' ? '' : String(v).replace('.', ','));
      el.querySelector('[data-importar]').onclick = () => arqImp.click();
      arqImp.onchange = async () => {
        if (!arqImp.files.length) return;
        const fd = new FormData();
        Array.from(arqImp.files).forEach((f) => fd.append('arquivos', f, f.name));
        arqImp.value = '';
        importado.innerHTML = '<div class="carregando"><div class="giro"></div>Lendo os documentos e sugerindo as classificações...</div>';
        const r = await comp.enviarComProgresso(`/hub/simulador/importar?data=${encodeURIComponent(inpData.value || Hub.hoje())}`, fd);
        if (!r.ok) { importado.innerHTML = Hub.problema(r, 'Não foi possível importar'); return; }
        const ops = r.data.operacoes || [];
        // depois de carregar o documento, pergunta se as empresas dos arquivos devem entrar no cadastro
        const perguntarEmpresas = () => Hub.empresas.perguntarCadastro(r.data.empresasEncontradas);
        if (!ops.length) { importado.innerHTML = '<div class="aviso">Nenhum documento com itens foi encontrado nos arquivos.</div>'; perguntarEmpresas(); return; }
        if (ops.length === 1) { carregarOperacao(ops[0]); perguntarEmpresas(); return; }
        importado.innerHTML = '';
        // vários documentos: cada um é uma operação (data e local próprios); o usuário escolhe qual simular
        const c = Hub.modal(`<h2>Escolha o documento para simular</h2>
          <p class="sutil">Foram lidos ${ops.length} documentos. Cada um tem data, local e itens próprios: escolha qual carregar no simulador.</p>
          <div class="tabela-wrap" style="max-height:60vh"><table class="tab"><thead><tr><th>Documento</th><th>Emissão</th><th>Emitente</th>
            <th class="num">Itens</th><th class="num">Sugeridos</th><th></th></tr></thead><tbody>
          ${ops.map((o, i) => `<tr><td>${esc(o.origem)}${o.numero ? ' nº ' + esc(o.numero) : ''}<div class="sutil">${esc(o.arquivo)}</div></td>
            <td>${esc(fmt.data(o.dataHora))}</td><td>${esc(o.emitenteNome || '')}<div class="sutil">${esc(fmt.cnpj(o.emitenteCnpj))}</div></td>
            <td class="num">${o.totalItens}</td><td class="num">${o.itens.filter((x) => x.origemClassificacao === 'SUGERIDO').length}</td>
            <td><button class="btn btn-peq" type="button" data-op="${i}">Carregar</button></td></tr>`).join('')}
          </tbody></table></div>`);
        c.querySelectorAll('[data-op]').forEach((b) => { b.onclick = () => { Hub.fecharModal(); carregarOperacao(ops[Number(b.dataset.op)]); perguntarEmpresas(); }; });
      };

      function carregarOperacao(o) {
        if (o.dataHora) {
          inpData.value = o.dataHora.substring(0, 10);
          if (o.dataHora.length >= 16) el.querySelector('[name=hora]').value = o.dataHora.substring(11, 16);
          ajustarAliq();
        }
        const selDoc = el.querySelector('[name=tpDoc]');
        if (o.tpDoc && Array.from(selDoc.options).some((x) => x.value === String(o.tpDoc))) selDoc.value = String(o.tpDoc);
        if (o.uf && o.municipio) loc.pronto.then(() => loc.definir(o.uf, o.municipio));
        lista.innerHTML = '';
        res.innerHTML = '';
        let sugeridos = 0;
        o.itens.forEach((it) => {
          const comComp = (it.baseCalculo === null || it.baseCalculo === undefined) && it.componentes && Object.keys(it.componentes).length;
          const dados = {
            tipoNom: it.nbs ? 'nbs' : it.ncm ? 'ncm' : '', codNom: it.nbs || it.ncm || '',
            cClassTrib: it.cClassTrib || '', cst: it.cst || '', quantidade: num(it.quantidade), unidade: it.unidade || '',
            modoBase: comComp ? 'comp' : 'base', baseCalculo: num(it.baseCalculo),
          };
          if (comComp) Object.entries(it.componentes).forEach(([k, v]) => { dados['c_' + k] = num(v); });
          const b = novoItem(dados);
          b.querySelector('.item-cab strong').insertAdjacentHTML('afterend',
            `<span class="item-desc">${esc([it.codigo, it.descricao].filter(Boolean).join(' – '))}${it.cfop ? ` · CFOP ${esc(it.cfop)}` : ''}</span>`);
          const inpCct = b.querySelector('[name=cClassTrib]');
          const info = b.querySelector('[data-sug-info]');
          if (it.origemClassificacao === 'SUGERIDO') {
            sugeridos++;
            inpCct.dataset.sugerido = '1';
            inpCct.title = 'Sugerido pelo NCM/NBS – não veio do documento';
            inpCct.addEventListener('input', () => { delete inpCct.dataset.sugerido; inpCct.title = ''; }, { once: true });
            info.innerHTML = ` <span class="etq etq-amarelo">cClassTrib sugerido – não veio do documento</span> ${esc(it.motivo || '')}`;
          } else if (it.origemClassificacao === 'SEM_CLASSIFICACAO') {
            info.innerHTML = ` <span class="etq etq-vermelho">Sem cClassTrib</span> ${esc(it.motivo || '')}`;
          } else {
            info.innerHTML = ' <span class="etq etq-verde">cClassTrib do documento</span>';
          }
        });
        if (!lista.children.length) novoItem();
        importado.innerHTML = `<div class="info-caixa"><strong>Importado:</strong> ${esc(o.origem)}${o.numero ? ' nº ' + esc(o.numero) : ''}
          ${o.dataHora ? ' de ' + esc(fmt.data(o.dataHora)) : ''}${o.emitenteNome ? ' · ' + esc(o.emitenteNome) : ''} – ${o.itens.length} item(ns)${o.totalItens > o.itens.length ? ` de ${o.totalItens}` : ''}.
          ${sugeridos ? `<br><strong>${sugeridos} item(ns) com cClassTrib sugerido</strong> (marcados em amarelo): o documento não trazia a classificação; confira antes de usar o resultado.` : ''}
          ${(o.avisos || []).length ? `<ul>${o.avisos.map((a) => `<li>${esc(a)}</li>`).join('')}</ul>` : ''}</div>`;
        Hub.toast('Documento carregado no simulador.');
      }

      el.querySelector('[data-add]').onclick = () => novoItem();
      el.querySelector('[data-calc]').onclick = calcular;
      el.querySelector('[data-exemplo]').onclick = exemplo;
      el.querySelector('[data-limpar]').onclick = () => { lista.innerHTML = ''; novoItem(); res.innerHTML = ''; };
      el.querySelector('[data-json-env]').onclick = () => comp.verJson(montarOperacao().op, 'Operação a enviar');

      const inicial = {};
      if (params.ncm) { inicial.tipoNom = 'ncm'; inicial.codNom = params.ncm; }
      if (params.nbs) { inicial.tipoNom = 'nbs'; inicial.codNom = params.nbs; }
      if (params.cClassTrib) { inicial.cClassTrib = params.cClassTrib; inicial.cst = params.cClassTrib.substring(0, 3); }
      novoItem(inicial);
    },
  });
})();
