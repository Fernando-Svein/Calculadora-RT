package br.com.escritorio.hubfiscal.empresa;

import java.util.Map;

/**
 * Empresa atendida pelo escritório, com as particularidades que os simuladores usam nos cálculos.
 *
 * @param id                 identificador interno
 * @param cnpj               CNPJ só com dígitos/letras (14 posições; aceita o CNPJ alfanumérico)
 * @param razaoSocial        razão social (do SPED/XML ou digitada)
 * @param nomeFantasia       nome fantasia (opcional)
 * @param uf                 UF do estabelecimento
 * @param municipio          código IBGE do município do estabelecimento
 * @param regime             {@link Regime} (null = a definir)
 * @param regimeApuracao     regime normal: LUCRO_REAL, LUCRO_PRESUMIDO ou LUCRO_ARBITRADO
 * @param ibscbsForaSimples  optante do Simples que recolhe IBS/CBS pelo regime regular (tpRegimeIBSCBS = 1)
 * @param fatorR             fator r igual ou maior que 28% (Simples)
 * @param sublimiteExcedido  ultrapassou o sublimite estadual (Simples: IBS e ISS saem do DAS)
 * @param atividadeSimples   atividade principal no Simples (código cAtivSN da calculadora oficial)
 * @param aliquotasSimples   parcelas de CBS e IBS na alíquota efetiva do DAS, por anexo (I a V):
 *                           {"III": {"cbs": "2.3874", "ibs": "0.0263", "cbsSemIss": "...", "ibsSemIss": "..."}}
 * @param documentoPadrao    documento fiscal mais emitido (55, 65, 91...)
 * @param cClassTribPadrao   classificação tributária usual das vendas
 * @param observacoes        anotações do escritório
 * @param origem             MANUAL, SPED ou XML (como a empresa entrou no cadastro)
 * @param revisado           false enquanto o cadastro automático não for conferido por alguém
 * @param criadoEm           data/hora do cadastro
 * @param atualizadoEm       data/hora da última alteração
 */
public record Empresa(
        long id,
        String cnpj,
        String razaoSocial,
        String nomeFantasia,
        String uf,
        Integer municipio,
        String regime,
        String regimeApuracao,
        boolean ibscbsForaSimples,
        boolean fatorR,
        boolean sublimiteExcedido,
        String atividadeSimples,
        Map<String, Map<String, String>> aliquotasSimples,
        String documentoPadrao,
        String cClassTribPadrao,
        String observacoes,
        String origem,
        boolean revisado,
        String criadoEm,
        String atualizadoEm) {

    /** Regime tributário da empresa (equivale ao CRT da NF-e). */
    public enum Regime {
        SIMPLES("1", "Simples Nacional"),
        SIMPLES_EXCESSO("2", "Simples Nacional – excesso de sublimite"),
        NORMAL("3", "Regime normal (Lucro Real ou Presumido)"),
        MEI("4", "MEI");

        public final String crt;
        public final String rotulo;

        Regime(String crt, String rotulo) {
            this.crt = crt;
            this.rotulo = rotulo;
        }

        /** Regime pelo CRT da NF-e (1, 2, 3 ou 4), ou null. */
        public static Regime porCrt(String crt) {
            for (Regime r : values()) {
                if (r.crt.equals(crt)) {
                    return r;
                }
            }
            return null;
        }
    }

    public static final java.util.Set<String> APURACOES = java.util.Set.of("LUCRO_REAL", "LUCRO_PRESUMIDO", "LUCRO_ARBITRADO");
}
