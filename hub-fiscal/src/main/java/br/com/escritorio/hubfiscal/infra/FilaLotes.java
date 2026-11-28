package br.com.escritorio.hubfiscal.infra;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.config.HubProperties;

/**
 * Limita quantos lotes rodam ao mesmo tempo ({@code hub.lotes-simultaneos}) e guarda o andamento de cada um.
 * <p>
 * Cada lote lê os arquivos inteiros na memória (SPED de centenas de MB): sem o limite, alguns envios grandes
 * simultâneos esgotavam a memória do hub e derrubavam o processamento de todos. Quem chega com as vagas
 * ocupadas espera na fila, e a tela mostra "Na fila".
 */
@Component
public class FilaLotes {

    /** Código que a tela gera para acompanhar o envio (UUID ou parecido). */
    private static final Pattern TAREFA = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final Semaphore vagas;
    private final Map<String, Andamento> andamentos = new ConcurrentHashMap<>();

    public FilaLotes(HubProperties props) {
        this.vagas = new Semaphore(props.lotesSimultaneos(), true);
    }

    /**
     * Executa {@code trabalho} quando houver vaga. Com {@code tarefa}, o andamento fica disponível em
     * {@link #andamento} até o fim do processamento.
     */
    public <T> T executar(long usuario, String tarefa, Function<Andamento, T> trabalho) {
        Andamento a = new Andamento();
        String chave = tarefa != null && TAREFA.matcher(tarefa).matches() ? usuario + ":" + tarefa : null;
        if (chave != null) {
            andamentos.put(chave, a);
        }
        try {
            if (!vagas.tryAcquire()) {
                a.etapa("Na fila: aguardando outro processamento terminar", 0);
                vagas.acquireUninterruptibly();
            }
            try {
                return trabalho.apply(a);
            } finally {
                vagas.release();
            }
        } finally {
            if (chave != null) {
                andamentos.remove(chave);
            }
        }
    }

    /** Andamento do lote {@code tarefa} do usuário (vazio se já terminou, se não existe ou se é de outro usuário). */
    public Optional<Map<String, Object>> andamento(long usuario, String tarefa) {
        return Optional.ofNullable(andamentos.get(usuario + ":" + tarefa)).map(Andamento::resumo);
    }
}
