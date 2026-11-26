package br.com.escritorio.hubfiscal.auditoria;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.com.escritorio.hubfiscal.excel.Planilha;
import br.com.escritorio.hubfiscal.excel.Planilha.Coluna;

/** Gera a planilha Excel da auditoria de XML. */
public final class AuditoriaExcel {

    /** Cor de fundo (RGB) da coluna Status. */
    private static final Map<StatusAuditoria, String> CORES = new EnumMap<>(Map.of(
            StatusAuditoria.DIVERGENTE, "F8B4B4",
            StatusAuditoria.ERRO_CLASSIFICACAO, "F4C28E",
            StatusAuditoria.OK, "8FD19E",
            StatusAuditoria.SEM_GRUPO, "D9D9D9",
            StatusAuditoria.NAO_AUDITADO, "E7E6E6"));

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private AuditoriaExcel() {
    }

    /** Planilha com as abas Resumo e Itens. */
    public static byte[] gerar(ResultadoAuditoria r) {
        try (Planilha p = new Planilha()) {
            CORES.forEach((s, cor) -> p.corStatus(s.rotulo(), cor));

            List<String[]> resumo = new ArrayList<>();
            resumo.add(new String[] { "Gerado em", r.geradoEm });
            resumo.add(new String[] { "Documentos lidos", String.valueOf(r.totalDocumentos) });
            resumo.add(new String[] { "Itens", String.valueOf(r.totalItens) });
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Resultado por status" });
            r.contagemStatus.forEach((s, n) -> resumo.add(new String[] {
                    StatusAuditoria.valueOf(s).rotulo(), String.valueOf(n) }));
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Totais (itens auditados)" });
            resumo.add(new String[] { "CBS informada no XML", moeda(r.totais.get("xmlVCBS")) });
            resumo.add(new String[] { "CBS calculada – alíquota oficial", moeda(r.totais.get("calcVCBS")) });
            if (r.cenarioCbs != null) {
                resumo.add(new String[] { "CBS calculada – " + r.cenarioCbs.get("rotulo") + " ("
                        + new BigDecimal(String.valueOf(r.cenarioCbs.get("aliquota"))).stripTrailingZeros().toPlainString().replace('.', ',')
                        + "%)", moeda(r.totais.get("cenVCBS")) });
            }
            resumo.add(new String[] { "IBS informado no XML", moeda(r.totais.get("xmlVIBS")) });
            resumo.add(new String[] { "IBS calculado", moeda(r.totais.get("calcVIBS")) });
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Como ler", "Cada item é recalculado na calculadora oficial do Regime Geral com "
                    + "o CST/cClassTrib e a base do próprio XML. A coluna \"Base calculada (componentes)\" refaz a base "
                    + "a partir de vProd, frete, seguro, outras despesas, descontos, ICMS, PIS/COFINS etc. Diferenças "
                    + "acima de R$ 0,01 aparecem como Divergente. \"Erro de classificação\" indica que a calculadora "
                    + "rejeitou o CST/cClassTrib/NCM do item (ex.: NCM fora do anexo)."
                    + (r.cenarioCbs == null ? "" : " As colunas do cenário mostram a CBS do mesmo cálculo com a alíquota do cenário, "
                            + "lado a lado com a oficial (o IBS não muda); são só simulação: o status compara o XML com a alíquota oficial.") });
            Planilha.secoesArquivosEAvisos(resumo, r.arquivos, r.avisos);
            p.abaResumo("Resumo", "Auditoria IBS/CBS dos documentos fiscais", resumo);

            boolean cen = r.cenarioCbs != null;
            String rotCen = cen ? String.valueOf(r.cenarioCbs.get("rotulo")) : "";
            List<Coluna> cols = new ArrayList<>(List.of(Coluna.texto("Status", 18), Coluna.texto("Documento", 7),
                    Coluna.texto("Número", 10), Coluna.texto("Emissão", 11), Coluna.texto("Chave", 46),
                    Coluna.texto("Emitente", 30), Coluna.texto("CNPJ emitente", 17), Coluna.texto("Destinatário", 30),
                    Coluna.numero("Item"), Coluna.texto("Código", 14), Coluna.texto("Descrição", 36),
                    Coluna.texto("NCM/NBS", 11), Coluna.texto("CFOP", 6), Coluna.texto("CST", 6),
                    Coluna.texto("cClassTrib", 9),
                    Coluna.moeda("vBC XML"), Coluna.moeda("Base calculada (componentes)"),
                    Coluna.percentual("pCBS XML"), Coluna.percentual("pCBS calc. (oficial)"),
                    Coluna.moeda("vCBS XML"), Coluna.moeda("vCBS calc. (oficial)")));
            if (cen) {
                cols.add(Coluna.percentual("pCBS " + rotCen));
                cols.add(Coluna.moeda("vCBS " + rotCen));
            }
            cols.addAll(List.of(Coluna.percentual("pIBSUF XML"), Coluna.percentual("pIBSUF calc."),
                    Coluna.moeda("vIBSUF XML"), Coluna.moeda("vIBSUF calc."),
                    Coluna.percentual("pIBSMun XML"), Coluna.percentual("pIBSMun calc."),
                    Coluna.moeda("vIBSMun XML"), Coluna.moeda("vIBSMun calc."),
                    Coluna.texto("Diferenças", 50), Coluna.texto("Mensagens", 60),
                    Coluna.texto("Memória de cálculo (CBS)", 60), Coluna.texto("Arquivo", 30)));
            List<Object[]> linhas = new ArrayList<>();
            for (LinhaAuditoria l : r.linhas) {
                List<Object> v = new ArrayList<>(java.util.Arrays.asList(l.statusRotulo, l.origem, l.numero, l.emissao, l.chave,
                        l.emitenteNome, l.emitenteCnpj, l.destinatario, l.nItem, l.codigo, l.descricao, l.ncmNbs, l.cfop, l.cst,
                        l.cClassTrib, l.xmlVBC, l.calcVBCComponentes, l.xmlPCBS, l.calcPCBS, l.xmlVCBS, l.calcVCBS));
                if (cen) {
                    v.add(l.cenPCBS);
                    v.add(l.cenVCBS);
                }
                v.addAll(java.util.Arrays.asList(l.xmlPIBSUF, l.calcPIBSUF, l.xmlVIBSUF, l.calcVIBSUF, l.xmlPIBSMun, l.calcPIBSMun,
                        l.xmlVIBSMun, l.calcVIBSMun, String.join("\n", l.diferencas),
                        String.join("\n", l.mensagens), l.memoriaCalculo, l.arquivo));
                linhas.add(v.toArray());
            }
            p.abaTabela("Itens", cols, linhas, 0);
            return p.bytes();
        }
    }

    private static String moeda(BigDecimal b) {
        return b == null ? "0,00" : String.format(PT_BR, "R$ %,.2f", b);
    }
}
