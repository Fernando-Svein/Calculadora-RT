package br.com.escritorio.hubfiscal.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import br.com.escritorio.hubfiscal.excel.Planilha.Coluna;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm.Linha;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm.TotalEmpresa;

/** Aba "Vendas por NCM" das planilhas de classificação e de auditoria (mesmo formato nas duas). */
public final class VendasExcel {

    private VendasExcel() {
    }

    /** Linhas do resumo: total geral e observações (o que entrou e o que ficou de fora). */
    public static void resumo(List<String[]> resumo, VendasPorNcm.Resumo v) {
        if (v == null) {
            return;
        }
        resumo.add(new String[] { "" });
        resumo.add(new String[] { "Vendas por NCM (aba \"Vendas por NCM\")" });
        resumo.add(new String[] { "Itens vendidos", String.valueOf(v.itens()) });
        resumo.add(new String[] { "Valor dos produtos/serviços", moeda(v.valorBruto()) });
        resumo.add(new String[] { "Descontos", moeda(v.descontos()) });
        resumo.add(new String[] { "Total vendido (líquido)", moeda(v.valorLiquido()) });
        for (String o : v.observacoes()) {
            resumo.add(new String[] { "", o });
        }
    }

    /** Aba com uma linha por empresa + NCM, subtotal por empresa (quando há mais de uma) e o total no fim. */
    public static void aba(Planilha p, VendasPorNcm.Resumo v) {
        if (v == null) {
            return;
        }
        List<Coluna> cols = List.of(Coluna.texto("Empresa", 30), Coluna.texto("CNPJ", 17), Coluna.texto("NCM/NBS", 11),
                Coluna.texto("Descrição", 50), Coluna.numero("Itens"), Coluna.numero("Quantidade"), Coluna.texto("Unidade", 8),
                Coluna.moeda("Valor dos produtos"), Coluna.moeda("Descontos"), Coluna.moeda("Valor vendido (líquido)"),
                Coluna.numero("% da empresa"));
        List<Object[]> linhas = new ArrayList<>();
        boolean variasEmpresas = v.empresas().size() > 1;
        String atual = null;
        for (Linha l : v.linhas()) {
            String emp = String.valueOf(l.empresaCnpj());
            if (variasEmpresas && atual != null && !atual.equals(emp)) {
                linhas.add(subtotal(v, atual));
            }
            atual = emp;
            linhas.add(new Object[] { l.empresaNome(), l.empresaCnpj(), l.ncm() == null ? "(sem NCM)" : l.ncm(), l.descricao(),
                    l.itens(), l.quantidade(), l.unidade(), l.valorBruto(), l.descontos(), l.valorLiquido(), l.percentual() });
        }
        if (variasEmpresas && atual != null) {
            linhas.add(subtotal(v, atual));
        }
        linhas.add(new Object[] { "TOTAL GERAL", null, null, v.linhas().size() + " NCM/NBS", v.itens(), null, null,
                v.valorBruto(), v.descontos(), v.valorLiquido(), null });
        p.abaTabela("Vendas por NCM", cols, linhas, -1);
    }

    private static Object[] subtotal(VendasPorNcm.Resumo v, String cnpj) {
        TotalEmpresa t = v.empresas().stream().filter(e -> String.valueOf(e.empresaCnpj()).equals(cnpj)).findFirst().orElseThrow();
        return new Object[] { "Subtotal – " + t.empresaNome(), t.empresaCnpj(), null, t.ncms() + " NCM/NBS", t.itens(), null, null,
                t.valorBruto(), t.descontos(), t.valorLiquido(), null };
    }

    private static String moeda(java.math.BigDecimal b) {
        return b == null ? "R$ 0,00" : String.format(Locale.of("pt", "BR"), "R$ %,.2f", b);
    }
}
