/* Atividades do Simples Nacional (cAtivSN), anexos e fator r. */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  /** Carrega as atividades do Simples vigentes (cache por data/documento). */
  Hub.atividadesSimples = async (data, tpDoc) => {
    const q = new URLSearchParams({ data });
    if (tpDoc) q.set('tpDoc', tpDoc);
    const r = await Hub.getCache(api.sn('/calculadora/simples-nacional/atividades?' + q));
    return r.ok ? r.data : [];
  };

  Hub.registrar({
    rota: 'atividades-simples',
    titulo: 'Atividades do Simples',
    grupo: 'Consultas e tabelas',
    icone: '🏷️',
    ordem: 4,
    etiquetas: ['Simples'],
    descricao: 'Catálogo de atividades do Simples Nacional: anexo, sujeição ao fator r e documentos admitidos.',
    render(el) {
      el.innerHTML = comp.cabecalho('Atividades do Simples Nacional',
        'Códigos de atividade usados no cálculo do IBS/CBS no Simples (no leiaute da nota, campo cAtivSN). Indicam o anexo aplicável, se a atividade depende do fator r e em quais documentos pode ser usada.') +
        `<div class="card"><div class="linha">
          <div class="campo u-flex-0-0-180"><label>Data</label><input type="date" name="data" value="${Hub.hoje() < '2027-01-01' ? '2027-01-01' : Hub.hoje()}"></div>
          <div class="campo u-flex-0-0-160"><label>Anexos</label><input name="anexos" placeholder="Ex.: III,V"></div>
          <div class="campo u-flex-0-0-180"><label>Documento</label><select name="tpDoc"><option value="">Todos</option>
            <option value="55">NF-e (55)</option><option value="65">NFC-e (65)</option><option value="91">NFS-e (91)</option>
            <option value="57">CT-e (57)</option><option value="67">CT-e OS (67)</option><option value="63">BP-e (63)</option><option value="62">NFCom (62)</option></select></div>
          <div class="campo u-flex-0-0-160"><label>cClassTrib</label><input name="cClassTrib" placeholder="Ex.: 000001"></div>
          <button class="btn" data-consultar>Consultar</button>
        </div></div><div class="card" data-res></div>`;
      const res = el.querySelector('[data-res]');
      const consultar = async () => {
        const f = comp.lerCampos(el.querySelector('.card'));
        const q = new URLSearchParams(f);
        Hub.carregando(res);
        const r = await api.get(api.sn('/calculadora/simples-nacional/atividades?' + q));
        if (!r.ok) { res.innerHTML = Hub.problema(r); return; }
        comp.tabela(res, {
          colunas: [
            { titulo: 'Código', campo: 'codigo', render: (l) => `<code><strong>${esc(l.codigo)}</strong></code>` },
            { titulo: 'Descrição', campo: 'descricao', largura: 70 },
            { titulo: 'Anexo', campo: 'rotuloAnexo' },
            { titulo: 'Anexo sem fator r', campo: 'anexoSemFatorR' },
            { titulo: 'Anexo com fator r', campo: 'anexoComFatorR' },
            { titulo: 'Fator r', exportar: (l) => (l.sujeitaFatorR ? 'Sim' : 'Não') },
            { titulo: 'Documentos', exportar: (l) => (l.tpDocsAdmissiveis || []).map((c) => Hub.rotulos.documento(c)).join(', ') },
            { titulo: 'Disponível', exportar: (l) => (l.disponivel ? 'Sim' : 'Não – faltam: ' + (l.anexosFaltantes || []).join(', ')) },
          ],
          linhas: r.data,
          exportar: { arquivo: 'atividades-simples', titulo: 'Atividades do Simples Nacional' },
        });
      };
      el.querySelector('[data-consultar]').onclick = consultar;
      consultar();
    },
  });
})();
