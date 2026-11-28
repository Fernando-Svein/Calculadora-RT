package br.com.escritorio.hubfiscal.classificacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.AnaliseNomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.catalogo.Candidata.Categoria;
import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;
import br.com.escritorio.hubfiscal.infra.J;
import br.com.escritorio.hubfiscal.infra.Paralelo;
import br.com.escritorio.hubfiscal.infra.Valores;

/**
 * Classificação tributária em lote (cClassTrib/CST) a partir de SPED e XML.
 * <p>
 * Para cada produto/serviço do cadastro: valida o NCM/NBS, lista as
 * classificações vinculadas por anexo, sugere a mais provável, confere a
 * classificação já usada nas notas e aponta o que precisa de revisão humana.
 */
@Service
public class ClassificacaoService {

    /** Tributação integral: sugestão quando o produto não tem benefício vinculado. */
    private static final String INTEGRAL = "000001";

    private static final Map<String, String> TIPOS_ITEM_SPED = Map.ofEntries(
            Map.entry("00", "00 – Mercadoria para revenda"), Map.entry("01", "01 – Matéria-prima"),
            Map.entry("02", "02 – Embalagem"), Map.entry("03", "03 – Produto em processo"),
            Map.entry("04", "04 – Produto acabado"), Map.entry("05", "05 – Subproduto"),
            Map.entry("06", "06 – Produto intermediário"), Map.entry("07", "07 – Material de uso e consumo"),
            Map.entry("08", "08 – Ativo imobilizado"), Map.entry("09", "09 – Serviços"),
            Map.entry("10", "10 – Outros insumos"), Map.entry("99", "99 – Outras"));

    private final CatalogoService catalogo;
    private final HubProperties props;
    private final ConferenciaOficial conferencia;

    public ClassificacaoService(CatalogoService catalogo, HubProperties props, ConferenciaOficial conferencia) {
        this.catalogo = catalogo;
        this.props = props;
        this.conferencia = conferencia;
    }

    /** Nomenclatura analisada para um item: NCM ou NBS, código e modelo de documento predominante. */
    private record ChaveAnalise(Tipo tipo, String codigo, int modelo) {
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
            boolean cadastro = "0200".equals(it.registro);
            origens.add(it.origem + (cadastro ? " (cadastro)" : ""));
            if (cadastro) {
                return;
            }
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

        /** NBS tem prioridade sobre NCM; sem modelo nas notas, assume NFS-e (91) para NBS e NF-e (55) para NCM. */
        ChaveAnalise chaveAnalise() {
            Integer modelo = maisFrequente(modelos);
            if (!nbss.isEmpty()) {
                return new ChaveAnalise(Tipo.NBS, nbss.iterator().next(), modelo == null ? 91 : modelo);
            }
            if (!ncms.isEmpty()) {
                return new ChaveAnalise(Tipo.NCM, ncms.iterator().next(), modelo == null ? 55 : modelo);
            }
            return null;
        }
    }

