package br.com.escritorio.hubfiscal.empresa;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consulta pública de CNPJ (padrão: BrasilAPI, que republica os dados abertos do CNPJ da Receita Federal).
 * <p>
 * Devolve o cadastro já normalizado (razão social, fantasia, situação cadastral, CNAEs, endereço, Simples/MEI,
 * regime tributário declarado e quadro societário). Só o CNPJ consultado sai do servidor. Respostas válidas ficam
 * 24 h em memória para poupar o limite de requisições do serviço. Falhas viram mensagens claras (CNPJ inexistente,
 * limite atingido, serviço fora do ar) e nunca impedem o cadastro manual.
 */
@Service
public class ConsultaCnpjService {

    private static final Logger log = LoggerFactory.getLogger(ConsultaCnpjService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration VALIDADE_CACHE = Duration.ofHours(24);
    private static final Duration TEMPO_LIMITE = Duration.ofSeconds(12);

    private record Guardado(Map<String, Object> dados, Instant em) {
    }

    /**
     * Serviços em ordem de preferência (hub.consulta-cnpj-url, separados por vírgula). O seguinte é tentado quando o
     * anterior falha (5xx, tempo esgotado, sem conexão, limite ou "não encontrado"): a BrasilAPI, por exemplo, responde
     * 503 para alguns CNPJs que a fonte dela (minhareceita.org) devolve normalmente. Todos no formato da BrasilAPI.
     */
    private final List<String> urls;
    private final HttpClient http;
    private final Map<String, Guardado> cache = new ConcurrentHashMap<>();

    public ConsultaCnpjService(HubProperties props) {
        String cfg = props.consultaCnpjUrl().trim();
        this.urls = "desativada".equalsIgnoreCase(cfg) ? List.of()
                : java.util.Arrays.stream(cfg.split(",")).map(String::trim).filter(u -> u.contains("{cnpj}")).toList();
        this.http = HttpClient.newBuilder().connectTimeout(TEMPO_LIMITE).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public boolean disponivel() {
        return !urls.isEmpty();
    }

    /** Falha de um serviço: status HTTP (0 = sem resposta) e motivo. */
    private record Falha(int status, String motivo) {
    }

    /** Cadastro público do CNPJ, normalizado; erros como ResponseStatusException/ErroNegocio com mensagem para a tela. */
    public Map<String, Object> consultar(String cnpjInformado) {
        if (!disponivel()) {
            throw new ErroNegocio("A consulta de CNPJ está desativada neste servidor (hub.consulta-cnpj-url). Preencha o cadastro manualmente.");
        }
        String cnpj = EmpresaService.normalizarCnpj(cnpjInformado);
        if (!EmpresaService.cnpjValido(cnpj)) {
            throw new ErroNegocio("CNPJ inválido. Confira os 14 caracteres e os dígitos verificadores.");
        }
        Guardado g = cache.get(cnpj);
        if (g != null && g.em().plus(VALIDADE_CACHE).isAfter(Instant.now())) {
            return g.dados();
        }
        List<Falha> falhas = new ArrayList<>();
        for (String url : urls) {
            Object r = consultarEm(url, cnpj);
            if (r instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dados = (Map<String, Object>) m;
                if (!falhas.isEmpty()) {
                    log.info("Consulta de CNPJ atendida pelo serviço reserva {} (o anterior falhou: {})", host(url), falhas);
                }
                cache.put(cnpj, new Guardado(dados, Instant.now()));
                return dados;
            }
            falhas.add((Falha) r);
        }
        // nenhum serviço respondeu: a mensagem reflete a falha mais informativa
        String fmt = EmpresaService.formatarCnpj(cnpj);
        if (falhas.stream().allMatch(f -> f.status() == 404)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "CNPJ " + fmt + " não encontrado na base pública da Receita. Confira o número ou cadastre manualmente.");
        }
        if (falhas.stream().anyMatch(f -> f.status() == 429)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de consultas do serviço público de CNPJ atingido. Aguarde alguns minutos e tente de novo (o cadastro manual continua disponível).");
        }
        if (falhas.stream().allMatch(f -> f.status() == 400 || f.status() == 404)) {
            throw new ErroNegocio("O serviço de consulta recusou o CNPJ " + fmt
                    + " (o CNPJ alfanumérico pode ainda não ser aceito por ele). Cadastre manualmente.");
        }
        log.warn("Consulta de CNPJ sem sucesso em todos os serviços: {}", falhas);
        throw indisponivel(String.join("; ", falhas.stream().map(Falha::motivo).toList()));
    }

    /** Consulta um serviço: devolve o cadastro normalizado (Map) ou a {@link Falha}. */
    private Object consultarEm(String url, String cnpj) {
        String quem = host(url);
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.replace("{cnpj}", cnpj)))
                    .timeout(TEMPO_LIMITE)
                    .header("Accept", "application/json")
                    .header("User-Agent", "HubFiscal/1.0")
                    .GET().build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return new Falha(0, quem + " demorou demais para responder");
        } catch (IOException e) {
            return new Falha(0, "sem conexão com " + quem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Falha(0, "consulta interrompida");
        } catch (IllegalArgumentException e) {
            return new Falha(0, "endereço inválido em hub.consulta-cnpj-url");
        }
        int st = resp.statusCode();
        if (st < 200 || st >= 300) {
            return new Falha(st, quem + " respondeu com erro HTTP " + st);
        }
        JsonNode j;
        try {
            j = JSON.readTree(resp.body());
        } catch (RuntimeException e) {
            return new Falha(st, quem + " devolveu resposta em formato inesperado");
        }
        if (j == null || !j.isObject() || texto(j, "razao_social") == null) {
            return new Falha(st, quem + " devolveu resposta sem a razão social");
        }
        return normalizar(j, cnpj, quem);
    }

    private static String host(String url) {
        try {
            String h = URI.create(url.replace("{cnpj}", "0")).getHost();
            return h == null ? url : h;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static ResponseStatusException indisponivel(String motivo) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível consultar o CNPJ agora: " + motivo + ". Tente mais tarde ou preencha o cadastro manualmente.");
    }

    /** Formato do hub (independe do serviço usado), com os campos públicos que interessam ao escritório. */
    static Map<String, Object> normalizar(JsonNode j, String cnpj, String fonte) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cnpj", cnpj);
        m.put("razaoSocial", texto(j, "razao_social"));
        m.put("nomeFantasia", texto(j, "nome_fantasia"));
        m.put("situacaoCadastral", texto(j, "descricao_situacao_cadastral"));
        m.put("dataSituacaoCadastral", texto(j, "data_situacao_cadastral"));
        m.put("motivoSituacaoCadastral", texto(j, "descricao_motivo_situacao_cadastral"));
        m.put("dataAbertura", texto(j, "data_inicio_atividade"));
        m.put("naturezaJuridica", texto(j, "natureza_juridica"));
        m.put("porte", texto(j, "porte") != null ? texto(j, "porte") : texto(j, "descricao_porte"));
        m.put("capitalSocial", j.path("capital_social").isNumber() ? j.path("capital_social").decimalValue() : null);
        m.put("cnaePrincipal", cnae(j.path("cnae_fiscal").asString(null), texto(j, "cnae_fiscal_descricao")));
        List<Map<String, Object>> sec = new ArrayList<>();
        for (JsonNode c : j.path("cnaes_secundarios")) {
            Map<String, Object> x = cnae(c.path("codigo").asString(null), texto(c, "descricao"));
            if (x != null) {
                sec.add(x);
            }
        }
        m.put("cnaesSecundarios", sec);
        Map<String, Object> end = new LinkedHashMap<>();
        String tipoLog = texto(j, "descricao_tipo_de_logradouro");
        String logr = texto(j, "logradouro");
        end.put("logradouro", tipoLog != null && logr != null && !logr.toUpperCase(Locale.ROOT).startsWith(tipoLog.toUpperCase(Locale.ROOT))
                ? tipoLog + " " + logr : logr);
        end.put("numero", texto(j, "numero"));
        end.put("complemento", texto(j, "complemento"));
        end.put("bairro", texto(j, "bairro"));
        end.put("cep", texto(j, "cep"));
        end.put("municipio", texto(j, "municipio"));
        end.put("uf", texto(j, "uf"));
        String ibge = j.path("codigo_municipio_ibge").asString(null);
        end.put("codigoMunicipioIbge", ibge != null && ibge.matches("\\d{7}") ? Integer.valueOf(ibge) : null);
        m.put("endereco", end);
        m.put("telefone", texto(j, "ddd_telefone_1"));
        m.put("email", texto(j, "email"));
        Map<String, Object> sn = new LinkedHashMap<>();
        sn.put("optanteSimples", j.path("opcao_pelo_simples").isBoolean() ? j.path("opcao_pelo_simples").asBoolean() : null);
        sn.put("dataOpcaoSimples", texto(j, "data_opcao_pelo_simples"));
        sn.put("dataExclusaoSimples", texto(j, "data_exclusao_do_simples"));
        sn.put("mei", j.path("opcao_pelo_mei").isBoolean() ? j.path("opcao_pelo_mei").asBoolean() : null);
        m.put("simples", sn);
        // regime declarado (ECF/DCTF): o ano mais recente
        List<JsonNode> regimes = new ArrayList<>();
        j.path("regime_tributario").forEach(regimes::add);
        regimes.stream().max(Comparator.comparingInt(r -> r.path("ano").asInt(0))).ifPresent(r -> {
            Map<String, Object> rt = new LinkedHashMap<>();
            rt.put("ano", r.path("ano").asInt(0));
            rt.put("forma", texto(r, "forma_de_tributacao"));
            m.put("regimeTributario", rt);
        });
        List<Map<String, Object>> qsa = new ArrayList<>();
        for (JsonNode s : j.path("qsa")) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nome", texto(s, "nome_socio"));
            x.put("qualificacao", texto(s, "qualificacao_socio"));
            x.put("dataEntrada", texto(s, "data_entrada_sociedade"));
            x.put("faixaEtaria", texto(s, "faixa_etaria"));
            x.put("representanteLegal", texto(s, "nome_representante_legal"));
            if (x.get("nome") != null) {
                qsa.add(x);
            }
        }
        m.put("qsa", qsa);
        m.put("regimeSugerido", regimeSugerido(m));
        m.put("fonte", "Dados públicos do CNPJ (Receita Federal) via " + fonte);
        m.put("consultadoEm", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return m;
    }

    /** Regime para pré-preencher o cadastro: MEI, Simples ou, pela forma de tributação declarada, regime normal. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> regimeSugerido(Map<String, Object> m) {
        Map<String, Object> sn = (Map<String, Object>) m.get("simples");
        Map<String, Object> r = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(sn.get("mei"))) {
            r.put("regime", "MEI");
        } else if (Boolean.TRUE.equals(sn.get("optanteSimples"))) {
            r.put("regime", "SIMPLES");
        } else if (m.get("regimeTributario") instanceof Map<?, ?> rt && rt.get("forma") != null) {
            String f = String.valueOf(rt.get("forma")).toUpperCase(Locale.ROOT);
            if (f.contains("REAL")) {
                r.put("regime", "NORMAL");
                r.put("regimeApuracao", "LUCRO_REAL");
            } else if (f.contains("PRESUMIDO")) {
                r.put("regime", "NORMAL");
                r.put("regimeApuracao", "LUCRO_PRESUMIDO");
            } else if (f.contains("ARBITRADO")) {
                r.put("regime", "NORMAL");
                r.put("regimeApuracao", "LUCRO_ARBITRADO");
            }
        } else if (Boolean.FALSE.equals(sn.get("optanteSimples"))) {
            r.put("regime", "NORMAL");
        }
        return r.isEmpty() ? null : r;
    }

    private static Map<String, Object> cnae(String codigo, String descricao) {
        if (codigo == null || codigo.isBlank() || "0".equals(codigo)) {
            return null;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("codigo", codigo);
        c.put("descricao", descricao);
        return c;
    }

    private static String texto(JsonNode j, String campo) {
        JsonNode n = j.get(campo);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asString("").trim();
        return s.isEmpty() ? null : s;
    }
}
