package br.com.escritorio.hubfiscal.sistema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Path arquivoAgendamento;
    private final AtualizacaoManual atualizacaoManual;

    public SistemaController(ApiReceitaClient api, BaseOficial base, HubProperties props, AtualizacaoManual atualizacaoManual) {
        this.api = api;
        this.base = base;
        this.atualizacaoManual = atualizacaoManual;
        this.arquivoAtualizacao = Path.of(props.arquivoUltimaAtualizacao());
        // ao lado do resultado da verificação (pasta logs): gravado pelos scripts ao agendar a atualização diária
        Path pasta = this.arquivoAtualizacao.toAbsolutePath().getParent();
        this.arquivoAgendamento = pasta == null ? Path.of("agendamento-atualizacao.json") : pasta.resolve("agendamento-atualizacao.json");
    }

    /** Usado pelo topo das telas (indicadores) e pela tela "Situação do sistema". */
    @GetMapping("/hub/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regimeGeral", statusApi(Api.RG, EndpointsReceita.RG_VERSAO));
        r.put("simplesNacional", statusApi(Api.SN, EndpointsReceita.SN_VERSAO));
        r.put("bancoOficial", base.versao());
        r.put("dataServidor", LocalDate.now().toString());
        Object atualizacao = ultimaAtualizacao();
        r.put("atualizacao", atualizacao);
        r.put("base", datasDaBase(atualizacao));
        r.put("atualizacaoManual", atualizacaoManual.situacao());
        // a tela mostra os comandos do sistema do servidor (.bat no Windows, hub.sh no Linux)
        r.put("servidorLinux", !System.getProperty("os.name", "").toLowerCase().contains("win"));
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

    /**
     * Datas exibidas no topo das telas:
     * <ul>
     * <li>última atualização: a mais recente entre a geração do banco oficial (arquivo recriado a cada versão
     * nova das calculadoras) e a última verificação diária bem-sucedida (que confirmou a base em dia);</li>
     * <li>próxima atualização: o próximo horário da verificação diária, lido de
     * {@code logs/agendamento-atualizacao.json} (gravado ao agendar: {@code hub.ps1 agendar} no Windows,
     * {@code instalar.sh}/{@code hub.sh} no Linux). Sem esse arquivo, a atualização automática não está agendada.</li>
     * </ul>
     */
    private Map<String, Object> datasDaBase(Object atualizacao) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDateTime banco = null;
        try {
            if (base.disponivel()) {
                banco = LocalDateTime.ofInstant(Files.getLastModifiedTime(base.arquivo()).toInstant(), ZoneId.systemDefault())
                        .truncatedTo(ChronoUnit.SECONDS);
            }
        } catch (Exception e) {
            // sem a data do arquivo
        }
        LocalDateTime verificada = null;
        if (atualizacao instanceof Map<?, ?> a && a.get("data") != null
                && List.of("sem-novidade", "atualizado").contains(String.valueOf(a.get("resultado")))) {
            try {
                verificada = LocalDateTime.parse(String.valueOf(a.get("data")));
            } catch (Exception e) {
                // data em formato inesperado
            }
        }
        LocalDateTime ultima = banco == null ? verificada
                : verificada == null || banco.isAfter(verificada) ? banco : verificada;
        m.put("ultimaAtualizacao", ultima == null ? null : ultima.toString());
        m.put("bancoGeradoEm", banco == null ? null : banco.toString());
        m.put("verificadaEm", verificada == null ? null : verificada.toString());
        LocalTime horario = horarioAgendado();
        m.put("agendada", horario != null);
        m.put("horario", horario == null ? null : horario.toString());
        if (horario != null) {
            LocalDateTime agora = LocalDateTime.now();
            LocalDateTime proxima = agora.toLocalDate().atTime(horario);
            if (!proxima.isAfter(agora)) {
                proxima = proxima.plusDays(1);
            }
            m.put("proximaAtualizacao", proxima.toString());
        } else {
            m.put("proximaAtualizacao", null);
        }
        return m;
    }

    /** Horário da verificação diária (arquivo de agendamento), ou null se não estiver agendada. */
    private LocalTime horarioAgendado() {
        if (!Files.isRegularFile(arquivoAgendamento)) {
            return null;
        }
        try {
            String json = Files.readString(arquivoAgendamento, StandardCharsets.UTF_8).replace("﻿", "");
            Object h = json.isBlank() ? null : api.mapper().readValue(json, Map.class).get("horario");
            return h == null ? null : LocalTime.parse(String.valueOf(h).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Resultado da última verificação automática, gravado por {@code scripts\hub.ps1 verificar} (ou null). */
    private Object ultimaAtualizacao() {
        if (!Files.isRegularFile(arquivoAtualizacao)) {
            return null;
        }
        try {
            // o PowerShell grava o JSON com BOM (U+FEFF), que o leitor de JSON não aceita
            String json = Files.readString(arquivoAtualizacao, StandardCharsets.UTF_8).replace("﻿", "");
            if (json.isBlank()) {
                return null; // arquivo criado mas ainda sem resultado: tratado como "nenhuma verificação"
            }
            return api.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("resultado", "falha", "mensagem", "Não foi possível ler " + arquivoAtualizacao + ": " + e.getMessage());
        }
    }
}
