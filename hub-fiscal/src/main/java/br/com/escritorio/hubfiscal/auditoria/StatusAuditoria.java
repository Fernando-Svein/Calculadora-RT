package br.com.escritorio.hubfiscal.auditoria;

import java.util.LinkedHashMap;
import java.util.Map;

/** Status de um item na auditoria de XML, na ordem em que aparecem no resultado e na planilha. */
public enum StatusAuditoria {
    DIVERGENTE("Divergente"),
    ERRO_CLASSIFICACAO("Erro de classificação"),
    OK("OK"),
    SEM_GRUPO("Sem grupo IBS/CBS"),
    NAO_AUDITADO("Não auditado");

    private final String rotulo;

    StatusAuditoria(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Texto exibido nas telas e na planilha. */
    public String rotulo() {
        return rotulo;
    }

    /** Contagem com todos os status zerados (chave = nome do status), na ordem de exibição. */
    public static Map<String, Integer> contagemZerada() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (StatusAuditoria s : values()) {
            m.put(s.name(), 0);
        }
        return m;
    }
}
