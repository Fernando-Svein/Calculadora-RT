/* Comparador: IBS/CBS dentro do Simples × pelo regime regular (opção do art. 41 da LC 214/2025). */
'use strict';

(() => {
  const { esc, fmt, api, comp, numero } = Hub;

  Hub.registrar({
    rota: 'comparador',
    titulo: 'Comparador Simples × Regular',
    grupo: 'Calculadoras – Simples Nacional',
    icone: '⚖️',
    ordem: 2,
    etiquetas: ['Simples', 'Regime Regular'],
    descricao: 'Apoio à decisão do optante do Simples: recolher IBS/CBS dentro do DAS ou pelo regime regular (créditos para clientes e sobre compras).',
    render(el) {
      const dataInicial = Hub.hoje() < '2027-01-01' ? '2027-01-01' : Hub.hoje();
      el.innerHTML = comp.cabecalho('Comparador: IBS/CBS no Simples × no regime regular',
        'Simulação simplificada para o optante do Simples Nacional que pode recolher o IBS e a CBS pelo regime regular. ' +
        'Compara o valor recolhido, os créditos sobre compras e o crédito que cada opção transfere aos clientes contribuintes. ' +
        'Os dois cenários são calculados nas calculadoras oficiais.') +
        `<div class="card"><h3>Dados do período</h3>
          <div class="grade g4">
            ${comp.campo('Data de referência *', `<input type="date" name="data" min="2027-01-01" value="${dataInicial}">`)}
            ${comp.campo('Tipo de receita', '<select name="tipo"><option value="55">Venda de mercadorias (NF-e)</option><option value="91">Prestação de serviços (NFS-e)</option></select>')}
            ${comp.campo('Faturamento do período (R$) *', '<input name="faturamento" value="100.000,00">')}
            ${comp.campo('% das vendas para contribuintes (B2B)', '<input name="b2b" value="70">', 'Clientes que aproveitam crédito de IBS/CBS.')}
            ${comp.campo('Compras com crédito (R$)', '<input name="compras" value="40.000,00">', 'Aquisições tributadas que gerariam crédito no regime regular.')}
            ${comp.campo('cClassTrib das vendas', '<input name="cClassTrib" value="000001">')}
            ${comp.campo('NCM/NBS principal', '<input name="nom" placeholder="opcional">')}
          </div>
          <div data-loc style="margin-top:12px"></div>
        </div>
        <div class="grade g2">
          <div class="card"><h3>Cenário A – IBS/CBS dentro do Simples</h3>
            <div class="grade g2">
              ${comp.campo('Atividade (cAtivSN) *', '<select name="cAtivSN"></select>')}
              ${comp.campo('Anexo da atividade', '<input name="anexo" readonly>')}
              ${comp.campo('pCBSSN (parcela CBS da alíq. efetiva) *', '<input name="pCBSSN" placeholder="ex.: 1,1612">')}
              ${comp.campo('pIBSSN (parcela IBS da alíq. efetiva) *', '<input name="pIBSSN" placeholder="ex.: 0,0129">')}
            </div>
            <label class="check" style="margin-top:8px"><input type="checkbox" name="fatorR"> Fator r ≥ 28%</label>
          </div>
          <div class="card"><h3>Cenário B – IBS/CBS pelo regime regular</h3>
            <div class="grade g3">
              ${comp.campo('CBS %', '<input name="aCbs" value="8,8">')}
              ${comp.campo('IBS UF %', '<input name="aIbsUf" value="0,05">')}
              ${comp.campo('IBS Mun %', '<input name="aIbsMun" value="0,05">')}
            </div>
            <p class="sutil" style="margin-top:8px">Alíquotas nominais estimadas para o ano; confira as vigentes. Em 2027–2028 o IBS ainda é de teste; a comparação fica mais relevante com as alíquotas cheias (2033).</p>
          </div>
        </div>
        <div class="acoes" style="margin-bottom:16px"><button class="btn" data-calc>Comparar</button></div>
        <div data-res></div>`;

      const loc = comp.seletorMunicipio(el.querySelector('[data-loc]'), { uf: 'SP', municipio: 3550308 });
      const selAtiv = el.querySelector('[name=cAtivSN]');
      const inpData = el.querySelector('[name=data]');
      const selTipo = el.querySelector('[name=tipo]');
      const res = el.querySelector('[data-res]');
      let atividades = [];

      async function carregar() {
        atividades = await Hub.atividadesSimples(inpData.value, selTipo.value);
        selAtiv.innerHTML = atividades.map((a) => `<option value="${a.codigo}">${a.codigo} – ${esc(a.descricao.substring(0, 80))}</option>`).join('');
        selAtiv.onchange();
      }
      selAtiv.onchange = () => {
        const a = atividades.find((x) => x.codigo === selAtiv.value);
        el.querySelector('[name=anexo]').value = a ? (a.sujeitaFatorR ? `${a.anexoSemFatorR} ou ${a.anexoComFatorR} (fator r)` : a.rotuloAnexo) : '';
      };
      inpData.onchange = carregar;
      selTipo.onchange = carregar;
      carregar();

      el.querySelector('[data-calc]').onclick = async () => {
        const f = comp.lerCampos(el);
        const fat = numero(f.faturamento);
        const compras = numero(f.compras) || 0;
        const b2b = Math.min(100, Math.max(0, numero(f.b2b) || 0)) / 100;
        const l = loc.valor();
        if (!fat || !f.cAtivSN || !f.pCBSSN || !f.pIBSSN) { Hub.toast('Preencha faturamento, atividade e alíquotas do Simples.', 'erro'); return; }
        if (!l.municipio) { Hub.toast('Selecione o município.', 'erro'); return; }
        if (!f.data || f.data < '2027-01-01') {
          res.innerHTML = '<div class="aviso">Use uma data a partir de 2027: em 2026 o optante do Simples não recolhe IBS/CBS e o regime regular usa as alíquotas de teste, o que distorce a comparação.</div>';
          return;
        }
        const a = atividades.find((x) => x.codigo === f.cAtivSN) || {};
        const anexo = a.sujeitaFatorR ? (f.fatorR ? a.anexoComFatorR : a.anexoSemFatorR) : a.anexoSemFatorR;
        const nfse = f.tipo === '91';
        const nom = (f.nom || '').replace(/\D/g, '');
        Hub.carregando(res, 'Calculando os dois cenários nas calculadoras oficiais...');

        // Cenário A – Simples
        const par = { pCBSSN: String(numero(f.pCBSSN)), pIBSSN: String(numero(f.pIBSSN)) };
        const ali = { fatorR: !!f.fatorR, impedimentoSublimite: false };
        ali['anexo' + (anexo || 'I')] = Object.assign({}, par, ['III', 'IV', 'V'].includes(anexo) ? { semRedistribuicaoISS: par } : {});
        const itemSn = { numero: 1, cAtivSN: f.cAtivSN, cClassTrib: f.cClassTrib || '000001', baseCalculo: fat.toFixed(2) };
        if (nom) itemSn[nfse ? 'nbs' : 'ncm'] = nom;
        const opSn = { id: 'cmp-a', versao: '3.0.0', data: f.data, tpDoc: f.tipo, aliquotasEfetivas: ali, itens: [itemSn] };
        if (nfse) {
          Object.assign(opSn, { opSimpNac: '3', regApIBSCBSSN: '1', regApTribSN: '1' });
          Object.assign(itemSn, { tribISSQN: '1', tpRetISSQN: '1' });
        } else {
          opSn.CRT = '1';
          itemSn.valorImpostoSeletivo = '0.00';
        }

        // Cenário B – regime regular
        const aliqNom = { cbs: numero(f.aCbs) || 0, ibsEstadual: numero(f.aIbsUf) || 0, ibsMunicipal: numero(f.aIbsMun) || 0 };
        const itemRg = { numero: 1, cst: (f.cClassTrib || '000001').substring(0, 3), cClassTrib: f.cClassTrib || '000001', baseCalculo: fat };
        if (nom) itemRg[nfse ? 'nbs' : 'ncm'] = nom;
        if (Number(f.data.substring(0, 4)) >= 2027) itemRg.aliquotasNominais = aliqNom;
        const opRg = { id: 'cmp-b', versao: '1.0.0', dhFatoGerador: Hub.dataHoraComFuso(f.data), municipio: l.municipio, uf: l.uf, tpDoc: Number(f.tipo), itens: [itemRg] };

        const [ra, rb] = await Promise.all([
          api.post(api.sn('/calculadora/observabilidade/simples-nacional/nao-assistido'), opSn),
          api.post(api.rg('/calculadora/observabilidade/regime-geral'), opRg),
        ]);
        if (!ra.ok) { res.innerHTML = Hub.problema(ra, 'Cenário A (Simples) rejeitado'); return; }
        if (!rb.ok) { res.innerHTML = Hub.problema(rb, 'Cenário B (regime regular) rejeitado'); return; }
        const errA = (ra.data.objetos || []).flatMap((o) => o.erros || []);
        const errB = (rb.data.objetos || []).flatMap((o) => o.erros || []);
        const totA = Number((ra.data.total || {}).vCBSSNtot || 0) + Number((ra.data.total || {}).vIBSSNtot || 0);
        const ibscbs = ((rb.data.total || {}).tribCalc || {}).IBSCBSTot || {};
        const debB = Number(Hub.prof(ibscbs.gCBS, 'vCBS') || 0) + Number(Hub.prof(ibscbs.gIBS, 'vIBS') || 0);
        const aliqEfetB = fat ? debB / fat : 0;
        const credCompras = compras * (aliqNom.cbs + aliqNom.ibsEstadual + aliqNom.ibsMunicipal) / 100;
        const recolherB = Math.max(0, debB - credCompras);
        const credClientesA = totA * b2b;
        const credClientesB = debB * b2b;
        const linha = (t, a, b, dica) => `<tr><td>${t}${dica ? `<div class="sutil" style="font-size:11.5px">${dica}</div>` : ''}</td><td class="num">${fmt.moeda(a)}</td><td class="num">${fmt.moeda(b)}</td></tr>`;
        res.innerHTML = `<div class="card"><h2>Comparação</h2>
          ${errA.length || errB.length ? `<div class="aviso">Há avisos/erros nos cálculos: ${[...errA, ...errB].map((e) => esc(e.code + ' ' + e.detail)).join(' | ')}</div>` : ''}
          <div class="tabela-wrap" style="max-height:none"><table class="tab"><thead><tr><th></th><th class="num">A – dentro do Simples</th><th class="num">B – regime regular</th></tr></thead><tbody>
            ${linha('IBS/CBS sobre o faturamento', totA, debB, `Alíquota efetiva A: ${fmt.pct((totA / fat) * 100)} · B: ${fmt.pct(aliqEfetB * 100)}`)}
            ${linha('(−) Créditos sobre compras', 0, credCompras, 'No Simples não há apropriação de créditos de IBS/CBS.')}
            ${linha('<strong>IBS/CBS a recolher</strong>', totA, recolherB)}
            ${linha('Crédito transferido aos clientes contribuintes', credClientesA, credClientesB, `Considerando ${fmt.num(b2b * 100)}% das vendas para contribuintes.`)}
          </tbody></table></div>
          <div class="kpis" style="margin-top:14px">
            <div class="kpi"><small>Diferença a recolher (B − A)</small><strong>${fmt.moeda(recolherB - totA)}</strong></div>
            <div class="kpi"><small>Crédito adicional aos clientes (B − A)</small><strong>${fmt.moeda(credClientesB - credClientesA)}</strong></div>
          </div>
          <div class="info-caixa">Leitura: a opção B tende a compensar quando os clientes são contribuintes e valorizam o crédito integral,
            e quando há muitas compras com crédito. A opção A mantém o recolhimento unificado com carga menor, mas transfere crédito menor.
            Simulação simplificada: não considera o efeito no DAS dos demais tributos, sublimites, regimes específicos, nem o fluxo de caixa do split payment.</div>
          <div class="acoes"><button class="btn btn-sec btn-peq" data-ja>JSON cenário A</button><button class="btn btn-sec btn-peq" data-jb>JSON cenário B</button></div>
        </div>`;
        res.querySelector('[data-ja]').onclick = () => comp.verJson({ envio: opSn, resposta: ra.data }, 'Cenário A – Simples');
        res.querySelector('[data-jb]').onclick = () => comp.verJson({ envio: opRg, resposta: rb.data }, 'Cenário B – Regime regular');
      };
    },
  });
})();
