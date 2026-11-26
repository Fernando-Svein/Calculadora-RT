package br.com.escritorio.hubfiscal.importacao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import br.com.escritorio.hubfiscal.classificacao.Cfops;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.SpedParser.ResultadoSped;

/**
 * Vendas por NCM (NBS nos serviços) dos arquivos importados, por empresa (emitente do XML / contribuinte do SPED).
 * <p>
 * Venda = item de saída com CFOP de venda ou prestação ({@link Cfops#venda}); sem CFOP, NFS-e, cupom fiscal (C425) e
 * serviço prestado no SPED (A170 com IND_OPER = 1). Entradas, remessas, transferências, devoluções e notas canceladas
 * ou denegadas ficam de fora, com o valor informado nas observações. Uma nota presente no SPED e no XML (mesma chave)
 * conta uma vez só, pelo XML. Valor líquido = valor dos produtos/serviços − descontos.
 */
public final class VendasPorNcm {

    /** Uma linha: empresa + NCM/NBS. quantidade só é somada quando todos os itens usam a mesma unidade. */
    public record Linha(String empresaCnpj, String empresaNome, String ncm, String descricao, int itens,
            BigDecimal quantidade, String unidade, BigDecimal valorBruto, BigDecimal descontos, BigDecimal valorLiquido,
            BigDecimal percentual) {
    }

    /** Total por empresa (para o subtotal quando os arquivos têm mais de uma). */
    public record TotalEmpresa(String empresaCnpj, String empresaNome, int ncms, int itens, BigDecimal valorBruto,
            BigDecimal descontos, BigDecimal valorLiquido) {
    }

    public record Resumo(List<Linha> linhas, List<TotalEmpresa> empresas, int itens, BigDecimal valorBruto,
            BigDecimal descontos, BigDecimal valorLiquido, List<String> observacoes) {
    }

    private static final String SEM_NCM = "";

    private VendasPorNcm() {
    }

    private static final class Acumulado {
        String cnpj;
        String nome;
        String ncm;
        int itens;
        BigDecimal quantidade = BigDecimal.ZERO;
        Set<String> unidades = new LinkedHashSet<>();
        BigDecimal bruto = BigDecimal.ZERO;
        BigDecimal descontos = BigDecimal.ZERO;
        Map<String, Integer> descricoes = new HashMap<>();
    }

