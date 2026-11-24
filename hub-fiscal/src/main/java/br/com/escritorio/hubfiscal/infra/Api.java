package br.com.escritorio.hubfiscal.infra;

/** Calculadoras oficiais consumidas pelo hub. */
public enum Api {
    /** Calculadora do Regime Geral (regime regular): CBS, IBS e IS. */
    RG,
    /** Calculadora do Simples Nacional. */
    SN;

    public static Api of(String valor) {
        return switch (valor.toLowerCase()) {
            case "rg", "regime-geral" -> RG;
            case "sn", "simples-nacional" -> SN;
            default -> throw new IllegalArgumentException("API desconhecida: " + valor);
        };
    }
}
