package br.com.escritorio.hubfiscal.classificacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CfopsTest {

    @Test
    void saidaEEntradaPeloPrimeiroDigito() {
        assertTrue(Cfops.saida("5102"));
        assertTrue(Cfops.saida("7101"));
        assertFalse(Cfops.saida("1102"));
        assertTrue(Cfops.entrada("2102"));
        assertFalse(Cfops.entrada("6102"));
        assertFalse(Cfops.saida(null));
        assertFalse(Cfops.entrada(""));
    }

    @Test
    void vendaSoParaReceitaDaOperacao() {
        assertTrue(Cfops.venda("5102"));
        assertTrue(Cfops.venda("6405"));
        assertTrue(Cfops.venda("5933"));
        assertFalse(Cfops.venda("5910"), "bonificação não é venda");
        assertFalse(Cfops.venda("5152"), "transferência não é venda");
        assertFalse(Cfops.venda("5929"), "nota também registrada no ECF não conta duas vezes");
        assertFalse(Cfops.venda("1102"), "entrada");
        assertFalse(Cfops.venda("51A2"));
    }

    @Test
    void indicacaoDeOperacoesEspeciais() {
        assertEquals("410004", Cfops.indicacao("7101").cClassTrib());
        assertEquals("410001", Cfops.indicacao("5910").cClassTrib());
        assertEquals("410003", Cfops.indicacao("6911").cClassTrib());
        assertEquals("410002", Cfops.indicacao("5152").cClassTrib());
        assertEquals("410999", Cfops.indicacao("5949").cClassTrib());
        assertNull(Cfops.indicacao("5202").cClassTrib(), "devolução usa a classificação da operação original");
        assertNull(Cfops.indicacao("5102"), "venda normal não tem indicação");
        assertNull(Cfops.indicacao("1102"), "só saídas");
    }
}
