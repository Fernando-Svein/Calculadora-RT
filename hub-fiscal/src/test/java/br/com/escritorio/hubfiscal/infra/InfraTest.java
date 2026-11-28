package br.com.escritorio.hubfiscal.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;

/** Fila de lotes, guarda de resultados e execução paralela. */
class InfraTest {

    static HubProperties props(int lotesSimultaneos, int resultadosPorUsuario) {
        return new HubProperties("http://127.0.0.1:1/api", "http://127.0.0.1:1/api", null, 4, 60, null, null, null, null,
                null, null, null, lotesSimultaneos, resultadosPorUsuario);
    }

    @Test
    void resultadoSoParaODonoEComLimitePorUsuario() {
        ResultadoStore store = new ResultadoStore(props(2, 2));
        String a = store.guardar("A", 1);
        String b = store.guardar("B", 1);
        String outro = store.guardar("X", 2);
        String c = store.guardar("C", 1);
        assertThrows(ErroNegocio.class, () -> store.obter(a, String.class, 1), "o mais antigo sai ao passar do limite");
        assertEquals("B", store.obter(b, String.class, 1));
        assertEquals("C", store.obter(c, String.class, 1));
        assertEquals("X", store.obter(outro, String.class, 2), "o limite é por usuário");
        assertThrows(ErroNegocio.class, () -> store.obter(b, String.class, 2), "outro usuário não acessa");
    }

    @Test
    void filaSeguraOSegundoLoteEMostraOAndamento() throws Exception {
        FilaLotes fila = new FilaLotes(props(1, 5));
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        CompletableFuture<String> primeiro = CompletableFuture.supplyAsync(() -> fila.executar(1, "tarefa-um-123", a -> {
            a.etapa("Analisando", 10);
            a.avancar();
            dentro.countDown();
            await(liberar);
            return "1";
        }));
        assertTrue(dentro.await(5, TimeUnit.SECONDS));
        assertEquals(Map.of("etapa", "Analisando", "feitos", 1, "total", 10), fila.andamento(1, "tarefa-um-123").orElseThrow());
        assertTrue(fila.andamento(2, "tarefa-um-123").isEmpty(), "andamento é só do dono");

        CompletableFuture<String> segundo = CompletableFuture.supplyAsync(() -> fila.executar(1, "tarefa-dois-123", a -> "2"));
        for (int i = 0; i < 50 && fila.andamento(1, "tarefa-dois-123").isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertTrue(String.valueOf(fila.andamento(1, "tarefa-dois-123").orElseThrow().get("etapa")).startsWith("Na fila"));
        liberar.countDown();
        assertEquals("1", primeiro.get(5, TimeUnit.SECONDS));
        assertEquals("2", segundo.get(5, TimeUnit.SECONDS));
        assertTrue(fila.andamento(1, "tarefa-um-123").isEmpty(), "andamento some quando o lote termina");
    }

    @Test
    void filaIgnoraCodigoDeTarefaInvalido() {
        FilaLotes fila = new FilaLotes(props(1, 5));
        assertEquals("ok", fila.executar(1, "../x", a -> "ok"));
        assertTrue(fila.andamento(1, "../x").isEmpty());
    }

    @Test
    void paraleloMantemAOrdemEContaOsItens() {
        Andamento a = new Andamento();
        a.etapa("Teste", 5);
        List<Integer> r = Paralelo.mapear(List.of(1, 2, 3, 4, 5), 2, x -> x * 10, a);
        assertEquals(List.of(10, 20, 30, 40, 50), r);
        assertEquals(5, a.resumo().get("feitos"));
    }

    @Test
    void paraleloRelancaAExcecaoDoItem() {
        IllegalStateException e = new IllegalStateException("falhou");
        assertSame(e, assertThrows(IllegalStateException.class, () -> Paralelo.mapear(List.of(1), 1, x -> {
            throw e;
        })));
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
