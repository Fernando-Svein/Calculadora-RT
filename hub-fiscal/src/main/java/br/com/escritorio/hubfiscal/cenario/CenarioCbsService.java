package br.com.escritorio.hubfiscal.cenario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.config.HubProperties.CenarioCbs;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;
import br.com.escritorio.hubfiscal.infra.J;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cenário de simulação da CBS com alíquota diferente da oficial.
 * <p>
 * A calculadora oficial não aceita alíquota informada antes de 2027 (erro REG-036: ela usa a alíquota
 * vigente, 0,9% de teste em 2026). Por isso a operação é calculada oficialmente e este serviço
 * reescala apenas a CBS da resposta: alíquota do cenário, alíquota efetiva = cenário × (1 − redução)
 * e valor = base × alíquota efetiva, com os totais acompanhando. IBS, base, classificação, erros e
 * demais campos ficam exatamente como a calculadora devolveu.
 * <p>
 * Diferimento: valor diferido = base × alíquota efetiva × % diferido, e a CBS devida é o restante.
 * Suspensão e afins: a tributação regular informada ({@code gTribRegular}) passa para a alíquota do
 * cenário, na mesma proporção da alíquota de referência oficial da data. Devolução ({@code gDevTrib})
 * é ajustada proporcionalmente; a CBS monofásica (alíquota ad rem, por unidade) não é alterada.
 * Os casos especiais aparecem nos avisos do resultado.
 */
@Service
public class CenarioCbsService {

    private static final BigDecimal CEM = new BigDecimal("100");

    /**
     * Mesmo modo de arredondamento da calculadora oficial (ArredondamentoUtils.MODO, ABNT NBR 5891):
     * metade exata vai para o algarismo par. Com HALF_UP, empates como 730,545 dariam 1 centavo a mais.
     */
    private static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_EVEN;

    /** Leitura com BigDecimal: mantém casas decimais e evita erro de ponto flutuante. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final CenarioCbs cenario;
    private final ApiReceitaClient api;
    /** Alíquota de referência oficial da CBS por data (a base é somente leitura; muda só com atualização da Receita). */
    private final Map<LocalDate, Optional<BigDecimal>> referencias = new ConcurrentHashMap<>();

    public CenarioCbsService(HubProperties props, ApiReceitaClient api) {
        this.cenario = props.cenarioCbs();
        this.api = api;
    }

