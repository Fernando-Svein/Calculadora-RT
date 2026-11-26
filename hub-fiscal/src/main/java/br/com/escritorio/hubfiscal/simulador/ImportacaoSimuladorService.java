package br.com.escritorio.hubfiscal.simulador;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.AnaliseNomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.classificacao.Cfops;
import br.com.escritorio.hubfiscal.importacao.DocumentoFiscal;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;
import br.com.escritorio.hubfiscal.importacao.SpedParser.ResultadoSped;
import br.com.escritorio.hubfiscal.infra.Ufs;
import br.com.escritorio.hubfiscal.infra.Valores;

/**
 * Converte documentos importados (XML de NF-e/NFC-e/NFS-e ou SPED) em operações prontas para o Simulador do
 * Regime Regular: data, local, documento e itens com NCM/NBS, CST, cClassTrib, quantidade e base.
 * <p>
 * Quando o item não traz cClassTrib (XML sem grupo IBSCBS, ou SPED), aplica a mesma sugestão do botão "Sugerir"
 * e da classificação em lote: a classificação do anexo vinculada ao NCM/NBS e admitida no documento; sem anexo,
 * tributação integral (000001). O item informa de onde veio a classificação, para a tela sinalizar a sugestão.
 */
@Service
public class ImportacaoSimuladorService {

    /** Itens por operação: acima disso o simulador fica lento; o processamento em lote atende volumes grandes. */
    static final int LIMITE_ITENS = 300;
    private static final String INTEGRAL = "000001";

    /** Item pronto para o simulador. origemClassificacao: DOCUMENTO, SUGERIDO ou SEM_CLASSIFICACAO. */
    public record ItemSimulador(int numero, String codigo, String descricao, String ncm, String nbs, String cfop,
            BigDecimal quantidade, String unidade, BigDecimal baseCalculo, Map<String, BigDecimal> componentes,
            String cst, String cClassTrib, String origemClassificacao, String motivo) {
    }

    /** Operação (um documento XML, ou o SPED inteiro) pronta para o simulador. */
    public record OperacaoSimulador(String arquivo, String origem, String numero, String chave, String dataHora,
            Integer municipio, String uf, Integer tpDoc, String emitenteCnpj, String emitenteNome, int totalItens,
            List<ItemSimulador> itens, List<String> avisos) {
    }

    private record Chave(Tipo tipo, String codigo, Integer modelo) {
    }

    private final CatalogoService catalogo;

    public ImportacaoSimuladorService(CatalogoService catalogo) {
        this.catalogo = catalogo;
    }

    public List<OperacaoSimulador> converter(Importacao imp, LocalDate dataPadrao) {
        Map<Chave, AnaliseNomenclatura> analises = new HashMap<>(); // o mesmo NCM aparece em várias notas
        List<OperacaoSimulador> ops = new ArrayList<>();
        for (DocumentoFiscal d : imp.documentos) {
            LocalDate data = dataDe(d.dataHoraEmissao, dataPadrao);
            List<String> avisos = new ArrayList<>();
            if (d.situacao != null) {
                avisos.add(d.situacao);
            }
            if (d.emitenteCrt != null && List.of("1", "2", "4").contains(d.emitenteCrt)) {
                avisos.add("Emitente do Simples Nacional (CRT " + d.emitenteCrt + "): o IBS/CBS dele é calculado pelo Simulador do Simples.");
            }
            List<ItemSimulador> itens = itens(d.itens, d.modelo, data, analises, avisos);
            ops.add(new OperacaoSimulador(d.arquivo, d.origem, d.numero, d.chave, d.dataHoraEmissao, d.municipioFatoGerador,
                    Ufs.doMunicipio(d.municipioFatoGerador, d.emitenteUf), d.modelo, d.emitenteCnpj, d.emitenteNome,
                    d.itens.size(), itens, avisos));
        }
        for (ResultadoSped s : imp.speds) {
            // SPED: movimentos (C170/A170/C425); sem movimentos (saídas com NF-e própria), o cadastro 0200
            List<ItemFiscal> fonte = s.movimentos().isEmpty() ? s.cadastro() : s.movimentos();
            List<String> avisos = new ArrayList<>();
            avisos.add(s.movimentos().isEmpty()
                    ? "O SPED não tem itens de documentos (C170/A170): foram usados os itens do cadastro 0200, sem valores. Informe a base de cada item."
                    : "SPED: cada movimento (C170/A170/C425) virou um item; confira os valores, pois o SPED não traz a base do IBS/CBS.");
            LocalDate data = dataDe(s.dataFinal(), dataPadrao);
            Integer modelo = fonte.stream().map(i -> i.modelo).filter(m -> m != null).findFirst().orElse(null);
            List<ItemSimulador> itens = itens(fonte, modelo, data, analises, avisos);
            ops.add(new OperacaoSimulador(s.tipo() + " – " + s.nome(), s.tipo(), null, null,
                    s.dataFinal() == null ? null : s.dataFinal() + "T10:00:00", s.municipio(), s.uf(), modelo, s.cnpj(), s.nome(),
                    fonte.size(), itens, avisos));
        }
        return ops;
    }

