package br.com.escritorio.hubfiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações do hub (application.yml, prefixo {@code hub}).
 *
 * @param apiRegimeGeral            URL base da calculadora do Regime Geral (ex.: http://localhost:8080/api)
 * @param apiSimplesNacional        URL base da calculadora do Simples Nacional (ex.: http://localhost:8082/api)
 * @param bancoOficial              caminho do arquivo SQLite oficial (somente leitura)
 * @param paralelismo               chamadas simultâneas às calculadoras nos processamentos em lote
 * @param retencaoResultadosMinutos tempo que um resultado de lote fica disponível para download
 * @param bancoUsuarios             arquivo SQLite com os usuários do hub (criado automaticamente)
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        String apiRegimeGeral,
        String apiSimplesNacional,
        String bancoOficial,
        int paralelismo,
        int retencaoResultadosMinutos,
        String bancoUsuarios) {

    public HubProperties {
        if (bancoUsuarios == null || bancoUsuarios.isBlank()) {
            bancoUsuarios = "../dados/hub-usuarios.db";
        }
        if (paralelismo <= 0) {
            paralelismo = 8;
        }
        if (retencaoResultadosMinutos <= 0) {
            retencaoResultadosMinutos = 240;
        }
    }
}
