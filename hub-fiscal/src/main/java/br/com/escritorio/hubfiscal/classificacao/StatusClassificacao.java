package br.com.escritorio.hubfiscal.classificacao;

import java.util.LinkedHashMap;
import java.util.Map;

/** Status de um item na classificação em lote, na ordem em que aparecem no resultado e na planilha. */
public enum StatusClassificacao {
    DIVERGENTE("Divergente"),
    REVISAR("Revisar"),
    SUGERIDO("Sugerido"),
    CONFERIDO("Conferido"),
    INVALIDO("NCM/NBS inválido"),
    SEM_NOMENCLATURA("Sem NCM/NBS");

    private final String rotulo;

    StatusClassificacao(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Texto exibido nas telas e na planilha. */
    public String rotulo() {
        return rotulo;
    }

    /** Contagem com todos os status zerados (chave = nome do status), na ordem de exibição. */
    public static Map<String, Integer> contagemZerada() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (StatusClassificacao s : values()) {
            m.put(s.name(), 0);
        }
        return m;
    }
}
