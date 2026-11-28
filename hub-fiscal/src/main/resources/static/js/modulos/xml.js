/* Validação de XML (grupo IBS/CBS ou nota completa) contra os XSD oficiais. */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  Hub.registrar({
    rota: 'xml-validar',
    titulo: 'Validar XML no leiaute',
    grupo: 'Documentos XML',
    icone: '✅',
    ordem: 1,
    etiquetas: ['Regime Regular', 'Simples'],
    descricao: 'Valida o grupo de tributação IBS/CBS ou a nota completa contra os schemas (XSD) das notas técnicas vigentes.',
    render(el) {
      el.innerHTML = comp.cabecalho('Validar XML no leiaute',
        'Usa os schemas oficiais embutidos na calculadora. "Grupo" valida só os grupos de tributação (IBSCBS/IS); "Nota" valida o documento completo.') +
        `<div class="card"><div class="grade g4">
          ${comp.campo('Tipo de documento', '<select name="tipo"><option value="nfe">Carregando...</option></select>')}
          ${comp.campo('O que validar', '<select name="subtipo"><option value="grupo">Grupo de tributação</option><option value="nota">Nota completa</option></select>')}
        </div>
        <div class="u-mt-12" data-up></div>
        <div class="campo u-mt-12"><label>…ou cole o XML</label><textarea name="xml" rows="12" placeholder="&lt;infNFe ...&gt;"></textarea></div>
        <div class="acoes u-mt-12"><button class="btn" data-ok>Validar</button></div></div>
        <div data-res></div>`;
      const selTipo = el.querySelector('[name=tipo]');
      (async () => {
        const r = await Hub.getCache(api.rg('/calculadora/xml/validate'));
        if (r.ok) selTipo.innerHTML = r.data.map((t) => `<option value="${esc(t.mnemonico)}">${esc(t.nome)} – NT ${esc(t.versaoNotaTecnica)}</option>`).join('');
      })();
      const txt = el.querySelector('[name=xml]');
      comp.upload(el.querySelector('[data-up]'), {
        multiplo: false, aceita: '.xml,.XML', titulo: 'Arraste o XML aqui ou clique para selecionar',
        aoMudar: async (fs) => { if (fs[0]) txt.value = await fs[0].text(); },
      });
      el.querySelector('[data-ok]').onclick = async () => {
        const res = el.querySelector('[data-res]');
        if (!txt.value.trim()) { Hub.toast('Informe o XML.', 'erro'); return; }
        Hub.carregando(res, 'Validando...');
        const q = new URLSearchParams({ tipo: selTipo.value, subtipo: el.querySelector('[name=subtipo]').value });
        const r = await api.post(api.rg('/calculadora/xml/validate?' + q), txt.value, { contentType: 'application/xml' });
        res.innerHTML = r.ok && String(r.texto).trim() === 'true'
          ? '<div class="card"><div class="ok-caixa"><strong>XML válido</strong> no leiaute selecionado.</div></div>'
          : `<div class="card">${Hub.problema(r, 'XML inválido',
            'o XML não segue o leiaute (XSD) selecionado – campo obrigatório ausente, valor em formato errado (ex.: texto em campo numérico, ' +
            'casas decimais a mais) ou ordem de tags diferente da prevista. A calculadora oficial não informa qual campo falhou; ' +
            'confira o tipo/subtipo escolhido e compare com um XML gerado pelo Simulador Regime Regular.')}</div>`;
      };
    },
  });
})();
