/*
 * Novidades da versão (página inicial, só para administradores): arquivos alterados/criados, como testar
 * cada funcionalidade nova e as decisões de implementação que o administrador deve revisar.
 */
'use strict';

(() => {
  const { esc } = Hub;
  const VERSAO = '2026-11-26-simulador-cnpj';

  const ARQUIVOS = {
    criados: [
      ['hub-fiscal/src/main/java/.../empresa/Empresa.java', 'empresa e suas particularidades (regime, município, alíquotas do DAS...)'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaRepositorio.java', 'banco SQLite próprio dados/hub-empresas.db (CNPJ único)'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaService.java', 'validação (CNPJ numérico e alfanumérico), cadastro automático na importação'],
      ['hub-fiscal/src/main/java/.../empresa/EmpresaController.java', 'API /hub/empresas (consultar, incluir, editar, excluir)'],
      ['hub-fiscal/src/main/java/.../relatorio/RelatorioPdf.java', 'gerador do PDF (A4 paisagem, logo do escritório, quebra de página)'],
      ['hub-fiscal/src/main/java/.../relatorio/RelatorioController.java', 'API /hub/relatorio/pdf'],
      ['hub-fiscal/src/main/java/.../sistema/AtualizacaoManual.java, AtualizacaoController.java', 'botão "Atualizar agora" (/admin/sistema/atualizar-calculadoras)'],
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
      ['simulador/ImportacaoSimuladorService.java, SimuladorController.java', 'importação de XML/SPED no simulador (/hub/simulador/importar)'],
      ['empresa/ConsultaCnpjService.java', 'consulta pública de CNPJ (BrasilAPI) com cache e mensagens de erro'],
      ['auditoria/AuditoriaService.java, AuditoriaExcel.java, LinhaAuditoria.java', 'CBS do cenário lado a lado; itens malformados não derrubam o lote'],
      ['importacao/SpedParser.java, ItemFiscal.java, XmlFiscalParser.java', 'SPED grande sem estourar memória; BOM; leitor de XML reaproveitado'],
      ['infra/Ufs.java', 'UF pelo código IBGE (antes duplicado e sem validação)'],
      ['testes/suite-simulador.ps1, mock-cnpj.ps1, dados/cnpj-*.json, nfe-auditoria-cenario.xml', 'testes novos (total 207)'],
    ],
  };

  const TESTES = [
    ['1. Cadastro automático de empresas', [
      'Processamento em lote → Classificar cadastro (ou Importar XML/SPED no simulador): envie <code>testes/dados/empresa-sped-contribuicoes.txt</code> e <code>empresa-nfe-simples.xml</code>.',
      'Abre a caixa "Adicionar empresas ao cadastro?" com as empresas novas. Marque e clique em Adicionar selecionadas: cada uma entra com a consulta completa do CNPJ (situação, CNAEs, endereço, quadro societário) e aparece em Cadastros → Empresas como "A conferir".',
      'Processe os mesmos arquivos de novo: a caixa não aparece (nada novo) e nenhuma empresa é duplicada. Em Cadastros → Empresas, marque várias empresas e use Excluir selecionadas.',
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
      'Botão <strong>Atualizar agora</strong> (Sistema → Situação do sistema, só administradores): consulta o GitHub da Receita na hora e acompanha a atualização na tela. No Linux, rode uma vez <code>hub.sh atualizar-hub</code> para instalar o gatilho do botão.',
      'Testes automáticos: <code>testar-hub.bat</code> (inclui a suíte nova "Empresas").',
    ]],
    ['5. Importar XML/SPED no simulador', [
      'Simulador Regime Regular → <strong>Importar XML/SPED</strong>: envie <code>testes/dados/nfe-ibscbs.xml</code> e <code>empresa-nfe-simples.xml</code> e escolha o documento.',
      'A data, o município e os itens vêm da nota. O item sem cClassTrib aparece em amarelo com "cClassTrib sugerido – não veio do documento".',
    ]],
    ['6. Consulta de CNPJ', [
      'Cadastros → Empresas → Nova empresa: digite o CNPJ e clique em <strong>Consultar CNPJ na base pública</strong>. Razão social, fantasia, município e regime são preenchidos e aparecem situação cadastral, CNAEs, endereço e quadro societário.',
      'CNPJ inexistente ou limite de consultas: aparece a explicação e o cadastro manual continua.',
    ]],
    ['8. Vendas por NCM', [
      'Classificar cadastro ou Auditar XML: abaixo do resultado, o quadro <strong>Vendas por NCM</strong> mostra o valor vendido por empresa e NCM, com o total geral (também na aba da planilha). Em "O que entra no total" aparecem os itens que ficaram de fora (entradas, remessas, devoluções...).',
    ]],
    ['7. Auditor com 0,9% e 11,3%', [
      'Auditar XML com <code>testes/dados/nfe-auditoria-cenario.xml</code>: a CBS aparece calculada com a alíquota oficial (0,9%: R$ 68,26) e com o Cenário TCU (11,3%: R$ 857,10), lado a lado na tela e no Excel.',
    ]],
  ];

  const DECISOES = [
    '<strong>Empresas na importação:</strong> nada é cadastrado sem confirmação. O hub lista o contribuinte do SPED (registro 0000) e o <em>emitente</em> de cada XML (não o destinatário) que ainda não estão no cadastro, e o usuário marca quais adicionar; em notas de compra o emitente é o fornecedor. Cada escolhida é cadastrada com a consulta do CNPJ; o regime do arquivo (CRT/EFD) prevalece sobre o indicado pela base pública. A lista vale por 2 horas e só para quem importou.',
    '<strong>Regime deduzido do arquivo:</strong> EFD Contribuições → regime normal (0110: 1 = Lucro Real, 2 = Lucro Presumido); EFD ICMS/IPI → "a definir"; NF-e → CRT; NFS-e → opSimpNac. CPF e CNPJ com dígito verificador inválido não são cadastrados (por isso os arquivos de teste antigos, com CNPJ fictício, não geram cadastro).',
    '<strong>Reimportação não altera</strong> uma empresa já cadastrada (nem a razão social): o que o escritório conferiu prevalece.',
    '<strong>Permissão:</strong> consultar empresas é liberado a todo usuário logado (os simuladores precisam do seletor); incluir, editar e excluir exige a ferramenta nova "Empresas". Usuários com acesso a todas as ferramentas já a recebem; os com acesso parcial precisam que o administrador a libere.',
    '<strong>Campos preenchidos pela empresa podem ser alterados</strong> na tela antes de calcular: o cálculo usa o que está na tela (os campos vindos do cadastro ficam com borda violeta até serem alterados). A empresa escolhida é lembrada neste navegador e vale para todos os simuladores.',
    '<strong>Alíquotas do DAS no cadastro</strong> valem para um período de apuração: o escritório precisa atualizá-las quando o PGDAS-D mudar.',
    '<strong>Botão "Excel" da tabela dos simuladores</strong> foi trocado por "Relatório Excel" (mesmas colunas, mais a aba Resumo com a empresa). Os lotes não mudaram.',
    '<strong>PDF</strong> gerado no servidor com Apache PDFBox 3.0.8 (nova dependência, licença Apache 2.0) e fontes padrão do PDF: símbolos fora do alfabeto latino (ex.: ≥, →) saem como ">=", "->".',
    '<strong>Atualização diária:</strong> foi mantido o agendamento que já existia (Windows: tarefa "Hub Fiscal - Atualizacao"; Linux: timer das 03:00), em vez de criar um segundo. Ele só baixa e recompila quando a Receita publica versão nova; nos demais dias confirma que a base está em dia. "Base atualizada em" é a data mais recente entre a geração do banco oficial e a última verificação sem erro. Neste servidor Windows a tarefa ainda não está agendada: até rodar <code>ativar-atualizacao-automatica.bat</code> como administrador, o topo mostra "Próxima: não agendada".',
    '<strong>Topo:</strong> a pílula "Regime Geral" passou a "Regime Normal". A versão da base (V0073) saiu do topo, mas continua na tela Situação do sistema como detalhe técnico.',
    '<strong>Aviso de empresas novas</strong> vai num cabeçalho da resposta do lote (e não no resultado), para não alterar o resultado nem a planilha do lote.',
    '<strong>Botão "Atualizar agora":</strong> o hub não roda a atualização (no Linux ele não tem privilégio para parar e religar serviços). Ele grava um pedido em <code>logs/pedido-atualizacao.json</code>; no Linux uma unidade <code>hubfiscal-atualizacao.path</code> do systemd (root) inicia a mesma verificação das 03:00; no Windows o hub inicia <code>scripts\\hub.ps1 verificar</code>. Só verifica e atualiza se houver versão nova; desligável com <code>hub.atualizacao-manual: desativada</code>.',
    '<strong>Consulta de CNPJ pela BrasilAPI, com a minhareceita.org (fonte dela) como reserva</strong> quando a BrasilAPI falha – ela responde 503 para alguns CNPJs (gratuita, sem chave, republica os dados abertos do CNPJ): o servidor envia só o CNPJ consultado e precisa de internet. Não há garantia de disponibilidade nem limite publicado; o hub guarda cada resposta por 24 h. Se o escritório tiver contrato com outro provedor, basta trocar <code>hub.consulta-cnpj-url</code> (o formato da resposta precisa ser o da BrasilAPI) ou desligar com <code>desativada</code>.',
    '<strong>Auditor:</strong> o status OK/Divergente continua comparando o XML com a alíquota oficial (a que vale para a nota em 2026). A coluna do cenário TCU é só simulação, calculada no hub sem nova chamada à calculadora.',
    '<strong>Importação no simulador:</strong> cada XML é uma operação (data e local próprios): com vários arquivos, a tela pede qual simular. O simulador carrega até 300 itens por documento; acima disso, use o processamento em lote. A sugestão de cClassTrib é a mesma da classificação em lote (anexo do NCM/NBS; sem anexo, 000001) e precisa ser conferida.',
    '<strong>Nome:</strong> a marca voltou a "Hub Fiscal" em telas, títulos, PDF, scripts e documentação. O PDF da documentação em <code>docs/</code> foi gerado antes e não foi alterado.',
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
