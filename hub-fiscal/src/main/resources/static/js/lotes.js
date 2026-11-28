/*
 * Peças comuns dos processamentos em lote (classificação e auditoria): tela de envio com fila e andamento,
 * reabertura do último resultado, contadores por status, quadro "Vendas por NCM" e lista dos arquivos lidos.
 */
'use strict';

(() => {
  const { esc, fmt, comp } = Hub;

  // código do envio para acompanhar o andamento (crypto.randomUUID só existe em HTTPS ou localhost)
  const novaTarefa = () => (window.crypto && crypto.randomUUID ? crypto.randomUUID()
    : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12));
  // último resultado de cada lote nesta aba do navegador: voltar à tela reabre sem reenviar os arquivos
  const chaveUltimo = (url) => 'hub.ultimoLote.' + url;
  const lerUltimo = (url) => { try { return sessionStorage.getItem(chaveUltimo(url)); } catch { return null; } };
  const gravarUltimo = (url, id) => {
    try { if (id) sessionStorage.setItem(chaveUltimo(url), id); else sessionStorage.removeItem(chaveUltimo(url)); } catch { /* sem armazenamento */ }
  };

  /**
   * Tela de upload + processamento reutilizada pela classificação e pela auditoria. Durante o processamento consulta
   * o andamento no servidor (fila, etapa e itens concluídos); o resultado fica guardado no servidor por algumas horas
   * e é reaberto ao voltar à tela.
   */
  Hub.telaLote = (el, cfg) => {
    el.innerHTML = comp.cabecalho(cfg.titulo, cfg.texto) +
      `<div class="card"><div data-up></div>
        <div class="linha u-mt-14">
          ${cfg.comData ? `<div class="campo u-flex-0-0-200"><label>Data de referência (vigência)</label><input type="date" data-data value="${Hub.hoje()}"></div>` : ''}
          <button class="btn" data-proc disabled>Processar</button>
          <span class="sutil" data-st role="status" aria-live="polite"></span>
        </div>
        <div class="progresso" hidden><div></div></div>
      </div><div data-res></div>`;
    const up = comp.upload(el.querySelector('[data-up]'), {
      aceita: cfg.aceita, titulo: 'Arraste os arquivos aqui ou clique para selecionar', dica: cfg.dica,
      aoMudar: (fs) => { el.querySelector('[data-proc]').disabled = !fs.length; },
    });
    const barra = el.querySelector('.progresso');
    const st = el.querySelector('[data-st]');
    const res = el.querySelector('[data-res]');

    const acompanhar = (tarefa) => {
      let ativo = true;
      const consultar = async () => {
        if (!ativo) return;
        const r = await Hub.api.get(`/hub/lote/andamento/${encodeURIComponent(tarefa)}`);
        if (!ativo) return;
        if (r.ok && r.data && r.data.etapa) {
          const a = r.data;
          st.textContent = a.total ? `${a.etapa}: ${fmt.num(a.feitos)} de ${fmt.num(a.total)}` : `${a.etapa}...`;
          if (a.total) barra.firstElementChild.style.width = Math.round((a.feitos / a.total) * 100) + '%';
        }
        setTimeout(consultar, 1000);
      };
      setTimeout(consultar, 500);
      return () => { ativo = false; };
    };

    let enviou = false;
    el.querySelector('[data-proc]').onclick = async (e) => {
      enviou = true;
      const btn = e.target;
      const fd = new FormData();
      up.arquivos().forEach((f) => fd.append('arquivos', f, f.name));
      btn.disabled = true;
      barra.hidden = false;
      barra.firstElementChild.style.width = '0';
      st.textContent = 'Enviando arquivos...';
      res.innerHTML = '';
      const tarefa = novaTarefa();
      const q = new URLSearchParams({ tarefa });
      if (cfg.comData) q.set('data', el.querySelector('[data-data]').value);
      let parar = () => {};
      const r = await comp.enviarComProgresso(`${cfg.url}?${q}`, fd, (p) => {
        barra.firstElementChild.style.width = Math.round(p * 100) + '%';
        if (p >= 1) {
          st.textContent = cfg.msgProcessando;
          barra.firstElementChild.style.width = '0';
          parar = acompanhar(tarefa);
        }
      });
      parar();
      btn.disabled = false;
      barra.hidden = true;
      st.textContent = '';
      if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
      gravarUltimo(cfg.url, r.data.id);
      cfg.mostrar(res, r.data);
      // empresas dos arquivos que não estão no cadastro: pergunta quais adicionar (nada foi gravado ainda)
      Hub.empresas.perguntarCadastro(r.cabecalho && r.cabecalho('X-Hub-Empresas-Encontradas'));
    };

    const ultimo = lerUltimo(cfg.url);
    if (ultimo) {
      (async () => {
        const r = await Hub.api.get(`${cfg.url}/${encodeURIComponent(ultimo)}`);
        if (!r.ok) { gravarUltimo(cfg.url, null); return; } // expirou ou o hub foi reiniciado
        if (enviou) return; // um processamento novo já começou
        res.innerHTML = `<div class="info-caixa u-mb-10">Último processamento desta aba (gerado em ${esc(r.data.geradoEm)}).
          Envie os arquivos de novo para atualizar.</div>`;
        const area = document.createElement('div');
        res.appendChild(area);
        cfg.mostrar(area, r.data);
      })();
    }
  };

  /**
   * Quadro "Vendas por NCM" abaixo do resultado (classificação e auditoria): valor vendido por empresa e NCM/NBS,
   * com o total no fim e o que ficou de fora (entradas, remessas, devoluções...). Mesmos números da aba da planilha.
   */
  Hub.vendasPorNcm = (res, v) => {
    if (!v) return;
    const empresas = v.empresas || [];
    const varias = empresas.length > 1;
    const quadro = Hub.el(`<div class="card vendas-ncm"><div class="card-cab"><h2>Vendas por NCM</h2>
        <span class="sutil">Também na aba "Vendas por NCM" da planilha Excel</span></div>
      <div class="kpis">
        <div class="kpi destaque"><small>Total vendido (líquido)</small><strong>${fmt.moeda(v.valorLiquido)}</strong></div>
        <div class="kpi"><small>Itens vendidos</small><strong>${fmt.num(v.itens)}</strong></div>
        <div class="kpi"><small>NCM/NBS diferentes</small><strong>${fmt.num(new Set(v.linhas.map((l) => l.ncm)).size)}</strong></div>
        <div class="kpi"><small>Descontos</small><strong>${fmt.moeda(v.descontos)}</strong></div>
      </div>
      ${varias ? `<div class="campo u-maxw-520 u-mb-10"><label>Empresa</label><select data-emp-vendas>
        <option value="">Todas (${empresas.length})</option>${empresas.map((e) => `<option value="${esc(e.empresaCnpj)}">${esc(e.empresaNome || '')} – ${esc(fmt.cnpj(e.empresaCnpj))}</option>`).join('')}
        </select></div>` : ''}
      <div data-tab-vendas></div>
      <div class="vendas-total" data-total-vendas></div>
      <details class="u-mt-10"><summary>O que entra no total</summary><ul>${(v.observacoes || []).map((o) => `<li>${esc(o)}</li>`).join('')}</ul></details>
    </div>`);
    res.appendChild(quadro);
    if (!v.linhas.length) {
      quadro.querySelector('[data-tab-vendas]').innerHTML = '<p class="sutil">Nenhuma venda nos arquivos (só entradas, remessas ou devoluções).</p>';
      return;
    }
    const tab = comp.tabela(quadro.querySelector('[data-tab-vendas]'), {
      placeholder: 'Buscar NCM ou descrição...',
      porPagina: 50,
      colunas: [
        ...(varias ? [{ titulo: 'Empresa', campo: 'empresaNome' }] : []),
        { titulo: 'NCM/NBS', campo: 'ncm', render: (l) => (l.ncm ? `<code>${esc(l.ncm)}</code>` : '<span class="sutil">sem NCM</span>') },
        { titulo: 'Descrição', campo: 'descricao', render: (l) => `<span class="desc-curta" title="${esc(l.descricao || '')}">${esc(l.descricao || '')}</span>` },
        { titulo: 'Itens', campo: 'itens', tipo: 'num' },
        { titulo: 'Quantidade', campo: 'quantidade', tipo: 'num' },
        { titulo: 'Unid.', campo: 'unidade' },
        { titulo: 'Valor dos produtos', campo: 'valorBruto', tipo: 'moeda' },
        { titulo: 'Descontos', campo: 'descontos', tipo: 'moeda' },
        { titulo: 'Valor vendido', campo: 'valorLiquido', tipo: 'moeda' },
        { titulo: '% da empresa', campo: 'percentual', tipo: 'pct' },
      ],
      linhas: v.linhas,
    });
    const total = quadro.querySelector('[data-total-vendas]');
    const somar = () => {
      const sel = quadro.querySelector('[data-emp-vendas]');
      const cnpj = sel ? sel.value : '';
      const ls = tab.linhas(); // linhas exibidas (empresa escolhida e busca)
      const soma = (k) => ls.reduce((s, l) => s + Number(l[k] || 0), 0);
      const filtrado = ls.length !== v.linhas.length;
      total.innerHTML = `<span><strong>${filtrado ? 'Total exibido' : 'Total geral'}</strong> · ${fmt.num(ls.length)} NCM/NBS · ${fmt.num(soma('itens'))} itens</span>
        <span>Produtos ${fmt.moeda(soma('valorBruto'))}</span><span>Descontos ${fmt.moeda(soma('descontos'))}</span>
        <span class="vendas-total-valor">Vendido ${fmt.moeda(soma('valorLiquido'))}</span>
        ${cnpj ? '<span class="sutil">(empresa selecionada)</span>' : ''}`;
    };
    const sel = quadro.querySelector('[data-emp-vendas]');
    if (sel) sel.onchange = () => { tab.filtrar(sel.value ? (l) => String(l.empresaCnpj) === sel.value : null); somar(); };
    quadro.querySelector('[data-tab-vendas] input[type=search]').addEventListener('input', () => setTimeout(somar, 250));
    somar();
  };

  /** Contadores por status (clicáveis para filtrar a tabela). */
  Hub.contadoresStatus = (alvo, contagem, rotulos, tabela, campo = 'status') => {
    alvo.innerHTML = `<div class="contadores">${Object.entries(contagem).map(([k, v]) =>
      `<button class="contador st-${k}" data-st="${k}"><strong>${v}</strong><span>${esc(rotulos[k] || k)}</span></button>`).join('')}</div>`;
    let ativo = null;
    alvo.querySelectorAll('[data-st]').forEach((b) => {
      b.onclick = () => {
        ativo = ativo === b.dataset.st ? null : b.dataset.st;
        alvo.querySelectorAll('[data-st]').forEach((x) => x.classList.toggle('sel', x.dataset.st === ativo));
        tabela.filtrar(ativo ? (l) => l[campo] === ativo : null);
      };
    });
  };

  function arquivosHtml(arquivos, avisos) {
    const lista = Object.entries(arquivos || {});
    return `<details><summary>Arquivos lidos (${lista.length})</summary><ul>${lista.map(([k, v]) => `<li><code>${esc(k)}</code> – ${esc(v)}</li>`).join('')}</ul></details>
      ${(avisos || []).length ? `<div class="aviso"><ul>${avisos.map((a) => `<li>${esc(a)}</li>`).join('')}</ul></div>` : ''}`;
  }
  Hub.arquivosHtml = arquivosHtml;
})();
