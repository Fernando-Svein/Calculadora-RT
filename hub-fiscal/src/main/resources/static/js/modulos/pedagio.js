/* Cálculo de CBS/IBS no pedágio (rateio por trechos e municípios). */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;

  /** Quadros com o valor devido de cada tributo (total da operação devolvido pela calculadora). */
  function totais(d) {
    const t = (d && d.total) || {};
    const v = (g) => (t[g] ? Number(t[g].valorDevido || 0) : null);
    const cbs = v('cbsTotal');
    const uf = v('ibsEstadualTotal');
    const mun = v('ibsMunicipalTotal');
    if (cbs === null && uf === null && mun === null) return '';
    return `<div class="kpis">
      <div class="kpi"><small>Base de cálculo</small><strong>${fmt.moeda(d.baseCalculo)}</strong></div>
      <div class="kpi"><small>CBS devida</small><strong>${fmt.moeda(cbs)}</strong></div>
      <div class="kpi"><small>IBS estadual devido</small><strong>${fmt.moeda(uf)}</strong></div>
      <div class="kpi"><small>IBS municipal devido</small><strong>${fmt.moeda(mun)}</strong></div>
      <div class="kpi destaque"><small>Total de tributos</small><strong>${fmt.moeda((cbs || 0) + (uf || 0) + (mun || 0))}</strong></div>
    </div><h4 style="margin:10px 0 6px">Rateio por trecho</h4>`;
  }

  Hub.registrar({
    rota: 'pedagio',
    titulo: 'Pedágio',
    grupo: 'Calculadoras – Regime Regular',
    icone: '🛣️',
    ordem: 3,
    etiquetas: ['Regime Regular'],
    descricao: 'Exploração de via: rateia o IBS entre os municípios e UFs atravessados conforme a extensão de cada trecho.',
    render(el) {
      const dataPadrao = Hub.hoje();
      el.innerHTML = comp.cabecalho('Pedágio (exploração de via)',
        'Calcula a CBS e o IBS da tarifa de pedágio, distribuindo o IBS entre os municípios e estados de cada trecho pela extensão. ' +
        'A calculadora oficial de pedágio usa as alíquotas de referência da base: hoje só há alíquotas cadastradas para 2026 ' +
        '(datas de 2027 em diante retornam "alíquota não encontrada").') +
        `<div class="card"><h3>Operação</h3><div class="grade g4">
          ${comp.campo('Data de emissão *', `<input type="date" name="data" value="${dataPadrao}">`)}
          ${comp.campo('CST *', '<input name="cst" value="000">')}
          ${comp.campo('Classificação tributária (cClassTrib) *', '<input name="cClassTrib" value="000002">')}
          ${comp.campo('Base de cálculo (tarifa) *', '<input name="baseCalculo" value="200,00">')}
        </div><h4 style="margin-top:14px">Município de origem</h4><div data-orig></div></div>
        <div class="card"><div class="card-cab"><h3>Trechos</h3><button class="btn btn-sec btn-peq" data-add>+ Trecho</button></div><div data-trechos class="itens-lista"></div></div>
        <div class="acoes" style="margin-bottom:16px"><button class="btn" data-calc>Calcular</button></div><div data-res></div>`;
      const orig = comp.seletorMunicipio(el.querySelector('[data-orig]'), { uf: 'SP', municipio: 3550308 });
      const lista = el.querySelector('[data-trechos]');
      const trechos = [];
      const addTrecho = () => {
        const b = Hub.el(`<div class="item-bloco"><div class="item-cab"><strong>Trecho ${lista.children.length + 1}</strong><span class="espaco"></span><button class="btn btn-perigo btn-peq" data-rm>Remover</button></div>
          <div class="grade g3"><div data-mun style="grid-column: span 2"></div>${comp.campo('Extensão (km) *', '<input name="extensao" value="10">')}</div></div>`);
        lista.appendChild(b);
        const sel = comp.seletorMunicipio(b.querySelector('[data-mun]'), { uf: 'SP', municipio: 3550308 });
        const t = { b, sel };
        trechos.push(t);
        b.querySelector('[data-rm]').onclick = () => { b.remove(); trechos.splice(trechos.indexOf(t), 1); };
      };
      el.querySelector('[data-add]').onclick = addTrecho;
      addTrecho();
      el.querySelector('[data-calc]').onclick = async () => {
        const f = comp.lerCampos(el.querySelector('.card'));
        const o = orig.valor();
        const corpo = {
          dataHoraEmissao: Hub.dataHoraComFuso(f.data),
          codigoMunicipioOrigem: o.municipio,
          ufMunicipioOrigem: o.uf,
          cst: f.cst,
          cClassTrib: f.cClassTrib,
          baseCalculo: Hub.numero(f.baseCalculo),
          trechos: trechos.map((t, i) => {
            const v = t.sel.valor();
            return { numero: i + 1, municipio: v.municipio, uf: v.uf, extensao: Hub.numero(t.b.querySelector('[name=extensao]').value) };
          }),
        };
        const res = el.querySelector('[data-res]');
        Hub.carregando(res, 'Calculando...');
        const r = await api.post(api.rg('/calculadora/pedagio'), corpo);
        if (!r.ok) { res.innerHTML = Hub.problema(r, 'Cálculo rejeitado'); return; }
        // cenário de CBS (seletor no topo): o hub reescala a CBS de cada trecho e dos totais
        if (Hub.cenarioCbs.ativo()) {
          const c = await Hub.cenarioCbs.aplicar('pedagio', r.data);
          if (!c.ok) { res.innerHTML = Hub.problema(c, 'Não foi possível aplicar o cenário de CBS'); return; }
          r.data = c.data;
        }
        res.innerHTML = `<div class="card"><div class="card-cab"><h2>Resultado</h2><button class="btn btn-sec btn-peq" data-json>Ver resposta técnica</button></div>
          ${r.aviso ? `<div class="aviso">${esc(Hub.avisoSimulado(r.aviso))}</div>` : ''}${Hub.cenarioCbs.aviso(r.data)}${totais(r.data)}<div data-tab></div></div>`;
        const dados = r.data;
        const lista2 = Array.isArray(dados) ? dados : (dados.trechos || dados.objetos || [dados]);
        comp.jsonTabela(res.querySelector('[data-tab]'), lista2, { busca: false, exportar: { arquivo: 'pedagio', titulo: 'Cálculo de pedágio' } });
        res.querySelector('[data-json]').onclick = () => comp.verJson(dados, 'Resposta – pedágio');
      };
    },
  });
})();