    /** Classifica todos os itens importados, com vigência na {@code data}. */
    public ResultadoClassificacao classificar(Importacao imp, LocalDate data) {
        Map<String, Agregado> agregados = agregar(imp);

        // análises distintas (NCM/NBS × modelo), em paralelo: cada uma consulta a base e as tabelas oficiais
        Set<ChaveAnalise> chaves = new LinkedHashSet<>();
        for (Agregado a : agregados.values()) {
            ChaveAnalise k = a.chaveAnalise();
            if (k != null) {
                chaves.add(k);
            }
        }
        List<ChaveAnalise> listaChaves = new ArrayList<>(chaves);
        List<AnaliseNomenclatura> resultados = Paralelo.mapear(listaChaves, props.paralelismo(),
                k -> catalogo.analisar(k.tipo(), k.codigo(), data, k.modelo()));
        Map<ChaveAnalise, AnaliseNomenclatura> analises = new HashMap<>();
        for (int i = 0; i < listaChaves.size(); i++) {
            analises.put(listaChaves.get(i), resultados.get(i));
        }

        ResultadoClassificacao res = new ResultadoClassificacao();
        res.dataReferencia = data.toString();
        res.geradoEm = Valores.dataHoraGeracao();
        res.arquivos.putAll(imp.arquivos);
        res.avisos.addAll(imp.avisos);
        Set<String> contribuintes = new LinkedHashSet<>();
        imp.speds.forEach(s -> contribuintes.add(s.nome() + " – CNPJ " + s.cnpj() + " (" + s.tipo() + ", "
                + s.dataInicial() + " a " + s.dataFinal() + ")"));
        res.contribuintes.addAll(contribuintes);
        res.vendasPorNcm = VendasPorNcm.calcular(imp, catalogo.descricoesNomenclatura(data));

        Map<String, Map<String, Object>> tabela = catalogo.classificacoes(data);
        for (Agregado a : agregados.values()) {
            ChaveAnalise k = a.chaveAnalise();
            res.linhas.add(montarLinha(a, k == null ? null : analises.get(k), tabela, data));
        }
        conferirNaCalculadora(res, imp, data);
        res.linhas.sort(Comparator.comparing((LinhaClassificacao l) -> l.status)
                .thenComparing(l -> l.descricao == null ? "" : l.descricao));
        res.totalItens = res.linhas.size();
        res.contagemStatus.putAll(StatusClassificacao.contagemZerada());
        res.linhas.forEach(l -> res.contagemStatus.merge(l.status.name(), 1, Integer::sum));
        return res;
    }

