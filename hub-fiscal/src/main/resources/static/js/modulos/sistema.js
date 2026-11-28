/* Sistema: situação das calculadoras e guia rápido. */
'use strict';

(() => {
  const { esc, fmt, api, comp } = Hub;

  Hub.registrar({
    rota: 'status',
    titulo: 'Situação do sistema',
    grupo: 'Sistema',
    icone: '🩺',
    ordem: 1,
    descricao: 'Verifica se as calculadoras oficiais estão no ar, a versão da aplicação e da base de dados oficial.',
    async render(el) {
      el.innerHTML = comp.cabecalho('Situação do sistema') + '<div data-res></div>';
      const res = el.querySelector('[data-res]');
      Hub.carregando(res);
      const r = await api.get('/hub/status');
      if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
      const s = r.data;
      const ROTULOS = {
        ambiente: 'Ambiente', versaoApp: 'Versão da aplicação', descricaoVersaoApp: 'Versão (descrição)', versaoDb: 'Versão da base',
        dataVersaoDb: 'Data da base', descricaoVersaoDb: 'Conteúdo da base', arquivoDb: 'Arquivo da base',
      };
      const bloco = (titulo, x, cor) => {
        const det = x.online && x.detalhes && typeof x.detalhes === 'object' ? x.detalhes : null;
        return `<div class="card servico"><div class="servico-cab"><span class="tile tile-${cor}">${Hub.icone('calculadora')}</span>
            <div class="u-flex-1 u-minw-0"><h3>${titulo}</h3><span class="sutil">${esc(x.url)}</span></div>
            ${x.online ? '<span class="etq st-OK">Em funcionamento</span>' : '<span class="etq st-DIVERGENTE">Fora do ar</span>'}</div>
          ${det ? `<dl class="dl">${Object.entries(det).map(([k, v]) => `<dt>${esc(ROTULOS[k] || k)}</dt><dd>${esc(/data/i.test(k) ? fmt.data(v) : v)}</dd>`).join('')}</dl>`
            : `<div class="erro-caixa u-m-0">${esc(typeof x.detalhes === 'string' ? x.detalhes : JSON.stringify(x.detalhes))}
              <br>${s.servidorLinux ? 'No servidor, rode <code>sudo bash /opt/hub-fiscal/scripts/linux/hub.sh iniciar</code>.' : 'Inicie as calculadoras com o arquivo <code>iniciar-hub.bat</code> na pasta do projeto.'}</div>`}</div>`;
      };
      const b = s.bancoOficial || {};
      res.innerHTML = `<div class="grade g2">${bloco('Calculadora – Regime Normal (Regime Regular)', s.regimeGeral, 'indigo')}${bloco('Calculadora – Simples Nacional', s.simplesNacional, 'verde')}</div>
        <div class="card servico u-mt-18"><div class="servico-cab"><span class="tile tile-azul">${Hub.icone('banco')}</span>
          <div class="u-flex-1"><h3>Banco de dados oficial</h3><span class="sutil">SQLite somente leitura</span></div>
          ${b.versao ? `<span class="etq etq-azul">${esc(b.versao)}</span>` : ''}</div>
          ${b.erro ? `<div class="erro-caixa">${esc(b.erro)}</div>` : ''}
          <dl class="dl"><dt>Versão</dt><dd>${esc(b.versao || '–')}</dd><dt>Data</dt><dd>${fmt.data(b.data)}</dd>
          <dt>Conteúdo</dt><dd>${esc(b.descricao || '–')}</dd><dt>Arquivo</dt><dd><code>${esc(b.arquivo || '–')}</code></dd>
          <dt>Data do servidor</dt><dd>${fmt.data(s.dataServidor)}</dd></dl>
        </div>
        ${blocoAtualizacao(s.atualizacao, s.servidorLinux, s.base || {})}`;
      ligarAtualizarAgora(res, s, () => this.render(el));
    },
  });

  const hora = (v) => (v ? new Date(v).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '');

  /**
   * Botão "Atualizar agora" (só administradores): pede ao servidor a mesma verificação do agendamento diário.
   * Enquanto houver pedido ou verificação em andamento, consulta a situação a cada 10 s e redesenha a tela no fim.
   */
  function ligarAtualizarAgora(res, s, recarregar) {
    const area = res.querySelector('[data-atualizar-agora]');
    if (!area) return;
    if (!(s.atualizacaoManual && s.atualizacaoManual.disponivel)) { area.remove(); return; }
    const linux = s.servidorLinux;
    const desenhar = (m, foraDoAr) => {
      if (foraDoAr) {
        area.innerHTML = `<div class="aviso u-m-0">${Hub.icone('relogio')} O hub está reiniciando para aplicar a versão nova das calculadoras.
          Esta tela volta sozinha quando ele estiver no ar (se pedir login, entre de novo).</div>`;
        return;
      }
      if (m.emAndamentoDesde) {
        area.innerHTML = `<div class="info-caixa u-m-0"><strong>Atualização em andamento</strong> desde ${esc(hora(m.emAndamentoDesde))}:
          consultando o GitHub da Receita. Se houver versão nova, as calculadoras e o hub ficam fora do ar por alguns minutos (base nova e compilação).</div>`;
        return;
      }
      if (m.pedidoEm) {
        const minutos = (Date.now() - new Date(m.pedidoEm).getTime()) / 60000;
        area.innerHTML = `<div class="info-caixa u-m-0"><strong>Atualização solicitada</strong> às ${esc(hora(m.pedidoEm))}: aguardando o servidor iniciar.
          ${minutos > 2 ? `<br><span class="sutil">Está demorando? ${linux
            ? 'No servidor, rode uma vez <code>sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub</code> (instala o gatilho do botão).'
            : 'Veja <code>logs\\atualizacao-manual.log</code> no servidor.'}</span>` : ''}</div>`;
        return;
      }
      area.innerHTML = `<div class="acoes u-m-0"><button class="btn" type="button" data-atualizar>${Hub.icone('nuvem')} Atualizar agora</button>
        <span class="sutil">Consulta o GitHub da Receita agora, sem esperar a verificação diária. Sem versão nova, nada muda.</span></div>`;
      area.querySelector('[data-atualizar]').onclick = async (e) => {
        if (!confirm('Consultar agora o GitHub da Receita e atualizar as calculadoras?\n\n' +
          'Se houver versão nova, as calculadoras e o hub ficam fora do ar por alguns minutos enquanto a base é recriada ' +
          'e os serviços são recompilados. Avise a equipe antes. Se não houver novidade, nada muda.')) return;
        e.currentTarget.disabled = true;
        const r = await api.post('/admin/sistema/atualizar-calculadoras');
        if (!r.ok) { Hub.modal(Hub.problema(r, 'Atualização não iniciada')); desenhar({}); return; }
        Hub.toast('Atualização solicitada.');
        acompanhar(r.data.pedidoEm);
      };
    };

    let ultimaAntes = s.atualizacao && s.atualizacao.data;
    function acompanhar(pedidoEm) {
      desenhar({ pedidoEm });
      const iv = setInterval(async () => {
        if (!area.isConnected) { clearInterval(iv); return; } // saiu da tela
        const r = await api.get('/hub/status');
        if (!r.ok) { desenhar({}, r.status === 0 || r.status >= 500); return; }
        const m = r.data.atualizacaoManual || {};
        const terminou = !m.pedidoEm && !m.emAndamentoDesde;
        const novoResultado = r.data.atualizacao && r.data.atualizacao.data && r.data.atualizacao.data !== ultimaAntes;
        if (terminou && (novoResultado || !pedidoEm)) {
          clearInterval(iv);
          Hub.toast(r.data.atualizacao && r.data.atualizacao.resultado === 'falha' ? 'A atualização terminou com falha: veja o resultado.' : 'Verificação concluída.',
            r.data.atualizacao && r.data.atualizacao.resultado === 'falha' ? 'erro' : undefined);
          recarregar();
          return;
        }
        if (terminou) { clearInterval(iv); desenhar({}); return; } // pedido expirou sem ser atendido
        desenhar(m);
      }, 10000);
    }

    const m = s.atualizacaoManual || {};
    if (m.pedidoEm || m.emAndamentoDesde) { ultimaAntes = s.atualizacao && s.atualizacao.data; acompanhar(m.pedidoEm || m.emAndamentoDesde); desenhar(m); } else desenhar(m);
  }

  function blocoAtualizacao(a, linux, base) {
    const dh = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '–');
    const datas = `<dl class="dl"><dt>Base atualizada em</dt><dd>${esc(dh(base.ultimaAtualizacao))}</dd>
        <dt>Próxima atualização</dt><dd>${base.proximaAtualizacao ? esc(dh(base.proximaAtualizacao)) + ` <span class="sutil">(todo dia às ${esc(base.horario)})</span>`
          : '<span class="etq etq-amarelo">Não agendada</span>'}</dd></dl>`;
    const dica = linux
      ? `No servidor Linux a verificação já está agendada para toda madrugada (03:00): só atualiza quando a Receita publica versão nova.
        Para verificar na hora, use o botão <strong>Atualizar agora</strong> (administradores) ou rode
        <code>sudo bash /opt/hub-fiscal/scripts/linux/hub.sh verificar</code>; a situação dos serviços sai com <code>hub.sh status</code>.`
      : `No servidor, execute uma vez <code>ativar-atualizacao-automatica.bat</code> como administrador:
        o hub passa a ligar sozinho com o servidor e verifica toda madrugada se a Receita publicou versão nova (só atualiza quando há novidade).
        Para verificar na hora, use o botão <strong>Atualizar agora</strong> (administradores) ou <code>atualizar-calculadoras.bat</code>.`;
    const ROT = {
      'sem-novidade': ['st-OK', 'Em dia'],
      atualizado: ['st-OK', 'Atualizado'],
      falha: ['st-DIVERGENTE', 'Falhou'],
    };
    const [cls, txt] = a ? (ROT[a.resultado] || ['st-REVISAR', a.resultado]) : ['st-REVISAR', 'Não configurada'];
    const quando = a && a.data ? new Date(a.data).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '–';
    const repos = a && Array.isArray(a.repos) ? a.repos : [];
    return `<div class="card servico u-mt-18"><div class="servico-cab"><span class="tile tile-ambar">${Hub.icone('nuvem')}</span>
        <div class="u-flex-1"><h3>Atualização automática</h3><span class="sutil">GitHub da Receita Federal</span></div>
        <span class="etq ${cls}">${esc(txt)}</span></div>
      ${datas}
      ${a ? `<dl class="dl"><dt>Última verificação</dt><dd>${esc(quando)}</dd><dt>Resultado</dt><dd>${esc(a.mensagem || '–')}</dd>
        ${repos.map((r) => `<dt>${esc(r.repo)}</dt><dd><code>${esc(String(r.novo || '').slice(0, 10))}</code>${r.commitsNovos ? ` · ${esc(r.commitsNovos)} commit(s) novo(s)` : ''}</dd>`).join('')}</dl>`
        : `<p class="sutil u-m-0-0-12">Nenhuma verificação automática registrada neste servidor.</p>`}
      ${Hub.usuario && Hub.usuario.perfil === 'ADMIN' ? '<div class="u-m-4-0-14" data-atualizar-agora></div>' : ''}
      <div class="info-caixa u-mb-0">${dica}</div></div>`;
  }

  Hub.registrar({
    rota: 'ajuda',
    titulo: 'Guia rápido',
    grupo: 'Sistema',
    icone: '❓',
    ordem: 2,
    descricao: 'Como usar cada ferramenta, conceitos (CST, cClassTrib, IBS, CBS, IS) e limites das simulações.',
    render(el) {
      el.innerHTML = comp.cabecalho('Guia rápido') + `
        <div class="card"><h3>Conceitos</h3><dl class="dl">
          <dt>CBS</dt><dd>Contribuição sobre Bens e Serviços (federal), substitui PIS/COFINS.</dd>
          <dt>IBS</dt><dd>Imposto sobre Bens e Serviços (estadual + municipal), substitui ICMS e ISS ao longo da transição.</dd>
          <dt>IS</dt><dd>Imposto Seletivo, sobre bens e serviços prejudiciais à saúde ou ao meio ambiente.</dd>
          <dt>CST</dt><dd>Código de situação tributária do IBS/CBS (3 dígitos): 000 integral, 200 redução/alíquota zero, 410 imunidade/não incidência, 550 suspensão, 620 monofásico...</dd>
          <dt>cClassTrib</dt><dd>Classificação tributária (6 dígitos; os 3 primeiros são o CST). Define o tratamento exato e a fundamentação legal.</dd>
          <dt>Anexo</dt><dd>Listas da LC 214/2025 que vinculam benefícios a NCM/NBS (ex.: Anexo I – cesta básica). O hub usa a mesma regra da calculadora oficial para dizer se um NCM está coberto.</dd>
        </dl></div>
        <div class="card"><h3>Roteiros comuns</h3>
          <ol>
            <li><strong>Saneamento de cadastro:</strong> Processamento em lote → Classificar cadastro. Envie o SPED do cliente (e, se tiver, os XMLs de saída). Baixe a planilha, trate as linhas "Divergente" e "Revisar" e registre a decisão nas colunas de preenchimento.</li>
            <li><strong>Conferência das notas de 2026:</strong> Auditar XML. Envie um ZIP com os XMLs do mês. Itens "Divergente" e "Erro de classificação" precisam de correção no emissor.</li>
            <li><strong>Dúvida pontual de produto:</strong> Classificar NCM / NBS → veja a sugestão, o anexo e a fundamentação. Use "Simular" para ver os valores.</li>
            <li><strong>Cliente do Simples:</strong> Simulador Simples Nacional para os valores do período; Comparador para avaliar a opção pelo regime regular do IBS/CBS.</li>
            <li><strong>Emissão:</strong> no Simulador Regime Regular, gere o XML do grupo IBSCBS e valide no leiaute.</li>
          </ol></div>
        <div class="card"><h3>Limites importantes</h3><ul>
          <li>As sugestões de cClassTrib consideram NCM/NBS, documento e CFOP. Requisitos que não aparecem nos arquivos (destinatário, finalidade, regime especial) precisam de análise do responsável.</li>
          <li>De 2027 em diante a base oficial não tem alíquotas de referência; os simuladores usam as alíquotas informadas e marcam o cálculo como simulado.</li>
          <li>O SPED não traz NBS: serviços vindos só do SPED aparecem como "Sem NCM/NBS". Use os XMLs de NFS-e.</li>
          <li>Os cálculos são das calculadoras oficiais (Receita/Serpro) em modo offline. Mantenha-as atualizadas.</li>
        </ul></div>`;
    },
  });
})();
