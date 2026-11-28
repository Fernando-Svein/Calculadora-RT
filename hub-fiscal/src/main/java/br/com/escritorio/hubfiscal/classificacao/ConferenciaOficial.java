package br.com.escritorio.hubfiscal.classificacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;
import br.com.escritorio.hubfiscal.infra.J;
import br.com.escritorio.hubfiscal.infra.Paralelo;
import br.com.escritorio.hubfiscal.infra.Ufs;

/**
 * Confere na calculadora oficial do Regime Geral se ela aceita cada combinação NCM/NBS + CST + cClassTrib + modelo
 * de documento. Cada combinação vai como um item de uma operação de teste (base 100,00) no endpoint de
 * observabilidade, que devolve o estado de cada item: calculado (aceita) ou com os erros da validação oficial.
 * A conferência é a mesma que a calculadora faz ao calcular uma nota de verdade, sem regra própria do hub.
 */
@Service
public class ConferenciaOficial {

    /** Itens por operação de teste: poucas chamadas sem montar requisições enormes. */
    private static final int ITENS_POR_OPERACAO = 100;
    private static final BigDecimal BASE_TESTE = new BigDecimal("100.00");
    /** A partir de 2027 a calculadora exige alíquotas nominais; os valores não interferem na validação da classificação. */
    private static final int ANO_ALIQUOTAS_NOMINAIS = 2027;
    /** Município usado quando os arquivos não trazem o do contribuinte (São Paulo/SP). */
    static final int MUNICIPIO_PADRAO = 3550308;

    public enum Situacao {
        ACEITA, REJEITADA, NAO_CONFERIDA
    }

    /** Combinação enviada à calculadora. {@code tipo} é "NCM" ou "NBS". */
    public record Combinacao(String tipo, String codigo, int modelo, String cst, String cClassTrib, int municipio) {
    }

    /** Resposta da calculadora para uma combinação; {@code mensagem} traz os erros ou o motivo de não conferir. */
    public record Resultado(Situacao situacao, String mensagem) {

        /** Texto para a planilha e a tela. */
        public String rotulo() {
            return switch (situacao) {
                case ACEITA -> "Aceita";
                case REJEITADA -> "Rejeitada: " + mensagem;
                case NAO_CONFERIDA -> "Não conferida: " + mensagem;
            };
        }
    }

    private record Grupo(int municipio, int modelo) {
    }

    private final ApiReceitaClient api;
    private final HubProperties props;

    public ConferenciaOficial(ApiReceitaClient api, HubProperties props) {
        this.api = api;
        this.props = props;
    }

    /** Envia as combinações à calculadora (em paralelo, limitado a {@code hub.paralelismo}) com vigência em {@code data}. */
    public Map<Combinacao, Resultado> conferir(Collection<Combinacao> combinacoes, LocalDate data) {
        // a operação tem um município e um modelo de documento: agrupa por eles e divide em blocos
        Map<Grupo, List<Combinacao>> porGrupo = new LinkedHashMap<>();
        for (Combinacao c : combinacoes) {
            porGrupo.computeIfAbsent(new Grupo(c.municipio(), c.modelo()), k -> new ArrayList<>()).add(c);
        }
        List<List<Combinacao>> blocos = new ArrayList<>();
        for (List<Combinacao> lista : porGrupo.values()) {
            for (int i = 0; i < lista.size(); i += ITENS_POR_OPERACAO) {
                blocos.add(lista.subList(i, Math.min(i + ITENS_POR_OPERACAO, lista.size())));
            }
        }
        Map<Combinacao, Resultado> r = new HashMap<>();
        Paralelo.mapear(blocos, props.paralelismo(), b -> conferirBloco(b, data)).forEach(r::putAll);
        return r;
    }

    private Map<Combinacao, Resultado> conferirBloco(List<Combinacao> bloco, LocalDate data) {
        Map<Combinacao, Resultado> r = new HashMap<>();
        ApiResposta resp = api.postJson(Api.RG, EndpointsReceita.RG_CALCULO_OBSERVABILIDADE, operacao(bloco, data));
        if (!resp.ok()) {
            Resultado falha = new Resultado(Situacao.NAO_CONFERIDA, api.mensagemErro(resp));
            bloco.forEach(c -> r.put(c, falha));
            return r;
        }
        for (Object obj : J.list(api.jsonObjeto(resp), "objetos")) {
            Integer n = J.inteiro(obj, "nObj");
            if (n == null || n < 1 || n > bloco.size()) {
                continue;
            }
            String estado = J.str(obj, "estadoItem");
            if ("CALCULADO".equals(estado) || "CALCULO_SIMULADO".equals(estado)) {
                r.put(bloco.get(n - 1), new Resultado(Situacao.ACEITA, null));
            } else {
                List<String> erros = new ArrayList<>();
                for (Object e : J.list(obj, "erros")) {
                    erros.add(J.str(e, "code") + " – " + J.str(e, "detail"));
                }
                r.put(bloco.get(n - 1), new Resultado(Situacao.REJEITADA,
                        erros.isEmpty() ? "item não calculado (" + estado + ")" : String.join(" | ", erros)));
            }
        }
        for (Combinacao c : bloco) {
            r.putIfAbsent(c, new Resultado(Situacao.NAO_CONFERIDA, "a calculadora não devolveu resultado para o item"));
        }
        return r;
    }

    private static Map<String, Object> operacao(List<Combinacao> bloco, LocalDate data) {
        Combinacao primeira = bloco.get(0);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("id", "conferencia-classificacao");
        op.put("versao", "1.0.0");
        op.put("dhFatoGerador", data + "T12:00:00-03:00");
        op.put("municipio", primeira.municipio());
        String uf = Ufs.doMunicipio(primeira.municipio(), null);
        if (uf != null) {
            op.put("uf", uf);
        }
        op.put("tpDoc", primeira.modelo());
        List<Map<String, Object>> itens = new ArrayList<>();
        for (int i = 0; i < bloco.size(); i++) {
            Combinacao c = bloco.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("numero", i + 1);
            m.put("NBS".equals(c.tipo()) ? "nbs" : "ncm", c.codigo());
            m.put("cst", c.cst());
            m.put("cClassTrib", c.cClassTrib());
            m.put("baseCalculo", BASE_TESTE);
            if (data.getYear() >= ANO_ALIQUOTAS_NOMINAIS) {
                // valores de exemplo do contrato da calculadora
                Map<String, Object> aliq = new LinkedHashMap<>();
                aliq.put("cbs", new BigDecimal("8.8"));
                aliq.put("ibsEstadual", new BigDecimal("8.85"));
                aliq.put("ibsMunicipal", new BigDecimal("8.85"));
                m.put("aliquotasNominais", aliq);
            }
            itens.add(m);
        }
        op.put("itens", itens);
        return op;
    }
}
