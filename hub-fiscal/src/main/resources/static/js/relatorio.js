/*
 * Relatórios de uma simulação individual (PDF e Excel), usados pelos simuladores depois de calcular.
 * O conteúdo é o que a tela já calculou: empresa da simulação, resumo, tabela e observações.
 * Os processamentos em lote continuam com a planilha própria deles.
 */
'use strict';

(() => {
  /** Botões "Relatório PDF" e "Relatório Excel" (ligue-os com Hub.relatorio.ligar). */
  function botoes() {
    return `<button class="btn btn-sec btn-peq" type="button" data-rel-pdf>${Hub.icone('pdf')} Relatório PDF</button>
      <button class="btn btn-sec btn-peq" type="button" data-rel-xlsx>${Hub.icone('planilha')} Relatório Excel</button>`;
  }

  /**
   * montar() (pode ser async) devolve:
   * { titulo, arquivo, empresa: [linhas], resumo: [[rótulo, valor] | [subtítulo]], colunas: [{titulo, tipo, largura}],
   *   linhas: [[...]], observacoes: [textos] }
   */
  function ligar(raiz, montar) {
    const pdf = raiz.querySelector('[data-rel-pdf]');
    const xlsx = raiz.querySelector('[data-rel-xlsx]');
    if (pdf) pdf.onclick = () => gerar('pdf', montar, pdf);
    if (xlsx) xlsx.onclick = () => gerar('xlsx', montar, xlsx);
  }

  const texto = (v) => (v === null || v === undefined ? '' : String(v));

  async function gerar(formato, montar, botao) {
    botao.disabled = true;
    try {
      const d = await montar();
      const empresa = d.empresa || [];
      const resumo = (d.resumo || []).map((p) => p.map(texto));
      const observacoes = (d.observacoes || []).filter(Boolean).map(texto);
      if (formato === 'pdf') {
        const r = await Hub.api.req('POST', '/hub/relatorio/pdf', {
          titulo: d.titulo, arquivo: d.arquivo, empresa, resumo, colunas: d.colunas, linhas: d.linhas, observacoes,
        }, { blob: true });
        if (!r.ok) { Hub.modal(Hub.problema(r, 'Não foi possível gerar o PDF')); return; }
        const cd = r.headers.get('Content-Disposition') || '';
        const m = cd.match(/filename\*=UTF-8''([^;]+)/i) || cd.match(/filename="?([^";]+)"?/i);
        Hub.baixarBlob(r.blob, m ? decodeURIComponent(m[1]) : `${d.arquivo || 'relatorio'}.pdf`);
        return;
      }
      // Excel: aba "Resumo" (empresa, resumo e observações) e aba "Dados" (a tabela)
      const pares = [['Empresa da simulação']];
      if (empresa.length) {
        pares.push(['Empresa', empresa[0]]);
        empresa.slice(1).forEach((l, i) => pares.push([i === 0 ? 'Identificação' : '', l]));
      } else {
        pares.push(['Empresa', 'Nenhuma: simulação com valores genéricos']);
      }
      if (resumo.length) pares.push([''], ['Resumo da simulação'], ...resumo);
      if (observacoes.length) pares.push([''], ['Observações'], ...observacoes.map((o) => ['', o]));
      await Hub.exportarExcel({ titulo: d.titulo, arquivo: d.arquivo, resumo: pares, colunas: d.colunas, linhas: d.linhas });
    } catch (e) {
      console.error(e);
      Hub.toast('Não foi possível montar o relatório: ' + (e.message || e), 'erro');
    } finally {
      botao.disabled = false;
    }
  }

  /** Converte quadros (KPIs) em tabela de duas colunas, para relatórios sem tabela própria. */
  function tabelaDePares(pares, tituloValor = 'Valor', tipo = 'moeda') {
    return {
      colunas: [{ titulo: 'Item', tipo: 'texto', largura: 40 }, { titulo: tituloValor, tipo, largura: 18 }],
      linhas: pares.map(([k, v]) => [k, v === undefined ? null : v]),
    };
  }

  Hub.relatorio = { botoes, ligar, tabelaDePares };
})();
