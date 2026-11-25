/* Alíquotas de referência (União, UF e município). */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;
  const DA = '/calculadora/dados-abertos';

  Hub.registrar({
    rota: 'aliquotas',
    titulo: 'Alíquotas de referência',
    grupo: 'Consultas e tabelas',
    icone: '％',
    ordem: 3,
    etiquetas: ['Regime Regular'],
    descricao: 'Alíquotas padrão/de referência da CBS (União), do IBS estadual e do IBS municipal para uma data e localidade.',
    render(el) {
      el.innerHTML = comp.cabecalho('Alíquotas de referência',
        'Consulta as alíquotas cadastradas na base oficial. Hoje a base traz as alíquotas do ano de teste (2026); para 2027 em diante a consulta retorna "não encontrado" e as alíquotas devem ser informadas nos simuladores.') +
        `<div class="card"><div class="grade g3">
          <div class="campo"><label>Data</label><input type="date" data-data value="${Hub.hoje()}"></div>
          <div data-loc style="grid-column: span 2"></div>
        </div>
        <div class="acoes" style="margin-top:12px"><button class="btn" data-consultar>Consultar</button></div></div>
        <div data-res></div>`;
      const loc = comp.seletorMunicipio(el.querySelector('[data-loc]'), { uf: 'SP', obrigatorio: false });
      const res = el.querySelector('[data-res]');
      el.querySelector('[data-consultar]').onclick = consultar;

      async function consultar() {
        const data = el.querySelector('[data-data]').value;
        const v = loc.valor();
        Hub.carregando(res);
        const pedidos = [api.get(api.rg(`${DA}/aliquota-uniao?data=${data}`))];
        pedidos.push(v.codigoUf ? api.get(api.rg(`${DA}/aliquota-uf?codigoUf=${v.codigoUf}&data=${data}`)) : null);
        pedidos.push(v.municipio ? api.get(api.rg(`${DA}/aliquota-municipio?codigoMunicipio=${v.municipio}&data=${data}`)) : null);
        const [u, uf, mun] = await Promise.all(pedidos);
        await Hub.cenarioCbs.pronto;
        // alíquota de CBS do cenário (hub.cenario-cbs no application.yml), em destaque para comparar com a oficial
        const cenario = Hub.cenarioCbs.aliquota()
          ? `<div class="kpi kpi-cenario"><small>CBS – ${esc(Hub.cenarioCbs.rotulo() || 'cenário')} (sugerida)</small>
              <strong>${fmt.pct(Hub.cenarioCbs.aliquota())}</strong><span>Não oficial: usada só nas simulações</span></div>` : '';
        const valor = (r) => {
          if (!r) return '<span class="sutil">não informado</span>';
          if (!r.ok) return `<span class="sutil">${r.status === 404 ? 'sem alíquota cadastrada' : 'erro ' + r.status}</span>`;
          return fmt.pct(r.data.aliquotaReferencia ?? r.data.aliquota ?? r.data.valor);
        };
        const avisos = [...new Set([u, uf, mun].filter((r) => r && r.aviso).map((r) => Hub.avisoSimulado(r.aviso)))];
        const total = [u, uf, mun].every((r) => r && r.ok)
          ? Number(u.data.aliquotaReferencia || 0) + Number(uf.data.aliquotaReferencia || 0) + Number(mun.data.aliquotaReferencia || 0) : null;
        res.innerHTML = `<div class="card"><h3>Alíquotas em ${fmt.data(data)}</h3>
          <div class="kpis">
            <div class="kpi"><small>CBS (União)</small><strong>${valor(u)}</strong></div>
            ${cenario}
            <div class="kpi"><small>IBS estadual ${esc(v.uf || '')}</small><strong>${valor(uf)}</strong></div>
            <div class="kpi"><small>IBS municipal ${esc(v.nomeMunicipio || '')}</small><strong>${valor(mun)}</strong></div>
            ${total !== null ? `<div class="kpi destaque"><small>Total CBS + IBS</small><strong>${fmt.pct(total)}</strong></div>` : ''}
          </div>
          ${avisos.length ? `<div class="aviso">${avisos.map(esc).join('<br>')}</div>` : ''}
          ${u && u.status === 404 ? '<div class="info-caixa">Não há alíquota de referência cadastrada para esta data. A partir de 2027 informe as alíquotas nominais nos simuladores.</div>' : ''}
        </div>`;
      }
      loc.pronto.then(consultar);
    },
  });
})();
