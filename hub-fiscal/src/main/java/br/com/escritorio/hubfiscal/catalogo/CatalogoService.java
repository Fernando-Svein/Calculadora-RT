package br.com.escritorio.hubfiscal.catalogo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Nomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Vinculo;
import br.com.escritorio.hubfiscal.catalogo.Candidata.Categoria;
import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;
import br.com.escritorio.hubfiscal.infra.J;

/**
 * Catálogo tributário: classificações (cClassTrib), fundamentação legal e
 * análise de NCM/NBS. Combina as APIs oficiais (tabelas e descrições) com o
 * banco oficial (vínculos por anexo). Resultados ficam em cache por data,
 * pois a base é somente leitura.
 */
@Service
public class CatalogoService {

    /**
     * Palavras que indicam que um benefício depende do adquirente, da região ou
     * de um regime, e não só do produto (heurística para ordenar a sugestão).
     */
    private static final Pattern CONDICIONAL = Pattern.compile(
            "(?i)administra[cç][aã]o p[uú]blica|[oó]rg[aã]os|emerg[eê]ncia|produtor rural|\\bZFM\\b|zona franca|"
                    + "livre com[eé]rcio|incentivad|motorista|cooperativ|regime|repetro|projetos|\\bEAC\\b|"
                    + "importa[cç][aã]o|adquirid[oa]s por|soberania|reabilita|autônomo|aut[oô]nomo|nanoempreendedor");

    /**
     * Prefixos de NCM de combustíveis do regime monofásico (gasolina, diesel, querosene, GLP/GN, etanol, biodiesel).
     * A base oficial não vincula combustíveis a NCM, por isso a lista é mantida aqui para emitir o alerta.
     */
    private static final List<String> COMBUSTIVEIS = List.of("271012", "271019", "271020", "2711", "220710", "220720",
            "3826");

    private final ApiReceitaClient api;
    private final BaseOficial base;

    private final Map<String, Map<String, Map<String, Object>>> cacheClassificacoes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> cacheFundamentacao = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> cacheComAnexo = new ConcurrentHashMap<>();
    private final Map<String, AnaliseNomenclatura> cacheAnalise = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> cacheNcmApi = new ConcurrentHashMap<>();

    public CatalogoService(ApiReceitaClient api, BaseOficial base) {
        this.api = api;
        this.base = base;
    }

    /** Limpa caches diariamente (a base pode ser atualizada pela Receita). */
    @Scheduled(cron = "0 0 3 * * *")
    public void limparCaches() {
        cacheClassificacoes.clear();
        cacheFundamentacao.clear();
        cacheComAnexo.clear();
        cacheAnalise.clear();
        cacheNcmApi.clear();
    }

    // ------------------------------------------------------------------ tabelas

    /** Todas as classificações CBS/IBS vigentes na data, por código. */
    public Map<String, Map<String, Object>> classificacoes(LocalDate data) {
        return cacheClassificacoes.computeIfAbsent(data.toString(), k -> {
            ApiResposta r = api.get(Api.RG, EndpointsReceita.RG_CLASSIFICACOES_CBS_IBS + "?data=" + k);
            if (!r.ok()) {
                throw new ErroNegocio("Não foi possível obter a tabela de classificações da calculadora oficial: "
                        + J.str(api.json(r), "detail"));
            }
            Map<String, Map<String, Object>> m = new LinkedHashMap<>();
            for (Object o : api.jsonLista(r)) {
                m.put(J.str(o, "codigo"), J.map(o));
            }
            return m;
        });
    }

    /** Fundamentação legal resumida por cClassTrib. */
    public Map<String, String> fundamentacoes(LocalDate data) {
        return cacheFundamentacao.computeIfAbsent(data.toString(), k -> {
            ApiResposta r = api.get(Api.RG, EndpointsReceita.RG_FUNDAMENTACOES + "?data=" + k);
            Map<String, String> m = new LinkedHashMap<>();
            if (r.ok()) {
                for (Object o : api.jsonLista(r)) {
                    String cod = J.str(o, "codigoClassificacaoTributaria");
                    String texto = J.str(o, "textoCurto");
                    String detalhe = J.str(o, "texto");
                    String valor = texto == null ? "" : texto;
                    if (detalhe != null && !detalhe.isBlank() && !detalhe.equalsIgnoreCase(valor)) {
                        valor += (valor.isEmpty() ? "" : " - ") + detalhe;
                    }
                    m.merge(cod, valor, (a, b) -> a.contains(b) ? a : a + "; " + b);
                }
            }
            return m;
        });
    }

    private Set<String> comAnexo(Tipo t, LocalDate data) {
        return cacheComAnexo.computeIfAbsent(t + "|" + data, k -> base.classificacoesComAnexo(t, data));
    }