    /**
     * @param descricaoNcm descrição oficial do NCM/NBS (ou null para usar a descrição mais frequente dos itens)
     */
    public static Resumo calcular(Importacao imp, Function<String, String> descricaoNcm) {
        List<ItemFiscal> itens = new ArrayList<>();
        Set<String> chavesXml = new HashSet<>();
        int cancelados = 0;
        BigDecimal valorCancelados = BigDecimal.ZERO;
        for (DocumentoFiscal d : imp.documentos) {
            if (d.situacao != null) { // protocolo diferente de autorizado (cancelada, denegada...)
                cancelados += d.itens.size();
                for (ItemFiscal it : d.itens) {
                    valorCancelados = valorCancelados.add(zero(it.valor));
                }
                continue;
            }
            if (d.chave != null) {
                chavesXml.add(d.chave);
            }
            itens.addAll(d.itens);
        }
        int duplicados = 0;
        for (ResultadoSped s : imp.speds) {
            for (ItemFiscal it : s.movimentos()) {
                if (it.chave != null && chavesXml.contains(it.chave)) {
                    duplicados++;
                    continue;
                }
                itens.add(it);
            }
        }

        Map<String, Acumulado> grupos = new LinkedHashMap<>();
        int entradas = 0, outrasSaidas = 0;
        BigDecimal valorEntradas = BigDecimal.ZERO, valorOutras = BigDecimal.ZERO;
        for (ItemFiscal it : itens) {
            boolean venda = it.cfop != null ? Cfops.venda(it.cfop) : Boolean.TRUE.equals(it.saida);
            if (!venda) {
                boolean entrada = it.cfop != null ? Cfops.entrada(it.cfop) : !Boolean.TRUE.equals(it.saida);
                if (entrada) {
                    entradas++;
                    valorEntradas = valorEntradas.add(zero(it.valor));
                } else {
                    outrasSaidas++;
                    valorOutras = valorOutras.add(zero(it.valor));
                }
                continue;
            }
            String ncm = it.nbs != null ? it.nbs : it.ncm != null ? it.ncm : SEM_NCM;
            String cnpj = it.donoCnpj == null ? "" : it.donoCnpj;
            Acumulado a = grupos.computeIfAbsent(cnpj + "|" + ncm, k -> {
                Acumulado n = new Acumulado();
                n.cnpj = it.donoCnpj;
                n.nome = it.donoNome;
                n.ncm = ncm;
                return n;
            });
            a.itens++;
            a.bruto = a.bruto.add(zero(it.valor));
            a.descontos = a.descontos.add(zero(it.desconto));
            if (it.quantidade != null) {
                a.quantidade = a.quantidade.add(it.quantidade);
            }
            a.unidades.add(it.unidade == null ? "" : it.unidade.trim().toUpperCase(java.util.Locale.ROOT));
            if (it.descricao != null) {
                a.descricoes.merge(it.descricao, 1, Integer::sum);
            }
        }

        // totais por empresa (o percentual de cada NCM é sobre o total da própria empresa)
        Map<String, BigDecimal> liquidoEmpresa = new HashMap<>();
        grupos.values().forEach(a -> liquidoEmpresa.merge(String.valueOf(a.cnpj), a.bruto.subtract(a.descontos), BigDecimal::add));
        List<Linha> linhas = new ArrayList<>();
        for (Acumulado a : grupos.values()) {
            BigDecimal liquido = a.bruto.subtract(a.descontos);
            BigDecimal totalEmp = liquidoEmpresa.get(String.valueOf(a.cnpj));
            BigDecimal pct = totalEmp == null || totalEmp.signum() == 0 ? null
                    : liquido.multiply(BigDecimal.valueOf(100)).divide(totalEmp, 2, RoundingMode.HALF_EVEN);
            boolean umaUnidade = a.unidades.size() == 1 && !a.unidades.contains("");
            String descricao = a.ncm.isEmpty() ? "Itens sem NCM/NBS" : descricaoNcm == null ? null : descricaoNcm.apply(a.ncm);
            if (descricao == null) {
                descricao = a.descricoes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            }
            linhas.add(new Linha(a.cnpj, a.nome, a.ncm.isEmpty() ? null : a.ncm, descricao, a.itens,
                    umaUnidade ? a.quantidade : null, umaUnidade ? a.unidades.iterator().next() : a.unidades.size() > 1 ? "várias" : null,
                    a.bruto, a.descontos, liquido, pct));
        }
        linhas.sort(Comparator.comparing((Linha l) -> l.empresaNome() == null ? "" : l.empresaNome())
                .thenComparing(Linha::valorLiquido, Comparator.reverseOrder()));

        Map<String, TotalEmpresa> porEmpresa = new LinkedHashMap<>();
        for (Linha l : linhas) {
            porEmpresa.merge(String.valueOf(l.empresaCnpj()), new TotalEmpresa(l.empresaCnpj(), l.empresaNome(), 1, l.itens(),
                    l.valorBruto(), l.descontos(), l.valorLiquido()),
                    (x, y) -> new TotalEmpresa(x.empresaCnpj(), x.empresaNome(), x.ncms() + 1, x.itens() + y.itens(),
                            x.valorBruto().add(y.valorBruto()), x.descontos().add(y.descontos()), x.valorLiquido().add(y.valorLiquido())));
        }
        BigDecimal bruto = BigDecimal.ZERO, desc = BigDecimal.ZERO;
        int n = 0;
        for (Linha l : linhas) {
            bruto = bruto.add(l.valorBruto());
            desc = desc.add(l.descontos());
            n += l.itens();
        }

        List<String> obs = new ArrayList<>();
        obs.add("Vendas: itens de saída com CFOP de venda ou prestação (5/6/7.101–125, 251–258, 301–307, 351–360, 401–405, "
                + "651–656, 667, 932, 933), NFS-e, cupom fiscal (C425) e serviços prestados no SPED. Valor líquido = valor dos "
                + "produtos/serviços − descontos.");
        if (entradas > 0) {
            obs.add(entradas + " item(ns) de entrada não entram (" + dinheiro(valorEntradas) + ").");
        }
        if (outrasSaidas > 0) {
            obs.add(outrasSaidas + " item(ns) de outras saídas (remessa, transferência, devolução, bonificação, venda de ativo...) "
                    + "não entram (" + dinheiro(valorOutras) + ").");
        }
        if (cancelados > 0) {
            obs.add(cancelados + " item(ns) de notas canceladas/denegadas não entram (" + dinheiro(valorCancelados) + ").");
        }
        if (duplicados > 0) {
            obs.add(duplicados + " item(ns) do SPED já constavam dos XMLs (mesma chave) e foram contados uma vez só.");
        }
        if (porEmpresa.size() > 1) {
            obs.add("Os arquivos têm " + porEmpresa.size() + " empresas (emitente do XML / contribuinte do SPED): em XMLs de compra, "
                    + "o emitente é o fornecedor. Veja o subtotal de cada uma.");
        }
        return new Resumo(linhas, new ArrayList<>(porEmpresa.values()), n, bruto, desc, bruto.subtract(desc), obs);
    }

    private static BigDecimal zero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static String dinheiro(BigDecimal b) {
        return String.format(java.util.Locale.of("pt", "BR"), "R$ %,.2f", b);
    }
}
