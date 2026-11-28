package br.com.escritorio.hubfiscal.importacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import br.com.escritorio.hubfiscal.importacao.SpedParser.ResultadoSped;

/** Leitores de SPED e XML com os mesmos arquivos do teste de Linux (scripts/linux/testes). */
public class LeitoresTest {

    /** Arquivo de src/test/resources (também usado pelos testes de outros pacotes). */
    public static byte[] recurso(String nome) throws IOException {
        try (InputStream in = LeitoresTest.class.getResourceAsStream("/" + nome)) {
            return in.readAllBytes();
        }
    }

    @Test
    void spedContribuinteCadastroEMovimentos() throws IOException {
        byte[] sped = recurso("sped-classificacao.txt");
        assertTrue(SpedParser.pareceSped(sped));
        ResultadoSped s = SpedParser.ler(sped);
        assertEquals("EMPRESA TESTE LTDA", s.nome());
        assertEquals("12345678000199", s.cnpj());
        assertEquals("SP", s.uf());
        assertEquals(3550308, s.municipio());
        assertEquals(6, s.cadastro().size(), "itens do 0200");
        assertEquals(4, s.movimentos().size(), "itens do C170");
        ItemFiscal arroz = s.cadastro().stream().filter(i -> "P1".equals(i.codigo)).findFirst().orElseThrow();
        assertEquals("10063021", arroz.ncm);
    }

    @Test
    void nfeComGrupoIbsCbs() throws IOException {
        String[] motivo = new String[1];
        DocumentoFiscal d = XmlFiscalParser.ler("nfe.xml", recurso("nfe-classificacao-conferencia.xml"), motivo);
        assertNotNull(d, motivo[0]);
        assertEquals(55, d.modelo);
        assertEquals("12345678000199", d.emitenteCnpj);
        assertEquals(3550308, d.emitenteMunicipio);
        assertEquals(7, d.itens.size());
        ItemFiscal c2 = d.itens.get(1);
        assertEquals("73181500", c2.ncm);
        assertEquals("200", c2.cstAtual());
        assertEquals("200003", c2.cClassTribAtual());
    }

    @Test
    void xmlComDoctypeERecusadoXxe() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<nfeProc><NFe><infNFe><ide><mod>55</mod></ide><emit><xNome>&e;</xNome></emit></infNFe></NFe></nfeProc>";
        String[] motivo = new String[1];
        assertNull(XmlFiscalParser.ler("xxe.xml", xxe.getBytes(StandardCharsets.UTF_8), motivo));
        assertTrue(motivo[0].startsWith("XML inválido"), motivo[0]);
    }
}
