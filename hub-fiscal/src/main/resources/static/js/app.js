/* Hub Fiscal – núcleo: rotas, menu, chamadas às APIs e utilitários. */
'use strict';

window.Hub = (() => {
  const GRUPOS = [
    'Consultas e tabelas',
    'Calculadoras – Regime Regular',
    'Calculadoras – Simples Nacional',
    'Processamento em lote',
    'Documentos XML',
    'Sistema',
  ];
  const modulos = [];
  let moduloAtual = null;

  // ------------------------------------------------------------------ registro e rotas
  function registrar(m) {
    modulos.push(Object.assign({ menu: true, ordem: 50, etiquetas: [] }, m));
  }

  function modulo(rota) {
    return modulos.find((m) => m.rota === rota);
  }

  function lerHash() {
    const h = location.hash.replace(/^#\/?/, '');
    const [rota, query] = h.split('?');
    const params = Object.fromEntries(new URLSearchParams(query || ''));
    return { rota: rota || '', params };
  }

  function ir(rota, params) {
    const q = params ? '?' + new URLSearchParams(params).toString() : '';
    location.hash = '#/' + rota + q;
  }

  async function rotear() {
    const { rota, params } = lerHash();
    const m = modulo(rota) || modulo('');
    moduloAtual = m;
    document.querySelectorAll('.menu-item').forEach((a) => a.classList.toggle('ativo', a.dataset.rota === m.rota));
    if (m.grupo) alternarGrupo(m.grupo, true);
    registrarRecente(m.rota);
    document.getElementById('tituloPagina').innerHTML = m.grupo
      ? `<span class="migalha">${esc(m.grupo)}</span><span class="migalha-sep">/</span><strong>${esc(m.titulo)}</strong>`
      : `<strong>${esc(m.titulo)}</strong>`;
    document.title = m.titulo + ' – Hub Fiscal';
    document.getElementById('sidebar').classList.remove('aberta');
    const el = document.getElementById('conteudo');
    el.innerHTML = '';
    el.scrollTop = 0;
    window.scrollTo(0, 0);
    try {
      await m.render(el, params);
    } catch (e) {
      console.error(e);
      el.innerHTML = `<div class="erro-caixa"><strong>Erro ao abrir a tela.</strong><br>${esc(e.message || e)}</div>`;
    }
    el.focus({ preventScroll: true });
  }

  // ------------------------------------------------------------------ menu lateral
  const pref = {
    ler(chave, padrao) { try { const v = localStorage.getItem('hub.' + chave); return v === null ? padrao : JSON.parse(v); } catch { return padrao; } },
    gravar(chave, v) { try { localStorage.setItem('hub.' + chave, JSON.stringify(v)); } catch { /* sem armazenamento */ } },
  };
  let gruposAbertos = new Set(pref.ler('gruposAbertos', []));

  function montarMenu() {
    const nav = document.getElementById('menu');
    const item = (m) => `<a class="menu-item" data-rota="${m.rota}" href="#/${m.rota}" title="${esc(m.titulo)}"
        data-busca="${esc(semAcento(`${m.titulo} ${m.descricao || ''} ${m.grupo || ''} ${(m.etiquetas || []).join(' ')}`))}">
        <span class="ico">${Hub.iconeRota(m.rota)}</span><span class="mtexto">${esc(m.titulo)}</span></a>`;
    const partes = [item({ rota: '', titulo: 'Início' })];
    for (const g of GRUPOS) {
      const itens = modulos.filter((m) => m.grupo === g && m.menu).sort((a, b) => a.ordem - b.ordem);
      if (!itens.length) continue;
      partes.push(`<div class="menu-grupo ${gruposAbertos.has(g) ? 'aberto' : ''}" data-grupo="${esc(g)}">
        <button class="menu-grupo-titulo" type="button" aria-expanded="${gruposAbertos.has(g)}">
          <span class="mtexto">${esc(g.replace(/^Calculadoras – /, ''))}</span><span class="qtd">${itens.length}</span>${Hub.icone('chevron', 'chev')}</button>
        <div class="menu-grupo-itens"><div class="menu-grupo-interno">${itens.map(item).join('')}</div></div></div>`);
    }
    nav.innerHTML = partes.join('');
    nav.querySelectorAll('.menu-grupo-titulo').forEach((b) => {
      b.onclick = () => alternarGrupo(b.parentElement.dataset.grupo);
    });
  }

  function alternarGrupo(g, abrir) {
    const el = document.querySelector(`.menu-grupo[data-grupo="${CSS.escape(g)}"]`);
    if (!el) return;
    const aberto = abrir ?? !el.classList.contains('aberto');
    el.classList.toggle('aberto', aberto);
    el.querySelector('.menu-grupo-titulo').setAttribute('aria-expanded', aberto);
    if (aberto) gruposAbertos.add(g); else gruposAbertos.delete(g);
    pref.gravar('gruposAbertos', [...gruposAbertos]);
  }

  function filtrarMenu(texto) {
    const q = semAcento(texto.trim());
    const nav = document.getElementById('menu');
    nav.classList.toggle('buscando', !!q);
    let primeiro = null;
    nav.querySelectorAll('.menu-item').forEach((a) => {
      const ok = !q || q.split(/\s+/).every((p) => a.dataset.busca.includes(p));
      a.classList.toggle('oculto', !ok);
      if (ok && q && !primeiro) primeiro = a;
    });
    nav.querySelectorAll('.menu-grupo').forEach((g) => g.classList.toggle('oculto', !!q && !g.querySelector('.menu-item:not(.oculto)')));
    nav.querySelectorAll('.menu-item.destaque').forEach((a) => a.classList.remove('destaque'));
    if (primeiro) primeiro.classList.add('destaque');
    nav.classList.toggle('sem-resultado', !!q && !primeiro);
    return primeiro;
  }

  const telaLarga = window.matchMedia('(min-width: 901px)');
  function recolherMenu(recolher) {
    document.body.classList.toggle('menu-recolhido', recolher && telaLarga.matches);
    const b = document.getElementById('btnRecolher');
    b.title = recolher ? 'Fixar menu aberto' : 'Recolher menu';
    b.setAttribute('aria-label', b.title);
    pref.gravar('menuRecolhido', recolher);
  }

  function registrarRecente(rota) {
    if (!rota) return;
    const r = pref.ler('recentes', []).filter((x) => x !== rota);
    r.unshift(rota);
    pref.gravar('recentes', r.slice(0, 5));
  }
  const recentes = () => pref.ler('recentes', []).filter((r) => modulo(r));

  function prepararMenu() {
    document.getElementById('btnRecolher').innerHTML = Hub.icone('painel');
    document.querySelector('.menu-busca-ico').innerHTML = Hub.icone('busca');
    recolherMenu(pref.ler('menuRecolhido', false));
    telaLarga.addEventListener('change', () => recolherMenu(pref.ler('menuRecolhido', false)));
    document.getElementById('btnRecolher').onclick = () => recolherMenu(!document.body.classList.contains('menu-recolhido'));
    const busca = document.getElementById('menuBusca');
    busca.addEventListener('input', () => filtrarMenu(busca.value));
    busca.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const a = filtrarMenu(busca.value);
        if (a) { location.hash = a.getAttribute('href'); busca.value = ''; filtrarMenu(''); busca.blur(); }
      } else if (e.key === 'Escape') { busca.value = ''; filtrarMenu(''); busca.blur(); }
    });
    document.addEventListener('keydown', (e) => {
      const alvo = e.target;
      const digitando = alvo.closest && alvo.closest('input, textarea, select, [contenteditable]');
      if ((e.key === '/' && !digitando) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey))) {
        e.preventDefault();
        const alvoBusca = document.querySelector('#conteudo .busca-home input') || busca;
        if (alvoBusca === busca) document.getElementById('sidebar').classList.add('aberta');
        alvoBusca.focus();
        alvoBusca.select();
      }
    });
  }

  // ------------------------------------------------------------------ status no topo
  async function atualizarStatus() {
    const r = await api.get('/hub/status');
    const pilRG = document.getElementById('pilRG');
    const pilSN = document.getElementById('pilSN');
    const pilBase = document.getElementById('pilBase');
    if (!r.ok) {
      [pilRG, pilSN].forEach((p) => { p.classList.remove('ok'); p.classList.add('erro'); });
      pilBase.textContent = 'Hub sem resposta';
      return;
    }
    const s = r.data;
    pilRG.classList.toggle('ok', !!s.regimeGeral.online);
    pilRG.classList.toggle('erro', !s.regimeGeral.online);
    pilSN.classList.toggle('ok', !!s.simplesNacional.online);
    pilSN.classList.toggle('erro', !s.simplesNacional.online);
    const b = s.bancoOficial || {};
    pilBase.textContent = b.versao ? `Base ${b.versao}` : 'Base indisponível';
    pilBase.title = b.data ? `Banco oficial ${b.versao} de ${String(b.data).substring(0, 10)}` : (b.erro || '');
    [pilRG, pilSN, pilBase].forEach((p) => { p.onclick = () => ir('status'); });
    Hub.statusAtual = s;
  }

  // ------------------------------------------------------------------ chamadas HTTP
  // ------------------------------------------------------------------ autenticação
  function cookie(nome) {
    const c = document.cookie.split('; ').find((x) => x.startsWith(nome + '='));
    return c ? decodeURIComponent(c.split('=')[1]) : '';
  }

  /** Token CSRF (cookie XSRF-TOKEN) exigido pelo servidor em POST/PUT/DELETE. */
  async function tokenCsrf() {
    if (!cookie('XSRF-TOKEN')) await fetch('/auth/csrf', { headers: { Accept: 'application/json' } });
    return cookie('XSRF-TOKEN');
  }

  /** Sessão expirada/encerrada: volta ao login e, depois de entrar, para a mesma tela. */
  function irParaLogin(motivo) {
    const voltar = location.hash && location.hash !== '#/' ? '&voltar=' + encodeURIComponent(location.hash) : '';
    location.replace(`/login.html?${motivo || 'expirou'}=1${voltar}`);
  }

  /** Trata respostas de autenticação comuns a fetch e XHR. Retorna true se redirecionou. */
  function tratarAuth(status, data) {
    if (status === 401) { irParaLogin('expirou'); return true; }
    if (status === 403 && data && data.codigo === 'TROCAR_SENHA') { irParaLogin('trocar'); return true; }
    return false;
  }

  async function req(metodo, url, corpo, opcoes = {}) {
    const init = { method: metodo, headers: { Accept: 'application/json, */*' } };
    if (metodo !== 'GET') init.headers['X-XSRF-TOKEN'] = await tokenCsrf();
    if (corpo instanceof FormData) {
      init.body = corpo;
    } else if (corpo !== undefined && corpo !== null) {
      if (typeof corpo === 'string') {
        init.body = corpo;
        init.headers['Content-Type'] = opcoes.contentType || 'application/json';
      } else {
        init.body = JSON.stringify(corpo);
        init.headers['Content-Type'] = 'application/json';
      }
    }
    let resp;
    try {
      resp = await fetch(url, init);
    } catch (e) {
      return { ok: false, status: 0, data: { title: 'Sem conexão com o hub', detail: String(e.message || e) }, texto: '' };
    }
    const ct = resp.headers.get('Content-Type') || '';
    const aviso = resp.headers.get('x-warning-dados-simulados');
    if (opcoes.blob) {
      if (resp.ok) return { ok: true, status: resp.status, blob: await resp.blob(), headers: resp.headers };
    }
    const texto = await resp.text();
    let data = texto;
    if (ct.includes('json') && texto) {
      try { data = JSON.parse(texto); } catch (e) { /* mantém texto */ }
    }
    if (!url.startsWith('/auth/') && tratarAuth(resp.status, data)) {
      return new Promise(() => {}); // a página está sendo trocada pelo login
    }
    // token CSRF desatualizado (ex.: cookie apagado): renova e tenta uma vez
    if (resp.status === 403 && data && data.codigo === 'CSRF' && !opcoes.repetido) {
      document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/';
      return req(metodo, url, corpo, Object.assign({}, opcoes, { repetido: true }));
    }
    return { ok: resp.ok, status: resp.status, data, texto, aviso, contentType: ct };
  }

  const api = {
    get: (url) => req('GET', url),
    post: (url, corpo, opcoes) => req('POST', url, corpo, opcoes),
    put: (url, corpo) => req('PUT', url, corpo),
    del: (url) => req('DELETE', url),
    /** Calculadora do Regime Geral (via proxy). */
    rg: (caminho) => '/proxy/rg' + caminho,
    /** Calculadora do Simples Nacional (via proxy). */
    sn: (caminho) => '/proxy/sn' + caminho,
    req,
  };

  /** Cache simples de GETs (tabelas que mudam pouco). */
  const cacheGet = new Map();
  async function getCache(url) {
    if (cacheGet.has(url)) return cacheGet.get(url);
    const r = await api.get(url);
    if (r.ok) cacheGet.set(url, r);
    return r;
  }

  // ------------------------------------------------------------------ utilitários
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  const nfMoeda = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const nfNum = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 6 });
  const fmt = {
    moeda: (v) => (v === null || v === undefined || v === '' || isNaN(Number(v)) ? '–' : 'R$ ' + nfMoeda.format(Number(v))),
    valor: (v) => (v === null || v === undefined || v === '' || isNaN(Number(v)) ? '–' : nfMoeda.format(Number(v))),
    num: (v) => (v === null || v === undefined || v === '' || isNaN(Number(v)) ? '–' : nfNum.format(Number(v))),
    pct: (v) => (v === null || v === undefined || v === '' || isNaN(Number(v)) ? '–' : nfNum.format(Number(v)) + '%'),
    data: (v) => {
      if (!v) return '–';
      const m = String(v).match(/^(\d{4})-(\d{2})-(\d{2})/);
      return m ? `${m[3]}/${m[2]}/${m[1]}` : String(v);
    },
    cnpj: (v) => {
      const d = String(v || '').replace(/\D/g, '');
      if (d.length === 14) return d.replace(/^(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})$/, '$1.$2.$3/$4-$5');
      if (d.length === 11) return d.replace(/^(\d{3})(\d{3})(\d{3})(\d{2})$/, '$1.$2.$3-$4');
      return v || '';
    },
  };

  function hoje() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  /** Data/hora local com fuso, no formato aceito pela calculadora (2026-03-10T10:00:00-03:00). */
  function dataHoraComFuso(dataISO, hora = '10:00') {
    const off = -new Date(`${dataISO}T${hora}:00`).getTimezoneOffset();
    const sinal = off >= 0 ? '+' : '-';
    const hh = String(Math.floor(Math.abs(off) / 60)).padStart(2, '0');
    const mm = String(Math.abs(off) % 60).padStart(2, '0');
    return `${dataISO}T${hora}:00${sinal}${hh}:${mm}`;
  }

  /** Converte "1.234,56" ou "1234.56" em número (ou null). */
  function numero(v) {
    if (v === null || v === undefined) return null;
    let s = String(v).trim();
    if (!s) return null;
    if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.');
    const n = Number(s);
    return isNaN(n) ? null : n;
  }

  function el(html) {
    const t = document.createElement('template');
    t.innerHTML = html.trim();
    return t.content.firstElementChild;
  }

  function debounce(fn, ms = 300) {
    let t;
    return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  }

  function semAcento(s) {
    return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  }

  // ------------------------------------------------------------------ interface
  function toast(msg, tipo) {
    const t = el(`<div class="toast ${tipo === 'erro' ? 'erro' : ''}">${esc(msg)}</div>`);
    document.getElementById('toasts').appendChild(t);
    setTimeout(() => t.remove(), tipo === 'erro' ? 7000 : 3500);
  }

  function modal(html) {
    const m = document.getElementById('modal');
    const c = document.getElementById('modalConteudo');
    if (typeof html === 'string') c.innerHTML = html; else { c.innerHTML = ''; c.appendChild(html); }
    m.hidden = false;
    return c;
  }

  function fecharModal() {
    document.getElementById('modal').hidden = true;
  }

  function carregando(alvo, msg = 'Consultando...') {
    alvo.innerHTML = `<div class="carregando"><div class="giro"></div>${esc(msg)}</div>`;
  }

  /** HTML de erro a partir de uma resposta (ProblemDetail das calculadoras ou do hub). */
  /**
   * Caixa de erro. `dica` explica a causa provável quando a calculadora oficial recusa
   * sem detalhar o motivo (algumas validações devolvem 400/422 com corpo vazio).
   */
  function problema(r, titulo, dica) {
    const d = r && r.data;
    const semCorpo = d === undefined || d === null || d === '';
    if (semCorpo && r && r.status === 0) {
      return `<div class="erro-caixa"><strong>${esc(titulo || 'Não foi possível concluir')}</strong><br>A calculadora oficial está fora do ar.
        Veja a tela <a href="#/status">Situação do sistema</a>.</div>`;
    }
    if (semCorpo && r && (r.status === 400 || r.status === 422)) {
      return `<div class="erro-caixa"><strong>${esc(titulo || 'Não foi possível concluir')}</strong>
        <br>A calculadora oficial recusou os dados sem informar o motivo.
        ${dica ? `<br><strong>Causa provável:</strong> ${dica}` : '<br>Confira a combinação de documento, regime, data e classificação informada.'}
        <div class="sutil" style="margin-top:6px">HTTP ${r.status}</div></div>`;
    }
    let html = `<div class="erro-caixa"><strong>${esc(titulo || (d && d.title) || 'Não foi possível concluir')}</strong>`;
    if (d && typeof d === 'object') {
      if (titulo && d.title) html += `<br>${esc(d.title)}`;
      if (d.detail) html += `<br>${esc(d.detail)}`;
      const listas = [d.erros, d.errors, d.invalidParams, d.objects, d.objetos].filter(Array.isArray);
      const itens = [];
      for (const l of listas) {
        for (const e of l) {
          if (e && typeof e === 'object') {
            const partes = [e.code || e.codigo, e.title || e.titulo, e.detail || e.descricao || e.message || e.userMessage, e.pointer || e.name].filter(Boolean);
            if (partes.length) itens.push(partes.join(' – '));
            if (Array.isArray(e.erros)) e.erros.forEach((x) => itens.push([x.code, x.detail, x.pointer].filter(Boolean).join(' – ')));
          } else if (e) itens.push(String(e));
        }
      }
      if (itens.length) html += '<ul>' + itens.map((i) => `<li>${esc(i)}</li>`).join('') + '</ul>';
      if (!d.detail && !itens.length && !d.title) html += `<pre class="json">${esc(JSON.stringify(d, null, 2))}</pre>`;
    } else if (d) {
      html += `<br>${esc(String(d).substring(0, 800))}`;
    }
    if (r && r.status) html += `<div class="sutil" style="margin-top:6px">HTTP ${r.status}</div>`;
    return html + '</div>';
  }

  function baixarBlob(blob, nome) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = nome;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  function baixarTexto(texto, nome, tipo = 'application/json') {
    baixarBlob(new Blob([texto], { type: tipo + ';charset=utf-8' }), nome);
  }

  /** Baixa um arquivo de uma URL do hub (GET), respeitando o nome enviado pelo servidor. */
  async function baixarUrl(url, nomePadrao) {
    const r = await req('GET', url, null, { blob: true });
    if (!r.ok) { toast('Não foi possível gerar o arquivo.', 'erro'); return; }
    const cd = r.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename\*=UTF-8''([^;]+)/i) || cd.match(/filename="?([^";]+)"?/i);
    baixarBlob(r.blob, m ? decodeURIComponent(m[1]) : nomePadrao);
  }

  /** Exporta uma tabela para Excel pelo hub. */
  async function exportarExcel(pedido) {
    const r = await req('POST', '/hub/excel', pedido, { blob: true });
    if (!r.ok) { toast('Falha ao gerar o Excel.', 'erro'); return; }
    baixarBlob(r.blob, (pedido.arquivo || 'hub-fiscal') + '.xlsx');
  }

  /** Texto do cabeçalho x-warning-dados-simulados (códigos do enum TipoWarningDadosSimulados da calculadora). */
  const AVISOS_SIMULADOS = {
    1: 'Dados simulados: a base oficial usa valores de teste para esta data.',
    2: 'Compra governamental: redutores e transferências calculados com dados simulados.',
    3: 'Imposto Seletivo calculado com dados simulados.',
    4: 'Compra governamental com Imposto Seletivo: dados simulados.',
    5: 'Alíquotas fictícias: os valores são apenas ilustrativos.',
    6: 'Cálculo simulado com as alíquotas informadas (a base oficial não tem alíquotas de referência para esta data).',
  };
  function avisoSimulado(codigo) {
    if (!codigo) return null;
    return AVISOS_SIMULADOS[String(codigo).trim()] || `Aviso da calculadora: dados simulados (código ${codigo}).`;
  }

  // ------------------------------------------------------------------ usuário logado
  function iniciais(nome) {
    const p = String(nome || '?').trim().split(/\s+/);
    return ((p[0] || '')[0] + (p.length > 1 ? p[p.length - 1][0] : '')).toUpperCase();
  }

  function montarUsuario(u) {
    const alvo = document.getElementById('usuarioMenu');
    alvo.innerHTML = `<button class="usuario-btn" type="button" aria-haspopup="true" aria-expanded="false">
        <span class="avatar">${esc(iniciais(u.nome))}</span><span class="u-nome">${esc(u.nome)}</span></button>
      <div class="usuario-pop" hidden>
        <div class="u-cab"><strong>${esc(u.nome)}</strong><small class="sutil">${esc(u.login)} · ${esc(u.perfilRotulo)}</small></div>
        ${u.perfil === 'ADMIN' ? `<button type="button" data-u="usuarios">${Hub.icone('usuarios')} Gerenciar usuários</button>` : ''}
        <button type="button" data-u="senha">${Hub.icone('cadeado')} Trocar senha</button>
        <button type="button" data-u="sair" class="perigo">${Hub.icone('sair')} Sair</button>
      </div>`;
    const btn = alvo.querySelector('.usuario-btn');
    const pop = alvo.querySelector('.usuario-pop');
    const abrir = (sim) => { pop.hidden = !sim; btn.setAttribute('aria-expanded', sim); };
    btn.onclick = (e) => { e.stopPropagation(); abrir(pop.hidden); };
    document.addEventListener('click', (e) => { if (!alvo.contains(e.target)) abrir(false); });
    alvo.querySelectorAll('[data-u]').forEach((b) => {
      b.onclick = () => {
        abrir(false);
        if (b.dataset.u === 'usuarios') ir('usuarios');
        if (b.dataset.u === 'senha') trocarSenha();
        if (b.dataset.u === 'sair') sair();
      };
    });
  }

  function trocarSenha() {
    const c = modal(`<h2>Trocar senha</h2>
      <form data-f class="grade" style="gap:12px;max-width:380px">
        <div class="campo"><label>Senha atual</label><input type="password" name="atual" autocomplete="current-password" required></div>
        <div class="campo"><label>Nova senha</label><input type="password" name="nova" autocomplete="new-password" required>
          <span class="ajuda">Mínimo de 8 caracteres, com letras e números.</span></div>
        <div class="campo"><label>Confirme a nova senha</label><input type="password" name="nova2" autocomplete="new-password" required></div>
        <div data-erro></div>
        <div class="acoes"><button class="btn" type="submit">Salvar</button><button class="btn btn-sec" type="button" data-fechar-m>Cancelar</button></div>
      </form>`);
    const f = c.querySelector('[data-f]');
    c.querySelector('[data-fechar-m]').onclick = fecharModal;
    f.querySelector('[name=atual]').focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const erro = f.querySelector('[data-erro]');
      if (f.nova.value !== f.nova2.value) { erro.innerHTML = '<div class="erro-caixa">As senhas não conferem.</div>'; return; }
      const r = await api.post('/auth/senha', { senhaAtual: f.atual.value, novaSenha: f.nova.value });
      if (!r.ok) { erro.innerHTML = problema(r, 'Senha não alterada'); return; }
      fecharModal();
      toast('Senha alterada.');
    };
  }

  async function sair() {
    await req('POST', '/auth/logout');
    location.replace('/login.html');
  }

  /** Telas com `perfil` só aparecem (e só abrem) para quem tem o perfil. */
  function aplicarPerfil(u) {
    for (let i = modulos.length - 1; i >= 0; i--) {
      if (modulos[i].perfil && modulos[i].perfil !== u.perfil) modulos.splice(i, 1);
    }
  }

  async function iniciar() {
    const eu = await req('GET', '/auth/eu');
    if (!eu.ok) { irParaLogin(eu.status === 401 ? 'expirou' : 'erro'); return; }
    if (eu.data.trocarSenha) { irParaLogin('trocar'); return; }
    Hub.usuario = eu.data;
    aplicarPerfil(eu.data);
    montarUsuario(eu.data);
    montarMenu();
    prepararMenu();
    window.addEventListener('hashchange', rotear);
    document.getElementById('btnMenu').onclick = () => document.getElementById('sidebar').classList.toggle('aberta');
    document.querySelectorAll('[data-fechar]').forEach((b) => { b.onclick = fecharModal; });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') fecharModal(); });
    rotear();
    atualizarStatus();
    setInterval(atualizarStatus, 60000);
  }

  return {
    GRUPOS, modulos, registrar, iniciar, ir, rotear, api, getCache, esc, fmt, hoje, dataHoraComFuso, numero, el,
    debounce, semAcento, toast, modal, fecharModal, carregando, problema, baixarBlob, baixarTexto, baixarUrl,
    exportarExcel, avisoSimulado, statusAtual: null, atual: () => moduloAtual, recentes,
    usuario: null, tokenCsrf, tratarAuth,
  };
})();
