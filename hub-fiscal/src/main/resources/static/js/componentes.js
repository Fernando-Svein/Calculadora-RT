/* HUB Fiscal – componentes reutilizáveis. */
'use strict';

(() => {
  const { esc, fmt, el, api, semAcento } = Hub;

  // ------------------------------------------------------------------ tabela
  /**
   * Tabela com busca, ordenação, paginação, exportação para Excel e linha de detalhe.
   * colunas: [{ titulo, campo, render(linha), tipo: 'texto'|'moeda'|'num'|'pct', exportar(linha) }]
   */
  function tabela(container, opts) {
    const o = Object.assign({ busca: true, porPagina: 100, linhas: [], vazio: 'Nenhum registro.' }, opts);
    let linhas = o.linhas || [];
    let filtradas = linhas;
    let pagina = 0;
    let ordem = null;
    let filtroExtra = null;
    let termo = '';

    const raiz = el(`<div class="tabela-comp">
      <div class="tabela-barra">
        ${o.busca ? `<input type="search" placeholder="${esc(o.placeholder || 'Filtrar...')}" aria-label="Filtrar tabela">` : ''}
        <span class="extras"></span>
        <span class="espaco"></span>
        <span class="tabela-info"></span>
        ${o.exportar ? `<button class="btn btn-sec btn-peq" data-exp>${Hub.icone('baixar')} Excel</button>` : ''}
      </div>
      <div class="tabela-wrap"><table class="tab"><thead></thead><tbody></tbody></table></div>
      <div class="paginacao"></div>
    </div>`);
    container.innerHTML = '';
    container.appendChild(raiz);
    if (o.extras) raiz.querySelector('.extras').appendChild(o.extras);
    const thead = raiz.querySelector('thead');
    const tbody = raiz.querySelector('tbody');
    const info = raiz.querySelector('.tabela-info');
    const pag = raiz.querySelector('.paginacao');

    const numCols = new Set(['moeda', 'num', 'pct']);
    thead.innerHTML = '<tr>' + o.colunas.map((c, i) =>
      `<th class="${numCols.has(c.tipo) ? 'num ' : ''}${c.ordenar === false ? '' : 'ord'}" data-i="${i}">${esc(c.titulo)}</th>`).join('') + '</tr>';
    thead.querySelectorAll('th.ord').forEach((th) => {
      th.onclick = () => {
        const i = Number(th.dataset.i);
        ordem = ordem && ordem.i === i ? { i, dir: -ordem.dir } : { i, dir: 1 };
        aplicar();
      };
    });

    function valorBruto(c, l) {
      if (c.exportar) return c.exportar(l);
      if (c.campo) return c.campo.split('.').reduce((a, k) => (a == null ? a : a[k]), l);
      if (c.render) return textoDeHtml(c.render(l));
      return '';
    }

    function celula(c, l) {
      if (c.render) return c.render(l);
      const v = valorBruto(c, l);
      switch (c.tipo) {
        case 'moeda': return fmt.valor(v);
        case 'num': return fmt.num(v);
        case 'pct': return fmt.pct(v);
        default: return esc(v);
      }
    }

    function aplicar() {
      const t = semAcento(termo);
      filtradas = linhas.filter((l) => (!filtroExtra || filtroExtra(l)) &&
        (!t || semAcento(o.colunas.map((c) => valorBruto(c, l)).join(' ')).includes(t)));
      if (ordem) {
        const c = o.colunas[ordem.i];
        filtradas = filtradas.slice().sort((a, b) => {
          const va = valorBruto(c, a);
          const vb = valorBruto(c, b);
          const na = Number(va);
          const nb = Number(vb);
          const r = !isNaN(na) && !isNaN(nb) && va !== '' && vb !== '' && va !== null && vb !== null
            ? na - nb : String(va ?? '').localeCompare(String(vb ?? ''), 'pt-BR', { numeric: true });
          return r * ordem.dir;
        });
      }
      pagina = 0;
      desenhar();
    }

    function desenhar() {
      const total = filtradas.length;
      const ini = pagina * o.porPagina;
      const fatia = filtradas.slice(ini, ini + o.porPagina);
      if (!total) {
        tbody.innerHTML = `<tr><td colspan="${o.colunas.length}" class="vazio">${esc(o.vazio)}</td></tr>`;
      } else {
        tbody.innerHTML = '';
        fatia.forEach((l) => {
          const tr = document.createElement('tr');
          if (o.aoClicar || o.detalhe) tr.className = 'clicavel';
          tr.innerHTML = o.colunas.map((c) => `<td class="${numCols.has(c.tipo) ? 'num' : ''}">${celula(c, l)}</td>`).join('');
          tbody.appendChild(tr);
          if (o.aoClicar) tr.onclick = (e) => { if (!e.target.closest('a,button,input,select')) o.aoClicar(l, tr); };
          else if (o.detalhe) {
            tr.onclick = (e) => {
              if (e.target.closest('a,button,input,select')) return;
              const prox = tr.nextElementSibling;
              if (prox && prox.classList.contains('detalhe')) { prox.remove(); return; }
              const d = document.createElement('tr');
              d.className = 'detalhe';
              d.innerHTML = `<td colspan="${o.colunas.length}"><div class="detalhe-corpo"></div></td>`;
              const conteudo = o.detalhe(l);
              const corpo = d.querySelector('.detalhe-corpo');
              if (typeof conteudo === 'string') corpo.innerHTML = conteudo; else corpo.appendChild(conteudo);
              tr.after(d);
            };
          }
        });
      }
      info.textContent = total === linhas.length ? `${total} registro(s)` : `${total} de ${linhas.length} registro(s)`;
      const paginas = Math.ceil(total / o.porPagina);
      pag.innerHTML = paginas > 1
        ? `<button class="btn btn-sec btn-peq" data-p="-1" ${pagina === 0 ? 'disabled' : ''}>‹ Anterior</button>
           Página ${pagina + 1} de ${paginas}
           <button class="btn btn-sec btn-peq" data-p="1" ${pagina >= paginas - 1 ? 'disabled' : ''}>Próxima ›</button>` : '';
      pag.querySelectorAll('[data-p]').forEach((b) => { b.onclick = () => { pagina += Number(b.dataset.p); desenhar(); }; });
    }

    if (o.busca) {
      raiz.querySelector('input[type=search]').oninput = Hub.debounce((e) => { termo = e.target.value; aplicar(); }, 200);
    }
    if (o.exportar) {
      raiz.querySelector('[data-exp]').onclick = async (e) => {
        const botao = e.currentTarget;
        botao.disabled = true;
        try {
          await Hub.exportarExcel({
            titulo: o.exportar.titulo,
            arquivo: o.exportar.arquivo,
            resumo: o.exportar.resumo,
            colunas: o.colunas.filter((c) => c.excel !== false).map((c) => ({
              titulo: c.titulo,
              tipo: c.tipo === 'moeda' ? 'moeda' : c.tipo === 'num' ? 'numero' : c.tipo === 'pct' ? 'percentual' : 'texto',
              largura: c.largura,
            })),
            linhas: filtradas.map((l) => o.colunas.filter((c) => c.excel !== false).map((c) => {
              const v = valorBruto(c, l);
              return v === undefined ? null : (typeof v === 'object' && v !== null ? JSON.stringify(v) : v);
            })),
          });
        } finally { botao.disabled = false; }
      };
    }
    aplicar();
    return {
      atualizar(novas) { linhas = novas || []; aplicar(); },
      filtrar(fn) { filtroExtra = fn; aplicar(); },
      linhas: () => filtradas,
    };
  }

  function textoDeHtml(h) {
    const d = document.createElement('div');
    d.innerHTML = h == null ? '' : String(h);
    return d.textContent;
  }

  // ------------------------------------------------------------------ JSON genérico → tabela
  function achatar(obj, prefixo = '', saida = {}) {
    if (obj === null || obj === undefined) return saida;
    if (Array.isArray(obj)) {
      if (obj.every((x) => typeof x !== 'object')) saida[prefixo || 'valor'] = obj.join(', ');
      else saida[prefixo || 'itens'] = obj.map((x) => (typeof x === 'object' ? Object.values(x).filter((v) => typeof v !== 'object').join(' / ') : x)).join(' | ');
      return saida;
    }
    if (typeof obj !== 'object') { saida[prefixo || 'valor'] = obj; return saida; }
    for (const [k, v] of Object.entries(obj)) {
      const chave = prefixo ? `${prefixo}.${k}` : k;
      if (v && typeof v === 'object') achatar(v, chave, saida); else saida[chave] = v;
    }
    return saida;
  }

  function jsonTabela(container, dados, opts = {}) {
    const lista = Array.isArray(dados) ? dados : [dados];
    const linhas = lista.map((x) => achatar(x));
    const chaves = [];
    linhas.forEach((l) => Object.keys(l).forEach((k) => { if (!chaves.includes(k)) chaves.push(k); }));
    return tabela(container, Object.assign({
      colunas: chaves.map((k) => ({ titulo: Hub.rotulos ? Hub.rotulos.campo(k) : k, campo: null, exportar: (l) => l[k], render: (l) => esc(typeof l[k] === 'boolean' ? (l[k] ? 'Sim' : 'Não') : l[k]) })),
      linhas,
    }, opts));
  }

  // ------------------------------------------------------------------ abas
  function abas(container, lista, inicial) {
    const raiz = el(`<div><div class="abas" role="tablist"></div><div class="abas-corpo"></div></div>`);
    container.appendChild(raiz);
    const barra = raiz.querySelector('.abas');
    const corpo = raiz.querySelector('.abas-corpo');
    const montadas = {};
    lista.forEach((a) => {
      const b = el(`<button class="aba" role="tab" data-id="${a.id}">${esc(a.titulo)}</button>`);
      b.onclick = () => mostrar(a.id);
      barra.appendChild(b);
    });
    function mostrar(id) {
      barra.querySelectorAll('.aba').forEach((b) => b.classList.toggle('ativa', b.dataset.id === id));
      Object.values(montadas).forEach((d) => { d.hidden = true; });
      if (!montadas[id]) {
        const d = document.createElement('div');
        corpo.appendChild(d);
        montadas[id] = d;
        lista.find((a) => a.id === id).render(d);
      }
      montadas[id].hidden = false;
    }
    mostrar(inicial || lista[0].id);
    return { mostrar };
  }

  // ------------------------------------------------------------------ UF / município
  /** Cria selects de UF e município. Retorna { valor(): {uf, codigoUf, municipio, nomeMunicipio} }. */
  function seletorMunicipio(container, opts = {}) {
    const raiz = el(`<div class="grade g2">
      <div class="campo"><label>UF</label><select data-uf><option value="">Carregando...</option></select></div>
      <div class="campo"><label>Município ${opts.obrigatorio === false ? '' : '*'}</label><select data-mun><option value="">Selecione a UF</option></select></div>
    </div>`);
    container.appendChild(raiz);
    const selUf = raiz.querySelector('[data-uf]');
    const selMun = raiz.querySelector('[data-mun]');
    let ufs = [];
    const pronto = (async () => {
      const r = await Hub.getCache(api.rg('/calculadora/dados-abertos/ufs'));
      ufs = r.ok ? r.data : [];
      selUf.innerHTML = '<option value="">Selecione</option>' + ufs.map((u) => `<option value="${u.sigla}" data-cod="${u.codigo}">${u.sigla} – ${esc(u.nome)}</option>`).join('');
      if (opts.uf) { selUf.value = opts.uf; await carregarMunicipios(opts.municipio); }
    })();
    async function carregarMunicipios(selecionar) {
      if (!selUf.value) { selMun.innerHTML = '<option value="">Selecione a UF</option>'; return; }
      selMun.innerHTML = '<option value="">Carregando...</option>';
      const r = await Hub.getCache(api.rg('/calculadora/dados-abertos/ufs/municipios?siglaUf=' + selUf.value));
      const lista = r.ok ? r.data : [];
      selMun.innerHTML = '<option value="">Selecione</option>' + lista.map((m) => `<option value="${m.codigo}">${esc(m.nome)}</option>`).join('');
      if (selecionar) selMun.value = String(selecionar);
      if (opts.aoMudar) opts.aoMudar();
    }
    selUf.onchange = () => carregarMunicipios();
    selMun.onchange = () => opts.aoMudar && opts.aoMudar();
    return {
      valor() {
        const o = selUf.selectedOptions[0];
        return {
          uf: selUf.value || null,
          codigoUf: o && o.dataset.cod ? Number(o.dataset.cod) : null,
          municipio: selMun.value ? Number(selMun.value) : null,
          nomeMunicipio: selMun.value ? selMun.selectedOptions[0].textContent : null,
        };
      },
      definir: async (uf, mun) => { selUf.value = uf; await carregarMunicipios(mun); },
      /** Promessa resolvida quando as listas iniciais terminaram de carregar. */
      pronto,
    };
  }

  // ------------------------------------------------------------------ autocompletar NCM/NBS
  /** Liga um input a busca de NCM ou NBS no banco oficial. tipo: 'ncm' | 'nbs' | function → tipo. */
  function autocompletarNomenclatura(input, tipo, aoEscolher) {
    const wrap = input.parentElement;
    wrap.classList.add('rel');
    let caixa = null;
    const fechar = () => { if (caixa) { caixa.remove(); caixa = null; } };
    input.setAttribute('autocomplete', 'off');
    input.addEventListener('input', Hub.debounce(async () => {
      const q = input.value.trim();
      const t = typeof tipo === 'function' ? tipo() : tipo;
      if (q.length < 3 || (/^\d+$/.test(q.replace(/\D/g, '')) && q.replace(/\D/g, '').length >= (t === 'ncm' ? 8 : 9))) { fechar(); return; }
      const r = await api.get(`/hub/busca/${t}?q=${encodeURIComponent(q)}&limite=40`);
      fechar();
      if (!r.ok || !r.data.length) return;
      caixa = el('<div class="sugestoes"></div>');
      r.data.forEach((n) => {
        const b = el(`<button type="button"><code>${esc(n.codigo)}</code> ${esc(n.descricao)}</button>`);
        b.onmousedown = (e) => { e.preventDefault(); input.value = n.codigo; fechar(); if (aoEscolher) aoEscolher(n); input.dispatchEvent(new Event('change')); };
        caixa.appendChild(b);
      });
      wrap.appendChild(caixa);
    }, 300));
    input.addEventListener('blur', () => setTimeout(fechar, 150));
  }

  // ------------------------------------------------------------------ upload
  function upload(container, opts = {}) {
    let arquivos = [];
    const raiz = el(`<div>
      <div class="upload" tabindex="0" role="button">
        <strong>${esc(opts.titulo || 'Arraste os arquivos aqui ou clique para selecionar')}</strong>
        <span class="sutil">${esc(opts.dica || '')}</span>
        <input type="file" hidden ${opts.multiplo === false ? '' : 'multiple'} accept="${esc(opts.aceita || '')}">
      </div>
      <ul class="lista-arquivos"></ul>
    </div>`);
    container.appendChild(raiz);
    const zona = raiz.querySelector('.upload');
    const input = raiz.querySelector('input');
    const lista = raiz.querySelector('.lista-arquivos');
    const desenhar = () => {
      lista.innerHTML = arquivos.map((f, i) => `<li>${esc(f.name)} <span class="sutil">(${(f.size / 1024).toFixed(0)} KB)</span> <button class="btn-link" data-rm="${i}" title="Remover">×</button></li>`).join('');
      lista.querySelectorAll('[data-rm]').forEach((b) => { b.onclick = () => { arquivos.splice(Number(b.dataset.rm), 1); desenhar(); opts.aoMudar && opts.aoMudar(arquivos); }; });
      opts.aoMudar && opts.aoMudar(arquivos);
    };
    const adicionar = (fs) => { arquivos = opts.multiplo === false ? [fs[0]] : arquivos.concat(Array.from(fs)); desenhar(); };
    zona.onclick = () => input.click();
    zona.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') input.click(); };
    input.onchange = () => { adicionar(input.files); input.value = ''; };
    zona.ondragover = (e) => { e.preventDefault(); zona.classList.add('arrastando'); };
    zona.ondragleave = () => zona.classList.remove('arrastando');
    zona.ondrop = (e) => { e.preventDefault(); zona.classList.remove('arrastando'); adicionar(e.dataTransfer.files); };
    return { arquivos: () => arquivos, limpar: () => { arquivos = []; desenhar(); } };
  }

  /** Envia FormData com progresso (XHR). Resolve com {ok, status, data}. */
  function enviarComProgresso(url, formData, aoProgresso) {
    return new Promise(async (resolve) => {
      const token = await Hub.tokenCsrf();
      const x = new XMLHttpRequest();
      x.open('POST', url);
      x.setRequestHeader('Accept', 'application/json');
      x.setRequestHeader('X-XSRF-TOKEN', token);
      x.upload.onprogress = (e) => { if (e.lengthComputable && aoProgresso) aoProgresso(e.loaded / e.total); };
      x.onload = () => {
        let data = x.responseText;
        try { data = JSON.parse(x.responseText); } catch (e) { /* texto */ }
        if (Hub.tratarAuth(x.status, data)) return; // sessão encerrada: vai para o login
        resolve({ ok: x.status >= 200 && x.status < 300, status: x.status, data });
      };
      x.onerror = () => resolve({ ok: false, status: 0, data: { title: 'Falha de conexão com o hub' } });
      x.send(formData);
    });
  }

  // ------------------------------------------------------------------ diversos
  function verJson(obj, titulo = 'JSON') {
    const texto = typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2);
    const c = Hub.modal(`<h2>${esc(titulo)}</h2>
      <div class="acoes" style="margin-bottom:8px"><button class="btn btn-sec btn-peq" data-copiar>Copiar</button>
      <button class="btn btn-sec btn-peq" data-baixar>Baixar</button></div>
      <pre class="json">${esc(texto)}</pre>`);
    c.querySelector('[data-copiar]').onclick = () => { navigator.clipboard.writeText(texto); Hub.toast('Copiado.'); };
    c.querySelector('[data-baixar]').onclick = () => Hub.baixarTexto(texto, titulo.replace(/\W+/g, '-').toLowerCase() + '.json');
  }

  function cabecalho(titulo, texto) {
    const m = Hub.atual();
    const icone = m ? `<div class="cab-icone tile-${Hub.corGrupo(m.grupo)}">${Hub.iconeRota(m.rota)}</div>` : '';
    return `<div class="cabecalho-pagina">${icone}<div><h1>${esc(titulo)}</h1>${texto ? `<p>${texto}</p>` : ''}</div></div>`;
  }

  function campo(rotulo, html, ajuda) {
    return `<div class="campo"><label>${rotulo}</label>${html}${ajuda ? `<span class="ajuda">${ajuda}</span>` : ''}</div>`;
  }

  /** Etiqueta de status (classes .st-*). */
  function status(cod, rotulo) {
    return `<span class="etq st-${esc(cod)}">${esc(rotulo || cod)}</span>`;
  }

  /** Lê os campos [name] de um formulário/container em objeto (números convertidos quando data-num). */
  function lerCampos(raiz) {
    const o = {};
    raiz.querySelectorAll('[name]').forEach((i) => {
      if (i.type === 'checkbox') { o[i.name] = i.checked; return; }
      const v = i.value.trim();
      if (v === '') return;
      o[i.name] = i.dataset.num !== undefined ? Hub.numero(v) : v;
    });
    return o;
  }

  /**
   * Acessibilidade: liga cada rótulo de campo (.campo > label) ao seu controle (for/id), para leitores de tela
   * anunciarem o nome do campo e o clique no rótulo focar o campo. Roda também para o que as telas criam depois
   * (itens novos, modais, resultados), sem cada módulo precisar gerar ids.
   */
  let sequenciaRotulo = 0;
  function vincularRotulos(raiz) {
    raiz.querySelectorAll('.campo > label:not([for])').forEach((rotulo) => {
      const controle = rotulo.parentElement.querySelector('input:not([type=hidden]), select, textarea');
      if (!controle) return;
      if (!controle.id) controle.id = `campo-${++sequenciaRotulo}`;
      rotulo.htmlFor = controle.id;
    });
  }
  vincularRotulos(document.body);
  new MutationObserver(() => vincularRotulos(document.body)).observe(document.body, { childList: true, subtree: true });

  Hub.comp = { tabela, jsonTabela, abas, seletorMunicipio, autocompletarNomenclatura, upload, enviarComProgresso, verJson, cabecalho, campo, status, lerCampos };
})();
