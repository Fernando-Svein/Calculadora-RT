package br.com.escritorio.hubfiscal.infra;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Conversões de valores usadas pelos leitores de arquivos, pelos lotes e pela navegação em JSON. */
public final class Valores {

    private static final DateTimeFormatter GERACAO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Valores() {
    }

    /**
     * Número em formato técnico, com ponto decimal (XML, JSON): {@code "1234.56"}.
     *
     * @return o valor, ou null se o texto for vazio ou inválido
     */
    public static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Inteiro; null se o texto for vazio ou inválido. */
    public static Integer inteiro(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Zero quando o valor é nulo (para somas e comparações). */
    public static BigDecimal zeroSeNulo(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    /** Data e hora atuais no formato exibido nos resultados de lote ({@code dd/MM/yyyy HH:mm}). */
    public static String dataHoraGeracao() {
        return LocalDateTime.now().format(GERACAO);
    }
}