    /** A classificação possui anexo (lista fechada de NCM/NBS) vigente para o tipo? */
    public boolean temAnexo(Tipo t, String cClassTrib, LocalDate data) {
        return comAnexo(t, data).contains(cClassTrib);
    }

    /** Dados do NCM na API oficial (inclui sujeição ao Imposto Seletivo); vazio se a API recusar. */
    private Map<String, Object> ncmApi(String ncm, LocalDate data) {
        return cacheNcmApi.computeIfAbsent(ncm + "|" + data, k -> {
            ApiResposta r = api.get(Api.RG, EndpointsReceita.RG_NCM + "?ncm=" + ncm + "&data=" + data);
            return r.ok() ? api.jsonObjeto(r) : Map.of();
        });
    }

    // ------------------------------------------------------------------ análise

    /**
     * Analisa um NCM (8 dígitos) ou NBS (9 dígitos) e devolve as classificações
     * candidatas, em ordem de prioridade.
     *
     * @param tipoDfe modelo do documento (55, 65, 91...) ou null para não filtrar
     */
    public AnaliseNomenclatura analisar(Tipo tipo, String codigoBruto, LocalDate data, Integer tipoDfe) {
        String codigo = codigoBruto == null ? "" : codigoBruto.replaceAll("\\D", "");
        String chave = tipo + "|" + codigo + "|" + data + "|" + tipoDfe;
        AnaliseNomenclatura cache = cacheAnalise.get(chave);
        if (cache != null) {
            return cache;
        }
        AnaliseNomenclatura a = analisarSemCache(tipo, codigo, data, tipoDfe);
        cacheAnalise.put(chave, a);
        return a;
    }

    private AnaliseNomenclatura analisarSemCache(Tipo tipo, String codigo, LocalDate data, Integer tipoDfe) {
        int tamanho = tipo == Tipo.NCM ? 8 : 9;
        if (codigo.length() != tamanho) {
            return invalida(tipo, codigo, data, tipoDfe,
                    tipo + " deve ter " + tamanho + " dígitos (informado: \"" + codigo + "\").");
        }
        Optional<Nomenclatura> nom = base.nomenclatura(tipo, codigo, data);
        if (nom.isEmpty()) {
            return invalida(tipo, codigo, data, tipoDfe, tipo + " " + codigo + " não existe na tabela oficial.");
        }
        Nomenclatura n = nom.get();
        if (!n.vigente()) {
            return new AnaliseNomenclatura(tipo.name(), codigo, data.toString(), tipoDfe, false,
                    tipo + " " + codigo + " não está vigente em " + data + " (vigência " + n.inicioVigencia() + " a "
                            + n.fimVigencia() + ").",
                    n.descricao(), n.descricaoCompleta(), n.lc116(), false, List.of(), List.of(), List.of());
        }
        boolean is = false;
        if (tipo == Tipo.NCM) {
            is = J.bool(ncmApi(codigo, data), "tributadoPeloImpostoSeletivo");
        }

        Map<String, Map<String, Object>> tabela = classificacoes(data);
        Map<String, String> fund = fundamentacoes(data);
        List<Candidata> especificas = new ArrayList<>();
        for (Vinculo v : base.vinculos(tipo, codigo, data)) {
            Map<String, Object> c = tabela.get(v.cClassTrib());
            if (c == null) {
                continue; // classificação não vigente na data
            }
            especificas.add(candidata(c, v, fund, tipoDfe));
        }
        especificas.sort(ORDEM);

        List<Candidata> situacionais = new ArrayList<>();
        Set<String> comAnexo = comAnexo(tipo, data);
        for (Map<String, Object> c : tabela.values()) {
            String cod = J.str(c, "codigo");
            String nomenclatura = String.valueOf(J.str(c, "nomenclatura"));
            if (comAnexo.contains(cod) || !nomenclatura.contains(tipo.name())) {
                continue;
            }
            Candidata cand = candidata(c, null, fund, tipoDfe);
            if (!cand.dfeCompativel()) {
                continue;
            }
            // Para NCM, listar apenas reduções/benefícios sem anexo e a tributação integral;
            // para NBS, todas as possibilidades por atividade.
            if (tipo == Tipo.NCM && cand.categoria() == Categoria.OPERACAO) {
                continue;
            }
            situacionais.add(cand);
        }
        situacionais.sort(ORDEM);

        List<String> obs = new ArrayList<>();
        if (tipo == Tipo.NCM && COMBUSTIVEIS.stream().anyMatch(codigo::startsWith)) {
            obs.add("Combustível possivelmente sujeito ao regime monofásico do IBS/CBS (LC 214/2025, arts. 172 e "
                    + "seguintes): a base oficial não vincula combustíveis por NCM. Avalie 620001 (produtor/importador), "
                    + "620006 (tributação cobrada anteriormente) e as demais classificações 620xxx.");
        }
        if (is) {
            obs.add("Sujeito ao Imposto Seletivo a partir de 2027: informe também o grupo IS (CST e cClassTrib do IS).");
        }
        return new AnaliseNomenclatura(tipo.name(), codigo, data.toString(), tipoDfe, true, null, n.descricao(),
                n.descricaoCompleta(), n.lc116(), is, especificas, situacionais, obs);
    }

