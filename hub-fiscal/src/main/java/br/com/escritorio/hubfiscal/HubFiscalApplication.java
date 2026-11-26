package br.com.escritorio.hubfiscal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HUB Fiscal: interface interna do departamento fiscal sobre as calculadoras
 * oficiais de CBS, IBS e Imposto Seletivo (Receita Federal / Serpro).
 * <p>
 * O hub não altera o código das calculadoras oficiais: ele as consome por HTTP
 * (Regime Geral e Simples Nacional) e lê o banco oficial em modo somente leitura.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HubFiscalApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubFiscalApplication.class, args);
    }
}
