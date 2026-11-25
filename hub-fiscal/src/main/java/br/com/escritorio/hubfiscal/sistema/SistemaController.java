package br.com.escritorio.hubfiscal.sistema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.catalogo.BaseOficial;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;

/** Situação do sistema: calculadoras oficiais, banco oficial e última atualização automática. */
@RestController
public class SistemaController {

    private final ApiReceitaClient api;
    private final BaseOficial base;
    private final Path arquivoAtualizacao;

    public SistemaController(ApiReceitaClient api, BaseOficial base, HubProperties props) {
        this.api = api;
        this.base = base;
        this.arquivoAtualizacao = Path.of(props.arquivoUltimaAtualizacao());
    }

    /** Usado pelo topo das telas (indicadores) e pela tela "Situação do sistema". */
    @GetMapping("/hub/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regimeGeral", statusApi(Api.RG, EndpointsReceita.RG_VERSAO));
        r.put("simplesNacional", statusApi(Api.SN, EndpointsReceita.SN_VERSAO));
        r.put("bancoOficial", base.versao());
        r.put("dataServidor", LocalDate.now().toString());
        r.put("atualizacao", ultimaAtualizacao());
        return r;
    }

    private Map<String, Object> statusApi(Api alvo, String caminho) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", api.baseUrl(alvo));
        ApiResposta r = api.get(alvo, caminho);
        m.put("online", r.ok());
        m.put("detalhes", r.ok() ? api.json(r) : r.texto());
        return m;
    }

    /** Resultado da última verificação automática, gravado por {@code scripts\hub.ps1 verificar} (ou null). */
    private Object ultimaAtualizacao() {
        if (!Files.isRegularFile(arquivoAtualizacao)) {
            return null;
        }
        try {
            // o PowerShell grava o JSON com BOM (U+FEFF), que o leitor de JSON não aceita
            String json = Files.readString(arquivoAtualizacao, StandardCharsets.UTF_8).replace("﻿", "");
            return api.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("resultado", "falha", "mensagem", "Não foi possível ler " + arquivoAtualizacao + ": " + e.getMessage());
        }
    }
}
