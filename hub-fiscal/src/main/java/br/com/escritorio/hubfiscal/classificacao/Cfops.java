package br.com.escritorio.hubfiscal.classificacao;

import java.util.Set;

/**
 * Regras auxiliares por CFOP: identificam operações que normalmente usam uma
 * classificação de "situação da operação" em vez da classificação do produto.
 * São indicações para o analisador, não decisões automáticas.
 */
public final class Cfops {

    /** Sugestão derivada de um CFOP. */
    public record Indicacao(String cClassTrib, String descricao) {
    }

    private static final Set<String> TRANSFERENCIA = Set.of("151", "152", "153", "155", "156", "408", "409", "552",
            "557", "658", "659");
    private static final Set<String> REMESSA = Set.of("901", "902", "903", "905", "906", "907", "908", "909", "912",
            "913", "914", "915", "916", "917", "918", "919", "920", "921", "923", "924", "925", "934", "949");
    private static final Set<String> DEVOLUCAO = Set.of("201", "202", "203", "204", "208", "209", "210", "410", "411",
            "412", "413", "503", "553", "555", "556", "660", "661", "662", "918", "919");

    private Cfops() {
    }

    public static boolean saida(String cfop) {
        return cfop != null && !cfop.isEmpty() && "567".indexOf(cfop.charAt(0)) >= 0;
    }

    /**
     * CFOP de venda ou prestação (receita da operação): saídas 5/6/7 com final em
     * 101–125 (vendas de produção e mercadorias), 251–258 (energia), 301–307 (comunicação), 351–360 (transporte),
     * 401–405 (vendas com substituição tributária), 651–656 e 667 (combustíveis), 932 e 933 (prestação de serviço).
     * Ficam de fora remessas, transferências, devoluções, bonificações, amostras, venda de ativo (551) e o 929
     * (lançamento de nota também registrada no ECF, para não contar a venda duas vezes).
     */
    public static boolean venda(String cfop) {
        if (cfop == null || cfop.length() != 4 || !saida(cfop) || !cfop.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int fim = Integer.parseInt(cfop.substring(1));
        return fim >= 101 && fim <= 125 || fim >= 251 && fim <= 258 || fim >= 301 && fim <= 307
                || fim >= 351 && fim <= 360 || fim >= 401 && fim <= 405 || fim >= 651 && fim <= 656 || fim == 667
                || fim == 932 || fim == 933;
    }

    public static boolean entrada(String cfop) {
        return cfop != null && !cfop.isEmpty() && "123".indexOf(cfop.charAt(0)) >= 0;
    }

    /** Indicação de cClassTrib para CFOPs de saída especiais (ou null). */
    public static Indicacao indicacao(String cfop) {
        if (cfop == null || cfop.length() != 4 || !saida(cfop)) {
            return null;
        }
        String fim = cfop.substring(1);
        if (cfop.startsWith("7") && !DEVOLUCAO.contains(fim)) {
            return new Indicacao("410004", "Exportação (CFOP " + cfop + ")");
        }
        if (fim.equals("910")) {
            return new Indicacao("410001", "Bonificação/doação/brinde (CFOP " + cfop + ") – se doação, 410003");
        }
        if (fim.equals("911")) {
            return new Indicacao("410003", "Amostra grátis (CFOP " + cfop + ")");
        }
        if (TRANSFERENCIA.contains(fim)) {
            return new Indicacao("410002", "Transferência entre estabelecimentos (CFOP " + cfop + ")");
        }
        if (DEVOLUCAO.contains(fim)) {
            return new Indicacao(null, "Devolução (CFOP " + cfop + ") – usar a classificação da operação original");
        }
        if (REMESSA.contains(fim)) {
            return new Indicacao("410999", "Remessa sem fornecimento (CFOP " + cfop + ") – avaliar 410999");
        }
        return null;
    }
}
