package br.com.escritorio.hubfiscal.classificacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.LeitoresTest;
import br.com.escritorio.hubfiscal.importacao.SpedParser;

class AgregadoTest {

    private static Map<String, Agregado> agregarSped() throws IOException {
        Importacao imp = new Importacao();
        imp.speds.add(SpedParser.ler(LeitoresTest.recurso("sped-classificacao.txt")));
        return Agregado.agregar(imp);
    }

    @Test
    void cadastroNaoContaComoOcorrenciaEMovimentosSomam() throws IOException {
        Map<String, Agregado> ag = agregarSped();
        Agregado arroz = ag.get("12345678000199|P1");
        assertEquals(2, arroz.ocorrencias, "dois C170; o 0200 não conta");
        assertEquals(0, new BigDecimal("100.00").compareTo(arroz.valor));
        assertEquals(Set.of("5152", "7101"), arroz.cfopsSaida, "transferência e exportação");
        Agregado gasolina = ag.get("12345678000199|P4");
        assertEquals(0, gasolina.ocorrencias, "só cadastrado no 0200");
    }

    @Test
    void chaveDeAnaliseUsaNcmComNfePorPadrao() throws IOException {
        Agregado parafuso = agregarSped().get("12345678000199|P2");
        Agregado.ChaveAnalise k = parafuso.chaveAnalise();
        assertEquals(Tipo.NCM, k.tipo());
        assertEquals("73181500", k.codigo());
        assertEquals(55, k.modelo());
        assertNull(agregarSped().get("12345678000199|P3").chaveAnalise(), "serviço sem NBS no SPED");
    }

    @Test
    void maisFrequente() {
        assertEquals("200003", Agregado.maisFrequente(Map.of("000001", 1, "200003", 3)));
        assertNull(Agregado.maisFrequente(Map.of()));
    }
}
