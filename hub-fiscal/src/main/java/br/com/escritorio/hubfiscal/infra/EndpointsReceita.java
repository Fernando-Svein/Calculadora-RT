package br.com.escritorio.hubfiscal.infra;

/**
 * Endpoints das calculadoras oficiais usados pelo servidor do hub, num só lugar.
 * <p>
 * Os caminhos são relativos às URLs base configuradas em {@code application.yml}
 * ({@code hub.api-regime-geral} e {@code hub.api-simples-nacional}) e seguem exatamente
 * o contrato publicado pela Receita (ver {@code docs-api/*-openapi.json}). As telas
 * chamam os demais endpoints diretamente, pelo proxy ({@code /proxy/rg/...}, {@code /proxy/sn/...}).
 */
public final class EndpointsReceita {

    private EndpointsReceita() {
    }

    // ---------------------------------------------------------------- Regime Geral
    /** Versão da aplicação e da base oficial (usado na situação do sistema). */
    public static final String RG_VERSAO = "/calculadora/dados-abertos/versao";
    /** Tabela de classificações tributárias de CBS/IBS vigentes ({@code ?data=AAAA-MM-DD}). */
    public static final String RG_CLASSIFICACOES_CBS_IBS = "/calculadora/dados-abertos/classificacoes-tributarias/cbs-ibs";
    /** Fundamentação legal por cClassTrib ({@code ?data=AAAA-MM-DD}). */
    public static final String RG_FUNDAMENTACOES = "/calculadora/dados-abertos/fundamentacoes-legais";
    /** Alíquota de referência da CBS (União) na data ({@code ?data=AAAA-MM-DD}); 404 quando não há (2027+). */
    public static final String RG_ALIQUOTA_UNIAO = "/calculadora/dados-abertos/aliquota-uniao";
    /** Dados de um NCM, inclusive sujeição ao Imposto Seletivo ({@code ?ncm=...&data=...}). */
    public static final String RG_NCM = "/calculadora/dados-abertos/ncm";
    /** Cálculo de uma operação com o resultado de cada item (calculado ou com erros). */
    public static final String RG_CALCULO_OBSERVABILIDADE = "/calculadora/observabilidade/regime-geral";

    // ---------------------------------------------------------------- Simples Nacional
    /** Versão da aplicação e da base oficial (usado na situação do sistema). */
    public static final String SN_VERSAO = "/versao";

    /**
     * Cabeçalho de resposta que a calculadora usa para indicar cálculo com dados simulados
     * (enum {@code TipoWarningDadosSimulados}); o hub o repassa às telas.
     */
    public static final String CABECALHO_DADOS_SIMULADOS = "x-warning-dados-simulados";
}