    /** Alíquota de referência oficial da CBS na data, consultada na calculadora (vazio se não houver). */
    private Optional<BigDecimal> referenciaOficial(LocalDate data) {
        return referencias.computeIfAbsent(data, d -> {
            ApiResposta r = api.get(Api.RG, EndpointsReceita.RG_ALIQUOTA_UNIAO + "?data=" + d);
            return r.ok() ? Optional.ofNullable(J.num(api.json(r), "aliquotaReferencia")) : Optional.empty();
        });
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void limparCache() {
        referencias.clear();
    }

    /** O cenário está configurado (hub.cenario-cbs.aliquota)? */
    public boolean disponivel() {
        return cenario != null && cenario.aliquota() != null && cenario.aliquota().signum() > 0;
    }

    public CenarioCbs configuracao() {
        return cenario;
    }

    private BigDecimal alvo() {
        if (!disponivel()) {
            throw new ErroNegocio("O cenário de CBS não está configurado (hub.cenario-cbs.aliquota no application.yml).");
        }
        return cenario.aliquota();
    }

    // ---------------------------------------------------------------- Regime Geral

    /**
     * Reescala a CBS de uma resposta das calculadoras de operação (regime-geral e observabilidade).
     *
     * @param data data do fato gerador: define a alíquota de referência oficial (usada na tributação regular)
     */
    public JsonNode regimeGeral(byte[] respostaOficial, LocalDate data) {
        ObjectNode raiz = ler(respostaOficial);
        BigDecimal alvo = alvo();
        BigDecimal referencia = referenciaOficial(data).orElse(null);
        List<String> avisos = new ArrayList<>();
        BigDecimal oficial = null;
        int ajustados = 0;
        // diferenças por campo de total (vCBS, vDif, vDevTrib), somadas aos totais oficiais
        BigDecimal dvCbs = BigDecimal.ZERO, dvDif = BigDecimal.ZERO, dvDev = BigDecimal.ZERO;

        for (JsonNode obj : raiz.path("objetos")) {
            JsonNode ibscbs = obj.path("tribCalc").path("IBSCBS");
            JsonNode g = ibscbs.path("gIBSCBS");
            if (!(g.path("gCBS") instanceof ObjectNode gCbs)) {
                continue; // item não calculado (erro) ou sem grupo de CBS
            }
            String item = "item " + obj.path("nObj").asString("?");
            if (ibscbs.path("CST").asString("").startsWith("620")) {
                avisos.add(item + ": CBS monofásica (alíquota por unidade) mantida como na calculadora oficial.");
                continue;
            }
            BigDecimal base = numeroOuZero(g.get("vBC"));
            boolean ajustado = false;

            // grupos informativos com a CBS "que seria devida": tributação regular (suspensão etc.) e
            // compra governamental — passam para a alíquota do cenário, na proporção da referência oficial
            ajustado |= reescalarPorReferencia(g.get("gTribRegular"), "pAliqEfetRegCBS", "vTribRegCBS", base, alvo, referencia,
                    item + ": tributação regular", avisos);
            ajustado |= reescalarPorReferencia(g.get("gTribCompraGov"), "pAliqCBS", "vTribCBS", base, alvo, referencia,
                    item + ": compra governamental", avisos);
            avisarCamposNaoTratados(g, "", item, avisos);

            BigDecimal pOficial = numero(gCbs.get("pCBS"));
            if (pOficial == null || pOficial.signum() == 0) {
                ajustados += ajustado ? 1 : 0;
                continue; // CBS devida zero (suspensão, isenção...): nada mais a reescalar
            }
            oficial = pOficial;
            BigDecimal reducao = numeroOuZero(gCbs.path("gRed").get("pRedAliq"));
            BigDecimal efetiva = alvo.multiply(BigDecimal.ONE.subtract(reducao.divide(CEM, 10, ARREDONDAMENTO)));
            BigDecimal vAntes = numeroOuZero(gCbs.get("vCBS"));
            BigDecimal bruto = dinheiro(base.multiply(efetiva).divide(CEM, 10, ARREDONDAMENTO));

            gCbs.put("pCBS", aliquota(alvo));
            if (gCbs.get("gRed") instanceof ObjectNode gRed) {
                gRed.put("pAliqEfet", aliquota(efetiva));
            }
            BigDecimal devida = bruto;
            if (gCbs.get("gDif") instanceof ObjectNode dif) {
                // diferimento: parte diferida = CBS × % diferido; a devida é o restante
                BigDecimal vDifAntes = numeroOuZero(dif.get("vDif"));
                BigDecimal vDif = dinheiro(bruto.multiply(numeroOuZero(dif.get("pDif"))).divide(CEM, 10, ARREDONDAMENTO));
                dif.put("vDif", vDif);
                dvDif = dvDif.add(vDif.subtract(vDifAntes));
                devida = bruto.subtract(vDif);
            }
            if (gCbs.get("gDevTrib") != null) {
                BigDecimal fator = alvo.divide(pOficial, 10, ARREDONDAMENTO);
                dvDev = dvDev.add(escalarValores(gCbs.get("gDevTrib"), fator));
                avisos.add(item + ": devolução de tributo da CBS ajustada proporcionalmente (× " + fmt(alvo) + " ÷ " + fmt(pOficial) + ").");
            }
            gCbs.put("vCBS", devida);
            dvCbs = dvCbs.add(devida.subtract(vAntes));
            if (ibscbs.path("memoriaCalculo").get("cbs") != null && ibscbs.path("memoriaCalculo") instanceof ObjectNode mem) {
                mem.put("cbs", mem.get("cbs").asString("").replace("alíquota de " + fmt(pOficial) + "%",
                        "alíquota de " + fmt(alvo) + "% (" + cenario.rotulo() + "; oficial " + fmt(pOficial) + "%)"));
            }
            ajustados++;
        }

        JsonNode totCbs = raiz.path("total").path("tribCalc").path("IBSCBSTot").path("gCBS");
        if (totCbs instanceof ObjectNode t) {
            somar(t, "vCBS", dvCbs);
            if (dvDif.signum() != 0) {
                somar(t, "vDif", dvDif);
            }
            if (dvDev.signum() != 0) {
                somar(t, "vDevTrib", dvDev);
            }
            if (numeroOuZero(t.get("vCredPres")).signum() != 0) {
                avisos.add("Crédito presumido da CBS mantido como na calculadora oficial (não depende da alíquota).");
            }
        }
        raiz.set("cenarioCbs", resumo(oficial, ajustados, avisos));
        return raiz;
    }

    // ---------------------------------------------------------------- Pedágio

    /** Reescala a CBS de uma resposta da calculadora de pedágio (por trecho e totais). */
    public JsonNode pedagio(byte[] respostaOficial) {
        ObjectNode raiz = ler(respostaOficial);
        BigDecimal alvo = alvo();
        BigDecimal oficial = null;
        BigDecimal soma = BigDecimal.ZERO, somaAntes = BigDecimal.ZERO;
        int ajustados = 0;
        for (JsonNode trecho : raiz.path("trechos")) {
            if (!(trecho.get("cbs") instanceof ObjectNode cbs)) {
                continue;
            }
            BigDecimal pOficial = numero(cbs.get("aliquota"));
            if (pOficial == null || pOficial.signum() == 0) {
                continue;
            }
            oficial = pOficial;
            // alíquota efetiva do trecho = alíquota × participação da extensão do trecho
            BigDecimal participacao = numeroOuZero(cbs.get("aliquotaEfetiva")).divide(pOficial, 10, ARREDONDAMENTO);
            BigDecimal efetiva = alvo.multiply(participacao);
            BigDecimal base = numeroOuZero(trecho.get("baseCalculo"));
            BigDecimal antes = numeroOuZero(cbs.get("tributoCalculado"));
            BigDecimal depois = dinheiro(base.multiply(efetiva).divide(CEM, 10, ARREDONDAMENTO));
            cbs.put("aliquota", alvo);
            cbs.put("aliquotaEfetiva", efetiva.setScale(4, ARREDONDAMENTO));
            cbs.put("tributoCalculado", depois);
            soma = soma.add(depois);
            somaAntes = somaAntes.add(antes);
            ajustados++;
        }
        if (raiz.path("total").get("cbsTotal") instanceof ObjectNode tot) {
            BigDecimal delta = soma.subtract(somaAntes);
            for (String campo : List.of("valorApurado", "valorDevido", "valorTributo")) {
                if (tot.has(campo)) {
                    // a calculadora devolve esses totais como texto ("1.80"): mantém o formato
                    tot.put(campo, dinheiro(numeroOuZero(tot.get(campo)).add(delta)).toPlainString());
                }
            }
        }
        raiz.set("cenarioCbs", resumo(oficial, ajustados, List.of()));
        return raiz;
    }

    // ---------------------------------------------------------------- auxiliares

    /**
     * Passa um grupo informativo da CBS (alíquota + valor) para o cenário: alíquota × cenário ÷ referência
     * oficial (preserva reduções da classificação do grupo) e valor = base × alíquota.
     *
     * @return true se o grupo foi ajustado
     */
    private static boolean reescalarPorReferencia(JsonNode grupo, String campoAliquota, String campoValor, BigDecimal base,
            BigDecimal alvo, BigDecimal referencia, String descricao, List<String> avisos) {
        if (!(grupo instanceof ObjectNode g) || numero(g.get(campoAliquota)) == null || numero(g.get(campoAliquota)).signum() == 0) {
            return false;
        }
        if (referencia == null || referencia.signum() == 0) {
            avisos.add(descricao + " da CBS mantida (sem alíquota de referência oficial na data).");
            return false;
        }
        BigDecimal p = alvo.multiply(numero(g.get(campoAliquota))).divide(referencia, 10, ARREDONDAMENTO);
        g.put(campoAliquota, aliquota(p));
        g.put(campoValor, dinheiro(base.multiply(p).divide(CEM, 10, ARREDONDAMENTO)));
        return true;
    }

    /** Campos de CBS já tratados acima (os demais geram aviso, para nada ficar na alíquota oficial em silêncio). */
    private static final java.util.Set<String> CAMPOS_CBS_TRATADOS = java.util.Set.of(
            "gCBS", "gTribRegular.pAliqEfetRegCBS", "gTribRegular.vTribRegCBS", "gTribCompraGov.pAliqCBS", "gTribCompraGov.vTribCBS");

    private static void avisarCamposNaoTratados(JsonNode no, String caminho, String item, List<String> avisos) {
        if (!(no instanceof ObjectNode o)) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : o.properties()) {
            String c = caminho.isEmpty() ? e.getKey() : caminho + "." + e.getKey();
            if (CAMPOS_CBS_TRATADOS.contains(c)) {
                continue;
            }
            if (e.getValue().isObject()) {
                avisarCamposNaoTratados(e.getValue(), c, item, avisos);
            } else if (c.contains("CBS") && !c.contains("IBSCBS") && numeroOuZero(e.getValue()).signum() != 0) {
                avisos.add(item + ": " + c + " mantido como na calculadora oficial (não reescalado pelo cenário).");
            }
        }
    }

