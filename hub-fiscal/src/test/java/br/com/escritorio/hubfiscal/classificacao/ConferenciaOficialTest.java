package br.com.escritorio.hubfiscal.classificacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.escritorio.hubfiscal.classificacao.ConferenciaOficial.Combinacao;
import br.com.escritorio.hubfiscal.classificacao.ConferenciaOficial.Resultado;
import br.com.escritorio.hubfiscal.classificacao.ConferenciaOficial.Situacao;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.infra.Andamento;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import tools.jackson.databind.json.JsonMapper;

/** Interpretação da resposta da calculadora oficial (as respostas imitam as reais, vistas na VPS). */
class ConferenciaOficialTest {

    private static final HubProperties PROPS = new HubProperties("http://127.0.0.1:1/api", "http://127.0.0.1:1/api", null,
            2, 60, null, null, null, null, null, null, null, 1, 5);

    /** Cliente que devolve a resposta dada e guarda os corpos enviados. */
    static class ClienteFalso extends ApiReceitaClient {
        final List<Object> enviados = new ArrayList<>();
        final ApiResposta resposta;

        ClienteFalso(int status, String json) {
            super(PROPS, JsonMapper.builder().build());
            this.resposta = new ApiResposta(status, json.getBytes(StandardCharsets.UTF_8), "application/json", null);
        }

        @Override
        public ApiResposta postJson(Api api, String caminho, Object corpo) {
            enviados.add(corpo);
            return resposta;
        }
    }

    private static final Combinacao ARROZ = new Combinacao("NCM", "10063021", 55, "200", "200003", 3550308);
    private static final Combinacao PARAFUSO = new Combinacao("NCM", "73181500", 55, "200", "200003", 3550308);

    @Test
    void aceitaERejeitaComOErroOficial() {
        ClienteFalso api = new ClienteFalso(200, """
                {"objetos":[{"nObj":1,"estadoItem":"CALCULADO","erros":[]},
                 {"nObj":2,"estadoItem":"INCONSISTENCIA_ENTRADA","erros":[{"code":"REG-011",
                  "detail":"NCM de código 73181500 não vinculada à Classificação Tributária de código 200003 (CBS e IBS)"}]}]}""");
        Map<Combinacao, Resultado> r = new ConferenciaOficial(api, PROPS)
                .conferir(List.of(ARROZ, PARAFUSO), LocalDate.of(2026, 3, 10), new Andamento());
        assertEquals("Aceita", r.get(ARROZ).rotulo());
        assertEquals(Situacao.REJEITADA, r.get(PARAFUSO).situacao());
        assertTrue(r.get(PARAFUSO).rotulo().startsWith("Rejeitada: REG-011 – NCM de código 73181500"));
    }

    @Test
    void operacaoDeTesteSem2027SemAliquotasNominais() {
        ClienteFalso api = new ClienteFalso(200, "{\"objetos\":[]}");
        new ConferenciaOficial(api, PROPS).conferir(List.of(ARROZ), LocalDate.of(2026, 3, 10), new Andamento());
        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) api.enviados.get(0);
        assertEquals("2026-03-10T12:00:00-03:00", op.get("dhFatoGerador"));
        assertEquals("SP", op.get("uf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) op.get("itens")).get(0);
        assertEquals("10063021", item.get("ncm"));
        assertTrue(!item.containsKey("aliquotasNominais"), "proibidas antes de 2027");

        new ConferenciaOficial(api, PROPS).conferir(List.of(ARROZ), LocalDate.of(2027, 6, 1), new Andamento());
        @SuppressWarnings("unchecked")
        Map<String, Object> item2027 = ((List<Map<String, Object>>) ((Map<String, Object>) api.enviados.get(1)).get("itens")).get(0);
        assertTrue(item2027.containsKey("aliquotasNominais"), "obrigatórias a partir de 2027");
    }

    @Test
    void calculadoraForaDoArViraNaoConferida() {
        ClienteFalso api = new ClienteFalso(0, "{\"title\":\"Calculadora indisponível\",\"detail\":\"não respondeu\"}");
        Map<Combinacao, Resultado> r = new ConferenciaOficial(api, PROPS)
                .conferir(List.of(ARROZ, PARAFUSO), LocalDate.of(2026, 3, 10), new Andamento());
        assertEquals(Situacao.NAO_CONFERIDA, r.get(ARROZ).situacao());
        assertTrue(r.get(PARAFUSO).rotulo().contains("Calculadora indisponível"));
    }

    @Test
    void itemSemRespostaNaoFicaSemSituacao() {
        ClienteFalso api = new ClienteFalso(200, "{\"objetos\":[{\"nObj\":1,\"estadoItem\":\"CALCULADO\"}]}");
        Map<Combinacao, Resultado> r = new ConferenciaOficial(api, PROPS)
                .conferir(List.of(ARROZ, PARAFUSO), LocalDate.of(2026, 3, 10), new Andamento());
        assertEquals(Situacao.ACEITA, r.get(ARROZ).situacao());
        assertEquals(Situacao.NAO_CONFERIDA, r.get(PARAFUSO).situacao());
    }
}
