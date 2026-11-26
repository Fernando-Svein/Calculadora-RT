/*
 * Cadastros → Empresas: lista das empresas atendidas e cadastro de cada uma com as particularidades que os
 * simuladores usam (regime, município, documento e cClassTrib usuais, alíquotas do DAS por anexo, fator r...).
 * Empresas novas entram sozinhas quando um SPED/XML é importado num processamento em lote.
 */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;
  const E = () => Hub.empresas;
  const ANEXOS = ['I', 'II', 'III', 'IV', 'V'];
  const COM_ISS = new Set(['III', 'IV', 'V']);

  Hub.registrar({
    rota: 'empresas',
    titulo: 'Empresas',
    grupo: 'Cadastros',
    ordem: 1,
    etiquetas: ['CNPJ', 'cliente', 'regime', 'particularidades'],
    descricao: 'Empresas atendidas e suas particularidades (regime, município, alíquotas do DAS). Usadas pelos simuladores; cadastradas automaticamente na importação de SPED/XML.',
    render(el, params) {
      if (params.id || params.nova) return formulario(el, params.id ? Number(params.id) : null);
      return lista(el, params);
    },
  });

  // ------------------------------------------------------------------ lista

  async function lista(el, params) {
    el.innerHTML = comp.cabecalho('Empresas',
      'Empresas atendidas pelo escritório. Ao importar um SPED ou XML num processamento em lote, a empresa do arquivo é cadastrada ' +
      'automaticamente com o CNPJ e a razão social; complete aqui o regime e as demais particularidades usadas nos simuladores.') +
      `<div class="card"><div class="card-cab"><h3>Empresas cadastradas</h3>
        <div class="acoes"><label class="check"><input type="checkbox" data-conferir ${params.conferir ? 'checked' : ''}> Somente as que faltam conferir</label>
        <button class="btn btn-peq" type="button" data-nova>${Hub.icone('mais')} Nova empresa</button></div></div>
        <div data-tab></div></div>`;
    el.querySelector('[data-nova]').onclick = () => Hub.ir('empresas', { nova: 1 });
    const alvo = el.querySelector('[data-tab]');
    Hub.carregando(alvo, 'Carregando empresas...');
    const empresas = await E().listar(true);
    const tab = comp.tabela(alvo, {
      placeholder: 'Filtrar por nome, CNPJ, regime ou UF...',
      vazio: 'Nenhuma empresa cadastrada. Use "Nova empresa" ou importe um SPED/XML em Processamento em lote.',
      colunas: [
        { titulo: 'Razão social', campo: 'razaoSocial', render: (e) => `<strong>${esc(e.razaoSocial)}</strong>${e.nomeFantasia ? `<div class="sutil">${esc(e.nomeFantasia)}</div>` : ''}` },
        { titulo: 'CNPJ', exportar: (e) => fmt.cnpj(e.cnpj), render: (e) => `<code>${esc(fmt.cnpj(e.cnpj))}</code>` },
        { titulo: 'Regime', exportar: (e) => E().regimeTexto(e) },
        { titulo: 'UF', campo: 'uf' },
        { titulo: 'Origem', exportar: (e) => E().ORIGENS[e.origem] || e.origem },
        { titulo: 'Situação', exportar: (e) => (e.revisado ? 'Conferido' : 'A conferir'),
          render: (e) => (e.revisado ? '<span class="etq etq-verde">Conferido</span>' : '<span class="etq etq-amarelo">A conferir</span>') },
        { titulo: 'Atualizado em', exportar: (e) => fmt.data(e.atualizadoEm || e.criadoEm) },
      ],
      linhas: empresas,
      aoClicar: (e) => E().abrirCadastro(e.id),
      exportar: { arquivo: 'empresas', titulo: 'Empresas cadastradas' },
    });
    const chk = el.querySelector('[data-conferir]');
    const filtrar = () => tab.filtrar(chk.checked ? (e) => !e.revisado : null);
    chk.onchange = filtrar;
    filtrar();
  }

  // ------------------------------------------------------------------ cadastro (abrir, incluir e editar)

  async function formulario(el, id) {
    let e = null;
    if (id) {
      Hub.carregando(el, 'Abrindo o cadastro...');
      const r = await api.get('/hub/empresas/' + id);
      if (!r.ok) { el.innerHTML = Hub.problema(r, 'Empresa não encontrada') + '<a href="#/empresas">Voltar à lista</a>'; return; }
      e = r.data;
    }
    const a = (e && e.aliquotasSimples) || {};
    const v = (x) => esc(x ?? '');
    const docs = Object.entries(E().DOCUMENTOS);
    el.innerHTML = comp.cabecalho(e ? e.razaoSocial : 'Nova empresa',
      e ? `CNPJ ${esc(fmt.cnpj(e.cnpj))} · ${esc(E().ORIGENS[e.origem] || e.origem)} em ${esc(fmt.data(e.criadoEm))}` : 'Cadastro manual de uma empresa atendida.') +
      `${e && !e.revisado ? `<div class="aviso"><strong>Cadastro criado automaticamente</strong> a partir de ${e.origem === 'SPED' ? 'um SPED' : 'um XML'}:
          confira o regime e preencha as particularidades. Ao salvar, o cadastro passa a constar como conferido.</div>` : ''}
      <form data-f novalidate>
      <div class="card"><h3>Identificação</h3>
        <div class="grade g3">
          ${comp.campo('CNPJ *', `<input name="cnpj" value="${v(e && fmt.cnpj(e.cnpj))}" ${e ? 'readonly' : ''} placeholder="00.000.000/0000-00" maxlength="18" autocomplete="off">`,
            e ? 'O CNPJ não muda: para outro CNPJ, cadastre outra empresa.' : 'Aceita o CNPJ numérico e o alfanumérico.')}
          ${comp.campo('Razão social *', `<input name="razaoSocial" value="${v(e && e.razaoSocial)}" maxlength="150">`)}
          ${comp.campo('Nome fantasia', `<input name="nomeFantasia" value="${v(e && e.nomeFantasia)}" maxlength="150">`)}
        </div>
        <div data-loc style="margin-top:12px"></div>
        <span class="ajuda sutil">Município do estabelecimento: usado como local da operação nos simuladores do regime normal e no pedágio.</span>
      </div>
      <div class="card"><h3>Regime tributário</h3>
        <div class="grade g3">
          ${comp.campo('Regime *', `<select name="regime"><option value="">A definir</option>
            ${Object.entries(E().REGIMES).map(([k, t]) => `<option value="${k}" ${e && e.regime === k ? 'selected' : ''}>${esc(t)}${k === 'NORMAL' ? ' (CRT 3)' : k === 'SIMPLES' ? ' (CRT 1)' : k === 'SIMPLES_EXCESSO' ? ' (CRT 2)' : ' (CRT 4)'}</option>`).join('')}</select>`,
            'Define o CRT e a situação no Simples enviados às calculadoras.')}
          <div data-apuracao>${comp.campo('Forma de apuração', `<select name="regimeApuracao"><option value="">Não informada</option>
            ${Object.entries(E().APURACOES).map(([k, t]) => `<option value="${k}" ${e && e.regimeApuracao === k ? 'selected' : ''}>${esc(t)}</option>`).join('')}</select>`)}</div>
        </div>
        <div data-simples style="margin-top:12px">
          <div class="acoes" style="margin-bottom:10px;flex-wrap:wrap">
            <label class="check"><input type="checkbox" name="ibscbsForaSimples" ${e && e.ibscbsForaSimples ? 'checked' : ''}> Optou por recolher o IBS e a CBS pelo regime regular (fora do DAS)</label>
            <label class="check"><input type="checkbox" name="fatorR" ${e && e.fatorR ? 'checked' : ''}> Fator r igual ou maior que 28%</label>
            <label class="check"><input type="checkbox" name="sublimiteExcedido" ${e && e.sublimiteExcedido ? 'checked' : ''}> Ultrapassou o sublimite estadual</label>
          </div>
          <div class="grade g2">${comp.campo('Atividade principal no Simples', '<select name="atividadeSimples"><option value="">Carregando atividades...</option></select>',
            'Sugerida nos itens do Simulador do Simples e usada no Comparador.')}</div>
          <h4 style="margin:14px 0 6px">Alíquotas efetivas do período (parcelas de CBS e IBS dentro do DAS, em %)</h4>
          <div class="tabela-wrap" style="max-height:none"><table class="tab tab-aliq"><thead>
            <tr><th rowspan="2">Anexo</th><th colspan="2">Alíquota normal</th><th colspan="2">Quando o ISS não é recolhido no DAS</th></tr>
            <tr><th>CBS (%)</th><th>IBS (%)</th><th>CBS (%)</th><th>IBS (%)</th></tr></thead><tbody>
            ${ANEXOS.map((x) => {
              const p = a[x] || {};
              const inp = (campo, rot) => `<input data-anexo="${x}" data-campo="${campo}" value="${v(p[campo] && String(p[campo]).replace('.', ','))}" aria-label="Anexo ${x}: ${rot}">`;
              return `<tr><td><strong>${x}</strong></td><td>${inp('cbs', 'CBS, alíquota normal (%)')}</td><td>${inp('ibs', 'IBS, alíquota normal (%)')}</td>
                <td>${COM_ISS.has(x) ? inp('cbsSemIss', 'CBS quando o ISS não é recolhido no DAS (%)') : '<span class="sutil">–</span>'}</td>
                <td>${COM_ISS.has(x) ? inp('ibsSemIss', 'IBS quando o ISS não é recolhido no DAS (%)') : '<span class="sutil">–</span>'}</td></tr>`;
            }).join('')}
          </tbody></table></div>
          <p class="sutil" style="margin-top:8px">Valores da apuração do PGDAS-D do período, com todas as casas decimais. Os simuladores usam estes valores
            enquanto a empresa estiver selecionada; atualize-os quando mudar o período de apuração.</p>
        </div>
      </div>
      <div class="card"><h3>Operações usuais</h3>
        <div class="grade g3">
          ${comp.campo('Documento fiscal mais emitido', `<select name="documentoPadrao"><option value="">Não informar</option>
            ${docs.map(([k, t]) => `<option value="${k}" ${e && e.documentoPadrao === k ? 'selected' : ''}>${esc(t)}</option>`).join('')}</select>`)}
          ${comp.campo('Classificação tributária usual (cClassTrib)', `<input name="cClassTribPadrao" value="${v(e && e.cClassTribPadrao)}" maxlength="6" placeholder="000001" inputmode="numeric">`,
            'Sugerida nos itens novos dos simuladores.')}
        </div>
        ${comp.campo('Observações', `<textarea name="observacoes" rows="3" maxlength="2000">${v(e && e.observacoes)}</textarea>`)}
      </div>
      <div data-erro></div>
      <div class="acoes" style="margin-bottom:16px">
        <button class="btn" type="submit">${e ? 'Salvar alterações' : 'Cadastrar empresa'}</button>
        ${e ? '<button class="btn btn-sec" type="button" data-simular>Simular com esta empresa</button>' : ''}
        <a class="btn btn-sec" href="#/empresas">Voltar à lista</a>
        <span class="espaco"></span>
        ${e ? '<button class="btn btn-perigo" type="button" data-excluir>Excluir</button>' : ''}
      </div>
      </form>`;

    const f = el.querySelector('[data-f]');
    const loc = comp.seletorMunicipio(el.querySelector('[data-loc]'), { uf: e && e.uf, municipio: e && e.municipio, obrigatorio: false });
    const selRegime = f.regime;
    const ajustar = () => {
      const simples = ['SIMPLES', 'SIMPLES_EXCESSO'].includes(selRegime.value);
      el.querySelector('[data-simples]').hidden = !simples;
      el.querySelector('[data-apuracao]').hidden = selRegime.value !== 'NORMAL';
    };
    selRegime.onchange = ajustar;
    ajustar();

    // atividades do Simples vigentes (a partir de 2027, quando o IBS/CBS entra no DAS)
    (async () => {
      const ref = Hub.hoje() < '2027-01-01' ? '2027-01-01' : Hub.hoje();
      const atividades = await Hub.atividadesSimples(ref);
      const sel = f.atividadeSimples;
      sel.innerHTML = '<option value="">Não informar</option>' + atividades.map((x) =>
        `<option value="${esc(x.codigo)}">${esc(x.codigo)} – ${esc(x.descricao.length > 90 ? x.descricao.substring(0, 89) + '…' : x.descricao)} (Anexo ${esc(x.rotuloCurtoAnexo || x.rotuloAnexo)})</option>`).join('');
      if (e && e.atividadeSimples) {
        if (!atividades.some((x) => x.codigo === e.atividadeSimples)) sel.insertAdjacentHTML('beforeend', `<option value="${esc(e.atividadeSimples)}">${esc(e.atividadeSimples)}</option>`);
        sel.value = e.atividadeSimples;
      }
    })();

    function dados() {
      const c = comp.lerCampos(f);
      const l = loc.valor();
      const aliquotas = {};
      f.querySelectorAll('[data-anexo]').forEach((i) => {
        const n = Hub.numero(i.value);
        if (n === null) return;
        (aliquotas[i.dataset.anexo] = aliquotas[i.dataset.anexo] || {})[i.dataset.campo] = String(n);
      });
      return {
        cnpj: c.cnpj, razaoSocial: c.razaoSocial, nomeFantasia: c.nomeFantasia || null,
        uf: l.uf, municipio: l.municipio,
        regime: c.regime || null, regimeApuracao: c.regimeApuracao || null,
        ibscbsForaSimples: !!c.ibscbsForaSimples, fatorR: !!c.fatorR, sublimiteExcedido: !!c.sublimiteExcedido,
        atividadeSimples: c.atividadeSimples || null, aliquotasSimples: aliquotas,
        documentoPadrao: c.documentoPadrao || null, cClassTribPadrao: c.cClassTribPadrao || null, observacoes: c.observacoes || null,
      };
    }

    f.onsubmit = async (ev) => {
      ev.preventDefault();
      const erro = el.querySelector('[data-erro]');
      erro.innerHTML = '';
      const d = dados();
      if (!e && !d.cnpj) { erro.innerHTML = '<div class="erro-caixa">Informe o CNPJ.</div>'; return; }
      if (!d.razaoSocial) { erro.innerHTML = '<div class="erro-caixa">Informe a razão social.</div>'; return; }
      if (d.uf && !d.municipio) { erro.innerHTML = '<div class="erro-caixa">Selecione o município da UF escolhida (ou deixe a UF em branco).</div>'; return; }
      const r = e ? await api.put('/hub/empresas/' + e.id, d) : await api.post('/hub/empresas', d);
      if (!r.ok) { erro.innerHTML = Hub.problema(r, 'Cadastro não salvo'); return; }
      E().invalidar();
      Hub.toast(e ? 'Cadastro atualizado.' : 'Empresa cadastrada.');
      if (e) formulario(el, e.id); else Hub.ir('empresas', { id: r.data.id });
    };

    const simular = el.querySelector('[data-simular]');
    if (simular) {
      simular.onclick = () => {
        try { localStorage.setItem('hub.empresaSimulacao', String(e.id)); } catch { /* sem armazenamento */ }
        const destino = E().ehSimples(e) && !e.ibscbsForaSimples ? 'calc-sn' : 'calc-rg';
        if (!Hub.modulos.some((m) => m.rota === destino)) { Hub.toast('O simulador deste regime não está liberado para o seu usuário.', 'erro'); return; }
        Hub.ir(destino);
      };
    }
    const excluir = el.querySelector('[data-excluir]');
    if (excluir) {
      excluir.onclick = async () => {
        if (!confirm(`Excluir o cadastro de ${e.razaoSocial}? As particularidades informadas serão perdidas. ` +
          'Se um SPED/XML desta empresa for importado de novo, ela volta a ser cadastrada automaticamente (sem as particularidades).')) return;
        const r = await api.del('/hub/empresas/' + e.id);
        if (!r.ok) { Hub.modal(Hub.problema(r, 'Não foi possível excluir')); return; }
        E().invalidar();
        Hub.toast('Cadastro excluído.');
        Hub.ir('empresas');
      };
    }
  }
})();
