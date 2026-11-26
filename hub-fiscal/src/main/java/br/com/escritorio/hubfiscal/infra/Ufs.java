package br.com.escritorio.hubfiscal.infra;

import java.util.Map;

/** UF pela tabela do IBGE: os 2 primeiros dígitos do código do município identificam a UF. */
public final class Ufs {

    private static final Map<String, String> POR_CODIGO = Map.ofEntries(
            Map.entry("11", "RO"), Map.entry("12", "AC"), Map.entry("13", "AM"), Map.entry("14", "RR"),
            Map.entry("15", "PA"), Map.entry("16", "AP"), Map.entry("17", "TO"), Map.entry("21", "MA"),
            Map.entry("22", "PI"), Map.entry("23", "CE"), Map.entry("24", "RN"), Map.entry("25", "PB"),
            Map.entry("26", "PE"), Map.entry("27", "AL"), Map.entry("28", "SE"), Map.entry("29", "BA"),
            Map.entry("31", "MG"), Map.entry("32", "ES"), Map.entry("33", "RJ"), Map.entry("35", "SP"),
            Map.entry("41", "PR"), Map.entry("42", "SC"), Map.entry("43", "RS"), Map.entry("50", "MS"),
            Map.entry("51", "MT"), Map.entry("52", "GO"), Map.entry("53", "DF"));

    private Ufs() {
    }

    /** Sigla da UF do município (código IBGE de 7 dígitos), ou {@code padrao} se não for possível identificar. */
    public static String doMunicipio(Integer municipio, String padrao) {
        if (municipio == null) {
            return padrao;
        }
        String c = String.valueOf(municipio);
        return c.length() == 7 ? POR_CODIGO.getOrDefault(c.substring(0, 2), padrao) : padrao;
    }
}
