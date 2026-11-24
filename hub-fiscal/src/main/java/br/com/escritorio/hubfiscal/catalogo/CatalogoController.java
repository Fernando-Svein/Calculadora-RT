package br.com.escritorio.hubfiscal.catalogo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Nomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;

/** Consultas do hub que combinam o banco oficial com as APIs. */
@RestController
@RequestMapping("/hub")
public class CatalogoController {

    private final CatalogoService catalogo;
    private final BaseOficial base;
    private final ApiReceitaClient api;

    public CatalogoController(CatalogoService catalogo, BaseOficial base, ApiReceitaClient api) {
        this.catalogo = catalogo;
        this.base = base;
        this.api = api;
    }

    private static LocalDate dataOuHoje(LocalDate data) {
        return data == null ? LocalDate.now() : data;
    }

    /** Classificações possíveis para um NCM (bens). */
    @GetMapping("/ncm/{ncm}")
    public AnaliseNomenclatura ncm(@PathVariable String ncm, @RequestParam(required = false) LocalDate data,
            @RequestParam(required = false) Integer tipoDfe) {
        return catalogo.analisar(Tipo.NCM, ncm, dataOuHoje(data), tipoDfe);
    }

    /** Classificações possíveis para uma NBS (serviços). */
    @GetMapping("/nbs/{nbs}")
    public AnaliseNomenclatura nbs(@PathVariable String nbs, @RequestParam(required = false) LocalDate data,
            @RequestParam(required = false) Integer tipoDfe) {
        return catalogo.analisar(Tipo.NBS, nbs, dataOuHoje(data), tipoDfe);
    }

    /** Busca de NCM/NBS por código ou por texto da descrição. */
    @GetMapping("/busca/{tipo}")
    public List<Nomenclatura> buscar(@PathVariable String tipo, @RequestParam String q,
            @RequestParam(required = false) LocalDate data, @RequestParam(defaultValue = "60") int limite) {
        Tipo t = Tipo.valueOf(tipo.toUpperCase());
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        return base.buscar(t, q, dataOuHoje(data), Math.min(limite, 300));
    }

    /** Detalhe de uma classificação, com os NCM/NBS do anexo. */
    @GetMapping("/classificacao/{cClassTrib}")
    public Map<String, Object> classificacao(@PathVariable String cClassTrib,
            @RequestParam(required = false) LocalDate data) {
        return catalogo.detalheClassificacao(cClassTrib, dataOuHoje(data));
    }

    /** Situação das calculadoras e do banco oficial. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regimeGeral", statusApi(Api.RG, "/calculadora/dados-abertos/versao"));
        r.put("simplesNacional", statusApi(Api.SN, "/versao"));
        r.put("bancoOficial", base.versao());
        r.put("dataServidor", LocalDate.now().toString());
        r.put("atualizacao", ultimaAtualizacao());
        return r;
    }

    /** Resultado da última verificação automática (gravado por scripts\hub.ps1 verificar). */
    private Object ultimaAtualizacao() {
        Path arquivo = Path.of("..", "logs", "ultima-atualizacao.json");
        if (!Files.isRegularFile(arquivo)) return null;
        try {
            String json = Files.readString(arquivo, StandardCharsets.UTF_8).replace("﻿", "");
            return api.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("resultado", "falha", "mensagem", "Não foi possível ler " + arquivo + ": " + e.getMessage());
        }
    }

    private Map<String, Object> statusApi(Api alvo, String caminho) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", api.baseUrl(alvo));
        ApiResposta r = api.get(alvo, caminho);
        m.put("online", r.ok());
        m.put("detalhes", r.ok() ? api.json(r) : r.texto());
        return m;
    }
}
