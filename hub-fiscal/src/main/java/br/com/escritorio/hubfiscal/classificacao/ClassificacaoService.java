package br.com.escritorio.hubfiscal.classificacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.AnaliseNomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.catalogo.Candidata.Categoria;
import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;
import br.com.escritorio.hubfiscal.infra.J;

/**
 * Classificação tributária em lote (cClassTrib/CST) a partir de SPED e XML.
 * <p>
 * Para cada produto/serviço do cadastro: valida o NCM/NBS, lista as
 * classificações vinculadas por anexo, sugere a mais provável, confere a
 * classificação já usada nas notas e aponta o que precisa de revisão humana.
 */
@Service
public class ClassificacaoService {

    public static final Map<String, String> ROTULOS = new LinkedHashMap<>();
    static {
        ROTULOS.put("DIVERGENTE", "Divergente");
        ROTULOS.put("REVISAR", "Revisar");
        ROTULOS.put("SUGERIDO", "Sugerido");
        ROTULOS.put("CONFERIDO", "Conferido");
        ROTULOS.put("INVALIDO", "NCM/NBS inválido");
        ROTULOS.put("SEM_NOMENCLATURA", "Sem NCM/NBS");
    }

    private static final Map<String, String> TIPOS_ITEM_SPED = Map.ofEntries(
            Map.entry("00", "00 – Mercadoria para revenda"), Map.entry("01", "01 – Matéria-prima"),
            Map.entry("02", "02 – Embalagem"), Map.entry("03", "03 – Produto em processo"),
            Map.entry("04", "04 – Produto acabado"), Map.entry("05", "05 – Subproduto"),
            Map.entry("06", "06 – Produto intermediário"), Map.entry("07", "07 – Material de uso e consumo"),
            Map.entry("08", "08 – Ativo imobilizado"), Map.entry("09", "09 – Serviços"),
            Map.entry("10", "10 – Outros insumos"), Map.entry("99", "99 – Outras"));

    private final CatalogoService catalogo;
    private final HubProperties props;

    public ClassificacaoService(CatalogoService catalogo, HubProperties props) {
        this.catalogo = catalogo;
        this.props = props;
    }

    /** Agregado de um produto/serviço ao longo de todos os arquivos. */
    private static class Agregado {
        String donoCnpj;
        String donoNome;
        String codigo;
        String descricao;
        String tipoItem;
        final Set<String> ncms = new LinkedHashSet<>();
        final Set<String> nbss = new LinkedHashSet<>();
        final Set<String> origens = new TreeSet<>();
        final Set<String> cfopsSaida = new TreeSet<>();
        final Set<String> cfopsEntrada = new TreeSet<>();
        final Map<Integer, Integer> modelos = new HashMap<>();
        final Map<String, Integer> cClassTribAtual = new HashMap<>();
        final Map<String, Integer> cstAtual = new HashMap<>();
        int ocorrencias;
        BigDecimal valor = BigDecimal.ZERO;

        void absorver(ItemFiscal it) {
            if (descricao == null && it.descricao != null) {
                descricao = it.descricao;
            }
            if (tipoItem == null && it.tipoItemSped != null) {
                tipoItem = it.tipoItemSped;
            }
            if (donoNome == null) {
                donoNome = it.donoNome;
            }
            if (it.ncm != null) {
                ncms.add(it.ncm);
            }
            if (it.nbs != null) {
                nbss.add(it.nbs);
            }
            origens.add(it.origem + ("0200".equals(it.registro) ? " (cadastro)" : ""));
            if (!"0200".equals(it.registro)) {
                ocorrencias++;
                if (it.valor != null) {
                    valor = valor.add(it.valor);
                }
                if (it.cfop != null) {
                    if (Cfops.saida(it.cfop)) {
                        cfopsSaida.add(it.cfop);
                    } else if (Cfops.entrada(it.cfop)) {
                        cfopsEntrada.add(it.cfop);
                    }
                }
                if (it.modelo != null && (it.modelo == 55 || it.modelo == 65 || it.modelo == 91)) {
                    modelos.merge(it.modelo, 1, Integer::sum);
                }
                if (it.cClassTribAtual() != null) {
                    cClassTribAtual.merge(it.cClassTribAtual(), 1, Integer::sum);
                }
                if (it.cstAtual() != null) {
                    cstAtual.merge(it.cstAtual(), 1, Integer::sum);
                }
            }
        }
    }

