package br.com.escritorio.hubfiscal.infra;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Execução paralela com limite de simultaneidade, usada nos lotes para não sobrecarregar
 * as calculadoras oficiais (limite = {@code hub.paralelismo}).
 */
public final class Paralelo {

    private Paralelo() {
    }

    /**
     * Aplica {@code funcao} a cada item em threads virtuais, com no máximo {@code limite}
     * execuções ao mesmo tempo.
     *
     * @return os resultados na mesma ordem dos itens
     * @throws RuntimeException a exceção lançada pela função em algum item (relançada como está)
     */
    public static <T, R> List<R> mapear(Collection<T> itens, int limite, Function<T, R> funcao) {
        return mapear(itens, limite, funcao, null);
    }

    /** Igual a {@link #mapear(Collection, int, Function)}, avisando {@code andamento} a cada item concluído. */
    public static <T, R> List<R> mapear(Collection<T> itens, int limite, Function<T, R> funcao, Andamento andamento) {
        Semaphore vagas = new Semaphore(limite);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> tarefas = new ArrayList<>(itens.size());
            for (T item : itens) {
                tarefas.add(executor.submit(() -> {
                    vagas.acquireUninterruptibly();
                    try {
                        return funcao.apply(item);
                    } finally {
                        vagas.release();
                        if (andamento != null) {
                            andamento.avancar();
                        }
                    }
                }));
            }
            List<R> resultados = new ArrayList<>(tarefas.size());
            for (Future<R> t : tarefas) {
                resultados.add(aguardar(t));
            }
            return resultados;
        }
    }

    private static <R> R aguardar(Future<R> tarefa) {
        try {
            return tarefa.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