    /**
     * Confere na calculadora oficial o cClassTrib das notas e o sugerido de cada item. Rejeição do cClassTrib das notas
     * torna o item divergente; rejeição da sugestão leva o item para revisão. Calculadora fora do ar não interrompe o
     * lote: os itens ficam como "Não conferida" e o resumo avisa.
     */
    private void conferirNaCalculadora(ResultadoClassificacao res, Importacao imp, LocalDate data) {
        Map<String, Integer> municipios = municipiosPorContribuinte(imp);
        int padrao = municipios.values().stream().findFirst().orElse(ConferenciaOficial.MUNICIPIO_PADRAO);
        Map<LinhaClassificacao, ConferenciaOficial.Combinacao> atuais = new HashMap<>();
        Map<LinhaClassificacao, ConferenciaOficial.Combinacao> sugeridas = new HashMap<>();
        for (LinhaClassificacao l : res.linhas) {
            if (l.status == StatusClassificacao.INVALIDO || l.status == StatusClassificacao.SEM_NOMENCLATURA
                    || l.modeloAnalisado == null) {
                continue;
            }
            int municipio = municipios.getOrDefault(l.donoCnpj == null ? "" : l.donoCnpj, padrao);
            if (l.cClassTribAtual != null && l.cClassTribAtual.length() == 6) {
                atuais.put(l, new ConferenciaOficial.Combinacao(l.tipoNomenclatura, l.nomenclatura, l.modeloAnalisado,
                        l.cstAtual != null ? l.cstAtual : l.cClassTribAtual.substring(0, 3), l.cClassTribAtual,
                        municipio));
            }
            if (l.cClassTribSugerido != null && l.cstSugerido != null) {
                sugeridas.put(l, new ConferenciaOficial.Combinacao(l.tipoNomenclatura, l.nomenclatura,
                        l.modeloAnalisado, l.cstSugerido, l.cClassTribSugerido, municipio));
            }
        }
        Set<ConferenciaOficial.Combinacao> todas = new LinkedHashSet<>(atuais.values());
        todas.addAll(sugeridas.values());
        if (todas.isEmpty()) {
            return;
        }
        Map<ConferenciaOficial.Combinacao, ConferenciaOficial.Resultado> r = conferencia.conferir(todas, data);

        Map<ConferenciaOficial.Situacao, Integer> contagem = new HashMap<>();
        r.values().forEach(x -> contagem.merge(x.situacao(), 1, Integer::sum));
        res.conferenciaOficial = todas.size() + " combinação(ões) NCM/NBS + CST + cClassTrib conferidas na calculadora "
                + "oficial: " + contagem.getOrDefault(ConferenciaOficial.Situacao.ACEITA, 0) + " aceita(s), "
                + contagem.getOrDefault(ConferenciaOficial.Situacao.REJEITADA, 0) + " rejeitada(s), "
                + contagem.getOrDefault(ConferenciaOficial.Situacao.NAO_CONFERIDA, 0) + " não conferida(s).";
        if (contagem.getOrDefault(ConferenciaOficial.Situacao.NAO_CONFERIDA, 0) > 0) {
            res.avisos.add("Parte das classificações não foi conferida na calculadora oficial (veja as colunas "
                    + "\"Calculadora oficial\"). Verifique se a calculadora do Regime Geral está no ar e processe de novo.");
        }

        for (LinhaClassificacao l : res.linhas) {
            ConferenciaOficial.Resultado atual = atuais.containsKey(l) ? r.get(atuais.get(l)) : null;
            ConferenciaOficial.Resultado sugerido = sugeridas.containsKey(l) ? r.get(sugeridas.get(l)) : null;
            if (atual != null) {
                l.oficialAtual = atual.rotulo();
                if (atual.situacao() == ConferenciaOficial.Situacao.REJEITADA
                        && l.status != StatusClassificacao.DIVERGENTE) {
                    l.revisar = "A calculadora oficial rejeitou o cClassTrib " + l.cClassTribAtual + " usado nas notas: "
                            + atual.mensagem() + (l.cClassTribSugerido == null
                                    || l.cClassTribSugerido.equals(l.cClassTribAtual) ? ""
                                            : " Sugestão: " + l.cClassTribSugerido + ".");
                    finalizar(l, StatusClassificacao.DIVERGENTE);
                } else if (atual.situacao() == ConferenciaOficial.Situacao.ACEITA
                        && l.status == StatusClassificacao.DIVERGENTE) {
                    l.observacoes.add("A calculadora oficial aceitou a combinação usada nas notas; a divergência "
                            + "apontada é da conferência do hub. Confira antes de corrigir.");
                }
            }
            if (sugerido != null) {
                l.oficialSugerido = sugerido.rotulo();
                if (sugerido.situacao() == ConferenciaOficial.Situacao.REJEITADA) {
                    String texto = "A sugestão " + l.cClassTribSugerido + " foi rejeitada pela calculadora oficial: "
                            + sugerido.mensagem() + " Escolha outra classificação (aba \"Alternativas por item\").";
                    if (l.status == StatusClassificacao.SUGERIDO || l.status == StatusClassificacao.CONFERIDO) {
                        l.revisar = texto;
                        finalizar(l, StatusClassificacao.REVISAR);
                    } else {
                        l.observacoes.add(texto);
                    }
                }
            }
        }
    }

    /** Município (IBGE) de cada contribuinte pelo SPED (registro 0000) ou pelo emitente dos XMLs. */
    private static Map<String, Integer> municipiosPorContribuinte(Importacao imp) {
        Map<String, Integer> m = new LinkedHashMap<>();
        imp.speds.forEach(s -> {
            if (s.cnpj() != null && s.municipio() != null && String.valueOf(s.municipio()).length() == 7) {
                m.putIfAbsent(s.cnpj(), s.municipio());
            }
        });
        imp.documentos.forEach(d -> {
            if (d.emitenteCnpj != null && d.emitenteMunicipio != null
                    && String.valueOf(d.emitenteMunicipio).length() == 7) {
                m.putIfAbsent(d.emitenteCnpj, d.emitenteMunicipio);
            }
        });
        return m;
    }

