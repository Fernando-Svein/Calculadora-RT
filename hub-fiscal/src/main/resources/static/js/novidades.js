/*
 * Novidades da versão (página inicial, só para administradores): arquivos alterados/criados, como testar
 * cada funcionalidade nova e as decisões de implementação que o administrador deve revisar.
 */
'use strict';

(() => {
  const { esc } = Hub;
  const VERSAO = '2026-11-26-empresas';

  const ARQUIVOS = {
    criados: [
      ['hub-fiscal/src/main/java/.../empresa/Empresa.java', 'empresa e suas particularidades (regime, município, alíquotas do DAS...)'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaRepositorio.java', 'banco SQLite próprio dados/hub-empresas.db (CNPJ único)'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaService.java', 'validação (CNPJ numérico e alfanumérico), cadastro automático na importação'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaController.java', 'API /hub/empresas (consultar, incluir, editar, excluir)'],
      ['hub-fiscal/src/main/java/.../relatorio/RelatorioPdf.java', 'gerador do PDF (A4 paisagem, logo do escritório, quebra de página)'],
      ['hub-fiscal/src/main/java/.../relatorio/RelatorioController.java', 'API /hub/relatorio/pdf'],
      ['static/js/empresas.js', 'seletor "Empresa da simulação" e regras que levam o cadastro aos simuladores'],
      ['static/js/relatorio.js', 'botões "Relatório PDF" e "Relatório Excel"'],
      ['static/js/modulos/empresas.js', 'tela Cadastros → Empresas (lista e cadastro)'],
      ['static/js/novidades.js', 'este quadro'],
      ['testes/suite-empresas.ps1', '33 testes automáticos novos (o total passa a 163)'],
      ['testes/dados/empresa-sped-contribuicoes.txt, empresa-nfe-simples.xml', 'arquivos de teste com CNPJ válido'],
    ],
    alterados: [
      ['hub-fiscal/pom.xml', 'dependência Apache PDFBox 3.0.8'],
      ['application.yml, config/HubProperties.java', 'hub.banco-empresas'],
      ['importacao/SpedParser.java', 'lê UF e município do 0000 e o regime de PIS/COFINS do 0110'],
      ['importacao/XmlFiscalParser.java, DocumentoFiscal.java', 'lê nome fantasia, município do emitente e opSimpNac da NFS-e'],
      ['lote/LoteController.java', 'cadastra as empresas dos arquivos (cabeçalho X-Hub-Empresas-Novas)'],
      ['seguranca/Ferramentas.java', 'ferramenta "Empresas" (incluir/editar)'],
      ['sistema/SistemaController.java', 'datas da última e da próxima atualização da base'],
      ['static/index.html, js/app.js', 'topo: Regime Normal, Simples Nacional e datas da base (sem a versão V0073); grupo Cadastros'],
      ['js/componentes.js, js/icones.js, css/app.css', 'exportação reutilizável da tabela, ícones e estilos novos'],
      ['js/modulos/calc-regime-geral.js, calc-simples.js, comparador.js, pedagio.js, bases-calculo.js', 'seletor de empresa e relatórios PDF/Excel'],
      ['js/modulos/lote-classificacao.js', 'aviso das empresas cadastradas na importação'],
      ['js/modulos/sistema.js, inicio.js', 'datas da base na Situação do sistema; este quadro'],
      ['scripts/hub.ps1, scripts/linux/comum.sh, instalar.sh, hub.sh', 'registram o horário da atualização diária para o hub'],
      ['testes/executar-testes.ps1, README.md', 'nova suíte e documentação'],
    ],
  };

  const TESTES = [
    ['1. Cadastro automático de empresas', [
      'Processamento em lote → Classificar cadastro: envie <code>testes/dados/empresa-sped-contribuicoes.txt</code> e <code>empresa-nfe-simples.xml</code>.',
      'O resultado mostra "2 empresa(s) cadastrada(s) automaticamente". Em Cadastros → Empresas elas aparecem como "A conferir" (a do SPED como Regime normal – Lucro Presumido; a da NF-e, com CNPJ alfanumérico, como Simples Nacional).',
      'Processe os mesmos arquivos de novo: nenhum aviso e nenhuma empresa duplicada.',
      'Abra uma empresa, informe regime, alíquotas do DAS, documento e cClassTrib usuais e salve: passa a "Conferido".',
    ]],
    ['2. Empresa nos simuladores', [
      'Abra o Simulador Simples Nacional e escolha a empresa no quadro "Empresa da simulação": CRT, fator r, alíquotas do DAS, atividade e documento são preenchidos (campos com borda violeta).',
      'Calcule: o resultado mostra "Simulação para <empresa>". Mude para o Simulador Regime Regular, Comparador, Pedágio ou Bases de cálculo: a mesma empresa continua selecionada e aplica o município, o documento e a cClassTrib usuais.',
      'Escolha "Nenhuma – valores genéricos" para voltar a simular sem empresa.',
    ]],
    ['3. Relatórios PDF e Excel', [
      'Depois de calcular em qualquer simulador, use "Relatório PDF" e "Relatório Excel": trazem a empresa, o resumo, a tabela e as observações.',
      'Nas telas de processamento em lote nada mudou (continuam só com a planilha própria).',
    ]],
    ['4. Atualização diária da base', [
      'O topo mostra "Regime Normal", "Simples Nacional" e "Base atualizada em … · Próxima: …" (sem "Base V0073"). A tela Situação do sistema mostra as mesmas datas.',
      'Windows: execute <code>ativar-atualizacao-automatica.bat</code> como administrador; a "Próxima" passa de "não agendada" para a data e hora da próxima verificação (03:00).',
      'Linux: <code>sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub</code> registra o horário do timer (03:00) e o topo passa a mostrar a próxima atualização.',
      'Testes automáticos: <code>testar-hub.bat</code> (inclui a suíte nova "Empresas").',
    ]],
  ];

  const DECISOES = [
    '<strong>Quem é cadastrado na importação:</strong> do SPED, o contribuinte do registro 0000; do XML, só o <em>emitente</em> (não o destinatário). Importar XMLs de compras cadastra os fornecedores: eles ficam "A conferir" e podem ser excluídos.',
    '<strong>Regime deduzido do arquivo:</strong> EFD Contribuições → regime normal (0110: 1 = Lucro Real, 2 = Lucro Presumido); EFD ICMS/IPI → "a definir"; NF-e → CRT; NFS-e → opSimpNac. CPF e CNPJ com dígito verificador inválido não são cadastrados (por isso os arquivos de teste antigos, com CNPJ fictício, não geram cadastro).',
    '<strong>Reimportação não altera</strong> uma empresa já cadastrada (nem a razão social): o que o escritório conferiu prevalece.',
    '<strong>Permissão:</strong> consultar empresas é liberado a todo usuário logado (os simuladores precisam do seletor); incluir, editar e excluir exige a ferramenta nova "Empresas". Usuários com acesso a todas as ferramentas já a recebem; os com acesso parcial precisam que o administrador a libere.',
    '<strong>Campos preenchidos pela empresa podem ser alterados</strong> na tela antes de calcular: o cálculo usa o que está na tela (os campos vindos do cadastro ficam com borda violeta até serem alterados). A empresa escolhida é lembrada neste navegador e vale para todos os simuladores.',
    '<strong>Alíquotas do DAS no cadastro</strong> valem para um período de apuração: o escritório precisa atualizá-las quando o PGDAS-D mudar.',
    '<strong>Botão "Excel" da tabela dos simuladores</strong> foi trocado por "Relatório Excel" (mesmas colunas, mais a aba Resumo com a empresa). Os lotes não mudaram.',
    '<strong>PDF</strong> gerado no servidor com Apache PDFBox 3.0.8 (nova dependência, licença Apache 2.0) e fontes padrão do PDF: símbolos fora do alfabeto latino (ex.: ≥, →) saem como ">=", "->".',
    '<strong>Atualização diária:</strong> foi mantido o agendamento que já existia (Windows: tarefa "HUB Fiscal - Atualizacao"; Linux: timer das 03:00), em vez de criar um segundo. Ele só baixa e recompila quando a Receita publica versão nova; nos demais dias confirma que a base está em dia. "Base atualizada em" é a data mais recente entre a geração do banco oficial e a última verificação sem erro. Neste servidor Windows a tarefa ainda não está agendada: até rodar <code>ativar-atualizacao-automatica.bat</code> como administrador, o topo mostra "Próxima: não agendada".',
    '<strong>Topo:</strong> a pílula "Regime Geral" passou a "Regime Normal". A versão da base (V0073) saiu do topo, mas continua na tela Situação do sistema como detalhe técnico.',
    '<strong>Aviso de empresas novas</strong> vai num cabeçalho da resposta do lote (e não no resultado), para não alterar o resultado nem a planilha do lote.',
    '<strong>Este quadro</strong> aparece só para administradores e pode ser ocultado.',
  ];

  const lista = (itens) => `<ul>${itens.map(([a, b]) => `<li><code>${esc(a)}</code> – ${esc(b)}</li>`).join('')}</ul>`;

  function html() {
    const u = Hub.usuario;
    if (!u || u.perfil !== 'ADMIN') return '';
    let oculto = false;
    try { oculto = localStorage.getItem('hub.novidadesOcultas') === VERSAO; } catch { /* sem armazenamento */ }
    return `<details class="card novidades" ${oculto ? '' : 'open'} data-novidades>
      <summary><strong>Novidades desta versão</strong> <span class="sutil">– cadastro de empresas, empresa nos simuladores, relatórios PDF/Excel e datas da base (visível só para administradores)</span></summary>
      <h4>Como testar</h4>
      <div class="grade g2">${TESTES.map(([t, passos]) => `<div><strong>${esc(t)}</strong><ul>${passos.map((p) => `<li>${p}</li>`).join('')}</ul></div>`).join('')}</div>
      <h4>Decisões de implementação para revisar</h4>
      <ul>${DECISOES.map((d) => `<li>${d}</li>`).join('')}</ul>
      <h4>Arquivos criados</h4>${lista(ARQUIVOS.criados)}
      <h4>Arquivos alterados</h4>${lista(ARQUIVOS.alterados)}
      <p class="sutil" style="margin:12px 0 0">Caminhos Java abreviados: <code>...</code> = <code>src/main/java/br/com/escritorio/hubfiscal</code>; <code>static</code> = <code>hub-fiscal/src/main/resources/static</code>.</p>
    </details>`;
  }

  /** Lembra se o administrador recolheu o quadro (volta a abrir numa versão nova). */
  function ligar(raiz) {
    const d = raiz.querySelector('[data-novidades]');
    if (!d) return;
    d.addEventListener('toggle', () => {
      try { if (d.open) localStorage.removeItem('hub.novidadesOcultas'); else localStorage.setItem('hub.novidadesOcultas', VERSAO); } catch { /* sem armazenamento */ }
    });
  }

  Hub.novidades = { html, ligar };
})();