    private ObjectNode resumo(BigDecimal oficial, int ajustados, List<String> avisos) {
        ObjectNode r = JSON.createObjectNode();
        r.put("rotulo", cenario.rotulo());
        r.put("aliquota", alvo());
        if (oficial != null) {
            r.put("aliquotaOficial", oficial);
        }
        r.put("itensAjustados", ajustados);
        ArrayNode a = r.putArray("avisos");
        avisos.forEach(a::add);
        return r;
    }

    private static ObjectNode ler(byte[] json) {
        try {
            if (JSON.readTree(json) instanceof ObjectNode o) {
                return o;
            }
        } catch (RuntimeException e) {
            // cai na mensagem abaixo
        }
        throw new IllegalArgumentException("Envie a resposta da calculadora (objeto JSON).");
    }

    /** Multiplica pelo fator os valores (campos "v...") de um grupo; devolve a diferença total. */
    private static BigDecimal escalarValores(JsonNode grupo, BigDecimal fator) {
        BigDecimal delta = BigDecimal.ZERO;
        if (grupo instanceof ObjectNode g) {
            for (Map.Entry<String, JsonNode> e : List.copyOf(g.properties())) {
                if (e.getKey().startsWith("v") && numero(e.getValue()) != null) {
                    BigDecimal antes = numero(e.getValue());
                    BigDecimal depois = dinheiro(antes.multiply(fator));
                    g.put(e.getKey(), depois);
                    delta = delta.add(depois.subtract(antes));
                }
            }
        }
        return delta;
    }

    private static void somar(ObjectNode o, String campo, BigDecimal delta) {
        if (o.has(campo)) {
            o.put(campo, dinheiro(numeroOuZero(o.get(campo)).add(delta)));
        }
    }

    private static BigDecimal numero(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        try {
            return new BigDecimal(n.asString("").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal numeroOuZero(JsonNode n) {
        BigDecimal b = numero(n);
        return b == null ? BigDecimal.ZERO : b;
    }

    /** Alíquota publicada: até 4 casas e no mínimo 2 (tipo 3v2-4 do leiaute, como a calculadora). */
    private static BigDecimal aliquota(BigDecimal b) {
        BigDecimal a = b.setScale(4, ARREDONDAMENTO).stripTrailingZeros();
        return a.scale() < 2 ? a.setScale(2) : a;
    }

    private static BigDecimal dinheiro(BigDecimal b) {
        return b.setScale(2, ARREDONDAMENTO);
    }

    /** 0,90 / 11,30 — mesmo formato da memória de cálculo da calculadora. */
    private static String fmt(BigDecimal b) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return f.format(b);
    }
}
