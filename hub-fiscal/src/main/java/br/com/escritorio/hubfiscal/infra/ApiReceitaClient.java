package br.com.escritorio.hubfiscal.infra;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.config.HubProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente HTTP das calculadoras oficiais (Regime Geral e Simples Nacional).
 * <p>
 * Não lança exceção em erros HTTP: devolve sempre {@link ApiResposta}, para que
 * o chamador decida (o hub mostra os erros da calculadora ao usuário). Quando a
 * calculadora está fora do ar, devolve status 0 com uma mensagem explicativa.
 */
@Component
public class ApiReceitaClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HubProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ApiReceitaClient(HubProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public String baseUrl(Api api) {
        String base = api == Api.RG ? props.apiRegimeGeral() : props.apiSimplesNacional();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Executa uma chamada genérica (usada pelo proxy e pelos serviços). */
    public ApiResposta chamar(Api api, String metodo, String caminhoEQuery, byte[] corpo, String contentType) {
        String caminho = caminhoEQuery.startsWith("/") ? caminhoEQuery : "/" + caminhoEQuery;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl(api) + caminho)).timeout(TIMEOUT);
        boolean temCorpo = corpo != null && corpo.length > 0;
        if (temCorpo) {
            req.header("Content-Type", contentType == null ? "application/json" : contentType);
        }
        req.method(metodo.toUpperCase(),
                temCorpo ? HttpRequest.BodyPublishers.ofByteArray(corpo) : HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("Content-Type").orElse("application/json");
            String aviso = resp.headers().firstValue("x-warning-dados-simulados").orElse(null);
            return new ApiResposta(resp.statusCode(), resp.body(), ct, aviso);
        } catch (IOException e) {
            return indisponivel(api, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return indisponivel(api, e);
        }
    }

    private ApiResposta indisponivel(Api api, Exception e) {
        String nome = api == Api.RG ? "Regime Geral" : "Simples Nacional";
        String msg = "{\"title\":\"Calculadora indisponível\",\"detail\":\"A calculadora oficial do " + nome
                + " não respondeu em " + baseUrl(api) + ". Verifique se ela está em execução. ("
                + e.getClass().getSimpleName() + ")\"}";
        return new ApiResposta(0, msg.getBytes(StandardCharsets.UTF_8), "application/json", null);
    }

    public ApiResposta get(Api api, String caminhoEQuery) {
        return chamar(api, "GET", caminhoEQuery, null, null);
    }

    public ApiResposta postJson(Api api, String caminho, Object corpo) {
        byte[] bytes = mapper.writeValueAsBytes(corpo);
        return chamar(api, "POST", caminho, bytes, "application/json");
    }

    /** Converte o corpo JSON em Map/List/valores simples (null se vazio ou inválido). */
    public Object json(ApiResposta resposta) {
        if (resposta.corpo() == null || resposta.corpo().length == 0) {
            return null;
        }
        try {
            return mapper.readValue(resposta.corpo(), Object.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Object> jsonLista(ApiResposta resposta) {
        Object o = json(resposta);
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> jsonObjeto(ApiResposta resposta) {
        Object o = json(resposta);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
