package br.com.escritorio.hubfiscal.catalogo;

import java.math.BigDecimal;

/**
 * Classificação tributária (cClassTrib) candidata para um NCM/NBS.
 *
 * @param cClassTrib     código da classificação
 * @param cst            CST correspondente (3 primeiros dígitos)
 * @param descricao      descrição oficial
 * @param tratamento     descrição do tratamento tributário (ex.: "Alíquota zero")
 * @param reducaoCbs     percentual de redução da CBS
 * @param reducaoIbsUf   percentual de redução do IBS estadual
 * @param reducaoIbsMun  percentual de redução do IBS municipal
 * @param categoria      agrupamento usado na sugestão
 * @param condicional    true quando depende de requisitos da operação/adquirente
 * @param viaAnexo       true quando o vínculo veio do anexo (específica do produto/serviço)
 * @param anexo          número do anexo da LC 214/2025 (quando houver)
 * @param itemAnexo      item do anexo
 * @param textoAnexo     texto do item do anexo
 * @param fundamentacao  referência legal resumida
 * @param dfeCompativel  se a classificação é admitida no tipo de documento analisado
 */
public record Candidata(
        String cClassTrib,
        String cst,
        String descricao,
        String tratamento,
        BigDecimal reducaoCbs,
        BigDecimal reducaoIbsUf,
        BigDecimal reducaoIbsMun,
        Categoria categoria,
        boolean condicional,
        boolean viaAnexo,
        String anexo,
        String itemAnexo,
        String textoAnexo,
        String fundamentacao,
        boolean dfeCompativel) {

    /** Agrupamentos de classificação, em ordem de prioridade para a sugestão. */
    public enum Categoria {
        MONOFASICO("Tributação monofásica"),
        BENEFICIO_PRODUTO("Benefício vinculado ao produto/serviço"),
        BENEFICIO_ATIVIDADE("Benefício que depende da atividade ou do adquirente"),
        REGIME_ESPECIAL("Regime especial / condicional"),
        INTEGRAL("Tributação integral"),
        OPERACAO("Situação da operação (exportação, bonificação, transferência...)");

        private final String rotulo;

        Categoria(String rotulo) {
            this.rotulo = rotulo;
        }

        public String getRotulo() {
            return rotulo;
        }
    }

    public String reducaoResumo() {
        if (reducaoCbs == null || reducaoCbs.signum() == 0) {
            return tratamento == null ? "" : tratamento;
        }
        boolean iguais = reducaoCbs.compareTo(nz(reducaoIbsUf)) == 0 && reducaoCbs.compareTo(nz(reducaoIbsMun)) == 0;
        if (iguais && reducaoCbs.compareTo(new BigDecimal("100")) == 0 && tratamento != null && !tratamento.isBlank()) {
            return tratamento; // ex.: "Alíquota zero" (texto oficial) em vez de "Redução 100%"
        }
        String base = iguais ? reducaoCbs.stripTrailingZeros().toPlainString() + "%"
                : "CBS " + reducaoCbs.stripTrailingZeros().toPlainString() + "% / IBS "
                        + nz(reducaoIbsUf).stripTrailingZeros().toPlainString() + "%";
        return "Redução " + base;
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
