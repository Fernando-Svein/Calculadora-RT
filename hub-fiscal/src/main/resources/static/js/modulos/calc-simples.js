/* Simulador do Simples Nacional (cálculo não assistido – todos os documentos). */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;

  const DOCS = [['55', 'NF-e (55)'], ['65', 'NFC-e (65)'], ['91', 'NFS-e (91)'], ['57', 'CT-e (57)'], ['67', 'CT-e OS (67)'],
    ['63', 'BP-e (63)'], ['93', 'BP-e TM (93)'], ['62', 'NFCom (62)']];
  const ANEXOS = ['I', 'II', 'III', 'IV', 'V'];
  const COM_ISS = new Set(['III', 'IV', 'V']);

  /** Número digitado (pt-BR) → texto com ponto decimal, como a calculadora espera. */
  function txt(v) {
    const n = Hub.numero(v);
    return n === null ? undefined : String(n);
  }

  Hub.registrar({
    rota: 'calc-sn',
    titulo: 'Simulador Simples Nacional',
    grupo: 'Calculadoras – Simples Nacional',
    icone: '🧮',
    ordem: 1,
    etiquetas: ['Simples'],
    descricao: 'Calcula CBS e IBS no Simples Nacional para NF-e, NFC-e, CT-e, BP-e, NFCom e NFS-e, com escolha de anexo, fator r e ISS.',
    render(el) {
      const dataInicial = Hub.hoje() < '2027-01-01' ? '2027-01-01' : Hub.hoje();
      el.innerHTML = comp.cabecalho('Simulador – Simples Nacional',
        'Usa a calculadora oficial do Simples Nacional. Informe, para cada anexo em que a empresa atua, quanto da alíquota efetiva do DAS ' +
        'corresponde à CBS e ao IBS no período. A calculadora escolhe o anexo pela atividade e pelo fator r, e decide qual linha de alíquotas ' +
        'usar conforme a situação do ISS.') +
        `<div data-empresa></div>
        <div class="card"><div class="card-cab"><h3>Operação</h3><div class="acoes">
            <button class="btn btn-sec btn-peq" data-ex-nfe>Exemplo NF-e</button><button class="btn btn-sec btn-peq" data-ex-nfse>Exemplo NFS-e</button>
            <button class="btn btn-sec btn-peq" data-json-env>Ver dados técnicos</button></div></div>
          <div class="grade g4">
            ${comp.campo('Data do fato gerador *', `<input type="date" name="data" value="${dataInicial}">`)}
            ${comp.campo('Documento *', `<select name="tpDoc">${DOCS.map(([v, t]) => `<option value="${v}">${t}</option>`).join('')}</select>`)}
            ${comp.campo('Venda para órgão público', `<select name="tpEnteGov"><option value="">Não</option><option value="1">Sim – União</option><option value="2">Sim – Estado</option><option value="3">Sim – Distrito Federal</option><option value="4">Sim – Município</option></select>`)}
          </div>
          <div class="grade g4 u-mt-12" data-crt>
            ${comp.campo('Regime tributário do emitente (CRT) *', `<select name="CRT"><option value="1">1 – Simples Nacional</option><option value="2">2 – Simples Nacional, com excesso de sublimite</option>
              <option value="3">3 – Regime normal</option><option value="4">4 – MEI</option></select>`)}
            ${comp.campo('Como a empresa recolhe o IBS e a CBS', `<select name="tpRegimeIBSCBS"><option value="">Não informar (segue o CRT)</option>
              <option value="1">Optou por recolher fora do Simples (regime regular)</option><option value="2">Pediu o Simples e a opção ainda está pendente</option></select>`,
              '“Fora do Simples” vale para CRT 1 ou 2: a nota leva só a receita, sem calcular IBS/CBS. “Opção pendente” vale para CRT 3: os valores ficam suspensos.')}
          </div>
          <div class="grade g4 u-mt-12" data-nfse hidden>
            ${comp.campo('Situação do prestador no Simples *', `<select name="opSimpNac"><option value="3">Optante – ME ou EPP</option><option value="1">Não optante</option>
              <option value="2">MEI</option><option value="4">Opção pelo Simples pendente</option></select>`)}
            ${comp.campo('Como recolhe o IBS e a CBS', `<select name="regApIBSCBSSN"><option value="1">IBS e CBS dentro do Simples</option>
              <option value="2">CBS no Simples e IBS fora (excesso de sublimite)</option><option value="3">IBS e CBS fora do Simples (regime regular)</option></select>`)}
            ${comp.campo('Como recolhe os tributos federais e o ISS', `<select name="regApTribSN"><option value="1">Federais e ISS dentro do Simples</option>
              <option value="2">Federais no Simples e ISS fora</option><option value="3">Federais e ISS fora do Simples</option></select>`,
              'Não muda o regime do IBS/CBS: só define qual linha de alíquotas é usada (com ou sem a parcela do ISS).')}
          </div>
        </div>
        <div class="card"><h3>Alíquotas efetivas do período (parcelas de CBS e IBS dentro do DAS, em %)</h3>
          <div class="acoes u-mb-10">
            <label class="check"><input type="checkbox" name="fatorR"> Fator r igual ou maior que 28% (atividades sujeitas ao fator r vão para o Anexo III)</label>
            <label class="check"><input type="checkbox" name="impedimentoSublimite"> Ultrapassou o sublimite estadual (IBS e ISS saem do Simples)</label>
          </div>
          <div class="tabela-wrap u-maxh-none"><table class="tab tab-aliq"><thead>
            <tr><th rowspan="2">Anexo</th><th colspan="2">Alíquota normal</th><th colspan="2">Quando o ISS não é recolhido no DAS</th></tr>
            <tr><th>CBS (%)</th><th>IBS (%)</th><th>CBS (%)</th><th>IBS (%)</th></tr></thead><tbody>
            ${ANEXOS.map((a) => `<tr><td><strong>${a}</strong></td>
              <td><input name="a${a}_cbs" placeholder="ex.: 1,1612" aria-label="Anexo ${a}: CBS, alíquota normal (%)"></td><td><input name="a${a}_ibs" placeholder="ex.: 0,0129" aria-label="Anexo ${a}: IBS, alíquota normal (%)"></td>
              <td>${COM_ISS.has(a) ? `<input name="a${a}_scbs" aria-label="Anexo ${a}: CBS quando o ISS não é recolhido no DAS (%)">` : '<span class="sutil">–</span>'}</td>
              <td>${COM_ISS.has(a) ? `<input name="a${a}_sibs" aria-label="Anexo ${a}: IBS quando o ISS não é recolhido no DAS (%)">` : '<span class="sutil">–</span>'}</td></tr>`).join('')}
          </tbody></table></div>
          <p class="sutil u-mt-8">Preencha só os anexos das atividades da empresa, com todas as casas decimais da apuração do PGDAS-D.
            Nos anexos III, IV e V informe também a linha usada quando o ISS é retido, isento, não incide ou é recolhido fora do Simples.</p>
        </div>
        <div class="card"><div class="card-cab"><h3>Itens</h3><button class="btn btn-sec btn-peq" data-add>+ Adicionar item</button></div>
          <div class="itens-lista" data-itens></div></div>
        <div class="acoes u-mb-16"><button class="btn" data-calc>Calcular</button></div>
        <div data-res></div>`;

      const cards = el.querySelectorAll('.card');
      const cardOp = cards[0];
      const cardAliq = cards[1];
      const lista = el.querySelector('[data-itens]');
      const res = el.querySelector('[data-res]');
      const selDoc = el.querySelector('[name=tpDoc]');
      const inpData = el.querySelector('[name=data]');
      let atividades = [];

      async function carregarAtividades() {
        atividades = await Hub.atividadesSimples(inpData.value, selDoc.value);
        lista.querySelectorAll('select[name=cAtivSN]').forEach((s) => preencherAtividades(s, s.value));
        // a atividade da empresa só pode ser marcada depois que a lista do documento/data carregou
        if (emp && emp.empresa() && emp.empresa().atividadeSimples) {
          lista.querySelectorAll('select[name=cAtivSN]').forEach((s) => { if (!s.value) Hub.empresas.definir(s, emp.empresa().atividadeSimples); });
        }
      }

      // empresa da simulação: CRT, forma de recolher o IBS/CBS, fator r, sublimite, alíquotas do DAS por anexo,
      // atividade, documento e cClassTrib usuais vêm do cadastro
      let emp = null;
      let padrao = { atividade: null, cct: null };
      function aplicarEmpresa(e) {
        padrao = { atividade: (e && e.atividadeSimples) || null, cct: (e && e.cClassTribPadrao) || null };
        if (!e) return {};
        const D = Hub.empresas.definir;
        const q = (n) => el.querySelector(`[name="${n}"]`);
        const usadas = [];
        const simples = Hub.empresas.ehSimples(e);
        const docAntes = selDoc.value;
        if (D(selDoc, e.documentoPadrao)) usadas.push('documento usual ' + (Hub.empresas.DOCUMENTOS[e.documentoPadrao] || e.documentoPadrao));
        if (e.regime) {
          D(q('CRT'), Hub.empresas.CRT[e.regime]);
          q('tpRegimeIBSCBS').value = '';
          if (simples && e.ibscbsForaSimples) D(q('tpRegimeIBSCBS'), '1');
          D(q('opSimpNac'), Hub.empresas.OP_SIMP_NAC[e.regime]);
          D(q('regApIBSCBSSN'), e.ibscbsForaSimples ? '3' : (e.regime === 'SIMPLES_EXCESSO' || e.sublimiteExcedido) ? '2' : '1');
          usadas.push('regime ' + Hub.empresas.regimeTexto(e));
        }
        if (simples) {
          D(cardAliq.querySelector('[name=fatorR]'), !!e.fatorR);
          D(cardAliq.querySelector('[name=impedimentoSublimite]'), !!e.sublimiteExcedido);
          usadas.push(`fator r ${e.fatorR ? 'igual ou maior que 28%' : 'abaixo de 28%'}`);
          if (e.sublimiteExcedido) usadas.push('sublimite estadual ultrapassado');
          const aliq = e.aliquotasSimples || {};
          if (Object.keys(aliq).length) {
            // as alíquotas da empresa substituem toda a tabela (anexos sem valor ficam em branco)
            ANEXOS.forEach((x) => ['cbs', 'ibs', 'scbs', 'sibs'].forEach((c) => { const i = cardAliq.querySelector(`[name=a${x}_${c}]`); if (i) i.value = ''; }));
            const nomes = { cbs: 'cbs', ibs: 'ibs', cbsSemIss: 'scbs', ibsSemIss: 'sibs' };
            Object.entries(aliq).forEach(([x, p]) => Object.entries(p).forEach(([c, v]) => D(cardAliq.querySelector(`[name=a${x}_${nomes[c]}]`), String(v).replace('.', ','))));
            usadas.push('alíquotas do DAS dos anexos ' + Object.keys(aliq).join(', '));
          }
        }
        if (padrao.cct) {
          lista.querySelectorAll('[name=cClassTrib]').forEach((i) => { if (!i.value || i.value === '000001') D(i, padrao.cct); });
          usadas.push('cClassTrib usual ' + padrao.cct);
        }
        if (padrao.atividade) usadas.push('atividade ' + padrao.atividade);
        if (selDoc.value !== docAntes) ajustarDoc(); else carregarAtividades();
        let aviso = '';
        if (!simples && e.regime === 'NORMAL') aviso = 'Empresa do regime normal: use o <a href="#/calc-rg">Simulador Regime Regular</a>. Esta calculadora só atende optantes do Simples.';
        if (e.regime === 'MEI') aviso = 'MEI recolhe valores fixos no DAS-MEI: a calculadora do Simples não calcula IBS/CBS por documento para o MEI.';
        if (simples && !Object.keys(e.aliquotasSimples || {}).length) aviso = 'O cadastro ainda não tem as alíquotas do DAS: preencha-as na tabela abaixo ou no <a href="#/empresas?id=' + encodeURIComponent(e.id) + '">cadastro da empresa</a>.';
        return { usadas, aviso };
      }

      function preencherAtividades(sel, valor) {
        const v = valor || sel.dataset.desejado;
        sel.innerHTML = '<option value="">Selecione</option>' + atividades.map((a) =>
          `<option value="${a.codigo}">${a.codigo} – ${esc(a.descricao.length > 90 ? a.descricao.substring(0, 89) + '…' : a.descricao)} (Anexo ${esc(a.rotuloCurtoAnexo || a.rotuloAnexo)})</option>`).join('');
        if (v) sel.value = v;
        sel.onchange = () => { sel.dataset.desejado = sel.value; };
      }

      function ajustarDoc() {
        const nfse = selDoc.value === '91';
        el.querySelector('[data-crt]').hidden = nfse;
        el.querySelector('[data-nfse]').hidden = !nfse;
        lista.querySelectorAll('[data-so-nfse]').forEach((x) => { x.hidden = !nfse; });
        lista.querySelectorAll('[data-so-crt]').forEach((x) => { x.hidden = nfse; });
        el.querySelector('[data-add]').disabled = nfse && lista.children.length >= 1;
        carregarAtividades();
      }

      function novoItem(d = {}) {
        // itens novos já vêm com a atividade e a cClassTrib usuais da empresa selecionada
        d = Object.assign({}, padrao.atividade ? { cAtivSN: padrao.atividade } : {}, padrao.cct ? { cClassTrib: padrao.cct } : {}, d);
        const b = Hub.el(`<div class="item-bloco">
          <div class="item-cab"><strong>Item ${lista.children.length + 1}</strong><span class="espaco"></span><button class="btn btn-perigo btn-peq" data-rm>Remover</button></div>
          <div class="grade g4">
            ${comp.campo('Atividade no Simples *', '<select name="cAtivSN"></select>')}
            ${comp.campo('Classificação tributária (cClassTrib) *', '<input name="cClassTrib" value="000001">')}
            ${comp.campo('Nomenclatura', '<select name="tipoNom"><option value="">Nenhuma</option><option value="ncm">NCM</option><option value="nbs">NBS</option></select>')}
            ${comp.campo('Código NCM/NBS', '<input name="codNom">')}
            ${comp.campo('Base de cálculo (R$) *', '<input name="baseCalculo" placeholder="0,00">', 'Mercadorias: produtos + frete + seguro + outras despesas − descontos (sem o Imposto Seletivo). NFS-e: valor do serviço.')}
            <div data-so-crt>${comp.campo('Imposto Seletivo da nota (R$)', '<input name="valorImpostoSeletivo" value="0,00">')}</div>
          </div>
          <div class="grade g4 u-mt-8" data-so-nfse hidden>
            ${comp.campo('Tributação do ISS', '<select name="tribISSQN"><option value="1">Tributável</option><option value="2">Imunidade</option><option value="3">Exportação de serviço</option><option value="4">Não incidência</option></select>')}
            ${comp.campo('Retenção do ISS', '<select name="tpRetISSQN"><option value="1">Não retido</option><option value="2">Retido pelo tomador</option><option value="3">Retido pelo intermediário</option></select>')}
            ${comp.campo('Benefício municipal do ISS', '<select name="tpBM"><option value="">Nenhum</option><option value="1">Isenção</option><option value="2">Redução da base em %</option><option value="3">Redução da base em R$</option><option value="4">Alíquota diferenciada</option></select>')}
          </div></div>`);
        lista.appendChild(b);
        b.querySelector('[name=cAtivSN]').dataset.desejado = d.cAtivSN || '';
        preencherAtividades(b.querySelector('[name=cAtivSN]'), d.cAtivSN);
        for (const [k, v] of Object.entries(d)) {
          const i = b.querySelector(`[name="${k}"]`);
          if (i && k !== 'cAtivSN') i.value = v;
        }
        comp.autocompletarNomenclatura(b.querySelector('[name=codNom]'), () => b.querySelector('[name=tipoNom]').value || 'ncm');
        b.querySelector('[data-rm]').onclick = () => { b.remove(); ajustarDoc(); };
        ajustarDoc();
      }

      function montar() {
        const f = comp.lerCampos(cardOp);
        const a = comp.lerCampos(cardAliq);
        const nfse = f.tpDoc === '91';
        const op = { id: 'hub-' + Date.now(), versao: '3.0.0', data: f.data, tpDoc: f.tpDoc };
        if (nfse) {
          op.opSimpNac = f.opSimpNac;
          if (['3', '4'].includes(f.opSimpNac)) { op.regApIBSCBSSN = f.regApIBSCBSSN; op.regApTribSN = f.regApTribSN; }
        } else {
          op.CRT = f.CRT;
          if (f.tpRegimeIBSCBS) op.tpRegimeIBSCBS = f.tpRegimeIBSCBS;
        }
        if (f.tpEnteGov) op.tpEnteGov = f.tpEnteGov;
        const ali = { fatorR: !!a.fatorR, impedimentoSublimite: !!a.impedimentoSublimite };
        let algum = false;
        ANEXOS.forEach((x) => {
          const cbs = txt(a[`a${x}_cbs`]);
          const ibs = txt(a[`a${x}_ibs`]);
          if (cbs === undefined && ibs === undefined) return;
          algum = true;
          const par = { pCBSSN: cbs, pIBSSN: ibs };
          const scbs = txt(a[`a${x}_scbs`]);
          const sibs = txt(a[`a${x}_sibs`]);
          if (scbs !== undefined || sibs !== undefined) par.semRedistribuicaoISS = { pCBSSN: scbs, pIBSSN: sibs };
          ali['anexo' + x] = par;
        });
        if (algum || a.fatorR || a.impedimentoSublimite) op.aliquotasEfetivas = ali;
        op.itens = Array.from(lista.children).map((b, i) => {
          const c = comp.lerCampos(b);
          const it = { numero: i + 1, cAtivSN: c.cAtivSN, cClassTrib: c.cClassTrib, baseCalculo: txt(c.baseCalculo) };
          const cod = (c.codNom || '').replace(/\D/g, '');
          if (cod && c.tipoNom) it[c.tipoNom] = cod;
          if (nfse) {
            it.tribISSQN = c.tribISSQN;
            if (c.tribISSQN === '1') it.tpRetISSQN = c.tpRetISSQN;
            if (c.tpBM) it.tpBM = c.tpBM;
          } else {
            it.valorImpostoSeletivo = Number(Hub.numero(c.valorImpostoSeletivo) || 0).toFixed(2);
          }
          if (it.baseCalculo !== undefined) it.baseCalculo = Number(it.baseCalculo).toFixed(2);
          return it;
        });
        return op;
      }

      async function calcular() {
        const op = montar();
        if (!op.itens.length) { Hub.toast('Inclua ao menos um item.', 'erro'); return; }
        const fora = foraDoEscopo(op);
        if (fora) { res.innerHTML = `<div class="aviso"><strong>Esta operação não é calculada pela calculadora do Simples.</strong><br>${fora}</div>`; return; }
        Hub.carregando(res, 'Calculando na calculadora do Simples Nacional...');
        const ctx = { empresa: emp.empresa(), usadas: emp.usadas() };
        const r = await api.post(api.sn('/calculadora/observabilidade/simples-nacional/nao-assistido'), op);
        if (!r.ok) {
          res.innerHTML = Hub.problema(r, 'A calculadora rejeitou a operação',
            'combinação de documento e regime que a calculadora não cobre (ex.: documento não suportado, regime do emitente (CRT) incompatível com a forma de recolher o IBS/CBS) ou data sem alíquotas cadastradas.');
          return;
        }
        mostrar(r.data, op, ctx);
      }

      /** Situações que a calculadora oficial recusa sem mensagem (HTTP 422 vazio). */
      function foraDoEscopo(op) {
        if (op.data < '2027-01-01') {
          return 'O IBS e a CBS passam a ser recolhidos pelo Simples Nacional a partir de 2027. Em 2026 o optante não destaca esses tributos. Use uma data de 2027 em diante.';
        }
        if (op.CRT === '3') return 'CRT 3 é regime normal: use o <a href="#/calc-rg">Simulador Regime Regular</a>.';
        if (op.CRT === '4') return 'O MEI recolhe valores fixos mensais no DAS-MEI e não calcula IBS/CBS por documento.';
        if (op.opSimpNac === '1') return 'Prestador não optante: use o <a href="#/calc-rg">Simulador Regime Regular</a>.';
        if (op.opSimpNac === '2') return 'O MEI recolhe valores fixos mensais no DAS-MEI e não calcula IBS/CBS por documento.';
        return null;
      }

      function mostrar(d, op, ctx) {
        const t = d.total || {};
        const resol = (d.resolucaoAliquotas && d.resolucaoAliquotas.itens) || [];
        // Na NFS-e a calculadora usa a alíquota com 2 casas (regra do leiaute, NT 2026.005)
        const arred = op && op.tpDoc === '91' ? resol.filter((x) => [x.pCBSSN, x.pIBSSN].some((p) => p && /\.\d{3,}/.test(String(p)) && !/\.\d{2}0+$/.test(String(p)))) : [];
        if (arred.length) {
          d.avisos = [].concat(d.avisos || [], [`Na NFS-e a calculadora oficial usa a alíquota efetiva com 2 casas decimais (regra do leiaute – NT 2026.005): ` +
            arred.map((x) => { const v = (p) => String(p).replace('.', ','); return `CBS ${v(x.pCBSSN)}% → ${v(Number(x.pCBSSN).toFixed(2))}%, IBS ${v(x.pIBSSN)}% → ${v(Number(x.pIBSSN).toFixed(2))}%`; }).join('; ') +
            '. O valor da nota pode diferir do apurado no PGDAS-D com a alíquota completa.']);
        }
        const estados = { CALCULADO: 'etq-verde', NAO_CALCULADO: 'etq-amarelo', INCONSISTENCIA_ENTRADA: 'etq-vermelho', NAO_IMPLEMENTADO: 'etq-amarelo' };
        const R = Hub.rotulos;
        const linhas = (d.objetos || []).map((o) => {
          const tc = o.tribCalc || {};
          const rs = resol.find((x) => x.numero === o.nObj) || {};
          return Object.assign({ n: o.nObj, estado: o.estadoItem, motivoEstado: o.motivoEstado, erros: o.erros || [], memoria: tc.memoriaCalculo, rs }, tc);
        });
        const avisosGerais = [].concat((d.resolucaoAliquotas && d.resolucaoAliquotas.avisos) || [], d.avisos || []);
        res.innerHTML = `<div class="card"><div class="card-cab"><h2>Resultado</h2>
            <div class="acoes"><span class="etq etq-teal" title="Regime de apuração identificado pela calculadora">${esc(R.regimeSimples(d.oper && d.oper.regimeApuracao))}</span>
            ${Hub.relatorio.botoes()}
            <button class="btn btn-sec btn-peq" data-json>Ver resposta técnica</button></div></div>
          ${Hub.empresas.faixaResultado(ctx.empresa)}
          ${avisosGerais.length ? `<div class="aviso">${avisosGerais.map((a) => esc(typeof a === 'object' ? (a.mensagem || a.detail || JSON.stringify(a)) : a)).join('<br>')}</div>` : ''}
          <div class="kpis">
            <div class="kpi"><small>Receita bruta do Simples</small><strong>${fmt.moeda(t.vRBSNtot)}</strong></div>
            <div class="kpi"><small>Base de cálculo</small><strong>${fmt.moeda(t.vBC)}</strong></div>
            <div class="kpi"><small>CBS devida</small><strong>${fmt.moeda(t.vCBSSNtot)}</strong></div>
            <div class="kpi"><small>IBS devido</small><strong>${fmt.moeda(t.vIBSSNtot)}</strong></div>
            <div class="kpi"><small>Pendente/suspenso</small><strong>${fmt.moeda(Number(t.vCBSSNtotPendSusp || 0) + Number(t.vIBSSNtotPendSusp || 0))}</strong></div>
            <div class="kpi destaque"><small>Total de tributos</small><strong>${fmt.moeda(t.totalTributos)}</strong></div>
          </div><div data-tab></div>
          <p class="sutil">Clique num item para ver por que essas alíquotas foram usadas, a memória de cálculo e os erros.</p></div>`;
        const tab = comp.tabela(res.querySelector('[data-tab]'), {
          busca: false,
          colunas: [
            { titulo: 'Item', campo: 'n', tipo: 'num' },
            { titulo: 'Situação', campo: 'estado', render: (l) => `<span class="etq ${estados[l.estado] || ''}">${esc(R.estado(l.estado))}</span>`, exportar: (l) => R.estado(l.estado) },
            { titulo: 'Atividade', exportar: (l) => l.rs.cAtivSN },
            { titulo: 'Anexo', exportar: (l) => l.rs.anexoAplicado },
            { titulo: 'Alíquota usada', exportar: (l) => R.tipoAliquotaSimples(l.rs.tipoAliquota) },
            { titulo: 'Tipo de receita', exportar: (l) => l.tpRBSNDescricao || (l.tpRBSN ?? '') },
            { titulo: 'Receita', campo: 'vRBSNitem', tipo: 'moeda' },
            { titulo: 'Base', campo: 'vBC', tipo: 'moeda' },
            { titulo: 'Alíq. CBS', campo: 'pCBSSN', tipo: 'pct' },
            { titulo: 'CBS', campo: 'vCBSSN', tipo: 'moeda' },
            { titulo: 'Alíq. IBS', campo: 'pIBSSN', tipo: 'pct' },
            { titulo: 'IBS', campo: 'vIBSSN', tipo: 'moeda' },
            { titulo: 'Pendente/suspenso', exportar: (l) => Number(l.vCBSPendSusp || 0) + Number(l.vIBSPendSusp || 0), tipo: 'moeda' },
            { titulo: 'Total', campo: 'totalItem', tipo: 'moeda' },
          ],
          linhas,
          // a exportação da tabela agora sai pelos botões "Relatório PDF / Excel" (com empresa e resumo)
          detalhe: (l) => {
            const rs = l.rs || {};
            const mem = l.memoria ? Object.entries(l.memoria).map(([k, v]) => `<div class="memoria"><strong>${esc(R.memoria(k))}:</strong> ${esc(v)}</div>`).join('') : '';
            const avisos = (rs.avisos || []).map((a) => `<li>${esc(typeof a === 'object' ? (a.mensagem || a.codigo || JSON.stringify(a)) : a)}</li>`).join('');
            const motivo = l.motivoEstado && l.motivoEstado.descricao ? `<div class="aviso">${esc(l.motivoEstado.descricao)}</div>` : '';
            const errs = l.erros.length ? `<div class="erro-caixa"><ul>${l.erros.map((e) => `<li><strong>${esc(e.code)}</strong> – ${esc(e.title || '')}: ${esc(e.detail)}</li>`).join('')}</ul></div>` : '';
            return `<dl class="dl"><dt>Alíquota usada</dt><dd>${esc(R.tipoAliquotaSimples(rs.tipoAliquota) || '–')}</dd>
                <dt>Por quê</dt><dd>${esc(R.regraSimples(rs.regraAliquota) || '–')}</dd>
                <dt>Resultado da busca</dt><dd>${esc(R.motivoSimples(rs.motivo) || '–')}</dd></dl>
              ${motivo}${avisos ? `<div class="aviso"><ul>${avisos}</ul></div>` : ''}${mem}${errs}`;
          },
        });
        res.querySelector('[data-json]').onclick = () => comp.verJson(d, 'Resposta da calculadora do Simples');
        Hub.relatorio.ligar(res, async () => {
          const x = tab.exportacao();
          const comErro = linhas.filter((l) => l.erros.length);
          const regime = op.tpDoc === '91'
            ? { 1: 'Não optante', 2: 'MEI', 3: 'Optante – ME ou EPP', 4: 'Opção pelo Simples pendente' }[op.opSimpNac] || op.opSimpNac
            : { 1: '1 – Simples Nacional', 2: '2 – Simples Nacional, excesso de sublimite', 3: '3 – Regime normal', 4: '4 – MEI' }[op.CRT] || op.CRT;
          const anexos = Object.keys((op.aliquotasEfetivas || {})).filter((k) => /^anexo/.test(k)).map((k) => {
            const p = op.aliquotasEfetivas[k];
            return [`Alíquotas do DAS – Anexo ${k.substring(5)}`, `CBS ${String(p.pCBSSN ?? '–').replace('.', ',')}% · IBS ${String(p.pIBSSN ?? '–').replace('.', ',')}%` +
              (p.semRedistribuicaoISS ? ` (sem ISS no DAS: CBS ${String(p.semRedistribuicaoISS.pCBSSN ?? '–').replace('.', ',')}% · IBS ${String(p.semRedistribuicaoISS.pIBSSN ?? '–').replace('.', ',')}%)` : '')];
          });
          return {
            titulo: 'Simulação – Simples Nacional (CBS e IBS)',
            arquivo: 'simulacao-simples-nacional',
            empresa: await Hub.empresas.linhasRelatorio(ctx.empresa, ctx.usadas),
            resumo: [
              ['Data do fato gerador', fmt.data(op.data)],
              ['Documento', Hub.empresas.DOCUMENTOS[op.tpDoc] || op.tpDoc],
              [op.tpDoc === '91' ? 'Situação do prestador no Simples' : 'Regime do emitente (CRT)', regime],
              ['Regime de apuração identificado', R.regimeSimples(d.oper && d.oper.regimeApuracao) || '–'],
              ['Fator r igual ou maior que 28%', op.aliquotasEfetivas && op.aliquotasEfetivas.fatorR ? 'Sim' : 'Não'],
              ...anexos,
              ['Receita bruta do Simples', fmt.moeda(t.vRBSNtot)],
              ['Base de cálculo', fmt.moeda(t.vBC)],
              ['CBS devida', fmt.moeda(t.vCBSSNtot)],
              ['IBS devido', fmt.moeda(t.vIBSSNtot)],
              ['Pendente/suspenso', fmt.moeda(Number(t.vCBSSNtotPendSusp || 0) + Number(t.vIBSSNtotPendSusp || 0))],
              ['Total de tributos', fmt.moeda(t.totalTributos)],
            ],
            colunas: x.colunas,
            linhas: x.linhas,
            observacoes: [
              ...avisosGerais.map((a) => (typeof a === 'object' ? (a.mensagem || a.detail || JSON.stringify(a)) : a)),
              ...comErro.map((l) => `Item ${l.n}: ${l.erros.map((e) => `${e.code} – ${e.detail}`).join(' | ')}`),
              'Valores calculados pela calculadora oficial do Simples Nacional da Receita Federal.',
            ],
          };
        });
      }

      function exemploNfe() {
        selDoc.value = '55';
        el.querySelector('[name=CRT]').value = '1';
        cardAliq.querySelector('[name=fatorR]').checked = true;
        cardAliq.querySelector('[name=aI_cbs]').value = '1,1612';
        cardAliq.querySelector('[name=aI_ibs]').value = '0,0129';
        lista.innerHTML = '';
        novoItem({ cAtivSN: '01', cClassTrib: '000001', baseCalculo: '1000,00', valorImpostoSeletivo: '0,00' });
      }

      function exemploNfse() {
        selDoc.value = '91';
        el.querySelector('[name=opSimpNac]').value = '3';
        el.querySelector('[name=regApIBSCBSSN]').value = '1';
        el.querySelector('[name=regApTribSN]').value = '1';
        cardAliq.querySelector('[name=aIII_cbs]').value = '2,3874';
        cardAliq.querySelector('[name=aIII_ibs]').value = '0,0263';
        cardAliq.querySelector('[name=aIII_scbs]').value = '2,3591';
        cardAliq.querySelector('[name=aIII_sibs]').value = '0,0260';
        lista.innerHTML = '';
        novoItem({ cAtivSN: '12', cClassTrib: '000001', tipoNom: 'nbs', codNom: '101011100', baseCalculo: '10000,00', tribISSQN: '1', tpRetISSQN: '1', tpBM: '1' });
      }

      selDoc.onchange = ajustarDoc;
      inpData.onchange = carregarAtividades;
      el.querySelector('[data-add]').onclick = () => novoItem();
      el.querySelector('[data-calc]').onclick = calcular;
      el.querySelector('[data-ex-nfe]').onclick = exemploNfe;
      el.querySelector('[data-ex-nfse]').onclick = exemploNfse;
      el.querySelector('[data-json-env]').onclick = () => comp.verJson(montar(), 'Operação a enviar');
      novoItem();
      emp = Hub.empresas.seletor(el.querySelector('[data-empresa]'), { raiz: el, aoMudar: aplicarEmpresa });
    },
  });
})();