    public ResultadoClassificacao classificar(Importacao imp, LocalDate data) {
        Map<String, Agregado> agregados = new LinkedHashMap<>();
        List<ItemFiscal> todos = new ArrayList<>();
        todos.addAll(imp.cadastroSped());
        todos.addAll(imp.movimentosSped());
        todos.addAll(imp.itensXml());
        for (ItemFiscal it : todos) {
            if (it.codigo == null && it.ncm == null && it.nbs == null) {
                continue;
            }
            String dono = it.donoCnpj == null ? "" : it.donoCnpj;
            String chave = dono + "|" + (it.codigo != null ? it.codigo : "NCM:" + it.ncm + "|" + it.descricao);
            Agregado a = agregados.computeIfAbsent(chave, k -> {
                Agregado n = new Agregado();
                n.donoCnpj = it.donoCnpj;
                n.codigo = it.codigo;
                return n;
            });
            a.absorver(it);
        }

        // análises distintas (NCM/NBS × modelo) em paralelo
        Map<String, AnaliseNomenclatura> analises = new ConcurrentHashMap<>();
        Set<String> chaves = new LinkedHashSet<>();
        for (Agregado a : agregados.values()) {
            String k = chaveAnalise(a);
            if (k != null) {
                chaves.add(k);
            }
        }
        analisarEmParalelo(chaves, data, analises);

        ResultadoClassificacao res = new ResultadoClassificacao();
        res.dataReferencia = data.toString();
        res.geradoEm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        res.arquivos.putAll(imp.arquivos);
        res.avisos.addAll(imp.avisos);
        Set<String> contribuintes = new LinkedHashSet<>();
        imp.speds.forEach(s -> contribuintes.add(s.nome() + " – CNPJ " + s.cnpj() + " (" + s.tipo() + ", "
                + s.dataInicial() + " a " + s.dataFinal() + ")"));
        res.contribuintes.addAll(contribuintes);

        Map<String, Map<String, Object>> tabela = catalogo.classificacoes(data);
        for (Agregado a : agregados.values()) {
            String k = chaveAnalise(a);
            res.linhas.add(montarLinha(a, k == null ? null : analises.get(k), tabela, data));
        }
        res.linhas.sort(Comparator.comparing((LinhaClassificacao l) -> ordemStatus(l.status))
                .thenComparing(l -> l.descricao == null ? "" : l.descricao));
        res.totalItens = res.linhas.size();
        for (String s : ROTULOS.keySet()) {
            res.contagemStatus.put(s, 0);
        }
        res.linhas.forEach(l -> res.contagemStatus.merge(l.status, 1, Integer::sum));
        return res;
    }

    private static int ordemStatus(String s) {
        int i = 0;
        for (String k : ROTULOS.keySet()) {
            if (k.equals(s)) {
                return i;
            }
            i++;
        }
        return 99;
    }

    /** "NCM|10063021|55" ou "NBS|122011200|91"; null se não houver nomenclatura. */
    private static String chaveAnalise(Agregado a) {
        Integer modelo = a.modelos.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(null);
        if (!a.nbss.isEmpty()) {
            return "NBS|" + a.nbss.iterator().next() + "|" + (modelo == null ? 91 : modelo);
        }
        if (!a.ncms.isEmpty()) {
            return "NCM|" + a.ncms.iterator().next() + "|" + (modelo == null ? 55 : modelo);
        }
        return null;
    }

