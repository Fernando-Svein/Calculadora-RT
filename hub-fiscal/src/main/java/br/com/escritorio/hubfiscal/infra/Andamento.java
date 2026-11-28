package br.com.escritorio.hubfiscal.infra;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Andamento de um processamento em lote (etapa atual e quantos itens dela já terminaram). A tela consulta
 * enquanto espera a resposta do envio; os serviços só avisam o que estão fazendo, sem saber quem acompanha.
 */
public final class Andamento {

    private volatile String etapa = "Preparando";
    private volatile int total;
    private final AtomicInteger feitos = new AtomicInteger();

    /** Começa uma etapa com {@code total} itens (0 quando não há contagem, ex.: leitura dos arquivos). */
    public void etapa(String descricao, int total) {
        this.feitos.set(0);
        this.total = Math.max(total, 0);
        this.etapa = descricao;
    }

    /** Mais um item da etapa atual concluído (chamado das threads do processamento). */
    public void avancar() {
        feitos.incrementAndGet();
    }

    public Map<String, Object> resumo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("etapa", etapa);
        m.put("feitos", Math.min(feitos.get(), total));
        m.put("total", total);
        return m;
    }
}
