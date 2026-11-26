package br.com.escritorio.hubfiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações do hub (application.yml, prefixo {@code hub}): o único lugar com URLs das
 * calculadoras e caminhos de arquivos. Não há credenciais: as calculadoras rodam localmente sem autenticação.
 *
 * @param apiRegimeGeral            URL base da calculadora do Regime Geral (ex.: http://127.0.0.1:8080/api)
 * @param apiSimplesNacional        URL base da calculadora do Simples Nacional (ex.: http://127.0.0.1:8082/api)
 * @param bancoOficial              caminho do arquivo SQLite oficial (somente leitura)
 * @param paralelismo               chamadas simultâneas às calculadoras nos processamentos em lote
 * @param retencaoResultadosMinutos tempo que um resultado de lote fica disponível para download
 * @param bancoUsuarios             arquivo SQLite com os usuários do hub (criado automaticamente)
 * @param arquivoUltimaAtualizacao  JSON gravado por {@code scripts\hub.ps1 verificar} (mostrado na situação do sistema)
 * @param cenarioCbs                alíquota alternativa de CBS para simulações (sem {@code aliquota}, o seletor não aparece)
 * @param arquivoLogo               logo do escritório enviada pelo administrador (tela Sistema → Logo do escritório)
 * @param bancoEmpresas             arquivo SQLite com as empresas atendidas e suas particularidades (criado automaticamente)
 * @param atualizacaoManual         botão "Atualizar agora" da Situação do sistema: {@code auto} (Windows dispara
 *                                  {@code scripts\hub.ps1 verificar}; Linux grava o pedido que o systemd atende como root),
 *                                  {@code pedido} (só grava o pedido; usado nos testes) ou {@code desativada}
 * @param consultaCnpjUrl           API pública de consulta de CNPJ, com {@code {cnpj}} no lugar do número
 *                                  (padrão BrasilAPI); {@code desativada} desliga o botão "Consultar CNPJ"
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        String apiRegimeGeral,
        String apiSimplesNacional,
        String bancoOficial,
        int paralelismo,
        int retencaoResultadosMinutos,
        String bancoUsuarios,
        String arquivoUltimaAtualizacao,
        CenarioCbs cenarioCbs,
        String arquivoLogo,
        String bancoEmpresas,
        String atualizacaoManual,
        String consultaCnpjUrl) {

    /**
     * Cenário de simulação da CBS com alíquota diferente da oficial (ex.: alíquota estimada para o regime
     * pleno, em vez da alíquota de teste de 2026). Usado só nas simulações; documentos fiscais usam a oficial.
     *
     * @param aliquota alíquota da CBS no cenário, em % (ex.: 11.3)
     * @param rotulo   nome exibido no seletor e nos resultados (ex.: "Cenário TCU")
     */
    public record CenarioCbs(java.math.BigDecimal aliquota, String rotulo) {
    }

    /**
     * Valores de segurança caso a propriedade seja removida do application.yml: um paralelismo 0
     * travaria os lotes (nenhuma vaga) e uma retenção 0 apagaria os resultados antes do download.
     */
    public HubProperties {
        if (bancoUsuarios == null || bancoUsuarios.isBlank()) {
            bancoUsuarios = "../dados/hub-usuarios.db";
        }
        if (arquivoUltimaAtualizacao == null || arquivoUltimaAtualizacao.isBlank()) {
            arquivoUltimaAtualizacao = "../logs/ultima-atualizacao.json";
        }
        if (arquivoLogo == null || arquivoLogo.isBlank()) {
            arquivoLogo = "../dados/logo.png";
        }
        if (bancoEmpresas == null || bancoEmpresas.isBlank()) {
            bancoEmpresas = "../dados/hub-empresas.db";
        }
        if (atualizacaoManual == null || atualizacaoManual.isBlank()) {
            atualizacaoManual = "auto";
        }
        if (consultaCnpjUrl == null || consultaCnpjUrl.isBlank()) {
            consultaCnpjUrl = "https://brasilapi.com.br/api/cnpj/v1/{cnpj},https://minhareceita.org/{cnpj}";
        }
        if (paralelismo <= 0) {
            paralelismo = 8;
        }
        if (retencaoResultadosMinutos <= 0) {
            retencaoResultadosMinutos = 240;
        }
    }
}
