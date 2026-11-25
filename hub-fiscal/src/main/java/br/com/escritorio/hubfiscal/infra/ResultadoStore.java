package br.com.escritorio.hubfiscal.infra;

import java.time.Duration;
import java.time.Instant;
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

    public ResultadoStore(HubProperties props) {
        this.retencao = Duration.ofMinutes(props.retencaoResultadosMinutos());
    }

    /** Guarda o resultado do usuário {@code dono} e devolve o identificador para download. */
    public String guardar(Object valor, long dono) {
        String id = UUID.randomUUID().toString();
        dados.put(id, new Entrada(valor, dono, Instant.now()));
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
