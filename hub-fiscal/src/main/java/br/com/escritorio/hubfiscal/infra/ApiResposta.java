package br.com.escritorio.hubfiscal.infra;

import java.nio.charset.StandardCharsets;

/**
 * Resposta bruta de uma chamada a uma calculadora oficial.
 *
 * @param status      código HTTP (0 quando a calculadora não respondeu)
 * @param corpo       bytes da resposta
 * @param contentType tipo de conteúdo devolvido
 * @param aviso       conteúdo do cabeçalho {@code x-warning-dados-simulados}, se houver
 */
public record ApiResposta(int status, byte[] corpo, String contentType, String aviso) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public String texto() {
        return corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
    }
}
