package br.com.escritorio.hubfiscal.classificacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.AnaliseNomenclatura;
import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.catalogo.Candidata.Categoria;
import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm;
import br.com.escritorio.hubfiscal.classificacao.Agregado.ChaveAnalise;
import br.com.escritorio.hubfiscal.infra.Andamento;
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

    private static final Map<String, String> TIPOS_ITEM_SPED = Map.ofEntries(
            Map.entry("00", "00 – Mercadoria para revenda"), Map.entry("01", "01 – Matéria-prima"),
            Map.entry("02", "02 – Embalagem"), Map.entry("03", "03 – Produto em processo"),
            Map.entry("04", "04 – Produto acabado"), Map.entry("05", "05 – Subproduto"),
            Map.entry("06", "06 – Produto intermediário"), Map.entry("07", "07 – Material de uso e consumo"),
            Map.entry("08", "08 – Ativo imobilizado"), Map.entry("09", "09 – Serviços"),
            Map.entry("10", "10 – Outros insumos"), Map.entry("99", "99 – Outras"));

    private final CatalogoService catalogo;
    private final HubProperties props;
    private final RegrasStatus regras;
    private final ConferenciaClassificacao conferenciaLinhas;

    public ClassificacaoService(CatalogoService catalogo, HubProperties props, RegrasStatus regras,
            ConferenciaClassificacao conferenciaLinhas) {
        this.catalogo = catalogo;
        this.props = props;
        this.regras = regras;
        this.conferenciaLinhas = conferenciaLinhas;
    }


    /** Classifica todos os itens importados, com vigência na {@code data}, avisando o {@code andamento}. */
    public ResultadoClassificacao classificar(Importacao imp, LocalDate data, Andamento andamento) {
        Map<String, Agregado> agregados = Agregado.agregar(imp);

        // análises distintas (NCM/NBS × modelo), em paralelo: cada uma consulta a base e as tabelas oficiais
        Set<ChaveAnalise> chaves = new LinkedHashSet<>();
        for (Agregado a : agregados.values()) {
            ChaveAnalise k = a.chaveAnalise();
            if (k != null) {
                chaves.add(k);
            }
        }
        List<ChaveAnalise> listaChaves = new ArrayList<>(chaves);
        andamento.etapa("Analisando os NCM/NBS na base oficial", listaChaves.size());
        List<AnaliseNomenclatura> resultados = Paralelo.mapear(listaChaves, props.paralelismo(),
                k -> catalogo.analisar(k.tipo(), k.codigo(), data, k.modelo()), andamento);
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
        andamento.etapa("Montando a classificação dos itens", 0);
        for (Agregado a : agregados.values()) {
            ChaveAnalise k = a.chaveAnalise();
            res.linhas.add(montarLinha(a, k == null ? null : analises.get(k), tabela, data));
        }
        conferenciaLinhas.conferir(res, imp, data, andamento);
        res.linhas.sort(Comparator.comparing((LinhaClassificacao l) -> l.status)
                .thenComparing(l -> l.descricao == null ? "" : l.descricao));
        res.totalItens = res.linhas.size();
        res.contagemStatus.putAll(StatusClassificacao.contagemZerada());
        res.linhas.forEach(l -> res.contagemStatus.merge(l.status.name(), 1, Integer::sum));
        return res;
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
        l.cClassTribAtual = Agregado.maisFrequente(a.cClassTribAtual);
        l.cstAtual = Agregado.maisFrequente(a.cstAtual);
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
            return l.definirStatus(StatusClassificacao.SEM_NOMENCLATURA);
        }
        l.tipoNomenclatura = an.tipo();
        l.nomenclatura = an.codigo();
        l.descricaoNomenclatura = an.descricaoCompleta() != null ? an.descricaoCompleta() : an.descricao();
        l.modeloAnalisado = an.tipoDfe();
        l.impostoSeletivo = an.impostoSeletivo();
        if (!an.valida()) {
            l.revisar = "Corrija o " + an.tipo() + " no cadastro: " + an.mensagem();
            return l.definirStatus(StatusClassificacao.INVALIDO);
        }
        l.observacoes.addAll(an.observacoes());

        // candidatas do anexo, compatíveis com o documento
        List<Candidata> especificas = an.especificas().stream().filter(Candidata::dfeCompativel).toList();
        l.alternativas.addAll(an.especificas());
        List<Candidata> principais = especificas.stream().filter(c -> !c.condicional()).toList();
        Candidata sugerida = !principais.isEmpty() ? principais.get(0) : null;
        if (sugerida == null) {
            Map<String, Object> integral = tabela.get(RegrasStatus.INTEGRAL);
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

        return l.definirStatus(regras.avaliar(l, a, an, especificas, principais, sugerida, indicados, tabela));
    }

    static String abreviar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