    /** Junta cadastro e movimentos do SPED e itens dos XMLs por contribuinte + código do item. */
    private static Map<String, Agregado> agregar(Importacao imp) {
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
            agregados.computeIfAbsent(chave, k -> {
                Agregado n = new Agregado();
                n.donoCnpj = it.donoCnpj;
                n.codigo = it.codigo;
                return n;
            }).absorver(it);
        }
        return agregados;
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
            l.revisar = "09".equals(a.tipoItem)
                    ? "Serviço sem NBS: o SPED não traz a NBS. Informe a NBS para classificar."
                    : "Item sem NCM/NBS: preencha o NCM no cadastro.";
            return finalizar(l, StatusClassificacao.SEM_NOMENCLATURA);
        }
        l.tipoNomenclatura = an.tipo();
        l.nomenclatura = an.codigo();
        l.descricaoNomenclatura = an.descricaoCompleta() != null ? an.descricaoCompleta() : an.descricao();
        l.modeloAnalisado = an.tipoDfe();
        l.impostoSeletivo = an.impostoSeletivo();
        if (!an.valida()) {
            l.revisar = "Corrija o " + an.tipo() + " no cadastro: " + an.mensagem();
            return finalizar(l, StatusClassificacao.INVALIDO);
        }
        l.observacoes.addAll(an.observacoes());

        // candidatas do anexo, compatíveis com o documento
        List<Candidata> especificas = an.especificas().stream().filter(Candidata::dfeCompativel).toList();
        l.alternativas.addAll(an.especificas());
        List<Candidata> principais = especificas.stream().filter(c -> !c.condicional()).toList();
        Candidata sugerida = !principais.isEmpty() ? principais.get(0) : null;
        if (sugerida == null) {
            Map<String, Object> integral = tabela.get(INTEGRAL);
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

        return finalizar(l, avaliarStatus(l, a, an, especificas, principais, sugerida, indicados, tabela));
    }

    /** Decide o status do item e acrescenta as observações que o justificam. */
    private StatusClassificacao avaliarStatus(LinhaClassificacao l, Agregado a, AnaliseNomenclatura an,
            List<Candidata> especificas, List<Candidata> principais, Candidata sugerida, Set<String> indicados,
            Map<String, Map<String, Object>> tabela) {
        String atual = l.cClassTribAtual;
        boolean beneficioSugerido = sugerida != null && !INTEGRAL.equals(sugerida.cClassTrib());
        List<Candidata> condicionaisAnexo = especificas.stream().filter(Candidata::condicional).toList();

        if (atual != null) {
            Map<String, Object> regAtual = tabela.get(atual);
            if (regAtual == null) {
                l.revisar = "cClassTrib " + atual + " usado nas notas não existe ou não está vigente em " + an.data()
                        + "." + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            String nomenclaturaAtual = String.valueOf(J.str(regAtual, "nomenclatura"));
            boolean atualViaAnexo = an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(atual));
            if (classificacaoExigeAnexo(atual, an) && !atualViaAnexo) {
                l.revisar = an.tipo() + " " + an.codigo() + " não consta do anexo do cClassTrib " + atual
                        + " (a calculadora oficial rejeita: erro REG-011)." + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            if (!nomenclaturaAtual.contains(an.tipo()) && !nomenclaturaAtual.equals("Não possui")) {
                l.observacoes.add("cClassTrib " + atual + " é de nomenclatura \"" + nomenclaturaAtual + "\".");
            }
            if (!CatalogoService.admiteDocumento(regAtual, an.tipoDfe())) {
                l.revisar = "cClassTrib " + atual + " não é admitido no documento modelo " + an.tipoDfe() + "."
                        + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            String cstEsperado = atual.substring(0, 3);
            if (l.cstAtual != null && !l.cstAtual.equals(cstEsperado)) {
                l.revisar = "CST " + l.cstAtual + " incompatível com o cClassTrib " + atual + ": corrija o CST para "
                        + cstEsperado + " ou o cClassTrib.";
                return StatusClassificacao.DIVERGENTE;
            }
            if (sugerida != null && atual.equals(sugerida.cClassTrib()) || indicados.contains(atual)) {
                if (beneficioSugerido) {
                    l.observacoes.add("Benefício aplicado – confira se os requisitos legais do anexo são atendidos.");
                }
                return StatusClassificacao.CONFERIDO;
            }
            if (INTEGRAL.equals(atual) && beneficioSugerido) {
                l.revisar = "Possível benefício não aplicado: " + sugerida.cClassTrib() + " ("
                        + sugerida.reducaoResumo() + "). Confira se o produto atende aos requisitos.";
            } else if (atualViaAnexo) {
                l.revisar = "cClassTrib atual (" + atual + ") é válido para o " + an.tipo()
                        + ", mas difere da sugestão principal (" + (sugerida == null ? "-" : sugerida.cClassTrib())
                        + "). Confirme qual se aplica.";
            } else {
                l.revisar = "cClassTrib atual (" + atual + ") é válido, mas depende da operação/adquirente. "
                        + "Sugestão para venda normal: " + (sugerida == null ? "-" : sugerida.cClassTrib()) + ".";
            }
            return StatusClassificacao.REVISAR;
        }

        // sem classificação informada: sugestão
        StatusClassificacao status;
        boolean combustivel = an.observacoes().stream().anyMatch(o -> o.startsWith("Combustível"));
        if (combustivel || sugerida != null && sugerida.categoria() == Categoria.MONOFASICO) {
            status = StatusClassificacao.REVISAR;
            l.revisar = "Combustível em regime monofásico: defina o cClassTrib pela posição na cadeia "
                    + "(620001 produtor/importador, 620006 tributação já cobrada etc.).";
        } else if (beneficioSugerido) {
            status = StatusClassificacao.REVISAR;
            l.revisar = principais.size() > 1
                    ? "Mais de um benefício possível para o produto: " + principais.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ". Escolha conforme os requisitos."
                    : "Benefício vinculado ao produto (" + sugerida.cClassTrib() + ", " + sugerida.reducaoResumo()
                            + "): confira os requisitos legais antes de aplicar.";
        } else {
            status = StatusClassificacao.SUGERIDO;
        }
        if (!condicionaisAnexo.isEmpty()) {
            l.observacoes.add("Há tratamentos condicionais para este " + an.tipo() + " (dependem do adquirente, "
                    + "região ou regime): " + condicionaisAnexo.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ".");
        }
        if (!indicados.isEmpty()) {
            l.observacoes.add("Há saídas com CFOP de operação especial: " + String.join("; ", l.sugestoesPorCfop)
                    + ".");
        }
        if ("07".equals(a.tipoItem) || "08".equals(a.tipoItem)) {
            l.observacoes.add("Item de uso e consumo/ativo: a classificação só é relevante se houver saída.");
        }
        return status;
    }

    /** A classificação exige anexo (tem lista fechada de NCM/NBS) para o tipo analisado? */
    private boolean classificacaoExigeAnexo(String cClassTrib, AnaliseNomenclatura an) {
        // se aparece nas específicas, tem anexo; se aparece nas situacionais, não tem; senão consulta a base
        if (an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib))) {
            return true;
        }
        if (an.situacionais().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib))) {
            return false;
        }
        return catalogo.temAnexo(Tipo.valueOf(an.tipo()), cClassTrib, LocalDate.parse(an.data()));
    }

    /** Complemento de uma divergência: para qual classificação corrigir. */
    private static String sugestao(Candidata sugerida) {
        return sugerida == null ? "" : " Sugestão: " + sugerida.cClassTrib() + " (" + sugerida.reducaoResumo() + ").";
    }

    private static LinhaClassificacao finalizar(LinhaClassificacao l, StatusClassificacao status) {
        l.status = status;
        l.statusRotulo = status.rotulo();
        return l;
    }

    private static <K> K maisFrequente(Map<K, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    static String abreviar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