    private List<ItemSimulador> itens(List<ItemFiscal> fonte, Integer modelo, LocalDate data, Map<Chave, AnaliseNomenclatura> analises,
            List<String> avisos) {
        List<ItemSimulador> r = new ArrayList<>();
        int n = 0;
        int sugeridos = 0;
        for (ItemFiscal it : fonte) {
            if (n == LIMITE_ITENS) {
                avisos.add("Documento com " + fonte.size() + " itens: o simulador carrega os " + LIMITE_ITENS
                        + " primeiros. Para todos, use Processamento em lote.");
                break;
            }
            n++;
            BigDecimal base = Valores.decimal(it.ibscbs.get("vBC"));
            if (base == null) {
                base = it.valor;
            }
            String cct = it.cClassTribAtual();
            String cst = it.cstAtual();
            String origem = "DOCUMENTO";
            String motivo = null;
            if (cct == null) {
                Candidata c = sugestao(it, modelo, data, analises);
                if (c != null) {
                    cct = c.cClassTrib();
                    cst = c.cst() != null ? c.cst() : cct.substring(0, 3);
                    origem = "SUGERIDO";
                    sugeridos++;
                    motivo = "Sugerido pelo " + (it.nbs != null ? "NBS " + it.nbs : "NCM " + it.ncm) + ": "
                            + (c.reducaoResumo() == null ? c.descricao() : c.reducaoResumo())
                            + (INTEGRAL.equals(cct) ? " (sem benefício vinculado ao produto)" : " – confira os requisitos");
                    Cfops.Indicacao ind = it.cfop == null ? null : Cfops.indicacao(it.cfop);
                    if (ind != null) {
                        motivo += ". CFOP " + it.cfop + ": " + ind.descricao()
                                + (ind.cClassTrib() == null ? "" : " (indica " + ind.cClassTrib() + ")");
                    }
                } else {
                    origem = "SEM_CLASSIFICACAO";
                    motivo = it.ncm == null && it.nbs == null ? "Item sem NCM/NBS: informe a classificação."
                            : "NCM/NBS inválido ou fora de vigência: informe a classificação.";
                }
            } else if (cst == null) {
                cst = cct.substring(0, Math.min(3, cct.length()));
            }
            r.add(new ItemSimulador(n, it.codigo, it.descricao, it.nbs == null ? it.ncm : null, it.nbs, it.cfop, it.quantidade,
                    it.unidade, base, it.componentes, cst, cct, origem, motivo));
        }
        if (sugeridos > 0) {
            avisos.add(sugeridos + " item(ns) sem cClassTrib no documento: a classificação foi sugerida pelo NCM/NBS (marcada na tela). Confira antes de usar.");
        }
        return r;
    }

    /** Mesma sugestão da classificação em lote: anexo compatível com o documento e não condicional; senão 000001. */
    private Candidata sugestao(ItemFiscal it, Integer modelo, LocalDate data, Map<Chave, AnaliseNomenclatura> analises) {
        Tipo tipo = it.nbs != null ? Tipo.NBS : it.ncm != null ? Tipo.NCM : null;
        if (tipo == null) {
            return null;
        }
        String codigo = tipo == Tipo.NBS ? it.nbs : it.ncm;
        AnaliseNomenclatura an = analises.computeIfAbsent(new Chave(tipo, codigo, modelo),
                k -> catalogo.analisar(k.tipo(), k.codigo(), data, k.modelo()));
        if (an == null || !an.valida()) {
            return null;
        }
        return an.especificas().stream().filter(Candidata::dfeCompativel).filter(c -> !c.condicional()).findFirst()
                .orElseGet(() -> {
                    Map<String, Object> integral = catalogo.classificacoes(data).get(INTEGRAL);
                    return integral == null ? null : catalogo.candidata(integral, null, catalogo.fundamentacoes(data), an.tipoDfe());
                });
    }

    private static LocalDate dataDe(String iso, LocalDate padrao) {
        try {
            return iso == null || iso.length() < 10 ? padrao : LocalDate.parse(iso.substring(0, 10));
        } catch (RuntimeException e) {
            return padrao;
        }
    }

    /** Resumo das operações para a resposta (a tela escolhe qual carregar). */
    public static Map<String, Object> resposta(List<OperacaoSimulador> ops, Importacao imp, String empresasEncontradas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operacoes", ops);
        m.put("arquivos", imp.arquivos);
        m.put("avisos", imp.avisos);
        m.put("empresasEncontradas", empresasEncontradas); // código da lista para a tela perguntar (null: nenhuma nova)
        return m;
    }
}
