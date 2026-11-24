package br.com.escritorio.hubfiscal.proxy;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Repassa chamadas das telas do hub para as calculadoras oficiais.
 * <p>
 * {@code /proxy/rg/...} → Regime Geral; {@code /proxy/sn/...} → Simples Nacional.
 * Assim os computadores do escritório só precisam acessar o hub (porta 8090),
 * e as calculadoras podem ficar acessíveis apenas na máquina servidora.
 */
@RestController
public class ProxyController {

    private static final String AVISO = "x-warning-dados-simulados";

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
        String prefixo = request.getContextPath() + "/proxy/" + api;
        String caminho = request.getRequestURI().substring(prefixo.length());
        if (request.getQueryString() != null) {
            caminho += "?" + request.getQueryString();
        }
        byte[] corpo = request.getInputStream().readAllBytes();
        ApiResposta resp = client.chamar(alvo, request.getMethod(), caminho, corpo, request.getContentType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resp.contentType()));
        if (resp.aviso() != null) {
            headers.add(AVISO, resp.aviso());
            headers.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, AVISO);
        }
        int status = resp.status() == 0 ? HttpStatus.BAD_GATEWAY.value() : resp.status();
        return ResponseEntity.status(status).headers(headers).body(resp.corpo());
    }
}
