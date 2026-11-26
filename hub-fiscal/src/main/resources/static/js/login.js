/* Tela de login do HUB Fiscal (arquivo separado: a política CSP não permite scripts inline). */
'use strict';
(() => {
  const $ = (s) => document.querySelector(s);
  const cookie = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').split('=')[1];

  async function csrf() {
    if (!cookie('XSRF-TOKEN')) await fetch('/auth/csrf', { headers: { Accept: 'application/json' } });
    return decodeURIComponent(cookie('XSRF-TOKEN') || '');
  }
  async function post(url, corpo) {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-XSRF-TOKEN': await csrf() },
      body: JSON.stringify(corpo || {}),
    });
    let data = null;
    try { data = await r.json(); } catch (e) { /* sem corpo */ }
    return { ok: r.ok, status: r.status, data };
  }
  function mostrar(id) {
    ['fLogin', 'fPrimeiro', 'fNaoConfigurado', 'fTrocar'].forEach((f) => { $('#' + f).hidden = f !== id; });
    const primeiro = $('#' + id).querySelector('input');
    if (primeiro) primeiro.focus();
  }
  function msg(form, texto, tipo) {
    const m = form.querySelector('.login-msg');
    m.textContent = texto || '';
    m.className = 'login-msg ' + (tipo || 'erro');
    m.hidden = !texto;
  }
  function ocupado(form, sim) {
    const b = form.querySelector('[type=submit]');
    b.disabled = sim;
    b.classList.toggle('carregando-btn', sim);
  }
  const erroDe = (r) => (r.data && (r.data.detail || r.data.title)) || 'Não foi possível concluir. Tente novamente.';
  const destino = () => {
    const v = new URLSearchParams(location.search).get('voltar');
    return v && v.startsWith('#/') ? '/' + v : '/';
  };

  // mostrar/ocultar senha e aviso de Caps Lock
  document.querySelectorAll('[data-ver]').forEach((b) => {
    b.onclick = () => {
      const i = document.getElementById(b.dataset.ver);
      i.type = i.type === 'password' ? 'text' : 'password';
      b.textContent = i.type === 'password' ? 'Mostrar' : 'Ocultar';
    };
  });
  $('#lSenha').addEventListener('keyup', (e) => { $('.caps').hidden = !(e.getModifierState && e.getModifierState('CapsLock')); });

  $('#fLogin').onsubmit = async (e) => {
    e.preventDefault();
    const f = e.target;
    msg(f, '');
    ocupado(f, true);
    const r = await post('/auth/login', { usuario: $('#lUsuario').value, senha: $('#lSenha').value });
    ocupado(f, false);
    if (!r.ok) {
      msg(f, erroDe(r));
      $('#lSenha').value = '';
      $('#lSenha').focus();
      return;
    }
    if (r.data.trocarSenha) {
      $('#fTrocar [data-nome]').textContent = r.data.nome;
      $('#tAtual').value = $('#lSenha').value;
      mostrar('fTrocar');
      $('#tNova').focus();
      return;
    }
    location.replace(destino());
  };

  $('#fPrimeiro').onsubmit = async (e) => {
    e.preventDefault();
    const f = e.target;
    if ($('#pSenha').value !== $('#pSenha2').value) { msg(f, 'As senhas não conferem.'); return; }
    ocupado(f, true);
    const r = await post('/auth/primeiro-acesso', { usuario: $('#pUsuario').value, nome: $('#pNome').value, senha: $('#pSenha').value });
    ocupado(f, false);
    if (!r.ok) { msg(f, erroDe(r)); return; }
    location.replace('/');
  };

  $('#fTrocar').onsubmit = async (e) => {
    e.preventDefault();
    const f = e.target;
    if ($('#tNova').value !== $('#tNova2').value) { msg(f, 'As senhas não conferem.'); return; }
    ocupado(f, true);
    const r = await post('/auth/senha', { senhaAtual: $('#tAtual').value, novaSenha: $('#tNova').value });
    ocupado(f, false);
    if (!r.ok) { msg(f, erroDe(r)); return; }
    location.replace(destino());
  };

  $('[data-sair]').onclick = async () => { await post('/auth/logout'); location.replace('/login.html'); };

  // estado inicial: já logado? primeiro acesso?
  (async () => {
    const eu = await fetch('/auth/eu', { headers: { Accept: 'application/json' } });
    if (eu.ok) {
      const u = await eu.json();
      if (!u.trocarSenha) { location.replace(destino()); return; }
      $('#fTrocar [data-nome]').textContent = u.nome;
      mostrar('fTrocar');
      return;
    }
    await csrf();
    const est = await (await fetch('/auth/estado', { headers: { Accept: 'application/json' } })).json();
    if (est.primeiroAcesso) mostrar(est.acessoLocal ? 'fPrimeiro' : 'fNaoConfigurado');
    else {
      mostrar('fLogin');
      const q = new URLSearchParams(location.search);
      if (q.has('expirou')) msg($('#fLogin'), 'Sua sessão terminou. Entre novamente.', 'info');
    }
  })();
})();
