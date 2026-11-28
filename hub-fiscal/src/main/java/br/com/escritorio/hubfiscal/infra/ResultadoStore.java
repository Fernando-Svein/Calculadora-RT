package br.com.escritorio.hubfiscal.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;

/**
 * Guarda em memória, por algumas horas, os resultados de processamentos em
 * lote, para que a tela mostre o resultado e o usuário baixe a planilha sem
 * reprocessar os arquivos. Cada resultado só pode ser baixado por quem o gerou
 * (os dados vêm de notas e SPED de clientes).
 */
@Component
public class ResultadoStore {

    private record Entrada(Object valor, long dono, Instant criado) {
    }

    private final Map<String, Entrada> dados = new ConcurrentHashMap<>();
    private final Duration retencao;
    private final int porUsuario;

    public ResultadoStore(HubProperties props) {
        this.retencao = Duration.ofMinutes(props.retencaoResultadosMinutos());
        this.porUsuario = props.resultadosPorUsuario();
    }

    /**
     * Guarda o resultado do usuário {@code dono} e devolve o identificador para download. Cada usuário fica com no
     * máximo {@code hub.resultados-por-usuario} resultados: um lote de SPED grande ocupa centenas de MB, e sem o
     * limite vários lotes seguidos ficavam todos na memória até a retenção vencer.
     */
    public synchronized String guardar(Object valor, long dono) {
        String id = UUID.randomUUID().toString();
        dados.put(id, new Entrada(valor, dono, Instant.now()));
        List<String> doDono = dados.entrySet().stream().filter(e -> e.getValue().dono() == dono)
                .sorted(Comparator.comparing(e -> e.getValue().criado())).map(Map.Entry::getKey).toList();
        doDono.subList(0, Math.max(0, doDono.size() - porUsuario)).forEach(dados::remove);
        return id;
    }

    public <T> T obter(String id, Class<T> tipo, long dono) {
        Entrada e = dados.get(id);
        // resultado de outro usuário responde igual a inexistente (não confirma que o id existe)
        if (e == null || e.dono() != dono || !tipo.isInstance(e.valor())) {
            throw new ErroNegocio("Resultado não encontrado ou expirado. Processe os arquivos novamente.");
        }
        return tipo.cast(e.valor());
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void limpar() {
        Instant limite = Instant.now().minus(retencao);
        dados.entrySet().removeIf(e -> e.getValue().criado().isBefore(limite));
    }
}
