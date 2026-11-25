/*
 * Tradução dos nomes técnicos das calculadoras oficiais para a linguagem do contador.
 * Fonte dos significados: descrições (@Schema) e enumerações dos próprios projetos da Receita
 * (calculadora-rtc-regime-regular e calculadora-rtc-simples-nacional) e NT 2026.005.
 * Só muda o que aparece na tela: os dados enviados e recebidos continuam com os nomes oficiais.
 */
'use strict';

(() => {
  /** Nomes de campos das respostas (tabelas montadas automaticamente e quadros de resultado). */
  const CAMPOS = {
    // genéricos
    codigo: 'Código', descricao: 'Descrição', titulo: 'Título', grupo: 'Grupo', ordem: 'Ordem', id: 'Código',
    valor: 'Percentual (%)', inicioVigencia: 'Início da vigência', fimVigencia: 'Fim da vigência',
    ncm: 'NCM', nbs: 'NBS', uf: 'UF', municipio: 'Município (código IBGE)', numero: 'Nº',
    cst: 'CST', CST: 'CST', cClassTrib: 'cClassTrib', avisos: 'Avisos', erros: 'Erros',
    // versão
    ambiente: 'Ambiente', versaoApp: 'Versão da calculadora', descricaoVersaoApp: 'Versão da calculadora (descrição)',
    versaoDb: 'Versão da base de dados', dataVersaoDb: 'Data da base de dados', descricaoVersaoDb: 'Novidades da base de dados',
    // documentos fiscais e atores
    modelo: 'Modelo', permiteNcmZero: 'Aceita NCM genérico (00000000)', suportado: 'Calculado pela calculadora', ator: 'Fornecedor / adquirente',
    // classificações (tabela do Imposto Seletivo)
    descricaoTratamentoTributario: 'Tratamento tributário', tipoAliquota: 'Tipo de alíquota', nomenclatura: 'Nomenclatura',
    exigeGrupoDesoneracao: 'Exige informar a desoneração', incompativelComSuspensao: 'Incompatível com suspensão',
    indicaApropriacaoCreditoAdquirenteCbs: 'Adquirente toma crédito de CBS', indicaApropriacaoCreditoAdquirenteIbs: 'Adquirente toma crédito de IBS',
    indicaCreditoPresumidoAdquirente: 'Crédito presumido para o adquirente', indicaCreditoPresumidoFornecedor: 'Crédito presumido para o fornecedor',
    possuiPercentualReducao: 'Tem redução de alíquota', tiposDfeClassificacao: 'Documentos fiscais admitidos',
    // NFS-e
    cIndOp: 'Indicador de operação (cIndOp)', tipoOperacao: 'Tipo de operação', localFornecimento: 'Local de fornecimento',
    localIncidencia: 'Local de incidência do IBS', codigoLocalFornecimento: 'Código do local de fornecimento',
    codigoLocalIncidencia: 'Código do local de incidência', dataOcorrenciaFatoGerador: 'Data do fato gerador', valido: 'Combinação válida',
    cTribNac: 'Código de tributação nacional',
    // bases de cálculo
    baseCalculo: 'Base de cálculo', baseRG: 'Base do IBS/CBS (regime regular)', baseIS: 'Base do Imposto Seletivo',
    baseSN: 'Receita bruta para o Simples Nacional',
    // pedágio
    extensaoTrecho: 'Extensão do trecho (km)', extensaoTotal: 'Extensão total (km)',
    aliquota: 'alíquota (%)', aliquotaEfetiva: 'alíquota efetiva (%)', tributoCalculado: 'valor (R$)',
    valorApurado: 'valor apurado (R$)', valorDevido: 'valor devido (R$)', valorTributo: 'valor do tributo (R$)',
  };

  /** Grupos (prefixo de campos aninhados: "cbs.aliquota" → "CBS – alíquota (%)"). */
  const GRUPOS = {
    cbs: 'CBS', ibsEstadual: 'IBS estadual', ibsMunicipal: 'IBS municipal',
    cbsTotal: 'CBS', ibsEstadualTotal: 'IBS estadual', ibsMunicipalTotal: 'IBS municipal',
  };

  /** Situação do item no resultado das calculadoras (estadoItem). */
  const ESTADOS = {
    CALCULADO: 'Calculado',
    CALCULO_SIMULADO: 'Simulado (alíquota informada)',
    NAO_CALCULADO: 'Não calculado',
    INCONSISTENCIA_ENTRADA: 'Dados inconsistentes',
    NAO_IMPLEMENTADO: 'Ainda não calculado pela Receita',
    FALHA_TECNICA: 'Falha técnica',
  };

  /** Regime de apuração do IBS/CBS no Simples (regimeApuracao). */
  const REGIMES_SIMPLES = {
    SN_PLENO: 'IBS e CBS pelo Simples Nacional',
    SN_SUBLIMITE: 'Só a CBS pelo Simples (excesso de sublimite)',
    PENDENTE: 'Opção pelo Simples pendente (valores ficam suspensos)',
    PENDENTE_SUBLIMITE: 'Opção pendente, só a CBS',
    SOMENTE_RECEITA: 'IBS e CBS pelo regime regular (nota leva só a receita)',
  };

  /** Qual linha de alíquotas foi usada no item do Simples (tipoAliquota). */
  const TIPOS_ALIQUOTA_SIMPLES = {
    SEM_ALIQUOTA: 'Sem alíquota (atividade sem anexo)',
    PAR_UNICO: 'Alíquota única do anexo (I ou II)',
    COM_REDISTRIBUICAO: 'Alíquota normal (com redistribuição do ISS)',
    SEM_REDISTRIBUICAO: 'Alíquota sem o ISS (ISS fora do DAS)',
  };

  /** Por que essa linha foi escolhida (regraAliquota). */
  const REGRAS_SIMPLES = {
    DOCUMENTO: 'Atividade não admitida neste documento',
    ATIVIDADE_NAO_TRIBUTADA: 'Atividade sem anexo no Simples',
    ANEXO_SEM_ISS: 'Anexo sem ISS (I ou II)',
    ISS_FORA_DO_SIMPLES: 'ISS recolhido fora do Simples (sublimite ou opção)',
    ATIVIDADE_SEM_ISS: 'Atividade sem ISS no Simples',
    ISS_NAO_INCIDE: 'ISS não incide (imunidade, exportação ou não incidência)',
    ISENCAO_MUNICIPAL: 'Isenção municipal do ISS',
    ISS_RETIDO: 'ISS retido pelo tomador ou intermediário',
    PADRAO: 'Regra geral',
  };

  /** Desfecho da escolha das alíquotas do item (motivo). */
  const MOTIVOS_SIMPLES = {
    RESOLVIDA: 'Alíquotas encontradas',
    ATIVIDADE_SEM_ANEXO: 'Atividade sem anexo: alíquota zero',
    ATIVIDADE_DESCONHECIDA: 'Atividade não informada, inexistente ou fora de vigência',
    ATIVIDADE_INADMISSIVEL_NO_DOCUMENTO: 'Atividade não admitida neste documento',
    ANEXO_SEM_ALIQUOTA: 'Faltou informar a alíquota de CBS do anexo',
    FATOR_R_AUSENTE: 'Faltou informar o fator r',
    ITEM_NAO_IMPLEMENTADO: 'Situação ainda não calculada pela Receita',
  };

  /** Chaves da memória de cálculo. */
  const MEMORIA = { cbs: 'CBS', ibs: 'IBS', ibsUF: 'IBS estadual', ibsMun: 'IBS municipal', is: 'Imposto Seletivo', IS: 'Imposto Seletivo' };

  /** Documentos fiscais pelo código do modelo. */
  const DOCUMENTOS = {
    55: 'NF-e', 65: 'NFC-e', 91: 'NFS-e', 57: 'CT-e', 67: 'CT-e OS', 63: 'BP-e', 93: 'BP-e TM', 62: 'NFCom', 66: 'NF3e', 75: 'NFAg',
  };

  /** "dataInicioVigencia" → "Data inicio vigencia" (último recurso para campos sem tradução). */
  function humanizar(k) {
    const s = String(k).replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  function campo(chave) {
    if (CAMPOS[chave]) return CAMPOS[chave].charAt(0).toUpperCase() + CAMPOS[chave].slice(1);
    const partes = String(chave).split('.');
    if (partes.length > 1) {
      const fim = partes[partes.length - 1];
      const grupo = partes.slice(0, -1).map((p) => GRUPOS[p] || CAMPOS[p] || humanizar(p)).join(' – ');
      return `${grupo} – ${CAMPOS[fim] || humanizar(fim).toLowerCase()}`;
    }
    return humanizar(chave);
  }

  const de = (mapa) => (v) => (v === null || v === undefined || v === '' ? '' : mapa[v] || String(v));

  /** "/itens/0/cClassTrib" → "item 1 › cClassTrib" (local do erro informado pela calculadora). */
  function ponteiro(p) {
    if (!p) return '';
    return String(p).replace(/^\//, '').split('/')
      .map((x, i, arr) => (/^\d+$/.test(x) && arr[i - 1] === 'itens' ? `item ${Number(x) + 1}` : x))
      .filter((x) => x !== 'itens').join(' › ');
  }

  Hub.rotulos = {
    campo,
    estado: de(ESTADOS),
    regimeSimples: de(REGIMES_SIMPLES),
    tipoAliquotaSimples: de(TIPOS_ALIQUOTA_SIMPLES),
    regraSimples: de(REGRAS_SIMPLES),
    motivoSimples: de(MOTIVOS_SIMPLES),
    memoria: (k) => MEMORIA[k] || humanizar(k),
    documento: (c) => (DOCUMENTOS[c] ? `${DOCUMENTOS[c]} (${c})` : String(c)),
    ponteiro,
  };
})();
