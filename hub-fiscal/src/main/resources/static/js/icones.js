/* Hub Fiscal – ícones vetoriais (traço), no estilo de linha fina. */
'use strict';

(() => {
  const P = {
    inicio: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
    busca: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    livro: '<path d="M2 4h7a3 3 0 0 1 3 3v14a2 2 0 0 0-2-2H2z"/><path d="M22 4h-7a3 3 0 0 0-3 3v14a2 2 0 0 1 2-2h8z"/>',
    percentual: '<path d="M19 5 5 19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
    etiqueta: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
    recibo: '<path d="M5 2h14v20l-3-2-2 2-2-2-2 2-2-2-3 2z"/><path d="M9 7h6M9 11h6M9 15h4"/>',
    banco: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    alerta: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    calculadora: '<rect x="4" y="2" width="16" height="20" rx="2"/><path d="M8 6h8M8 11h.01M12 11h.01M16 11h.01M8 15h.01M12 15h.01M16 15h.01M8 18.5h.01M12 18.5h4"/>',
    camadas: '<path d="m12 2 10 5-10 5L2 7z"/><path d="m2 12 10 5 10-5"/><path d="m2 17 10 5 10-5"/>',
    estrada: '<path d="M4 21 8 3M20 21 16 3M12 4v3M12 11v3M12 18v3"/>',
    balanca: '<path d="M12 3v18M7 21h10M4 7h16"/><path d="m7 7-3 7a3 3 0 0 0 6 0zM17 7l-3 7a3 3 0 0 0 6 0z"/>',
    importar: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M12 18v-6M9 15l3-3 3 3"/>',
    auditar: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h5"/><path d="M14 2v6h6v3"/><circle cx="16.5" cy="17.5" r="3"/><path d="m21 22-2.3-2.3"/>',
    validar: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="m9 15 2 2 4-4"/>',
    pulso: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
    ajuda: '<circle cx="12" cy="12" r="10"/><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    baixar: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
    mais: '<path d="M12 5v14M5 12h14"/>',
    fechar: '<path d="M18 6 6 18M6 6l12 12"/>',
    seta: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
    ok: '<circle cx="12" cy="12" r="10"/><path d="m8 12 3 3 5-6"/>',
    nuvem: '<path d="M12 13v8M8 17l4-4 4 4"/><path d="M20 16.6A5 5 0 0 0 18 7h-1.3A8 8 0 1 0 4 15.3"/>',
    escudo: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    raio: '<path d="M13 2 3 14h9l-1 8 10-12h-9z"/>',
    chevron: '<path d="m9 6 6 6-6 6"/>',
    painel: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M9 3v18"/><path d="m16 10-2 2 2 2"/>',
    relogio: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  };

  /** Ícone de cada grupo. */
  const ICONES_GRUPO = {
    'Consultas e tabelas': 'livro',
    'Calculadoras – Regime Regular': 'calculadora',
    'Calculadoras – Simples Nacional': 'etiqueta',
    'Processamento em lote': 'camadas',
    'Documentos XML': 'validar',
    Sistema: 'pulso',
  };

  /** Ícone de cada tela (por rota). */
  const ROTAS = {
    '': 'inicio', 'classificar-item': 'busca', 'tabela-cclasstrib': 'livro', aliquotas: 'percentual',
    'atividades-simples': 'etiqueta', nfse: 'recibo', 'dados-abertos': 'banco', erros: 'alerta',
    'calc-rg': 'calculadora', bases: 'camadas', pedagio: 'estrada', 'calc-sn': 'calculadora', comparador: 'balanca',
    'lote-classificacao': 'importar', 'lote-auditoria': 'auditar', 'xml-validar': 'validar', status: 'pulso', ajuda: 'ajuda',
  };

  /** Cor de destaque de cada grupo do menu. */
  const CORES_GRUPO = {
    'Consultas e tabelas': 'azul',
    'Calculadoras – Regime Regular': 'indigo',
    'Calculadoras – Simples Nacional': 'verde',
    'Processamento em lote': 'ambar',
    'Documentos XML': 'violeta',
    Sistema: 'cinza',
  };

  Hub.icone = (nome, classe = '') =>
    `<svg class="i ${classe}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[nome] || P.info}</svg>`;
  Hub.iconeRota = (rota) => Hub.icone(ROTAS[rota] || 'info');
  Hub.corGrupo = (grupo) => CORES_GRUPO[grupo] || 'azul';
  Hub.iconeGrupo = (grupo) => Hub.icone(ICONES_GRUPO[grupo] || 'info');
})();
