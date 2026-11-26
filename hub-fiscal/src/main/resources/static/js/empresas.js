/*
 * Empresas atendidas: seletor "Empresa da simulação" usado em todos os simuladores e funções que traduzem
 * o cadastro (regime, município, alíquotas do DAS...) nos campos de cada simulador.
 * A empresa escolhida vale para todos os simuladores (fica guardada neste navegador).
 */
'use strict';

(() => {
  const { esc, fmt, api } = Hub;

  const REGIMES = {
    SIMPLES: 'Simples Nacional',
    SIMPLES_EXCESSO: 'Simples Nacional – excesso de sublimite',
    NORMAL: 'Regime normal',
    MEI: 'MEI',
  };
  const APURACOES = { LUCRO_REAL: 'Lucro Real', LUCRO_PRESUMIDO: 'Lucro Presumido', LUCRO_ARBITRADO: 'Lucro Arbitrado' };
  /** Regime → CRT da NF-e. */
  const CRT = { SIMPLES: '1', SIMPLES_EXCESSO: '2', NORMAL: '3', MEI: '4' };
  /** Regime → situação do prestador no Simples na NFS-e (opSimpNac). */
  const OP_SIMP_NAC = { SIMPLES: '3', SIMPLES_EXCESSO: '3', MEI: '2', NORMAL: '1' };
  const ORIGENS = { MANUAL: 'Cadastro manual', SPED: 'Importação de SPED', XML: 'Importação de XML' };
  const DOCUMENTOS = { 55: 'NF-e (55)', 65: 'NFC-e (65)', 91: 'NFS-e (91)', 57: 'CT-e (57)', 67: 'CT-e OS (67)', 63: 'BP-e (63)', 93: 'BP-e TM (93)', 62: 'NFCom (62)', 66: 'NF3e (66)', 75: 'NFAg (75)' };
  const CHAVE = 'hub.empresaSimulacao';

  let cache = null;
  async function listar(forcar) {
    if (!cache || forcar) {
      const r = await api.get('/hub/empresas');
      if (!r.ok) return [];
      cache = r.data;
    }
    return cache;
  }
  const invalidar = () => { cache = null; };

  const ehSimples = (e) => !!e && (e.regime === 'SIMPLES' || e.regime === 'SIMPLES_EXCESSO');

  function regimeTexto(e) {
    if (!e || !e.regime) return 'Regime a definir';
    let t = REGIMES[e.regime] || e.regime;
    if (e.regime === 'NORMAL' && e.regimeApuracao) t += ' – ' + (APURACOES[e.regimeApuracao] || e.regimeApuracao);
    if (ehSimples(e) && e.ibscbsForaSimples) t += ' (IBS/CBS pelo regime regular)';
    return t;
  }

  const nome = (e) => (e.nomeFantasia ? `${e.razaoSocial} (${e.nomeFantasia})` : e.razaoSocial);

  async function nomeMunicipio(uf, codigo) {
    if (!uf || !codigo) return null;
    const r = await Hub.getCache(api.rg('/calculadora/dados-abertos/ufs/municipios?siglaUf=' + encodeURIComponent(uf)));
    const m = r.ok && Array.isArray(r.data) ? r.data.find((x) => Number(x.codigo) === Number(codigo)) : null;
    return m ? m.nome : null;
  }

  async function local(e) {
    if (!e.uf) return '';
    const mun = await nomeMunicipio(e.uf, e.municipio);
    return mun ? `${mun}/${e.uf}` : e.uf;
  }

  /** Linhas que identificam a empresa nos relatórios (PDF e Excel). */
  async function linhasRelatorio(e, usadas) {
    if (!e) return [];
    const onde = await local(e);
    const linhas = [nome(e), `CNPJ ${fmt.cnpj(e.cnpj)} · ${regimeTexto(e)}${onde ? ' · ' + onde : ''}`];
    if (usadas && usadas.length) linhas.push('Particularidades usadas no cálculo: ' + usadas.join('; ') + '.');
    if (!e.revisado) linhas.push('Atenção: cadastro vindo de uma importação e ainda não conferido pelo escritório.');
    return linhas;
  }

  function lerPreferencia() {
    try { return Number(localStorage.getItem(CHAVE)) || null; } catch { return null; }
  }
  function gravarPreferencia(id) {
    try { if (id) localStorage.setItem(CHAVE, String(id)); else localStorage.removeItem(CHAVE); } catch { /* sem armazenamento */ }
  }

  // ------------------------------------------------------------------ preenchimento dos campos

  /** Preenche um campo com um valor do cadastro e o marca (borda colorida) até o usuário alterá-lo. */
  function definir(campo, valor) {
    if (!campo || valor === undefined || valor === null || valor === '') return false;
    // select sem a opção (ex.: documento que o simulador não aceita): mantém o que estava
    if (campo.tagName === 'SELECT' && !Array.from(campo.options).some((o) => o.value === String(valor))) return false;
    if (campo.type === 'checkbox') campo.checked = !!valor; else campo.value = String(valor);
    campo.dataset.daEmpresa = '1';
    if (!campo.dataset.tituloOriginal) campo.dataset.tituloOriginal = campo.title || '';
    campo.title = 'Preenchido pelo cadastro da empresa da simulação';
    const soltar = () => desmarcarCampo(campo);
    campo.addEventListener('input', soltar, { once: true });
    campo.addEventListener('change', soltar, { once: true });
    return true;
  }
  function desmarcarCampo(c) {
    if (!c.dataset.daEmpresa) return;
    delete c.dataset.daEmpresa;
    c.title = c.dataset.tituloOriginal || '';
  }
  /** Tira a marcação "veio do cadastro" de todos os campos (ao trocar ou tirar a empresa). */
  const desmarcar = (raiz) => raiz.querySelectorAll('[data-da-empresa]').forEach(desmarcarCampo);

  /** Abre o cadastro de uma empresa existente (tela Cadastros → Empresas). */
  const abrirCadastro = (id) => Hub.ir('empresas', { id });

  // ------------------------------------------------------------------ seletor

  /**
   * Quadro "Empresa da simulação" no topo de um simulador.
   * opts.aoMudar(empresa | null) aplica as particularidades no formulário e devolve
   * { usadas: [textos do que foi preenchido], aviso: 'html opcional' }.
   * Retorna { empresa(), usadas(), linhasRelatorio(), reaplicar(), pronto }.
   */
  function seletor(container, opts = {}) {
    let atual = null;
    let usadas = [];
    let aviso = '';
    const raiz = Hub.el(`<section class="empresa-sim" data-estado="nenhuma">
      <div class="empresa-sim-cab">
        <span class="empresa-sim-ico">${Hub.icone('empresa')}</span>
        <span class="empresa-sim-rot">Empresa da simulação</span>
        <select data-emp aria-label="Empresa da simulação"><option value="">Carregando empresas...</option></select>
      </div>
      <div class="empresa-sim-info" data-info aria-live="polite"></div>
    </section>`);
    container.appendChild(raiz);
    const sel = raiz.querySelector('[data-emp]');
    const info = raiz.querySelector('[data-info]');
    let lista = [];

    async function desenhar() {
      raiz.dataset.estado = atual ? 'ativa' : 'nenhuma';
      if (!atual) {
        info.innerHTML = lista.length
          ? 'Nenhuma empresa selecionada: o cálculo usa os valores genéricos digitados abaixo.'
          : `Nenhuma empresa cadastrada ainda. Cadastre em <a href="#/empresas">Cadastros → Empresas</a> ou importe um SPED/XML num
             <a href="#/lote-classificacao">processamento em lote</a> (o hub pergunta se as empresas dos arquivos devem ser adicionadas).`;
        return;
      }
      const e = atual;
      const onde = await local(e);
      if (atual !== e) return; // trocou enquanto buscava o município
      info.innerHTML = `<div class="empresa-sim-dados"><strong>${esc(nome(e))}</strong>
          <span>CNPJ ${esc(fmt.cnpj(e.cnpj))}</span><span>${esc(regimeTexto(e))}</span>${onde ? `<span>${esc(onde)}</span>` : ''}
          ${e.revisado ? '' : '<span class="etq etq-amarelo" title="Adicionada a partir de uma importação">Cadastro não conferido</span>'}
          <a href="#/empresas?id=${encodeURIComponent(e.id)}">Abrir cadastro</a></div>
        ${usadas.length ? `<div class="empresa-sim-usadas">Preenchido pelo cadastro (campos com borda colorida): ${esc(usadas.join('; '))}.</div>` : ''}
        ${!e.regime ? '<div class="empresa-sim-alerta">O regime tributário desta empresa ainda não foi informado: complete o cadastro para que a simulação use o regime correto.</div>' : ''}
        ${aviso ? `<div class="empresa-sim-alerta">${aviso}</div>` : ''}`;
    }

    function aplicar() {
      const r = opts.aoMudar ? opts.aoMudar(atual) || {} : {};
      usadas = Array.isArray(r) ? r : (r.usadas || []);
      aviso = Array.isArray(r) ? '' : (r.aviso || '');
      desenhar();
    }

    sel.onchange = () => {
      atual = lista.find((e) => String(e.id) === sel.value) || null;
      gravarPreferencia(atual && atual.id);
      if (opts.raiz) desmarcar(opts.raiz);
      aplicar();
    };

    const pronto = (async () => {
      lista = await listar(true);
      sel.innerHTML = '<option value="">Nenhuma – valores genéricos</option>' + lista.map((e) =>
        `<option value="${esc(e.id)}">${esc(e.razaoSocial)} – ${esc(fmt.cnpj(e.cnpj))}${e.revisado ? '' : ' (a conferir)'}</option>`).join('');
      const pref = lerPreferencia();
      atual = pref ? lista.find((e) => e.id === pref) || null : null;
      sel.value = atual ? String(atual.id) : '';
      if (atual) aplicar(); else desenhar();
    })();

    return {
      empresa: () => atual,
      usadas: () => usadas.slice(),
      linhasRelatorio: () => linhasRelatorio(atual, usadas),
      /** Aplica de novo (ex.: depois que listas dependentes, como as atividades do Simples, carregaram). */
      reaplicar: () => { if (atual) aplicar(); },
      pronto,
    };
  }

  // ------------------------------------------------------------------ empresas encontradas na importação

  /**
   * Depois de importar SPED/XML: pergunta se as empresas dos arquivos que não estão no cadastro devem ser adicionadas.
   * Cada empresa escolhida é cadastrada com a consulta completa do CNPJ (situação, CNAEs, endereço, quadro societário).
   * Sem a ferramenta "Empresas", só avisa. token: código devolvido pela importação (nada foi gravado ainda).
   */
  async function perguntarCadastro(token) {
    if (!token) return;
    const r = await api.get('/hub/empresas/encontradas/' + encodeURIComponent(token));
    if (!r.ok || !Array.isArray(r.data) || !r.data.length) return;
    const lista = r.data;
    if (!Hub.modulos.some((m) => m.rota === 'empresas')) {
      Hub.toast(`${lista.length} empresa(s) dos arquivos não estão no cadastro. Peça a quem tem acesso a Cadastros → Empresas para incluí-las.`);
      return;
    }
    const origem = { SPED: 'SPED (contribuinte)', XML: 'XML (emitente)' };
    const c = Hub.modal(`<h2>Adicionar empresas ao cadastro?</h2>
      <p>Os arquivos trazem ${lista.length === 1 ? 'uma empresa que ainda não está' : `${lista.length} empresas que ainda não estão`} no cadastro.
        Marque as que deseja adicionar: cada uma é cadastrada com a <strong>consulta completa do CNPJ</strong> na base pública
        (situação cadastral, CNAEs, endereço, Simples/MEI e quadro societário).</p>
      ${lista.some((e) => e.origem === 'XML') ? '<p class="sutil">Nos XMLs, a empresa considerada é o <strong>emitente</strong>: em notas de compra, é o fornecedor. Desmarque quem não for cliente do escritório.</p>' : ''}
      <label class="check" style="margin:6px 0"><input type="checkbox" data-todas checked> Marcar todas</label>
      <div class="tabela-wrap" style="max-height:45vh"><table class="tab"><thead><tr><th></th><th>Razão social</th><th>CNPJ</th><th>Origem</th><th>UF</th><th>Regime no arquivo</th></tr></thead><tbody>
      ${lista.map((e, i) => `<tr><td><input type="checkbox" data-cnpj="${esc(e.cnpj)}" checked aria-label="Adicionar ${esc(e.razaoSocial)}"></td>
        <td>${esc(e.razaoSocial)}${e.nomeFantasia ? `<div class="sutil">${esc(e.nomeFantasia)}</div>` : ''}</td>
        <td><code>${esc(fmt.cnpj(e.cnpj))}</code></td><td>${esc(origem[e.origem] || e.origem)}</td><td>${esc(e.uf || '–')}</td>
        <td>${esc(e.regime ? regimeTexto(e) : 'não informado')}</td></tr>`).join('')}
      </tbody></table></div>
      <div data-res-cad></div>
      <div class="acoes" style="margin-top:12px"><button class="btn" type="button" data-adicionar>Adicionar selecionadas</button>
        <button class="btn btn-sec" type="button" data-agora-nao>Agora não</button><span class="sutil" data-qtd></span></div>`);
    const caixas = () => Array.from(c.querySelectorAll('[data-cnpj]'));
    const contar = () => { c.querySelector('[data-qtd]').textContent = `${caixas().filter((x) => x.checked).length} de ${lista.length} selecionada(s)`; };
    c.querySelector('[data-todas]').onchange = (e) => { caixas().forEach((x) => { x.checked = e.target.checked; }); contar(); };
    caixas().forEach((x) => { x.onchange = contar; });
    contar();
    c.querySelector('[data-agora-nao]').onclick = Hub.fecharModal;
    c.querySelector('[data-adicionar]').onclick = async (ev) => {
      const cnpjs = caixas().filter((x) => x.checked).map((x) => x.dataset.cnpj);
      if (!cnpjs.length) { Hub.toast('Marque ao menos uma empresa.', 'erro'); return; }
      const b = ev.currentTarget;
      b.disabled = true;
      const res = c.querySelector('[data-res-cad]');
      res.innerHTML = `<div class="carregando"><div class="giro"></div>Cadastrando e consultando ${cnpjs.length} CNPJ(s) na base pública...</div>`;
      const rr = await api.post('/hub/empresas/encontradas/' + encodeURIComponent(token), { cnpjs });
      if (!rr.ok) { res.innerHTML = Hub.problema(rr, 'Empresas não adicionadas'); b.disabled = false; return; }
      invalidar();
      const ok = rr.data.filter((x) => x.situacao === 'CADASTRADA');
      const semConsulta = ok.filter((x) => x.consulta !== 'OK');
      res.innerHTML = `<div class="${semConsulta.length ? 'aviso' : 'ok-caixa'}"><strong>${ok.length} empresa(s) adicionada(s)</strong>
        ${ok.length - semConsulta.length ? ` – ${ok.length - semConsulta.length} com os dados da consulta do CNPJ` : ''}.
        ${semConsulta.length ? `<br>Sem consulta (ficaram com os dados do arquivo; use "Consultar CNPJ" no cadastro depois):<ul>${semConsulta.map((x) => `<li>${esc(x.razaoSocial)} – ${esc(x.consulta || '')}</li>`).join('')}</ul>` : ''}
        ${rr.data.some((x) => x.situacao === 'JA_CADASTRADA') ? '<br>Algumas já estavam cadastradas e não foram alteradas.' : ''}
        <br>Confira o regime e as particularidades em <a href="#/empresas?conferir=1" data-fechar-cad>Cadastros → Empresas</a>.</div>`;
      res.querySelector('[data-fechar-cad]').onclick = Hub.fecharModal;
      c.querySelector('[data-adicionar]').hidden = true;
      c.querySelector('[data-agora-nao]').textContent = 'Fechar';
    };
  }

  /** Faixa no topo do resultado: deixa claro para qual empresa a simulação foi feita. */
  function faixaResultado(e) {
    return e
      ? `<div class="empresa-res">${Hub.icone('empresa')}<span>Simulação para <strong>${esc(nome(e))}</strong> · CNPJ ${esc(fmt.cnpj(e.cnpj))} · ${esc(regimeTexto(e))}</span></div>`
      : `<div class="empresa-res generica">${Hub.icone('empresa')}<span>Simulação genérica: nenhuma empresa selecionada (valores digitados na tela).</span></div>`;
  }

  Hub.empresas = {
    listar, invalidar, seletor, regimeTexto, ehSimples, nome, linhasRelatorio, nomeMunicipio, definir, desmarcar, abrirCadastro, faixaResultado,
    perguntarCadastro,
    REGIMES, APURACOES, CRT, OP_SIMP_NAC, ORIGENS, DOCUMENTOS,
  };
})();
