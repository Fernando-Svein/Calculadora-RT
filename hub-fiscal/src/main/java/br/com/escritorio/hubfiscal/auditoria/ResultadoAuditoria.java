package br.com.escritorio.hubfiscal.auditoria;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resultado de uma auditoria em lote de XMLs. */
public class ResultadoAuditoria {

    public String id;
    public String geradoEm;
    public int totalDocumentos;
    public int totalItens;
    public Map<String, Integer> contagemStatus = new LinkedHashMap<>();
    /** Somatórios: xmlVCBS, calcVCBS, cenVCBS, xmlVIBS, calcVIBS (somente itens auditados). */
    public Map<String, BigDecimal> totais = new LinkedHashMap<>();
    /** Cenário de CBS aplicado lado a lado com a alíquota oficial (rótulo e alíquota), ou null se não configurado. */
    public Map<String, Object> cenarioCbs;
    public Map<String, String> arquivos = new LinkedHashMap<>();
    public List<String> avisos = new ArrayList<>();
    public List<LinhaAuditoria> linhas = new ArrayList<>();
    /** Vendas por NCM/NBS e empresa dos XMLs (aba "Vendas por NCM" da tela e da planilha). */
    public br.com.escritorio.hubfiscal.importacao.VendasPorNcm.Resumo vendasPorNcm;
}
