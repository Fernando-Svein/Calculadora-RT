/*
  Cenário de simulação da CBS: seletor no topo ("CBS oficial" × alíquota do cenário) e aplicação nos resultados.
  A escolha fica no navegador. A reescala é feita pelo servidor (/hub/cenario-cbs), sobre a resposta oficial.
  Configuração (alíquota e nome): hub.cenario-cbs no application.yml.
*/
'use strict';

(() => {
  const CHAVE = 'hub.cenarioCbs';
  let config = { disponivel: false };

  const escolha = {
    ler() { try { return localStorage.getItem(CHAVE); } catch (e) { return null; } },
    gravar(v) { try { localStorage.setItem(CHAVE, v); } catch (e) { /* navegador sem armazenamento */ } },
  };

  /** O usuário escolheu o cenário (e ele está configurado no servidor)? */
  function ativo() {
    return !!config.disponivel && escolha.ler() === 'cenario';
  }

  /** Alíquota do cenário no formato dos campos de tela (ex.: "11,3"). */
  function aliquotaTexto() {
    return String(config.aliquota).replace('.', ',');
  }

  function montarSeletor() {
    const alvo = document.getElementById('cenarioCbs');
    if (!alvo || !config.disponivel) return;
    alvo.hidden = false;
    alvo.innerHTML = `<label title="Alíquota da CBS usada nas simulações (Simulador Regime Regular, Pedágio e Comparador)">
        <span class="cenario-rotulo">CBS</span>
        <select aria-label="Alíquota da CBS nas simulações">
          <option value="oficial">Oficial (calculadora da Receita)</option>
          <option value="cenario">${Hub.esc(config.rotulo || 'Cenário')} – ${Hub.esc(Hub.fmt.pct(config.aliquota))}</option>
        </select></label>`;
    const sel = alvo.querySelector('select');
    const marcar = () => alvo.classList.toggle('ativo', ativo());
    sel.value = ativo() ? 'cenario' : 'oficial';
    marcar();
    sel.onchange = () => {
      escolha.gravar(sel.value);
      marcar();
      window.dispatchEvent(new CustomEvent('hub:cenario-cbs', { detail: { ativo: ativo() } }));
      Hub.toast(ativo()
        ? `Simulações com CBS de ${Hub.fmt.pct(config.aliquota)} (${config.rotulo}). Calcule novamente para aplicar.`
        : 'Simulações com a alíquota oficial da CBS. Calcule novamente para aplicar.');
    };
  }

  /** Promessa resolvida quando a configuração do servidor foi lida. */
  const pronto = fetch('/hub/cenario-cbs', { headers: { Accept: 'application/json' } })
    .then((r) => (r.ok ? r.json() : config))
    .then((c) => { config = c; montarSeletor(); })
    .catch(() => { /* sem cenário: o seletor fica oculto */ });

  /**
   * Reescala a CBS de uma resposta oficial. tipo: 'regime-geral' | 'pedagio'.
   * data (AAAA-MM-DD): data do fato gerador, obrigatória no regime-geral (alíquota de referência oficial).
   * Devolve a resposta da chamada ({ ok, data, ... }), como Hub.api.
   */
  function aplicar(tipo, dadosOficiais, data) {
    return Hub.api.post(`/hub/cenario-cbs/${tipo}${data ? '?data=' + encodeURIComponent(data) : ''}`, dadosOficiais);
  }

  /** Aviso a mostrar junto do resultado quando ele foi reescalado (vazio se não foi). */
  function aviso(dados) {
    const c = dados && dados.cenarioCbs;
    if (!c) return '';
    const oficial = c.aliquotaOficial !== undefined ? ` em vez da alíquota oficial de ${Hub.fmt.pct(c.aliquotaOficial)}` : '';
    const extras = (c.avisos || []).length ? `<ul>${c.avisos.map((a) => `<li>${Hub.esc(a)}</li>`).join('')}</ul>` : '';
    return `<div class="aviso"><strong>${Hub.esc(c.rotulo)}: CBS calculada a ${Hub.fmt.pct(c.aliquota)}${oficial}.</strong>
      Simulação: não use estes valores em documentos fiscais. Base, IBS, classificação e validações são os da calculadora oficial;
      o hub recalculou só a CBS (alíquota × (1 − redução) × base).${extras}</div>`;
  }

  /** Preenche um campo de alíquota nominal da CBS (2027 em diante) conforme o cenário, e acompanha a troca. */
  function ligarCampoNominal(input, padrao) {
    const ajustar = () => {
      // a tela foi trocada: o campo saiu do documento e o ouvinte deixa de existir (evita acumular um por visita)
      if (!input.isConnected) { window.removeEventListener('hub:cenario-cbs', ajustar); return; }
      input.value = ativo() ? aliquotaTexto() : padrao;
    };
    pronto.then(ajustar);
    window.addEventListener('hub:cenario-cbs', ajustar);
  }

  Hub.cenarioCbs = { pronto, ativo, aplicar, aviso, ligarCampoNominal, rotulo: () => config.rotulo, aliquota: () => config.aliquota };
})();
