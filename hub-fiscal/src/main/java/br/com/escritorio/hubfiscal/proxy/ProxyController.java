package br.com.escritorio.hubfiscal.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;
import br.com.escritorio.hubfiscal.seguranca.Ferramentas;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Repassa chamadas das telas do hub para as calculadoras oficiais, sem alterar
 * método, caminho, query, corpo nem Content-Type.
 * <p>
 * {@code /proxy/rg/...} → Regime Geral; {@code /proxy/sn/...} → Simples Nacional.
 * Assim os computadores do escritório só precisam acessar o hub (porta 8090),
 * e as calculadoras ficam acessíveis apenas na máquina servidora.
 */
@RestController
public class ProxyController {

    private final ApiReceitaClient client;

    public ProxyController(ApiReceitaClient client) {
        this.client = client;
    }

    @RequestMapping("/proxy/{api}/**")
    public ResponseEntity<byte[]> repassar(@PathVariable String api, HttpServletRequest request) throws IOException {
        Api alvo;
        try {
            alvo = Api.of(api);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        // repassa exatamente o caminho que passou pela checagem de permissão (ver Ferramentas.caminhoCanonico),
        // recodificado para a URL: variações como /proxy/RG/..., /proxy/regime-geral/... ou %2D não escapam da regra
        String canonico = Ferramentas.caminhoCanonico(request);
        String prefixo = "/proxy/" + (alvo == Api.RG ? "rg" : "sn");
        if (!canonico.startsWith(prefixo)) {
            return ResponseEntity.notFound().build();
        }
        String caminho = UriUtils.encodePath(canonico.substring(prefixo.length()), StandardCharsets.UTF_8);
        if (request.getQueryString() != null) {
            caminho += "?" + request.getQueryString();
        }
        byte[] corpo = request.getInputStream().readAllBytes();
        ApiResposta resp = client.chamar(alvo, request.getMethod(), caminho, corpo, request.getContentType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resp.contentType()));
        if (resp.aviso() != null) {
            headers.add(EndpointsReceita.CABECALHO_DADOS_SIMULADOS, resp.aviso());
        }
        int status = resp.status() == 0 ? HttpStatus.BAD_GATEWAY.value() : resp.status();
        return ResponseEntity.status(status).headers(headers).body(resp.corpo());
    }
}