    private static AnaliseNomenclatura invalida(Tipo tipo, String codigo, LocalDate data, Integer tipoDfe, String msg) {
        return new AnaliseNomenclatura(tipo.name(), codigo, data.toString(), tipoDfe, false, msg, null, null, null,
                false, List.of(), List.of(), List.of());
    }

    /** Ordem: categoria (prioridade), maior redução, código. */
    private static final Comparator<Candidata> ORDEM = Comparator
            .comparing((Candidata c) -> !c.dfeCompativel())
            .thenComparing(Candidata::condicional)
            .thenComparing(Candidata::categoria)
            .thenComparing(c -> c.reducaoCbs() == null ? BigDecimal.ZERO : c.reducaoCbs(), Comparator.reverseOrder())
            .thenComparing(Candidata::cClassTrib);

    /** Monta a candidata a partir do registro da API (e do vínculo de anexo, se houver). */
    public Candidata candidata(Map<String, Object> c, Vinculo v, Map<String, String> fund, Integer tipoDfe) {
        String cod = J.str(c, "codigo");
        String cst = cod.substring(0, 3);
        String descricao = J.str(c, "descricao");
        Categoria cat = categoria(cst, descricao, v != null);
        boolean condicional = cat == Categoria.REGIME_ESPECIAL || cat == Categoria.OPERACAO
                || cat == Categoria.BENEFICIO_ATIVIDADE || CONDICIONAL.matcher(String.valueOf(descricao)).find();
        return new Candidata(cod, cst, descricao, J.str(c, "descricaoTratamentoTributario"),
                J.num(c, "percentualReducaoCbs"), J.num(c, "percentualReducaoIbsUf"), J.num(c, "percentualReducaoIbsMun"),
                cat, condicional, v != null,
                v == null ? null : v.anexo(), v == null ? null : v.itemAnexo(), v == null ? null : v.textoItemAnexo(),
                fund.get(cod), admiteDocumento(c, tipoDfe));
    }

    /**
     * A classificação (registro da tabela oficial) é admitida no documento modelo {@code tipoDfe}
     * (lista {@code tiposDfeClassificacao})? Sem modelo informado, não filtra.
     */
    public static boolean admiteDocumento(Map<String, Object> registro, Integer tipoDfe) {
        return tipoDfe == null || J.list(registro, "tiposDfeClassificacao").stream()
                .anyMatch(t -> tipoDfe.toString().equals(J.str(t, "tipo")));
    }

    private static Categoria categoria(String cst, String descricao, boolean viaAnexo) {
        return switch (cst) {
            case "620" -> Categoria.MONOFASICO;
            case "000" -> descricao != null && descricao.toLowerCase().contains("integralmente") ? Categoria.INTEGRAL
                    : Categoria.REGIME_ESPECIAL;
            case "200" -> viaAnexo ? Categoria.BENEFICIO_PRODUTO : Categoria.BENEFICIO_ATIVIDADE;
            case "010", "011", "221", "222" -> Categoria.BENEFICIO_ATIVIDADE;
            case "410", "400" -> Categoria.OPERACAO;
            case "800", "810", "811", "820", "830" -> Categoria.OPERACAO;
            default -> Categoria.REGIME_ESPECIAL; // 510, 515, 550 etc.
        };
    }

    /** Detalhe de uma classificação: registro oficial, fundamentação e anexos NCM/NBS. */
    public Map<String, Object> detalheClassificacao(String cClassTrib, LocalDate data) {
        Map<String, Object> c = classificacoes(data).get(cClassTrib);
        if (c == null) {
            throw new ErroNegocio("cClassTrib " + cClassTrib + " não encontrado ou não vigente em " + data + ".");
        }
        Map<String, Object> r = new LinkedHashMap<>(c);
        r.put("fundamentacao", fundamentacoes(data).get(cClassTrib));
        r.put("anexoNcm", base.anexoDaClassificacao(Tipo.NCM, cClassTrib, data));
        r.put("anexoNbs", base.anexoDaClassificacao(Tipo.NBS, cClassTrib, data));
        return r;
    }
}
