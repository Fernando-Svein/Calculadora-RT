package br.com.escritorio.hubfiscal.cenario;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Cenário de simulação da CBS (seletor no topo das telas). As telas calculam na calculadora oficial
 * e enviam a resposta para cá quando o cenário está ligado; não há chamada às calculadoras aqui.
 */
@RestController
@RequestMapping("/hub/cenario-cbs")
public class CenarioCbsController {

    private final CenarioCbsService cenario;

    public CenarioCbsController(CenarioCbsService cenario) {
        this.cenario = cenario;
    }

    /** Configuração do cenário para montar o seletor ({@code disponivel=false} esconde o seletor). */
    @GetMapping
    public Map<String, Object> configuracao() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponivel", cenario.disponivel());
        if (cenario.disponivel()) {
            m.put("aliquota", cenario.configuracao().aliquota());
            m.put("rotulo", cenario.configuracao().rotulo());
        }
        return m;
    }

    /**
     * Recebe a resposta oficial do cálculo de operação (Regime Geral) e devolve a CBS no cenário.
     * {@code data}: data do fato gerador da operação (define a alíquota de referência oficial).
     */
    @PostMapping("/regime-geral")
    public JsonNode regimeGeral(@RequestBody byte[] respostaOficial, @RequestParam LocalDate data) {
        return cenario.regimeGeral(respostaOficial, data);
    }

    /** Recebe a resposta oficial do cálculo de pedágio e devolve a CBS no cenário. */
    @PostMapping("/pedagio")
    public JsonNode pedagio(@RequestBody byte[] respostaOficial) {
        return cenario.pedagio(respostaOficial);
    }
}
