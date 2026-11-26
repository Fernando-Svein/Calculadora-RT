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
        `<div data-empresa></div>
        <div class="card"><h3>Dados do período</h3>
          <div class="grade g4">
            ${comp.campo('Data de referência *', `<input type="date" name="data" min="2027-01-01" value="${dataInicial}">`)}
            ${comp.campo('Tipo de receita', '<select name="tipo"><option value="55">Venda de mercadorias (NF-e)</option><option value="91">Prestação de serviços (NFS-e)</option></select>')}
            ${comp.campo('Faturamento do período (R$) *', '<input name="faturamento" value="100.000,00">')}
            ${comp.campo('% das vendas para contribuintes (B2B)', '<input name="b2b" value="70">', 'Clientes que aproveitam crédito de IBS/CBS.')}
            ${comp.campo('Compras com crédito (R$)', '<input name="compras" value="40.000,00">', 'Aquisições tributadas que gerariam crédito no regime regular.')}
            ${comp.campo('Classificação tributária das vendas (cClassTrib)', '<input name="cClassTrib" value="000001">')}
            ${comp.campo('NCM/NBS principal', '<input name="nom" placeholder="opcional">')}
          </div>
          <div data-loc style="margin-top:12px"></div>
        </div>
        <div class="grade g2">
          <div class="card"><h3>Cenário A – IBS/CBS dentro do Simples</h3>
            <div class="grade g2">
              ${comp.campo('Atividade no Simples *', '<select name="cAtivSN"></select>')}
              ${comp.campo('Anexo da atividade', '<input name="anexo" readonly>')}
              ${comp.campo('Parcela da CBS na alíquota efetiva do DAS (%) *', '<input name="pCBSSN" placeholder="ex.: 1,1612">')}
              ${comp.campo('Parcela do IBS na alíquota efetiva do DAS (%) *', '<input name="pIBSSN" placeholder="ex.: 0,0129">')}
            </div>
            <label class="check" style="margin-top:8px"><input type="checkbox" name="fatorR"> Fator r igual ou maior que 28%</label>
          </div>
          <div class="card"><h3>Cenário B – IBS/CBS pelo regime regular</h3>
            <div class="grade g3">
              ${comp.campo('CBS %', '<input name="aCbs" value="8,8">')}
              ${comp.campo('IBS estadual %', '<input name="aIbsUf" value="0,05">')}
              ${comp.campo('IBS municipal %', '<input name="aIbsMun" value="0,05">')}
            </div>
            <p class="sutil" style="margin-top:8px">Alíquotas nominais estimadas para o ano; confira as vigentes. Em 2027–2028 o IBS ainda é de teste; a comparação fica mais relevante com as alíquotas cheias (2033).</p>
          </div>
        </div>
        <div class="acoes" style="margin-bottom:16px"><button class="btn" data-calc>Comparar</button></div>
        <div data-res></div>`;

      const loc = comp.seletorMunicipio(el.querySelector('[data-loc]'), { uf: 'SP', municipio: 3550308 });
      // cenário de CBS (seletor no topo): vai como alíquota nominal, calculada pela própria calculadora
      Hub.cenarioCbs.ligarCampoNominal(el.querySelector('[name=aCbs]'), '8,8');
      const selAtiv = el.querySelector('[name=cAtivSN]');
      const inpData = el.querySelector('[name=data]');
      const selTipo = el.querySelector('[name=tipo]');
      const res = el.querySelector('[data-res]');
      let atividades = [];

      let emp = null;
      async function carregar() {
        atividades = await Hub.atividadesSimples(inpData.value, selTipo.value);
        const antes = selAtiv.value;
        selAtiv.innerHTML = atividades.map((a) => `<option value="${a.codigo}">${a.codigo} – ${esc(a.descricao.substring(0, 80))}</option>`).join('');
        if (antes && atividades.some((a) => a.codigo === antes)) selAtiv.value = antes;
        const e = emp && emp.empresa();
        if (e && e.atividadeSimples) Hub.empresas.definir(selAtiv, e.atividadeSimples);
        selAtiv.onchange();
      }
      /** Anexo efetivo da atividade escolhida (depende do fator r). */
      function anexoAtual() {
        const a = atividades.find((x) => x.codigo === selAtiv.value);
        if (!a) return null;
        return a.sujeitaFatorR ? (el.querySelector('[name=fatorR]').checked ? a.anexoComFatorR : a.anexoSemFatorR) : a.anexoSemFatorR;
      }
      /** Com empresa selecionada, as parcelas de CBS/IBS do DAS vêm do cadastro, conforme o anexo da atividade. */
      function aliquotasDaEmpresa() {
        const e = emp && emp.empresa();
        const p = e && e.aliquotasSimples && e.aliquotasSimples[anexoAtual()];
        if (!p) return false;
        Hub.empresas.definir(el.querySelector('[name=pCBSSN]'), p.cbs && String(p.cbs).replace('.', ','));
        Hub.empresas.definir(el.querySelector('[name=pIBSSN]'), p.ibs && String(p.ibs).replace('.', ','));
        return true;
      }
      selAtiv.onchange = () => {
        const a = atividades.find((x) => x.codigo === selAtiv.value);
        el.querySelector('[name=anexo]').value = a ? (a.sujeitaFatorR ? `${a.anexoSemFatorR} ou ${a.anexoComFatorR} (fator r)` : a.rotuloAnexo) : '';
        aliquotasDaEmpresa();
      };
      el.querySelector('[name=fatorR]').addEventListener('change', aliquotasDaEmpresa);
      inpData.onchange = carregar;
      selTipo.onchange = carregar;

      function aplicarEmpresa(e) {
        if (!e) return {};
        const D = Hub.empresas.definir;
        const usadas = [];
        if (e.uf && e.municipio) {
          usadas.push('município do estabelecimento');
          loc.pronto.then(() => loc.definir(e.uf, e.municipio)).then(() => {
            D(el.querySelector('[data-loc] [data-uf]'), e.uf);
            D(el.querySelector('[data-loc] [data-mun]'), e.municipio);
          });
        }
        if (['55', '91'].includes(e.documentoPadrao) && D(selTipo, e.documentoPadrao)) usadas.push('tipo de receita pelo documento usual');
        if (D(el.querySelector('[name=cClassTrib]'), e.cClassTribPadrao)) usadas.push('cClassTrib usual ' + e.cClassTribPadrao);
        if (Hub.empresas.ehSimples(e)) {
          D(el.querySelector('[name=fatorR]'), !!e.fatorR);
          usadas.push(`fator r ${e.fatorR ? 'igual ou maior que 28%' : 'abaixo de 28%'}`);
        }
        if (e.atividadeSimples) usadas.push('atividade ' + e.atividadeSimples);
        if (Object.keys(e.aliquotasSimples || {}).length) usadas.push('alíquotas do DAS do anexo da atividade');
        carregar();
        let aviso = '';
        if (e.regime && !Hub.empresas.ehSimples(e)) aviso = 'O comparador é para optantes do Simples Nacional; esta empresa está cadastrada como ' + esc(Hub.empresas.regimeTexto(e)) + '.';
        return { usadas, aviso };
      }
      emp = Hub.empresas.seletor(el.querySelector('[data-empresa]'), { raiz: el, aoMudar: aplicarEmpresa });
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
        const ctx = { empresa: emp.empresa(), usadas: emp.usadas() };
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
        res.innerHTML = `<div class="card"><div class="card-cab"><h2>Comparação</h2><div class="acoes">${Hub.relatorio.botoes()}</div></div>
          ${Hub.empresas.faixaResultado(ctx.empresa)}
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
            Simulação simplificada: não considera o efeito no DAS dos demais tributos, sublimites, regimes específicos, nem o fluxo de caixa do recolhimento na liquidação financeira (split payment).</div>
          <div class="acoes"><button class="btn btn-sec btn-peq" data-ja>Dados técnicos – cenário A</button><button class="btn btn-sec btn-peq" data-jb>Dados técnicos – cenário B</button></div>
        </div>`;
        res.querySelector('[data-ja]').onclick = () => comp.verJson({ envio: opSn, resposta: ra.data }, 'Cenário A – Simples');
        res.querySelector('[data-jb]').onclick = () => comp.verJson({ envio: opRg, resposta: rb.data }, 'Cenário B – Regime regular');
        Hub.relatorio.ligar(res, async () => ({
          titulo: 'Comparador – IBS/CBS no Simples × no regime regular',
          arquivo: 'comparador-simples-regular',
          empresa: await Hub.empresas.linhasRelatorio(ctx.empresa, ctx.usadas),
          resumo: [
            ['Data de referência', fmt.data(f.data)],
            ['Tipo de receita', nfse ? 'Prestação de serviços (NFS-e)' : 'Venda de mercadorias (NF-e)'],
            ['Local', l.nomeMunicipio ? `${l.nomeMunicipio}/${l.uf}` : String(l.municipio)],
            ['Faturamento do período', fmt.moeda(fat)],
            ['Vendas para contribuintes (B2B)', fmt.pct(b2b * 100)],
            ['Compras com crédito', fmt.moeda(compras)],
            ['Classificação tributária (cClassTrib)', f.cClassTrib || '000001'],
            ['Cenário A – atividade e anexo', `${f.cAtivSN} – Anexo ${anexo || '–'}${f.fatorR ? ' (fator r ≥ 28%)' : ''}`],
            ['Cenário A – parcelas do DAS', `CBS ${String(f.pCBSSN).replace('.', ',')}% · IBS ${String(f.pIBSSN).replace('.', ',')}%`],
            ['Cenário B – alíquotas nominais', `CBS ${fmt.pct(aliqNom.cbs)} · IBS estadual ${fmt.pct(aliqNom.ibsEstadual)} · IBS municipal ${fmt.pct(aliqNom.ibsMunicipal)}`],
            ['Diferença a recolher (B − A)', fmt.moeda(recolherB - totA)],
            ['Crédito adicional aos clientes (B − A)', fmt.moeda(credClientesB - credClientesA)],
          ],
          colunas: [{ titulo: 'Item', tipo: 'texto', largura: 46 }, { titulo: 'A – dentro do Simples', tipo: 'moeda', largura: 20 }, { titulo: 'B – regime regular', tipo: 'moeda', largura: 20 }],
          linhas: [
            ['IBS/CBS sobre o faturamento', totA, debB],
            ['(−) Créditos sobre compras', 0, credCompras],
            ['IBS/CBS a recolher', totA, recolherB],
            ['Crédito transferido aos clientes contribuintes', credClientesA, credClientesB],
          ].map((x) => [x[0], Number(x[1].toFixed(2)), Number(x[2].toFixed(2))]),
          observacoes: [
            ...[...errA, ...errB].map((e) => `${e.code} – ${e.detail}`),
            'Simulação simplificada: não considera o efeito no DAS dos demais tributos, sublimites, regimes específicos, nem o fluxo de caixa do recolhimento na liquidação financeira (split payment).',
            'Os dois cenários foram calculados nas calculadoras oficiais da Receita Federal.',
          ],
        }));
      };
    },
  });
})();