    private void analisarEmParalelo(Set<String> chaves, LocalDate data, Map<String, AnaliseNomenclatura> destino) {
        Semaphore sem = new Semaphore(props.paralelismo());
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (String k : chaves) {
                fs.add(ex.submit(() -> {
                    sem.acquireUninterruptibly();
                    try {
                        String[] p = k.split("\\|");
                        destino.put(k, catalogo.analisar(Tipo.valueOf(p[0]), p[1], data, Integer.valueOf(p[2])));
                    } finally {
                        sem.release();
                    }
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable causa = e.getCause() == null ? e : e.getCause();
                    if (causa instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException(causa);
                }
            }
        }
    }

    private LinhaClassificacao montarLinha(Agregado a, AnaliseNomenclatura an, Map<String, Map<String, Object>> tabela,
            LocalDate data) {
        LinhaClassificacao l = new LinhaClassificacao();
        l.donoCnpj = a.donoCnpj;
        l.donoNome = a.donoNome;
        l.codigo = a.codigo;
        l.descricao = a.descricao;
        l.tipoItemSped = a.tipoItem == null ? null : TIPOS_ITEM_SPED.getOrDefault(a.tipoItem, a.tipoItem);
        l.origens = String.join(", ", a.origens);
        l.cfopsSaida = String.join(", ", a.cfopsSaida);
        l.cfopsEntrada = String.join(", ", a.cfopsEntrada);
        l.ocorrencias = a.ocorrencias;
        l.valorTotal = a.valor;
        l.cClassTribAtual = maisFrequente(a.cClassTribAtual);
        l.cstAtual = maisFrequente(a.cstAtual);
        if (a.cClassTribAtual.size() > 1) {
            l.observacoes.add("Notas usam mais de um cClassTrib para o item: " + a.cClassTribAtual.keySet()
                    + " (pode ser correto se as operações forem diferentes).");
        }
        if (a.ncms.size() > 1) {
            l.observacoes.add("Item com mais de um NCM nos arquivos: " + a.ncms + ". Analisado o primeiro.");
        }

        if (an == null) {
            l.status = "SEM_NOMENCLATURA";
            l.observacoes.add("09".equals(a.tipoItem)
                    ? "Serviço sem NBS: o SPED não traz a NBS. Informe a NBS para classificar."
                    : "Item sem NCM/NBS: preencha o NCM no cadastro.");
            return finalizar(l);
        }
        l.tipoNomenclatura = an.tipo();
        l.nomenclatura = an.codigo();
        l.descricaoNomenclatura = an.descricaoCompleta() != null ? an.descricaoCompleta() : an.descricao();
        l.modeloAnalisado = an.tipoDfe();
        l.impostoSeletivo = an.impostoSeletivo();
        if (!an.valida()) {
            l.status = "INVALIDO";
            l.observacoes.add(an.mensagem());
            return finalizar(l);
        }
        l.observacoes.addAll(an.observacoes());

        // candidatas do anexo, compatíveis com o documento
        List<Candidata> especificas = an.especificas().stream().filter(Candidata::dfeCompativel).toList();
        l.alternativas.addAll(an.especificas());
        List<Candidata> principais = especificas.stream().filter(c -> !c.condicional()).toList();
        Candidata sugerida = !principais.isEmpty() ? principais.get(0) : null;
        if (sugerida == null) {
            Map<String, Object> integral = tabela.get("000001");
            if (integral != null) {
                sugerida = catalogo.candidata(integral, null, catalogo.fundamentacoes(data), an.tipoDfe());
            }
        }
        if (sugerida != null) {
            l.cstSugerido = sugerida.cst();
            l.cClassTribSugerido = sugerida.cClassTrib();
            l.descricaoSugerido = sugerida.descricao();
            l.tratamentoSugerido = sugerida.reducaoResumo();
            l.anexoSugerido = sugerida.anexo() == null ? null
                    : "Anexo " + sugerida.anexo() + (sugerida.itemAnexo() == null ? "" : ", item " + sugerida.itemAnexo())
                            + (sugerida.textoAnexo() == null ? "" : " – " + sugerida.textoAnexo());
            l.fundamentacaoSugerido = sugerida.fundamentacao();
        }
        // possibilidades por atividade (principalmente serviços)
        an.situacionais().stream()
                .filter(c -> c.categoria() == Categoria.BENEFICIO_ATIVIDADE)
                .limit(25)
                .forEach(c -> l.outrasPossibilidades.add(c.cClassTrib() + " (" + c.reducaoResumo() + ": "
                        + abreviar(c.descricao(), 70) + ")"));

        // indicações por CFOP
        Set<String> indicados = new LinkedHashSet<>();
        for (String cfop : a.cfopsSaida) {
            Cfops.Indicacao ind = Cfops.indicacao(cfop);
            if (ind != null) {
                l.sugestoesPorCfop.add((ind.cClassTrib() == null ? "" : ind.cClassTrib() + " – ") + ind.descricao());
                if (ind.cClassTrib() != null) {
                    indicados.add(ind.cClassTrib());
                }
            }
        }

        avaliarStatus(l, a, an, especificas, principais, sugerida, indicados, tabela);
        return finalizar(l);
    }

    private void avaliarStatus(LinhaClassificacao l, Agregado a, AnaliseNomenclatura an, List<Candidata> especificas,
            List<Candidata> principais, Candidata sugerida, Set<String> indicados, Map<String, Map<String, Object>> tabela) {
        String atual = l.cClassTribAtual;
        boolean beneficioSugerido = sugerida != null && !"000001".equals(sugerida.cClassTrib());
        List<Candidata> condicionaisAnexo = especificas.stream().filter(Candidata::condicional).toList();

        if (atual != null) {
            Map<String, Object> regAtual = tabela.get(atual);
            if (regAtual == null) {
                l.status = "DIVERGENTE";
                l.observacoes.add("cClassTrib " + atual + " usado nas notas não existe ou não está vigente em "
                        + an.data() + ".");
                return;
            }
            String nomenclaturaAtual = String.valueOf(J.str(regAtual, "nomenclatura"));
            boolean atualViaAnexo = an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(atual));
            boolean atualExigeAnexo = classificacaoExigeAnexo(atual, an);
            if (atualExigeAnexo && !atualViaAnexo) {
                l.status = "DIVERGENTE";
                l.observacoes.add(an.tipo() + " " + an.codigo() + " não consta do anexo do cClassTrib " + atual
                        + " (a calculadora oficial rejeita: erro REG-011).");
                return;
            }
            if (!nomenclaturaAtual.contains(an.tipo()) && !nomenclaturaAtual.equals("Não possui")) {
                l.observacoes.add("cClassTrib " + atual + " é de nomenclatura \"" + nomenclaturaAtual + "\".");
            }
            boolean dfeOk = an.tipoDfe() == null || J.list(regAtual, "tiposDfeClassificacao").stream()
                    .anyMatch(t -> an.tipoDfe().toString().equals(J.str(t, "tipo")));
            if (!dfeOk) {
                l.status = "DIVERGENTE";
                l.observacoes.add("cClassTrib " + atual + " não é admitido no documento modelo " + an.tipoDfe() + ".");
                return;
            }
            String cstEsperado = atual.substring(0, 3);
            if (l.cstAtual != null && !l.cstAtual.equals(cstEsperado)) {
                l.status = "DIVERGENTE";
                l.observacoes.add("CST " + l.cstAtual + " incompatível com o cClassTrib " + atual + " (esperado CST "
                        + cstEsperado + ").");
                return;
            }
            if (sugerida != null && atual.equals(sugerida.cClassTrib()) || indicados.contains(atual)) {
                l.status = "CONFERIDO";
                if (beneficioSugerido) {
                    l.observacoes.add("Benefício aplicado – confira se os requisitos legais do anexo são atendidos.");
                }
                return;
            }
            l.status = "REVISAR";
            if ("000001".equals(atual) && beneficioSugerido) {
                l.observacoes.add("Possível benefício não aplicado: " + sugerida.cClassTrib() + " ("
                        + sugerida.reducaoResumo() + "). Confira se o produto atende aos requisitos.");
            } else if (atualViaAnexo) {
                l.observacoes.add("cClassTrib atual é válido para o " + an.tipo() + ", mas difere da sugestão principal ("
                        + (sugerida == null ? "-" : sugerida.cClassTrib()) + "). Confirme qual se aplica.");
            } else {
                l.observacoes.add("cClassTrib atual (" + atual + ") é válido, mas depende da operação/adquirente. "
                        + "Sugestão para venda normal: " + (sugerida == null ? "-" : sugerida.cClassTrib()) + ".");
            }
            return;
        }

        // sem classificação informada: sugestão
        boolean combustivel = an.observacoes().stream().anyMatch(o -> o.startsWith("Combustível"));
        if (combustivel || sugerida != null && sugerida.categoria() == Categoria.MONOFASICO) {
            l.status = "REVISAR";
            if (!combustivel) {
                l.observacoes.add("Combustível em regime monofásico: o cClassTrib depende da posição na cadeia "
                        + "(620001 produtor/importador, 620006 tributação já cobrada etc.).");
            }
        } else if (beneficioSugerido) {
            l.status = "REVISAR";
            l.observacoes.add(principais.size() > 1
                    ? "Mais de um benefício possível para o produto: " + principais.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ". Escolha conforme os requisitos."
                    : "Benefício vinculado ao produto: confira os requisitos legais antes de aplicar.");
        } else {
            l.status = "SUGERIDO";
        }
        if (!condicionaisAnexo.isEmpty()) {
            l.observacoes.add("Há tratamentos condicionais para este " + an.tipo() + " (dependem do adquirente, "
                    + "região ou regime): " + condicionaisAnexo.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ".");
        }
        if (!indicados.isEmpty()) {
            l.observacoes.add("Há saídas com CFOP de operação especial – veja a coluna de sugestões por CFOP.");
        }
        if ("07".equals(a.tipoItem) || "08".equals(a.tipoItem)) {
            l.observacoes.add("Item de uso e consumo/ativo: a classificação só é relevante se houver saída.");
        }
    }

    /** A classificação exige anexo (tem lista de NCM/NBS) para o tipo analisado? */
    private boolean classificacaoExigeAnexo(String cClassTrib, AnaliseNomenclatura an) {
        // se aparece nas específicas, tem anexo; senão, consulta a lista de situacionais (sem anexo)
        if (an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib))) {
            return true;
        }
        boolean semAnexo = an.situacionais().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib));
        if (semAnexo) {
            return false;
        }
        return catalogo.temAnexo(Tipo.valueOf(an.tipo()), cClassTrib, LocalDate.parse(an.data()));
    }

    private static LinhaClassificacao finalizar(LinhaClassificacao l) {
        l.statusRotulo = ROTULOS.getOrDefault(l.status, l.status);
        return l;
    }

    private static String maisFrequente(Map<String, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    static String abreviar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
