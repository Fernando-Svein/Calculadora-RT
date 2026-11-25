package br.com.escritorio.hubfiscal.infra;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Navegação segura nas respostas JSON das calculadoras (já convertidas para Map/List):
 * caminho inexistente devolve null (ou lista/mapa vazio), nunca exceção.
 * Ex.: {@code J.str(obj, "tribCalc", "IBSCBS", "cClassTrib")}.
 */
public final class J {

    private J() {
    }

    /** Valor no caminho (chaves de objeto ou índices de lista), ou null. */
    public static Object get(Object raiz, Object... caminho) {
        Object atual = raiz;
        for (Object chave : caminho) {
            if (atual == null) {
                return null;
            }
            if (chave instanceof Integer i && atual instanceof List<?> l) {
                atual = i >= 0 && i < l.size() ? l.get(i) : null;
            } else if (atual instanceof Map<?, ?> m) {
                atual = m.get(String.valueOf(chave));
            } else {
                return null;
            }
        }
        return atual;
    }

    public static String str(Object raiz, Object... caminho) {
        Object v = get(raiz, caminho);
        return v == null ? null : String.valueOf(v);
    }

    /** Número no caminho (aceita número ou texto numérico), ou null. */
    public static BigDecimal num(Object raiz, Object... caminho) {
        Object v = get(raiz, caminho);
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b : Valores.decimal(String.valueOf(v));
    }

    /** Parte inteira do número no caminho, ou null. */
    public static Integer inteiro(Object raiz, Object... caminho) {
        BigDecimal n = num(raiz, caminho);
        return n == null ? null : n.intValue();
    }

    public static boolean bool(Object raiz, Object... caminho) {
        Object v = get(raiz, caminho);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object raiz, Object... caminho) {
        Object v = get(raiz, caminho);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object raiz, Object... caminho) {
        Object v = get(raiz, caminho);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
